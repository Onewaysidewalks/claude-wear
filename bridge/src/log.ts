/** Everything goes to stderr so stdout stays free for the pairing code and JSON output. */

export type LogLevel = "debug" | "info" | "warn" | "error" | "silent";

const ORDER: Record<LogLevel, number> = { debug: 10, info: 20, warn: 30, error: 40, silent: 100 };

let threshold = ORDER[(process.env.LOG_LEVEL as LogLevel) ?? "info"] ?? ORDER.info;

export function setLogLevel(level: LogLevel): void {
  threshold = ORDER[level];
}

function write(level: LogLevel, msg: string, fields?: Record<string, unknown>): void {
  if (ORDER[level] < threshold) return;
  const suffix = fields
    ? " " +
      Object.entries(fields)
        .map(([k, v]) => `${k}=${typeof v === "string" ? v : JSON.stringify(v)}`)
        .join(" ")
    : "";
  process.stderr.write(`${new Date().toISOString()} ${level.toUpperCase().padEnd(5)} ${msg}${suffix}\n`);
}

export const log = {
  debug: (msg: string, fields?: Record<string, unknown>) => write("debug", msg, fields),
  info: (msg: string, fields?: Record<string, unknown>) => write("info", msg, fields),
  warn: (msg: string, fields?: Record<string, unknown>) => write("warn", msg, fields),
  error: (msg: string, fields?: Record<string, unknown>) => write("error", msg, fields),
};
