package com.lynxmask.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// BUG-OCR-BRAMKA-ZNIKNELA (16.10): bramka odrzucania słabych skanów (7817239, 25.06) zniknęła
// z aplikacji 29.06 razem z "martwym kodem" ImageRouteChoice (1fd32cf). Zdjęcie grafiku z OCR
// 54% szło do maskowania jako gruz. Próg 70% — decyzja Pawła: "68% jakości to też będzie gruz".
class OcrQualityGateTest {

    @Test
    fun zdjecie54procent_odrzucone() {
        assertTrue(shouldRejectOcr(conf = 0.54f, chars = 800))
    }

    @Test
    fun zdjecie68procent_odrzucone() {
        assertTrue(shouldRejectOcr(conf = 0.68f, chars = 800))
    }

    @Test
    fun zdjecie75procent_przepuszczone() {
        assertFalse(shouldRejectOcr(conf = 0.75f, chars = 800))
    }

    @Test
    fun dokladnieNaProgu_przepuszczone() {
        assertFalse(shouldRejectOcr(conf = OCR_CONF_THRESHOLD, chars = 800))
    }

    @Test
    fun plikTekstowyBezPewnosciOcr_nigdyNieOdrzucony() {
        // DOCX / Excel / TXT przychodzą z mlKitConfidence = null
        assertFalse(shouldRejectOcr(conf = null, chars = 5))
        assertFalse(shouldRejectOcr(conf = null, chars = 5000))
    }
}
