# PhoneDesk Trusted Pairing Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the next independently testable Remote Mode stage: Android and Windows register their long-term identities with the relay, pair once using an expiring 6-digit fallback code, require explicit phone confirmation, and persist a trusted-device relationship without using Wireless Debugging.

**Architecture:** The relay adds signed HTTPS control endpoints on top of the existing P-256 device identities. The phone creates a short-lived pairing session, Windows claims it with a 6-digit code while proving possession of its identity key, and the phone confirms the computer before trust is created. Android and Windows store only public peer identity metadata. This is subproject 1 of the approved Push-to-Start architecture; FCM access requests, active native streaming/input, and reconnect/media work are separate plans after this stage is proven.

**Tech Stack:** Python 3.12, FastAPI 0.115.6, Pydantic 2.10.4, pytest 8.3.4, httpx 0.28.1, cryptography 44.0.0, Android Kotlin/JVM 17, compileSdk 36, targetSdk 35, JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-09-14-phonedesk-remote-mode-design.md`

## Global Constraints

- No Android PIN, password, pattern, fingerprint, face-unlock, or other lock credential may be stored, replayed, or bypassed.
- Do not suppress, disguise, rename, or hide Android-mandated privacy/security indicators.
- Idle Remote Mode must not require a foreground service or persistent PhoneDesk notification.
- Pairing material expires after 5 minutes or immediately after successful use.
- The 6-digit fallback code is single-use and never written to persistent logs or client configuration.
- Private identity keys remain on-device: Android Keystore on Android, DPAPI-protected application storage on Windows.
- The relay stores public identities, pairing-state metadata, and trust relationships only; never private keys or plaintext lock credentials.
- Protocol major version remains `1`.
- Existing local Wireless Debugging/scrcpy behavior remains available only under Advanced/Local diagnostics and is not required by this pairing stage.
- This stage intentionally uses in-memory relay registries so the trust protocol can be proven first. PostgreSQL persistence is part of the later hardening/deployment plan.

## File Structure

### Relay
- Create `phonedesk-relay/phonedesk_relay/device_auth.py` — stable device IDs, canonical signed requests, signature verification, replay protection.
- Create `phonedesk-relay/phonedesk_relay/devices.py` — registered public device identities and trusted relationships.
- Create `phonedesk-relay/phonedesk_relay/pairing.py` — pairing-session state machine, code HMAC, expiry, attempt limits.
- Modify `phonedesk-relay/phonedesk_relay/app.py` — device registration and pairing endpoints.
- Modify `phonedesk-relay/requirements.txt` — add `cryptography==44.0.0`.
- Create relay tests for authentication, pairing, replay protection, and trust creation.

### Windows
- Create `phonedesk-pc/relay_api.py` — deterministic JSON encoding, signed requests, registration and pairing claim.
- Create `phonedesk-pc/trusted_phone.py` — public trusted-phone metadata persistence.
- Modify `phonedesk-pc/requirements-remote.txt` — add `httpx==0.28.1`.
- Create `phonedesk-pc/test_relay_api.py` and `phonedesk-pc/test_trusted_phone.py`.
- Modify `phonedesk-pc/phonedesk_pc.py` only after API/storage tests pass.

### Android
- Create `phonedesk-android/app/src/main/java/com/phonedesk/remote/RelayAuth.kt` — stable device ID and canonical signed request bytes.
- Create `phonedesk-android/app/src/main/java/com/phonedesk/remote/RelayApi.kt` — pairing API contract and HTTP implementation.
- Create `phonedesk-android/app/src/main/java/com/phonedesk/remote/TrustedComputerStore.kt` — public peer metadata persistence.
- Create `phonedesk-android/app/src/main/java/com/phonedesk/remote/PairingManager.kt` — create/status/confirm orchestration.
- Add JVM unit tests under `phonedesk-android/app/src/test/java/com/phonedesk/remote/`.
- Modify `phonedesk-android/app/src/main/java/com/phonedesk/MainActivity.kt` last.

---

### Task 1: Stable device IDs and signed relay requests

**Files:**
- Create: `phonedesk-relay/phonedesk_relay/device_auth.py`
- Test: `phonedesk-relay/tests/test_device_auth.py`
- Modify: `phonedesk-relay/requirements.txt`

**Interfaces:**
- `device_id_from_public_key(public_key_der: bytes) -> str`
- `canonical_json(data: dict) -> bytes`
- `canonical_request(method: str, path: str, timestamp: str, nonce: str, body: bytes) -> bytes`
- `verify_signature(public_key_der: bytes, message: bytes, signature_b64: str) -> bool`
- `ReplayGuard.accept(device_id: str, timestamp: int, nonce: str, now: int) -> bool`

- [ ] **Step 1: Write failing tests**

```python
# phonedesk-relay/tests/test_device_auth.py
import base64
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from phonedesk_relay.device_auth import (
    ReplayGuard,
    canonical_json,
    canonical_request,
    device_id_from_public_key,
    verify_signature,
)


