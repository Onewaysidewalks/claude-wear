"""What a brain looks like from the gateway's side.

The gateway does not think. It hands text to a brain and relays what comes back. Hermes is the
only real brain; the fake one exists so the whole stack can be exercised on a laptop.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Literal, Protocol


@dataclass
class BrainText:
    delta: str


@dataclass
class BrainToolStart:
    tool_id: str
    name: str
    summary: str = ""


@dataclass
class BrainToolEnd:
    tool_id: str
    name: str
    ok: bool = True
    summary: str = ""


@dataclass
class BrainApproval:
    """The brain is paused until `approve()` is called with this id."""

    approval_id: str
    action: str
    detail: str = ""


@dataclass
class BrainDone:
    text: str
    usage: dict[str, int] | None = None
    status: Literal["ok", "cancelled", "error"] = "ok"
    error: str | None = None


BrainEvent = BrainText | BrainToolStart | BrainToolEnd | BrainApproval | BrainDone


@dataclass
class BrainRequest:
    text: str
    session_key: str  # gateway-scoped; the brain maps it to its own session
    reset: bool = False
    locale: str = "en-US"
    device_name: str = ""
    extra: dict = field(default_factory=dict)


class Brain(Protocol):
    kind: str

    async def run(self, request: BrainRequest) -> AsyncIterator[BrainEvent]: ...

    async def approve(self, run_ref: str, approval_id: str, decision: Literal["allow", "deny"]) -> None: ...

    async def cancel(self, run_ref: str) -> None: ...

    async def healthy(self) -> bool: ...

    def run_ref(self, session_key: str) -> str | None:
        """The brain's handle for the run currently open on this session, if any."""
        ...
