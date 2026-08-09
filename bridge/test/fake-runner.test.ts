import { describe, expect, it } from "vitest";
import type { PermissionResult, RunnerMessage, RunnerOptions, ToolPermissionRequest } from "../src/runner/types.js";
import { FakeAgentRunner, loadScenarios, parseScenario, DEFAULT_SCENARIO_DIR } from "../src/runner/fake.js";

interface Harness {
  messages: RunnerMessage[];
  errors: Error[];
  requests: ToolPermissionRequest[];
  answer(index: number, result: PermissionResult): void;
  options: RunnerOptions;
}

function harness(scenario: string): Harness & { runner: FakeAgentRunner } {
  const messages: RunnerMessage[] = [];
  const errors: Error[] = [];
  const requests: ToolPermissionRequest[] = [];
  const resolvers: ((result: PermissionResult) => void)[] = [];
  const options: RunnerOptions = {
    sessionId: "s_1",
    cwd: "/tmp",
    resume: null,
    permissionMode: "default",
    scenario,
    canUseTool: (request) => {
      requests.push(request);
      return new Promise<PermissionResult>((resolve) => resolvers.push(resolve));
    },
    onMessage: (m) => messages.push(m),
    onError: (e) => errors.push(e),
  };
  const runner = new FakeAgentRunner({ rotation: [scenario], timeScale: 0 });
  return {
    runner,
    messages,
    errors,
    requests,
    options,
    answer: (index, result) => resolvers[index]!(result),
  };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 5));

describe("scenario files", () => {
  it("all parse", () => {
    const scenarios = loadScenarios(DEFAULT_SCENARIO_DIR);
    expect(scenarios.size).toBeGreaterThanOrEqual(6);
    expect([...scenarios.keys()]).toContain("auq-then-bash");
  });

  it("reject a step with no action, or two", () => {
    expect(() => parseScenario("x", '[{"at": 0}]')).toThrow(/exactly one/);
    expect(() => parseScenario("x", '[{"at": 0, "awaitPrompt": true, "expectAnswer": true}]')).toThrow(/exactly one/);
  });

  it("reject a step with no delay, and a file that is not an array", () => {
    expect(() => parseScenario("x", '[{"emit": {"type": "assistant", "text": "hi"}}]')).toThrow(/at/);
    expect(() => parseScenario("x", '{"steps": []}')).toThrow(/array/);
    expect(() => parseScenario("x", "nope")).toThrow(/valid JSON/);
  });

  it("refuses to start with a scenario it does not have", () => {
    expect(() => new FakeAgentRunner({ rotation: ["nope"] })).toThrow(/unknown scenario/);
  });
});

describe("auq-then-bash", () => {
  it("blocks on the question, then on the Bash permission, then finishes", async () => {
    const h = harness("auq-then-bash");
    h.runner.start(h.options);
    await settle();

    expect(h.messages[0]).toMatchObject({ type: "init" });
    expect(h.messages[1]).toMatchObject({ type: "assistant", text: "Looking at the repo…" });
    expect(h.requests).toHaveLength(1);
    expect(h.requests[0]!.toolName).toBe("AskUserQuestion");

    // The script is genuinely parked: nothing else happens until we answer.
    await settle();
    expect(h.requests).toHaveLength(1);

    h.answer(0, { behavior: "allow", updatedInput: {} });
    await settle();
    expect(h.requests).toHaveLength(2);
    expect(h.requests[1]).toMatchObject({ toolName: "Bash", input: { command: "npm test" } });
    expect(h.requests[1]!.suggestions[0]).toMatchObject({ destination: "localSettings" });

    h.answer(1, { behavior: "allow", updatedInput: {} });
    await settle();
    expect(h.messages.at(-1)).toMatchObject({ type: "result", subtype: "success", isError: false });
    expect(h.errors).toEqual([]);
  });

  it("fails the session when expectAnswer does not match", async () => {
    const h = harness("auq-then-bash");
    h.runner.start(h.options);
    await settle();
    h.answer(0, { behavior: "deny", message: "no" });
    await settle();
    expect(h.errors).toHaveLength(1);
    expect(h.errors[0]!.message).toMatch(/expected the last decision to be allow/);
  });
});

describe("denied-rm-rf", () => {
  it("expects the deny and then adapts", async () => {
    const h = harness("denied-rm-rf");
    h.runner.start(h.options);
    await settle();
    expect(h.requests[0]).toMatchObject({ input: { command: "rm -rf build/" } });
    h.answer(0, { behavior: "deny", message: "show me first" });
    await settle();
    expect(h.errors).toEqual([]);
    expect(h.messages.map((m) => m.type)).toContain("result");
  });
});

describe("multi-turn", () => {
  it("waits for a dictated prompt between results", async () => {
    const h = harness("multi-turn");
    const handle = h.runner.start(h.options);
    await settle();
    expect(h.messages.filter((m) => m.type === "result")).toHaveLength(1);

    handle.push("now run the linter");
    await settle();
    expect(h.messages.filter((m) => m.type === "result")).toHaveLength(2);
    expect(h.messages.at(-1)).toMatchObject({ result: "Done — pushed to the branch." });
  });

  it("stays usable once the script runs out", async () => {
    const h = harness("multi-turn");
    const handle = h.runner.start(h.options);
    await settle();
    handle.push("one");
    await settle();
    handle.push("two");
    await settle();
    const before = h.messages.length;

    handle.push("and another");
    await settle();
    expect(h.messages.length).toBeGreaterThan(before);
    expect(h.messages.at(-1)).toMatchObject({ type: "result", result: "scenario exhausted" });
  });
});

describe("interrupt", () => {
  it("ends the run with an error result and stops the script", async () => {
    const h = harness("slow-permission");
    const handle = h.runner.start(h.options);
    await settle();
    expect(h.requests).toHaveLength(1);

    await handle.interrupt();
    await settle();
    expect(h.messages.at(-1)).toMatchObject({ type: "result", subtype: "error_during_execution", isError: true });
    expect(h.errors).toEqual([]);
  });
});

describe("rotation", () => {
  it("hands successive sessions different scenarios, cycling", async () => {
    const runner = new FakeAgentRunner({ rotation: ["quick-idle", "slow-permission"], timeScale: 0 });
    const seen: string[] = [];
    const options = (sessionId: string): RunnerOptions => ({
      sessionId,
      cwd: "/tmp",
      resume: null,
      permissionMode: "default",
      scenario: null,
      canUseTool: () => new Promise<PermissionResult>(() => {}),
      onMessage: (m) => {
        if (m.type === "assistant") seen.push(m.text);
      },
      onError: () => {},
    });
    runner.start(options("s_1"));
    runner.start(options("s_2"));
    runner.start(options("s_3"));
    await settle();
    expect(seen.filter((t) => t.startsWith("Deploy script"))).toHaveLength(1);
    expect(seen.filter((t) => t.startsWith("Reading the changed files"))).toHaveLength(2);
  });
});
