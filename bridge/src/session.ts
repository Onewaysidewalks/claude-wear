/**
 * One chat: one agent run plus its pending-request map.
 *
 * `canUseTool` blocks the agent until it returns, and the SDK waits indefinitely -- that
 * is exactly the semantics the watch wants, so a request simply parks in `pending` until
 * someone answers it. Pending requests survive watch disconnects; walking out of Wi-Fi
 * range does not drop a decision on the floor.
 */
import { randomUUID } from "node:crypto";
import type {
  AskEvent,
  AskQuestion,
  ErrorCode,
  PermissionDecision,
  PermissionEvent,
  PermissionMode,
  PermissionSuggestion,
  Resolution,
  ServerEvent,
  SessionSummary,
  SessionsEvent,
} from "./protocol.js";
import { log } from "./log.js";
import {
  ASK_USER_QUESTION,
  type AgentHandle,
  type AgentRunner,
  type PermissionResult,
  type ToolInput,
  type ToolPermissionRequest,
} from "./runner/types.js";
import {
  type PendingKind,
  type Turn,
  deriveTurn,
  describeToolInput,
  summariseAsk,
  summarisePermission,
} from "./turn.js";

/** Omit that distributes over the ServerEvent union instead of collapsing it to its common keys. */
type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never;

/** A session event minus the two fields the session itself fills in. */
type ServerEventDraft = DistributiveOmit<Exclude<ServerEvent, SessionsEvent>, "sessionId" | "seq">;

/** A failure the watch should see as a clean `error` event rather than a dropped frame. */
export class SessionError extends Error {
  constructor(
    readonly code: ErrorCode,
    message: string,
  ) {
    super(message);
    this.name = "SessionError";
  }
}

interface Pending {
  requestId: string;
  kind: PendingKind;
  summary: string;
  toolName: string;
  input: ToolInput;
  suggestions: PermissionSuggestion[];
  /** The event as first broadcast, replayed verbatim when a watch reconnects. */
  event: AskEvent | PermissionEvent;
  resolve(result: PermissionResult): void;
  createdAt: number;
}

export interface SessionOptions {
  id: string;
  cwd: string;
  name: string;
  mode: PermissionMode;
  runner: AgentRunner;
  scenario: string | null;
  resume: string | null;
  emit(event: ServerEvent): void;
  /** Called once the agent reports its own session id, so a restart can resume it. */
  onAgentSessionId(agentSessionId: string): void;
}

export class Session {
  readonly id: string;
  readonly cwd: string;
  readonly createdAt = Date.now();

  private name: string;
  private mode: PermissionMode;
  private seq = 0;
  private lastActivityAt = Date.now();
  private started = false;
  private finished = false;
  private failed = false;
  private closed = false;
  private agentSessionId: string | null = null;
  private lastTurnKey = "";
  private readonly pending = new Map<string, Pending>();
  private readonly handle: AgentHandle;

  constructor(private readonly options: SessionOptions) {
    this.id = options.id;
    this.cwd = options.cwd;
    this.name = options.name;
    this.mode = options.mode;
    this.handle = options.runner.start({
      sessionId: options.id,
      cwd: options.cwd,
      resume: options.resume,
      permissionMode: options.mode,
      scenario: options.scenario,
      canUseTool: (request) => this.awaitDecision(request),
      onMessage: (message) => {
        switch (message.type) {
          case "init":
            this.started = true;
            this.agentSessionId = message.agentSessionId;
            this.options.onAgentSessionId(message.agentSessionId);
            break;
          case "assistant":
            this.finished = false;
            this.emit({ type: "text", text: message.text });
            break;
          case "result":
            this.finished = true;
            this.emit({
              type: "done",
              subtype: message.subtype,
              isError: message.isError,
              durationMs: message.durationMs,
              numTurns: message.numTurns,
              result: message.result,
            });
            break;
        }
        this.broadcastTurn();
      },
      onError: (error) => {
        this.failed = true;
        log.error("agent failed", { sessionId: this.id, error: error.message });
        this.emit({ type: "error", code: "runnerFailed", message: error.message, requestId: null });
        this.broadcastTurn();
      },
    });
    this.broadcastTurn();
  }

