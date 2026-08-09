import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { AuthStore, PAIRING_CODE_TTL_MS } from "../src/auth.js";

let dir: string;
let now: number;
const clock = () => now;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "claude-wear-auth-"));
  now = 1_700_000_000_000;
});
afterEach(() => rmSync(dir, { recursive: true, force: true }));

describe("pairing", () => {
  it("exchanges a code for a token that then verifies", () => {
    const auth = new AuthStore(dir, clock);
    const code = auth.issuePairingCode();
    expect(code).toMatch(/^\d{8}$/);

    const paired = auth.pair(code, "Galaxy Watch");
    expect(paired).not.toBeNull();
    expect(auth.verify(paired!.token)?.deviceId).toBe(paired!.deviceId);
  });

  it("rejects the wrong code", () => {
    const auth = new AuthStore(dir, clock);
    auth.issuePairingCode();
    expect(auth.pair("00000000".slice(0, 8), "watch") ?? auth.pair("12345678", "watch")).toBeNull();
  });

  it("burns the code after one use", () => {
    const auth = new AuthStore(dir, clock);
    const code = auth.issuePairingCode();
    expect(auth.pair(code, "first")).not.toBeNull();
    expect(auth.pair(code, "second")).toBeNull();
  });

  it("expires the code after five minutes", () => {
    const auth = new AuthStore(dir, clock);
    const code = auth.issuePairingCode();
    now += PAIRING_CODE_TTL_MS + 1;
    expect(auth.pair(code, "watch")).toBeNull();
  });

  it("refuses to pair when no code is outstanding", () => {
    expect(new AuthStore(dir, clock).pair("12345678", "watch")).toBeNull();
  });
});

describe("tokens", () => {
  it("rejects an unknown, empty or absent token", () => {
    const auth = new AuthStore(dir, clock);
    expect(auth.verify("nope")).toBeNull();
    expect(auth.verify("")).toBeNull();
    expect(auth.verify(null)).toBeNull();
  });

  it("stores only the hash, never the token", () => {
    const auth = new AuthStore(dir, clock);
    const code = auth.issuePairingCode();
    const paired = auth.pair(code, "watch")!;
    const onDisk = readFileSync(join(dir, "devices.json"), "utf8");
    expect(onDisk).not.toContain(paired.token);
    expect(JSON.parse(onDisk)[0].tokenHash).toMatch(/^[0-9a-f]{64}$/);
  });

  it("survives a restart, and revokes one device without touching the others", () => {
    const first = new AuthStore(dir, clock);
    const a = first.pair(first.issuePairingCode(), "watch a")!;
    const b = first.pair(first.issuePairingCode(), "watch b")!;

    const reloaded = new AuthStore(dir, clock);
    expect(reloaded.verify(a.token)).not.toBeNull();
    expect(reloaded.revoke(a.deviceId)).toBe(true);
    expect(reloaded.verify(a.token)).toBeNull();
    expect(reloaded.verify(b.token)).not.toBeNull();
    expect(reloaded.revoke("dev_nothere")).toBe(false);
  });
});
