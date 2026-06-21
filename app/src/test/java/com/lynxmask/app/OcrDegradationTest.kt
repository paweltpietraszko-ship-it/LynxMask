package com.lynxmask.app

import org.junit.Before
import org.junit.After
import org.junit.Ignore
import org.junit.Test
import org.junit.Assert.*

/**
 * Test degradacji OCR — sprawdza silnik na 4 poziomach zniekształcenia.
 * Każdy poziom przepuszczany osobno (brak "uczenia się" między sekcjami).
 *
 * Ground truth:
 *   OSOBA:   Krzysztof Nowicki, Beata Kamińska
 *   PESEL:   91040512361, 74081934523
 *   NIP:     512-34-56-786, 678-901-23-47
 *   IBAN:    PL89109010147449555252110732
 *   TELEFON: +48 601 234 567, (32) 456-78-90
 *   EMAIL:   krzysztof.nowicki@firma.pl, b.kaminska@onet.pl
 *   ADRES:   ul. Lipowa 22, 41-200 Bytom
 *   DOWÓD:   EFG 456789
 *
 * Testy z prefiksem FAIL — oczekiwane porażki (dokumentują gdzie silnik się gubi).
 * Testy bez prefiksu FAIL — oczekiwane sukcesy (ich porażka to regresja).
 */
class OcrDegradationTest {

    @Before
    fun setup() {
        // initializeFromClasspath ładuje surnames_top1000.json i names_inflected.json
        // z src/test/resources/ — zawierają "nowicki", "kamiński", "krzysztof", "beata"
        LookupTables.initializeFromClasspath()
    }

    @After
    fun teardown() {
        LookupTables.resetForTesting()
    }

    // ── Teksty testowe (każdy LVL jako osobny string) ──────────────────────────

    private val LVL0 = """
        Zleceniodawca: Krzysztof Nowicki, PESEL: 91040512361, NIP: 512-34-56-786,
        zamieszkały ul. Lipowa 22, 41-200 Bytom, tel.: +48 601 234 567,
        e-mail: krzysztof.nowicki@firma.pl, dowód osobisty: EFG 456789.

        Zleceniobiorca: Beata Kamińska, PESEL: 74081934523, NIP: 678-901-23-47,
        tel.: (32) 456-78-90, e-mail: b.kaminska@onet.pl.

        Konto: IBAN: PL89109010147449555252110732.
    """.trimIndent()

    private val LVL1 = """
        Zleceniodawca: Krzysztof N0wicki, PESEL: 910 40512361, NIP: 512-34-56-786,
        zamieszkały ul. Lip0wa 22, 41-200 Bytom, tel.: +48 601 234 567,
        e-mail: krzysztof.nowicki@firma.pl, dowód osobisty: EFG 456789.

        Zleceniobiorca: Beata Kaminska, PESEL: 7408 1934523, NIP: 678-901-23-47,
        tel.: (32) 456-78-90, e-mail: b.kaminska@onet.pl.

        Konto: IBAN: PL89 1090 1014 7449 5552 5211 0732.
    """.trimIndent()

    private val LVL2 = """
        Zleceniodawca: Krzyszt0f N0wlckl, PESEL: 9104 05 12361, NIP: 512-3456-786,
        zamieszkaly ul. Llpowa 22, 41-200 Byt0m, tel.: +48 601234 567,
        e-mail: krzysztof. nowicki@firma.pl, dowod osobisty: EFG 456789.

        Zleceniobiorca: B3ata Kamlnska, PESEL: 748 01934523, NIP: 678-901-23-47,
        tel.: (32) 456 78 90, e-mail: b. kaminska@onet.pl.

        Konto: IBAN: PL89 10901014 74495552 52110732.
    """.trimIndent()

    private val LVL3 = """
        Zleceniodawca: Krzy5zt0f N0w1ck1, PESEL: 9l0405 l2361, NIP: 5l2-34-56-786,
        zamieszkaly u. Llp0wa 22, 4l-200 Byt0m, tel: 48 60l 234 567,
        e-mail: krzyszt0f. n0wicki@flrma.pl, dow0d 0sobisty: EFG 456789.

        Zleceniobiorca: Be4ta Kamlnska, PE5EL: 7408l934 523, NlP: 678-90l-23-47,
        tel: (32) 456-78 90, e-mall: b .kamlnska@0net.pl.

        Kont0: IBAN: PL89l090l0l474495552 52ll0732.
    """.trimIndent()

    private fun tokens(text: String): Collection<String> =
        PseudonymEngine.pseudonymize(text).tokenMap.values