  // --- outbound -------------------------------------------------------------

  private emit(event: ServerEventDraft): ServerEvent {
    this.seq += 1;
    this.lastActivityAt = Date.now();
    const full = { ...event, sessionId: this.id, seq: this.seq } as ServerEvent;
    this.options.emit(full);
    return full;
  }

  private currentTurn(): Turn {
    return deriveTurn({
      started: this.started,
      closed: this.closed,
      failed: this.failed,
      finished: this.finished,
      pending: [...this.pending.values()]
        .sort((a, b) => a.createdAt - b.createdAt)
        .map((p) => ({ requestId: p.requestId, kind: p.kind, summary: p.summary })),
    });
  }

  /** Emits a turn event only when the derived turn actually changed. */
  private broadcastTurn(): void {
    const turn = this.currentTurn();
    const key = `${turn.state}|${turn.reason}|${turn.requestId ?? ""}|${turn.summary}`;
    if (key === this.lastTurnKey) return;
    this.lastTurnKey = key;
    this.emit({
      type: "turn",
      state: turn.state,
      reason: turn.reason,
      requestId: turn.requestId,
      sessionName: this.name,
      summary: turn.summary,
    });
  }

  // --- the pending-request map ---------------------------------------------

  private awaitDecision(request: ToolPermissionRequest): Promise<PermissionResult> {
    const requestId = `req_${randomUUID().replace(/-/g, "").slice(0, 12)}`;
    const questions = asQuestions(request);

    return new Promise<PermissionResult>((resolve) => {
      const event =
        questions !== null
          ? (this.emit({ type: "ask", requestId, questions }) as AskEvent)
          : (this.emit({
              type: "permission",
              requestId,
              tool: request.toolName,
              input: request.input,
              display: describeToolInput(request.toolName, request.input),
              suggestions: request.suggestions,
            }) as PermissionEvent);

      this.pending.set(requestId, {
        requestId,
        kind: questions !== null ? "ask" : "permission",
        summary: questions !== null ? summariseAsk(questions) : summarisePermission(request.toolName, request.input),
        toolName: request.toolName,
        input: request.input,
        suggestions: request.suggestions,
        event,
        resolve,
        createdAt: Date.now(),
      });

      // The agent gave up on this request before we answered it.
      request.signal.addEventListener(
        "abort",
        () => {
          if (this.pending.delete(requestId)) {
            this.emit({ type: "resolved", requestId, resolution: "cancelled", by: null });
            this.broadcastTurn();
            resolve({ behavior: "deny", message: "The request was cancelled." });
          }
        },
        { once: true },
      );

      this.broadcastTurn();
    });
  }

  private take(requestId: string, kind: PendingKind): Pending {
    const entry = this.pending.get(requestId);
    if (!entry) {
      throw new SessionError(
        this.seenRequestIds.has(requestId) ? "alreadyResolved" : "unknownRequest",
        this.seenRequestIds.has(requestId)
          ? "that request was already answered"
          : `no pending request ${requestId} in this session`,
      );
    }
    if (entry.kind !== kind) {
      throw new SessionError("malformed", `request ${requestId} is a ${entry.kind}, not a ${kind}`);
    }
    return entry;
  }

  private readonly seenRequestIds = new Set<string>();

  private settle(entry: Pending, result: PermissionResult, resolution: Resolution, by: string | null): void {
    this.pending.delete(entry.requestId);
    this.seenRequestIds.add(entry.requestId);
    entry.resolve(result);
    this.emit({ type: "resolved", requestId: entry.requestId, resolution, by });
    this.broadcastTurn();
  }

  // --- inbound from the watch ----------------------------------------------

