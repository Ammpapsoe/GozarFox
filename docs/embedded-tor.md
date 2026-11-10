# Embedded first-login transport

Gozar starts Tor only for account authentication. It does not require Orbot, another VPN application, an existing Mozilla pass, or a relay secret. The Android service and both pluggable transports run inside Gozar's own process. No global proxy or VPN route is installed for login.

The transport order is direct Tor, Snowflake, then Meek. A candidate is used only after Tor reports bootstrap progress 100 and the fixed Mozilla server-list endpoint returns an HTTP response through its loopback SOCKS listener. Account authentication then runs once on that selected transport. HTTP/API account errors do not resend credentials through another transport. Tor, IPtProxy, listeners and temporary state are closed and removed when the authentication step finishes, including failure. Subsequent server loading, token renewal and Mozilla tunnel traffic use direct connections.

The login SOCKS scope is confined to its worker thread. `FoxNet` rejects destinations outside the exact Firefox Account, Guardian and Mozilla server-list routes. Worker fallback uses the same scope and retains ordinary TLS verification plus the observed workers.dev SPKI pin. The challenge solver cannot open a direct connection while this scope is active. The post-login control tunnel retains its separate, stricter allowlist.

The visible login status is only `در حال اتصال امن...`. Transport names, bootstrap details, bridge addresses and account data are not displayed.

## Verified upstream inputs

- `info.guardianproject:tor-android:0.4.9.14`, Maven Central, SHA-256 `28ff2bb6d4d42eabe57183dedd7a97fce77e8ce4b6f5af6fa03993b3ca054edd`.
- `com.netzarchitekten:IPtProxy:5.8.0`, Maven Central, SHA-256 `1f9ba9314ac2862e358134e60f20d89bce988e34bd60ae8b1b02797cdb9439c1`.
- Tor Browser 16.0.1 Tor Expert Bundle, `https://dist.torproject.org/torbrowser/16.0.1/`, supplied the Snowflake and Meek bridge parameters. The Android AArch64 bundle SHA-256 is `0091927b9fd72992a88021d55416b23a8380dc39a70c4b73a2cca9ee6a3dbcec`; the Linux x86_64 bundle SHA-256 is `7039cb16060ab22e669852e55774d894b55f25ea8bd0ab7194d3478429a58725`. Both matched the published `sha256sums-signed-build.txt` during acquisition.

The application includes only the three ABIs shared by both native dependencies: `arm64-v8a`, `armeabi-v7a`, and `x86_64`. There is no incomplete x86 Snowflake build and no universal APK containing unrelated architectures. ABI splits keep each download smaller than packaging all native libraries together. IPtProxy logging is disabled, unsafe logging is never enabled, Tor safe logging remains enabled, disk writes are minimized, and temporary Tor/transport directories are removed after use.

WebTunnel is not advertised as a fallback because the verified Tor Browser 16.0.1 default configuration did not provide a WebTunnel bridge. No bridge has been invented. The third fallback is the verified Meek configuration instead.

## Diagnostic results and limits

On 2026-11-10, the official Linux Tor 0.4.9.16 and Lyrebird 0.10.0 binaries from the verified 16.0.1 bundle reached bootstrap 100 with both the selected Snowflake and Meek configurations. Snowflake completed in approximately 21 seconds and Meek in approximately 57 seconds in the successful run. A deliberately invalid Guardian token produced HTTP 401 through both. These requests used no real credential. Earlier harness attempts with an incorrect, nonexistent Mozilla hostname produced SOCKS host-unreachable replies and were discarded as Mozilla evidence.

These results establish only that the tested transports could bootstrap and reach an authentication boundary from the diagnostic network. They do not prove reachability from an Iranian ISP, successful real login, behavior on every Tor exit, or compatibility of every Android device. The GitHub Android instrumentation job exercises the packaged native implementation and shutdown on x86_64; physical-device and real-account validation remain separate release gates.

A stable release additionally requires the exact-commit CI checks, a real authorized first login without any VPN, direct Mozilla tunnel establishment after Tor stops, and physical-device validation. Debug artifacts from the autonomous workflow are test artifacts, not Stable releases.
