package com.gozar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoxTorPlanTest {
    @Test
    fun escalationOrderIsDirectSnowflakeMeek() {
        assertEquals(listOf(FoxTorPlan.Mode.DIRECT, FoxTorPlan.Mode.SNOWFLAKE, FoxTorPlan.Mode.MEEK), FoxTorPlan.order)
    }

    @Test
    fun directConfigurationHasOnlyLoopbackSocks() {
        val config = FoxTorPlan.torrc(FoxTorPlan.Mode.DIRECT, 0)
        assertTrue(config.contains("SocksPort 127.0.0.1:auto IsolateDestAddr"))
        assertTrue(config.contains("UseBridges 0"))
        assertTrue(config.contains("HTTPTunnelPort 0"))
        assertTrue(config.contains("AvoidDiskWrites 1"))
        assertTrue(config.contains("SafeLogging 1"))
        assertFalse(config.contains("ClientTransportPlugin"))
        assertFalse(config.contains("Bridge "))
        assertFalse(config.contains("0.0.0.0"))
    }

    @Test
    fun bridgedConfigurationsUseInProcessSocksListeners() {
        for (mode in listOf(FoxTorPlan.Mode.SNOWFLAKE, FoxTorPlan.Mode.MEEK)) {
            val config = FoxTorPlan.torrc(mode, 23456)
            val transport = FoxTorPlan.transportName(mode)
            assertTrue(config.contains("UseBridges 1"))
            assertTrue(config.contains("ClientTransportPlugin $transport socks5 127.0.0.1:23456"))
            assertFalse(config.contains(" exec "))
            assertFalse(config.contains("9050"))
            assertFalse(config.contains("8118"))
        }
    }

    @Test
    fun officialSnowflakeAndMeekParametersAreComplete() {
        val snowflake = FoxTorPlan.torrc(FoxTorPlan.Mode.SNOWFLAKE, 1234)
        val meek = FoxTorPlan.torrc(FoxTorPlan.Mode.MEEK, 1234)
        assertTrue(snowflake.contains("url=https://1098762253.rsc.cdn77.org/"))
        assertTrue(snowflake.contains("fronts=app.datapacket.com,www.datapacket.com"))
        assertTrue(snowflake.contains("fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72"))
        assertTrue(snowflake.contains("ice=stun:"))
        assertTrue(meek.contains("Bridge meek_lite 192.0.2.20:80"))
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
    }

    @Test(expected = IllegalArgumentException::class)
    fun bridgedConfigurationRejectsInvalidPort() {
        FoxTorPlan.torrc(FoxTorPlan.Mode.SNOWFLAKE, 0)
    }
}