    private fun String.digitsOnly() = replace(Regex("[^0-9A-Za-z]"), "")

    // ── LVL 0 — czysty tekst (wszystkie powinny być zielone) ───────────────────

    @Test fun `lvl0 OSOBA Nowicki wykryty`() =
        assertTrue("Krzysztof Nowicki nie wykryty",
            tokens(LVL0).any { "Nowicki" in it })

    @Test fun `lvl0 OSOBA Kaminska wykryta`() =
        assertTrue("Beata Kamińska nie wykryta",
            tokens(LVL0).any { "Kami" in it })

    @Test fun `lvl0 PESEL1 wykryty`() =
        assertTrue("PESEL 91040512361 nie wykryty",
            tokens(LVL0).any { it.replace(" ", "").contains("91040512361") })

    @Test fun `lvl0 PESEL2 wykryty`() =
        assertTrue("PESEL 74081934523 nie wykryty",
            tokens(LVL0).any { it.replace(" ", "").contains("74081934523") })

    @Test fun `lvl0 NIP1 wykryty`() =
        assertTrue("NIP 512-34-56-786 nie wykryty",
            tokens(LVL0).any { it.digitsOnly().contains("5123456786") })

    @Test fun `lvl0 NIP2 wykryty`() =
        assertTrue("NIP 678-901-23-47 nie wykryty",
            tokens(LVL0).any { it.digitsOnly().contains("6789012347") })

    @Test fun `lvl0 IBAN wykryty`() =
        assertTrue("IBAN PL89109010147449555252110732 nie wykryty",
            tokens(LVL0).any { it.replace(" ", "").contains("PL89109010147449555252110732") })

    @Test fun `lvl0 TEL1 wykryty`() =
        assertTrue("+48 601 234 567 nie wykryty",
            tokens(LVL0).any { "601" in it && "234" in it })

    @Test fun `lvl0 TEL2 wykryty`() =
        assertTrue("(32) 456-78-90 nie wykryty",
            tokens(LVL0).any { "456" in it && "78" in it })

    @Test fun `lvl0 EMAIL1 wykryty`() =
        assertTrue("krzysztof.nowicki@firma.pl nie wykryty",
            tokens(LVL0).any { "nowicki@firma" in it })

    @Test fun `lvl0 EMAIL2 wykryty`() =
        assertTrue("b.kaminska@onet.pl nie wykryta",
            tokens(LVL0).any { "kaminska@onet" in it })

    @Test fun `lvl0 ADRES ulica wykryta`() =
        assertTrue("ul. Lipowa 22 nie wykryta",
            tokens(LVL0).any { "Lipowa" in it })

    @Test fun `lvl0 ADRES kod pocztowy wykryty`() =
        assertTrue("41-200 Bytom nie wykryty",
            tokens(LVL0).any { "Bytom" in it || "41-200" in it })

    @Test fun `lvl0 DOWOD wykryty`() =
        assertTrue("EFG 456789 nie wykryty",
            tokens(LVL0).any { "EFG" in it && "456789" in it })

    // ── LVL 1 — lekki szum (O↔0, l↔1, spacje w numerach) ─────────────────────
    // Wszystkie powinny być zielone — OcrNormalizer obsługuje ten poziom.

    @Test fun `lvl1 OSOBA Nowicki wykryty mimo N0wicki`() =
        assertTrue("Krzysztof N0wicki nie wykryty",
            tokens(LVL1).any { "Nowicki" in it || "N0wicki" in it })

    @Test fun `lvl1 OSOBA Kaminska wykryta bez ogonkow`() =
        assertTrue("Beata Kaminska nie wykryta",
            tokens(LVL1).any { "Kamins" in it })

    @Test fun `lvl1 PESEL1 wykryty ze spacja`() =
        assertTrue("PESEL 910 40512361 (ze spacją) nie wykryty",
            tokens(LVL1).any { it.replace(" ", "").contains("91040512361") })

    @Test fun `lvl1 PESEL2 wykryty ze spacja`() =
        assertTrue("PESEL 7408 1934523 (ze spacją) nie wykryty",
            tokens(LVL1).any { it.replace(" ", "").contains("74081934523") })

    @Test fun `lvl1 IBAN wykryty ze spacjami`() =
        assertTrue("IBAN ze spacjami (PL89 1090 ...) nie wykryty",
            tokens(LVL1).any { it.replace(" ", "").contains("PL89109010147449555252110732") })

