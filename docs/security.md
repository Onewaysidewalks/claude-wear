# Security

**Threat model.** A lost or stolen watch; a phone on hostile Wi-Fi; a bug in a client; a
voice command from someone who is not you. Not in scope: a compromised Mac (then Hermes itself
is gone), or a compromised Tailscale account.

## Where things live

| secret / trust | watch | phone | Mac |
| --- | --- | --- | --- |
| gateway address | no | yes (encrypted prefs) | – |
| device token (`hg1_…`) | **no** | yes, `EncryptedSharedPreferences` (AES-256-GCM, Keystore-backed master key) | SHA-256 hash only, `~/.hermes-gateway/devices.json` (mode 600) |
| Hermes API key | no | no | env / config on the Mac only |
| tailnet membership | no (Wear stubs `VpnService`) | yes | yes |
| STT / TTS provider keys | no | no | Mac only |

The watch talks only to the phone, over the Data Layer, which Google encrypts end to end
between the paired devices. It has no address to leak and no token to steal.

## Network

* The gateway binds to the Mac's Tailscale IP and refuses anything else without
  `--allow-any-bind`. Hermes' own API server stays on loopback.
* The phone refuses to save a plain `http://` gateway address unless its host is a Tailscale
  (100.64.0.0/10) or loopback address (`GatewayConfig.problem()`); anything else must be
  HTTPS. Tailscale provides the encryption and mutual authentication of the two nodes; the
  bearer token identifies the *app instance* on top of that.
* Nothing listens on the public internet. If the Data Layer's cloud path proves unusable
  (PLAN.md A3), the relay goes behind Tailscale Funnel or a minimal authenticated WebSocket
  forwarder; Hermes is still never exposed.

## Tokens

* One token per phone, minted on the Mac, shown once, stored hashed, constant-time compared.
* `hermes-gateway token revoke <device id>` kills one phone without touching the others.
* Sessions, turns, and approvals are scoped to the device that authenticated: one phone cannot
  approve another phone's action or read its turns (`404 unknown_turn`).

## Confirmations

* Hermes decides when an action needs approval (its `approvals.mode`); the gateway relays it
  and classifies the action with `policy.py`. Anything matching the high-risk patterns
  (destructive shell, payments, locks/doors/alarms, sending messages, credentials, power) is
  `risk: high`.
* The reference clients render `high` as two deliberate taps (`Allow…` then `Confirm`) on the
  watch and a two-step dialog on the phone. There is no voice "yes".
* An approval nobody answers is **denied** when it expires (default 120 s). Cancelling a turn
  denies its pending approvals.
* Add your own patterns in `~/.hermes-gateway/config.json` `high_risk_patterns`.

## What to do if

* **Watch lost**: nothing to revoke; unpair it from the phone.
* **Phone lost**: `hermes-gateway token revoke <id>`, and remove the device from Tailscale.
* **Suspect the Mac**: stop the gateway (`launchctl unload`), rotate `API_SERVER_KEY`, reissue
  tokens.
