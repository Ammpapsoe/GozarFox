import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:window_manager/window_manager.dart';

import 'core.dart';
import 'strings.dart';
import 'theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await windowManager.ensureInitialized();
  await windowManager.setPreventClose(true);
  const options = WindowOptions(
    size: Size(460, 380),
    minimumSize: Size(360, 360),
    center: true,
    backgroundColor: Colors.transparent,
    titleBarStyle: TitleBarStyle.hidden,
    windowButtonVisibility: false,
    title: 'Gozar',
  );
  runApp(const GozarApp(windowControls: true));
  unawaited(windowManager.waitUntilReadyToShow(options, () async {
    if (Platform.isWindows) await windowManager.setAsFrameless();
    await windowManager.show();
    await windowManager.focus();
  }));
}

class GozarApp extends StatefulWidget {
  const GozarApp({super.key, this.core, this.windowControls = false});

  final Core? core;
  final bool windowControls;

  @override
  State<GozarApp> createState() => _GozarAppState();
}

class _GozarAppState extends State<GozarApp> with WidgetsBindingObserver {
  String _language = '';
  String _theme = 'system';

  bool get _persian => _language.isEmpty
      ? WidgetsBinding.instance.platformDispatcher.locale.languageCode == 'fa'
      : _language == 'fa';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeLocales(List<Locale>? locales) {
    if (_language.isEmpty && mounted) setState(() {});
  }

  void _changeLanguage(String value) {
    final normalized = value == 'system' ? '' : value;
    if (_language != normalized) setState(() => _language = normalized);
  }

  void _changeTheme(String value) {
    if (_theme != value) setState(() => _theme = value);
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
        debugShowCheckedModeBanner: false,
        title: 'Gozar',
        theme: buildTheme(lightMode: true),
        darkTheme: buildTheme(),
        themeMode: _theme == 'light'
            ? ThemeMode.light
            : _theme == 'dark'
                ? ThemeMode.dark
                : ThemeMode.system,
        locale: Locale(_persian ? 'fa' : 'en'),
        supportedLocales: const [Locale('fa'), Locale('en')],
        localizationsDelegates: const [
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        home: GozarScreen(
          core: widget.core,
          windowControls: widget.windowControls,
          persian: _persian,
          language: _language,
          onLanguageChanged: _changeLanguage,
          onThemeChanged: _changeTheme,
        ),
      );
}

class GozarScreen extends StatefulWidget {
  const GozarScreen({
    super.key,
    this.core,
    required this.windowControls,
    required this.persian,
    required this.language,
    required this.onLanguageChanged,
    required this.onThemeChanged,
  });

  final Core? core;
  final bool windowControls;
  final bool persian;
  final String language;
  final ValueChanged<String> onLanguageChanged;
  final ValueChanged<String> onThemeChanged;

  @override
  State<GozarScreen> createState() => _GozarScreenState();
}

class _GozarScreenState extends State<GozarScreen> with WindowListener {
  late final Core _core;
  late final Future<void> _startup;
  final _email = TextEditingController();
  final _password = TextEditingController();
  final _confirm = TextEditingController();
  final _code = TextEditingController();
  final _captcha = TextEditingController();
  CoreState _state = const CoreState();
  Timer? _timer;
  bool _starting = true;
  bool _busy = false;
  bool _connectionBusy = false;
  bool _updateBusy = false;
  bool _refreshing = false;
  bool _loadingServers = false;
  bool _needsCode = false;
  bool _creating = false;
  bool _adding = false;
  bool _showPassword = false;
  bool _settingsOpen = false;
  bool _windowClosing = false;
  String _settingsSection = '';
  String? _captchaImage;
  String? _error;
  String? _fatal;

  Words get words => Words(widget.persian);

  @override
  void initState() {
    super.initState();
    _core = widget.core ?? Core();
    if (widget.windowControls) windowManager.addListener(this);
    _core.onExit = () {
      if (mounted) setState(() => _fatal = 'core disconnected');
    };
    _startup = _start();
    unawaited(_startup);
  }

  @override
  void dispose() {
    _timer?.cancel();
    if (widget.windowControls) windowManager.removeListener(this);
    _core.stop();
    _email.dispose();
    _password.dispose();
    _confirm.dispose();
    _code.dispose();
    _captcha.dispose();
    super.dispose();
  }

  Future<void> _start() async {
    try {
      await _core.start();
      var reply = await _core.call('state');
      if (reply.state?.mode != 'tun' && reply.state?.live == false) {
        reply = await _core.call('mode', {'tunnel': true});
      }
      if (!mounted) return;
      setState(() {
        _state = reply.state ?? const CoreState();
        _email.text = _state.email;
        _starting = false;
      });
      widget.onLanguageChanged(_state.language);
      widget.onThemeChanged(_state.theme);
      _timer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
      if (_state.signedIn) {
        unawaited(_loadServers());
        unawaited(_usage());
      }
    } on Object catch (error) {
      if (mounted) {
        setState(() {
          _fatal = error.toString();
          _starting = false;
        });
      }
    }
  }

  void _apply(CoreReply reply) {
    if (!mounted || reply.state == null) return;
    final state = reply.state!;
    setState(() => _state = state);
    if (state.language != widget.language) {
      widget.onLanguageChanged(state.language);
    }
    widget.onThemeChanged(state.theme);
  }

  Future<void> _refresh() async {
    if (_busy || _refreshing || !_core.running) return;
    _refreshing = true;
    try {
      _apply(await _core.call('state'));
    } on Object {
      return;
    } finally {
      _refreshing = false;
    }
  }

  Future<void> _loadServers() async {
    if (_loadingServers || !_core.running) return;
    if (mounted) setState(() => _loadingServers = true);
    try {
      final reply = await _core.call('servers');
      _apply(reply);
      if (reply.error != null && mounted) setState(() => _error = '@servers');
    } on Object {
      if (mounted) setState(() => _error = '@servers');
    } finally {
      if (mounted) setState(() => _loadingServers = false);
    }
  }

  Future<void> _usage() async {
    try {
      _apply(await _core.call('usage'));
    } on Object {
      return;
    }
  }

