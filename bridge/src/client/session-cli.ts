/**
 * The terminal half of `bridge-cli`: everything that turns server events into lines of
 * text, and typed lines into client messages.
 *
 * It speaks exactly the protocol the watch speaks -- same `hello`, same `subscribe`, same
 * `answer` and `permission` frames -- so driving a real Claude session from a terminal
 * proves the bridge, not a second code path. Kept free of sockets and stdin so a test can
 * drive it directly and so the wiring in `bridge-cli.ts` stays trivially small.
 */
import type {
  AskEvent,
  AskQuestion,
  ClientMessage,
  PermissionEvent,
  PermissionMode,
  ServerEvent,
  SessionSummary,
} from "../protocol.js";
import { PERMISSION_MODES } from "../protocol.js";

export interface PendingRequest {
  sessionId: string;
  requestId: string;
  event: AskEvent | PermissionEvent;
}

export interface BridgeCliOptions {
  send(message: ClientMessage): void;
  write(line: string): void;
  /** Terminal colour. Off when stdout is not a TTY, and off in tests. */
  colour?: boolean;
  quit?(): void;
}

const HELP = `
  <text>                  send it as a prompt to the current chat
  /new <cwd> [name]       start a chat in a directory that already exists
  /sessions               list chats; /use <n|id> picks the current one
  /use <n|id>             switch chats
  /rename <name>          relabel the current chat
  /mode <mode>            ${PERMISSION_MODES.join(" | ")}
  /interrupt              stop the agent mid-turn
  /pending                show every request waiting on you, across all chats

  answering a question    /1  /2 …            pick an option
                          /1,3                pick several when multiSelect
                          /other <text>       answer in your own words
                          /say <text>         dismiss the questions and just talk
  answering a permission  /y                  allow, once
                          /always             allow and persist the rule
                          /n [reason]         deny; Claude sees the reason

  /resync                 re-request everything the bridge is holding
  /help  /quit
`;

const COLOURS = {
  reset: "[0m",
  dim: "[2m",
  bold: "[1m",
  red: "[31m",
  green: "[32m",
  yellow: "[33m",
  blue: "[34m",
  magenta: "[35m",
} as const;

export class BridgeCli {
  private readonly options: BridgeCliOptions;
  /** requestId -> the request, oldest first by insertion. */
  private readonly pending = new Map<string, PendingRequest>();
  private sessions: SessionSummary[] = [];
  /** What the bridge will let a chat open. Empty means it is configured permissively. */
  private projectRoots: string[] = [];
  /** Names learned from `turn`, which can outrun the snapshot that would carry them. */
  private readonly names = new Map<string, string>();
  private current: string | null = null;

  constructor(options: BridgeCliOptions) {
    this.options = options;
  }

  get currentSessionId(): string | null {
    return this.current;
  }

  get pendingCount(): number {
    return this.pending.size;
  }

  // --- rendering ------------------------------------------------------------

  private paint(colour: keyof typeof COLOURS, text: string): string {
    return this.options.colour ? `${COLOURS[colour]}${text}${COLOURS.reset}` : text;
  }

  private line(text = ""): void {
    this.options.write(text);
  }

  /**
   * Session names are the only way to tell two waiting chats apart at a glance, and a
   * `turn` can arrive before the snapshot that would name its session -- which is exactly
   * why the event carries `sessionName` denormalised.
   */
  private nameOf(sessionId: string): string {
    return this.sessions.find((s) => s.sessionId === sessionId)?.name ?? this.names.get(sessionId) ?? sessionId;
  }

  private tag(sessionId: string): string {
    return this.paint("dim", `[${this.nameOf(sessionId)}]`);
  }

  banner(): void {
    this.line(this.paint("dim", "type /help for commands, /quit to leave"));
  }

