"""Speech to text. The watch sends raw PCM; the gateway turns it into words before Hermes
sees anything, so Hermes stays a text brain and the transcript is in the event stream."""

from __future__ import annotations

import asyncio
import struct
from typing import Protocol

import httpx

from .config import Settings


class Transcriber(Protocol):
    kind: str

    async def transcribe(self, pcm: bytes, sample_rate: int, locale: str) -> str: ...


def wav_bytes(pcm: bytes, sample_rate: int, channels: int = 1) -> bytes:
    data_size = len(pcm)
    header = struct.pack(
        "<4sI4s4sIHHIIHH4sI",
        b"RIFF",
        36 + data_size,
        b"WAVE",
        b"fmt ",
        16,
        1,
        channels,
        sample_rate,
        sample_rate * channels * 2,
        channels * 2,
        16,
        b"data",
        data_size,
    )
    return header + pcm


class NoTranscriber:
    kind = "none"

    async def transcribe(self, pcm: bytes, sample_rate: int, locale: str) -> str:
        raise RuntimeError("speech-to-text is not configured on this gateway (HERMES_GATEWAY_STT)")


class OpenAICompatibleTranscriber:
    """POST multipart WAV to /v1/audio/transcriptions. Works with a local whisper server on the
    Mac (speaches, faster-whisper-server, whisper.cpp server) as well as hosted providers."""

    kind = "openai"

    def __init__(
        self,
        base_url: str,
        api_key: str = "",
        model: str = "whisper-1",
        client: httpx.AsyncClient | None = None,
    ):
        self.base = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self._client = client or httpx.AsyncClient(timeout=60.0)

    async def transcribe(self, pcm: bytes, sample_rate: int, locale: str) -> str:
        headers = {"Authorization": f"Bearer {self.api_key}"} if self.api_key else {}
        data = {"model": self.model, "response_format": "json"}
        if locale:
            data["language"] = locale.split("-")[0]
        r = await self._client.post(
            f"{self.base}/v1/audio/transcriptions",
            headers=headers,
            data=data,
            files={"file": ("utterance.wav", wav_bytes(pcm, sample_rate), "audio/wav")},
        )
        r.raise_for_status()
        return (r.json().get("text") or "").strip()


class LocalWhisperTranscriber:
    kind = "whisper"

    def __init__(self, model: str = "base.en"):
        from faster_whisper import WhisperModel  # optional dependency

        self._model = WhisperModel(model, device="auto", compute_type="int8")

    async def transcribe(self, pcm: bytes, sample_rate: int, locale: str) -> str:
        import numpy as np

        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
        if sample_rate != 16000:
            idx = (np.arange(int(len(audio) * 16000 / sample_rate)) * sample_rate / 16000).astype(np.int64)
            audio = audio[np.clip(idx, 0, len(audio) - 1)]

        def _run() -> str:
            segments, _ = self._model.transcribe(audio, language=locale.split("-")[0] if locale else None)
            return " ".join(s.text.strip() for s in segments).strip()

        return await asyncio.to_thread(_run)


def make_transcriber(settings: Settings) -> Transcriber:
    if settings.stt == "none":
        return NoTranscriber()
    if settings.stt == "openai":
        return OpenAICompatibleTranscriber(settings.stt_base_url, settings.stt_api_key, settings.stt_model)
    if settings.stt == "whisper":
        return LocalWhisperTranscriber(settings.whisper_model)
    raise ValueError(f"unknown stt {settings.stt!r}")
