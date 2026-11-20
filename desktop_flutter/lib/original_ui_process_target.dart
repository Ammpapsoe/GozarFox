import 'dart:async';
import 'dart:io';

import 'ui_handoff.dart';

/// Process launcher for the future original Qt application. The target owns its
/// own core and must reserve stdout exclusively for the readiness protocol.
/// This launcher is experimental; it is not an implementation of the Qt UI.
class OriginalUiProcessTarget implements OriginalUiTarget {
  OriginalUiProcessTarget(this.executablePath, {this.environment});

  /// Only a fixed, bundled executable beside the Flutter executable is used
  /// by the app entry point. Do not pass credentials or shell commands here.
  factory OriginalUiProcessTarget.bundled() {
    final parent = File(Platform.resolvedExecutable).parent.path;
    final name = Platform.isWindows ? 'gozar-original-ui.exe' : 'gozar-original-ui';
    return OriginalUiProcessTarget('$parent${Platform.pathSeparator}$name');
  }

  final String executablePath;
  // Overrides are for test/developer configuration, not user credentials.
  final Map<String, String>? environment;
  bool _started = false;

  @override
  Future<bool> isAvailable() async {
    if (_started || executablePath.isEmpty) return false;
    try {
      final info = await File(executablePath).stat();
      return info.type == FileSystemEntityType.file &&
          (Platform.isWindows || (info.mode & 0x49) != 0);
    } on FileSystemException {
      return false;
    }
  }

  @override
  Future<bool> startAndWaitReady() async {
    if (_started) return false;
    // Never retry a partial start: it may already have acquired a core.
    _started = true;
    final process = await Process.start(
      executablePath,
      const ['--handoff-stdio-v1'],
      workingDirectory: File(executablePath).absolute.parent.path,
      environment: environment,
      runInShell: false,
    );
    // The child owns its own lifetime. No credentials or control commands are
    // sent on stdin; no timeout or communication failure force-kills it.
    unawaited(process.stdin.close().catchError((Object _) {}));
    final ready = Completer<bool>();
    final line = <int>[];
    var reading = true;
    void fail() {
      reading = false;
      if (!ready.isCompleted) ready.complete(false);
    }

    process.stdout.listen((bytes) {
      if (!reading) return; // keep draining to avoid filling the child's pipe
      for (final byte in bytes) {
        if (byte == 10) {
          if (line.isNotEmpty && line.last == 13) line.removeLast();
          const expected = 'GOZAR_QT_READY_V1';
          final matches = String.fromCharCodes(line) == expected;
          reading = false;
          if (!ready.isCompleted) ready.complete(matches);
          break;
        }
        line.add(byte);
        if (line.length > 128) { fail(); break; }
      }
    }, onError: (Object _) { fail(); }, onDone: fail);
    process.stderr.listen((_) {}, onError: (Object _) {});
    unawaited(process.exitCode.then((_) { fail(); }, onError: (Object _) { fail(); }));
    // A timeout or malformed response is uncertain ownership, not permission
    // to spawn another core. Keep the pipes drained and leave the child alone.
    final accepted = await ready.future.timeout(const Duration(seconds: 25), onTimeout: () {
      fail();
      return false;
    });
    if (!accepted) return false;
    // Catch an immediate crash after acknowledgement; this is not a promise
    // that the UI cannot exit later.
    return Future.any<bool>([
      process.exitCode.then((_) => false, onError: (Object _) => false),
      Future<bool>.delayed(const Duration(milliseconds: 250), () => true),
    ]);
  }
}
