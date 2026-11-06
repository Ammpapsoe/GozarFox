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

The blanket reset for unauthenticated requests means the proxy rejects before
protocol dispatch, so **connect-ip acceptance cannot be confirmed or denied
without a valid Bearer token**. `connect-udp` (RFC 9298) is known to work in
production with authentication (reference clients ship it against these same
relays).

## Decision

Do not implement CONNECT-IP (RFC 9484) blindly. Ship it in Phase 7 (UDP) with
runtime negotiation instead:

1. On the first UDP flow, try `:protocol = connect-ip` on an authenticated
   session.
2. On `RST_STREAM` or a 4xx/5xx for that attempt, permanently fall back to
   `:protocol = connect-udp` (DATAGRAM capsules, RFC 9297) for the session and
   record the outcome so later sessions skip the probe.

This gives CONNECT-IP where the backend accepts it and the proven
CONNECT-UDP path everywhere else, with no user-visible failure either way.
The prerequisite (extended CONNECT enabled on the relays) is verified; the
remaining unknown is backend protocol acceptance, which only an
authenticated in-app probe can settle.
