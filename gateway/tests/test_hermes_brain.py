"""The Hermes adapter against a stand-in Hermes API server that speaks the documented shapes.

This proves the adapter's wiring (run, events, approval, stop, session continuity), not Hermes
itself: `hermes-gateway probe` does that against the real thing.
"""

from __future__ import annotations

import asyncio
import json
import socket

import pytest
import uvicorn
from fastapi import FastAPI, Header, Request
from fastapi.responses import StreamingResponse

from hermes_gateway.brain import BrainApproval, BrainDone, BrainText, BrainToolEnd, BrainToolStart
from hermes_gateway.brains.hermes import HermesBrain
from hermes_gateway.config import Settings


def fake_hermes() -> tuple[FastAPI, dict]:
    state: dict = {"runs": {}, "approvals": [], "stops": [], "sessions": [], "auth": []}
    app = FastAPI()

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    @app.post("/v1/runs")
    async def create(request: Request, authorization: str = Header(default="")):
        body = await request.json()
        state["auth"].append(authorization)
        state["sessions"].append((body.get("session_id"), request.headers.get("x-hermes-session-key")))
        run_id = f"run_{len(state['runs']) + 1}"
        state["runs"][run_id] = {"input": body["input"], "session_id": body.get("session_id"), "approved": asyncio.Event()}
        return {"run_id": run_id, "status": "started"}

    @app.get("/v1/runs/{run_id}/events")
    async def events(run_id: str):
        run = state["runs"][run_id]

        def sse(name: str, data: dict) -> bytes:
            return f"event: {name}\ndata: {json.dumps(data)}\n\n".encode()

        async def gen():
            yield sse("response.created", {"type": "response.created", "response": {"id": run_id}})
            text = run["input"]
            if text.startswith("tool"):
                yield sse(
                    "response.output_item.added",
                    {"item": {"type": "function_call", "id": "fc_1", "name": "terminal", "arguments": '{"command":"ls"}'}},
                )
                yield sse(
                    "response.output_item.done",
                    {"item": {"type": "function_call", "id": "fc_1", "name": "terminal", "status": "completed", "output": "a b c"}},
                )
            if text.startswith("approve"):
                yield sse("approval.required", {"approval_id": "ap_1", "command": "rm -rf build", "reason": "cleanup"})
                await run["approved"].wait()
            for word in ("pong", " pong"):
                yield sse("response.output_text.delta", {"type": "response.output_text.delta", "delta": word})
            yield sse(
                "response.completed",
                {
                    "response": {
                        "id": run_id,
                        "output_text": "pong pong",
                        "usage": {"input_tokens": 1, "output_tokens": 2, "total_tokens": 3},
                    }
                },
            )

        return StreamingResponse(gen(), media_type="text/event-stream")

    @app.post("/v1/runs/{run_id}/approval")
    async def approval(run_id: str, request: Request):
        body = await request.json()
        state["approvals"].append((run_id, body))
        state["runs"][run_id]["approved"].set()
        return {"status": "resolved"}

    @app.post("/v1/runs/{run_id}/stop")
    async def stop(run_id: str):
        state["stops"].append(run_id)
        return {"status": "stopping"}

    @app.get("/v1/runs/{run_id}")
    async def get_run(run_id: str):
        return {"object": "hermes.run", "run_id": run_id, "status": "completed", "output": "pong pong", "usage": {"total_tokens": 3}}

    return app, state


@pytest.fixture
async def hermes(tmp_path):
    app, state = fake_hermes()
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", lifespan="off"))
    task = asyncio.create_task(server.serve())
    while not server.started:
        await asyncio.sleep(0.01)
    settings = Settings(home=tmp_path, hermes_base_url=f"http://127.0.0.1:{port}", hermes_api_key="k", hermes_memory_key="me")
    try:
        yield HermesBrain(settings), state
    finally:
        server.should_exit = True
        await task


async def collect(brain, **kw):
    from hermes_gateway.brain import BrainRequest

    return [e async for e in brain.run(BrainRequest(**{"text": "hi", "session_key": "dev/default", **kw}))]


async def test_text_run_streams_and_completes(hermes):
    brain, state = hermes
    assert await brain.healthy()
    events = await collect(brain, text="ping")
    assert [type(e) for e in events] == [BrainText, BrainText, BrainDone]
    assert events[-1].text == "pong pong" and events[-1].usage["total_tokens"] == 3
    assert state["auth"] == ["Bearer k"]
    session_id, memory_key = state["sessions"][0]
    assert session_id.startswith("hg-") and memory_key == "me"


async def test_session_id_is_reused_until_reset(hermes):
    brain, state = hermes
    await collect(brain, text="one")
    await collect(brain, text="two")
    await collect(brain, text="three", reset=True)
    ids = [s for s, _ in state["sessions"]]
    assert ids[0] == ids[1] != ids[2]
    # and it survives a new adapter instance (on-disk map)
    again = HermesBrain(brain.settings)
    await collect(again, text="four")
    assert state["sessions"][3][0] == ids[2]


async def test_tool_items_become_tool_events(hermes):
    brain, _ = hermes
    events = await collect(brain, text="tool please")
    assert isinstance(events[0], BrainToolStart) and events[0].name == "terminal" and "ls" in events[0].summary
    assert isinstance(events[1], BrainToolEnd) and events[1].ok and events[1].summary == "a b c"


async def test_approval_pauses_until_approved(hermes):
    brain, state = hermes
    seen = []

    async def run():
        async for e in brain.run(
            __import__("hermes_gateway.brain", fromlist=["BrainRequest"]).BrainRequest(text="approve it", session_key="dev/x")
        ):
            seen.append(e)
            if isinstance(e, BrainApproval):
                assert e.approval_id == "ap_1" and e.action == "rm -rf build" and e.detail == "cleanup"
                await brain.approve(brain.run_ref("dev/x"), e.approval_id, "allow")

    await asyncio.wait_for(run(), 5)
    assert state["approvals"] == [("run_1", {"approval_id": "ap_1", "decision": "allow", "approved": True})]
    assert isinstance(seen[-1], BrainDone) and seen[-1].text == "pong pong"


async def test_cancel_calls_stop(hermes):
    brain, state = hermes
    await brain.cancel("run_9")
    assert state["stops"] == ["run_9"]


async def test_unreachable_hermes_is_an_error_not_a_crash(tmp_path):
    brain = HermesBrain(Settings(home=tmp_path, hermes_base_url="http://127.0.0.1:1", hermes_api_key="k"))
    assert not await brain.healthy()
    events = await collect(brain, text="hello")
    assert len(events) == 1 and events[0].status == "error" and "unreachable" in events[0].error
