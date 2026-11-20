# Experimental Windscribe-Qt / Gozar core integration

**Work in progress: this is not a desktop UI, an installer, or a release.**
The existing Flutter application and login remain untouched. This directory
contains copied upstream source, a Qt transport adapter and isolated tests with
a simulated core. It does not yet provide an integrated application, integrate
login, or control a real VPN. Do not ship or use it to manage a real system
tunnel.

## Pinned original UI source

The GUI, frontend-common and client-common sources and license were copied to
`vendor/windscribe` from Windscribe/Desktop-App `v2.26.2`, commit
`5578945964760be0bf234c506fdbcbcfe07c974d`. The `locationsmenu` and
`connectwindowitem` source/header pairs have since been modified for Gozar as
recorded in `vendor/windscribe/UPSTREAM.txt`; the other copied source files remain
at that pin. `fetch-upstream.sh` reproduces a clean full
checkout elsewhere and refuses an existing directory. Its source is
GPL v2; the exact applicable version, component licences, font and artwork
terms, trademarks and compatibility with Gozar's AGPL components require
review before distribution. Separate processes do not automatically settle
licence compatibility. No third-party source was relicensed by this directory.

The test build generates a Qt resource map that substitutes an original Gozar
wordmark for `svg/LOGO.svg` and omits the unsupported branded spinner; the
pinned upstream source is not modified. This is not a complete artwork or
trademark audit. The full original preferences feature that needs that spinner
is not integrated. Other bundled graphics, translations and names still require
review before distribution.

The original GUI source is `src/client/frontend/gui`; notably,
`connectwindow/connectwindowitem.cpp` calls `Background::onLocationSelected`
with the country code; `background.cpp` and
`connectwindow/backgroundimage/backgroundimage.cpp` render/animate flags. These
are to be used as upstream Qt code, not re-drawn in Flutter. They depend on the
upstream preferences, location models, resources, and other Qt/client types;
compiling one file alone is not equivalent to transferring the complete UI.

## Adapter proof of concept

