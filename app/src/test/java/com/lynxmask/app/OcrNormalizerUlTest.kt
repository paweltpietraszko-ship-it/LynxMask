package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

class OcrNormalizerUlTest {

    @Test
    fun `naprawa u kropka Nazwa`() {
        val input = "Adres: u. Różana 31, 70-001 Łódź"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("Brak naprawy u. → ul.", result.normalizedText.contains("ul. Różana"))
    }

    @Test
    fun `naprawa u spacja Nazwa`() {
        val input = "zamieszkały u Szkolna 180, 87-100 Rybnik"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("Brak naprawy u → ul.", result.normalizedText.contains("ul. Szkolna"))
    }

    @Test
    fun `nie naprawia srodka slowa`() {
        val input = "ustawy z dnia 14 czerwca"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("Nie powinno zmieniać środka słowa", input, result.normalizedText)
    }

    @Test
    fun `nie naprawia skrotu ul juz poprawnego`() {
        val input = "ul. Krakowska 71, 30-001 Gdynia"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("Nie powinno zmieniać poprawnego ul.", input, result.normalizedText)
    }
}
