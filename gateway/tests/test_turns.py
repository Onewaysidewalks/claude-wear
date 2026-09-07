import asyncio
import base64
import json

from tests.conftest import auth


async def sse_events(client, token, body, stream=True):
    events = []
    async with client.stream("POST", "/v1/turns", json=body, headers=auth(token)) as r:
        assert r.status_code == 200, await r.aread()
        assert r.headers["content-type"].startswith("text/event-stream")
        name = None
        async for line in r.aiter_lines():
            if line.startswith("event: "):
                name = line[7:]
            elif line.startswith("data: "):
                data = json.loads(line[6:])
                assert data["type"] == name
                events.append(data)
    return events


def text_turn(text, session="default", **options):
    return {"session": {"key": session}, "input": {"type": "text", "text": text}, "options": options}


async def test_text_turn_streams_deltas_then_done(client, token):
    events = await sse_events(client, token, text_turn("echo hello wrist"))
    types = [e["type"] for e in events]
    assert types[0] == "turn.started"
    assert types[-2:] == ["output.done", "turn.completed"]
    assert "".join(e["text"] for e in events if e["type"] == "output.delta") == "hello wrist"
    assert events[-2]["text"] == "hello wrist"
    assert events[-1]["status"] == "ok"
    seqs = [e["seq"] for e in events]
    assert seqs == sorted(seqs) and len(set(seqs)) == len(seqs)
    assert len({e["turn_id"] for e in events}) == 1


async def test_non_streaming_summary(client, token):
    r = await client.post("/v1/turns?stream=false", json=text_turn("echo hi"), headers=auth(token))
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok" and body["text"] == "hi" and body["transcript"] is None
    assert [e["type"] for e in body["events"]][-1] == "turn.completed"


async def test_tool_events_are_relayed(client, token):
    events = await sse_events(client, token, text_turn("tool weather"))
    types = [e["type"] for e in events]
    assert types.index("tool.started") < types.index("tool.completed") < types.index("output.done")
    started = next(e for e in events if e["type"] == "tool.started")
    assert started["name"] == "lookup" and started["summary"] == "weather"


async def test_audio_turn_is_transcribed_first(client, token, transcriber):
    pcm = b"\x00\x01" * 16000  # one second
    body = {
        "input": {"type": "audio", "sample_rate": 16000, "data": base64.b64encode(pcm).decode()},
        "options": {"locale": "en-GB"},
    }
    events = await sse_events(client, token, body)
    assert events[0]["input_type"] == "audio"
    assert events[1] == {**events[1], "type": "input.transcript", "text": "what time is it", "final": True}
    assert events[-2]["text"] == "You said: what time is it"
    assert transcriber.calls == [(len(pcm), 16000, "en-GB")]


async def test_audio_rejected_when_stt_missing(client, token, engine):
    from hermes_gateway.stt import NoTranscriber

    engine.transcriber = NoTranscriber()
    body = {"input": {"type": "audio", "data": base64.b64encode(b"\x00\x01" * 8000).decode()}}
    events = await sse_events(client, token, body)
    assert [e["type"] for e in events] == ["turn.started", "error", "turn.completed"]
    assert events[1]["code"] == "stt_failed" and events[-1]["status"] == "error"


async def test_speak_option_streams_speech_after_done(client, token):
    events = await sse_events(client, token, text_turn("echo talk", speak=True))
    types = [e["type"] for e in events]
    assert types[-4:] == ["output.done", "speech.chunk", "speech.chunk", "turn.completed"]
    chunks = [e for e in events if e["type"] == "speech.chunk"]
    assert chunks[0]["format"] == "audio/wav" and not chunks[0]["last"] and chunks[1]["last"]
    assert base64.b64decode(chunks[1]["data"]) == b"tail"


async def test_brain_error_becomes_error_event(client, token):
    events = await sse_events(client, token, text_turn("fail"))
    assert [e["type"] for e in events] == ["turn.started", "error", "turn.completed"]
    assert events[1]["code"] == "brain_error" and events[1]["retryable"]
    assert events[-1]["status"] == "error"


async def test_one_turn_per_session_at_a_time(client, token):
    async def slow():
        return await sse_events(client, token, text_turn("slow 0.5 done", session="s1"))

    task = asyncio.create_task(slow())
    await asyncio.sleep(0.1)
    r = await client.post("/v1/turns", json=text_turn("echo again", session="s1"), headers=auth(token))
    assert r.status_code == 409 and r.json()["error"]["code"] == "session_busy"
    other = await client.post("/v1/turns?stream=false", json=text_turn("echo other", session="s2"), headers=auth(token))
    assert other.status_code == 200
    events = await task
    assert events[-1]["status"] == "ok"


async def test_sessions_are_scoped_per_device(client, devices, brain):
    _, a = devices.add("phone-a")
    _, b = devices.add("phone-b")
    await sse_events(client, a, text_turn("echo one", session="default"))
    await sse_events(client, b, text_turn("echo two", session="default"))
    assert len(brain.history) == 2
    keys = sorted(brain.history)
    assert all(k.endswith("/default") for k in keys) and keys[0] != keys[1]


async def test_reset_starts_a_fresh_conversation(client, token, brain):
    await sse_events(client, token, text_turn("echo first"))
    await sse_events(
        client,
        token,
        {"session": {"key": "default", "reset": True}, "input": {"type": "text", "text": "echo second"}},
    )
    (history,) = brain.history.values()
    assert history == ["echo second"]


async def test_validation_errors_are_422(client, token):
    r = await client.post("/v1/turns", json={"input": {"type": "text", "text": ""}}, headers=auth(token))
    assert r.status_code == 422
    r = await client.post(
        "/v1/turns",
        json={"session": {"key": "a\nb"}, "input": {"type": "text", "text": "x"}},
        headers=auth(token),
    )
    assert r.status_code == 422
