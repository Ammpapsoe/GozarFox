import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:gozar/core.dart';

void main() {
  test('handoff validation rejects missing or invalid safety state', () {
    final missing = CoreReply.fromJson({
      'state': {'signed_in': true, 'live': false},
    });
    expect(missing.stateVerified, isFalse);
    expect(missing.errorVerified, isTrue);

    final invalidError = CoreReply.fromJson({
      'state': {'signed_in': true, 'live': false, 'connecting': false},
      'error': 1,
    });
    expect(invalidError.stateVerified, isTrue);
    expect(invalidError.errorVerified, isFalse);
  });

  final fakePath = Platform.environment['GOZAR_HANDOFF_TEST_CORE'];
  test('overlapping starts share one actual child process', () async {
    final permit = Completer<void>();
    var launches = 0;
    final core = Core(executablePath: fakePath, processStarter: (path, args) async {
      launches++;
      await permit.future;
      return Process.start(path, args);
    });
    final first = core.start();
    final second = core.start();
    await Future<void>.delayed(Duration.zero);
    expect(launches, 1);
    permit.complete();
    await Future.wait([first, second]);
    expect(launches, 1);
    expect((await core.call('state')).state?.signedIn, isTrue);
    await core.shutdown();
    expect(core.running, isFalse);
    await expectLater(core.start(), throwsStateError);
  }, skip: fakePath == null ? 'Requires the GitHub-built fake core' : false);

  test('shutdown during spawn gracefully quits the late-starting child', () async {
    final permit = Completer<void>();
    Process? spawned;
    final core = Core(executablePath: fakePath, processStarter: (path, args) async {
      await permit.future;
      return spawned = await Process.start(path, args);
    });
    final startError = expectLater(core.start(), throwsStateError);
    final shutdown = core.shutdown();
    permit.complete();
    await startError;
    await shutdown;
    expect(spawned, isNotNull);
    expect(await spawned!.exitCode, 0);
    expect(core.running, isFalse);
    await expectLater(core.start(), throwsStateError);
  }, skip: fakePath == null ? 'Requires the GitHub-built fake core' : false);

  test('one owner: connected handoff refused, quit acknowledged before transfer',
      () async {
    final core = Core(executablePath: fakePath);
    await core.start();
    final initial = await core.call('state');
    expect(initial.stateVerified, isTrue);
    expect(initial.state?.signedIn, isTrue);
    await core.call('connect');
    expect(await core.prepareUiHandoff(), CoreHandoffResult.connected);
    expect(core.running, isTrue);
    await core.call('disconnect');
    expect(await core.prepareUiHandoff(), CoreHandoffResult.ready);
    await expectLater(core.call('connect'), throwsStateError);
  }, skip: fakePath == null ? 'Requires the GitHub-built fake core' : false);
}
