package com.gozar

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A foreground, user-initiated check for a newer signed recovery update.
 * Android normally does not permit an ordinary app to install an older APK.
 */
class UpdateRecoveryActivity : BaseActivity() {
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkForRecovery()
    }

    private fun checkForRecovery() {
        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rollback_notification_title)
            .setMessage(R.string.update_checking)
            .setNegativeButton(R.string.close) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
        UpdateChecker.check(this, notify = false) { result ->
            if (isFinishing || isDestroyed) return@check
            dialog?.dismiss()
            dialog = when (result) {
                is UpdateChecker.Result.Available -> MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.update_title)
                    .setMessage(
                        result.recovery?.let { getString(R.string.recovery_identified, result.version, it.restoresVersion) }
                            ?: getString(R.string.recovery_available, result.version)
                    )
                    .setNegativeButton(R.string.close) { _, _ -> finish() }
                    .setPositiveButton(R.string.update_action_download) { _, _ ->
                        UpdateInstaller.start(this)
                        toast(R.string.update_downloading)
                        finish()
                    }
                    .setOnCancelListener { finish() }
                    .show()
                UpdateChecker.Result.Current -> retryDialog(R.string.recovery_no_update)
                UpdateChecker.Result.Failed -> retryDialog(R.string.recovery_check_failed)
            }
        }
    }

    private fun retryDialog(message: Int): AlertDialog = MaterialAlertDialogBuilder(this)
        .setTitle(R.string.rollback_notification_title)
        .setMessage(message)
        .setNegativeButton(R.string.close) { _, _ -> finish() }
        .setPositiveButton(R.string.recovery_retry) { _, _ -> checkForRecovery() }
        .setOnCancelListener { finish() }
        .show()

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }
}
