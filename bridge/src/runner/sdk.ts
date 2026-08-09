/**
 * The real AgentRunner: wraps `query()` from @anthropic-ai/claude-agent-sdk.
 *
 * Driven by a **streaming input AsyncIterable** rather than a one-shot string prompt. That
 * is what lets a follow-up message be pushed into a live session, and it is what keeps
 * `canUseTool` -- and therefore the whole watch interaction -- reachable at all. The
 * control methods (`interrupt`, `setPermissionMode`) only exist in streaming mode too.
 *
 * Everything above this file is runner-agnostic, so the mapping work all happens here:
 * SDK message -> RunnerMessage, SDK PermissionUpdate <-> wire PermissionSuggestion, and
 * the resume-on-restart fallback.
 */
import type {
  Options as SdkOptions,
  PermissionMode as SdkPermissionMode,
  PermissionResult as SdkPermissionResult,
  PermissionUpdate,
  Query,
  SDKAssistantMessage,
  SDKMessage,
  SDKUserMessage,
} from "@anthropic-ai/claude-agent-sdk";
import type { DoneSubtype, PermissionMode, PermissionSuggestion } from "../protocol.js";
import { log } from "../log.js";
import type { AgentHandle, AgentRunner, RunnerOptions } from "./types.js";

export type QueryFn = (params: {
  prompt: string | AsyncIterable<SDKUserMessage>;
  options?: SdkOptions;
}) => Query;

/**
 * The bridge's permission modes must all be modes the SDK understands. This is a
 * compile-time assertion, not a runtime one: if the protocol ever grows a mode the SDK
 * does not have, this file stops compiling instead of failing on someone's wrist.
 */
const _modesAreASubsetOfTheSdks: readonly SdkPermissionMode[] = [] as readonly PermissionMode[];
void _modesAreASubsetOfTheSdks;

/**
 * Loaded on first use rather than at import time, so `--fake` -- the CI default and the
 * whole no-API-key story -- never pays for a 4 MB dependency it will not call.
 */
let cachedQuery: QueryFn | null = null;
async function realQuery(): Promise<QueryFn> {
  cachedQuery ??= (await import("@anthropic-ai/claude-agent-sdk")).query as QueryFn;
  return cachedQuery;
}

export interface SdkAgentRunnerConfig {
  /** Injected by tests so every mapping below is exercised with no API key and no network. */
  query?: QueryFn;
  /**
   * `bypassPermissions` skips `canUseTool` entirely -- the agent stops asking, and the
   * watch stops buzzing. The SDK requires an explicit opt-in for it and so do we.
   */
  allowBypassPermissions?: boolean;
}

/** The SDK's result subtypes are a superset of the three the wire protocol carries. */
function toDoneSubtype(subtype: string): DoneSubtype {
  if (subtype === "success" || subtype === "error_max_turns") return subtype;
  // error_max_budget_usd, error_max_structured_output_retries and anything added later.
  return "error_during_execution";
}

/** Assistant text, joined. The watch gets a summary, never a token-by-token stream. */
function assistantText(message: SDKAssistantMessage): string {
  // Thinking, tool_use and the rest are deliberately dropped: the watch renders what
  // Claude said, not how it got there.
  return message.message.content
    .flatMap((block) => (block.type === "text" ? [block.text] : []))
    .join("\n")
    .trim();
}

/** SDK suggestion -> the wire shape the watch renders. */
export function toWireSuggestion(update: PermissionUpdate): PermissionSuggestion {
  const rules =
    "rules" in update
      ? update.rules.map((rule) => ({ toolName: rule.toolName, ruleContent: rule.ruleContent ?? null }))
      : [];
  return {
    type: update.type,
    behavior: "behavior" in update ? update.behavior : null,
    destination: update.destination ?? null,
    rules,
  };
}

function sameSuggestion(a: PermissionSuggestion, b: PermissionSuggestion): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

