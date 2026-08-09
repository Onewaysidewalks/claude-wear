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

## Decisions

Settled up front, because each one shapes the module boundaries below.

| Question | Decision |
| --- | --- |
| Watch → bridge transport | **Direct WSS now**, phone-relay companion as a phase-2 module behind the same protocol |
| Turn notification delivery | **WebSocket + foreground service now**, FCM as an opt-in transport later |
| Bridge hosting | **Deployed cloud service** (container, public HTTPS hostname) |
| APK signing | **Release keystore from GitHub Secrets**, debug-sign fallback for local/contributor builds |

The two "…later" answers are the reason `NotificationTransport` and `ClientTransport`
exist as interfaces from day one. Adding FCM or a phone relay afterwards should be a new
implementation of an existing seam, never a rewrite of the UI or the protocol.

## Assumptions (flag these if wrong)

- **"Galaxy 9"** is read as a current Samsung Galaxy Watch running **Wear OS 5**.
  Target `compileSdk`/`targetSdk` 34, `minSdk` 30 (Wear OS 3+). If you meant a specific
  device with a different OS level, only the Gradle config changes.
- The bridge is **single-tenant** — deployed by you, for you. Device-scoped tokens let you
  pair several watches, but there are no user accounts, orgs, or per-user isolation. If
  this ever serves other people, the auth model needs to be rebuilt, not extended.
- **Workspace provisioning is the biggest unresolved design question** — see
  *Open questions*. The plan assumes git-clone-per-session as the default because it's
  the only option that works on ephemeral container storage, but this needs your call
  before M1.

---

## Architecture

```
┌──────────────┐                                  ┌─────────────────────────────────┐
│  Wear OS app │  WSS + Bearer token, over the    │  bridge container (cloud)       │
│  (Kotlin,    │ ═══ public internet ═══════════► │                                 │
│   Compose)   │ ◄══════════════════════════════  │  SessionRegistry                │
└──────────────┘   turn / ask / permission / text │   ├─ Session A ─ query()        │
       │                                          │   ├─ Session B ─ query()        │
       ├─ Vibrator + ongoing notification         │   └─ Session C ─ query()        │
       ├─ RemoteInput (voice from shade)          │                                 │
       ├─ RecognizerIntent (voice in app)         │  @anthropic-ai/claude-agent-sdk │
       │                                          │  ANTHROPIC_API_KEY (server-side)│
       ├─ ClientTransport ─────┐                  │                                 │
       │   └─ (phase 2) phone  │                  │  /workspaces  ← persistent vol  │
       └─ NotificationTransport│                  │  /sessions    ← transcripts     │
           └─ (phase 2) FCM ───┘                  └─────────────────────────────────┘
```

### Why a bridge, not "the SDK on the watch"

The Agent SDK ships for Node and Python only, and needs a filesystem and shell to be
useful. Even if it ran on-device, the watch isn't where your code lives. The bridge is
where the agent actually works; the watch is a notification-and-decision surface.

### Two seams that exist purely for phase 2

Both "later" answers get an interface now, so the follow-on work never touches the UI:

```kotlin
// wear/app/.../transport/ClientTransport.kt
interface ClientTransport {                     // WebSocketTransport today
    fun connect(): Flow<ServerEvent>            // PhoneRelayTransport later,
    suspend fun send(msg: ClientMessage)        // via the Wear Data Layer API
}

// wear/app/.../notify/NotificationTransport.kt
interface NotificationTransport {               // SocketNotifications today
    fun onTurn(event: TurnEvent)                // FcmNotifications later
}
```

The ViewModels depend on the interfaces, never on OkHttp or Firebase. Swapping in a
phone relay or FCM means one new class and a DI binding.

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
│   │   ├── auth.ts             # device tokens, pairing, rate limiting
│   │   ├── workspace.ts        # provisioning: clone/refresh a repo per session
│   │   └── protocol.ts         # generated from protocol/schema
│   └── test/
├── deploy/
│   ├── Dockerfile              # the one image: runs locally AND in prod
│   ├── compose.yaml            # local: bridge + fake agent, hermetic
│   └── fly.toml                # (or render.yaml) — platform config
├── wear/                       # Android Gradle project
│   ├── app/                    # :app  — Wear OS, Compose for Wear OS
│   └── protocol/               # :protocol — kotlinx.serialization models
├── scripts/e2e.sh
└── .github/workflows/{ci.yml,release.yml,deploy.yml}
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

