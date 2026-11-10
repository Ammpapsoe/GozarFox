# Guardian bootstrap relays

## Scope

The relay system is limited to Mozilla control-plane requests that can fail with HTTP 451. It obtains or activates a Guardian pass and retrieves Mozilla's public server list. It is not a general HTTP proxy, does not accept a destination from the client, and does not carry VPN traffic. After pass acquisition, the Mozilla tunnel uses the existing direct relay path.

Three Workers are currently published:

- `https://gozar-guardian-a.gozarfox7.workers.dev`
- `https://gozar-guardian-b.gozarfox7.workers.dev`
- `https://gozar-guardian-c.gozarfox7.workers.dev`

They belong to the same Cloudflare account. They provide endpoint and deployment fallback, not independent providers, jurisdictions, or guaranteed egress diversity. Mozilla can return 451 for all of them.

## Fixed request contract

| Application path | Method | Fixed upstream |
| --- | --- | --- |
| `/v1/guardian/pass` | GET, HEAD | `https://vpn.mozilla.org/api/v1/fpn/token` |
| `/v1/guardian/activate` | POST | `https://vpn.mozilla.org/api/v1/fpn/activate` |
| `/v1/mozilla/servers` | GET | Mozilla's exact public VPN server-list URL |
| `/health` | GET | No upstream request |

Other paths, methods, query strings, arbitrary URLs, IP destinations, and redirects are rejected. Activation requires a small JSON body and the caller's Mozilla authorization. The relay itself has no password, pass, account, or activation prerequisite.

The Worker forwards only the required Authorization and JSON headers plus a fixed service-side Firefox-compatible User-Agent required to avoid Mozilla edge error 1010. It never forwards the user's User-Agent, Referer, Cookie, forwarding headers, or other client identifiers to Mozilla. Responses are streamed and limited to the required content and quota headers. Set-Cookie is not returned to the application.

## Signed endpoint discovery

`packaging/relay-endpoints.json` contains the schema version and at most three HTTPS Worker URLs. `packaging/relay-endpoints.json.sig` is a detached Ed25519 signature over the exact JSON bytes with the domain-separated message prefix `GOZAR-RELAY-ENDPOINTS-V1\n`.

The signature is produced by `.github/workflows/sign-endpoints.yml` using `GOZAR_LIST_KEY`. The private key is not stored in the repository or application. Android and desktop contain only the pinned public key. The signing script rejects a different private key.

At startup, each application retrieves the JSON and signature from the `main` branch, verifies the signature before parsing, and applies only a strict schema. Worker URLs must use HTTPS, have no credentials, port, query, fragment, or non-root path, and belong to the expected `gozarfox7.workers.dev` account. Invalid, missing, oversized, unknown, duplicated, or tampered values cannot replace the previous verified list.

A verified list is cached for at most 24 hours using the existing encrypted application storage. An expired or malformed cache is ignored. Network or verification failure retains the previous verified in-memory list; otherwise the built-in list is used. A JSON/signature update observed between the two downloads fails verification instead of being partially trusted. The current schema authenticates bytes but does not provide a monotonic anti-rollback counter.

## Fallback behavior

The normal direct Guardian request runs first. HTTP 451 activates relay fallback. Each relay is tried in signed list order. Transport failures, HTTP 408, 425, 429, 451, and 5xx move to the next relay. Other API errors retain their Mozilla meaning instead of being hidden by endless retries.

For a new or unactivated account, a 403 pass response can be followed by the exact activation request and one pass retry through the same relay. This does not require an existing Mozilla pass. Renewal paths that forbid activation retain that restriction. `PassTokenThrough` also has relay fallback after 451 and never silently returns to an unscoped direct socket.

The public server list uses the same fallback mechanism and never sends account authorization to the settings origin. The existing encrypted 24-hour server-list cache remains a separate bootstrap layer.

## Application-side TLS verification

Relay connections require all of the following:

1. HTTPS with the normal platform CA chain and exact hostname verification.
2. TLS 1.2 or newer.
3. A SHA-256 match between the leaf certificate's SubjectPublicKeyInfo and the application's pinned SPKI set.
4. No HTTP request or Bearer credential is sent when any check fails.

