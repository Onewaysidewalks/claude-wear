"""The WebSocket path the phone uses to relay watch audio."""

import json

from starlette.testclient import TestClient


def frames_until(ws, stop_types=("turn.completed", "error")):
    out = []
    while True:
        f = json.loads(ws.receive_text())
        out.append(f)
        if f["type"] in stop_types:
            return out


def test_audio_stream_round_trip(app, token, transcriber):
    with (
        TestClient(app) as tc,
        tc.websocket_connect("/v1/stream", headers={"Authorization": f"Bearer {token}"}) as ws,
    ):
        assert json.loads(ws.receive_text())["type"] == "hello"
        ws.send_text(
            json.dumps(
                {
                    "type": "start",
                    "session": {"key": "watch"},
                    "audio": {"sample_rate": 16000},
                    "options": {"locale": "en-US"},
                }
            )
        )
        assert json.loads(ws.receive_text())["type"] == "listening"
        for _ in range(10):
            ws.send_bytes(b"\x01\x00" * 1600)  # 100 ms each
        ws.send_text(json.dumps({"type": "end"}))
        frames = frames_until(ws)
        types = [f["type"] for f in frames]
        assert types[:2] == ["turn.started", "input.transcript"]
        assert types[-2:] == ["output.done", "turn.completed"]
        assert frames[-2]["text"] == "You said: what time is it"
        assert transcriber.calls[0][0] == 32000


def test_text_turn_over_ws_and_approval_frame(app, token):
    with TestClient(app) as tc, tc.websocket_connect("/v1/stream?token=" + token) as ws:
        ws.receive_text()
        ws.send_text(json.dumps({"type": "text", "text": "approve open the gate", "session": {"key": "watch"}}))
        frames = frames_until(ws, stop_types=("approval.required",))
        req = frames[-1]
        assert req["risk"] == "high"
        ws.send_text(
            json.dumps(
                {
                    "type": "approval",
                    "turn_id": req["turn_id"],
                    "approval_id": req["approval_id"],
                    "decision": "deny",
                }
            )
        )
        rest = frames_until(ws)
        assert rest[-2]["text"] == "Okay, I won't."


def test_ws_rejects_bad_token_and_stray_audio(app, token):
    with TestClient(app) as tc:
        try:
            with tc.websocket_connect("/v1/stream?token=hg1_bad") as ws:
                ws.receive_text()
            raise AssertionError("expected the socket to close")
        except Exception as e:  # noqa: BLE001 - starlette raises WebSocketDisconnect from the close frame
            assert "4401" in str(e) or e.__class__.__name__ == "WebSocketDisconnect"
        with tc.websocket_connect("/v1/stream?token=" + token) as ws:
            ws.receive_text()
            ws.send_bytes(b"\x00" * 100)
            assert json.loads(ws.receive_text())["code"] == "no_start"
            ws.send_text(json.dumps({"type": "start"}))
            ws.receive_text()
            ws.send_text(json.dumps({"type": "end"}))
            assert json.loads(ws.receive_text())["code"] == "empty_audio"
