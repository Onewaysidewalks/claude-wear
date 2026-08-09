/**
 * The bridge over a real socket: pairing, the protocol handshake, and the multi-session
 * cases -- two chats blocked at once, replay after a disconnect, and a request answered
 * somewhere else while its card is still on your wrist.
 */
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { WebSocket } from "ws";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { PROTOCOL_VERSION, REGISTRY_SESSION_ID } from "../src/protocol.js";
import { startTestBridge, type TestBridge } from "./helpers.js";

let bridge: TestBridge;
let projectA: string;
let projectB: string;

beforeEach(() => {
  projectA = mkdtempSync(join(tmpdir(), "claude-wear-a-"));
  projectB = mkdtempSync(join(tmpdir(), "claude-wear-b-"));
});

afterEach(async () => {
  await bridge?.stop();
  rmSync(projectA, { recursive: true, force: true });
  rmSync(projectB, { recursive: true, force: true });
});

async function pairedClient(overrides: Parameters<typeof startTestBridge>[0] = {}) {
  bridge = await startTestBridge(overrides);
  const { token } = await bridge.pair();
  const client = await bridge.connect(token);
  await client.hello();
  return { client, token };
}

describe("pairing and auth", () => {
  it("exchanges a code for a token over HTTP", async () => {
    bridge = await startTestBridge();
    const code = bridge.auth.issuePairingCode();
    const res = await fetch(`${bridge.url}/pair`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code, deviceName: "Galaxy Watch" }),
    });
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({
      deviceId: expect.stringMatching(/^dev_/),
      token: expect.any(String),
      protocolVersion: PROTOCOL_VERSION,
    });
  });

  it("refuses a bad pairing code", async () => {
    bridge = await startTestBridge();
    bridge.auth.issuePairingCode();
    const res = await fetch(`${bridge.url}/pair`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ code: "00000000", deviceName: "attacker" }),
    });
    expect(res.status).toBe(401);
  });

  it("rejects a WebSocket upgrade without a valid token", async () => {
    bridge = await startTestBridge();
    const socket = new WebSocket(`${bridge.url.replace("http", "ws")}/ws?token=nope`);
    const err = await new Promise<Error>((resolve) => socket.once("error", resolve));
    expect(err.message).toContain("401");
  });

  it("accepts the token on the query string as well as the header", async () => {
    bridge = await startTestBridge();
    const { token } = await bridge.pair();
    const socket = new WebSocket(`${bridge.url.replace("http", "ws")}/ws?token=${token}`);
    await new Promise<void>((resolve, reject) => {
      socket.once("open", () => resolve());
      socket.once("error", reject);
    });
    socket.close();
  });

  it("reports health without a token", async () => {
    bridge = await startTestBridge();
    const res = await fetch(`${bridge.url}/health`);
    expect(await res.json()).toMatchObject({ ok: true, runner: "fake", protocolVersion: PROTOCOL_VERSION });
  });
});

describe("the handshake", () => {
  it("answers hello with a session snapshot", async () => {
    const { client } = await pairedClient();
    expect(client.seen("sessions")[0]).toMatchObject({ sessionId: REGISTRY_SESSION_ID, sessions: [], maxSessions: 5 });
  });

  it("refuses to do anything before hello", async () => {
    bridge = await startTestBridge();
    const { token } = await bridge.pair();
    const client = await bridge.connect(token);
    const error = client.waitFor("error");
    client.send({ type: "subscribe", sinceSeq: null });
    expect(await error).toMatchObject({ code: "malformed" });
  });

  it("says plainly which side is out of date", async () => {
    bridge = await startTestBridge();
    const { token } = await bridge.pair();
    const client = await bridge.connect(token);
    const error = client.waitFor("error");
    client.send({
      type: "hello",
      protocolVersion: PROTOCOL_VERSION + 1,
      deviceId: "dev_x",
      deviceName: "future watch",
      clientVersion: "9.9.9",
    });
    expect(await error).toMatchObject({ code: "protocolVersion" });
  });

  it("answers a malformed frame with an error and stays connected", async () => {
    const { client } = await pairedClient();
    const error = client.waitFor("error");
    client.send({ type: "prompt", sessionId: "s_1" } as never);
    expect(await error).toMatchObject({ code: "malformed", sessionId: REGISTRY_SESSION_ID });

    const snapshot = client.waitFor("sessions");
    client.send({ type: "subscribe", sinceSeq: null });
    await snapshot;
  });
});

