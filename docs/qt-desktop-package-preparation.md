# Qt desktop package preparation (not publication)

The workflow **Prepare Qt desktop packages (validation only)** runs on
`packaging/qt-preparation` or by dispatch. It does not run on a release tag,
modify `main`, require a signing secret, or publish an installer or archive.
The outputs persisted by Actions are package evidence only.

It builds the existing Flutter launcher with its existing opt-in Qt handoff,
the current Qt target, and the Go core from the exact same checkout. No UI
source, core source, Android source, or release versions are rewritten.

| Native platform | Local runner output | Validation |
| --- | --- | --- |
| Windows amd64 | Standard installer and portable ZIP | Clean install to a path containing spaces, exact core comparison, Qt startup without a session using installed runtime, uninstall and cleanup; unchanged 20 MB ZIP budget reported |
| Linux amd64 | tar.xz and DEB | Extract both packages to temporary paths, compare installed core, launch Qt in an isolated signed-out profile |
| macOS Intel and Apple Silicon | Separate `.app` archives | Launch the extracted Qt client in isolated signed-out mode and verify ad-hoc bundle signature and main executable architectures |

The Windows installer uses a manifest of staged files when `REMOVE_QT_PAYLOAD`
is set, rather than recursively removing directories that can contain
user-added files. This flag is not set in the existing release workflow.

The reported SHA-256 and file counts are build evidence, not approval to
publish. Same-commit versions currently remain 1.19.0; do not relabel these
packages as 1.20.0. Before promotion: resolve distribution rights and exact
corresponding-source/notices for the deployed dependencies, review the budget
and Linux dependencies, validate native runtime setup, upgrades and real-account
flows, and verify required release signatures. Do not substitute ad-hoc macOS
signing or a successful signed-out test for these checks. Android and other
packaging targets are outside this desktop preparation workflow.
