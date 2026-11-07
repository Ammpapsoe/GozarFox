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

## Remaining limitations

- In-flight raw TCP/TLS connect calls are not centrally interruptible; a
  connection can finish after stop, although generation checks reject or close
  a late result. A direct socket connect already in progress can itself reach
  the network before the post-connect validity check. This is not a kill switch.
- Overlapping service-instance teardown and restart, flows admitted while the
  same TUN stack is being reconnected, DNS workers, and all error paths require
  additional deterministic integration tests. Lifecycle checks can prevent
  stale use without proving that no network packet was emitted.
- Emulated and synthetic tests do not replace Android physical-interface
  captures, real-device always-on/lockdown tests, or authorized relay testing.
  No device-wide leak-freedom claim is justified.
