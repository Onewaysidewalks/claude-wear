#!/usr/bin/env bash
#
# The full loop: a bridge with no API key and no network, a Wear emulator, and the real app
# pairing with it, listing sessions and receiving a turn.
#
#   ./scripts/e2e.sh
#
# Uses an already-running emulator if there is one — which is how CI runs it, under
# reactivecircus/android-emulator-runner — and otherwise boots the AVD itself.
#
# Environment:
#   BRIDGE_PORT   default 8787. The emulator reaches the host's loopback at 10.0.2.2.
#   AVD_NAME      default wearos_small_round
#   SYSTEM_IMAGE  default system-images;android-34;android-wear;x86_64
#   KEEP_BRIDGE   set to 1 to leave the bridge running after the test
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE_PORT="${BRIDGE_PORT:-8787}"
AVD_NAME="${AVD_NAME:-wearos_small_round}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-34;android-wear;x86_64}"
WORK_DIR="$REPO_ROOT/.e2e"
BRIDGE_LOG="$WORK_DIR/bridge.log"
BRIDGE_STATE="$WORK_DIR/state"
BRIDGE_PID=""
EMULATOR_PID=""

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33m==> %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[31m==> %s\033[0m\n' "$*" >&2; exit 1; }

cleanup() {
  local status=$?
  if [[ -n "$BRIDGE_PID" && "${KEEP_BRIDGE:-0}" != "1" ]]; then
    kill "$BRIDGE_PID" 2>/dev/null || true
    wait "$BRIDGE_PID" 2>/dev/null || true
  fi
  if [[ -n "$EMULATOR_PID" ]]; then
    kill "$EMULATOR_PID" 2>/dev/null || true
  fi
  if [[ $status -ne 0 && -f "$BRIDGE_LOG" ]]; then
    warn "bridge log:"
    tail -n 40 "$BRIDGE_LOG" >&2 || true
  fi
  exit $status
}
trap cleanup EXIT

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR" "$BRIDGE_STATE"

# --- 1. the bridge, with a fake agent -----------------------------------------------

log "building the bridge"
cd "$REPO_ROOT/bridge"
[[ -d node_modules ]] || npm ci
npm run --silent build

log "starting the bridge on :$BRIDGE_PORT (fake agent, no API key, no network)"
FAKE_AGENT=1 node dist/cli.js \
  --port "$BRIDGE_PORT" \
  --inbox \
  --state-dir "$BRIDGE_STATE" \
  --scenarios auq-then-bash,quick-idle \
  --project-root "$REPO_ROOT" \
  >"$BRIDGE_LOG" 2>&1 &
BRIDGE_PID=$!

for _ in $(seq 1 50); do
  if curl -sf "http://127.0.0.1:$BRIDGE_PORT/health" >/dev/null; then break; fi
  kill -0 "$BRIDGE_PID" 2>/dev/null || die "the bridge exited before it was ready"
  sleep 0.2
done
curl -sf "http://127.0.0.1:$BRIDGE_PORT/health" >/dev/null || die "the bridge never became healthy"

PAIR_CODE="$(sed -n 's/.*pairing code: \([0-9]\{8\}\).*/\1/p' "$BRIDGE_LOG" | head -n 1)"
[[ -n "$PAIR_CODE" ]] || die "could not read a pairing code out of $BRIDGE_LOG"
log "bridge is up; pairing code $PAIR_CODE"

# --- 2. a Wear emulator -------------------------------------------------------------

command -v adb >/dev/null 2>&1 || export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"
command -v adb >/dev/null 2>&1 || die "adb is not on PATH; set ANDROID_HOME to an Android SDK"

adb start-server >/dev/null 2>&1 || true
running_devices() { adb devices | awk 'NR > 1 && $2 == "device"' | wc -l | tr -d '[:space:]'; }

if [[ "$(running_devices)" -eq 0 ]]; then
  EMULATOR_BIN="${ANDROID_HOME:-$HOME/Android/Sdk}/emulator/emulator"
  [[ -x "$EMULATOR_BIN" ]] || die "no emulator is running and $EMULATOR_BIN does not exist.
Start a Wear AVD first, or run this under reactivecircus/android-emulator-runner as CI does."

  if ! "$EMULATOR_BIN" -list-avds | grep -qx "$AVD_NAME"; then
    log "creating the $AVD_NAME AVD from $SYSTEM_IMAGE"
    "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" "$SYSTEM_IMAGE" >/dev/null
    echo no | "${ANDROID_HOME}/cmdline-tools/latest/bin/avdmanager" create avd \
      -n "$AVD_NAME" -k "$SYSTEM_IMAGE" -d wearos_small_round >/dev/null
  fi

  log "booting $AVD_NAME"
  "$EMULATOR_BIN" -avd "$AVD_NAME" -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
  EMULATOR_PID=$!
fi

log "waiting for the device"
adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
adb shell input keyevent 82 >/dev/null 2>&1 || true

# --- 3. the app ---------------------------------------------------------------------

log "installing and running the instrumented test"
cd "$REPO_ROOT/wear"
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.bridgeUrl=http://10.0.2.2:$BRIDGE_PORT" \
  "-Pandroid.testInstrumentationRunnerArguments.pairCode=$PAIR_CODE" \
  "-Pandroid.testInstrumentationRunnerArguments.cwd=$REPO_ROOT"

# --- 4. assert against what the bridge actually received -----------------------------

log "checking the bridge's recorded inbox"
curl -sf "http://127.0.0.1:$BRIDGE_PORT/debug/inbox" -o "$WORK_DIR/inbox.json" \
  || die "could not read the inbox"

node - "$WORK_DIR/inbox.json" <<'NODE'
const { readFileSync } = require("node:fs");
const { entries } = JSON.parse(readFileSync(process.argv[2], "utf8"));

const received = entries.filter((e) => e.direction === "in").map((e) => e.type);
const sent = entries.filter((e) => e.direction === "out").map((e) => e.type);

const failures = [];
const wants = (list, type, where) => {
  if (!list.includes(type)) failures.push(`the bridge never ${where} a \`${type}\``);
};

wants(received, "hello", "received");
wants(received, "subscribe", "received");
wants(received, "newSession", "received");
wants(received, "prompt", "received");
wants(sent, "sessions", "sent");
wants(sent, "turn", "sent");

const snapshot = entries.find((e) => e.direction === "out" && e.type === "sessions");
if (snapshot && !Array.isArray(snapshot.payload.projectRoots)) {
  failures.push("the snapshot carried no projectRoots for the New chat screen to offer");
}

const hello = entries.find((e) => e.direction === "in" && e.type === "hello");
if (hello && hello.payload.protocolVersion !== 1) {
  failures.push(`the watch greeted with protocol v${hello.payload.protocolVersion}`);
}

if (failures.length) {
  console.error("inbox assertions failed:");
  for (const f of failures) console.error(`  - ${f}`);
  console.error(`\nin: ${received.join(", ")}\nout: ${sent.join(", ")}`);
  process.exit(1);
}
console.log(`inbox ok — in: ${[...new Set(received)].join(", ")}; out: ${[...new Set(sent)].join(", ")}`);
NODE

log "e2e passed"
