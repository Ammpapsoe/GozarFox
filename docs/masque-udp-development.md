# WARP CONNECT-IP UDP: development candidate

The Android and desktop candidates carry non-DNS IPv4 UDP using Cloudflare
WARP's HTTP/2 CONNECT-IP transport **inside an authenticated Mozilla TCP
CONNECT tunnel**. There is no direct WARP fallback, and the former experimental
Mozilla CONNECT-UDP implementation is not selected by either app. DNS retains
its separate, tunnelled resolver path. WARP is off by default.

## User consent and routing

Enabling the setting requires explicit acceptance of Cloudflare's application
terms. The client registers a free WARP device, stores its identity encrypted,
and discloses that Cloudflare processes its UDP. While the VPN is connected,
the client scans known Cloudflare endpoints and selects one only after a pinned
TLS handshake, CONNECT-IP acceptance **and an actual DNS-over-UDP round trip**.
Endpoint TCP reachability alone is not reported as success. An unavailable
transport causes UDP to fail closed; Android retries scanning after a failure,
and both cores can re-scan after the selected session is lost.

Android forwards non-DNS IPv4 UDP through WARP regardless of configured TCP
split rules, routes IPv6 into the VPN for dropping, and bounds flow counts and
packet queues. Per-app exclusions and the separate experimental root-routing
feature have their own disclosed bypass limitations. Android uses the normal
force-tunnel loopback proxy for both Cloudflare enrollment and the WARP endpoint;
the proxy itself opens an authenticated Mozilla CONNECT.

Desktop temporarily pauses selective direct split routing while WARP is enabled:
otherwise a host-wide route installed for a direct TCP flow could also send UDP
outside the TUN. It may reconnect when changing the setting. Existing operating
system LAN routes, relay-host exceptions and IPv6 can still be outside the
IPv4-only desktop tunnel, including UDP. They are disclosed in the consent
prompt; do not interpret the setting as device-wide leak protection. A firewall
or kill-switch failure, a forced exit, or a reconnect may also interrupt
protection. Desktop WARP identity storage fails closed if encryption is not
available; Unix stores a private local key alongside its encrypted data, while
Windows uses the user-bound OS protection API.

Both implementations use a 1280-byte inner MTU and do not reassemble fragmented
IPv4 datagrams. Oversized payloads, IPv6 inside WARP, and unsupported
destinations are dropped. HTTP/2 over TCP has head-of-line blocking; latency,
throughput, arbitrary game ports and quality on other networks are not promised.
Cloudflare's bare-IP type-zero capsule format is distinct from RFC 9298's
context-prefixed CONNECT-UDP format. Android 7–9 lack the public ALPN API and
try platform Conscrypt's non-public ALPN methods; if unavailable or blocked,
WARP fails closed. This compatibility path has not been verified on a physical
device.

## Evidence and limits

With explicit authorization, an isolated Python probe authenticated a real
Firefox account, refreshed its token, obtained a Mozilla pass, and completed a
real UDP DNS response through Mozilla → WARP. At commit `aa81e51`, CI-built
Kotlin and Go diagnostics using the apps' WARP cores each passed authenticated
endpoint scanning and a second UDP round trip through a restricted local bridge
into that real Mozilla relay. The Kotlin core also re-enrolled the same test
device through Mozilla. These tests are useful transport evidence, but the
bridge is not the Android VPN interface or the desktop app's production tunnel
manager. In a separate desktop-JVM diagnostic, the real Android account core
signed in and refreshed tokens but its default-TLS proxy-pass request received
HTTP 406; TLS 1.2 succeeded with the same token. At `92fdccd`, the actual
Android account core then obtained a nonempty proxy pass using the scoped retry.
At `72ea593`, the actual Go account core also completed login, OAuth, refresh,
server list and a read-only proxy-pass request. These were isolated diagnostics;
they have not established actual Android or desktop app connectivity.

GitHub Actions covers synthetic protocol, lifecycle, UI and security tests;
signed-upgrade emulator and native desktop TUN tests use simulated upstreams.
A successful synthetic or isolated transport test is not proof of the real
Android UI, physical device, production desktop application, every network, or
all updater/reconnect scenarios. The previous unauthenticated relay experiments
in `connect-ip-probe.md` cannot establish authenticated Mozilla CONNECT-UDP
support and are not used as proof for WARP.
