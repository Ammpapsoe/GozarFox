package com.gozar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxTorLogTest {
    @Test
    fun logViewerKeepsStateAndRedactsSensitiveNetworkValues() {
        val bridge = "obfs4 217.182.93.225:62782 D4FC51091A3D23F098354F660F2D5B17B2F3432C cert=UDjYbRlpv/GEc19L1dPssudaWQE83uxgUKxtUDQ7COumf1ERpup7+mcOvshJdO4Luh/Udw iat-mode=0"
        FoxTorLog.clear()
        FoxTorLog.record("orbot_state", "STARTING")
        FoxTorLog.record("attempt_failed", bridge + " token=secret https://example.invalid/private")
        val rendered = FoxTorLog.render()
        assertTrue(rendered.contains("ORBOT_STATE"))
        assertTrue(rendered.contains("[bridge redacted]"))
        assertFalse(rendered.contains("217.182.93.225"))
        assertFalse(rendered.contains("D4FC51091A3D23F098354F660F2D5B17B2F3432C"))
        assertFalse(rendered.contains("UDjYbRlpv"))
        assertFalse(rendered.contains("secret"))
        assertFalse(rendered.contains("example.invalid"))
        FoxTorLog.clear()
        assertTrue(FoxTorLog.render().isEmpty())
    }
}
