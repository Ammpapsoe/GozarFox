# UDP over HTTP/2: connected implementation, production validation pending

The development branch routes non-DNS IPv4 UDP through the application's VPN
packet paths on Android and desktop. Each target flow uses an authenticated,
certificate-verified HTTP/2 extended CONNECT stream with DATAGRAM capsules.
Existing DNS handling remains separate. Refusal or an unsupported peer does
**not** fall back to an unprotected UDP socket.

This describes the branch, not an already-published release. Real relay
acceptance, authenticated account access, end-to-end UDP delivery and destination
port policy remain unverified. In particular, an earlier unauthenticated probe
saw `SETTINGS_ENABLE_CONNECT_PROTOCOL=1`, but the relay reset all tested streams;
that does not prove UDP is supported or unsupported. Fastly's June 2026
[architecture article](https://www.fastly.com/blog/we-built-proxy-behind-firefoxs-new-built-in-vpn-heres-how-it-works)
describes the initial Firefox deployment as HTTP/2 TCP CONNECT and discusses
full MASQUE over HTTP/3 as a future direction. It does not establish whether
this account's current relay supports HTTP/2 CONNECT-UDP. A user-reported
successful installation of the older 1.11.2 release is not a UDP test.

## Protocol and routing

The codec implements a bounded subset of the DATAGRAM Capsule Protocol from
[RFC 9297](https://www.rfc-editor.org/rfc/rfc9297.html) and context-zero UDP
payload encoding from [RFC 9298](https://www.rfc-editor.org/rfc/rfc9298.html).
An empty datagram remains distinct from end of stream. Unknown capsule types
and context IDs are skipped, malformed/truncated values fail, and memory is
bounded. The target uses the default well-known UDP URI template; its use by a
specific production service is an assumption, not a discovery result.

The client currently requires an explicit, unparameterized
`Capsule-Protocol: ?1`, a 200 response without HTTP representation/body framing
headers, and a live streaming body. This is stricter than the RFC and may reject
otherwise-conforming proxies. HTTP/2 carries datagrams over an ordered, reliable
TCP stream; it does not provide native QUIC datagrams and can suffer
head-of-line delay. A per-flow idle timeout and concurrency limits bound
resources. Neither implementation uses a direct UDP fallback on relay failure.

On Android, non-DNS IPv4 UDP is demultiplexed in `FoxStack` and uses a
service-lifetime/transport-generation-scoped `FoxTunnel.openUDP` stream. Up to
64 flows share an 8 MiB aggregate queued-payload budget; packets exceeding the
configured TUN MTU are rejected. Its H2 pool checks the peer setting, decodes
complete HPACK response blocks in
wire order and validates the capsule response. IPv6 and fragmented IP packets
remain fail-closed; oversized return datagrams are dropped because the stack
does not fragment them. Split-routing UDP is also sent to the relay; it is not
sent through the direct TCP bypass. On desktop, the gVisor packet engine uses
`Manager.OpenUDP` with bounded tracked flows, while DNS keeps its existing
resolver path. The current desktop engine is IPv4-only.

## Automated evidence and remaining work

Unit tests use local fake TLS/H2 proxies and virtual packet interfaces. They
cover datagram boundaries, empty packets, headers/authentication, peer settings,
rejection without direct fallback and reset/stop races. Android tests also send
packets through the stack and H2 capsule client in one synthetic path. Desktop
builds include native TUN-to-local-proxy integration tests on Linux, Windows and
macOS; results must be checked for the actual commit. Simulated and native-TUN
local-proxy tests do not prove support by a real upstream relay.

Before representing the feature as verified for users, run an authorized
end-to-end account/relay test against an actual UDP echo destination and
relevant port policy. Login previously reached HTTP 406 after email verification
without reaching a relay; see `real-account-validation.md`. Establish actual
Android-device and desktop-network behavior separately where required. No
synthetic test proves device-wide leak freedom or the behavior of every network.
CONNECT-IP is a separate protocol and remains unimplemented.
