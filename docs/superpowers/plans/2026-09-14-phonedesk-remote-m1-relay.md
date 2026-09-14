# PhoneDesk Remote Mode Milestone 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify the versioned PhoneDesk relay protocol plus a minimal HTTPS/WebSocket presence relay that can register an authenticated device connection in memory, report presence, reject invalid protocol versions, and route opaque control envelopes without inspecting payload contents.

**Architecture:** Milestone 1 is a Python 3.12 FastAPI service under `phonedesk-relay/`. Protocol messages are JSON envelopes validated with Pydantic; the relay owns connection/presence routing only and does not implement pairing, encryption, ADB, or persistent PostgreSQL storage yet. Presence is held behind a `PresenceRegistry` interface so PostgreSQL-backed persistence can be added in later milestones without changing the WebSocket contract.

**Tech Stack:** Python 3.12, FastAPI, Pydantic v2, Uvicorn, pytest, Starlette TestClient/WebSocket test support.

**Spec:** `docs/superpowers/specs/2026-09-14-phonedesk-remote-mode-design.md`

## Global Constraints

- Protocol major version is exactly `1` for Remote Mode v1.
- Unknown protocol major versions are rejected cleanly.
- Relay payload routing is opaque; the server must not parse or log decrypted screen, input, ADB, or control payload contents.
- No Android lock credentials, ADB pairing codes, one-time pairing secrets, private keys, or decrypted remote payloads may be logged or persisted.
- Public relay transport will ultimately be HTTPS/WSS on port 443; local development may run plain HTTP/WS behind a TLS terminator.
- Milestone 1 must not expose raw ADB, scrcpy, PostgreSQL, debug consoles, or admin ports.
- This milestone intentionally does not implement device pairing, trust persistence, end-to-end session crypto, ADB tunneling, or native fallback.

---

### Task 1: Versioned protocol models

**Files:**
- Create: `phonedesk-relay/phonedesk_relay/protocol.py`
- Create: `phonedesk-relay/tests/test_protocol.py`
- Create: `phonedesk-relay/phonedesk_relay/__init__.py`

**Interfaces:**
- Produces: `PROTOCOL_VERSION: int = 1`
- Produces: `MessageType` enum with `HELLO`, `HELLO_ACK`, `PRESENCE`, `ROUTE`, `ERROR`, `PING`, `PONG`
- Produces: `Envelope` Pydantic model with `protocol_version`, `type`, `device_id`, optional `target_device_id`, optional `session_id`, and opaque `payload`
- Produces: `validate_protocol_version(version: int) -> None`

- [ ] **Step 1: Write the failing tests**

```python
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
```

- [ ] **Step 2: Run the test to verify RED**

Run from `phonedesk-relay/`:

```bash
python -m pytest tests/test_protocol.py -q
```

Expected: FAIL because `phonedesk_relay.protocol` does not exist.

- [ ] **Step 3: Implement the protocol models**

```python
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field, field_validator

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
```

- [ ] **Step 4: Run the protocol tests**

```bash
python -m pytest tests/test_protocol.py -q
```

Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-relay/phonedesk_relay/__init__.py phonedesk-relay/phonedesk_relay/protocol.py phonedesk-relay/tests/test_protocol.py
git commit -m "feat: add Remote Mode v1 protocol envelopes"
```

---

### Task 2: In-memory presence registry

**Files:**
- Create: `phonedesk-relay/phonedesk_relay/presence.py`
- Create: `phonedesk-relay/tests/test_presence.py`

**Interfaces:**
- Produces: `DevicePresence` dataclass with `device_id`, `state`, `connected_at`, `last_seen`
- Produces: `PresenceRegistry.connect(device_id: str, state: str = "ONLINE") -> DevicePresence`
- Produces: `PresenceRegistry.touch(device_id: str) -> DevicePresence | None`
- Produces: `PresenceRegistry.disconnect(device_id: str) -> None`
- Produces: `PresenceRegistry.get(device_id: str) -> DevicePresence | None`

- [ ] **Step 1: Write the failing tests**

```python
from phonedesk_relay.presence import PresenceRegistry


def test_connect_marks_device_online():
    registry = PresenceRegistry()
    presence = registry.connect("phone-1")
    assert presence.device_id == "phone-1"
    assert presence.state == "ONLINE"
    assert registry.get("phone-1") is presence


def test_disconnect_removes_device():
    registry = PresenceRegistry()
    registry.connect("phone-1")
    registry.disconnect("phone-1")
    assert registry.get("phone-1") is None


def test_touch_updates_existing_device_only():
    registry = PresenceRegistry()
    assert registry.touch("missing") is None
    before = registry.connect("phone-1")
    old_last_seen = before.last_seen
    after = registry.touch("phone-1")
    assert after is before
    assert after.last_seen >= old_last_seen
```

- [ ] **Step 2: Run RED**

```bash
python -m pytest tests/test_presence.py -q
```

Expected: FAIL because `phonedesk_relay.presence` does not exist.

- [ ] **Step 3: Implement the registry**

Use `datetime.now(timezone.utc)` for timestamps and an internal dictionary protected by `threading.RLock`. `disconnect()` must delete the entry rather than retaining stale online state.

- [ ] **Step 4: Run the presence tests**

```bash
python -m pytest tests/test_presence.py -q
```

Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-relay/phonedesk_relay/presence.py phonedesk-relay/tests/test_presence.py
git commit -m "feat: add relay presence registry"
```

---

### Task 3: WebSocket relay application

**Files:**
- Create: `phonedesk-relay/phonedesk_relay/app.py`
- Create: `phonedesk-relay/tests/test_websocket.py`