The current and previously observed pins are shared by Kotlin and Go. `.github/workflows/worker-spki-watch.yml` checks all three live endpoints monthly and creates a reviewed pull request when it observes a new key. Existing observed pins are retained. No fabricated future backup key is used: Cloudflare's future private key cannot be predicted.

The initial managed certificate was observed with a Let's Encrypt issuer and an expiry of 2027-02-08. That date is not a rotation guarantee. Cloudflare can rotate a managed `workers.dev` certificate without overlap, and monthly observation cannot guarantee continuity for old installations. A release must compare the live SPKI with the pinned set immediately before publication. Users with no matching pin fail closed until an application update contains a matching key.

Pinning authenticates the TLS endpoint and reduces MITM and fake-relay risk. It does **not** protect credentials if the legitimate Worker or its Cloudflare account is compromised. The Worker terminates the client's TLS connection and can read the Mozilla Authorization header and returned pass. No end-to-end TLS-through-CONNECT claim is made.

## Cloudflare controls and limitations

- The application has no global mutable request store, database, KV, Durable Object, R2, cache, or disk writes.
- Worker Observability, invocation logs, and traces are disabled in `wrangler.jsonc`. Logpush is disabled and no Tail consumers are configured. The Worker source contains no console logging.
- Request data necessarily exists transiently in the Cloudflare runtime while being processed. Physical memory erasure, Cloudflare-internal processing, and provider metadata retention cannot be guaranteed by application code. “Stateless” here means no application-level persistence or reuse between requests.
- The managed Workers Rate Limiting binding is configured for 5 requests per 60 seconds per client IP. Cloudflare documents this binding as approximate, eventually consistent, and separately counted per Cloudflare location. It is not a strict global five-request guarantee and bursts can exceed the threshold.
- This account has no Cloudflare Zone. Zone-scoped WAF rules, Bot Fight Mode, Security Level, and Under Attack Mode cannot be applied to these `workers.dev` endpoints. No claim is made that those settings are active.
- Cloudflare and Mozilla necessarily process network metadata required to provide their services. Disabling application logs does not mean that either provider has no internal telemetry.
- Health checks do not contact Mozilla and do not prove that a real account can obtain a pass.

## Validation and release gates

### Current live blocker — 2026-11-10

All three health endpoints returned HTTP 200. Direct requests from the diagnostic environment returned HTTP 200 for the public server list and HTTP 401 for a deliberately invalid Guardian credential. The same upstream requests through all three Workers returned HTTP 403 with Cloudflare error 1010. A fixed service-side browser-compatible User-Agent did not remove that response. An isolated raw-socket experiment also returned 1010, and a temporary end-to-end WebSocket probe did not complete its TLS handshake; the probe was deleted.

This means the relay is **not currently validated for real login and must not be released as stable**. Health success is not upstream success. No real Mozilla credential was sent into this known-failing path, and no claim of successful uncensored login is made. The source remains a release candidate while this provider-edge blocker is unresolved.

Ordinary CI tests fixed-route policy, header stripping, activation, rate-limit behavior, signature acceptance and rejection, tampering, cache expiry, multi-relay ordering, 451 fallback, activation after 403, server-list fallback, CA verification, SPKI mismatch, and failure before credentials reach an HTTP handler.

`.github/workflows/live-relay-login.yml` is separately dispatched with explicit authorization. It uses a temporary `GOZAR_LIVE_INPUT` secret and never uploads credentials or tokens. Its real relay-only pass request distinguishes live relay success from a direct-path success. Synthetic 451 tests establish switching behavior, not proof of geographic reachability on every censored network.

A stable release requires the exact-commit CI and signed builds, a current live SPKI check, and a successful authorized real-account relay login. A live core test is still not a physical Android/desktop UI test, a leak-freedom proof, or a guarantee for every ISP. If these gates are incomplete, the work remains a release candidate and the limitation must be stated in the release notes.

## Operations

Deploy from `relay/worker` with a short-lived API token supplied through the environment:

```bash
npx wrangler deploy --config wrangler.jsonc --name gozar-guardian-a
npx wrangler deploy --config wrangler.jsonc --name gozar-guardian-b
npx wrangler deploy --config wrangler.jsonc --name gozar-guardian-c
```

Never place Cloudflare tokens, Mozilla credentials, private signing keys, or live-account input in source control, workflow logs, release assets, or chat. Rotate credentials that were previously exposed and remove temporary GitHub secrets after the authorized run.
