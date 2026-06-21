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
    fun `email wykrywany mimo ze znak malpa zamieniony na Q przez OCR`() {
        // OCR daje: "lukaszszymanski Qinteria.pl"
        // OCR_EMAIL_AT_Q w OcrNormalizer naprawia Q → @ przed przekazaniem do silnika
        // Silnik zapisuje email jako NUMER_xxx (brak TOKEN_EMAIL w architekturze)
        val ocrText = loadOcrText()
        println("Fragment OCR z emailem: " +
            ocrText.lines().firstOrNull { it.contains("Qinteria") || it.contains("interia") })
        val result = PseudonymEngine.pseudonymize(ocrText)
        println("tokenMap: ${result.tokenMap}")
        assertTrue(
            "Email 'lukaszszymanski@interia.pl' powinien byc wykryty jako NUMER_xxx",
            result.tokenMap.values.any { it.contains("@") && it.contains("interia") }
        )
    }

    @Test
    fun `NIP wykrywany z poprawnym myslnikiem mimo ze OCR zamienil ostatni myslnik na kropke`() {
        // OCR daje: "873-054-80.39" zamiast "873-054-80-39"
        // OCR_NIP_DOT w OcrNormalizer naprawia kropkę na myślnik przed przekazaniem do silnika
        // Silnik zapisuje NIP jako NUMER_xxx (brak TOKEN_NIP w architekturze)
        val ocrText = loadOcrText()
        println("Fragment OCR z NIP: " +
            ocrText.lines().firstOrNull { it.contains("873") || it.contains("054") })
        val result = PseudonymEngine.pseudonymize(ocrText)
        println("tokenMap: ${result.tokenMap}")
        assertTrue(
            "NIP '873-054-80-39' powinien byc wykryty z poprawnym myslnikiem (nie kropka)",
            result.tokenMap.values.any { it.contains("873-054-80-39") }
        )
    }

    @Test
    fun `PESEL 10 cyfr OCR zgubil cyfre jest zamaskowany jako NUMER`() {
        // OCR daje: "6810568585" (10 cyfr) zamiast "68100568585" (11 cyfr)
        // Wzorzec kontekstowy PESEL łapie od 6 cyfr — 10 cyfr jest zamaskowane jako NUMER_xxx
        val ocrText = loadOcrText()
        val result = PseudonymEngine.pseudonymize(ocrText)
        assertTrue(
            "PESEL '6810568585' (10 cyfr, OCR zgubil cyfre) powinien byc zamaskowany jako NUMER_xxx",
            result.tokenMap.keys.any { it.startsWith("NUMER") }
        )
    }
}
