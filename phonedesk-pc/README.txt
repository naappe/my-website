PhoneDesk Windows v0.2

Portable Windows client for pairing with and controlling your own Android phone over Android Wireless Debugging.

EASY FIRST-TIME SETUP
1. Put the Android phone and Windows PC on the same Wi-Fi/local network.
2. On Android open Developer options → Wireless debugging.
3. Tap “Pair device with pairing code” and keep that popup open.
4. Open PhoneDesk.exe on Windows.
5. Click FIND MY PHONE. PhoneDesk finds the phone IP and pairing port automatically.
6. Type only the temporary 6-digit code shown by Android.
7. Click PAIR & CONNECT. PhoneDesk looks for the normal Wireless Debugging connection port automatically.
8. When the status says Connected, click CONTROL PHONE.

IF AUTOMATIC DISCOVERY FAILS
Use “Manual setup” at the bottom. This fallback is only for networks that block Android mDNS discovery.

IMPORTANT
- The temporary six-digit pairing code is never saved.
- PhoneDesk saves only the trusted phone IP/device port and screen-off preference.
- PhoneDesk does not store your Android PIN, password, pattern, fingerprint, or biometrics.
- Do not expose ADB directly to the public internet.
- Some banking/DRM apps can intentionally block mirroring or require separate authentication.
- After a phone reboot, Android may require a local unlock before all services become available.

CONTENTS
- PhoneDesk.exe — easy Windows controller UI
- adb.exe / Android platform tools — pairing and trusted connection engine
- scrcpy.exe and support files — low-latency Android screen/control