describe("a single chat, end to end", () => {
  it("runs the plan's worked example: text, a question, a permission, a result", async () => {
    const { client } = await pairedClient({ scenarios: ["auq-then-bash"] });

    const ask = client.waitFor("ask");
    client.send({ type: "newSession", cwd: projectA, name: "claude-wear" });
    const askEvent = await ask;
    expect(askEvent.questions[0]!.header).toBe("Format");

    const askTurn = client.seen("turn").find((t) => t.reason === "ask")!;
    expect(askTurn).toMatchObject({ state: "awaiting", requestId: askEvent.requestId, sessionName: "claude-wear" });

    const permission = client.waitFor("permission");
    client.send({
      type: "answer",
      sessionId: askEvent.sessionId,
      requestId: askEvent.requestId,
      answers: { "How should I format the output?": "Summary" },
      response: null,
    });
    const permissionEvent = await permission;
    expect(permissionEvent).toMatchObject({ tool: "Bash", display: "npm test" });

    const done = client.waitFor("done");
    client.send({
      type: "permission",
      sessionId: permissionEvent.sessionId,
      requestId: permissionEvent.requestId,
      decision: "allowAlways",
      message: null,
    });
    expect(await done).toMatchObject({ subtype: "success", isError: false, result: "Tests pass." });
    expect(client.seen("turn").at(-1)).toMatchObject({ state: "idle", summary: "Your turn" });
  });

  it("takes a dictated follow-up and comes back with another turn", async () => {
    const { client } = await pairedClient({ scenarios: ["multi-turn"] });
    const firstDone = client.waitFor("done");
    client.send({ type: "newSession", cwd: projectA, name: "chat" });
    const { sessionId } = await firstDone;

    const secondDone = client.waitFor("done");
    client.send({ type: "prompt", sessionId, text: "now run the linter" });
    expect(await secondDone).toMatchObject({ result: "Done — pushed to the branch." });
  });

  it("interrupts a runaway agent", async () => {
    const { client } = await pairedClient({ scenarios: ["slow-permission"] });
    const permission = client.waitFor("permission");
    client.send({ type: "newSession", cwd: projectA, name: "deploy" });
    const { sessionId, requestId } = await permission;

    const resolved = client.waitFor("resolved");
    client.send({ type: "interrupt", sessionId });
    expect(await resolved).toMatchObject({ requestId, resolution: "interrupted" });
  });

  it("renames a chat and re-broadcasts the list", async () => {
    const { client } = await pairedClient({ scenarios: ["quick-idle"] });
    const created = client.waitFor("sessions", (e) => e.sessions.length === 1);
    client.send({ type: "newSession", cwd: projectA, name: null });
    const { sessions } = await created;
    expect(sessions[0]!.name).toBe(projectA.split("/").pop());

    const renamed = client.waitFor("sessions", (e) => e.sessions[0]?.name === "wear app");
    client.send({ type: "renameSession", sessionId: sessions[0]!.sessionId, name: "wear app" });
    await renamed;
  });

  it("re-broadcasts the list after a mode change, so the chip you tapped is the one you see", async () => {
    const { client } = await pairedClient({ scenarios: ["quick-idle"] });
    const created = client.waitFor("sessions", (e) => e.sessions.length === 1);
    client.send({ type: "newSession", cwd: projectA, name: "modes" });
    const { sessions } = await created;
    expect(sessions[0]!.mode).toBe("default");

    const switched = client.waitFor("sessions", (e) => e.sessions[0]?.mode === "acceptEdits");
    client.send({ type: "setMode", sessionId: sessions[0]!.sessionId, mode: "acceptEdits" });
    await switched;
  });

  it("rejects a chat past --max-sessions with a clear error", async () => {
    const { client } = await pairedClient({ maxSessions: 1, scenarios: ["quick-idle"] });
    client.send({ type: "newSession", cwd: projectA, name: "one" });
    const error = client.waitFor("error");
    client.send({ type: "newSession", cwd: projectB, name: "two" });
    expect(await error).toMatchObject({ code: "maxSessions", sessionId: REGISTRY_SESSION_ID });
  });

  it("rejects a cwd that is not there", async () => {
    const { client } = await pairedClient();
    const error = client.waitFor("error");
    client.send({ type: "newSession", cwd: join(projectA, "nope"), name: null });
    expect(await error).toMatchObject({ code: "invalidCwd" });
  });
});

