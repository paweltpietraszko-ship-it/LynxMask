package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

class OcrNormalizerPeselSplitTest {

    @Test
    fun `naprawa PESEL ze spacja w srodku numeru`() {
        val input = "PESEL: 6802041 8568"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("spacja w PESEL nie usunieta", result.normalizedText.contains("68020418568"))
    }

    @Test
    fun `naprawa PESE1 ze spacjami w numerze`() {
        val input = "PESE1: 900 10874 140"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("spacja w PESE1 nie usunieta", result.normalizedText.contains("90010874140"))
    }

    @Test
    fun `naprawa pesel male litery ze spacja`() {
        val input = "pesel 75120 934634"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("spacja w pesel (male litery) nie usunieta", result.normalizedText.contains("75120934634"))
    }

    @Test
    fun `nie naprawia liczby poza kontekstem PESEL`() {
        val input = "numer 6802041 8568"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("nie powinno zmieniac poza kontekstem PESEL", input, result.normalizedText)
    }

    @Test
    fun `poprawny PESEL bez spacji pozostaje bez zmian`() {
        val input = "PESEL: 68020418568"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("poprawny PESEL nie powinien byc zmieniany", input, result.normalizedText)
    }
}
