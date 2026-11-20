# Qt desktop package preparation (not publication)

The workflow **Prepare Qt desktop packages (validation only)** runs on
`packaging/qt-preparation` or by dispatch. It does not run on a release tag,
modify `main`, require a signing secret, or publish an installer or archive.
The outputs persisted by Actions are package evidence only.

It builds the existing Flutter launcher with its existing opt-in Qt handoff,
the current Qt target, and the Go core from the exact same checkout. The staging scripts do not rewrite UI source, core source, Android source, or release versions.

| Native platform | Local runner output | Validation |
| --- | --- | --- |
| Windows amd64 | Standard installer and portable ZIP | Clean install to a path containing spaces, exact core comparison, Qt startup without a session using installed runtime, uninstall and cleanup; approved ZIP and Setup budgets reported |
| Linux amd64 | tar.xz and DEB | Extract both packages to temporary paths, compare installed core, launch Qt in an isolated signed-out profile |
| macOS Intel and Apple Silicon | Separate `.app` archives | Launch the extracted Qt client in isolated signed-out mode and verify ad-hoc bundle signature and main executable architectures |

The Windows installer uses a manifest of staged files when `REMOVE_QT_PAYLOAD`
is set, rather than recursively removing directories that can contain
user-added files. The Qt release and validation paths both use this flag.

The reported SHA-256 and file counts are build evidence, not approval to
publish. Historical run `44783442294` used version 1.19.0; do not relabel its packages as 1.20.0. Before promotion: resolve distribution rights and exact
corresponding-source/notices for the deployed dependencies, review the budget
and Linux dependencies, validate native runtime setup, upgrades and real-account
flows, and verify required release signatures. Do not substitute ad-hoc macOS
signing or a successful signed-out test for these checks. Android and other
packaging targets are outside this desktop preparation workflow.

## Official wiring on the 1.20.0 preparation branch

`packaging/desktop-release-ui` explicitly selects Qt: an unsuccessful Qt
preflight must not fall back to Flutter. The existing `Build` matrix uses the
same Qt packaging/staging logic as validation. Its top-level preflight
blocks jobs that could access signing keys (including Android) until the
distribution check passes. It also rejects a mismatched tag or draft notes
on a tag event. The official publish job rechecks distribution prerequisites.
No approval record is supplied by this change. An authorized reviewer must
record the result in `packaging/qt-distribution-approval.json` for the exact
version, with `decision: "approved"`, `review_reference`,
`rights_for_bundled_code_and_assets`, and `corresponding_source_plan`. The record
is a required statement of the review, not a substitute for that review or for
offering the corresponding source and notices.

The approved Qt limits are 45 MB for Windows portable ZIP, 35 MB for
Windows Setup, 50 MB for each Linux archive (`.deb` and `.tar.xz`),
and 25 MB for each macOS archive. Non-Qt limits are unchanged.
The draft `1.20.0` notes report the last measured output sizes. The checks-only
workflow still stores evidence, not public release assets. The version bump
changes declarations only and must be rebuilt and verified before signing.

The existing official pipeline uses pinned OpenPGP signatures for release
files, pinned Ed25519 update-manifest signatures, and the pinned Android
APK signing certificate. These do not provide Windows Authenticode or macOS
Developer ID/notarization; the current Mac layout uses only ad-hoc codesign.
