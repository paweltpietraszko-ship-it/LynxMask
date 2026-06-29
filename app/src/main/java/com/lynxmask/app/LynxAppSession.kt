package com.lynxmask.app

/**
 * Stan odblokowania aplikacji w obrębie procesu — współdzielony między MainActivity a ShareTargetActivity.
 * Po zamknięciu procesu wraca do LOCKED (hasło trzeba podać ponownie).
 */
object LynxAppSession {

    enum class Access { LOCKED, AUTHENTICATED, EXPRESS }

    @Volatile
    private var access: Access = Access.LOCKED

    val isAuthenticated: Boolean get() = access == Access.AUTHENTICATED
    val isExpress: Boolean get() = access == Access.EXPRESS
    val isUnlocked: Boolean get() = access != Access.LOCKED
    val allowsLibrary: Boolean get() = isAuthenticated

    fun unlockAuthenticated() {
        access = Access.AUTHENTICATED
    }

    fun unlockExpress() {
        access = Access.EXPRESS
    }

    fun lock() {
        access = Access.LOCKED
    }
}
