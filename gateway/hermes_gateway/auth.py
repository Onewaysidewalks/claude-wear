"""Per-device bearer tokens.

A token is minted on the Mac (`hermes-gateway token add watch-phone`), shown once, and stored
only as a SHA-256 hash. The phone keeps the plaintext in EncryptedSharedPreferences; the watch
never sees it (docs/security.md).
"""

from __future__ import annotations

import hashlib
import hmac
import json
import secrets
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path


@dataclass(frozen=True)
class Device:
    id: str
    name: str
    token_hash: str
    created_at: str
    revoked: bool = False


def _hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


class DeviceStore:
    def __init__(self, path: Path):
        self.path = path
        self._devices: dict[str, Device] = {}
        self._load()

    def _load(self) -> None:
        if self.path.exists():
            raw = json.loads(self.path.read_text())
            self._devices = {d["id"]: Device(**d) for d in raw.get("devices", [])}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"devices": [asdict(d) for d in self._devices.values()]}, indent=2))
        tmp.chmod(0o600)
        tmp.replace(self.path)

    def add(self, name: str) -> tuple[Device, str]:
        """Returns the device and the plaintext token. The token is never stored."""
        token = "hg1_" + secrets.token_urlsafe(32)
        device = Device(
            id="dev_" + secrets.token_hex(6),
            name=name,
            token_hash=_hash(token),
            created_at=datetime.now(UTC).isoformat(timespec="seconds"),
        )
        self._devices[device.id] = device
        self._save()
        return device, token

    def revoke(self, device_id: str) -> bool:
        d = self._devices.get(device_id)
        if not d:
            return False
        self._devices[device_id] = Device(**{**asdict(d), "revoked": True})
        self._save()
        return True

    def list(self) -> list[Device]:
        return list(self._devices.values())

    def authenticate(self, token: str | None) -> Device | None:
        if not token or not token.startswith("hg1_"):
            return None
        h = _hash(token)
        for d in self._devices.values():
            if not d.revoked and hmac.compare_digest(d.token_hash, h):
                return d
        return None
