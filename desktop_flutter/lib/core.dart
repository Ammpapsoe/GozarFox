import 'dart:async';
import 'dart:convert';
import 'dart:io';

class Place {
  const Place({
    required this.code,
    required this.country,
    required this.city,
    required this.host,
    required this.recommended,
    this.port = 0,
  });

  final String code;
  final String country;
  final String city;
  final String host;
  final bool recommended;
  final int port;

  factory Place.fromJson(Map<String, dynamic> json) => Place(
        code: json['code'] as String? ?? '',
        country: json['country'] as String? ?? '',
        city: json['city'] as String? ?? '',
        host: json['host'] as String? ?? '',
        recommended: json['recommended'] as bool? ?? false,
        port: (json['port'] as num?)?.toInt() ?? 0,
      );

  String label(bool fa) {
    if (recommended) return fa ? 'بهترین سرور' : 'Recommended server';
    if (country.isEmpty) return city;
    if (city.isEmpty || city == country) return country;
    return '$country · $city';
  }
}

class SavedAccount {
  const SavedAccount(
      {required this.id, required this.email, required this.active});

  final String id;
  final String email;
  final bool active;

  factory SavedAccount.fromJson(Map<String, dynamic> json) => SavedAccount(
        id: json['id'] as String? ?? '',
        email: json['email'] as String? ?? '',
        active: json['active'] as bool? ?? false,
      );
}

class CoreState {
  const CoreState({
    this.signedIn = false,
    this.email = '',
    this.language = '',
    this.theme = 'system',
    this.recents = false,
    this.warpUdp = false,
    this.killSwitch = false,
    this.chain = const [],
    this.accounts = const [],
    this.stage = '',
    this.live = false,
    this.connecting = false,
    this.mode = 'tun',
    this.fault = '',
    this.exit = '',
    this.device = '',
    this.needsAdmin = false,
    this.port = 0,
    this.limit = 0,
    this.remaining = 0,
    this.rateDown = 0,
    this.rateUp = 0,
    this.down = 0,
    this.up = 0,
    this.version = '',
    this.server,
    this.servers = const [],
  });

  final bool signedIn;
  final String email;
  final String language;
  final String theme;
  final bool recents;
  final bool warpUdp;
  final bool killSwitch;
  final List<String> chain;
  final List<SavedAccount> accounts;
  final String stage;
  final bool live;
  final bool connecting;
  final String mode;
  final String fault;
  final String exit;
  final String device;
  final bool needsAdmin;
  final int port;
  final int limit;
  final int remaining;
  final int rateDown;
  final int rateUp;
  final int down;
  final int up;
  final String version;
  final Place? server;
  final List<Place> servers;

  factory CoreState.fromJson(Map<String, dynamic> json) {
    final rawServers = json['servers'] as List<dynamic>? ?? const [];
    final rawServer = json['server'];
    final rawAccounts = json['accounts'] as List<dynamic>? ?? const [];
    final rawChain = json['chain'] as List<dynamic>? ?? const [];
    return CoreState(
      signedIn: json['signed_in'] as bool? ?? false,
      email: json['email'] as String? ?? '',
      language: json['language'] as String? ?? '',
      theme: json['theme'] as String? ?? 'system',
      recents: json['recents'] as bool? ?? false,
      warpUdp: json['warp_udp'] as bool? ?? false,
      killSwitch: json['kill_switch'] as bool? ?? false,
      chain: rawChain.whereType<String>().toList(growable: false),
      accounts: rawAccounts
          .whereType<Map<String, dynamic>>()
          .map(SavedAccount.fromJson)
          .toList(growable: false),
      stage: json['stage'] as String? ?? '',
      live: json['live'] as bool? ?? false,
      connecting: json['connecting'] as bool? ?? false,
      mode: json['mode'] as String? ?? 'tun',
      fault: json['fault'] as String? ?? '',
      exit: json['exit'] as String? ?? '',
      device: json['device'] as String? ?? '',
      needsAdmin: json['needs_admin'] as bool? ?? false,
      port: (json['port'] as num?)?.toInt() ?? 0,
      limit: (json['limit'] as num?)?.toInt() ?? 0,
      remaining: (json['remaining'] as num?)?.toInt() ?? 0,
      rateDown: (json['rate_down'] as num?)?.toInt() ?? 0,
      rateUp: (json['rate_up'] as num?)?.toInt() ?? 0,
      down: (json['down'] as num?)?.toInt() ?? 0,
      up: (json['up'] as num?)?.toInt() ?? 0,
      version: json['version'] as String? ?? '',
      server:
          rawServer is Map<String, dynamic> ? Place.fromJson(rawServer) : null,
      servers: rawServers
          .whereType<Map<String, dynamic>>()
          .map(Place.fromJson)
          .toList(growable: false),
    );
  }
}

class UpdatePackage {
  const UpdatePackage(
      {required this.id,
      required this.name,
      required this.kind,
      required this.size});

  final String id;
  final String name;
  final String kind;
  final int size;
}

class DesktopUpdate {
  const DesktopUpdate(
      {required this.version, required this.newer, required this.packages});

