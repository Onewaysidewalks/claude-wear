import type { PermissionUpdate } from "@anthropic-ai/claude-agent-sdk";
import { describe, expect, it } from "vitest";
import type { PermissionSuggestion } from "../src/protocol.js";
import { SdkAgentRunner, toWireSuggestion } from "../src/runner/sdk.js";
import type { PermissionResult, RunnerMessage, RunnerOptions } from "../src/runner/types.js";
import { assistantMessage, fakeQueryFn, initMessage, resultMessage } from "./fake-query.js";

/**
 * The real runner, driven by a fake `query()`. Everything here is the mapping layer that
 * only exists in sdk.ts -- SDK message shapes, the canUseTool round-trip, resume -- and all
 * of it runs with no API key and no network, which is the constraint M0 set and M1 keeps.
 */
function start(overrides: Partial<RunnerOptions> = {}) {
  const { fn, calls } = fakeQueryFn();
  const messages: RunnerMessage[] = [];
  const errors: Error[] = [];
  let answer: (result: PermissionResult) => void = () => {};

  const runner = new SdkAgentRunner({ query: fn });
  const handle = runner.start({
    sessionId: "s_test",
    cwd: "/tmp/project",
    resume: null,
    permissionMode: "default",
    scenario: null,
    canUseTool: () => new Promise<PermissionResult>((resolve) => (answer = resolve)),
    onMessage: (message) => messages.push(message),
    onError: (error) => errors.push(error),
    ...overrides,
  });

  return { handle, calls, messages, errors, answerWith: (r: PermissionResult) => answer(r) };
}

/** The handle spins the agent loop up asynchronously, exactly as the SDK does. */
async function settle(): Promise<void> {
  for (let i = 0; i < 5; i += 1) await new Promise((resolve) => setTimeout(resolve, 0));
}

describe("query options", () => {
  it("drives the session with a streaming input iterable, not a one-shot prompt", async () => {
    const { handle, calls } = start();
    await settle();
    expect(calls).toHaveLength(1);

    handle.push("first");
    handle.push("second");
    await calls[0]!.query.waitForInput(2);
    expect(calls[0]!.query.received).toEqual(["first", "second"]);
  });

  it("passes cwd, permission mode and the resume id through", async () => {
    const { calls } = start({ cwd: "/srv/code", permissionMode: "acceptEdits", resume: "agent-123" });
    await settle();
    expect(calls[0]!.options).toMatchObject({
      cwd: "/srv/code",
      permissionMode: "acceptEdits",
      resume: "agent-123",
    });
  });

  it("never asks the SDK for bypassPermissions unless the bridge was started for it", async () => {
    const { calls } = start({ permissionMode: "bypassPermissions" });
    await settle();
    expect(calls[0]!.options.permissionMode).toBe("default");
    expect(calls[0]!.options.allowDangerouslySkipPermissions).toBeUndefined();
  });

  it("opts in properly when it was", async () => {
    const { fn, calls } = fakeQueryFn();
    const runner = new SdkAgentRunner({ query: fn, allowBypassPermissions: true });
    runner.start({
      sessionId: "s",
      cwd: "/tmp",
      resume: null,
      permissionMode: "bypassPermissions",
      scenario: null,
      canUseTool: async () => ({ behavior: "deny", message: "no" }),
      onMessage: () => {},
      onError: () => {},
    });
    await settle();
    expect(calls[0]!.options).toMatchObject({
      permissionMode: "bypassPermissions",
      allowDangerouslySkipPermissions: true,
    });
  });
});

