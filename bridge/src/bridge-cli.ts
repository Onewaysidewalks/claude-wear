#!/usr/bin/env node
/**
 * claude-wear-cli — a terminal client that speaks the watch's protocol.
 *
 *   claude-wear-cli --pair 12345678 --new ~/code/thing
 *
 * The point is not convenience: it is that a real Claude session can be driven end to end,
 * including AskUserQuestion and permission prompts, without an emulator in the loop. Every
 * frame it sends is a frame the watch sends, so anything working here is the bridge
 * working, not a second implementation of it.
 */
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { createInterface } from "node:readline";
import { clearLine, cursorTo } from "node:readline";
import { join, resolve } from "node:path";
import { WebSocket } from "ws";
import { BridgeCli } from "./client/session-cli.js";
import { expandHome } from "./config.js";
import { PROTOCOL_VERSION, type ClientMessage, type ServerEvent } from "./protocol.js";

const USAGE = `claude-wear-cli — drive a claude-wear bridge from a terminal

  --url <url>          bridge base URL (default http://127.0.0.1:8787)
  --pair <code>        exchange the 8-digit code the bridge printed for a token
  --token <token>      use this token instead of the saved one
  --device-name <n>    how this client shows up in the bridge's device list (default cli)
  --state-dir <path>   where the saved token lives (default ~/.claude-wear)
  --new <cwd>          start a chat in this directory as soon as we connect
  --no-colour
  --help
`;

interface ClientConfig {
  url: string;
  pair: string | null;
  token: string | null;
  deviceName: string;
  stateDir: string;
  newCwd: string | null;
  colour: boolean;
}

function parseArgs(argv: string[]): ClientConfig {
  const config: ClientConfig = {
    url: process.env.CLAUDE_WEAR_URL ?? "http://127.0.0.1:8787",
    pair: null,
    token: process.env.CLAUDE_WEAR_TOKEN ?? null,
    deviceName: "cli",
    stateDir: process.env.CLAUDE_WEAR_STATE_DIR ?? join(homedir(), ".claude-wear"),
    newCwd: null,
    colour: process.stdout.isTTY === true,
  };
  const value = (flag: string, next: string | undefined): string => {
    if (next === undefined) {
      process.stderr.write(`${flag} needs a value\n`);
      process.exit(2);
    }
    return next;
  };
  for (let i = 0; i < argv.length; i += 1) {
    const flag = argv[i]!;
    switch (flag) {
      case "--help":
      case "-h":
        process.stdout.write(USAGE);
        process.exit(0);
        break;
      case "--url":
        config.url = value(flag, argv[++i]);
        break;
      case "--pair":
        config.pair = value(flag, argv[++i]);
        break;
      case "--token":
        config.token = value(flag, argv[++i]);
        break;
      case "--device-name":
        config.deviceName = value(flag, argv[++i]);
        break;
      case "--state-dir":
        config.stateDir = resolve(expandHome(value(flag, argv[++i])));
        break;
      case "--new":
        config.newCwd = resolve(expandHome(value(flag, argv[++i])));
        break;
      case "--no-colour":
      case "--no-color":
        config.colour = false;
        break;
      default:
        process.stderr.write(`unknown flag \`${flag}\`\n\n${USAGE}`);
        process.exit(2);
    }
  }
  config.url = config.url.replace(/\/$/, "");
  return config;
}

// --- the saved token --------------------------------------------------------
//
// Keyed by bridge URL so pointing at a second bridge does not evict the first.

type TokenStore = Record<string, { deviceId: string; token: string }>;

function tokenPath(stateDir: string): string {
  return join(stateDir, "cli-tokens.json");
}

function readTokens(stateDir: string): TokenStore {
  try {
    return JSON.parse(readFileSync(tokenPath(stateDir), "utf8")) as TokenStore;
  } catch {
    return {};
  }
}

function saveToken(stateDir: string, url: string, entry: { deviceId: string; token: string }): void {
  mkdirSync(stateDir, { recursive: true });
  const store = readTokens(stateDir);
  store[url] = entry;
  const path = tokenPath(stateDir);
  const tmp = `${path}.${process.pid}.tmp`;
  writeFileSync(tmp, `${JSON.stringify(store, null, 2)}\n`, { mode: 0o600 });
  renameSync(tmp, path);
}