class SdkAgentHandle implements AgentHandle {
  /** User messages waiting for the SDK to pull them off the input iterable. */
  private queue: string[] = [];
  /** Handed to the SDK on this attempt but not yet acted on; replayed if we retry. */
  private inFlight: string[] = [];
  private wake: (() => void) | null = null;
  private inputClosed = false;
  private query: Query | null = null;
  private closed = false;
  private sawAnyMessage = false;
  /** Bumped when an attempt is abandoned, so its input iterable stands down. */
  private generation = 0;
  private permissionMode: PermissionMode;

  constructor(
    private readonly options: RunnerOptions,
    private readonly queryFn: QueryFn,
    private readonly allowBypassPermissions: boolean,
  ) {
    this.permissionMode = options.permissionMode;
    void this.run();
  }

  // --- AgentHandle ----------------------------------------------------------

  push(text: string): void {
    if (this.inputClosed) return;
    this.queue.push(text);
    this.wakeInput();
  }

  async interrupt(): Promise<void> {
    try {
      await this.query?.interrupt();
    } catch (err) {
      // Interrupting a session that is not mid-turn is a no-op, not a failure the wearer
      // needs to see -- the pending requests have already been denied by Session.
      log.debug("interrupt was rejected", { sessionId: this.options.sessionId, error: (err as Error).message });
    }
  }

  setPermissionMode(mode: PermissionMode): void {
    this.permissionMode = mode;
    if (mode === "bypassPermissions" && !this.allowBypassPermissions) {
      this.options.onError(
        new Error(
          "bypassPermissions is off on this bridge; start it with --allow-bypass-permissions if you really " +
            "want the agent to stop asking.",
        ),
      );
      return;
    }
    this.query?.setPermissionMode(mode).catch((err: Error) => {
      log.warn("the agent refused a permission-mode change", { sessionId: this.options.sessionId, mode, error: err.message });
      this.options.onError(new Error(`could not switch to ${mode}: ${err.message}`));
    });
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.inputClosed = true;
    this.wakeInput();
    try {
      this.query?.close();
    } catch (err) {
      log.debug("close threw", { sessionId: this.options.sessionId, error: (err as Error).message });
    }
  }

  // --- streaming input ------------------------------------------------------

  private wakeInput(): void {
    const wake = this.wake;
    this.wake = null;
    wake?.();
  }

  /**
   * Never ends until the session is closed. An input iterable that returns would end the
   * query, and with it the chat -- the whole point is that this session stays live between
   * turns, holding its context, waiting for the next thing you dictate.
   *
   * `generation` lets the abandoned attempt of a resume retry stand down. Only one waiter
   * is ever registered, so the old iterable cannot steal a message from the new one -- but
   * without this it would stay parked on `next()` for the life of the session instead of
   * returning.
   */
  private async *inputStream(generation: number): AsyncGenerator<SDKUserMessage> {
    for (;;) {
      while (this.queue.length === 0) {
        if (this.inputClosed || generation !== this.generation) return;
        await new Promise<void>((resolve) => {
          this.wake = resolve;
        });
        if (generation !== this.generation) return;
      }
      if (generation !== this.generation) return;
      const text = this.queue.shift()!;
      this.inFlight.push(text);
      yield {
        type: "user",
        parent_tool_use_id: null,
        message: { role: "user", content: text },
      } as SDKUserMessage;
    }
  }

  // --- the agent loop -------------------------------------------------------

  private buildOptions(resume: string | null): SdkOptions {
    const mode = this.permissionMode;
    const bypassing = mode === "bypassPermissions" && this.allowBypassPermissions;
    return {
      cwd: this.options.cwd,
      resume: resume ?? undefined,
      permissionMode: bypassing || mode !== "bypassPermissions" ? mode : "default",
      ...(bypassing ? { allowDangerouslySkipPermissions: true } : {}),
      canUseTool: async (toolName, input, { signal, suggestions }) => {
        // Keep the SDK's own objects and echo those back rather than reconstructing them
        // from the wire shape -- "always allow" writes a real rule to a real settings file,
        // and a lossy round-trip there is the kind of bug you find months later.
        const offered = (suggestions ?? []).map((sdk) => ({ sdk, wire: toWireSuggestion(sdk) }));
        const decision = await this.options.canUseTool({
          toolName,
          input,
          suggestions: offered.map((o) => o.wire),
          signal,
        });
        if (decision.behavior === "deny") return { behavior: "deny", message: decision.message };
        const allow: SdkPermissionResult = { behavior: "allow", updatedInput: decision.updatedInput };
        const kept = (decision.updatedPermissions ?? [])
          .map((wire) => offered.find((o) => sameSuggestion(o.wire, wire))?.sdk)
          .filter((sdk): sdk is PermissionUpdate => sdk !== undefined);
        if (kept.length > 0) allow.updatedPermissions = kept;
        return allow;
      },
      stderr: (data) => log.debug("agent stderr", { sessionId: this.options.sessionId, data: data.trimEnd() }),
    };
  }

