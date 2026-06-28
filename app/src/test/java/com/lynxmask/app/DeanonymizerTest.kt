package com.lynxmask.app

import org.junit.Assert.*
import org.junit.Test

class DeanonymizerTest {

    // ── restore() ─────────────────────────────────────────────────────────────

    @Test
    fun restore_singleToken_replacedWithOriginal() {
        val tokenMap = mapOf("OSOBA_001" to "Jan Kowalski")
        val result = Deanonymizer.restore("Pismo dotyczy OSOBA_001.", tokenMap)
        assertEquals("Pismo dotyczy Jan Kowalski.", result)
    }

    @Test
    fun restore_multipleTokens_allReplaced() {
        val tokenMap = mapOf(
            "OSOBA_001" to "Anna Nowak",
            "NUMER_001" to "600 123 456",
            "EMAIL_001" to "anna@example.com"
        )
        val input = "OSOBA_001 tel. NUMER_001 email EMAIL_001"
        val result = Deanonymizer.restore(input, tokenMap)
        assertEquals("Anna Nowak tel. 600 123 456 email anna@example.com", result)
    }

    @Test
    fun restore_tokenWithSpecialChars_replacedCorrectly() {
        val tokenMap = mapOf("FIRMA_001" to "Sp. z o.o. \"Alfa\"")
        val result = Deanonymizer.restore("Firma: FIRMA_001.", tokenMap)
        assertEquals("Firma: Sp. z o.o. \"Alfa\".", result)
    }

    @Test
    fun restore_tokenWithBackslashAndNewline_replacedCorrectly() {
        val tokenMap = mapOf("ADRES_001" to "ul. Lipowa\n14")
        val result = Deanonymizer.restore("Adres: ADRES_001", tokenMap)
        assertEquals("Adres: ul. Lipowa\n14", result)
    }

    @Test
    fun restore_emptyTokenMap_returnsOriginalText() {
        val result = Deanonymizer.restore("Tekst bez tokenów.", emptyMap())
        assertEquals("Tekst bez tokenów.", result)
    }

    @Test
    fun restore_unknownToken_notSubstituted() {
        val tokenMap = mapOf("OSOBA_001" to "Jan Kowalski")
        val result = Deanonymizer.restore("Tekst OSOBA_002 reszta.", tokenMap)
        assertEquals("Tekst OSOBA_002 reszta.", result)
    }

    @Test
    fun restore_tokenAppearsMultipleTimes_allOccurrencesReplaced() {
        val tokenMap = mapOf("OSOBA_001" to "Kowalski")
        val result = Deanonymizer.restore("OSOBA_001 i jeszcze raz OSOBA_001.", tokenMap)
        assertEquals("Kowalski i jeszcze raz Kowalski.", result)
    }

    // ── detectSessionId() ─────────────────────────────────────────────────────

    @Test
    fun detectSessionId_validPattern_returnsId() {
        val text = "Treść z tokenem SESJA_AB12CD i dalej."
        assertEquals("AB12CD", Deanonymizer.detectSessionId(text))
    }

    @Test
    fun detectSessionId_noPattern_returnsNull() {
        assertNull(Deanonymizer.detectSessionId("Tekst bez identyfikatora sesji."))
    }

    @Test
    fun detectSessionId_lowercasePattern_notMatched() {
        // Pattern wymaga [A-Z0-9] — małe litery nie pasują
        assertNull(Deanonymizer.detectSessionId("sesja_ab12cd"))
    }

    // ── extractTokens() ───────────────────────────────────────────────────────

    @Test
    fun extractTokens_mixedTokenTypes_allFound() {
        val text = "OSOBA_001 i FIRMA_002 oraz NUMER_003 i EMAIL_004 i KWOTA_005 i ADRES_006."
        val tokens = Deanonymizer.extractTokens(text)
        assertEquals(
            setOf("OSOBA_001", "FIRMA_002", "NUMER_003", "EMAIL_004", "KWOTA_005", "ADRES_006"),
            tokens
        )
    }

    @Test
    fun extractTokens_noTokens_returnsEmptySet() {
        assertTrue(Deanonymizer.extractTokens("Normalny tekst bez tokenów.").isEmpty())
    }

    @Test
    fun extractTokens_duplicateTokens_returnedOnce() {
        val text = "OSOBA_001 napisał do OSOBA_001 ponownie."
        assertEquals(setOf("OSOBA_001"), Deanonymizer.extractTokens(text))
    }
}