def test_device_id_vector_is_cross_client_stable():
    assert device_id_from_public_key(b"PhoneDesk") == "pd_vYVSrqAZQSyIHKdKfecnCA"


def test_canonical_json_is_sorted_and_minified():
    assert canonical_json({"z": 2, "a": 1}) == b'{"a":1,"z":2}'


def test_signed_request_verifies():
    key = ec.generate_private_key(ec.SECP256R1())
    der = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    body = canonical_json({"approved": True})
    message = canonical_request("POST", "/v1/pairings/p1/confirm", "1700000000", "n1", body)
    signature = key.sign(message, ec.ECDSA(hashes.SHA256()))
    assert verify_signature(der, message, base64.b64encode(signature).decode("ascii"))


def test_replay_guard_rejects_reused_nonce_and_old_timestamp():
    guard = ReplayGuard(max_age_seconds=120)
    assert guard.accept("pd_test", 1000, "n1", now=1050)
    assert not guard.accept("pd_test", 1000, "n1", now=1051)
    assert not guard.accept("pd_test", 800, "n2", now=1051)
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-relay
python -m pytest tests/test_device_auth.py -q
```

Expected: import failure because `device_auth.py` does not exist.

- [ ] **Step 3: Implement the primitives**

```python
# phonedesk-relay/phonedesk_relay/device_auth.py
from __future__ import annotations

import base64
import hashlib
import json
import time

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def device_id_from_public_key(public_key_der: bytes) -> str:
    digest = hashlib.sha256(public_key_der).digest()[:16]
    token = base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")
    return f"pd_{token}"


def canonical_json(data: dict) -> bytes:
    return json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def canonical_request(method: str, path: str, timestamp: str, nonce: str, body: bytes) -> bytes:
    body_hash = hashlib.sha256(body).hexdigest()
    return f"{method.upper()}\n{path}\n{timestamp}\n{nonce}\n{body_hash}".encode("utf-8")


def verify_signature(public_key_der: bytes, message: bytes, signature_b64: str) -> bool:
    try:
        signature = base64.b64decode(signature_b64, validate=True)
        public_key = serialization.load_der_public_key(public_key_der)
        if not isinstance(public_key, ec.EllipticCurvePublicKey):
            return False
        public_key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
        return True
    except (ValueError, InvalidSignature):
        return False


class ReplayGuard:
    def __init__(self, max_age_seconds: int = 120) -> None:
        self.max_age_seconds = max_age_seconds
        self._seen: dict[tuple[str, str], int] = {}

    def accept(self, device_id: str, timestamp: int, nonce: str, now: int | None = None) -> bool:
        current = int(time.time()) if now is None else now
        if abs(current - timestamp) > self.max_age_seconds:
            return False
        key = (device_id, nonce)
        if key in self._seen:
            return False
        self._seen[key] = current
        cutoff = current - self.max_age_seconds
        self._seen = {key: seen_at for key, seen_at in self._seen.items() if seen_at >= cutoff}
        return True
```

Append to `phonedesk-relay/requirements.txt`:

```text
cryptography==44.0.0
```

- [ ] **Step 4: Run GREEN**

```bash
cd phonedesk-relay
python -m pytest tests/test_device_auth.py -q
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-relay/phonedesk_relay/device_auth.py phonedesk-relay/tests/test_device_auth.py phonedesk-relay/requirements.txt
git commit -m "feat: add signed relay request authentication"
```

### Task 2: Device registry, trust registry, and expiring pairing state machine

**Files:**
- Create: `phonedesk-relay/phonedesk_relay/devices.py`
- Create: `phonedesk-relay/phonedesk_relay/pairing.py`
- Test: `phonedesk-relay/tests/test_devices.py`
- Test: `phonedesk-relay/tests/test_pairing.py`

**Interfaces:**
- `DeviceRegistry.register(display_name: str, platform: str, public_key_der: bytes) -> RegisteredDevice`
- `DeviceRegistry.get(device_id: str) -> RegisteredDevice | None`
- `DeviceRegistry.trust(first: str, second: str) -> None`
- `DeviceRegistry.is_trusted(first: str, second: str) -> bool`
- `PairingManager.create(phone_id: str, now: int) -> CreatedPairing`
- `PairingManager.claim(pairing_id: str, computer_id: str, code: str, now: int) -> PairingView`
- `PairingManager.confirm(pairing_id: str, phone_id: str, approved: bool, now: int) -> PairingView`
- Pairing states: `CREATED`, `CLAIMED`, `CONFIRMED`, `DENIED`, `EXPIRED`.

- [ ] **Step 1: Write failing registry tests**

```python
# phonedesk-relay/tests/test_devices.py
from phonedesk_relay.devices import DeviceRegistry


