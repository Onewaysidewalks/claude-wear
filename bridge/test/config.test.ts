import { homedir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { ConfigError, parseArgs, resolveBind } from "../src/config.js";

const originalEnv = { ...process.env };
afterEach(() => {
  process.env = { ...originalEnv };
});

describe("defaults", () => {
  it("binds loopback, caps sessions at five, and never bypasses permissions", () => {
    delete process.env.FAKE_AGENT;
    delete process.env.CLAUDE_WEAR_STATE_DIR;
    expect(parseArgs([])).toMatchObject({
      port: 8787,
      bind: "127.0.0.1",
      maxSessions: 5,
      defaultMode: "default",
      fake: false,
      inbox: false,
      pair: true,
      allowedRoots: [],
      stateDir: join(homedir(), ".claude-wear"),
    });
  });

  it("takes the fake runner from the environment as well as the flag", () => {
    process.env.FAKE_AGENT = "1";
    expect(parseArgs([]).fake).toBe(true);
    delete process.env.FAKE_AGENT;
    expect(parseArgs(["--fake"]).fake).toBe(true);
  });
});

describe("flags", () => {
  it("parses the whole surface", () => {
    const config = parseArgs([
      "--port", "9000",
      "--bind", "0.0.0.0",
      "--max-sessions", "2",
      "--state-dir", "/tmp/state",
      "--permission-mode", "acceptEdits",
      "--project-root", "/srv/code",
      "--project-root", "/srv/other",
      "--fake",
      "--scenarios", "quick-idle, auq-then-bash ,",
      "--time-scale", "0",
      "--inbox",
      "--no-pair",
    ]);
    expect(config).toMatchObject({
      port: 9000,
      bind: "0.0.0.0",
      maxSessions: 2,
      stateDir: "/tmp/state",
      defaultMode: "acceptEdits",
      allowedRoots: ["/srv/code", "/srv/other"],
      scenarios: ["quick-idle", "auq-then-bash"],
      timeScale: 0,
      inbox: true,
      pair: false,
    });
  });

  it("rejects nonsense rather than starting with it", () => {
    expect(() => parseArgs(["--nope"])).toThrow(ConfigError);
    expect(() => parseArgs(["--port"])).toThrow(/needs a value/);
    expect(() => parseArgs(["--port", "-1"])).toThrow(/non-negative integer/);
    expect(() => parseArgs(["--permission-mode", "yolo"])).toThrow(/must be one of/);
    expect(() => parseArgs(["--project-root", "relative/path"])).toThrow(/absolute/);
    expect(() => parseArgs(["--time-scale", "-2"])).toThrow(/non-negative/);
  });

  it("says so when --scenarios is passed without a fake agent", () => {
    delete process.env.FAKE_AGENT;
    expect(() => parseArgs(["--scenarios", "quick-idle"])).toThrow(/only means anything with --fake/);
  });
});

describe("resolveBind", () => {
  it("passes addresses through untouched", () => {
    expect(resolveBind("127.0.0.1")).toBe("127.0.0.1");
    expect(resolveBind("0.0.0.0")).toBe("0.0.0.0");
    expect(resolveBind("::1")).toBe("::1");
  });

  it("resolves an interface name, which is how --bind tailscale0 works", () => {
    // Whichever family the loopback lists first; the point is that a name resolves at all.
    expect(["127.0.0.1", "::1"]).toContain(resolveBind("lo"));
  });

  it("fails loudly for an interface that is not there", () => {
    expect(() => resolveBind("tailscale0-not-here")).toThrow(/no address found/);
  });
});
