from dataclasses import dataclass

from .device_auth import device_id_from_public_key


@dataclass(frozen=True)
class RegisteredDevice:
    device_id: str
    display_name: str
    platform: str
    public_key_der: bytes


class DeviceRegistry:
    def __init__(self) -> None:
        self._devices: dict[str, RegisteredDevice] = {}
        self._trust: set[frozenset[str]] = set()

    def register(self, display_name: str, platform: str, public_key_der: bytes) -> RegisteredDevice:
        if not display_name.strip():
            raise ValueError("display_name must not be blank")
        if platform not in {"android", "windows"}:
            raise ValueError("unsupported platform")
        device = RegisteredDevice(
            device_id_from_public_key(public_key_der),
            display_name.strip(),
            platform,
            bytes(public_key_der),
        )
        self._devices[device.device_id] = device
        return device

    def get(self, device_id: str) -> RegisteredDevice | None:
        return self._devices.get(device_id)

    def trust(self, first: str, second: str) -> None:
        if first == second or self.get(first) is None or self.get(second) is None:
            raise ValueError("both distinct devices must be registered")
        self._trust.add(frozenset((first, second)))

    def is_trusted(self, first: str, second: str) -> bool:
        return frozenset((first, second)) in self._trust

    def list_trusted(self, device_id: str) -> list[RegisteredDevice]:
        result = []
        for relation in self._trust:
            if device_id in relation:
                peer_id = next(value for value in relation if value != device_id)
                peer = self.get(peer_id)
                if peer is not None:
                    result.append(peer)
        return sorted(result, key=lambda item: item.device_id)