  private async run(): Promise<void> {
    try {
      const query = this.queryFn;
      const resume = this.options.resume;
      try {
        await this.pump(query, resume);
      } catch (err) {
        // A resume that dies before producing a single message is almost always a
        // transcript the SDK cannot find -- a state file that outlived its ~/.claude
        // entry. Losing the history is much better than losing the chat, so start fresh
        // and say so, rather than leaving a session that can never be talked to.
        if (resume === null || this.sawAnyMessage || this.closed) throw err;
        log.warn("could not resume; starting a fresh agent session", {
          sessionId: this.options.sessionId,
          resume,
          error: (err as Error).message,
        });
        this.generation += 1;
        this.wakeInput();
        this.queue = [...this.inFlight, ...this.queue];
        this.inFlight = [];
        this.options.onMessage({
          type: "assistant",
          text: "Could not resume the previous conversation, so this chat is starting fresh.",
        });
        await this.pump(query, null);
      }
    } catch (err) {
      if (this.closed) return;
      this.options.onError(err instanceof Error ? err : new Error(String(err)));
    }
  }

  private async pump(query: QueryFn, resume: string | null): Promise<void> {
    const q = query({ prompt: this.inputStream(this.generation), options: this.buildOptions(resume) });
    this.query = q;
    for await (const message of q) {
      this.sawAnyMessage = true;
      this.dispatch(message);
      if (this.closed) return;
    }
  }

  private dispatch(message: SDKMessage): void {
    switch (message.type) {
      case "system":
        if (message.subtype === "init") {
          // The id to persist. On a resume the SDK may hand back a different one, so this
          // is read every time rather than only on the first run.
          this.options.onMessage({ type: "init", agentSessionId: message.session_id });
        }
        return;
      case "assistant": {
        // Subagent chatter belongs in the transcript, not on a 1.5" screen.
        if (message.parent_tool_use_id !== null) return;
        const text = assistantText(message);
        if (text) this.options.onMessage({ type: "assistant", text });
        return;
      }
      case "result": {
        // Every message the SDK has pulled has now been acted on.
        this.inFlight = [];
        this.options.onMessage({
          type: "result",
          subtype: toDoneSubtype(message.subtype),
          isError: message.is_error,
          durationMs: message.duration_ms,
          numTurns: message.num_turns,
          result: message.subtype === "success" ? message.result : null,
        });
        return;
      }
      default:
        // Partial assistant deltas, tool progress, hook events, compact boundaries: all
        // real and all deliberately not rendered. See "Deferred" in PLAN.md.
        return;
    }
  }
}

export class SdkAgentRunner implements AgentRunner {
  readonly name = "sdk";
  private readonly config: SdkAgentRunnerConfig;
  private queryFn: QueryFn | null;

  constructor(config: SdkAgentRunnerConfig = {}) {
    this.config = config;
    this.queryFn = config.query ?? null;
  }

  /** Resolves the SDK import up front so `start()` can stay synchronous. */
  async prepare(): Promise<void> {
    this.queryFn ??= await realQuery();
  }

  start(options: RunnerOptions): AgentHandle {
    if (!this.queryFn) {
      throw new Error("SdkAgentRunner.prepare() must be awaited before the first session starts");
    }
    log.info("agent session starting", {
      sessionId: options.sessionId,
      cwd: options.cwd,
      resume: options.resume,
      permissionMode: options.permissionMode,
    });
    return new SdkAgentHandle(options, this.queryFn, this.config.allowBypassPermissions ?? false);
  }
}
