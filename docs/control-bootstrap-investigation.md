# Account bootstrap and renewal: candidate verification

This branch is a candidate, **not a released or fully verified fix**. It does
not introduce public/shared relays or discover unknown proxy servers.

## Design

* An existing, encrypted, account-bound Mozilla proxy pass with an explicit
  expiry and at least 90 seconds remaining can be reused for initial relay
  verification. The Mozilla relay still authenticates the pass; the locally
  decoded JWT expiry is only a scheduling check, not signature validation.
* While the verified tunnel remains available, the proxy pass and, if needed,
  the OAuth access token are renewed through that tunnel. Renewal never
  activates an entitlement, follows a redirect, invokes a direct-socket
  challenge solver, or silently falls back to a direct request. Normal TLS
  certificate-chain and hostname verification remain enabled.
* A pass with sufficient lifetime is scheduled for renewal five minutes before
  expiry. If the remaining lifetime is too short to make that possible, the
  candidate uses roughly half the remaining lifetime with a minimum delay.
  A pass with an explicit expiry is retained on transient errors only until
  its expiry; an opaque legacy pass has only a conservative local deadline
  and remains subject to rejection by Mozilla.
* Android uses its existing encrypted app storage (Android Keystore where
  available, otherwise its existing app-private software-key fallback). Desktop
  uses the existing platform protection: Windows DPAPI or, on Unix-like
  systems, AES-GCM with a local private key file. A failure to encrypt a new
  desktop proxy pass does not write it in plaintext. A local attacker with the
  same user privileges may still access the Unix key and encrypted data.
* The Android control channel uses the existing loopback force-tunnel proxy.
  Its HTTP CONNECT listener is **not authenticated**: another local process
  may be able to use it and consume quota. Inner TLS protects the Mozilla
  credentials from the proxy itself. This needs further hardening before
  claiming isolation from other local applications.

## Important limits

* A pass is usable only until its actual expiry. Once the tunnel is closed,
  the application cannot renew through it; an expired or absent pass cannot
  bootstrap a blocked network. A first sign-in or a cold start without a valid
  pass still needs a trusted connection to Mozilla.
* The server list is not persisted by this candidate. A cold start can still
  fail if the Mozilla server-list endpoint is unavailable.
* If Mozilla returns HTTP 451 **inside** the tunnel, this candidate fails
  closed and retries; it does not yet implement an explicit fallback to a
  user-supplied local WARP/corporate proxy. Such a fallback must be opt-in,
  identified to the user, TLS-verified, and tested on the affected network.
* The changed signup origin is supported by controlled request probes and
  simulated tests, not by a completed new-account verification. A real
  GitHub diagnostic (run 42770989249) received HTTP 400 / errno 101 for an
  already-existing account; that is not a successful new signup and is not
  evidence that email verification or OAuth would work for a new account.
* Simulated 451 tests use local TLS endpoints and synthetic credentials. They
  are not a real geographic-block test, real relay renewal, physical-device
  test, or a production DNS/leak-freedom proof.

## Release gate

Do not publish v1.11.4 until a genuinely new user-controlled mailbox is used
for end-to-end signup and verification, and real pass/OAuth renewal is tested
through an actual Mozilla tunnel on the affected network. If an in-tunnel 451
occurs, implement and verify the explicit trusted-local-proxy fallback first.
Run the complete exact-commit CI, signed build and appropriate device checks
before any release. Never place mailbox passwords, one-time codes, access or
refresh tokens, or passes into logs, source control or public artifacts.
