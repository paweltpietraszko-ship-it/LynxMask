package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

/**
 * Testy OCR_DIGIT_IN_CONTEXT (krok 14) + rozszerzeń OCR_PESEL_SPLIT i OCR_IBAN_SPLIT (v2.0).
 * Scenariusz: 'l' lub 'O' wśród cyfr — OCR myli litery z cyframi.
 */
class OcrNormalizerDigitContextTest {

    // ── OCR_DIGIT_IN_CONTEXT ────────────────────────────────────────────────────

    @Test
    fun `OCR_DIGIT_IN_CONTEXT pojedyncze l miedzy cyframi`() {
        val result = OcrNormalizer.normalize("wartość 9l04")
        assertTrue("'l' między cyframi powinno być '1'",
            result.normalizedText.contains("9104"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT podwojne ll miedzy cyframi`() {
        val result = OcrNormalizer.normalize("kod 52ll0732")
        assertTrue("'ll' między cyframi powinno być '11'",
            result.normalizedText.contains("52110732"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT O miedzy cyframi`() {
        val result = OcrNormalizer.normalize("numer 7408O934")
        assertTrue("'O' między cyframi powinno być '0'",
            result.normalizedText.contains("74080934"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT nie zmienia l w srodku slowa`() {
        // 'l' w słowie "Kowalski" — nie otoczone cyframi → bez zmian
        val result = OcrNormalizer.normalize("Kowalski")
        assertEquals("słowo bez cyfr nie powinno być zmienione",
            "Kowalski", result.normalizedText)
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT naprawia l przez spacje gdy po spacji cyfra`() {
        // v2.1: 'l' po cyfrze + spacja + cyfra → naprawia (kontekst liczby z grupami)
        // "60l 234 567" → "601 234 567" (np. numer telefonu rozbity przez OCR)
        val result = OcrNormalizer.normalize("tel: 60l 234 567")
        assertTrue("'l' przed spacją+cyfrą powinno być konwertowane",
            result.normalizedText.contains("601 234"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT nie zmienia gdy po spacji litera`() {
        // "3l abc" — po spacji litera, nie cyfra → bez zmian
        val result = OcrNormalizer.normalize("poz. 3l abc")
        assertFalse("'l' przed spacją+literą nie powinno być konwertowane",
            result.normalizedText.contains("31 abc"))
    }

    @Test
    fun `OCR_DIGIT_IN_CONTEXT naprawia O w grupie IBAN przed spacja`() {
        // "325O 0003" → "3250 0003" — 'O' na końcu grupy cyfr IBAN (v2.1)
        val input = "Nr konta: PL03 325O 0003 6637 2867 44O1 4154"
        val result = OcrNormalizer.normalize(input)
        assertTrue("'O' na końcu grupy IBAN powinno być '0'",
            result.normalizedText.contains("3250 0003"))
    }

    // ── OCR_PESEL_SPLIT v2.0 — l/O + spacja ────────────────────────────────────

    @Test
    fun `OCR_PESEL_SPLIT v2 naprawa l i spacja w PESEL`() {
        val input = "PESEL: 9l0405 l2367"
        val result = OcrNormalizer.normalize(input)
        assertTrue("'9l0405 l2367' powinno być naprawione do '91040512367'",
            result.normalizedText.contains("91040512367"))
    }

    @Test
    fun `OCR_PESEL_SPLIT v2 naprawa l i spacja PESEL2`() {
        val input = "PE5EL: 7408l934 521"
        val result = OcrNormalizer.normalize(input)
        assertTrue("'7408l934 521' powinno być naprawione do '74081934521'",
            result.normalizedText.contains("74081934521"))
    }

    @Test
    fun `OCR_PESEL_SPLIT v2 wsteczna kompatybilnosc tylko spacja`() {
        // Istniejące przypadki z samymi spacjami nadal działają
        val input = "PESEL: 6802041 8568"
        val result = OcrNormalizer.normalize(input)
        assertTrue("Spacja w PESEL (bez liter) nadal naprawiana",
            result.normalizedText.contains("68020418568"))
    }

    // ── OCR_IBAN_SPLIT v2.0 — l/O + spacja ─────────────────────────────────────

    @Test
    fun `OCR_IBAN_SPLIT v2 naprawa l i spacja w IBAN`() {
        val input = "IBAN: PL89l090l0l474495552 52ll0732"
        val result = OcrNormalizer.normalize(input)
        assertTrue("IBAN z 'l' i spacją powinien być naprawiony",
            result.normalizedText.contains("PL89109010147449555252110732"))
    }

    @Test
    fun `OCR_IBAN_SPLIT v2 wsteczna kompatybilnosc tylko spacja`() {
        // Istniejące przypadki z samymi spacjami nadal działają
        val input = "PL02114019872105222748309 170"
        val result = OcrNormalizer.normalize(input)
        assertTrue("Spacja w IBAN (bez liter) nadal naprawiana",
            result.normalizedText.contains("PL02114019872105222748309170"))
    }

    @Test
    fun `OCR_IBAN_SPLIT v2 nie skleja gdy suma nie wynosi 26`() {
        val input = "PL89l090 52ll"
        val result = OcrNormalizer.normalize(input)
        assertFalse("Krótki numer z literami nie powinien być sklejony",
            result.normalizedText.contains("PL89109052110"))
    }
}
