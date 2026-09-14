# PhoneDesk Remote Mode v1 — Push-to-Start Design Specification

## Purpose

PhoneDesk Remote Mode lets the owner of an Android phone leave the phone elsewhere and securely connect to it from a trusted Windows laptop over the public internet, including when the phone is using mobile data.

The final Remote Mode must not depend on Android Wireless Debugging or scrcpy for ordinary internet access. Wireless Debugging/scrcpy remains only as an optional local diagnostic/high-performance path.

The primary design goal is a user-friendly remote-control flow with **zero persistent PhoneDesk notification while the phone is idle**. A new remote session must always begin with an explicit user approval on the phone.

## Non-negotiable privacy and platform rules

PhoneDesk must never:

- bypass Android PIN, password, pattern, fingerprint, face unlock, or other lock authentication,
- suppress, disguise, rename, or hide Android-mandated security/privacy indicators,
- silently start screen capture without the Android permissions/consent required by the OS,
- secretly enable microphone or camera capture,
- expose raw ADB to the public internet,
- store or replay lock credentials,
- continue screen capture/input after the user ends the session,
- leave an active remote-control session hidden after disconnection.

PhoneDesk may have **no persistent PhoneDesk notification while idle**. During an approved active remote-control session, Android may require a foreground-service and/or screen-capture indicator; PhoneDesk must respect that requirement.

## User experience

### First-time setup

The first-time setup should avoid IP addresses, ports, ADB terminology, and scrcpy terminology.

1. The user opens PhoneDesk on Android.
2. PhoneDesk asks for notification permission so future **PhoneDesk access request** notifications can arrive.
3. PhoneDesk recommends Android battery usage be set to **Unrestricted** for reliable push delivery.
4. Android PhoneDesk creates a permanent device identity and stores its private key in Android Keystore-backed secure storage.
5. The user chooses **Pair new computer**.
6. The phone displays a QR code and a 6-digit fallback pairing code.
7. Windows PhoneDesk creates its own permanent device identity and scans/enters the pairing information.
8. The phone confirms the new trusted computer.
9. Both devices save the other device's public identity.
10. Temporary pairing material expires after 5 minutes or immediately after successful use.

### Android idle screen

The normal Android home screen should be simple:

- **PhoneDesk**
- **Ready**
- “No remote session is active.”
- **My trusted computer** — Office Laptop — Trusted
- **Test connection**
- **Trusted devices**
- **Settings**

There is no permanent “Remote Mode ON” foreground service while idle.

### Windows idle screen

The normal Windows client should show:

- **My Phone**
- Ready / Offline / Waiting / Locked / Reconnecting
- large **CONNECT TO PHONE** button

After first-time pairing, no ADB, IP, pairing-port, or temporary-code fields should appear in the normal flow.

### Starting a remote session

1. The user presses **CONNECT TO PHONE** on the trusted Windows laptop.
2. Windows authenticates to the PhoneDesk relay and requests a session for the paired phone.
3. The relay sends a low-power push wake request to Android using Firebase Cloud Messaging (FCM).
4. Android shows a normal notification titled **PhoneDesk access request**.
5. Notification text identifies the trusted computer, for example: **Office Laptop wants to connect.**
6. The notification provides **ACCEPT** and **DENY** actions.
7. If the user taps **DENY**, the request is rejected and no remote service starts.
8. If the user taps **ACCEPT**, PhoneDesk opens the session-start screen and performs any Android-required screen-capture confirmation.
9. Only after required user approval does PhoneDesk start the active remote-control service and connect to the relay.
10. Windows automatically opens the PC mirror when the active stream becomes ready.

### Active-session Android screen

During an approved session, PhoneDesk should clearly show:

- **Remote session active**
- trusted computer name, e.g. **Office Laptop**
- connection state
- **STOP REMOTE ACCESS**

Android-required foreground-service or screen-sharing indicators remain visible while required by the OS.

### Active-session Windows screen

The Windows client should show:

- **CONNECTED**
- live mirror automatically open
- connection state: Streaming / Reconnecting / Waiting for phone unlock / Ended
- **Screen quality:** Auto / Fast / Sharp
- phone-screen control where supported
- **DISCONNECT**

The default quality mode should prioritize responsiveness and low latency.

## Idle architecture

When no remote session is active:

- there is no PhoneDesk foreground service,
- there is no persistent PhoneDesk notification,
- there is no continuous PhoneDesk WSS connection,
- there is no screen capture,
- there is no remote-input channel,
- there is no continuous video encoding,
- there is no continuous ADB session for final Remote Mode,
- FCM is used only to deliver wake/session-request notifications.

This keeps idle battery and data usage minimal compared with a permanent socket or permanent foreground service.

If the user force-stops PhoneDesk, disables notifications, or the OEM blocks push delivery, a remote request may not arrive until the app is opened again. PhoneDesk must surface this reliability limitation during setup.

## Active-session architecture

```text
Office Windows PhoneDesk
        |
        | HTTPS/WSS 443
        v
PhoneDesk Relay
        |
        | FCM wake request while Android is idle
        v
Home Android PhoneDesk
        |
        | user taps ACCEPT
        | Android-required capture consent
        v
ActiveSessionService
        |
        | encrypted WSS media/control session
        +---------------------------> PhoneDesk Relay <--------------------+
                                                                            |
                                                                            v
                                                               Office Windows PhoneDesk
```

The relay is the rendezvous and encrypted transport router. It must not need plaintext access to screen frames or input events.

## Android module boundaries

### `identity/DeviceIdentityStore`

Responsibilities:

- create the long-term PhoneDesk device identity,
- keep the private key in Android Keystore-backed storage,
- expose only public identity/fingerprint to pairing/session modules.

### `push/PushRegistration`

Responsibilities:

- obtain and refresh the FCM registration token,
- register the token with the PhoneDesk relay for the authenticated phone identity,
- never start screen capture or remote input itself.

### `remote/AccessRequestManager`

Responsibilities:

- validate incoming push session requests,
- confirm the requesting laptop is trusted and not revoked,
- generate the **PhoneDesk access request** notification,
- handle ACCEPT / DENY,
- prevent expired/replayed access requests from starting sessions.

### `remote/ActiveSessionService`

Responsibilities:

- start only after an accepted request and required Android consent,
- own the foreground-service lifecycle during an active session where required,
- maintain the active WSS/TLS transport,
- stop itself completely when the session ends or expires,
- remove its PhoneDesk active-session notification on teardown where Android allows.

### `remote/SessionCrypto`

Responsibilities:

- authenticate both trusted long-term device identities,
- establish fresh ephemeral session keys for every approved remote session,
- encrypt/authenticate SCREEN, INPUT, CONTROL, HEARTBEAT, and DIAGNOSTICS frames,
- prevent replay with monotonically enforced sequence numbers/nonces.

Do not invent cryptographic primitives. Use well-reviewed libraries and established primitives such as X25519-style key agreement plus ChaCha20-Poly1305 or AES-GCM.

### `remote/NativeScreenChannel`

Responsibilities:

- use Android MediaProjection and MediaCodec H.264/compatible hardware encoding,
- capture only after Android's required consent,
- expose tunable bitrate/resolution/FPS profiles,
- prioritize low latency in **Fast** mode,
- stop encoding immediately when the phone locks, the session ends, or capture permission is lost.

### `remote/RemoteInputService`

Responsibilities:

- use Android Accessibility APIs only after the user explicitly enables the PhoneDesk accessibility service,
- map authenticated input events to permitted Android gestures/actions,
- stop accepting remote input when the device is locked or the session is not ACTIVE,
- never interact with lock credentials.

### `remote/SessionRecovery`

Responsibilities:

- coordinate Wi-Fi/mobile-data changes,
- maintain a 2-minute reconnection window for an already-approved session,
- pause video/input while the device is locked,
- resume the approved session automatically after local unlock if still within the valid session/reconnect window,
- signal Windows to reopen/resume the PC mirror automatically,
- end the session completely after the 2-minute recovery window expires.

