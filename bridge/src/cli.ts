#!/usr/bin/env node
/**
 * npx claude-wear-bridge --port 8787 --bind tailscale0
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { AuthStore } from "./auth.js";
import { ConfigError, loadConfig } from "./config.js";
import { Inbox } from "./inbox.js";
import { log } from "./log.js";
import { PROTOCOL_VERSION } from "./protocol.js";
import { FakeAgentRunner } from "./runner/fake.js";
import { SdkAgentRunner } from "./runner/sdk.js";
import type { AgentRunner } from "./runner/types.js";
import { BridgeServer } from "./server.js";
import { SessionRegistry } from "./sessions.js";

function version(): string {
  try {
    const pkg = JSON.parse(readFileSync(fileURLToPath(new URL("../package.json", import.meta.url)), "utf8"));
    return pkg.version ?? "0.0.0";
  } catch {
    return "0.0.0";
  }
}

async function main(): Promise<void> {
  const config = loadConfig(process.argv.slice(2));

  if (config.defaultMode === "bypassPermissions" && !config.allowBypassPermissions) {
    throw new ConfigError(
      "--permission-mode bypassPermissions needs --allow-bypass-permissions as well. In that mode the agent " +
        "stops asking, so your watch stops buzzing — it is the most dangerous control in this product and it " +
        "takes two hands to reach for.",
    );
  }

  let runner: AgentRunner;
  if (config.fake) {
    runner = new FakeAgentRunner({
      scenarioDir: config.scenarioDir ?? undefined,
      rotation: config.scenarios,
      timeScale: config.timeScale,
    });
  } else {
    const sdk = new SdkAgentRunner({ allowBypassPermissions: config.allowBypassPermissions });
    // Fail here, at startup, rather than on the first session the watch asks for.
    await sdk.prepare();
    runner = sdk;
  }

  const auth = new AuthStore(config.stateDir);
  const inbox = new Inbox(config.inbox);
  const registry = new SessionRegistry({
    runner,
    maxSessions: config.maxSessions,
    stateDir: config.stateDir,
    defaultMode: config.defaultMode,
    allowedRoots: config.allowedRoots,
  });

  const server = new BridgeServer({ config, registry, auth, inbox, runnerName: runner.name, version: version() });
  const { address, port } = await server.listen();

  const lines = [
    `claude-wear-bridge ${version()}  (protocol v${PROTOCOL_VERSION}, runner: ${runner.name})`,
    `  listening   ws://${address}:${port}/ws`,
    `  sessions    up to ${config.maxSessions}${config.allowedRoots.length ? ` under ${config.allowedRoots.join(", ")}` : ""}`,
  ];
  if (config.configPath) {
    lines.push(`  config      ${config.configPath}`);
  }
  if (config.bind === "127.0.0.1") {
    lines.push("  bound to loopback — pass --bind <tailnet-iface> to reach it from your watch");
  }
  if (config.allowedRoots.length === 0 && !config.fake) {
    lines.push("  any existing directory may be opened — set `projectRoots` in config.json to narrow that");
  }
  if (config.allowBypassPermissions) {
    lines.push("  bypassPermissions is PERMITTED — in that mode the agent stops asking and your watch stops buzzing");
  }
  if (config.fake) {
    lines.push("  FAKE AGENT — replaying scenarios, no API key and no network");
  }
  if (config.inbox) {
    lines.push(`  inbox       http://${address}:${port}/debug/inbox`);
  }
  if (config.pair) {
    lines.push("", `  pairing code: ${auth.issuePairingCode()}   (single use, valid 5 minutes)`);
  }
  process.stdout.write(`${lines.join("\n")}\n\n`);

  let shuttingDown = false;
  const shutdown = async (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    log.info("shutting down", { signal });
    await registry.closeAll();
    await server.close();
    process.exit(0);
  };
  process.on("SIGINT", () => void shutdown("SIGINT"));
  process.on("SIGTERM", () => void shutdown("SIGTERM"));
}

main().catch((err) => {
  if (err instanceof ConfigError) {
    process.stderr.write(`${err.message}\n`);
    process.exit(2);
  }
  log.error("bridge failed to start", { error: (err as Error).message });
  process.stderr.write(`${(err as Error).stack ?? err}\n`);
  process.exit(1);
});
