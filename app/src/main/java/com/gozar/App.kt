package com.gozar

import android.app.Application
import android.content.Context
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
        UpdateChecker.schedule(this)
        UpdateChecker.check(this)
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
