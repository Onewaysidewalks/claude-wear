"""The v1 event contract. These shapes are the stable part of the gateway.

Rules (see API.md):
  * Fields are only ever added, never renamed or removed, inside v1.
  * Every event carries `type`, `turn_id`, `seq`, `at`.
  * Clients must ignore event types and fields they do not know.
"""

from __future__ import annotations

import base64
from datetime import UTC, datetime
from typing import Annotated, Literal

from pydantic import BaseModel, Field, field_validator

Risk = Literal["normal", "high"]
Decision = Literal["allow", "deny"]
TurnStatus = Literal["ok", "cancelled", "error"]


def now_iso() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class _Event(BaseModel):
    turn_id: str
    seq: int = 0
    at: str = Field(default_factory=now_iso)


class TurnStarted(_Event):
    type: Literal["turn.started"] = "turn.started"
    session_key: str
    input_type: Literal["text", "audio"]


class InputTranscript(_Event):
    """What the gateway heard. Emitted once for audio turns, before the brain sees anything."""

    type: Literal["input.transcript"] = "input.transcript"
    text: str
    final: bool = True


class OutputDelta(_Event):
    type: Literal["output.delta"] = "output.delta"
    text: str


class ToolStarted(_Event):
    type: Literal["tool.started"] = "tool.started"
    tool_id: str
    name: str
    summary: str = ""


class ToolCompleted(_Event):
    type: Literal["tool.completed"] = "tool.completed"
    tool_id: str
    name: str
    ok: bool = True
    summary: str = ""


class ApprovalRequired(_Event):
    """The brain wants to do something it will not do without a human. The turn stays open until
    POST /v1/turns/{turn_id}/approvals/{approval_id} arrives or the approval expires (then: deny)."""

    type: Literal["approval.required"] = "approval.required"
    approval_id: str
    action: str
    detail: str = ""
    risk: Risk = "normal"
    expires_at: str


class ApprovalResolved(_Event):
    type: Literal["approval.resolved"] = "approval.resolved"
    approval_id: str
    decision: Decision
    by: Literal["client", "timeout", "policy"] = "client"


class OutputDone(_Event):
    """The whole answer, in one piece, even when deltas were streamed. Clients that only want the
    final text (TTS on the watch) can ignore deltas and wait for this."""

    type: Literal["output.done"] = "output.done"
    text: str


class SpeechChunk(_Event):
    """Server-side speech, when the client asked to `speak` and the gateway has a voice. Reserved
    in v1: the reference client uses on-device TTS until this is switched on."""

    type: Literal["speech.chunk"] = "speech.chunk"
    format: Literal["audio/mpeg", "audio/wav", "audio/ogg;codecs=opus"]
    data: str  # base64
    last: bool = False


class TurnCompleted(_Event):
    type: Literal["turn.completed"] = "turn.completed"
    status: TurnStatus
    usage: dict[str, int] | None = None


class ErrorEvent(_Event):
    type: Literal["error"] = "error"
    code: str
    message: str
    retryable: bool = False


Event = Annotated[
    TurnStarted
    | InputTranscript
    | OutputDelta
    | ToolStarted
    | ToolCompleted
    | ApprovalRequired
    | ApprovalResolved
    | OutputDone
    | SpeechChunk
    | TurnCompleted
    | ErrorEvent,
    Field(discriminator="type"),
]

EVENT_TYPES: tuple[type[BaseModel], ...] = (
    TurnStarted,
    InputTranscript,
    OutputDelta,
    ToolStarted,
    ToolCompleted,
    ApprovalRequired,
    ApprovalResolved,
    OutputDone,
    SpeechChunk,
    TurnCompleted,
    ErrorEvent,
)


# ---- requests -------------------------------------------------------------------------------


class SessionRef(BaseModel):
    """Which conversation this turn belongs to. The key is chosen by the client and scoped to the
    device that authenticated; two devices using the key "default" get two conversations."""

    key: str = "default"
    reset: bool = False

    @field_validator("key")
    @classmethod
    def _sane(cls, v: str) -> str:
        if not v or len(v) > 128 or any(c in v for c in "\r\n\x00"):
            raise ValueError("session key must be 1..128 chars without control characters")
        return v


class TextInput(BaseModel):
    type: Literal["text"] = "text"
    text: str = Field(min_length=1, max_length=20_000)


class AudioInput(BaseModel):
    """One utterance, complete. v1 accepts raw little-endian 16-bit PCM only; that is what
    AudioRecord produces and it keeps the watch free of codecs."""

    type: Literal["audio"] = "audio"
    format: Literal["pcm_s16le"] = "pcm_s16le"
    sample_rate: Literal[8000, 16000, 22050, 44100, 48000] = 16000
    channels: Literal[1] = 1
    data: str  # base64

    def pcm(self) -> bytes:
        return base64.b64decode(self.data)


class TurnOptions(BaseModel):
    speak: bool = False
    locale: str = "en-US"
    # None means the gateway default (config approval_timeout_s).
    approval_timeout_s: int | None = Field(default=None, ge=5, le=900)


class TurnRequest(BaseModel):
    session: SessionRef = SessionRef()
    input: TextInput | AudioInput = Field(discriminator="type")
    options: TurnOptions = TurnOptions()


class ApprovalRequest(BaseModel):
    decision: Decision


class TurnSummary(BaseModel):
    """Non-streaming shape of a finished turn (`?stream=false`)."""

    turn_id: str
    status: TurnStatus
    text: str
    transcript: str | None = None
    events: list[Event]
