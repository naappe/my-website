from dataclasses import dataclass
from datetime import datetime, timezone
from threading import RLock


@dataclass
class DevicePresence:
    device_id: str
    state: str
    connected_at: datetime
    last_seen: datetime


class PresenceRegistry:
    def __init__(self) -> None:
        self._items: dict[str, DevicePresence] = {}
        self._lock = RLock()

    def connect(self, device_id: str, state: str = "ONLINE") -> DevicePresence:
        now = datetime.now(timezone.utc)
        presence = DevicePresence(
            device_id=device_id,
            state=state,
            connected_at=now,
            last_seen=now,
        )
        with self._lock:
            self._items[device_id] = presence
        return presence

    def touch(self, device_id: str) -> DevicePresence | None:
        with self._lock:
            presence = self._items.get(device_id)
            if presence is None:
                return None
            presence.last_seen = datetime.now(timezone.utc)
            return presence

    def disconnect(self, device_id: str) -> None:
        with self._lock:
            self._items.pop(device_id, None)

    def get(self, device_id: str) -> DevicePresence | None:
        with self._lock:
            return self._items.get(device_id)