## Windows module boundaries

### `identity.py`

- create and persist the Windows long-term identity,
- protect its private key with Windows DPAPI or equivalent OS protection,
- store trusted phone public identity/fingerprint.

### `relay_client.py`

- authenticate to the relay,
- request a session,
- wait for phone ACCEPT/DENY/timeout,
- establish active WSS transport only for approved sessions,
- handle reconnect state for up to 2 minutes.

### `session_crypto.py`

- authenticated session handshake,
- fresh ephemeral keys per session,
- authenticated encrypted frame handling.

### `native_viewer.py`

- receive/decode low-latency Android video,
- open automatically when stream state becomes ACTIVE,
- reopen automatically after unlock/reconnect resume,
- support Auto / Fast / Sharp profiles.

### `input_sender.py`

- send pointer, touch, keyboard, navigation, and supported control events only while the session is ACTIVE and phone state is UNLOCKED.

### `phonedesk_pc.py`

- user-friendly UI orchestration,
- no normal-use IP/port/ADB fields,
- show Waiting for approval / Connected / Reconnecting / Waiting for phone unlock / Ended,
- expose CONNECT, DISCONNECT, and quality selection.

Existing local Wireless Debugging/scrcpy code remains available only in an advanced/local diagnostic path.

## Relay responsibilities

The relay has two logical planes.

### Control plane

Handles:

- device registration,
- FCM token registration/update,
- one-time device pairing,
- trusted-device relationships,
- revocation,
- remote access request creation,
- ACCEPT/DENY/timeout state,
- active-session creation,
- 2-minute reconnect coordination,
- teardown,
- rate limiting.

### Data plane

During an approved active session, forwards opaque end-to-end-encrypted frames for:

- CONTROL
- SCREEN
- INPUT
- HEARTBEAT
- DIAGNOSTICS

The relay must not require decrypted screen frames or decrypted input-event content.

## Push request security

An FCM wake message is not authority to start remote control.

Each access request must include/resolve to:

- unique request ID,
- requesting trusted-laptop identity,
- target phone identity,
- short expiry,
- relay-authenticated request binding,
- anti-replay state.

The Android app must confirm that the laptop is trusted and not revoked before showing an actionable request.

Tapping **ACCEPT** authorizes only that session request. A later new session requires a new **PhoneDesk access request** and another ACCEPT.

## Session lifecycle

### IDLE

- zero persistent PhoneDesk notification,
- no remote stream,
- no input,
- no foreground session service.

### REQUESTED

- PhoneDesk access request notification visible,
- no screen capture,
- no remote input.

### STARTING

- user has tapped ACCEPT,
- Android-required capture confirmation is being completed,
- fresh session crypto is negotiated.

### ACTIVE

- screen streaming and remote input allowed,
- Android active-session indicators shown as required,
- Windows mirror opens automatically.

### LOCKED_PAUSED

If the phone locks during an approved session:

- video stops,
- remote input stops,
- Windows shows **Waiting for phone unlock**,
- PhoneDesk does not attempt to unlock the phone,
- the approved session may retain only minimal heartbeat/recovery state.

When the user unlocks locally and the session is still valid, the stream resumes and the PC mirror reopens automatically without a second ACCEPT.

### RECONNECTING

If connectivity is interrupted during an approved session:

- retry automatically across Wi-Fi/mobile-data changes,
- Windows shows **RECONNECTING…**,
- keep the approval valid for up to 2 minutes,
- use fresh transport/session-resume protections as defined by the protocol,
- if reconnection succeeds within 2 minutes, resume automatically,
- if not, transition to ENDED.

### ENDED

On user disconnect, STOP REMOTE ACCESS, denial, fatal error, or reconnect timeout:

- stop video encoder,
- stop accepting input,
- close active WSS transport,
- stop ActiveSessionService,
- discard temporary session keys,
- clear transient session state,
- return Android to Ready/IDLE,
- remove PhoneDesk active-session notification where Android permits,
- require a new access request and ACCEPT for the next remote session.

