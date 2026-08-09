import { describe, expect, it } from "vitest";
import type { AskEvent, PermissionEvent, PermissionSuggestion, ServerEvent, TurnEvent } from "../src/protocol.js";
import { Session, SessionError } from "../src/session.js";
import { StubRunner } from "./stub-runner.js";

const QUESTIONS = [
  {
    question: "How should I format the output?",
    header: "Format",
    options: [
      { label: "Summary", description: "A few sentences" },
      { label: "Full report", description: null },
    ],
    multiSelect: false,
  },
];

const LOCAL_RULE: PermissionSuggestion = {
  type: "addRules",
  behavior: "allow",
  destination: "localSettings",
  rules: [{ toolName: "Bash", ruleContent: "npm test:*" }],
};
const USER_RULE: PermissionSuggestion = { ...LOCAL_RULE, destination: "userSettings" };

function makeSession() {
  const runner = new StubRunner();
  const events: ServerEvent[] = [];
  const agentSessionIds: string[] = [];
  const session = new Session({
    id: "s_test",
    cwd: "/tmp/project",
    name: "project",
    mode: "default",
    runner,
    scenario: null,
    resume: null,
    emit: (event) => events.push(event),
    onAgentSessionId: (id) => agentSessionIds.push(id),
  });
  const last = <T extends ServerEvent["type"]>(type: T) =>
    [...events].reverse().find((e) => e.type === type) as Extract<ServerEvent, { type: T }> | undefined;
  return { runner, events, session, agentSessionIds, last };
}

describe("seq", () => {
  it("is per session and strictly increasing", () => {
    const { runner, events, session } = makeSession();
    runner.emit({ type: "init", agentSessionId: "sdk_1" });
    runner.emit({ type: "assistant", text: "hi" });
    void session;
    expect(events.map((e) => e.seq)).toEqual([...events.keys()].map((i) => i + 1));
    expect(new Set(events.map((e) => e.sessionId))).toEqual(new Set(["s_test"]));
  });
});

describe("turn events", () => {
  it("goes starting -> working -> idle as the agent reports in", () => {
    const { runner, events } = makeSession();
    runner.emit({ type: "init", agentSessionId: "sdk_1" });
    runner.emit({ type: "assistant", text: "Looking at the repo…" });
    runner.emit({ type: "result", subtype: "success", isError: false, durationMs: 5, numTurns: 1, result: "done" });
    const states = events.filter((e): e is TurnEvent => e.type === "turn").map((e) => e.state);
    expect(states).toEqual(["starting", "working", "idle"]);
  });

  it("does not re-emit an unchanged turn", () => {
    const { runner, events } = makeSession();
    runner.emit({ type: "init", agentSessionId: "sdk_1" });
    runner.emit({ type: "assistant", text: "one" });
    runner.emit({ type: "assistant", text: "two" });
    expect(events.filter((e) => e.type === "turn")).toHaveLength(2); // starting, working
  });

  it("carries the blocking requestId, which is what routes a reply from the shade", () => {
    const { runner, last } = makeSession();
    runner.emit({ type: "init", agentSessionId: "sdk_1" });
    runner.askToUse("Bash", { command: "npm test" });
    const permission = last("permission")!;
    const turn = last("turn")!;
    expect(turn).toMatchObject({ state: "awaiting", reason: "permission", requestId: permission.requestId });
    expect(turn.sessionName).toBe("project");
    expect(turn.summary).toBe("may I run npm test?");
  });

  it("reports the agent failing rather than going quiet", () => {
    const { runner, last } = makeSession();
    runner.emit({ type: "init", agentSessionId: "sdk_1" });
    runner.fail("the CLI subprocess exited");
    expect(last("error")).toMatchObject({ code: "runnerFailed", message: "the CLI subprocess exited" });
    expect(last("turn")).toMatchObject({ state: "error", reason: "failed" });
  });
});

