# Hermes Gateway changelog

## 1.0.0 — 2026-09-07

* v1 contract: `/v1/health`, `/v1/whoami`, `/v1/turns` (SSE and `?stream=false`),
  `/v1/turns/{id}/approvals/{approval_id}`, `/v1/turns/{id}/cancel`, `WS /v1/stream`.
* Events: `turn.started`, `input.transcript`, `output.delta`, `tool.started`, `tool.completed`,
  `approval.required`, `approval.resolved`, `output.done`, `speech.chunk` (reserved, off by default),
  `turn.completed`, `error`.
* Brains: `hermes` (over Hermes Agent's API server) and `fake` (scripted, for development).
* STT: none / OpenAI-compatible HTTP / in-process faster-whisper. TTS: none / OpenAI-compatible.
* Per-device bearer tokens, hashed at rest; high-risk classification of approvals.