  handle(event: ServerEvent): void {
    switch (event.type) {
      case "sessions": {
        this.sessions = event.sessions;
        this.projectRoots = event.projectRoots;
        // Land on something usable without being asked, so the first thing you type after
        // /new is a prompt rather than a /use.
        if (this.current === null || !this.sessions.some((s) => s.sessionId === this.current)) {
          this.current = this.sessions[0]?.sessionId ?? null;
        }
        for (const id of [...this.pending.keys()]) {
          if (!this.sessions.some((s) => s.sessionId === this.pending.get(id)!.sessionId)) this.pending.delete(id);
        }
        return;
      }
      case "turn": {
        // Snapshots only go out when the *set* of chats changes, so `turn` is what keeps
        // /sessions from showing a state the chat left several events ago.
        this.names.set(event.sessionId, event.sessionName);
        const known = this.sessions.find((s) => s.sessionId === event.sessionId);
        if (known) {
          known.name = event.sessionName;
          known.state = event.state;
        }
        if (event.state === "awaiting") return; // the ask/permission event says it better
        this.line(`${this.tag(event.sessionId)} ${this.paint("dim", `· ${event.state} — ${event.summary}`)}`);
        return;
      }
      case "text":
        this.line(`${this.tag(event.sessionId)} ${event.text}`);
        return;
      case "done": {
        const colour = event.isError ? "red" : "green";
        const detail = event.result ? ` — ${event.result}` : "";
        this.line(
          `${this.tag(event.sessionId)} ${this.paint(colour, `✓ ${event.subtype}`)}` +
            `${detail} ${this.paint("dim", `(${event.numTurns} turns, ${Math.round(event.durationMs / 100) / 10}s)`)}`,
        );
        this.line(this.paint("dim", "  your turn"));
        return;
      }
      case "ask":
        this.pending.set(event.requestId, { sessionId: event.sessionId, requestId: event.requestId, event });
        this.renderAsk(event);
        return;
      case "permission":
        this.pending.set(event.requestId, { sessionId: event.sessionId, requestId: event.requestId, event });
        this.renderPermission(event);
        return;
      case "resolved": {
        const had = this.pending.delete(event.requestId);
        if (had) this.line(this.paint("dim", `  ${event.requestId} ${event.resolution}${event.by ? ` by ${event.by}` : ""}`));
        return;
      }
      case "error":
        this.line(this.paint("red", `! ${event.code}: ${event.message}`));
        return;
    }
  }

  private renderAsk(event: AskEvent): void {
    this.line();
    this.line(`${this.tag(event.sessionId)} ${this.paint("magenta", "? Claude is asking")}`);
    event.questions.forEach((question, qi) => {
      const multi = question.multiSelect ? this.paint("dim", " (pick any)") : "";
      this.line(`  ${this.paint("bold", question.header)} ${question.question}${multi}`);
      question.options.forEach((option, oi) => {
        const n = this.optionNumber(event.questions, qi, oi);
        const description = option.description ? this.paint("dim", ` — ${option.description}`) : "";
        this.line(`    ${this.paint("yellow", `/${n}`)} ${option.label}${description}`);
      });
    });
    this.line(this.paint("dim", "  or /other <text>, or /say <text> to dismiss and just talk"));
  }

  /** Options are numbered across the whole request, so `/3` is never ambiguous. */
  private optionNumber(questions: AskQuestion[], questionIndex: number, optionIndex: number): number {
    let n = 1;
    for (let q = 0; q < questionIndex; q += 1) n += questions[q]!.options.length;
    return n + optionIndex;
  }

  private renderPermission(event: PermissionEvent): void {
    this.line();
    this.line(`${this.tag(event.sessionId)} ${this.paint("yellow", `? ${event.tool}`)}`);
    // Verbatim, never a summary. Approving what you cannot see is the failure mode the
    // whole product designs against, and a terminal has no excuse for abbreviating.
    if (event.display) this.line(`    ${event.display}`);
    const always = event.suggestions.some((s) => s.destination === "localSettings")
      ? ", /always to allow and remember"
      : "";
    this.line(this.paint("dim", `  /y to allow, /n [reason] to deny${always}`));
  }

  // --- commands -------------------------------------------------------------

  /** The oldest thing waiting on you: the current chat's if it has one, else anyone's. */
  private oldestPending(): PendingRequest | null {
    const all = [...this.pending.values()];
    return all.find((p) => p.sessionId === this.current) ?? all[0] ?? null;
  }

