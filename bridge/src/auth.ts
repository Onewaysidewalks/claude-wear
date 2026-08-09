/**
 * Pairing and device tokens.
 *
 * The bridge is not on the public internet -- the tailnet or the LAN is the real auth
 * boundary, and it is a much better one than anything hand-rolled here. What this adds on
 * top is per-device revocation: losing a watch revokes one token, not all of them.
 *
 * An 8-digit single-use code with a 5-minute TTL is adequate for an endpoint you cannot
 * reach from outside. On a public bridge it would be a short brute force, and it is one of
 * the things listed for rebuilding if hosting ever moves.
 */
import { createHash, randomBytes, randomInt, timingSafeEqual } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { log } from "./log.js";

export const PAIRING_CODE_TTL_MS = 5 * 60 * 1000;

export interface Device {
  deviceId: string;
  deviceName: string;
  /** sha256 of the token. The token itself is shown once, at pairing, and never stored. */
  tokenHash: string;
  pairedAt: number;
  lastSeenAt: number;
}

export interface PairingResult {
  deviceId: string;
  token: string;
}

function sha256(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function constantTimeEquals(a: string, b: string): boolean {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  if (left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}

export class AuthStore {
  private readonly devices = new Map<string, Device>();
  private pairingCode: { code: string; expiresAt: number } | null = null;

  constructor(
    private readonly stateDir: string,
    private readonly now: () => number = Date.now,
  ) {
    mkdirSync(stateDir, { recursive: true });
    this.load();
  }

  /** Issues (or reissues) the code the bridge prints on startup. Single use, 5 minute TTL. */
  issuePairingCode(): string {
    const code = String(randomInt(0, 100_000_000)).padStart(8, "0");
    this.pairingCode = { code, expiresAt: this.now() + PAIRING_CODE_TTL_MS };
    return code;
  }

  get pairingCodeExpiresAt(): number | null {
    return this.pairingCode?.expiresAt ?? null;
  }

  /** Exchanges a pairing code for a long-lived, device-scoped bearer token. */
  pair(code: string, deviceName: string): PairingResult | null {
    const pending = this.pairingCode;
    if (!pending) return null;
    if (this.now() > pending.expiresAt) {
      this.pairingCode = null;
      return null;
    }
    if (!constantTimeEquals(code, pending.code)) return null;

    this.pairingCode = null; // single use
    const token = randomBytes(32).toString("base64url"); // 256 bits
    const deviceId = `dev_${randomBytes(4).toString("hex")}`;
    this.devices.set(deviceId, {
      deviceId,
      deviceName: deviceName.trim() || "watch",
      tokenHash: sha256(token),
      pairedAt: this.now(),
      lastSeenAt: this.now(),
    });
    this.save();
    log.info("device paired", { deviceId, deviceName });
    return { deviceId, token };
  }

  verify(token: string | null | undefined): Device | null {
    if (!token) return null;
    const hash = sha256(token);
    for (const device of this.devices.values()) {
      if (constantTimeEquals(device.tokenHash, hash)) {
        device.lastSeenAt = this.now();
        return device;
      }
    }
    return null;
  }

  revoke(deviceId: string): boolean {
    const removed = this.devices.delete(deviceId);
    if (removed) this.save();
    return removed;
  }

  list(): Device[] {
    return [...this.devices.values()];
  }

  private get path(): string {
    return join(this.stateDir, "devices.json");
  }

  private load(): void {
    try {
      const raw = JSON.parse(readFileSync(this.path, "utf8")) as Device[];
      for (const device of raw) this.devices.set(device.deviceId, device);
      log.debug("loaded paired devices", { count: this.devices.size });
    } catch {
      /* first run */
    }
  }

  private save(): void {
    const tmp = `${this.path}.${process.pid}.tmp`;
    writeFileSync(tmp, `${JSON.stringify([...this.devices.values()], null, 2)}\n`, { mode: 0o600 });
    renameSync(tmp, this.path);
  }
}
