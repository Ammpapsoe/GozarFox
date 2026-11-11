# Embedded first-login transport

Gozar starts Tor only for account authentication. It does not require Orbot, another VPN application, an existing Mozilla pass, or a relay secret. Tor and the pluggable transports are embedded in Gozar. No global proxy or VPN route is installed for login.

The Android manifest declares the official `org.torproject.jni.TorService` in the dedicated `:tor` process. Gozar follows Orbot's bound-service ownership, atomic start-request guard, ordered shutdown/unbind, and binding-death handling. The main process connects to Tor's official Unix Control Socket with jtorctl; it does not cast a local Binder across processes and does not use a handwritten remote PID protocol. Guardian Project and Orbot attribution and the BSD 3-Clause notice are retained in `NOTICE`, `CONTRIBUTORS.md`, and `ORBOT-LICENSE.txt`.

The route order is manual obfs4, signed obfs4/WebTunnel candidates, Snowflake, then Meek. A candidate is retained only after Tor reports bootstrap 100 and the Mozilla reachability check succeeds through its loopback SOCKS listener. Before 75% the bounded rotation budget applies; at 75% or later the attempt receives one fixed 30-second circuit-completion window. A healthy 100% tunnel is retained across Mozilla, code, OAuth, and Pass retries. A dead Tor binding is not retained.

Transport and Bridge configuration is applied with an atomic jtorctl collection SETCONF before `DisableNetwork=0`. Manual Bridge lines are normalized and validated, accepted only in RAM, and never written to torrc, preferences, saved instance state, native file logs, or the event viewer. Signed-feed authentication and endpoint restrictions remain independent from Bridge health checks.

The login SOCKS scope is confined to its worker thread. `FoxNet` rejects destinations outside the exact Firefox Account, Guardian, and Mozilla server-list routes. A short-lived loopback HTTP CONNECT bridge sends the original hostname to Tor as SOCKS ATYP=DOMAIN instead of allowing Java to resolve it locally. It permits only the Mozilla and strict Worker destinations on port 443, strips inbound proxy headers, and closes its listener and sockets when the scope ends. The challenge solver cannot open a direct connection while this scope is active. After completely successful authentication and Pass acquisition, Tor is stopped and the Mozilla tunnel is established directly.

Stop and Logout revoke the authentication epoch and release the bound TorService, native transports, listeners, ports, and scoped proxies. Temporary Tor and transport state is removed. The visible log viewer contains a bounded RAM-only sequence of sanitized state and diagnostic events; it does not accept raw Tor logs.

## Verified upstream inputs

- Tor-Android 0.4.9.14, Maven Central SHA-256 `28ff2bb6d4d42eabe57183dedd7a97fce77e8ce4b6f5af6fa03993b3ca054edd`. The vendored AAR retains every class, manifest entry, and native binary from that artifact; only automatically generated `aar-metadata.properties` is omitted for the existing AGP 8.12 / compileSdk 36 toolchain. The repackaged AAR SHA-256 is `2f5844ccb313b617f556ad6ad571f89e422bbb4f628714c8e8d688ddb0c57f21`.
- IPtProxy 5.8.0 source commit `5b33fa42bed1d7b16ee0d61402178a35822eb115`. Release and native-validation builds compile one AAR for arm64-v8a, armeabi-v7a, x86_64, and x86 with the upstream Android build settings and `android/386`. The pinned NDK is 28.0.13004108 and minimum Android API is 24. Native library substitution or mixing versions is not permitted.
- Orbot lifecycle reference: `https://github.com/guardianproject/orbot-android`; TorService/control reference: `https://github.com/guardianproject/tor-android`. These are source and architecture dependencies, not an Orbot application runtime dependency.
- Tor Browser 16.0.1 Tor Expert Bundle supplied the Snowflake and Meek parameters. The Android AArch64 bundle SHA-256 is `0091927b9fd72992a88021d55416b23a8380dc39a70c4b73a2cca9ee6a3dbcec`; the Linux x86_64 bundle SHA-256 is `7039cb16060ab22e669852e55774d894b55f25ea8bd0ab7194d3478429a58725`.

ABI splits keep individual downloads small. The universal APK contains the same four native ABI sets and is checked separately. Every release APK must contain compressed native libraries. IPtProxy debug logging and unsafe Tor logging remain disabled.

## Diagnostic results and limits

Earlier Linux diagnostics established that the selected Snowflake and Meek configurations could bootstrap and reach an authentication boundary from the diagnostic network. Those results do not establish compatibility with an Iranian ISP, every Tor exit, or every Android device.

Release 1.12.0-rc.4 requires exact-commit compilation, JVM/Robolectric tests, repeated native lifecycle and binding-death tests on x86_64 and x86 emulators, and authorized real login/signup emulator tests. Rooted emulator CI with test-only SELinux Permissive is a test harness, not an application feature. Emulator success must never be presented as physical-device success on MCI or Irancell. Physical-device validation remains separate and is required before any Stable claim.
