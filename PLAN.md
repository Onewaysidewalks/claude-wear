# claude-wear — Implementation Plan

Control Claude Agent SDK sessions from a Samsung Galaxy watch: get pinged when it's
your turn, answer by voice, handle multiple concurrent chats and clarifying questions
the way Claude Desktop and the CLI do.

---

## Context

The Claude Agent SDK is a Node/Python library that runs the Claude Code agent loop **in
your process**, with a real filesystem and shell. A watch cannot host that. So the
product is two pieces:

- A **bridge** — a small Node service that owns N live Agent SDK sessions on a machine
  that has your code (laptop, homelab box, dev container).
- A **Wear OS app** — a thin remote control. It renders the agent's turn boundaries,
  buzzes your wrist, and takes voice input. It holds no agent logic.

The interaction the SDK gives us maps almost exactly onto a watch. The SDK's
`canUseTool` callback **blocks the agent** until you answer, and it fires for exactly
the two moments a watch is good at: "may I run this?" and `AskUserQuestion`
("which of these do you want?"). Everything else — the agent thinking, reading files,
editing — is not something you want on a 1.5" screen and we deliberately don't render it
in detail.

**Goals**

1. Everything runs and is testable on a laptop with no cloud dependencies and no API key
   (via a fake agent runner).
2. Merge to `master` publishes a signed APK to GitHub Releases.
3. Watch app scope: notify/vibrate on turn, voice-to-text reply, multiple chats,
   AskUserQuestion (AUQ) and permission prompts.

---

## Assumptions (flag these if wrong)

- **"Galaxy 9"** is read as a current Samsung Galaxy Watch running **Wear OS 5**.
  Target `compileSdk`/`targetSdk` 34, `minSdk` 30 (Wear OS 3+). If you meant a specific
  device with a different OS level, only the Gradle config changes.
- The bridge runs on a machine you control, reachable from the watch over **LAN,
  Wi-Fi, or a Tailscale/WireGuard tailnet**. Not exposed to the public internet.
- Watch → bridge is a **direct WebSocket**. Wear OS routes HTTP over the paired phone's
  connection when the watch has no Wi-Fi/LTE, so no companion phone app is required in
  v1. (FCM push is deliberately deferred — see *Deferred*.)
- Bridge auth is a **pre-shared token** obtained by a one-time pairing code. Adequate
  for a private network; not a multi-tenant auth story.

---

## Architecture

```
┌──────────────┐   WebSocket (JSON, token auth)   ┌───────────────────────────┐
│  Wear OS app │ ───────────────────────────────► │  bridge (Node 22, TS)     │
│  (Kotlin,    │ ◄─────────────────────────────── │                           │
│   Compose)   │   turn / ask / permission / text │  SessionRegistry          │
└──────────────┘                                  │   ├─ Session A ─ query()  │
       │                                          │   ├─ Session B ─ query()  │
       ├─ Vibrator + ongoing notification         │   └─ Session C ─ query()  │
       ├─ RemoteInput (voice from shade)          │                           │
       └─ RecognizerIntent (voice in app)         │  @anthropic-ai/claude-    │
                                                  │   agent-sdk               │
                                                  └───────────────────────────┘
```

### Why a bridge, not "the SDK on the watch"

The Agent SDK ships for Node and Python only, and needs a filesystem and shell to be
useful. Even if it ran on-device, the watch isn't where your repo lives. The bridge is
where the agent actually works; the watch is a notification-and-decision surface.

### Repository layout

```
claude-wear/
├── protocol/                   # single source of truth for the wire format
│   ├── schema/*.schema.json    # JSON Schema per message
│   └── golden/*.json           # fixtures BOTH sides assert against
├── bridge/                     # TypeScript / Node 22
│   ├── src/
│   │   ├── server.ts           # HTTP + ws
│   │   ├── sessions.ts         # SessionRegistry: create/list/resume
│   │   ├── session.ts          # one query() + its pending-request map
│   │   ├── runner/
│   │   │   ├── types.ts        # AgentRunner interface  ← the seam
│   │   │   ├── sdk.ts          # real: query() from the Agent SDK
│   │   │   └── fake.ts         # scripted fixtures, no API key
│   │   ├── turn.ts             # "is it the user's turn?" derivation
│   │   ├── pairing.ts          # code → token exchange
│   │   └── protocol.ts         # generated from protocol/schema
│   └── test/
├── wear/                       # Android Gradle project
│   ├── app/                    # :app  — Wear OS, Compose for Wear OS
│   └── protocol/               # :protocol — kotlinx.serialization models
├── scripts/e2e.sh
└── .github/workflows/{ci.yml,release.yml}
```

