# PhoneDesk Trusted Pairing Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the next independently testable Remote Mode stage: Android and Windows register their long-term identities with the relay, pair once using an expiring 6-digit fallback code, require explicit phone confirmation, and persist a revocable trusted-device relationship without using Wireless Debugging.

**Architecture:** The relay adds cryptographically authenticated HTTP control endpoints on top of the existing P-256 device identities. A pairing session is created by the phone, claimed by a Windows identity with a short-lived code, and confirmed by the phone before trust is created. Android and Windows persist only public peer identity/trust metadata; temporary pairing codes are never persisted. This is subproject 1 of the approved Push-to-Start design. FCM access requests, active MediaProjection streaming, Accessibility input, and the native Windows viewer are separate follow-up plans.

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
- Existing local Wireless Debugging/scrcpy behavior remains available only as an advanced/local diagnostic path and must not be required by this pairing foundation.

## File Structure

### Relay
- Create `phonedesk-relay/phonedesk_relay/device_auth.py` — device-id derivation, signature verification, nonce/timestamp replay protection.
- Create `phonedesk-relay/phonedesk_relay/devices.py` — registered public device identities and trusted relationships.
- Create `phonedesk-relay/phonedesk_relay/pairing.py` — pairing-session state machine, code hashing, expiry, claim limits.
- Modify `phonedesk-relay/phonedesk_relay/app.py` — registration, pairing create/claim/status/confirm/trusted-device endpoints.
- Modify `phonedesk-relay/requirements.txt` — add `cryptography==44.0.0`.
- Create tests under `phonedesk-relay/tests/` for authentication, pairing lifecycle, replay protection, and trust creation.

### Windows
- Create `phonedesk-pc/relay_api.py` — canonical signed request builder and pairing API client.
- Create `phonedesk-pc/trusted_phone.py` — public trusted-phone metadata persistence.
- Modify `phonedesk-pc/requirements-remote.txt` — add `httpx==0.28.1`.
- Create `phonedesk-pc/test_relay_api.py` and `phonedesk-pc/test_trusted_phone.py`.
- Modify `phonedesk-pc/phonedesk_pc.py` only after the API client is tested, adding a separate first-time Remote Pairing panel without removing the existing local diagnostic flow.

### Android
- Create `phonedesk-android/app/src/main/java/com/phonedesk/remote/RelayAuth.kt` — stable device ID and signed-request canonicalization.
- Create `phonedesk-android/app/src/main/java/com/phonedesk/remote/RelayApi.kt` — pairing HTTP API abstraction.
- Create `phonedesk-android/app/src/main/java/com/phonedesk/remote/TrustedComputerStore.kt` — public trusted-computer metadata persistence.
- Create `phonedesk-android/app/src/main/java/com/phonedesk/remote/PairingManager.kt` — pairing create/status/confirm orchestration.
- Add unit tests under `phonedesk-android/app/src/test/java/com/phonedesk/remote/`.
- Modify `phonedesk-android/app/src/main/java/com/phonedesk/MainActivity.kt` last, adding a simple **Pair new computer** flow and keeping Wireless Debugging under an Advanced/Local section.

---

### Task 1: Define stable device IDs and signed HTTP requests on the relay

**Files:**
- Create: `phonedesk-relay/phonedesk_relay/device_auth.py`
- Test: `phonedesk-relay/tests/test_device_auth.py`
- Modify: `phonedesk-relay/requirements.txt`

**Interfaces:**
- Produces: `device_id_from_public_key(public_key_der: bytes) -> str`
- Produces: `canonical_request(method: str, path: str, timestamp: str, nonce: str, body: bytes) -> bytes`
- Produces: `verify_signature(public_key_der: bytes, message: bytes, signature_b64: str) -> bool`
- Produces: `ReplayGuard.accept(device_id: str, timestamp: int, nonce: str, now: int) -> bool`

- [ ] **Step 1: Write the failing tests**

