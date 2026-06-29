package com.lynxmask.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Jednorazowa inicjalizacja silnika i bazy — może trwać kilka sekund na słabszym urządzeniu. */
object LynxAppInit {
    @Volatile
    private var ready = false
    private val lock = Any()

    val isReady: Boolean get() = ready

    suspend fun ensureReady(context: Context) {
        if (ready) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (ready) return@withContext
                LookupTables.initialize(context.applicationContext)
                resetRegexCache()
                EngineSmoke.runOnce()
                SessionStore.init(context.applicationContext)
                ready = true
            }
        }
    }
}
