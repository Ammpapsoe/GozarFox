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
and identity variants (run 42401992344). That comparison cannot establish the
cause of the authenticated 406. The observed error may depend on the runner,
network, headers, or Mozilla's security checks; it is not evidence of a general
Mozilla outage or of all devices failing. Public OAuth client metadata lists the
current Firefox client redirect as `urn:ietf:wg:oauth:2.0:oob:oauth-redirect-webchannel`;
that does not establish an app- or loopback-compatible redirect. A browser
flow needs an appropriately registered client and redirect.

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
