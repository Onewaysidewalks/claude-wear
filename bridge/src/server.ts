/**
 * HTTP + WebSocket front door.
 *
 *   POST /pair          exchange an 8-digit code for a device-scoped bearer token
 *   GET  /health        liveness, and which runner is behind it
 *   GET  /debug/inbox   what the watch actually sent (--inbox only)
 *   WS   /ws            the protocol, token on the upgrade
 */
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import type { Duplex } from "node:stream";
import { WebSocket, WebSocketServer } from "ws";
import { PROTOCOL_VERSION, type ClientMessage, type ServerEvent } from "./protocol.js";
import type { AuthStore, Device } from "./auth.js";
import type { BridgeConfig } from "./config.js";
import type { Inbox } from "./inbox.js";
import { log } from "./log.js";
import { SessionError, type SessionRegistry } from "./sessions.js";
import { parseClientMessage } from "./validate.js";

const MAX_FRAME_BYTES = 256 * 1024;

export interface BridgeServerOptions {
  config: BridgeConfig;
  registry: SessionRegistry;
  auth: AuthStore;
  inbox: Inbox;
  runnerName: string;
  version: string;
}

interface Connection {
  socket: WebSocket;
  device: Device;
  greeted: boolean;
  /** What the client says it already has. Carried for M5's delta replay; gap detection itself is the watch's job. */
  sinceSeq: Record<string, number> | null;
  unsubscribe: (() => void) | null;
}

export class BridgeServer {
  private readonly http: Server;
  private readonly wss = new WebSocketServer({ noServer: true, maxPayload: MAX_FRAME_BYTES });
  private readonly connections = new Set<Connection>();

  constructor(private readonly options: BridgeServerOptions) {
    this.http = createServer((req, res) => this.handleHttp(req, res));
    this.http.on("upgrade", (req, socket, head) => this.handleUpgrade(req, socket, head));
    this.options.registry.on((event) => {
      this.options.inbox.recordOut(event);
      this.broadcast(event);
    });
  }

  // --- lifecycle ------------------------------------------------------------

  listen(): Promise<{ address: string; port: number }> {
    return new Promise((resolve, reject) => {
      this.http.once("error", reject);
      this.http.listen(this.options.config.port, this.options.config.bind, () => {
        this.http.removeListener("error", reject);
        const info = this.http.address();
        if (info === null || typeof info === "string") {
          reject(new Error("server did not bind to a TCP address"));
          return;
        }
        resolve({ address: info.address, port: info.port });
      });
    });
  }

  async close(): Promise<void> {
    for (const connection of this.connections) connection.socket.close(1001, "bridge shutting down");
    this.connections.clear();
    await new Promise<void>((resolve) => this.wss.close(() => resolve()));
    const closed = new Promise<void>((resolve) => this.http.close(() => resolve()));
    // An upgraded connection keeps its TCP socket alive; without this, shutdown waits for
    // a watch that has already been told to go away.
    this.http.closeAllConnections();
    await closed;
  }

  get connectionCount(): number {
    return this.connections.size;
  }

  // --- HTTP -----------------------------------------------------------------

  private handleHttp(req: IncomingMessage, res: ServerResponse): void {
    const url = new URL(req.url ?? "/", "http://bridge");
    if (req.method === "GET" && url.pathname === "/health") {
      this.json(res, 200, {
        ok: true,
        version: this.options.version,
        protocolVersion: PROTOCOL_VERSION,
        runner: this.options.runnerName,
        sessions: this.options.registry.size,
        maxSessions: this.options.registry.maxSessions,
        devices: this.options.auth.list().length,
      });
      return;
    }
    if (req.method === "POST" && url.pathname === "/pair") {
      void this.handlePair(req, res);
      return;
    }
    if (req.method === "GET" && url.pathname === "/debug/inbox") {
      if (!this.options.inbox.enabled) {
        this.json(res, 404, { error: "the inbox is off; start the bridge with --inbox" });
        return;
      }
      this.json(res, 200, { entries: this.options.inbox.all() });
      return;
    }
    this.json(res, 404, { error: "not found" });
  }

  private async handlePair(req: IncomingMessage, res: ServerResponse): Promise<void> {
    let body = "";
    for await (const chunk of req) {
      body += chunk;
      if (body.length > 4096) {
        this.json(res, 413, { error: "body too large" });
        return;
      }
    }
    let parsed: { code?: unknown; deviceName?: unknown };
    try {
      parsed = JSON.parse(body || "{}");
    } catch {
      this.json(res, 400, { error: "body is not valid JSON" });
      return;
    }
    if (typeof parsed.code !== "string") {
      this.json(res, 400, { error: "`code` is required" });
      return;
    }
    const result = this.options.auth.pair(
      parsed.code,
      typeof parsed.deviceName === "string" ? parsed.deviceName : "watch",
    );
    if (!result) {
      this.json(res, 401, { error: "that pairing code is wrong, used, or expired" });
      return;
    }
    this.json(res, 200, { ...result, protocolVersion: PROTOCOL_VERSION });
  }

