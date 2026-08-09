# protocol/

Single source of truth for the claude-wear wire format. Two languages talk over this
socket; this directory exists so they cannot drift silently.

```
schema/common.schema.json     shared $defs (enums + nested objects)
schema/client/*.schema.json   watch -> bridge, one file per message
schema/server/*.schema.json   bridge -> watch, one file per event
golden/{client,server}/*.json fixtures both sides decode AND re-encode
codegen/                      schema -> TypeScript + Kotlin
```

## Generated files

| Output | Consumer |
| --- | --- |
| `bridge/src/protocol.ts` | TS types, the enum value lists, and the schemas embedded for runtime validation |
| `wear/protocol/src/main/kotlin/dev/claudewear/protocol/Protocol.kt` | kotlinx.serialization `@Serializable` data classes |

```sh
make protocol           # regenerate
make protocol-check     # fail if either output is stale
```

Codegen is a pure function of `schema/**` — same input, byte-identical output. CI
regenerates and then fails if the working tree is dirty.

## Rules the schemas follow

These are enforced by `codegen/schema.mjs`, which throws rather than quietly emitting
something one of the two languages cannot express.

- **`additionalProperties: false`** on every object.
- **No optional fields.** Every property is in `required`; absence is spelled `null` via
  `"type": ["string", "null"]`. Optional-vs-absent is the classic round-trip hazard
  between two serialisers — `kotlinx` omits an absent default, TS omits `undefined`, and
  the golden fixtures stop matching for reasons that have nothing to do with the protocol.
- **`type` is a `const`** and doubles as the polymorphic discriminator. The Kotlin data
  classes do not declare it: `@SerialName` on the class supplies it, and declaring it as
  a field would write it twice.
- **Message names come from `title`**, so the client and server `permission` messages get
  distinct type names (`PermissionDecisionMessage` / `PermissionEvent`).
- **JSON `integer` maps to Kotlin `Long`** everywhere, uniformly.

## `sessionId` on registry events

Every server event carries `sessionId` and a per-session monotonic `seq`. Two events are
not about one chat — `sessions` and connection-scoped `error`s — and they use the reserved
id `@registry`, which has its own `seq` counter. That keeps the watch's gap detection a
single code path (`seq[sessionId]`) instead of a special case.

## Golden fixtures

Each fixture is one complete message. The contract tests on both sides do
**decode -> re-encode -> compare parsed structures** against the original. Structural
comparison rather than string comparison, because key order is not part of the wire
contract — but a renamed, dropped, or added field still fails, on whichever side did not
get the memo.

Kotlin decodes with `ignoreUnknownKeys = false`, so a field added on the TS side alone
fails the Android build rather than being silently discarded.

Add a fixture whenever a message gets a shape the tests do not already cover — a
4-question `multiSelect` ask, a denied `rm -rf`, a free-text answer. They are cheap and
they are the only thing standing between the two languages.
