package com.lynxmask.app

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class OcrNormalizerSurnameTest {

    @Before
    fun setup() {
        LookupTables.initializeForTesting()
    }

    @After
    fun teardown() {
        LookupTables.resetForTesting()
    }

    @Test
    fun `S6 Kowal ski sklejony do Kowalski`() {
        val input = "Zleceniodawca: Jan Kowal ski, PESEL"
        val result = OcrNormalizer.normalize(input)
        assertTrue("Kowal ski powinno być sklejone", result.normalizedText.contains("Kowalski"))
        assertFalse("Nie może zostać 'Kowal ski'", result.normalizedText.contains("Kowal ski"))
        assertTrue("Powinien być co najmniej 1 correction", result.corrections >= 1)
    }

    @Test
    fun `S6 Kowal skiego dopelniacz sklejony`() {
        val input = "dane: Kowal skiego Jana"
        val result = OcrNormalizer.normalize(input)
        assertTrue("Kowalskiego powinno być sklejone", result.normalizedText.contains("Kowalskiego"))
    }

    @Test
    fun `S6 Malinow ski sklejony do Malinowski`() {
        val input = "pracownik: Adam Malinow ski"
        val result = OcrNormalizer.normalize(input)
        assertTrue("Malinowski powinno być sklejone", result.normalizedText.contains("Malinowski"))
    }

    @Test
    fun `S6 nie skleja slow których nie ma w słowniku`() {
        val input = "Zawarta w umowie strony"
        val result = OcrNormalizer.normalize(input)
        assertEquals("Tekst bez nazwisk nie powinien być zmieniony", input, result.normalizedText)
    }

    @Test
    fun `S6 nie skleja gdy prawa część ma wielką literę`() {
        // "Jan Ski" — "Ski" zaczyna się wielką = nowe słowo, NIE fragment nazwiska
        val input = "Jan Ski Jones"
        val result = OcrNormalizer.normalize(input)
        assertEquals("Wielka litera w prawej części = nie skleja", input, result.normalizedText)
    }

    // ── OCR_NAME_L_AS_I (12.07, diagnoza Cursor, BUG-KAMLNSKA-REGRESJA) ─────────────

    @Test
    fun `S6b litera l zamiast i w nazwisku naprawiana gdy odblokowuje slownik`() {
        // "Malinowskl" (OCR: ostatnie 'i' -> 'l') — "malinowski" jest w fixture testowej.
        val result = OcrNormalizer.normalize("Zleceniobiorca: Malinowskl, PESEL")
        assertTrue("Malinowskl powinno być naprawione do Malinowski",
            result.normalizedText.contains("Malinowski"))
        assertFalse("Nie może zostać błędna litera 'l' na końcu",
            result.normalizedText.contains("Malinowskl"))
    }

    @Test
    fun `S6b nie rusza slowa ktore juz jest poprawnym nazwiskiem z litera l`() {
        val input = "Zleceniobiorca: Kowalski, PESEL"
        val result = OcrNormalizer.normalize(input)
        assertEquals("Kowalski (już poprawne, ma prawdziwe 'l') nie powinno być zmienione",
            input, result.normalizedText)
    }

    @Test
    fun `S6b nie zgaduje gdy podstawienie nie daje trafienia w slowniku`() {
        val input = "Zwykle slowo bez nazwiska w tekscie"
        val result = OcrNormalizer.normalize(input)
        assertEquals("Brak trafienia w słowniku po podstawieniu = bez zmian",
            input, result.normalizedText)
    }
}
