"""Configuration. Environment variables first, then a JSON file, then defaults.

Nothing here is secret except HERMES_API_KEY and the device token file; both stay on the Mac.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_HOME = Path(os.environ.get("HERMES_GATEWAY_HOME", Path.home() / ".hermes-gateway"))


@dataclass
class Settings:
    # Bind to the tailnet address of the Mac, never 0.0.0.0. `hermes-gateway serve` refuses to
    # start on a non-loopback, non-CGNAT (100.64.0.0/10) address unless --allow-any-bind is given.
    host: str = "127.0.0.1"
    port: int = 8731

    # Where Hermes' own API server listens (see Hermes docs: gateway.api_server). Loopback by
    # default: Hermes should not be reachable from anywhere but this process.
    hermes_base_url: str = "http://127.0.0.1:8642"
    hermes_api_key: str = ""
    # X-Hermes-Session-Key: the long-term-memory scope. One person, one key: every device shares
    # the same Hermes memory, which is the point ("one brain").
    hermes_memory_key: str = "gateway"
    # Which brain to run. "hermes" for the real thing, "fake" for development without Hermes.
    brain: str = "hermes"

    # Speech-to-text. "none" rejects audio turns; "openai" posts WAV to an OpenAI-compatible
    # /v1/audio/transcriptions (a local whisper server on the Mac, or a hosted one); "whisper"
    # runs faster-whisper in-process (pip install 'hermes-gateway[whisper]').
    stt: str = "none"
    stt_base_url: str = "http://127.0.0.1:8000"
    stt_api_key: str = ""
    stt_model: str = "whisper-1"
    whisper_model: str = "base.en"

    # Text-to-speech. "none" means clients use on-device TTS (v1 default). "openai" posts to an
    # OpenAI-compatible /v1/audio/speech and streams speech.chunk events.
    tts: str = "none"
    tts_base_url: str = "http://127.0.0.1:8000"
    tts_api_key: str = ""
    tts_model: str = "tts-1"
    tts_voice: str = "alloy"

    home: Path = field(default_factory=lambda: DEFAULT_HOME)
    # Extra regexes (case-insensitive) that mark a tool action as high risk. See policy.py.
    high_risk_patterns: list[str] = field(default_factory=list)
    approval_timeout_s: int = 120
    log_level: str = "info"

    @property
    def tokens_path(self) -> Path:
        return self.home / "devices.json"

    @property
    def sessions_path(self) -> Path:
        return self.home / "sessions.json"

    @classmethod
    def load(cls, path: Path | None = None) -> Settings:
        s = cls()
        file = path or (s.home / "config.json")
        if file.exists():
            data = json.loads(file.read_text())
            for k, v in data.items():
                if hasattr(s, k):
                    setattr(s, k, Path(v) if k == "home" else v)
        env = {
            "host": "HERMES_GATEWAY_HOST",
            "port": "HERMES_GATEWAY_PORT",
            "hermes_base_url": "HERMES_BASE_URL",
            "hermes_api_key": "HERMES_API_KEY",
            "hermes_memory_key": "HERMES_MEMORY_KEY",
            "brain": "HERMES_GATEWAY_BRAIN",
            "stt": "HERMES_GATEWAY_STT",
            "stt_base_url": "HERMES_GATEWAY_STT_BASE_URL",
            "stt_api_key": "HERMES_GATEWAY_STT_API_KEY",
            "stt_model": "HERMES_GATEWAY_STT_MODEL",
            "whisper_model": "HERMES_GATEWAY_WHISPER_MODEL",
            "tts": "HERMES_GATEWAY_TTS",
            "tts_base_url": "HERMES_GATEWAY_TTS_BASE_URL",
            "tts_api_key": "HERMES_GATEWAY_TTS_API_KEY",
            "tts_model": "HERMES_GATEWAY_TTS_MODEL",
            "tts_voice": "HERMES_GATEWAY_TTS_VOICE",
            "approval_timeout_s": "HERMES_GATEWAY_APPROVAL_TIMEOUT_S",
            "log_level": "HERMES_GATEWAY_LOG_LEVEL",
        }
        for attr, var in env.items():
            if (val := os.environ.get(var)) is not None:
                current = getattr(s, attr)
                setattr(s, attr, int(val) if isinstance(current, int) else val)
        return s
