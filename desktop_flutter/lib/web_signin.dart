import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:webview_flutter/webview_flutter.dart' as wf;
import 'package:webview_windows/webview_windows.dart' as ww;

import 'core.dart';
import 'strings.dart';
import 'theme.dart';
import 'web_channel.dart';

/// Whether this platform has the in-app browser for the sign-in: WebView2 on
/// Windows, the system WKWebView on macOS. Linux signs in with the email and
/// password form, whose Mozilla security check the core answers itself.
bool get browserSignInSupported => Platform.isWindows || Platform.isMacOS;

/// The Windows web views share one WebView2 environment, made once per run
/// of the app with the sign-in proxy it is given then. The core keeps that
/// proxy's port stable for the run, so later pages reuse the environment
/// instead of remaking it.
bool _windowsEnvironmentReady = false;

/// The proxy channel of the macOS window (macos/Runner/MainFlutterWindow.swift).
const MethodChannel _macWebview = MethodChannel('gozar/webview');

/// Signs an account in inside the app's browser view: the person uses
/// Mozilla's own page, and the captured session goes to the core, which
/// exchanges it for the account's tokens exactly like the Android app does.
///
/// Pops `true` once the core has the account stored, `false` on cancel or
/// when the person chooses the form instead.
class BrowserSignInPage extends StatefulWidget {
  const BrowserSignInPage({
    super.key,
    required this.core,
    required this.persian,
    this.email = '',
  });

  final Core core;
  final bool persian;
  final String email;

  @override
  State<BrowserSignInPage> createState() => _BrowserSignInPageState();
}

class _BrowserSignInPageState extends State<BrowserSignInPage> {
  /// The sign-in page Firefox desktop opens for sync: with these parameters
  /// accounts.firefox.com reports the signed-in session over its WebChannel,
  /// which carries the session token the core exchanges for the account.
  static const String _page = 'https://accounts.firefox.com/';

  /// How long the first paint may take before the page offers a way out.
  static const Duration _pageBudget = Duration(seconds: 45);

  static const Color _dark = Color(0xFF0B0B0D);

  Words get words => Words(widget.persian);

  // Windows: WebView2's controller; macOS: webview_flutter's.
  ww.WebviewController? _windows;
  StreamSubscription<dynamic>? _windowsMessages;
  StreamSubscription<ww.LoadingState>? _windowsLoading;
  wf.WebViewController? _view;

  // The page paints from here on: the spinner must never be seen on top of
  // Mozilla's own page.
  bool _pageStarted = false;
  bool _failed = false;
  bool _done = false;
  String _doneEmail = '';

  // One exchange per announced session. An unverified session re-arms: the
  // page stays open for the code and Mozilla announces the session again.
  bool _armed = true;
  bool _exchanging = false;

  String? _proxyAddress;
  Timer? _budget;

  String get _loginUrl {
    final query = <String, String>{
      'context': 'fx_desktop_v3',
      'service': 'sync',
      if (widget.email.isNotEmpty) 'email': widget.email,
    };
    return Uri.parse(_page).replace(queryParameters: query).toString();
  }

