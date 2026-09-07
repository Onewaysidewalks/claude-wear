"""The turn engine: one request in, an ordered stream of v1 events out.

A turn is the unit of conversation: one utterance from a device, everything the brain did about
it, one answer. The engine owns approvals (with a timeout that denies), cancellation, and the
"one turn per session at a time" rule. It knows nothing about HTTP or WebSockets.
"""

from __future__ import annotations

import asyncio
import logging
import secrets
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Literal

from .auth import Device
from .brain import Brain, BrainApproval, BrainDone, BrainRequest, BrainText, BrainToolEnd, BrainToolStart
from .events import (
    ApprovalRequired,
    ApprovalResolved,
    AudioInput,
    ErrorEvent,
    InputTranscript,
    OutputDelta,
    OutputDone,
    SpeechChunk,
    TextInput,
    ToolCompleted,
    ToolStarted,
    TurnCompleted,
    TurnRequest,
    TurnStarted,
)
from .policy import RiskPolicy
from .stt import Transcriber
from .tts import Speaker

log = logging.getLogger("hermes_gateway.turns")


class SessionBusy(Exception):
    pass


class UnknownTurn(Exception):
    pass


@dataclass
class PendingApproval:
    approval_id: str
    risk: str
    future: asyncio.Future[str] = field(default_factory=lambda: asyncio.get_running_loop().create_future())
    timed_out: bool = False


@dataclass
class Turn:
    id: str
    device: Device
    session_key: str  # device-scoped
    started: datetime = field(default_factory=lambda: datetime.now(UTC))
    seq: int = 0
    done: bool = False
    cancelled: bool = False
    pending: dict[str, PendingApproval] = field(default_factory=dict)
    brain_ref: str | None = None
    final_text: str = ""

    def next_seq(self) -> int:
        self.seq += 1
        return self.seq


