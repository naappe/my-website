from pydantic import ValidationError
import pytest

from phonedesk_relay.protocol import Envelope, MessageType, PROTOCOL_VERSION, validate_protocol_version


def test_protocol_version_is_v1():
    assert PROTOCOL_VERSION == 1


def test_valid_route_envelope_keeps_payload_opaque():
    payload = {"ciphertext": "AAECAw==", "channel": "CONTROL", "sequence": 7}
    env = Envelope(
        protocol_version=1,
        type=MessageType.ROUTE,
        device_id="laptop-1",
        target_device_id="phone-1",
        session_id="session-1",
        payload=payload,
    )
    assert env.payload == payload


def test_unknown_protocol_version_is_rejected():
    with pytest.raises(ValueError, match="unsupported protocol version"):
        validate_protocol_version(2)


def test_device_id_must_not_be_blank():
    with pytest.raises(ValidationError):
        Envelope(protocol_version=1, type=MessageType.HELLO, device_id="   ")
