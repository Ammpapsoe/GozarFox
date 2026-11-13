import 'dart:convert';

/// A validated credential from Mozilla's sign-in page.
///
/// `toString` is overridden because the default would print the token into
/// any log line that ever received this object.
class SignInSession {
  const SignInSession({
    required this.email,
    required this.uid,
    required this.sessionToken,
  });

  final String email;
  final String uid;
  final String sessionToken;

  @override
  String toString() =>
      'SignInSession(email=$email, uid=$uid, sessionToken=redacted)';
}

/// One envelope the sign-in page sent, judged.
sealed class ChannelMessage {
  const ChannelMessage();
}

/// The page handed us a credential.
class SignedIn extends ChannelMessage {
  const SignedIn(this.session);

  final SignInSession session;
}

/// The page is waiting for an answer to [command]; [messageId] echoes back.
class Waiting extends ChannelMessage {
  const Waiting(this.command, this.messageId);

  final String command;
  final String messageId;
}

/// A channel message we have no use for, well-formed or not.
class Ignored extends ChannelMessage {
  const Ignored();
}

/// Structurally wrong or incomplete. [reason] names the failed check only;
/// it never carries message content.
class Rejected extends ChannelMessage {
  const Rejected(this.reason);

  final String reason;
}

/// The WebChannel that Firefox Accounts speaks to a browser — the desktop
/// twin of the Android core's rules, and just as strict: a credential is the
/// single most valuable thing this app ever touches, so a message that is
/// even slightly off is dropped rather than half-accepted.
///
/// The sign-in page does not post a credential to a URL. It announces it to
/// the thing hosting the page by dispatching a `WebChannelMessageToChrome`
/// window event whose detail is a JSON envelope, and it waits for answers on
/// `WebChannelMessageToContent`.
class WebChannel {
  const WebChannel._();

  /// The only origin a credential may arrive from. The relay script checks
  /// it before forwarding, because on desktop the bridge itself carries no
  /// origin.
  static const String origin = 'https://accounts.firefox.com';

  /// Envelope id the page uses. A message addressed to another channel is
  /// not ours.
  static const String channelId = 'account_updates';

  static const String commandLogin = 'fxaccounts:login';
  static const String commandCanLink = 'fxaccounts:can_link_account';
  static const String commandStatus = 'fxaccounts:fxa_status';
  static const String commandFlowActive = 'fxaccounts:oauth_flow_is_active';

  /// A session token is 32 bytes; a uid is 16. Both arrive lowercase hex.
  static final RegExp _sessionToken = RegExp(r'^[0-9a-f]{64}$');
  static final RegExp _accountId = RegExp(r'^[0-9a-f]{32}$');

  static const int _maxEnvelopeBytes = 64 * 1024;

  /// Reads one envelope. Both desktop bridges deliver what the relay script
  /// posted: a JSON string. Anything else is rejected before parsing.
  static ChannelMessage read(Object? raw) {
    final text = raw is String ? raw.trim() : '';
    if (text.isEmpty) return const Rejected('empty');
    if (text.length > _maxEnvelopeBytes) return const Rejected('size');
    final Object? decoded;
    try {
      decoded = jsonDecode(text);
    } on FormatException {
      return const Rejected('envelope');
    }
    if (decoded is! Map<String, dynamic>) return const Rejected('envelope');
    if (decoded['id'] != channelId) return const Rejected('channel');
    final message = decoded['message'];
    if (message is! Map<String, dynamic>) return const Rejected('message');
    final command = message['command'];
    if (command is! String || command.isEmpty) return const Rejected('command');
    switch (command) {
      case commandLogin:
        return _login(message);
      case commandCanLink:
      case commandStatus:
      case commandFlowActive:
        return Waiting(command, message['messageId'] as String? ?? '');
      default:
        return const Ignored();
    }
  }

  static ChannelMessage _login(Map<String, dynamic> message) {
    final data = message['data'];
    if (data is! Map<String, dynamic>) return const Rejected('login-data');
    // No trimming: a hex token that arrives with whitespace attached is not
    // a token we recognise, and silently cleaning it would hide a page that
    // is producing something other than what Mozilla documents.
    final token = (data['sessionToken'] as String? ?? '').toLowerCase();
    if (!_sessionToken.hasMatch(token)) return const Rejected('session-token');
    final uid = (data['uid'] as String? ?? '').toLowerCase();
    if (!_accountId.hasMatch(uid)) return const Rejected('uid');
    final email = _saneEmail(data['email'] as String?);
    if (email == null) return const Rejected('email');
    return SignedIn(SignInSession(email: email, uid: uid, sessionToken: token));
  }

  /// A sanity check, not a full RFC 5322 parse: Mozilla has already accepted
  /// this address, we only refuse something that cannot be an address at all.
  static String? _saneEmail(String? raw) {
    final email = raw?.trim() ?? '';
    if (email.isEmpty || email.length > 254) return null;
    final at = email.indexOf('@');
    if (at <= 0 || at != email.lastIndexOf('@')) return null;
    if (at == email.length - 1) return null;
    for (final code in email.codeUnits) {
      if (code <= 0x20 || code == 0x7f) return null;
      if (code > 0x7f && String.fromCharCode(code).trim().isEmpty) {
        return null;
      }
    }
    return email;
  }