```python
# phonedesk-relay/tests/test_device_auth.py
import base64
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from phonedesk_relay.device_auth import (
    ReplayGuard,
    canonical_request,
    device_id_from_public_key,
    verify_signature,
)


def test_device_id_is_stable_and_public_key_bound():
    key = ec.generate_private_key(ec.SECP256R1())
    der = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    assert device_id_from_public_key(der) == device_id_from_public_key(der)
    assert device_id_from_public_key(der).startswith("pd_")


def test_signed_request_verifies():
    key = ec.generate_private_key(ec.SECP256R1())
    der = key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    message = canonical_request("POST", "/v1/pairings", "1700000000", "abc123", b"{}")
    signature = key.sign(message, ec.ECDSA(hashes.SHA256()))
    assert verify_signature(der, message, base64.b64encode(signature).decode("ascii"))


def test_replay_guard_rejects_reused_nonce_and_old_timestamp():
    guard = ReplayGuard(max_age_seconds=120)
    assert guard.accept("pd_test", 1000, "n1", now=1050)
    assert not guard.accept("pd_test", 1000, "n1", now=1051)
    assert not guard.accept("pd_test", 800, "n2", now=1051)
```

- [ ] **Step 2: Run RED verification**

Run:
```bash
cd phonedesk-relay
python -m pytest tests/test_device_auth.py -q
```
Expected: import failure because `device_auth.py` does not yet exist.

- [ ] **Step 3: Implement the minimal authenticated-request primitives**

```python
# phonedesk-relay/phonedesk_relay/device_auth.py
from __future__ import annotations

import base64
import hashlib
import time
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def device_id_from_public_key(public_key_der: bytes) -> str:
    digest = hashlib.sha256(public_key_der).digest()[:16]
    token = base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")
    return f"pd_{token}"


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
        self._seen = {k: seen_at for k, seen_at in self._seen.items() if seen_at >= cutoff}
        return True
```

Add to `phonedesk-relay/requirements.txt`:
```text
cryptography==44.0.0
```

- [ ] **Step 4: Run GREEN verification**

Run:
```bash
cd phonedesk-relay
python -m pytest tests/test_device_auth.py -q
```
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-relay/phonedesk_relay/device_auth.py phonedesk-relay/tests/test_device_auth.py phonedesk-relay/requirements.txt
git commit -m "feat: add signed relay request authentication"
```

### Task 2: Register device public identities and store trust relationships

**Files:**
- Create: `phonedesk-relay/phonedesk_relay/devices.py`
- Test: `phonedesk-relay/tests/test_devices.py`

**Interfaces:**
- Produces: `RegisteredDevice(device_id, display_name, platform, public_key_der)`
- Produces: `DeviceRegistry.register(...) -> RegisteredDevice`
- Produces: `DeviceRegistry.get(device_id) -> RegisteredDevice | None`
- Produces: `DeviceRegistry.trust(phone_id, computer_id) -> None`
- Produces: `DeviceRegistry.is_trusted(phone_id, computer_id) -> bool`
- Produces: `DeviceRegistry.list_trusted(device_id) -> list[RegisteredDevice]`

- [ ] **Step 1: Write failing tests for deterministic registration and bilateral lookup**

```python
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from phonedesk_relay.devices import DeviceRegistry


def public_der():
    key = ec.generate_private_key(ec.SECP256R1())
    return key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )


def test_register_derives_device_id_from_public_key():
    registry = DeviceRegistry()
    device = registry.register("Phone", "android", public_der())
    assert device.device_id.startswith("pd_")
    assert registry.get(device.device_id) == device