  /// A Firefox user agent: accounts.firefox.com only speaks its WebChannel
  /// to what looks like Firefox.
  String get _userAgent => Platform.isMacOS
      ? 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:145.0) '
          'Gecko/20100101 Firefox/145.0'
      : 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:145.0) '
          'Gecko/20100101 Firefox/145.0';

  @override
  void initState() {
    super.initState();
    _armBudget();
    unawaited(_init());
  }

  void _armBudget() {
    _budget?.cancel();
    _budget = Timer(_pageBudget, () {
      if (mounted && !_pageStarted && !_done && !_failed) {
        setState(() => _failed = true);
      }
    });
  }

  @override
  void dispose() {
    _budget?.cancel();
    _windowsMessages?.cancel();
    _windowsLoading?.cancel();
    final windows = _windows;
    _windows = null;
    if (windows != null) unawaited(windows.dispose());
    if (Platform.isMacOS) {
      // Straight out again. Windows keeps the proxy: its environment was
      // made with that port and cannot be remade in this run.
      unawaited(_setMacProxy(null));
      unawaited(widget.core
          .call('close_signin_proxy')
          .catchError((Object _) => const CoreReply()));
    }
    super.dispose();
  }

  Future<void> _init() async {
    await _openProxy();
    if (!mounted) return;
    try {
      if (Platform.isWindows) {
        await _initWindows();
      } else if (Platform.isMacOS) {
        await _initMac();
      } else {
        setState(() => _failed = true);
      }
    } on Object {
      // No WebView2 runtime, or the web view failed to start.
      if (mounted) setState(() => _failed = true);
    }
  }

  /// The core's sign-in proxy: the view reaches Mozilla's pages through it —
  /// every address of a name raced, independent resolvers, the ClientHello
  /// split inside the SNI name — which some networks need to let the page
  /// load at all. Null when the core cannot open it: the view then connects
  /// by itself.
  Future<void> _openProxy() async {
    try {
      final reply = await widget.core.call('open_signin_proxy');
      _proxyAddress = reply.proxyAddress;
    } on Object {
      _proxyAddress = null;
    }
  }

  Future<void> _initWindows() async {
    if (!_windowsEnvironmentReady) {
      _windowsEnvironmentReady = true;
      try {
        await ww.WebviewController.initializeEnvironment(
          additionalArguments:
              _proxyAddress == null ? null : '--proxy-server=$_proxyAddress',
        );
      } on PlatformException {
        // Made already (a hot restart): it keeps what it was made with.
      }
    }
    final controller = ww.WebviewController();
    await controller.initialize();
    await controller.setBackgroundColor(_dark);
    await controller.setPopupWindowPolicy(ww.WebviewPopupWindowPolicy.sameWindow);
    await controller.setUserAgent(_userAgent);
    await controller.addScriptToExecuteOnDocumentCreated(WebChannel.darkCanvas);
    await controller.addScriptToExecuteOnDocumentCreated(WebChannel.relayScript);
    _windowsMessages = controller.webMessage.listen(_onBridgeMessage);
    _windowsLoading = controller.loadingState.listen((state) {
      if (state != ww.LoadingState.none) _painted();
    });
    await controller.loadUrl(_loginUrl);
    if (!mounted) {
      await controller.dispose();
      return;
    }
    setState(() => _windows = controller);
  }

  Future<void> _initMac() async {
    // Set before the first page loads: the override applies to the
    // connections the view starts from then on.
    if (_proxyAddress != null) await _setMacProxy(_proxyAddress);
    final controller = wf.WebViewController()
      ..setJavaScriptMode(wf.JavaScriptMode.unrestricted)
      ..setBackgroundColor(_dark)
      ..setUserAgent(_userAgent)
      ..addJavaScriptChannel(
        'GozarBridge',
        onMessageReceived: (message) => _onBridgeMessage(message.message),
      )
      ..setNavigationDelegate(
        wf.NavigationDelegate(
          // Injected twice: early, and again for pages whose start was
          // missed; the relay installs itself only once.
          onPageStarted: (_) {
            _injectMac();
            _painted();
          },
          onPageFinished: (_) => _injectMac(),
        ),
      )
      ..loadRequest(Uri.parse(_loginUrl));
    if (!mounted) return;
    setState(() => _view = controller);
  }

  void _injectMac() {
    _view?.runJavaScript(WebChannel.darkCanvas);
    _view?.runJavaScript(WebChannel.relayScript);
  }

  Future<void> _setMacProxy(String? address) async {
    try {
      await _macWebview
          .invokeMethod<bool>('setWebViewProxy', <String, Object?>{
        'address': address,
      });
    } on PlatformException {
      // The view connects by itself.
    } on MissingPluginException {
      // Running outside the window (a test): nothing to set.
    }
  }

  void _painted() {
    _budget?.cancel();
    if (!mounted || _pageStarted) return;
    setState(() => _pageStarted = true);
  }

  void _onBridgeMessage(Object? raw) {
    switch (WebChannel.read(raw)) {
      case SignedIn(:final session):
        unawaited(_accept(session));
      case Waiting waiting:
        // The page waits for these; silence would hang the sign-in.
        _replyTo(waiting);
      case Ignored():
        break;
      case Rejected():
        // Silent on screen by design; the message never existed.
        break;
    }
  }

  void _replyTo(Waiting waiting) {
    // jsonEncode of the envelope string is a quoted, escaped JS literal.
    final script =
        'window.gozarDeliver(${jsonEncode(WebChannel.replyTo(waiting))});';
    _windows?.executeScript(script);
    _view?.runJavaScript(script);
  }

  Future<void> _accept(SignInSession session) async {
    if (_done || _exchanging || !_armed) return;
    _armed = false;
    _exchanging = true;
    try {
      final reply = await widget.core.call('browser_signin', <String, Object?>{
        'email': session.email,
        'uid': session.uid,
        'session_token': session.sessionToken,
      });
      if (!mounted) return;
      if (reply.unverified) {
        // The account is not confirmed yet. That is not a failure of this
        // screen: the code is typed into Mozilla's page, which then
        // announces the session again and we take it the second time.
        _armed = true;
        return;
      }
      final state = reply.state;
      if (reply.error == null && state != null && state.signedIn) {
        setState(() {
          _done = true;
          _doneEmail = state.email.isNotEmpty ? state.email : session.email;
        });
      } else {
        setState(() => _failed = true);
      }
    } on Object {
      if (mounted) setState(() => _failed = true);
    } finally {
      _exchanging = false;
    }
  }

  void _retry() {
    setState(() {
      _failed = false;
      _pageStarted = false;
      _armed = true;
    });
    _armBudget();
    if (_windows != null) {
      _windows!.loadUrl(_loginUrl);
    } else {
      _view?.loadRequest(Uri.parse(_loginUrl));
    }
  }

  @override
  Widget build(BuildContext context) {
    final view = _windows != null
        ? ww.Webview(_windows!)
        : _view != null
            ? wf.WebViewWidget(controller: _view!)
            : null;
    return Scaffold(
      backgroundColor: _dark,
      body: Column(
        children: [
          _header(),
          Expanded(
            child: Stack(
              children: [
                // The web view stays laid out and visible; opaque overlays
                // cover it.
                Positioned.fill(
                  child: view ?? const ColoredBox(color: _dark),
                ),
                // Only the silence before the first byte of the page gets a
                // spinner; afterwards the page, and only the page, is on
                // stage.
                if (!_pageStarted && !_done && !_failed)
                  const Positioned.fill(
                    child: Center(child: _SpinnerCapsule()),
                  ),
                if (_done)
                  Positioned.fill(child: _successCard())
                else if (_failed)
                  Positioned.fill(child: _failureCard()),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// One capsule — logo, name, cancel — inside a full-width opaque black
  /// band, so the page can never shine through the top.
  Widget _header() => Container(
        color: Colors.black,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        alignment: AlignmentDirectional.centerStart,
        child: Container(
          padding: const EdgeInsetsDirectional.fromSTEB(12, 4, 4, 4),
          decoration: BoxDecoration(
            gradient: const LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [Color(0xFF2E2E31), Color(0xFF1F1F22), Color(0xFF121214)],
            ),
            border: Border.all(color: const Color(0xFF3C3C41)),
            borderRadius: BorderRadius.circular(999),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Image.asset('assets/gozar.png', width: 22, height: 22),
              const SizedBox(width: 8),
              const Text(
                'Gozar',
                style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.w600,
                  fontSize: 14,
                ),
              ),
              const SizedBox(width: 10),
              Container(width: 1, height: 18, color: const Color(0xFF3C3C41)),
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                style: TextButton.styleFrom(
                  foregroundColor: soft,
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                  minimumSize: Size.zero,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
                child: Text(
                  words.cancel,
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 13,
                  ),
                ),
              ),
            ],
          ),
        ),
      );

  Widget _successCard() => ColoredBox(
        color: _dark,
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 400),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 96,
                    height: 96,
                    decoration: const BoxDecoration(
                      color: Color(0xFF2FBF71),
                      shape: BoxShape.circle,
                    ),
                    child: const Icon(Icons.check_rounded,
                        color: Colors.white, size: 56),
                  ),
                  const SizedBox(height: 20),
                  Text(
                    words.signedInTitle,
                    textAlign: TextAlign.center,
                    style: Theme.of(context)
                        .textTheme
                        .headlineSmall
                        ?.copyWith(fontWeight: FontWeight.w700),
                  ),
                  if (_doneEmail.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Text(
                      _doneEmail,
                      textAlign: TextAlign.center,
                      textDirection: TextDirection.ltr,
                      style: TextStyle(color: muted(context), fontSize: 15),
                    ),
                  ],
                  const SizedBox(height: 24),
                  FilledButton(
                    onPressed: () => Navigator.of(context).pop(true),
                    child: Text(words.startConnection),
                  ),
                ],
              ),
            ),
          ),
        ),
      );

  /// The view could not run, or the exchange failed: what happened stays one
  /// short line, and the way out is the form or another try.
  Widget _failureCard() => ColoredBox(
        color: _dark,
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 400),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  const Icon(Icons.public_off_rounded, size: 44, color: soft),
                  const SizedBox(height: 14),
                  Text(
                    words.browserSignInFailed,
                    textAlign: TextAlign.center,
                    style: Theme.of(context)
                        .textTheme
                        .titleMedium
                        ?.copyWith(fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 20),
                  FilledButton(
                    onPressed: _retry,
                    child: Text(words.tryAgain),
                  ),
                  const SizedBox(height: 8),
                  TextButton(
                    onPressed: () => Navigator.of(context).pop(false),
                    child: Text(words.useTheForm),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
}

/// A pill with a quiet spinner — the only loading sign the page ever shows,
/// and only before Mozilla's own page starts painting.
class _SpinnerCapsule extends StatelessWidget {
  const _SpinnerCapsule();

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0xFF2E2E31), Color(0xFF1F1F22), Color(0xFF121214)],
          ),
          border: Border.all(color: const Color(0xFF3C3C41)),
          borderRadius: BorderRadius.circular(999),
        ),
        child: const SizedBox(
          width: 22,
          height: 22,
          child: CircularProgressIndicator(
            strokeWidth: 2.4,
            color: Color(0xCCFFFFFF),
          ),
        ),
      );
}


