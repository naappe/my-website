# PhoneDesk Remote Mode v1 — Design Specification

## Purpose

PhoneDesk Remote Mode lets the owner of an Android phone leave the phone at home and securely connect to it from a Windows laptop at the office over the public internet, without exposing raw ADB or Wireless Debugging ports to the internet.

This design extends the existing PhoneDesk Android pairing/ADB work and the existing PhoneDesk Windows scrcpy client.

## User experience

### First-time device pairing

1. Android PhoneDesk generates a permanent device identity and keeps the private key in app-private/secure storage.
2. The user chooses **Pair new laptop**.
3. The phone creates a one-time pairing session with:
   - a QR payload containing a high-entropy temporary secret plus the phone public identity,
   - a 6-digit manual fallback code,
   - a short human-readable public-key fingerprint.
4. Windows PhoneDesk generates its own permanent device identity.
5. The laptop scans the QR or uses the 6-digit fallback code.
6. The phone confirms the new laptop and both sides save the other device's public identity.
7. Temporary pairing material expires after 5 minutes or immediately after successful use.
8. The pairing code, Android lock credentials, and temporary ADB pairing code are never stored by the relay.

### Normal remote use

The Windows client should normally show only:

- My Phone
- Online / Offline / Locked / Reconnecting
- Connect
- Control Phone

After first-time pairing, no new 6-digit code is required for ordinary sessions.

## System architecture

```text
Home Android PhoneDesk
        |
        | outbound WSS/TLS 443
        v
PhoneDesk Relay
        ^
        | outbound WSS/TLS 443
        |
Office Windows PhoneDesk
        |
        +--> local scrcpy process when ADB Remote is available
```

No home PC is required.

## Hybrid control model

Remote Mode uses two control paths.

### Primary: ADB Remote / scrcpy

When the Android PhoneDesk app has a healthy trusted local ADB session, Remote Mode opens an end-to-end encrypted logical ADB tunnel from the Windows client to the phone. The Windows client exposes the remote ADB transport only to its own local PhoneDesk process and launches scrcpy against that transport.

Goals:

- low-latency screen/control,
- best compatibility with existing PhoneDesk code,
- physical phone screen can be turned off where Android/scrcpy allows it,
- no public ADB listener.

### Fallback: native Android remote channel

If ADB is unavailable, PhoneDesk falls back to an Android-native remote path only where the user has explicitly granted Android permissions. This path is intended for availability, not lock-screen bypass.

The native fallback must never:

- bypass Android lock authentication,
- suppress mandatory Android privacy/security indicators,
- secretly enable microphone or camera capture,
- store or replay PIN, password, pattern, fingerprint, or biometric credentials.

## Android states reported to the laptop

The phone can publish a minimal presence state to the relay:

- ONLINE
- OFFLINE
- LOCKED
- UNLOCKED
- ADB_READY
- FALLBACK_READY
- LOCAL_UNLOCK_REQUIRED

Screen frames are not sent merely because the phone is online. Media/control traffic begins only after an authenticated trusted laptop starts a session.

## Relay responsibilities

The relay has two logical planes.

### Control plane

Handles:

- device registration,
- one-time pairing session creation,
- presence,
- trusted-device relationships,
- revocation,
- remote session creation,
- session teardown,
- rate limiting,
- reconnect coordination.

### Data plane

Forwards opaque encrypted frames for logical channels:

- CONTROL
- ADB
- SCREEN
- INPUT
- HEARTBEAT
- DIAGNOSTICS

The relay must not require access to decrypted screen frames, input events, or tunneled ADB payloads.

## Cryptography

Do not invent custom cryptographic primitives.

Use established libraries and primitives:

- long-term device identity keys,
- X25519-style ephemeral session key agreement,
- authenticated encryption such as ChaCha20-Poly1305 or AES-GCM,
- transcript authentication tied to both long-term device identities,
- fresh ephemeral session keys for each remote-control session,
- sequence numbers/nonces for replay protection,
- explicit trusted-device revocation.

The relay sees routing metadata but not plaintext session payloads.

## Pairing security

The QR path is preferred because it can carry a high-entropy pairing secret.

The 6-digit code is a convenience fallback and therefore must be protected by:

- short expiry,
- one successful use maximum,
- per-session and per-IP attempt limits,
- phone-side confirmation of the new laptop,
- fingerprint display on both devices.

A failed or expired code cannot create device trust.

## Reconnection behavior

Both Android and Windows maintain outbound relay connections and reconnect automatically after network changes.

Suggested retry schedule:

- 2 seconds
- 5 seconds
- 10 seconds
- 30 seconds maximum repeating delay

Trust survives ordinary network loss. Re-pairing is not required unless the device is revoked or local trust storage is cleared.

## Android reboot behavior

After a full Android reboot, the operating system may require a local first unlock before credential-encrypted app data and the full remote service are available.

PhoneDesk must report **LOCAL_UNLOCK_REQUIRED** instead of attempting any lock bypass.

## Windows behavior

Windows PhoneDesk stores:

- its own private identity key in OS-protected application storage,
- the trusted phone public identity,
- relay URL/configuration,
- non-secret UI preferences.

It does not persist one-time pairing codes.

When the user presses **Connect**:

1. authenticate the phone identity,
2. negotiate fresh session keys,
3. query available control paths,
4. prefer ADB Remote,
5. otherwise choose native fallback if available,
6. surface the selected mode clearly in diagnostics,
7. expose only simple status in the main UI.

## Server deployment model

Remote Mode v1 uses one relay service reachable on HTTPS/WSS port 443.

Initial deployment:

```text
Internet
   |
TLS/WSS :443
   |
PhoneDesk Relay Service
   |
PostgreSQL
```

