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
    fun `every line is a section header or a two-space key, never a tab`() {
        val text = HevConfig.document(1280, 1, credential)
        val lines = text.split('\n').filter { it.isNotEmpty() }
        val headers = lines.filter { !it.first().isWhitespace() }
        val keys = lines.filter { it.first().isWhitespace() }
        assertEquals(listOf("tunnel:", "socks5:", "misc:"), headers)
        for (line in keys) {
            assertTrue("a key must be indented exactly two spaces: <$line>", line.startsWith("  ") && !line.startsWith("   "))
            assertFalse("YAML forbids tabs: <$line>", line.contains('\t'))
            assertTrue("a key line must end with a value: <$line>", line.contains(':'))
        }
        assertTrue("the engine needs at least one key per section", keys.size >= 6)
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

    /**
     * 1.18.0 shipped a fix that relied on `writeText` restoring the mode of a
     * file an older build had left owner-unreadable. It does not: `FileOutputStream`
     * truncates the existing inode and leaves its mode alone, so every device
     * that had ever run 1.17.x kept failing to start the engine and reported
     * "Android refused to open the tunnel device". This pins the repair.
     */
    @Test
    fun `an owner-unreadable config left by an older build is repaired`() {
        val directory = createTempDir("hev-stale")
        val file = File(directory, HevEngine.CONFIG_FILE)
        val document = HevConfig.document(1280, 10810, credential)

        file.writeText("tunnel:\n")
        assertTrue(file.setReadable(false, false))
        assertFalse(file.canRead())

        // What HevEngine.start does before handing the path to the engine.
        file.writeText(document)
        assertFalse("writeText alone does not repair the mode", file.canRead())
        assertTrue(file.setReadable(true, true))
        assertTrue("repair makes the engine's fopen succeed", file.canRead())
        assertEquals(document, file.readText())
    }

    @Test
    fun `a config that cannot be repaired is replaced rather than reused`() {
        val directory = createTempDir("hev-dead")
        val file = File(directory, HevEngine.CONFIG_FILE)
        file.writeText("stale")
        file.setReadable(false, false)
        // Deleting is always allowed for the owning app, and a fresh file is
        // created with the editor's owner-only 0600.
        file.delete()
        assertFalse(file.exists())
        file.writeText(HevConfig.document(1280, 10810, credential))
        assertTrue(file.canRead())
    }
}
