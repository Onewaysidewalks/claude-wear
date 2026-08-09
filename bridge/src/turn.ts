/**
 * Turn detection -- the core of the product.
 *
 * The watch buzzes when, and only when, the agent is blocked on you. Everything that
 * decides that lives here as a pure function, so it is testable without a socket, a
 * session, or an agent.
 */
import type { AskQuestion, SessionState, TurnReason } from "./protocol.js";
import type { ToolInput } from "./runner/types.js";

export type PendingKind = "ask" | "permission";

export interface PendingSummary {
  requestId: string;
  kind: PendingKind;
  /** One line, already shortened for a 1.5" screen. */
  summary: string;
}

export interface TurnInputs {
  /** The runner has emitted its init message. */
  started: boolean;
  closed: boolean;
  failed: boolean;
  /** A result arrived and nothing has been asked of the agent since. */
  finished: boolean;
  /** Outstanding canUseTool requests, oldest first. */
  pending: PendingSummary[];
}

export interface Turn {
  state: SessionState;
  reason: TurnReason;
  requestId: string | null;
  summary: string;
}

/** How long a summary may be before it stops being readable at a glance. */
const SUMMARY_MAX = 64;

export function shorten(text: string, max = SUMMARY_MAX): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length <= max ? flat : `${flat.slice(0, max - 1).trimEnd()}…`;
}

/**
 * The actual command or path, never a summary of it. Approving something you cannot see
 * is the failure mode this product designs against, and it gets worse the smaller the
 * screen -- so this stays verbatim, and only the *turn summary* is ever shortened.
 */
export function describeToolInput(tool: string, input: ToolInput): string {
  const str = (key: string): string | null => (typeof input[key] === "string" ? (input[key] as string) : null);
  switch (tool) {
    case "Bash":
      return str("command") ?? "";
    case "Read":
    case "Write":
    case "Edit":
    case "NotebookEdit":
      return str("file_path") ?? "";
    case "Glob":
    case "Grep":
      return str("pattern") ?? "";
    case "WebFetch":
      return str("url") ?? "";
    default: {
      const json = JSON.stringify(input);
      return json === "{}" ? "" : json;
    }
  }
}

/** The one line a permission notification leads with. */
export function summarisePermission(tool: string, input: ToolInput): string {
  const display = describeToolInput(tool, input);
  switch (tool) {
    case "Bash":
      return shorten(`may I run ${display}?`);
    case "Write":
    case "Edit":
    case "NotebookEdit":
      return shorten(`may I edit ${display}?`);
    case "Read":
      return shorten(`may I read ${display}?`);
    default:
      return shorten(display ? `may I use ${tool} on ${display}?` : `may I use ${tool}?`);
  }
}

/** The one line a question notification leads with: the first question, verbatim. */
export function summariseAsk(questions: AskQuestion[]): string {
  const first = questions[0];
  if (!first) return "a question for you";
  const more = questions.length > 1 ? ` (+${questions.length - 1} more)` : "";
  return shorten(`${first.question}${more}`);
}

/**
 * The oldest outstanding request wins. Two sessions competing for your attention are two
 * notifications; two requests inside one session are answered in the order the agent
 * asked, which is the only order that keeps its own control flow making sense.
 */
export function deriveTurn(inputs: TurnInputs): Turn {
  if (inputs.closed) {
    return { state: "closed", reason: "result", requestId: null, summary: "closed" };
  }
  if (inputs.failed) {
    return { state: "error", reason: "failed", requestId: null, summary: "the agent stopped with an error" };
  }
  const oldest = inputs.pending[0];
  if (oldest) {
    return {
      state: "awaiting",
      reason: oldest.kind,
      requestId: oldest.requestId,
      summary: oldest.summary,
    };
  }
  if (inputs.finished) {
    return { state: "idle", reason: "result", requestId: null, summary: "Your turn" };
  }
  if (!inputs.started) {
    return { state: "starting", reason: "started", requestId: null, summary: "starting" };
  }
  return { state: "working", reason: "started", requestId: null, summary: "working" };
}

/** True when this turn is one the wearer should feel. */
export function shouldAlert(turn: Turn): boolean {
  return turn.state === "awaiting" || turn.state === "idle" || turn.state === "error";
}

export const ALERTING_STATES: readonly SessionState[] = ["awaiting", "idle", "error"];

export type { SessionState, TurnReason };
