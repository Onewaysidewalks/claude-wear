/**
 * FakeAgentRunner -- replays a scripted scenario instead of talking to Claude.
 *
 * No API key, no network, no subprocess. `--fake` gives you a fully interactive bridge
 * whose blocking behaviour is the real thing: an `askUserQuestion` or `permission` step
 * genuinely parks the script until a decision comes back, exactly as `canUseTool` parks
 * the agent.
 */
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import type { AskQuestion, DoneSubtype, PermissionMode, PermissionSuggestion } from "../protocol.js";
import { log } from "../log.js";
import {
  type AgentHandle,
  type AgentRunner,
  type PermissionResult,
  type RunnerOptions,
  type ToolInput,
  ASK_USER_QUESTION,
} from "./types.js";

export const DEFAULT_SCENARIO_DIR = fileURLToPath(new URL("../../test/scenarios", import.meta.url));

export type ScenarioStep = { at: number } & (
  | { emit: { type: "assistant"; text: string } }
  | {
      emit: {
        type: "result";
        subtype?: DoneSubtype;
        result?: string | null;
        numTurns?: number;
      };
    }
  | { askUserQuestion: { questions: AskQuestion[] } }
  | { permission: { tool: string; input: ToolInput; suggestions?: PermissionSuggestion[] } }
  | { expectAnswer: true | { behavior: "allow" | "deny" } }
  | { awaitPrompt: true }
);

export type Scenario = ScenarioStep[];

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

export function parseScenario(name: string, raw: string): Scenario {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    throw new Error(`scenario ${name} is not valid JSON: ${(err as Error).message}`);
  }
  assert(Array.isArray(parsed), `scenario ${name} must be a JSON array of steps`);
  parsed.forEach((step, i) => {
    const where = `scenario ${name} step ${i}`;
    assert(typeof step === "object" && step !== null, `${where} must be an object`);
    const s = step as Record<string, unknown>;
    assert(typeof s.at === "number" && s.at >= 0, `${where} needs a non-negative \`at\` delay in ms`);
    const kinds = ["emit", "askUserQuestion", "permission", "expectAnswer", "awaitPrompt"].filter((k) => k in s);
    assert(kinds.length === 1, `${where} must have exactly one of emit/askUserQuestion/permission/expectAnswer/awaitPrompt`);
  });
  return parsed as Scenario;
}

export function loadScenarios(dir: string): Map<string, Scenario> {
  const out = new Map<string, Scenario>();
  for (const file of readdirSync(dir).sort()) {
    if (!file.endsWith(".json")) continue;
    const name = file.slice(0, -".json".length);
    out.set(name, parseScenario(name, readFileSync(join(dir, file), "utf8")));
  }
  return out;
}

export interface FakeAgentRunnerConfig {
  scenarioDir?: string;
  /**
   * Scenarios handed to successive sessions, cycling. Two entries is how the multi-session
   * cases get two chats blocked on you at the same time with different scripts.
   */
  rotation?: string[];
  /** Multiplies every step's `at`. 0 runs a scenario as fast as the event loop allows. */
  timeScale?: number;
}

class FakeAgentHandle implements AgentHandle {
  private readonly prompts: string[] = [];
  private promptWaiter: (() => void) | null = null;
  private readonly abort = new AbortController();
  private lastDecision: PermissionResult | null = null;
  private exhausted = false;
  private readonly startedAt = Date.now();
  private turns = 0;

  constructor(
    private readonly scenarioName: string,
    private readonly scenario: Scenario,
    private readonly options: RunnerOptions,
    private readonly timeScale: number,
  ) {
    void this.run();
  }

  push(text: string): void {
    this.prompts.push(text);
    this.turns += 1;
    const waiter = this.promptWaiter;
    this.promptWaiter = null;
    if (waiter) {
      waiter();
    } else if (this.exhausted) {
      this.replyExhausted();
    }
  }

  async interrupt(): Promise<void> {
    if (this.abort.signal.aborted) return;
    this.abort.abort(new Error("interrupted"));
    this.options.onMessage({
      type: "result",
      subtype: "error_during_execution",
      isError: true,
      durationMs: Date.now() - this.startedAt,
      numTurns: this.turns,
      result: null,
    });
  }

  setPermissionMode(mode: PermissionMode): boolean {
    log.debug("fake runner permission mode", { sessionId: this.options.sessionId, mode });
    return true;
  }

  async close(): Promise<void> {
    if (!this.abort.signal.aborted) this.abort.abort(new Error("closed"));
  }

