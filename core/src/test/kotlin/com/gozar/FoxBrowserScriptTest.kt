package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The injected script is the only code of ours that runs inside Mozilla's page,
 * so its guarantees are checked here rather than trusted: it must reach the
 * bridge, it must not reach anything else, and it must not be installed twice.
 */
class FoxBrowserScriptTest {

    private val script = FoxBrowserScript.script

    @Test
    fun theBridgeNameIsAPlainJavascriptIdentifier() {
        assertTrue(
            "not a valid identifier: ${FoxBrowserScript.BRIDGE}",
            Regex("^[A-Za-z_$][A-Za-z0-9_$]*$").matches(FoxBrowserScript.BRIDGE)
        )
        assertTrue(script.contains("window." + FoxBrowserScript.BRIDGE))
    }

    @Test
    fun bothDirectionsOfTheChannelAreHooked() {
        assertTrue(script.contains("\"WebChannelMessageToChrome\""))
        assertTrue(script.contains("\"WebChannelMessageToContent\""))
        assertTrue(script.contains(FoxWebChannel.CHANNEL_ID))
        assertTrue(script.contains("addEventListener"))
        assertTrue(script.contains("dispatchEvent"))
    }

    @Test
    fun theScriptInstallsItselfOnlyOnce() {
        assertTrue(script.contains("window.gozarChannelRelay"))
        assertEquals(2, Regex("gozarChannelRelay").findAll(script).count())
    }

    @Test
    fun interpolationLeftNoTemplateHolesBehind() {
        assertFalse(script.contains("\${"))
        // Both substituted values must have landed, not the Kotlin placeholder.
        assertFalse(script.contains("FoxWebChannel"))
        assertFalse(script.contains("BRIDGE"))
    }

    @Test
    fun theScriptNeverTouchesAnythingButTheChannel() {
        for (forbidden in listOf(
            // No document, storage or credential access of any kind.
            "document.cookie",
            "localStorage",
            "sessionStorage",
            "indexedDB",
            "querySelector",
            "getElementsByTagName",
            "innerHTML",
            "submit(",
            // No dialogs, no navigation, no fetches of our own.
            "alert(",
            "prompt(",
            "confirm(",
            "window.open",
            "location.href",
            "fetch(",
            "XMLHttpRequest",
            // No Java bridge: the app must never expose an object this way.
            "addJavascriptInterface",
            "_interface"
        )) {
            assertFalse("script must not contain $forbidden", script.contains(forbidden))
        }
    }

    @Test
    fun theScriptIsAStrictModeClosure() {
        assertTrue(script.startsWith("(function ()"))
        assertTrue(script.contains("\"use strict\""))
        assertTrue(script.trimEnd().endsWith("})();"))
    }

    @Test
    fun pollingForTheBridgeIsBounded() {
        assertTrue(script.contains("POLL_LIMIT"))
        assertTrue(script.contains("clearInterval"))
        // A queue that grows without a limit would be a way for a page to
        // spend our memory before the bridge ever appears.
        assertTrue(script.contains("QUEUE_LIMIT"))
        assertTrue(script.contains("MAX_LENGTH"))
    }

    @Test
    fun theScriptDoesNotValidateContentItOnlyRelays() {
        // Validation lives in FoxWebChannel, on the Kotlin side, where it can be
        // tested and where a compromised page cannot edit it.
        assertFalse(script.contains("sessionToken"))
    }
}
