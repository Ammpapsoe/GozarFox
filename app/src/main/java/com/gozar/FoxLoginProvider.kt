package com.gozar

/** The network operations needed to complete a sign-in attempt. */
internal interface FoxLoginProvider {
    fun register(email: String, password: String): FoxSession
    fun signIn(email: String, password: String): FoxSession
    fun emailVerified(sessionToken: String): Boolean
    fun verifyEmail(sessionToken: String, uid: String, code: String)
    fun resendSignupCode(sessionToken: String)
    fun verifySession(sessionToken: String, code: String)
    fun tokens(sessionToken: String): FoxTokens
    fun pass(access: String): FoxPass
}

internal object MozillaLoginProvider : FoxLoginProvider {
    override fun register(email: String, password: String): FoxSession = throw FoxError("Mozilla WebView sign-up is required")
    override fun signIn(email: String, password: String): FoxSession = throw FoxError("Mozilla WebView sign-in is required")
    override fun emailVerified(sessionToken: String): Boolean = FoxApi.emailVerified(sessionToken)
    override fun verifyEmail(sessionToken: String, uid: String, code: String) = FoxApi.verifyEmail(sessionToken, uid, code)
    override fun resendSignupCode(sessionToken: String) = FoxApi.resendSignupCode(sessionToken)
    override fun verifySession(sessionToken: String, code: String) = FoxApi.verifySession(sessionToken, code)
    override fun tokens(sessionToken: String): FoxTokens = FoxApi.tokens(sessionToken)
    override fun pass(access: String): FoxPass = FoxApi.pass(access)
}
