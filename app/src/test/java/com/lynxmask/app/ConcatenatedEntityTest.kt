package com.lynxmask.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Testy maskowania encji sklejonych bez separatora — regresja sesja 28.06.
 *
 * Przypadki:
 *  - IBAN bezpośrednio poprzedzający sufiks alfanumeryczny (fix: usunięcie \b z StructuralEngine)
 *  - IBAN z prefixem OCR bez separatora po nim (fix: usunięcie (?!\w) z OCR_IBAN_DIGITS)
 *  - NIP z garbled digits (O→0) przez OCR
 *  - PESEL na początku linii bez spacji przed słowem kluczowym
 */
class ConcatenatedEntityTest {

    @Before
    fun setup() {
        LookupTables.initializeForTesting()
        resetRegexCache()
    }

    // Regresja: StructuralEngine kończył dopasowanie IBAN po 5 grupach (przed "4517")
    // gdy następna cyfra była bezpośrednio sklejona. Fix: usunięcie \b na końcu wzorca.
    @Test
    fun `IBAN sklejony z sufiksem alfanumerycznym jest maskowany w calosci`() {
        val input = "IBAN: PL87150010131625519045176SSTNUMER_005"
        val result = PseudonymEngine.pseudonymize(input, emptyList())
        assertFalse(
            "Cyfry 4517 widoczne — IBAN nie zamaskowany w całości\nWynik: ${result.pseudonymizedText}",
            result.pseudonymizedText.contains("4517")
        )
        assertFalse(
            "Cyfry 1625 widoczne — IBAN nie zamaskowany\nWynik: ${result.pseudonymizedText}",
            result.pseudonymizedText.contains("1625")
        )
    }

    // OCR_IBAN_DIGITS: prefix "IBAN:" z numerem sklejonym z kolejnym tokenem.
    // Fix: usunięcie lookahead (?!\w) z OCR_IBAN_DIGITS — normalizacja uruchamia się
    // nawet gdy IBAN przylega bez separatora.
    @Test
    fun `IBAN z prefixem OCR sklejony z tokenem jest normalizowany`() {
        val input = "Nr konta: PL87150010131625519045176SSTNUMER_005"
        val normalized = OcrNormalizer.normalize(input)
        // Po normalizacji IBAN powinien być ciągły (bez sklejonych liter na końcu)
        // lub przynajmniej rozpoznany jako blok cyfr
        val r = PseudonymEngine.pseudonymize(normalized.normalizedText, emptyList())
        assertFalse(
            "4517 widoczne po normalizacji i pseudonimizacji\nNorm: ${normalized.normalizedText}\nWynik: ${r.pseudonymizedText}",
            r.pseudonymizedText.contains("4517")
        )
    }

    // NIP z garbled digits przez OCR: O→0, S→5 — normalizer musi odwzorować i wykryć NIP
    @Test
    fun `NIP z garbled digits OCR jest normalizowany`() {
        val input = "NIP: 526-O3O-O1-34"
        val normalized = OcrNormalizer.normalize(input)
        assertTrue(
            "O→0 nie działa: ${normalized.normalizedText}",
            normalized.normalizedText.contains("526-030-01-34") ||
                normalized.normalizedText.contains("5260300134") ||
                normalized.normalizedText.replace("-", "").contains("5260300134")
        )
    }

    // PESEL sklejony z poprzednim słowem (regresja DOCX — brak \n między paragrafami)
    @Test
    fun `PESEL po slowoNazwisko bez spacji jest maskowany`() {
        val input = "JabłońskiPESEL:90090515836"
        val result = PseudonymEngine.pseudonymize(input, emptyList())
        assertFalse(
            "PESEL 90090515836 widoczny\nWynik: ${result.pseudonymizedText}",
            result.pseudonymizedText.contains("90090515836")
        )
    }

    // PESEL ze spacjami OCR (cyfry rozdzielone spacją) — normalizer powinien złożyć w całość
    @Test
    fun `PESEL z lukami OCR jest normalizowany i maskowany`() {
        val input = "PESEL: 90 090 515 836"
        val result = PseudonymEngine.pseudonymize(input, emptyList())
        assertFalse(
            "PESEL z lukami widoczny\nWynik: ${result.pseudonymizedText}",
            result.pseudonymizedText.contains("90090515836") &&
                !result.tokenMap.any { it.key.startsWith("NUMER_") }
        )
    }

    // OCR_HOUSE_NUM: I→1, O→0 w numerze budynku po ul./al. (sesja 28.06)
    @Test
    fun `garbled numer budynku I35 normalizowany do 135`() {
        val input = "ul. Niepodleglości I35, 20-100 Rybnik"
        val result = OcrNormalizer.normalize(input)
        assertTrue(
            "I35 nie naprawiony: ${result.normalizedText}",
            result.normalizedText.contains("135,")
        )
        assertFalse("I35 nadal widoczny", result.normalizedText.contains("I35"))
    }

    @Test
    fun `garbled numer budynku I9 normalizowany do 19`() {
        val input = "ul. Sloneczna I9"
        val result = OcrNormalizer.normalize(input)
        assertTrue(
            "I9 nie naprawiony: ${result.normalizedText}",
            result.normalizedText.contains("19")
        )
        assertFalse("I9 nadal widoczny", result.normalizedText.contains(" I9"))
    }

    @Test
    fun `garbled numer budynku I2O normalizowany do 120`() {
        val input = "ul. Kopernka I2O,"
        val result = OcrNormalizer.normalize(input)
        assertTrue(
            "I2O nie naprawiony: ${result.normalizedText}",
            result.normalizedText.contains("120")
        )
    }

    @Test
    fun `poprawny numer budynku nie jest modyfikowany`() {
        val input = "ul. Kwiatowa 15A"
        val result = OcrNormalizer.normalize(input)
        assertEquals("Poprawny adres nie powinien byc zmieniany", input, result.normalizedText)
    }

    // Dwa różne numery na tej samej linii bez separatora — oba powinny być flagowane lub zamaskowane
    @Test
    fun `dwa sklejone numery na jednej linii sa wykrywane`() {
        // NIP + PESEL sklejone — przynajmniej Guard powinien to zasygnalizować
        val input = "5260300134 90090515836"
        val result = PseudonymEngine.pseudonymize(input, emptyList())
        val hasTokens = result.tokenMap.isNotEmpty()
        val hasGuardHits = result.guardHits.isNotEmpty()
        assertTrue(
            "Żadna z wartości nie wykryta\nTokens: ${result.tokenMap}\nGuard: ${result.guardHits}",
            hasTokens || hasGuardHits
        )
    }
}