**Interfaces:**
- Produces: `create_app() -> FastAPI`
- Endpoint: `GET /health` returns `{"status":"ok","protocol_version":1}`
- Endpoint: `WS /v1/ws`
- First client message must be `HELLO`
- Successful HELLO returns `HELLO_ACK`
- `PING` returns `PONG`
- `PRESENCE` updates in-memory state and acknowledges with a `PRESENCE` envelope
- `ROUTE` forwards the envelope unchanged to the currently connected `target_device_id`
- Missing/offline target returns an `ERROR` envelope to the sender
- Unsupported protocol version returns `ERROR` and closes the socket with code `1008`

- [ ] **Step 1: Write failing WebSocket tests**

Create tests that use `fastapi.testclient.TestClient` and verify:

```python
def test_health_reports_protocol_v1(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "protocol_version": 1}


def test_hello_acknowledges_device(client):
    with client.websocket_connect("/v1/ws") as ws:
        ws.send_json({"protocol_version": 1, "type": "HELLO", "device_id": "phone-1"})
        reply = ws.receive_json()
        assert reply["type"] == "HELLO_ACK"
        assert reply["device_id"] == "phone-1"


def test_route_forwards_opaque_payload(client):
    with client.websocket_connect("/v1/ws") as phone, client.websocket_connect("/v1/ws") as laptop:
        phone.send_json({"protocol_version": 1, "type": "HELLO", "device_id": "phone-1"})
        phone.receive_json()
        laptop.send_json({"protocol_version": 1, "type": "HELLO", "device_id": "laptop-1"})
        laptop.receive_json()
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
```

Also add tests for unsupported protocol version, offline target, and PING/PONG.

- [ ] **Step 2: Run RED**

```bash
python -m pytest tests/test_websocket.py -q
```

Expected: FAIL because the app does not exist.

- [ ] **Step 3: Implement `ConnectionHub` and `create_app()`**

`ConnectionHub` owns only active WebSocket objects keyed by `device_id`. It must never inspect `Envelope.payload`. `create_app()` owns one `PresenceRegistry` and one `ConnectionHub`. WebSocket disconnect removes both active connection and presence entry.

- [ ] **Step 4: Run all Milestone 1 tests**

```bash
python -m pytest -q
```

Expected: all protocol, presence, health, and WebSocket tests pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-relay/phonedesk_relay/app.py phonedesk-relay/tests/test_websocket.py
git commit -m "feat: add PhoneDesk relay WebSocket presence skeleton"
```

---

### Task 4: Reproducible dependencies and local entry point

**Files:**
- Create: `phonedesk-relay/requirements.txt`
- Create: `phonedesk-relay/requirements-dev.txt`
- Create: `phonedesk-relay/run.py`
- Create: `phonedesk-relay/README.md`

**Interfaces:**
- `requirements.txt`: FastAPI, Pydantic, Uvicorn
- `requirements-dev.txt`: includes runtime requirements plus pytest and httpx
- `run.py`: launches `uvicorn` on `127.0.0.1:8765` for local development only

- [ ] **Step 1: Add pinned dependency manifests**

Use exact compatible versions to make CI reproducible.

- [ ] **Step 2: Add local runner**

```python
import uvicorn

if __name__ == "__main__":
    uvicorn.run("phonedesk_relay.app:create_app", factory=True, host="127.0.0.1", port=8765)
```

- [ ] **Step 3: Document the local test/run commands**

README must state that local `ws://127.0.0.1:8765` is development-only and production will terminate TLS/WSS on port 443.

- [ ] **Step 4: Reinstall from manifests and rerun tests**

```bash
python -m pip install -r requirements-dev.txt
python -m pytest -q
```

Expected: zero failures.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-relay/requirements.txt phonedesk-relay/requirements-dev.txt phonedesk-relay/run.py phonedesk-relay/README.md
git commit -m "chore: make relay milestone reproducible"
```

---

### Task 5: CI verification for relay milestone

**Files:**
- Create: `.github/workflows/phonedesk-remote-relay.yml`

**Interfaces:**
- Runs on pushes to `build/phonedesk-remote-v1` affecting `phonedesk-relay/**` or the workflow file
- Uses Python 3.12
- Installs `phonedesk-relay/requirements-dev.txt`
- Executes `python -m pytest -q`

- [ ] **Step 1: Add the workflow**

```yaml
name: Test PhoneDesk Remote Relay

on:
  push:
    branches:
      - build/phonedesk-remote-v1
    paths:
      - 'phonedesk-relay/**'
      - '.github/workflows/phonedesk-remote-relay.yml'
  workflow_dispatch:

permissions:
  contents: read

jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: phonedesk-relay
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - run: python -m pip install --disable-pip-version-check -r requirements-dev.txt
      - run: python -m pytest -q
```

- [ ] **Step 2: Push and observe CI**

Expected: workflow completes successfully with all Milestone 1 tests passing.

- [ ] **Step 3: Verify attack-surface constraints**

Confirm the milestone contains no raw ADB listener, database listener, pairing-secret logging, screen recording, or debug/admin public endpoint.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/phonedesk-remote-relay.yml
git commit -m "ci: verify PhoneDesk Remote relay milestone"
```

## Milestone 1 Completion Gate

Milestone 1 is complete only when:

1. Protocol v1 envelopes validate and reject unknown versions.
2. Two WebSocket clients can HELLO and appear online.
3. One client can route an opaque envelope to another connected device without server-side payload inspection.
4. Offline routing returns a controlled error.
5. Disconnect clears presence.
6. Tests run from a clean dependency install.
7. GitHub Actions passes on `build/phonedesk-remote-v1`.
8. No raw ADB, pairing-secret persistence, session crypto, or lock bypass exists in this milestone.
