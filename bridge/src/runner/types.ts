/**
 * The AgentRunner seam.
 *
 * `sdk.ts` wraps the real Agent SDK `query()`; `fake.ts` replays a scripted scenario with
 * no API key and no network. Everything above this interface -- SessionRegistry, turn
 * derivation, the wire protocol -- is identical either way, which is what makes the whole
 * product testable on a laptop.
 */
import type { AskQuestion, DoneSubtype, PermissionMode, PermissionSuggestion } from "../protocol.js";

/** The tool input, verbatim from the agent. */
export type ToolInput = Record<string, unknown>;

/** What `canUseTool` is asked, and what it must answer. */
export interface ToolPermissionRequest {
  toolName: string;
  input: ToolInput;
  /** "Always allow" rules the agent offered; echoed back in updatedPermissions if taken. */
  suggestions: PermissionSuggestion[];
  /** Aborted when the agent gives up on the request (interrupt, session close). */
  signal: AbortSignal;
}

export type PermissionResult =
  | { behavior: "allow"; updatedInput: ToolInput; updatedPermissions?: PermissionSuggestion[] }
  | { behavior: "deny"; message: string };

/** The subset of SDKMessage the watch actually renders. */
export type RunnerMessage =
  | { type: "init"; agentSessionId: string }
  | { type: "assistant"; text: string }
  | {
      type: "result";
      subtype: DoneSubtype;
      isError: boolean;
      durationMs: number;
      numTurns: number;
      result: string | null;
    };

export interface RunnerOptions {
  /** The bridge's id for this chat. Not the agent's own session id, which arrives on init. */
  sessionId: string;
  cwd: string;
  /** Agent session id to resume, set when the bridge restarts under a live chat. */
  resume: string | null;
  permissionMode: PermissionMode;
  /** Fake runner only: which scenario to replay. */
  scenario: string | null;
  /**
   * Blocks the agent until the wearer answers. The SDK waits indefinitely and so do we --
   * that is the whole point, the agent genuinely pauses while the watch is in your pocket.
   */
  canUseTool(request: ToolPermissionRequest): Promise<PermissionResult>;
  onMessage(message: RunnerMessage): void;
  onError(error: Error): void;
}

export interface AgentHandle {
  /** Push a follow-up user message into the live session. */
  push(text: string): void;
  interrupt(): Promise<void>;
  /**
   * False when the runner refused outright -- today only `bypassPermissions` on a bridge
   * that did not opt into it. The session keeps its old mode in that case, because a watch
   * showing `bypassPermissions` over an agent that is still asking is the one direction
   * this control must never be wrong in.
   */
  setPermissionMode(mode: PermissionMode): boolean;
  close(): Promise<void>;
}

export interface AgentRunner {
  /** Shown at startup so it is obvious whether a bridge is talking to Claude or to a fixture. */
  readonly name: string;
  start(options: RunnerOptions): AgentHandle;
}

/** Convenience for the AskUserQuestion input shape, which the bridge special-cases. */
export interface AskUserQuestionInput {
  questions: AskQuestion[];
}

export const ASK_USER_QUESTION = "AskUserQuestion";
