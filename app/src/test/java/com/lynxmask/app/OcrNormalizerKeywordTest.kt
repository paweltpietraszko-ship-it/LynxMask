package com.lynxmask.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Krok 0 — keyword canonicalization (v2.6).
 * Zdegradowany keyword → PESEL/NIP/REGON, potem naprawa cyfr w kolejnych krokach.
 */
class OcrNormalizerKeywordTest {

    @Test
    fun `P3SEL kanonizuje do PESEL i naprawia cyfry`() {
        val input = "P3SEL: 80012312349"
        val result = OcrNormalizer.normalize(input)
        assertTrue(result.normalizedText.startsWith("PESEL:"))
        assertTrue(result.normalizedText.contains("80012312349"))
    }

    @Test
    fun `PESE1 kanonizuje do PESEL`() {
        val input = "PESE1: 72030375656"
        val result = OcrNormalizer.normalize(input)
        assertEquals("PESEL: 72030375656", result.normalizedText)
    }

    @Test
    fun `PE5E1 kanonizuje do PESEL i naprawia T w numerze`() {
        val input = "PE5E1: T2030375656"
        val result = OcrNormalizer.normalize(input)
        assertEquals("PESEL: 72030375656", result.normalizedText)
    }

    @Test
    fun `PESE pipe kanonizuje do PESEL`() {
        val input = "PESE|: 72030375656"
        val result = OcrNormalizer.normalize(input)
        assertEquals("PESEL: 72030375656", result.normalizedText)
    }

    @Test
    fun `P E5EL ze spacja kanonizuje do PESEL`() {
        val input = "P E5EL: 80012312345"
        val result = OcrNormalizer.normalize(input)
        assertEquals("PESEL: 80012312345", result.normalizedText)
    }

    @Test
    fun `PB5EL kanonizuje do PESEL`() {
        val input = "PB5EL: 80012312345"
        val result = OcrNormalizer.normalize(input)
        assertEquals("PESEL: 80012312345", result.normalizedText)
    }

    @Test
    fun `NlP kanonizuje do NIP i naprawia cyfry`() {
        val input = "NlP: 526-O3O-O1-34"
        val result = OcrNormalizer.normalize(input)
        assertEquals("NIP: 526-030-01-34", result.normalizedText)
    }

    @Test
    fun `NtP kanonizuje do NIP`() {
        val input = "NtP: 526-000-13-29"
        val result = OcrNormalizer.normalize(input)
        assertTrue(result.normalizedText.startsWith("NIP:"))
        assertTrue(result.normalizedText.contains("526-000-13-29"))
    }

    @Test
    fun `N pipe P kanonizuje do NIP`() {
        val input = "N|P: 526-000-13-29"
        val result = OcrNormalizer.normalize(input)
        assertTrue(result.normalizedText.startsWith("NIP:"))
    }

    @Test
    fun `REGON z O jako zero kanonizuje`() {
        val input = "R3G0N: 123456785"
        val result = OcrNormalizer.normalize(input)
        assertTrue(result.normalizedText.startsWith("REGON:"))
        assertTrue(result.normalizedText.contains("123456785"))
    }

    @Test
    fun `nie kanonizuje NAP jako NIP`() {
        val input = "NAP rawno-legalityczny"
        val result = OcrNormalizer.normalize(input)
        assertEquals(input, result.normalizedText)
    }
}
