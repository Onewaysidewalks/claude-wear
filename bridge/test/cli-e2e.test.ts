import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { WebSocket } from "ws";
import { BridgeCli } from "../src/client/session-cli.js";
import { PROTOCOL_VERSION, type ClientMessage, type ServerEvent } from "../src/protocol.js";
import { SdkAgentRunner } from "../src/runner/sdk.js";
import { assistantMessage, fakeQueryFn, initMessage, resultMessage } from "./fake-query.js";
import { startTestBridge, type TestBridge } from "./helpers.js";

/**
 * M1's done-when, minus the API key: a chat driven end to end from the terminal, over a
 * real WebSocket, through the real pairing flow, against the real `SdkAgentRunner`. Only
 * `query()` itself is a stand-in, so everything between a typed line and the SDK's own
 * callback is the code that ships.
 */
let bridge: TestBridge;
let projectDir: string;

beforeEach(() => {
  projectDir = mkdtempSync(join(tmpdir(), "claude-wear-cli-"));
});
afterEach(async () => {
  await bridge?.stop();
  rmSync(projectDir, { recursive: true, force: true });
});

/** A CLI wired to a real socket, exactly as `bridge-cli.ts` wires it. */
async function connectCli(target: TestBridge, token: string) {
  const out: string[] = [];
  const socket = new WebSocket(`${target.url.replace("http", "ws")}/ws`, {
    headers: { authorization: `Bearer ${token}` },
  });
  await new Promise<void>((resolve, reject) => {
    socket.once("open", () => resolve());
    socket.once("error", reject);
  });

  const send = (message: ClientMessage) => socket.send(JSON.stringify(message));
  const client = new BridgeCli({ send, write: (line) => out.push(line) });
  socket.on("message", (data) => client.handle(JSON.parse(data.toString()) as ServerEvent));

  send({
    type: "hello",
    protocolVersion: PROTOCOL_VERSION,
    deviceId: "cli",
    deviceName: "cli",
    clientVersion: "test",
  });
  send({ type: "subscribe", sinceSeq: null });

  const until = async (predicate: () => boolean, what: string, timeoutMs = 4000) => {
    const deadline = Date.now() + timeoutMs;
    while (!predicate()) {
      if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}; saw:\n${out.join("\n")}`);
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
  };

  return {
    client,
    out,
    text: () => out.join("\n"),
    until,
    saw: (needle: string) => until(() => out.join("\n").includes(needle), needle),
    close: () =>
      new Promise<void>((resolve) => {
        socket.once("close", () => resolve());
        socket.close();
      }),
  };
}

describe("driving a session from the terminal", () => {
  it("pairs, opens a chat, answers a question, approves a command and reads the result", async () => {
    bridge = await startTestBridge({ scenarios: ["auq-then-bash"], timeScale: 0 });
    const { token } = await bridge.pair("cli");
    const cli = await connectCli(bridge, token);

    cli.client.command(`/new ${projectDir} the wear app`);
    await cli.saw("Looking at the repo");

    // The AUQ card, rendered as numbered options.
    await cli.saw("How should I format the output?");
    expect(cli.text()).toContain("/1 Summary");
    cli.client.command("/1");

    // The scenario's `expectAnswer` step fails the run if the answer did not come back
    // as an allow, so reaching the Bash prompt at all proves the AUQ round-trip.
    await cli.saw("npm test");
    cli.client.command("/y");

    await cli.saw("Tests pass.");
    expect(cli.text()).toContain("your turn");

    // And the bridge saw the frames a watch would have sent.
    const inbound = bridge.inbox.all().filter((e) => e.direction === "in");
    expect(inbound.map((e) => e.type)).toEqual([
      "hello",
      "subscribe",
      "newSession",
      "answer",
      "permission",
    ]);

    await cli.close();
  });

  it("denies a command with a reason Claude can read", async () => {
    bridge = await startTestBridge({ scenarios: ["denied-rm-rf"], timeScale: 0 });
    const { token } = await bridge.pair("cli");
    const cli = await connectCli(bridge, token);

    cli.client.command(`/new ${projectDir}`);
    await cli.saw("rm -rf build/");
    cli.client.command("/n not on my machine");
    // The scenario's `expectAnswer: deny` step fails the run if the decision arrives as
    // anything else, so reaching the next line at all is part of the assertion.
    await cli.saw("leaving it alone");

    const sent = bridge.inbox.received("permission")[0]!;
    expect(sent.payload).toMatchObject({ decision: "deny", message: "not on my machine" });
    await cli.close();
  });

  it("keeps two waiting chats apart, and answers the one you picked", async () => {
    bridge = await startTestBridge({ scenarios: ["slow-permission", "slow-permission"], timeScale: 0 });
    const { token } = await bridge.pair("cli");
    const cli = await connectCli(bridge, token);

    cli.client.command(`/new ${projectDir} first`);
    cli.client.command(`/new ${projectDir} second`);
    await cli.until(() => cli.client.pendingCount === 2, "both chats to block");

    cli.client.command("/use 2");
    cli.client.command("/y");
    await cli.until(() => cli.client.pendingCount === 1, "one request to resolve");

    const decisions = bridge.inbox.received("permission").map((e) => e.payload as { sessionId: string });
    expect(decisions).toHaveLength(1);
    // Whichever chat is second in the list is the one that got answered.
    const second = bridge.registry.list()[1]!;
    expect(decisions[0]!.sessionId).toBe(second.id);
    await cli.close();
  });

  it("replays outstanding requests to a client that reconnects", async () => {
    bridge = await startTestBridge({ scenarios: ["slow-permission"], timeScale: 0 });
    const { token } = await bridge.pair("cli");
    const first = await connectCli(bridge, token);
    first.client.command(`/new ${projectDir}`);
    await first.until(() => first.client.pendingCount === 1, "the chat to block");
    await first.close();

    const second = await connectCli(bridge, token);
    await second.until(() => second.client.pendingCount === 1, "the replayed request");
    expect(second.text()).toContain("./deploy.sh production");
    await second.close();
  });

  it("rejects a client with no token at all", async () => {
    bridge = await startTestBridge();
    const socket = new WebSocket(`${bridge.url.replace("http", "ws")}/ws`);
    const status = await new Promise<number>((resolve) => {
      socket.once("unexpected-response", (_req, res) => resolve(res.statusCode ?? 0));
      socket.once("error", () => resolve(0));
    });
    expect(status).toBe(401);
  });
});

describe("the whole stack, on the real SDK runner", () => {
  it("carries a typed prompt to query()'s input stream and the reply back to the terminal", async () => {
    const { fn, calls } = fakeQueryFn();
    const runner = new SdkAgentRunner({ query: fn });
    bridge = await startTestBridge({}, runner);
    const { token } = await bridge.pair("cli");
    const cli = await connectCli(bridge, token);

    cli.client.command(`/new ${projectDir} real-ish`);
    await cli.until(() => calls.length === 1, "query() to be called");
    expect(calls[0]!.options.cwd).toBe(projectDir);

    const query = calls[0]!.query;
    query.emit(initMessage("agent-e2e"));

    cli.client.command("what does this repo do?");
    await query.waitForInput(1);
    expect(query.received).toEqual(["what does this repo do?"]);

    query.emit(assistantMessage([{ type: "text", text: "It bridges a watch to the Agent SDK." }]));
    await cli.saw("It bridges a watch to the Agent SDK.");

    // A permission prompt, all the way from canUseTool to a typed /y and back.
    const decision = query.askToUse("Bash", { command: "npm test" }, [
      { type: "addRules", rules: [{ toolName: "Bash", ruleContent: "npm test" }], behavior: "allow", destination: "localSettings" },
    ]);
    await cli.saw("npm test");
    cli.client.command("/always");
    await expect(decision).resolves.toEqual({
      behavior: "allow",
      updatedInput: { command: "npm test" },
      updatedPermissions: [
        {
          type: "addRules",
          rules: [{ toolName: "Bash", ruleContent: "npm test" }],
          behavior: "allow",
          destination: "localSettings",
        },
      ],
    });

    query.emit(resultMessage({ result: "Tests pass." }));
    await cli.saw("Tests pass.");

    // And the agent session id made it to disk, which is what a restart resumes from.
    expect(bridge.registry.resumable()).toEqual([
      expect.objectContaining({ cwd: projectDir, name: "real-ish", agentSessionId: "agent-e2e" }),
    ]);

    await cli.close();
  });

  it("interrupts a runaway agent from the terminal", async () => {
    const { fn, calls } = fakeQueryFn();
    bridge = await startTestBridge({}, new SdkAgentRunner({ query: fn }));
    const { token } = await bridge.pair("cli");
    const cli = await connectCli(bridge, token);

    cli.client.command(`/new ${projectDir}`);
    await cli.until(() => calls.length === 1, "query() to be called");
    calls[0]!.query.emit(initMessage("agent-run"));

    const pending = calls[0]!.query.askToUse("Bash", { command: "sleep 9999" });
    await cli.saw("sleep 9999");
    cli.client.command("/interrupt");

    // The blocked tool call is denied rather than left hanging, and the agent hears why.
    await expect(pending).resolves.toEqual({
      behavior: "deny",
      message: "The user interrupted the agent.",
    });
    await cli.until(() => calls[0]!.query.interrupts === 1, "the interrupt to reach the query");
    await cli.close();
  });

  it("raises the permission mode on a live session", async () => {
    const { fn, calls } = fakeQueryFn();
    bridge = await startTestBridge({}, new SdkAgentRunner({ query: fn }));
    const { token } = await bridge.pair("cli");
    const cli = await connectCli(bridge, token);

    cli.client.command(`/new ${projectDir}`);
    await cli.until(() => calls.length === 1, "query() to be called");
    cli.client.command("/mode acceptEdits");
    await cli.until(() => calls[0]!.query.modes.length === 1, "the mode change");
    expect(calls[0]!.query.modes).toEqual(["acceptEdits"]);
    await cli.close();
  });
});