`CoreBridge` launches `gozar-core --rpc` using QProcess and exchanges
newline-delimited JSON. The wire format is the existing released 1.19.0 Go RPC
protocol. It must not be used for actual connections yet: notably application
lifecycle, reliable disconnect and cleanup under transport failures, shared
Flutter login state/process ownership, platform packaging and behaviour are
not integrated. Credentials must not be logged or passed on the command line.
A fallback lets a running core finish asynchronously if its bridge is destroyed,
instead of killing it immediately. Experimental `shutdownFinished` requires a
successful quit response with disconnected state and a normal process exit; its
positive result reflects the core's report, not independent verification of OS
cleanup. The Dart `prepareUiHandoff` guard likewise refuses connected or unknown
states and waits for quit acknowledgement and process exit. Concurrent starts
on one Flutter Core object share a single process-start future; a stop during
spawn requests graceful shutdown as soon as the process appears. This is not a
system-wide singleton and does not prevent another application instance from
starting its own core. Experimental
`OriginalUiHandoff` can be injected into the unchanged Flutter login flow and
existing signed-in startup. It checks target availability before shutdown and
will only invoke the target after the previous core reports successful shutdown
and exits. `OriginalUiProcessTarget` starts the fixed sibling executable
`gozar-original-ui` (`.exe` on Windows), passing only `--handoff-stdio-v1`;
stdout is reserved for the single readiness line `GOZAR_QT_READY_V1`. Its
preflight verifies a regular file (and executable permission on Unix), but is
not a signature check and cannot eliminate changes between checking and
starting. It rejects malformed output and early exit, and never kills the child
or starts a replacement on timeout. A child must report ready only after its UI
is initialized and its own core is signed in and disconnected. `UiReadiness`
provides an experimental Qt-side gate for a visible window and verified core
state, but the complete application still has to call it only when the UI is
actually ready and write the protocol line. Visibility does not establish GUI
correctness or OS cleanup. The process launcher is also tested against a CI-only executable containing
an actual upstream Qt widget and the readiness gate; it uses test-only backend
shims and a fake core and is not a real application. `gozar_qt_readonly_probe` is a separate development executable that opens the
original Qt home widget and reads signed-out state from an isolated real core;
it has no login or active controls, uses test backend shims and is not a
production application. It accepts only `--ci-readonly <core-path>` and cannot
serve as the launcher target. No complete Qt application executable is built
or packaged yet. The CI-only `gozar_qt_simulator` assembles the actual home,
locations and mode/language controls into an experimental shell, exercises
fake-core server selection, mode, connect/disconnect and guarded close, and can
accept a simulated Flutter handoff. Its `--demo` argument opens an interactive
simulation; it is labelled prominently and uses an isolated temporary profile.
In its normal demo and handoff modes it starts only the adjacent `fake_core`
fixture. `--ci-demo` checks that demo's startup and guarded close without human
input. A separate `--ci-real-signed-out <core-path>` mode renders the assembled
widgets using an isolated real core, explicitly rejects signed-in/live state,
does not bind the power control, and waits for confirmed shutdown. The unchanged
core may perform its own startup discovery and recovery even without a VPN
connection; this is not proof of zero network or OS side effects. These are
development features, not a production VPN: the application uses test backend
shims and is not the complete original controller. Never package this simulator
as a real product. The experimental resource map now includes the upstream font
expected by FontManager; its redistribution rights remain unverified. CI can
produce a hash inventory of resources in that map, which is not a complete
software bill of materials or rights clearance. The preliminary distribution
review is in `RELEASE-LICENSE-REVIEW.md`; it is not legal clearance. See
`PACKAGING-PLAN.md` for the blocked cross-platform release plan. The entry point
remains disabled by default; `GOZAR_EXPERIMENTAL_ORIGINAL_QT` is only a build-time
opt-in for future integration builds, not permission to distribute this WIP.
A caller must not launch the next owner on an unconfirmed result. The fallback
alone does not guarantee successful cleanup after a crash or when the event loop
exits. `UiCloseGuard` is an experimental Qt close-event coordinator: it keeps
the window open until the core acknowledges shutdown and exits, and leaves an
unconfirmed failure open for explicit recovery rather than forcing an exit.
Its tests use a fake core; the full application must still wire it into every
exit path, display errors, retain the event loop and verify OS cleanup. It
cannot protect against OS shutdown, crashes or forced process termination. The
included fake core is only for tests and must never ship as the real core.

Build the isolated transport tests (Qt 6 Core and Test, CMake, compiler):

```
cmake -S desktop_qt_adapter -B /tmp/gozar-qt-check -G Ninja
cmake --build /tmp/gozar-qt-check
ctest --test-dir /tmp/gozar-qt-check --output-on-failure
```

The tests cover request IDs, JSON response/parameters, core failure, malformed
responses, missing executable and shutdown. An optional offscreen test builds
the original upstream `ConnectWindowItem` and its visible children, background,
image-transition code and resources. With test-only shims for preferences,
persistence and tooltips, a mouse click on the actual window's connection
button sends an RPC to a simulated core. The separate background test exercises
simulated country and connection state changes. These checks do not build the
complete desktop application. Run them using:

```
cmake -S desktop_qt_adapter -B /tmp/gozar-qt-source-check -G Ninja -DBUILD_UPSTREAM_SMOKE_TEST=ON
cmake --build /tmp/gozar-qt-source-check
ctest --test-dir /tmp/gozar-qt-source-check --output-on-failure
```

`HomeBinder` is an experimental adapter connecting original upstream widgets
to Gozar's RPC. It requests state, sends connection and server-selection
commands, and feeds the returned country code and connection state to those
widgets. The experimental `bindOriginalHomeWindow` uses the real upstream
`ConnectWindowItem` public methods and forwards its connect click. GitHub
Actions built and ran that original window with test-only backend services on
Windows, Linux and macOS. The click and settings tests use a fake core; another
window test reads state from the real, signed-out 1.19.0 core in an isolated
configuration and requests shutdown. It is **not** an end-to-end UI test,
integrated Flutter login, complete application or real VPN integration.
It must not be used with a real VPN. The upstream window's protocol widget
defaults to WireGuard/443, and its firewall control has different semantics.
The modified original window hides and disables these displays when the home
binding is installed, and keeps them disabled after location or clickability
updates. They are not replacements for implementing real Gozar status/settings.
The separate experimental power binding controls only the connection button; it
does not make the other window controls functional. The experimental home
binding feeds the core-reported exit address to the original IP widget only
while a TUN connection is live; it clears it for proxy modes, disconnect or
unknown state. The upstream IP widget displays IPv4 only; it does not prove
that other processes route through the connection.

