# Real-account validation — transport verified, app validation incomplete

## Later authorized test

A later local, explicitly authorized test successfully authenticated the same
Firefox account against Mozilla's alternate origin, refreshed the OAuth token,
obtained the existing proxy pass and server list, and reached a target through
an authenticated Mozilla relay. A separate free WARP test identity was used
with the user's explicit consent. The Python script completed UDP through the
Mozilla → WARP chain. CI-built Kotlin and Go diagnostics from commit
`aa81e51` used the actual WARP cores through a restricted loopback-to-Mozilla
bridge and each obtained a real UDP response. Kotlin also re-enrolled the same
test device through the relay. A separate diagnostic using the actual shared
Android account core on a desktop JVM successfully signed in, exchanged and
refreshed OAuth tokens, and read the server list. Its direct proxy-pass request
received HTTP 406; an isolated Python client using the very same access token
obtained HTTP 200. A Java HTTPS retry restricted to TLS 1.2 also received HTTP
200 with that same token, while changing common HTTP headers did not help. The
candidate now retries only a 406 on Mozilla's exact read-only proxy-pass
endpoint with TLS 1.2, retaining ordinary certificate and hostname checks.
This desktop JVM is not Android; the retry and full app still require Android
device validation. A separate real-account desktop Go diagnostic signed in but
the OAuth exchange encountered a Mozilla security-check failure; a narrowly
scoped TLS 1.2 retry is being evaluated for that endpoint and the read-only pass
request. Passwords, codes, tokens and device keys were not placed in CI artifacts. These results do
not test the full Android VPN service, a physical phone or the desktop app end to end.

An earlier test device's temporary credentials were lost during an environment
reset; its remote deletion could not be confirmed. A replacement test identity
was registered with renewed consent and must be deleted after testing.

## Earlier 406 investigation

In November 2026, an explicitly authorized Mozilla test account was used from a
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
check. Another fake-provider test reproduced login completion restoring an
account after logout (run 42408330942). The candidate uses an authentication
epoch to revoke late login results before transport teardown; run 42408787304
passed 17 tests, including logout during sign-in, verification, token and
pass acquisition and 50 controlled logout races. These checks do not cancel
requests already sent to the server. They do not establish that OAuth succeeds,
that retry resolves HTTP 406, or that Android devices were tested with the real
account. Signup/link flows and actual device lifecycles still require review.

The same-commit emulator run 42469488226 initially stopped before the emulator
started because direct downloads of old release APKs returned HTTP 504. The
candidate CI fixture now uses pinned GitHub release-asset API IDs and SHA-256
hashes, and still verifies the official signing certificate and package
versions. After the two older preview releases were removed, their original
byte-identical, signed universal APKs were recovered from prior Actions build
artifacts and preserved in the CI-only draft release
`ci-android-upgrade-fixtures`. The emulator job needs repository write
permission to read these draft assets; hashes and official signing identity are
still verified. Do not delete that draft unless its fixtures are preserved and
repinned first. This is a CI reliability change, not evidence about the app's
network behavior.

The probe encrypted input to the ephemeral CI job, did not upload passwords,
verification codes, account identifiers or tokens as artifacts, and removed its
temporary repository secret. The completed jobs' logs were checked in memory
for the account identifier, password and their respective codes without printing
those values. The user should review active Mozilla sessions and rotate the test
password after finishing because it was shared in chat.

Remaining work:

- Exercise the actual Android UI, updater, VPN service, reconnects and routing
  on appropriate emulators and physical devices, and validate the production
  desktop path. The later real-account transport success is not a full app test.
- Check exact-commit signed release builds and upgrade scenarios. Do not claim
  device-wide leak freedom or publish stable on incomplete validation.