  /**
   * Answer an AskUserQuestion. The original questions array must be passed back through
   * with the answers; the SDK's contract, not ours.
   */
  answer(requestId: string, answers: Record<string, string> | null, response: string | null, by: string | null): void {
    if ((answers === null) === (response === null)) {
      throw new SessionError("malformed", "an answer sets exactly one of `answers` and `response`");
    }
    const entry = this.take(requestId, "ask");
    const questions = (entry.input as { questions?: unknown }).questions ?? [];
    const updatedInput: ToolInput = answers !== null ? { questions, answers } : { questions, response };
    this.settle(entry, { behavior: "allow", updatedInput }, "answered", by);
  }

  decide(requestId: string, decision: PermissionDecision, message: string | null, by: string | null): void {
    const entry = this.take(requestId, "permission");
    if (decision === "deny") {
      const reason = message?.trim() || "The user denied this from their watch.";
      this.settle(entry, { behavior: "deny", message: reason }, "denied", by);
      return;
    }
    const result: PermissionResult = { behavior: "allow", updatedInput: entry.input };
    if (decision === "allowAlways") {
      // Only localSettings rules; a wrist tap should not write to your user-level config.
      const persistable = entry.suggestions.filter((s) => s.destination === "localSettings");
      if (persistable.length > 0) result.updatedPermissions = persistable;
    }
    this.settle(entry, result, "allowed", by);
  }

  /** Report a failure against this session's own seq stream, so the watch sees it in order. */
  emitError(code: ErrorCode, message: string, requestId: string | null = null): void {
    this.emit({ type: "error", code, message, requestId });
  }

  prompt(text: string): void {
    if (this.closed) throw new SessionError("unknownSession", "that chat is closed");
    this.finished = false;
    this.handle.push(text);
    this.broadcastTurn();
  }

  async interrupt(): Promise<void> {
    for (const entry of [...this.pending.values()]) {
      this.settle(entry, { behavior: "deny", message: "The user interrupted the agent." }, "interrupted", null);
    }
    await this.handle.interrupt();
    this.broadcastTurn();
  }

  setMode(mode: PermissionMode): void {
    this.mode = mode;
    this.handle.setPermissionMode(mode);
    this.emit({ type: "text", text: `Permission mode is now ${mode}.` });
  }

  rename(name: string): void {
    this.name = name;
    this.lastTurnKey = "";
    this.broadcastTurn();
  }

  async close(): Promise<void> {
    if (this.closed) return;
    for (const entry of [...this.pending.values()]) {
      this.settle(entry, { behavior: "deny", message: "The session was closed." }, "cancelled", null);
    }
    this.closed = true;
    await this.handle.close();
    this.broadcastTurn();
  }

  // --- views ----------------------------------------------------------------

  /** Replayed verbatim on `subscribe`, across every session, so three blocked chats come back as three cards. */
  outstandingEvents(): ServerEvent[] {
    return [...this.pending.values()].sort((a, b) => a.createdAt - b.createdAt).map((p) => p.event);
  }

  summary(): SessionSummary {
    const turn = this.currentTurn();
    return {
      sessionId: this.id,
      name: this.name,
      cwd: this.cwd,
      state: turn.state,
      mode: this.mode,
      seq: this.seq,
      pendingRequestIds: [...this.pending.values()].sort((a, b) => a.createdAt - b.createdAt).map((p) => p.requestId),
      createdAt: this.createdAt,
      lastActivityAt: this.lastActivityAt,
    };
  }

  get resumeId(): string | null {
    return this.agentSessionId;
  }

  get displayName(): string {
    return this.name;
  }
}

/** AskUserQuestion is the one tool the bridge understands the input of. */
function asQuestions(request: ToolPermissionRequest): AskQuestion[] | null {
  if (request.toolName !== ASK_USER_QUESTION) return null;
  const questions = (request.input as { questions?: unknown }).questions;
  if (!Array.isArray(questions)) {
    log.warn("AskUserQuestion without a questions array; rendering it as a permission card");
    return null;
  }
  return questions as AskQuestion[];
}
