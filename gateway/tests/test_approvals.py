import asyncio
import json

from tests.conftest import auth
from tests.test_turns import text_turn


async def run_with_decider(client, token, prompt, decide):
    """Stream a turn; when approval.required arrives, call decide(turn_id, approval_id)."""
    events = []
    async with client.stream("POST", "/v1/turns", json=text_turn(prompt), headers=auth(token)) as r:
        assert r.status_code == 200
        async for line in r.aiter_lines():
            if line.startswith("data: "):
                e = json.loads(line[6:])
                events.append(e)
                if e["type"] == "approval.required":
                    await decide(e["turn_id"], e["approval_id"])
    return events


async def test_allow_lets_the_brain_continue(client, token):
    async def allow(turn_id, approval_id):
        r = await client.post(f"/v1/turns/{turn_id}/approvals/{approval_id}", json={"decision": "allow"}, headers=auth(token))
        assert r.status_code == 200

    events = await run_with_decider(client, token, "approve send the report", allow)
    types = [e["type"] for e in events]
    assert types.index("approval.required") < types.index("approval.resolved") < types.index("output.done")
    req = next(e for e in events if e["type"] == "approval.required")
    assert req["action"] == "do send the report" and req["risk"] == "high"  # "send" is high risk by policy
    assert "expires_at" in req
    assert next(e for e in events if e["type"] == "approval.resolved")["decision"] == "allow"
    assert events[-2]["text"] == "Done."


async def test_deny_is_relayed(client, token):
    async def deny(turn_id, approval_id):
        await client.post(f"/v1/turns/{turn_id}/approvals/{approval_id}", json={"decision": "deny"}, headers=auth(token))

    events = await run_with_decider(client, token, "approve tidy the desk", deny)
    req = next(e for e in events if e["type"] == "approval.required")
    assert req["risk"] == "normal"
    assert events[-2]["text"] == "Okay, I won't."


async def test_high_risk_action_is_flagged(client, token):
    async def deny(turn_id, approval_id):
        await client.post(f"/v1/turns/{turn_id}/approvals/{approval_id}", json={"decision": "deny"}, headers=auth(token))

    events = await run_with_decider(client, token, "danger clean up", deny)
    req = next(e for e in events if e["type"] == "approval.required")
    assert req["risk"] == "high" and req["action"].startswith("rm -rf")


async def test_timeout_denies(client, token, engine):
    engine.default_timeout = 1

    async def wait(turn_id, approval_id):
        await asyncio.sleep(0)

    events = await run_with_decider(client, token, "approve something", wait)
    resolved = next(e for e in events if e["type"] == "approval.resolved")
    assert resolved == {**resolved, "decision": "deny", "by": "timeout"}


async def test_approval_endpoint_rejects_strangers_and_stale_ids(client, token, devices):
    _, other = devices.add("other-phone")

    async def probe(turn_id, approval_id):
        r = await client.post(f"/v1/turns/{turn_id}/approvals/{approval_id}", json={"decision": "allow"}, headers=auth(other))
        assert r.status_code == 404
        r = await client.post(f"/v1/turns/{turn_id}/approvals/nope", json={"decision": "allow"}, headers=auth(token))
        assert r.status_code == 409
        r = await client.post(f"/v1/turns/{turn_id}/approvals/{approval_id}", json={"decision": "allow"}, headers=auth(token))
        assert r.status_code == 200
        r = await client.post(f"/v1/turns/{turn_id}/approvals/{approval_id}", json={"decision": "allow"}, headers=auth(token))
        assert r.status_code == 409

    await run_with_decider(client, token, "approve x", probe)


async def test_cancel_ends_a_slow_turn(client, token):
    events = []

    async def go():
        async with client.stream("POST", "/v1/turns", json=text_turn("slow 5 late"), headers=auth(token)) as r:
            turn_id = r.headers["X-Turn-Id"]
            asyncio.get_running_loop().call_later(0.1, lambda: asyncio.ensure_future(cancel(turn_id)))
            async for line in r.aiter_lines():
                if line.startswith("data: "):
                    events.append(json.loads(line[6:]))

    async def cancel(turn_id):
        r = await client.post(f"/v1/turns/{turn_id}/cancel", headers=auth(token))
        assert r.status_code == 200

    await asyncio.wait_for(go(), timeout=8)
    assert events[-1]["type"] == "turn.completed" and events[-1]["status"] == "cancelled"
