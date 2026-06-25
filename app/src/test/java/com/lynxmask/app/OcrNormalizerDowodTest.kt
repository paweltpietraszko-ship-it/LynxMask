package com.lynxmask.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OcrNormalizerDowodTest {

    @Before
    fun setup() {
        LookupTables.initializeForTesting()
        resetRegexCache()
    }

    // Krok 11d — OCR_DOWOD_DIGITS: litery jako cyfry w numerze dowodu po słowie kluczowym

    @Test
    fun `OCR dowod S w cyfrach zamienione na 5`() {
        val result = OcrNormalizer.normalize("dowód osobisty: AHB S45316")
        assertTrue("S→5 w cyfrowej części numeru dowodu",
            result.normalizedText.contains("AHB 545316"))
    }

    @Test
    fun `OCR dowod O w cyfrach zamienione na 0`() {
        val result = OcrNormalizer.normalize("Nr dowodu: CAN6O1202")
        assertTrue("O→0 w cyfrowej części numeru dowodu",
            result.normalizedText.contains("CAN601202"))
    }

    @Test
    fun `OCR dowod I w cyfrach zamienione na 1`() {
        val result = OcrNormalizer.normalize("dowód: LDW5I5208")
        assertTrue("I→1 w cyfrowej części numeru dowodu",
            result.normalizedText.contains("LDW515208"))
    }

    @Test
    fun `OCR dowod bez slowa kluczowego nie jest zmieniany`() {
        val result = OcrNormalizer.normalize("AHB S45316")
        assertFalse("bez kontekstu 'dowód' nie zmieniamy serii",
            result.normalizedText.contains("AHB 545316"))
    }

    // Krok 11d v2.2 — BUG-SERIA-DOWOD-CYFRA: cyfra w miejscu litery serii (odwrotna sytuacja)

    @Test
    fun `OCR dowod seria Z zamieniona na 2 przez OCR`() {
        // doc_00020: OCR widzi "2TS935950" zamiast "ZTS935950"
        val result = OcrNormalizer.normalize("dowód: 2TS935950")
        assertTrue("2→Z w serii dowodu po słowie kluczowym",
            result.normalizedText.contains("ZTS935950"))
    }

    @Test
    fun `OCR dowod seria kilka cyfr zamiast liter`() {
        // 2→Z, 8→B w serii
        val result = OcrNormalizer.normalize("d.o.: 28N601202")
        assertTrue("2→Z i 8→B w serii dowodu",
            result.normalizedText.contains("ZBN601202"))
    }

    @Test
    fun `OCR dowod prawidlowa seria nie jest zmieniana`() {
        val result = OcrNormalizer.normalize("dowód osobisty: ZTS935950")
        assertTrue("poprawna seria liter pozostaje bez zmian",
            result.normalizedText.contains("ZTS935950"))
    }

    @Test
    fun `OCR dowod forma dowodu osobistego benchmark doc20`() {
        val result = OcrNormalizer.normalize("Nr dowodu osobistego 2TS935950")
        assertTrue("dowodu + 2TS → ZTS", result.normalizedText.contains("ZTS935950"))
    }

    @Test
    fun `pseudonymize benchmark doc20 dowodu nie wycieka`() {
        val r = PseudonymEngine.pseudonymize("Nr dowodu osobistego 2TS935950 PESEL 60020104908")
        assertFalse(r.pseudonymizedText.contains("2TS935950"))
        assertFalse(r.pseudonymizedText.contains("935950"))
    }
}
