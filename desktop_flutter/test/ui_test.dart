import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gozar/core.dart';
import 'package:gozar/main.dart';

class FakeCore extends Core {
  FakeCore({
    this.signedIn = false,
    this.language = '',
    this.twoStep = false,
    this.requiresCaptcha = false,
    this.systemEmails = const ['system@example.test'],
  }) {
    if (signedIn) saved.add('example@example.com');
  }

  bool signedIn;
  final bool twoStep;
  final bool requiresCaptcha;
  final List<String> systemEmails;
  final List<String> saved = [];
  String activeEmail = 'example@example.com';
  String theme = 'system';
  bool recents = false;
  bool warpUdp = false;
  Map<String, dynamic>? lastWarp;
  List<String> chain = [];
  bool live = false;
  int rateDown = 0;
  int rateUp = 0;
  int down = 0;
  int up = 0;
  int limit = 0;
  int remaining = 0;
  String exit = '';
  String device = '';
  bool disconnectFailure = false;
  bool captchaDone = false;
  int loginCalls = 0;
  Map<String, dynamic>? lastAuth;
  String language;
  bool isRunning = false;
  String? selectedHost;
  final requests = <String>[];

  static const places = [
    Place(
      code: 'REC',
      country: 'Recommended',
      city: '',
      host: 'recommended.example',
      recommended: true,
    ),
    Place(
      code: 'NL',
      country: 'Netherlands',
      city: 'Amsterdam',
      host: 'amsterdam.example',
      recommended: false,
    ),
  ];

  CoreState get current => CoreState(
        signedIn: signedIn,
        email: signedIn ? activeEmail : '',
        language: language,
        theme: theme,
        recents: recents,
        warpUdp: warpUdp,
        chain: chain,
        live: live,
        rateDown: rateDown,
        rateUp: rateUp,
        down: down,
        up: up,
        limit: limit,
        remaining: remaining,
        exit: exit,
        device: device,
        accounts: saved
            .map((email) => SavedAccount(
                id: 'email:$email',
                email: email,
                active: signedIn && email == activeEmail))
            .toList(),
        mode: 'tun',
        version: '1.9.0',
        servers: signedIn ? places : const [],
        server: places.firstWhere(
          (place) => place.host == selectedHost,
          orElse: () => places.first,
        ),
      );

  @override
  bool get running => isRunning;

  @override
  Future<void> start() async => isRunning = true;

  @override
  Future<CoreReply> call(String method, [Map<String, dynamic>? params]) async {
    requests.add(method);
    if (method == 'language') {
      final choice = params!['language'] as String;
      language = choice == 'system' ? '' : choice;
    }
    if (method == 'server') selectedHost = params!['host'] as String;
    if (method == 'theme') theme = params!['theme'] as String;
    if (method == 'recents') recents = params!['enabled'] as bool;
    if (method == 'warp_udp') {
      lastWarp = Map<String, dynamic>.from(params!);
      warpUdp = params['enabled'] as bool;
    }
    if (method == 'chain') chain = (params!['hops'] as List).cast<String>();
    if (method == 'connect') live = true;
    if (method == 'disconnect') {
      if (disconnectFailure) {
        return CoreReply(state: current, error: 'disconnect failed');
      }
      live = false;
    }
    if (method == 'system_accounts') {
      return CoreReply(state: current, systemEmails: systemEmails);
    }
    if (method == 'switch_account') {
      final id = params!['id'] as String;
      activeEmail = saved.firstWhere((email) => id == 'email:$email');
      signedIn = true;
      live = false;
    }
    if (method == 'login' || method == 'register') {
      loginCalls++;
      lastAuth = Map<String, dynamic>.from(params!);
      if (requiresCaptcha && !captchaDone) {
        if (params['captcha'] != 'human') {
          return CoreReply(
            state: current,
            error: 'security check required',
            captchaImage: 'data:image/png;base64,'
                'iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP89esXAwMDEwMDAwMDAwAjUgLyJQhtpgAAAABJRU5ErkJggg==',
          );
        }
        captchaDone = true;
      }
      if ((params['code'] as String).isEmpty || (twoStep && loginCalls == 2)) {
        return CoreReply(state: current, error: 'code needed', needsCode: true);
      }
      activeEmail = params['email'] as String;
      if (!saved.contains(activeEmail)) saved.add(activeEmail);
      signedIn = true;
    }
    if (method == 'logout') {
      saved.remove(activeEmail);
      signedIn = false;
      live = false;
    }
    return CoreReply(state: current);
  }

