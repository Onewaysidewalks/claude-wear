"""Risk classification for tool actions.

Hermes has its own approval flow; the gateway relays those approvals to the device. On top of
that, anything matching these patterns is marked `risk: high`, which the reference clients
render as an explicit confirmation that has to be tapped, never auto-allowed, never voice-only.
"""

from __future__ import annotations

import re

DEFAULT_HIGH_RISK = [
    r"\brm\s+(-[a-z]*r[a-z]*\s+|-[a-z]*f[a-z]*\s+)",  # rm -rf and friends
    r"\bsudo\b",
    r"\bmkfs\b|\bdiskutil\s+erase|\bdd\s+if=",
    r"\bgit\s+push\s+.*--force|\bgit\s+reset\s+--hard",
    r"\b(pay|payment|transfer|wire|purchase|buy|order)\b",
    r"\b(unlock|disarm|open)\b.*\b(door|lock|garage|gate|alarm)\b",
    r"\b(delete|drop|truncate|wipe|erase)\b",
    r"\b(send|email|text|sms|post|tweet|publish)\b",
    r"\bshutdown\b|\breboot\b|\bkill\s+-9\b|\bpkill\b|\blaunchctl\s+(unload|remove)",
    r"\bsecurity\s+find-generic-password|\bkeychain\b|\b\.ssh/|\bAPI[_ ]?KEY\b",
]


class RiskPolicy:
    def __init__(self, extra_patterns: list[str] | None = None):
        self._patterns = [re.compile(p, re.IGNORECASE | re.DOTALL) for p in DEFAULT_HIGH_RISK + (extra_patterns or [])]

    def classify(self, action: str, detail: str = "") -> str:
        text = f"{action} {detail}"
        return "high" if any(p.search(text) for p in self._patterns) else "normal"
