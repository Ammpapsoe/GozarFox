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
