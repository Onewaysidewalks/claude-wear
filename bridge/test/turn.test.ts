import { describe, expect, it } from "vitest";
import { deriveTurn, describeToolInput, shorten, shouldAlert, summariseAsk, summarisePermission } from "../src/turn.js";

const base = { started: true, closed: false, failed: false, finished: false, pending: [] };

describe("deriveTurn", () => {
  it("is starting until the agent has reported in", () => {
    expect(deriveTurn({ ...base, started: false }).state).toBe("starting");
  });

  it("is working while the agent runs and nothing is blocked on you", () => {
    expect(deriveTurn(base).state).toBe("working");
  });

  it("is your turn when a result arrives", () => {
    const turn = deriveTurn({ ...base, finished: true });
    expect(turn).toMatchObject({ state: "idle", reason: "result", requestId: null, summary: "Your turn" });
  });

  it("is awaiting you while a question is outstanding", () => {
    const turn = deriveTurn({
      ...base,
      pending: [{ requestId: "req_1", kind: "ask", summary: "Which sections?" }],
    });
    expect(turn).toMatchObject({ state: "awaiting", reason: "ask", requestId: "req_1" });
  });

  it("answers the oldest outstanding request first", () => {
    const turn = deriveTurn({
      ...base,
      pending: [
        { requestId: "req_old", kind: "permission", summary: "may I run npm test?" },
        { requestId: "req_new", kind: "ask", summary: "Which sections?" },
      ],
    });
    expect(turn.requestId).toBe("req_old");
    expect(turn.reason).toBe("permission");
  });

  it("prefers a pending request over a stale result", () => {
    const turn = deriveTurn({
      ...base,
      finished: true,
      pending: [{ requestId: "req_1", kind: "permission", summary: "may I run npm test?" }],
    });
    expect(turn.state).toBe("awaiting");
  });

  it("reports closed and failed sessions distinctly", () => {
    expect(deriveTurn({ ...base, closed: true }).state).toBe("closed");
    expect(deriveTurn({ ...base, failed: true })).toMatchObject({ state: "error", reason: "failed" });
  });
});

describe("shouldAlert", () => {
  it("buzzes for awaiting, idle and error but not for working", () => {
    expect(shouldAlert(deriveTurn({ ...base, finished: true }))).toBe(true);
    expect(shouldAlert(deriveTurn({ ...base, pending: [{ requestId: "r", kind: "ask", summary: "?" }] }))).toBe(true);
    expect(shouldAlert(deriveTurn({ ...base, failed: true }))).toBe(true);
    expect(shouldAlert(deriveTurn(base))).toBe(false);
    expect(shouldAlert(deriveTurn({ ...base, started: false }))).toBe(false);
  });
});

describe("describeToolInput", () => {
  it("shows the actual command, verbatim and untruncated", () => {
    const command = `rm -rf ${"very/deep/path/".repeat(20)}`;
    expect(describeToolInput("Bash", { command })).toBe(command);
  });

  it("shows the path for file tools and the pattern for search tools", () => {
    expect(describeToolInput("Edit", { file_path: "/src/app.ts" })).toBe("/src/app.ts");
    expect(describeToolInput("Grep", { pattern: "TODO" })).toBe("TODO");
  });

  it("falls back to the raw input for tools it does not know", () => {
    expect(describeToolInput("Mystery", { a: 1 })).toBe('{"a":1}');
    expect(describeToolInput("Mystery", {})).toBe("");
  });
});

describe("summaries", () => {
  it("leads a permission with what is actually being asked", () => {
    expect(summarisePermission("Bash", { command: "npm test" })).toBe("may I run npm test?");
    expect(summarisePermission("Write", { file_path: "/a.ts" })).toBe("may I edit /a.ts?");
    expect(summarisePermission("Task", {})).toBe("may I use Task?");
  });

  it("shortens a summary but never the command itself", () => {
    const long = "x".repeat(200);
    expect(summarisePermission("Bash", { command: long }).length).toBeLessThanOrEqual(64);
    expect(describeToolInput("Bash", { command: long })).toHaveLength(200);
  });

  it("counts the questions it is not showing", () => {
    const q = (question: string) => ({ question, header: "H", options: [], multiSelect: false });
    expect(summariseAsk([q("A?")])).toBe("A?");
    expect(summariseAsk([q("A?"), q("B?"), q("C?")])).toBe("A? (+2 more)");
    expect(summariseAsk([])).toBe("a question for you");
  });

  it("collapses whitespace when shortening", () => {
    expect(shorten("a\n  b\tc")).toBe("a b c");
  });
});
