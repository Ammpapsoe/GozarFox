package com.gozar

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale

data class SavedFoxAccount(val id: String, val email: String, val active: Boolean)

data class FoxCredentials(val id: String, val access: String, val refresh: String, val expiresAt: Long, val revision: Long)

object FoxStore {

    private val lock = Any()
    private fun p(c: Context) = c.getSharedPreferences("fox", Context.MODE_PRIVATE)

    private fun secret(c: Context, prefs: SharedPreferences, key: String): String =
        SecretBox.open(c, prefs.getString(key, "") ?: "")

    fun email(c: Context): String = p(c).getString("email", "") ?: ""
    fun uid(c: Context): String = p(c).getString("uid", "") ?: ""
    fun access(c: Context): String = secret(c, p(c), "access")
    fun refreshToken(c: Context): String = secret(c, p(c), "refresh")
    fun expiresAt(c: Context): Long = p(c).getLong("expires_at", 0L)
    fun pass(c: Context): String = secret(c, p(c), "pass")
    fun passUntil(c: Context): Long = p(c).getLong("pass_until", 0L)

    internal fun passExpiry(token: String, now: Long = System.currentTimeMillis()): Long {
        return try {
            val payload = token.split('.').getOrNull(1) ?: return now + 4 * 60_000L
            val decoded = android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            val seconds = JSONObject(String(decoded, Charsets.UTF_8)).optLong("exp", 0)
            if (seconds in 1..4_102_444_800L) seconds * 1000L else now + 4 * 60_000L
        } catch (_: Exception) {
            now + 4 * 60_000L
        }
    }

    internal fun renewalDelay(expiry: Long, now: Long = System.currentTimeMillis()): Long =
        (expiry - now - 120_000L).coerceIn(15_000L, 4 * 60_000L)
    fun limit(c: Context): Long = p(c).getLong("limit", 0L)
    fun remaining(c: Context): Long = p(c).getLong("remaining", 0L)
    fun reset(c: Context): String = p(c).getString("reset", "") ?: ""
    fun selected(c: Context): String = p(c).getString("selected", "REC") ?: "REC"
    fun authRevision(c: Context): Long = p(c).getLong("auth_revision", 0L)

    private fun accountId(email: String, uid: String): String =
        if (uid.isNotBlank()) "uid:$uid" else if (email.isNotBlank()) "email:${email.lowercase(Locale.ROOT)}" else ""

    private fun activeId(prefs: SharedPreferences): String =
        accountId(prefs.getString("email", "").orEmpty(), prefs.getString("uid", "").orEmpty())

    fun activeId(c: Context): String = synchronized(lock) { activeId(p(c)) }

    fun credentials(c: Context): FoxCredentials = synchronized(lock) {
        val prefs = p(c)
        FoxCredentials(
            activeId(prefs),
            secret(c, prefs, "access"),
            secret(c, prefs, "refresh"),
            prefs.getLong("expires_at", 0L),
            prefs.getLong("session_revision", 0L)
        )
    }

    private fun snapshot(c: Context, prefs: SharedPreferences): JSONObject = JSONObject()
        .put("email", prefs.getString("email", "").orEmpty())
        .put("uid", prefs.getString("uid", "").orEmpty())
        .put("access", secret(c, prefs, "access"))
        .put("refresh", secret(c, prefs, "refresh"))
        .put("expires_at", prefs.getLong("expires_at", 0L))
        .put("pass", secret(c, prefs, "pass"))
        .put("pass_until", prefs.getLong("pass_until", 0L))
        .put("limit", prefs.getLong("limit", 0L))
        .put("remaining", prefs.getLong("remaining", 0L))
        .put("reset", prefs.getString("reset", "").orEmpty())
        .put("selected", prefs.getString("selected", "REC").orEmpty())

    private fun entries(c: Context, prefs: SharedPreferences): JSONObject {
        val stored = try {
            JSONObject(SecretBox.open(c, prefs.getString("accounts", "{}").orEmpty()).ifEmpty { "{}" })
        } catch (e: Exception) {
            JSONObject()
        }
        val id = activeId(prefs)
        if (id.isNotEmpty() && secret(c, prefs, "refresh").isNotEmpty()) {
            stored.put(id, snapshot(c, prefs))
        }
        return stored
    }

    private fun writeActive(c: Context, edit: SharedPreferences.Editor, account: JSONObject) {
        for (key in listOf("email", "uid", "reset", "selected")) {
            edit.putString(key, account.optString(key, if (key == "selected") "REC" else ""))
        }
        for (key in listOf("access", "refresh", "pass")) {
            val value = account.optString(key, "")
            edit.putString(key, if (value.isEmpty()) value else SecretBox.seal(c, value))
        }
        for (key in listOf("expires_at", "pass_until", "limit", "remaining")) {
            edit.putLong(key, account.optLong(key, 0L))
        }
    }

