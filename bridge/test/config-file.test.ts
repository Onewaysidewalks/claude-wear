import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { ConfigError, expandHome, loadConfig, parseConfigFile } from "../src/config.js";

/**
 * Which project roots the bridge will open is the cheapest real limit on blast radius, and
 * the plan wants it to be a config file rather than a code change. So the file is treated
 * as load-bearing: a typo in it is an error, not a shrug.
 */
let stateDir: string;

beforeEach(() => {
  stateDir = mkdtempSync(join(tmpdir(), "claude-wear-config-"));
  delete process.env.FAKE_AGENT;
});
afterEach(() => {
  rmSync(stateDir, { recursive: true, force: true });
});

function writeConfig(body: unknown, name = "config.json"): string {
  const path = join(stateDir, name);
  writeFileSync(path, typeof body === "string" ? body : JSON.stringify(body));
  return path;
}

describe("finding the file", () => {
  it("reads <state-dir>/config.json without being asked", () => {
    writeConfig({ port: 9999, projectRoots: ["/srv/code"] });
    const config = loadConfig(["--state-dir", stateDir]);
    expect(config.port).toBe(9999);
    expect(config.allowedRoots).toEqual(["/srv/code"]);
    expect(config.configPath).toBe(join(stateDir, "config.json"));
  });

  it("treats a missing default file as an ordinary first run", () => {
    const config = loadConfig(["--state-dir", stateDir]);
    expect(config.configPath).toBeNull();
    expect(config.allowedRoots).toEqual([]);
    expect(config.port).toBe(8787);
  });

  it("fails loudly when an explicitly named file is not there", () => {
    expect(() => loadConfig(["--state-dir", stateDir, "--config", join(stateDir, "nope.json")])).toThrow(
      /could not read/,
    );
  });

  it("takes --config over the default location", () => {
    writeConfig({ port: 1111 });
    const other = writeConfig({ port: 2222 }, "other.json");
    expect(loadConfig(["--state-dir", stateDir, "--config", other]).port).toBe(2222);
  });
});

describe("precedence", () => {
  it("lets a flag win over the file, key by key", () => {
    writeConfig({ port: 9999, maxSessions: 9, permissionMode: "acceptEdits", projectRoots: ["/srv/code"] });
    const config = loadConfig(["--state-dir", stateDir, "--port", "7000", "--project-root", "/srv/other"]);
    expect(config.port).toBe(7000);
    expect(config.allowedRoots).toEqual(["/srv/other"]);
    // Untouched by flags, so the file still applies.
    expect(config.maxSessions).toBe(9);
    expect(config.defaultMode).toBe("acceptEdits");
  });

  it("replaces the roots rather than merging them, so --project-root is a real override", () => {
    writeConfig({ projectRoots: ["/srv/a", "/srv/b"] });
    expect(loadConfig(["--state-dir", stateDir, "--project-root", "/srv/c"]).allowedRoots).toEqual(["/srv/c"]);
  });

  it("resolves a bind interface named in the file", () => {
    writeConfig({ bind: "lo" });
    expect(["127.0.0.1", "::1"]).toContain(loadConfig(["--state-dir", stateDir]).bind);
  });
});

describe("validation", () => {
  it("rejects an unknown key rather than silently ignoring it", () => {
    writeConfig({ projectRoot: ["/srv/code"] });
    expect(() => loadConfig(["--state-dir", stateDir])).toThrow(/unknown key `projectRoot`/);
  });

  it("rejects a relative project root", () => {
    writeConfig({ projectRoots: ["code/thing"] });
    expect(() => loadConfig(["--state-dir", stateDir])).toThrow(/absolute path/);
  });

  it("rejects nonsense in every typed field", () => {
    expect(() => parseConfigFile("c", "{")).toThrow(/not valid JSON/);
    expect(() => parseConfigFile("c", "[]")).toThrow(/must contain a JSON object/);
    expect(() => parseConfigFile("c", '{"port": -1}')).toThrow(/non-negative integer/);
    expect(() => parseConfigFile("c", '{"bind": 80}')).toThrow(/bind must be a string/);
    expect(() => parseConfigFile("c", '{"permissionMode": "yolo"}')).toThrow(/must be one of/);
    expect(() => parseConfigFile("c", '{"projectRoots": "/srv"}')).toThrow(/array of absolute paths/);
    expect(() => parseConfigFile("c", '{"allowBypassPermissions": "yes"}')).toThrow(/true or false/);
  });

  it("is a ConfigError, so the CLI exits 2 with the message instead of a stack trace", () => {
    writeConfig({ nope: true });
    expect(() => loadConfig(["--state-dir", stateDir])).toThrow(ConfigError);
  });
});

describe("home expansion", () => {
  it("expands a leading ~ so a hand-edited file can say what it means", () => {
    expect(expandHome("~/code/thing")).toBe(join(homedir(), "code/thing"));
    expect(expandHome("~")).toBe(homedir());
    expect(expandHome("/srv/code")).toBe("/srv/code");
    // Only a leading ~/, never a ~ in the middle of a name.
    expect(expandHome("/srv/~backup")).toBe("/srv/~backup");
  });

  it("expands it in projectRoots too", () => {
    writeConfig({ projectRoots: ["~/code/thing"] });
    expect(loadConfig(["--state-dir", stateDir]).allowedRoots).toEqual([join(homedir(), "code/thing")]);
  });
});

describe("bypassPermissions", () => {
  it("is off unless the file or a flag says otherwise", () => {
    expect(loadConfig(["--state-dir", stateDir]).allowBypassPermissions).toBe(false);
    writeConfig({ allowBypassPermissions: true });
    expect(loadConfig(["--state-dir", stateDir]).allowBypassPermissions).toBe(true);
    rmSync(join(stateDir, "config.json"));
    expect(loadConfig(["--state-dir", stateDir, "--allow-bypass-permissions"]).allowBypassPermissions).toBe(true);
  });
});
