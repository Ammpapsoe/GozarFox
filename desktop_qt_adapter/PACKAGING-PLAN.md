# Qt integration packaging plan — blocked, not a release

This document describes integration work still required. Existing Flutter-only
release packages are separate and do not contain the experimental Qt UI. Do not
publish or distribute the simulator or its fake core as a VPN product.

## Intended desktop layout

The current Flutter process launcher resolves `gozar-original-ui` (or `.exe`)
next to `Platform.resolvedExecutable`. The core launcher similarly resolves
`gozar-core` (or `.exe`). These are future integration targets, not files this
project currently creates for production.

| Platform | Layout to verify in a future build | Required deployment work |
| --- | --- | --- |
| Windows x64 | Flutter executable, `gozar-original-ui.exe`, and `gozar-core.exe` in the same application directory | Bundle only audited Qt runtime DLLs and required platform/image/SVG plugins; handle loader search paths, installer upgrade/uninstall, Windows signing and existing Wintun licence. Verify that fake/test executables are absent. |
| Linux amd64 | Flutter executable, `gozar-original-ui`, and `gozar-core` in the bundle directory | Bundle or declare exact Qt libraries/plugins, test loader paths across supported distributions, and integrate with existing tar/deb and other package formats. Test sandbox permissions separately for AppImage/Flatpak/Snap. |
| macOS Intel and ARM | Within the existing `.app`, siblings under `Contents/MacOS`; Qt frameworks/plugins in appropriate bundle paths | Fix install names/rpaths, verify architecture of every bundled binary, sign nested Qt code before the app, notarize if required, and test both architectures and upgrade. This needs more than the currently tested Intel Qt build. |

A production launcher must be bound to the intended files securely; ordinary
file-existence preflight is not signature validation and has a time-of-check /
time-of-use race. Packaging must not weaken file permissions or log credentials.
The existing Flutter login and Go engine must remain unchanged.

## Gates before implementing packaging/publishing

1. Complete the actual original UI/controller integration and its bindings;
   simulate every relevant flow and explicitly label unsupported controls.
2. Complete robust single-owner lifecycle and recovery, including startup,
   signed-in handoff, normal close, failure and OS-managed shutdown. Observed
   core-reported disconnect alone is not independent proof of OS cleanup.
3. Resolve exact code licence grants, AGPL/GPL compatibility, Qt and dependency
   obligations, rights for every font/asset, and trademark concerns. Independent
   process boundaries and a resource hash inventory do not prove compliance.
4. Add platform-specific packaging jobs using the real target, never the
   simulator or test shims. Provide corresponding source, licence notices,
   complete SBOMs, reproducible build information and appropriate signing.
5. Run a consolidated three-platform simulated test plus architecture and
   installer checks on GitHub Actions. The owner conducts real login/connection
   and explicitly reviews those results; never create or activate an account.
6. Have the release owner review technical and legal/security gates, then
   explicitly approve the exact signed artifacts and release. Until then no
   stable tag, updater metadata or public release should be produced.

## Inventory produced by the experimental workflow

`branding/inventory_resources.py` lists paths, sizes and SHA-256 hashes for
inputs to the generated development `images.qrc`, with rights marked not
reviewed. It includes the original font and Gozar substitute wordmark; the
Windscribe spinner is excluded. The development CI also records Go module and
package dependency metadata and Flutter dependency metadata per tested OS.
These are source/build evidence, not an inventory of an actual release package:
they omit compiled translation catalogues, deployed Qt binaries and plugins,
platform-specific installer contents and operating-system dependencies. They
are not a licence determination or production SBOM.
