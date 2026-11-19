# FakeDNS identity safety: activation blocker

Status on the development branch: **Windows TUN uses a mitigated FakeDNS**,
subject to validation before release. This is not guaranteed safe across other
VPNs: applications may retain a foreign synthetic IP that aliases a known
Gozar mapping. Windows clears the system resolver cache before and after TUN
startup; this does not clear application/browser caches. The machine-wide
journal prevents reuse between cooperating Gozar sessions as long as its
history survives. Linux and other platforms continue using real DNS. Do not
advertise this as protection against all foreign cached identities.

## What was checked

The ArmanLife desktop FakeDNS source at tag `v1.0.19`,
`core/internal/tun/fakedns.go`, was reviewed without incorporating its GPL
code. It allocates from `198.18.0.0/15`, starting at `198.18.0.4`, keeps the
reverse map in memory, and reuses addresses after wrapping. A separate temporary
experiment ran that source with two newly constructed desktop FakeDNS objects:
a cached address for `first.example` was interpreted as
`different.example:443` after the second object allocated its first address.
This is a model of a client retaining an answer across a restart, not proof of
how often any particular browser or operating system retains one. Unknown
addresses are rejected, but the colliding address is *known* in the new map.

Our machine-wide journal avoids this particular reuse across cooperating
Gozar sessions as long as its complete history survives. It cannot protect
against another implementation allocating the same address independently, or
against complete coordinated deletion/rollback of its own persistent state.
`TestFakeDNSForeignCachedAddressCanAliasKnownIdentity` models a foreign cached
address colliding with a known Gozar allocation and shows both TCP and UDP
using the wrong identity. `TestFakeDNSForeignCollisionLiveKernelDemonstration`
also checks the same ambiguity through a genuine disposable OS TUN and local
TCP/UDP peers. It explicitly models a retained address: it does not prove an
OS, browser, or ArmanLife actually cached that address. These intentionally
passing demonstration tests document the problem, not its resolution.
`TestFakeDNSProductionEntryPointUnsupportedPlatforms` verifies that the
mitigated starter refuses activation on platforms other than Windows.

The prototype synthesizes only plain recursive A/AAAA questions. Queries with
EDNS (including DNSSEC DO and options), AD/CD signalling, unusual sections or
flags are forwarded unchanged to the existing resolver rather than silently
stripping protocol information. This is conservative compatibility, not
full EDNS support and not a fix for foreign-namespace collisions. TCP and UDP
client DNS may therefore receive real-IP answers for those queries.

## Why simple mitigations are insufficient

* A random offset reduces some collisions but does not prevent them.
* DNS TTL, flushing an OS DNS cache, or waiting cannot guarantee that every
  application or independent cache has discarded a prior answer.
* Rejecting unknown synthetic addresses does not reject a foreign address
  which happens to be a known local address.
* TLS SNI, HTTP Host, certificate verification, or protocol inspection do not
  reliably supply an identity for all TCP and UDP protocols; encrypted or
  absent names and clients that skip certificate verification remain.
* Choosing another IPv4 range does not prove it cannot be used by another
  resolver, VPN, LAN, or real service. Route-table inspection does not discover
  answers cached by applications, prior VPNs, or future route changes.
* A machine-local journal, lock, or marker protects cooperating instances, not
  unrelated VPNs. Restoring or deleting all correlated state can go undetected.

## Remaining safety criteria

The Windows branch is an explicitly risk-accepted mitigation, **not** a
solution to the foreign namespace collision. A future claim of identity
safety requires a design and evidence that preserve identity across
application caches, Gozar restarts and accounts, and foreign namespace
collisions, or a supported environment that actually enforces those
properties. Tests must cover TCP/UDP collision scenarios, non-cooperating
users, crashes and recovery, and real platform routing. Persistent history
contains visited domain names and is protected by machine storage ACLs, but
complete coordinated deletion/rollback is not detectable. A warning,
randomization, or cache flushing does not prove the stronger invariant.

Synthetic DNS being off does not itself prove the existing ordinary TUN DNS
path has no leaks, that all routing conflicts are handled, or that WAN and real
Mozilla connectivity work. Those require separate validation.
