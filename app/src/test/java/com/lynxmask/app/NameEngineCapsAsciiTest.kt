package com.lynxmask.app

import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.*

/**
 * Testy S1 (CAPS LOCK) i S2 (ASCII bez ogonków) w NameEngine.
 * Używa initializeFromClasspath() — pełny słownik z withAsciiVariants().
 */
class NameEngineCapsAsciiTest {

    @Before
    fun setup() {
        LookupTables.resetForTesting()
        LookupTables.initializeFromClasspath()
    }

    @After
    fun teardown() {
        LookupTables.resetForTesting()
        resetRegexCache()
    }

    // ── S2 — ASCII imiona bez ogonków ──────────────────────────────────────

    @Test
    fun `S2 imie ASCII Stanislaw z polskim nazwiskiem jest maskowane`() {
        val r = PseudonymEngine.pseudonymize("Imię: Stanislaw Kowalski, PESEL: 65051112345")
        assertTrue(
            "Stanislaw Kowalski powinien być zamaskowany jako OSOBA, tokenMap: ${r.tokenMap}",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) }
        )
        assertFalse(
            "Stanislaw Kowalski nie powinien być widoczny w wyniku",
            r.pseudonymizedText.contains("Stanislaw Kowalski", ignoreCase = true)
        )
    }

    @Test
    fun `S2 imie ASCII Lukasz z polskim nazwiskiem jest maskowane`() {
        val r = PseudonymEngine.pseudonymize("Osoba: Lukasz Wiśniewski")
        assertTrue(
            "Lukasz Wiśniewski powinien być zamaskowany jako OSOBA, tokenMap: ${r.tokenMap}",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) }
        )
        assertFalse(
            "Lukasz Wiśniewski nie powinien być widoczny",
            r.pseudonymizedText.contains("Lukasz", ignoreCase = true)
        )
    }

    @Test
    fun `S2 nazwisko ASCII Szymanski bez ogonka jest maskowane`() {
        // withAsciiVariants() dodaje "szymanski" do surnamesForms obok "szymański"
        val r = PseudonymEngine.pseudonymize("Jan Szymanski podpisał umowę.")
        assertTrue(
            "Jan Szymanski powinien być zamaskowany, tokenMap: ${r.tokenMap}",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) }
        )
        assertFalse(
            "Szymanski nie powinien być widoczny",
            r.pseudonymizedText.contains("Szymanski", ignoreCase = true)
        )
    }

    // ── S1 — CAPS LOCK imię + nazwisko ─────────────────────────────────────

    @Test
    fun `S1 CAPS LOCK JAN KOWALSKI jest maskowane`() {
        // Bug S1: NAME_FORWARD_REGEX wymaga [a-z...] po pierwszej literze.
        // "KOWALSKI" jest all-caps — regex nie pasuje bez S1-FIX.
        val r = PseudonymEngine.pseudonymize("Dane osoby: JAN KOWALSKI, PESEL: 65051112345")
        assertTrue(
            "JAN KOWALSKI powinien być zamaskowany jako OSOBA, tokenMap: ${r.tokenMap}",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) }
        )
        assertFalse(
            "JAN KOWALSKI nie powinien być widoczny w wyniku",
            r.pseudonymizedText.contains("JAN KOWALSKI", ignoreCase = false)
        )
    }

    @Test
    fun `S1 CAPS LOCK ANNA NOWAK jest maskowane`() {
        val r = PseudonymEngine.pseudonymize("Zleceniobiorca: ANNA NOWAK, adres: ul. Lipowa 1")
        assertTrue(
            "ANNA NOWAK powinna być zamaskowana jako OSOBA, tokenMap: ${r.tokenMap}",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) }
        )
        assertFalse(
            "ANNA NOWAK nie powinna być widoczna",
            r.pseudonymizedText.contains("ANNA NOWAK", ignoreCase = false)
        )
    }

    @Test
    fun `S1 CAPS LOCK nie tworzy FP dla slow kluczowych UMOWA RODO`() {
        // Bramka słownikowa: "umowa" i "rodo" nie są w namesForms/surnamesForms
        val r = PseudonymEngine.pseudonymize("Podpisano UMOWA RODO nr 123.")
        assertFalse(
            "UMOWA RODO nie powinno być tokenizowane jako OSOBA",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) }
        )
    }

    @Test
    fun `S1 CAPS LOCK nie tworzy FP dla akronimow instytucji ZUS NFZ`() {
        // Akronimy 2-3 litery nie przejdą bramki — nie są w słowniku imion/nazwisk
        val r = PseudonymEngine.pseudonymize("Składka do ZUS NFZ opłacona.")
        assertFalse(
            "ZUS NFZ nie powinno być tokenizowane jako OSOBA",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) }
        )
    }
}