def test_trust_requires_two_registered_distinct_devices():
    registry = DeviceRegistry()
    phone = registry.register("My Phone", "android", b"phone-key")
    pc = registry.register("Office Laptop", "windows", b"pc-key")
    registry.trust(phone.device_id, pc.device_id)
    assert registry.is_trusted(phone.device_id, pc.device_id)
    assert registry.is_trusted(pc.device_id, phone.device_id)
```

```python
# phonedesk-relay/tests/test_pairing.py
import pytest
from phonedesk_relay.pairing import PairingManager, PairingState


def test_pairing_claim_then_phone_confirm():
    manager = PairingManager(hmac_key=b"test-key")
    created = manager.create("phone", now=1000)
    assert len(created.manual_code) == 6
    assert created.manual_code.isdigit()
    claimed = manager.claim(created.pairing_id, "pc", created.manual_code, now=1010)
    assert claimed.state is PairingState.CLAIMED
    confirmed = manager.confirm(created.pairing_id, "phone", True, now=1020)
    assert confirmed.state is PairingState.CONFIRMED


def test_pairing_expires_after_300_seconds():
    manager = PairingManager(hmac_key=b"test-key")
    created = manager.create("phone", now=1000)
    assert manager.get(created.pairing_id, now=1301).state is PairingState.EXPIRED


def test_pairing_locks_after_five_bad_codes():
    manager = PairingManager(hmac_key=b"test-key", max_attempts=5)
    created = manager.create("phone", now=1000)
    wrong = "000000" if created.manual_code != "000000" else "999999"
    for second in range(5):
        with pytest.raises(ValueError):
            manager.claim(created.pairing_id, "pc", wrong, now=1001 + second)
    with pytest.raises(ValueError, match="attempt"):
        manager.claim(created.pairing_id, "pc", created.manual_code, now=1010)
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-relay
python -m pytest tests/test_devices.py tests/test_pairing.py -q
```

Expected: both modules missing.

- [ ] **Step 3: Implement the device registry**

```python
# phonedesk-relay/phonedesk_relay/devices.py
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
```

- [ ] **Step 4: Implement the pairing manager with no stored plaintext code**

`pairing.py` must keep two representations: `CreatedPairing`, returned only by `create()` and containing the one-time plaintext code, and an internal `_PairingRecord` that stores only `code_digest`.

```python
from dataclasses import dataclass
from enum import StrEnum
import hashlib
import hmac
import secrets


class PairingState(StrEnum):
    CREATED = "CREATED"
    CLAIMED = "CLAIMED"
    CONFIRMED = "CONFIRMED"
    DENIED = "DENIED"
    EXPIRED = "EXPIRED"


@dataclass(frozen=True)
class CreatedPairing:
    pairing_id: str
    manual_code: str
    expires_at: int


@dataclass(frozen=True)
class PairingView:
    pairing_id: str
    phone_id: str
    computer_id: str | None
    state: PairingState
    expires_at: int


@dataclass
class _PairingRecord:
    pairing_id: str
    phone_id: str
    code_digest: bytes
    expires_at: int
    computer_id: str | None = None
    state: PairingState = PairingState.CREATED
    failed_attempts: int = 0


class PairingManager:
    def __init__(self, hmac_key: bytes, max_attempts: int = 5) -> None:
        self.hmac_key = hmac_key
        self.max_attempts = max_attempts
        self._records: dict[str, _PairingRecord] = {}

    def _digest(self, code: str) -> bytes:
        return hmac.new(self.hmac_key, code.encode("ascii"), hashlib.sha256).digest()

    def create(self, phone_id: str, now: int) -> CreatedPairing:
        pairing_id = secrets.token_urlsafe(18)
        code = f"{secrets.randbelow(1_000_000):06d}"
        record = _PairingRecord(pairing_id, phone_id, self._digest(code), now + 300)
        self._records[pairing_id] = record
        return CreatedPairing(pairing_id, code, record.expires_at)

    def _view(self, record: _PairingRecord, now: int) -> PairingView:
        if now > record.expires_at and record.state not in {PairingState.CONFIRMED, PairingState.DENIED}:
            record.state = PairingState.EXPIRED
        return PairingView(record.pairing_id, record.phone_id, record.computer_id, record.state, record.expires_at)

    def get(self, pairing_id: str, now: int) -> PairingView:
        return self._view(self._records[pairing_id], now)

    def claim(self, pairing_id: str, computer_id: str, code: str, now: int) -> PairingView:
        record = self._records[pairing_id]
        view = self._view(record, now)
        if view.state is PairingState.EXPIRED:
            raise ValueError("pairing expired")
        if record.failed_attempts >= self.max_attempts:
            raise ValueError("pairing attempt limit reached")
        if record.state is not PairingState.CREATED:
            raise ValueError("pairing is not claimable")
        if not hmac.compare_digest(record.code_digest, self._digest(code)):
            record.failed_attempts += 1
            raise ValueError("invalid pairing code")
        record.computer_id = computer_id
        record.state = PairingState.CLAIMED
        return self._view(record, now)

    def confirm(self, pairing_id: str, phone_id: str, approved: bool, now: int) -> PairingView:
        record = self._records[pairing_id]
        self._view(record, now)
        if record.phone_id != phone_id:
            raise ValueError("only the pairing phone can confirm")
        if record.state is not PairingState.CLAIMED:
            raise ValueError("pairing has not been claimed")
        record.state = PairingState.CONFIRMED if approved else PairingState.DENIED
        return self._view(record, now)
