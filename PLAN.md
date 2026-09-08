# Plan

Goal: hold the button on the Galaxy Watch, speak, Hermes on the Mac Mini acts and answers out
loud, at home and away. One brain (Hermes); the watch, the phone, and home satellites are
interfaces to it.

## Principles

1. **Hermes is the only brain.** No model calls, no memory, no tools anywhere else. The gateway
   transcribes, relays, and enforces confirmations; it does not think.
2. **One contract.** `gateway/API.md` is versioned (`/v1`) and additive-only. Every client is
   tested against the same generated fixtures.
3. **Nothing on the public internet.** The gateway binds to the Mac's Tailscale address; the
   phone is the only other node; the watch talks only to the phone.
4. **No secrets on the watch.** The device token lives in the phone's encrypted preferences.
5. **Validate, don't guess.** Every assumption below has a command or a step that turns it into
   a fact, and a fallback if it turns out false.

## Architecture

```
┌───────────────┐  Data Layer  ┌───────────────────┐   tailnet    ┌──────────────────────────┐
│ Galaxy Watch  │ ───────────▶ │ Android phone     │ ───────────▶ │ Mac Mini                 │
│ android/watch │ ◀─────────── │ android/phone     │ ◀─────────── │ gateway/ (hermes-gateway)│
│  AudioRecord  │  events      │  device token     │  SSE / WS    │  ├─ STT (whisper)        │
│  TTS          │  status      │  GatewayClient    │              │  ├─ risk policy          │
│  ASSIST entry │              │  Relay (FGS)      │              │  └─ Hermes API server    │
└───────────────┘              └───────────────────┘              └──────────────────────────┘
```

* **Watch → phone**: `MessageClient` for text/approvals/cancel/ping, `ChannelClient` for PCM.
  Paths under `/hermes/v1/…` (`android/shared/.../WatchProtocol.kt`).
* **Phone → gateway**: `POST /v1/turns` (SSE) for text, `WS /v1/stream` for audio. Bearer token
  per device. The phone forwards every gateway event to the watch untouched, tagged with the
  watch's request id.
* **Gateway → Hermes**: Hermes Agent's API server (`/v1/runs`, SSE events, approvals) on
  loopback. `hermes_gateway/brains/hermes.py`.

## Milestones

Each milestone has a proof. Do them in order; do not start the next until the proof passes.

| # | milestone | proof | code |
| --- | --- | --- | --- |
| M0 | **Button invocation on your watch** | The spike's log shows an `ASSIST_ACTIVITY` or `VIS_SESSION` line after holding the button. | `android/spike`, `docs/phase0-spike.md` |
| M1 | **Watch → phone text** | Type on the watch ("Speak via keyboard"), phone shows the text turn arriving (relay log), watch shows an error event that says `unconfigured` (no gateway yet) | `android/watch`, `android/phone` |
| M2 | **Phone → Hermes text** | Phone app "Talk to Hermes" returns a real Hermes answer over the tailnet, from cellular | `android/phone`, `gateway/` |
| M3 | **End to end text** | Watch keyboard → phone → gateway → Hermes → answer spoken on the watch | all |
| M4 | **Voice** | Hold the mic button on the watch, speak; `input.transcript` shows what was heard; answer spoken | `android/watch/audio`, gateway STT |
| M5 | **Assistant role** | Watch app selected as Digital assistant; holding the button starts listening | `android/watch` `AssistEntry` (+ VIS services if M0 says so) |
| M6 | **Away** | M4 passes in every row of `docs/testing-matrix.md` | relay if the Data Layer fails remotely |

Done means M5 and M6. The bring-up runbook for each step is `docs/bringup.md`; `scripts/loopback.sh`
proves M2 and M3 with no devices.

## Assumptions to validate (in order of how much they change the plan)

### A1. Samsung's press-and-hold reaches a third-party assistant

