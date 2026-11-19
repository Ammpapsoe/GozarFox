# Experimental Windscribe-Qt / Gozar core integration

**Work in progress: this is not a desktop UI, an installer, or a release.**
The existing Flutter application and login remain untouched. This directory
contains copied upstream source, a Qt transport adapter and isolated tests with
a simulated core. It does not yet provide an integrated application, integrate
login, or control a real VPN. Do not ship or use it to manage a real system
tunnel.

## Pinned original UI source

The unmodified GUI, frontend-common and client-common sources and license are
copied to `vendor/windscribe` from Windscribe/Desktop-App `v2.26.2`, commit
`5578945964760be0bf234c506fdbcbcfe07c974d`. `fetch-upstream.sh` reproduces a clean full
checkout elsewhere and refuses an existing directory. Its source is
GPL v2; the exact applicable version, component licences, font and artwork
terms, trademarks and compatibility with Gozar's AGPL components require
review before distribution. Separate processes do not automatically settle
licence compatibility. No third-party source was relicensed by this directory.

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
states and waits for quit acknowledgement and process exit. These are unverified
integration work and are not wired to the Flutter screen or a Qt launcher. A
caller must not launch the next owner on an unconfirmed result. The fallback
alone does not guarantee successful cleanup after a crash or when the event loop
exits. The included fake core is only for tests and must never ship as the real
core.

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
It must not be used with a real VPN. The upstream window's
protocol widget defaults to WireGuard/443, which does not describe Gozar; it
must be corrected or disabled before this binding is shown to users. The
separate experimental power binding controls only the connection button; it
does not make the other window controls functional.

`LocationCatalog` converts Gozar server snapshots to the upstream location
model's types and maps selected IDs back to actual hostnames. It rejects
identifier collisions, ambiguous hostname targets, and clears stale choices on
malformed snapshots. GitHub Actions exercised the original `LocationsWindow`
and original location model using a simulated core on Windows, Linux and macOS.
Other tabs, automatic best-server selection and persisted favorites are not
integrated by this test. `bindOriginalLocationsNavigation` is an unverified
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
completeness across the full UI. The production preferences page is not yet
connected to this adapter. `warp_udp` requires explicit user consent.
Unsupported controls must not silently claim success.

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
