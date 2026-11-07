package com.gozar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runs the actions behind the update notifications: download, or roll back. */
class UpdateActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DOWNLOAD = "com.gozar.UPDATE_DOWNLOAD"
        const val ACTION_ROLLBACK = "com.gozar.UPDATE_ROLLBACK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DOWNLOAD -> UpdateInstaller.start(context)
            ACTION_ROLLBACK -> {
                val ok = UpdateInstaller.rollback(context)
                Notify.installResult(context.applicationContext, ok)
            }
        }
    }
}
