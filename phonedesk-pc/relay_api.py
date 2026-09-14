from __future__ import annotations

import base64
import hashlib
import json
import secrets
import time
from dataclasses import dataclass

import httpx

from identity import DeviceIdentityStore


def canonical_json(data: dict) -> bytes:
    return json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def canonical_request(method: str, path: str, timestamp: str, nonce: str, body: bytes) -> bytes:
    body_hash = hashlib.sha256(body).hexdigest()
    return f"{method.upper()}\n{path}\n{timestamp}\n{nonce}\n{body_hash}".encode("utf-8")


@dataclass(frozen=True)
class DeviceRegistration:
    device_id: str
    display_name: str
    platform: str
    fingerprint: str
    public_key_der_b64: str


@dataclass(frozen=True)
class TrustedDeviceSummary:
    device_id: str
    display_name: str
    platform: str
    fingerprint: str
    public_key_der_b64: str


class RelayApi:
    def __init__(
        self,
        base_url: str,
        identity_store: DeviceIdentityStore,
        display_name: str,
        transport: httpx.BaseTransport | None = None,
        timeout: float = 15.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.identity_store = identity_store
        self.display_name = display_name.strip() or "Office Laptop"
        self._client = httpx.Client(base_url=self.base_url, transport=transport, timeout=timeout)
        self._device_id: str | None = None

    @property
    def device_id(self) -> str | None:
        return self._device_id

    def register(self) -> DeviceRegistration:
        identity = self.identity_store.load_or_create()
        body = canonical_json(
            {
                "display_name": self.display_name,
                "platform": "windows",
                "public_key_der_b64": base64.b64encode(identity.public_key_der).decode("ascii"),
            }
        )
        response = self._client.post(
            "/v1/devices/register",
            content=body,
            headers={"Content-Type": "application/json"},
        )
        response.raise_for_status()
        payload = response.json()
        registration = DeviceRegistration(
            device_id=payload["device_id"],
            display_name=payload.get("display_name", self.display_name),
            platform=payload.get("platform", "windows"),
            fingerprint=payload.get("fingerprint", identity.fingerprint),
            public_key_der_b64=payload.get(
                "public_key_der_b64",
                base64.b64encode(identity.public_key_der).decode("ascii"),
            ),
        )
        self._device_id = registration.device_id
        return registration

    def _ensure_registered(self) -> str:
        if self._device_id is None:
            self.register()
        assert self._device_id is not None
        return self._device_id

    def _signed_headers(self, method: str, path: str, body: bytes) -> dict[str, str]:
        device_id = self._ensure_registered()
        timestamp = str(int(time.time()))
        nonce = secrets.token_hex(16)
        message = canonical_request(method, path, timestamp, nonce, body)
        signature = self.identity_store.sign(message)
        return {
            "X-PhoneDesk-Device": device_id,
            "X-PhoneDesk-Timestamp": timestamp,
            "X-PhoneDesk-Nonce": nonce,
            "X-PhoneDesk-Signature": base64.b64encode(signature).decode("ascii"),
        }

    def _post_signed(self, path: str, payload: dict) -> httpx.Response:
        body = canonical_json(payload)
        headers = self._signed_headers("POST", path, body)
        headers["Content-Type"] = "application/json"
        response = self._client.post(path, content=body, headers=headers)
        response.raise_for_status()
        return response

    def _get_signed(self, path: str) -> httpx.Response:
        body = b""
        response = self._client.get(path, headers=self._signed_headers("GET", path, body))
        response.raise_for_status()
        return response

    def claim_pairing(self, pairing_id: str, manual_code: str) -> str:
        path = f"/v1/pairings/{pairing_id}/claim"
        response = self._post_signed(path, {"manual_code": manual_code})
        return str(response.json()["state"])

    def list_trusted(self) -> list[TrustedDeviceSummary]:
        response = self._get_signed("/v1/trusted-devices")
        devices = response.json().get("devices", [])
        return [
            TrustedDeviceSummary(
                device_id=item["device_id"],
                display_name=item["display_name"],
                platform=item.get("platform", "android"),
                fingerprint=item["fingerprint"],
                public_key_der_b64=item["public_key_der_b64"],
            )
            for item in devices
        ]

    def close(self) -> None:
        self._client.close()
