from __future__ import annotations

from typing import Any

from fastapi import FastAPI, WebSocket
from pydantic import ValidationError
from starlette.websockets import WebSocketDisconnect

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


def create_app() -> FastAPI:
    app = FastAPI(title="PhoneDesk Relay", version="0.1.0")
    presence = PresenceRegistry()
    hub = ConnectionHub()

    @app.get("/health")
    async def health() -> dict[str, Any]:
        return {"status": "ok", "protocol_version": PROTOCOL_VERSION}

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
