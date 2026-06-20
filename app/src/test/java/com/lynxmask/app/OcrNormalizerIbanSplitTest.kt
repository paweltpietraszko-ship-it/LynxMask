package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

/**
 * Testy OCR_IBAN_SPLIT — naprawa spacji wstawionej przez OCR wewnątrz numeru IBAN.
 * doc_00057: "PL02114019872105222748309 170" → "PL02114019872105222748309170"
 */
class OcrNormalizerIbanSplitTest {

    @Test
    fun `OCR_IBAN_SPLIT naprawa spacji przed ostatnimi cyframi IBAN`() {
        // Bug: StructuralEngine IBAN regex nie obsługuje podziału X + spacja + XXX
        // gdy granica podziału nie pokrywa się z grupami po 4 cyfry.
        // "PL02114019872105222748309 170" — OCR wstawił spację przed ostatnimi 3 cyframi.
        // Bez OCR_IBAN_SPLIT: strukturalny wzorzec PL\d{2}(?:\d{4}){6} nie pasuje.
        val input = "Nr konta: PL02114019872105222748309 170"
        val result = OcrNormalizer.normalize(input)
        assertTrue(
            "IBAN po normalizacji powinien być ciągły: PL02114019872105222748309170",
            result.normalizedText.contains("PL02114019872105222748309170")
        )
        assertFalse(
            "Spacja w środku IBAN powinna być usunięta",
            result.normalizedText.contains("9 170")
        )
    }

    @Test
    fun `OCR_IBAN_SPLIT naprawa gołego IBAN bez prefiksu kontekstowego`() {
        // IBAN bez "Nr konta:" — tylko surowy numer
        val input = "PL02114019872105222748309 170"
        val result = OcrNormalizer.normalize(input)
        assertTrue(
            "Surowy IBAN ze spacją powinien być naprawiony",
            result.normalizedText.contains("PL02114019872105222748309170")
        )
    }

    @Test
    fun `OCR_IBAN_SPLIT nie skleja gdy suma cyfr nie wynosi 26`() {
        // Ochrona przed false-positive: "PL123 456" to nie IBAN (6 cyfr ≠ 26)
        val input = "PL123 456"
        val result = OcrNormalizer.normalize(input)
        assertEquals("Krotki numer nie powinien byc sklejony", input, result.normalizedText)
    }

    @Test
    fun `OCR_IBAN_SPLIT nie modyfikuje poprawnego IBAN bez spacji`() {
        // Poprawny IBAN bez spacji — nie powinien być dotknięty
        val input = "PL02114019872105222748309170"
        val result = OcrNormalizer.normalize(input)
        assertTrue("Poprawny IBAN powinien pozostac niezmieniony",
            result.normalizedText.contains("PL02114019872105222748309170"))
    }
}
