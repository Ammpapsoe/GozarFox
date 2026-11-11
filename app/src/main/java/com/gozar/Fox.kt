package com.gozar

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
    private val operations = FoxBusyCounter()
    private val authLock = Any()
    private var authInFlight = false
    private var authEpoch = 0L

    private class LoginCancelled : Exception()

    private inline fun <T> currentAuth(epoch: Long, block: () -> T): T = synchronized(authLock) {
        if (epoch != authEpoch) throw LoginCancelled()
        block()
    }

    @Volatile internal var loginProvider: FoxLoginProvider = MozillaLoginProvider
    @Volatile internal var loginTransport: FoxLoginTransport = FoxTorRuntime

    @Volatile var email: String = ""
    @Volatile var limit: Long = 0L
    @Volatile var remaining: Long = 0L
    @Volatile var reset: String = ""
    var busy: Boolean
        get() = operations.busy
        // UI tests can force the indicator, but cannot hide actual active work.
        internal set(value) { operations.busy = value }
    @Volatile var message: String = ""
    @Volatile var stage: String = ""
    @Volatile internal var lastFailureDiagnostic: String = ""
    @Volatile var servers: List<FoxLocation> = emptyList()
    // Serializes list loading: view creation and connect share one fetch.
    private val serversLock = Any()
    @Volatile private var serversUntil = 0L
    @Volatile private var serversFromCache = false

    @Volatile private var byLink = false
    @Volatile private var step = STEP_NONE
    // Once verified, the one-time code must never be submitted again for this session.
    @Volatile private var loginSessionVerified = false
    private var pendingEmail = ""
    private var pendingPassword = ""
    private var pendingUid = ""
    @Volatile private var pendingToken = ""

    var onChange: (() -> Unit)? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun watch(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unwatch(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun load(c: Context) = synchronized(authLock) {
        email = FoxStore.email(c)
        limit = FoxStore.limit(c)
        remaining = FoxStore.remaining(c)
        reset = FoxStore.reset(c)
    }

    fun signedIn(c: Context): Boolean = FoxStore.email(c).isNotEmpty() && FoxStore.refreshToken(c).isNotEmpty()

    fun needsCode(): Boolean = step != STEP_NONE && !byLink && !canRetryLogin()

    fun needsStep(): Boolean = step != STEP_NONE

    fun canRetryLogin(): Boolean = step == STEP_LOGIN && loginSessionVerified

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

    internal fun updateLoginStage(text: String) {
        stage = text
        notifyUi()
    }

    private fun work(
        c: Context,
        first: Int = 0,
        failureEpoch: Long? = null,
        onComplete: () -> Unit = {},
        body: () -> Unit
    ) {
        operations.begin()
        try {
            message = ""
            if (first != 0) stage = c.getString(first)
            notifyUi()
            val app = c.applicationContext
            thread {
                var failure: String? = null
                var diagnostic = ""
                try {
                    body()
                } catch (e: FoxError) {
                    failure = e.message ?: "Sign-in failed"
                    diagnostic = e.javaClass.simpleName + "; http=" + e.status + "; errno=" + e.errno
                } catch (e: Exception) {
                    failure = e.message ?: "Network error"
                    diagnostic = e.javaClass.simpleName
                } finally {
                    try {
                        failure?.let { text ->
                            lastFailureDiagnostic = diagnostic + "; stage=" + stage.take(160)
                            synchronized(authLock) {
                                if (failureEpoch == null || failureEpoch == authEpoch) message = text
                            }
                        }
                        load(app)
                    } finally {
                        try {
                            onComplete()
                        } finally {
                            operations.end()
                            if (!busy) stage = ""
                            notifyUi()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // If scheduling failed synchronously, no worker owns this count.
            operations.end()
            if (!busy) stage = ""
            notifyUi()
            throw e
        }
    }

    // UI clicks normally honor busy, but serialize auth bodies as well so two
    // callers cannot verify a single one-time code concurrently.
    private fun authWork(c: Context, first: Int = 0, body: (Long) -> Unit) {
        val epoch = synchronized(authLock) {
            if (authInFlight) return
            authInFlight = true
            authEpoch
        }
        try {
            work(c, first, failureEpoch = epoch, onComplete = {
                synchronized(authLock) { authInFlight = false }
            }) {
                try {
                    currentAuth(epoch) { }
                    loginTransport.run(c.applicationContext) { body(epoch) }
                } catch (_: LoginCancelled) {
                    // Logout or cancellation revoked this job's authority to
                    // publish state. Already-started network requests may finish.
                }
            }
        } catch (e: Throwable) {
            synchronized(authLock) { authInFlight = false }
            throw e
        }
    }

    // Avoid data-class toString(): this snapshot contains a password and token.
    private class PendingLogin(val token: String, val uid: String, val mail: String, val password: String)

    fun signUp(c: Context, mail: String, password: String) {
        val clean = mail.trim()
        prepareFreshLogin(c)
        authWork(c, R.string.fox_stage_signup) { epoch ->
            currentAuth(epoch) { loginSessionVerified = false }
            val session = try {
                loginProvider.register(clean, password)
            } catch (e: FoxError) {
                currentAuth(epoch) { }
                if (e.errno == 101 || e.errno == 144) {
                    signInNow(c.applicationContext, clean, password, true, epoch)
                    return@authWork
                }
                throw e
            }
            currentAuth(epoch) {
                pendingEmail = clean
                pendingPassword = password
                pendingUid = session.uid
                pendingToken = session.token
                byLink = false
                if (session.needsCode) {
                    step = STEP_SIGNUP
                    message = "code"
                }
            }
            if (!session.needsCode) finish(c.applicationContext, session.token, session.uid, clean, epoch)
        }
    }

    private fun signInNow(c: Context, mail: String, password: String, created: Boolean, epoch: Long) {
        if (created) currentAuth(epoch) { message = "" }
        val session = loginProvider.signIn(mail, password)
        currentAuth(epoch) {
            loginSessionVerified = false
            pendingEmail = mail
            pendingPassword = password
            pendingUid = session.uid
            pendingToken = session.token
            byLink = session.byLink
            if (session.needsCode) {
                step = STEP_LOGIN
                message = if (byLink) "link" else "code"
            } else {
                step = STEP_LOGIN
                byLink = false
                loginSessionVerified = true
            }
        }
        if (!session.needsCode) {
            try {
                finish(c, session.token, session.uid, mail, epoch)
            } catch (e: FoxError) {
                if (e.errno != 104) throw e
                currentAuth(epoch) {
                    step = if (created) STEP_SIGNUP else STEP_LOGIN
                    byLink = false
                    loginSessionVerified = false
                    message = "code"
                }
            }
        }
    }

    fun isLinkStep(): Boolean = step != STEP_NONE && byLink

    fun confirmLink(c: Context) {
        if (step == STEP_NONE || canRetryLogin()) return
        val token = pendingToken
        authWork(c) { epoch ->
            val pending = currentAuth(epoch) {
                if (step == STEP_NONE || pendingToken != token || canRetryLogin()) return@authWork
                PendingLogin(pendingToken, pendingUid, pendingEmail, pendingPassword)
            }
            say(c, R.string.fox_stage_link)
            try {
                if (loginProvider.emailVerified(pending.token)) {
                    currentAuth(epoch) { }
                    val again = loginProvider.signIn(pending.mail, pending.password)
                    currentAuth(epoch) {
                        pendingToken = again.token
                        pendingUid = again.uid
                    }
                    if (!again.needsCode) {
                        finish(c.applicationContext, again.token, again.uid, pending.mail, epoch)
                        return@authWork
                    }
                    currentAuth(epoch) {
                        byLink = again.byLink
                        message = if (byLink) "link" else "code"
                    }
                    throw FoxError(if (again.byLink) "The email link is not confirmed yet" else "Mozilla emailed a code to finish signing in")
                }
                finish(c.applicationContext, pending.token, pending.uid, pending.mail, epoch)
            } catch (e: FoxError) {
                currentAuth(epoch) { }
                val again = loginProvider.signIn(pending.mail, pending.password)
                currentAuth(epoch) {
                    pendingToken = again.token
                    pendingUid = again.uid
                }
                if (again.needsCode) {
                    currentAuth(epoch) {
                        byLink = again.byLink
                        message = if (byLink) "link" else "code"
                    }
                    throw FoxError(if (again.byLink) "The email link is not confirmed yet" else "Mozilla now wants the emailed code")
                }
                finish(c.applicationContext, again.token, again.uid, pending.mail, epoch)
            }
        }
    }

    private fun prepareFreshLogin(c: Context) {
        synchronized(authLock) {
            if (authInFlight) return
            authEpoch++
            clearPendingLocked()
            stage = ""
            lastFailureDiagnostic = ""
        }
        FoxTorRuntime.clean(c.applicationContext)
        notifyUi()
    }

    fun signIn(c: Context, mail: String, password: String) {
        val clean = mail.trim()
        prepareFreshLogin(c)
        authWork(c, R.string.fox_stage_signin) { epoch ->
            signInNow(c.applicationContext, clean, password, false, epoch)
        }
    }

    fun submitCode(c: Context, raw: String) {
        if (canRetryLogin()) {
            retryLogin(c)
            return
        }
        val clean = cleanCode(raw)
        if (clean.isEmpty() || step == STEP_NONE) return
        val mode = step
        val token = pendingToken
        authWork(c) { epoch ->
            val pending = currentAuth(epoch) {
                if (step != mode || pendingToken != token) return@authWork
                PendingLogin(pendingToken, pendingUid, pendingEmail, pendingPassword)
            }
            say(c, R.string.fox_stage_code)
            if (mode == STEP_SIGNUP) {
                loginProvider.verifyEmail(pending.token, pending.uid, clean)
                currentAuth(epoch) { }
                try {
                    finish(c.applicationContext, pending.token, pending.uid, pending.mail, epoch)
                } catch (e: FoxError) {
                    currentAuth(epoch) { }
                    val again = loginProvider.signIn(pending.mail, pending.password)
                    currentAuth(epoch) {
                        pendingToken = again.token
                        pendingUid = again.uid
                        if (again.needsCode) {
                            step = STEP_LOGIN
                            byLink = again.byLink
                            message = if (byLink) "link" else "code"
                        }
                    }
                    if (!again.needsCode) finish(c.applicationContext, again.token, again.uid, pending.mail, epoch)
                }
            } else {
                loginProvider.verifySession(pending.token, clean)
                currentAuth(epoch) {
                    // A successful verification consumes the code even if a
                    // later OAuth, pass, or persistence operation fails.
                    loginSessionVerified = true
                }
                finish(c.applicationContext, pending.token, pending.uid, pending.mail, epoch)
            }
        }
    }

    fun retryLogin(c: Context) {
        if (!canRetryLogin()) return
        val token = pendingToken
        authWork(c) { epoch ->
            val pending = currentAuth(epoch) {
                if (!canRetryLogin() || pendingToken != token) return@authWork
                PendingLogin(pendingToken, pendingUid, pendingEmail, pendingPassword)
            }
            finish(c.applicationContext, pending.token, pending.uid, pending.mail, epoch)
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
        if (step == STEP_NONE || canRetryLogin()) return
        val mode = step
        val token = pendingToken
        val app = c.applicationContext
        authWork(c) { epoch ->
            val pending = currentAuth(epoch) {
                if (step != mode || pendingToken != token || canRetryLogin()) return@authWork
                PendingLogin(pendingToken, pendingUid, pendingEmail, pendingPassword)
            }
            if (mode == STEP_SIGNUP) {
                loginProvider.resendSignupCode(pending.token)
                currentAuth(epoch) { message = "code" }
            } else {
                signInNow(app, pending.mail, pending.password, false, epoch)
            }
        }
    }

    private fun finish(c: Context, sessionToken: String, uid: String, mail: String, epoch: Long) {
        currentAuth(epoch) { }
        say(c, R.string.fox_stage_pass)
        val tokens = loginProvider.tokens(sessionToken)
        // Do not start a new pass request after logout has invalidated the job.
        currentAuth(epoch) { }
        val pass = loginProvider.pass(tokens.access)
        currentAuth(epoch) {
            // Store publication and logout are ordered by the same lock.
            FoxStore.saveAccount(c, mail, uid, tokens, pass)
            clearPendingLocked()
            FoxBridgeStore.clear()
        }
    }

    fun refreshUsage(c: Context) {
        if (!signedIn(c)) return
        work(c, R.string.fox_stage_usage) {
            if (FoxLink.connected) {
                FoxLink.refreshUsageThroughTunnel(c.applicationContext)
                message = "quota"
            } else {
                val access = validAccess(c)
                val pass = FoxApi.pass(access.token)
                if (FoxStore.saveUsage(c, pass, access.id, access.revision)) message = "quota"
            }
        }
    }

    fun loadServers(c: Context) {
        if (servers.isNotEmpty() && serversUntil > System.currentTimeMillis()) return
        work(c, R.string.fox_stage_servers) {
            obtainServers(c.applicationContext, forceFresh = false) { FoxApi.servers() }
            message = "servers"
        }
    }

    /**
     * The active list. An unexpired sealed snapshot is used before any network call. A
     * fresh fetch is stored only after it succeeds, and a failed fetch leaves the current
     * list untouched. Reuse never extends the stored fetched_at.
     */
    internal fun obtainServers(app: Context, forceFresh: Boolean, fetch: () -> List<FoxLocation>): List<FoxLocation> {
        synchronized(serversLock) {
            val now = System.currentTimeMillis()
            if (!forceFresh) {
                if (servers.isNotEmpty() && serversUntil > now) return servers
                FoxServerCache.decode(FoxStore.serverCache(app), now)?.let { snapshot ->
                    servers = snapshot.locations
                    serversUntil = snapshot.expiresAt
                    serversFromCache = true
                    return snapshot.locations
                }
            }
            val fresh = fetch()
            if (fresh.isEmpty()) throw FoxError("Mozilla returned no servers")
            servers = fresh
            serversUntil = now + FoxServerCache.LIFETIME_MS
            serversFromCache = false
            FoxServerCache.encode(fresh, now)?.let { FoxStore.saveServerCache(app, it) }
            return fresh
        }
    }

    /** True while the list in use came from the sealed snapshot rather than a live fetch. */
    internal fun serversCameFromCache(): Boolean = serversFromCache

    fun measure(c: Context) {
        if (servers.isEmpty()) return
        val app = c.applicationContext
        val session = FoxStore.credentials(app)
        operations.begin()
        try {
            stage = c.getString(R.string.fox_stage_measure)
            notifyUi()
            thread {
                try {
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
                    message = "ping"
                } catch (e: Exception) {
                    message = e.message ?: "Network error"
                } finally {
                    operations.end()
                    if (!busy) stage = ""
                    notifyUi()
                }
            }
        } catch (e: Throwable) {
            operations.end()
            if (!busy) stage = ""
            notifyUi()
            throw e
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

    // Caller must hold authLock. Credentials are retained only for a pending flow.
    private fun clearPendingLocked() {
        step = STEP_NONE
        loginSessionVerified = false
        byLink = false
        pendingEmail = ""
        pendingPassword = ""
        pendingUid = ""
        pendingToken = ""
        message = ""
    }

    fun cancelLogin() {
        synchronized(authLock) {
            if (busy || authInFlight) return
            authEpoch++
            clearPendingLocked()
        }
        FoxBridgeStore.clear()
        notifyUi()
    }

    fun switchAccount(c: Context, id: String): Boolean {
        val epoch = synchronized(authLock) {
            if (busy || authInFlight) return false
            authInFlight = true
            authEpoch
        }
        try {
            FoxVpnService.stop(c)
            val switched = synchronized(authLock) {
                if (epoch != authEpoch || !FoxStore.switchAccount(c, id)) false
                else {
                    authEpoch++
                    clearPendingLocked()
                    true
                }
            }
            if (!switched) return false
            load(c)
            notifyUi()
            return true
        } finally {
            synchronized(authLock) { authInFlight = false }
        }
    }

    fun signOut(c: Context) {
        try {
            synchronized(authLock) {
                // Revoke publication before stopping transport, so a late
                // login cannot replace the active account during sign-out.
                authEpoch++
                clearPendingLocked()
                FoxStore.clear(c)
                FoxBridgeStore.clear()
            }
        } finally {
            FoxVpnService.stop(c)
        }
        load(c)
        probes.clear()
        notifyUi()
    }
}
