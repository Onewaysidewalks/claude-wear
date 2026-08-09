/**
 * A stand-in for the Agent SDK's `query()`.
 *
 * The real thing needs an API key, a network and a subprocess. This one needs none of
 * them, and it is shaped exactly like the surface `sdk.ts` actually touches: an async
 * generator of SDKMessages, plus the control methods. That keeps every mapping in the real
 * runner -- message translation, the canUseTool round-trip, resume fallback -- under test
 * in CI with no secret.
 */
import type {
  Options as SdkOptions,
  PermissionResult as SdkPermissionResult,
  PermissionUpdate,
  Query,
  SDKMessage,
  SDKUserMessage,
} from "@anthropic-ai/claude-agent-sdk";
import type { QueryFn } from "../src/runner/sdk.js";

export interface QueryCall {
  options: SdkOptions;
  query: FakeQuery;
}

export class FakeQuery {
  /** User messages the SDK has pulled off the streaming-input iterable. */
  readonly received: string[] = [];
  readonly modes: string[] = [];
  interrupts = 0;
  closes = 0;
  asks = 0;
  /** Set to make a control method reject, the way the SDK does when it is not applicable. */
  interruptError: Error | null = null;
  setModeError: Error | null = null;

  private readonly outbox: SDKMessage[] = [];
  private wake: (() => void) | null = null;
  private done = false;
  private thrown: Error | null = null;

  constructor(readonly options: SdkOptions) {}

  // --- what the test drives -------------------------------------------------

  emit(message: SDKMessage): void {
    this.outbox.push(message);
    this.flush();
  }

  /** End the stream the way a finished agent process does. */
  end(): void {
    this.done = true;
    this.flush();
  }

  /** End the stream the way a failing one does. */
  fail(error: Error): void {
    this.thrown = error;
    this.done = true;
    this.flush();
  }

  /** Fires canUseTool and returns the promise the agent is blocked on. */
  askToUse(
    toolName: string,
    input: Record<string, unknown>,
    suggestions: PermissionUpdate[] = [],
    signal: AbortSignal = new AbortController().signal,
  ): Promise<SdkPermissionResult | null> {
    const canUseTool = this.options.canUseTool;
    if (!canUseTool) throw new Error("the runner did not pass a canUseTool callback");
    this.asks += 1;
    return canUseTool(toolName, input, {
      signal,
      suggestions,
      toolUseID: `toolu_${this.asks}`,
      requestId: `ctrl_${this.asks}`,
    });
  }

  /** Resolves once the SDK side has pulled `count` user messages. */
  async waitForInput(count: number, timeoutMs = 2000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (this.received.length < count) {
      if (Date.now() > deadline) throw new Error(`only ${this.received.length} of ${count} messages arrived`);
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
  }

  // --- the Query surface ----------------------------------------------------

  private flush(): void {
    const wake = this.wake;
    this.wake = null;
    wake?.();
  }

  private async *messages(): AsyncGenerator<SDKMessage, void> {
    for (;;) {
      while (this.outbox.length === 0) {
        if (this.thrown) throw this.thrown;
        if (this.done) return;
        await new Promise<void>((resolve) => {
          this.wake = resolve;
        });
      }
      yield this.outbox.shift()!;
    }
  }

  private async drain(prompt: AsyncIterable<SDKUserMessage>): Promise<void> {
    for await (const message of prompt) {
      const content = message.message.content;
      this.received.push(typeof content === "string" ? content : JSON.stringify(content));
    }
  }

  asQuery(prompt: string | AsyncIterable<SDKUserMessage>): Query {
    if (typeof prompt !== "string") void this.drain(prompt);
    const generator = this.messages();
    return Object.assign(generator, {
      interrupt: async () => {
        this.interrupts += 1;
        if (this.interruptError) throw this.interruptError;
        return undefined;
      },
      setPermissionMode: async (mode: string) => {
        if (this.setModeError) throw this.setModeError;
        this.modes.push(mode);
      },
      close: () => {
        this.closes += 1;
        this.end();
      },
    }) as unknown as Query;
  }
}

/** A `query()` that records every call, so a test can assert on resume and permissionMode. */
export function fakeQueryFn(): { fn: QueryFn; calls: QueryCall[] } {
  const calls: QueryCall[] = [];
  const fn: QueryFn = ({ prompt, options }) => {
    const query = new FakeQuery(options ?? {});
    calls.push({ options: options ?? {}, query });
    return query.asQuery(prompt);
  };
  return { fn, calls };
}

// --- SDKMessage builders ----------------------------------------------------

export function initMessage(sessionId: string): SDKMessage {
  return { type: "system", subtype: "init", session_id: sessionId, uuid: "u-init" } as unknown as SDKMessage;
}

export function assistantMessage(
  blocks: { type: string; text?: string }[],
  extra: { parent_tool_use_id?: string | null } = {},
): SDKMessage {
  return {
    type: "assistant",
    parent_tool_use_id: extra.parent_tool_use_id ?? null,
    message: { role: "assistant", content: blocks },
    uuid: "u-assistant",
    session_id: "s",
  } as unknown as SDKMessage;
}

export function resultMessage(overrides: Partial<Record<string, unknown>> = {}): SDKMessage {
  return {
    type: "result",
    subtype: "success",
    is_error: false,
    duration_ms: 1234,
    num_turns: 2,
    result: "all done",
    session_id: "s",
    uuid: "u-result",
    ...overrides,
  } as unknown as SDKMessage;
}
