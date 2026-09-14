# PhoneDesk Remote Mode Milestone 2 Identities Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the Android phone and Windows laptop stable long-term cryptographic identities whose private keys remain protected locally and whose public-key fingerprints can be safely displayed during future pairing.

**Architecture:** Long-term identity uses ECDSA P-256 with SHA-256. Android stores a non-exportable key in `AndroidKeyStore`; Windows stores PKCS#8 private-key bytes protected with Windows DPAPI. Both platforms expose the public key as X.509 SubjectPublicKeyInfo DER and display the first four bytes of SHA-256(public-key-DER) as uppercase colon-separated hex. Session encryption remains a later milestone.

**Tech Stack:** Android/Kotlin/JVM 17/Android Keystore/JUnit 4; Windows Python 3.12/cryptography/ctypes DPAPI/unittest; GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-14-phonedesk-remote-mode-design.md`

## Global Constraints

- Android private identity keys must be non-exportable from Android Keystore.
- Windows private identity key bytes must never be written to disk in plaintext.
- Public keys use X.509 SubjectPublicKeyInfo DER.
- Identity signatures use ECDSA P-256 with SHA-256.
- Short fingerprint = first 4 bytes of SHA-256(public-key-DER), uppercase hex separated by `:`.
- `SHA-256(b"PhoneDesk")` test vector begins `BD:85:52:AE`.
- Pairing codes, QR secrets, lock credentials, ADB pairing codes, and session keys are not part of this milestone.
- Existing Android ADB and Windows local-control behavior must remain intact.

---

### Task 1: Android fingerprint helper

**Files:**
- Create: `phonedesk-android/app/src/main/java/com/phonedesk/identity/IdentityFingerprint.kt`
- Create: `phonedesk-android/app/src/test/java/com/phonedesk/identity/IdentityFingerprintTest.kt`

**Interfaces:**
- `IdentityFingerprint.shortSha256(publicKeyDer: ByteArray): String`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.phonedesk.identity

import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityFingerprintTest {
    @Test
    fun `fingerprint uses first four sha256 bytes`() {
        assertEquals("BD:85:52:AE", IdentityFingerprint.shortSha256("PhoneDesk".toByteArray()))
    }

    @Test
    fun `same key has stable fingerprint`() {
        val key = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(IdentityFingerprint.shortSha256(key), IdentityFingerprint.shortSha256(key))
    }
}
```

- [ ] **Step 2: Run RED**

```bash
cd phonedesk-android
gradle testDebugUnitTest --tests com.phonedesk.identity.IdentityFingerprintTest --stacktrace
```

Expected: FAIL because `IdentityFingerprint` does not exist.

- [ ] **Step 3: Implement**

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

- [ ] **Step 4: Verify focused + full Android unit tests**

```bash
gradle testDebugUnitTest --tests com.phonedesk.identity.IdentityFingerprintTest --stacktrace
gradle testDebugUnitTest --stacktrace
```

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

```kotlin
data class DeviceIdentity(val publicKeyDer: ByteArray, val fingerprint: String)
interface DeviceIdentityStore {
    fun getOrCreate(): DeviceIdentity
    fun sign(message: ByteArray): ByteArray
}
```

`AndroidDeviceIdentityStore(alias: String = "phonedesk_remote_identity_v1")` uses `AndroidKeyStore`, `secp256r1`, `KeyProperties.DIGEST_SHA256`, and `SHA256withECDSA`. It returns `certificate.publicKey.encoded` and must never read/export `PrivateKey.encoded`.

- [ ] **Step 1: Implement the interface and Android Keystore-backed store.**
- [ ] **Step 2: Add Android Remote CI on `build/phonedesk-remote-v1` using Java 21, Android platform 36, Gradle 8.11.1.**
- [ ] **Step 3: Run `gradle testDebugUnitTest assembleDebug --stacktrace`.**
- [ ] **Step 4: Inspect source to confirm no private key persistence/export and no lock credential use.**
- [ ] **Step 5: Commit `feat: add Android PhoneDesk device identity`.**

