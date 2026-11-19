# FakeDNS identity safety: activation blocker

Status: **not enabled in the desktop application**. The exported production
starter returns `ErrFakeDNSUnsafeNamespace` before provisioning state or
changing the network. `startUDPManaged` is used only for isolated tests.

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
using the wrong identity. This intentionally passing test documents the
problem, not its resolution. `TestFakeDNSProductionEntryPointIsFailClosed`
verifies that the public production starter refuses activation.

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

## Activation criteria

Before removing the gate, document a design and test evidence that preserve
identity across application caches, Gozar restarts and accounts, and foreign
namespace collisions, or constrain the supported environment in a way that
actually enforces those properties. The test suite must cover the explicit
TCP/UDP collision scenario, non-cooperating users, crashes and recovery, and
real platform routing. Explicitly assess persistent-history privacy and the
failure modes of removing or restoring storage. A user-visible warning or
probabilistic mitigation alone is not proof of this invariant.

Synthetic DNS being off does not itself prove the existing ordinary TUN DNS
path has no leaks, that all routing conflicts are handled, or that WAN and real
Mozilla connectivity work. Those require separate validation.
