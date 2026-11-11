package com.gozar

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : BaseActivity() {
    companion object {
        const val AUTO_CONNECT = "com.gozar.AUTO_CONNECT"
    }

    private lateinit var fox: FoxFragment
    private lateinit var settings: SettingsFragment
    private var onSettings = false
    private var nav: BottomNavigationView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Fox.signedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        Fox.load(this)
        setContentView(R.layout.activity_shell)
        if (savedInstanceState != null) onSettings = savedInstanceState.getBoolean("onSettings", false)

        val manager = supportFragmentManager
        val existingFox = manager.findFragmentByTag("fox") as? FoxFragment
        val existingSettings = manager.findFragmentByTag("settings") as? SettingsFragment
        fox = existingFox ?: FoxFragment()
        settings = existingSettings ?: SettingsFragment()
        if (existingFox == null || existingSettings == null) {
            val transaction = manager.beginTransaction().setReorderingAllowed(true)
            if (existingFox == null) transaction.add(R.id.container, fox, "fox")
            if (existingSettings == null) transaction.add(R.id.container, settings, "settings")
            transaction.commitNow()
        }
        settings.restore(intent)

        back()
        applyState()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.shell)) { target, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val bar = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav = bar
        bar.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    onSettings = false
                    applyState()
                    true
                }
                R.id.nav_settings -> {
                    onSettings = true
                    applyState()
                    true
                }
                else -> false
            }
        }
        bar.selectedItemId = if (onSettings) R.id.nav_settings else R.id.nav_home
    }

    override fun onResume() {
        super.onResume()
        if (!Fox.signedIn(this) && fox_ready()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun fox_ready(): Boolean = this::fox.isInitialized

    private fun back() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (onSettings) {
                    if (!settings.goBack()) showHome()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("onSettings", onSettings)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        onSettings = false
        applyState()
        nav?.selectedItemId = R.id.nav_home
        if (this::settings.isInitialized) settings.restore(intent)
    }

    fun showHome() {
        if (!onSettings) return
        onSettings = false
        applyState()
        nav?.selectedItemId = R.id.nav_home
    }

    fun showSettings() {
        if (onSettings) return
        onSettings = true
        applyState()
        nav?.selectedItemId = R.id.nav_settings
    }

    private fun applyState() {
        if (!this::fox.isInitialized || !this::settings.isInitialized) return
        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        if (onSettings) transaction.hide(fox).show(settings) else transaction.hide(settings).show(fox)
        transaction.commitAllowingStateLoss()
    }
}
