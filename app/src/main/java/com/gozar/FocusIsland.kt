package com.gozar

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import org.json.JSONObject

object FocusIsland {
    private const val PIC = "miui.focus.pic_gozar"

    fun permittedProtocol(context: Context): Int {
        return try {
            val version = Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
            if (version < 2) return 0
            val request = Bundle().apply { putString("package", context.packageName) }
            val result = context.contentResolver.call(
                Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, request
            )
            if (result?.getBoolean("canShowFocus", false) == true) version else 0
        } catch (_: Exception) {
            0
        }
    }

    fun decorate(context: Context, notification: Notification, protocol: Int, title: String, speed: String) {
        if (protocol < 2) return
        try {
            val image = JSONObject().put("type", 1).put("pic", PIC)
            val params = JSONObject()
                .put("protocol", 1)
                .put("business", "vpn")
                .put("updatable", true)
                .put("enableFloat", false)
                .put("islandFirstFloat", false)
                .put("filterWhenNoPermission", false)
                .put("ticker", speed)
                .put("tickerPic", PIC)
                .put("aodTitle", title)
                .put("baseInfo", JSONObject().put("type", 1).put("title", title).put("content", speed))
            if (protocol >= 3) {
                val left = JSONObject().put("type", 1).put("picInfo", image)
                    .put("textInfo", JSONObject().put("title", title))
                val big = JSONObject().put("imageTextInfoLeft", left)
                    .put("textInfo", JSONObject().put("title", speed))
                val small = JSONObject().put("picInfo", image)
                params.put("param_island", JSONObject()
                    .put("islandProperty", 1)
                    .put("bigIslandArea", big)
                    .put("smallIslandArea", small))
            }
            val pics = Bundle().apply { putParcelable(PIC, Icon.createWithResource(context, R.drawable.ic_fox_note)) }
            notification.extras.putBundle("miui.focus.pics", pics)
            notification.extras.putString("miui.focus.param", JSONObject().put("param_v2", params).toString())
        } catch (_: Exception) {
        }
    }
}
