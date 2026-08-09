# wear/

The Wear OS app: a thin remote control for the bridge. It renders turn boundaries, buzzes
your wrist, and takes voice input. It holds no agent logic.

```
:protocol   Kotlin/JVM. Generated @Serializable models + the golden contract test.
:app        Wear OS, Compose for Wear OS.
```

`:protocol` is a plain Kotlin library rather than an Android one, so the contract tests
run as fast unit tests with nothing device-shaped in the way.

```sh
./gradlew :protocol:test                 # the protocol contract, Kotlin side
./gradlew :app:testDebugUnitTest         # ViewModels against a fake transport
./gradlew :app:assembleDebug
make e2e                                 # from the repo root: emulator + fake bridge
```

## What is here in M0

Enough to make the E2E loop a real app talking to a real bridge, and no more:

- **`transport/ClientTransport`** — the seam. `WebSocketTransport` (OkHttp) is the only
  implementation; a phone relay over the Data Layer API is the deferred second one.
- **`notify/NotificationTransport`** — the other seam. `SocketNotifications` vibrates with
  a distinct pattern per event kind.
- **`ui/SessionsViewModel`** — holds the socket, keeps the session list, hands every turn
  to the notification transport, and resyncs when a `seq` jumps. Depends on the two
  interfaces and nothing else, which is why it is testable with no device.
- **`ui/ClaudeWearApp`** — a stub. Pair, see the chats, watch a turn arrive.

## What is not

| | Milestone |
| --- | --- |
| Real Pair / Sessions / Chat screens, foreground `ConnectionService`, Ongoing Activity | M2 |
| Question and permission cards, voice reply in-app and from the shade | M3 |
| Grouped per-session notifications with `RemoteInput` carrying the `requestId` | M3 |
| Reconnect backoff, battery pass | M5 |

The stub renders a waiting question or permission as a transcript line. That is deliberate:
a half-built permission card that does not show the actual command is worse than no card,
because the failure mode this product designs against is approving what you cannot see.

## Versions

`gradle/libs.versions.toml` is the single place. `compileSdk`/`targetSdk` 34 and `minSdk`
30 come from the plan's assumption that the target is a current Galaxy Watch on Wear OS 5;
confirming the actual device is open question 2, and it changes only that file.

Everything targets bytecode 17 and is built by whatever JDK runs Gradle (21 in CI), so no
toolchain has to be provisioned at build time.
