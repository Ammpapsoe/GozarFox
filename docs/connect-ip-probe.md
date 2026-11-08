# CONNECT-IP probe — findings and decision

Date: 2026-11-06. Probe run from a live client network (Amsterdam, NL) against a
production Mozilla VPN relay.

## What was probed

- Relay list: `https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records`
  (public, unauthenticated). Relays are Fastly MASQUE endpoints, e.g.
  `ehrd229.m1.fastly-masque.net:2499` (certificate `CN=*.m1.fastly-masque.net`,
  GlobalSign OV, ALPN `h2` available).
- Probe: TLS + ALPN h2 → HTTP/2 preface + SETTINGS → read the server SETTINGS →
  send unauthenticated extended-CONNECT requests with `:protocol` =
  `connect-ip`, `connect-udp`, `masque`, and plain CONNECT (control).

## Results

| Observation | Value |
| --- | --- |
| `SETTINGS_ENABLE_CONNECT_PROTOCOL` (id 8) | **1 — extended CONNECT (RFC 8441) is enabled** |
| `SETTINGS_MAX_CONCURRENT_STREAMS` (id 3) | 1000 |
| `SETTINGS_HEADER_TABLE_SIZE` | 8192 |
| Unauthenticated CONNECT with any `:protocol` | `RST_STREAM(PROTOCOL_ERROR)` — uniformly |

The blanket reset for unauthenticated requests does not distinguish rejection
by authentication from lack of a particular protocol. **Neither CONNECT-IP nor
CONNECT-UDP acceptance was established by this probe.** Other client projects
report authenticated CONNECT-UDP support, but that is not a production test of
this client, account, endpoint, or fallback behavior.

## Decision

This probe alone is not a basis for claiming production support for CONNECT-IP
(RFC 9484) or CONNECT-UDP (RFC 9298). The later development branch connects an
HTTP/2 CONNECT-UDP client to the application packet paths for automated
loopback testing, but production acceptance has not been established. Each
protocol needs an authenticated in-app test of its actual request, response and
datagram formats. CONNECT-IP and CONNECT-UDP have different semantics and
requirements; they are not interchangeable simply by changing the `:protocol`
header.

A failed request, timeout, connection reset or 4xx/5xx does not necessarily
mean permanent lack of protocol support: authentication, rate limits, server
health or the network may be responsible. Never cache those failures as a
permanent downgrade, and never silently send unsupported UDP outside the VPN.
See `masque-udp-development.md` for the connected development implementation
and remaining validation work.
