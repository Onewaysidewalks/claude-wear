import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { WebSocket } from "ws";
import { AuthStore } from "../src/auth.js";
import type { BridgeConfig } from "../src/config.js";
import { Inbox } from "../src/inbox.js";
import { PROTOCOL_VERSION, type ClientMessage, type ServerEvent, type ServerEventOf } from "../src/protocol.js";
import { FakeAgentRunner } from "../src/runner/fake.js";
import type { AgentRunner } from "../src/runner/types.js";
import { BridgeServer } from "../src/server.js";
import { SessionRegistry } from "../src/sessions.js";

export const REPO_ROOT = new URL("../..", import.meta.url).pathname.replace(/\/$/, "");

export function baseConfig(overrides: Partial<BridgeConfig> = {}): BridgeConfig {
  return {
    port: 0,
    bind: "127.0.0.1",
    bindSpec: "127.0.0.1",
    maxSessions: 5,
    fake: true,
    scenarioDir: null,
    scenarios: [],
    timeScale: 0,
    stateDir: mkdtempSync(join(tmpdir(), "claude-wear-test-")),
    defaultMode: "default",
    allowedRoots: [],
    allowBypassPermissions: false,
    inbox: true,
    pair: true,
    configPath: null,
    ...overrides,
  };
}

/** A watch, more or less: connects, greets, and lets a test await specific events. */
export class TestClient {
  readonly received: ServerEvent[] = [];
  private readonly waiters: { match: (e: ServerEvent) => boolean; resolve: (e: ServerEvent) => void }[] = [];

  private constructor(private readonly socket: WebSocket) {
    socket.on("message", (data) => {
      const event = JSON.parse(data.toString()) as ServerEvent;
      this.received.push(event);
      for (let i = this.waiters.length - 1; i >= 0; i -= 1) {
        const waiter = this.waiters[i]!;
        if (waiter.match(event)) {
          this.waiters.splice(i, 1);
          waiter.resolve(event);
        }
      }
    });
  }

  static async connect(url: string, token: string): Promise<TestClient> {
    const socket = new WebSocket(`${url}/ws`, { headers: { authorization: `Bearer ${token}` } });
    await new Promise<void>((resolve, reject) => {
      socket.once("open", () => resolve());
      socket.once("error", reject);
    });
    return new TestClient(socket);
  }

  send(message: ClientMessage): void {
    this.socket.send(JSON.stringify(message));
  }

  async hello(deviceName = "test watch"): Promise<void> {
    const greeted = this.wait((e) => e.type === "sessions");
    this.send({
      type: "hello",
      protocolVersion: PROTOCOL_VERSION,
      deviceId: "dev_test",
      deviceName,
      clientVersion: "test",
    });
    await greeted;
  }

  /** Resolves with the first *future* event matching the predicate. */
  wait<T extends ServerEvent = ServerEvent>(match: (e: ServerEvent) => boolean, timeoutMs = 5000): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error(`timed out waiting for an event; saw ${this.received.map((e) => e.type).join(", ")}`));
      }, timeoutMs);
      this.waiters.push({
        match,
        resolve: (event) => {
          clearTimeout(timer);
          resolve(event as T);
        },
      });
    });
  }

  waitFor<T extends ServerEvent["type"]>(type: T, extra: (e: ServerEventOf<T>) => boolean = () => true) {
    return this.wait<ServerEventOf<T>>((e) => e.type === type && extra(e as ServerEventOf<T>));
  }

  seen<T extends ServerEvent["type"]>(type: T): ServerEventOf<T>[] {
    return this.received.filter((e) => e.type === type) as ServerEventOf<T>[];
  }

  close(): Promise<void> {
    if (this.socket.readyState === WebSocket.CLOSED) return Promise.resolve();
    return new Promise((resolve) => {
      this.socket.once("close", () => resolve());
      this.socket.close();
    });
  }
}

export interface TestBridge {
  url: string;
  server: BridgeServer;
  registry: SessionRegistry;
  auth: AuthStore;
  inbox: Inbox;
  config: BridgeConfig;
  /** Runs the real pairing flow over HTTP and returns the token. */
  pair(deviceName?: string): Promise<{ deviceId: string; token: string }>;
  connect(token: string): Promise<TestClient>;
  stop(): Promise<void>;
}

export async function startTestBridge(
  overrides: Partial<BridgeConfig> = {},
  /** Swap in the real SDK runner (behind a fake `query()`) to exercise the whole stack. */
  injectedRunner?: AgentRunner,
): Promise<TestBridge> {
  const config = baseConfig(overrides);
  const runner =
    injectedRunner ??
    new FakeAgentRunner({
      scenarioDir: config.scenarioDir ?? undefined,
      rotation: config.scenarios,
      timeScale: config.timeScale,
    });
  const auth = new AuthStore(config.stateDir);
  const inbox = new Inbox(config.inbox);
  const registry = new SessionRegistry({
    runner,
    maxSessions: config.maxSessions,
    stateDir: config.stateDir,
    defaultMode: config.defaultMode,
    allowedRoots: config.allowedRoots,
  });
  const server = new BridgeServer({ config, registry, auth, inbox, runnerName: runner.name, version: "test" });
  const { port } = await server.listen();
  const url = `http://127.0.0.1:${port}`;
  const clients: TestClient[] = [];

  return {
    url,
    server,
    registry,
    auth,
    inbox,
    config,
    async pair(deviceName = "test watch") {
      const code = auth.issuePairingCode();
      const res = await fetch(`${url}/pair`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ code, deviceName }),
      });
      if (!res.ok) throw new Error(`pairing failed: ${res.status} ${await res.text()}`);
      return (await res.json()) as { deviceId: string; token: string };
    },
    async connect(token: string) {
      const client = await TestClient.connect(url.replace("http", "ws"), token);
      clients.push(client);
      return client;
    },
    async stop() {
      await Promise.all(clients.map((c) => c.close()));
      await registry.closeAll();
      await server.close();
      rmSync(config.stateDir, { recursive: true, force: true });
    },
  };
}
