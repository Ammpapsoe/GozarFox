package com.gozar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Notify {

    private const val CHANNEL_UPDATE = "updates"
    private const val ID_UPDATE = 4097
    private const val ID_ROLLBACK = 4098
    private const val ID_RESULT = 4099

    private fun mgr(c: Context) = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun updateChannel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        mgr(c).createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, c.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun flags(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

    fun updateAvailable(c: Context, version: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            updateChannel(c)
            val download = PendingIntent.getBroadcast(
                c,
                5,
                Intent(c, UpdateActionReceiver::class.java).setAction(UpdateActionReceiver.ACTION_DOWNLOAD),
                flags()
            )
            val note = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL_UPDATE) else Notification.Builder(c)
            note.setSmallIcon(R.drawable.ic_refresh)
                .setContentTitle(c.getString(R.string.update_notification_title))
                .setContentText(c.getString(R.string.update_notification_text, version))
                .setContentIntent(download)
                .addAction(0, c.getString(R.string.update_action_download), download)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
            if (Build.VERSION.SDK_INT < 26) note.setPriority(Notification.PRIORITY_DEFAULT)
            mgr(c).notify(ID_UPDATE, note.build())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** The trial is unconfirmed, not necessarily broken. Offer a real check
     * for a newer signed recovery update rather than an unsupported downgrade. */
    fun recoveryRecommended(c: Context, version: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return try {
            updateChannel(c)
            val action = PendingIntent.getActivity(
                c,
                6,
                Intent(c, UpdateRecoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags()
            )
            val note = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL_UPDATE) else Notification.Builder(c)
            note.setSmallIcon(R.drawable.ic_refresh)
                .setContentTitle(c.getString(R.string.rollback_notification_title))
                .setContentText(c.getString(R.string.rollback_notification_text, version))
                .setContentIntent(action)
                .addAction(0, c.getString(R.string.rollback_action), action)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
            if (Build.VERSION.SDK_INT < 26) note.setPriority(Notification.PRIORITY_DEFAULT)
            mgr(c).notify(ID_ROLLBACK, note.build())
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Report download/handoff failure; a successful handoff is not installation. */
    fun installResult(c: Context, success: Boolean) {
        if (!success) {
            try {
                updateChannel(c)
                val note = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CHANNEL_UPDATE) else Notification.Builder(c)
                note.setSmallIcon(R.drawable.ic_refresh)
                    .setContentTitle(c.getString(R.string.update_download_failed))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                if (Build.VERSION.SDK_INT < 26) note.setPriority(Notification.PRIORITY_DEFAULT)
                mgr(c).notify(ID_RESULT, note.build())
            } catch (_: Exception) {
            }
        } else {
            try {
                mgr(c).cancel(ID_UPDATE)
            } catch (_: Exception) {
            }
        }
    }
}
