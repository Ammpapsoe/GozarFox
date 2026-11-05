# Gozar v1.10.4-desktop — deep analysis on top of v1.10.3-desktop

Base: `v1.10.3-desktop` (`04ea9d7`) plus the distribution packaging already published for that release (`d985c34`).
Unrelated tags `v1.10.4`, `v1.10.5`, `v1.10.6` are not ancestors of this line and are not reused or rewritten.

## Evidence collected before changes

- `go test ./...` and `go vet ./...` pass on the base commit.
- Desktop gVisor uses `tcp.NewProtocol`, which defaults to Reno. SACK/RACK and receive-buffer moderation are enabled by default, but the congestion controller is not CUBIC and RTO bounds are the stack defaults (200ms..120s).
- Desktop proxy sockets are dialed with `net.DialTimeout`, which enables the Go default TCP keep-alive (15s). Idle proxy connections can therefore emit keep-alive probes even when the user has no traffic.
- Desktop server selection is static: configured server, then recommended, then first. There is no pre-connect latency/jitter probe and no silent failover between servers.
- Android `FoxStack` advertises a fixed 64240-byte receive window and uses a fixed 1400-byte MTU regardless of Wi-Fi/cellular. It does not negotiate window scaling.
- Android `FoxTunnel` uses the platform default socket keep-alive (off) and only answers HTTP/2 PING; it does not send periodic PING. The renewal thread is the only background control-plane traffic.
- Android server selection is also static unless the user opens the server sheet and triggers `Fox.measure`.
- OpenWrt/Raspberry Pi/FreeBSD ship the same headless Go core, so desktop core fixes apply there; packaging scripts cross-compile the core.

## Priority fixes (incremental, no rewrite)

1. Desktop TCP hardening: CUBIC, explicit SACK/RACK, receive-buffer moderation, bounded RTO, disable Nagle on the userspace stack.
2. Idle data reduction: disable OS TCP keep-alive on desktop proxy sockets; keep HTTP/2 and QUIC free of periodic keep-alive/PING.
3. Zero-config path selection: silent pre-connect latency/jitter ranking on desktop and Android, with bounded fallback. Manual server choice remains respected.
4. Android dynamic MTU/MSS: Wi-Fi/Ethernet 1400, cellular 1280, passed into `FoxStack` and the VpnService builder.
5. Proof-of-work tests: 10 MiB profiling, idle byte gate, loss/retransmit gate, failover, selector unit tests, CI matrix.
6. Release engineering: bump all version declarations to 1.10.4, publish as `v1.10.4-desktop`.

## Honest limits

- Real Firefox/Mozilla relay credentials are not available in CI; relay tests use TLS-verified local mock proxies.
- Android emulator/RIL and OpenWrt hardware are not available in this environment; Android logic is covered by JVM/Robolectric tests and OpenWrt by cross-compilation/packaging.
- Desktop kill switch is not implemented in this release line; Android relies on Always-on/lockdown. This is not claimed as a new desktop kill switch.
