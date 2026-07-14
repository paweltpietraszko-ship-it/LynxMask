package com.lynxmask.app

import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert.*

/**
 * Wykrywanie danych z dowodu osobistego:
 * - solo CAPS nazwisko bez etykiety ("KOWALSKI" w linii)
 * - pola "Imię:", "Imię ojca:", "Imię matki:"
 * - brak FP na akronimy (PESEL, KRS, RODO)
 */
class IdCardDetectionTest {

    companion object {
        // BUG-TESTY-WOLNE-SLOWNIK-FIX (14.07): raz na klasę zamiast raz na każdy z 13 testów.
        @BeforeClass @JvmStatic fun setupClass()    { LookupTables.resetForTesting(); LookupTables.initializeFromClasspath() }
        @AfterClass  @JvmStatic fun teardownClass() { LookupTables.resetForTesting(); resetRegexCache() }
    }

    private fun pseudo(text: String) = PseudonymEngine.pseudonymize(text)

    // --- KOWALSKI solo all-caps ---

    @Test fun `stary dowod KOWALSKI solo maskowane`() {
        val r = pseudo("KOWALSKI\nImię: JAN\nPESEL: 85071792056")
        assertFalse("KOWALSKI nie może być gołe", r.pseudonymizedText.contains("KOWALSKI"))
        assertTrue("musi być OSOBA token", r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) })
    }

    @Test fun `stary dowod NOWAK solo maskowane`() {
        val r = pseudo("NOWAK")
        assertFalse("NOWAK nie może być gołe", r.pseudonymizedText.contains("NOWAK"))
    }

    // --- Imię: JAN ---

    @Test fun `pole Imie z etykieta maskuje wartosc`() {
        val r = pseudo("Imię: JAN\nNazwisko: KOWALSKI")
        assertFalse("JAN nie może być gołe", r.pseudonymizedText.contains("JAN"))
        assertTrue("etykieta Imię zostaje", r.pseudonymizedText.contains("Imię:") ||
            r.pseudonymizedText.contains("Imię"))
    }

    // --- Imię ojca: STANISŁAW / Imię matki: MARIA ---

    @Test fun `pole Imie ojca maskuje imie rodzica`() {
        val r = pseudo("Imię ojca: STANISŁAW")
        assertFalse("STANISŁAW nie może być gołe", r.pseudonymizedText.contains("STANISŁAW"))
        assertTrue("etykieta pozostaje", r.pseudonymizedText.contains("ojca"))
    }

    @Test fun `pole Imie matki maskuje imie rodzica`() {
        val r = pseudo("Imię matki: MARIA")
        assertFalse("MARIA nie może być gołe", r.pseudonymizedText.contains("MARIA"))
        assertTrue("etykieta pozostaje", r.pseudonymizedText.contains("matki"))
    }

    @Test fun `caly stary dowod maskowany`() {
        val r = pseudo("""
            KOWALSKI
            Imię: JAN
            Imię ojca: STANISŁAW
            Imię matki: MARIA
            PESEL: 85071792056
        """.trimIndent())
        assertFalse("KOWALSKI gołe", r.pseudonymizedText.contains("KOWALSKI"))
        assertFalse("JAN gołe", r.pseudonymizedText.contains("JAN"))
        assertFalse("STANISŁAW gołe", r.pseudonymizedText.contains("STANISŁAW"))
        assertFalse("MARIA gołe", r.pseudonymizedText.contains("MARIA"))
        assertFalse("PESEL gołe", r.pseudonymizedText.contains("85071792056"))
    }

    // --- Brak FP na akronimy ---

    @Test fun `PESEL nie jest tokenizowane jako OSOBA`() {
        val r = pseudo("Numer PESEL: 65051112345")
        assertFalse("PESEL nie jest OSOBA", r.pseudonymizedText.contains("OSOBA_"))
        // PESEL jest NUMER, nie OSOBA
        assertTrue("PESEL jest NUMER", r.pseudonymizedText.contains("NUMER_"))
    }

    @Test fun `RODO KRS nie sa OSOBA`() {
        val r = pseudo("RODO i KRS obowiązują od 2018 roku.")
        assertFalse("RODO/KRS nie są OSOBA", r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) })
    }

    @Test fun `UMOWA nie jest OSOBA`() {
        val r = pseudo("UMOWA ZLECENIE nr 001/2026")
        assertFalse("UMOWA nie jest OSOBA", r.pseudonymizedText.contains("OSOBA_"))
    }

    // --- S4 FP fix: złotych przed rzeczownikiem ---

    @Test fun `S4 FP sto zlotych monet nie jest KWOTA`() {
        val r = pseudo("Posiada sto złotych monet kolekcjonerskich.")
        assertFalse("'sto złotych monet' nie powinno być KWOTA", r.pseudonymizedText.contains("KWOTA_"))
    }

    @Test fun `S4 FP dwa zlote medale nie sa KWOTA`() {
        val r = pseudo("Zdobył dwa złote medale olimpijskie.")
        assertFalse("'dwa złote medale' nie powinno być KWOTA", r.pseudonymizedText.contains("KWOTA_"))
    }

    @Test fun `S4 TP dwadziescia tysiecy zlotych jest KWOTA`() {
        val r = pseudo("Pożyczka w kwocie dwadzieścia tysięcy złotych.")
        assertTrue("dwadzieścia tysięcy złotych → KWOTA", r.pseudonymizedText.contains("KWOTA_"))
    }

    @Test fun `S4 TP piecset zlotych z kropka jest KWOTA`() {
        val r = pseudo("Wynagrodzenie: pięćset złotych.")
        assertTrue("pięćset złotych. → KWOTA", r.pseudonymizedText.contains("KWOTA_"))
    }
}
