package com.lynxmask.app

import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.Assert.*

/**
 * Test integracyjny: Tesseract na doc_00004.png (lvl=1, qs=96, formularz danych osobowych).
 *
 * Ground truth encji:
 *   imie_nazwisko:   Łukasz Szymański
 *   adres:           ul. Szkolna 150, 20-100 Kielce
 *   pesel:           68100568585
 *   dowod_osobisty:  YIT494289
 *   telefon:         +48 606 219 727
 *   email:           lukaszszymanski@interia.pl
 *   data_urodzenia:  24.10.1971
 *   nip:             873-054-80-39
 *
 * Tekst OCR (Tesseract pol, psm 3) zawiera artefakty:
 *   - '@' → 'Q' + spacja: "lukaszszymanski Qinteria.pl"
 *   - ostatni myślnik → kropka w NIP: "873-054-80.39"
 *   - zgubiona cyfra w PESEL: "6810568585" (10 cyfr zamiast 11)
 *   - brak polskich znaków w imieniu: "ukasz Szyma ski"
 */
class OcrLvl1IntegrationTest {

    @Before
    fun setup() {
        LookupTables.initializeForTesting()
    }

    @After
    fun teardown() {
        LookupTables.resetForTesting()
    }

    private fun loadOcrText(): String {
        return javaClass.classLoader!!
            .getResourceAsStream("ocr_lvl1_doc_00004.txt")!!
            .bufferedReader(Charsets.UTF_8)
            .readText()
    }

    // ── TESTY KTÓRE POWINNY PRZEJŚĆ (silnik działa) ──────────────────────────

    @Test
    fun `telefon jest wykrywany`() {
        val result = PseudonymEngine.pseudonymize(loadOcrText())
        println("tokenMap: ${result.tokenMap}")
        assertTrue(
            "telefon '+48 606 219 727' powinien byc wykryty",
            result.tokenMap.values.any { it.contains("606 219 727") }
        )
    }

    @Test
    fun `dowod osobisty jest wykrywany`() {
        val result = PseudonymEngine.pseudonymize(loadOcrText())
        println("tokenMap: ${result.tokenMap}")
        assertTrue(
            "dowod 'YIT494289' powinien byc wykryty",
            result.tokenMap.values.any { it.contains("YIT494289") }
        )
    }

    @Test
    fun `adres jest wykrywany`() {
        val result = PseudonymEngine.pseudonymize(loadOcrText())
        println("tokenMap: ${result.tokenMap}")
        assertTrue(
            "adres 'Szkolna' powinien byc wykryty",
            result.tokenMap.values.any { it.contains("Szkolna") }
        )
    }

    // ── TESTY KTÓRE FAILUJĄ (znane bugi OCR) ─────────────────────────────────

    @Test
    fun `FAIL email wykrywany mimo ze znak malpa zamieniony na Q przez OCR`() {
        // OCR daje: "lukaszszymanski Qinteria.pl"
        // OcrNormalizer nie obsługuje '@' → 'Q'
        // OCZEKIWANY FAIL — do naprawy w OcrNormalizer
        val ocrText = loadOcrText()
        println("Fragment OCR z emailem: " +
            ocrText.lines().firstOrNull { it.contains("Qinteria") || it.contains("interia") })
        val result = PseudonymEngine.pseudonymize(ocrText)
        println("tokenMap: ${result.tokenMap}")
        assertTrue(
            "EMAIL 'lukaszszymanski@interia.pl' nie wykryty — OCR zamienil '@' na 'Q'",
            result.tokenMap.keys.any { it.startsWith("EMAIL") }
        )
    }

    @Test
    fun `FAIL NIP wykrywany jako NIP mimo ze ostatni myslnik zamieniony na kropke przez OCR`() {
        // OCR daje: "873-054-80.39" zamiast "873-054-80-39"
        // Silnik wykrywa numer jako NUMER_002, ale NIE jako NIP — kropka łamie wzorzec NIP
        // OCZEKIWANY FAIL — do naprawy w OcrNormalizer (OCR_NIP_DOT) lub StructuralEngine
        val ocrText = loadOcrText()
        println("Fragment OCR z NIP: " +
            ocrText.lines().firstOrNull { it.contains("873") || it.contains("054") })
        val result = PseudonymEngine.pseudonymize(ocrText)
        println("tokenMap keys: ${result.tokenMap.keys}")
        println("tokenMap: ${result.tokenMap}")
        assertTrue(
            "NIP '873-054-80-39' nie wykryty jako NIP_xxx — OCR zamienil ostatni myslnik na kropke; jest NUMER_002",
            result.tokenMap.keys.any { it.startsWith("NIP") }
        )
    }

    @Test
    fun `FAIL PESEL wykrywany jako PESEL mimo ze OCR zgubil cyfre`() {
        // OCR daje: "6810568585" (10 cyfr) zamiast "68100568585" (11 cyfr)
        // Silnik wykrywa numer jako NUMER_001, ale NIE jako PESEL — 10 cyfr łamie walidację PESEL
        // OCZEKIWANY FAIL — bug OCR niemożliwy do naprawy bez ekstra kontekstu
        val ocrText = loadOcrText()
        println("Fragment OCR z PESEL: " +
            ocrText.lines().firstOrNull { it.contains("6810") || it.contains("PESEL") })
        val result = PseudonymEngine.pseudonymize(ocrText)
        println("tokenMap keys: ${result.tokenMap.keys}")
        println("tokenMap: ${result.tokenMap}")
        assertTrue(
            "PESEL '68100568585' nie wykryty jako PESEL_xxx — OCR zgubil cyfre (10 zamiast 11 cyfr); jest NUMER_001",
            result.tokenMap.keys.any { it.startsWith("PESEL") }
        )
    }
}