`protocol/` exists so the two languages can't drift silently. Schemas generate TS types
and Kotlin `@Serializable` data classes; the golden fixtures are decoded **and**
re-encoded by tests on both sides. A field renamed on one side fails CI on the other.

---

## Bridge design

### Sessions

One Agent SDK `query()` per chat. Each session is driven by a **streaming input
AsyncIterable** rather than a one-shot string prompt, which is what lets us push
follow-up user messages into a live session and keeps `canUseTool` reachable:

```ts
// bridge/src/session.ts (sketch)
const q = query({
  prompt: this.inputStream(),          // AsyncIterable<SDKUserMessage>
  options: {
    cwd: this.cwd,
    resume: this.resumeId,             // set on bridge restart
    permissionMode: "default",         // never bypassPermissions by default
    canUseTool: (toolName, input, { signal }) =>
      this.awaitDecision(toolName, input, signal),
  },
});
```

`query()` returns a `Query` that is an `AsyncGenerator<SDKMessage>` plus control
methods. We use:

| Need | API |
| --- | --- |
| Push a follow-up user message | the streaming-input iterable (or `q.streamInput()`) |
| Stop a runaway agent from the watch | `q.interrupt()` |
| Capture session id for restart-resume | `session_id` on the init `system` message |
| Loosen permissions mid-session | `q.setPermissionMode("acceptEdits")` |
| Clean shutdown | `q.close()` |

Session ids are persisted to `~/.claude-wear/sessions.json` so a bridge restart resumes
each chat with `resume: <id>` instead of losing context.

### Turn detection — the core of the product

The watch buzzes when, and only when, the agent is blocked on **you**. The bridge
derives that from three signals:

| Signal | Emitted event | Watch UI |
| --- | --- | --- |
| `canUseTool` fires with `toolName === "AskUserQuestion"` | `ask` | Question card, 2–4 chips per question |
| `canUseTool` fires with any other tool | `permission` | Allow / Deny card showing the command |
| A `result` message arrives on the stream | `idle` | "Your turn" — dictate the next prompt |

`canUseTool` **blocks the agent until it returns**, and the SDK will wait indefinitely.
That's exactly the semantics we want: the agent genuinely pauses while the watch is in
your pocket. The bridge implements it as a pending-promise map keyed by a `requestId`:

```ts
awaitDecision(toolName, input, signal): Promise<PermissionResult> {
  const requestId = randomUUID();
  const p = new Promise<PermissionResult>((resolve) =>
    this.pending.set(requestId, resolve));
  this.broadcast(toWireEvent(requestId, toolName, input));   // → watch vibrates
  signal.addEventListener("abort", () => this.pending.delete(requestId));
  return p;
}
```

**Pending requests survive watch disconnects.** They stay in `this.pending`; on the next
`subscribe`, the bridge replays every outstanding request. Walking out of Wi-Fi range
doesn't drop a decision on the floor.

### Answering AskUserQuestion

The AUQ input arrives as `{ questions: [{ question, header, options[{label, description}], multiSelect }] }`,
1–4 questions with 2–4 options each. The answer must echo the original `questions` array
back and add an `answers` map keyed by the **question text**, valued by the selected
**label**:

```ts
return {
  behavior: "allow",
  updatedInput: {
    questions: input.questions,                       // must be passed through
    answers: {
      "How should I format the output?": "Summary",
      "Which sections?": "Introduction, Conclusion",  // multiSelect → joined labels
    },
  },
};
```

Two extras the watch supports, both already in the protocol:

- **Free text instead of a listed option** — dictate a custom answer; the transcript goes
  in as the value (not the word "Other"). This is how Claude Desktop's "Other" works.
- **Dismiss and just talk** — set the top-level `response` field instead of `answers`,
  and Claude receives "The user responded: …". Mapped to a "Say something else" action.

### Permission decisions

`{ behavior: "allow", updatedInput: input }` or
`{ behavior: "deny", message: "<why>" }`. The deny message is visible to Claude, so the
watch's "Deny" flow offers a canned reason list plus voice, and the agent adapts rather
than retrying blindly.