export async function pairWithBridge(
  url: string,
  code: string,
  deviceName: string,
): Promise<{ deviceId: string; token: string }> {
  const res = await fetch(`${url}/pair`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ code, deviceName }),
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => ({}))) as { error?: string };
    throw new Error(`pairing failed (${res.status}): ${body.error ?? "no reason given"}`);
  }
  return (await res.json()) as { deviceId: string; token: string };
}

// --- wiring -----------------------------------------------------------------

async function main(): Promise<void> {
  const config = parseArgs(process.argv.slice(2));

  let token = config.token;
  if (config.pair) {
    const paired = await pairWithBridge(config.url, config.pair, config.deviceName);
    saveToken(config.stateDir, config.url, paired);
    token = paired.token;
    process.stdout.write(`paired as ${paired.deviceId}\n`);
  }
  token ??= readTokens(config.stateDir)[config.url]?.token ?? null;
  if (!token) {
    process.stderr.write(
      `no token for ${config.url}. Start the bridge, then run this with --pair <the 8-digit code it printed>.\n`,
    );
    process.exit(2);
  }

  const socket = new WebSocket(`${config.url.replace(/^http/, "ws")}/ws`, {
    headers: { authorization: `Bearer ${token}` },
  });

  const interactive = process.stdout.isTTY === true;
  const rl = createInterface({ input: process.stdin, output: process.stdout, prompt: interactive ? "» " : "" });

  /**
   * Redraw the prompt under whatever just arrived, so typing is never eaten by an event.
   * Only on a TTY: piping a script in should produce a clean transcript, not a column of
   * prompt characters.
   */
  const write = (line: string): void => {
    if (interactive) {
      cursorTo(process.stdout, 0);
      clearLine(process.stdout, 0);
    }
    process.stdout.write(`${line}\n`);
    if (interactive) rl.prompt(true);
  };

  const send = (message: ClientMessage): void => {
    if (socket.readyState !== WebSocket.OPEN) {
      write("not connected");
      return;
    }
    socket.send(JSON.stringify(message));
  };

  let quitting = false;
  const quit = (): void => {
    if (quitting) return;
    quitting = true;
    rl.close();
    socket.close(1000, "bye");
  };

  const cli = new BridgeCli({ send, write, colour: config.colour, quit });

  // Piped stdin arrives all at once, long before the socket finishes its upgrade. Holding
  // the lines until then is what makes the CLI scriptable rather than only interactive.
  let connected = false;
  const queued: string[] = [];

  socket.on("open", () => {
    connected = true;
    send({
      type: "hello",
      protocolVersion: PROTOCOL_VERSION,
      deviceId: "cli",
      deviceName: config.deviceName,
      clientVersion: "cli",
    });
    send({ type: "subscribe", sinceSeq: null });
    if (config.newCwd) send({ type: "newSession", cwd: config.newCwd, name: null });
    write(`connected to ${config.url}`);
    cli.banner();
    for (const line of queued.splice(0)) cli.command(line);
    if (interactive) rl.prompt(true);
  });
  socket.on("message", (data) => cli.handle(JSON.parse(data.toString()) as ServerEvent));
  socket.on("error", (err) => {
    process.stderr.write(`socket error: ${err.message}\n`);
    process.exitCode = 1;
    quit();
  });
  socket.on("unexpected-response", (_req, res) => {
    process.stderr.write(
      res.statusCode === 401
        ? "the bridge rejected that token. Re-pair with --pair <code>.\n"
        : `the bridge answered ${res.statusCode} to the upgrade\n`,
    );
    process.exit(2);
  });
  socket.on("close", () => {
    if (!quitting) write("the bridge closed the connection");
    rl.close();
  });

  rl.on("line", (line) => {
    if (!connected) {
      queued.push(line);
      return;
    }
    cli.command(line);
    if (!quitting && interactive) rl.prompt(true);
  });
  rl.on("close", () => {
    quit();
    process.exit(process.exitCode ?? 0);
  });
}

main().catch((err) => {
  process.stderr.write(`${(err as Error).message}\n`);
  process.exit(1);
});
