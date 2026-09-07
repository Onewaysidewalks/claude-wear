"""The Hermes brain: a thin adapter over Hermes Agent's API server.

What is VERIFIED against the Hermes docs (docs/user-guide/features/api-server):
  * POST /v1/runs {input, session_id} -> {run_id, status}
  * GET  /v1/runs/{id} -> {object:"hermes.run", run_id, status, session_id, output, usage}
  * GET  /v1/runs/{id}/events is SSE with response.created, response.output_text.delta,
         response.output_item.added, response.output_item.done, response.completed,
         subagent.start, subagent.complete
  * POST /v1/runs/{id}/stop -> {status:"stopping"}
  * POST /v1/runs/{id}/approval resolves a pending approval
  * Authorization: Bearer <API_SERVER_KEY>; X-Hermes-Session-Id; X-Hermes-Session-Key

What is ASSUMED and must be checked with `hermes-gateway probe` against the installed Hermes
version before trusting it (each assumption is marked ASSUMPTION below):
  * the field names inside the SSE payloads (delta text, tool item shape)
  * the name and shape of the event that announces a pending approval
  * the body of POST /v1/runs/{id}/approval
  * that a client-chosen session_id continues the same conversation on the next run

The adapter is deliberately tolerant: unknown events are ignored, and several candidate field
names are tried, so a small drift in Hermes produces a degraded answer rather than a crash.
"""

from __future__ import annotations

import asyncio
import json
import logging
import secrets
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any, Literal

import httpx
from httpx_sse import aconnect_sse

from ..brain import (
    BrainApproval,
    BrainDone,
    BrainEvent,
    BrainRequest,
    BrainText,
    BrainToolEnd,
    BrainToolStart,
)
from ..config import Settings

log = logging.getLogger("hermes_gateway.hermes")

TOOL_ITEM_TYPES = {"function_call", "tool_call", "tool", "tool_use", "custom_tool_call"}


class SessionMap:
    """gateway session key -> Hermes session id, on disk so a conversation survives a restart."""

    def __init__(self, path: Path):
        self.path = path
        self._map: dict[str, str] = {}
        if path.exists():
            self._map = json.loads(path.read_text())

    def get(self, key: str) -> str | None:
        return self._map.get(key)

    def set(self, key: str, session_id: str) -> None:
        self._map[key] = session_id
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self._map, indent=2))

    def drop(self, key: str) -> None:
        if self._map.pop(key, None) is not None:
            self.path.write_text(json.dumps(self._map, indent=2))


