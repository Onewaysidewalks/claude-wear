/**
 * A JSON Schema validator for exactly the subset protocol/schema uses, so the bridge can
 * reject a malformed frame without pulling in a general-purpose validator. The schemas
 * themselves are generated into protocol.ts, so this never reads the repo at runtime and
 * a published tarball validates against the schemas it shipped with.
 *
 * protocol/codegen/schema.mjs throws on anything outside this subset, which is what keeps
 * the two in step.
 */
import { CLIENT_MESSAGE_SCHEMAS, COMMON_SCHEMA, type ClientMessage, type JsonSchema } from "./protocol.js";

export interface ValidationFailure {
  path: string;
  message: string;
}

const COMMON_DEFS = (COMMON_SCHEMA as { $defs: Record<string, JsonSchema> }).$defs;

function resolve(schema: JsonSchema): JsonSchema {
  const ref = schema.$ref;
  if (typeof ref !== "string") return schema;
  const name = ref.replace("common.schema.json#/$defs/", "");
  const target = COMMON_DEFS[name];
  if (!target) throw new Error(`unresolvable $ref ${ref}`);
  return target;
}

function typeOf(value: unknown): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  if (Number.isInteger(value)) return "integer";
  return typeof value;
}

function matchesType(declared: string, actual: string): boolean {
  return declared === actual || (declared === "number" && actual === "integer");
}

function check(schema: JsonSchema, value: unknown, path: string, out: ValidationFailure[]): void {
  const s = resolve(schema);

  if (s.const !== undefined) {
    if (value !== s.const) out.push({ path, message: `expected ${JSON.stringify(s.const)}` });
    return;
  }

  const actual = typeOf(value);
  if (s.type !== undefined) {
    const declared = Array.isArray(s.type) ? (s.type as string[]) : [s.type as string];
    if (!declared.some((d) => matchesType(d, actual))) {
      out.push({ path, message: `expected ${declared.join(" or ")}, got ${actual}` });
      return;
    }
    if (actual === "null") return;
  }

  if (Array.isArray(s.enum) && !s.enum.includes(value as never)) {
    out.push({ path, message: `expected one of ${s.enum.map((e) => JSON.stringify(e)).join(", ")}` });
    return;
  }

  if (actual === "object") {
    const obj = value as Record<string, unknown>;
    const properties = (s.properties ?? {}) as Record<string, JsonSchema>;
    for (const key of (s.required ?? []) as string[]) {
      if (!(key in obj)) out.push({ path: `${path}.${key}`, message: "required" });
    }
    for (const [key, child] of Object.entries(obj)) {
      const propSchema = properties[key];
      if (propSchema) {
        check(propSchema, child, `${path}.${key}`, out);
      } else if (s.additionalProperties === false) {
        out.push({ path: `${path}.${key}`, message: "unexpected property" });
      } else if (typeof s.additionalProperties === "object" && s.additionalProperties !== null) {
        check(s.additionalProperties as JsonSchema, child, `${path}.${key}`, out);
      }
    }
  }

  if (actual === "array" && s.items) {
    (value as unknown[]).forEach((item, i) => check(s.items as JsonSchema, item, `${path}[${i}]`, out));
  }
}

/** Validate an arbitrary value against one of the generated schemas. */
export function validateAgainst(schema: JsonSchema, value: unknown): ValidationFailure[] {
  const out: ValidationFailure[] = [];
  check(schema, value, "$", out);
  return out;
}

export type ParseResult =
  | { ok: true; message: ClientMessage }
  | { ok: false; reason: string };

/** Parse and validate one frame from a watch. */
export function parseClientMessage(raw: string): ParseResult {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return { ok: false, reason: "frame is not valid JSON" };
  }
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return { ok: false, reason: "frame is not a JSON object" };
  }
  const type = (value as { type?: unknown }).type;
  if (typeof type !== "string") return { ok: false, reason: "frame has no string `type`" };

  const schema = (CLIENT_MESSAGE_SCHEMAS as Record<string, JsonSchema | undefined>)[type];
  if (!schema) return { ok: false, reason: `unknown message type \`${type}\`` };

  const failures = validateAgainst(schema, value);
  if (failures.length > 0) {
    return { ok: false, reason: failures.map((f) => `${f.path}: ${f.message}`).join("; ") };
  }
  return { ok: true, message: value as ClientMessage };
}
