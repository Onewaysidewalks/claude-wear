"""Text to speech on the server. Off in v1 by default: the watch speaks with its own
TextToSpeech. When switched on, the gateway streams `speech.chunk` events after `output.done`."""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Protocol

import httpx

from .config import Settings


class Speaker(Protocol):
    kind: str
    format: str

    def synthesize(self, text: str) -> AsyncIterator[bytes]: ...


class NoSpeaker:
    kind = "none"
    format = "audio/mpeg"

    async def synthesize(self, text: str) -> AsyncIterator[bytes]:
        return
        yield b""  # pragma: no cover


class OpenAICompatibleSpeaker:
    kind = "openai"
    format = "audio/mpeg"

    def __init__(self, base_url: str, api_key: str = "", model: str = "tts-1", voice: str = "alloy", client=None):
        self.base = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.voice = voice
        self._client = client or httpx.AsyncClient(timeout=60.0)

    async def synthesize(self, text: str) -> AsyncIterator[bytes]:
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        body = {"model": self.model, "voice": self.voice, "input": text, "response_format": "mp3"}
        async with self._client.stream("POST", f"{self.base}/v1/audio/speech", json=body, headers=headers) as r:
            r.raise_for_status()
            async for chunk in r.aiter_bytes(16 * 1024):
                if chunk:
                    yield chunk


def make_speaker(settings: Settings) -> Speaker:
    if settings.tts == "none":
        return NoSpeaker()
    if settings.tts == "openai":
        return OpenAICompatibleSpeaker(settings.tts_base_url, settings.tts_api_key, settings.tts_model, settings.tts_voice)
    raise ValueError(f"unknown tts {settings.tts!r}")
