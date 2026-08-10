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

## What is here in M3

- **Pair** — the address and the 8-digit code, exchanged for a device-scoped token in
  `EncryptedSharedPreferences`.
- **Sessions** — awaiting first, accent-coloured, a spinner while a chat is working.
  Rebuilt from `turn`, `ask`, `permission` and `resolved` rather than from snapshots
  alone, because the bridge only re-broadcasts the list when the *set* of chats changes.
- **New chat** — the roots the bridge sent, one tap each. Typing an absolute path is the
  fallback, and the only path when the bridge has no `projectRoots` configured.
- **Chat** — the condensed transcript, dictation and a prompt field, Stop, and the
  permission mode. Anything the agent is blocked on leads the screen as a chip that opens
  its card.
- **Question** — one section per AskUserQuestion question, `header` as the title because
  the SDK made it twelve characters for exactly this screen. Chips for options; toggles and
  a Send for a multiSelect; a single single-select question answers on the tap. "Something
  else…" dictates a free-text answer — the transcript goes in as the value, so Claude gets
  the answer rather than the word "Other" — and "Say something else" abandons the options
  and just talks.
- **Permission** — the tool, then the actual command in full, then Allow / Always allow /
  Deny. "Always allow" appears only when the bridge sent a `localSettings` rule it could
  persist, and it names that rule. Deny opens reason chips plus dictation, because Claude
  sees the deny message and adapts instead of retrying blindly.
- **`notify/SocketNotifications`** — one card per chat in a group with a summary, so three
  chats waiting on you are three cards. Vibration says what kind of thing wants you;
  the card says which chat, which is the only question worth answering with two of them
  blocked. A question offers its own options as `RemoteInput` choices, a permission offers
  Allow and a Deny carrying the same reasons the card does, and a `resolved` cancels exactly
  the card it belongs to.
- **`notify/Replies`** — what a shade reply means and how it gets back to the socket. Every
  reply carries the `sessionId` **and** the `requestId`, and the intents target
  `ConnectionService` rather than a receiver: if the process was reclaimed between the buzz
  and the reply, starting the service is what had to happen anyway.
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
| Signed APK on a merge to `main` | M4 |
| Battery pass, multi-session stress, reconnect under real radio conditions | M5 |
| Phone-relay `ClientTransport`, FCM `NotificationTransport` | Later, behind the seams |

A dictated reply from the shade to a multi-question AskUserQuestion goes in as the
top-level `response` rather than as an answer to one of them: Claude receives "The user
responded: …" and can deal with a sentence, whereas guessing which of four questions a
sentence answers cannot be done from a notification and should not be attempted. Tapping
one of the offered choices on a single-question card *is* answered as that question, since
then there is nothing to guess. The full multi-question path is the card in the app.

## Testing

`SessionsClient` depends on the two transport interfaces and nothing else, so the whole
protocol state machine is exercised with no device: reconnect, gap-and-resync, per-chat
transcripts, who-needs-you tracking between snapshots, and the shape of every answer and
decision that goes back out.

`CardsTest` covers what the cards produce rather than what they look like — the answers map
is keyed by the question text, a multiSelect joins its labels, dictation becomes the answer
itself, "always allow" only appears when there is a rule to write. It clicks through the
semantics action rather than injecting a touch, because a `ScalingLazyColumn` composes a row
slightly before it is fully inside the bezel; that a chip is legible and reachable is what
the screenshots are for.

`SocketNotificationsTest` runs the case the notification layer exists for: three chats
blocked at once are three grouped cards with a summary, each reply naming the request it
answers, and a `resolved` cancelling the one card it belongs to.

Two coroutine-test details are load-bearing enough to be worth knowing before you add a
test that involves the reconnect delay, and both are commented where they matter: a
`UnconfinedTestDispatcher()` built without `testScheduler` brings a scheduler of its own,
and `advanceUntilIdle` will not advance virtual time for work that only lives in
`backgroundScope`. Either one silently strands the delay and the test fails looking like a
bug in the client.

## Versions

`gradle/libs.versions.toml` is the single place. `compileSdk`/`targetSdk` 34 and `minSdk`
30 come from the plan's assumption that the target is a current Galaxy Watch on Wear OS 5;
confirming the actual device is open question 2, and it changes only that file and the
emulator image in `scripts/e2e.sh`.

Everything targets bytecode 17 and is built by whatever JDK runs Gradle (21 in CI), so no
toolchain has to be provisioned at build time.
