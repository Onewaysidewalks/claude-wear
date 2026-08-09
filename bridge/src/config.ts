/**
 * `npx claude-wear-bridge --port 8787 --bind tailscale0`
 *
 * Flags are the whole configuration surface for a one-off run. The things you do not want
 * to retype -- above all *which project roots the bridge will open* -- live in
 * `~/.claude-wear/config.json`, next to `devices.json` and `sessions.json`. Flags win over
 * the file, so a config file is a default, never a cage.
 */
import { readFileSync } from "node:fs";
import { networkInterfaces } from "node:os";
import { homedir } from "node:os";
import { isAbsolute, join, resolve } from "node:path";
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
  /**
   * `bypassPermissions` stops the agent asking, which stops the watch buzzing. Off unless
   * you say otherwise -- the SDK demands an explicit opt-in for it and so does the bridge.
   */
  allowBypassPermissions: boolean;
  inbox: boolean;
  /** Print a pairing code on startup. */
  pair: boolean;
  /** Where the config file was read from, or null if there wasn't one. */
  configPath: string | null;
}

export class ConfigError extends Error {}

/** `~/code` in a hand-edited config file should mean what it looks like it means. */
export function expandHome(path: string): string {
  if (path === "~") return homedir();
  if (path.startsWith("~/")) return join(homedir(), path.slice(2));
  return path;
}

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
  --state-dir <path>     where config.json, devices.json and sessions.json live
                         (default ~/.claude-wear)
  --config <path>        read settings from this file instead of <state-dir>/config.json
  --permission-mode <m>  ${PERMISSION_MODES.join(" | ")} (default default)
  --project-root <path>  repeatable; restricts which directories a chat may open.
                         Usually better set once as \`projectRoots\` in config.json
  --allow-bypass-permissions
                         permit permissionMode bypassPermissions. The agent then stops
                         asking, and your watch stops buzzing. Off by default
  --fake                 replay scripted scenarios instead of calling Claude.
                         No API key, no network. Same as FAKE_AGENT=1
  --scenario-dir <path>  where the fake runner reads scenarios from
  --scenarios <a,b,c>    scenarios for successive sessions, cycling
  --time-scale <n>       multiplies every scenario delay (0 = as fast as possible)
  --inbox                expose GET /debug/inbox, which E2E asserts against
  --no-pair              do not print a pairing code on startup
  --help

Config file (all keys optional; flags override it):

  {
    "port": 8787,
    "bind": "tailscale0",
    "maxSessions": 5,
    "permissionMode": "default",
    "projectRoots": ["~/code/claude-wear", "~/code/other-thing"],
    "allowBypassPermissions": false
  }
`;

function requireValue(flag: string, value: string | undefined): string {
  if (value === undefined) throw new ConfigError(`${flag} needs a value`);
  return value;
}

function positiveInt(flag: string, raw: string | number): number {
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 0) throw new ConfigError(`${flag} needs a non-negative integer, got \`${raw}\``);
  return n;
}

function permissionMode(where: string, raw: unknown): PermissionMode {
  if (typeof raw !== "string" || !(PERMISSION_MODES as readonly string[]).includes(raw)) {
    throw new ConfigError(`${where} must be one of ${PERMISSION_MODES.join(", ")}`);
  }
  return raw as PermissionMode;
}

function projectRoot(where: string, raw: string): string {
  const expanded = expandHome(raw);
  if (!isAbsolute(expanded)) throw new ConfigError(`${where} must be an absolute path, got \`${raw}\``);
  return resolve(expanded);
}

function defaults(): BridgeConfig {
  return {
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
    allowBypassPermissions: false,
    inbox: false,
    pair: true,
    configPath: null,
  };
}

/** Which flags the operator actually typed, so the config file knows what not to touch. */
export interface ParsedArgs {
  config: BridgeConfig;
  seen: Set<string>;
}

