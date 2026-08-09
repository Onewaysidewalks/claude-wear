import { describe, expect, it } from "vitest";
import type { AskQuestion, ClientMessage, ServerEvent, SessionSummary } from "../src/protocol.js";
import { BridgeCli } from "../src/client/session-cli.js";

/**
 * The terminal client's own logic: what it renders and, more importantly, what it puts on
 * the wire. Option numbering and the answers map are the two places a bug is silent --
 * Claude simply reads the wrong answer -- so they get the most attention here.
 */
function cli() {
  const sent: ClientMessage[] = [];
  const out: string[] = [];
  const client = new BridgeCli({ send: (m) => sent.push(m), write: (l) => out.push(l) });
  return { client, sent, out, text: () => out.join("\n") };
}

function summary(overrides: Partial<SessionSummary> = {}): SessionSummary {
  return {
    sessionId: "s_1",
    name: "claude-wear",
    cwd: "/srv/code/claude-wear",
    state: "working",
    mode: "default",
    seq: 1,
    pendingRequestIds: [],
    createdAt: 0,
    lastActivityAt: 0,
    ...overrides,
  };
}

function sessions(...list: SessionSummary[]): ServerEvent {
  return { type: "sessions", sessionId: "@registry", seq: 1, sessions: list, maxSessions: 5 };
}

const FORMAT: AskQuestion = {
  question: "How should I format the output?",
  header: "Format",
  options: [
    { label: "Summary", description: "A few sentences" },
    { label: "Full report", description: null },
  ],
  multiSelect: false,
};
const SECTIONS: AskQuestion = {
  question: "Which sections?",
  header: "Sections",
  options: [
    { label: "Introduction", description: null },
    { label: "Methods", description: null },
    { label: "Conclusion", description: null },
  ],
  multiSelect: true,
};

function ask(questions: AskQuestion[], sessionId = "s_1"): ServerEvent {
  return { type: "ask", sessionId, seq: 2, requestId: "req_1", questions };
}

function permission(sessionId = "s_1", localRule = true): ServerEvent {
  return {
    type: "permission",
    sessionId,
    seq: 2,
    requestId: "req_p",
    tool: "Bash",
    input: { command: "npm test" },
    display: "npm test",
    suggestions: localRule
      ? [{ type: "addRules", behavior: "allow", destination: "localSettings", rules: [] }]
      : [],
  };
}

describe("session tracking", () => {
  it("lands on a chat by itself, so the first thing you type can be a prompt", () => {
    const { client, sent } = cli();
    client.handle(sessions(summary()));
    expect(client.currentSessionId).toBe("s_1");
    client.command("have a look at the tests");
    expect(sent).toEqual([{ type: "prompt", sessionId: "s_1", text: "have a look at the tests" }]);
  });

  it("refuses to guess when there is no chat at all", () => {
    const { client, sent, text } = cli();
    client.command("hello?");
    expect(sent).toEqual([]);
    expect(text()).toMatch(/no chat selected/);
  });

  it("switches by number or by id, and says so when neither matches", () => {
    const { client, text } = cli();
    client.handle(sessions(summary(), summary({ sessionId: "s_2", name: "other" })));
    client.command("/use 2");
    expect(client.currentSessionId).toBe("s_2");
    client.command("/use s_1");
    expect(client.currentSessionId).toBe("s_1");
    client.command("/use 9");
    expect(client.currentSessionId).toBe("s_1");
    expect(text()).toMatch(/there is no chat 9/);
  });

  it("moves off a chat that has gone away", () => {
    const { client } = cli();
    client.handle(sessions(summary(), summary({ sessionId: "s_2" })));
    client.command("/use 2");
    client.handle(sessions(summary()));
    expect(client.currentSessionId).toBe("s_1");
  });

  it("sends the rest of the surface for the current chat", () => {
    const { client, sent } = cli();
    client.handle(sessions(summary()));
    client.command("/new /srv/code/thing my thing");
    client.command("/rename wear app");
    client.command("/mode acceptEdits");
    client.command("/interrupt");
    client.command("/resync");
    expect(sent).toEqual([
      { type: "newSession", cwd: "/srv/code/thing", name: "my thing" },
      { type: "renameSession", sessionId: "s_1", name: "wear app" },
      { type: "setMode", sessionId: "s_1", mode: "acceptEdits" },
      { type: "interrupt", sessionId: "s_1" },
      { type: "subscribe", sinceSeq: null },
    ]);
  });

  it("rejects a mode the protocol does not have", () => {
    const { client, sent, text } = cli();
    client.handle(sessions(summary()));
    client.command("/mode yolo");
    expect(sent).toEqual([]);
    expect(text()).toMatch(/wants one of/);
  });
});

