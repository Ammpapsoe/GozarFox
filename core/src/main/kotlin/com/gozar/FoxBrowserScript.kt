package com.gozar

/**
 * The one piece of JavaScript we put into the sign-in page.
 *
 * It is installed with `WebViewCompat.addDocumentStartJavaScript`, so it runs
 * before any of Mozilla's own scripts: the page dispatches the credential as a
 * window event the moment it has one, and a listener attached later would
 * simply miss it.
 *
 * The script is a relay and nothing more. It does not read form fields, does
 * not touch cookies or storage, does not rewrite the page, and does not decide
 * whether a message is acceptable — it forwards the envelope as it arrived and
 * lets [FoxWebChannel] judge it natively. Keeping the trust boundary on the
 * Kotlin side means the rules are reviewable and testable, and a compromised
 * page cannot talk us into accepting something by changing what the script
 * does, because the script never validates anything in the first place.
 *
 * Two practical problems are handled here. The bridge object may not exist yet
 * when the document starts, so outbound messages are queued briefly and the
 * reply handler is attached by polling. And the script can be installed more
 * than once across a navigation, so it marks the window and refuses to add a
 * second pair of listeners that would deliver every message twice.
 */
object FoxBrowserScript {

    /** Name of the object exposed to the page by `addWebMessageListener`. */
    const val BRIDGE = "gozarAccountBridge"

    val script: String = """
(function () {
  "use strict";
  if (window.gozarChannelRelay) { return; }
  window.gozarChannelRelay = true;

  var CHANNEL = "${FoxWebChannel.CHANNEL_ID}";
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
    var candidate = window.${BRIDGE};
    if (candidate && typeof candidate.postMessage === "function") { return candidate; }
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
  // events off the bridge. The native side is the authority on content.
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

  function fromNative(event) {
    var raw = asText(event && event.data);
    if (!raw) { return; }
    window.dispatchEvent(new CustomEvent(TO_CONTENT, { detail: raw }));
  }

  function wire() {
    if (wired) { return true; }
    var target = bridge();
    if (!target) { return false; }
    wired = true;
    target.onmessage = fromNative;
    while (queued.length) {
      var raw = queued.shift();
      try { target.postMessage(raw); } catch (error) { return true; }
    }
    return true;
  }

  window.addEventListener(TO_CHROME, function (event) {
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
""".trimIndent()
}