export function parseArgsWithProvenance(argv: string[]): ParsedArgs {
  const config = defaults();
  const seen = new Set<string>();

  for (let i = 0; i < argv.length; i += 1) {
    const flag = argv[i]!;
    const next = argv[i + 1];
    seen.add(flag);
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
        config.stateDir = resolve(expandHome(requireValue(flag, next)));
        i += 1;
        break;
      case "--config":
        config.configPath = resolve(expandHome(requireValue(flag, next)));
        i += 1;
        break;
      case "--permission-mode":
        config.defaultMode = permissionMode(flag, requireValue(flag, next));
        i += 1;
        break;
      case "--project-root":
        config.allowedRoots.push(projectRoot(flag, requireValue(flag, next)));
        i += 1;
        break;
      case "--allow-bypass-permissions":
        config.allowBypassPermissions = true;
        break;
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

  validate(config);
  return { config, seen };
}

function validate(config: BridgeConfig): void {
  if (!Number.isFinite(config.timeScale) || config.timeScale < 0) {
    throw new ConfigError("--time-scale needs a non-negative number");
  }
  if (config.scenarios.length > 0 && !config.fake) {
    throw new ConfigError("--scenarios only means anything with --fake");
  }
}

export function parseArgs(argv: string[]): BridgeConfig {
  return parseArgsWithProvenance(argv).config;
}

// --- the config file --------------------------------------------------------

/** Everything the file may set. Anything else is a typo, and typos here are dangerous. */
const FILE_KEYS = ["port", "bind", "maxSessions", "permissionMode", "projectRoots", "allowBypassPermissions"] as const;

export interface ConfigFile {
  port?: number;
  bind?: string;
  maxSessions?: number;
  permissionMode?: PermissionMode;
  projectRoots?: string[];
  allowBypassPermissions?: boolean;
}

export function parseConfigFile(path: string, raw: string): ConfigFile {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch (err) {
    throw new ConfigError(`${path} is not valid JSON: ${(err as Error).message}`);
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new ConfigError(`${path} must contain a JSON object`);
  }
  const file = parsed as Record<string, unknown>;

  // A mistyped `projectRoot` that silently opens your whole home directory is exactly what
  // this file exists to prevent, so an unknown key is an error rather than a shrug.
  for (const key of Object.keys(file)) {
    if (!(FILE_KEYS as readonly string[]).includes(key)) {
      throw new ConfigError(`${path}: unknown key \`${key}\`; expected one of ${FILE_KEYS.join(", ")}`);
    }
  }

  const out: ConfigFile = {};
  if (file.port !== undefined) out.port = positiveInt(`${path}: port`, file.port as number);
  if (file.bind !== undefined) {
    if (typeof file.bind !== "string") throw new ConfigError(`${path}: bind must be a string`);
    out.bind = file.bind;
  }
  if (file.maxSessions !== undefined) out.maxSessions = positiveInt(`${path}: maxSessions`, file.maxSessions as number);
  if (file.permissionMode !== undefined) out.permissionMode = permissionMode(`${path}: permissionMode`, file.permissionMode);
  if (file.projectRoots !== undefined) {
    if (!Array.isArray(file.projectRoots) || file.projectRoots.some((r) => typeof r !== "string")) {
      throw new ConfigError(`${path}: projectRoots must be an array of absolute paths`);
    }
    out.projectRoots = (file.projectRoots as string[]).map((r) => projectRoot(`${path}: projectRoots`, r));
  }
  if (file.allowBypassPermissions !== undefined) {
    if (typeof file.allowBypassPermissions !== "boolean") {
      throw new ConfigError(`${path}: allowBypassPermissions must be true or false`);
    }
    out.allowBypassPermissions = file.allowBypassPermissions;
  }
  return out;
}

/**
 * Flags, layered over `<state-dir>/config.json`. An explicit `--config` that is missing is
 * an error; the default one simply not being there is the normal first run.
 */
export function loadConfig(argv: string[]): BridgeConfig {
  const { config, seen } = parseArgsWithProvenance(argv);
  const explicit = seen.has("--config");
  const path = config.configPath ?? join(config.stateDir, "config.json");

  let raw: string;
  try {
    raw = readFileSync(path, "utf8");
  } catch (err) {
    if (explicit) throw new ConfigError(`could not read ${path}: ${(err as Error).message}`);
    config.configPath = null;
    return config;
  }

  const file = parseConfigFile(path, raw);
  config.configPath = path;
  if (file.port !== undefined && !seen.has("--port")) config.port = file.port;
  if (file.bind !== undefined && !seen.has("--bind")) {
    config.bindSpec = file.bind;
    config.bind = resolveBind(file.bind);
  }
  if (file.maxSessions !== undefined && !seen.has("--max-sessions")) config.maxSessions = file.maxSessions;
  if (file.permissionMode !== undefined && !seen.has("--permission-mode")) config.defaultMode = file.permissionMode;
  if (file.projectRoots !== undefined && !seen.has("--project-root")) config.allowedRoots = file.projectRoots;
  if (file.allowBypassPermissions !== undefined && !seen.has("--allow-bypass-permissions")) {
    config.allowBypassPermissions = file.allowBypassPermissions;
  }

  validate(config);
  return config;
}
