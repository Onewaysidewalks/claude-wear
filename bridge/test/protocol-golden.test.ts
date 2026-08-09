/**
 * The TypeScript half of the protocol contract. Kotlin's half lives in
 * wear/protocol/src/test — same fixtures, so a field renamed on one side fails CI on the
 * other.
 */
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  CLIENT_MESSAGE_SCHEMAS,
  CLIENT_MESSAGE_TYPES,
  SERVER_EVENT_SCHEMAS,
  SERVER_EVENT_TYPES,
  type JsonSchema,
} from "../src/protocol.js";
import { validateAgainst } from "../src/validate.js";
import { REPO_ROOT } from "./helpers.js";

const GOLDEN_DIR = join(REPO_ROOT, "protocol", "golden");

function goldens(direction: "client" | "server"): { name: string; json: Record<string, unknown> }[] {
  const dir = join(GOLDEN_DIR, direction);
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((file) => ({ name: `${direction}/${file}`, json: JSON.parse(readFileSync(join(dir, file), "utf8")) }));
}

describe.each([
  ["client", CLIENT_MESSAGE_SCHEMAS as Record<string, JsonSchema>],
  ["server", SERVER_EVENT_SCHEMAS as Record<string, JsonSchema>],
] as const)("%s goldens", (direction, schemas) => {
  const fixtures = goldens(direction);

  it("there is at least one fixture per message type", () => {
    const covered = new Set(fixtures.map((f) => f.json.type as string));
    expect([...Object.keys(schemas)].filter((t) => !covered.has(t))).toEqual([]);
  });

  it.each(fixtures.map((f) => [f.name, f] as const))("%s matches its generated schema", (_name, fixture) => {
    const schema = schemas[fixture.json.type as string];
    expect(schema, `no schema for type ${String(fixture.json.type)}`).toBeDefined();
    expect(validateAgainst(schema!, fixture.json)).toEqual([]);
  });

  it.each(fixtures.map((f) => [f.name, f] as const))("%s survives a JSON round trip", (_name, fixture) => {
    expect(JSON.parse(JSON.stringify(fixture.json))).toEqual(fixture.json);
  });
});

describe("the generated type lists", () => {
  it("cover every schema, in both directions", () => {
    expect([...CLIENT_MESSAGE_TYPES].sort()).toEqual(Object.keys(CLIENT_MESSAGE_SCHEMAS).sort());
    expect([...SERVER_EVENT_TYPES].sort()).toEqual(Object.keys(SERVER_EVENT_SCHEMAS).sort());
  });

  it("puts sessionId and seq on every server event", () => {
    for (const [type, schema] of Object.entries(SERVER_EVENT_SCHEMAS)) {
      const required = (schema as { required: string[] }).required;
      expect(required, type).toContain("sessionId");
      expect(required, type).toContain("seq");
    }
  });
});