  private json(res: ServerResponse, status: number, body: unknown): void {
    const payload = JSON.stringify(body);
    res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(payload) });
    res.end(payload);
  }

  // --- WebSocket ------------------------------------------------------------

  private handleUpgrade(req: IncomingMessage, socket: Duplex, head: Buffer): void {
    const url = new URL(req.url ?? "/", "http://bridge");
    if (url.pathname !== "/ws") {
      socket.write("HTTP/1.1 404 Not Found\r\n\r\n");
      socket.destroy();
      return;
    }
    const header = req.headers.authorization;
    const token = header?.startsWith("Bearer ") ? header.slice("Bearer ".length) : url.searchParams.get("token");
    const device = this.options.auth.verify(token);
    if (!device) {
      log.warn("rejected an unauthenticated upgrade", { remote: req.socket.remoteAddress });
      socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
      socket.destroy();
      return;
    }
    this.wss.handleUpgrade(req, socket, head, (ws) => this.accept(ws, device));
  }

  private accept(socket: WebSocket, device: Device): void {
    const connection: Connection = { socket, device, greeted: false, sinceSeq: null, unsubscribe: null };
    this.connections.add(connection);
    log.info("watch connected", { deviceId: device.deviceId, deviceName: device.deviceName });

    socket.on("message", (data) => this.onFrame(connection, data.toString()));
    socket.on("close", () => {
      connection.unsubscribe?.();
      this.connections.delete(connection);
      log.info("watch disconnected", { deviceId: device.deviceId });
    });
    socket.on("error", (err) => log.warn("socket error", { deviceId: device.deviceId, error: err.message }));
  }

  private send(connection: Connection, event: ServerEvent): void {
    if (connection.socket.readyState !== WebSocket.OPEN) return;
    connection.socket.send(JSON.stringify(event));
  }

  private broadcast(event: ServerEvent): void {
    for (const connection of this.connections) {
      if (connection.greeted) this.send(connection, event);
    }
  }

  private fail(connection: Connection, code: Parameters<SessionRegistry["registryError"]>[0], message: string): void {
    this.send(connection, this.options.registry.registryError(code, message));
  }

  private onFrame(connection: Connection, raw: string): void {
    const parsed = parseClientMessage(raw);
    if (!parsed.ok) {
      log.warn("malformed frame", { deviceId: connection.device.deviceId, reason: parsed.reason });
      this.fail(connection, "malformed", parsed.reason);
      return;
    }
    const message = parsed.message;
    this.options.inbox.recordIn(connection.device.deviceId, message);

    if (!connection.greeted && message.type !== "hello") {
      this.fail(connection, "malformed", "the first frame must be `hello`");
      connection.socket.close(1008, "hello first");
      return;
    }

    try {
      this.dispatch(connection, message);
    } catch (err) {
      if (err instanceof SessionError) {
        const sessionId = (message as { sessionId?: string }).sessionId;
        const session = sessionId ? this.options.registry.get(sessionId) : undefined;
        const requestId = (message as { requestId?: string }).requestId ?? null;
        if (session) {
          session.emitError(err.code, err.message, requestId);
        } else {
          this.send(connection, this.options.registry.registryError(err.code, err.message, requestId));
        }
        return;
      }
      log.error("failed to handle a frame", { type: message.type, error: (err as Error).message });
      this.fail(connection, "internal", (err as Error).message);
    }
  }

  private dispatch(connection: Connection, message: ClientMessage): void {
    const registry = this.options.registry;
    switch (message.type) {
      case "hello": {
        if (message.protocolVersion !== PROTOCOL_VERSION) {
          this.fail(
            connection,
            "protocolVersion",
            `this bridge speaks protocol v${PROTOCOL_VERSION}, the watch speaks v${message.protocolVersion}. Update whichever is older.`,
          );
          connection.socket.close(1008, "protocol version");
          return;
        }
        connection.greeted = true;
        this.send(connection, registry.snapshot());
        return;
      }
      case "subscribe": {
        // Replay is unconditional and global: three sessions blocked when you walked out
        // of range are three cards when you walk back in.
        connection.sinceSeq = message.sinceSeq;
        for (const event of registry.replay()) this.send(connection, event);
        return;
      }
      case "newSession": {
        registry.create(message.cwd, message.name);
        return;
      }
      case "prompt":
        registry.require(message.sessionId).prompt(message.text);
        return;
      case "answer":
        registry
          .require(message.sessionId)
          .answer(message.requestId, message.answers, message.response, connection.device.deviceId);
        return;
      case "permission":
        registry
          .require(message.sessionId)
          .decide(message.requestId, message.decision, message.message, connection.device.deviceId);
        return;
      case "interrupt":
        void registry.require(message.sessionId).interrupt();
        return;
      case "setMode":
        registry.require(message.sessionId).setMode(message.mode);
        return;
      case "renameSession": {
        registry.require(message.sessionId).rename(message.name);
        this.broadcast(registry.snapshot());
        return;
      }
    }
  }
}