describe("message mapping", () => {
  it("takes the agent session id off the init system message", async () => {
    const { calls, messages } = start();
    await settle();
    calls[0]!.query.emit(initMessage("agent-abc"));
    await settle();
    expect(messages).toContainEqual({ type: "init", agentSessionId: "agent-abc" });
  });

  it("sends assistant text and drops thinking and tool_use blocks", async () => {
    const { calls, messages } = start();
    await settle();
    calls[0]!.query.emit(
      assistantMessage([
        { type: "thinking", text: "hmm" },
        { type: "text", text: "Looking at the repo" },
        { type: "tool_use" },
        { type: "text", text: "and the tests" },
      ]),
    );
    await settle();
    expect(messages).toContainEqual({ type: "assistant", text: "Looking at the repo\nand the tests" });
  });

  it("says nothing at all for a tool-only assistant message", async () => {
    const { calls, messages } = start();
    await settle();
    calls[0]!.query.emit(assistantMessage([{ type: "tool_use" }]));
    await settle();
    expect(messages.filter((m) => m.type === "assistant")).toEqual([]);
  });

  it("keeps subagent chatter off the wrist", async () => {
    const { calls, messages } = start();
    await settle();
    calls[0]!.query.emit(assistantMessage([{ type: "text", text: "subagent noise" }], { parent_tool_use_id: "t1" }));
    await settle();
    expect(messages.filter((m) => m.type === "assistant")).toEqual([]);
  });

  it("maps a successful result", async () => {
    const { calls, messages } = start();
    await settle();
    calls[0]!.query.emit(resultMessage());
    await settle();
    expect(messages).toContainEqual({
      type: "result",
      subtype: "success",
      isError: false,
      durationMs: 1234,
      numTurns: 2,
      result: "all done",
    });
  });

  it("clamps result subtypes the wire protocol does not carry", async () => {
    const { calls, messages } = start();
    await settle();
    calls[0]!.query.emit(resultMessage({ subtype: "error_max_budget_usd", is_error: true, result: undefined }));
    await settle();
    expect(messages).toContainEqual({
      type: "result",
      subtype: "error_during_execution",
      isError: true,
      durationMs: 1234,
      numTurns: 2,
      result: null,
    });
  });

  it("keeps error_max_turns, which the protocol does carry", async () => {
    const { calls, messages } = start();
    await settle();
    calls[0]!.query.emit(resultMessage({ subtype: "error_max_turns", is_error: true, result: undefined }));
    await settle();
    expect(messages.find((m) => m.type === "result")).toMatchObject({ subtype: "error_max_turns" });
  });

  it("reports a dead agent loop as a runner error", async () => {
    const { calls, errors } = start();
    await settle();
    calls[0]!.query.fail(new Error("the CLI subprocess exited"));
    await settle();
    expect(errors.map((e) => e.message)).toEqual(["the CLI subprocess exited"]);
  });
});

