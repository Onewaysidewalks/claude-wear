"""HTTP and WebSocket surface of the v1 contract. See API.md for the shapes; this file only
moves bytes between the network and the TurnEngine."""

import asyncio
import ipaddress
import json
import logging
from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import ValidationError

from . import API_VERSION, GATEWAY_VERSION
from .auth import Device, DeviceStore
from .brains import make_brain
from .config import Settings
from .events import ApprovalRequest, AudioInput, SessionRef, TurnOptions, TurnRequest, TurnSummary
from .policy import RiskPolicy
from .stt import make_transcriber
from .tts import make_speaker
from .turns import SessionBusy, TurnEngine, UnknownTurn

log = logging.getLogger("hermes_gateway.app")

MAX_AUDIO_BYTES = 16_000 * 2 * 60  # one minute of 16 kHz mono PCM


def create_app(settings: Settings | None = None, engine: TurnEngine | None = None, devices: DeviceStore | None = None) -> FastAPI:
    settings = settings or Settings.load()
    devices = devices or DeviceStore(settings.tokens_path)
    if engine is None:
        engine = TurnEngine(
            brain=make_brain(settings),
            transcriber=make_transcriber(settings),
            speaker=make_speaker(settings),
            policy=RiskPolicy(settings.high_risk_patterns),
            default_approval_timeout_s=settings.approval_timeout_s,
        )

    app = FastAPI(title="Hermes Gateway", version=GATEWAY_VERSION, docs_url=None, redoc_url=None)
    app.state.settings = settings
    app.state.engine = engine
    app.state.devices = devices

    # ---- auth --------------------------------------------------------------------------------

    def _device_from_token(token: str | None) -> Device:
        d = devices.authenticate(token)
        if d is None:
            raise HTTPException(status_code=401, detail={"code": "unauthorized", "message": "unknown or revoked device token"})
        return d

    async def current_device(request: Request) -> Device:
        auth = request.headers.get("authorization", "")
        token = auth[7:] if auth.lower().startswith("bearer ") else None
        return _device_from_token(token)

    DeviceDep = Annotated[Device, Depends(current_device)]

    @app.middleware("http")
    async def version_header(request: Request, call_next):
        response = await call_next(request)
        response.headers["X-Gateway-Version"] = GATEWAY_VERSION
        response.headers["X-Gateway-Api"] = API_VERSION
        return response

    @app.exception_handler(HTTPException)
    async def http_error(_: Request, exc: HTTPException):
        detail = exc.detail if isinstance(exc.detail, dict) else {"code": "error", "message": str(exc.detail)}
        return JSONResponse(status_code=exc.status_code, content={"error": detail})

    # ---- endpoints ---------------------------------------------------------------------------

    @app.get("/v1/health")
    async def health():
        return {
            "ok": True,
            "api": API_VERSION,
            "version": GATEWAY_VERSION,
            "brain": {"kind": engine.brain.kind, "reachable": await engine.brain.healthy()},
            "stt": engine.transcriber.kind,
            "tts": engine.speaker.kind,
        }

    @app.get("/v1/whoami")
    async def whoami(device: DeviceDep):
        return {"device": {"id": device.id, "name": device.name}}

    @app.post("/v1/turns")
    async def create_turn(request: TurnRequest, device: DeviceDep, stream: bool = Query(default=True)):
        if isinstance(request.input, AudioInput) and len(request.input.data) * 3 // 4 > MAX_AUDIO_BYTES:
            raise HTTPException(413, {"code": "audio_too_long", "message": "one utterance is at most 60 seconds"})
        try:
            events = engine.run(request, device)
            first = await anext(events)  # fail fast on a busy session before committing to a stream
        except SessionBusy:
            raise HTTPException(409, {"code": "session_busy", "message": "a turn is already running on this session"}) from None

        async def with_first() -> AsyncIterator:
            yield first
            async for e in events:
                yield e

        if not stream:
            collected = [e async for e in with_first()]
            done = next((e for e in collected if e.type == "turn.completed"), None)
            text = next((e.text for e in collected if e.type == "output.done"), "")
            transcript = next((e.text for e in collected if e.type == "input.transcript"), None)
            return TurnSummary(
                turn_id=first.turn_id,
                status=done.status if done else "error",
                text=text,
                transcript=transcript,
                events=collected,
            )

        return StreamingResponse(
            _sse(with_first()),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no", "X-Turn-Id": first.turn_id},
        )

    @app.post("/v1/turns/{turn_id}/approvals/{approval_id}")
    async def approve(turn_id: str, approval_id: str, body: ApprovalRequest, device: DeviceDep):
        try:
            turn = engine.get(turn_id, device)
        except UnknownTurn:
            raise HTTPException(404, {"code": "unknown_turn", "message": "no such turn for this device"}) from None
        if not await engine.resolve_approval(turn, approval_id, body.decision):
            raise HTTPException(409, {"code": "not_pending", "message": "that approval is not pending"})
        return {"turn_id": turn_id, "approval_id": approval_id, "decision": body.decision}

    @app.post("/v1/turns/{turn_id}/cancel")
    async def cancel(turn_id: str, device: DeviceDep):
        try:
            turn = engine.get(turn_id, device)
        except UnknownTurn:
            raise HTTPException(404, {"code": "unknown_turn", "message": "no such turn for this device"}) from None
        await engine.cancel(turn)
        return {"turn_id": turn_id, "status": "cancelling" if not turn.done else "done"}

    # ---- websocket: audio in, events out ----------------------------------------------------

    @app.websocket("/v1/stream")
    async def stream(ws: WebSocket, token: str | None = Query(default=None)):
        auth = ws.headers.get("authorization", "")
        bearer = auth[7:] if auth.lower().startswith("bearer ") else token
        device = devices.authenticate(bearer)
        if device is None:
            await ws.close(code=4401, reason="unauthorized")
            return
        await ws.accept()
        await ws.send_json({"type": "hello", "api": API_VERSION, "version": GATEWAY_VERSION, "device": device.name})
        session = WsSession(ws, engine, device)
        try:
            await session.serve()
        except WebSocketDisconnect:
            pass
        finally:
            await session.close()

    return app