  Future<void> _login() async {
    if (_busy) return;
    if (_email.text.trim().isEmpty || _password.text.isEmpty) {
      setState(() => _error = '@credentials');
      return;
    }
    if (_creating && _password.text != _confirm.text) {
      setState(() => _error = '@passwordMismatch');
      return;
    }
    if (_needsCode && _code.text.trim().isEmpty) {
      setState(() => _error = '@code');
      return;
    }
    if (_captchaImage != null && _captcha.text.trim().isEmpty) {
      setState(() => _error = '@captcha');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final reply = await _core.call(_creating ? 'register' : 'login', {
        'email': _email.text.trim(),
        'password': _password.text,
        'code': _code.text.trim(),
        'captcha': _captcha.text.trim(),
      });
      _apply(reply);
      if (!mounted) return;
      if (reply.captchaImage != null) {
        _captcha.clear();
        setState(() {
          _captchaImage = reply.captchaImage;
          _error = null;
        });
      } else {
        _captcha.clear();
        _captchaImage = null;
        if (reply.needsCode) {
          _code.clear();
          setState(() {
            _needsCode = true;
            _error = null;
          });
        } else if (reply.error != null) {
          setState(() => _error = reply.error);
        } else if (_state.signedIn) {
          _password.clear();
          _confirm.clear();
          _code.clear();
          _needsCode = false;
          _creating = false;
          _adding = false;
          _settingsOpen = false;
          unawaited(_loadServers());
          unawaited(_usage());
        }
      }
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _switchAuthentication() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _core.call('cancel_auth');
    } on Object {
      return;
    } finally {
      if (mounted) {
        _code.clear();
        _confirm.clear();
        _captcha.clear();
        setState(() {
          _creating = !_creating;
          _needsCode = false;
          _captchaImage = null;
          _error = null;
          _busy = false;
        });
      }
    }
  }