```

- [ ] **Step 5: Run GREEN and commit**

```bash
cd phonedesk-relay
python -m pytest tests/test_devices.py tests/test_pairing.py -q
```

Expected: all tests pass.

```bash
git add phonedesk-relay/phonedesk_relay/devices.py phonedesk-relay/phonedesk_relay/pairing.py phonedesk-relay/tests/test_devices.py phonedesk-relay/tests/test_pairing.py
git commit -m "feat: add relay trusted pairing state machine"
```

### Task 3: Authenticated pairing HTTP API

**Files:**
- Modify: `phonedesk-relay/phonedesk_relay/app.py`
- Create: `phonedesk-relay/tests/test_pairing_api.py`

**Interfaces:**
- `POST /v1/devices/register`
- `POST /v1/pairings`
- `POST /v1/pairings/{pairing_id}/claim`
- `GET /v1/pairings/{pairing_id}`
- `POST /v1/pairings/{pairing_id}/confirm`
- `GET /v1/trusted-devices`
- Signed endpoints require `X-PhoneDesk-Device`, `X-PhoneDesk-Timestamp`, `X-PhoneDesk-Nonce`, `X-PhoneDesk-Signature`.

- [ ] **Step 1: Write the complete API test helper and happy-path test**

```python
# phonedesk-relay/tests/test_pairing_api.py
import base64
import json
import time
import uuid

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from phonedesk_relay.app import create_app
from phonedesk_relay.device_auth import canonical_json, canonical_request


