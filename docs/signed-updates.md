# Android signed updates and recovery

## Scope and threat model

An attacker who can alter or block GitHub responses must not be able to offer
modified APK bytes. Android verifies the exact `update.json` bytes against the
Ed25519 key pinned in `FoxUpdate.KEY_HEX` before treating an update as current
or available. A missing, malformed or invalid signature fails closed. The
platform package installer independently checks the APK signing certificate.
An attacker can still block updates and can replay an older, correctly signed
manifest; the format currently has no expiry or revocation mechanism.

On the recovery-hardening branch, the desktop core verifies a separate signed
`desktop-update.json` and can download a selected platform package after
checking its signed size and digest. The UI opens the downloaded file's folder;
it does not automatically install it or roll back the installation. This is
not yet a published, end-to-end-validated desktop recovery system. GPG
signatures for release files can also be verified separately.

## What the Android client verifies

* At most 64 KiB of manifest, strict base64, Ed25519 signature, field types,
  sensible timestamp, and safe version and artifact names.
* The signed artifact URL must be an exact HTTPS GitHub release-download URL
  in a project repository and must match the manifest version/tag and filename.
* The Android update must be newer than the running version. A signed manifest
  marked as an old-version rollback is not offered by the Android updater.
* APK bytes are streamed to a private temporary file with an exact signed
  size and SHA-256 limit. Only a verified file replaces the target. Failed or
  interrupted downloads are removed. The system installer is then opened;
  this handoff is **not** proof that the user accepted or that installation
  succeeded.

The core verifier retains its signed `rollback` schema for other potential
consumers, but that must not be confused with a working Android downgrade.
`channel_min` is a floor for such a core rollback verdict; it is not a data
migration guarantee or an enforcement of APK `versionCode` compatibility.

## Recovery on Android

Android's ordinary package installer does not normally allow an APK with a
lower `versionCode` to replace an installed one. The app therefore no longer
promises a one-tap reinstall of an older cached APK. Three launches without a
confirmed healthy tunnel generate a recommendation to check for a **newer
signed update**. An unconfirmed launch is not proof of a crash: the user may
not have connected or the network may be unavailable. The notification opens
a real signed-update check; if no newer update exists, it says so.

To recover a faulty published build without an uninstall, an operator can
prepare a *forward recovery* release:

1. Restore the known-good behavior in a new commit, while retaining necessary
   security fixes and migration code. Do not blindly revert persistent-data
   schemas or security fixes.
2. Give the recovery app a `versionName` newer than the affected version and
   a `versionCode` higher than **every** published affected APK. Sign the APK
   with the same official Android signing certificate. Version names alone do
   not prove that Android will accept a package.
3. Build and test all supported APK variants, upgrade and data migration from
   the affected versions, and a real installation on representative devices.
   Publish a normal (`rollback: false`) manifest signed with the pinned
   update key, and explain the recovery and any limitations in release notes.
4. The user must have network access and approve the platform installer; an OS
   policy, disabled install permission, incompatible data, or refusal can
   prevent recovery. This is not an automatic or fully proven rollback.

To label a forward recovery in its signed manifest, commit
`docs/recovery-plans/X.Y.Z.json` containing exactly `known_good_commit` and
`affected_commit`, both full lowercase commit hashes. The publication workflow
runs `tools/validate-forward-recovery.py` over full Git history. It checks
ancestry, application ID, increasing version names and increasing version
codes, then signs the resulting provenance into `update.json`. The client only
labels the update as targeted recovery when the installed affected version and
versionCode match. **Git ancestry and version ordering do not prove that the
known-good behavior was restored, that data migrated safely, or that every
published versionCode is below the recovery code.** Those require separate
review and testing before the draft release can be approved.

The optional Android emulator scenario builds three debug APKs with a deliberately
broken **synthetic** behavior probe in the middle one. An ephemeral Ed25519 test
key signs a fixture manifest; a debug-only activity uses the real verifier and
installer with a local fake transport. The scenario is designed to reject an
invalid signature and tampered APK, require the system installer's confirmation,
and check that the forward APK restores the probe while preserving a sentinel
file. It does not test the production key, live GitHub delivery, real faulty
production behavior, or arbitrary data migrations. The exported fixture activity
is injected only into the three test APKs, never a release APK.

