import 'core.dart';

/// A future, separately packaged Qt UI. This is deliberately not implemented
/// by the shipped app yet. [isAvailable] must be a side-effect-free preflight.
/// A target must return true from [startAndWaitReady] only after its own UI
/// has initialized and its new core reports a verified signed-in, disconnected
/// state, not merely after spawning a process. A timeout is not proof the
/// target stopped. No passwords or tokens may be passed as arguments.
abstract class OriginalUiTarget {
  Future<bool> isAvailable();
  Future<bool> startAndWaitReady();
}

enum OriginalUiHandoffResult {
  unavailable,
  busy,
  signedOut,
  connected,
  unconfirmed,
  launchFailed,
  ready,
}

/// Coordinates the existing login/core owner with a future Qt UI. It never
/// starts a second owner until the old core has reported clean shutdown and
/// exited. This is not independent verification of OS proxy/route cleanup.
class OriginalUiHandoff {
  OriginalUiHandoff(this.core, this.target);

  final Core core;
  final OriginalUiTarget target;
  bool _active = false;
  bool _consumed = false;

  Future<OriginalUiHandoffResult> transfer({bool Function()? canLaunch}) async {
    if (_active) return OriginalUiHandoffResult.busy;
    if (_consumed) return OriginalUiHandoffResult.unconfirmed;
    _active = true;
    try {
      try {
        if (!await target.isAvailable().timeout(const Duration(seconds: 5))) {
          return OriginalUiHandoffResult.unavailable;
        }
      } on Object {
        return OriginalUiHandoffResult.unavailable;
      }
      if (canLaunch != null && !canLaunch()) return OriginalUiHandoffResult.unavailable;
      CoreHandoffResult result;
      try {
        result = await core.prepareUiHandoff();
      } on Object {
        _consumed = true;
        return OriginalUiHandoffResult.unconfirmed;
      }
      switch (result) {
        case CoreHandoffResult.busy:
          return OriginalUiHandoffResult.busy;
        case CoreHandoffResult.signedOut:
          return OriginalUiHandoffResult.signedOut;
        case CoreHandoffResult.connected:
          return OriginalUiHandoffResult.connected;
        case CoreHandoffResult.unconfirmed:
          _consumed = true;
          return OriginalUiHandoffResult.unconfirmed;
        case CoreHandoffResult.ready:
          _consumed = true;
          // The target must never be started while the old owner is running,
          // even if an implementation accidentally reports ready too early.
          if (core.running || (canLaunch != null && !canLaunch())) {
            return OriginalUiHandoffResult.unconfirmed;
          }
          try {
            return await target.startAndWaitReady().timeout(const Duration(seconds: 30))
                ? OriginalUiHandoffResult.ready
                : OriginalUiHandoffResult.launchFailed;
          } on Object {
            // A failed/partial launch may itself own a core. Do not restart the
            // old owner or make another attempt without explicit recovery.
            return OriginalUiHandoffResult.launchFailed;
          }
      }
    } finally {
      _active = false;
    }
  }
}
