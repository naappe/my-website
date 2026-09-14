# PhoneDesk Remote Mode Milestone 2 Identities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Android phone and Windows laptop stable long-term cryptographic identities whose private keys remain protected locally and whose public-key fingerprints can be safely displayed during future pairing.

**Architecture:** Long-term identity uses ECDSA P-256 with SHA-256 because Android Keystore supports non-exportable EC P-256 keys on the current minSdk 26 and Python `cryptography` supports the same public-key format/signature scheme. Android stores the private key in `AndroidKeyStore`; Windows stores a PKCS#8 private key encrypted at rest with Windows DPAPI. Both platforms encode public keys as X.509 SubjectPublicKeyInfo DER and display the first four bytes of SHA-256(public-key-DER) as an uppercase colon-separated short fingerprint. The short fingerprint is for human comparison only; later trust uses the full public key.

**Tech Stack:** Android/Kotlin/JVM 17/Android Keystore/JUnit 4; Windows Python 3.12/cryptography/ctypes DPAPI/unittest; GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-14-phonedesk-remote-mode-design.md`

## Global Constraints

- Android private identity keys must be non-exportable from Android Keystore.
- Windows private identity key bytes must never be written to disk in plaintext; they are protected with DPAPI before persistence.
- Public keys are encoded as X.509 SubjectPublicKeyInfo DER.
- Identity signature algorithm is ECDSA P-256 with SHA-256.
- Short human fingerprint is SHA-256(public-key-DER), first 4 bytes, uppercase hex joined by `:`.
- Pairing codes, QR secrets, Android lock credentials, ADB pairing codes, and session keys are not part of this milestone.
- No device identity private key is logged.
- Existing Android ADB pairing and Windows local-control behavior must remain intact.

---

### Task 1: Android deterministic public-key fingerprint helper

**Files:**
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/identity/IdentityFingerprint.kt`
- Create: `phonedesk-android/app/src/test/java/com/phonedesk/identity/IdentityFingerprintTest.kt`

**Interfaces:**
- Produces: `object IdentityFingerprint`
- Produces: `fun shortSha256(publicKeyDer: ByteArray): String`

- [ ] **Step 1: Write the failing JVM unit tests**

```kotlin
package com.phonedesk.identity

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityFingerprintTest {
    @Test
    fun `fingerprint is first four sha256 bytes in uppercase colon format`() {
        assertEquals("03:90:58:C6", IdentityFingerprint.shortSha256("PhoneDesk".toByteArray()))
    }

    @Test
    fun `same public key has stable fingerprint`() {
        val key = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(IdentityFingerprint.shortSha256(key), IdentityFingerprint.shortSha256(key))
    }
}
```

- [ ] **Step 2: Run RED**

Run in `phonedesk-android/`:

```bash
gradle testDebugUnitTest --tests com.phonedesk.identity.IdentityFingerprintTest --stacktrace
```

Expected: FAIL because `IdentityFingerprint` does not exist.

- [ ] **Step 3: Implement helper**

```kotlin
package com.phonedesk.identity

import java.security.MessageDigest

object IdentityFingerprint {
    fun shortSha256(publicKeyDer: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(publicKeyDer)
            .take(4)
            .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
}
```

- [ ] **Step 4: Run focused test and full Android unit suite**

```bash
gradle testDebugUnitTest --tests com.phonedesk.identity.IdentityFingerprintTest --stacktrace
gradle testDebugUnitTest --stacktrace
```

Expected: focused test passes and existing tests remain green.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-android/app/src/main/java/com/phonedesk/identity/IdentityFingerprint.kt phonedesk-android/app/src/test/java/com/phonedesk/identity/IdentityFingerprintTest.kt
git commit -m "feat: add Android identity fingerprint"
```

---

### Task 2: Android Keystore identity store

**Files:**
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/identity/DeviceIdentityStore.kt`
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/identity/AndroidDeviceIdentityStore.kt`
- Create: `.github/workflows/phonedesk-remote-android.yml`

**Interfaces:**
- Produces: `data class DeviceIdentity(val publicKeyDer: ByteArray, val fingerprint: String)`
- Produces: `interface DeviceIdentityStore { fun getOrCreate(): DeviceIdentity; fun sign(message: ByteArray): ByteArray }`
- Produces: `AndroidDeviceIdentityStore(alias: String = "phonedesk_remote_identity_v1")`

- [ ] **Step 1: Add interface and Android implementation**

The implementation must:

```kotlin
val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
```

If alias is absent, generate with:

```kotlin
KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
```

and:

```kotlin
KeyGenParameterSpec.Builder(
    alias,
    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
)
    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
    .setDigests(KeyProperties.DIGEST_SHA256)
    .build()
