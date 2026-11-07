# Phase 7: UDP-over-HTTP development (not enabled)

The current application handles DNS over its existing tunnel, but other UDP is
still refused. The Kotlin branch code provides **capsule framing only**. The Go
branch also contains a target-specific, authenticated HTTP/2 extended-CONNECT
client and serialized capsule stream, but it is **not wired into the desktop
packet engine** and has no user-facing enable switch. Neither branch changes
the published release. Do not present this as working app UDP, CONNECT-IP,
WebRTC, HTTP/3, or a full IP VPN.

The codec implements a bounded subset of the DATAGRAM Capsule Protocol from
[RFC 9297](https://www.rfc-editor.org/rfc/rfc9297.html) and context-zero UDP
payload encoding from [RFC 9298](https://www.rfc-editor.org/rfc/rfc9298.html).
A clean end between capsules is distinct from an empty UDP datagram. Unknown
types and context IDs are skipped, non-minimal QUIC integer encodings are
accepted, truncated inputs fail, and resource use is bounded. Capsule values
larger than 1 MiB are rejected as a local resource policy; UDP payloads above
65,527 bytes are protocol errors. Stream decoding is incremental and consumes
unknown values without allocating them in full.

## Disabled desktop stream prototype

`Manager.OpenUDP` uses the existing authenticated and certificate-verified
HTTP/2 connection pool, a target-specific well-known UDP path, and
`Proxy-Authorization`. The Go HTTP/2 client waits for the peer's initial
`SETTINGS_ENABLE_CONNECT_PROTOCOL` before sending extended CONNECT. Failure
returns an error; there is no direct UDP fallback. Close/reset of the manager
closes tracked streams. UDP messages are serialized as bounded capsules, with
no intermediate per-flow datagram queue. This does not yet account for flow
limits or routing.

The first implementation deliberately requires a successful response with an
explicit, unparameterized `Capsule-Protocol: ?1`, no HTTP representation/body
framing headers, and a live streaming body. This is stricter than RFC 9297,
which only recommends that endpoints send the header and permits parameters:
otherwise-conforming relays may be rejected. Its URI template is an explicit
assumption requiring production validation, not proof that the relay supports
it. The normal manager's session-acquisition wait is not guaranteed to fit
within the supplied per-attempt timeout.

Synthetic loopback HTTP/2 tests cover target and credential headers, TLS
verification, explicit response acceptance, rejection without extended CONNECT
settings, capsule round trips including empty and maximum-sized payloads, and
pooled-session reuse. The test-only x/net server runs in a subprocess with
`GODEBUG=http2xconnect=1` because that server implementation disables extended
CONNECT by default; the setting is not changed in the application. Separate
50-iteration race tests exercise close while opening and concurrent capsule
writes / blocked-I/O cancellation. CI results must be checked on the actual
commit before treating any tests as passed.

## Remaining work before any routing is enabled

1. Implement equivalent authenticated extended CONNECT on Android. Its current
   custom HTTP/2 decoder extracts only the status, so it must validate the
   complete response and Capsule Protocol before sending datagrams. Confirm
   handling of peer settings and full duplex, without weakening TLS or auth.
2. Wire bounded per-flow queues and packet demultiplexing into the Android and
   desktop TUN engines, with cancellation, flow expiry, memory limits, and at
   least 50 iterations for each new concurrency race. Unsupported UDP must not
   escape through an unprotected direct socket.
3. Expand synthetic proxy tests to cover the packet engines, malformed capsules,
   fragmentation, stop/reset, DNS, UDP replies, loss and backpressure. Then test
   the actual production relay with user-authorized credentials without
   publishing secrets.
4. Investigate CONNECT-IP independently. It carries IP packets and has distinct
   address, routing, and capsule requirements. A failed or unauthenticated probe
   is not evidence that a relay lacks the protocol, and a transient/auth error
   must not be cached as permanent lack of support.
5. Reassess Android/desktop physical-interface captures and kill-switch behavior
   before making any claim about device-wide leak protection.

Even a successful synthetic proxy test would not establish production relay
compatibility or satisfy the real-world Iran connectivity targets.
