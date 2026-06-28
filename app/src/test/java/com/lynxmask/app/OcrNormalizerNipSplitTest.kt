package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

class OcrNormalizerNipSplitTest {

    @Test
    fun `naprawa NIP ze spacja w srodku segmentu`() {
        val input = "NIP: 740-61 7-82-26"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("spacja w NIP nie usunieta", result.normalizedText.contains("740-617-82-26"))
    }

    @Test
    fun `naprawa NlP ze spacja w srodku segmentu`() {
        val input = "NlP: 853-14 1-09-30"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("spacja w NlP nie usunieta", result.normalizedText.contains("853-141-09-30"))
    }

    @Test
    fun `naprawa NIP ze spacjami w roznych miejscach`() {
        // "521-3 3-1 5-332" — spacje wewnątrz dwóch segmentów, myślniki na miejscu
        val input = "NIP 521-3 3-1 5-332"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("spacje w NIP nie usuniete", result.normalizedText.contains("521-33-15-332"))
    }

    @Test
    fun `poprawny NIP bez zmian`() {
        val input = "NIP: 740-617-82-26"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("poprawny NIP nie powinien byc zmieniany", input, result.normalizedText)
    }

    @Test
    fun `nie naprawia NIP poza kontekstem`() {
        val input = "numer faktury 740-61 7-82-26"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("nie powinno zmieniac poza kontekstem NIP", input, result.normalizedText)
    }

    // ── Testy NIP z modyfikatorem słownym (sesja 28.06) ────────────────────────

    @Test
    fun `garbled NIP nabywcy naprawiany — I→1 O→0 S→5`() {
        val input = "NIP nabywcy: 45I-OS2-35-26"
        val result = OcrNormalizer.normalize(input)
        assertTrue(
            "45I-OS2 nie naprawione: ${result.normalizedText}",
            result.normalizedText.contains("451-052-35-26")
        )
    }

    @Test
    fun `garbled NIP swiadka naprawiany — II2→112`() {
        val input = "NIP świadka: 415-II2-22-69"
        val result = OcrNormalizer.normalize(input)
        assertTrue(
            "II2 nie naprawione: ${result.normalizedText}",
            result.normalizedText.contains("415-112-22-69")
        )
    }

    @Test
    fun `garbled NIP sprzedawcy naprawiany — O→0`() {
        val input = "NIP sprzedawcy: 526-030-O6-38"
        val result = OcrNormalizer.normalize(input)
        assertTrue(
            "O6 nie naprawione: ${result.normalizedText}",
            result.normalizedText.contains("526-030-06-38")
        )
    }

    @Test
    fun `modyfikator zachowany po naprawie`() {
        val input = "NIP nabywcy: 45I-OS2-35-26"
        val result = OcrNormalizer.normalize(input)
        assertTrue(
            "Prefix 'NIP nabywcy:' powinien byc zachowany: ${result.normalizedText}",
            result.normalizedText.startsWith("NIP nabywcy:")
        )
    }

    @Test
    fun `naprawa NIP z newline w srodku segmentu`() {
        val input = "NIP: 740-617\n82-26"
        val result = OcrNormalizer.normalize(input)
        assertTrue("newline → format 3-3-2-2", result.normalizedText.contains("740-617-82-26"))
        assertFalse("newline w srodku NIP powinien byc usuniety", result.normalizedText.contains("740-617\n82-26"))
        assertFalse("nie sklejaj segmentow bez myslnika", result.normalizedText.contains("740-61782-26"))
    }
}
