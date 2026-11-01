package com.gozar

internal object RelayCheck {
    private val targets = listOf("www.mozilla.org", "www.cloudflare.com")

    fun verify(open: (String) -> FoxPipe) {
        var failure: Exception? = null
        for (target in targets) {
            try {
                val pipe = open(target)
                try { pipe.close() } catch (_: Exception) { }
                return
            } catch (error: Exception) {
                if (FoxTunnel.isTlsFailure(error) || error is FoxError || passRejected(error) ||
                    error is FoxProxyRefusal && error.status in setOf(403, 429, 451)) throw error
                if (failure == null) failure = error
            }
        }
        throw failure ?: java.io.IOException("the relay did not answer")
    }

    fun passRejected(error: Exception): Boolean = error is FoxProxyRefusal &&
        (error.status == 400 || error.status == 401 || error.status == 407 ||
            error.message?.contains("pass was rejected", true) == true)
}