  /// The answer the page expects for a command it is waiting on.
  static Map<String, Object?> answer(String command) => switch (command) {
        // The page awaits this with no timeout at all. Silence here does not
        // mean "no": it hangs the sign-in forever. Answering false shows
        // "Login attempt cancelled". We have no other profile to link
        // against, so yes is the only correct answer.
        commandCanLink => <String, Object?>{'ok': true},
        // Honest about what we are: no Sync engines, no pairing, and never a
        // signed-in browser profile. Left unanswered the page waits a second
        // and then continues, so this only removes a stall.
        commandStatus => <String, Object?>{
            'capabilities': <String, Object?>{
              'engines': <Object?>[],
              'multiService': true,
              'pairing': false,
              'can_link_account_uid': false,
            },
          },
        // We hold no OAuth flow of our own; the page should start one.
        commandFlowActive => <String, Object?>{'isActive': false},
        _ => const <String, Object?>{},
      };

  /// The reply envelope for a [Waiting] message, ready to hand to
  /// `window.gozarDeliver`.
  static String replyTo(Waiting waiting) => jsonEncode(<String, Object?>{
        'id': channelId,
        'message': <String, Object?>{
          'command': waiting.command,
          'data': answer(waiting.command),
          'messageId': waiting.messageId,
        },
      });

  /// The one piece of JavaScript we put into the sign-in page — the desktop
  /// form of the Android relay. It is a relay and nothing more: it does not
  /// read form fields, does not touch cookies or storage, does not rewrite
  /// the page, and does not decide whether a message is acceptable. It
  /// forwards the envelope as it arrived and lets [WebChannel.read] judge it
  /// in Dart, so a compromised page cannot talk us into accepting something
  /// by changing what the script does.
  ///
  /// Both desktop bridges are found by name (`chrome.webview` on Windows,
  /// the `GozarBridge` JavaScript channel on macOS), outbound messages are
  /// queued briefly while the bridge appears, and answers come back through
  /// `window.gozarDeliver`, which the page side of this file calls with a
  /// reply envelope. The script marks the window and refuses to install
  /// twice, so injecting it early and again per page is safe.
  static const String relayScript = '''
(function () {
  "use strict";
  if (window.gozarChannelRelay) { return; }
  window.gozarChannelRelay = true;

  var CHANNEL = "account_updates";
  var ORIGIN = "https://accounts.firefox.com";
  var TO_CHROME = "WebChannelMessageToChrome";
  var TO_CONTENT = "WebChannelMessageToContent";
  var MAX_LENGTH = 65536;
  var QUEUE_LIMIT = 16;
  var POLL_MS = 100;
  var POLL_LIMIT = 600;

  var queued = [];
  var wired = false;
  var polls = 0;

  function bridge() {
    var edge = window.chrome && window.chrome.webview;
    if (edge && typeof edge.postMessage === "function") { return edge; }
    var named = window.GozarBridge;
    if (named && typeof named.postMessage === "function") { return named; }
    return null;
  }

  function asText(detail) {
    if (typeof detail === "string") { return detail; }
    if (detail && typeof detail === "object") {
      try { return JSON.stringify(detail); } catch (error) { return null; }
    }
    return null;
  }

  // A cheap pre-filter, not a validation: it only keeps unrelated window
  // events off the bridge. The Dart side is the authority on content.
  function isChannelEnvelope(raw) {
    if (!raw || raw.length > MAX_LENGTH) { return false; }
    var envelope;
    try { envelope = JSON.parse(raw); } catch (error) { return false; }
    if (!envelope || envelope.id !== CHANNEL) { return false; }
    var message = envelope.message;
    return !!message && typeof message.command === "string";
  }

  function post(raw) {
    var target = bridge();
    if (target) {
      try { target.postMessage(raw); } catch (error) { }
      return;
    }
    if (queued.length < QUEUE_LIMIT) { queued.push(raw); }
  }

  window.gozarDeliver = function (raw) {
    if (typeof raw !== "string" || raw.length > MAX_LENGTH) { return; }
    window.dispatchEvent(new CustomEvent(TO_CONTENT, { detail: raw }));
  };

  function wire() {
    if (wired) { return true; }
    var target = bridge();
    if (!target) { return false; }
    wired = true;
    while (queued.length) {
      var raw = queued.shift();
      try { target.postMessage(raw); } catch (error) { return true; }
    }
    return true;
  }

  window.addEventListener(TO_CHROME, function (event) {
    if (window.location.origin !== ORIGIN) { return; }
    var raw = asText(event && event.detail);
    if (isChannelEnvelope(raw)) { post(raw); }
  });

  if (!wire()) {
    var timer = window.setInterval(function () {
      polls += 1;
      if (wire() || polls > POLL_LIMIT) { window.clearInterval(timer); }
    }, POLL_MS);
  }
})();
''';

  /// Painted before the page's own CSS: the sign-in's blank canvas is the
  /// app's dark, never a white flash. Mozilla's stylesheet overrides it the
  /// moment it loads, so the page ends up looking exactly like Mozilla's.
  static const String darkCanvas = '''
(function () {
  var paint = function () {
    var root = document.documentElement;
    if (root && !root.style.backgroundColor) {
      root.style.backgroundColor = "#0b0b0d";
    }
    var body = document.body;
    if (body && !body.style.backgroundColor) {
      body.style.backgroundColor = "#0b0b0d";
    }
  };
  paint();
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", paint);
  }
})();
''';
}
