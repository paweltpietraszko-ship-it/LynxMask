package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

class OcrNormalizerPeselTest {

    @Test
    fun `naprawa T na 7 w PESEL`() {
        val input = "PESEL T2030375656"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("PESEL 72030375656", result.normalizedText)
    }

    @Test
    fun `naprawa PESEL z dwukropkiem`() {
        val input = "PESEL: T2030375656"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("PESEL: 72030375656", result.normalizedText)
    }

    @Test
    fun `naprawa wielu liter OCR w PESEL`() {
        val input = "PESEL: 9O1322O23IS"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("PESEL: 90132202315", result.normalizedText)
    }

    @Test
    fun `nie zmienia poprawnego PESEL`() {
        val input = "PESEL 90030375656"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("PESEL 90030375656", result.normalizedText)
    }
}
