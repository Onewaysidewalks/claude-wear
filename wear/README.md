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
./gradlew :app:testDebugUnitTest         # the client against a fake transport
./gradlew :app:assembleDebug
make e2e                                 # from the repo root: emulator + fake bridge
```

## What is here in M2

- **Pair** — the address and the 8-digit code, exchanged for a device-scoped token in
  `EncryptedSharedPreferences`.
- **Sessions** — awaiting first, accent-coloured, a spinner while a chat is working.
  Rebuilt from `turn`, `ask`, `permission` and `resolved` rather than from snapshots
  alone, because the bridge only re-broadcasts the list when the *set* of chats changes.
- **New chat** — the roots the bridge sent, one tap each. Typing an absolute path is the
  fallback, and the only path when the bridge has no `projectRoots` configured.
- **Chat** — the condensed transcript, a prompt field, Stop, and the permission mode.
- **`service/ConnectionService`** — a foreground service holding the socket, with an
  Ongoing Activity. The connection has to outlive the Activity: a chat that blocked on you
  twenty minutes ago is exactly the case where nothing is on screen.
- **`transport/Backoff`** — half fixed, half jittered, capped. Pure, so the policy is
  testable rather than inferred from a battery graph.
- **`transport/ClientTransport` / `notify/NotificationTransport`** — the two seams. A phone
  relay over the Data Layer API and an FCM notifier are the deferred second implementations.

## What is not

| | Milestone |
| --- | --- |
| Question and permission cards, voice reply in-app and from the shade | M3 |
| Grouped per-session notifications with `RemoteInput` carrying the `requestId` | M3 |
| Battery pass, multi-session stress | M5 |

A waiting question or permission renders as a transcript line. That is deliberate: a
half-built permission card that does not show the actual command is worse than no card,
because the failure mode this product designs against is approving what you cannot see.
Text entry is the platform keyboard for the same reason — M3 brings the recognizer to
every input at once rather than to one of them.

## Testing

`SessionsClient` depends on the two transport interfaces and nothing else, so the whole
protocol state machine is exercised with no device: reconnect, gap-and-resync, per-chat
transcripts, and who-needs-you tracking between snapshots.

Two coroutine-test details are load-bearing enough to be worth knowing before you add a
test that involves the reconnect delay, and both are commented where they matter: a
`UnconfinedTestDispatcher()` built without `testScheduler` brings a scheduler of its own,
and `advanceUntilIdle` will not advance virtual time for work that only lives in
`backgroundScope`. Either one silently strands the delay and the test fails looking like a
bug in the client.

Robolectric UI and notification tests arrive in M3 with the cards they would assert on.

## Versions

`gradle/libs.versions.toml` is the single place. `compileSdk`/`targetSdk` 34 and `minSdk`
30 come from the plan's assumption that the target is a current Galaxy Watch on Wear OS 5;
confirming the actual device is open question 2, and it changes only that file and the
emulator image in `scripts/e2e.sh`.

Everything targets bytecode 17 and is built by whatever JDK runs Gradle (21 in CI), so no
toolchain has to be provisioned at build time.
