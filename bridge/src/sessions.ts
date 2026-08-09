/**
 * SessionRegistry: create / list / resume, and the fan-out of every session's events to
 * every connected watch.
 *
 * Concurrency has a real cost -- each run is its own agent loop with its own context
 * window and, with the real SDK, its own subprocess, on a machine that is also running
 * your editor. Hence `maxSessions`: a clear rejection beats quietly thrashing the host.
 */
import { randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import { basename, join, resolve } from "node:path";
import type { ErrorCode, ErrorEvent, PermissionMode, ServerEvent, SessionsEvent } from "./protocol.js";
import { REGISTRY_SESSION_ID } from "./protocol.js";
import { log } from "./log.js";
import type { AgentRunner } from "./runner/types.js";
import { Session, SessionError } from "./session.js";

export interface SessionRegistryOptions {
  runner: AgentRunner;
  /** N sessions is N agent loops and N token spends. Default 5; worth measuring before raising. */
  maxSessions: number;
  /** Where sessions.json lives. `~/.claude-wear` in production. */
  stateDir: string;
  defaultMode: PermissionMode;
  /** Project roots the bridge is willing to open. Empty means any existing directory. */
  allowedRoots?: string[];
}

interface PersistedSession {
  cwd: string;
  name: string;
  agentSessionId: string | null;
  updatedAt: number;
}

export type EventListener = (event: ServerEvent) => void;

export class SessionRegistry {
  private readonly sessions = new Map<string, Session>();
  private readonly listeners = new Set<EventListener>();
  private readonly persisted = new Map<string, PersistedSession>();
  private registrySeq = 0;

  constructor(private readonly options: SessionRegistryOptions) {
    mkdirSync(options.stateDir, { recursive: true });
    this.loadPersisted();
  }

  // --- events ---------------------------------------------------------------

  on(listener: EventListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private broadcast(event: ServerEvent): void {
    for (const listener of this.listeners) {
      try {
        listener(event);
      } catch (err) {
        log.error("event listener threw", { error: (err as Error).message });
      }
    }
  }

  /** A registry-scoped event: the reserved `@registry` sessionId with its own seq. */
  snapshot(): SessionsEvent {
    this.registrySeq += 1;
    return {
      type: "sessions",
      sessionId: REGISTRY_SESSION_ID,
      seq: this.registrySeq,
      sessions: this.list().map((s) => s.summary()),
      maxSessions: this.options.maxSessions,
    };
  }

  registryError(code: ErrorCode, message: string, requestId: string | null = null): ErrorEvent {
    this.registrySeq += 1;
    return {
      type: "error",
      sessionId: REGISTRY_SESSION_ID,
      seq: this.registrySeq,
      code,
      message,
      requestId,
    };
  }

  /** Snapshot plus every outstanding request across all sessions -- reconnect replay is global. */
  replay(): ServerEvent[] {
    const events: ServerEvent[] = [this.snapshot()];
    for (const session of this.list()) events.push(...session.outstandingEvents());
    return events;
  }

  // --- lifecycle ------------------------------------------------------------

  create(cwd: string, name: string | null, scenario: string | null = null): Session {
    if (this.sessions.size >= this.options.maxSessions) {
      throw new SessionError(
        "maxSessions",
        `${this.options.maxSessions} sessions already running; close one first`,
      );
    }
    const absolute = resolve(cwd);
    this.assertUsableCwd(absolute);

    const id = `s_${randomUUID().replace(/-/g, "").slice(0, 8)}`;
    const previous = [...this.persisted.entries()].find(([, p]) => p.cwd === absolute)?.[1] ?? null;
    const sessionName = name?.trim() || basename(absolute);
    const session = new Session({
      id,
      cwd: absolute,
      name: sessionName,
      mode: this.options.defaultMode,
      runner: this.options.runner,
      scenario,
      resume: previous?.agentSessionId ?? null,
      emit: (event) => this.broadcast(event),
      onAgentSessionId: (agentSessionId) => {
        this.persisted.set(id, { cwd: absolute, name: sessionName, agentSessionId, updatedAt: Date.now() });
        this.savePersisted();
      },
    });
    this.sessions.set(id, session);
    log.info("session created", { sessionId: id, cwd: absolute, resume: previous?.agentSessionId ?? null });
    this.broadcast(this.snapshot());
    return session;
  }

  private assertUsableCwd(absolute: string): void {
    let stats;
    try {
      stats = statSync(absolute);
    } catch {
      throw new SessionError("invalidCwd", `${absolute} does not exist`);
    }
    if (!stats.isDirectory()) throw new SessionError("invalidCwd", `${absolute} is not a directory`);

    const roots = this.options.allowedRoots ?? [];
    if (roots.length > 0 && !roots.some((root) => absolute === root || absolute.startsWith(`${root}/`))) {
      throw new SessionError("invalidCwd", `${absolute} is outside the configured project roots`);
    }
  }

  require(sessionId: string): Session {
    const session = this.sessions.get(sessionId);
    if (!session) throw new SessionError("unknownSession", `no session ${sessionId}`);
    return session;
  }

  get(sessionId: string): Session | undefined {
    return this.sessions.get(sessionId);
  }

  list(): Session[] {
    // Awaiting first: the whole point of the list is "who needs me".
    return [...this.sessions.values()].sort((a, b) => {
      const rank = (s: Session) => (s.summary().state === "awaiting" ? 0 : s.summary().state === "idle" ? 1 : 2);
      return rank(a) - rank(b) || a.createdAt - b.createdAt;
    });
  }

  get size(): number {
    return this.sessions.size;
  }

  get maxSessions(): number {
    return this.options.maxSessions;
  }

  async close(sessionId: string): Promise<void> {
    const session = this.sessions.get(sessionId);
    if (!session) return;
    await session.close();
    this.sessions.delete(sessionId);
    this.broadcast(this.snapshot());
  }

  async closeAll(): Promise<void> {
    await Promise.all([...this.sessions.keys()].map((id) => this.close(id)));
  }

  // --- persistence ----------------------------------------------------------
  //
  // There is no store to design: the Agent SDK already writes transcripts to
  // ~/.claude/projects/<encoded-cwd>/*.jsonl. All the bridge keeps is the mapping from
  // its own chats to those session ids, so a restart resumes instead of losing context.

  private get persistPath(): string {
    return join(this.options.stateDir, "sessions.json");
  }

  private loadPersisted(): void {
    try {
      const raw = JSON.parse(readFileSync(this.persistPath, "utf8")) as Record<string, PersistedSession>;
      for (const [id, entry] of Object.entries(raw)) this.persisted.set(id, entry);
      log.debug("loaded persisted sessions", { count: this.persisted.size });
    } catch {
      /* first run */
    }
  }

  private savePersisted(): void {
    const tmp = `${this.persistPath}.${process.pid}.tmp`;
    writeFileSync(tmp, `${JSON.stringify(Object.fromEntries(this.persisted), null, 2)}\n`);
    renameSync(tmp, this.persistPath);
  }
}

export { SessionError };
