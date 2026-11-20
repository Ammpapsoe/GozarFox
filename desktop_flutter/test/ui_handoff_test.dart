import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:gozar/core.dart';
import 'package:gozar/original_ui_process_target.dart';
import 'package:gozar/ui_handoff.dart';

class Target implements OriginalUiTarget {
  bool available = true;
  bool succeeds = true;
  int checks = 0;
  int launches = 0;
  Completer<bool>? pending;
  @override
  Future<bool> isAvailable() async { checks++; return available; }
  @override
  Future<bool> startAndWaitReady() async {
    launches++;
    if (pending != null) return await pending!.future;
    return succeeds;
  }
}

class HandoffCore extends Core {
  HandoffCore(this.result, {this.remainRunning = false});
  CoreHandoffResult result;
  bool remainRunning;
  bool active = true;
  int attempts = 0;
  @override
  bool get running => active;
  @override
  Future<CoreHandoffResult> prepareUiHandoff() async {
    attempts++;
    if (result == CoreHandoffResult.ready && !remainRunning) active = false;
    return result;
  }
}

void main() {
  test('missing target is detected before stopping the existing core', () async {
    final core = HandoffCore(CoreHandoffResult.ready);
    final target = Target()..available = false;
    expect(await OriginalUiHandoff(core, target).transfer(), OriginalUiHandoffResult.unavailable);
    expect(core.attempts, 0);
    expect(core.running, isTrue);
    expect(target.launches, 0);
  });

  test('connected or unconfirmed core never launches the target', () async {
    for (final state in [CoreHandoffResult.busy, CoreHandoffResult.signedOut,
        CoreHandoffResult.connected, CoreHandoffResult.unconfirmed]) {
      final core = HandoffCore(state);
      final target = Target();
      final coordinator = OriginalUiHandoff(core, target);
      expect(await coordinator.transfer(), isNot(OriginalUiHandoffResult.ready));
      expect(target.launches, 0);
      if (state == CoreHandoffResult.unconfirmed) {
        expect(await coordinator.transfer(), OriginalUiHandoffResult.unconfirmed);
        expect(core.attempts, 1);
      }
    }
  });

  test('ready cannot launch while the old core is still running', () async {
    final core = HandoffCore(CoreHandoffResult.ready, remainRunning: true);
    final target = Target();
    expect(await OriginalUiHandoff(core, target).transfer(), OriginalUiHandoffResult.unconfirmed);
    expect(target.launches, 0);
  });

  test('failed or concurrent target launch cannot cause a second owner', () async {
    final core = HandoffCore(CoreHandoffResult.ready);
    final target = Target()..pending = Completer<bool>();
    final coordinator = OriginalUiHandoff(core, target);
    final first = coordinator.transfer();
    await Future<void>.delayed(Duration.zero);
    expect(target.launches, 1);
    expect(await coordinator.transfer(), OriginalUiHandoffResult.busy);
    target.pending!.complete(false);
    expect(await first, OriginalUiHandoffResult.launchFailed);
    expect(await coordinator.transfer(), OriginalUiHandoffResult.unconfirmed);
    expect(target.launches, 1);
  });

  test('cancelled owner does not launch a replacement after shutdown', () async {
    final core = HandoffCore(CoreHandoffResult.ready);
    final target = Target();
    var checks = 0;
    final result = await OriginalUiHandoff(core, target).transfer(canLaunch: () => ++checks == 1);
    expect(result, OriginalUiHandoffResult.unconfirmed);
    expect(target.launches, 0);
  });

  final fakePath = Platform.environment['GOZAR_HANDOFF_TEST_CORE'];
  test('process launcher refuses missing executable before stopping core', () async {
    final core = HandoffCore(CoreHandoffResult.ready);
    final target = OriginalUiProcessTarget('this-file-does-not-exist-gozar-qt');
    expect(await OriginalUiHandoff(core, target).transfer(), OriginalUiHandoffResult.unavailable);
    expect(core.attempts, 0);
    expect(core.running, isTrue);
  });
  test('process launcher rejects invalid or absent readiness and cannot retry', () async {
    for (final mode in ['invalid', 'oversize', 'exit']) {
      final target = OriginalUiProcessTarget(fakePath!, environment: {'FAKE_UI_MODE': mode});
      expect(await target.isAvailable(), isTrue);
      expect(await target.startAndWaitReady(), isFalse);
      expect(await target.startAndWaitReady(), isFalse);
    }
  }, skip: fakePath == null ? 'Requires the GitHub-built process fixture' : false);
  test('actual old process exits before separate launcher fixture acknowledges readiness', () async {
    final core = Core(executablePath: fakePath);
    await core.start();
    expect((await core.call('state')).state?.signedIn, isTrue);
    final target = OriginalUiProcessTarget(fakePath!, environment: {'FAKE_UI_MODE': 'ready'});
    expect(await OriginalUiHandoff(core, target).transfer(), OriginalUiHandoffResult.ready);
    expect(core.running, isFalse);
  }, skip: fakePath == null ? 'Requires the GitHub-built process fixture' : false);

  final qtFixture = Platform.environment['GOZAR_HANDOFF_TEST_QT'];
  test('handoff reaches a real upstream Qt widget with a fake core', () async {
    final core = Core(executablePath: fakePath);
    await core.start();
    expect((await core.call('state')).state?.signedIn, isTrue);
    final target = OriginalUiProcessTarget(qtFixture!, environment: {
      'GOZAR_FAKE_CORE': fakePath!,
      'QT_QPA_PLATFORM': 'offscreen',
    });
    expect(await OriginalUiHandoff(core, target).transfer(), OriginalUiHandoffResult.ready);
    expect(core.running, isFalse);
    await expectLater(core.start(), throwsStateError);
  }, skip: fakePath == null || qtFixture == null ? 'Requires GitHub-built Qt and fake core fixtures' : false);

  test('actual process exits before target launch is attempted', () async {
    final core = Core(executablePath: fakePath);
    await core.start();
    expect((await core.call('state')).state?.signedIn, isTrue);
    final target = Target();
    expect(await OriginalUiHandoff(core, target).transfer(), OriginalUiHandoffResult.ready);
    expect(core.running, isFalse);
    expect(target.launches, 1);
    await expectLater(core.start(), throwsStateError);
  }, skip: fakePath == null ? 'Requires the GitHub-built fake core' : false);
}
