#!/usr/bin/env bash
# Milestones 2 and 3 on one machine, no devices: start the real gateway with the scripted brain
# and fake speech-to-text, mint a token, and run the Kotlin client and relay against it.
set -euo pipefail
cd "$(dirname "$0")/.."

PY=${GATEWAY_PYTHON:-gateway/.venv/bin/python}
if [[ ! -x "$PY" ]]; then
  echo "no gateway venv at $PY; run: cd gateway && uv venv && uv pip install -e '.[dev]'" >&2
  exit 2
fi

HOME_DIR=$(mktemp -d)
PORT=$("$PY" -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')
export HERMES_GATEWAY_HOME="$HOME_DIR"
export HERMES_GATEWAY_STT=fake

TOKEN=$("$PY" -m hermes_gateway.cli token add loopback | tail -1)
"$PY" -m hermes_gateway.cli serve --brain fake --host 127.0.0.1 --port "$PORT" >"$HOME_DIR/gateway.log" 2>&1 &
GW=$!
trap 'kill $GW 2>/dev/null || true; rm -rf "$HOME_DIR"' EXIT

for _ in $(seq 1 50); do
  if curl -sf "http://127.0.0.1:$PORT/v1/health" >/dev/null 2>&1; then break; fi
  sleep 0.2
done
curl -sf "http://127.0.0.1:$PORT/v1/health" >/dev/null || { echo "gateway did not start:"; cat "$HOME_DIR/gateway.log"; exit 1; }
echo "gateway up on 127.0.0.1:$PORT (log: $HOME_DIR/gateway.log)"

export LOOPBACK_GATEWAY_URL="http://127.0.0.1:$PORT"
export LOOPBACK_GATEWAY_TOKEN="$TOKEN"
(cd android && ./gradlew --no-daemon -q :phone:testDebugUnitTest --tests 'dev.claudewear.phone.LoopbackTest' "$@")
echo "loopback: ok"