describe("AskUserQuestion", () => {
  it("passes the questions back through alongside the answers", async () => {
    const { runner, session, last } = makeSession();
    const { result } = runner.askToUse("AskUserQuestion", { questions: QUESTIONS });
    const ask = last("ask") as AskEvent;
    expect(ask.questions).toEqual(QUESTIONS);

    session.answer(ask.requestId, { "How should I format the output?": "Summary" }, null, "dev_1");
    await expect(result).resolves.toEqual({
      behavior: "allow",
      updatedInput: { questions: QUESTIONS, answers: { "How should I format the output?": "Summary" } },
    });
  });

  it("carries dictated free text through as the value, not the word Other", async () => {
    const { runner, session, last } = makeSession();
    const { result } = runner.askToUse("AskUserQuestion", { questions: QUESTIONS });
    session.answer(last("ask")!.requestId, { "How should I format the output?": "bullets, no prose" }, null, null);
    const resolved = await result;
    expect(resolved).toMatchObject({
      updatedInput: { answers: { "How should I format the output?": "bullets, no prose" } },
    });
  });

  it("supports dismissing the questions and just talking", async () => {
    const { runner, session, last } = makeSession();
    const { result } = runner.askToUse("AskUserQuestion", { questions: QUESTIONS });
    session.answer(last("ask")!.requestId, null, "forget it, is the build green?", null);
    await expect(result).resolves.toEqual({
      behavior: "allow",
      updatedInput: { questions: QUESTIONS, response: "forget it, is the build green?" },
    });
  });

  it("insists on exactly one of answers and response", () => {
    const { runner, session, last } = makeSession();
    runner.askToUse("AskUserQuestion", { questions: QUESTIONS });
    const requestId = last("ask")!.requestId;
    expect(() => session.answer(requestId, null, null, null)).toThrow(SessionError);
    expect(() => session.answer(requestId, {}, "both", null)).toThrow(SessionError);
  });

  it("renders an AskUserQuestion with no questions array as a permission card", () => {
    const { runner, last } = makeSession();
    runner.askToUse("AskUserQuestion", { nonsense: true });
    expect(last("ask")).toBeUndefined();
    expect(last("permission")).toMatchObject({ tool: "AskUserQuestion" });
  });
});

describe("permission decisions", () => {
  it("allows with the input untouched", async () => {
    const { runner, session, last } = makeSession();
    const input = { command: "npm test", description: "Run the tests" };
    const { result } = runner.askToUse("Bash", input, [LOCAL_RULE]);
    session.decide(last("permission")!.requestId, "allow", null, "dev_1");
    await expect(result).resolves.toEqual({ behavior: "allow", updatedInput: input });
  });

  it("persists only localSettings rules on Always", async () => {
    const { runner, session, last } = makeSession();
    const { result } = runner.askToUse("Bash", { command: "npm test" }, [LOCAL_RULE, USER_RULE]);
    session.decide(last("permission")!.requestId, "allowAlways", null, "dev_1");
    await expect(result).resolves.toMatchObject({ updatedPermissions: [LOCAL_RULE] });
  });

  it("omits updatedPermissions entirely when nothing is persistable", async () => {
    const { runner, session, last } = makeSession();
    const { result } = runner.askToUse("Bash", { command: "npm test" }, [USER_RULE]);
    session.decide(last("permission")!.requestId, "allowAlways", null, null);
    expect(await result).not.toHaveProperty("updatedPermissions");
  });

  it("sends the deny reason to Claude, and has one when the wearer did not dictate", async () => {
    const { runner, session, last } = makeSession();
    const first = runner.askToUse("Bash", { command: "rm -rf /" });
    session.decide(last("permission")!.requestId, "deny", "show me what you'd remove first", null);
    await expect(first.result).resolves.toEqual({
      behavior: "deny",
      message: "show me what you'd remove first",
    });

    const second = runner.askToUse("Bash", { command: "rm -rf /" });
    session.decide(last("permission")!.requestId, "deny", "   ", null);
    await expect(second.result).resolves.toMatchObject({ message: "The user denied this from their watch." });
  });

  it("shows the actual command on the card", () => {
    const { runner, last } = makeSession();
    runner.askToUse("Bash", { command: "rm -rf build/" });
    expect(last("permission") as PermissionEvent).toMatchObject({ display: "rm -rf build/" });
  });
});

