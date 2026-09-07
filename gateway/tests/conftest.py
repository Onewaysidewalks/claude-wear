from __future__ import annotations

import asyncio
import socket

import pytest
import uvicorn
from httpx import AsyncClient

from hermes_gateway.app import create_app
from hermes_gateway.auth import DeviceStore
from hermes_gateway.brains.fake import FakeBrain
from hermes_gateway.config import Settings
from hermes_gateway.turns import TurnEngine


class FakeTranscriber:
    kind = "fake"

    def __init__(self, text: str = "what time is it"):
        self.text = text
        self.calls: list[tuple[int, int, str]] = []

    async def transcribe(self, pcm: bytes, sample_rate: int, locale: str) -> str:
        self.calls.append((len(pcm), sample_rate, locale))
        return self.text


class FakeSpeaker:
    kind = "fake"
    format = "audio/wav"

    async def synthesize(self, text: str):
        yield b"RIFF" + text.encode()[:8]
        yield b"tail"


@pytest.fixture
def settings(tmp_path):
    return Settings(home=tmp_path, brain="fake", approval_timeout_s=2)


@pytest.fixture
def devices(settings):
    return DeviceStore(settings.tokens_path)


@pytest.fixture
def token(devices):
    _, tok = devices.add("test-phone")
    return tok


@pytest.fixture
def brain():
    return FakeBrain()


@pytest.fixture
def transcriber():
    return FakeTranscriber()


@pytest.fixture
def engine(brain, transcriber, settings):
    return TurnEngine(
        brain=brain,
        transcriber=transcriber,
        speaker=FakeSpeaker(),
        default_approval_timeout_s=settings.approval_timeout_s,
    )


@pytest.fixture
def app(settings, engine, devices):
    return create_app(settings, engine=engine, devices=devices)


@pytest.fixture
async def client(app):
    """A real server on a random loopback port. httpx's ASGITransport buffers whole responses,
    which hides exactly the thing these tests are about: events arriving while a turn is open."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", lifespan="off"))
    task = asyncio.create_task(server.serve())
    while not server.started:
        await asyncio.sleep(0.01)
    try:
        async with AsyncClient(base_url=f"http://127.0.0.1:{port}", timeout=10) as c:
            yield c
    finally:
        server.should_exit = True
        await task


def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}
