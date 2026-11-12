package com.gozar

import org.json.JSONArray
import org.json.JSONObject

/**
 * The WebChannel that Firefox Accounts speaks to a browser.
 *
 * The sign-in page does not post a credential to a URL. It announces it to the
 * thing hosting the page by dispatching a `WebChannelMessageToChrome` window
 * event whose detail is a JSON envelope, and it waits for answers on
 * `WebChannelMessageToContent`. This object is the only place that decides what
 * counts as a usable message, so the WebView layer can stay thin and the rules
 * can be tested without an Android device.
 *
 * Everything here is deliberately strict. A credential is the single most
 * valuable thing this app ever touches, so a message that is even slightly off
 * is dropped rather than half-accepted.
 */
object FoxWebChannel {

    /** The only origin a credential may arrive from. Compared exactly. */
    const val ORIGIN = "https://accounts.firefox.com"

    /** Envelope id the page uses. A message addressed to another channel is not ours. */
    const val CHANNEL_ID = "account_updates"

    const val COMMAND_LOGIN = "fxaccounts:login"
    const val COMMAND_CAN_LINK = "fxaccounts:can_link_account"
    const val COMMAND_STATUS = "fxaccounts:fxa_status"
    const val COMMAND_FLOW_ACTIVE = "fxaccounts:oauth_flow_is_active"

    /** A session token is 32 bytes; a uid is 16. Both arrive lowercase hex. */
    private val SESSION_TOKEN = Regex("^[0-9a-f]{64}$")
    private val ACCOUNT_ID = Regex("^[0-9a-f]{32}$")

    private const val MAX_ENVELOPE_BYTES = 64 * 1024

    /**
     * A validated credential. `toString` is overridden because a data class
     * would print the token into any log line that ever received this object.
     */
    class Login(val email: String, val uid: String, val sessionToken: String) {
        override fun toString() = "Login(email=$email, uid=$uid, sessionToken=redacted)"
    }

    sealed class Message {
        /** The page handed us a credential. */
        class SignedIn(val login: Login) : Message()

        /**
         * The page is waiting for an answer. [answer] builds the payload and
         * [reply] wraps it in the envelope the page will accept.
         */
        class Waiting(val command: String, val messageId: String) : Message()

        /** A channel message we have no use for, well-formed or not. */
        object Ignored : Message()

        /**
         * Structurally wrong, from the wrong origin, or incomplete. `reason`
         * names the failed check only; it never carries message content.
         */
        class Rejected(val reason: String) : Message()
    }

    /**
     * Reads one envelope. `origin` and `mainFrame` come from the platform and
     * are checked before the payload is even parsed, because a payload from
     * anywhere else must never influence us.
     */
    fun read(origin: String?, mainFrame: Boolean, payload: String?): Message {
        if (!ORIGIN.equals(normalizeOrigin(origin), ignoreCase = false)) {
            return Message.Rejected("origin")
        }
        if (!mainFrame) return Message.Rejected("frame")
        val text = payload?.trim().orEmpty()
        if (text.isEmpty()) return Message.Rejected("empty")
        if (text.length > MAX_ENVELOPE_BYTES) return Message.Rejected("size")
        val envelope = try {
            JSONObject(text)
        } catch (_: Exception) {
            return Message.Rejected("envelope")
        }
        if (envelope.optString("id") != CHANNEL_ID) return Message.Rejected("channel")
        val message = envelope.optJSONObject("message") ?: return Message.Rejected("message")
        return when (val command = message.optString("command")) {
            COMMAND_LOGIN -> login(message)
            COMMAND_CAN_LINK, COMMAND_STATUS, COMMAND_FLOW_ACTIVE ->
                Message.Waiting(command, message.optString("messageId"))
            "" -> Message.Rejected("command")
            else -> Message.Ignored
        }
    }

    private fun login(message: JSONObject): Message {
        val data = message.optJSONObject("data") ?: return Message.Rejected("login-data")
        // No trimming: a hex token that arrives with whitespace attached is not
        // a token we recognise, and silently cleaning it would hide a page that
        // is producing something other than what Mozilla documents.
        val token = data.optString("sessionToken").lowercase()
        if (!SESSION_TOKEN.matches(token)) return Message.Rejected("session-token")
        val uid = data.optString("uid").lowercase()
        if (!ACCOUNT_ID.matches(uid)) return Message.Rejected("uid")
        val email = saneEmail(data.optString("email")) ?: return Message.Rejected("email")
        return Message.SignedIn(Login(email, uid, token))
    }

    /**
     * A sanity check, not a full RFC 5322 parse: Mozilla has already accepted
     * this address, we only refuse something that cannot be an address at all.
     */
    private fun saneEmail(raw: String?): String? {
        val email = raw?.trim().orEmpty()
        if (email.isEmpty() || email.length > 254) return null
        val at = email.indexOf('@')
        if (at <= 0 || at != email.lastIndexOf('@')) return null
        if (at == email.length - 1) return null
        if (email.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return null
        return email
    }

    private fun normalizeOrigin(origin: String?): String {
        val text = origin?.trim().orEmpty()
        return if (text.endsWith("/")) text.dropLast(1) else text
    }

    /** The answer the page expects for a command it is waiting on. */
    fun answer(command: String): JSONObject = when (command) {
        // The page awaits this with no timeout at all. Silence here does not
        // mean "no": it hangs the sign-in forever. Answering false shows
        // "Login attempt cancelled". We have no other profile to link against,
        // so yes is the only correct answer.
        COMMAND_CAN_LINK -> JSONObject().put("ok", true)
        // Honest about what we are: no Sync engines, no pairing, and never a
        // signed-in browser profile. Left unanswered the page waits a second
        // and then continues, so this only removes a stall.
        COMMAND_STATUS -> JSONObject().put(
            "capabilities",
            JSONObject()
                .put("engines", JSONArray())
                .put("multiService", true)
                .put("pairing", false)
                .put("can_link_account_uid", false)
        )
        // We hold no OAuth flow of our own; the page should start one.
        COMMAND_FLOW_ACTIVE -> JSONObject().put("isActive", false)
        else -> JSONObject()
    }

    /** Wraps an answer in the envelope `WebChannelMessageToContent` expects. */
    fun reply(command: String, messageId: String, data: JSONObject): String = JSONObject()
        .put("id", CHANNEL_ID)
        .put(
            "message",
            JSONObject()
                .put("command", command)
                .put("data", data)
                .put("messageId", messageId)
        )
        .toString()

    /** Builds the reply for a [Message.Waiting] in one step. */
    fun replyTo(waiting: Message.Waiting): String =
        reply(waiting.command, waiting.messageId, answer(waiting.command))
}