## Deployment

One image, two run modes. `deploy/Dockerfile` is what runs in production *and* what
`compose.yaml` runs locally with `FAKE_AGENT=1` — so "works on my machine" and "works in
prod" are the same artifact with different env. This is what keeps the hermetic local
test path honest now that the bridge is a deployed service.

**Platform: Fly.io** (Render/Railway are drop-in alternatives). It's picked for three
properties this workload actually needs:

- **Persistent volumes.** Agent SDK transcripts live at `~/.claude/projects/<cwd>/*.jsonl`
  and session `resume` needs the file to still exist. On ephemeral storage every redeploy
  silently loses conversation history. Volume mounted at `/sessions`, with
  `CLAUDE_CONFIG_DIR` pointed at it.
- **WebSockets without idle timeouts.** A session parked on `canUseTool` while the watch
  is in your pocket may hold a connection open for a long time. Platforms that kill idle
  connections at 60s break the core interaction.
- **Scale-to-zero is a non-goal.** A cold start mid-session drops pending permission
  requests. The bridge runs `min_machines_running = 1`.

**Workspace provisioning** (`workspace.ts`). The agent needs code to work on. On a cloud
container, the default is **git-clone-per-session**: `newSession` takes a repo URL + ref,
the bridge clones into `/workspaces/<sessionId>`, and passes that as `cwd` to `query()`.
A fine-grained GitHub PAT lives in deploy secrets. Clones are cached by repo and refreshed
with `git fetch` rather than re-cloned. Volume-mount and bring-your-own-checkout remain
possible — see *Open questions*, this needs your call.

**Secrets** (platform secret store, never in the image): `ANTHROPIC_API_KEY`,
`GITHUB_TOKEN`, `PAIRING_ADMIN_KEY`. The watch never sees any of these — it holds only its
own device token.

**`deploy.yml`** — on push to `master`, after CI passes: build the image, push, deploy,
health-check `/healthz`, roll back on failure. Runs in parallel with the APK release so a
merge ships both halves of the matched pair.

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

`docker compose -f deploy/compose.yaml up` gives a fully interactive bridge with **no API
key, no network, and no cloud account** — the same image that runs in production, with
`FAKE_AGENT=1`. Every scenario the watch must handle, including ones awkward to provoke
for real (a 4-question multiSelect AUQ, a denied `rm -rf`, a mid-request disconnect), is
a fixture.

Deploying to a cloud service is the decision most likely to erode "completely testable
locally," so it's worth being explicit: **no test in CI touches the deployed bridge.**
The local compose stack is the test target, and `deploy.yml` runs strictly after the
suite passes.

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

A publicly reachable bridge is a materially different threat model from a LAN box, and
it's the part of these decisions with the most consequence. **This endpoint can run
arbitrary shell commands and holds your Anthropic API key and a GitHub token.** Treat it
as production infrastructure, not a dev convenience.

**Authentication**

- TLS terminated at the platform edge; the bridge rejects non-TLS upgrades outright.
- Device-scoped bearer tokens: 256-bit random, stored **hashed** (Argon2id), presented on
  the WS upgrade and revocable individually. Losing a watch revokes one token.
- Pairing: `POST /pair` with an 8-digit code, single-use, 5-minute TTL, generated only by
  an operator call carrying `PAIRING_ADMIN_KEY`. A public endpoint means codes can't be
  freely mintable — this is the difference between an 8-digit code being fine and being a
  10-minute brute force.
- Rate limits on `/pair` (strict, per-IP) and on session creation. Failed-auth attempts
  are logged and alert on a burst.

**Blast radius**

