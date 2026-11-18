package com.gozar

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * The screen the launcher opens: a held memorial poster, then the app.
 *
 * The poster is shown for a fixed, short time and then the launcher activity
 * is started in its place, so `back` from the main screen still leaves the
 * app. It is a poster, not an animated logo, so it simply fades in rather than
 * trying to animate art that is already complete. If the device is set to
 * remove animations the fade is skipped and the hold is the only cost.
 *
 * The first `Intent` is forwarded untouched: `MainActivity` accepts a
 * `gozar://` view intent and an auto-connect extra, and dropping either here
 * would break links and shortcut starts.
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val leave = Runnable { go() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tapping the launcher while the app is already alive pushes another
        // splash onto the existing task, and its hand-over would then stack a
        // second MainActivity on top of the first: two fragments, two links,
        // two of everything. Only the copy that roots the task plays.
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            startActivity(Intent(this, MainActivity::class.java).also {
                it.putExtras(intent)
                it.action = intent.action
                it.data = intent.data
            })
            finish()
            return
        }
        setContentView(R.layout.activity_splash)
        val poster = findViewById<android.view.View>(R.id.splashPoster)
        val animate = android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) > 0f
        if (animate) {
            poster.alpha = 0f
            poster.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        handler.postDelayed(leave, HOLD_MS)
    }

    private fun go() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java).also {
            it.putExtras(intent)
            it.action = intent.action
            it.data = intent.data
        })
        // No transition: the poster is the app's own artwork and a system
        // cross-fade over it looks like a second launch.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(leave)
        super.onDestroy()
    }

    private companion object {
        /** Long enough to read the dates, short enough not to feel like a gate. */
        const val HOLD_MS = 2_600L
        const val FADE_MS = 450L
    }
}
