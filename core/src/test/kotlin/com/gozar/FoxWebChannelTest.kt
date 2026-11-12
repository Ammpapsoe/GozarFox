package com.gozar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxWebChannelTest {

    private val token = "a".repeat(64)
    private val uid = "b".repeat(32)
    private val origin = FoxWebChannel.ORIGIN

    private fun envelope(
        command: String,
        data: JSONObject,
        channel: String = FoxWebChannel.CHANNEL_ID,
        messageId: String = "message-1"
    ): String = JSONObject()
        .put("id", channel)
        .put(
            "message",
            JSONObject().put("command", command).put("data", data).put("messageId", messageId)
        )
        .toString()

    private fun login(
        sessionToken: String = token,
        accountUid: String = uid,
        email: String = "someone@example.com"
    ) = JSONObject().put("sessionToken", sessionToken).put("uid", accountUid).put("email", email)

    private fun read(
        payload: String?,
        from: String? = origin,
        mainFrame: Boolean = true
    ) = FoxWebChannel.read(from, mainFrame, payload)

    private fun rejected(payload: String?, from: String? = origin, mainFrame: Boolean = true): String {
        val message = read(payload, from, mainFrame)
        assertTrue("expected a rejection, got $message", message is FoxWebChannel.Message.Rejected)
        return (message as FoxWebChannel.Message.Rejected).reason
    }

    @Test
    fun aCompleteLoginMessageIsAccepted() {
        val message = read(envelope(FoxWebChannel.COMMAND_LOGIN, login()))
        assertTrue(message is FoxWebChannel.Message.SignedIn)
        val accepted = (message as FoxWebChannel.Message.SignedIn).login
        assertEquals("someone@example.com", accepted.email)
        assertEquals(uid, accepted.uid)
        assertEquals(token, accepted.sessionToken)
    }

    @Test
    fun upperCaseHexIsNormalisedBeforeUse() {
        val message = read(envelope(FoxWebChannel.COMMAND_LOGIN, login(token.uppercase(), uid.uppercase())))
        val accepted = (message as FoxWebChannel.Message.SignedIn).login
        assertEquals(token, accepted.sessionToken)
        assertEquals(uid, accepted.uid)
    }

    @Test
    fun aSessionTokenIsNeverPrinted() {
        val accepted = (read(envelope(FoxWebChannel.COMMAND_LOGIN, login())) as FoxWebChannel.Message.SignedIn).login
        val printed = accepted.toString()
        assertFalse(printed.contains(token))
        assertTrue(printed.contains("redacted"))
    }

    @Test
    fun anythingFromAnotherOriginIsRefusedBeforeParsing() {
        val payload = envelope(FoxWebChannel.COMMAND_LOGIN, login())
        for (bad in listOf(
            null,
            "",
            "http://accounts.firefox.com",
            "https://accounts.firefox.com.evil.example",
            "https://evil.example/accounts.firefox.com",
            "https://sub.accounts.firefox.com",
            "file://",
            "null"
        )) {
            assertEquals("origin", rejected(payload, bad))
        }
    }

    @Test
    fun aTrailingSlashOnTheOriginIsTheSameOrigin() {
        val message = read(envelope(FoxWebChannel.COMMAND_LOGIN, login()), "$origin/")
        assertTrue(message is FoxWebChannel.Message.SignedIn)
    }

    @Test
    fun aSubframeCannotDeliverACredential() {
        assertEquals("frame", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login()), mainFrame = false))
    }

    @Test
    fun aMalformedEnvelopeIsRefused() {
        assertEquals("empty", rejected(null))
        assertEquals("empty", rejected("   "))
        assertEquals("envelope", rejected("not json"))
        assertEquals("envelope", rejected("[1,2,3]"))
        assertEquals("channel", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(), channel = "other")))
        assertEquals("message", rejected(JSONObject().put("id", FoxWebChannel.CHANNEL_ID).toString()))
        assertEquals("command", rejected(JSONObject().put("id", FoxWebChannel.CHANNEL_ID)
            .put("message", JSONObject().put("data", login())).toString()))
    }

    @Test
    fun anOversizedEnvelopeIsRefusedWithoutBeingParsed() {
        val huge = envelope(
            FoxWebChannel.COMMAND_LOGIN,
            login().put("padding", "x".repeat(70 * 1024))
        )
        assertEquals("size", rejected(huge))
    }

    @Test
    fun anIncompleteCredentialIsRefusedFieldByField() {
        assertEquals("session-token", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(sessionToken = ""))))
        assertEquals("session-token", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(sessionToken = "a".repeat(63)))))
        assertEquals("session-token", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(sessionToken = "a".repeat(65)))))
        assertEquals("session-token", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(sessionToken = "z".repeat(64)))))
        assertEquals("session-token", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(sessionToken = "$token\n"))))
        assertEquals("uid", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(accountUid = ""))))
        assertEquals("uid", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(accountUid = "b".repeat(31)))))
        assertEquals("uid", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(accountUid = "g".repeat(32)))))
        assertEquals("login-data", rejected(JSONObject().put("id", FoxWebChannel.CHANNEL_ID)
            .put("message", JSONObject().put("command", FoxWebChannel.COMMAND_LOGIN)).toString()))
    }

    @Test
    fun anAddressThatCannotBeAnAddressIsRefused() {
        for (bad in listOf("", "plain", "@example.com", "someone@", "two@@example.com", "some one@example.com")) {
            assertEquals("email", rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(email = bad))))
        }
    }

    @Test
    fun commandsThePageWaitsOnAreAnswered() {
        val canLink = read(envelope(FoxWebChannel.COMMAND_CAN_LINK, JSONObject().put("email", "a@b.co")))
        assertTrue(canLink is FoxWebChannel.Message.Waiting)
        val answer = JSONObject(FoxWebChannel.replyTo(canLink as FoxWebChannel.Message.Waiting))
        assertEquals(FoxWebChannel.CHANNEL_ID, answer.getString("id"))
        val message = answer.getJSONObject("message")
        assertEquals(FoxWebChannel.COMMAND_CAN_LINK, message.getString("command"))
        assertEquals("message-1", message.getString("messageId"))
        assertTrue(message.getJSONObject("data").getBoolean("ok"))
    }

    @Test
    fun theStatusAnswerClaimsNoSignedInBrowserProfile() {
        val status = read(envelope(FoxWebChannel.COMMAND_STATUS, JSONObject())) as FoxWebChannel.Message.Waiting
        val reply = FoxWebChannel.replyTo(status)
        val data = JSONObject(reply).getJSONObject("message").getJSONObject("data")
        val capabilities = data.getJSONObject("capabilities")
        assertTrue(capabilities.getBoolean("multiService"))
        assertFalse(capabilities.getBoolean("pairing"))
        assertFalse(capabilities.getBoolean("can_link_account_uid"))
        assertEquals(0, capabilities.getJSONArray("engines").length())
        // Claiming a signed-in user would make the page offer to reuse a
        // browser profile that does not exist.
        assertFalse(data.has("signedInUser"))
    }

    @Test
    fun anOAuthFlowWeDoNotHoldIsReportedInactive() {
        val flow = read(envelope(FoxWebChannel.COMMAND_FLOW_ACTIVE, JSONObject())) as FoxWebChannel.Message.Waiting
        val data = JSONObject(FoxWebChannel.replyTo(flow)).getJSONObject("message").getJSONObject("data")
        assertFalse(data.getBoolean("isActive"))
    }

    @Test
    fun channelMessagesWeHaveNoUseForAreIgnoredQuietly() {
        for (command in listOf("fxaccounts:logout", "fxaccounts:loaded", "profile:change", "fxaccounts:oauth_login")) {
            assertEquals(FoxWebChannel.Message.Ignored, read(envelope(command, JSONObject())))
        }
    }

    @Test
    fun aRejectedMessageNeverCarriesPayloadContent() {
        val reason = rejected(envelope(FoxWebChannel.COMMAND_LOGIN, login(sessionToken = "a".repeat(63))))
        assertEquals("session-token", reason)
        assertFalse(reason.contains("a".repeat(10)))
    }
}