  @override
  void stop() => isRunning = false;
}

class SlowStartCore extends FakeCore {
  SlowStartCore() : super(signedIn: true, language: 'en');

  final ready = Completer<void>();

  @override
  Future<void> start() async {
    await ready.future;
    isRunning = true;
  }
}

class UpdateFakeCore extends FakeCore {
  UpdateFakeCore({this.delayCheck = false})
      : super(signedIn: true, language: 'en');

  final bool delayCheck;
  final pendingCheck = Completer<CoreReply>();
  Map<String, dynamic>? downloadParams;

  @override
  Future<CoreReply> call(String method, [Map<String, dynamic>? params]) async {
    if (method == 'check_update') {
      requests.add(method);
      if (delayCheck) return pendingCheck.future;
      return const CoreReply(
          update: DesktopUpdate(
        version: '1.10.5',
        newer: true,
        packages: [
          UpdatePackage(
              id: 'linux-amd64-deb',
              name: 'Gozar-Linux-amd64.deb',
              kind: 'deb',
              size: 2048)
        ],
      ));
    }
    if (method == 'download_update') {
      requests.add(method);
      downloadParams = params;
      return const CoreReply(
          downloadedPath: '/home/user/.cache/Gozar/updates/verified.deb');
    }
    if (method == 'cancel_update') {
      requests.add(method);
      if (!pendingCheck.isCompleted) {
        pendingCheck.complete(const CoreReply(error: 'cancelled'));
      }
      return const CoreReply();
    }
    return super.call(method, params);
  }
}

Finder _settingsGear() {
  final keyed = find.byKey(const ValueKey('home-settings-gear'));
  if (keyed.evaluate().isNotEmpty) return keyed;
  return find.byTooltip('Settings').first;
}

