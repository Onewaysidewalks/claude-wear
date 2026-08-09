/**
 * Loads protocol/schema/** into a small intermediate representation that the TS and
 * Kotlin emitters walk. Deliberately supports only the subset of JSON Schema the wire
 * protocol uses -- anything outside it throws rather than being silently ignored, so a
 * schema change that the generators cannot express fails loudly at codegen time.
 */
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

export const SCHEMA_DIR = new URL("../schema/", import.meta.url).pathname;
export const GOLDEN_DIR = new URL("../golden/", import.meta.url).pathname;

/** Bumped whenever a wire change is not backwards compatible. */
export const PROTOCOL_VERSION = 1;

/** Reserved sessionId for events that belong to the registry rather than one chat. */
export const REGISTRY_SESSION_ID = "@registry";

function listJson(dir) {
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .sort();
}

function fail(where, msg) {
  throw new Error(`${where}: ${msg}`);
}

/**
 * @typedef {{kind: string, nullable: boolean, [k: string]: unknown}} IrType
 */

function parseType(where, node) {
  if (node.$ref) {
    const m = /^common\.schema\.json#\/\$defs\/(\w+)$/.exec(node.$ref);
    if (!m) fail(where, `unsupported $ref ${node.$ref}`);
    return { kind: "ref", name: m[1], nullable: false };
  }
  if (node.const !== undefined) {
    return { kind: "const", value: node.const, nullable: false };
  }

  let types = node.type;
  if (types === undefined) fail(where, "property has neither type, const nor $ref");
  if (!Array.isArray(types)) types = [types];
  const nullable = types.includes("null");
  const rest = types.filter((t) => t !== "null");
  if (rest.length !== 1) fail(where, `expected exactly one non-null type, got ${JSON.stringify(types)}`);
  const type = rest[0];

  switch (type) {
    case "string":
      if (node.enum) return { kind: "inlineEnum", values: node.enum, nullable };
      return { kind: "string", nullable };
    case "integer":
      return { kind: "integer", nullable };
    case "number":
      return { kind: "number", nullable };
    case "boolean":
      return { kind: "boolean", nullable };
    case "array":
      if (!node.items) fail(where, "array without items");
      return { kind: "array", items: parseType(`${where}[]`, node.items), nullable };
    case "object":
      if (node.additionalProperties && typeof node.additionalProperties === "object") {
        return { kind: "map", values: parseType(`${where}{}`, node.additionalProperties), nullable };
      }
      if (node.properties) fail(where, "inline object properties are not supported; use a common $def");
      return { kind: "json", nullable };
    default:
      fail(where, `unsupported type ${type}`);
      return undefined;
  }
}

function parseObject(where, name, node, extra = {}) {
  if (node.additionalProperties !== false) fail(where, "objects must set additionalProperties: false");
  const props = node.properties ?? {};
  const required = new Set(node.required ?? []);
  for (const key of Object.keys(props)) {
    if (!required.has(key)) {
      // Optional-vs-absent is a round-trip hazard across two serialisers. The wire
      // protocol has no optional fields: absence is spelled null and always present.
      fail(where, `property ${key} is not in required; use a nullable type instead of an optional field`);
    }
  }
  const fields = Object.entries(props).map(([key, value]) => ({
    name: key,
    doc: value.description ?? null,
    type: parseType(`${where}.${key}`, value),
  }));
  return { kind: "object", name, doc: node.description ?? null, fields, ...extra };
}

/** @returns {{enums: object[], structs: object[], messages: object[]}} */
export function loadSchemas() {
  const common = JSON.parse(readFileSync(join(SCHEMA_DIR, "common.schema.json"), "utf8"));
  const enums = [];
  const structs = [];

  for (const [name, def] of Object.entries(common.$defs)) {
    const where = `common.schema.json#/$defs/${name}`;
    if (def.type === "string" && def.enum) {
      enums.push({ kind: "enum", name, doc: def.description ?? null, values: def.enum });
    } else if (def.type === "object") {
      structs.push(parseObject(where, name, def));
    } else {
      fail(where, "common $defs must be string enums or objects");
    }
  }

  const messages = [];
  for (const direction of ["client", "server"]) {
    for (const file of listJson(join(SCHEMA_DIR, direction))) {
      const where = `${direction}/${file}`;
      const node = JSON.parse(readFileSync(join(SCHEMA_DIR, direction, file), "utf8"));
      const meta = node["x-message"];
      if (!meta || meta.direction !== direction || !meta.type) {
        fail(where, "missing or mismatched x-message metadata");
      }
      if (!node.title) fail(where, "missing title, which is the generated type name");
      const typeField = node.properties?.type;
      if (typeField?.const !== meta.type) fail(where, "properties.type.const must equal x-message.type");
      messages.push(
        parseObject(where, node.title, node, {
          direction,
          wireType: meta.type,
          file: `${direction}/${file}`,
          schema: node,
        }),
      );
    }
  }

  enums.sort((a, b) => a.name.localeCompare(b.name));
  structs.sort((a, b) => a.name.localeCompare(b.name));
  return { enums, structs, messages };
}

/** Golden fixtures, sorted, with the direction they belong to. */
export function loadGoldens() {
  const out = [];
  for (const direction of ["client", "server"]) {
    for (const file of listJson(join(GOLDEN_DIR, direction))) {
      const path = join(GOLDEN_DIR, direction, file);
      out.push({
        direction,
        name: `${direction}/${file}`,
        path,
        json: JSON.parse(readFileSync(path, "utf8")),
      });
    }
  }
  return out;
}
