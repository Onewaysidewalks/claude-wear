from __future__ import annotations

from ..config import Settings
from .fake import FakeBrain
from .hermes import HermesBrain


def make_brain(settings: Settings):
    if settings.brain == "fake":
        return FakeBrain()
    if settings.brain == "hermes":
        return HermesBrain(settings)
    raise ValueError(f"unknown brain {settings.brain!r}")
