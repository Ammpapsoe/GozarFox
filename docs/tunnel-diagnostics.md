# Android tunnel diagnostics: scope and limitations

The integrated diagnostic checks a positive DNS name and a randomized `.invalid`
canary through Gozar's **forced-tunnel DoH resolver**, and observes the IP
returned by a tunnel-only exit probe. The result can be `OK`, `ISSUE` or
`UNKNOWN`. An unexpected answer to the canary or a non-public positive answer
is an issue; an unavailable resolver, timeout or unverified exit is unknown.
NXDOMAIN and a lookup failure are not equivalent. A truncated, malformed or
contradictory DNS reply is unknown; a positive A answer must belong to the
queried name or its bounded CNAME chain. DNS message names are decoded with
bounded, backwards-only compression as described by RFC 1035. Counters record
IPv6 or malformed packets dropped by this VPN stack, but are not a device-wide
audit.

These observations **do not certify leak freedom**: they do not monitor the
physical interface, all other UIDs, OS DNS, browser/WebRTC behavior, bypassed
or split-tunnel apps, root routing, or packets sent before or after a VPN
session. Android's Always-on status is not proof of lockdown, and enabling
lockdown does not by itself prove that every physical-interface packet is
blocked. UI text must retain these distinctions. A DNS `OK` is not a WebRTC
or device-wide `OK`.

A report belongs to one live VPN generation. Disconnect, reconnect or a newer
probe invalidates older observations; the serialized diagnostic gate rejects a
late result. The local 50-race gate stress and GitHub Actions unit tests exercise
this synchronization, not the completeness of the leak model.

A realistic acceptance test still requires controlled capture on the physical
interface, representative Android versions and network changes, browser/WebRTC
traffic, every configured split/bypass mode, and explicit expected outcomes for
lockdown. Failures and inconclusive results must not be labeled safe. No
zero-leak guarantee is justified by the current implementation.