---

### Task 3: Import Windows v0.2 local-control baseline

**Files:**
- Create exact copies from branch `build/phonedesk-pc-v01`:
  - `phonedesk-pc/phonedesk_pc.py`
  - `phonedesk-pc/test_phonedesk_pc.py`
  - `phonedesk-pc/README.txt`

- [ ] **Step 1: Copy the three exact files without behavior changes.**
- [ ] **Step 2: On Windows/Python 3.12 run:**

```powershell
Set-Location phonedesk-pc
python -m unittest discover -v -p 'test_*.py'
```

Expected: existing v0.2 tests pass.

- [ ] **Step 3: Commit `chore: bring Windows PhoneDesk v0.2 into remote branch`.**

---

### Task 4: Windows DPAPI identity — test first

**Files:**
- Create: `phonedesk-pc/identity.py`
- Create: `phonedesk-pc/test_identity.py`
- Create: `phonedesk-pc/requirements-remote.txt`

**Interfaces:**
- `fingerprint_public_key(public_key_der: bytes) -> str`
- `WindowsDpapiProtector.protect(data: bytes) -> bytes`
- `WindowsDpapiProtector.unprotect(data: bytes) -> bytes`
- `DeviceIdentityStore(path: Path, protector).load_or_create()`
- `DeviceIdentityStore.sign(message: bytes) -> bytes`

- [ ] **Step 1: Write failing tests** using the same `BD:85:52:AE` fingerprint vector, a reversible test protector, stable reload, no PEM/private-key marker in persisted data, and ECDSA signature verification.
- [ ] **Step 2: Install `cryptography==44.0.0` and run `python -m unittest -v test_identity.py` to verify RED.**
- [ ] **Step 3: Implement P-256 identity generation; serialize private key as PKCS#8 DER only in memory, call `protector.protect()`, persist only protected bytes as base64 plus version/algorithm metadata, and expose public key as SubjectPublicKeyInfo DER.**
- [ ] **Step 4: Implement Windows DPAPI with `CryptProtectData`/`CryptUnprotectData`, `CRYPTPROTECT_UI_FORBIDDEN`, and `LocalFree`; non-Windows calls must fail rather than fall back to plaintext.**
- [ ] **Step 5: Run all Windows tests.**
- [ ] **Step 6: Commit `feat: add Windows PhoneDesk device identity`.**

---

### Task 5: Identity CI and real DPAPI verification

**Files:**
- Create: `.github/workflows/phonedesk-remote-windows.yml`
- Modify: `phonedesk-pc/test_identity.py`

- [ ] **Step 1: Add a Windows-only DPAPI round-trip test:**

```python
@unittest.skipUnless(os.name == "nt", "Windows DPAPI test")
def test_windows_dpapi_roundtrip(self):
    protector = WindowsDpapiProtector()
    plaintext = os.urandom(48)
    protected = protector.protect(plaintext)
    self.assertNotEqual(plaintext, protected)
    self.assertEqual(plaintext, protector.unprotect(protected))
```

- [ ] **Step 2: Add Windows CI** using Python 3.12, install `phonedesk-pc/requirements-remote.txt`, then run `python -m unittest discover -v -p 'test_*.py'` inside `phonedesk-pc`.
- [ ] **Step 3: Verify the real DPAPI test executes and passes on `windows-latest`.**
- [ ] **Step 4: Verify Android Remote CI passes at the same branch head.**
- [ ] **Step 5: Security gate: neither platform persists plaintext private identity material or references Android lock/ADB pairing credentials.**

## Milestone 2 Completion Gate

Milestone 2 is complete only when Android compiles a non-exportable P-256 identity, Android fingerprint tests pass, Windows v0.2 is preserved, Windows identity survives reload with DPAPI-protected private material, real DPAPI is CI-tested, Windows signatures verify, both platforms use the same DER/fingerprint convention, and all existing tests remain green. Pairing/session encryption is not claimed complete in this milestone.
