package com.lynxmask.app

// SmokeE2ETest.kt — W5: Smoke E2E roundtrip bez kamery
//
// Weryfikuje cały łańcuch: tekst (symulacja OCR) → PseudonymEngine → SessionStore → Deanonymizer.
// Bez wywołań OCR i bez UI — uruchamia się jako szybki test regresji po każdej zmianie silnika.
//
// Uruchomienie: ./gradlew :app:connectedDebugAndroidTest (telefon podłączony)
// lub: Android Studio → zielony trójkąt przy klasie

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeE2ETest {

    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        LookupTables.initialize(ctx)
        SessionStore.resetForTesting()
        ctx.getDatabasePath("sessions.db")?.delete()
        ctx.getSharedPreferences("lynxmask_session_store", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun tearDown() {
        SessionStore.resetForTesting()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 1 — Pełny roundtrip: silnik → SessionStore → depseudo
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun e2e_pseudonymize_save_load_restore_roundtrip() {
        val originalText = """
            Umowa zawarta z Janem Kowalskim (PESEL: 44051401458).
            Adres: ul. Kwiatowa 12, 00-001 Warszawa.
            Kontakt: jan.kowalski@example.com, tel. 600 123 456.
        """.trimIndent()

        // Krok 1: pseudonimizacja
        val result = PseudonymEngine.pseudonymize(originalText)
        val pseudoText = result.pseudonymizedText
        val sesjaId = result.sessionId

        // Pseudonimizacja musi ukryć PII
        assertFalse("PESEL w tekście po pseudonimizacji", pseudoText.contains("44051401458"))
        assertFalse("email w tekście po pseudonimizacji", pseudoText.contains("jan.kowalski@example.com"))
        assertTrue("tokenMap pusty — silnik nic nie wykrył", result.tokenMap.isNotEmpty())

        // Krok 2: zapis sesji
        val json = result.tokenMapJson()
        SessionStore.save(ctx, sesjaId, json, result.tokenMap.size)

        // Krok 3: załaduj z bazy
        val loaded = SessionStore.loadTokenMap(ctx, sesjaId)
        assertNotNull("SessionStore zwrócił null dla istniejącej sesji", loaded)
        assertEquals("TokenMap po roundtrip SessionStore ma inny rozmiar",
            result.tokenMap.size, loaded!!.size)

        // Krok 4: depseudonimizacja
        val restored = Deanonymizer.restore(pseudoText, loaded)

        // Wartości PII muszą wrócić
        assertTrue("PESEL nie przywrócony przez depseudo", restored.contains("44051401458"))
        assertTrue("email nie przywrócony przez depseudo",
            restored.contains("jan.kowalski@example.com"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 2 — Dwie sesje nie mieszają tokenMapów
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun e2e_dwie_sesje_nie_krzyzuja_tokenow() {
        val text1 = "Dokument 1: Anna Nowak, PESEL 44051401458."
        val text2 = "Dokument 2: Piotr Wiśniewski, NIP 526-000-13-29."

        val r1 = PseudonymEngine.pseudonymize(text1)
        val r2 = PseudonymEngine.pseudonymize(text2)

        // Różne sesje
        assertNotEquals("Dwa dokumenty dostały ten sam sesjaId", r1.sessionId, r2.sessionId)

        SessionStore.save(ctx, r1.sessionId, r1.tokenMapJson(), r1.tokenMap.size)
        SessionStore.save(ctx, r2.sessionId, r2.tokenMapJson(), r2.tokenMap.size)

        val loaded1 = SessionStore.loadTokenMap(ctx, r1.sessionId)!!
        val loaded2 = SessionStore.loadTokenMap(ctx, r2.sessionId)!!

        // Sesja 1 musi zawierać tylko swoje wartości
        val vals1 = loaded1.values.joinToString(" ")
        assertFalse("TokenMap sesji 1 zawiera dane z sesji 2",
            vals1.contains("Wiśniewski") || vals1.contains("526"))

        // Sesja 2 musi zawierać tylko swoje wartości
        val vals2 = loaded2.values.joinToString(" ")
        assertFalse("TokenMap sesji 2 zawiera dane z sesji 1",
            vals2.contains("Nowak") || vals2.contains("44051401458"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEST 3 — Polskie znaki i znaki specjalne przeżywają cały łańcuch
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun e2e_polskie_znaki_i_cudzyslow_przezyja_roundtrip() {
        // Adres z polskimi znakami + imię z cudzysłowem w pseudonimie
        val text = """
            Strona: Łukasz Żółtowski (PESEL 44051401458).
            Adres: ul. Śródmiejska 4/6, Łódź.
        """.trimIndent()

        val result = PseudonymEngine.pseudonymize(text)
        val sesjaId = result.sessionId

        SessionStore.save(ctx, sesjaId, result.tokenMapJson(), result.tokenMap.size)

        val loaded = SessionStore.loadTokenMap(ctx, sesjaId)
        assertNotNull("SessionStore zwrócił null", loaded)

        val restored = Deanonymizer.restore(result.pseudonymizedText, loaded!!)

        // Polskie litery nie mogą być zniekształcone przez JSON escaping
        assertTrue("'Żółtowski' nie przywrócony", restored.contains("Żółtowski"))
        assertTrue("'Łódź' lub 'Śródmiejska' nie przywrócone",
            restored.contains("Łódź") || restored.contains("Śródmiejska"))
    }
}
