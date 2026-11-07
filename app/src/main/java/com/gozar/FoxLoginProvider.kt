package com.gozar

/** The network operations needed to complete a sign-in attempt. */
internal interface FoxLoginProvider {
    fun signIn(email: String, password: String): FoxSession
    fun verifySession(sessionToken: String, code: String)
    fun tokens(sessionToken: String): FoxTokens
    fun pass(access: String): FoxPass
}

internal object MozillaLoginProvider : FoxLoginProvider {
    override fun signIn(email: String, password: String): FoxSession = FoxApi.signIn(email, password)
    override fun verifySession(sessionToken: String, code: String) = FoxApi.verifySession(sessionToken, code)
    override fun tokens(sessionToken: String): FoxTokens = FoxApi.tokens(sessionToken)
    override fun pass(access: String): FoxPass = FoxApi.pass(access)
}