describe("canUseTool", () => {
  const addRule: PermissionUpdate = {
    type: "addRules",
    rules: [{ toolName: "Bash", ruleContent: "npm test" }],
    behavior: "allow",
    destination: "localSettings",
  };
  const userRule: PermissionUpdate = {
    type: "addRules",
    rules: [{ toolName: "Bash", ruleContent: "npm test" }],
    behavior: "allow",
    destination: "userSettings",
  };

  it("hands the bridge the tool, the input and the suggestions in wire shape", async () => {
    const { fn, calls } = fakeQueryFn();
    let seen: { toolName: string; suggestions: PermissionSuggestion[] } | null = null;
    const runner = new SdkAgentRunner({ query: fn });
    runner.start({
      sessionId: "s",
      cwd: "/tmp",
      resume: null,
      permissionMode: "default",
      scenario: null,
      canUseTool: async (request) => {
        seen = { toolName: request.toolName, suggestions: request.suggestions };
        return { behavior: "deny", message: "not today" };
      },
      onMessage: () => {},
      onError: () => {},
    });
    await settle();

    const decision = await calls[0]!.query.askToUse("Bash", { command: "npm test" }, [addRule]);
    expect(seen).toEqual({
      toolName: "Bash",
      suggestions: [
        {
          type: "addRules",
          behavior: "allow",
          destination: "localSettings",
          rules: [{ toolName: "Bash", ruleContent: "npm test" }],
        },
      ],
    });
    expect(decision).toEqual({ behavior: "deny", message: "not today" });
  });

  it("blocks the agent until the answer comes back", async () => {
    const { calls, answerWith } = start();
    await settle();
    const pending = calls[0]!.query.askToUse("Bash", { command: "rm -rf /" });

    let settled = false;
    void pending.then(() => (settled = true));
    await settle();
    expect(settled).toBe(false);

    answerWith({ behavior: "allow", updatedInput: { command: "rm -rf /" } });
    await expect(pending).resolves.toEqual({ behavior: "allow", updatedInput: { command: "rm -rf /" } });
  });

  it("echoes back the SDK's own suggestion objects, not a reconstruction of them", async () => {
    const { calls, answerWith } = start();
    await settle();
    const pending = calls[0]!.query.askToUse("Bash", { command: "npm test" }, [addRule, userRule]);
    await settle();

    // What the bridge sends back is the wire shape, filtered to localSettings by Session.
    answerWith({
      behavior: "allow",
      updatedInput: { command: "npm test" },
      updatedPermissions: [toWireSuggestion(addRule)],
    });

    const decision = await pending;
    expect(decision).toEqual({
      behavior: "allow",
      updatedInput: { command: "npm test" },
      updatedPermissions: [addRule],
    });
    // Identity, not just equality: a rule that persists to a settings file must be the
    // SDK's own object rather than something rebuilt from a lossy wire shape.
    expect((decision as { updatedPermissions: PermissionUpdate[] }).updatedPermissions[0]).toBe(addRule);
  });

  it("omits updatedPermissions entirely when nothing was taken", async () => {
    const { calls, answerWith } = start();
    await settle();
    const pending = calls[0]!.query.askToUse("Bash", { command: "npm test" }, [addRule]);
    await settle();
    answerWith({ behavior: "allow", updatedInput: { command: "npm test" } });
    expect(await pending).toEqual({ behavior: "allow", updatedInput: { command: "npm test" } });
  });

  it("survives a suggestion type with no rules of its own", () => {
    expect(toWireSuggestion({ type: "setMode", mode: "acceptEdits", destination: "session" })).toEqual({
      type: "setMode",
      behavior: null,
      destination: "session",
      rules: [],
    });
  });
});

describe("control methods", () => {
  it("interrupts through the query", async () => {
    const { handle, calls } = start();
    await settle();
    await handle.interrupt();
    expect(calls[0]!.query.interrupts).toBe(1);
  });

  it("does not surface a rejected interrupt; Session has already denied the pending requests", async () => {
    const { handle, calls, errors } = start();
    await settle();
    // Interrupting a session that is not mid-turn is what the SDK rejects.
    calls[0]!.query.interruptError = new Error("no query is running");
    await expect(handle.interrupt()).resolves.toBeUndefined();
    expect(errors).toEqual([]);
  });

  it("changes the permission mode on the live session", async () => {
    const { handle, calls } = start();
    await settle();
    handle.setPermissionMode("acceptEdits");
    await settle();
    expect(calls[0]!.query.modes).toEqual(["acceptEdits"]);
  });

  it("tells the watch when the agent refuses a mode change", async () => {
    const { handle, calls, errors } = start();
    await settle();
    calls[0]!.query.setModeError = new Error("plan mode is not available here");
    handle.setPermissionMode("plan");
    await settle();
    expect(errors[0]?.message).toBe("could not switch to plan: plan mode is not available here");
  });

  it("refuses bypassPermissions with an error the watch can render", async () => {
    const { handle, calls, errors } = start();
    await settle();
    handle.setPermissionMode("bypassPermissions");
    await settle();
    expect(calls[0]!.query.modes).toEqual([]);
    expect(errors[0]?.message).toMatch(/--allow-bypass-permissions/);
  });

  it("closes the query", async () => {
    const { handle, calls } = start();
    await settle();
    await handle.close();
    expect(calls[0]!.query.closes).toBe(1);
  });

  it("does not report an error for a stream that ended because we closed it", async () => {
    const { handle, calls, errors } = start();
    await settle();
    await handle.close();
    calls[0]!.query.fail(new Error("closed under us"));
    await settle();
    expect(errors).toEqual([]);
  });
});

