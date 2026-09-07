"""A scripted brain for development and tests.

It answers deterministically from the prompt text so every milestone can be proven without a
Mac, a Hermes install, or a model:

  "echo ..."         -> streams the rest back in word-sized deltas
  "tool ..."         -> runs a pretend tool, then answers
  "approve ..."      -> asks for approval; "allow" answers, "deny" declines
  "danger ..."       -> asks for approval of a high-risk action (rm -rf)
  "fail"             -> reports an error
  "slow N ..."       -> waits N seconds before answering (cancel tests)
  anything else      -> "You said: ..."
"""

from __future__ import annotations

import asyncio
import itertools
from collections.abc import AsyncIterator
from typing import Literal

from ..brain import (
    BrainApproval,
    BrainDone,
    BrainEvent,
    BrainRequest,
    BrainText,
    BrainToolEnd,
    BrainToolStart,
)


class FakeBrain:
    kind = "fake"

    def __init__(self) -> None:
        self._ids = itertools.count(1)
        self._approvals: dict[str, asyncio.Future[str]] = {}
        self._cancelled: set[str] = set()
        self._open: dict[str, str] = {}  # session_key -> run_ref
        self.history: dict[str, list[str]] = {}

    def run_ref(self, session_key: str) -> str | None:
        return self._open.get(session_key)

    async def healthy(self) -> bool:
        return True

    async def approve(self, run_ref: str, approval_id: str, decision: Literal["allow", "deny"]) -> None:
        fut = self._approvals.get(approval_id)
        if fut and not fut.done():
            fut.set_result(decision)

    async def cancel(self, run_ref: str) -> None:
        self._cancelled.add(run_ref)
        for fut in self._approvals.values():
            if not fut.done():
                fut.set_result("deny")

    async def run(self, request: BrainRequest) -> AsyncIterator[BrainEvent]:
        ref = f"fake_{next(self._ids)}"
        self._open[request.session_key] = ref
        if request.reset:
            self.history.pop(request.session_key, None)
        self.history.setdefault(request.session_key, []).append(request.text)
        try:
            async for ev in self._script(ref, request.text.strip()):
                yield ev
                if ref in self._cancelled:
                    yield BrainDone(text="", status="cancelled")
                    return
        finally:
            self._open.pop(request.session_key, None)

    async def _stream(self, text: str) -> AsyncIterator[BrainEvent]:
        words = text.split(" ")
        for i, w in enumerate(words):
            yield BrainText(delta=w if i == len(words) - 1 else w + " ")
            await asyncio.sleep(0)

    async def _script(self, ref: str, text: str) -> AsyncIterator[BrainEvent]:
        head, _, rest = text.partition(" ")
        head = head.lower()
        if head == "echo":
            async for ev in self._stream(rest):
                yield ev
            yield BrainDone(text=rest)
        elif head == "tool":
            yield BrainToolStart(tool_id="t1", name="lookup", summary=rest)
            await asyncio.sleep(0)
            yield BrainToolEnd(tool_id="t1", name="lookup", ok=True, summary="found it")
            answer = f"Looked up {rest}."
            async for ev in self._stream(answer):
                yield ev
            yield BrainDone(text=answer)
        elif head in ("approve", "danger"):
            action = "rm -rf /tmp/scratch" if head == "danger" else f"do {rest}"
            approval_id = f"apr_{ref}"
            fut: asyncio.Future[str] = asyncio.get_running_loop().create_future()
            self._approvals[approval_id] = fut
            yield BrainApproval(approval_id=approval_id, action=action, detail=rest)
            decision = await fut
            self._approvals.pop(approval_id, None)
            answer = "Done." if decision == "allow" else "Okay, I won't."
            async for ev in self._stream(answer):
                yield ev
            yield BrainDone(text=answer)
        elif head == "fail":
            yield BrainDone(text="", status="error", error="scripted failure")
        elif head == "slow":
            secs, _, tail = rest.partition(" ")
            await asyncio.sleep(float(secs or "1"))
            answer = tail or "finally"
            yield BrainText(delta=answer)
            yield BrainDone(text=answer)
        else:
            answer = f"You said: {text}"
            async for ev in self._stream(answer):
                yield ev
            yield BrainDone(text=answer)