## Phone lock and reboot behavior

PhoneDesk never unlocks the device remotely.

If the phone is already locked when a new request arrives, the request may be deferred until after local unlock rather than exposing remote-access details on the lock screen.

After a full Android reboot, local first unlock may be required before encrypted app data, push-registration state, accessibility state, or session capabilities are fully available. PhoneDesk must report **LOCAL_UNLOCK_REQUIRED** rather than attempting any bypass.

## Mobile-data behavior

Final Remote Mode must operate when the phone is on mobile data because Android initiates all relay connections outbound after ACCEPT.

No inbound home port forwarding is required.

Changing from Wi-Fi to mobile data, or mobile data to Wi-Fi, during an approved session should enter RECONNECTING and resume automatically if the connection returns within the 2-minute window.

PhoneDesk must not monopolize the phone's data connection. The rest of the phone should remain usable normally, subject to normal network bandwidth/latency constraints.

## Battery and bandwidth behavior

Idle state must avoid permanent video, permanent WSS, permanent ADB, and foreground-service work.

During an active session, PhoneDesk should use adaptive streaming:

- **Fast**: lower resolution/bitrate, lower buffering, responsive controls,
- **Auto**: adjust bitrate/resolution based on measured latency/bandwidth,
- **Sharp**: higher visual quality with potentially more latency/data use.

When locked/paused or reconnecting without active video, the app should reduce activity to heartbeat/recovery traffic only.

## First-time Android permissions

PhoneDesk should guide the user through only permissions needed for the chosen features:

- Notifications — required for **PhoneDesk access request** delivery.
- Battery usage Unrestricted — recommended for reliable push/session startup on aggressive OEM battery managers.
- Accessibility service — required for native remote input/control.
- MediaProjection consent — required by Android when starting screen capture; PhoneDesk must follow OS behavior and must not bypass or fake this permission.

Camera and microphone permissions are not part of Remote Mode v1.

## Pairing security

The QR path is preferred because it can carry a high-entropy temporary secret.

The 6-digit fallback must have:

- short expiry,
- one successful use maximum,
- per-session and per-IP attempt limits,
- phone-side confirmation,
- public-key fingerprint display on both devices.

Pairing code/QR secret must never be written to persistent logs or relay storage in plaintext.

## Relay deployment

Initial deployment:

```text
Internet
   |
HTTPS/WSS :443
   |
PhoneDesk Relay
   |
PostgreSQL
   +-- device/trust/session metadata
   +-- FCM token metadata
```

Only expose:

- HTTPS registration/pairing endpoints,
- access-request endpoints,
- authenticated WSS active-session endpoint,
- trusted-device/revocation endpoints,
- minimal health endpoint.

Do not publicly expose PostgreSQL, raw ADB, scrcpy, internal debug consoles, or admin ports.

## Stored metadata

The relay may store only what is required for routing/trust:

- generated device IDs,
- public device identities,
- trusted-device relationships,
- revocation state,
- hashed/expiring pairing-session material,
- current FCM registration token and refresh metadata,
- access-request status/expiry,
- minimal active-session/reconnect metadata.

It must not store:

- Android lock credentials,
- ADB Wireless Debugging pairing codes,
- private device identity keys,
- decrypted screen/video frames,
- decrypted input events,
- decrypted active-session payloads.

## Logging/privacy

Logs may contain:

- generated device/request/session IDs,
- timestamps,
- state transitions,
- error classes,
- relay latency and byte counts.

Logs must never contain:

- 6-digit pairing codes,
- QR secrets,
- Android lock credentials,
- private keys,
- plaintext screen content,
- plaintext input-event content.

## Reliability requirements

