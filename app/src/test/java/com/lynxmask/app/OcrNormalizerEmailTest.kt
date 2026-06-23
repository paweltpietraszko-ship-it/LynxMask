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
    fun `OCR_EMAIL_AT_Q naprawa Q na malpe`() {
        val input = "lukaszszymanski Qinteria.pl"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        println("Poprawek: ${result.corrections}")
        assertTrue("Q powinno byc zamienione na @", result.normalizedText.contains("lukaszszymanski@interia.pl"))
    }

    @Test
    fun `N3 wiele spacji w local-part wszystkie naprawiane`() {
        // "jan k owal ski@wp.pl" → "jan_k_owal_ski@wp.pl"
        // Poprzednio: jedna iteracja replace → tylko pierwsza spacja naprawiana
        val input = "jan k owal ski@wp.pl"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("Wszystkie spacje powinny być zastąpione _", result.normalizedText.contains("jan_k_owal_ski@wp.pl"))
    }

    @Test
    fun `N3 trzy spacje w local-part`() {
        val input = "kontakt: anna maria nowak kowalska@firma.com.pl dane"
        val result = OcrNormalizer.normalize(input)
        assertTrue("Wszystkie spacje w local-part naprawione",
            result.normalizedText.contains("anna_maria_nowak_kowalska@firma.com.pl"))
    }

    @Test
    fun `BUG-EMAIL-TLD1 TLD z cyfra naprawiany przez TOKEN EMAIL`() {
        // OCR: l→1 w TLD, "bartosz@prawnik p1" zamiast "bartosz@prawnik.pl"
        // OcrNormalizer (TLDSPACE) poprawia spację → "@prawnik.p1"
        // TOKEN_EMAIL (po naprawie): [a-zA-Z][a-zA-Z0-9]{1,} akceptuje "p1"
        val input = "bartosz.jablowski@prawnik p1"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("TLD 'p1' powinien byc naprawiony do '@prawnik.p1'", result.normalizedText.contains("@prawnik.p1"))
    }

    @Test
    fun `OCR_EMAIL_SLDSPACE spacja wewnatrz SLD naprawiana`() {
        val input = "jan.kowalski@inte ria.pl"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("Spacja w SLD powinna byc usunieta", result.normalizedText.contains("@interia.pl"))
    }

    @Test
    fun `OCR_EMAIL_SLDSPACE z local-part spacjami i spacja w SLD`() {
        val input = "anna nowak@inte ria.pl dane osobowe"
        val result = OcrNormalizer.normalize(input)
        println("PRZED: $input")
        println("PO:    ${result.normalizedText}")
        assertTrue("Spacja w SLD usunieta", result.normalizedText.contains("@interia.pl"))
        assertTrue("Spacja w local-part zamieniona na _", result.normalizedText.contains("anna_nowak@"))
    }

    @Test
    fun `spacja po malpce kompaktowana`() {
        val r = OcrNormalizer.normalize("e-mail: piotr@  interia.pl")
        assertTrue(r.normalizedText.contains("piotr@interia.pl"))
    }

    @Test
    fun `spacja zamiast kropki w domenie po e-mail`() {
        val r = OcrNormalizer.normalize("e-mail: piotr@o2 pl")
        assertTrue(r.normalizedText.contains("@o2.pl"))
    }

    @Test
    fun `spacja w local-part przed malpalka`() {
        val r = OcrNormalizer.normalize("e-mail: justyna.dud ek@gmail.com")
        assertTrue(r.normalizedText.contains("justyna.dudek@gmail.com"))
    }

    @Test
    fun `benchmark spacja po malpce w SLD i local-part`() {
        val r = OcrNormalizer.normalize("e-mail: piotr dudek45@ inte ria.pl")
        assertTrue(r.normalizedText.contains("piotrdudek45@interia.pl"))
    }
}