describe("resume across a bridge restart", () => {
  it("starts fresh, and says so, when the transcript to resume has gone", async () => {
    const { fn, calls } = fakeQueryFn();
    const messages: RunnerMessage[] = [];
    const errors: Error[] = [];
    const runner = new SdkAgentRunner({ query: fn });
    const handle = runner.start({
      sessionId: "s",
      cwd: "/tmp",
      resume: "agent-gone",
      permissionMode: "default",
      scenario: null,
      canUseTool: async () => ({ behavior: "deny", message: "no" }),
      onMessage: (m) => messages.push(m),
      onError: (e) => errors.push(e),
    });
    await settle();

    handle.push("carry on where we left off");
    await calls[0]!.query.waitForInput(1);
    calls[0]!.query.fail(new Error("No conversation found with session ID: agent-gone"));
    await settle();

    expect(calls).toHaveLength(2);
    expect(calls[0]!.options.resume).toBe("agent-gone");
    expect(calls[1]!.options.resume).toBeUndefined();
    expect(errors).toEqual([]);
    expect(messages).toContainEqual({
      type: "assistant",
      text: "Could not resume the previous conversation, so this chat is starting fresh.",
    });

    // The prompt handed to the dead attempt is not lost on the floor.
    await calls[1]!.query.waitForInput(1);
    expect(calls[1]!.query.received).toEqual(["carry on where we left off"]);

    // Everything pushed after the retry goes to the live attempt, not the dead one.
    handle.push("and another thing");
    await calls[1]!.query.waitForInput(2);
    expect(calls[1]!.query.received).toEqual(["carry on where we left off", "and another thing"]);
    expect(calls[0]!.query.received).toEqual(["carry on where we left off"]);
  });

  it("does not retry a session that had already got going", async () => {
    const { fn, calls } = fakeQueryFn();
    const errors: Error[] = [];
    const runner = new SdkAgentRunner({ query: fn });
    runner.start({
      sessionId: "s",
      cwd: "/tmp",
      resume: "agent-live",
      permissionMode: "default",
      scenario: null,
      canUseTool: async () => ({ behavior: "deny", message: "no" }),
      onMessage: () => {},
      onError: (e) => errors.push(e),
    });
    await settle();
    calls[0]!.query.emit(initMessage("agent-live"));
    await settle();
    calls[0]!.query.fail(new Error("network blew up mid-turn"));
    await settle();

    expect(calls).toHaveLength(1);
    expect(errors.map((e) => e.message)).toEqual(["network blew up mid-turn"]);
  });

  it("does not retry when there was nothing to resume in the first place", async () => {
    const { calls, errors } = start();
    await settle();
    calls[0]!.query.fail(new Error("could not spawn the CLI"));
    await settle();
    expect(calls).toHaveLength(1);
    expect(errors.map((e) => e.message)).toEqual(["could not spawn the CLI"]);
  });

  it("re-reads the agent session id on every init, since a resume may hand back a new one", async () => {
    const { calls, messages } = start({ resume: "agent-old" });
    await settle();
    calls[0]!.query.emit(initMessage("agent-new"));
    await settle();
    expect(messages).toContainEqual({ type: "init", agentSessionId: "agent-new" });
  });
});

describe("prepare", () => {
  it("refuses to start before the SDK has been resolved", () => {
    const runner = new SdkAgentRunner();
    expect(() =>
      runner.start({
        sessionId: "s",
        cwd: "/tmp",
        resume: null,
        permissionMode: "default",
        scenario: null,
        canUseTool: async () => ({ behavior: "deny", message: "no" }),
        onMessage: () => {},
        onError: () => {},
      }),
    ).toThrow(/prepare\(\)/);
  });
});
