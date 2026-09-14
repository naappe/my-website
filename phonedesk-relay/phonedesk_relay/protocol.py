from enum import StrEnum
from typing import Any

from pydantic import BaseModel, field_validator

PROTOCOL_VERSION = 1


class MessageType(StrEnum):
    HELLO = "HELLO"
    HELLO_ACK = "HELLO_ACK"
    PRESENCE = "PRESENCE"
    ROUTE = "ROUTE"
    ERROR = "ERROR"
    PING = "PING"
    PONG = "PONG"


class Envelope(BaseModel):
    protocol_version: int
    type: MessageType
    device_id: str
    target_device_id: str | None = None
    session_id: str | None = None
    payload: Any = None

    @field_validator("device_id")
    @classmethod
    def validate_device_id(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("device_id must not be blank")
        return value


def validate_protocol_version(version: int) -> None:
    if version != PROTOCOL_VERSION:
        raise ValueError(f"unsupported protocol version: {version}")