class Signer:
    def __init__(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.public_der = self.key.public_key().public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
        self.device_id = None

    def register(self, client: TestClient, name: str, platform: str):
        response = client.post("/v1/devices/register", json={
            "display_name": name,
            "platform": platform,
            "public_key_der_b64": base64.b64encode(self.public_der).decode("ascii"),
        })
        assert response.status_code == 200
        self.device_id = response.json()["device_id"]
        return response.json()

    def headers(self, method: str, path: str, body: bytes):
        timestamp = str(int(time.time()))
        nonce = uuid.uuid4().hex
        message = canonical_request(method, path, timestamp, nonce, body)
        signature = self.key.sign(message, ec.ECDSA(hashes.SHA256()))
        return {
            "X-PhoneDesk-Device": self.device_id,
            "X-PhoneDesk-Timestamp": timestamp,
            "X-PhoneDesk-Nonce": nonce,
            "X-PhoneDesk-Signature": base64.b64encode(signature).decode("ascii"),
            "Content-Type": "application/json",
        }

    def post(self, client: TestClient, path: str, payload: dict):
        body = canonical_json(payload)
        return client.post(path, content=body, headers=self.headers("POST", path, body))

    def get(self, client: TestClient, path: str):
        body = b""
        return client.get(path, headers=self.headers("GET", path, body))


def test_manual_pairing_creates_trust_only_after_phone_confirmation():
    with TestClient(create_app(pairing_hmac_key=b"test-pairing-key")) as client:
        phone = Signer()
        pc = Signer()
        phone.register(client, "My Phone", "android")
        pc.register(client, "Office Laptop", "windows")

        created = phone.post(client, "/v1/pairings", {})
        assert created.status_code == 200
        pairing_id = created.json()["pairing_id"]
        code = created.json()["manual_code"]

        claim = pc.post(client, f"/v1/pairings/{pairing_id}/claim", {"manual_code": code})
        assert claim.status_code == 200
        assert claim.json()["state"] == "CLAIMED"

        status = phone.get(client, f"/v1/pairings/{pairing_id}")
        assert status.json()["computer"]["display_name"] == "Office Laptop"

        confirm = phone.post(client, f"/v1/pairings/{pairing_id}/confirm", {"approved": True})
        assert confirm.json()["state"] == "CONFIRMED"

        trusted = phone.get(client, "/v1/trusted-devices")
        assert trusted.json()["devices"][0]["display_name"] == "Office Laptop"
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-relay
python -m pytest tests/test_pairing_api.py -q
```

Expected: endpoint failures because the new API does not exist.

- [ ] **Step 3: Add endpoint models and signed-request authentication to `create_app()`**

Change the factory signature to:

```python
def create_app(pairing_hmac_key: bytes = b"development-only-change-me") -> FastAPI:
```

Inside `create_app()` create one `DeviceRegistry`, one `PairingManager`, and one `ReplayGuard`. Add a private async helper that:

1. Reads the four `X-PhoneDesk-*` headers.
2. Looks up the registered public key by `X-PhoneDesk-Device`.
3. Parses timestamp as an integer.
4. Calls `ReplayGuard.accept(...)`.
5. Builds `canonical_request(request.method, request.url.path, timestamp_text, nonce, raw_body)`.
6. Calls `verify_signature(...)`.
7. Raises `HTTPException(status_code=401)` for any failure.

Registration derives the device ID from the submitted public key. Pairing creation requires an authenticated Android device. Claim requires an authenticated Windows device. Confirmation requires the pairing phone. On approved confirmation call `registry.trust(view.phone_id, view.computer_id)` after asserting `computer_id` is present.

- [ ] **Step 4: Run the full relay suite**

```bash
cd phonedesk-relay
python -m pytest -q
```

Expected: all existing websocket/presence tests plus pairing/auth tests pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-relay/phonedesk_relay/app.py phonedesk-relay/tests/test_pairing_api.py
git commit -m "feat: expose authenticated trusted pairing API"
```

### Task 4: Windows signed relay client and trusted-phone store

**Files:**
- Create: `phonedesk-pc/relay_api.py`
- Create: `phonedesk-pc/trusted_phone.py`
- Create: `phonedesk-pc/test_relay_api.py`
- Create: `phonedesk-pc/test_trusted_phone.py`
- Modify: `phonedesk-pc/requirements-remote.txt`

**Interfaces:**
- `RelayApi.register() -> DeviceRegistration`
- `RelayApi.claim_pairing(pairing_id: str, manual_code: str) -> str`
- `RelayApi.list_trusted() -> list[TrustedDeviceSummary]`
- `TrustedPhoneStore.load/save/clear`

- [ ] **Step 1: Write failing signed-request test**

```python
# phonedesk-pc/test_relay_api.py
import json
import tempfile
import unittest
from pathlib import Path

import httpx

from identity import DeviceIdentityStore
from test_identity import ReversibleTestProtector
from relay_api import RelayApi


class RelayApiTests(unittest.TestCase):
    def test_claim_pairing_sends_signed_headers_and_canonical_body(self):
        seen = {}

        def handler(request: httpx.Request) -> httpx.Response:
            seen["headers"] = dict(request.headers)
            seen["body"] = request.content
            return httpx.Response(200, json={"state": "CLAIMED"})

        with tempfile.TemporaryDirectory() as directory:
            identity = DeviceIdentityStore(Path(directory) / "id.json", ReversibleTestProtector())
            transport = httpx.MockTransport(handler)
            api = RelayApi("https://relay.test", identity, "Office Laptop", transport=transport)
            api.register()
            api.claim_pairing("pair-1", "123456")

        self.assertEqual(seen["body"], b'{"manual_code":"123456"}')
        self.assertIn("x-phonedesk-signature", seen["headers"])
        self.assertIn("x-phonedesk-device", seen["headers"])
        self.assertIn("x-phonedesk-nonce", seen["headers"])
        self.assertIn("x-phonedesk-timestamp", seen["headers"])
```

- [ ] **Step 2: Write failing public-metadata persistence test**

```python
# phonedesk-pc/test_trusted_phone.py
import tempfile
import unittest
from pathlib import Path

from trusted_phone import TrustedPhone, TrustedPhoneStore


class TrustedPhoneStoreTests(unittest.TestCase):
    def test_roundtrip_contains_no_pairing_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trusted-phone.json"
            store = TrustedPhoneStore(path)
            phone = TrustedPhone("pd_phone", "My Phone", "AA:BB:CC:DD", "BASE64PUBLIC")
            store.save(phone)
            self.assertEqual(TrustedPhoneStore(path).load(), phone)
            raw = path.read_text(encoding="utf-8")
            self.assertNotIn("pairing", raw.lower())
            self.assertNotIn("123456", raw)
```

- [ ] **Step 3: Run RED**

```bash
cd phonedesk-pc
python -m unittest -v test_relay_api.py test_trusted_phone.py
```

Expected: missing-module failures.

- [ ] **Step 4: Implement deterministic signing and atomic trust storage**

Append to `requirements-remote.txt`:

```text
httpx==0.28.1
```

`relay_api.py` must serialize request dictionaries using:

```python
def canonical_json(data: dict) -> bytes:
    return json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
```

For every signed request, create a Unix-seconds timestamp and `secrets.token_hex(16)` nonce, build the exact canonical request format from Task 1, sign with `DeviceIdentityStore.sign`, and send the already-serialized bytes using `content=body`, not `json=payload`.

`trusted_phone.py` must use:

```python
@dataclass(frozen=True)
class TrustedPhone:
    device_id: str
    display_name: str
    fingerprint: str
    public_key_der_b64: str
```

`TrustedPhoneStore.save()` writes JSON to `path.with_suffix(path.suffix + ".tmp")` and then calls `temporary.replace(path)`.

- [ ] **Step 5: Run GREEN and full Windows tests**

```bash
cd phonedesk-pc
python -m unittest discover -v -p 'test_*.py'
```

Expected: zero failures.

- [ ] **Step 6: Commit**

```bash
git add phonedesk-pc/relay_api.py phonedesk-pc/trusted_phone.py phonedesk-pc/test_relay_api.py phonedesk-pc/test_trusted_phone.py phonedesk-pc/requirements-remote.txt
git commit -m "feat: add Windows trusted pairing client"
```

### Task 5: Android relay auth, pairing manager, and trusted-computer store

**Files:**
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/remote/RelayAuth.kt`
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/remote/RelayApi.kt`
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/remote/TrustedComputerStore.kt`
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/remote/PairingManager.kt`
- Create tests under `phonedesk-android/app/src/test/java/com/phonedesk/remote/`

**Interfaces:**
- `RelayAuth.deviceId(publicKeyDer: ByteArray): String`
- `RelayAuth.canonicalJson(values: Map<String, Any?>): ByteArray`
- `RelayAuth.canonicalRequest(...)`
- `PairingRelayApi.registerDevice/createPairing/getPairingStatus/confirmPairing`
- `TrustedComputerStore.load/save/clear`
- `PairingManager.begin/refresh/confirm`

- [ ] **Step 1: Write failing cross-client vector tests**

```kotlin
// RelayAuthTest.kt
package com.phonedesk.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayAuthTest {
    @Test
    fun deviceIdMatchesRelayVector() {
        assertEquals("pd_vYVSrqAZQSyIHKdKfecnCA", RelayAuth.deviceId("PhoneDesk".toByteArray()))
    }

    @Test
    fun canonicalJsonSortsKeysAndMinifies() {
        assertArrayEquals(
            "{\"a\":1,\"z\":2}".toByteArray(),
            RelayAuth.canonicalJson(mapOf("z" to 2, "a" to 1)),
        )
    }
}
```

- [ ] **Step 2: Write failing pairing-manager test with explicit fakes**

```kotlin
// PairingManagerTest.kt
package com.phonedesk.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeRelayApi : PairingRelayApi {
    var confirmed = false
    override suspend fun registerDevice() = DeviceRegistration("pd_phone", "AA:AA:AA:AA")
    override suspend fun createPairing() = PairingOffer("p1", "123456", 300)
    override suspend fun getPairingStatus(pairingId: String) =
        PairingCandidate("pd_pc", "Office Laptop", "BB:BB:BB:BB", "BASE64PUBLIC")
    override suspend fun confirmPairing(pairingId: String, approved: Boolean) {
        confirmed = approved
    }
}

private class FakeTrustedStore : TrustedComputerStore {
    private var value: TrustedComputer? = null
    override fun load() = value
    override fun save(computer: TrustedComputer) { value = computer }
    override fun clear() { value = null }
}

class PairingManagerTest {
    @Test
    fun storesComputerOnlyAfterExplicitApproval() = runTest {
        val relay = FakeRelayApi()
        val store = FakeTrustedStore()
        val manager = PairingManager(relay, store)
        manager.begin()
        val candidate = manager.refresh()
        assertEquals("Office Laptop", candidate.displayName)
        assertNull(store.load())
        manager.confirm(true)
        assertEquals("Office Laptop", store.load()?.displayName)
        assertEquals(true, relay.confirmed)
    }
}
```

- [ ] **Step 3: Run RED**

```bash
cd phonedesk-android
gradle testDebugUnitTest --tests 'com.phonedesk.remote.*' --stacktrace
```

Expected: missing classes/interfaces.

- [ ] **Step 4: Implement the pure Kotlin auth/store/manager contracts**

Use these exact data contracts:

```kotlin
data class DeviceRegistration(val deviceId: String, val fingerprint: String)
data class PairingOffer(val pairingId: String, val manualCode: String, val expiresInSeconds: Int)
data class PairingCandidate(
    val deviceId: String,
    val displayName: String,
    val fingerprint: String,
    val publicKeyDerBase64: String,
)
data class TrustedComputer(
    val deviceId: String,
    val displayName: String,
    val fingerprint: String,
    val publicKeyDerBase64: String,
)

interface PairingRelayApi {
    suspend fun registerDevice(): DeviceRegistration
    suspend fun createPairing(): PairingOffer
    suspend fun getPairingStatus(pairingId: String): PairingCandidate
    suspend fun confirmPairing(pairingId: String, approved: Boolean)
}

interface TrustedComputerStore {
    fun load(): TrustedComputer?
    fun save(computer: TrustedComputer)
    fun clear()
}
```

`PairingManager` must hold the current `PairingOffer` and `PairingCandidate` in memory only. `confirm(true)` calls the relay first, then persists the candidate. `confirm(false)` calls the relay and persists nothing.

`RelayAuth.canonicalJson()` only needs the primitive types used in this stage: `String`, `Boolean`, integral `Number`, and `null`. Sort map keys lexicographically and escape string values using JSON rules; do not use unordered `JSONObject.toString()` for signed bodies.

- [ ] **Step 5: Implement Android HTTP transport after the manager tests pass**

`RelayApi.kt` should implement `PairingRelayApi` using `HttpURLConnection` on `Dispatchers.IO`. Build the exact body bytes with `RelayAuth.canonicalJson()`, sign `RelayAuth.canonicalRequest()` using the existing `DeviceIdentityStore.sign()`, and set the same four `X-PhoneDesk-*` headers as Windows. Do not log the manual pairing code.

- [ ] **Step 6: Run GREEN and Android build**

```bash
cd phonedesk-android
gradle testDebugUnitTest assembleDebug --stacktrace
```

Expected: unit tests pass and the debug APK builds.

- [ ] **Step 7: Commit**

```bash
git add phonedesk-android/app/src/main/java/com/phonedesk/remote phonedesk-android/app/src/test/java/com/phonedesk/remote
git commit -m "feat: add Android trusted pairing foundation"
```

### Task 6: User-friendly first-time pairing UI

**Files:**
- Modify: `phonedesk-android/app/src/main/java/com/phonedesk/MainActivity.kt`
- Modify: `phonedesk-pc/phonedesk_pc.py`
- Modify: `phonedesk-pc/test_phonedesk_pc.py`

**UI contract:**
- Android normal screen: **PhoneDesk**, **Ready**, **My trusted computer**, **PAIR NEW COMPUTER**, **Trusted devices**, **Settings**.
- Android pairing state: show the temporary 6-digit code and expiry. When a Windows computer claims it, show its display name and fingerprint with **TRUST THIS COMPUTER** and **DENY**.
- Windows first-time section: **PAIR WITH PHONE**, pairing ID/code entry, visible status. Once trust exists, normal Remote Mode shows **My Phone — Ready** and hides the pairing form.
- Existing IP/port/ADB/scrcpy controls remain under **Advanced / Local diagnostics** only.

- [ ] **Step 1: Write failing pure Windows UI-state tests**

Add to `test_phonedesk_pc.py`:

```python
def test_remote_pairing_panel_hidden_after_trust(self):
    self.assertTrue(phonedesk_pc.remote_pairing_panel_visible(False))
    self.assertFalse(phonedesk_pc.remote_pairing_panel_visible(True))


def test_remote_primary_status(self):
    self.assertEqual(phonedesk_pc.remote_primary_status(False), "Pair your phone")
    self.assertEqual(phonedesk_pc.remote_primary_status(True), "Ready")
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-pc
python -m unittest -v test_phonedesk_pc.py
```

Expected: missing helper failures.

- [ ] **Step 3: Implement the pure UI helpers first**

```python
def remote_pairing_panel_visible(has_trusted_phone: bool) -> bool:
    return not has_trusted_phone


def remote_primary_status(has_trusted_phone: bool) -> str:
    return "Ready" if has_trusted_phone else "Pair your phone"
```

- [ ] **Step 4: Wire the Windows Remote Pairing panel to `RelayApi` and `TrustedPhoneStore`**

Normal-use labels must not mention Wireless Debugging, ADB, IP, ports, or scrcpy. The existing local-control card is moved beneath a collapsed **Advanced / Local diagnostics** control. Pairing success stores the phone returned by `/v1/trusted-devices`; it never stores the temporary code.

- [ ] **Step 5: Wire Android `MainActivity` to `PairingManager`**

The Activity owns pairing polling only while its pairing screen is visible. Poll every 2 seconds for at most the offer's 300-second lifetime. Stop polling in `onStop()` or after confirm/deny. Do not add a Service, boot receiver, persistent notification, FCM receiver, MediaProjection, or Accessibility service in this stage.

- [ ] **Step 6: Verify both client suites**

```bash
cd phonedesk-pc
python -m unittest discover -v -p 'test_*.py'
```

```bash
cd phonedesk-android
gradle testDebugUnitTest assembleDebug --stacktrace
```

Expected: Windows tests pass; Android tests pass and debug APK builds.

- [ ] **Step 7: Commit**

```bash
git add phonedesk-pc/phonedesk_pc.py phonedesk-pc/test_phonedesk_pc.py phonedesk-android/app/src/main/java/com/phonedesk/MainActivity.kt
git commit -m "feat: add user friendly Remote Mode pairing UI"
```

### Task 7: Security regressions and CI gate

**Files:**
- Create: `phonedesk-relay/tests/test_pairing_security.py`
- Modify workflows only if needed to execute all new tests: `.github/workflows/phonedesk-remote-relay.yml`, `.github/workflows/phonedesk-remote-windows.yml`, `.github/workflows/phonedesk-remote-android.yml`

- [ ] **Step 1: Add concrete security regression tests**

```python
# phonedesk-relay/tests/test_pairing_security.py
import pytest
from phonedesk_relay.pairing import PairingManager, PairingState


def test_expired_pairing_cannot_be_claimed():
    manager = PairingManager(hmac_key=b"security-test")
    created = manager.create("phone", now=1000)
    with pytest.raises(ValueError, match="expired"):
        manager.claim(created.pairing_id, "pc", created.manual_code, now=1301)


def test_phone_rejects_confirmation_before_claim():
    manager = PairingManager(hmac_key=b"security-test")
    created = manager.create("phone", now=1000)
    with pytest.raises(ValueError, match="claimed"):
        manager.confirm(created.pairing_id, "phone", True, now=1010)


def test_wrong_phone_cannot_confirm_claimed_pairing():
    manager = PairingManager(hmac_key=b"security-test")
    created = manager.create("phone", now=1000)
    manager.claim(created.pairing_id, "pc", created.manual_code, now=1010)
    with pytest.raises(ValueError, match="pairing phone"):
        manager.confirm(created.pairing_id, "attacker-phone", True, now=1020)


def test_denied_pairing_never_reaches_confirmed():
    manager = PairingManager(hmac_key=b"security-test")
    created = manager.create("phone", now=1000)
    manager.claim(created.pairing_id, "pc", created.manual_code, now=1010)
    denied = manager.confirm(created.pairing_id, "phone", False, now=1020)
    assert denied.state is PairingState.DENIED
```

Replay rejection remains covered by `test_device_auth.py`; trust-only-after-approval remains covered by `test_pairing_api.py`.

- [ ] **Step 2: Run all relay tests**

```bash
cd phonedesk-relay
python -m pytest -q
```

Expected: zero failures.

- [ ] **Step 3: Run all Windows tests**

```bash
cd phonedesk-pc
python -m unittest discover -v -p 'test_*.py'
```

Expected: zero failures.

- [ ] **Step 4: Run Android tests and debug build**

```bash
cd phonedesk-android
gradle testDebugUnitTest assembleDebug --stacktrace
```

Expected: zero test failures and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Confirm idle architecture has not regressed**

Inspect `AndroidManifest.xml` and the new Android source. This stage must contain no foreground-service declaration, no boot receiver, no MediaProjection service, no Accessibility service, and no FCM service. Those belong to later approved stages with their own tests and permission flows.

- [ ] **Step 6: Commit verification coverage**

```bash
git add phonedesk-relay/tests/test_pairing_security.py .github/workflows/phonedesk-remote-relay.yml .github/workflows/phonedesk-remote-windows.yml .github/workflows/phonedesk-remote-android.yml
git commit -m "test: gate trusted pairing foundation"
```

## Stage Acceptance Criteria

This plan is complete only when all of the following are demonstrated:

1. Android and Windows can each register a public P-256 identity with the relay.
2. Signed control requests are rejected when stale, invalid, or replayed.
3. Android can create a 5-minute pairing session and display a 6-digit single-use fallback code.
4. Windows can claim the session while proving possession of its own identity key.
5. Android sees the claimed computer name/fingerprint and must explicitly approve it.
6. Trust is created only after phone approval.
7. Client persistence contains only public peer metadata; the temporary code is not persisted.
8. Normal Remote Mode pairing requires no IP, port, ADB, or scrcpy fields.
9. No foreground service, persistent idle PhoneDesk notification, screen capture, Accessibility control, FCM service, or permanent background socket is introduced by this stage.
10. Relay, Windows, and Android CI all pass.

## Follow-up Plans Required by the Approved Spec

After this stage passes, write and execute separate plans in this order:

1. **Push Access Request Control Plane** — Firebase Cloud Messaging registration, **PhoneDesk access request**, ACCEPT/DENY, Windows waiting state, no active stream yet.
2. **Active Native Session** — active-only foreground service, WSS session, lock-pause/unlock-resume, 2-minute reconnect state machine.
3. **Native Screen + Input** — MediaProjection/MediaCodec low-latency video, Accessibility input, Auto/Fast/Sharp profiles, Windows native viewer and automatic mirror reopen.
4. **Hardening + Packaging** — revocation UI, PostgreSQL persistence, deployment, mobile-data end-to-end testing, battery/latency measurement, APK/EXE packaging.

The approved design also calls for a preferred high-entropy QR pairing path. The relay trust state machine in this plan is deliberately written so a later QR-secret claim path can be added without changing device identity or confirmation semantics; camera/QR UX is deferred until the 6-digit end-to-end trust flow is proven on the real phone and laptop.
