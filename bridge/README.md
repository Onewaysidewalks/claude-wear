# claude-wear-bridge

Owns N live Claude Agent SDK sessions on a machine that has your code, and speaks the
claude-wear protocol to a watch. Node 22, TypeScript.

```sh
npx claude-wear-bridge --port 8787 --bind tailscale0
```

That is the whole deployment story. `ANTHROPIC_API_KEY` comes from the environment exactly
as it does for the CLI, workspaces are directories that already exist, and transcripts land
in `~/.claude` where the SDK already puts them.

## Driving it from a terminal

`claude-wear-cli` is a terminal client that speaks **the same wire protocol as the watch** —
same `hello`, same `subscribe`, same `answer` and `permission` frames. It exists so a real
Claude session can be driven end to end without an emulator, and so AskUserQuestion and
permission flows can be exercised by hand.

```sh
# one terminal
npx claude-wear-bridge --inbox
#   pairing code: 46341878

# another
npx claude-wear-cli --pair 46341878 --new ~/code/thing
```

```
» /new ~/code/thing the wear app
[the wear app] Looking at the repo…

[the wear app] ? Claude is asking
  Format How should I format the output?
    /1 Summary — A few sentences
    /2 Full report — Every section, with detail
» /1

[the wear app] ? Bash
    npm test
  /y to allow, /n [reason] to deny, /always to allow and remember
» /y
[the wear app] ✓ success — Tests pass. (1 turns, 2.2s)
  your turn
```

`/help` lists the rest: `/sessions`, `/use`, `/rename`, `/mode`, `/interrupt`, `/pending`,
`/other <text>` for a free-text answer, `/say <text>` to dismiss the questions and just
talk. The token is saved per bridge URL in `<state-dir>/cli-tokens.json`, so you pair once.

Piped stdin works too — commands are held until the socket is open — which makes it
scriptable, not only interactive.

## No API key, no network

```sh
npm run build && FAKE_AGENT=1 node dist/cli.js --inbox
```

`FakeAgentRunner` replays scripted scenarios (see `test/scenarios/`). Its blocking
behaviour is the real thing: an `askUserQuestion` step parks until a decision comes back,
exactly as `canUseTool` parks the agent. Every scenario the watch has to handle — a
4-question `multiSelect`, a denied `rm -rf`, a mid-request disconnect — is a fixture, and
nothing in CI needs a secret.

The real runner is tested the same way: `test/fake-query.ts` stands in for the SDK's
`query()`, so message mapping, the `canUseTool` round-trip and resume are all covered
without a key. See *What a keyed run still has to prove* below for what that cannot reach.

## Configuration

Flags are the whole surface for a one-off run. Anything you do not want to retype lives in
`~/.claude-wear/config.json`, next to `devices.json` and `sessions.json`:

```json
{
  "port": 8787,
  "bind": "tailscale0",
  "maxSessions": 5,
  "permissionMode": "default",
  "projectRoots": ["~/code/claude-wear", "~/code/other-thing"],
  "allowBypassPermissions": false
}
```

Flags win over the file, key by key, so the file is a default and never a cage. An unknown
key is an error rather than a shrug — a mistyped `projectRoot` that silently opens your
whole home directory is exactly what this file exists to prevent. `--config <path>` reads
somewhere else; naming a file that is not there is an error, while the default one simply
not existing is an ordinary first run.

The three flags worth knowing:

- **`--bind`** defaults to `127.0.0.1`. It takes an address or an interface name, and a
  tailnet interface is the recommended target: away-from-home access without a port open
  to your LAN, let alone the internet. The bridge terminates no TLS of its own — the
  tailnet is the encrypted transport and the auth boundary.
- **`projectRoots`** / **`--project-root`** restricts which directories a chat may open.
  The bridge runs shell commands as you, on the machine holding your code; this is the
  cheapest real limit on that. **Empty means any existing directory**, and the bridge says
  so at startup. The roots also travel in every `sessions` snapshot, so the watch's New
  chat screen is a list of taps rather than a path you have to type on a 1.5" screen —
  which makes configuring them a usability win as well as a safety one.
- **`--allow-bypass-permissions`** is off by default. In `bypassPermissions` the agent
  stops asking, so the watch stops buzzing; the SDK demands an explicit opt-in for it and
  so does the bridge. Without the flag, a watch asking for that mode gets a clean error
  and the chat keeps the mode it had — a wrist that reads `bypassPermissions` over an
  agent that is still asking would be worse than the error.

