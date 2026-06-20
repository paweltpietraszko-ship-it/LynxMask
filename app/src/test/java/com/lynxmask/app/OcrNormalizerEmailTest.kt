package com.lynxmask.app

import org.junit.Test
import org.junit.Assert.*

class OcrNormalizerEmailTest {

    @Test
    fun `naprawa spacji przed TLD`() {
        val input = "Adres emait mariusz kaminski@o2 pl"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        println("Poprawek: ${result.corrections}")
        assertTrue("Powinna być kropka przed pl", result.normalizedText.contains("@o2.pl"))
    }

    @Test
    fun `naprawa spacji w local-part`() {
        val input = "krzysztof nowakowski@onet pl"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        println("Poprawek: ${result.corrections}")
        assertTrue("Powinna być kropka przed pl", result.normalizedText.contains("@onet.pl"))
    }

    @Test
    fun `naprawa obu artefaktow naraz`() {
        val input = "kontakt: krzysztof nowakowski@onet pl dane osobowe"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("Brak poprawki TLD", result.normalizedText.contains("@onet.pl"))
    }

    @Test
    fun `naprawa doc_00072 krzysztof nowakowski`() {
        val input = "Adres e-mail krzysztof nowakowski@onet pl DANE"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        println("Poprawek: ${result.corrections}")
        assertTrue("Brak naprawy TLD", result.normalizedText.contains("@onet.pl"))
        assertTrue("Brak naprawy local-part", result.normalizedText.contains("krzysztof_nowakowski@"))
    }

    @Test
    fun `FAIL BUG-EMAIL-TLD1 TLD z cyfra nie jest naprawiany`() {
        // OCR: l→1 w TLD, "bartosz@prawnik p1" zamiast "bartosz@prawnik.pl"
        // OCR_EMAIL_TLDSPACE szuka [a-zA-Z]{2,4} — nie matchuje "p1" bo zawiera cyfrę
        // OCZEKIWANY FAIL — fix: [a-zA-Z0-9]{2,4} w OCR_EMAIL_TLDSPACE
        val input = "bartosz.jablowski@prawnik p1"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("TLD 'p1' powinien byc naprawiony do '@prawnik.p1'", result.normalizedText.contains("@prawnik.p1"))
    }
}
