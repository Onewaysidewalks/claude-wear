# claude-wear

Control Claude Agent SDK sessions from a Samsung Galaxy watch: get pinged when it's your
turn, answer by voice, handle multiple concurrent chats and clarifying questions the way
Claude Desktop and the CLI do.

[`PLAN.md`](PLAN.md) is the design. This README is how to run what exists.

```
protocol/   JSON Schema per message + golden fixtures + codegen  → the wire format
bridge/     Node 22 / TypeScript. Owns the agent sessions.
wear/       Android Gradle project. :app (Wear OS) + :protocol
scripts/    e2e.sh
```

## Status: M0

The scaffold. Protocol, codegen, the bridge with a fake agent, a stub watch UI, and the
E2E loop that ties them together.

| | |
| --- | --- |
| **M0** | Monorepo, protocol schema + codegen, `FakeAgentRunner`, CI green — **this** |
| M1 | The bridge on the real Agent SDK; pairing/tokens; a CLI for driving it |
| M2 | Watch: pairing, session list, chat, connection service, vibrate on turn |
| M3 | AskUserQuestion card + permission card + voice reply |
| M4 | Release pipeline: signed APK to Releases |
| M5 | Hardening: reconnect/replay, battery, multi-session stress, tailnet access |

## Running it

Nothing here needs an API key, a secret, a container registry or network access.

```sh
make install      # bridge dependencies
make bridge       # lint, typecheck, test
make dev          # a fully interactive bridge with a fake agent
make e2e          # fake bridge + Wear emulator + the real app
make ci           # everything CI runs except the emulator job
```

`make dev` prints a pairing code and a `ws://` address. `FakeAgentRunner` replays scripted
scenarios (`bridge/test/scenarios/`) whose blocking behaviour is the real thing: an
`askUserQuestion` step parks until you answer, exactly as `canUseTool` parks the agent.

Real Claude sessions arrive in M1 — `bridge/src/runner/sdk.ts` is a stub, and starting the
bridge without `--fake` says so.

## The protocol

`protocol/` is the single source of truth, because two languages talk over this socket and
they must not drift silently. Schemas generate TypeScript types and Kotlin `@Serializable`
data classes; the golden fixtures are decoded **and** re-encoded by tests on both sides. A
field renamed on one side fails CI on the other. See [`protocol/README.md`](protocol/README.md).

```sh
make protocol         # regenerate
make protocol-check   # fail if a generated file is stale
make contract         # the golden fixtures, both sides
```

## CI

Four jobs on every PR and on `main`: `bridge`, `android`, `contract` (regenerate, fail if
the tree is dirty, then golden tests both sides) and `e2e` (a Wear emulator on a hosted
runner). `release.yml` is M4.

## Security posture in one line

The bridge runs arbitrary shell commands as you, on the machine holding your code. It binds
`127.0.0.1` by default, widening it is an explicit flag, a tailnet is the recommended
boundary, and `--project-root` is the cheapest real limit on blast radius. See the plan's
*Security posture* for the rest.
