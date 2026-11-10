package com.gozar

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object FoxRelayStore {
    private val started = AtomicBoolean(false)

    fun initialize(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val stored = app.getSharedPreferences("fox", Context.MODE_PRIVATE).getString("relay_endpoints", "").orEmpty()
        if (SecretBox.isSealed(stored)) FoxRelayDiscovery.restoreCache(SecretBox.open(app, stored))
        thread(isDaemon = true, name = "fox-relay-discovery") {
            if (!FoxRelayDiscovery.refresh(force = true)) return@thread
            val encoded = FoxRelayDiscovery.encodeCache(FoxRelayDiscovery.relays(), System.currentTimeMillis()) ?: return@thread
            val sealed = SecretBox.seal(app, encoded)
            if (!SecretBox.isSealed(sealed)) return@thread
            app.getSharedPreferences("fox", Context.MODE_PRIVATE).edit().putString("relay_endpoints", sealed).apply()
        }
    }
}
