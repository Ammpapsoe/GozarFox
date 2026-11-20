# Qt 1.20.0 licensing and release checklist — not approved for distribution

## Rights and notices

- The root `LICENSE` remains AGPL-3.0. `LICENSE-SCOPE.md` and the headers
  record a scoped GPLv2 alternative for the 24 named Gozar adapter files,
  based on the project owner's confirmation. This is not an approval for
  other code or the complete package. Preserve applicable upstream notices
  for the 13 shims/wrappers and 32 modified Windscribe inputs.
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
| macOS (Intel and Apple Silicon) | Separate `.app` tar.xz archives | DMG creation and mount verification are wired into internal validation, but not yet run. Plan how it coexists with the update archives, verify extracted bundle/notices, and verify signatures. Prior 25 MB cap applies to tar.xz, not a new DMG. |
| Linux amd64 | DEB and tar.xz | Verify DEB install/upgrade and notices. Qt AppImage build/smoke is wired into validation and the official derivative job, but is not covered by the earlier green native run. |

The staging script already copies the root LICENSE, LICENSE-SCOPE.md, NOTICE and Windscribe
GPLv2 text into the Qt layout. Restart from the same reviewed commit and
verify those files and everything else required in the exact final packages.
Existing release signing covers detached OpenPGP signatures and signed
update manifests (and the Android APK certificate). It does not by itself
provide Windows Authenticode, macOS Developer ID, or notarization.

Do not add an approval record or create `v1.20.0` until rights and source
provision are actually resolved, the appropriate tests and native checks
succeed, and official signing validation passes. No publication date is
implied by the successful validation-only workflow.