  Future<void> _toggleConnection() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _connectionBusy = true;
      _error = null;
    });
    try {
      final reply = await _core.call(_state.live ? 'disconnect' : 'connect');
      _apply(reply);
      if (mounted && reply.error != null) setState(() => _error = reply.error);
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _connectionBusy = false;
        });
      }
    }
  }

  Future<void> _selectServer(Place place) async {
    if (_busy || _state.live) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final reply = await _core.call('server', {'host': place.host});
      _apply(reply);
      if (reply.error != null && mounted) setState(() => _error = reply.error);
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _selectAuto() async {
    if (_busy || _state.live) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final reply = await _core.call('server', {'host': ''});
      _apply(reply);
      if (reply.error != null && mounted) setState(() => _error = reply.error);
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _signOut() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final reply = await _core.call('logout');
      _apply(reply);
      if (mounted && reply.error != null) {
        setState(() => _error = reply.error);
      } else {
        _password.clear();
        _confirm.clear();
        _code.clear();
        _captcha.clear();
        _captchaImage = null;
        _needsCode = false;
        _creating = false;
        _adding = false;
        _settingsOpen = false;
        _settingsSection = '';
        _email.clear();
      }
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _message(String error) {
    final lower = error.toLowerCase();
    if (error == '@credentials') return words.credentialsRequired;
    if (error == '@code') return words.codeRequired;
    if (error == '@passwordMismatch') return words.passwordMismatch;
    if (error == '@captcha') return words.securityRequired;
    if (lower.contains('security check') || lower.contains('security-check')) {
      return words.securityFailed;
    }
    if (lower.contains('verification link required')) return words.verifyLink;
    if (error == '@servers') return words.serverError;
    if (error == '@browser') return words.browserError;
    if (lower.contains('core not found')) return words.coreMissing;
    if (lower.contains('core disconnected') ||
        lower.contains('core not running')) {
      return words.coreStopped;
    }
    if (lower.contains('administrator') || lower.contains('needs root')) {
      return Platform.isWindows
          ? words.windowsAdminRequired
          : words.adminRequired;
    }
    if (lower.contains('timed out') || lower.contains('timeout')) {
      return words.timeout;
    }
    if (lower.contains('not signed in')) return words.signInRequired;
    if (lower.contains('socketexception') ||
        lower.contains('connection refused')) {
      return words.connectionError;
    }
    return error.replaceFirst('Bad state: ', '');
  }

  Future<void> _openPage(String address) async {
    try {
      if (Platform.isWindows) {
        await Process.start(
            'rundll32', ['url.dll,FileProtocolHandler', address]);
      } else if (Platform.isMacOS) {
        await Process.start('open', [address]);
      } else {
        await Process.start('xdg-open', [address]);
      }
    } on Object {
      if (mounted) setState(() => _error = '@browser');
    }
  }

  Future<void> _chooseLanguage() async {
    if (_busy) return;
    final choice = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.language),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final entry in [
              ('system', words.system),
              ('fa', 'پارسی'),
              ('en', 'English'),
            ])
              RadioListTile<String>(
                title: Text(entry.$2),
                value: entry.$1,
                groupValue:
                    widget.language.isEmpty ? 'system' : widget.language,
                onChanged: (value) => Navigator.pop(dialogContext, value),
              ),
          ],
        ),
      ),
    );
    if (choice == null || !mounted) return;
    try {
      final reply = await _core.call('language', {'language': choice});
      _apply(reply);
      if (reply.error != null && mounted) setState(() => _error = reply.error);
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  void _showServers() {
    if (_busy || _state.live) return;
    if (_state.servers.isEmpty) {
      unawaited(_loadServers());
      return;
    }
    showDialog<void>(
      context: context,
      builder: (dialogContext) => ServerDialog(
        places: _state.servers,
        selected: _state.server?.host,
        words: words,
        onSelect: (place) {
          Navigator.pop(dialogContext);
          unawaited(_selectServer(place));
        },
        onAuto: () {
          Navigator.pop(dialogContext);
          unawaited(_selectAuto());
        },
      ),
    );
  }

  Future<bool> _requestSetting(
      String method, Map<String, dynamic> params) async {
    try {
      final reply = await _core.call(method, params);
      _apply(reply);
      if (reply.error != null) {
        if (mounted) setState(() => _error = reply.error);
        return false;
      }
      return true;
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
      return false;
    }
  }

  Future<void> _toggleWarp(bool enabled) async {
    if (_busy) return;
    if (enabled) {
      final accepted = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          scrollable: true,
          title: Text(words.warpUdp),
          content: Text(words.warpConsent),
          actions: [
            TextButton(
              onPressed: () => unawaited(_openPage('https://www.cloudflare.com/application/terms/')),
              child: Text(words.cloudflareTerms),
            ),
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: Text(words.cancel),
            ),
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: Text(words.accept),
            ),
          ],
        ),
      );
      if (accepted != true || !mounted) return;
    } else if (_state.live || _state.connecting) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(words.warpUdp),
          content: Text(words.warpReconnect),
          actions: [
            TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: Text(words.cancel)),
            TextButton(onPressed: () => Navigator.pop(dialogContext, true), child: Text(words.accept)),
          ],
        ),
      );
      if (confirmed != true || !mounted) return;
    }
    setState(() => _busy = true);
    try {
      await _requestSetting('warp_udp', {'enabled': enabled, 'consent': enabled});
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _chooseTheme() async {
    final choice = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.appearance),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final option in [
              ('system', words.system),
              ('light', words.lightTheme),
              ('dark', words.darkTheme),
            ])
              RadioListTile<String>(
                title: Text(option.$2),
                value: option.$1,
                groupValue: _state.theme,
                onChanged: (value) => Navigator.pop(dialogContext, value),
              ),
          ],
        ),
      ),
    );
    if (choice != null) await _requestSetting('theme', {'theme': choice});
  }

  Future<void> _editChain() async {
    if (_state.live || _state.connecting) {
      await _info(words.proxyChain, words.switchDisconnect);
      return;
    }
    final value = await showDialog<String>(
      context: context,
      builder: (context) =>
          ProxyChainDialog(words: words, initial: _state.chain.join('\n')),
    );
    if (value != null) {
      final hops = value
          .split(RegExp(r'[\n,]'))
          .map((item) => item.trim())
          .where((item) => item.isNotEmpty)
          .toList();
      await _requestSetting('chain', {'hops': hops});
    }
  }

  Future<void> _info(String title, String message) async {
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: Text(words.close))
        ],
      ),
    );
  }

  Future<bool> _confirmDisconnect() async {
    if (!_state.live && !_state.connecting) return true;
    return await showDialog<bool>(
          context: context,
          builder: (dialogContext) => AlertDialog(
            title: Text(words.manageAccounts),
            content: Text(words.switchDisconnect),
            actions: [
              TextButton(
                  onPressed: () => Navigator.pop(dialogContext, false),
                  child: Text(words.cancel)),
              TextButton(
                  onPressed: () => Navigator.pop(dialogContext, true),
                  child: Text(words.disconnect)),
            ],
          ),
        ) ??
        false;
  }

  Future<void> _showAccounts({bool fromLogin = false}) async {
    if (_busy) return;
    final choice = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.savedAccounts),
        content: SizedBox(
          width: 380,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final account in _state.accounts)
                  ListTile(
                    leading:
                        const Icon(Icons.account_circle_outlined, color: soft),
                    title:
                        Text(account.email, textDirection: TextDirection.ltr),
                    subtitle: account.active ? Text(words.activeAccount) : null,
                    trailing: account.active
                        ? const Icon(Icons.check, color: soft)
                        : null,
                    onTap: account.active
                        ? null
                        : () => Navigator.pop(dialogContext, account.id),
                  ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: Text(words.close)),
          if (!fromLogin)
            TextButton.icon(
              onPressed: () => Navigator.pop(dialogContext, '__add__'),
              icon: const Icon(Icons.person_add_alt_1, color: soft),
              label: Text(words.addAccount),
            ),
        ],
      ),
    );
    if (choice == null || !mounted) return;
    if (choice == '__add__') {
      await _beginAddAccount();
    } else {
      await _switchAccount(choice);
    }
  }

  Future<void> _beginAddAccount() async {
    if (_busy || !await _confirmDisconnect() || !mounted) return;
    if (_state.live || _state.connecting) {
      setState(() => _busy = true);
      try {
        final reply = await _core.call('disconnect');
        _apply(reply);
        if (reply.error != null ||
            reply.state?.live == true ||
            reply.state?.connecting == true) {
          if (mounted) {
            setState(() => _error = reply.error ?? words.connectionError);
          }
          return;
        }
      } on Object catch (error) {
        if (mounted) setState(() => _error = error.toString());
        return;
      } finally {
        if (mounted) setState(() => _busy = false);
      }
    }
    if (!mounted) return;
    _email.clear();
    _password.clear();
    _confirm.clear();
    _code.clear();
    _captcha.clear();
    setState(() {
      _adding = true;
      _creating = false;
      _needsCode = false;
      _captchaImage = null;
      _error = null;
    });
  }

  Future<void> _cancelAdd() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _core.call('cancel_auth');
    } on Object {
      return;
    } finally {
      _password.clear();
      _confirm.clear();
      _code.clear();
      _captcha.clear();
      if (mounted) {
        setState(() {
          _adding = false;
          _creating = false;
          _needsCode = false;
          _captchaImage = null;
          _error = null;
          _busy = false;
          _settingsOpen = false;
          _email.text = _state.email;
        });
      }
    }
  }

  Future<void> _switchAccount(String id) async {
    if (_busy || !await _confirmDisconnect() || !mounted) return;
    setState(() => _busy = true);
    try {
      final reply = await _core.call('switch_account', {'id': id});
      _apply(reply);
      if (reply.error != null) {
        if (mounted) setState(() => _error = reply.error);
        return;
      }
      _email.text = _state.email;
      _password.clear();
      _confirm.clear();
      _code.clear();
      if (mounted) {
        setState(() {
          _adding = false;
          _settingsOpen = false;
        });
      }
      unawaited(_usage());
    } on Object catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _chooseSystemEmail() async {
    if (_busy) return;
    setState(() => _busy = true);
    CoreReply? reply;
    try {
      reply = await _core.call('system_accounts');
      _apply(reply);
    } on Object {
      reply = null;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    if (!mounted) return;
    if (reply == null || reply.error != null) {
      await _info(words.systemAccounts, words.systemPickerUnavailable);
      return;
    }
    if (reply.systemEmails.isEmpty) {
      await _info(words.systemAccounts, words.noSystemAccounts);
      return;
    }
    final selected = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.systemAccounts),
        content: SizedBox(
          width: 380,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                for (final email in reply!.systemEmails)
                  ListTile(
                    leading:
                        const Icon(Icons.account_circle_outlined, color: soft),
                    title: Text(email, textDirection: TextDirection.ltr),
                    onTap: () => Navigator.pop(dialogContext, email),
                  ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext),
              child: Text(words.cancel))
        ],
      ),
    );
    if (selected != null && mounted) _email.text = selected;
  }

  Future<void> _showDonation() async {
    const address = 'UQDxiU-jPFK_TgB0wOzZndhf3IKJLTLm8Vg_Uaif7_C2eCL3';
    final copy = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.donate),
        content: SizedBox(
          width: 380,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(words.donateMessage),
              const SizedBox(height: 16),
              const SelectableText(address, textDirection: TextDirection.ltr),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: Text(words.close)),
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: Text(words.copyAddress)),
        ],
      ),
    );
    if (copy == true) {
      await Clipboard.setData(const ClipboardData(text: address));
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(words.addressCopied)));
      }
    }
  }

  Future<void> _cancelUpdateOperation() async {
    try {
      await _core.call('cancel_update');
    } on Object {
      // The core may already have disconnected; never claim cancellation succeeded.
    }
  }

  Future<CoreReply?> _updateOperation(String method, String message,
      [Map<String, dynamic>? params]) async {
    if (!mounted) return null;
    final navigator = Navigator.of(context, rootNavigator: true);
    var cancelled = false;
    late final DialogRoute<void> route;
    route = DialogRoute<void>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: Text(words.updates),
        content: Row(children: [
          const SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(strokeWidth: 2)),
          const SizedBox(width: 16),
          Expanded(child: Text(message)),
        ]),
        actions: [
          TextButton(
            onPressed: () {
              cancelled = true;
              unawaited(_cancelUpdateOperation());
              if (route.isActive) navigator.removeRoute(route);
            },
            child: Text(words.cancel),
          ),
        ],
      ),
    );
    unawaited(navigator.push(route));
    late final CoreReply reply;
    try {
      reply = await _core.call(method, params);
    } on Object {
      reply = const CoreReply(error: 'update operation failed');
    } finally {
      if (navigator.mounted && route.isActive) navigator.removeRoute(route);
    }
    if (cancelled || !mounted) return null;
    if (reply.error != null) {
      await _info(
          words.updates,
          method == 'download_update'
              ? words.updateDownloadFailed
              : words.updateFailed);
      return null;
    }
    return reply;
  }

  Future<void> _openUpdateFolder(String path) async {
    try {
      final directory = File(path).parent.path;
      if (Platform.isWindows) {
        await Process.start('explorer.exe', [directory]);
      } else if (Platform.isMacOS) {
        await Process.start('open', [directory]);
      } else {
        await Process.start('xdg-open', [directory]);
      }
    } on Object {
      if (mounted) setState(() => _error = '@browser');
    }
  }

  Future<void> _checkUpdates() async {
    if (_updateBusy) return;
    _updateBusy = true;
    try {
      final reply =
          await _updateOperation('check_update', words.updateChecking);
      if (reply == null || !mounted) return;
      final update = reply.update;
      if (update == null) {
        await _info(words.updates, words.updateFailed);
        return;
      }
      if (!update.newer) {
        await _info(words.updates, words.updateCurrent);
        return;
      }
      if (update.packages.isEmpty) {
        await _info(words.updates, words.updateNoPackage);
        return;
      }
      final selected = await showDialog<UpdatePackage>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text('${words.updateAvailable} ${update.version}'),
          content: SizedBox(
            width: 380,
            height: math.min(260.0, 55.0 + update.packages.length * 68.0),
            child: Column(children: [
              Text(words.updateChoosePackage),
              Expanded(
                  child: ListView(
                children: [
                  for (final package in update.packages)
                    ListTile(
                      title: Text(package.name,
                          maxLines: 1, overflow: TextOverflow.ellipsis),
                      subtitle:
                          Text('${package.kind} · ${_size(package.size)}'),
                      onTap: () => Navigator.pop(dialogContext, package),
                    ),
                ],
              )),
            ]),
          ),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(dialogContext),
                child: Text(words.close))
          ],
        ),
      );
      if (selected == null || !mounted) return;
      final downloaded = await _updateOperation(
          'download_update', words.updateDownloading, {'id': selected.id});
      if (downloaded == null || !mounted) return;
      final path = downloaded.downloadedPath;
      if (path == null || path.isEmpty) {
        await _info(words.updates, words.updateDownloadFailed);
        return;
      }
      final openFolder = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(words.updates),
          content: SizedBox(
              width: 380,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(words.updateDownloaded),
                  const SizedBox(height: 12),
                  SelectableText(path, textDirection: TextDirection.ltr),
                ],
              )),
          actions: [
            TextButton(
                onPressed: () => Navigator.pop(dialogContext, false),
                child: Text(words.close)),
            TextButton(
                onPressed: () async {
                  await Clipboard.setData(ClipboardData(text: path));
                  if (dialogContext.mounted) {
                    Navigator.pop(dialogContext, false);
                  }
                },
                child: Text(words.updateCopyPath)),
            TextButton(
                onPressed: () => Navigator.pop(dialogContext, true),
                child: Text(words.updateOpenFolder)),
          ],
        ),
      );
      if (openFolder == true) await _openUpdateFolder(path);
    } finally {
      _updateBusy = false;
    }
  }

  void _showSettings() {
    setState(() {
      _settingsOpen = true;
      _settingsSection = '';
    });
  }

  void _backFromSettings() {
    setState(() {
      if (_settingsSection.isEmpty) {
        _settingsOpen = false;
      } else {
        _settingsSection = '';
      }
    });
  }

  @override
  void onWindowClose() {
    unawaited(_closeWindow());
  }

  Future<void> _closeWindow() async {
    if (_windowClosing || !widget.windowControls) return;
    setState(() => _windowClosing = true);
    try {
      await _startup.timeout(const Duration(seconds: 45));
      if (_core.running) {
        final reply =
            await _core.call('disconnect').timeout(const Duration(seconds: 45));
        if (reply.error != null ||
            reply.state == null ||
            reply.state!.live ||
            reply.state!.connecting) {
          throw StateError('disconnect failed');
        }
        _apply(reply);
        await _core.shutdown();
      }
      await windowManager.setPreventClose(false);
      await windowManager.close();
    } on Object {
      if (mounted) {
        setState(() {
          _windowClosing = false;
          _error = words.closeFailed;
        });
        if (_settingsOpen || !_state.signedIn || _adding || _fatal != null) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text(words.closeFailed)),
          );
        }
      }
    }
  }

  Widget _titleBar() {
    final isHome = _state.signedIn && !_adding && !_settingsOpen;
    final foreground = isHome
        ? const Color(0xFFF6F7F8)
        : Theme.of(context).colorScheme.onSurface;
    return SizedBox(
      height: 56,
      child: Row(children: [
        if (_state.signedIn && !_adding)
          SizedBox(
            width: 48,
            child: IconButton(
              tooltip: words.settings,
              onPressed: _showSettings,
              icon: Icon(Icons.menu_rounded, color: foreground, size: 20),
            ),
          )
        else
          const SizedBox(width: 16),
        Expanded(
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onPanStart: widget.windowControls
                ? (_) => unawaited(windowManager.startDragging())
                : null,
            child: Row(children: [
              Image.asset('assets/firefox.png', width: 25, height: 25),
              const SizedBox(width: 8),
              Text('Gozar',
                  style: TextStyle(
                      color: foreground,
                      fontSize: 17,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0)),
            ]),
          ),
        ),
        if (widget.windowControls) ...[
          _windowButton(Icons.remove, words.minimize,
              () => windowManager.minimize(), foreground),
          _windowButton(Icons.close, words.close, _closeWindow, foreground),
          const SizedBox(width: 8),
        ],
      ]),
    );
  }

  Widget _windowButton(IconData icon, String tooltip,
          Future<void> Function() action, Color color) =>
      SizedBox(
        width: 40,
        height: 40,
        child: IconButton(
          tooltip: tooltip,
          padding: EdgeInsets.zero,
          splashRadius: 18,
          icon: Icon(icon, color: color, size: 18),
          onPressed: _windowClosing ? null : () => unawaited(action()),
        ),
      );

  Widget _settingsScreen() {
    final title = switch (_settingsSection) {
      'account' => words.account,
      'connection' => words.connectionSettings,
      'display' => words.displaySettings,
      'general' => words.generalSettings,
      _ => words.settings,
    };
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 460),
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 8, 16, 4),
              child: Row(children: [
                IconButton(
                    tooltip: words.back,
                    onPressed: _backFromSettings,
                    icon: const Icon(Icons.arrow_back)),
                const SizedBox(width: 8),
                Expanded(
                    child: Text(title,
                        style: Theme.of(context).textTheme.titleLarge)),
              ]),
            ),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
                child: AnimatedSwitcher(
                  duration: const Duration(milliseconds: 180),
                  child: KeyedSubtree(
                      key: ValueKey(_settingsSection),
                      child: _settingsContent()),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _settingTile(
          IconData icon, String title, String subtitle, VoidCallback? onTap) =>
      Card(
        margin: const EdgeInsets.only(bottom: 8),
        child: ListTile(
          leading: Icon(icon, color: soft),
          title: Text(title),
          subtitle: subtitle.isEmpty
              ? null
              : Text(subtitle, maxLines: 2, overflow: TextOverflow.ellipsis),
          trailing: onTap == null
              ? null
              : const Icon(Icons.chevron_right, color: soft),
          onTap: onTap,
        ),
      );

  Widget _settingsContent() {
    if (_settingsSection.isEmpty) {
      return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Padding(
            padding: const EdgeInsets.fromLTRB(4, 8, 4, 12),
            child: Text(words.settingsSections,
                style: TextStyle(color: muted(context)))),
        _settingTile(
            Icons.account_circle_outlined,
            words.account,
            words.accountSectionDetail,
            () => setState(() => _settingsSection = 'account')),
        _settingTile(
            Icons.alt_route,
            words.connectionSettings,
            words.connectionSectionDetail,
            () => setState(() => _settingsSection = 'connection')),
        _settingTile(
            Icons.palette_outlined,
            words.displaySettings,
            words.displaySectionDetail,
            () => setState(() => _settingsSection = 'display')),
        _settingTile(
            Icons.info_outline,
            words.generalSettings,
            words.generalSectionDetail,
            () => setState(() => _settingsSection = 'general')),
      ]);
    }
    if (_settingsSection == 'account') {
      return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        _settingTile(Icons.account_circle_outlined, words.manageAccounts,
            _state.email, () => unawaited(_showAccounts())),
        const SizedBox(height: 12),
        FilledButton.icon(
          style: FilledButton.styleFrom(
              backgroundColor: const Color(0xFFA3001F),
              foregroundColor: Colors.white),
          onPressed: _busy ? null : () => unawaited(_signOut()),
          icon: const Icon(Icons.logout),
          label: Text(words.signOut),
        ),
      ]);
    }
    if (_settingsSection == 'display') {
      return Column(children: [
        _settingTile(
            Icons.language,
            words.language,
            widget.language == 'fa'
                ? 'پارسی'
                : widget.language == 'en'
                    ? 'English'
                    : words.system,
            () => unawaited(_chooseLanguage())),
        _settingTile(
            Icons.palette_outlined,
            words.appearance,
            _state.theme == 'light'
                ? words.lightTheme
                : _state.theme == 'dark'
                    ? words.darkTheme
                    : words.system,
            () => unawaited(_chooseTheme())),
      ]);
    }
    if (_settingsSection == 'connection') {
      return Column(children: [
        Card(
            margin: const EdgeInsets.only(bottom: 8),
            child: SwitchListTile(
              secondary: const Icon(Icons.history, color: soft),
              title: Text(words.rememberServer),
              value: _state.recents,
              onChanged: _busy
                  ? null
                  : (value) =>
                      unawaited(_requestSetting('recents', {'enabled': value})),
            )),
        Card(
            margin: const EdgeInsets.only(bottom: 8),
            child: SwitchListTile(
              secondary: const Icon(Icons.public, color: soft),
              title: Text(words.warpUdp),
              value: _state.warpUdp,
              onChanged: _busy ? null : (value) => unawaited(_toggleWarp(value)),
            )),
        _settingTile(
            Icons.alt_route,
            words.proxyChain,
            _state.chain.isEmpty
                ? words.noProxyChain
                : _state.chain.join(' → '),
            () => unawaited(_editChain())),
        _settingTile(Icons.route_outlined, words.routing,
            words.desktopRoutingLimits, null),
      ]);
    }
    return Column(children: [
      _settingTile(Icons.system_update_alt, words.updates, '',
          () => unawaited(_checkUpdates())),
      _settingTile(
          Icons.info_outline,
          words.about,
          'Gozar ${_state.version} · AGPL-3.0',
          () => unawaited(
              _openPage('https://github.com/Ammpapsoe/GozarFox'))),
      _settingTile(Icons.favorite_outline, words.donate, 'TON',
          () => unawaited(_showDonation())),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    final isHome = _state.signedIn &&
        !_adding &&
        !_settingsOpen &&
        !_starting &&
        _fatal == null;
    final shellColor = isHome
        ? const Color(0xFF121720)
        : Theme.of(context).scaffoldBackgroundColor;
    return Material(
      color: Colors.transparent,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(widget.windowControls ? 20 : 0),
        child: Stack(children: [
          Positioned.fill(
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: shellColor,
                gradient: isHome
                    ? const RadialGradient(
                        center: Alignment(.38, -.2),
                        radius: 1.05,
                        colors: [
                          Color(0xFF524322),
                          Color(0xFF25251F),
                          Color(0xFF10151E)
                        ],
                        stops: [0, .48, 1],
                      )
                    : null,
              ),
            ),
          ),
          Scaffold(
            backgroundColor: Colors.transparent,
            body: Column(children: [
              _titleBar(),
              Expanded(
                child: SafeArea(
                  top: false,
                  child: _starting
                      ? const Center(child: CircularProgressIndicator())
                      : _fatal != null
                          ? Center(
                              child: Padding(
                                  padding: const EdgeInsets.all(32),
                                  child: Text(_message(_fatal!),
                                      textAlign: TextAlign.center)))
                          : _state.signedIn && !_adding
                              ? _settingsOpen
                                  ? _settingsScreen()
                                  : _home()
                              : _loginScreen(),
                ),
              ),
            ]),
          ),
          if (widget.windowControls)
            Positioned.fill(
              child: IgnorePointer(
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(color: const Color(0x66575C67)),
                  ),
                ),
              ),
            ),
        ]),
      ),
    );
  }

  Widget _loginScreen() => Stack(
        children: [
          Positioned(
            left: -55,
            right: -55,
            top: -100,
            child: IgnorePointer(
              child: Opacity(
                opacity: 0.32,
                child: Image.asset('assets/firefox.png', height: 420),
              ),
            ),
          ),
          Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(24, 96, 24, 24),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 440),
                child: Container(
                  padding: const EdgeInsets.all(24),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surface,
                    borderRadius:
                        BorderRadius.circular(GzRadius.card + GzSpace.xs),
                    border: Border.all(
                        color: Theme.of(context).colorScheme.outlineVariant),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        _creating ? words.createTitle : words.signIn,
                        style: Theme.of(context)
                            .textTheme
                            .headlineMedium
                            ?.copyWith(
                              fontWeight: FontWeight.w700,
                            ),
                      ),
                      const SizedBox(height: 12),
                      Text(
                        _creating
                            ? words.createDescription
                            : words.signInDescription,
                        style: TextStyle(color: muted(context)),
                      ),
                      const SizedBox(height: 24),
                      TextField(
                        controller: _email,
                        enabled: !_busy,
                        textDirection: TextDirection.ltr,
                        keyboardType: TextInputType.emailAddress,
                        autofillHints: const [AutofillHints.email],
                        decoration: InputDecoration(
                          labelText: words.email,
                          prefixIcon:
                              Icon(Icons.email_outlined, color: muted(context)),
                          suffixIcon: IconButton(
                            tooltip: words.systemAccounts,
                            onPressed: _busy ? null : _chooseSystemEmail,
                            icon: const Icon(Icons.account_circle_outlined,
                                color: soft),
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),
                      TextField(
                        controller: _password,
                        enabled: !_busy,
                        textDirection: TextDirection.ltr,
                        obscureText: !_showPassword,
                        autofillHints: const [AutofillHints.password],
                        onSubmitted: (_) => _login(),
                        decoration: InputDecoration(
                          labelText: words.password,
                          prefixIcon:
                              Icon(Icons.lock_outline, color: muted(context)),
                          suffixIcon: IconButton(
                            onPressed: () =>
                                setState(() => _showPassword = !_showPassword),
                            icon: Icon(
                              _showPassword
                                  ? Icons.visibility_off_outlined
                                  : Icons.visibility_outlined,
                              color: muted(context),
                            ),
                          ),
                        ),
                      ),
                      if (_creating) ...[
                        const SizedBox(height: 12),
                        TextField(
                          controller: _confirm,
                          enabled: !_busy,
                          textDirection: TextDirection.ltr,
                          obscureText: !_showPassword,
                          autofillHints: const [AutofillHints.newPassword],
                          onSubmitted: (_) => _login(),
                          decoration: InputDecoration(
                            labelText: words.repeatPassword,
                            prefixIcon:
                                Icon(Icons.lock_outline, color: muted(context)),
                          ),
                        ),
                      ],
                      if (_needsCode) ...[
                        const SizedBox(height: 12),
                        Text(words.checkEmail,
                            style: TextStyle(color: muted(context))),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _code,
                          enabled: !_busy,
                          textDirection: TextDirection.ltr,
                          keyboardType: TextInputType.text,
                          autocorrect: false,
                          onSubmitted: (_) => _login(),
                          decoration: InputDecoration(
                            labelText: words.verificationCode,
                            prefixIcon:
                                Icon(Icons.pin_outlined, color: muted(context)),
                          ),
                        ),
                      ],
                      if (_captchaImage != null) ...[
                        const SizedBox(height: 12),
                        Text(words.securityCheck,
                            style: Theme.of(context).textTheme.titleSmall),
                        const SizedBox(height: 8),
                        Text(words.securityInstruction,
                            style: TextStyle(color: muted(context))),
                        const SizedBox(height: 8),
                        Semantics(
                          label: words.securityCheck,
                          child: Image.memory(
                            base64Decode(_captchaImage!.split(',').last),
                            height: 88,
                            fit: BoxFit.contain,
                          ),
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _captcha,
                          enabled: !_busy,
                          textDirection: TextDirection.ltr,
                          autocorrect: false,
                          onSubmitted: (_) => _login(),
                          decoration: InputDecoration(
                            labelText: words.securityAnswer,
                            prefixIcon: Icon(Icons.shield_outlined,
                                color: muted(context)),
                          ),
                        ),
                      ],
                      Align(
                        alignment: AlignmentDirectional.centerEnd,
                        child: TextButton(
                          onPressed: _busy
                              ? null
                              : () => _openPage(_creating
                                  ? 'https://accounts.firefox.com/signup'
                                  : 'https://accounts.firefox.com/reset_password'),
                          child: Text(_creating
                              ? words.createOnMozilla
                              : words.forgotPassword),
                        ),
                      ),
                      if (_error != null) ...[
                        ErrorText(_message(_error!)),
                        const SizedBox(height: 12),
                      ],
                      const SizedBox(height: 8),
                      FilledButton(
                        onPressed: _busy ? null : _login,
                        child: _busy
                            ? const SmallProgress()
                            : Text(_needsCode
                                ? (_creating
                                    ? words.verifyAndCreate
                                    : words.verifyAndSignIn)
                                : (_creating
                                    ? words.createAction
                                    : words.signIn)),
                      ),
                      const SizedBox(height: 12),
                      TextButton(
                        onPressed: _busy ? null : _switchAuthentication,
                        child: FittedBox(
                          fit: BoxFit.scaleDown,
                          child: Text(
                            _creating ? words.haveAccount : words.createAccount,
                            maxLines: 1,
                          ),
                        ),
                      ),
                      if (!_adding && _state.accounts.isNotEmpty)
                        TextButton.icon(
                          onPressed: _busy
                              ? null
                              : () => _showAccounts(fromLogin: true),
                          icon: const Icon(Icons.switch_account_outlined,
                              color: soft),
                          label: Text(words.savedAccounts),
                        ),
                      if (_adding)
                        TextButton(
                          onPressed: _busy ? null : _cancelAdd,
                          child: Text(words.cancel),
                        ),
                      const SizedBox(height: 12),
                      Text(
                        words.passwordNote,
                        textAlign: TextAlign.center,
                        style: Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.copyWith(color: muted(context)),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          PositionedDirectional(
            top: 8,
            end: 12,
            child: TextButton.icon(
              onPressed: _busy ? null : _chooseLanguage,
              icon: const Icon(Icons.language, size: 20),
              label: Text(words.language),
            ),
          ),
        ],
      );

  Widget _home() {
    const secondary = Color(0xFFAAADB6);
    const foreground = Color(0xFFF6F7F8);
    final server = _state.server;
    final selected = server != null && server.host.isNotEmpty;
    final location = selected
        ? server.label(widget.persian)
        : _state.live
            ? words.unavailable
            : words.bestServer;
    final connecting = _connectionBusy || _state.connecting;
    final status = connecting
        ? (_state.live ? words.disconnecting : words.connecting)
        : (_state.live ? words.connected : words.disconnected);
    final canSelect = !_busy && !_state.live && !_state.connecting;
    return Column(children: [
      Expanded(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
          child:
              Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Expanded(
                child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Wrap(
                          crossAxisAlignment: WrapCrossAlignment.center,
                          spacing: 8,
                          runSpacing: 4,
                          children: [
                            Container(
                              padding: const EdgeInsets.symmetric(
                                  horizontal: 8, vertical: 4),
                              decoration: BoxDecoration(
                                color: _state.live
                                    ? const Color(0xFF245248)
                                    : const Color(0xFF3A404B),
                                borderRadius: BorderRadius.circular(GzRadius.s),
                              ),
                              child: Text(status,
                                  style: const TextStyle(
                                      color: foreground,
                                      fontSize: 11,
                                      fontWeight: FontWeight.w700)),
                            ),
                            Text(
                                server != null && server.port > 0
                                    ? 'TUN · Relay ${server.port}'
                                    : 'TUN',
                                textDirection: TextDirection.ltr,
                                style: const TextStyle(
                                    color: secondary,
                                    fontSize: 12,
                                    fontWeight: FontWeight.w600)),
                            Tooltip(
                              message: words.tunnelNotice,
                              child: InkWell(
                                borderRadius: BorderRadius.circular(18),
                                onTap: () =>
                                    unawaited(_info('TUN', words.tunnelNotice)),
                                child: const Padding(
                                  padding: EdgeInsets.all(4),
                                  child: Icon(Icons.info_outline,
                                      color: secondary, size: 15),
                                ),
                              ),
                            ),
                          ]),
                      const SizedBox(height: 12),
                      Text(_state.live ? words.tapDisconnect : words.tapConnect,
                          style:
                              const TextStyle(color: secondary, fontSize: 12)),
                    ]),
              ),
              const SizedBox(width: 12),
              Tooltip(
                message: _state.live ? words.disconnect : words.connect,
                child: Semantics(
                  button: true,
                  label: _state.live ? words.disconnect : words.connect,
                  child: Material(
                    color: foreground,
                    shape: const CircleBorder(),
                    child: InkWell(
                      customBorder: const CircleBorder(),
                      onTap: _busy || _windowClosing ? null : _toggleConnection,
                      child: SizedBox(
                        width: 78,
                        height: 78,
                        child: Center(
                          child: connecting
                              ? const SizedBox(
                                  width: 25,
                                  height: 25,
                                  child: CircularProgressIndicator(
                                      color: Color(0xFF191C22), strokeWidth: 3))
                              : const Icon(Icons.power_settings_new_rounded,
                                  color: Color(0xFF191C22), size: 39),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ]),
            const SizedBox(height: 12),
            InkWell(
              borderRadius: BorderRadius.circular(GzRadius.m),
              onTap: canSelect ? _showServers : null,
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(children: [
                  CountryFlag(code: server?.code, size: 30),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Text(location,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context)
                            .textTheme
                            .headlineSmall
                            ?.copyWith(color: foreground)),
                  ),
                  if (canSelect)
                    Icon(
                        widget.persian
                            ? Icons.chevron_left
                            : Icons.chevron_right,
                        color: secondary,
                        size: 21),
                ]),
              ),
            ),
            const SizedBox(height: 12),
            Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Icon(Icons.device_hub_outlined, color: secondary, size: 17),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                    '${words.tunnelDevice}: ${_state.live && _state.device.isNotEmpty ? _state.device : '—'}',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: secondary, fontSize: 12)),
              ),
              const SizedBox(width: 8),
              Flexible(
                child: Text(
                    '${words.exitIp}: ${_state.live && _state.exit.isNotEmpty ? _state.exit : '—'}',
                    textAlign: TextAlign.end,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: secondary, fontSize: 12)),
              ),
            ]),
            const SizedBox(height: 12),
            Wrap(spacing: 20, runSpacing: 8, children: [
              _trafficLabel(Icons.south_rounded, words.download,
                  _state.live ? _speed(_state.rateDown) : '—'),
              _trafficLabel(Icons.north_rounded, words.upload,
                  _state.live ? _speed(_state.rateUp) : '—'),
              if (_state.live)
                Text('${words.totalUsage}: ${_size(_state.down + _state.up)}',
                    style: const TextStyle(color: secondary, fontSize: 11)),
            ]),
            if (_error != null || _state.fault.isNotEmpty) ...[
              const SizedBox(height: 10),
              ErrorText(_message(_error ?? _state.fault)),
            ],
          ]),
        ),
      ),
      Container(
        decoration: const BoxDecoration(
            border: Border(top: BorderSide(color: Color(0xFF31343A)))),
        child: Row(children: [
          Tooltip(
            message: words.unavailable,
            child: Padding(
              padding: const EdgeInsetsDirectional.fromSTEB(20, 8, 12, 8),
              child: Row(children: [
                const Icon(Icons.shield_outlined,
                    color: Color(0xFF858A94), size: 20),
                const SizedBox(width: 8),
                Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(words.firewall,
                      style: const TextStyle(color: secondary, fontSize: 12)),
                  Text(words.unavailable,
                      style: Theme.of(context)
                          .textTheme
                          .labelSmall
                          ?.copyWith(color: const Color(0xFF868C97))),
                ]),
              ]),
            ),
          ),
          Container(width: 1, height: 36, color: const Color(0xFF31343A)),
          Expanded(
            child: TextButton.icon(
              onPressed: canSelect ? _showServers : null,
              icon: const Icon(Icons.public, size: 18),
              label: Text(words.locations,
                  maxLines: 1, overflow: TextOverflow.ellipsis),
              style: TextButton.styleFrom(
                foregroundColor: foreground,
                disabledForegroundColor: secondary,
                padding:
                    const EdgeInsets.symmetric(horizontal: 12, vertical: 16),
              ),
            ),
          ),
        ]),
      ),
      Container(
        color: const Color(0xFF0D1016),
        padding: const EdgeInsetsDirectional.fromSTEB(20, 8, 8, 8),
        child: Row(children: [
          Icon(_state.limit > 0 ? Icons.donut_small : Icons.circle_outlined,
              color: _state.limit > 0 ? const Color(0xFF70CCB0) : secondary,
              size: 17),
          const SizedBox(width: 7),
          Expanded(
            child: Text(
                _state.limit > 0
                    ? '${_size(_state.remaining)} ${words.remaining}'
                    : words.usageUnavailable,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: secondary, fontSize: 12)),
          ),
          TextButton(
            onPressed: _busy ? null : () => unawaited(_showAccounts()),
            style: TextButton.styleFrom(
              visualDensity: VisualDensity.compact,
              foregroundColor: const Color(0xFFE5E6EA),
            ),
            child: Text(words.account,
                maxLines: 1, overflow: TextOverflow.ellipsis),
          ),
        ]),
      ),
    ]);
  }

  Widget _trafficLabel(IconData icon, String label, String value) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, color: const Color(0xFFA9AFB9), size: 16),
          const SizedBox(width: 6),
          Text('$label  $value',
              style: const TextStyle(color: Color(0xFFCFD1D6), fontSize: 12)),
        ],
      );
}