```

`getOrCreate()` returns `certificate.publicKey.encoded` and the helper fingerprint. `sign()` uses `SHA256withECDSA` with the non-exportable private key retrieved from Android Keystore.

- [ ] **Step 2: Add Remote Mode Android CI**

Workflow requirements:

```yaml
name: Test PhoneDesk Remote Android
on:
  push:
    branches: [ build/phonedesk-remote-v1 ]
    paths:
      - 'phonedesk-android/**'
      - '.github/workflows/phonedesk-remote-android.yml'
  workflow_dispatch:
```

Reuse Java 21, Android platform 36, Gradle 8.11.1, then run:

```bash
gradle testDebugUnitTest assembleDebug --stacktrace
```

- [ ] **Step 3: Run Android CI**

Expected: full unit suite and debug APK compilation pass. The Android Keystore implementation is compile-verified; real secure-keystore behavior is device-tested in a later Android integration milestone.

- [ ] **Step 4: Security review**

Confirm no call serializes or exports `PrivateKey.encoded`, no private key is written to SharedPreferences/files, and no lock credential is used.

- [ ] **Step 5: Commit**

```bash
git add phonedesk-android/app/src/main/java/com/phonedesk/identity .github/workflows/phonedesk-remote-android.yml
git commit -m "feat: add Android PhoneDesk device identity"
```

---

### Task 3: Bring Windows v0.2 local-control baseline into Remote branch

**Files:**
- Create from `build/phonedesk-pc-v01`: `phonedesk-pc/phonedesk_pc.py`
- Create from `build/phonedesk-pc-v01`: `phonedesk-pc/test_phonedesk_pc.py`
- Create from `build/phonedesk-pc-v01`: `phonedesk-pc/README.txt`

**Interfaces:**
- Preserves same-network `FIND MY PHONE -> PAIR & CONNECT -> CONTROL PHONE` behavior.
- Later Remote Mode UI work may import identity/session modules without rewriting existing local ADB/scrcpy logic.

- [ ] **Step 1: Copy the three exact files from `build/phonedesk-pc-v01`**

Do not modify their behavior during the import.

- [ ] **Step 2: Run baseline Windows tests**

On Windows/Python 3.12:

```powershell
Set-Location phonedesk-pc
python -m unittest discover -v -p 'test_*.py'
```

Expected: existing PhoneDesk v0.2 tests pass unchanged.

- [ ] **Step 3: Commit**

```bash
git add phonedesk-pc
git commit -m "chore: bring Windows PhoneDesk v0.2 into remote branch"
```

---

### Task 4: Windows DPAPI protector and identity store — test first

**Files:**
- Create: `phonedesk-pc/identity.py`
- Create: `phonedesk-pc/test_identity.py`
- Create: `phonedesk-pc/requirements-remote.txt`

**Interfaces:**
- Produces: `fingerprint_public_key(public_key_der: bytes) -> str`
- Produces: `class WindowsDpapiProtector` with `protect(data: bytes) -> bytes`, `unprotect(data: bytes) -> bytes`
- Produces: `class DeviceIdentityStore(path: pathlib.Path, protector)`
- `DeviceIdentityStore.load_or_create()` returns a stable object with `public_key_der: bytes` and `fingerprint: str`
- `DeviceIdentityStore.sign(message: bytes) -> bytes`

- [ ] **Step 1: Write failing tests**

```python
import tempfile
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from identity import DeviceIdentityStore, fingerprint_public_key


class ReversibleTestProtector:
    def protect(self, data: bytes) -> bytes:
        return bytes(value ^ 0xA5 for value in data)

    def unprotect(self, data: bytes) -> bytes:
        return bytes(value ^ 0xA5 for value in data)