- access request can arrive while PhoneDesk is not open, subject to Android/FCM/OEM limitations,
- phone can start an approved session on Wi-Fi or mobile data,
- network changes do not erase trust,
- approved sessions get a 2-minute reconnect window,
- phone lock pauses stream/input,
- local unlock resumes the same approved session automatically when still valid,
- Windows mirror reopens automatically after unlock/reconnect,
- session timeout tears everything down and requires a new ACCEPT,
- device revocation blocks new sessions,
- no raw ADB port is exposed publicly.

## Existing code integration

### Android

Preserve reusable existing pieces where appropriate:

- permanent device identity work,
- current unit-test approach,
- local Wireless Debugging/ADB code only for diagnostics or optional same-network testing.

Do not make the final internet Remote Mode depend on a self-ADB connection.

### Windows

Preserve:

- permanent Windows identity work,
- existing simple desktop UI foundations,
- bundled local scrcpy/ADB only for advanced local diagnostics.

The main Remote Mode path uses the native encrypted video/input channel.

### Relay

Preserve the existing protocol/presence/pairing skeleton where compatible, but change idle Android presence from a permanent WSS connection to push-to-start registration plus active-session WSS only after ACCEPT.

## Protocol versioning

Every control message includes a protocol version.

Remote Mode v1 starts with `protocol_version = 1`.

Unknown major versions are rejected cleanly.

## Build order

The implementation is divided into independently testable milestones:

1. Update shared protocol for access requests, ACCEPT/DENY, session lifecycle, lock state, and 2-minute reconnect semantics.
2. Add relay FCM token registration and access-request issuance.
3. Add Android FCM registration and **PhoneDesk access request** notification flow.
4. Add Android trusted-request validation and ACCEPT/DENY handling.
5. Add active-session authenticated crypto handshake.
6. Add `ActiveSessionService` that exists only during an approved session.
7. Add native Android screen capture/encoding and Windows low-latency viewer.
8. Add Accessibility-based input channel with explicit permission checks.
9. Add lock pause/unlock auto-resume and automatic Windows mirror reopen.
10. Add 2-minute reconnect across Wi-Fi/mobile-data changes.
11. Add user-friendly Windows/Android status UI and Auto/Fast/Sharp quality profiles.
12. Add revocation, teardown hardening, packaging, deployment documentation, and end-to-end tests.

## Acceptance criteria

Remote Mode v1 is acceptable only when all of the following are demonstrated:

1. Android has no persistent PhoneDesk foreground-service notification while idle.
2. Windows can request access while Android PhoneDesk is not open, subject to FCM/Android delivery constraints.
3. Android displays **PhoneDesk access request** with ACCEPT and DENY.
4. DENY never starts screen capture or remote input.
5. ACCEPT plus required Android capture consent starts the active session.
6. Phone on mobile data and Windows on a separate internet connection can establish an approved session through the relay.
7. The Windows mirror opens automatically when the stream becomes ready.
8. Screen/control payloads are end-to-end encrypted and the relay only routes opaque frames.
9. Locking the phone pauses screen/input and Windows shows **Waiting for phone unlock**.
10. Local unlock automatically resumes a still-valid approved session and reopens/resumes the PC mirror.
11. Wi-Fi/mobile-data transition can recover automatically within the 2-minute reconnect window.
12. Failure to reconnect within 2 minutes ends the session and requires a new access request/ACCEPT.
13. STOP REMOTE ACCESS or Windows DISCONNECT stops streaming/input, closes the active connection, discards temporary session keys, and returns Android to IDLE.
14. No raw ADB port is exposed publicly.
15. Android lock authentication is never bypassed.
16. Pairing secrets, lock credentials, screen content, and input content do not appear in persistent logs.
17. Existing local/same-network ADB/scrcpy tooling remains available only as an advanced diagnostic path and is not required for internet Remote Mode.

## Explicitly out of scope for v1

- bypassing Android PIN/password/pattern/biometrics,
- hiding or disguising mandatory Android security/privacy indicators,
- covert remote control,
- covert camera or microphone activation,
- recording/storing remote screen sessions on the relay,
- public raw ADB exposure,
- multi-region relay clustering,
- enterprise fleet management,
- app-store production signing/distribution.