- `permissionMode` stays `default`. **`bypassPermissions` is not exposed to the watch at
  all** — on a LAN box that's a convenience toggle; on an internet-facing container that
  runs your repos it's a remote-code-execution switch behind a phone screen. Raising to
  `acceptEdits` is available; going further requires a redeploy.
- The container runs non-root with a read-only root filesystem apart from `/workspaces`
  and `/sessions`. No Docker socket, no host mounts.
- The GitHub PAT is fine-grained and scoped to the repos you actually want the agent
  touching. It is the practical limit on what a compromised session can reach.
- Egress is unrestricted by default (the agent needs npm, PyPI, the web). If that's not
  acceptable, an allowlisted egress proxy is the lever — flagged, not assumed.

**On the watch**

- Token in `EncryptedSharedPreferences`, cleared on unpair.
- The permission card renders the **actual** command or path, never a summary. Approving
  something you can't see is the failure mode worth designing against, and it gets worse
  the smaller the screen.
- No transcript content persisted beyond the in-memory session view.

---

## Milestones

| # | Deliverable | Done when |
| --- | --- | --- |
| M0 | Scaffold: monorepo, protocol schema + codegen, `FakeAgentRunner`, Dockerfile + compose, CI green | `make e2e` passes against the local container with a stub UI |
| M1 | Bridge on the real Agent SDK; workspace provisioning; `bridge-cli` for terminal-driving it | Real Claude session driven end-to-end from the CLI |
| M2 | Auth: device tokens, pairing, rate limits. First cloud deploy behind TLS | Watch pairs against the deployed bridge over WSS |
| M3 | Wear app: session list, chat, connection service, vibrate on turn (`ClientTransport` + `NotificationTransport` seams in place) | Watch buzzes on a real `result` |
| M4 | AUQ card + permission card + voice reply (in-app and from the shade) | Full scripted E2E green on emulator |
| M5 | Release pipeline: signed APK to Releases, image deploy, matched pair | A merge to `master` ships both halves |
| M6 | Hardening: reconnect/replay, battery pass, multi-session stress, egress review | — |
| P2 | Phone-relay `ClientTransport` and FCM `NotificationTransport` | Battery data from M6 says whether both are needed or just one |

---

## Phase 2 (seamed, not built)

- **FCM `NotificationTransport`.** Lets the watch sleep instead of holding a socket. Needs
  a Firebase project and `google-services.json`, and can't be exercised hermetically — so
  it ships behind the interface, with `SocketNotifications` as the CI-tested default. The
  protocol already carries everything a push payload needs.
- **Phone-relay `ClientTransport`.** Data Layer relay via a companion phone app: better
  battery and off-Wi-Fi reliability, at the cost of a second Android module. Same
  interface, no UI changes.

## Deferred (no seam yet)

- **The `defer` hook decision.** The SDK can defer a pending tool call so the process can
  exit and resume from the persisted session later. Not needed while the bridge is a
  container with `min_machines_running = 1`; becomes relevant if it ever scales to zero.
- **Streaming assistant text**, subagent progress, context-usage display.
- **Multi-user.** Device tokens let you pair several watches to *your* bridge. Real
  accounts would mean rebuilding auth, not extending it.

---

## Open questions

1. **How does the agent get your code?** The one that blocks M1. Three options:
   *(a)* git-clone-per-session with a fine-grained PAT — the plan's default, works on
   ephemeral storage, but every session starts cold and the agent can only touch repos
   the PAT allows; *(b)* a long-lived persistent volume holding checkouts you manage —
   warm and fast, but you're maintaining state on a box you don't shell into often;
   *(c)* the agent works only on repos it clones fresh per task, no persistence at all.
   This shapes `workspace.ts`, the volume layout, and the PAT scope.
2. **Which cloud platform**, if not Fly? The three requirements are persistent volumes,
   no WebSocket idle timeout, and no scale-to-zero. Render and Railway qualify; most
   serverless/edge platforms do not.
3. **Is unrestricted egress acceptable** from the container? The agent wants npm, PyPI,
   and the web; locking it down means an allowlisted proxy and some broken tool calls.
4. Confirm the target device/OS level so `minSdk` and the emulator image are pinned to
   what you actually wear.