  final String version;
  final bool newer;
  final List<UpdatePackage> packages;

  static DesktopUpdate? fromReply(Object? raw) {
    if (raw is! Map || raw['version'] is! String || raw['newer'] is! bool) {
      return null;
    }
    final items = raw['artifacts'];
    if (items != null && items is! List) return null;
    final packages = <UpdatePackage>[];
    for (final item in (items as List? ?? const [])) {
      if (item is! Map ||
          item['id'] is! String ||
          item['name'] is! String ||
          item['kind'] is! String ||
          item['size'] is! int ||
          (item['size'] as int) <= 0) {
        return null;
      }
      packages.add(UpdatePackage(
        id: item['id'] as String,
        name: item['name'] as String,
        kind: item['kind'] as String,
        size: item['size'] as int,
      ));
    }
    return DesktopUpdate(
        version: raw['version'] as String,
        newer: raw['newer'] as bool,
        packages: packages);
  }
}

class CoreReply {
  const CoreReply({
    this.state,
    this.error,
    this.stateVerified = false,
    this.errorVerified = false,
    this.needsCode = false,
    this.unverified = false,
    this.proxyAddress,
    this.captchaImage,
    this.systemEmails = const [],
    this.update,
    this.downloadedPath,
  });

  final CoreState? state;
  final String? error;

  /// Whether the safety-critical state and error fields were structurally
  /// valid in the original RPC response. Used for process ownership handoff;
  /// default CoreState values are never sufficient to prove disconnection.
  final bool stateVerified;
  final bool errorVerified;
  final bool needsCode;

  /// The browser sign-in's session is not confirmed by Mozilla yet: the page
  /// stays open for the code and announces the session again.
  final bool unverified;

  /// The loopback address of the core's sign-in proxy ("127.0.0.1:port").
  final String? proxyAddress;
  final String? captchaImage;
  final List<String> systemEmails;
  final DesktopUpdate? update;
  final String? downloadedPath;

  factory CoreReply.fromJson(Map<String, dynamic> json) {
    final state = json['state'];
    final result = json['reply'];
    final error = json['error'];
    return CoreReply(
      state: state is Map<String, dynamic> ? CoreState.fromJson(state) : null,
      error: error is String && error.isNotEmpty ? error : null,
      stateVerified: state is Map<String, dynamic> &&
          state['signed_in'] is bool &&
          state['live'] is bool &&
          state['connecting'] is bool,
      errorVerified: error == null || error is String,
      needsCode: result is Map && result['needs_code'] == true,
      unverified: result is Map && result['unverified'] == true,
      proxyAddress: result is Map && result['address'] is String
          ? result['address'] as String
          : null,
      systemEmails: result is Map && result['emails'] is List
          ? (result['emails'] as List)
              .whereType<String>()
              .toList(growable: false)
          : const [],
      captchaImage: result is Map && result['captcha_image'] is String
          ? result['captcha_image'] as String
          : null,
      update: DesktopUpdate.fromReply(result),
      downloadedPath: result is Map && result['downloaded_path'] is String
          ? result['downloaded_path'] as String
          : null,
    );
  }
}

/// Outcome of preparing the existing core for a future UI handoff. A ready
/// result means the core reported successful cleanup and exited normally; it
/// does not independently prove that the operating system restored every route.
enum CoreHandoffResult { ready, busy, signedOut, connected, unconfirmed }

class Core {
  Core({this.executablePath, Future<Process> Function(String, List<String>)? processStarter})
      : _processStarter = processStarter;

  final String? executablePath;
  final Future<Process> Function(String, List<String>)? _processStarter;
  Process? _process;
  Future<void>? _starting;
  int _nextId = 0;
  bool _stopping = false;
  bool _handoffInProgress = false;
  final Map<int, Completer<CoreReply>> _pending = {};
  void Function()? onExit;

  bool get running => _process != null;

  String? _findBinary() {
    final explicit = executablePath;
    if (explicit != null) {
      return explicit.isNotEmpty && File(explicit).existsSync() ? explicit : null;
    }
    final override = Platform.environment['GOZAR_CORE'];
    if (override != null &&
        override.isNotEmpty &&
        File(override).existsSync()) {
      return override;
    }
    final name = Platform.isWindows ? 'gozar-core.exe' : 'gozar-core';
    final parent = File(Platform.resolvedExecutable).parent.path;
    final candidate = '$parent${Platform.pathSeparator}$name';
    return File(candidate).existsSync() ? candidate : null;
  }

  Future<void> start() {
    // A stopped or transferred owner cannot be restarted implicitly: a
    // partially launched replacement may already own the network state.
    if (_stopping || _handoffInProgress) {
      return Future.error(StateError('core ownership is stopping or transferred'));
    }
    if (_process != null) return Future.value();
    if (_starting != null) return _starting!;
    // Install the guard before invoking the asynchronous process starter: two
    // overlapping calls must never start two core processes for this owner.
    final completion = Completer<void>();
    _starting = completion.future;
    unawaited(() async {
      try {
        await _startOnce();
        completion.complete();
      } on Object catch (error, stack) {
        completion.completeError(error, stack);
      } finally {
        _starting = null;
      }
    }());
    return completion.future;
  }

