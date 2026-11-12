package com.gozar

object FoxDiagnostics {
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun event(message: String) {
        sink?.invoke(message)
    }
}
