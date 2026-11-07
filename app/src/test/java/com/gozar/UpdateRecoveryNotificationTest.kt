package com.gozar

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UpdateRecoveryNotificationTest {
    @Test
    fun tappingTheUpdateNoticeUsesTheVerifiedDownloader() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(Notify.updateAvailable(context, "1.12.0"))
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val note = shadowOf(manager).allNotifications.single()
        val intent = shadowOf(note.contentIntent).savedIntent
        assertEquals(UpdateActionReceiver::class.java.name, intent.component?.className)
        assertEquals(UpdateActionReceiver.ACTION_DOWNLOAD, intent.action)
    }

    @Test
    fun recoveryNoticeOpensARealNonExportedUpdateCheck() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(Notify.recoveryRecommended(context, "1.11.0"))
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val note = shadowOf(manager).allNotifications.single()
        assertNotNull(note.contentIntent)
        assertTrue(note.actions.isNotEmpty())
        val intent = shadowOf(note.contentIntent).savedIntent
        assertEquals(UpdateRecoveryActivity::class.java.name, intent.component?.className)
        val info = context.packageManager.getActivityInfo(ComponentName(context, UpdateRecoveryActivity::class.java), 0)
        assertFalse(info.exported)
    }
}
