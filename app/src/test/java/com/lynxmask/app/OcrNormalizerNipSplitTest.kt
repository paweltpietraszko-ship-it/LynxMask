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

    @Test
    fun `naprawa NIP z newline w srodku segmentu`() {
        val input = "NIP: 740-617\n82-26"
        val result = OcrNormalizer.normalize(input)
        assertTrue("newline → format 3-3-2-2", result.normalizedText.contains("740-617-82-26"))
        assertFalse("newline w srodku NIP powinien byc usuniety", result.normalizedText.contains("740-617\n82-26"))
        assertFalse("nie sklejaj segmentow bez myslnika", result.normalizedText.contains("740-61782-26"))
    }
}