The database stores only the minimum required metadata:

- device IDs,
- public identities,
- trusted-device relationships,
- revocation state,
- pairing-session hashes/expiry,
- minimal session/presence metadata.

It must not store:

- Android lock credentials,
- ADB Wireless Debugging pairing codes,
- decrypted ADB traffic,
- decrypted screen/video frames,
- decrypted input events.

## Public attack surface

Only expose:

- HTTPS registration/pairing endpoints,
- authenticated WSS device connection endpoint,
- authenticated trusted-device/revocation endpoints,
- minimal health endpoint.

Do not expose PostgreSQL, raw ADB, scrcpy, debug consoles, or internal admin ports publicly.

## Reliability requirements

- Android reconnects after Wi-Fi/mobile-data changes.
- Windows reconnects after office-network changes.
- Relay outages do not erase device trust.
- ADB failure does not automatically revoke the laptop.
- Native fallback can be selected when available.
- The UI distinguishes Offline, Relay unavailable, Locked, Local unlock required, ADB unavailable, and Revoked.

## Existing-code integration

### Android baseline

Start from branch `build/phonedesk-v02` and preserve:

- `AndroidKadbClient`,
- `PairingInput`,
- `ConnectionInput`,
- `PhoneDeskCoordinator`,
- app-private ADB private-key storage,
- current unit-test approach.

Remote Mode adds focused modules rather than moving all logic into `MainActivity`.

### Windows baseline

Bring the current portable Windows client from branch `build/phonedesk-pc-v01` into the Remote Mode branch and preserve:

- bundled `adb.exe`,
- bundled `scrcpy.exe`,
- simple desktop UI,
- automatic local Wireless Debugging discovery for same-network mode.

Remote Mode becomes an additional connection mode; same-network mode remains useful for setup and diagnostics.

## Module boundaries

### Android

- `identity/DeviceIdentityStore` — long-term PhoneDesk identity keys.
- `remote/RelayClient` — persistent WSS connection, heartbeat, reconnect.
- `remote/PairingManager` — create/expire/confirm one-time laptop pairing.
- `remote/SessionCrypto` — authenticated session key establishment and encrypted frames.
- `remote/RemoteSessionCoordinator` — state machine choosing ADB Remote vs native fallback.
- `remote/AdbTunnelEndpoint` — bridges encrypted ADB channel to the existing trusted local ADB session.
- `remote/RemoteForegroundService` — Android lifecycle/service ownership with required OS visibility.

### Windows

- `identity.py` — Windows device identity storage.
- `relay_client.py` — WSS connection/reconnect/presence/session signaling.
- `session_crypto.py` — authenticated session crypto.
- `remote_transport.py` — encrypted logical channel transport.
- `adb_bridge.py` — local endpoint used by PhoneDesk/scrcpy for the remote ADB channel.
- `phonedesk_pc.py` — UI orchestration only; Remote Mode main status and actions.

### Relay

- `server/app.*` — HTTPS/WSS application entry point.
- `server/pairing.*` — pairing-session issuance, rate limiting, expiry.
- `server/devices.*` — registration, trust, revocation, presence.
- `server/sessions.*` — authenticated routing between trusted phone/laptop devices.
- `server/storage.*` — PostgreSQL persistence interfaces.
- `server/protocol.*` — versioned message types and validation.

## Protocol versioning

Every control message must include a protocol version. Remote Mode v1 starts at `protocol_version = 1`.

Unknown major versions are rejected cleanly rather than interpreted loosely.

## Logging/privacy

Logs may contain:

- generated device/session IDs,
- connection timestamps,
- state transitions,
- error classes,
- relay latency/byte counts.

Logs must redact or never record:

- one-time pairing codes,
- pairing QR secrets,
- Android lock credentials,
- encryption private keys,
- decrypted ADB commands/responses,
- screen contents,
- input-event content.

## v1 build order

Remote Mode is implemented in milestones so each stage is independently testable.

1. Shared protocol and relay presence skeleton.
2. Android + Windows long-term identities.
3. One-time QR/manual pairing and trusted-device persistence.
4. End-to-end encrypted control-channel handshake.
5. Online/offline/reconnect status.
6. Encrypted generic byte-stream tunnel through relay.
7. ADB Remote bridge over that tunnel.
8. scrcpy launch against remote ADB transport.
9. Android native fallback scaffolding where OS permissions allow it.
10. Revocation, hardening, packaging, deployment documentation, and end-to-end tests.

## Acceptance criteria for Remote Mode v1

A v1 build is acceptable when all of the following are demonstrated:

1. A phone on one internet connection and a Windows laptop on another can both connect outbound to the relay.
2. The devices pair once using QR or the manual fallback flow.
3. A later session authenticates using saved device trust with no repeated pairing code.
4. Session payloads are end-to-end encrypted and the relay routes opaque frames.
5. Network interruption recovers without deleting trust.
6. Device revocation blocks new sessions.
7. No raw ADB port is exposed publicly.
8. When the phone has a healthy local trusted ADB session, the office laptop can establish an encrypted ADB Remote stream through the relay.
9. scrcpy can use the remote transport when ADB Remote is healthy.
10. Android lock authentication is never bypassed; after reboot PhoneDesk can report local unlock required.
11. Pairing secrets and lock credentials do not appear in persistent configuration or logs.
12. Existing local/same-network PhoneDesk behavior remains usable for setup and diagnostics.

## Explicitly out of scope for v1

- bypassing Android PIN/password/pattern/biometrics,
- hiding mandatory Android security/privacy indicators,
- covert camera or microphone activation,
- public raw ADB exposure,
- recording/storing remote screen sessions on the relay,
- multi-region relay clustering,
- enterprise fleet management,
- app-store production signing/distribution.