class IdentityTests(unittest.TestCase):
    def test_fingerprint_format_is_stable(self):
        self.assertEqual("03:90:58:C6", fingerprint_public_key(b"PhoneDesk"))

    def test_identity_survives_reload_without_plaintext_private_key(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "identity.json"
            first = DeviceIdentityStore(path, ReversibleTestProtector()).load_or_create()
            raw_file = path.read_bytes()
            second_store = DeviceIdentityStore(path, ReversibleTestProtector())
            second = second_store.load_or_create()
            self.assertEqual(first.public_key_der, second.public_key_der)
            self.assertNotIn(b"PRIVATE KEY", raw_file)

    def test_signature_verifies_with_public_key(self):
        with tempfile.TemporaryDirectory() as directory:
            store = DeviceIdentityStore(Path(directory) / "identity.json", ReversibleTestProtector())
            identity = store.load_or_create()
            message = b"PhoneDesk identity proof"
            signature = store.sign(message)
            public_key = serialization.load_der_public_key(identity.public_key_der)
            public_key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
```

- [ ] **Step 2: Run RED on Windows**

```powershell
python -m pip install cryptography==44.0.0
python -m unittest -v test_identity.py
```

Expected: FAIL because `identity.py` does not exist.

- [ ] **Step 3: Implement identity store**

Use `ec.generate_private_key(ec.SECP256R1())`, PKCS#8 DER for the private key before local protection, SubjectPublicKeyInfo DER for the public key, and ECDSA/SHA-256 signatures. Persist only base64 of `protector.protect(private_der)` plus algorithm/version metadata. Reconstruct private key with `serialization.load_der_private_key()` after `unprotect()`.

- [ ] **Step 4: Implement `WindowsDpapiProtector`**

Use `ctypes.windll.crypt32.CryptProtectData` and `CryptUnprotectData` with `CRYPTPROTECT_UI_FORBIDDEN`, and release returned buffers with `kernel32.LocalFree`. Raise `OSError(ctypes.get_last_error())` on failure. This protector must reject non-Windows execution instead of silently storing plaintext.

- [ ] **Step 5: Run all Windows tests**

```powershell
python -m unittest discover -v -p 'test_*.py'
```

Expected: identity tests and imported v0.2 tests all pass.

- [ ] **Step 6: Commit**

```bash
git add phonedesk-pc/identity.py phonedesk-pc/test_identity.py phonedesk-pc/requirements-remote.txt
git commit -m "feat: add Windows PhoneDesk device identity"
```

---

### Task 5: Verify real Windows DPAPI and Remote Mode identity CI

**Files:**
- Create: `.github/workflows/phonedesk-remote-windows.yml`
- Modify: `phonedesk-pc/test_identity.py`

**Interfaces:**
- Windows CI runs Python 3.12 and all `phonedesk-pc/test_*.py` tests.
- On Windows only, a dedicated test protects and unprotects random bytes through real DPAPI and asserts ciphertext differs from plaintext.

- [ ] **Step 1: Add real DPAPI test**

```python
@unittest.skipUnless(os.name == "nt", "Windows DPAPI test")
def test_windows_dpapi_roundtrip(self):
    protector = WindowsDpapiProtector()
    plaintext = os.urandom(48)
    protected = protector.protect(plaintext)
    self.assertNotEqual(plaintext, protected)
    self.assertEqual(plaintext, protector.unprotect(protected))
```

- [ ] **Step 2: Add workflow**

```yaml
name: Test PhoneDesk Remote Windows
on:
  push:
    branches: [ build/phonedesk-remote-v1 ]
    paths:
      - 'phonedesk-pc/**'
      - '.github/workflows/phonedesk-remote-windows.yml'
  workflow_dispatch:

permissions:
  contents: read

jobs:
  test:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - name: Install remote dependencies
        run: python -m pip install --disable-pip-version-check -r phonedesk-pc/requirements-remote.txt
      - name: Test
        shell: pwsh
        run: |
          Set-Location phonedesk-pc
          python -m unittest discover -v -p 'test_*.py'
```

- [ ] **Step 3: Run CI and verify DPAPI evidence**

Expected: all Windows tests pass and the real `WindowsDpapiProtector` round-trip test executes rather than skips.

- [ ] **Step 4: Run Android Remote CI at the same branch head**

Expected: Android unit tests + debug compile pass with `AndroidDeviceIdentityStore` present.

- [ ] **Step 5: Security gate**

Verify by source inspection that neither platform persists an unprotected private identity key and neither identity module knows anything about Android lock credentials or ADB pairing codes.

## Milestone 2 Completion Gate

Milestone 2 is complete only when:

1. Android can compile a non-exportable P-256 identity implementation using Android Keystore.
2. Android public-key fingerprint helper is unit tested.
3. Windows v0.2 local-control baseline exists unchanged on the Remote branch.
4. Windows identity persists stably across reloads with encrypted-at-rest private key material.
5. Real Windows DPAPI protect/unprotect is CI-tested.
6. Windows ECDSA signatures verify with the persisted public key.
7. Android and Windows use the same public-key DER/fingerprint convention.
8. Existing Android and Windows tests remain green.
9. No pairing/session crypto or lock bypass is falsely claimed as complete in this milestone.