"Always allow" echoes back the `suggestions` array the SDK hands the callback, filtered
to `destination === "localSettings"`, in `updatedPermissions` — so the rule persists to
`.claude/settings.local.json` and you stop being asked.

> **Note:** a tool auto-approved by an allow rule or by `acceptEdits`/`bypassPermissions`
> **never reaches `canUseTool`** — so it never buzzes your wrist. That's the correct
> behavior (approved things shouldn't interrupt you), but it means the permission mode
> is effectively the notification-volume knob. The watch exposes it per session.

### Wire protocol (v1)

Watch → bridge: `hello`, `subscribe`, `newSession`, `prompt`, `answer`, `permission`,
`interrupt`, `setMode`.
Bridge → watch: `sessions`, `turn`, `ask`, `permission`, `text`, `done`, `error`.

Every server event carries `sessionId` and a monotonic `seq` so the watch can detect gaps
and re-sync after a reconnect. Assistant text is sent **summarized, not streamed** — a
watch doesn't need token-by-token deltas, and it's a meaningful battery saving.

---

## Wear OS app design

Kotlin, **Compose for Wear OS**, single `:app` module + `:protocol`.

| Screen | Contents |
| --- | --- |
| **Pair** | Digit entry for the 8-digit code the bridge prints; exchanges it for a long-lived token stored in `EncryptedSharedPreferences`. |
| **Sessions** | `ScalingLazyColumn` of chats. Badge states: *awaiting you* (accent), *working* (spinner), *idle*. Sorted awaiting-first. |
| **Chat** | Condensed transcript, mic FAB, interrupt button, overflow → permission mode. |
| **Question** | One card per AUQ question. `header` as the title (≤12 chars, made for exactly this). Chips for options, toggles + confirm when `multiSelect`. Trailing "Other…" chip → dictation. |
| **Permission** | Tool name + the actual command/path, then Allow / Always / Deny. Deny opens reason chips + dictation. |

**Connectivity.** A foreground `ConnectionService` holds the WebSocket and posts an
[Ongoing Activity](https://developer.android.com/training/wearables/ongoing-activity) so
the connection survives Doze while a session is live. Exponential-backoff reconnect;
`subscribe` replays anything missed.

**Alerting.** `VibrationEffect` with distinct patterns per event kind (question ≠
permission ≠ idle) plus a notification carrying a `RemoteInput` action — so a voice reply
can be dictated straight from the notification without opening the app. This is the
"buzz and answer without looking" path and it's the one to get right.

**Voice-to-text.** `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` in-app and `RemoteInput` in
the shade. Both use the platform recognizer: on-device, free, no extra API key, and it
gives users the keyboard/handwriting fallbacks for free.

---

## Local testability

This is a first-class requirement, so the seams are designed for it up front.

### The `AgentRunner` seam

```ts
// bridge/src/runner/types.ts
export interface AgentRunner {
  start(opts: RunnerOptions): AgentHandle;   // yields SDKMessage-shaped events
}
```

`sdk.ts` wraps the real `query()`. `fake.ts` replays a scripted scenario file:

```jsonc
// bridge/test/scenarios/auq-then-bash.json
[
  { "at": 0,    "emit": { "type": "assistant", "text": "Looking at the repo…" } },
  { "at": 300,  "askUserQuestion": { "questions": [ /* … */ ] } },
  { "at": 0,    "expectAnswer": true },
  { "at": 200,  "permission": { "tool": "Bash", "input": { "command": "npm test" } } },
  { "at": 0,    "emit": { "type": "result", "subtype": "success" } }
]
```

`FAKE_AGENT=1 npm run dev` gives a fully interactive bridge with **no API key and no
network**. Every scenario the watch must handle — including ones that are awkward to
provoke for real, like a 4-question multiSelect AUQ or a denied `rm -rf` — is a fixture.

### Test layers

| Layer | Tooling | What it covers |
| --- | --- | --- |
| Bridge unit | Vitest | turn derivation, AUQ answer mapping, pending-request replay on reconnect, pairing |
| Protocol contract | Vitest + JUnit | both sides decode **and** re-encode every `protocol/golden/*.json`; drift fails CI |
| Wear unit | JUnit + Turbine | ViewModels against a fake WS transport; no device needed |
| Wear UI | Robolectric + Compose test rule | question card renders 4 options, multiSelect toggles, permission card shows the command |
| E2E | Wear emulator + fake bridge | full loop, scripted |

### `make e2e`

`scripts/e2e.sh`: boot the bridge with `FAKE_AGENT=1` on `:8787` → launch the Wear
emulator (`system-images;android-34;android-wear;x86_64`, `wearos_small_round`) → install
the debug APK → run an instrumented test that pairs, receives a turn, answers an AUQ,
approves a permission, and sends a dictated reply (speech stubbed via an
`ActivityResult` shim). Asserts against the bridge's recorded inbox. Emulator reaches the
host at `10.0.2.2`.

---

## CI/CD

### `.github/workflows/ci.yml` — on PR and on `master`

| Job | Steps |
| --- | --- |
| `bridge` | Node 22, `npm ci`, `lint`, `typecheck`, `vitest run --coverage` |
| `android` | JDK 21, Gradle cache, `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` |
| `contract` | regenerate types from `protocol/schema`, fail if the working tree is dirty; run golden tests both sides |
| `e2e` | `reactivecircus/android-emulator-runner` (KVM on `ubuntu-latest`), runs `scripts/e2e.sh` |

### `.github/workflows/release.yml` — on push to `master`

1. Run the full `ci.yml` suite as a gate (reusable workflow).
2. Version: `v0.1.<github.run_number>`, written into `versionName`/`versionCode`.
3. `./gradlew :app:assembleRelease`.
4. **Sign** with a keystore restored from secrets:
   `WEAR_KEYSTORE_BASE64`, `WEAR_KEYSTORE_PASSWORD`, `WEAR_KEY_ALIAS`, `WEAR_KEY_PASSWORD`.
   If the secrets are absent the build still succeeds, produces a debug-signed APK, and
   the release is marked a pre-release — so **the pipeline is testable before the
   keystore exists**, rather than being dead code until launch day.
5. Tag, then create a GitHub Release with generated notes, attaching:
   - `claude-wear-<version>.apk`
   - `claude-wear-bridge-<version>.tgz` (`npm pack`) — the watch app and the bridge that
     speaks its protocol version are released as a matched pair.

---

## Security posture

- Bridge binds to `127.0.0.1` by default; LAN/tailnet binding is explicit opt-in.
- Token auth on every WS frame; pairing codes are single-use and expire in 5 minutes.
- `permissionMode` defaults to `default` — never `bypassPermissions`. The watch can raise
  it per session, and the UI says plainly that raising it means fewer wrist buzzes.
- The permission card renders the **actual** command/path, never a summary. Approving
  something you can't see is the failure mode worth designing against.
- No transcript content is persisted on the watch beyond the in-memory session view.

---

## Milestones

| # | Deliverable | Done when |
| --- | --- | --- |
| M0 | Scaffold: monorepo, protocol schema + codegen, `FakeAgentRunner`, CI green | `make e2e` passes with a stub UI |
| M1 | Bridge on the real Agent SDK; a `bridge-cli` client for driving it from a terminal | Real Claude session driven end-to-end from the CLI |
| M2 | Wear app: pairing, session list, chat, connection service, vibrate on turn | Watch buzzes on a real `result` |
| M3 | AUQ card + permission card + voice reply (in-app and from the shade) | Full scripted E2E green on emulator |
| M4 | Release pipeline, signing, matched bridge tarball | A merge to `master` produces an installable APK |
| M5 | Hardening: reconnect/replay edge cases, battery pass, multi-session stress | — |

---

## Deferred (explicitly out of v1)

- **FCM push.** Would let the watch sleep instead of holding a socket, but needs a
  Firebase project and can't be exercised in a hermetic local test — which conflicts with
  the "fully testable locally" requirement. Revisit once battery data from M5 justifies
  the cost. The protocol already carries everything a push payload would need.
- **Phone companion app.** Data Layer pairing and token sync would be nicer than digit
  entry, but it doubles the Android surface for a one-time flow.
- **The `defer` hook decision.** The SDK can defer a pending tool call so the bridge
  process can exit and resume from the persisted session later. Right answer for a
  laptop that sleeps; not needed while the bridge is assumed always-on.
- **Streaming assistant text**, subagent progress, context-usage display.

---

## Open questions

1. Where does the bridge live — a laptop that sleeps, or an always-on box? A sleeping
   host makes the `defer` work in *Deferred* a v1 item rather than a later one.
2. Is a paired phone always present? If yes, a companion app makes pairing and push
   materially better and M2 should absorb it.
3. Confirm the target device/OS level so `minSdk` and the emulator image are pinned to
   what you actually wear.
