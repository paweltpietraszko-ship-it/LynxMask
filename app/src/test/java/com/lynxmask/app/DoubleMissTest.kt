package com.lynxmask.app

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Anti-leak: encja musi być w tokenMap LUB złapana przez OutputGuard RED.
 * Scenariusze z OcrDegradationTest / audyt RODO v2.
 */
class DoubleMissTest {

    @Before
    fun setup() {
        LookupTables.initializeForTesting()
        resetRegexCache()
    }

    @After
    fun teardown() {
        LookupTables.resetForTesting()
        resetRegexCache()
    }

    private fun pseudonymize(text: String): PseudonymResult =
        PseudonymEngine.pseudonymize(text)

    private fun isCovered(r: PseudonymResult, digitNeedle: String): Boolean {
        val needle = digitNeedle.filter { it.isDigit() }
        require(needle.length >= 6) { "needle too short" }
        fun digits(s: String) = s.filter { it.isDigit() }
        return r.tokenMap.values.any { digits(it).contains(needle) }
            || r.guardHits.any { it.level == "RED" && digits(it.matchedText).contains(needle) }
    }

    @Test
    fun `double miss telefon OCR l zamiast 1 nie wycieka`() {
        val r = pseudonymize("Kontakt: tel: 48 60l 234 567")
        assertTrue(
            "Numer telefonu musi być w tokenMap LUB Guard RED",
            isCovered(r, "601234567")
        )
        assertFalse("Surowy numer nie może zostać w output", r.pseudonymizedText.contains("601234567"))
        assertFalse(r.pseudonymizedText.contains("60l 234"))
    }

    @Test
    fun `double miss nip z separatorami Guard RED gdy S5 blokuje token`() {
        // Poprawny format, prawdopodobnie zła suma kontrolna — silnik może pominąć, Guard musi złapać
        val r = pseudonymize("Numer konta: 521-334-15-34")
        val guardNip = r.guardHits.any { it.level == "RED" && it.label == "NIP" }
        val tokenized = r.tokenMap.values.any { it.contains("521") && it.contains("334") }
        assertTrue("NIP w tokenMap lub Guard RED NIP", tokenized || guardNip)
    }

    @Test
    fun `double miss nip OCR l w cyfrze po normalizacji`() {
        val r = pseudonymize("521-3l4-15-33")
        val guardNip = r.guardHits.any { it.level == "RED" && it.label == "NIP" }
        val tokenized = r.tokenMap.isNotEmpty()
        assertTrue("NIP OCR-l: tokenMap lub Guard RED", tokenized || guardNip)
        assertFalse(r.pseudonymizedText.contains("3l4"))
    }

    @Test
    fun `double miss nip OCR l na poczatku segmentu`() {
        val r = pseudonymize("52l-334-15-33")
        val guardNip = r.guardHits.any { it.level == "RED" && it.label == "NIP" }
        val tokenized = r.tokenMap.isNotEmpty()
        assertTrue("NIP 52l: tokenMap lub Guard RED", tokenized || guardNip)
        assertFalse("Fragment 52l nie może zostać w output", r.pseudonymizedText.contains("52l"))
    }

    @Test
    fun `double miss nip cyrylica Z w segmencie`() {
        val r = pseudonymize("521-3\u04174-15-33") // Cyrillic З
        val guardNip = r.guardHits.any { it.level == "RED" && it.label == "NIP" }
        val tokenized = r.tokenMap.isNotEmpty()
        assertTrue("NIP cyrylica: tokenMap lub Guard RED", tokenized || guardNip)
        assertFalse(r.pseudonymizedText.contains("\u0417"))
    }

    @Test
    fun `double miss pesel bez kontekstu bledna suma nie wycieka`() {
        val peselBlednaSum = "44051401448" // OCR: 5→4, zła suma kontrolna
        val r = pseudonymize("Numer: $peselBlednaSum")
        assertTrue(
            "PESEL musi być w tokenMap LUB Guard RED",
            isCovered(r, peselBlednaSum)
        )
        assertFalse(r.pseudonymizedText.contains(peselBlednaSum))
    }

    @Test
    fun `isClipboardClean false gdy Guard RED`() {
        // NIP z błędną sumą kontrolną — S5 blokuje tokenizację, Guard RED łapie format NIP
        val r = pseudonymize("521-334-15-34")
        assertTrue("Guard RED powinien złapać NIP z błędną sumą", r.guardHits.any { it.level == "RED" })
        assertFalse("isClipboardClean musi być false gdy Guard RED", isClipboardClean(r))
    }
}
