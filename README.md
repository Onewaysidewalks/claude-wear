# claude-wear: Hermes on the wrist

Hold the button on a Galaxy Watch. Speak. The request reaches Hermes on the Mac Mini; the answer
comes back and is read aloud. At home and away.

**Hermes is the only brain. Everything else is an interface.** The watch, the phone, and any
home satellite speak one contract, [gateway/API.md](gateway/API.md), to one door, the Hermes
Gateway on the Mac. Nothing else thinks, remembers, or holds a secret it does not need.

```
Galaxy Watch ── Data Layer (BT, or cloud when apart) ──▶ Android phone ── Tailscale ──▶ Mac Mini
 AudioRecord → ChannelClient                             holds the token             hermes-gateway
 TextToSpeech ← events                                   relays, confirms            ├─ STT
                                                                                     └─ Hermes API
```

| part | where | status |
| --- | --- | --- |
| Phase 0 spike: does the button reach us on your firmware? | `android/spike` | built, **needs your watch** ([runbook](docs/phase0-spike.md)) |
| Hermes Gateway v1: contract + reference implementation | `gateway/` | built, 55 tests, **needs `hermes-gateway probe` against your Hermes** |
| Shared types (contract check against the gateway fixtures) | `android/shared` | built, 9 tests |
| Phone companion: token, gateway session, relay for the watch | `android/phone` | built, 16 tests |
| Watch app: listen, think, speak | `android/watch` | built, 7 tests |

The plan, the milestones, and the list of things that must be **validated rather than assumed**
are in [PLAN.md](PLAN.md). The step-by-step bring-up, with what to expect at each milestone and
what a failure means, is [docs/bringup.md](docs/bringup.md). The testing matrix (home, cellular, Wi-Fi away, LTE away) is in
[docs/testing-matrix.md](docs/testing-matrix.md). What holds which secret is in
[docs/security.md](docs/security.md).

## Build

```bash
# Gateway (Python 3.11+, uv)
cd gateway && uv venv && . .venv/bin/activate && uv pip install -e '.[dev]' && pytest -q

# Android (JDK 21, Android SDK with platform 35; the wrapper fetches Gradle)
cd android && ./gradlew :shared:test :phone:testDebugUnitTest :watch:testDebugUnitTest \
    :spike:assembleDebug :phone:assembleDebug :watch:assembleDebug
```

APKs land in `android/*/build/outputs/apk/`. `make` at the top level runs all of it, and
`scripts/loopback.sh` runs the real Python gateway against the real Kotlin client and relay on
one machine: milestones 2 and 3 with no devices.

## Definition of done

Hold the button, speak, Hermes acts and answers out loud. At home and away. Everything in
between is a milestone in PLAN.md with a test that proves it.
