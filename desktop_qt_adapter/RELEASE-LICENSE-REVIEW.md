# Preliminary distribution review — NOT release approval

Scope: proposed original Windscribe Qt GUI + existing Gozar Flutter UI/core; review of repository evidence on 2026-11-16. This is technical due diligence, not legal advice. Do not treat this report as permission to ship or as an exhaustive bill of materials.

## Evidence and unresolved blockers

| Item | Evidence | Required before publishing |
|---|---|---|
| Windscribe source | `vendor/windscribe/UPSTREAM.txt` pins v2.26.2 commit `5578945964760be0bf234c506fdbcbcfe07c974d`; the copied `LICENSE` is GPL version 2. GitHub identifies the repository as GPL-2.0. Some copied files do not themselves state a version or an “or later” grant. | Confirm the exact grant applicable to **each included** source and modified asset with the rights holders. A copy of the GPL text's sample “or later” notice is not itself proof of such a grant. Preserve notices and offer the corresponding source as required. |
| Gozar | Root `LICENSE` is AGPL-3.0; `NOTICE` identifies ZedSecure-derived work as AGPL-3.0-or-later. This does not prove every file has the same grant. | Inventory the actual source and dependencies in the desktop package, including generated code, determine rights and exact versions, and supply applicable source and notices. |
| Compatibility | FSF explains AGPLv3 is not compatible with GPLv2; GPLv2-only cannot generally be combined into one program with AGPLv3 code. Whether separately communicating processes form separate works is fact-dependent; separate processes alone do not prove compliance. | Obtain qualified legal review of the actual GUI, Flutter and Go architecture and IPC. If needed obtain a suitable additional grant from all relevant rights holders, replace components, or redesign the combination. No compatibility approval exists yet. |
| Qt | CI uses Qt 6.8.3 Core, Widgets, Svg and Network, plus Test/Linguist for tests/build. Qt licensing varies per module, version and third-party code; current Qt SVG documentation lists GPLv2 and LGPLv3 alternatives. | Audit the **exact distributed** Qt 6.8.3 modules, plugins, transitive dependencies and chosen licence options; collect the corresponding source, notices and relinking/replacement instructions where applicable. Test/build tools are not automatically runtime dependencies. Do not infer all modules are available under the same licence. |
| Artwork/marks | Upstream `gui/images.qrc` embeds `svg/LOGO.svg`, `gif/windscribe_spinner.gif`, flags and many other resources. Windscribe's published terms state its name and logo are trademarks and grant no trademark licence. | Inventory each shipped asset, origin, grant and trademark rights. Obtain written rights or replace/remove marks and unlicensed assets, avoid implying endorsement, and preserve applicable attribution. Source-code licence does not automatically resolve trademark rights. |
| Font | The upstream copied font `gui/resources/fonts/ibmplexsans.ttf` identifies itself as IBM Plex Sans. IBM describes IBM Plex as OFL-licensed. | Verify this exact font file/version and its licence/copyright from the actual source, include required notices and observe OFL conditions; do not assume a family name establishes the provenance of this binary. |
| Other dependencies | Root `NOTICE`, `GO-DEPS-LICENSES.txt`, Flutter `NOTICES.Z`, Wintun licence and upstream `client-common/licenses/open_source_licenses.txt` cover different dependency sets; upstream list is not a bill of materials for Gozar. | Generate platform-specific package inventories/SBOMs, match licences and notices to the exact bundled versions, and audit Go, Flutter, Qt, platform plugins, fonts, images and installer dependencies. |

## Packaging and release gates

1. No release executable exists yet. The development handoff candidate can start the real core but links test shims and implements only a limited selection of original widgets; it is not the complete original UI and is not a release. A separate development-only workflow can stage this incomplete candidate for owner evaluation; it is not approval for general distribution. Never bundle `fake_core`, `upstream_background_test`, test-only shims, credentials, or CI fixtures in a production package. No executable currently meets the requirements for a production Qt package.
2. Prepare Windows, Linux and macOS packages only after the complete original UI is bound, unsupported controls are disabled or implemented, lifecycle and independently observed network cleanup have been validated, and actual bundled paths and loader dependencies are verified. Bundle no production login token or password in arguments, logs or artifacts.
3. Provide the source for exact distributed binaries, modifications, build/install scripts and applicable notices, including dependencies; document reproducibility and licence obligations for each platform. Complete legal/brand review and any required replacement assets or permission **before** public distribution.
4. Validate install/upgrade/uninstall and signed release artifacts, startup/teardown, crash/recovery, DPI, localisation, and connection flows across all target platforms. Do not sign in/create/activate a real account without explicit owner authorization. Existing simulated and signed-out tests cannot substitute for these tests.
5. Require release-owner approval after security, legal, operational and CI gates. Do not create a stable tag or release on the basis of this report.

## References

- Pinned upstream: https://github.com/Windscribe/Desktop-App/tree/5578945964760be0bf234c506fdbcbcfe07c974d
- FSF licence explanations: https://www.gnu.org/licenses/license-list.html and https://www.gnu.org/licenses/gpl-faq.html
- Qt licence overview and SVG: https://doc.qt.io/qt-6/licensing.html and https://doc.qt.io/qt-6/qtsvg-index.html (current documentation; verify against the shipped 6.8.3 edition).
- Windscribe trademark/terms: https://windscribe.com/terms (applies to its service/terms; obtain legal advice as to scope and any conflict with applicable open-source rights).
- IBM Plex project: https://github.com/IBM/plex
