from __future__ import annotations

import base64
import hashlib
import time
from typing import Any

from fastapi import FastAPI, HTTPException, Request, WebSocket
from pydantic import BaseModel, ValidationError
from starlette.websockets import WebSocketDisconnect

from .device_auth import ReplayGuard, canonical_request, verify_signature
from .devices import DeviceRegistry, RegisteredDevice
from .pairing import PairingManager, PairingState
from .presence import PresenceRegistry
from .protocol import Envelope, MessageType, PROTOCOL_VERSION, validate_protocol_version


class ConnectionHub:
    def __init__(self) -> None:
        self._connections: dict[str, WebSocket] = {}

    def register(self, device_id: str, websocket: WebSocket) -> None:
        self._connections[device_id] = websocket

    def unregister(self, device_id: str, websocket: WebSocket) -> None:
        current = self._connections.get(device_id)
        if current is websocket:
            self._connections.pop(device_id, None)

    async def send_to(self, device_id: str, message: dict[str, Any]) -> bool:
        websocket = self._connections.get(device_id)
        if websocket is None:
            return False
        await websocket.send_json(message)
        return True


class DeviceRegistrationInput(BaseModel):
    display_name: str
    platform: str
    public_key_der_b64: str


class PairingClaimInput(BaseModel):
    manual_code: str


class PairingConfirmInput(BaseModel):
    approved: bool


def _error(device_id: str, code: str, message: str) -> dict[str, Any]:
    return {
        "protocol_version": PROTOCOL_VERSION,
        "type": MessageType.ERROR.value,
        "device_id": device_id,
        "payload": {"code": code, "message": message},
    }


def _parse_envelope(raw: Any) -> Envelope:
    if not isinstance(raw, dict):
        raise ValueError("message must be a JSON object")
    return Envelope.model_validate(raw)


def _fingerprint(public_key_der: bytes) -> str:
    digest = hashlib.sha256(public_key_der).digest()
    return ":".join(f"{value:02X}" for value in digest[:4])


def _device_summary(device: RegisteredDevice) -> dict[str, Any]:
    return {
        "device_id": device.device_id,
        "display_name": device.display_name,
        "platform": device.platform,
        "fingerprint": _fingerprint(device.public_key_der),
        "public_key_der_b64": base64.b64encode(device.public_key_der).decode("ascii"),
    }


