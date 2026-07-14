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
                val t0 = System.currentTimeMillis()
                LookupTables.initialize(context.applicationContext)
                val t1 = System.currentTimeMillis()
                resetRegexCache()
                val t2 = System.currentTimeMillis()
                EngineSmoke.runOnce()
                val t3 = System.currentTimeMillis()
                SessionStore.init(context.applicationContext)
                val t4 = System.currentTimeMillis()
                if (BuildConfig.DEBUG) android.util.Log.d("LynxTiming",
                    "LookupTables=${t1-t0}ms resetRegexCache=${t2-t1}ms EngineSmoke=${t3-t2}ms SessionStore=${t4-t3}ms TOTAL=${t4-t0}ms")
                ready = true
            }
        }
    }
}
