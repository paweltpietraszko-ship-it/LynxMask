package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

class OcrNormalizerPostalSpaceTest {

    @Test
    fun `OCR_POSTAL_SPACE spacja zamiast myslnika w kodzie po przecinku`() {
        val r = OcrNormalizer.normalize("ul. Wolności 99, 41 200 Sosnowiec")
        assertTrue("41 200 → 41-200", r.normalizedText.contains("41-200"))
    }

    @Test
    fun `OCR_POSTAL_SPACE z wielka litera polskiego miasta`() {
        val r = OcrNormalizer.normalize("al. Niepodległości 13/2, 65 001 Gliwice")
        assertTrue("65 001 → 65-001", r.normalizedText.contains("65-001"))
    }

    @Test
    fun `OCR_POSTAL_SPACE kilka spacji w kodzie`() {
        val r = OcrNormalizer.normalize("ul. Długa 72, 70  001 Rybnik")
        assertTrue("70  001 → 70-001", r.normalizedText.contains("70-001"))
    }

    @Test
    fun `OCR_POSTAL_SPACE brak FP dla kwoty bez przecinka`() {
        val r = OcrNormalizer.normalize("cena wynosi 41 200 złotych")
        assertFalse("kwota bez przecinka – brak naprawy", r.normalizedText.contains("41-200"))
    }

    @Test
    fun `OCR_POSTAL_SPACE brak FP dla numeru telefonu`() {
        val r = OcrNormalizer.normalize("tel 41 200 300")
        assertFalse("telefon – brak naprawy", r.normalizedText.contains("41-200"))
    }
}
