"""Regenerate schema/v1/events.schema.json and fixtures/v1/*.json from the pydantic models.

The fixtures are the cross-language contract test: the Python side asserts they validate, and the
Kotlin `shared` module asserts it can decode every one of them (android/shared/src/test).
Run: python scripts/gen_fixtures.py   (CI fails if the result differs from what is committed.)
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from pydantic import TypeAdapter

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from hermes_gateway import events as ev  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
TURN = "turn_0123456789abcdef"
AT = "2026-09-07T12:00:00.000Z"

SAMPLES: dict[str, object] = {
    "turn-started-text": ev.TurnStarted(turn_id=TURN, seq=1, at=AT, session_key="default", input_type="text"),
    "turn-started-audio": ev.TurnStarted(turn_id=TURN, seq=1, at=AT, session_key="watch", input_type="audio"),
    "input-transcript": ev.InputTranscript(turn_id=TURN, seq=2, at=AT, text="turn off the porch light"),
    "output-delta": ev.OutputDelta(turn_id=TURN, seq=3, at=AT, text="Turning "),
    "tool-started": ev.ToolStarted(
        turn_id=TURN,
        seq=4,
        at=AT,
        tool_id="call_1",
        name="ha_call_service",
        summary='{"entity_id":"light.porch"}',
    ),
    "tool-completed": ev.ToolCompleted(turn_id=TURN, seq=5, at=AT, tool_id="call_1", name="ha_call_service", ok=True, summary="ok"),
    "tool-failed": ev.ToolCompleted(turn_id=TURN, seq=5, at=AT, tool_id="call_1", name="terminal", ok=False, summary="exit 1"),
    "approval-required-normal": ev.ApprovalRequired(
        turn_id=TURN,
        seq=6,
        at=AT,
        approval_id="apr_1",
        action="terminal",
        detail="brew upgrade",
        risk="normal",
        expires_at="2026-09-07T12:02:00Z",
    ),
    "approval-required-high": ev.ApprovalRequired(
        turn_id=TURN,
        seq=6,
        at=AT,
        approval_id="apr_2",
        action="ha_call_service",
        detail="lock.unlock front_door",
        risk="high",
        expires_at="2026-09-07T12:02:00Z",
    ),
    "approval-resolved-allow": ev.ApprovalResolved(turn_id=TURN, seq=7, at=AT, approval_id="apr_1", decision="allow", by="client"),
    "approval-resolved-timeout": ev.ApprovalResolved(turn_id=TURN, seq=7, at=AT, approval_id="apr_2", decision="deny", by="timeout"),
    "output-done": ev.OutputDone(turn_id=TURN, seq=8, at=AT, text="Turning off the porch light."),
    "speech-chunk": ev.SpeechChunk(
        turn_id=TURN,
        seq=9,
        at=AT,
        format="audio/mpeg",
        data="SUQzBAAAAAAAI1RTU0UAAAAPAAADTGF2ZjU4LjI5LjEwMAAAAAAAAAAAAAAA",
        last=True,
    ),
    "turn-completed-ok": ev.TurnCompleted(
        turn_id=TURN,
        seq=10,
        at=AT,
        status="ok",
        usage={"input_tokens": 50, "output_tokens": 12, "total_tokens": 62},
    ),
    "turn-completed-cancelled": ev.TurnCompleted(turn_id=TURN, seq=10, at=AT, status="cancelled"),
    "turn-completed-error": ev.TurnCompleted(turn_id=TURN, seq=10, at=AT, status="error"),
    "error-stt": ev.ErrorEvent(turn_id=TURN, seq=2, at=AT, code="stt_failed", message="whisper server unreachable", retryable=True),
    "error-brain": ev.ErrorEvent(turn_id=TURN, seq=3, at=AT, code="brain_error", message="hermes refused the run: 429", retryable=True),
    "request-text": ev.TurnRequest(
        session=ev.SessionRef(key="default"),
        input=ev.TextInput(text="what's on my calendar today?"),
        options=ev.TurnOptions(speak=False, locale="en-US"),
    ),
    "request-audio": ev.TurnRequest(
        session=ev.SessionRef(key="watch", reset=False),
        input=ev.AudioInput(sample_rate=16000, channels=1, data="AAABAAIAAwAEAAUABgAHAA=="),
        options=ev.TurnOptions(speak=True, locale="en-US", approval_timeout_s=90),
    ),
    "request-approval": ev.ApprovalRequest(decision="allow"),
}


def main(check: bool = False) -> int:
    schema = TypeAdapter(ev.Event).json_schema()
    schema["$schema"] = "https://json-schema.org/draft/2020-12/schema"
    schema["title"] = "Hermes Gateway v1 event"
    out = {ROOT / "schema" / "v1" / "events.schema.json": json.dumps(schema, indent=2, sort_keys=True) + "\n"}
    out[ROOT / "schema" / "v1" / "turn-request.schema.json"] = (
        json.dumps(
            {**ev.TurnRequest.model_json_schema(), "$schema": "https://json-schema.org/draft/2020-12/schema"},
            indent=2,
            sort_keys=True,
        )
        + "\n"
    )
    for name, sample in SAMPLES.items():
        out[ROOT / "fixtures" / "v1" / f"{name}.json"] = json.dumps(sample.model_dump(mode="json"), indent=2) + "\n"
    stale = []
    for path, text in out.items():
        if check:
            if not path.exists() or path.read_text() != text:
                stale.append(path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
    if stale:
        print("stale generated files:\n  " + "\n  ".join(str(p.relative_to(ROOT)) for p in stale))
        return 1
    print(f"{'checked' if check else 'wrote'} {len(out)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main(check="--check" in sys.argv))
