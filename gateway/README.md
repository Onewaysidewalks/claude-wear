# Hermes Gateway

A small Python service that runs on the Mac Mini next to Hermes and is the **only** thing any
device talks to. Text or audio comes in from a phone (which relays for the watch); a stream of
events and one answer go out. The contract is in [API.md](API.md); it is versioned and stays put.

```
watch ──Data Layer──▶ phone ──tailnet──▶ hermes-gateway ──loopback──▶ Hermes API server
                                          │  device tokens, STT, risk policy, event stream
                                          └─(optional) whisper server, TTS server, also loopback
```

## Install on the Mac

Hermes Agent must be installed with its API server on
(`hermes gateway` running with `API_SERVER_ENABLED=true` and an `API_SERVER_KEY`; see Hermes'
docs, *Features → API Server*). Then:

```bash
cd gateway
uv venv && . .venv/bin/activate
uv pip install -e .            # add '.[whisper]' for local speech-to-text in-process

hermes-gateway token add pixel   # prints the token once; paste it into the phone app
hermes-gateway probe             # talks to Hermes and prints the raw events it sends: READ THIS
```

`probe` exists because the Hermes adapter carries assumptions about event field names, the
approval event, and session continuity that the Hermes docs do not pin down. Compare its output
with the `ASSUMPTION` comments in `hermes_gateway/brains/hermes.py` before trusting the gateway.

Configuration is `~/.hermes-gateway/config.json` or environment variables (see `config.py`).
The minimum:

```bash
export HERMES_API_KEY=...                       # Hermes' API_SERVER_KEY
export HERMES_GATEWAY_HOST=100.x.y.z           # the Mac's Tailscale address
export HERMES_GATEWAY_STT=openai               # or whisper / none
export HERMES_GATEWAY_STT_BASE_URL=http://127.0.0.1:8000   # a local whisper server
hermes-gateway serve
```

`serve` refuses to bind anything that is not loopback or a Tailscale address unless you pass
`--allow-any-bind`. There is no reason to.

Run it without Hermes to develop against the scripted brain:

```bash
hermes-gateway serve --brain fake --host 127.0.0.1
hermes-gateway say "echo hello wrist" --token hg1_…
hermes-gateway say "danger clean up" --token hg1_… --approve deny
hermes-gateway speak utterance.wav --token hg1_…      # 16-bit mono WAV; proves STT before the watch is involved
```

## As a launchd agent

```bash
cat > ~/Library/LaunchAgents/dev.claudewear.hermes-gateway.plist <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>dev.claudewear.hermes-gateway</string>
  <key>ProgramArguments</key><array>
    <string>/Users/YOU/claude-wear/gateway/.venv/bin/hermes-gateway</string><string>serve</string>
  </array>
  <key>EnvironmentVariables</key><dict>
    <key>HERMES_API_KEY</key><string>…</string>
    <key>HERMES_GATEWAY_HOST</key><string>100.x.y.z</string>
    <key>HERMES_GATEWAY_STT</key><string>openai</string>
  </dict>
  <key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/tmp/hermes-gateway.log</string>
  <key>StandardErrorPath</key><string>/tmp/hermes-gateway.log</string>
</dict></plist>
PLIST
launchctl load ~/Library/LaunchAgents/dev.claudewear.hermes-gateway.plist
```

## Layout

| path | what |
| --- | --- |
| `hermes_gateway/events.py` | the v1 shapes (the stable part) |
| `hermes_gateway/turns.py` | the turn engine: approvals, timeouts, cancellation, one turn per session |
| `hermes_gateway/app.py` | HTTP + WebSocket surface |
| `hermes_gateway/brains/hermes.py` | adapter over Hermes' `/v1/runs`; `probe` lives here |
| `hermes_gateway/brains/fake.py` | scripted brain for tests and offline development |
| `hermes_gateway/stt.py`, `tts.py` | pluggable speech in and out |
| `hermes_gateway/policy.py` | what counts as high risk |
| `schema/v1`, `fixtures/v1` | generated contract; `python scripts/gen_fixtures.py` |

## Tests

```bash
uv pip install -e '.[dev]'
pytest -q
ruff check . && ruff format --check .
```
