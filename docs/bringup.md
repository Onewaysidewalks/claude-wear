# Bring-up, milestone by milestone

Each step ends with something you can see. Do not skip ahead: every later step assumes the
earlier one is green, and the whole point of the order is to know which layer broke.

## 0. Laptop only (no devices)

```bash
cd gateway && uv venv && uv pip install -e '.[dev]' && pytest -q && cd ..
cd android && ./gradlew :shared:test :phone:testDebugUnitTest :watch:testDebugUnitTest && cd ..
scripts/loopback.sh        # real Python gateway ↔ real Kotlin client and relay, fake brain
```

`loopback.sh` is milestones 2 and 3 with every device replaced by code on one machine. If it
passes, the SSE framing, the WebSocket control frames, approvals, and the relay's byte stream
to the watch all agree across the two languages.

## M0. The button (needs the watch)

`docs/phase0-spike.md`. Fill in its results table. Decide the M5 entry point from that table
before touching the watch app's manifest.

## M2. Phone → Hermes text (needs the phone and the Mac; do this before M1)

On the Mac, with Hermes' API server on:

```bash
hermes-gateway probe                     # read the raw events; fix ASSUMPTION lines if needed
hermes-gateway token add pixel           # paste the printed token into the phone app
HERMES_API_KEY=… HERMES_GATEWAY_HOST=100.x.y.z hermes-gateway serve
hermes-gateway say "what time is it" --url http://100.x.y.z:8731 --token hg1_…   # from any tailnet machine
```

On the phone: install `android/phone`, enter `http://100.x.y.z:8731` and the token, *Save &
check*. Expect `ok: gateway 1.0.0, brain hermes, stt …, device "pixel"`. Type a prompt into
*Talk to Hermes*; the answer streams into the card. Repeat with the phone on **cellular**.

Failure map: `failed: unauthorized` → wrong token; `failed: … connect` → phone not on the
tailnet or gateway bound to the wrong address; `brain hermes (unreachable)` → Hermes' API
server is off or `HERMES_BASE_URL` is wrong.

## M1. Watch → phone text (needs both devices)

Install `android/watch` (same signing as the phone app is not required; the Data Layer keys
off the package name, which is `dev.claudewear.hermes` on both). On the phone app, *Watches*
should list the watch as `nearby, relay-capable` is not required (the capability is on the
phone side); the watch's bottom line should read `phone: ready` after a few seconds.

On the watch tap *Speak via keyboard*, say or type anything. Expect the watch to show
`Thinking` then the answer, and `adb logcat -s HermesRelay` on the phone to show the turn.
With the phone app unconfigured you get `phone: set up the Hermes app` and an error on the
watch that says so: that is the M1 proof without a gateway.

## M3. End to end text

M1 with the phone configured. The watch speaks the answer. Try an action that needs approval
("delete the scratch folder"); the watch shows *Allow? / High risk. Sure?*; deny it.

## M4. Voice

Set `HERMES_GATEWAY_STT` on the Mac (`whisper` for in-process, or `openai` with a local
whisper server). Restart the gateway; the phone's check line shows `stt whisper`. On the watch
tap the big button, speak, stop talking. Expect the quoted transcript to appear within ~2 s of
silence, then the answer.

If the transcript never appears: `adb logcat -s HermesRelay WearableLS` on the phone shows
whether the channel opened and how many bytes arrived; `/tmp/hermes-gateway.log` shows whether
the WebSocket got `start`, audio, `end`, and how long STT took.

## M5. The button

Per the M0 decision: nothing to do (ASSIST alias is already in the watch manifest), or copy the
spike's `voice` services into `android/watch`, or set *Double press* to the app. Then: hold,
speak, answer.

## M6. Away

`docs/testing-matrix.md`, all rows, ten turns each. Numbers in the table, not impressions.