    fun accounts(c: Context): List<SavedFoxAccount> = synchronized(lock) {
        val prefs = p(c)
        val data = entries(c, prefs)
        val active = activeId(prefs)
        val list = mutableListOf<SavedFoxAccount>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val item = data.optJSONObject(id) ?: continue
            val address = item.optString("email", "")
            if (address.isNotBlank() && item.optString("refresh", "").isNotBlank()) {
                list.add(SavedFoxAccount(id, address, id == active))
            }
        }
        prefs.edit().putString("accounts", SecretBox.seal(c, data.toString())).apply()
        list.sortedWith(compareByDescending<SavedFoxAccount> { it.active }.thenBy { it.email.lowercase(Locale.ROOT) })
    }

    fun switchAccount(c: Context, id: String): Boolean = synchronized(lock) {
        val prefs = p(c)
        val data = entries(c, prefs)
        val target = data.optJSONObject(id) ?: return@synchronized false
        if (target.optString("email").isBlank() || target.optString("refresh").isBlank()) return@synchronized false
        val edit = prefs.edit().putString("accounts", SecretBox.seal(c, data.toString()))
            .putLong("session_revision", prefs.getLong("session_revision", 0L) + 1L)
        writeActive(c, edit, target)
        edit.commit()
    }

    fun setSelected(c: Context, code: String) = synchronized(lock) {
        val prefs = p(c)
        val data = entries(c, prefs)
        val id = activeId(prefs)
        data.optJSONObject(id)?.put("selected", code)
        prefs.edit().putString("selected", code).putString("accounts", SecretBox.seal(c, data.toString())).apply()
    }

    fun saveAccount(c: Context, email: String, uid: String, tokens: FoxTokens, pass: FoxPass) = synchronized(lock) {
        val prefs = p(c)
        val data = entries(c, prefs)
        val id = accountId(email, uid)
        var existing = data.optJSONObject(id)
        val duplicateIds = mutableListOf<String>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = data.optJSONObject(key) ?: continue
            if (key != id && item.optString("email", "").equals(email, ignoreCase = true)) {
                if (existing == null) existing = item
                duplicateIds.add(key)
            }
        }
        duplicateIds.forEach { data.remove(it) }
        val account = JSONObject()
            .put("email", email)
            .put("uid", uid)
            .put("access", tokens.access)
            .put("refresh", tokens.refresh)
            .put("expires_at", System.currentTimeMillis() + tokens.expiresIn * 1000L)
            .put("pass", pass.token)
            .put("pass_until", passExpiry(pass.token))
            .put("limit", pass.limit)
            .put("remaining", pass.remaining)
            .put("reset", pass.reset)
            .put("selected", existing?.optString("selected", "REC") ?: "REC")
        data.put(id, account)
        val edit = prefs.edit().putString("accounts", SecretBox.seal(c, data.toString()))
            .putLong("auth_revision", prefs.getLong("auth_revision", 0L) + 1L)
            .putLong("session_revision", prefs.getLong("session_revision", 0L) + 1L)
        writeActive(c, edit, account)
        if (!edit.commit()) throw IllegalStateException("Could not save the account")
    }

    fun saveAccess(c: Context, tokens: FoxTokens, expectedId: String, expectedRevision: Long): Boolean = synchronized(lock) {
        val prefs = p(c)
        val id = activeId(prefs)
        if (id.isEmpty() || id != expectedId || prefs.getLong("session_revision", 0L) != expectedRevision) return@synchronized false
        val data = entries(c, prefs)
        val account = data.optJSONObject(id) ?: snapshot(c, prefs)
        val expiry = System.currentTimeMillis() + tokens.expiresIn * 1000L
        account.put("access", tokens.access).put("refresh", tokens.refresh).put("expires_at", expiry)
        data.put(id, account)
        val edit = prefs.edit().putString("accounts", SecretBox.seal(c, data.toString()))
        writeActive(c, edit, account)
        edit.commit()
    }

    fun saveUsage(c: Context, pass: FoxPass, expectedId: String, expectedRevision: Long): Boolean = synchronized(lock) {
        val prefs = p(c)
        val id = activeId(prefs)
        if (id.isEmpty() || id != expectedId || prefs.getLong("session_revision", 0L) != expectedRevision) return@synchronized false
        val data = entries(c, prefs)
        val account = data.optJSONObject(id) ?: snapshot(c, prefs)
        account.put("pass", pass.token)
            .put("pass_until", passExpiry(pass.token))
            .put("limit", pass.limit)
            .put("remaining", pass.remaining)
            .put("reset", pass.reset)
        data.put(id, account)
        val edit = prefs.edit().putString("accounts", SecretBox.seal(c, data.toString()))
        writeActive(c, edit, account)
        edit.commit()
    }

    fun clear(c: Context) = synchronized(lock) {
        val prefs = p(c)
        val data = entries(c, prefs)
        data.remove(activeId(prefs))
        val edit = prefs.edit().putString("accounts", SecretBox.seal(c, data.toString()))
            .putLong("session_revision", prefs.getLong("session_revision", 0L) + 1L)
        writeActive(c, edit, JSONObject().put("selected", "REC"))
        edit.commit()
    }
}