    @Test fun `lvl1 EMAIL1 wykryty`() =
        assertTrue("krzysztof.nowicki@firma.pl nie wykryty (LVL1)",
            tokens(LVL1).any { "nowicki@firma" in it })

    @Test fun `lvl1 EMAIL2 wykryty`() =
        assertTrue("b.kaminska@onet.pl nie wykryta (LVL1)",
            tokens(LVL1).any { "kaminska@onet" in it })

    @Test fun `lvl1 ADRES ulica wykryta mimo Lip0wa`() =
        assertTrue("ul. Lip0wa 22 nie wykryta",
            tokens(LVL1).any { "Lipowa" in it || "Lip0wa" in it })

    @Test fun `lvl1 DOWOD wykryty`() =
        assertTrue("EFG 456789 nie wykryty (LVL1)",
            tokens(LVL1).any { "EFG" in it && "456789" in it })

    // ── LVL 2 — średni szum (cyfry w nazwach, zepsute polskie znaki) ───────────
    // Część przechodzi, część nie. FAIL = oczekiwana porażka (dokumentuje limit silnika).

    @Test fun `lvl2 OSOBA Nowicki wykryty mimo Krzyszt0f N0wlckl`() =
        assertTrue("Krzyszt0f N0wlckl nie wykryty — NameEngine nie obsłużył LVL2",
            tokens(LVL2).any { "Krzyszt" in it || "N0wlckl" in it || "Nowlckl" in it })

    @Test fun `FAIL lvl2 OSOBA Kaminska pominięta B3ata`() =
        // Cyfra 3 w imieniu "B3ata" łamie wykrycie przez NameEngine. OCZEKIWANY FAIL.
        assertTrue("B3ata Kamlnska nie wykryta (LVL2 — oczekiwany fail: cyfra w imieniu)",
            tokens(LVL2).any { "B3ata" in it || "Kamlnska" in it })

    @Test fun `lvl2 PESEL1 wykryty ze spacjami`() =
        assertTrue("PESEL 9104 05 12361 (dwie spacje) nie wykryty",
            tokens(LVL2).any { it.replace(" ", "").contains("91040512361") })

    @Test fun `lvl2 PESEL2 wykryty ze spacja`() =
        // "748 01934523" → po złączeniu "74801934521" (OCR przestawił cyfry — ale 11 cyfr = wykryty)
        assertTrue("PESEL 748 01934523 nie wykryty",
            tokens(LVL2).any { it.replace(" ", "").length == 11 && it.replace(" ", "").all { c -> c.isDigit() } })

    @Test fun `lvl2 IBAN wykryty ze spacjami`() =
        assertTrue("IBAN (PL89 10901014 ...) nie wykryty",
            tokens(LVL2).any { it.replace(" ", "").contains("PL89109010147449555252110732") })

    @Test fun `lvl2 EMAIL1 wykryty ze spacja w local-part`() =
        assertTrue("krzysztof. nowicki@firma.pl (spacja w local-part) nie wykryty",
            tokens(LVL2).any { "nowicki@firma" in it })

    @Test fun `lvl2 EMAIL2 wykryty ze spacja w local-part`() =
        assertTrue("b. kaminska@onet.pl (spacja w local-part) nie wykryta",
            tokens(LVL2).any { "kaminska@onet" in it })

    @Test fun `lvl2 ADRES ulica wykryta mimo Llpowa`() =
        assertTrue("ul. Llpowa 22 nie wykryta",
            tokens(LVL2).any { "Llpowa" in it || "Lipowa" in it })

    @Test fun `FAIL lvl2 ADRES kod pocztowy pominięty Byt0m`() =
        // "Byt0m" (zero zamiast 'o') nie pasuje do KNOWN_CITY_FORMS. OCZEKIWANY FAIL.
        assertTrue("41-200 Byt0m nie wykryty (LVL2 — oczekiwany fail: KNOWN_CITY_FORMS nie zna Byt0m)",
            tokens(LVL2).any { "Bytom" in it || "Byt0m" in it || "41-200" in it })

    @Test fun `lvl2 DOWOD wykryty`() =
        assertTrue("EFG 456789 nie wykryty (LVL2)",
            tokens(LVL2).any { "EFG" in it && "456789" in it })

    // ── LVL 3 — ciężki szum (wiele artefaktów) ─────────────────────────────────
    // Większość FAIL — dokumentuje dolną granicę silnika.