class TurnEngine:
    def __init__(
        self,
        brain: Brain,
        transcriber: Transcriber,
        speaker: Speaker,
        policy: RiskPolicy | None = None,
        default_approval_timeout_s: int = 120,
        keep_finished_s: int = 600,
    ):
        self.brain = brain
        self.transcriber = transcriber
        self.speaker = speaker
        self.policy = policy or RiskPolicy()
        self.default_timeout = default_approval_timeout_s
        self.keep_finished = keep_finished_s
        self._turns: dict[str, Turn] = {}
        self._active_by_session: dict[str, str] = {}

    # ---- lookups ---------------------------------------------------------------------------

    def get(self, turn_id: str, device: Device) -> Turn:
        t = self._turns.get(turn_id)
        if t is None or t.device.id != device.id:
            raise UnknownTurn(turn_id)
        return t

    @staticmethod
    def scope(device: Device, key: str) -> str:
        return f"{device.id}/{key}"

    # ---- control ---------------------------------------------------------------------------

    async def resolve_approval(self, turn: Turn, approval_id: str, decision: Literal["allow", "deny"]) -> bool:
        p = turn.pending.get(approval_id)
        if p is None or p.future.done():
            return False
        p.future.set_result(decision)
        return True

    async def cancel(self, turn: Turn) -> None:
        if turn.done:
            return
        turn.cancelled = True
        for p in turn.pending.values():
            if not p.future.done():
                p.future.set_result("deny")
        ref = turn.brain_ref or self.brain.run_ref(turn.session_key)
        if ref:
            await self.brain.cancel(ref)

    # ---- the turn itself -------------------------------------------------------------------

    async def run(self, request: TurnRequest, device: Device) -> AsyncIterator:
        session_key = self.scope(device, request.session.key)
        if session_key in self._active_by_session:
            raise SessionBusy(session_key)
        turn = Turn(id="turn_" + secrets.token_hex(8), device=device, session_key=session_key)
        self._turns[turn.id] = turn
        self._active_by_session[session_key] = turn.id
        timeout = request.options.approval_timeout_s or self.default_timeout

        def ev(cls, **kw):
            return cls(turn_id=turn.id, seq=turn.next_seq(), **kw)

        try:
            yield ev(TurnStarted, session_key=request.session.key, input_type=request.input.type)

            text: str
            if isinstance(request.input, AudioInput):
                try:
                    text = await self.transcriber.transcribe(request.input.pcm(), request.input.sample_rate, request.options.locale)
                except Exception as e:  # noqa: BLE001 - any STT failure is reported the same way
                    yield ev(ErrorEvent, code="stt_failed", message=str(e)[:300], retryable=True)
                    yield ev(TurnCompleted, status="error")
                    return
                yield ev(InputTranscript, text=text, final=True)
                if not text:
                    yield ev(ErrorEvent, code="empty_transcript", message="I did not catch that.", retryable=True)
                    yield ev(TurnCompleted, status="error")
                    return
            else:
                assert isinstance(request.input, TextInput)
                text = request.input.text

            brain_request = BrainRequest(
                text=text,
                session_key=session_key,
                reset=request.session.reset,
                locale=request.options.locale,
                device_name=device.name,
            )
            collected: list[str] = []
            status = "ok"
            usage = None
            async for be in self.brain.run(brain_request):
                turn.brain_ref = self.brain.run_ref(session_key) or turn.brain_ref
                if isinstance(be, BrainText):
                    collected.append(be.delta)
                    yield ev(OutputDelta, text=be.delta)
                elif isinstance(be, BrainToolStart):
                    yield ev(ToolStarted, tool_id=be.tool_id, name=be.name, summary=be.summary)
                elif isinstance(be, BrainToolEnd):
                    yield ev(ToolCompleted, tool_id=be.tool_id, name=be.name, ok=be.ok, summary=be.summary)
                elif isinstance(be, BrainApproval):
                    risk = self.policy.classify(be.action, be.detail)
                    pending = PendingApproval(approval_id=be.approval_id, risk=risk)
                    turn.pending[be.approval_id] = pending
                    expires = datetime.now(UTC) + timedelta(seconds=timeout)
                    yield ev(
                        ApprovalRequired,
                        approval_id=be.approval_id,
                        action=be.action,
                        detail=be.detail,
                        risk=risk,
                        expires_at=expires.isoformat(timespec="seconds").replace("+00:00", "Z"),
                    )
                    # The brain's stream is paused on its side; we wait here for the device (or
                    # the clock) and relay the decision. A high-risk action that times out is
                    # denied, never allowed by default.
                    asyncio.get_running_loop().create_task(self._await_decision(turn, pending, timeout))
                    decision = await pending.future
                    by = "timeout" if pending.timed_out else "client"
                    yield ev(ApprovalResolved, approval_id=be.approval_id, decision=decision, by=by)
                elif isinstance(be, BrainDone):
                    status = be.status
                    usage = be.usage
                    if be.status == "error":
                        yield ev(
                            ErrorEvent,
                            code="brain_error",
                            message=be.error or "the brain failed",
                            retryable=True,
                        )
                    turn.final_text = be.text or "".join(collected)
                    break
            if turn.cancelled and status == "ok":
                status = "cancelled"
            if status == "ok":
                yield ev(OutputDone, text=turn.final_text)
                if request.options.speak and self.speaker.kind != "none" and turn.final_text:
                    async for chunk in self._speech(turn, ev):
                        yield chunk
            yield ev(TurnCompleted, status=status, usage=usage)
        finally:
            turn.done = True
            self._active_by_session.pop(session_key, None)
            asyncio.get_running_loop().call_later(self.keep_finished, self._turns.pop, turn.id, None)

    async def _await_decision(self, turn: Turn, pending: PendingApproval, timeout: int) -> None:
        try:
            decision = await asyncio.wait_for(asyncio.shield(pending.future), timeout)
        except TimeoutError:
            pending.timed_out = True
            if not pending.future.done():
                pending.future.set_result("deny")
            decision = "deny"
        ref = turn.brain_ref or self.brain.run_ref(turn.session_key)
        if ref:
            await self.brain.approve(ref, pending.approval_id, decision)  # type: ignore[arg-type]
        turn.pending.pop(pending.approval_id, None)

    async def _speech(self, turn: Turn, ev):
        import base64

        try:
            chunks = []
            async for data in self.speaker.synthesize(turn.final_text):
                chunks.append(data)
            for i, data in enumerate(chunks):
                yield ev(
                    SpeechChunk,
                    format=self.speaker.format,
                    data=base64.b64encode(data).decode(),
                    last=i == len(chunks) - 1,
                )
        except Exception as e:  # noqa: BLE001
            log.warning("tts failed on %s: %s", turn.id, e)
            yield ev(ErrorEvent, code="tts_failed", message=str(e)[:300], retryable=True)