class CountryFlag extends StatelessWidget {
  const CountryFlag({super.key, required this.code, this.size = 36});

  final String? code;
  final double size;

  @override
  Widget build(BuildContext context) {
    final value = code?.toLowerCase() ?? '';
    Widget fallback() => CircleAvatar(
          radius: size / 2,
          backgroundColor: const Color(0x334D5965),
          child: Icon(Icons.public,
              color: const Color(0xFFBFC5CD), size: size * .55),
        );
    if (!RegExp(r'^[a-z]{2}$').hasMatch(value)) return fallback();
    return Semantics(
      label: code!.toUpperCase(),
      child: ClipOval(
          child: SizedBox(
              width: size,
              height: size,
              child: Image.asset('assets/flags/$value.png',
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) => fallback()))),
    );
  }
}

class SmallProgress extends StatelessWidget {
  const SmallProgress({super.key});

  @override
  Widget build(BuildContext context) => const SizedBox(
        width: 22,
        height: 22,
        child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
      );
}

class ErrorText extends StatelessWidget {
  const ErrorText(this.message, {super.key});

  final String message;

  @override
  Widget build(BuildContext context) => Text(
        message,
        textAlign: TextAlign.center,
        style: const TextStyle(color: soft),
      );
}

class RateCard extends StatelessWidget {
  const RateCard(
      {super.key,
      required this.icon,
      required this.label,
      required this.value});

  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Card(
        margin: EdgeInsets.zero,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(icon, color: soft, size: 19),
                  const SizedBox(width: 5),
                  Text(label, style: TextStyle(color: muted(context))),
                ],
              ),
              const SizedBox(height: 5),
              Text(value, textDirection: TextDirection.ltr),
            ],
          ),
        ),
      );
}

