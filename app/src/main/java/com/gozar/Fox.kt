package com.gozar

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

data class FoxAccess(val token: String, val id: String, val revision: Long)

object Fox {

    private const val STEP_NONE = 0
    private const val STEP_SIGNUP = 1
    private const val STEP_LOGIN = 2

    private val main = Handler(Looper.getMainLooper())
    private class ProbeEntry(val sample: FoxPathSample, val at: Long)

    private val probes = ConcurrentHashMap<String, ProbeEntry>()
    private val refreshLock = Any()
    private val operations = AtomicInteger()

    @Volatile internal var loginProvider: FoxLoginProvider = MozillaLoginProvider

    @Volatile var email: String = ""
    @Volatile var limit: Long = 0L
    @Volatile var remaining: Long = 0L
    @Volatile var reset: String = ""
    @Volatile var busy: Boolean = false
    @Volatile var message: String = ""
    @Volatile var stage: String = ""
    @Volatile var servers: List<FoxLocation> = emptyList()

    @Volatile private var byLink = false
    private var step = STEP_NONE
    private var pendingEmail = ""
    private var pendingPassword = ""
    private var pendingUid = ""
    private var pendingToken = ""

    var onChange: (() -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun watch(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unwatch(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun load(c: Context) {
        email = FoxStore.email(c)
        limit = FoxStore.limit(c)
        remaining = FoxStore.remaining(c)
        reset = FoxStore.reset(c)
    }

    fun signedIn(c: Context): Boolean = FoxStore.email(c).isNotEmpty() && FoxStore.refreshToken(c).isNotEmpty()

    fun needsCode(): Boolean = step != STEP_NONE && !byLink

    fun needsStep(): Boolean = step != STEP_NONE

    private fun notifyUi() {
        main.post {
            onChange?.invoke()
            for (listener in listeners) {
                try {
                    listener()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun say(c: Context, text: Int) {
        stage = c.getString(text)
        notifyUi()
    }

    private fun work(c: Context, first: Int = 0, body: () -> Unit) {
        operations.incrementAndGet()
        busy = true
        message = ""
        if (first != 0) stage = c.getString(first)
        notifyUi()
        val app = c.applicationContext
        thread {
            var failure: String? = null
            try {
                body()
            } catch (e: FoxError) {
                failure = e.message ?: "Sign-in failed"
            } catch (e: Exception) {
                failure = e.message ?: "Network error"
            }
            busy = operations.decrementAndGet() > 0
            if (!busy) stage = ""
            if (failure != null) message = failure
            load(app)
            notifyUi()
        }
    }

    fun signUp(c: Context, mail: String, password: String) {
        val clean = mail.trim()
        work(c, R.string.fox_stage_signup) {
            val session = try {
                FoxApi.register(clean, password)
            } catch (e: FoxError) {
                if (e.errno == 101 || e.errno == 144) {
                    signInNow(c.applicationContext, clean, password, true)
                    return@work
                }
                throw e
            }
            pendingEmail = clean
            pendingPassword = password
            pendingUid = session.uid
            pendingToken = session.token
            byLink = false
            if (session.needsCode) {
                step = STEP_SIGNUP
                message = "code"
            } else {
                finish(c.applicationContext, session.token, session.uid, clean, password)
            }
        }
    }

    private fun signInNow(c: Context, mail: String, password: String, created: Boolean) {
        if (created) message = ""
        val session = loginProvider.signIn(mail, password)
        pendingEmail = mail
        pendingPassword = password
        pendingUid = session.uid
        pendingToken = session.token
        byLink = session.byLink
        if (session.needsCode) {
            step = STEP_LOGIN
            message = if (byLink) "link" else "code"
        } else {
            finish(c, session.token, session.uid, mail, password)
        }
    }

    fun isLinkStep(): Boolean = step != STEP_NONE && byLink

    fun confirmLink(c: Context) {
        if (step == STEP_NONE) return
        work(c) {
            say(c, R.string.fox_stage_link)
            try {
                if (FoxApi.emailVerified(pendingToken)) {
                    val again = loginProvider.signIn(pendingEmail, pendingPassword)
                    pendingToken = again.token
                    pendingUid = again.uid
                    if (!again.needsCode) {
                        finish(c.applicationContext, again.token, again.uid, pendingEmail, pendingPassword)
                        return@work
                    }
                    byLink = again.byLink
                    message = if (byLink) "link" else "code"
                    throw FoxError(if (byLink) "The email link is not confirmed yet" else "Mozilla emailed a code to finish signing in")
                }
                finish(c.applicationContext, pendingToken, pendingUid, pendingEmail, pendingPassword)
            } catch (e: FoxError) {
                val again = loginProvider.signIn(pendingEmail, pendingPassword)
                pendingToken = again.token
                if (again.needsCode) {
                    byLink = again.byLink
                    message = if (byLink) "link" else "code"
                    throw FoxError(if (byLink) "The email link is not confirmed yet" else "Mozilla now wants the emailed code")
                }
                finish(c.applicationContext, again.token, again.uid, pendingEmail, pendingPassword)
            }
        }
    }

    fun signIn(c: Context, mail: String, password: String) {
        val clean = mail.trim()
        work(c, R.string.fox_stage_signin) {
            signInNow(c.applicationContext, clean, password, false)
        }
    }

    fun submitCode(c: Context, raw: String) {
        val clean = cleanCode(raw)
        if (clean.isEmpty() || step == STEP_NONE) return
        val mode = step
        work(c) {
            say(c, R.string.fox_stage_code)
            if (mode == STEP_SIGNUP) {
                FoxApi.verifyEmail(pendingToken, pendingUid, clean)
                try {
                    finish(c.applicationContext, pendingToken, pendingUid, pendingEmail, pendingPassword)
                } catch (e: FoxError) {
                    val again = loginProvider.signIn(pendingEmail, pendingPassword)
                    pendingToken = again.token
                    if (again.needsCode) {
                        step = STEP_LOGIN
                        message = "code"
                    } else {
                        finish(c.applicationContext, again.token, again.uid, pendingEmail, pendingPassword)
                    }
                }
            } else {
                loginProvider.verifySession(pendingToken, clean)
                finish(c.applicationContext, pendingToken, pendingUid, pendingEmail, pendingPassword)
            }
        }
    }

    private fun cleanCode(raw: String): String {
        val text = raw.trim()
        if (text.contains("code=")) {
            val code = text.substringAfter("code=").substringBefore("&").substringBefore(" ").trim()
            if (code.isNotEmpty()) return code
        }
        return text.filter { !it.isWhitespace() && it != '-' }
    }

    fun resend(c: Context) {
        if (step == STEP_NONE) return
        val mode = step
        val app = c.applicationContext
        work(c) {
            if (mode == STEP_SIGNUP) {
                FoxApi.resendSignupCode(pendingToken)
                message = "code"
            } else {
                signInNow(app, pendingEmail, pendingPassword, false)
            }
        }
    }

    private fun finish(c: Context, sessionToken: String, uid: String, mail: String, password: String) {
        say(c, R.string.fox_stage_pass)
        val tokens = loginProvider.tokens(sessionToken)
        val pass = loginProvider.pass(tokens.access)
        FoxStore.saveAccount(c, mail, uid, tokens, pass)
        step = STEP_NONE
        byLink = false
        pendingPassword = ""
        pendingToken = ""
        message = ""
    }

    fun refreshUsage(c: Context) {
        if (!signedIn(c)) return
        work(c, R.string.fox_stage_usage) {
            val access = validAccess(c)
            val pass = FoxApi.pass(access.token)
            if (FoxStore.saveUsage(c, pass, access.id, access.revision)) message = "quota"
        }
    }

    fun loadServers(c: Context) {
        if (servers.isNotEmpty()) return
        work(c, R.string.fox_stage_servers) {
            servers = FoxApi.servers()
            message = "servers"
        }
    }

    fun measure(c: Context) {
        if (servers.isEmpty()) return
        val app = c.applicationContext
        val session = FoxStore.credentials(app)
        operations.incrementAndGet()
        busy = true
        stage = c.getString(R.string.fox_stage_measure)
        notifyUi()
        thread {
            val stale = servers.filter { freshProbe(it.host + ":" + it.port) == null }
            for (sample in FoxPath.measure(stale)) {
                probes[sample.host + ":" + sample.port] = ProbeEntry(sample, System.currentTimeMillis())
            }
            val chosen = FoxStore.selected(app)
            if (chosen == "REC" || chosen == "AUTO" || chosen.isEmpty() || servers.none { it.code == chosen }) {
                val best = autoCandidates(app).firstOrNull()
                val current = FoxStore.credentials(app)
                if (best != null && current.id == session.id && current.revision == session.revision) {
                    FoxStore.setSelected(app, best.code)
                }
            }
            busy = operations.decrementAndGet() > 0
            if (!busy) stage = ""
            message = "ping"
            notifyUi()
        }
    }

    private fun freshProbe(key: String): FoxPathSample? {
        val entry = probes[key] ?: return null
        if (System.currentTimeMillis() - entry.at >= FoxPath.TTL_MS) {
            probes.remove(key, entry)
            return null
        }
        return entry.sample
    }

    fun delayOf(location: FoxLocation): Int {
        val sample = freshProbe(location.host + ":" + location.port) ?: return -1
        return if (sample.ok) sample.averageMs.toInt() else -1
    }

    fun hasDelays(): Boolean = servers.any { delayOf(it) >= 0 }

    fun validAccess(c: Context): FoxAccess = synchronized(refreshLock) {
        val current = FoxStore.credentials(c)
        if (current.id.isEmpty() || current.refresh.isEmpty()) throw FoxError("Sign in again")
        if (current.access.isNotEmpty() && current.expiresAt - 120_000L > System.currentTimeMillis()) {
            return@synchronized FoxAccess(current.access, current.id, current.revision)
        }
        val fresh = FoxApi.refresh(current.refresh)
        if (!FoxStore.saveAccess(c, fresh, current.id, current.revision)) throw FoxError("Account changed")
        FoxAccess(fresh.access, current.id, current.revision)
    }

    fun selectedLocation(c: Context): FoxLocation? {
        val code = FoxStore.selected(c)
        val list = servers
        if (list.isEmpty()) return null
        return list.firstOrNull { it.code == code } ?: list.firstOrNull { it.recommended } ?: list.first()
    }

    fun autoCandidates(c: Context): List<FoxLocation> {
        val list = servers
        if (list.isEmpty()) return emptyList()
        val code = FoxStore.selected(c)
        if (code != "REC" && code != "AUTO" && code.isNotEmpty() && list.any { it.code == code }) {
            return listOf(list.first { it.code == code })
        }
        val stale = list.filter { freshProbe(it.host + ":" + it.port) == null }
        for (sample in FoxPath.measure(stale)) {
            probes[sample.host + ":" + sample.port] = ProbeEntry(sample, System.currentTimeMillis())
        }
        val samples = list.mapNotNull { freshProbe(it.host + ":" + it.port) }
        val byKey = list.associateBy { it.host + ":" + it.port }
        val ordered = FoxPath.rank(samples).mapNotNull { byKey[it.host + ":" + it.port] }
        return if (ordered.isEmpty()) list else ordered
    }

    fun autoSelect(c: Context): FoxLocation? = autoCandidates(c).firstOrNull() ?: selectedLocation(c)

    fun select(c: Context, location: FoxLocation) {
        FoxStore.setSelected(c, location.code)
    }

    fun cancelLogin() {
        if (busy) return
        step = STEP_NONE
        byLink = false
        pendingEmail = ""
        pendingPassword = ""
        pendingUid = ""
        pendingToken = ""
        message = ""
        notifyUi()
    }

    fun switchAccount(c: Context, id: String): Boolean {
        if (busy) return false
        FoxVpnService.stop(c)
        if (!FoxStore.switchAccount(c, id)) return false
        cancelLogin()
        load(c)
        notifyUi()
        return true
    }

    fun signOut(c: Context) {
        FoxVpnService.stop(c)
        FoxStore.clear(c)
        cancelLogin()
        load(c)
        probes.clear()
        notifyUi()
    }
}
