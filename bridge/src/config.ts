/**
 * `npx claude-wear-bridge --port 8787 --bind tailscale0`
 *
 * That is the whole deployment story, so the flags are the whole configuration surface.
 */
import { networkInterfaces } from "node:os";
import { homedir } from "node:os";
import { isAbsolute, resolve } from "node:path";
import { PERMISSION_MODES, type PermissionMode } from "./protocol.js";

export interface BridgeConfig {
  port: number;
  /** Already resolved to an address. Defaults to loopback; widening it is deliberate. */
  bind: string;
  bindSpec: string;
  maxSessions: number;
  fake: boolean;
  scenarioDir: string | null;
  /** Scenarios handed to successive sessions, cycling. Fake runner only. */
  scenarios: string[];
  timeScale: number;
  stateDir: string;
  defaultMode: PermissionMode;
  /** Project roots the bridge will open. Empty means any directory that exists. */
  allowedRoots: string[];
  inbox: boolean;
  /** Print a pairing code on startup. */
  pair: boolean;
}

export class ConfigError extends Error {}

/** `--bind tailscale0` resolves to that interface's IPv4 address. */
export function resolveBind(spec: string): string {
  if (/^[\d.]+$/.test(spec) || spec.includes(":")) return spec;
  const iface = networkInterfaces()[spec];
  const address = iface?.find((a) => a.family === "IPv4" && !a.internal) ?? iface?.[0];
  if (!address) throw new ConfigError(`no address found for interface \`${spec}\``);
  return address.address;
}

const USAGE = `claude-wear-bridge — owns your Claude Agent SDK sessions and talks to a watch

  --port <n>             port to listen on (default 8787)
  --bind <addr|iface>    address or interface name (default 127.0.0.1)
                         a tailnet interface gets you away-from-home access without
                         opening a port to your LAN, let alone the internet
  --max-sessions <n>     concurrent chats (default 5)
  --state-dir <path>     where devices.json and sessions.json live (default ~/.claude-wear)
  --permission-mode <m>  ${PERMISSION_MODES.join(" | ")} (default default)
  --project-root <path>  repeatable; restricts which directories a chat may open
  --fake                 replay scripted scenarios instead of calling Claude.
                         No API key, no network. Same as FAKE_AGENT=1
  --scenario-dir <path>  where the fake runner reads scenarios from
  --scenarios <a,b,c>    scenarios for successive sessions, cycling
  --time-scale <n>       multiplies every scenario delay (0 = as fast as possible)
  --inbox                expose GET /debug/inbox, which E2E asserts against
  --no-pair              do not print a pairing code on startup
  --help
`;

function requireValue(flag: string, value: string | undefined): string {
  if (value === undefined) throw new ConfigError(`${flag} needs a value`);
  return value;
}

function positiveInt(flag: string, raw: string): number {
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 0) throw new ConfigError(`${flag} needs a non-negative integer, got \`${raw}\``);
  return n;
}

export function parseArgs(argv: string[]): BridgeConfig {
  const config: BridgeConfig = {
    port: 8787,
    bind: "127.0.0.1",
    bindSpec: "127.0.0.1",
    maxSessions: 5,
    fake: process.env.FAKE_AGENT === "1",
    scenarioDir: null,
    scenarios: [],
    timeScale: Number(process.env.FAKE_AGENT_TIME_SCALE ?? "1"),
    stateDir: process.env.CLAUDE_WEAR_STATE_DIR ?? resolve(homedir(), ".claude-wear"),
    defaultMode: "default",
    allowedRoots: [],
    inbox: false,
    pair: true,
  };

  for (let i = 0; i < argv.length; i += 1) {
    const flag = argv[i]!;
    const next = argv[i + 1];
    switch (flag) {
      case "--help":
      case "-h":
        process.stdout.write(USAGE);
        process.exit(0);
        break;
      case "--port":
        config.port = positiveInt(flag, requireValue(flag, next));
        i += 1;
        break;
      case "--bind":
        config.bindSpec = requireValue(flag, next);
        config.bind = resolveBind(config.bindSpec);
        i += 1;
        break;
      case "--max-sessions":
        config.maxSessions = positiveInt(flag, requireValue(flag, next));
        i += 1;
        break;
      case "--state-dir":
        config.stateDir = resolve(requireValue(flag, next));
        i += 1;
        break;
      case "--permission-mode": {
        const mode = requireValue(flag, next);
        if (!(PERMISSION_MODES as readonly string[]).includes(mode)) {
          throw new ConfigError(`--permission-mode must be one of ${PERMISSION_MODES.join(", ")}`);
        }
        config.defaultMode = mode as PermissionMode;
        i += 1;
        break;
      }
      case "--project-root": {
        const root = requireValue(flag, next);
        if (!isAbsolute(root)) throw new ConfigError("--project-root must be an absolute path");
        config.allowedRoots.push(resolve(root));
        i += 1;
        break;
      }
      case "--fake":
        config.fake = true;
        break;
      case "--scenario-dir":
        config.scenarioDir = resolve(requireValue(flag, next));
        i += 1;
        break;
      case "--scenarios":
        config.scenarios = requireValue(flag, next)
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean);
        i += 1;
        break;
      case "--time-scale":
        config.timeScale = Number(requireValue(flag, next));
        i += 1;
        break;
      case "--inbox":
        config.inbox = true;
        break;
      case "--no-pair":
        config.pair = false;
        break;
      default:
        throw new ConfigError(`unknown flag \`${flag}\`\n\n${USAGE}`);
    }
  }

  if (!Number.isFinite(config.timeScale) || config.timeScale < 0) {
    throw new ConfigError("--time-scale needs a non-negative number");
  }
  if (config.scenarios.length > 0 && !config.fake) {
    throw new ConfigError("--scenarios only means anything with --fake");
  }
  return config;
}
