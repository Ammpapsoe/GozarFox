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
The included fake core is only for tests and must never ship as the real core.

Build the isolated transport tests (Qt 6 Core and Test, CMake, compiler):

```
cmake -S desktop_qt_adapter -B /tmp/gozar-qt-check -G Ninja
cmake --build /tmp/gozar-qt-check
ctest --test-dir /tmp/gozar-qt-check --output-on-failure
```

The tests cover request IDs, JSON response/parameters, core failure, malformed
responses, missing executable and shutdown. An optional offscreen test compiles
Windscribe's **actual unmodified background and image transition code and
resources**, links them to the adapter and exercises simulated server-country
and connection-state responses. The same test also instantiates the actual
unmodified Windscribe connect button and sends a simulated RPC on mouse click.
Test-only shims replace upstream preferences and other non-visual services.
Run it using:

```
cmake -S desktop_qt_adapter -B /tmp/gozar-qt-source-check -G Ninja -DBUILD_UPSTREAM_SMOKE_TEST=ON
cmake --build /tmp/gozar-qt-source-check
ctest --test-dir /tmp/gozar-qt-source-check --output-on-failure
```

`HomeBinder` is an experimental adapter connecting the original upstream
background and connect-button classes to Gozar's RPC. It requests state, sends
connection and server-selection commands, and feeds the returned country code
and connection state to those original widgets. It is **not** yet integrated
into the complete window or Flutter login, and must not be used with a real VPN.
The prior revision was tested on GitHub Actions with a fake core and isolated
upstream widgets on Windows, Linux and macOS. This is not an end-to-end UI test.
The settings RPC wrappers added after that run remain untested.

The Gozar `kill_switch` field is an opt-in for firewall rules on the next TUN
connection, not Windscribe's current firewall state. Do not bind it to the
upstream firewall indicator as though they were equivalent. Likewise, Gozar's
persisted language RPC accepts only English, Persian or system, whereas the
upstream UI includes more translation files; those other UI translations need
an independent persistence strategy. `warp_udp` requires explicit user consent.
Unsupported controls must not silently claim success.

The UI tests use a simulated core. For a separate, optional real-core check,
build the Gozar 1.19.0 core, configure with `-DGOZAR_REAL_CORE=/path/to/core`
and run CTest. The `realcore_smoke` test launches it with a temporary config,
reads its version/state and shuts it down; it does not sign in or connect.
None of these tests exercise the complete Windscribe UI, real login or a real
VPN connection. The real-core smoke test runs on the three GitHub Actions
platforms, but checks only state, version and shutdown.

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
