import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { REGISTRY_SESSION_ID, type ServerEvent } from "../src/protocol.js";
import { FakeAgentRunner } from "../src/runner/fake.js";
import { SessionRegistry } from "../src/sessions.js";
import { StubRunner } from "./stub-runner.js";

let stateDir: string;
let projectDir: string;

beforeEach(() => {
  stateDir = mkdtempSync(join(tmpdir(), "claude-wear-registry-"));
  projectDir = mkdtempSync(join(tmpdir(), "claude-wear-project-"));
});
afterEach(() => {
  rmSync(stateDir, { recursive: true, force: true });
  rmSync(projectDir, { recursive: true, force: true });
});

function registry(overrides: Partial<ConstructorParameters<typeof SessionRegistry>[0]> = {}) {
  const events: ServerEvent[] = [];
  const reg = new SessionRegistry({
    runner: new FakeAgentRunner({ rotation: ["slow-permission"], timeScale: 0 }),
    maxSessions: 5,
    stateDir,
    defaultMode: "default",
    ...overrides,
  });
  reg.on((e) => events.push(e));
  return { reg, events };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 10));

describe("creating sessions", () => {
  it("names a chat after the cwd basename when you do not", () => {
    const { reg } = registry();
    expect(reg.create(projectDir, null).summary().name).toBe(projectDir.split("/").pop());
    expect(reg.create(projectDir, "  wear app  ").summary().name).toBe("wear app");
  });

  it("refuses a cwd that does not exist or is not a directory", () => {
    const { reg } = registry();
    expect(() => reg.create(join(projectDir, "nope"), null)).toThrow(
      expect.objectContaining({ code: "invalidCwd" }),
    );
  });

  it("refuses a cwd outside the configured project roots", () => {
    const inside = join(projectDir, "repo");
    mkdirSync(inside);
    const { reg } = registry({ allowedRoots: [projectDir] });
    expect(reg.create(inside, null)).toBeDefined();
    expect(() => reg.create(tmpdir(), null)).toThrow(expect.objectContaining({ code: "invalidCwd" }));
  });

  it("rejects past --max-sessions rather than thrashing the host", () => {
    const { reg } = registry({ maxSessions: 2 });
    reg.create(projectDir, "one");
    reg.create(projectDir, "two");
    expect(() => reg.create(projectDir, "three")).toThrow(
      expect.objectContaining({ code: "maxSessions", message: expect.stringContaining("2 sessions already running") }),
    );
    expect(reg.size).toBe(2);
  });

  it("broadcasts a fresh snapshot whenever the set of chats changes", async () => {
    const { reg, events } = registry();
    const session = reg.create(projectDir, "one");
    await reg.close(session.id);
    const snapshots = events.filter((e) => e.type === "sessions");
    expect(snapshots).toHaveLength(2);
    expect(snapshots.map((e) => e.sessionId)).toEqual([REGISTRY_SESSION_ID, REGISTRY_SESSION_ID]);
    expect(snapshots.map((e) => e.seq)).toEqual([1, 2]);
    expect(snapshots.at(-1)!.sessions).toEqual([]);
  });
});

describe("the session list", () => {
  it("sorts awaiting first, then idle, then everything else", async () => {
    const runner = new FakeAgentRunner({ rotation: ["quick-idle", "slow-permission"], timeScale: 0 });
    const { reg } = registry({ runner });
    const idle = reg.create(projectDir, "idle one");
    const awaiting = reg.create(projectDir, "blocked one");
    await settle();

    expect(reg.get(awaiting.id)!.summary().state).toBe("awaiting");
    expect(reg.get(idle.id)!.summary().state).toBe("idle");
    expect(reg.list().map((s) => s.displayName)).toEqual(["blocked one", "idle one"]);
  });

  it("replays the snapshot plus every outstanding request, across all sessions", async () => {
    const { reg } = registry();
    reg.create(projectDir, "one");
    reg.create(projectDir, "two");
    await settle();

    const replay = reg.replay();
    expect(replay[0]!.type).toBe("sessions");
    expect(replay.slice(1).map((e) => e.type)).toEqual(["permission", "permission"]);
    expect(new Set(replay.slice(1).map((e) => e.sessionId)).size).toBe(2);
  });
});