  Future<void> _startOnce() async {
    final path = _findBinary();
    if (path == null) throw StateError('core not found');
    final starter = _processStarter;
    final process = starter == null
        ? await Process.start(path, const ['--rpc'])
        : await starter(path, const ['--rpc']);
    _process = process;
    process.stdout
        .transform(utf8.decoder)
        .transform(const LineSplitter())
        .listen(_receive);
    process.stderr.listen((_) {});
    unawaited(process.exitCode.then((_) {
      if (_process == process) _process = null;
      for (final waiter in _pending.values) {
        if (!waiter.isCompleted) {
          waiter.completeError(StateError('core disconnected'));
        }
      }
      _pending.clear();
      if (!_stopping) onExit?.call();
    }));
    if (_stopping) {
      // The owner closed while the OS was spawning the child. The child still
      // needs a graceful quit; do not leak it or kill it during cleanup.
      _requestQuit(process);
      throw StateError('core start cancelled by shutdown');
    }
  }

  void _receive(String line) {
    try {
      final decoded = jsonDecode(line);
      if (decoded is! Map<String, dynamic>) return;
      final id = decoded['id'];
      if (id is! int) return;
      final waiter = _pending.remove(id);
      if (waiter != null && !waiter.isCompleted) {
        waiter.complete(CoreReply.fromJson(decoded));
      }
    } on FormatException {
      return;
    }
  }

  Future<CoreReply> call(String method, [Map<String, dynamic>? params]) =>
      _call(method, params);

  Future<CoreReply> _call(String method, Map<String, dynamic>? params,
      {bool handoff = false, bool markStopping = false, Duration? timeout}) async {
    final process = _process;
    if (process == null || _stopping || (_handoffInProgress && !handoff)) {
      throw StateError('core not running');
    }
    final id = ++_nextId;
    final waiter = Completer<CoreReply>();
    _pending[id] = waiter;
    final message = <String, dynamic>{'id': id, 'method': method};
    if (params != null) message['params'] = params;
    if (markStopping) _stopping = true;
    try {
      process.stdin.writeln(jsonEncode(message));
      await process.stdin.flush();
      final limit = timeout ??
          (method == 'download_update'
              ? const Duration(minutes: 16)
              : const Duration(seconds: 180));
      return await waiter.future.timeout(limit);
    } finally {
      _pending.remove(id);
    }
  }

  /// Experimental prerequisite for transferring ownership to another UI.
  /// This does not launch another process. A caller must launch nothing unless
  /// it receives [CoreHandoffResult.ready]. Credentials remain in the core's
  /// existing storage and are never passed on a command line.
  Future<CoreHandoffResult> prepareUiHandoff() async {
    final process = _process;
    if (process == null || _stopping) return CoreHandoffResult.unconfirmed;
    if (_handoffInProgress || _pending.isNotEmpty) {
      return CoreHandoffResult.busy;
    }
    _handoffInProgress = true;
    try {
      final stateReply = await _call('state', null,
          handoff: true, timeout: const Duration(seconds: 10));
      final state = stateReply.state;
      if (_stopping || _process != process || !stateReply.stateVerified ||
          !stateReply.errorVerified || stateReply.error != null || state == null) {
        return CoreHandoffResult.unconfirmed;
      }
      if (!state.signedIn) return CoreHandoffResult.signedOut;
      if (state.live || state.connecting) return CoreHandoffResult.connected;

      // Only the owning process requests quit. Never kill it on a timeout:
      // OS proxy and tunnel cleanup can require time.
      final quitReply = await _call('quit', null,
          handoff: true,
          markStopping: true,
          timeout: const Duration(seconds: 45));
      final after = quitReply.state;
      if (!quitReply.stateVerified || !quitReply.errorVerified ||
          quitReply.error != null || after == null || after.live || after.connecting) {
        return CoreHandoffResult.unconfirmed;
      }
      final exitCode = await process.exitCode.timeout(const Duration(seconds: 10));
      return exitCode == 0
          ? CoreHandoffResult.ready
          : CoreHandoffResult.unconfirmed;
    } on Object {
      // Errors and timeouts are not permission to start a second owner.
      return CoreHandoffResult.unconfirmed;
    } finally {
      if (!_stopping) _handoffInProgress = false;
    }
  }

  Future<void> shutdown() async {
    final opening = _starting;
    stop();
    if (opening != null) {
      try {
        await opening;
      } on Object {
        // An interrupted spawn can still have started a process. Wait for it
        // below if present; failure is not permission to start another owner.
      }
    }
    final process = _process;
    if (process == null) return;
    try {
      await process.exitCode.timeout(const Duration(seconds: 10));
    } on TimeoutException {
      return;
    }
  }

  void stop() {
    if (_stopping) return;
    final process = _process;
    if (process == null && _starting == null) return;
    _stopping = true;
    if (process != null) _requestQuit(process);
  }

  void _requestQuit(Process process) {
    try {
      process.stdin.writeln(jsonEncode({'id': ++_nextId, 'method': 'quit'}));
      unawaited(process.stdin.close());
    } on Object {
      return;
    }
  }
}