  private requireSession(): string | null {
    if (this.current) return this.current;
    this.line(this.paint("red", "no chat selected — /new <cwd> to start one"));
    return null;
  }

  command(raw: string): void {
    const line = raw.trim();
    if (!line) return;
    if (!line.startsWith("/")) {
      const sessionId = this.requireSession();
      if (sessionId) this.options.send({ type: "prompt", sessionId, text: line });
      return;
    }

    const [word, ...rest] = line.slice(1).split(/\s+/);
    const argument = line.slice(1 + (word?.length ?? 0)).trim();

    // `/1` and `/1,3` pick options on the question that is waiting.
    if (word && /^\d+(,\d+)*$/.test(word)) {
      this.answerByNumber(word);
      return;
    }

    switch (word) {
      case "help":
        this.line(HELP);
        return;
      case "quit":
      case "exit":
        this.options.quit?.();
        return;
      case "new": {
        if (!rest[0]) {
          this.line(this.paint("red", "/new needs a directory"));
          return;
        }
        const cwd = rest[0];
        const name = rest.slice(1).join(" ").trim();
        this.options.send({ type: "newSession", cwd, name: name || null });
        return;
      }
      case "sessions":
        this.listSessions();
        return;
      case "use": {
        const target = this.resolveSession(argument);
        if (!target) return;
        this.current = target;
        this.line(this.paint("dim", `now talking to ${this.nameOf(target)}`));
        return;
      }
      case "rename": {
        const sessionId = this.requireSession();
        if (!sessionId) return;
        if (!argument) {
          this.line(this.paint("red", "/rename needs a name"));
          return;
        }
        this.options.send({ type: "renameSession", sessionId, name: argument });
        return;
      }
      case "mode": {
        const sessionId = this.requireSession();
        if (!sessionId) return;
        if (!(PERMISSION_MODES as readonly string[]).includes(argument)) {
          this.line(this.paint("red", `/mode wants one of ${PERMISSION_MODES.join(", ")}`));
          return;
        }
        this.options.send({ type: "setMode", sessionId, mode: argument as PermissionMode });
        return;
      }
      case "interrupt": {
        const sessionId = this.requireSession();
        if (sessionId) this.options.send({ type: "interrupt", sessionId });
        return;
      }
      case "pending":
        this.listPending();
        return;
      case "resync":
        this.options.send({ type: "subscribe", sinceSeq: null });
        return;
      case "y":
      case "allow":
        this.decide("allow", null);
        return;
      case "always":
        this.decide("allowAlways", null);
        return;
      case "n":
      case "deny":
        this.decide("deny", argument || null);
        return;
      case "other":
        this.answerFreeText(argument);
        return;
      case "say":
        this.answerResponse(argument);
        return;
      default:
        this.line(this.paint("red", `unknown command /${word ?? ""} — /help lists them`));
        return;
    }
  }

  private resolveSession(argument: string): string | null {
    if (!argument) {
      this.line(this.paint("red", "/use needs a number or a session id"));
      return null;
    }
    if (/^\d+$/.test(argument)) {
      const target = this.sessions[Number(argument) - 1];
      if (!target) {
        this.line(this.paint("red", `there is no chat ${argument}`));
        return null;
      }
      return target.sessionId;
    }
    if (!this.sessions.some((s) => s.sessionId === argument)) {
      this.line(this.paint("red", `there is no chat ${argument}`));
      return null;
    }
    return argument;
  }

  private listSessions(): void {
    if (this.sessions.length === 0) {
      this.line(this.paint("dim", "no chats yet — /new <cwd>"));
      // The watch turns the same list into chips; here it saves you guessing which paths
      // the bridge is willing to open.
      for (const root of this.projectRoots) this.line(this.paint("dim", `  ${root}`));
      return;
    }
    this.sessions.forEach((session, i) => {
      const marker = session.sessionId === this.current ? this.paint("green", "▸") : " ";
      const waiting = session.pendingRequestIds.length > 0 ? this.paint("yellow", " ← waiting on you") : "";
      this.line(
        `${marker} ${i + 1}. ${this.paint("bold", session.name)} ${this.paint("dim", session.cwd)} ` +
          `${session.state}/${session.mode}${waiting}`,
      );
    });
  }

