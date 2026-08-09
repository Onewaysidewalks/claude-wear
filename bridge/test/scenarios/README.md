# Fake agent scenarios

A scenario is a JSON array of steps that `FakeAgentRunner` replays instead of calling
Claude. `at` is a delay in milliseconds before the step runs; each step has exactly one of:

| Step | Effect |
| --- | --- |
| `emit: {type: "assistant", text}` | assistant text -> a `text` event |
| `emit: {type: "result", subtype?, result?, numTurns?}` | -> a `done` event, and the session goes idle |
| `askUserQuestion: {questions}` | **blocks** the script until answered, exactly as `canUseTool` blocks the agent |
| `permission: {tool, input, suggestions?}` | **blocks** the script until decided |
| `expectAnswer: true \| {behavior}` | asserts what the previous block resolved to; a mismatch fails the session |
| `awaitPrompt: true` | blocks until the wearer dictates a follow-up |

When the script runs out the session stays usable: a further prompt gets a canned reply and
a success result, so the watch never ends up talking to a dead chat.

`--scenarios a,b,c` assigns scenarios to successive sessions, cycling — which is how the
multi-session cases get two chats blocked on you at the same time with different scripts.

| Scenario | Covers |
| --- | --- |
| `auq-then-bash` | the plan's worked example: text, a question, then a Bash permission with an "always allow" suggestion |
| `quick-idle` | no decisions at all — just text and a result. The "buzz on a `result`" path |
| `multi-turn` | result, wait for a dictated prompt, result again. The voice-reply loop |
| `denied-rm-rf` | a dangerous command that the scenario asserts you denied, then adapts |
| `four-question-multiselect` | the widest AUQ the SDK allows: 4 questions, one `multiSelect` with 4 options |
| `slow-permission` | blocks for ten minutes. Used for disconnect/replay, for answering from a second client, and for two sessions waiting at once |