  private replyExhausted(): void {
    this.options.onMessage({
      type: "assistant",
      text: `The ${this.scenarioName} scenario has no more scripted steps.`,
    });
    this.options.onMessage({
      type: "result",
      subtype: "success",
      isError: false,
      durationMs: 0,
      numTurns: this.turns,
      result: "scenario exhausted",
    });
  }

  private sleep(ms: number): Promise<void> {
    const scaled = Math.round(ms * this.timeScale);
    if (scaled <= 0) return Promise.resolve();
    return new Promise((resolve) => {
      const timer = setTimeout(finish, scaled);
      const onAbort = () => finish();
      function finish() {
        clearTimeout(timer);
        resolve();
      }
      this.abort.signal.addEventListener("abort", onAbort, { once: true });
    });
  }

  private nextPrompt(): Promise<void> {
    if (this.prompts.length > 0) {
      this.prompts.shift();
      return Promise.resolve();
    }
    return new Promise((resolve) => {
      this.promptWaiter = () => {
        this.prompts.shift();
        resolve();
      };
      this.abort.signal.addEventListener("abort", () => resolve(), { once: true });
    });
  }

  private async decide(toolName: string, input: ToolInput, suggestions: PermissionSuggestion[]): Promise<void> {
    this.lastDecision = await this.options.canUseTool({
      toolName,
      input,
      suggestions,
      signal: this.abort.signal,
    });
  }

  private async run(): Promise<void> {
    try {
      // Never call back into the caller before its constructor has finished. The real SDK
      // emits its init message off a subprocess, so nothing downstream should assume it
      // can arrive synchronously either.
      await Promise.resolve();
      this.options.onMessage({ type: "init", agentSessionId: `fake_${this.options.sessionId}` });

      for (const step of this.scenario) {
        if (this.abort.signal.aborted) return;
        await this.sleep(step.at);
        if (this.abort.signal.aborted) return;

        if ("emit" in step) {
          if (step.emit.type === "assistant") {
            this.options.onMessage({ type: "assistant", text: step.emit.text });
          } else {
            this.turns += 1;
            this.options.onMessage({
              type: "result",
              subtype: step.emit.subtype ?? "success",
              isError: (step.emit.subtype ?? "success") !== "success",
              durationMs: Date.now() - this.startedAt,
              numTurns: step.emit.numTurns ?? this.turns,
              result: step.emit.result ?? null,
            });
          }
        } else if ("askUserQuestion" in step) {
          await this.decide(ASK_USER_QUESTION, step.askUserQuestion as unknown as ToolInput, []);
        } else if ("permission" in step) {
          await this.decide(step.permission.tool, step.permission.input, step.permission.suggestions ?? []);
        } else if ("expectAnswer" in step) {
          const want = step.expectAnswer === true ? "allow" : step.expectAnswer.behavior;
          const got = this.lastDecision?.behavior;
          if (got !== want) {
            throw new Error(
              `scenario ${this.scenarioName}: expected the last decision to be ${want}, got ${got ?? "nothing"}`,
            );
          }
        } else {
          await this.nextPrompt();
        }
      }

      this.exhausted = true;
      if (this.prompts.length > 0) this.replyExhausted();
    } catch (err) {
      if (this.abort.signal.aborted) return;
      this.options.onError(err instanceof Error ? err : new Error(String(err)));
    }
  }
}

export class FakeAgentRunner implements AgentRunner {
  readonly name = "fake";
  private readonly scenarios: Map<string, Scenario>;
  private readonly rotation: string[];
  private readonly timeScale: number;
  private started = 0;

  constructor(config: FakeAgentRunnerConfig = {}) {
    this.scenarios = loadScenarios(config.scenarioDir ?? DEFAULT_SCENARIO_DIR);
    this.timeScale = config.timeScale ?? 1;
    const rotation = config.rotation?.length ? config.rotation : ["auq-then-bash"];
    for (const name of rotation) {
      if (!this.scenarios.has(name)) {
        throw new Error(`unknown scenario \`${name}\`; have ${[...this.scenarios.keys()].join(", ")}`);
      }
    }
    this.rotation = rotation;
  }

  get scenarioNames(): string[] {
    return [...this.scenarios.keys()];
  }

  start(options: RunnerOptions): AgentHandle {
    const name = options.scenario ?? this.rotation[this.started % this.rotation.length]!;
    this.started += 1;
    const scenario = this.scenarios.get(name);
    if (!scenario) throw new Error(`unknown scenario \`${name}\``);
    log.info("fake agent started", { sessionId: options.sessionId, scenario: name });
    return new FakeAgentHandle(name, scenario, options, this.timeScale);
  }
}
