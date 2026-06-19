package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

class OcrNormalizerNumericTest {

    @Test
    fun `naprawa NIP z kreskami`() {
        val input = "NIP: 4I5-II2-22-69"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("NIP nie naprawiony", result.normalizedText.contains("415-112-22-69"))
    }

    @Test
    fun `naprawa NIP bez kresek`() {
        val input = "NlP: 9S710G6250"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("NIP nie naprawiony", result.normalizedText.contains("9571066250"))
    }

    @Test
    fun `naprawa REGON 9 cyfr`() {
        val input = "REGON: 36OI4S219"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("REGON nie naprawiony", result.normalizedText.contains("360145219"))
    }

    @Test
    fun `naprawa IBAN`() {
        val input = "IBAN: PLG2 I240 IO13 S9II 64O7 2345 96O3"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("IBAN nie naprawiony", result.normalizedText.contains("PL62 1240 1013 5911 6407 2345 9603"))
    }
}