class ProxyChainDialog extends StatefulWidget {
  const ProxyChainDialog(
      {super.key, required this.words, required this.initial});

  final Words words;
  final String initial;

  @override
  State<ProxyChainDialog> createState() => _ProxyChainDialogState();
}

class _ProxyChainDialogState extends State<ProxyChainDialog> {
  late final TextEditingController field;

  @override
  void initState() {
    super.initState();
    field = TextEditingController(text: widget.initial);
  }

  @override
  void dispose() {
    field.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: Text(widget.words.proxyChain),
        content: SizedBox(
          width: 380,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(widget.words.proxyChainDetail),
              const SizedBox(height: 12),
              TextField(
                controller: field,
                textDirection: TextDirection.ltr,
                minLines: 3,
                maxLines: 6,
                decoration:
                    InputDecoration(hintText: widget.words.proxyChainHint),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(context),
              child: Text(widget.words.cancel)),
          TextButton(
              onPressed: () => Navigator.pop(context, field.text),
              child: Text(widget.words.save)),
        ],
      );
}

class ServerDialog extends StatefulWidget {
  const ServerDialog({
    super.key,
    required this.places,
    required this.selected,
    required this.words,
    required this.onSelect,
    required this.onAuto,
  });

  final List<Place> places;
  final String? selected;
  final Words words;
  final ValueChanged<Place> onSelect;
  final VoidCallback onAuto;

