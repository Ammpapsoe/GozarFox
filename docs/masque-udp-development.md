# Phase 7: UDP-over-HTTP development (not enabled)

The current application handles DNS over its existing tunnel, but other UDP is
still refused. The new Kotlin and Go code in this development branch provides
**capsule framing only**; it is not wired into a network connection or packet
stack and does not change the published release. Do not present it as working
UDP, CONNECT-UDP, CONNECT-IP, WebRTC, HTTP/3, or a full IP VPN.

The codec implements a bounded subset of the DATAGRAM Capsule Protocol from
[RFC 9297](https://www.rfc-editor.org/rfc/rfc9297.html) and context-zero UDP
payload encoding from [RFC 9298](https://www.rfc-editor.org/rfc/rfc9298.html).
A clean end between capsules is distinct from an empty UDP datagram. Unknown
types and context IDs are skipped, non-minimal QUIC integer encodings are
accepted, truncated inputs fail, and resource use is bounded. Capsule values
larger than 1 MiB are rejected as a local resource policy; UDP payloads above
65,527 bytes are protocol errors. Stream decoding is incremental and consumes
unknown values without allocating them in full.

## Remaining work before any routing is enabled

1. Implement authenticated HTTP/2 extended CONNECT with an explicit UDP target
   URI template. Wait for `SETTINGS_ENABLE_CONNECT_PROTOCOL` before sending
   `:protocol=connect-udp`, and verify a conforming response before reading or
   writing capsules. Preserve the normal certificate and authorization checks.
2. Wire bounded per-flow queues and packet demultiplexing into the Android and
   desktop TUN engines, with cancellation, flow expiry, memory limits, and at
   least 50 iterations for each new concurrency race. Unsupported UDP must not
   escape through an unprotected direct socket.
3. Exercise real packets against a local authenticated synthetic MASQUE proxy
   in GitHub Actions, including fragmentation, malformed capsules, stop/reset,
   rejection, DNS, UDP replies, and loss/backpressure. Then test the actual
   production relay with user-authorized credentials without publishing secrets.
4. Investigate CONNECT-IP independently. It carries IP packets and has distinct
   address, routing, and capsule requirements. A failed or unauthenticated probe
   is not evidence that a relay lacks the protocol, and a transient/auth error
   must not be cached as permanent lack of support.
5. Reassess Android/desktop physical-interface captures and kill-switch behavior
   before making any claim about device-wide leak protection.

Even a successful synthetic proxy test would not establish production relay
compatibility or satisfy the real-world Iran connectivity targets.