  private listPending(): void {
    if (this.pending.size === 0) {
      this.line(this.paint("dim", "nothing is waiting on you"));
      return;
    }
    for (const entry of this.pending.values()) {
      const what = entry.event.type === "ask" ? entry.event.questions[0]?.question : entry.event.tool;
      this.line(`  ${this.tag(entry.sessionId)} ${entry.requestId} ${what ?? ""}`);
    }
  }

  // --- answering ------------------------------------------------------------

  private takeAsk(): (PendingRequest & { event: AskEvent }) | null {
    const entry = this.oldestPending();
    if (!entry) {
      this.line(this.paint("red", "nothing is waiting on you"));
      return null;
    }
    if (entry.event.type !== "ask") {
      this.line(this.paint("red", `${entry.requestId} is a permission — /y, /always or /n [reason]`));
      return null;
    }
    return entry as PendingRequest & { event: AskEvent };
  }

  /**
   * `/1,3` across a multi-question request. Answers are keyed by question text and valued
   * by the selected label; multiSelect labels are joined with ", ". That mapping is the
   * SDK's contract, and getting it wrong is silent -- Claude just reads the wrong answer.
   */
  private answerByNumber(spec: string): void {
    const entry = this.takeAsk();
    if (!entry) return;
    const questions = entry.event.questions;
    const picked = new Map<string, string[]>();

    for (const raw of spec.split(",")) {
      const n = Number(raw);
      let offset = n;
      let found = false;
      for (const question of questions) {
        if (offset <= question.options.length) {
          const option = question.options[offset - 1]!;
          const existing = picked.get(question.question) ?? [];
          if (!question.multiSelect && existing.length > 0) {
            this.line(this.paint("red", `"${question.header}" only takes one answer`));
            return;
          }
          picked.set(question.question, [...existing, option.label]);
          found = true;
          break;
        }
        offset -= question.options.length;
      }
      if (!found) {
        this.line(this.paint("red", `there is no option ${n}`));
        return;
      }
    }

    const unanswered = questions.filter((q) => !picked.has(q.question));
    if (unanswered.length > 0) {
      this.line(this.paint("red", `still unanswered: ${unanswered.map((q) => q.header).join(", ")}`));
      return;
    }

    const answers: Record<string, string> = {};
    for (const [question, labels] of picked) answers[question] = labels.join(", ");
    this.options.send({ type: "answer", sessionId: entry.sessionId, requestId: entry.requestId, answers, response: null });
  }

  /** The dictated "Other" path: the transcript goes in as the value, not the word "Other". */
  private answerFreeText(text: string): void {
    const entry = this.takeAsk();
    if (!entry) return;
    if (!text) {
      this.line(this.paint("red", "/other needs some text"));
      return;
    }
    const answers: Record<string, string> = {};
    for (const question of entry.event.questions) answers[question.question] = text;
    this.options.send({ type: "answer", sessionId: entry.sessionId, requestId: entry.requestId, answers, response: null });
  }

  /** Dismiss the questions and just talk; Claude receives "The user responded: …". */
  private answerResponse(text: string): void {
    const entry = this.takeAsk();
    if (!entry) return;
    if (!text) {
      this.line(this.paint("red", "/say needs some text"));
      return;
    }
    this.options.send({
      type: "answer",
      sessionId: entry.sessionId,
      requestId: entry.requestId,
      answers: null,
      response: text,
    });
  }

  private decide(decision: "allow" | "allowAlways" | "deny", message: string | null): void {
    const entry = this.oldestPending();
    if (!entry) {
      this.line(this.paint("red", "nothing is waiting on you"));
      return;
    }
    if (entry.event.type !== "permission") {
      this.line(this.paint("red", `${entry.requestId} is a question — /1, /other <text> or /say <text>`));
      return;
    }
    this.options.send({
      type: "permission",
      sessionId: entry.sessionId,
      requestId: entry.requestId,
      decision,
      message,
    });
  }
}
