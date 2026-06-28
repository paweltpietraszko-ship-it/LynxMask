package com.lynxmask.app

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf

/** Intent extras — nawigacja po zakończeniu ShareTarget / zapisie do biblioteki. */
object LynxNavExtras {
    const val OPEN_LIBRARY = "com.lynxmask.app.OPEN_LIBRARY"
    const val OPEN_LIBRARY_SESSION = "com.lynxmask.app.OPEN_LIBRARY_SESSION"
}

/** Otwarcie biblioteki z ShareTarget (osobny task) — intent bywa gubiony przy singleTop. */
object LynxPendingNav {
    @Volatile private var pendingLibrary = false
    @Volatile private var pendingSessionId: String? = null
    private val revisionState = mutableIntStateOf(0)

    /** Odczyt w composable — subskrypcja na requestLibrary(). */
    val revision: Int get() = revisionState.intValue

    fun hasPending(): Boolean = pendingLibrary

    fun peekSessionId(): String? = if (pendingLibrary) pendingSessionId else null

    fun requestLibrary(sessionId: String?) {
        pendingLibrary = true
        pendingSessionId = sessionId
        revisionState.intValue++
    }

    /** null = brak żądania; "" = lista biblioteki; inaczej id dokumentu. */
    fun consumeLibraryOpen(): String? {
        if (!pendingLibrary) return null
        pendingLibrary = false
        return pendingSessionId ?: ""
    }
}

fun Context.openMainToLibrary(sessionId: String? = null) {
    LynxPendingNav.requestLibrary(sessionId)
    startActivity(Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(LynxNavExtras.OPEN_LIBRARY, true)
        sessionId?.let { putExtra(LynxNavExtras.OPEN_LIBRARY_SESSION, it) }
    })
}
