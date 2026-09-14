import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from phonedesk_relay.app import create_app


@pytest.fixture
def client():
    with TestClient(create_app()) as test_client:
        yield test_client


def hello(ws, device_id: str):
    ws.send_json({"protocol_version": 1, "type": "HELLO", "device_id": device_id})
    return ws.receive_json()


def test_health_reports_protocol_v1(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "protocol_version": 1}


def test_public_api_docs_are_disabled(client):
    assert client.get("/docs").status_code == 404
    assert client.get("/redoc").status_code == 404
    assert client.get("/openapi.json").status_code == 404


def test_hello_acknowledges_device(client):
    with client.websocket_connect("/v1/ws") as ws:
        reply = hello(ws, "phone-1")
        assert reply["type"] == "HELLO_ACK"
        assert reply["device_id"] == "phone-1"
        assert reply["protocol_version"] == 1


def test_ping_returns_pong(client):
    with client.websocket_connect("/v1/ws") as ws:
        hello(ws, "phone-1")
        ws.send_json({"protocol_version": 1, "type": "PING", "device_id": "phone-1"})
        reply = ws.receive_json()
        assert reply["type"] == "PONG"
        assert reply["device_id"] == "phone-1"


def test_offline_route_returns_controlled_error(client):
    with client.websocket_connect("/v1/ws") as ws:
        hello(ws, "laptop-1")
        ws.send_json({
            "protocol_version": 1,
            "type": "ROUTE",
            "device_id": "laptop-1",
            "target_device_id": "phone-1",
            "session_id": "s1",
            "payload": {"ciphertext": "opaque-data", "channel": "CONTROL"},
        })
        reply = ws.receive_json()
        assert reply["type"] == "ERROR"
        assert reply["payload"]["code"] == "TARGET_OFFLINE"
        assert "opaque-data" not in str(reply)


def test_route_forwards_opaque_payload(client):
    with client.websocket_connect("/v1/ws") as phone, client.websocket_connect("/v1/ws") as laptop:
        hello(phone, "phone-1")
        hello(laptop, "laptop-1")
        envelope = {
            "protocol_version": 1,
            "type": "ROUTE",
            "device_id": "laptop-1",
            "target_device_id": "phone-1",
            "session_id": "s1",
            "payload": {"ciphertext": "opaque-data", "channel": "CONTROL"},
        }
        laptop.send_json(envelope)
        assert phone.receive_json() == envelope


def test_unsupported_protocol_version_returns_error_and_closes(client):
    with client.websocket_connect("/v1/ws") as ws:
        ws.send_json({"protocol_version": 2, "type": "HELLO", "device_id": "phone-1"})
        reply = ws.receive_json()
        assert reply["type"] == "ERROR"
        assert reply["payload"]["code"] == "UNSUPPORTED_PROTOCOL_VERSION"
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_json()
        assert exc.value.code == 1008


def test_first_message_must_be_hello(client):
    with client.websocket_connect("/v1/ws") as ws:
        ws.send_json({"protocol_version": 1, "type": "PING", "device_id": "phone-1"})
        reply = ws.receive_json()
        assert reply["type"] == "ERROR"
        assert reply["payload"]["code"] == "HELLO_REQUIRED"
