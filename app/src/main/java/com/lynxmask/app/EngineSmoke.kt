package com.lynxmask.app

import android.util.Log

// Wymusza kompilację regexów ICU na urządzeniu przed pierwszym pseudonymize().
// Android ICU odrzuca wzorce legalne w JVM (np. lookbehind z \w*) — błąd
// jest rzucany przy inicjalizacji obiektu, nie przy starcie apki.
//
// Wywołaj po LookupTables.initialize() + resetRegexCache().
// Idempotentny — kolejne wywołania są no-op (flaga done).
object EngineSmoke {

    @Volatile private var done = false

    @Volatile var failed = false
        private set

    fun runOnce() {
        if (done) return
        synchronized(this) {
            if (done) return
            try {
                OcrNormalizer.normalize(
                    "PESEL 90010100010 NIP 123-456-78-90 test@test.pl ul. Kwiatowa 5"
                )
                PseudonymEngine.pseudonymize(
                    "Jan Kowalski PESEL 90010100010 NIP 111-22-33-444"
                )
                done = true
            } catch (e: Exception) {
                done = true
                failed = true
                if (BuildConfig.DEBUG)
                    throw RuntimeException(
                        "EngineSmoke FAIL — regex ICU nie skompilował się: ${e.message}", e
                    )
                Log.e("EngineSmoke", "Regex ICU crash — maskowanie może być niestabilne", e)
            }
        }
    }
}
