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
}