def create_app(pairing_hmac_key: bytes = b"development-only-change-me") -> FastAPI:
    app = FastAPI(
        title="PhoneDesk Relay",
        version="0.2.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    presence = PresenceRegistry()
    hub = ConnectionHub()
    devices = DeviceRegistry()
    pairings = PairingManager(pairing_hmac_key)
    replay_guard = ReplayGuard(max_age_seconds=120)

    async def authenticate_request(request: Request) -> tuple[RegisteredDevice, bytes]:
        device_id = request.headers.get("X-PhoneDesk-Device", "").strip()
        timestamp_text = request.headers.get("X-PhoneDesk-Timestamp", "").strip()
        nonce = request.headers.get("X-PhoneDesk-Nonce", "").strip()
        signature = request.headers.get("X-PhoneDesk-Signature", "").strip()
        if not device_id or not timestamp_text or not nonce or not signature:
            raise HTTPException(status_code=401, detail="missing PhoneDesk authentication headers")

        device = devices.get(device_id)
        if device is None:
            raise HTTPException(status_code=401, detail="unknown device")

        try:
            timestamp = int(timestamp_text)
        except ValueError as exc:
            raise HTTPException(status_code=401, detail="invalid timestamp") from exc

        if not replay_guard.accept(device_id, timestamp, nonce):
            raise HTTPException(status_code=401, detail="expired or replayed request")

        body = await request.body()
        message = canonical_request(request.method, request.url.path, timestamp_text, nonce, body)
        if not verify_signature(device.public_key_der, message, signature):
            raise HTTPException(status_code=401, detail="invalid signature")
        return device, body

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return {"status": "ok", "protocol_version": PROTOCOL_VERSION}

    @app.post("/v1/devices/register")
    async def register_device(payload: DeviceRegistrationInput) -> dict[str, Any]:
        try:
            public_key_der = base64.b64decode(payload.public_key_der_b64, validate=True)
            device = devices.register(payload.display_name, payload.platform, public_key_der)
        except (ValueError, TypeError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return _device_summary(device)

    @app.post("/v1/pairings")
    async def create_pairing(request: Request) -> dict[str, Any]:
        device, _ = await authenticate_request(request)
        if device.platform != "android":
            raise HTTPException(status_code=403, detail="only Android devices can create pairings")
        created = pairings.create(device.device_id, now=int(time.time()))
        return {
            "pairing_id": created.pairing_id,
            "manual_code": created.manual_code,
            "expires_at": created.expires_at,
            "expires_in_seconds": 300,
        }

    @app.post("/v1/pairings/{pairing_id}/claim")
    async def claim_pairing(pairing_id: str, request: Request) -> dict[str, Any]:
        device, body = await authenticate_request(request)
        if device.platform != "windows":
            raise HTTPException(status_code=403, detail="only Windows devices can claim pairings")
        try:
            payload = PairingClaimInput.model_validate_json(body)
            view = pairings.claim(pairing_id, device.device_id, payload.manual_code, now=int(time.time()))
        except (ValidationError, ValueError, KeyError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return {"pairing_id": view.pairing_id, "state": view.state.value}

    @app.get("/v1/pairings/{pairing_id}")
    async def pairing_status(pairing_id: str, request: Request) -> dict[str, Any]:
        device, _ = await authenticate_request(request)
        try:
            view = pairings.get(pairing_id, now=int(time.time()))
        except KeyError as exc:
            raise HTTPException(status_code=404, detail="pairing not found") from exc
        if view.phone_id != device.device_id:
            raise HTTPException(status_code=403, detail="only the pairing phone can view status")
        computer = devices.get(view.computer_id) if view.computer_id else None
        return {
            "pairing_id": view.pairing_id,
            "state": view.state.value,
            "expires_at": view.expires_at,
            "computer": _device_summary(computer) if computer is not None else None,
        }

    @app.post("/v1/pairings/{pairing_id}/confirm")
    async def confirm_pairing(pairing_id: str, request: Request) -> dict[str, Any]:
        device, body = await authenticate_request(request)
        try:
            payload = PairingConfirmInput.model_validate_json(body)
            view = pairings.confirm(pairing_id, device.device_id, payload.approved, now=int(time.time()))
            if view.state is PairingState.CONFIRMED:
                if view.computer_id is None:
                    raise ValueError("claimed pairing has no computer")
                devices.trust(view.phone_id, view.computer_id)
        except (ValidationError, ValueError, KeyError) as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        return {"pairing_id": view.pairing_id, "state": view.state.value}

    @app.get("/v1/trusted-devices")
    async def trusted_devices(request: Request) -> dict[str, Any]:
        device, _ = await authenticate_request(request)
        return {"devices": [_device_summary(peer) for peer in devices.list_trusted(device.device_id)]}

    @app.websocket("/v1/ws")
    async def relay_socket(websocket: WebSocket) -> None:
        await websocket.accept()
        authenticated_device_id: str | None = None

        try:
            try:
                raw = await websocket.receive_json()
                hello = _parse_envelope(raw)
            except (ValidationError, ValueError):
                await websocket.send_json(_error("unknown", "BAD_MESSAGE", "invalid HELLO message"))
                await websocket.close(code=1008)
                return

            try:
                validate_protocol_version(hello.protocol_version)
            except ValueError:
                await websocket.send_json(
                    _error(hello.device_id, "UNSUPPORTED_PROTOCOL_VERSION", "unsupported protocol version")
                )
                await websocket.close(code=1008)
                return

            if hello.type is not MessageType.HELLO:
                await websocket.send_json(_error(hello.device_id, "HELLO_REQUIRED", "first message must be HELLO"))
                await websocket.close(code=1008)
                return

            authenticated_device_id = hello.device_id
            hub.register(authenticated_device_id, websocket)
            presence.connect(authenticated_device_id)
            await websocket.send_json(
                {
                    "protocol_version": PROTOCOL_VERSION,
                    "type": MessageType.HELLO_ACK.value,
                    "device_id": authenticated_device_id,
                    "payload": {"state": "ONLINE"},
                }
            )

            while True:
                try:
                    raw = await websocket.receive_json()
                    envelope = _parse_envelope(raw)
                except (ValidationError, ValueError):
                    await websocket.send_json(
                        _error(authenticated_device_id, "BAD_MESSAGE", "invalid protocol message")
                    )
                    continue

                try:
                    validate_protocol_version(envelope.protocol_version)
                except ValueError:
                    await websocket.send_json(
                        _error(authenticated_device_id, "UNSUPPORTED_PROTOCOL_VERSION", "unsupported protocol version")
                    )
                    await websocket.close(code=1008)
                    return

                if envelope.device_id != authenticated_device_id:
                    await websocket.send_json(
                        _error(authenticated_device_id, "DEVICE_ID_MISMATCH", "device_id does not match HELLO identity")
                    )
                    continue

                presence.touch(authenticated_device_id)

                if envelope.type is MessageType.PING:
                    await websocket.send_json(
                        {
                            "protocol_version": PROTOCOL_VERSION,
                            "type": MessageType.PONG.value,
                            "device_id": authenticated_device_id,
                        }
                    )
                    continue

                if envelope.type is MessageType.PRESENCE:
                    state = "ONLINE"
                    if isinstance(envelope.payload, dict):
                        candidate = envelope.payload.get("state")
                        if isinstance(candidate, str) and candidate.strip():
                            state = candidate.strip().upper()
                    presence.connect(authenticated_device_id, state=state)
                    await websocket.send_json(
                        {
                            "protocol_version": PROTOCOL_VERSION,
                            "type": MessageType.PRESENCE.value,
                            "device_id": authenticated_device_id,
                            "payload": {"state": state},
                        }
                    )
                    continue

                if envelope.type is MessageType.ROUTE:
                    target = envelope.target_device_id
                    if not target:
                        await websocket.send_json(
                            _error(authenticated_device_id, "TARGET_REQUIRED", "target_device_id is required")
                        )
                        continue
                    delivered = await hub.send_to(target, raw)
                    if not delivered:
                        await websocket.send_json(
                            _error(authenticated_device_id, "TARGET_OFFLINE", "target device is offline")
                        )
                    continue

                await websocket.send_json(
                    _error(authenticated_device_id, "UNSUPPORTED_MESSAGE", f"unsupported message type: {envelope.type.value}")
                )

        except WebSocketDisconnect:
            pass
        finally:
            if authenticated_device_id is not None:
                hub.unregister(authenticated_device_id, websocket)
                presence.disconnect(authenticated_device_id)

    return app