  @override
  State<ServerDialog> createState() => _ServerDialogState();
}

class _ServerDialogState extends State<ServerDialog> {
  String query = '';

  @override
  Widget build(BuildContext context) {
    final filtered = widget.places.where((place) {
      final value =
          '${place.code} ${place.country} ${place.city} ${place.label(widget.words.fa)}';
      return value.toLowerCase().contains(query.toLowerCase());
    }).toList();
    return AlertDialog(
      title: Text(widget.words.chooseServer),
      content: SizedBox(
        width: 360,
        height: 390,
        child: Column(
          children: [
            ListTile(
              leading: const Icon(Icons.auto_awesome, color: soft),
              title: Text(widget.words.autoServer),
              trailing: (widget.selected == null || widget.selected!.isEmpty)
                  ? const Icon(Icons.check, color: soft)
                  : null,
              onTap: widget.onAuto,
            ),
            const Divider(height: 1),
            TextField(
              onChanged: (value) => setState(() => query = value.trim()),
              decoration: InputDecoration(
                hintText: widget.words.searchServers,
                prefixIcon: const Icon(Icons.search, color: soft),
              ),
            ),
            const SizedBox(height: 10),
            Expanded(
              child: filtered.isEmpty
                  ? Center(child: Text(widget.words.noServers))
                  : ListView.builder(
                      itemCount: filtered.length,
                      itemBuilder: (context, index) {
                        final place = filtered[index];
                        return ListTile(
                          leading: CountryFlag(code: place.code),
                          title: Text(place.label(widget.words.fa)),
                          trailing: place.host == widget.selected
                              ? const Icon(Icons.check, color: soft)
                              : null,
                          onTap: () => widget.onSelect(place),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(widget.words.close)),
      ],
    );
  }
}

String _size(int value) {
  if (value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  final index =
      math.min((math.log(value) / math.ln2 / 10).floor(), units.length - 1);
  final number = value / math.pow(1024, index);
  return '${number.toStringAsFixed(index == 0 ? 0 : 1)} ${units[index]}';
}

String _speed(int bytes) => '${_size(math.max(0, bytes))}/s';
