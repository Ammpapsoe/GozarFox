# Qt 1.20.0 licensing and release checklist — not approved for distribution

## Rights and notices

- The root `LICENSE` remains AGPL-3.0. Replacing it does not grant rights to
  relicense existing contributions. The proposal is a scoped compatible
  grant only for the relevant Qt interface code, if the people with rights
  can authorize it. Do not insert GPLv2 headers or mark this approved before
  confirming the exact files, their provenance and the scope of that grant.
- The compiled-input inventory identifies 24 local adapter files, 13 local
  shims/wrappers and 32 modified Windscribe inputs in the sampled Linux build.
  No separate compatible grant for the 24 adapter files was evidenced. The
  first prototype import is `551ca27`; commit history is not proof of ownership.
  Check the shims and changes for adapted code and retain applicable notices,
  including file-specific modification notices where required.
- The 429 Qt resource inputs in run `44795363782` include 422 paths in the
  vendored tree. In the Linux checkout those 422 files match the pinned
  upstream bytes. This does not establish a blanket grant for artwork,
  fonts or marks. Gozar's wordmark substitutes for the upstream logo in the
  generated map; the Windscribe spinner is excluded. Separately account for
  the 18 Flutter SVG assets, translation catalogs, and the IBM Plex Sans
  OFL 1.1 notice (font metadata evidenced; exact standalone text/packaging
  still to verify). Preserve the existing Vazirmatn OFL notice.
- The Linux executable examined locally imports Qt Core, Gui, Widgets,
  Svg and Network as shared libraries. Deployment on Windows/macOS is
  configured to copy Qt runtimes; verify DLL and framework imports on the
  final candidates. Dynamic linking is not automatic LGPL compliance.
  Select and document the applicable license for the actual Qt 6.8.3
  libraries/plugins, preserve notices and corresponding source, and meet
  replacement/relink requirements where applicable. Sampled source files
  have a GPLv2 option; do not infer that all deployed components do.
- Resolve the status of Qt, Flutter and Go under their actual handoff/IPC,
  and record exact package inventories, grants and a corresponding-source
  plan for the distributed files before enabling publication.

## Package and signing plan

| Platform | Validated internally | Official-format work still needed |
| --- | --- | --- |
| Windows x64 | NSIS Setup EXE and portable ZIP | Use the existing EXE; no MSI required by the EXE-or-MSI choice. Validate install, upgrade, notices and official signatures. |
| macOS (Intel and Apple Silicon) | Separate `.app` tar.xz archives | DMG is an additional unbuilt format. Plan how it coexists with the update archives, verify extracted bundle/notices, and verify signatures. Prior 25 MB cap applies to tar.xz, not a new DMG. |
| Linux amd64 | DEB and tar.xz | Verify DEB install/upgrade and notices. Build and verify the Qt-based AppImage in the official derivative package job; not covered by the green native run. |

The staging script already copies the root LICENSE, NOTICE and Windscribe
GPLv2 text into the Qt layout. Restart from the same reviewed commit and
verify those files and everything else required in the exact final packages.
Existing release signing covers detached OpenPGP signatures and signed
update manifests (and the Android APK certificate). It does not by itself
provide Windows Authenticode, macOS Developer ID, or notarization.

Do not add an approval record or create `v1.20.0` until rights and source
provision are actually resolved, the appropriate tests and native checks
succeed, and official signing validation passes. No publication date is
implied by the successful validation-only workflow.