async def _sse(events: AsyncIterator) -> AsyncIterator[bytes]:
    """Server-sent events with a keepalive comment every 15 s so proxies and phones keep the
    socket open while the brain thinks."""
    it = events.__aiter__()
    while True:
        try:
            e = await asyncio.wait_for(anext(it), timeout=15)
        except TimeoutError:
            yield b": keepalive\n\n"
            continue
        except StopAsyncIteration:
            return
        yield f"event: {e.type}\ndata: {e.model_dump_json()}\n\n".encode()


class WsSession:
    """One socket, many turns. The client streams PCM as binary frames between `start` and `end`;
    everything else is JSON text frames (API.md, "WebSocket")."""

    def __init__(self, ws: WebSocket, engine: TurnEngine, device: Device):
        self.ws = ws
        self.engine = engine
        self.device = device
        self.buffer = bytearray()
        self.pending: dict | None = None
        self.turn_task: asyncio.Task | None = None
        self.turn_id: str | None = None

    async def serve(self) -> None:
        while True:
            msg = await self.ws.receive()
            if msg.get("type") == "websocket.disconnect":
                return
            if (data := msg.get("bytes")) is not None:
                if self.pending is None:
                    await self._error("no_start", "send a start frame before audio")
                    continue
                if len(self.buffer) + len(data) > MAX_AUDIO_BYTES:
                    await self._error("audio_too_long", "one utterance is at most 60 seconds")
                    self.pending, self.buffer = None, bytearray()
                    continue
                self.buffer.extend(data)
            elif (text := msg.get("text")) is not None:
                await self._control(text)

    async def _control(self, text: str) -> None:
        try:
            frame = json.loads(text)
            kind = frame.get("type")
        except (json.JSONDecodeError, AttributeError):
            await self._error("bad_frame", "control frames are JSON objects")
            return
        if kind == "start":
            if self.turn_task and not self.turn_task.done():
                await self._error("busy", "a turn is already running on this socket")
                return
            self.pending = frame
            self.buffer = bytearray()
            await self.ws.send_json({"type": "listening"})
        elif kind == "end":
            if self.pending is None:
                await self._error("no_start", "nothing to end")
                return
            frame, pcm = self.pending, bytes(self.buffer)
            self.pending, self.buffer = None, bytearray()
            await self._begin_turn(frame, audio_pcm=pcm)
        elif kind == "text":
            if self.turn_task and not self.turn_task.done():
                await self._error("busy", "a turn is already running on this socket")
                return
            await self._begin_turn(frame, text=str(frame.get("text", "")))
        elif kind == "approval":
            try:
                turn = self.engine.get(str(frame.get("turn_id") or self.turn_id), self.device)
                ok = await self.engine.resolve_approval(turn, str(frame.get("approval_id")), frame.get("decision"))
            except (UnknownTurn, ValueError):
                ok = False
            if not ok:
                await self._error("not_pending", "that approval is not pending")
        elif kind == "cancel":
            self.pending, self.buffer = None, bytearray()
            if self.turn_id:
                try:
                    await self.engine.cancel(self.engine.get(self.turn_id, self.device))
                except UnknownTurn:
                    pass
        elif kind == "ping":
            await self.ws.send_json({"type": "pong"})
        else:
            await self._error("unknown_frame", f"unknown frame type {kind!r}")

    async def _begin_turn(self, frame: dict, *, text: str | None = None, audio_pcm: bytes | None = None) -> None:
        import base64

        try:
            audio = frame.get("audio") or {}
            req = TurnRequest(
                session=SessionRef(**(frame.get("session") or {})),
                input=(
                    {"type": "text", "text": text}
                    if text is not None
                    else {
                        "type": "audio",
                        "format": audio.get("format", "pcm_s16le"),
                        "sample_rate": audio.get("sample_rate", 16000),
                        "channels": audio.get("channels", 1),
                        "data": base64.b64encode(audio_pcm or b"").decode(),
                    }
                ),
                options=TurnOptions(**(frame.get("options") or {})),
            )
        except ValidationError as e:
            await self._error("invalid", e.errors()[0].get("msg", "invalid request"))
            return
        if audio_pcm is not None and len(audio_pcm) < 3200:  # < 0.1 s at 16 kHz
            await self._error("empty_audio", "no audio was received before end", retryable=True)
            return
        try:
            events = self.engine.run(req, self.device)
            first = await anext(events)
        except SessionBusy:
            await self._error("session_busy", "a turn is already running on this session")
            return
        self.turn_id = first.turn_id
        await self.ws.send_json(first.model_dump())

        async def pump():
            async for e in events:
                await self.ws.send_json(e.model_dump())

        self.turn_task = asyncio.create_task(pump())

    async def _error(self, code: str, message: str, retryable: bool = False) -> None:
        await self.ws.send_json(
            {
                "type": "error",
                "turn_id": self.turn_id or "",
                "seq": 0,
                "code": code,
                "message": message,
                "retryable": retryable,
            }
        )

    async def close(self) -> None:
        if self.turn_task and not self.turn_task.done():
            if self.turn_id:
                try:
                    await self.engine.cancel(self.engine.get(self.turn_id, self.device))
                except UnknownTurn:
                    pass
            self.turn_task.cancel()


def bind_is_private(host: str) -> bool:
    """True for loopback and Tailscale CGNAT (100.64.0.0/10) addresses, the only ones `serve`
    accepts without --allow-any-bind."""
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return host in ("localhost",)
    return ip.is_loopback or ip in ipaddress.ip_network("100.64.0.0/10") or ip in ipaddress.ip_network("fd7a:115c:a1e0::/48")
