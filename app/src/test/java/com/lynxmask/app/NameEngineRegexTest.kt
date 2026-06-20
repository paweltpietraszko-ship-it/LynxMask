package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

/**
 * Testy jednostkowe wzorców regex w NameEngine.
 * Nie wymagają LookupTables — testują wzorce bezpośrednio.
 */
class NameEngineRegexTest {

    // ── STREET_CANDIDATE_REGEX ────────────────────────────────────────────

    @Test
    fun `STREET_CANDIDATE_REGEX nie powinien wciagac newline do numeru budynku`() {
        // Bug: [/\s] w grupie numeru domu pasuje do '\n'
        // Dla "Długa 80\n41-200 Bytom" regex dopasowuje "80\n41" —
        // wciągając nową linię i pierwsze cyfry kodu pocztowego.
        // Token ADRES dostaje wartość "Długa 80\n41", co powoduje że
        // benchmark norm() nie może dopasować do GT "ul. Długa 80, 41-200 Bytom".
        val input = "Długa 80\n41-200 Bytom"
        val match = STREET_CANDIDATE_REGEX.find(input)
        assertNotNull("Regex powinien znaleźć ulicę z numerem", match)
        assertEquals(
            "Numer budynku nie powinien zawierać znaku nowej linii (bug: [/\\s] → zamień na [/[^\\S\\n]])",
            "80",
            match!!.groupValues[2]
        )
    }

    @Test
    fun `STREET_CANDIDATE_REGEX dopasowuje adres z ulamkowym numerem budynku`() {
        // Regresja: numer z ukośnikiem (80/12) powinien być wykrywany jako "80/12"
        val input = "Szkolna 80/12 coś"
        val match = STREET_CANDIDATE_REGEX.find(input)
        assertNotNull("Regex powinien znaleźć ulicę z numerem ułamkowym", match)
        assertEquals("80/12", match!!.groupValues[2])
    }

    @Test
    fun `STREET_CANDIDATE_REGEX dopasowuje ulicę dwuczłonową`() {
        // Regresja: ulica dwuczłonowa (Jana Pawła) powinna być wykrywana
        val input = "Jana Pawła 15"
        val match = STREET_CANDIDATE_REGEX.find(input)
        assertNotNull("Regex powinien znaleźć ulicę dwuczłonową", match)
        assertEquals("15", match!!.groupValues[2])
    }
}
