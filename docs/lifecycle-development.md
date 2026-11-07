# Android transport lifecycle work (development branch)

This is not a published fix. An isolated loopback test demonstrated that an
already-established HTTP/1 tunnel could still accept writes after the existing
`FoxTunnel.resetTransport()`; it failed at iteration zero on the earlier code.
That test does not prove device-wide leakage.

The branch tracks established HTTP/1 duplexes and aborts them on an explicit
transport reset. An abort drops buffered unsent bytes, closes the underlying
socket, and causes subsequent writes to fail; a concurrent graceful close can
no longer hide the socket from the reset. Existing HTTP/2 session reset and
in-flight generation checks are retained. A new opt-in transport ticket and
stop/start API rejects stale tickets and new opens while stopped.

This first commit does **not** yet wire `stopTransport()` or tickets into the
Android service, `FoxLink`, proxy handlers, TUN flow scheduling, or background
workers. A stale task may therefore still begin a new unticketed open after a
later start. Do not describe app disconnect as fixed, or infer device-wide leak
protection, until integration and cancellation paths are independently tested.

The development stress suite includes 50 iterations each for established H1
reset, concurrent H1 close/reset, and stale ticket after restart, plus the
existing pending H1/H2/reset and prewarm/reset races. GitHub Actions must pass
on the actual commit in addition to those permitted local stress cases.
