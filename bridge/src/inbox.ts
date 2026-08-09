/**
 * A recording of everything the bridge was asked to do, so an E2E run can assert on what
 * the watch actually sent rather than on what the emulator's screen looked like.
 *
 * Off unless --inbox is passed. It is a debugging surface on a process that runs shell
 * commands as you; it does not get to be on by default.
 */
import type { ClientMessage, ServerEvent } from "./protocol.js";

export interface InboxEntry {
  at: number;
  deviceId: string | null;
  direction: "in" | "out";
  type: string;
  payload: ClientMessage | ServerEvent;
}

export class Inbox {
  private readonly entries: InboxEntry[] = [];

  constructor(
    readonly enabled: boolean,
    private readonly limit = 1000,
  ) {}

  recordIn(deviceId: string | null, message: ClientMessage): void {
    this.push({ at: Date.now(), deviceId, direction: "in", type: message.type, payload: message });
  }

  recordOut(event: ServerEvent): void {
    this.push({ at: Date.now(), deviceId: null, direction: "out", type: event.type, payload: event });
  }

  private push(entry: InboxEntry): void {
    if (!this.enabled) return;
    this.entries.push(entry);
    if (this.entries.length > this.limit) this.entries.shift();
  }

  all(): InboxEntry[] {
    return [...this.entries];
  }

  received(type: ClientMessage["type"]): InboxEntry[] {
    return this.entries.filter((e) => e.direction === "in" && e.type === type);
  }
}