`LocationCatalog` converts Gozar server snapshots to the upstream location
model's types and maps selected IDs back to actual hostnames. It rejects
identifier collisions, ambiguous hostname targets, and clears stale choices on
malformed snapshots. GitHub Actions exercised the original `LocationsWindow`
and original location model using a simulated core on Windows, Linux and macOS.
The binding invalidates the previous catalogue on refresh, account change,
unknown state or core exit; old entries cannot be used if a refresh fails.
Other tabs, automatic best-server selection and persisted favorites are not
integrated. The modified upstream menu can restrict interaction to all/search
before it changes the tab or persistence; it hides unsupported tabs, maps the
right-arrow shortcut to search, and normalizes an unsupported persisted tab.
The isolated Qt test covers the menu slots, keyboard and persisted state; this
is not a complete application or a guarantee about unconnected controllers.
`bindOriginalLocationsNavigation` is an unverified
adapter for the original MainWindowController expansion/collapse API; it has not
been exercised with the real controller. `bindOriginalPowerControl` is an
experimental adapter for the original power button: it disables it when the core state is unknown,
signed out or changing. Install it after any call to the original window's
`setClickable`, which controls every button. It does not integrate or sanitize
the other upstream controls, and it must not yet be shipped with a real VPN.

The Gozar `kill_switch` field is an opt-in for firewall rules on the next TUN
connection, not Windscribe's current firewall state. Do not bind it to the
upstream firewall indicator as though they were equivalent. Likewise, Gozar's
persisted language RPC accepts only English, Persian or system. Experimental
`UiLanguage` persists the original Qt UI's language separately without changing
the core's language. The consolidated GitHub check compiled and loaded all 23
original language catalogs on Windows, Linux and macOS; it also checked a
Persian string, Persian RTL direction, German LTR direction, and rejected an
unknown language. That does not establish visual RTL correctness or translation
completeness across the full UI. `OriginalLanguageBinding` attaches the
original settings combo-box to the UI language choice, but the full preferences
page is not connected. The original page's language handler must be detached or
replaced rather than run alongside it. `warp_udp` requires explicit user
consent. Unsupported controls must not silently claim success.

`OriginalModeBinding` is an experimental adapter for the upstream preferences
combo-box API, not a complete preferences page. It only displays confirmed core
state, disables changes while connected or unavailable, and refuses system
proxy outside Windows. The integration scenario exercises both a stand-in and
the original combo-box and menu-selection logic against a fake core; it does not
exercise a complete preferences window or visual mouse interaction. It has not
been connected to the application or validated with a real VPN.

The interactive UI tests use a simulated core. For the separate real-core
smoke check, build the Gozar 1.19.0 core, configure with
`-DGOZAR_REAL_CORE=/path/to/core` and run CTest. `realcore_smoke` checks
version/state and shutdown using a temporary config. The original-window
state check also runs when `GOZAR_CI_TEST_CORE` is set. Neither test signs in
or connects. None exercise the complete Windscribe UI, real login or a real
VPN connection.

## Remaining work before any release

- Resolve distribution/licence and branding/asset rights.
- Compile upstream's real UI and resources with its required dependencies; replace
  its backend coupling with an adapter, while avoiding operation of the upstream
  VPN engine and retaining the native UI itself.
- Design and implement a secure lifetime/session handoff from the unchanged
  Flutter login; ensure only one core owns the session, and prevent token leakage.
- Map actual Gozar states, country codes, servers and actions to the original
  widgets, including original flag-background animation, all supported languages,
  the three Gozar modes and truthful unsupported controls.
- Integrate build, packaging, update and signing for Windows, Linux and macOS.
- Exercise real login, connect/disconnect, flag changes, core crash and shutdown,
  system proxy restoration, all UI states and multiple DPI scales on all targets.