describe("multiple sessions", () => {
  it("keeps two chats blocked on you distinguishable", async () => {
    const { client } = await pairedClient({ scenarios: ["slow-permission", "auq-then-bash"] });

    const first = client.waitFor("permission");
    client.send({ type: "newSession", cwd: projectA, name: "infra" });
    const permission = await first;

    const second = client.waitFor("ask");
    client.send({ type: "newSession", cwd: projectB, name: "claude-wear" });
    const ask = await second;

    expect(permission.sessionId).not.toBe(ask.sessionId);
    expect(permission.requestId).not.toBe(ask.requestId);

    // Every card leads with the session's name; "may I run npm test?" alone is not answerable.
    const awaiting = client.seen("turn").filter((t) => t.state === "awaiting");
    expect(new Set(awaiting.map((t) => t.sessionName))).toEqual(new Set(["infra", "claude-wear"]));
    expect(awaiting.map((t) => t.requestId).sort()).toEqual([permission.requestId, ask.requestId].sort());

    const snapshot = client.waitFor("sessions");
    client.send({ type: "subscribe", sinceSeq: null });
    const listed = await snapshot;
    expect(listed.sessions.every((s) => s.state === "awaiting")).toBe(true);
    expect(listed.sessions.flatMap((s) => s.pendingRequestIds).sort()).toEqual(
      [permission.requestId, ask.requestId].sort(),
    );
  });

  it("answers each session's request independently", async () => {
    const { client } = await pairedClient({ scenarios: ["slow-permission", "slow-permission"] });
    const a = client.waitFor("permission");
    client.send({ type: "newSession", cwd: projectA, name: "one" });
    const first = await a;
    const b = client.waitFor("permission", (e) => e.sessionId !== first.sessionId);
    client.send({ type: "newSession", cwd: projectB, name: "two" });
    const second = await b;

    const resolved = client.waitFor("resolved");
    client.send({
      type: "permission",
      sessionId: first.sessionId,
      requestId: first.requestId,
      decision: "allow",
      message: null,
    });
    expect(await resolved).toMatchObject({ sessionId: first.sessionId, requestId: first.requestId });

    // The other chat is still waiting for you, untouched.
    const snapshot = client.waitFor("sessions");
    client.send({ type: "subscribe", sinceSeq: null });
    const listed = await snapshot;
    expect(listed.sessions.find((s) => s.sessionId === second.sessionId)!.pendingRequestIds).toEqual([
      second.requestId,
    ]);
  });

  it("replays every outstanding request across all sessions after a disconnect", async () => {
    bridge = await startTestBridge({ scenarios: ["slow-permission", "slow-permission"] });
    const { token } = await bridge.pair();
    const watch = await bridge.connect(token);
    await watch.hello();
    const a = watch.waitFor("permission");
    watch.send({ type: "newSession", cwd: projectA, name: "one" });
    const one = await a;
    const b = watch.waitFor("permission", (e) => e.sessionId !== one.sessionId);
    watch.send({ type: "newSession", cwd: projectB, name: "two" });
    const two = await b;

    // Walk out of Wi-Fi range.
    await watch.close();
    expect(bridge.server.connectionCount).toBe(0);

    // Walk back in.
    const reconnected = await bridge.connect(token);
    await reconnected.hello();
    const snapshot = reconnected.waitFor("sessions");
    reconnected.send({ type: "subscribe", sinceSeq: { [one.sessionId]: 1 } });
    await snapshot;

    const replayed = reconnected.seen("permission");
    expect(replayed.map((e) => e.requestId).sort()).toEqual([one.requestId, two.requestId].sort());

    // And the replayed request is still answerable.
    const resolved = reconnected.waitFor("resolved");
    reconnected.send({
      type: "permission",
      sessionId: one.sessionId,
      requestId: one.requestId,
      decision: "allow",
      message: null,
    });
    expect(await resolved).toMatchObject({ requestId: one.requestId });
  });

  it("tells the other watch a request was answered, and says so if it answers anyway", async () => {
    const { client: phone } = await pairedClient({ scenarios: ["slow-permission"] });
    const { token: secondToken } = await bridge.pair("second watch");
    const watch = await bridge.connect(secondToken);
    await watch.hello();

    const onPhone = phone.waitFor("permission");
    const onWatch = watch.waitFor("permission");
    phone.send({ type: "newSession", cwd: projectA, name: "deploy" });
    const request = await onPhone;
    await onWatch;

    // Answered on the phone while the card is still up on the watch.
    const cancelled = watch.waitFor("resolved");
    phone.send({
      type: "permission",
      sessionId: request.sessionId,
      requestId: request.requestId,
      decision: "allow",
      message: null,
    });
    expect(await cancelled).toMatchObject({ requestId: request.requestId, resolution: "allowed" });

    const stale = watch.waitFor("error");
    watch.send({
      type: "permission",
      sessionId: request.sessionId,
      requestId: request.requestId,
      decision: "deny",
      message: "too late",
    });
    expect(await stale).toMatchObject({
      code: "alreadyResolved",
      sessionId: request.sessionId,
      requestId: request.requestId,
    });
  });
});

describe("the inbox", () => {
  it("records what the watch sent, so E2E can assert on it", async () => {
    const { client } = await pairedClient({ scenarios: ["quick-idle"] });
    const done = client.waitFor("done");
    client.send({ type: "newSession", cwd: projectA, name: "one" });
    await done;

    const res = await fetch(`${bridge.url}/debug/inbox`);
    const { entries } = (await res.json()) as { entries: { direction: string; type: string }[] };
    expect(entries.filter((e) => e.direction === "in").map((e) => e.type)).toEqual(["hello", "newSession"]);
    expect(entries.some((e) => e.direction === "out" && e.type === "done")).toBe(true);
  });

  it("is off unless you ask for it", async () => {
    bridge = await startTestBridge({ inbox: false });
    expect((await fetch(`${bridge.url}/debug/inbox`)).status).toBe(404);
  });
});
