# Hermes Gateway API, v1

The gateway is the only way any device talks to Hermes. Phones, watches, and home satellites are
interfaces; Hermes is the brain; this document is the seam between them.

**Stability.** `v1` is frozen in the sense that matters to a client:

* Paths under `/v1` do not change meaning. Fields are only ever added, never removed or renamed.
* New event types may appear. A client ignores event types and fields it does not know.
* Breaking changes become `/v2` next to `/v1`; `/v1` keeps working for at least one year after that.
* Every response carries `X-Gateway-Api: v1` and `X-Gateway-Version: <semver of the implementation>`.
* `schema/v1/*.schema.json` and `fixtures/v1/*.json` are generated from the reference implementation
  and are the contract test for any client (the Android `shared` module decodes every fixture).

**Transport.** HTTPS is not terminated here. The gateway binds to the Mac's Tailscale address and
refuses to bind anything else without an explicit override; the tailnet is the encrypted channel and
the only route in. Nothing here is reachable from the public internet.

**Auth.** Every request except `/v1/health` carries `Authorization: Bearer <device token>`. Tokens
are per device, minted on the Mac with `hermes-gateway token add <name>`, stored hashed, and
revocable one at a time. A token identifies a *device*, and everything that device does is scoped to
it: its sessions, its turns, its approvals.

## Model

* **Session**: a conversation. Identified by a client-chosen `key` (default `"default"`), scoped to
  the device. `reset: true` starts a fresh conversation under the same key. Long-term memory is
  shared across every device (one person, one brain); only the transcript is per session.
* **Turn**: one input from the device and everything that happens until the answer is complete.
  A turn is an ordered stream of **events**. One turn per session at a time.
* **Approval**: a point inside a turn where the brain wants a human decision. The turn stays open
  until the device answers or the approval expires (then it is denied). `risk: "high"` approvals are
  the ones the user must confirm with a deliberate tap, never by voice alone, never automatically.

## Endpoints

### `GET /v1/health` (no auth)

```json
{"ok": true, "api": "v1", "version": "1.0.0", "brain": {"kind": "hermes", "reachable": true}, "stt": "openai", "tts": "none"}
```

### `GET /v1/whoami`

`{"device": {"id": "dev_…", "name": "pixel-9"}}` — the device the token maps to. Use it to verify
pairing.

### `POST /v1/turns` → `text/event-stream`

Request (`schema/v1/turn-request.schema.json`):

```json
{
  "session": {"key": "default", "reset": false},
  "input": {"type": "text", "text": "what's on my calendar today?"},
  "options": {"speak": false, "locale": "en-US", "approval_timeout_s": null}
}
```

Audio input is one complete utterance of raw PCM, little-endian signed 16-bit, mono, base64:

```json
{"input": {"type": "audio", "format": "pcm_s16le", "sample_rate": 16000, "channels": 1, "data": "<base64>"}}
```

Limits: text ≤ 20 000 chars, audio ≤ 60 s (`413 audio_too_long`).

Response: `200` with `Content-Type: text/event-stream` and header `X-Turn-Id`. Each event is

```
event: output.delta
data: {"type":"output.delta","turn_id":"turn_…","seq":3,"at":"2026-09-07T12:00:00.000Z","text":"Turning "}
```

A `: keepalive` comment is sent every 15 s of silence. The stream ends after `turn.completed`.

`?stream=false` returns one JSON object after the turn finishes:
`{"turn_id", "status", "text", "transcript", "events": [...]}`. Handy for `curl` and for milestone 2.

Errors: `401 unauthorized`, `409 session_busy`, `413 audio_too_long`, `422` (validation).

### `POST /v1/turns/{turn_id}/approvals/{approval_id}`

Body `{"decision": "allow" | "deny"}`. `200` with the same three fields echoed; `404 unknown_turn`
(wrong device or expired turn); `409 not_pending` (already decided, timed out, or never existed).

### `POST /v1/turns/{turn_id}/cancel`

Stops the brain if it is still working and denies any pending approval. The stream ends with
`turn.completed {status: "cancelled"}`.

### `WS /v1/stream` — audio in, events out, one socket for many turns

Auth by `Authorization: Bearer` header, or `?token=` where a WebSocket client cannot set headers.
The server sends `{"type":"hello","api":"v1","version":"…","device":"…"}` on accept.

Client → server, JSON text frames unless noted:

| frame | meaning |
| --- | --- |
| `{"type":"start","session":{…},"audio":{"format":"pcm_s16le","sample_rate":16000,"channels":1},"options":{…}}` | an utterance begins; server answers `{"type":"listening"}` |
| *binary frames* | raw PCM, any chunking; ≤ 60 s total |
| `{"type":"end"}` | the utterance is complete; the turn starts and its events follow |
| `{"type":"text","text":"…","session":{…},"options":{…}}` | a text turn on the same socket |
| `{"type":"approval","turn_id":"…","approval_id":"…","decision":"allow"}` | same as the HTTP approval |
| `{"type":"cancel"}` | discard buffered audio and cancel the open turn |
| `{"type":"ping"}` | server answers `{"type":"pong"}` |

Server → client: every event of the turn as a JSON text frame, identical to the SSE `data`
payloads, plus `{"type":"error","code":…}` for socket-level problems (`no_start`, `empty_audio`,
`busy`, `session_busy`, `audio_too_long`, `bad_frame`, `unknown_frame`).

## Events (`schema/v1/events.schema.json`)

Every event has `type`, `turn_id`, `seq` (1, 2, 3… within the turn), `at` (RFC 3339, UTC).

| type | fields | when |
| --- | --- | --- |
| `turn.started` | `session_key`, `input_type` | always first |
| `input.transcript` | `text`, `final` | audio turns only, before the brain runs |
| `output.delta` | `text` | streamed answer text; may be absent if the brain does not stream |
| `tool.started` | `tool_id`, `name`, `summary` | the brain began a tool call |
| `tool.completed` | `tool_id`, `name`, `ok`, `summary` | it finished |
| `approval.required` | `approval_id`, `action`, `detail`, `risk`, `expires_at` | brain paused for a decision |
| `approval.resolved` | `approval_id`, `decision`, `by` (`client`/`timeout`/`policy`) | decision relayed |
| `output.done` | `text` | the complete answer, always sent on success even when deltas were |
| `speech.chunk` | `format`, `data` (base64), `last` | only with `options.speak` and server TTS on |
| `turn.completed` | `status` (`ok`/`cancelled`/`error`), `usage?` | always last |
| `error` | `code`, `message`, `retryable` | something failed; `turn.completed` still follows |

Error codes in `error` events: `stt_failed`, `empty_transcript`, `brain_error`, `tts_failed`.

## What a minimal client does

1. `POST /v1/turns` (or `WS start … end`).
2. Show `input.transcript` (audio) so the user knows what was heard.
3. Optionally render `output.delta` as it arrives; otherwise wait for `output.done`.
4. On `approval.required`: render `action`/`detail`; for `risk: high` require a deliberate tap;
   answer through the approval endpoint or frame before `expires_at`.
5. Speak `output.done.text` with on-device TTS (or play `speech.chunk` when present).
6. Treat `turn.completed` as the end; anything after it is a bug on the server side.

## Versioning of this document

Changes to v1 are listed in `CHANGELOG.md`. A change that would make an existing client
misbehave is not a v1 change.