describe("answering a question", () => {
  it("numbers options across every question, so /3 is never ambiguous", () => {
    const { client, sent, text } = cli();
    client.handle(sessions(summary()));
    client.handle(ask([FORMAT, SECTIONS]));
    expect(text()).toContain("/1 Summary");
    expect(text()).toContain("/2 Full report");
    expect(text()).toContain("/3 Introduction");
    expect(text()).toContain("/5 Conclusion");

    client.command("/1,3,5");
    expect(sent).toEqual([
      {
        type: "answer",
        sessionId: "s_1",
        requestId: "req_1",
        answers: {
          "How should I format the output?": "Summary",
          // multiSelect labels join with ", " — the SDK's contract, not ours.
          "Which sections?": "Introduction, Conclusion",
        },
        response: null,
      },
    ]);
  });

  it("will not send a half-answered request", () => {
    const { client, sent, text } = cli();
    client.handle(sessions(summary()));
    client.handle(ask([FORMAT, SECTIONS]));
    client.command("/1");
    expect(sent).toEqual([]);
    expect(text()).toMatch(/still unanswered: Sections/);
  });

  it("refuses two answers to a single-select question", () => {
    const { client, sent, text } = cli();
    client.handle(sessions(summary()));
    client.handle(ask([FORMAT]));
    client.command("/1,2");
    expect(sent).toEqual([]);
    expect(text()).toMatch(/only takes one answer/);
  });

  it("rejects an option number that is not there", () => {
    const { client, sent, text } = cli();
    client.handle(sessions(summary()));
    client.handle(ask([FORMAT]));
    client.command("/7");
    expect(sent).toEqual([]);
    expect(text()).toMatch(/there is no option 7/);
  });

  it("puts dictated free text in as the value, not the word Other", () => {
    const { client, sent } = cli();
    client.handle(sessions(summary()));
    client.handle(ask([FORMAT]));
    client.command("/other as a bulleted list please");
    expect(sent).toEqual([
      {
        type: "answer",
        sessionId: "s_1",
        requestId: "req_1",
        answers: { "How should I format the output?": "as a bulleted list please" },
        response: null,
      },
    ]);
  });

  it("dismisses the questions and just talks with /say", () => {
    const { client, sent } = cli();
    client.handle(sessions(summary()));
    client.handle(ask([FORMAT]));
    client.command("/say forget the report, fix the test first");
    expect(sent).toEqual([
      {
        type: "answer",
        sessionId: "s_1",
        requestId: "req_1",
        answers: null,
        response: "forget the report, fix the test first",
      },
    ]);
  });

  it("says which kind of answer the waiting request actually wants", () => {
    const { client, sent, text } = cli();
    client.handle(sessions(summary()));
    client.handle(ask([FORMAT]));
    client.command("/y");
    expect(sent).toEqual([]);
    expect(text()).toMatch(/is a question/);
  });
});

describe("answering a permission", () => {
  it("shows the actual command, never a summary of it", () => {
    const { client, text } = cli();
    client.handle(sessions(summary()));
    client.handle(permission());
    expect(text()).toContain("npm test");
  });

  it("offers /always only when there is a localSettings rule to persist", () => {
    const withRule = cli();
    withRule.client.handle(sessions(summary()));
    withRule.client.handle(permission("s_1", true));
    expect(withRule.text()).toMatch(/\/always/);

    const without = cli();
    without.client.handle(sessions(summary()));
    without.client.handle(permission("s_1", false));
    expect(without.text()).not.toMatch(/\/always/);
  });

  it("sends allow, allowAlways and deny with its reason", () => {
    for (const [command, expected] of [
      ["/y", { decision: "allow", message: null }],
      ["/always", { decision: "allowAlways", message: null }],
      ["/n not on main", { decision: "deny", message: "not on main" }],
      ["/n", { decision: "deny", message: null }],
    ] as const) {
      const { client, sent } = cli();
      client.handle(sessions(summary()));
      client.handle(permission());
      client.command(command);
      expect(sent).toEqual([{ type: "permission", sessionId: "s_1", requestId: "req_p", ...expected }]);
    }
  });

  it("tells you when nothing is waiting rather than sending a stray frame", () => {
    const { client, sent, text } = cli();
    client.handle(sessions(summary()));
    client.command("/y");
    expect(sent).toEqual([]);
    expect(text()).toMatch(/nothing is waiting on you/);
  });
});