def test_trust_is_queryable_from_both_devices():
    registry = DeviceRegistry()
    phone = registry.register("My Phone", "android", public_der())
    pc = registry.register("Office Laptop", "windows", public_der())
    registry.trust(phone.device_id, pc.device_id)
    assert registry.is_trusted(phone.device_id, pc.device_id)
    assert registry.is_trusted(pc.device_id, phone.device_id)
    assert [d.device_id for d in registry.list_trusted(phone.device_id)] == [pc.device_id]
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-relay
python -m pytest tests/test_devices.py -q
```
Expected: module missing.

- [ ] **Step 3: Implement `RegisteredDevice` and `DeviceRegistry` using in-memory storage for this stage**

```python
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
        device = RegisteredDevice(
            device_id=device_id_from_public_key(public_key_der),
            display_name=display_name.strip(),
            platform=platform.strip().lower(),
            public_key_der=bytes(public_key_der),
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
        peers = []
        for relation in self._trust:
            if device_id in relation:
                peer_id = next(value for value in relation if value != device_id)
                peer = self.get(peer_id)
                if peer is not None:
                    peers.append(peer)
        return sorted(peers, key=lambda item: item.device_id)
```

- [ ] **Step 4: Run GREEN**

```bash
cd phonedesk-relay
python -m pytest tests/test_devices.py -q
```
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-relay/phonedesk_relay/devices.py phonedesk-relay/tests/test_devices.py
git commit -m "feat: add relay device trust registry"
```

### Task 3: Implement expiring single-use pairing sessions

**Files:**
- Create: `phonedesk-relay/phonedesk_relay/pairing.py`
- Test: `phonedesk-relay/tests/test_pairing.py`

**Interfaces:**
- Produces: `PairingState` values `CREATED`, `CLAIMED`, `CONFIRMED`, `DENIED`, `EXPIRED`
- Produces: `PairingManager.create(phone_id, now) -> PairingSession`
- Produces: `PairingManager.claim(pairing_id, computer_id, manual_code, now) -> PairingSession`
- Produces: `PairingManager.confirm(pairing_id, phone_id, approved, now) -> PairingSession`
- Manual code: exactly 6 numeric digits, 5-minute expiry, maximum 5 failed attempts.

- [ ] **Step 1: Write failing lifecycle tests**

```python
from phonedesk_relay.pairing import PairingManager, PairingState


def test_pairing_requires_claim_then_phone_confirmation():
    manager = PairingManager(hmac_key=b"test-key")
    created = manager.create("phone", now=1000)
    assert len(created.manual_code) == 6
    claimed = manager.claim(created.pairing_id, "pc", created.manual_code, now=1010)
    assert claimed.state is PairingState.CLAIMED
    confirmed = manager.confirm(created.pairing_id, "phone", approved=True, now=1020)
    assert confirmed.state is PairingState.CONFIRMED


def test_pairing_code_expires_after_five_minutes():
    manager = PairingManager(hmac_key=b"test-key")
    created = manager.create("phone", now=1000)
    expired = manager.get(created.pairing_id, now=1301)
    assert expired.state is PairingState.EXPIRED


def test_wrong_code_is_rate_limited():
    manager = PairingManager(hmac_key=b"test-key", max_attempts=5)
    created = manager.create("phone", now=1000)
    for attempt in range(5):
        try:
            manager.claim(created.pairing_id, "pc", "000000", now=1001 + attempt)
        except ValueError:
            pass
    try:
        manager.claim(created.pairing_id, "pc", created.manual_code, now=1010)
        assert False, "expected pairing session to be locked"
    except ValueError as exc:
        assert "attempt" in str(exc).lower()
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-relay
python -m pytest tests/test_pairing.py -q
```
Expected: module missing.

- [ ] **Step 3: Implement the state machine**

Use `secrets.randbelow(1_000_000)` formatted as `f"{value:06d}"`. Store only an HMAC-SHA256 digest internally:

```python
def _digest(self, code: str) -> bytes:
    return hmac.new(self.hmac_key, code.encode("ascii"), hashlib.sha256).digest()
```

The public `PairingSession` returned by `create()` may include `manual_code`; the internally stored record must not retain plaintext `manual_code`. `get()` must never return the code after creation.

- [ ] **Step 4: Run GREEN**

```bash
cd phonedesk-relay
python -m pytest tests/test_pairing.py -q
```
Expected: all pairing tests pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-relay/phonedesk_relay/pairing.py phonedesk-relay/tests/test_pairing.py
git commit -m "feat: add expiring pairing sessions"
```

### Task 4: Expose authenticated registration and pairing endpoints

**Files:**
- Modify: `phonedesk-relay/phonedesk_relay/app.py`
- Test: `phonedesk-relay/tests/test_pairing_api.py`

**Interfaces:**
- `POST /v1/devices/register`
- `POST /v1/pairings`
- `POST /v1/pairings/{pairing_id}/claim`
- `GET /v1/pairings/{pairing_id}`
- `POST /v1/pairings/{pairing_id}/confirm`
- `GET /v1/trusted-devices`
- Signed endpoints require headers `X-PhoneDesk-Device`, `X-PhoneDesk-Timestamp`, `X-PhoneDesk-Nonce`, `X-PhoneDesk-Signature`.

- [ ] **Step 1: Write a failing end-to-end API test**

```python
def test_manual_pairing_creates_trust(client, phone_signer, pc_signer):
    phone = register(client, phone_signer, "My Phone", "android")
    pc = register(client, pc_signer, "Office Laptop", "windows")

    created = signed_post(client, phone_signer, phone["device_id"], "/v1/pairings", {})
    pairing_id = created.json()["pairing_id"]
    code = created.json()["manual_code"]

    claim = signed_post(
        client,
        pc_signer,
        pc["device_id"],
        f"/v1/pairings/{pairing_id}/claim",
        {"manual_code": code},
    )
    assert claim.json()["state"] == "CLAIMED"

    confirm = signed_post(
        client,
        phone_signer,
        phone["device_id"],
        f"/v1/pairings/{pairing_id}/confirm",
        {"approved": True},
    )
    assert confirm.json()["state"] == "CONFIRMED"

    trusted = signed_get(client, phone_signer, phone["device_id"], "/v1/trusted-devices")
    assert trusted.json()["devices"][0]["display_name"] == "Office Laptop"
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-relay
python -m pytest tests/test_pairing_api.py -q
```
Expected: endpoint 404 failures.

- [ ] **Step 3: Add request authentication dependency and endpoints to `create_app()`**

Registration accepts a base64 DER public key and returns the relay-derived device ID. Every other endpoint verifies the canonical signed request using the registered public key and `ReplayGuard`. When a phone confirms an already claimed pairing, call `DeviceRegistry.trust(phone_id, computer_id)` before returning `CONFIRMED`.

Use explicit response fields:

```json
{
  "pairing_id": "...",
  "state": "CREATED",
  "manual_code": "123456",
  "expires_in_seconds": 300
}
```

The status endpoint for the phone returns the claimed computer summary only after claim:

```json
{
  "state": "CLAIMED",
  "computer": {
    "device_id": "pd_...",
    "display_name": "Office Laptop",
    "fingerprint": "AA:BB:CC:DD"
  }
}
```

- [ ] **Step 4: Run the full relay suite**

```bash
cd phonedesk-relay
python -m pytest -q
```
Expected: existing presence/websocket tests plus new auth/pairing tests all pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-relay/phonedesk_relay/app.py phonedesk-relay/tests/test_pairing_api.py
git commit -m "feat: expose trusted pairing control API"
```

### Task 5: Add the signed relay API client on Windows

**Files:**
- Create: `phonedesk-pc/relay_api.py`
- Modify: `phonedesk-pc/requirements-remote.txt`
- Test: `phonedesk-pc/test_relay_api.py`

**Interfaces:**
- Produces: `RelayApi(base_url: str, identity_store: DeviceIdentityStore, display_name: str)`
- Produces: `register(platform="windows") -> DeviceRegistration`
- Produces: `claim_pairing(pairing_id: str, manual_code: str) -> str`
- Produces: `list_trusted() -> list[TrustedDeviceSummary]`

- [ ] **Step 1: Write failing tests against `httpx.MockTransport`**

```python
class RelayApiTests(unittest.TestCase):
    def test_signed_headers_are_present(self):
        seen = {}
        def handler(request):
            seen.update(request.headers)
            return httpx.Response(200, json={"state": "CLAIMED"})
        api = make_test_api(httpx.MockTransport(handler))
        api.claim_pairing("pair-1", "123456")
        self.assertIn("x-phonedesk-device", seen)
        self.assertIn("x-phonedesk-signature", seen)
        self.assertIn("x-phonedesk-nonce", seen)
        self.assertIn("x-phonedesk-timestamp", seen)
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-pc
python -m unittest -v test_relay_api.py
```
Expected: module missing.

- [ ] **Step 3: Implement the client using `httpx.Client`**

Add to `requirements-remote.txt`:
```text
httpx==0.28.1
```

Canonical signing must exactly match relay Task 1. Use `identity_store.sign(message)` and base64-encode the signature. Do not log request bodies containing pairing codes.

- [ ] **Step 4: Run GREEN and full Windows tests**

```bash
cd phonedesk-pc
python -m unittest discover -v -p 'test_*.py'
```
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-pc/relay_api.py phonedesk-pc/test_relay_api.py phonedesk-pc/requirements-remote.txt
git commit -m "feat: add signed Windows relay pairing client"
```

### Task 6: Persist trusted phone metadata on Windows

**Files:**
- Create: `phonedesk-pc/trusted_phone.py`
- Test: `phonedesk-pc/test_trusted_phone.py`

**Interfaces:**
- Produces: `TrustedPhone(device_id, display_name, fingerprint, public_key_der_b64)`
- Produces: `TrustedPhoneStore.load() -> TrustedPhone | None`
- Produces: `TrustedPhoneStore.save(phone: TrustedPhone) -> None`
- Produces: `TrustedPhoneStore.clear() -> None`

- [ ] **Step 1: Write failing persistence test**

```python
def test_trusted_phone_store_roundtrip(tmp_path):
    path = tmp_path / "trusted-phone.json"
    store = TrustedPhoneStore(path)
    phone = TrustedPhone("pd_phone", "My Phone", "AA:BB:CC:DD", "BASE64")
    store.save(phone)
    assert TrustedPhoneStore(path).load() == phone
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-pc
python -m unittest -v test_trusted_phone.py
```
Expected: module missing.

- [ ] **Step 3: Implement atomic JSON persistence**

Persist only public metadata. Write to `.tmp` and replace the final file atomically. No pairing code or QR secret field may exist in the schema.

- [ ] **Step 4: Run GREEN**

```bash
cd phonedesk-pc
python -m unittest discover -v -p 'test_*.py'
```
Expected: all Windows tests pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-pc/trusted_phone.py phonedesk-pc/test_trusted_phone.py
git commit -m "feat: persist trusted phone public metadata"
```

### Task 7: Add Android relay-auth and trusted-computer storage primitives

**Files:**
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/remote/RelayAuth.kt`
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/remote/TrustedComputerStore.kt`
- Test: `phonedesk-android/app/src/test/java/com/phonedesk/remote/RelayAuthTest.kt`
- Test: `phonedesk-android/app/src/test/java/com/phonedesk/remote/TrustedComputerStoreTest.kt`

**Interfaces:**
- Produces: `RelayAuth.deviceId(publicKeyDer: ByteArray): String`
- Produces: `RelayAuth.canonicalRequest(method, path, timestamp, nonce, body): ByteArray`
- Produces: `TrustedComputer(deviceId, displayName, fingerprint, publicKeyDerBase64)`
- Produces: `TrustedComputerStore.save/load/clear`

- [ ] **Step 1: Write failing JVM unit tests**

```kotlin
@Test
fun canonicalRequestMatchesRelayFormat() {
    val canonical = RelayAuth.canonicalRequest(
        method = "POST",
        path = "/v1/pairings",
        timestamp = "1700000000",
        nonce = "abc123",
        body = "{}".toByteArray(),
    )
    assertTrue(String(canonical).startsWith("POST\n/v1/pairings\n1700000000\nabc123\n"))
}
```

For `TrustedComputerStoreTest`, use an in-memory `SharedPreferences` test double rather than Android instrumentation.

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-android
gradle testDebugUnitTest --tests 'com.phonedesk.remote.*' --stacktrace
```
Expected: missing classes.

- [ ] **Step 3: Implement pure Kotlin primitives**

The device ID algorithm must match relay Task 1 exactly: SHA-256(public DER), first 16 bytes, URL-safe base64 without padding, `pd_` prefix.

- [ ] **Step 4: Run GREEN and all Android JVM tests**

```bash
cd phonedesk-android
gradle testDebugUnitTest --stacktrace
```
Expected: all unit tests pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-android/app/src/main/java/com/phonedesk/remote phonedesk-android/app/src/test/java/com/phonedesk/remote
git commit -m "feat: add Android relay trust primitives"
```

### Task 8: Implement Android pairing orchestration without background service

**Files:**
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/remote/RelayApi.kt`
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/remote/PairingManager.kt`
- Test: `phonedesk-android/app/src/test/java/com/phonedesk/remote/PairingManagerTest.kt`

**Interfaces:**
- `RelayApi.registerDevice(...)`
- `RelayApi.createPairing(...)`
- `RelayApi.getPairingStatus(...)`
- `RelayApi.confirmPairing(...)`
- `PairingManager.beginPairing() -> PairingOffer`
- `PairingManager.refresh() -> PairingCandidate?`
- `PairingManager.confirm(approved: Boolean) -> Result<Unit>`

- [ ] **Step 1: Write failing orchestration test with fake `RelayApi`**

```kotlin
@Test
fun confirmStoresComputerOnlyAfterRelayConfirmation() = runTest {
    val relay = FakeRelayApi(candidate = PairingCandidate("pd_pc", "Office Laptop", "AA:BB:CC:DD", "BASE64"))
    val store = FakeTrustedComputerStore()
    val manager = PairingManager(relay, store, fakeIdentityStore)

    manager.beginPairing()
    manager.refresh()
    assertNull(store.load())

    manager.confirm(true).getOrThrow()
    assertEquals("Office Laptop", store.load()?.displayName)
}
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-android
gradle testDebugUnitTest --tests 'com.phonedesk.remote.PairingManagerTest' --stacktrace
```
Expected: classes missing.

- [ ] **Step 3: Implement `PairingManager` as foreground Activity-owned work only**

Do not add a foreground service, boot receiver, persistent notification, screen capture, Accessibility control, or FCM in this task. The phone app is open during first-time pairing.

- [ ] **Step 4: Run GREEN**

```bash
cd phonedesk-android
gradle testDebugUnitTest --stacktrace
```
Expected: all Android unit tests pass.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-android/app/src/main/java/com/phonedesk/remote/RelayApi.kt phonedesk-android/app/src/main/java/com/phonedesk/remote/PairingManager.kt phonedesk-android/app/src/test/java/com/phonedesk/remote/PairingManagerTest.kt
git commit -m "feat: add Android trusted pairing manager"
```

### Task 9: Add user-friendly first-time pairing UI on Android and Windows

**Files:**
- Modify: `phonedesk-android/app/src/main/java/com/phonedesk/MainActivity.kt`
- Modify: `phonedesk-pc/phonedesk_pc.py`
- Modify tests: `phonedesk-pc/test_phonedesk_pc.py`

**Interfaces/UI:**
- Android main screen: **Ready**, **My trusted computer**, **PAIR NEW COMPUTER**, **Trusted devices**, **Settings**.
- Android pairing screen: displays the temporary 6-digit code, expiry text, and claimed computer details with **TRUST THIS COMPUTER** / **DENY**.
- Windows first-time panel: **PAIR WITH PHONE**, input for 6-digit code, status text. After trust exists, hide this panel in the normal flow.
- Existing Wireless Debugging controls move under **Advanced / Local diagnostics** and are not presented as the normal Remote Mode path.

- [ ] **Step 1: Add failing Windows UI-state tests for pairing-panel visibility**

Extract pure helpers in `phonedesk_pc.py`:

```python
def remote_pairing_panel_visible(has_trusted_phone: bool) -> bool:
    return not has_trusted_phone


def remote_primary_status(has_trusted_phone: bool) -> str:
    return "Ready" if has_trusted_phone else "Pair your phone"
```

Tests:

```python
self.assertTrue(phonedesk_pc.remote_pairing_panel_visible(False))
self.assertFalse(phonedesk_pc.remote_pairing_panel_visible(True))
self.assertEqual(phonedesk_pc.remote_primary_status(True), "Ready")
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-pc
python -m unittest -v test_phonedesk_pc.py
```
Expected: missing helper failures.

- [ ] **Step 3: Implement the Windows helpers and pairing panel, then wire Android Activity to `PairingManager`**

Do not expose IP address, pairing port, ADB, or scrcpy fields in the normal Remote Mode section. Keep the old local controls behind an explicit Advanced/Local diagnostics section.

- [ ] **Step 4: Verify both client suites**

```bash
cd phonedesk-pc
python -m unittest discover -v -p 'test_*.py'
```

```bash
cd phonedesk-android
gradle testDebugUnitTest assembleDebug --stacktrace
```

Expected: Windows tests pass and Android debug APK builds successfully.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-pc/phonedesk_pc.py phonedesk-pc/test_phonedesk_pc.py phonedesk-android/app/src/main/java/com/phonedesk/MainActivity.kt
git commit -m "feat: add user friendly Remote Mode pairing UI"
```

### Task 10: Cross-component verification and CI gate

**Files:**
- Modify only if required by test command coverage: `.github/workflows/phonedesk-remote-relay.yml`, `.github/workflows/phonedesk-remote-windows.yml`, `.github/workflows/phonedesk-remote-android.yml`
- Create: `phonedesk-relay/tests/test_pairing_security.py`

**Interfaces:**
- No new production interfaces.
- This task verifies the stage against the approved constraints.

- [ ] **Step 1: Add security regression tests**

Required cases:

```python
def test_pairing_code_not_returned_by_status_after_create(...): ...
def test_expired_pairing_cannot_be_claimed(...): ...
def test_replayed_signed_request_is_rejected(...): ...
def test_unregistered_device_cannot_create_pairing(...): ...
def test_computer_cannot_confirm_its_own_pairing(...): ...
def test_trust_is_created_only_after_phone_approval(...): ...
```

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
Expected: tests pass and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Commit verification coverage**

```bash
git add phonedesk-relay/tests/test_pairing_security.py .github/workflows/phonedesk-remote-relay.yml .github/workflows/phonedesk-remote-windows.yml .github/workflows/phonedesk-remote-android.yml
git commit -m "test: gate trusted pairing foundation"
```

## Stage Acceptance Criteria

This plan is complete only when all of these are demonstrated:

1. Android and Windows can each register a public P-256 identity with the relay.
2. Signed control requests are rejected when stale, invalid, or replayed.
3. Android can create a 5-minute pairing session and display a 6-digit single-use fallback code.
4. Windows can claim the session with that code while proving possession of its own identity key.
5. Android sees the claimed computer name/fingerprint and must explicitly approve it.
6. The relay creates trust only after phone approval.
7. Both clients persist only public peer metadata; the 6-digit pairing code is not persisted.
8. Normal Remote Mode UI no longer requires IP/port/ADB fields for pairing.
9. No foreground service, persistent idle notification, screen capture, Accessibility control, or background socket is introduced by this stage.
10. Relay, Windows, and Android CI all pass.

## Follow-up Plans Required by the Approved Spec

After this foundation passes, create separate plans in this order:

1. **Push Access Request Control Plane** — FCM registration, `PhoneDesk access request`, ACCEPT/DENY, Windows waiting state, no active stream yet.
2. **Active Native Session** — active-only foreground service, WSS session, 2-minute reconnect state machine, lock pause/unlock auto-resume.
3. **Native Screen + Input** — MediaProjection/MediaCodec low-latency video, Accessibility input, Auto/Fast/Sharp profiles, Windows native viewer.
4. **Hardening + Packaging** — revocation UI, PostgreSQL persistence, deployment, end-to-end mobile-data testing, battery/latency measurement, APK/EXE packaging.

QR camera scanning is intentionally assigned to the first follow-up UX pass after manual pairing is proven end-to-end; the relay pairing model already supports adding a high-entropy QR secret without changing the trust state machine.
