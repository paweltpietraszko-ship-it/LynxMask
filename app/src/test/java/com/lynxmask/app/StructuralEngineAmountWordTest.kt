package com.lynxmask.app

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * S4: Kwoty słowne — "dwadzieścia tysięcy złotych" → TOKEN_KWOTA.
 * Testy dla wzorców A (kontekst), B (skrót walutowy), C (złotych).
 */
class StructuralEngineAmountWordTest {

    @Before fun setup()   { LookupTables.initializeForTesting() }
    @After  fun teardown(){ LookupTables.resetForTesting() }

    private fun pseudonymize(text: String) =
        PseudonymEngine.pseudonymize(text).pseudonymizedText

    // --- Wzorzec C: liczebnik + złotych ---

    @Test fun `S4 dwadziescia tysiece zlotych wykrywane`() {
        val result = pseudonymize("Pożyczkę w kwocie dwadzieścia tysięcy złotych strony ustaliły")
        assertTrue("dwadzieścia tysięcy złotych → KWOTA", result.contains("KWOTA_"))
        assertFalse("Nie może przeciec", result.contains("dwadzieścia tysięcy złotych"))
    }

    @Test fun `S4 piecset zlotych bez kontekstu wykrywane`() {
        val result = pseudonymize("Wynagrodzenie: pięćset złotych brutto.")
        assertTrue("pięćset złotych → KWOTA", result.contains("KWOTA_"))
    }

    @Test fun `S4 tysiac zlotych wykrywane`() {
        val result = pseudonymize("zapłata tysiąc złotych")
        assertTrue("tysiąc złotych → KWOTA", result.contains("KWOTA_"))
    }

    @Test fun `S4 milion zlotych wykrywane`() {
        val result = pseudonymize("kwota milion złotych tytułem odszkodowania")
        assertTrue("milion złotych → KWOTA", result.contains("KWOTA_"))
    }

    // --- Wzorzec B: liczebnik + skrót "zł" ---

    @Test fun `S4 sto zl skrot walutowy wykrywane`() {
        val result = pseudonymize("należność wynosi sto zł")
        assertTrue("sto zł → KWOTA", result.contains("KWOTA_"))
    }

    @Test fun `S4 piecset groszy wykrywane`() {
        val result = pseudonymize("reszta: pięćset groszy")
        assertTrue("pięćset groszy → KWOTA", result.contains("KWOTA_"))
    }

    // --- Wzorzec A: kontekst słowny ---

    @Test fun `S4 wynagrodzenie z kontekstem wykrywane`() {
        val result = pseudonymize("wynagrodzenie w wysokości trzydzieści tysięcy złotych miesięcznie")
        assertTrue("wynagrodzenie X złotych → KWOTA", result.contains("KWOTA_"))
    }

    @Test fun `S4 kwota z kontekstem wykrywane`() {
        val result = pseudonymize("kwota pożyczki pięć tysięcy złotych")
        assertTrue("kwota X złotych → KWOTA", result.contains("KWOTA_"))
    }

    // --- Brak FP na liczbach strukturalnych ---

    @Test fun `S4 nie maskuje daty jako kwoty`() {
        val result = pseudonymize("dnia dwudziestego trzeciego czerwca dwa tysiące dwudziestego szóstego roku")
        // data słowna nie ma kotwicy złotych/zł — nie powinna być KWOTA
        assertFalse("Data słowna nie jest KWOTA", result.contains("KWOTA_"))
    }
}
