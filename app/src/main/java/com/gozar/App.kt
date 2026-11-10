package com.gozar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

class App : Application() {

    companion object {
        private var journalInstance: FoxUpdateJournal? = null

        /** One journal per process; the file lives in private app storage. */
        @Synchronized
        fun journal(context: Context): FoxUpdateJournal =
            journalInstance ?: FoxUpdateJournal(
                File(context.applicationContext.filesDir, "journal/update.json")
            ).also { journalInstance = it }

        /** Called when the tunnel is proven up; ends the trial of this build. */
        fun markTunnelHealthy(context: Context) {
            journal(context).markHealthy()
        }
    }

    override fun onCreate() {
        super.onCreate()
        deleteFile("last-crash.txt")
        AppCompatDelegate.setDefaultNightMode(nightModeOf(Store.theme(this)))
        startUpdateTrial()
        watchPower()
        UpdateChecker.schedule(this)
        UpdateChecker.check(this)
        FoxRelayStore.initialize(this)
        FoxTorRuntime.clean(this)
    }

    /** Respect the system battery-saver setting without penalizing normal use. */
    private fun watchPower() {
        applyPowerProfile()
        try {
            registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) = applyPowerProfile()
                },
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            )
        } catch (_: Exception) {
        }
    }

    private fun applyPowerProfile() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            FoxTunnel.setPowerSaving(power.isPowerSaveMode)
        } catch (_: Exception) {
        }
    }

    /** Three unconfirmed launches prompt a check for a newer signed recovery
     * build. They do not prove that this build crashed or is broken. */
    private fun startUpdateTrial() {
        try {
            val installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            if (installed.isBlank()) return
            val journal = journal(this)
            journal.beginLaunch(installed)
            val target = journal.rollbackTarget(installed) ?: return
            Notify.recoveryRecommended(this, target)
        } catch (_: Exception) {
        }
    }
}
