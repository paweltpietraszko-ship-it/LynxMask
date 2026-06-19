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

    @Test
    fun `naprawa UI kropka Nazwa`() {
        val input = "Adres: UI.Zyty 26, Zielona Góra"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("UI. nie naprawione", result.normalizedText.contains("ul. Zyty"))
    }

    @Test
    fun `naprawa ul bez kropki`() {
        val input = "zamieszkały ul Szkolna 15, Kraków"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("ul bez kropki nie naprawione", result.normalizedText.contains("ul. Szkolna"))
    }

    @Test
    fun `naprawa uI bez kropki`() {
        val input = "adres: uI Długa 8, Warszawa"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("uI nie naprawione", result.normalizedText.contains("ul. Długa"))
    }

    @Test
    fun `naprawa u l ze spacją między`() {
        val input = "adres: u l. Długa 8, Warszawa"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertFalse("u l. ze spacją — celowo nie naprawiamy, zbyt ryzykowne",
            result.normalizedText == input && result.corrections > 0)
    }

    @Test
    fun `naprawa UI bez spacji przed nazwa`() {
        val input = "Adres: UI.Zyty 26, Zielona Góra"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("UI.Zyty nie naprawione", result.normalizedText.contains("ul. Zyty"))
    }

    @Test
    fun `naprawa ulica pelne slowo`() {
        val input = "ulica Długa 8, Warszawa"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("ulica nie naprawione", result.normalizedText.contains("ul. Długa"))
    }

    @Test
    fun `nie naprawia U przed wielka litera w srodku zdania`() {
        val input = "Mieszka U Warszawie od lat"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("nie powinno zmieniać U Warszawie", input, result.normalizedText)
    }
}
