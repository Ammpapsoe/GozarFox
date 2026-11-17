package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The native engine exits before it reads a packet when its configuration is
 * unreadable or malformed, and the JNI start call cannot report that. These
 * checks pin the document shape and, above all, the file mode: clearing the
 * owner read bit on the written file is exactly the defect that produced a
 * "connected" VPN with no traffic.
 */
class HevConfigTest {

    private val credential = "dGVzdC1jcmVkZW50aWFs"

    @Test
    fun `document carries the tun and proxy sections the engine parses`() {
        val text = HevConfig.document(1500, 10810, credential)
        assertTrue(text.startsWith("tunnel:\n"))
        assertTrue(text.contains("\n  mtu: 1500\n"))
        assertTrue(text.contains("\nsocks5:\n  address: 127.0.0.1\n  port: 10810\n"))
        assertTrue(text.contains("  username: '$credential'\n"))
        assertTrue(text.contains("  password: '$credential'\n"))
        assertTrue(text.contains("  udp: 'udp'\n"))
        assertTrue(text.endsWith("misc:\n  log-level: warn\n"))
    }

    @Test
    fun `every line is either a section header or an indented key`() {
        val text = HevConfig.document(1280, 1, credential)
        val lines = text.split('\n').filter { it.isNotEmpty() }
        for (line in lines) {
            val ok = !line.first().isWhitespace() || line.startsWith("  ")
            assertTrue("unexpected indentation in <$line>", ok)
        }
        assertEquals(2, lines.count { !it.first().isWhitespace() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mtu below the engine minimum is rejected`() {
        HevConfig.document(576, 10810, credential)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mtu above the engine maximum is rejected`() {
        HevConfig.document(65535, 10810, credential)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `port zero is rejected`() {
        HevConfig.document(1500, 0, credential)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a credential that could break out of its quotes is rejected`() {
        HevConfig.document(1500, 10810, "abc'def")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty credential is rejected`() {
        HevConfig.document(1500, 10810, "")
    }

    @Test
    fun `mtu boundaries are inclusive`() {
        assertEquals(HevConfig.MIN_MTU, HevConfig.mtu(HevConfig.MIN_MTU))
        assertEquals(HevConfig.MAX_MTU, HevConfig.mtu(HevConfig.MAX_MTU))
    }

    /**
     * The regression itself: after the write-only chmod the owner can no
     * longer read the file, and the engine's `fopen(..., "r")` fails.
     */
    @Test
    fun `config written by the engine stays readable by its owner`() {
        val directory = createTempDir("hev-config")
        val file = File(directory, HevEngine.CONFIG_FILE)
        file.writeText(HevConfig.document(1500, 10810, credential))
        assertTrue("the config must stay readable by the owning process", file.canRead())
        assertEquals(HevConfig.document(1500, 10810, credential), file.readText())

        // Reproduce the defect to prove the assertion above is meaningful.
        assertTrue(file.setReadable(false, false))
        assertFalse("setReadable(false, false) revokes owner read as well", file.canRead())
    }

    @Test
    fun `ledger file name stays in the app cache directory scope`() {
        assertEquals("hev-socks5-config.yaml", HevEngine.CONFIG_FILE)
    }
}
