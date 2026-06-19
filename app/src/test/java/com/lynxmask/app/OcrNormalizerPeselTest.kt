package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

class OcrNormalizerPeselTest {

    @Test
    fun `naprawa PESEL z T na 7`() {
        val input = "PESEL T2030375656"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue(result.normalizedText.contains("72030375656"))
    }

    @Test
    fun `naprawa PESE1 z cyfra zamiast L`() {
        val input = "PESE1: 72030375656"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("PESE1 nie rozpoznany", result.normalizedText.contains("72030375656"))
    }

    @Test
    fun `naprawa PE5EL z cyfra zamiast S`() {
        val input = "PE5EL: 80012312345"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("PE5EL nie rozpoznany", result.normalizedText.contains("80012312345"))
    }

    @Test
    fun `naprawa P E S E L ze spacjami miedzy literami`() {
        val input = "P E S E L: 80012312345"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("P E S E L nie rozpoznany", result.normalizedText.contains("80012312345"))
    }

    @Test
    fun `naprawa PEB3L z wieloma bledami OCR`() {
        val input = "PEB3L 8OOI23I2345"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("PEB3L nie rozpoznany", result.normalizedText.contains("80012312345"))
    }

    @Test
    fun `naprawa pesel male litery`() {
        val input = "pesel: T2030375656"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("pesel małe nie rozpoznany", result.normalizedText.contains("72030375656"))
    }

    @Test
    fun `nie naprawia liczb bez kontekstu PESEL`() {
        val input = "numer faktury T2030375656"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertEquals("nie powinno zmieniać poza kontekstem", input, result.normalizedText)
    }

    @Test
    fun `naprawa PE5EL z bledna cyfra i blednym numerem`() {
        val input = "PE5EL: T2030375656"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("PE5EL z błędnym numerem nie naprawiony", result.normalizedText.contains("72030375656"))
    }

    @Test
    fun `naprawa P E S E L ze spacjami i blednym numerem`() {
        val input = "P E S E L 8OO12312345"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("P E S E L z błędnym numerem nie naprawiony", result.normalizedText.contains("80012312345"))
    }
}