The same optional workflow separately builds an officially signed release APK,
checks the certificates, and attempts emulator replace-installs over both the
published 1.10.4 stable APK and 1.11.1 preview APK. It checks the app launch and
package identity; an app-private sentinel is also checked when the emulator
exposes root ADB. This uses `adb`
rather than the interactive installer, covers one emulator/API level only, and
does not prove data migration or recovery from a faulty production build.

A full rollback feature, including data compatibility, delivery when the app
cannot start, user consent and desktop support, remains a separate incomplete
requirement. Do not claim completion from journal state or an installer intent.

## Release procedure and safe validation

`build.yml` verifies the official APK certificate and GPG-signs the artifacts.
The publication job uses `GOZAR_UPDATE_KEY` to generate the Android `update.json`
and desktop `desktop-update.json` with their detached `.sig` files, verifies
the Ed25519 signing key and signatures, includes them with the release assets,
and compares every published asset with the built file. The private key stays outside the repository. The tag must match
the Android version (`vX.Y.Z` or `vX.Y.Z-desktop`). The publication workflow
requires the Android, desktop UI and desktop core versions to match, so a new
desktop build is not mistakenly treated as an already-installed older version.

For a no-publication validation on a mission branch:

```sh
gh workflow run build.yml --ref <mission-branch> -f validate_release=true
```

This builds and signs the complete artifact set, checks the signatures and
uploads `release-validation-evidence`; it does **not** create a GitHub release
or a tag. Release notes live in `docs/release-notes/X.Y.Z.md` and must start
with exactly one channel marker: `<!-- DRAFT -->`, `<!-- PRERELEASE -->` or
`<!-- STABLE -->`. Draft notes block publication. A prerelease must describe
incomplete requirements and is not promoted to GitHub's latest stable release;
using its versionCode means a later Android release needs a higher one.

A tag build requires a successful Android install/recovery emulator workflow
for the **same commit**, creates a draft first, uploads and byte-compares the
complete asset set, and only then publishes it using the explicit channel.
Stable publication is appropriate only after the phase's acceptance is complete
and the reviewed branch is merged. The dry run and unit tests do not substitute
for real installation, migration or recovery tests.

Key rotation requires a client that already trusts the replacement key (or a
separate trusted update channel); simply switching the server key strands old
clients. A compromised signing key cannot currently be revoked remotely.

## Split-tunnel domain list (`packaging/ir-domains.txt`)

The desktop and Android clients fetch this list through the verified tunnel to
decide which destinations go direct. It is signed with a **separate** Ed25519
key, `GOZAR_LIST_KEY`, pinned as `ListKeyHex` (Go, `internal/geo/listsig.go`)
and `FoxGeo.LIST_KEY_HEX` (Android/core). The application update key
(`GOZAR_UPDATE_KEY`, `FoxUpdate.KEY_HEX`) is not used for this list.

* `packaging/ir-domains.txt.sig` is a detached signature: 128 lowercase hex
  characters (a 64-byte Ed25519 signature) over the exact bytes of
  `GOZAR-IR-DOMAINS-V1\n` followed by the list file.
* A client replaces its domain rules only when the list and its signature
  both download with HTTP 200 within size limits and the signature verifies.
  Any failure keeps the previously verified rules, and the built-in list is
  used at first start.
* `.github/workflows/sign-ir-domains.yml` signs on a push to `main` that changes
  the list, or on manual dispatch, refuses any key that is not the pinned list
  key, verifies the result with the Go verifier, and commits only the `.sig`
  file. Signing is not done on a developer machine.
* Scope and limits: the signature prevents tampering and substitution on the
  network path. It does not protect against a compromised repository key, and
  it does not prevent an older correctly signed list from being replayed. The
  list is a routing hint, not a credential, and the list key is separate from
  the update key so that compromise of one does not extend to the other.