void main() {
  testWidgets('language can be changed from the native login screen',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.localeTestValue = const Locale('en');
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearLocaleTestValue);
    final core = FakeCore();
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    expect(find.text('Sign in'), findsWidgets);
    expect(find.text('Language'), findsOneWidget);
    expect(find.text('Google'), findsNothing);
    expect(find.text('Apple'), findsNothing);
    await tester.tap(find.text('Language'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('پارسی'));
    await tester.pumpAndSettle();
    expect(find.text('ورود'), findsWidgets);
    expect(core.language, 'fa');
    expect(
        Localizations.localeOf(tester.element(find.text('ورود').first))
            .languageCode,
        'fa');
    expect(Directionality.of(tester.element(find.text('ورود').first)),
        TextDirection.rtl);
    expect(core.requests, isNot(contains('connect')));
  });

  testWidgets('settings changes language and sign out returns to login',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(signedIn: true, language: 'fa');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    expect(find.text('Gozar'), findsOneWidget);
    final gear = find.byKey(const ValueKey('home-settings-gear'));
    await tester.tap(
        gear.evaluate().isNotEmpty ? gear : find.byTooltip('تنظیمات').first);
    await tester.pumpAndSettle();
    expect(find.text('حساب Firefox'), findsOneWidget);
    await tester.tap(find.text('شخصی‌سازی'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('زبان'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('English'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    expect(find.text('Disconnected'), findsOneWidget);
    expect(Directionality.of(tester.element(find.text('Disconnected'))),
        TextDirection.ltr);
    await tester.tap(_settingsGear());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Firefox account'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Sign out'));
    await tester.tap(find.text('Sign out'));
    await tester.pumpAndSettle();
    expect(find.text('Sign in'), findsWidgets);
    expect(core.requests, isNot(contains('connect')));
  });

  testWidgets('WARP requires consent and warns before a live reconnect',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.localeTestValue = const Locale('en');
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearLocaleTestValue);
    final core = FakeCore(signedIn: true, language: 'en')..live = true;
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.tap(_settingsGear());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Connection'));
    await tester.pumpAndSettle();
    final option = find.text('UDP via Cloudflare WARP');
    await tester.ensureVisible(option);
    await tester.tap(option);
    await tester.pumpAndSettle();
    expect(find.textContaining('turning this off does not unregister'), findsOneWidget);
    expect(find.textContaining('LAN, relay routes and IPv6'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(core.requests, isNot(contains('warp_udp')));
    expect(core.warpUdp, isFalse);

    // The full disclosure remains scrollable in the compact minimum window.
    tester.view.physicalSize = const Size(360, 360);
    await tester.pumpAndSettle();
    await tester.ensureVisible(option);
    await tester.tap(option);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('Accept'));
    await tester.pumpAndSettle();
    expect(core.lastWarp, {'enabled': true, 'consent': true});
    expect(core.warpUdp, isTrue);

    await tester.ensureVisible(option);
    await tester.tap(option);
    await tester.pumpAndSettle();
    expect(find.textContaining('disconnect and reconnect'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(core.warpUdp, isTrue);
    await tester.tap(option);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Accept'));
    await tester.pumpAndSettle();
    expect(core.lastWarp, {'enabled': false, 'consent': false});
    expect(core.warpUdp, isFalse);
  });

  testWidgets('server selection uses the chosen host', (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(signedIn: true, language: 'en');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Locations'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Netherlands · Amsterdam'));
    await tester.pumpAndSettle();
    expect(core.selectedHost, 'amsterdam.example');
    expect(core.requests, isNot(contains('connect')));
  });
  testWidgets('verification code flow stays inside the native interface',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(language: 'en');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(0), 'person@example.com');
    await tester.enterText(find.byType(TextField).at(1), 'fake-password');
    await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
    await tester.pumpAndSettle();
    expect(find.text('Verification code'), findsOneWidget);
    await tester.enterText(find.byType(TextField).at(2), '123456');
    await tester.tap(find.widgetWithText(FilledButton, 'Verify and sign in'));
    await tester.pumpAndSettle();
    expect(find.text('Gozar'), findsOneWidget);
    expect(core.requests, isNot(contains('connect')));
  });
  testWidgets('a second verification step clears the previous code',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(language: 'en', twoStep: true);
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(0), 'person@example.com');
    await tester.enterText(find.byType(TextField).at(1), 'fake-password');
    await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(2), 'ABC123');
    await tester.tap(find.widgetWithText(FilledButton, 'Verify and sign in'));
    await tester.pumpAndSettle();
    expect(
        tester.widget<TextField>(find.byType(TextField).at(2)).controller!.text,
        isEmpty);
    await tester.enterText(find.byType(TextField).at(2), '123456');
    await tester.tap(find.widgetWithText(FilledButton, 'Verify and sign in'));
    await tester.pumpAndSettle();
    expect(find.text('Gozar'), findsOneWidget);
    expect(core.requests, isNot(contains('connect')));
  });
  testWidgets('create account stays in the app and verifies the email',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(language: 'en');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    final create = find.text('New here? Create a Firefox account');
    await tester.ensureVisible(create);
    await tester.tap(create);
    await tester.pumpAndSettle();
    expect(find.text('Repeat password'), findsOneWidget);
    expect(find.text('Already have an account? Sign in'), findsOneWidget);
    await tester.enterText(find.byType(TextField).at(0), 'person@example.com');
    await tester.enterText(find.byType(TextField).at(1), 'fake-password');
    await tester.enterText(find.byType(TextField).at(2), 'wrong-password');
    final submit = find.widgetWithText(FilledButton, 'Create account');
    await tester.ensureVisible(submit);
    await tester.tap(submit);
    await tester.pumpAndSettle();
    expect(find.text('The passwords do not match.'), findsOneWidget);
    expect(core.requests, isNot(contains('register')));
    await tester.enterText(find.byType(TextField).at(2), 'fake-password');
    await tester.ensureVisible(submit);
    await tester.tap(submit);
    await tester.pumpAndSettle();
    expect(core.requests, contains('register'));
    expect(find.text('Verification code'), findsOneWidget);
    await tester.enterText(find.byType(TextField).at(3), '123456');
    final verify =
        find.widgetWithText(FilledButton, 'Verify and create account');
    await tester.ensureVisible(verify);
    await tester.tap(verify);
    await tester.pumpAndSettle();
    expect(find.text('Gozar'), findsOneWidget);
    expect(core.lastAuth!['code'], '123456');
    expect(core.requests, isNot(contains('connect')));
  });

  testWidgets('interactive security image stays in the app', (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(language: 'en', requiresCaptcha: true);
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(0), 'person@example.com');
    await tester.enterText(find.byType(TextField).at(1), 'fake-password');
    final login = find.widgetWithText(FilledButton, 'Sign in');
    await tester.ensureVisible(login);
    await tester.tap(login);
    await tester.pumpAndSettle();
    expect(find.text('Mozilla security check'), findsOneWidget);
    expect(find.text('Image text'), findsOneWidget);
    await tester.enterText(find.byType(TextField).at(2), 'human');
    await tester.ensureVisible(login);
    await tester.tap(login);
    await tester.pumpAndSettle();
    expect(find.text('Verification code'), findsOneWidget);
    expect(core.lastAuth!['captcha'], 'human');
    expect(core.requests, isNot(contains('connect')));
  });
  testWidgets(
      'adding, cancelling, switching and signing out keep separate sessions',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.localeTestValue = const Locale('en');
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearLocaleTestValue);
    final core = FakeCore(signedIn: true, language: 'en');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();

    Future<void> openAccounts() async {
      await tester.tap(_settingsGear());
      await tester.pumpAndSettle();
      await tester.tap(find.text('Firefox account'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Manage accounts'));
      await tester.pumpAndSettle();
    }

    await openAccounts();
    await tester.tap(find.text('Add account'));
    await tester.pumpAndSettle();
    expect(find.text('Sign in'), findsWidgets);
    expect(core.activeEmail, 'example@example.com');
    await tester.ensureVisible(find.text('Cancel'));
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(find.text('Gozar'), findsOneWidget);
    expect(core.saved, ['example@example.com']);

    await openAccounts();
    await tester.tap(find.text('Add account'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(0), 'second@example.test');
    await tester.enterText(find.byType(TextField).at(1), 'not-a-real-password');
    final signIn = find.widgetWithText(FilledButton, 'Sign in');
    await tester.ensureVisible(signIn);
    await tester.tap(signIn);
    await tester.pumpAndSettle();
    expect(find.text('Verification code'), findsOneWidget);
    await tester.enterText(find.byType(TextField).at(2), '123456');
    final verify = find.widgetWithText(FilledButton, 'Verify and sign in');
    await tester.ensureVisible(verify);
    await tester.tap(verify);
    await tester.pumpAndSettle();
    expect(core.saved, ['example@example.com', 'second@example.test']);
    expect(core.activeEmail, 'second@example.test');

    await openAccounts();
    await tester.tap(find.text('example@example.com').last);
    await tester.pumpAndSettle();
    expect(core.activeEmail, 'example@example.com');
    await tester.tap(_settingsGear());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Firefox account'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Sign out'));
    await tester.tap(find.text('Sign out'));
    await tester.pumpAndSettle();
    expect(core.saved, ['second@example.test']);
    expect(find.text('Saved Gozar accounts'), findsOneWidget);
    await tester.ensureVisible(find.text('Saved Gozar accounts'));
    await tester.tap(find.text('Saved Gozar accounts'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('second@example.test'));
    await tester.pumpAndSettle();
    expect(find.text('Gozar'), findsOneWidget);
    expect(core.activeEmail, 'second@example.test');
    expect(core.requests, isNot(contains('connect')));
  });

  testWidgets('system email picker does not use saved Gozar sessions',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.localeTestValue = const Locale('en');
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearLocaleTestValue);
    final core = FakeCore(language: 'en')..saved.add('saved-vpn@example.test');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Choose an email from system accounts'));
    await tester.pumpAndSettle();
    expect(find.text('system@example.test'), findsOneWidget);
    expect(find.text('saved-vpn@example.test'), findsNothing);
    await tester.tap(find.text('system@example.test'));
    await tester.pumpAndSettle();
    expect(
        tester.widget<TextField>(find.byType(TextField).first).controller!.text,
        'system@example.test');
    expect(core.requests, contains('system_accounts'));
  });

  testWidgets('appearance, recents, proxy chain and donation have real actions',
      (tester) async {
    String? copied;
    tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') {
        copied = (call.arguments as Map<dynamic, dynamic>)['text'] as String?;
      }
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null));
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.localeTestValue = const Locale('en');
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearLocaleTestValue);
    final core = FakeCore(signedIn: true, language: 'en');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.tap(_settingsGear());
    await tester.pumpAndSettle();
    await tester.tap(find.text('Personalization'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Appearance'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Light'));
    await tester.pumpAndSettle();
    expect(core.theme, 'light');
    expect(tester.widget<MaterialApp>(find.byType(MaterialApp)).themeMode,
        ThemeMode.light);

    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Connection'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Remember last server'));
    await tester.pumpAndSettle();
    expect(core.recents, isTrue);
    await tester.ensureVisible(find.text('Proxy chain'));
    await tester.tap(find.text('Proxy chain'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'socks5://127.0.0.1:1080');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(core.chain, ['socks5://127.0.0.1:1080']);

    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('General'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('Support Gozar'));
    await tester.tap(find.text('Support Gozar'));
    await tester.pumpAndSettle();
    expect(find.text('UQDxiU-jPFK_TgB0wOzZndhf3IKJLTLm8Vg_Uaif7_C2eCL3'),
        findsOneWidget);
    await tester.tap(find.text('Copy address'));
    await tester.pumpAndSettle();
    expect(copied, 'UQDxiU-jPFK_TgB0wOzZndhf3IKJLTLm8Vg_Uaif7_C2eCL3');
    expect(core.requests, isNot(contains('connect')));
  });
  testWidgets(
      'signed desktop update downloads a selected package without claiming installation',
      (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.localeTestValue = const Locale('en');
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearLocaleTestValue);
    final core = UpdateFakeCore();
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.tap(_settingsGear());
    await tester.pumpAndSettle();
    await tester.tap(find.text('General'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Check for updates'));
    await tester.pumpAndSettle();
    expect(find.text('Gozar-Linux-amd64.deb'), findsOneWidget);
    await tester.tap(find.text('Gozar-Linux-amd64.deb'));
    await tester.pumpAndSettle();
    expect(core.downloadParams?['id'], 'linux-amd64-deb');
    expect(find.textContaining('not installed'), findsOneWidget);
    expect(
        find.byWidgetPredicate((widget) =>
            widget is SelectableText &&
            widget.data == '/home/user/.cache/Gozar/updates/verified.deb'),
        findsOneWidget);
    expect(find.text('Open folder'), findsOneWidget);
    await tester.tap(find.text('Close').last);
    await tester.pumpAndSettle();
  });

  testWidgets('update check can be cancelled through the core', (tester) async {
    tester.view.physicalSize = const Size(480, 740);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.localeTestValue = const Locale('en');
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearLocaleTestValue);
    final core = UpdateFakeCore(delayCheck: true);
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    await tester.tap(_settingsGear());
    await tester.pumpAndSettle();
    await tester.tap(find.text('General'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Check for updates'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 200));
    expect(find.text('Checking the signed release…'), findsOneWidget);
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(core.requests, contains('cancel_update'));
    expect(find.text('You are up to date.'), findsNothing);
  });

  test('desktop update RPC rejects malformed summaries', () {
    final reply = CoreReply.fromJson({
      'reply': {
        'version': '1.10.5',
        'newer': true,
        'artifacts': [
          {'id': 'a', 'name': 'package.deb', 'kind': 'deb', 'size': 42}
        ],
      }
    });
    expect(reply.update?.version, '1.10.5');
    expect(reply.update?.packages.single.id, 'a');
    final bad = CoreReply.fromJson({
      'reply': {
        'version': '1.10.5',
        'newer': true,
        'artifacts': [
          {'id': 'a', 'name': 7, 'kind': 'deb', 'size': 42}
        ],
      }
    });
    expect(bad.update, isNull);
  });

  test('core maps only reported connection data', () {
    final state = CoreState.fromJson({
      'live': true,
      'exit': '192.0.2.9',
      'device': 'Test TUN',
      'limit': 1000,
      'remaining': 600,
      'server': {
        'code': 'NL',
        'country': 'Netherlands',
        'city': 'Amsterdam',
        'host': 'relay.example',
        'port': 8443,
      },
    });
    expect(state.exit, '192.0.2.9');
    expect(state.device, 'Test TUN');
    expect(state.remaining, 600);
    expect(state.server!.port, 8443);
    expect(const CoreState().exit, isEmpty);
    expect(const CoreState().limit, 0);
  });

  testWidgets('unknown network, quota and firewall are not fabricated',
      (tester) async {
    tester.view.physicalSize = const Size(360, 360);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(signedIn: true, language: 'en');
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    expect(find.text('Disconnected'), findsOneWidget);
    expect(find.text('Usage unavailable'), findsOneWidget);
    expect(find.text('Tunnel device: —'), findsOneWidget);
    expect(find.text('Exit IP: —'), findsOneWidget);
    expect(find.text('Firewall'), findsOneWidget);
    expect(find.text('Unavailable'), findsOneWidget);
    expect(find.textContaining('WireGuard'), findsNothing);
    await tester.tap(find.byTooltip('Connect'));
    await tester.pumpAndSettle();
    expect(core.requests, contains('connect'));
    expect(find.text('Connected'), findsOneWidget);
    final locations = tester.widget<InkWell>(find.ancestor(
        of: find.text('Locations'),
        matching: find.byWidgetPredicate((widget) => widget is InkWell)));
    expect(locations.onTap, isNull);
    expect(tester.takeException(), isNull);
  });

  testWidgets('compact Persian dashboard is RTL without overflow',
      (tester) async {
    tester.view.physicalSize = const Size(360, 360);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final core = FakeCore(signedIn: true, language: 'fa')
      ..live = true
      ..selectedHost = 'amsterdam.example'
      ..exit = '2001:db8:1234:5678:1234:5678:abcd:1234'
      ..device = 'very-long-tunnel-device-name';
    await tester.pumpWidget(GozarApp(core: core));
    await tester.pumpAndSettle();
    expect(find.text('متصل است'), findsOneWidget);
    expect(Directionality.of(tester.element(find.text('متصل است'))),
        TextDirection.rtl);
    expect(find.text('دیوار آتش'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
      'compact Windows frame displays reported data and disconnects on close',
      (tester) async {
    tester.view.physicalSize = const Size(430, 380);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final actions = <String>[];
    const channel = MethodChannel('window_manager');
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel,
        (call) async {
      actions.add(call.method);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null));
    final core = FakeCore(signedIn: true, language: 'en')
      ..live = true
      ..selectedHost = 'amsterdam.example'
      ..rateDown = 2048
      ..rateUp = 1024
      ..down = 4096
      ..up = 1024
      ..exit = '192.0.2.4'
      ..device = 'Wintun'
      ..limit = 10240
      ..remaining = 5120;
    await tester.pumpWidget(GozarApp(core: core, windowControls: true));
    await tester.pumpAndSettle();
    expect(find.text('Connected'), findsOneWidget);
    expect(find.text('Download  2.0 KB/s'), findsOneWidget);
    expect(find.text('Upload  1.0 KB/s'), findsOneWidget);
    expect(find.text('Session traffic: 5.0 KB'), findsOneWidget);
    expect(find.text('Exit IP: 192.0.2.4'), findsOneWidget);
    expect(find.text('Tunnel device: Wintun'), findsOneWidget);
    expect(find.text('5.0 KB Remaining'), findsOneWidget);
    final flag = tester.widget<CountryFlag>(find.byType(CountryFlag).first);
    expect(flag.code, 'NL');
    await tester.tap(find.byTooltip('Minimize'));
    await tester.tap(find.byTooltip('Close'));
    await tester.pumpAndSettle();
    expect(actions, ['minimize', 'setPreventClose', 'close']);
    expect(core.requests, contains('disconnect'));
    expect(core.running, isFalse);
    expect(tester.takeException(), isNull);
  }, variant: const TargetPlatformVariant({TargetPlatform.windows}));

  testWidgets('native close event disconnects before exiting', (tester) async {
    final actions = <String>[];
    const channel = MethodChannel('window_manager');
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel,
        (call) async {
      actions.add(call.method);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null));
    final core = FakeCore(signedIn: true, language: 'en')..live = true;
    await tester.pumpWidget(GozarApp(core: core, windowControls: true));
    await tester.pumpAndSettle();
    final request = tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'window_manager',
        const StandardMethodCodec().encodeMethodCall(
            const MethodCall('onEvent', {'eventName': 'close'})),
        null);
    await tester.pumpAndSettle();
    await request;
    expect(core.requests, contains('disconnect'));
    expect(core.running, isFalse);
    expect(actions, ['setPreventClose', 'close']);
  }, variant: const TargetPlatformVariant({TargetPlatform.windows}));

  testWidgets('close waits for a starting core before disconnecting',
      (tester) async {
    final actions = <String>[];
    const channel = MethodChannel('window_manager');
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel,
        (call) async {
      actions.add(call.method);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null));
    final core = SlowStartCore()..live = true;
    await tester.pumpWidget(GozarApp(core: core, windowControls: true));
    final request = tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        'window_manager',
        const StandardMethodCodec().encodeMethodCall(
            const MethodCall('onEvent', {'eventName': 'close'})),
        null);
    await tester.pump();
    expect(actions, isEmpty);
    expect(core.requests, isEmpty);
    core.ready.complete();
    await tester.pumpAndSettle();
    await request;
    expect(core.requests, contains('disconnect'));
    expect(core.running, isFalse);
    expect(actions, ['setPreventClose', 'close']);
  }, variant: const TargetPlatformVariant({TargetPlatform.windows}));

  testWidgets('close failure keeps the core and window open', (tester) async {
    final actions = <String>[];
    const channel = MethodChannel('window_manager');
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel,
        (call) async {
      actions.add(call.method);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null));
    final core = FakeCore(signedIn: true, language: 'en')
      ..live = true
      ..disconnectFailure = true;
    await tester.pumpWidget(GozarApp(core: core, windowControls: true));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Close'));
    await tester.pumpAndSettle();
    expect(core.requests, contains('disconnect'));
    expect(core.running, isTrue);
    expect(actions, isEmpty);
    expect(
        find.text(
            'The tunnel could not be safely disconnected. Please try closing again.'),
        findsOneWidget);
  }, variant: const TargetPlatformVariant({TargetPlatform.windows}));
}