describe("stale and mismatched requests", () => {
  it("answering an already-resolved request is a clean error, not a silent no-op", () => {
    const { runner, session, last } = makeSession();
    runner.askToUse("Bash", { command: "npm test" });
    const requestId = last("permission")!.requestId;
    session.decide(requestId, "allow", null, "dev_1");
    expect(() => session.decide(requestId, "allow", null, "dev_2")).toThrow(
      expect.objectContaining({ code: "alreadyResolved" }),
    );
  });

  it("distinguishes a request it never had from one it already answered", () => {
    const { session } = makeSession();
    expect(() => session.decide("req_never", "allow", null, null)).toThrow(
      expect.objectContaining({ code: "unknownRequest" }),
    );
  });

  it("refuses to answer a permission as if it were a question", () => {
    const { runner, session, last } = makeSession();
    runner.askToUse("Bash", { command: "npm test" });
    expect(() => session.answer(last("permission")!.requestId, {}, null, null)).toThrow(SessionError);
  });

  it("broadcasts resolved so other watches can cancel the card", () => {
    const { runner, session, last } = makeSession();
    runner.askToUse("Bash", { command: "npm test" });
    session.decide(last("permission")!.requestId, "allow", null, "dev_1");
    expect(last("resolved")).toMatchObject({ resolution: "allowed", by: "dev_1" });
  });

  it("drops a request the agent itself abandoned", async () => {
    const { runner, session, last } = makeSession();
    const { result, controller } = runner.askToUse("Bash", { command: "npm test" });
    const requestId = last("permission")!.requestId;
    controller.abort();
    await expect(result).resolves.toMatchObject({ behavior: "deny" });
    expect(last("resolved")).toMatchObject({ requestId, resolution: "cancelled" });
    expect(session.summary().pendingRequestIds).toEqual([]);
  });
});

describe("outstanding requests", () => {
  it("survive a disconnect and replay oldest-first", () => {
    const { runner, session } = makeSession();
    runner.askToUse("Bash", { command: "npm test" });
    runner.askToUse("AskUserQuestion", { questions: QUESTIONS });
    const replay = session.outstandingEvents();
    expect(replay.map((e) => e.type)).toEqual(["permission", "ask"]);
    expect(session.summary().pendingRequestIds).toHaveLength(2);
  });

  it("are all released by an interrupt", async () => {
    const { runner, session } = makeSession();
    const a = runner.askToUse("Bash", { command: "one" });
    const b = runner.askToUse("Bash", { command: "two" });
    await session.interrupt();
    await expect(a.result).resolves.toMatchObject({ behavior: "deny" });
    await expect(b.result).resolves.toMatchObject({ behavior: "deny" });
    expect(runner.interrupted).toBe(1);
    expect(session.outstandingEvents()).toEqual([]);
  });

  it("are released when the session closes", async () => {
    const { runner, session } = makeSession();
    const pending = runner.askToUse("Bash", { command: "npm test" });
    await session.close();
    await expect(pending.result).resolves.toMatchObject({ behavior: "deny" });
    expect(session.summary().state).toBe("closed");
  });
});

describe("session controls", () => {
  it("pushes a prompt into the live run and goes back to working", () => {
    const { runner, session, last } = makeSession();
    runner.emit({ type: "init", agentSessionId: "sdk_1" });
    runner.emit({ type: "result", subtype: "success", isError: false, durationMs: 1, numTurns: 1, result: null });
    expect(last("turn")!.state).toBe("idle");
    session.prompt("now run the linter");
    expect(runner.prompts).toEqual(["now run the linter"]);
    expect(last("turn")!.state).toBe("working");
  });

  it("passes a mode change down to the agent", () => {
    const { runner, session } = makeSession();
    session.setMode("acceptEdits");
    expect(runner.modes).toEqual(["acceptEdits"]);
    expect(session.summary().mode).toBe("acceptEdits");
  });

  it("keeps the old mode when the runner refuses the new one", () => {
    const { runner, session } = makeSession();
    runner.acceptsModes = false;
    session.setMode("bypassPermissions");
    // The wrist must not read `bypassPermissions` over an agent that is still asking.
    expect(session.summary().mode).toBe("default");
  });

  it("renames, and the next turn card leads with the new name", () => {
    const { session, last } = makeSession();
    session.rename("wear app");
    expect(session.summary().name).toBe("wear app");
    expect(last("turn")!.sessionName).toBe("wear app");
  });

  it("records the agent's own session id so a restart can resume it", () => {
    const { runner, session, agentSessionIds } = makeSession();
    runner.emit({ type: "init", agentSessionId: "sdk_abc" });
    expect(agentSessionIds).toEqual(["sdk_abc"]);
    expect(session.resumeId).toBe("sdk_abc");
  });
});
