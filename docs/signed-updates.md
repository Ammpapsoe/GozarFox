# Signed updates and rollback

## Threat model

An attacker who can MITM or block `api.github.com` (routine on filtered
networks) must not be able to offer a device a modified build. The GitHub
release alone is therefore not trusted: every update is described by an
`update.json` manifest with a detached Ed25519 signature
(`update.json.sig`), checked against a public key pinned in
`FoxUpdate.KEY_HEX` (core). No signature, no verification, no install
offer — fail-closed.

## Rules enforced by `FoxUpdate.verify` (core)

1. Manifest ≤ 64 KiB, strict base64 signature, RFC 8032 Ed25519 check
   against the pinned key, done **before** anything else is trusted.
2. Schema: `version`, `channel_min`, `rollback`, `issued_at`, `notes`,
   `artifacts[]` with `name`, `sha256` (64 hex), `size`, `url`.
3. Artifact URLs must be https release-download URLs of the project's own
   GitHub repositories.
4. `issued_at` may not be more than a day in the future.
5. Normal updates must be strictly newer than the installed version
   (no downgrades). Rollback manifests must target a strictly older
   version that is still at or above `channel_min`.

Downloaded bytes are re-checked against the signed `sha256` and exact
`size` before the installer is invoked (`UpdateInstaller`).

## Rollback

`FoxUpdateJournal` makes every update a trial: `recordUpdate` on install,
`beginLaunch` on each start, `markHealthy` when the tunnel is proven up
(`FoxLink`). Three consecutive launches without a healthy tunnel mark the
build failed; on the next start the app offers the previous version — as a
one-tap reinstall when the previous APK was cached, otherwise via the
release page. A corrupt journal resets; it never throws.

## Release procedure (Android)

1. Bump `versionName`/`versionCode`, merge to `main`, tag `vX.Y.Z`.
   CI (`build.yml`) builds, signature-verifies, and uploads the APKs.
2. Download the `android` artifact, then:
   `GOZAR_RELEASE_KEY=/secure/path/release-private.pem tools/sign-update-manifest.sh X.Y.Z <channel_min> <apk-dir> "changelog"`
3. Publish a GitHub release with the APKs, `update.json`, and
   `update.json.sig` as assets.

The release private key is kept outside the repository; the signing tool
refuses to run when the key does not match the pinned public key.
Key rotation = ship a client release that pins the new key, then start
signing with it (old clients verify old-key manifests until they update).
