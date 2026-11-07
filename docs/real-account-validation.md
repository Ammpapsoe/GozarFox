# Real-account validation — incomplete

On 2026-11-07, an explicitly authorized Mozilla test account was used from a
GitHub-hosted Linux runner against the Gozar desktop authentication code. The
account password and a new emailed verification code were accepted by the
sign-in and session-verification calls. The subsequent OAuth token request did
not complete: the Mozilla OAuth endpoint returned HTTP 406, and the client did
not complete its security-check handling. In a diagnostic build, a retry with
`Accept-Encoding: identity` also encountered HTTP 406. CI run
42402949693 records the sanitized outcome. This is not a successful end-to-end
sign-in or a test of a production relay.

An earlier real-account run, 42401549245, also accepted an email code and then
failed during OAuth; its original diagnostics did not record the HTTP status.
An unauthenticated comparison on another runner returned HTTP 401 for both gzip
and identity variants (run 42401992344). Later synthetic-only comparisons on a
single runner (run 42404144300) returned HTTP 401 and Mozilla errno 110 for
Python's unauthenticated HTTP/1.1 request, but HTTP 406 for Go over HTTP/2 both
without authorization and with a made-up Hawk session. `curl` over HTTP/1.1
also returned 406, while forced HTTP/1.1 through Go ended in EOF. Changing
compression, JSON formatting, or the Go user-agent did not remove those 406s.
Python with a fabricated, properly shaped Hawk header also returned 401/110
(run 42404644659). A JVM `HttpURLConnection` comparison on a runner returned
406 with no authorization and 401 with a malformed, fabricated Hawk header
(run 42405253153); this is not an Android-device test. These differing
observations do not identify the cause of the real account's
406, and the fake-session result must not be presented as account validation.
They do not establish a general Mozilla outage or failure on all devices.
Public OAuth client metadata lists the
current Firefox client redirect as `urn:ietf:wg:oauth:2.0:oob:oauth-redirect-webchannel`;
that does not establish an app- or loopback-compatible redirect. A browser
flow needs an appropriately registered client and redirect.

## Verified-session retry (synthetic Android app tests)

Run 42404592144 reproduced a separate app-state defect with an injected fake
provider: after an email code was successfully verified and OAuth failed, a
retry submitted that consumed code again. Two overlapping calls could also
verify the same session concurrently. The candidate serializes login-operation
bodies, keeps an already verified login session for retry, and offers a
retry action without asking for the code again. Run 42405147616 passed seven
fake-provider tests, including 50 overlapping submissions and a Robolectric UI
check. This does not establish that OAuth succeeds, that retry resolves HTTP
406, or that Android devices were tested with the real account. The separate
signup/link and account logout lifecycles require additional review.

The probe encrypted input to the ephemeral CI job, did not upload passwords,
verification codes, account identifiers or tokens as artifacts, and removed its
temporary repository secret. The completed jobs' logs were checked in memory
for the account identifier, password and their respective codes without printing
those values. The user should review active Mozilla sessions and rotate the test
password after finishing because it was shared in chat.

Remaining work:

- Investigate the rejected Mozilla security check through supported user-driven
  authentication; do not assume that changing compression resolves it.
- Verify account entitlement without silently enrolling or purchasing anything,
  then test a real relay if access is available and explicitly authorized.
- Exercise the actual Android UI, updater, VPN service, reconnects and routing
  on appropriate emulators and physical devices. The real-account probe used
  desktop authentication plus shared Android core code; it did not complete
  the relay portion or test a physical device.
- Never claim device-wide leak freedom or publish a stable release on the basis
  of this incomplete result.
