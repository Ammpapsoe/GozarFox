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
rejection, and 50 stop/prepare transitions with a simulated interface. The results of GitHub Actions on the
commit containing these changes must be checked before reporting them as
passing, and they do not establish a successful real always-on reconnect.

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
error reporting. Check the corresponding GitHub Actions run before treating
this candidate as verified.

## Closed TCP DNS flow (candidate change)

A gated local regression found that `FoxDnsPipe` could publish a learned DNS
answer after its pipe had been closed; the test failed at iteration zero on
the earlier code. This is a stale name-to-address update, not evidence of a
packet leak. The candidate checks closure and publishes the answer under the
same lock. Its targeted 50-iteration local stress passed. GitHub Actions and
real reconnect scenarios must still be checked.

## Remaining limitations

- In-flight raw TCP/TLS connect calls are not centrally interruptible; a
  connection can finish after stop, although generation checks reject or close
  a late result. A direct socket connect already in progress can itself reach
  the network before the post-connect validity check. This is not a kill switch.
- Real overlapping service teardown/restart, root-session overlap, flows admitted
  while the same TUN stack is being reconnected, DNS workers, and all error paths
  require additional deterministic integration and device tests. Lifecycle checks can prevent
  stale use without proving that no network packet was emitted.
- Emulated and synthetic tests do not replace Android physical-interface
  captures, real-device always-on/lockdown tests, or authorized relay testing.
  No device-wide leak-freedom claim is justified.
