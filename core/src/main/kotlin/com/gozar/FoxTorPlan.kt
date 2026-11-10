package com.gozar

object FoxTorPlan {
    enum class Mode {
        DIRECT,
        SNOWFLAKE,
        MEEK
    }

    const val SNOWFLAKE_BROKER = "https://1098762253.rsc.cdn77.org/"
    const val SNOWFLAKE_FRONTS = "app.datapacket.com,www.datapacket.com"
    const val SNOWFLAKE_ICE = "stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478,stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478"

    private const val SNOWFLAKE_BRIDGE = "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=$SNOWFLAKE_BROKER fronts=$SNOWFLAKE_FRONTS ice=$SNOWFLAKE_ICE utls-imitate=hellorandomizedalpn"
    private const val MEEK_BRIDGE = "meek_lite 192.0.2.20:80 url=https://1603026938.rsc.cdn77.org front=www.phpmyadmin.net utls=HelloRandomizedALPN"

    val order = listOf(Mode.DIRECT, Mode.SNOWFLAKE, Mode.MEEK)

    fun transportName(mode: Mode): String? = when (mode) {
        Mode.DIRECT -> null
        Mode.SNOWFLAKE -> "snowflake"
        Mode.MEEK -> "meek_lite"
    }

    fun timeoutMs(mode: Mode): Long = when (mode) {
        Mode.DIRECT -> 45_000L
        Mode.SNOWFLAKE -> 100_000L
        Mode.MEEK -> 120_000L
    }

    fun torrc(mode: Mode, transportPort: Int): String {
        require(mode == Mode.DIRECT || transportPort in 1..65535) { "invalid transport port" }
        val lines = mutableListOf(
            "ClientOnly 1",
            "SocksPort 127.0.0.1:auto IsolateDestAddr",
            "SocksPolicy accept 127.0.0.1",
            "SocksPolicy reject *",
            "HTTPTunnelPort 0",
            "DNSPort 0",
            "TransPort 0",
            "NATDPort 0",
            "ControlPort 0",
            "AvoidDiskWrites 1",
            "SafeLogging 1",
            "Log notice file /dev/null"
        )
        val transport = transportName(mode)
        if (transport != null) {
            lines += "UseBridges 1"
            lines += "ClientTransportPlugin $transport socks5 127.0.0.1:$transportPort"
            lines += "Bridge " + when (mode) {
                Mode.SNOWFLAKE -> SNOWFLAKE_BRIDGE
                Mode.MEEK -> MEEK_BRIDGE
                Mode.DIRECT -> ""
            }
        } else {
            lines += "UseBridges 0"
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    fun bootstrapProgress(reply: String?): Int {
        if (reply == null) return -1
        val marker = "PROGRESS="
        val start = reply.indexOf(marker)
        if (start < 0) return -1
        val number = reply.substring(start + marker.length).takeWhile { it.isDigit() }
        return number.toIntOrNull()?.coerceIn(0, 100) ?: -1
    }
}
