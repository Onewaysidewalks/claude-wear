# Testing matrix

Every row is the same script: ten voice turns ("what time is it", "turn off the porch light",
one that needs approval, one long answer), timed from the end of speech to the first spoken
word of the answer. Record p50, worst, and failures. A row passes at ≤ 1 failure in 10 and
p50 ≤ 8 s (≤ 5 s at home).

| # | watch | phone | Mac | expected path | pass? | p50 / worst / fails | notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | at home, BT to phone | home Wi-Fi + Tailscale | on | Data Layer over BT → tailnet | | | |
| 2 | at home, BT to phone | **cellular** + Tailscale | on | proves the tailnet path off Wi-Fi | | | |
| 3 | **Wi-Fi, phone away** (leave the phone at home, watch on Wi-Fi elsewhere or BT off) | cellular | on | Data Layer over **cloud** (Samsung *Remote connection* on) | | | |
| 4 | **LTE, phone away** | cellular | on | same over cellular | | | |
| 5 | at home, phone screen off 30 min | Wi-Fi, dozing | on | Doze: does the relay wake in time? | | | |
| 6 | any | any | **Hermes stopped** | error spoken within 10 s, not a spinner | | | |
| 7 | any | **token revoked** | on | watch shows "token rejected", no retry storm | | | |

## What the rows are really testing

* Rows 1–2 prove the Tailscale-only-on-the-phone design (assumption A3 is not involved).
* Rows 3–4 are assumption **A3** in PLAN.md: the Data Layer's cloud path is documented but
  best-effort, has no latency promise, and users report drops when the phone dozes. If a row
  fails, the fix is a relay for the watch (Tailscale Funnel or a small public WebSocket relay),
  not more retries. `PhoneLink.relayNode()` logs whether the phone node was `nearby` (BT) or
  not (cloud) so you can tell which path a turn took.
* Row 5 is the phone's foreground service and Android's battery manager; if it fails, the fix
  is a high-priority FCM wake or keeping the relay service foreground while the watch is
  "remote-connected".
* Rows 6–7 are the failure UX: an error must be a sentence on the wrist, never silence.

## Commands

Gateway side, on the Mac:

```bash
hermes-gateway say "what time is it" --token <phone token>        # M2 without a phone
tail -f /tmp/hermes-gateway.log                                     # turns, STT timings, errors
```

Watch side:

```bash
adb logcat -s HermesRelay HermesWatch WearableLS                    # relay and data layer
```

Time each turn from the watch's `Listening` → `Thinking` transition (end of speech) to the
first `speech`/TTS start. The phone's log line for `input.transcript` marks STT done.
