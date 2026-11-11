package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxTorPlanTest {
    private val bridge = "obfs4 8.8.8.8:443 ${"A".repeat(40)} cert=${"A".repeat(70)} iat-mode=0"

    @Test
    fun escalationOrderIsObfs4WebTunnelSnowflakeMeek() {
        assertEquals(listOf(FoxTorPlan.Mode.OBFS4, FoxTorPlan.Mode.WEBTUNNEL, FoxTorPlan.Mode.SNOWFLAKE, FoxTorPlan.Mode.MEEK), FoxTorPlan.order)
    }

    @Test
    fun directConfigurationHasOnlyLoopbackSocksAndStartsOffline() {
        val config = FoxTorPlan.torrc(FoxTorPlan.Mode.DIRECT, 0)
        assertTrue(config.contains("SocksPort 127.0.0.1:auto IsolateDestAddr"))
        assertTrue(config.contains("UseBridges 0"))
        assertTrue(config.contains("DisableNetwork 1"))
        assertTrue(config.contains("AvoidDiskWrites 1"))
        assertTrue(config.contains("SafeLogging 1"))
        assertFalse(config.contains("ClientTransportPlugin"))
        assertFalse(config.contains("Bridge "))
        assertFalse(config.contains("Log "))
        assertFalse(config.contains("0.0.0.0"))
    }

    @Test
    fun bridgedConfigurationsContainNoBridgeOrLogOnDisk() {
        for (mode in FoxTorPlan.order) {
            val config = FoxTorPlan.torrc(mode, 23456)
            val transport = FoxTorPlan.transportName(mode)
            assertTrue(config.contains("UseBridges 1"))
            assertTrue(config.contains("DisableNetwork 1"))
            assertTrue(config.contains("ClientTransportPlugin $transport socks5 127.0.0.1:23456"))
            assertFalse(config.contains("Bridge "))
            assertFalse(config.contains("Log "))
            assertFalse(config.contains(" exec "))
        }
    }

    @Test
    fun bridgeIsAppliedOnlyThroughControlAfterOfflineStartup() {
        val activation = FoxTorPlan.activate(FoxTorPlan.Mode.OBFS4, bridge)
        assertTrue(activation.startsWith("SETCONF UseBridges=1 Bridge=\""))
        assertTrue(activation.endsWith(" DisableNetwork=0"))
        assertTrue(activation.contains(bridge))
        assertEquals("SETCONF DisableNetwork=0", FoxTorPlan.activate(FoxTorPlan.Mode.DIRECT))
    }

    @Test
    fun officialSnowflakeAndMeekParametersAreComplete() {
        val snowflake = FoxTorPlan.activate(FoxTorPlan.Mode.SNOWFLAKE)
        val meek = FoxTorPlan.activate(FoxTorPlan.Mode.MEEK)
        assertTrue(snowflake.contains("url=https://1098762253.rsc.cdn77.org/"))
        assertTrue(snowflake.contains("fronts=app.datapacket.com,www.datapacket.com"))
        assertTrue(snowflake.contains("fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72"))
        assertTrue(snowflake.contains("ice=stun:"))
        assertTrue(meek.contains("Bridge=\"meek_lite 192.0.2.20:80"))
        assertTrue(meek.contains("url=https://1603026938.rsc.cdn77.org"))
        assertTrue(meek.contains("front=www.phpmyadmin.net"))
        assertTrue(meek.contains("utls=HelloRandomizedALPN"))
    }

    @Test
    fun bootstrapParserAcceptsOnlyNumericProgress() {
        assertEquals(100, FoxTorPlan.bootstrapProgress("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
        assertEquals(42, FoxTorPlan.bootstrapProgress("PROGRESS=42 TAG=x"))
        assertEquals(-1, FoxTorPlan.bootstrapProgress("PROGRESS=abc"))
        assertEquals(-1, FoxTorPlan.bootstrapProgress(null))
        assertEquals("Done", FoxTorPlan.bootstrapSummary("PROGRESS=100 SUMMARY=\"Done\""))
    }

    @Test
    fun explicitRefusalAndResetFailFastWithoutCuttingOffProgress() {
        assertTrue(FoxTorPlan.fastFailure("PROGRESS=0 REASON=CONNREFUSED SUMMARY=\"Failed\""))
        assertTrue(FoxTorPlan.fastFailure("PROGRESS=0 SUMMARY=\"Connection reset by peer\""))
        assertFalse(FoxTorPlan.fastFailure("PROGRESS=42 TAG=handshake SUMMARY=\"TLS handshake\""))
        assertFalse(FoxTorPlan.fastFailure("PROGRESS=0 REASON=TIMEOUT"))
        assertFalse(FoxTorPlan.fastFailure(null))
    }

    @Test
    fun bridgeTimeoutsAreFifteenSecondsAndFallbacksRemainNinetySeconds() {
        assertEquals(15_000L, FoxTorPlan.timeoutMs(FoxTorPlan.Mode.OBFS4))
        assertEquals(15_000L, FoxTorPlan.timeoutMs(FoxTorPlan.Mode.WEBTUNNEL))
        assertEquals(90_000L, FoxTorPlan.timeoutMs(FoxTorPlan.Mode.SNOWFLAKE))
        assertEquals(90_000L, FoxTorPlan.timeoutMs(FoxTorPlan.Mode.MEEK))
    }

    @Test(expected = IllegalArgumentException::class)
    fun bridgedConfigurationRejectsInvalidPort() {
        FoxTorPlan.torrc(FoxTorPlan.Mode.SNOWFLAKE, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun dynamicBridgeRejectsConfigurationInjection() {
        FoxTorPlan.activate(FoxTorPlan.Mode.OBFS4, bridge + "\nSocksPort 0.0.0.0:9050")
    }
}