class HermesBrain:
    kind = "hermes"

    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None):
        self.settings = settings
        self.base = settings.hermes_base_url.rstrip("/")
        self.sessions = SessionMap(settings.sessions_path)
        self._client = client or httpx.AsyncClient(timeout=httpx.Timeout(30.0, read=None))
        self._open: dict[str, str] = {}  # session_key -> run_id

    def _headers(self, session_id: str | None = None) -> dict[str, str]:
        h = {
            "Authorization": f"Bearer {self.settings.hermes_api_key}",
            "X-Hermes-Session-Key": self.settings.hermes_memory_key,
        }
        if session_id:
            h["X-Hermes-Session-Id"] = session_id
        return h

    def run_ref(self, session_key: str) -> str | None:
        return self._open.get(session_key)

    async def healthy(self) -> bool:
        try:
            r = await self._client.get(f"{self.base}/health", headers=self._headers(), timeout=5.0)
            return r.status_code < 500
        except httpx.HTTPError:
            return False

    async def approve(self, run_ref: str, approval_id: str, decision: Literal["allow", "deny"]) -> None:
        # ASSUMPTION: body field names. Both spellings are sent; Hermes ignores what it does not know.
        body = {"approval_id": approval_id, "decision": decision, "approved": decision == "allow"}
        r = await self._client.post(f"{self.base}/v1/runs/{run_ref}/approval", json=body, headers=self._headers())
        if r.status_code >= 400:
            log.warning("approval %s on run %s rejected: %s %s", approval_id, run_ref, r.status_code, r.text[:200])

    async def cancel(self, run_ref: str) -> None:
        try:
            await self._client.post(f"{self.base}/v1/runs/{run_ref}/stop", headers=self._headers())
        except httpx.HTTPError as e:
            log.warning("stop %s failed: %s", run_ref, e)

    async def run(self, request: BrainRequest) -> AsyncIterator[BrainEvent]:
        if request.reset:
            self.sessions.drop(request.session_key)
        session_id = self.sessions.get(request.session_key)
        if session_id is None:
            # ASSUMPTION: a client-chosen id is accepted and continues the conversation next time.
            session_id = f"hg-{secrets.token_hex(8)}"
            self.sessions.set(request.session_key, session_id)

        body: dict[str, Any] = {"input": request.text, "session_id": session_id}
        try:
            r = await self._client.post(f"{self.base}/v1/runs", json=body, headers=self._headers(session_id))
        except httpx.HTTPError as e:
            yield BrainDone(text="", status="error", error=f"hermes unreachable: {e}")
            return
        if r.status_code == 429:
            yield BrainDone(text="", status="error", error="hermes is busy (too many concurrent runs)")
            return
        if r.status_code >= 400:
            yield BrainDone(text="", status="error", error=f"hermes refused the run: {r.status_code} {r.text[:200]}")
            return
        run_id = r.json().get("run_id")
        if not run_id:
            yield BrainDone(text="", status="error", error="hermes returned no run_id")
            return
        self._open[request.session_key] = run_id

        collected: list[str] = []
        final_text: str | None = None
        usage = None
        finished = False
        try:
            async with aconnect_sse(
                self._client, "GET", f"{self.base}/v1/runs/{run_id}/events", headers=self._headers(session_id)
            ) as source:
                async for sse in source.aiter_sse():
                    for ev in self._translate(sse.event, sse.data, collected):
                        if isinstance(ev, BrainDone):
                            final_text = ev.text or None
                            usage = ev.usage
                            finished = True
                            if ev.status != "ok":
                                yield ev
                                return
                        else:
                            yield ev
                    if finished:
                        break
        except httpx.HTTPError as e:
            yield BrainDone(text="".join(collected), status="error", error=f"hermes event stream failed: {e}")
            return
        finally:
            self._open.pop(request.session_key, None)

        if final_text is None or usage is None:
            # The stream may end before the run object is final; ask once more.
            try:
                r = await self._client.get(f"{self.base}/v1/runs/{run_id}", headers=self._headers(session_id))
                if r.status_code < 400:
                    data = r.json()
                    final_text = final_text or data.get("output") or None
                    usage = usage or data.get("usage")
                    if data.get("status") in ("failed", "error"):
                        yield BrainDone(text=final_text or "", status="error", error=str(data.get("error", "run failed")))
                        return
                    if data.get("status") in ("cancelled", "stopped"):
                        yield BrainDone(text=final_text or "", status="cancelled")
                        return
            except httpx.HTTPError as e:
                log.warning("could not fetch run %s: %s", run_id, e)
        yield BrainDone(text=final_text or "".join(collected), usage=usage)

    # ---- translation ---------------------------------------------------------------------

    def _translate(self, event_name: str | None, raw: str, collected: list[str]) -> list[BrainEvent]:
        try:
            data = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            return []
        if not isinstance(data, dict):
            return []
        kind = (event_name or data.get("type") or data.get("event") or "").strip()
        out: list[BrainEvent] = []

        if kind in ("response.output_text.delta", "output_text.delta", "text.delta", "token"):
            # ASSUMPTION: the text lives in `delta` (OpenAI Responses) or `text`.
            delta = data.get("delta") if isinstance(data.get("delta"), str) else data.get("text")
            if delta:
                collected.append(delta)
                out.append(BrainText(delta=delta))
        elif kind in ("response.output_item.added", "response.output_item.done"):
            item = data.get("item") or {}
            if isinstance(item, dict) and item.get("type") in TOOL_ITEM_TYPES:
                tool_id = str(item.get("id") or item.get("call_id") or data.get("output_index", "tool"))
                name = str(item.get("name") or item.get("tool") or "tool")
                summary = _short(item.get("arguments") or item.get("input") or item.get("summary") or "")
                if kind.endswith(".added"):
                    out.append(BrainToolStart(tool_id=tool_id, name=name, summary=summary))
                else:
                    ok = item.get("status") not in ("failed", "error", "incomplete")
                    out.append(BrainToolEnd(tool_id=tool_id, name=name, ok=ok, summary=_short(item.get("output") or "")))
            elif isinstance(item, dict) and item.get("type") == "message" and kind.endswith(".done"):
                text = _message_text(item)
                if text and not collected:
                    collected.append(text)
        elif kind in ("tool.started", "hermes.tool.progress", "tool.start"):
            out.append(
                BrainToolStart(
                    tool_id=str(data.get("tool_id") or data.get("id") or data.get("name") or "tool"),
                    name=str(data.get("name") or data.get("tool") or "tool"),
                    summary=_short(data.get("arguments") or data.get("summary") or data.get("input") or ""),
                )
            )
        elif kind in ("tool.completed", "tool.done", "tool.end"):
            out.append(
                BrainToolEnd(
                    tool_id=str(data.get("tool_id") or data.get("id") or data.get("name") or "tool"),
                    name=str(data.get("name") or data.get("tool") or "tool"),
                    ok=not data.get("error") and data.get("status") not in ("failed", "error"),
                    summary=_short(data.get("output") or data.get("result") or data.get("summary") or ""),
                )
            )
        elif "approval" in kind and not any(s in kind for s in ("resolved", "completed", "granted", "denied")):
            # ASSUMPTION: the event that pauses a run. Field names are guesses; `probe` shows the truth.
            out.append(
                BrainApproval(
                    approval_id=str(data.get("approval_id") or data.get("id") or data.get("request_id") or "approval"),
                    action=str(data.get("command") or data.get("action") or data.get("tool") or data.get("name") or "action"),
                    detail=_short(
                        data.get("arguments") or data.get("detail") or data.get("description") or data.get("reason") or "",
                        600,
                    ),
                )
            )
        elif kind in ("response.completed", "response.done", "run.completed"):
            resp = data.get("response") or data
            text = resp.get("output_text") if isinstance(resp, dict) else None
            if not text and isinstance(resp, dict):
                text = "".join(_message_text(i) for i in resp.get("output", []) if isinstance(i, dict))
            out.append(
                BrainDone(
                    text=text or "".join(collected),
                    usage=resp.get("usage") if isinstance(resp, dict) else None,
                )
            )
        elif kind in ("response.failed", "response.error", "run.failed", "error"):
            err = data.get("error") or data.get("message") or "run failed"
            out.append(BrainDone(text="".join(collected), status="error", error=_short(err, 300)))
        elif kind in ("response.cancelled", "run.cancelled", "run.stopped"):
            out.append(BrainDone(text="".join(collected), status="cancelled"))
        return out


