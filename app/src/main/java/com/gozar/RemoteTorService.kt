package com.gozar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import org.torproject.jni.TorService

internal class RemoteTorService : TorService() {
    companion object {
        const val PID_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION
        private const val NOTIFICATION_ID = 4100
        private const val CHANNEL_ID = "login_tor"
    }

    private val pidBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != PID_TRANSACTION) return super.onTransact(code, data, reply, flags)
            if (getCallingUid() != Process.myUid()) return false
            reply?.writeInt(Process.myPid())
            return true
        }
    }

    override fun onCreate() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.login_secure_connecting), NotificationManager.IMPORTANCE_MIN))
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        notification.setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.login_secure_connecting))
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT < 26) notification.setPriority(Notification.PRIORITY_MIN)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification.build())
        }
        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder = pidBinder

    override fun onDestroy() {
        stopForeground(true)
        super.onDestroy()
    }
}