`--inbox` exposes `GET /debug/inbox`, a recording of everything the watch sent. E2E asserts
against it. It is off by default and should stay that way outside a test run.

## Resume across a restart

There is no store to design: the SDK already writes transcripts to
`~/.claude/projects/<encoded-cwd>/*.jsonl`. All the bridge keeps is
`~/.claude-wear/sessions.json`, mapping a directory to the agent session id it was last
talking to, so opening a chat in that directory again continues it with `resume: <id>`
rather than starting cold. It prints what is resumable at startup.

Chats are **not** re-spawned automatically on boot — N sessions is N subprocesses and N
token spends, and starting them for chats nobody asked for is the wrong default. Resume
happens when you open the directory again.

Three rules make it behave with a real SDK session behind it:

- Most recent wins. After several chats in one directory you get the one you were last
  talking to.
- One resume point per directory, so `sessions.json` stays a lookup rather than a log.
- A directory a *live* session already holds is not resumable — two `query()` calls
  resuming one agent session id would write over each other's transcript.

If the transcript has gone (a state file that outlived its `~/.claude` entry) the runner
starts fresh and says so in the chat, rather than leaving a session that can never be
talked to.

## Layout

| File | What it owns |
| --- | --- |
| `src/cli.ts` | the bridge entry point |
| `src/bridge-cli.ts` | the terminal client entry point: pairing, socket, readline |
| `src/client/session-cli.ts` | what the terminal client renders and puts on the wire |
| `src/server.ts` | HTTP + `ws`: `/pair`, `/health`, `/debug/inbox`, and the socket |
| `src/config.ts` | flags, and the config file underneath them |
| `src/sessions.ts` | `SessionRegistry` — create/list/resume, `--max-sessions`, event fan-out |
| `src/session.ts` | one run plus its pending-request map |
| `src/turn.ts` | "is it the user's turn?", as a pure function |
| `src/auth.ts` | pairing codes and device-scoped tokens |
| `src/validate.ts` | rejects a malformed frame against the generated schemas |
| `src/runner/types.ts` | the `AgentRunner` seam |
| `src/runner/fake.ts` | scripted scenarios |
| `src/runner/sdk.ts` | the real `query()` |
| `src/protocol.ts` | generated from `protocol/schema`; do not edit |

## The real runner

`src/runner/sdk.ts` drives `query()` with a **streaming input AsyncIterable** rather than a
one-shot string prompt. That is what lets a follow-up message be pushed into a live session,
and it is what keeps `canUseTool` — and so the whole watch interaction — reachable at all;
the control methods only exist in streaming mode too.

| Need | API |
| --- | --- |
| Push a follow-up user message | the streaming-input iterable |
| Stop a runaway agent from the watch | `q.interrupt()` |
| Capture the session id for restart-resume | `session_id` on the init `system` message |
| Loosen permissions mid-session | `q.setPermissionMode(mode)` |
| Clean shutdown | `q.close()` |

Two mappings worth knowing about:

- The SDK's result subtypes are a superset of the three the wire protocol carries.
  `error_max_budget_usd` and anything added later are reported as
  `error_during_execution`; widening the protocol enum is a change to a contract shared
  with Kotlin and 31 golden fixtures, so it is not done casually.
- "Always allow" echoes back **the SDK's own suggestion objects**, matched by value to
  what the watch sent, rather than reconstructing them from the wire shape. Those rules get
  written to a real settings file, and a lossy round-trip there is the kind of bug you find
  months later.

Subagent chatter (`parent_tool_use_id !== null`), partial deltas, tool progress and hook
events are all received and deliberately not rendered.

## What a keyed run still has to prove

Everything above is covered without a secret, and CI stays green with none. Two things only
a run with real credentials can confirm:

- that a live `query()` emits its init `system` message before the first user message is
  pushed (the bridge shows the chat as `starting` until then either way), and
- that `resume: <id>` against a real `~/.claude` transcript continues the conversation
  rather than forking it.

## Not here yet

- Delta replay from `subscribe.sinceSeq`. Replay today is unconditional and global (a
  snapshot plus every outstanding request across all sessions), which is what the plan
  specifies; the field is carried so M5 can narrow it.