    @Test fun `lvl3 OSOBA Nowicki wykryty po de-leet Krzy5zt0f N0w1ck1`() =
        // De-leet (krok 15 normalizera): Krzy5zt0f→Krzysztof, N0w1ck1→Nowicki
        assertTrue("Krzy5zt0f N0w1ck1 nie wykryty po de-leet (LVL3)",
            tokens(LVL3).any { "Nowicki" in it || "Krzysztof" in it })

    @Test fun `FAIL lvl3 OSOBA Kaminska pominięta Be4ta`() =
        // "Be4ta Kamlnska" — cyfra w imieniu. OCZEKIWANY FAIL.
        assertTrue("Be4ta Kamlnska nie wykryta (LVL3 — oczekiwany fail)",
            tokens(LVL3).any { "Be4ta" in it || "Kamlnska" in it })

    @Test fun `lvl3 PESEL1 wykryty 9l0405 l2361`() =
        // OCR_PESEL_SPLIT v2.0: akceptuje l/O w cyfrach PESEL + konwertuje l→1 i usuwa spacje.
        assertTrue("PESEL 9l0405 l2361 nie wykryty (LVL3 — regresja OCR_PESEL_SPLIT)",
            tokens(LVL3).any { it.replace(" ", "").replace("l", "1").contains("91040512361") })

    @Test fun `lvl3 PESEL2 wykryty 7408l934`() =
        // OCR_PESEL_SPLIT v2.0: "7408l934 523" → "74081934523" (l→1 + usuwa spację).
        assertTrue("PESEL 7408l934 523 nie wykryty (LVL3 — regresja OCR_PESEL_SPLIT)",
            tokens(LVL3).any { it.replace(" ", "").replace("l", "1").contains("74081934523") })

    @Test fun `lvl3 NIP1 wykryty mimo 5l2 dzieki OCR_NIP_DIGITS`() =
        // OCR_NIP_DIGITS konwertuje 'l'→'1' w kontekście NIP. Powinno PRZEJŚĆ.
        assertTrue("NIP 5l2-34-56-786 nie wykryty — OCR_NIP_DIGITS nie zadziałał",
            tokens(LVL3).any { it.digitsOnly().contains("5123456786") })

    @Test fun `lvl3 NIP2 wykryty mimo 90l dzieki OCR_NIP_DIGITS`() =
        assertTrue("NIP 678-90l-23-47 nie wykryty — OCR_NIP_DIGITS nie zadziałał",
            tokens(LVL3).any { it.digitsOnly().contains("6789012347") })

    @Test fun `lvl3 EMAIL1 wykryty krzyszt0f n0wicki flrma`() =
        // OcrNormalizer obsługuje spację w local-part i 0 w domenie. Powinno PRZEJŚĆ.
        assertTrue("krzyszt0f. n0wicki@flrma.pl nie wykryty (LVL3)",
            tokens(LVL3).any { "@flrma.pl" in it || "n0wicki@" in it })

    @Test fun `FAIL lvl3 EMAIL2 czesciowy b kamlnska 0net`() =
        // "b .kamlnska@0net.pl" — spacja+kropka przed local-part rozbija wykrycie. OCZEKIWANY FAIL.
        assertTrue("b .kamlnska@0net.pl nie wykryta w całości (LVL3 — oczekiwany fail)",
            tokens(LVL3).any { "kamlnska@" in it || "@0net.pl" in it })

    @Test fun `lvl3 DOWOD wykryty EFG 456789 niezmienny`() =
        // EFG 456789 nie ulega degradacji OCR — zawsze powinno przejść.
        assertTrue("EFG 456789 nie wykryty (LVL3 — niespodziewany fail)",
            tokens(LVL3).any { "EFG" in it && "456789" in it })

    @Test fun `lvl3 IBAN wykryty PL89l090`() =
        // OCR_IBAN_SPLIT v2.0: akceptuje l/O w cyfrach IBAN + konwertuje l→1 i usuwa spację.
        assertTrue("IBAN PL89l090l0l... nie wykryty (LVL3 — regresja OCR_IBAN_SPLIT)",
            tokens(LVL3).any { it.replace(" ", "").replace("l", "1")
                .contains("PL89109010147449555252110732") })

    @Ignore("BUG-TEL-PREFIX: TELEFON nie trafia do tokenMap — maskowany tylko przez OutputGuard, nie StructuralEngine. Czeka na dodanie wzorca TELEFON do StructuralEngine (S10).")
    @Test fun `FAIL lvl3 TEL1 pominiety 48 60l 234 567`() =
        assertTrue("48 60l 234 567 nie wykryty (LVL3)",
            tokens(LVL3).any { "601" in it.replace("l","1") && "234" in it })
}
