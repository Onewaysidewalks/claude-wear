import { describe, expect, it } from "vitest";
import { parseClientMessage } from "../src/validate.js";

const hello = {
  type: "hello",
  protocolVersion: 1,
  deviceId: "dev_1",
  deviceName: "watch",
  clientVersion: "0.1.0",
};

describe("parseClientMessage", () => {
  it("accepts a well-formed frame", () => {
    const result = parseClientMessage(JSON.stringify(hello));
    expect(result.ok).toBe(true);
  });

  it("rejects frames that are not JSON objects", () => {
    expect(parseClientMessage("not json")).toMatchObject({ ok: false });
    expect(parseClientMessage("[]")).toMatchObject({ ok: false });
    expect(parseClientMessage("42")).toMatchObject({ ok: false });
  });

  it("rejects an unknown message type", () => {
    const result = parseClientMessage(JSON.stringify({ type: "definitelyNot" }));
    expect(result).toMatchObject({ ok: false });
    if (!result.ok) expect(result.reason).toContain("unknown message type");
  });

  it("rejects a missing required field", () => {
    const { deviceName: _dropped, ...missing } = hello;
    const result = parseClientMessage(JSON.stringify(missing));
    expect(result).toMatchObject({ ok: false });
    if (!result.ok) expect(result.reason).toContain("deviceName");
  });

  it("rejects an unexpected field, so a typo is never silently ignored", () => {
    const result = parseClientMessage(JSON.stringify({ ...hello, deviceNam: "typo" }));
    expect(result).toMatchObject({ ok: false });
    if (!result.ok) expect(result.reason).toContain("unexpected property");
  });

  it("rejects a wrong type and says what it wanted", () => {
    const result = parseClientMessage(JSON.stringify({ ...hello, protocolVersion: "1" }));
    expect(result).toMatchObject({ ok: false });
    if (!result.ok) expect(result.reason).toContain("expected integer");
  });

  it("enforces enums through a $ref", () => {
    const ok = parseClientMessage(
      JSON.stringify({ type: "setMode", sessionId: "s_1", mode: "acceptEdits" }),
    );
    expect(ok.ok).toBe(true);
    const bad = parseClientMessage(JSON.stringify({ type: "setMode", sessionId: "s_1", mode: "yolo" }));
    expect(bad).toMatchObject({ ok: false });
  });

  it("allows null where the schema says nullable, and not where it does not", () => {
    expect(parseClientMessage(JSON.stringify({ type: "newSession", cwd: "/tmp", name: null })).ok).toBe(true);
    expect(parseClientMessage(JSON.stringify({ type: "newSession", cwd: null, name: null })).ok).toBe(false);
  });

  it("validates the values of a map-typed field", () => {
    expect(parseClientMessage(JSON.stringify({ type: "subscribe", sinceSeq: { s_1: 4 } })).ok).toBe(true);
    expect(parseClientMessage(JSON.stringify({ type: "subscribe", sinceSeq: { s_1: "4" } })).ok).toBe(false);
  });

  it("validates inside arrays of $ref'd objects", () => {
    const ok = parseClientMessage(
      JSON.stringify({ type: "permission", sessionId: "s", requestId: "r", decision: "allow", message: null }),
    );
    expect(ok.ok).toBe(true);
  });
});