What we know (with sources in `docs/phase0-spike.md`): Wear OS has a "Digital assistant app"
picker; Home Assistant's Wear app, which only registers an `ACTION_ASSIST` activity, appears in
it and is reachable through Samsung's *Press and hold → Assistant* option on Wear OS 4 via a
chooser. Samsung's support pages list only Bixby, Google Assistant, and Gemini. Whether One UI
Watch 7/8 with Gemini preinstalled still shows a chooser is **unknown**.

Validate: run the spike (`docs/phase0-spike.md`). Two APKs, one with only the activity, one
with a `VoiceInteractionService` too.

Fallbacks, in order: (a) *Double press* Home → app (Samsung documents "any app"); the watch
app then auto-listens because it is launched fresh; (b) a Tile with a single mic button;
(c) a complication on the watch face. All three are one manifest/tile away and the rest of the
system is unchanged. Decide at M0; do not build M5 code speculatively.

### A2. Hermes' API server event shapes

Verified from the Hermes docs: `POST /v1/runs`, `GET /v1/runs/{id}/events` (SSE with
`response.output_text.delta`, `response.output_item.*`, `response.completed`),
`POST /v1/runs/{id}/approval`, `X-Hermes-Session-Id/Key`. **Not verified**: the field names
inside those events, the event that signals a pending approval, the approval request body,
and that a client-chosen `session_id` continues a conversation.

Validate: `hermes-gateway probe` prints the raw stream from your installed Hermes; compare with
the `ASSUMPTION` comments in `gateway/hermes_gateway/brains/hermes.py` and fix them. Then run a
prompt that needs a tool approval and read that event's shape. Also confirm Hermes'
`approvals.unattended_mode` for API sessions is not `deny` if you want approvals to reach the
watch at all.

### A3. Data Layer when the watch is away from the phone

Google documents that messages and channels can travel over Wi-Fi/LTE through Google's cloud
node when Bluetooth is gone, with no latency promise; users report delays and drops when the
phone is dozing. Samsung gates this behind *Remote connection*. Tailscale cannot run on the
watch (Wear OS stubs `VpnService`), so the phone stays the only tailnet client.

Validate: rows 3 and 4 of `docs/testing-matrix.md`, ten turns each, note round-trip time and
failures. Threshold: if more than 1 in 10 voice turns fails or p50 exceeds 8 s, build the
relay: the watch opens its own HTTPS/WSS connection to a small relay reachable from the
internet (Tailscale Funnel in front of the gateway, or a tiny public relay that only forwards
authenticated WebSocket frames), still with the phone's token model, never Hermes itself.

### A4. AudioRecord streaming over ChannelClient works at speech latency

Google names "voice data from the microphone" as the use case for `ChannelClient`. Chunk size
is 100 ms; the phone forwards as it reads.

Validate: M4 at home. Watch the gap between "stop talking" and `input.transcript`. If the
channel path is bad, the fallback is the platform recognizer (`RecognizerIntent`, already the
"Speak via keyboard" path) which yields text, and Hermes never knows the difference.

### A5. Assistant-role apps get the microphone when launched from a button hold

If the app is launched by the system, it is in the foreground and `RECORD_AUDIO` applies as
usual. If M5 reveals the launch is not foreground (e.g. only the VIS session is shown), record
inside the `VoiceInteractionSession` instead.

## Security

See `docs/security.md`. Short form: Tailscale is the transport; each phone has its own hashed
bearer token; the watch has no token and no address; `risk: high` actions need two deliberate
taps on whichever screen sees them and are denied on timeout.

## What is deliberately not here

* Server-side TTS. Reserved in v1 as `speech.chunk`; the watch uses on-device TTS.
* A relay for the watch. Only if A3 fails.
* Push/wake from the Mac to the watch. The watch initiates everything in v1.
* Multi-user. One person, one memory key.

## For the coding agent

Read this file and `docs/phase0-spike.md` before touching M5. When a step depends on Samsung's
button behaviour, the Data Layer over the cloud, or a Hermes event shape, **run the validation
and paste the observed output into the relevant doc** before writing code that depends on it.
If something in this plan contradicts an observation, the observation wins; update the plan.
