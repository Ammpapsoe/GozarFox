package com.gozar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.appcompat.app.AppCompatDelegate
import java.io.File

class App : Application() {

    companion object {
        private var journalInstance: FoxUpdateJournal? = null

        /** One journal per process; the file lives in private app storage. */
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
    }

    /**
     * Battery-aware transport: on battery or in system power-save, uploads
     * coalesce over a longer window so the radio wakes less often.
     */
    private fun watchPower() {
        applyPowerProfile()
        try {
            registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) = applyPowerProfile()
                },
                IntentFilter().apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                    addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun applyPowerProfile() {
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            FoxTunnel.setPowerSaving(power.isPowerSaveMode || plugged == 0)
        } catch (_: Exception) {
        }
    }

    /**
     * Every launch is a vote on the running build. When three launches in a
     * row end without the tunnel ever proving health, offer the rollback.
     */
    private fun startUpdateTrial() {
        try {
            val installed = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            if (installed.isBlank()) return
            val journal = journal(this)
            journal.beginLaunch(installed)
            val target = journal.rollbackTarget(installed) ?: return
            Notify.rollbackAvailable(this, target, journal.rollbackFile() != null)
        } catch (_: Exception) {
        }
    }
}