describe("resume across a restart", () => {
  it("persists the agent's session id and passes it back as `resume`", async () => {
    const first = registry();
    const session = first.reg.create(projectDir, "one");
    await settle();
    expect(session.resumeId).toBe(`fake_${session.id}`);

    const onDisk = JSON.parse(readFileSync(join(stateDir, "sessions.json"), "utf8"));
    expect(Object.values(onDisk)[0]).toMatchObject({ cwd: projectDir, agentSessionId: `fake_${session.id}` });

    const stub = new StubRunner();
    const second = registry({ runner: stub });
    second.reg.create(projectDir, "one again");
    expect(stub.options.resume).toBe(`fake_${session.id}`);
  });

  it("does not resume a directory it has never seen", () => {
    const stub = new StubRunner();
    const { reg } = registry({ runner: stub });
    reg.create(projectDir, null);
    expect(stub.options.resume).toBeNull();
  });

  it("resumes the chat you were last talking to in that directory, not the first one ever", async () => {
    const first = registry();
    const older = first.reg.create(projectDir, "older");
    await settle();
    await first.reg.close(older.id);
    const newer = first.reg.create(projectDir, "newer");
    await settle();
    await first.reg.close(newer.id);

    const stub = new StubRunner();
    registry({ runner: stub }).reg.create(projectDir, null);
    expect(stub.options.resume).toBe(`fake_${newer.id}`);
  });

  it("keeps one resume point per directory rather than a row per chat ever opened", async () => {
    const { reg } = registry();
    for (let i = 0; i < 3; i += 1) {
      const session = reg.create(projectDir, `chat ${i}`);
      await settle();
      await reg.close(session.id);
    }
    const onDisk = JSON.parse(readFileSync(join(stateDir, "sessions.json"), "utf8"));
    expect(Object.keys(onDisk)).toHaveLength(1);
  });

  it("will not resume a directory a live session is already holding", async () => {
    const stub = new StubRunner();
    const { reg } = registry({ runner: stub });
    const live = reg.create(projectDir, "live");
    stub.emit({ type: "init", agentSessionId: "agent-live" });
    await settle();
    expect(live.resumeId).toBe("agent-live");

    // Two query() calls resuming one agent session id would write over each other.
    reg.create(projectDir, "second");
    expect(stub.options.resume).toBeNull();
  });

  it("reuses the name you gave a chat when it resumes", async () => {
    const first = registry();
    const session = first.reg.create(projectDir, "the wear app");
    await settle();
    await first.reg.close(session.id);

    const stub = new StubRunner();
    const { reg } = registry({ runner: stub });
    expect(reg.create(projectDir, null).summary().name).toBe("the wear app");
  });

  it("lists what a restart could pick up again, newest first and one per directory", async () => {
    const other = mkdtempSync(join(tmpdir(), "claude-wear-other-"));
    try {
      const { reg } = registry();
      const a = reg.create(projectDir, "a");
      await settle();
      await reg.close(a.id);
      const b = reg.create(other, "b");
      await settle();
      await reg.close(b.id);
      expect(reg.resumable().map((r) => r.cwd)).toEqual([other, projectDir]);
    } finally {
      rmSync(other, { recursive: true, force: true });
    }
  });

  it("survives a sessions.json that has been hand-edited into nonsense", () => {
    writeFileSync(join(stateDir, "sessions.json"), '{"s_1": {"agentSessionId": 7}, "s_2": null}');
    const stub = new StubRunner();
    const { reg } = registry({ runner: stub });
    expect(reg.resumable()).toEqual([]);
    reg.create(projectDir, null);
    expect(stub.options.resume).toBeNull();
  });
});

describe("lookup", () => {
  it("raises a typed error for an unknown session", () => {
    const { reg } = registry();
    expect(() => reg.require("s_nope")).toThrow(expect.objectContaining({ code: "unknownSession" }));
  });
});
