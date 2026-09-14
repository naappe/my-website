from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class TrustedPhone:
    device_id: str
    display_name: str
    fingerprint: str
    public_key_der_b64: str


class TrustedPhoneStore:
    def __init__(self, path: Path) -> None:
        self.path = Path(path)

    def load(self) -> TrustedPhone | None:
        if not self.path.exists():
            return None
        data = json.loads(self.path.read_text(encoding="utf-8"))
        return TrustedPhone(
            device_id=str(data["device_id"]),
            display_name=str(data["display_name"]),
            fingerprint=str(data["fingerprint"]),
            public_key_der_b64=str(data["public_key_der_b64"]),
        )

    def save(self, phone: TrustedPhone) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(asdict(phone), sort_keys=True, separators=(",", ":")),
            encoding="utf-8",
        )
        temporary.replace(self.path)

    def clear(self) -> None:
        try:
            self.path.unlink()
        except FileNotFoundError:
            pass
