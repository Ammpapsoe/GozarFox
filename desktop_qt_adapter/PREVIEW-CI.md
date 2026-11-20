# Owner evaluation: desktop Qt preview, not a release

Push the integration branch under the name `qt/desktop-preview` to trigger
**Windscribe Qt UI integration (experimental)** on GitHub Actions. No release
or public distribution approval is implied. If the repository's Actions or
branch settings block that push event, have an administrator enable it. Do not
place credentials or signing material in the branch.

The preview branch runs only the Windows x64 package job. The reusable
workflow can still build Linux x64 and Intel/Apple Silicon macOS bundles when
called with `windows_only: false`. No preview push tags or publishes anything.
Each job:

1. Verifies the published 1.19.0 desktop archive against its pinned SHA-256,
   then extracts only `gozar-core` (`gozar-core.exe` on Windows).
2. Builds Qt and runs the offline UI fixture plus signed-out official-core
   checks. No account credentials or live VPN connection are used.
3. Captures screenshots from the actual Qt executable with simulated server
   and traffic data plus reserved `example.test` account addresses in English
   and Persian at 100%, 125% and 150%, and uploads them as a distinct
   artifact. No fixture core is included in the development package.
4. Stages the existing Flutter sign-in UI and Qt candidate for owner
   evaluation. The experimental Qt launcher can reopen the Flutter app for
   sign-out or to open the existing Add Account flow, only after acknowledged
   shutdown and core exit. The launch flag changes routing, not the form or
   authentication implementation. The macOS bundle is ad-hoc signed during packaging, so its
   executable signatures need not be byte-for-byte identical to the source
   archive. The Go sources are never modified or rebuilt by this workflow.

On Windows the workflow runs Flutter analysis and handoff tests with the built
fixtures, deploys the real Qt runtime and app-local MSVC runtime, and builds one
unsigned NSIS setup EXE. A runner smoke installs it, checks its layout and core
hash from a custom installation path with spaces, starts the installed Qt in a temporary signed-out profile with the Windows
Qt plugin, and verifies removal. The EXE is uploaded **only after** the checks:
`gozar-qt-windows-test-exe` → `Gozar-Qt-Experimental-Windows-x64-Setup.exe`.
The Actions artifact wrapper is a ZIP containing that single EXE. Sign-in may
require Microsoft's WebView2 runtime. Quit stable Gozar before opening the
preview; account/config data are shared even though installed files, Start
Menu shortcut and uninstall registration are separate.

Other artifact names: `gozar-qt-screenshots-*` and
`gozar-qt-real-core-*-EXPERIMENTAL-NOT-RELEASE`. The local test results do not
establish Windows/macOS runtime behavior, independent tunnel cleanup, or legal
approval. See `EXPERIMENTAL-WARNING.txt` and `RELEASE-LICENSE-REVIEW.md`.

## Rendering and account-switch checks

Qt 6 already takes the Windows display scale without system overrides. The
adapter now retains fractional DPR for SVG rasterization, draws location text
on the active Qt painter, and uses the upstream preferences radius of nine
logical pixels for the attached panels. The Windows fractional capture steps
use the native Qt plugin with a controlled Qt scale multiplier, not changes to
the system display settings. Physical Windows monitor and mixed-DPI checks are
still needed.

On Windows only, a confirmed switch to the requested account with no live or
pending connection schedules a guarded shutdown, then reopens Flutter to
load that account and hand off normally. A failed switch or unconfirmed
shutdown does not launch a second core. The fixture verifies the acknowledgement
contract; it does not use a real account or prove a physical-machine round trip.
