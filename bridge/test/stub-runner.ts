import type { PermissionSuggestion } from "../src/protocol.js";
import type {
  AgentHandle,
  AgentRunner,
  PermissionResult,
  RunnerMessage,
  RunnerOptions,
  ToolInput,
} from "../src/runner/types.js";

/**
 * A runner the test drives directly, so assertions can be about what the *bridge* handed
 * back to `canUseTool` — the AUQ answer shape, the deny message, the persisted rules.
 * FakeAgentRunner is the thing under test elsewhere; here it would only be in the way.
 */
export class StubRunner implements AgentRunner {
  readonly name = "stub";
  options!: RunnerOptions;
  readonly prompts: string[] = [];
  readonly modes: string[] = [];
  interrupted = 0;
  closed = 0;

  start(options: RunnerOptions): AgentHandle {
    this.options = options;
    return {
      push: (text) => this.prompts.push(text),
      interrupt: async () => {
        this.interrupted += 1;
      },
      setPermissionMode: (mode) => {
        this.modes.push(mode);
      },
      close: async () => {
        this.closed += 1;
      },
    };
  }

  emit(message: RunnerMessage): void {
    this.options.onMessage(message);
  }

  fail(message: string): void {
    this.options.onError(new Error(message));
  }

  /** Fires canUseTool and hands back the promise the agent would be blocked on. */
  askToUse(
    toolName: string,
    input: ToolInput,
    suggestions: PermissionSuggestion[] = [],
  ): { result: Promise<PermissionResult>; controller: AbortController } {
    const controller = new AbortController();
    const result = this.options.canUseTool({ toolName, input, suggestions, signal: controller.signal });
    return { result, controller };
  }
}
