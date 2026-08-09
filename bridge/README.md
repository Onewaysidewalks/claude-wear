# claude-wear-bridge

Owns N live Claude Agent SDK sessions on a machine that has your code, and speaks the
claude-wear protocol to a watch. Node 22, TypeScript.

```sh
npx claude-wear-bridge --port 8787 --bind tailscale0
```

That is the whole deployment story. `ANTHROPIC_API_KEY` comes from the environment exactly
as it does for the CLI, workspaces are directories that already exist, and transcripts land
in `~/.claude` where the SDK already puts them.

## No API key, no network

```sh
npm run build && FAKE_AGENT=1 node dist/cli.js --inbox
```

`FakeAgentRunner` replays scripted scenarios (see `test/scenarios/`). Its blocking
behaviour is the real thing: an `askUserQuestion` step parks until a decision comes back,
exactly as `canUseTool` parks the agent. Every scenario the watch has to handle — a
4-question `multiSelect`, a denied `rm -rf`, a mid-request disconnect — is a fixture, and
nothing in CI needs a secret.

## Layout

| File | What it owns |
| --- | --- |
| `src/server.ts` | HTTP + `ws`: `/pair`, `/health`, `/debug/inbox`, and the socket |
| `src/sessions.ts` | `SessionRegistry` — create/list/resume, `--max-sessions`, event fan-out |
| `src/session.ts` | one run plus its pending-request map |
| `src/turn.ts` | "is it the user's turn?", as a pure function |
| `src/auth.ts` | pairing codes and device-scoped tokens |
| `src/validate.ts` | rejects a malformed frame against the generated schemas |
| `src/runner/types.ts` | the `AgentRunner` seam |
| `src/runner/fake.ts` | scripted scenarios |
| `src/runner/sdk.ts` | the real `query()` — **M1**, a stub today |
| `src/protocol.ts` | generated from `protocol/schema`; do not edit |

## Flags

`--help` is the reference. The two worth knowing:

- **`--bind`** defaults to `127.0.0.1`. It takes an address or an interface name, and a
  tailnet interface is the recommended target: away-from-home access without a port open
  to your LAN, let alone the internet.
- **`--project-root`** (repeatable) restricts which directories a chat may open. The
  bridge runs shell commands as you, on the machine holding your code; this is the
  cheapest real limit on that.

`--inbox` exposes `GET /debug/inbox`, a recording of everything the watch sent. E2E asserts
against it. It is off by default and should stay that way outside a test run.

## Not here yet

- `runner/sdk.ts` — M1.
- Delta replay from `subscribe.sinceSeq`. Replay today is unconditional and global (a
  snapshot plus every outstanding request across all sessions), which is what the plan
  specifies; the field is carried so M5 can narrow it.
