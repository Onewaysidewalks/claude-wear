/**
 * The real AgentRunner: wraps `query()` from @anthropic-ai/claude-agent-sdk.
 *
 * M1. It is a stub on purpose -- M0's whole point is that the scaffold, the protocol, the
 * session machinery and the E2E loop are green before any of this needs an API key. The
 * shape below is what M1 fills in:
 *
 *   const q = query({
 *     prompt: this.inputStream(),          // AsyncIterable<SDKUserMessage>
 *     options: {
 *       cwd: options.cwd,
 *       resume: options.resume ?? undefined,
 *       permissionMode: options.permissionMode,
 *       canUseTool: (toolName, input, { signal, suggestions }) =>
 *         options.canUseTool({ toolName, input, suggestions: suggestions ?? [], signal }),
 *     },
 *   });
 *
 * and then: q.interrupt() for AgentHandle.interrupt, q.setPermissionMode for setPermissionMode,
 * q.close() for close, session_id off the init system message for RunnerMessage "init".
 */
import type { AgentHandle, AgentRunner, RunnerOptions } from "./types.js";

export class SdkAgentRunner implements AgentRunner {
  readonly name = "sdk";

  start(_options: RunnerOptions): AgentHandle {
    throw new Error(
      "The real Agent SDK runner lands in M1. Start the bridge with --fake (or FAKE_AGENT=1) to " +
        "drive scripted scenarios with no API key.",
    );
  }
}