describe("more than one chat waiting", () => {
  it("answers the current chat's request even when another chat asked first", () => {
    const { client, sent } = cli();
    client.handle(sessions(summary(), summary({ sessionId: "s_2", name: "other" })));
    client.handle({ ...permission("s_2"), requestId: "req_other" } as ServerEvent);
    client.handle({ ...permission("s_1"), requestId: "req_mine" } as ServerEvent);
    client.command("/use 1");
    client.command("/y");
    expect(sent.at(-1)).toMatchObject({ sessionId: "s_1", requestId: "req_mine" });
  });

  it("falls back to whoever asked first when the current chat wants nothing", () => {
    const { client, sent } = cli();
    client.handle(sessions(summary(), summary({ sessionId: "s_2", name: "other" })));
    client.handle({ ...permission("s_2"), requestId: "req_other" } as ServerEvent);
    client.command("/use 1");
    client.command("/y");
    expect(sent.at(-1)).toMatchObject({ sessionId: "s_2", requestId: "req_other" });
  });

  it("leads every line with the chat's name, which is the only way to tell them apart", () => {
    const { client, text } = cli();
    client.handle(sessions(summary({ name: "claude-wear" }), summary({ sessionId: "s_2", name: "other thing" })));
    client.handle({ type: "text", sessionId: "s_2", seq: 3, text: "hello from the other one" });
    expect(text()).toContain("[other thing] hello from the other one");
  });

  it("forgets a request once something else resolves it", () => {
    const { client, sent, text } = cli();
    client.handle(sessions(summary()));
    client.handle(permission());
    expect(client.pendingCount).toBe(1);
    client.handle({ type: "resolved", sessionId: "s_1", seq: 3, requestId: "req_p", resolution: "allowed", by: "dev_x" });
    expect(client.pendingCount).toBe(0);
    client.command("/y");
    expect(sent).toEqual([]);
    expect(text()).toMatch(/nothing is waiting on you/);
  });

  it("drops pending requests belonging to a chat that has closed", () => {
    const { client } = cli();
    client.handle(sessions(summary(), summary({ sessionId: "s_2" })));
    client.handle(permission("s_2"));
    expect(client.pendingCount).toBe(1);
    client.handle(sessions(summary()));
    expect(client.pendingCount).toBe(0);
  });
});

describe("rendering", () => {
  it("prints the result and hands the turn back", () => {
    const { client, text } = cli();
    client.handle(sessions(summary()));
    client.handle({
      type: "done",
      sessionId: "s_1",
      seq: 4,
      subtype: "success",
      isError: false,
      durationMs: 4200,
      numTurns: 3,
      result: "Tests pass.",
    });
    expect(text()).toContain("Tests pass.");
    expect(text()).toContain("your turn");
  });

  it("names a chat from the turn event, which can outrun the snapshot", () => {
    const { client, text } = cli();
    client.handle({
      type: "turn",
      sessionId: "s_9",
      seq: 1,
      state: "starting",
      reason: "started",
      requestId: null,
      sessionName: "the wear app",
      summary: "starting",
    });
    expect(text()).toContain("[the wear app]");
    expect(text()).not.toContain("[s_9]");
  });

  it("keeps /sessions current from turn events, which arrive between snapshots", () => {
    const { client, text } = cli();
    client.handle(sessions(summary({ state: "starting" })));
    client.handle({
      type: "turn",
      sessionId: "s_1",
      seq: 6,
      state: "idle",
      reason: "result",
      requestId: null,
      sessionName: "claude-wear",
      summary: "Your turn",
    });
    client.command("/sessions");
    expect(text()).toContain("idle/default");
    expect(text()).not.toContain("starting/default");
  });

  it("stays quiet on an awaiting turn, because the ask itself says it better", () => {
    const { client, out } = cli();
    client.handle(sessions(summary()));
    const before = out.length;
    client.handle({
      type: "turn",
      sessionId: "s_1",
      seq: 5,
      state: "awaiting",
      reason: "permission",
      requestId: "req_p",
      sessionName: "claude-wear",
      summary: "may I run npm test?",
    });
    expect(out.length).toBe(before);
  });

  it("shows errors from the bridge", () => {
    const { client, text } = cli();
    client.handle({ type: "error", sessionId: "@registry", seq: 1, code: "maxSessions", message: "too many", requestId: null });
    expect(text()).toContain("maxSessions: too many");
  });

  it("has help, and complains about a command it does not know", () => {
    const { client, text } = cli();
    client.command("/help");
    expect(text()).toContain("/interrupt");
    client.command("/nope");
    expect(text()).toMatch(/unknown command \/nope/);
  });

  it("ignores an empty line rather than sending an empty prompt", () => {
    const { client, sent } = cli();
    client.handle(sessions(summary()));
    client.command("   ");
    expect(sent).toEqual([]);
  });
});