def _message_text(item: dict) -> str:
    if item.get("type") != "message":
        return ""
    content = item.get("content")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(str(c.get("text", "")) for c in content if isinstance(c, dict))
    return ""


def _short(value: Any, limit: int = 200) -> str:
    s = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False) if value else ""
    return s if len(s) <= limit else s[: limit - 1] + "…"


async def probe(settings: Settings, prompt: str = "Reply with exactly the word: pong") -> int:
    """Talk to the installed Hermes and print what it really sends. Run this before trusting
    anything marked ASSUMPTION above."""
    brain = HermesBrain(settings)
    base = brain.base
    async with brain._client as client:
        try:
            r = await client.get(f"{base}/health", headers=brain._headers(), timeout=5.0)
            print(f"GET /health -> {r.status_code} {r.text[:200]}")
        except httpx.HTTPError as e:
            print(f"GET /health failed: {e}")
            return 2
        r = await client.post(
            f"{base}/v1/runs",
            json={"input": prompt, "session_id": "hg-probe"},
            headers=brain._headers("hg-probe"),
        )
        print(f"POST /v1/runs -> {r.status_code} {r.text[:500]}")
        if r.status_code >= 400:
            return 1
        run_id = r.json().get("run_id")
        print(f"--- SSE /v1/runs/{run_id}/events (raw) ---")
        async with aconnect_sse(client, "GET", f"{base}/v1/runs/{run_id}/events", headers=brain._headers("hg-probe")) as source:
            async for sse in source.aiter_sse():
                print(f"event={sse.event!r} data={sse.data[:800]}")
                if sse.event in ("response.completed", "response.failed", "run.completed", "error"):
                    break
        await asyncio.sleep(0.5)
        r = await client.get(f"{base}/v1/runs/{run_id}", headers=brain._headers("hg-probe"))
        print(f"--- GET /v1/runs/{run_id} -> {r.status_code}\n{r.text[:1000]}")
        print(
            "\nNow check: (1) did output_text.delta carry `delta`? (2) what did tool items look like?"
            " (3) run a prompt that needs approval and read the pausing event's shape;"
            " then fix the ASSUMPTION lines in brains/hermes.py if they differ."
        )
    return 0
