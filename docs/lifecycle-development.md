# Android transport lifecycle work (development branch)

This is not a published fix. A controlled loopback test demonstrated that an
already-established HTTP/1 tunnel could still accept writes after the existing
`FoxTunnel.resetTransport()`; it failed at iteration zero on the earlier code.
That test does not prove a device-wide leak. The public 1.11.2 release warning
has been updated without changing its published tag or binaries.

The development code tracks established HTTP/1 duplexes and aborts them on an
explicit transport reset. An abort drops buffered unsent bytes, closes the
underlying socket, and causes later writes to fail; a concurrent graceful close
cannot hide the socket from reset. Existing HTTP/2 session reset and in-flight
generation checks are retained. A transport ticket and stop/start API rejects
stale tickets and new opens while stopped.

The proposed app integration additionally:

- Serializes route application and transport startup with `FoxLink`'s attempt
  generation, so an old setup worker cannot reset a newer attempt.
- Stops transport on `FoxLink.stop()` and on current-attempt startup failure;
  startup verification, exit probing, and geo refresh retain their ticket.
- Binds proxy listeners to their owning attempt before delayed credentials or
  direct lookup; invalidated listeners cannot reuse a newer attempt's transport.
- Captures a TUN flow's scope before dispatching its dial worker, and invalidates
  a service stack's lifetime during release. Direct pipes also check the attempt
  and platform routing policy before forwarding.

The initial core-only commit passed its normal GitHub Actions test workflow and
three new local 50-iteration stresses: established H1 reset, concurrent H1
close/reset, and stale ticket after restart. The integration adds local 50-race
checks for stale setup, atomic setup/stop ordering, and delayed TUN dial scope,
an app-level 50-race direct-lookup test, and 50 sequential stop transitions in
CI. Results must be checked on that later commit before describing the
integration as verified.

## Same-interface retry (candidate change)

The in-place always-on retry retains the `VpnService` descriptor, its
`FoxResolver`, and, on Android 10+, the HTTP proxy address. Previously,
`FoxLink.stop()` closed its ephemeral-port listeners and the next start could
create listeners on different ports while that same interface still held the
old ports. This is a code-path finding; it has not been reproduced on a real
Android device.

The candidate keeps all three original loopback listeners reserved while that
interface exists. During a stop or failed attempt they are paused, reject new
clients, and close tracked clients and pipes; after successful verification the
same ports are resumed. Once the interface is released, the listeners are
closed. A listener-local epoch prevents work accepted before pause from being
resumed under the new attempt, including a delayed direct lookup or credential
renewal. Tests cover two sets of 50 pause/resume races, paused-listener
rejection, and 50 stop/prepare transitions with a simulated interface. GitHub
Actions Test 42392158846 and the installer-emulator workflow 42392164985 passed
on commit 38cc5f6. These do not establish a successful real always-on reconnect.

## Overlapping service instances (candidate change)

A Robolectric regression created a replacement service before destroying the
old instance. On the previous code, the old `onDestroy()` marked the process
service as not alive; GitHub Actions run 42392530748 failed that assertion on
the test-only commit. The same unconditional teardown also called the global
transport stop and closed the global direct-pipe registry. The test models
service objects and descriptors; it does not establish that Android creates
this interleaving on a particular device.

The candidate serializes global ownership changes and teardown. A stale
instance releases its own stack, root session and descriptor without stopping
the replacement's transport or closing its direct pipes; current-instance
teardown still stops global resources. A 50-transition Robolectric regression
checks liveness, the current transport, ports, direct-pipe ownership, and stale
error reporting. GitHub Actions Test 42393238388 and installer-emulator Test
42393245449 passed on commit b418668; neither is a real service-overlap test.

## Closed TCP DNS flow (candidate change)

