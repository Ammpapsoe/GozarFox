package com.gozar

/**
 * Hands out an access token that is still good, refreshing it first when it is
 * not.
 *
 * Every caller goes through the same lock, so a burst of concurrent work — the
 * tunnel asking for a proxy pass while the UI asks for usage — produces one
 * refresh request instead of several. That matters beyond tidiness: Mozilla
 * rotates the refresh token, so two parallel refreshes can invalidate each
 * other and log the user out.
 *
 * When Mozilla rejects the refresh token the credential is gone for good, and
 * retrying it on every request would only repeat the failure. The vault is told
 * to forget it, which is what puts the user back in front of the sign-in
 * screen.
 */
class FoxTokenManager(
    private val vault: Vault,
    private val exchange: (refreshToken: String) -> FoxTokens,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val skewMs: Long = DEFAULT_SKEW_MS
) {

    companion object {
        /** Refresh two minutes early so a request in flight never races expiry. */
        const val DEFAULT_SKEW_MS = 120_000L

        /** FxA `invalidToken` and `invalidRequestParameter`: never coming back. */
        private val DEAD_ERRNOS = setOf(108, 109, 110, 115)
        private val DEAD_STATUSES = setOf(400, 401)
    }

    /** What the vault holds. `label` and `revision` guard against a stale write. */
    class Entry(
        val label: String,
        val revision: Long,
        val access: String,
        val refreshToken: String,
        val expiresAt: Long
    )

    /** A token that is valid right now, plus the account it belongs to. */
    class Grant(val access: String, val label: String, val revision: Long)

    interface Vault {
        fun read(): Entry?

        /** False when the account changed between the read and this write. */
        fun write(previous: Entry, tokens: FoxTokens): Boolean

        /** Drop a credential Mozilla has refused; the user must sign in again. */
        fun forget(previous: Entry)
    }

    private val lock = Any()

    fun grant(): Grant = synchronized(lock) {
        val entry = vault.read() ?: throw FoxError("Sign in again")
        if (entry.label.isEmpty() || entry.refreshToken.isEmpty()) throw FoxError("Sign in again")
        if (usable(entry)) return@synchronized Grant(entry.access, entry.label, entry.revision)

        val tokens = try {
            exchange(entry.refreshToken)
        } catch (error: FoxError) {
            if (error.errno in DEAD_ERRNOS || error.status in DEAD_STATUSES) vault.forget(entry)
            throw error
        }
        if (tokens.access.isEmpty()) throw FoxError("Mozilla returned an incomplete token")
        if (!vault.write(entry, tokens)) throw FoxError("Account changed")
        Grant(tokens.access, entry.label, entry.revision)
    }

    private fun usable(entry: Entry): Boolean =
        entry.access.isNotEmpty() && entry.expiresAt - skewMs > now()
}