A gated local regression found that `FoxDnsPipe` could publish a learned DNS
answer after its pipe had been closed; the test failed at iteration zero on
the earlier code. This is a stale name-to-address update, not evidence of a
packet leak. The candidate checks closure and publishes the answer under the
same lock. Its targeted 50-iteration local stress passed.

## DNS attempt scoping (candidate change)

The same VPN interface retains its resolver across an in-place retry. DNS
packets and TCP DNS flows now capture a service-lifetime/transport scope at
admission; the resolver separates single-flight and cache entries by that
scope. A stale scope is checked before a fallback starts and before a finished
answer is used; the next attempt does not wait on the old attempt's DNS
flight. Proxy direct lookups and diagnostic DNS queries carry their expected
transport ticket too. Local 50-iteration stresses cover a blocked primary and
fallback, independent old/new flights, a stale TCP DNS pipe, and a synthetic
TUN UDP query followed by a successful replacement-attempt query.

These are best-effort validity checks, not an atomic network kill switch: a
raw lookup or TCP/TLS operation already past a check can still race a stop,
and a blocking request may continue until its timeout. GitHub Actions and
real reconnect scenarios must still be checked.

## Direct socket cancellation (candidate change)

Android's proxy and TUN direct-dial paths now register each direct TCP socket
before connecting. Starting a new transport attempt or stopping the current
one closes the registered pending and established sockets; a late dial cannot
be handed off under a new ticket. Two targeted 50-iteration local stresses use
controlled sockets to cover cancellation during connect, a late return after a
new attempt starts, and writes to a revoked established pipe. An app-level
50-transition loopback test checks the `FoxLink.stop()` integration in CI.
These tests establish that cancellation is requested and late pipes are
rejected; they cannot retract SYNs or other packets sent before a close, and
they are not a physical-device kill-switch test.

## Relay dial cleanup and cancellation (candidate change)

A controlled local test found that the raw relay socket was not explicitly
closed when initial setup or `connect()` failed; it failed at iteration zero on
the previous code. The candidate closes it while retaining the original
exception type and adaptive fallback behavior. A 50-transition stress passed
with and without an HTTP proxy hop. This is a resource cleanup finding, not
proof of traffic leakage.

Additional loopback tests held a proxy CONNECT reply, a TLS handshake, and an
H1 CONNECT response open. A reset did not close the pending connection before
release on the earlier code (iteration zero). The candidate registers the raw
relay socket before network setup, closes it on reset, and transfers ownership
under the same lock to the established H1 registry or H2 pool. Three targeted
50-race tests passed, as did 50-transition H1/H2 handoff checks and prior H1/H2
reset stresses. These are JVM/loopback observations; GitHub Actions and Android
physical-device behavior still need verification. Closing a socket cannot take
back packets already sent and does not establish device-wide traffic safety.

## Established H2 stream revocation (candidate change)

A controlled 50-race loopback regression found that an established H2 duplex
could accept a small buffered write after its session had been reset; it failed
at iteration zero on the prior code. The observation does not establish that
the buffered bytes left the device. The candidate tracks H2 duplexes alongside
H1, closes the old sessions, and aborts every tracked duplex to discard queued
bytes and reject subsequent writes. Local 50-race checks passed for post-reset
writes, concurrent close/reset of two streams, and successful handoff/cleanup.
Full GitHub Actions and real-device/real-relay behavior still require testing.

## Remaining limitations

- OS DNS resolution and other network work may not be promptly interruptible;
  cancellation races and socket-close behavior vary by platform. A direct or
  relay socket can emit traffic before being closed. This is not a kill switch.
- Real overlapping service teardown/restart, root-session overlap, flows admitted
  while the same TUN stack is being reconnected, DNS workers, and all error paths
  require additional deterministic integration and device tests. Lifecycle checks can prevent
  stale use without proving that no network packet was emitted.
- Emulated and synthetic tests do not replace Android physical-interface
  captures, real-device always-on/lockdown tests, or authorized relay testing.
  No device-wide leak-freedom claim is justified.
