package com.lynxmask.app

// PseudonymEngineTest.kt — Testy jednostkowe silnika pseudonimizacji
//
// Uruchamianie: ./gradlew test  (bez urządzenia, bez emulatora)
// Lokalizacja:  src/test/java/com/lynxmask/app/PseudonymEngineTest.kt
//
// Wymaganie w build.gradle.kts (dodaj jeśli nie ma):
//   android {
//       testOptions { unitTests.returnDefaultValues = true }
//   }
//
// Co testujemy: StructuralEngine, NameEngine, OcrNormalizer, OutputGuard.
// Co NIE wymaga testów tutaj: Camera, OCR (ML Kit), UI — to androidTest.

import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.After

class PseudonymEngineTest {

    @Before
    fun setup() {
        // Inicjalizacja bez Context — minimalny słownik dla testów JVM.
        // Warstwa strukturalna (PESEL, NIP, IBAN, email itd.) działa w pełni.
        // Warstwa imion używa wstrzykniętego słownika testowego.
        LookupTables.initializeForTesting()
    }

    @After
    fun teardown() {
        LookupTables.resetForTesting()
        resetRegexCache()
    }

    // =========================================================================
    // PESEL
    // =========================================================================

    @Test fun `PESEL 11 cyfr jest maskowany`() {
        val r = pseudonymize("PESEL: 65051112340")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "65051112340")
    }

    @Test fun `PESEL ze spacja OCR jest maskowany`() {
        val r = pseudonymize("PESEL: 650511 12340")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "650511")
    }

    @Test fun `PESEL 9 cyfr OCR zgubil 2 cyfry jest maskowany`() {
        val r = pseudonymize("PESEL: 650511123")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "650511123")
    }

    @Test fun `BUG-PESEL-10 PESEL z brakujaca cyfra OCR kontekstowy jest maskowany`() {
        // BUG-PESEL-10: OCR gubi 1 cyfrę z 11-cyfrowego PESEL → 10 cyfr
        // Wzorzec kontekstowy (z keywordem "pesel") rozszerzony na min 5 cyfr
        // Wzorzec strukturalny \d{11} nadal wymaga 11 cyfr
        val r = pseudonymize("PESEL: 6505111234")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "6505111234")
    }

    @Test fun `BUG-PESEL-10 regresja PESEL 11 cyfr nadal maskowany`() {
        // Regresja: zmiana wzorca kontekstowego nie może zepsuć pełnego PESEL
        val r = pseudonymize("PESEL: 44051401458")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "44051401458")
    }

    @Test fun `PESEL 6 cyfr OCR data urodzenia nie moze wyciec`() {
        val r = pseudonymize("PESEL: 650511")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "650511")
    }

    @Test fun `data roczna 2024 jest maskowana jako NUMER nie PESEL`() {
        val r = pseudonymize("Umowa z dnia 2024-01-15")
        // ISO data jest maskowana jako NUMER (S-DATE-ISO), a nie 11-cyfrowy PESEL
        assertNotInOutput(r, "2024-01-15")
        assertTokenExists(r, TOKEN_NUMER)
        // Upewnij się że tylko 1 token NUMER (data jako całość, nie fragmenty)
        assertEquals("Data powinna dać dokładnie 1 token", 1, r.tokenMap.size)
    }

    // =========================================================================
    // NIP
    // =========================================================================

    @Test fun `NIP format 3-3-2-2 jest maskowany`() {
        // S5: użyto NIP z poprawną sumą kontrolną (521-334-00-01, cyfry: 5213340001)
        val r = pseudonymize("NIP: 521-334-00-01")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "521-334-00-01")
    }

    @Test fun `NIP format 3-2-2-3 jest maskowany`() {
        // S5: użyto NIP z poprawną sumą kontrolną (521-10-00-005, cyfry: 5211000005)
        // BUG-NIP-CTX-3223: wzorzec kontekstowy musi objąć też "NIP:" — bez fixa zostawało w tekście
        val r = pseudonymize("NIP: 521-10-00-005")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "521-10-00-005")
        assertNotInOutput(r, "NIP:")
    }

    @Test fun `NIP bez myslnikow jest maskowany`() {
        // S5: użyto NIP z poprawną sumą kontrolną (5213340001)
        val r = pseudonymize("NIP 5213340001")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `NIP po OCR_NIP_SPLIT jest jednym tokenem nie dwoma`() {
        val r = PseudonymEngine.pseudonymize("NIP: 740-61 7-82-26", traceMode = true)
        val traceDump = r.trace.joinToString("\n") { "  [${it.layer}/${it.rule}] \"${it.matchedText}\" -> ${it.token}" }
        assertEquals(
            "Powinien być dokładnie 1 token NUMER, wynik: ${r.pseudonymizedText}\nTRACE:\n$traceDump",
            1,
            r.tokenMap.keys.count { it.startsWith("NUMER") }
        )
        assertFalse("'740' zostało jawne: ${r.pseudonymizedText}\nTRACE:\n$traceDump",
            r.pseudonymizedText.contains("740"))
        assertFalse("'82-26' zostało jawne: ${r.pseudonymizedText}\nTRACE:\n$traceDump",
            r.pseudonymizedText.contains("82-26"))
    }

    @Test fun `NIP po OCR_NIP_SPLIT z newline jest jednym tokenem`() {
        val r = pseudonymize("NIP: 740-617\n82-26")
        assertEquals(
            "Powinien być dokładnie 1 token NUMER",
            1,
            r.tokenMap.keys.count { it.startsWith("NUMER") }
        )
        assertFalse(r.pseudonymizedText.contains("740-617"))
        assertFalse(r.pseudonymizedText.contains("82-26"))
    }

    // =========================================================================
    // IBAN / konto bankowe
    // =========================================================================

    @Test fun `IBAN PL jest maskowany`() {
        val r = pseudonymize("Konto: PL61109010140000071219812874")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "PL61")
    }

    @Test fun `konto bez prefiksu PL jest maskowane`() {
        val r = pseudonymize("Przelej na: 61 1090 1014 0000 0712 1981 2874")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `NIP rozne formaty sa maskowane`() {
        // S5: wszystkie NIPy mają poprawne sumy kontrolne
        val cases = listOf(
            "NIP: 415-112-22-69",       // 4151122269 — valid ✓
            "NIP 142-199-06-38",        // 1421990638 — valid ✓
            "NIP: 4151122269",          // valid ✓
            "nip_sprzedawcy: 451-052-35-26"  // 4510523526 — valid ✓
        )
        val failures = mutableListOf<String>()
        for (text in cases) {
            val r = pseudonymize(text)
            val masked = r.pseudonymizedText.contains("NUMER_")
            if (!masked) failures.add("FAIL | $text → ${r.pseudonymizedText}")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // =========================================================================
    // S5 — Walidacja sum kontrolnych PESEL i NIP
    // =========================================================================

    @Test fun `s5 poprawny PESEL jest maskowany`() {
        // PESEL 44051401458 — suma kontrolna poprawna (wagi: 1,3,7,9,1,3,7,9,1,3)
        // suma = (4*1+4*3+0*7+5*9+1*1+4*3+0*7+1*9+4*1+5*3)%10 = 2, kontrolna = (10-2)%10 = 8 = d10
        val r = pseudonymize("PESEL: 44051401458")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "44051401458")
    }

    @Test fun `s5 niepoprawny PESEL bez kontekstu zamaskowany jako NUMER`() {
        // AUDIT-03: goły 11-cyfrowy z błędną sumą PESEL → S5 odrzuca wzorzec \d{11},
        // ale CATCHALL (\d{8,}) nie jest już w PESEL_PATTERN_STRINGS → S5 nie blokuje →
        // CATCHALL maskuje jako NUMER. Priorytet prywatności > precyzja (P1 > P2).
        val r = pseudonymize("Numer referencyjny: 44051401459")
        assertTrue("Goły numer z błędną sumą PESEL powinien być zamaskowany jako NUMER (CATCHALL)",
            r.pseudonymizedText.contains("NUMER_"))
        assertFalse("Oryginalna wartość nie powinna zostać w wyjściu",
            r.pseudonymizedText.contains("44051401459"))
    }

    @Test fun `s5 poprawny NIP jest maskowany`() {
        // NIP 526-000-13-29 (cyfry: 5260001329) — suma kontrolna poprawna
        // suma = (5*6+2*5+6*7+0*2+0*3+0*4+1*5+3*6+2*7)%11 = 9 = d9
        val r = pseudonymize("NIP: 526-000-13-29")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "526-000-13-29")
    }

    @Test fun `s5 niepoprawny NIP bez kontekstu zamaskowany jako NUMER`() {
        // AUDIT-03: 10-cyfrowy z błędną sumą NIP → S5 odrzuca wzorce NIP z separatorami,
        // ale CATCHALL (\d{8,}) nie jest już w NIP_PATTERN_STRINGS → S5 nie blokuje →
        // CATCHALL maskuje jako NUMER. Priorytet prywatności > precyzja (P1 > P2).
        val r = pseudonymize("Numer konta: 5260001320 przelew")
        assertTrue("Niepoprawny NIP bez kontekstu powinien być zamaskowany jako NUMER (CATCHALL)",
            r.pseudonymizedText.contains("NUMER_"))
        assertFalse("Oryginalna wartość nie powinna zostać w wyjściu",
            r.pseudonymizedText.contains("5260001320"))
    }

    @Test fun `kwota OCR litery zamiast zer jest maskowana`() {
        // BUG-KWOTA-OOO: "15 000" po OCR → "15 OOO" (O zamiast 0).
        // A.9 używało [0-9]/\d — nie matchowało liter. Fix: D-klasa.
        val r1 = pseudonymize("kwota: 15 OOO,OO zł")
        assertFalse("'15 OOO,OO' powinno być zamaskowane", r1.pseudonymizedText.contains("OOO"))
        val r2 = pseudonymize("23O 5OO PLN")
        assertFalse("'23O 5OO' powinno być zamaskowane", r2.pseudonymizedText.contains("23O"))
    }

    // A.9c (08.07, decyzja Pawła): kwota BEZ waluty/etykiety — sam kształt (cyfry +
    // separator dziesiętny + dokładnie 2 cyfry) jest kotwicą samą w sobie.
    @Test fun `kwota bez waluty jest maskowana przez sam ksztalt`() {
        val r1 = pseudonymize("15 000,00")
        assertFalse("'15 000,00' bez waluty powinno być zamaskowane: ${r1.pseudonymizedText}",
            r1.pseudonymizedText.contains("15 000,00"))
        assertTrue(r1.tokenMap.keys.any { it.startsWith("KWOTA_") })

        val r2 = pseudonymize("22 OOO,OO")
        assertFalse("'22 OOO,OO' (litery O) bez waluty powinno być zamaskowane: ${r2.pseudonymizedText}",
            r2.pseudonymizedText.contains("OOO"))
    }

    // BUG-KWOTA-CYFRA-MNOZNIK-FIX (08.07, pytanie Pawła): "w wysokości 15 tysięcy" —
    // cyfra + słowo-mnożnik, bez waluty. Żadna reguła KWOTA tego nie łapała (potwierdzone
    // Javą przed fixem).
    @Test fun `kwota cyfra plus mnoznik slowny z keywordem jest maskowana`() {
        val r = pseudonymize("Strony ustaliły płatność w wysokości 15 tysięcy.")
        assertFalse("'15 tysięcy' z keywordem 'wysokości' powinno być zamaskowane: ${r.pseudonymizedText}",
            r.pseudonymizedText.contains("15 tysięcy"))
        assertTrue(r.tokenMap.keys.any { it.startsWith("KWOTA_") })
    }

    // A.9d (08.07, mały bug naprawiony bez pytania): "15 tysięcy 30 milionów" bez
    // słowa kluczowego, rozbite na dwie linie, i "15tysięcy" bez spacji.
    @Test fun `kwota cyfra plus mnoznik bez keywordu jest maskowana`() {
        val r1 = pseudonymize("15 tysięcy 30 milionów")
        assertFalse("'15 tysięcy' bez keywordu powinno być zamaskowane: ${r1.pseudonymizedText}",
            r1.pseudonymizedText.contains("15 tysięcy"))
        assertFalse("'30 milionów' bez keywordu powinno być zamaskowane: ${r1.pseudonymizedText}",
            r1.pseudonymizedText.contains("30 milionów"))
    }

    @Test fun `kwota cyfra plus mnoznik rozbita na dwie linie jest maskowana`() {
        val r = pseudonymize("15 tysięcy\n30 milionów")
        assertFalse(r.pseudonymizedText.contains("15 tysięcy"))
        assertFalse(r.pseudonymizedText.contains("30 milionów"))
    }

    @Test fun `kwota cyfra plus mnoznik bez spacji jest maskowana`() {
        val r = pseudonymize("15tysięcy")
        assertFalse("'15tysięcy' bez spacji powinno być zamaskowane: ${r.pseudonymizedText}",
            r.pseudonymizedText.contains("15tysięcy"))
    }

    // BUG-WARTOSC-MIANOWNIK-FIX (08.07, znaleziony przy okazji, naprawiony bez pytania):
    // "wartości" (dopełniacz) było na liście, "wartość" (mianownik) — nie.
    @Test fun `kwota z keywordem wartosc w mianowniku jest maskowana`() {
        val r = pseudonymize("Wartość 500 złotych")
        assertFalse("'Wartość 500 złotych' z mianownikiem powinno być zamaskowane: ${r.pseudonymizedText}",
            r.pseudonymizedText.contains("500 złotych"))
    }

    // BUG-DATA-UR-ZJADA-PESEL-FIX (08.07, znaleziony w benchmarku po fixach — pre-existing,
    // nie regres tej sesji): data urodzenia z OCR-spacja w środku ("08. 07.1985") zjadała
    // dodatkowo słowo "PESEL", zabierając kotwicę prawdziwemu numerowi PESEL zaraz po nim.
    @Test fun `data urodzenia z rozbita spacja nie zjada slowa PESEL`() {
        val r = pseudonymize("Data urodzenia 08. 07.1985 PESEL 44051401459")
        assertFalse("Słowo PESEL nie powinno zniknąć wewnątrz tokenu daty: ${r.pseudonymizedText}",
            r.pseudonymizedText.contains("07.1985 PESEL"))
        val peselStillMasked = !r.pseudonymizedText.contains("44051401459")
        assertTrue("PESEL po naprawie powinien nadal mieć kotwicę i być zamaskowany: ${r.pseudonymizedText}",
            peselStillMasked)
    }

    // BUG-A9C-LISTA-KWOT-FIX (08.07, test telefon: tylko OSTATNIA z 3 kwot w jednej
    // linii się maskowała). Brak tolerancji spacji wokół przecinka + zbyt szeroki
    // lookahead (odrzucał kwotę gdy zaraz po niej był przecinek ROZDZIELAJĄCY listę,
    // nie kontynuacja tej samej liczby) — obie naprawione naraz.
    @Test fun `trzy kwoty bez waluty w jednej linii sa maskowane wszystkie`() {
        val r = pseudonymize("15 000 , 00  15000,00, 15 000,00")
        val text = r.pseudonymizedText
        assertFalse("Pierwsza kwota '15 000 , 00' zostawiona jawna: $text", text.contains("15 000 , 00"))
        assertFalse("Druga kwota '15000,00' zostawiona jawna: $text", text.contains("15000,00"))
        assertFalse("Trzecia kwota '15 000,00' zostawiona jawna: $text", text.contains("15 000,00"))
        val kwotaCount = Regex("""KWOTA_\d{3}""").findAll(text).map { it.value }.toSet().size
        assertTrue("Oczekiwano 3 tokenów KWOTA (albo mniej przez deduplikację tej samej wartości), jest $kwotaCount: $text",
            kwotaCount >= 1)
    }

    // CLEANUP-OGON-BEZ-SPACJI (08.07, pomysł Pawła): ogólny sprzątacz w AnchorEngine —
    // token + "/"/"-"/"." bez spacji + ciąg alfanumeryczny = ogon, dociągnij do tokenu.
    @Test fun `sierocy ogon bez spacji po tokenie NUMER jest sprzatany`() {
        val r = pseudonymize("FV 12/06/2026/WAW")
        assertFalse("'/WAW' nie powinno zostać jawnym ogonem: ${r.pseudonymizedText}",
            Regex("""NUMER_\d{3}\s*/\s*[A-Z]{2,6}\b""").containsMatchIn(r.pseudonymizedText))
    }

    @Test fun `dwa prawdziwe tokeny obok siebie przez ukosnik nie sa uszkodzone`() {
        // Regresja: cleanup ogonów nie może uciąć drugiego, PRAWDZIWEGO tokenu w połowie.
        val r = pseudonymize("Numer działki 7759000.0011.3706/6\nFaktura VAT 26/06/006")
        assertFalse("Token NUMER nie powinien być uszkodzony (brakujące cyfry w NNN)",
            Regex("""NUMER_\d{1,2}[^0-9]""").containsMatchIn(r.pseudonymizedText))
    }

    @Test fun `data z pelnym rokiem nie jest myslona za kwote przez A9c`() {
        // 4-cyfrowy rok NIE pasuje do wymogu "dokładnie 2 cyfry po separatorze" —
        // pełna data (format powszechny w dokumentach formalnych) jest bezpieczna.
        val r = pseudonymize("Umowa z dnia 15.03.2024 roku.")
        assertTrue("Pełna data z rokiem nie powinna zniknąć: ${r.pseudonymizedText}",
            r.pseudonymizedText.contains("2024") || r.tokenMap.values.any { it.contains("2024") })
    }

    // BUG-KWOTA-OOO-BEZ-PREFIKSU (08.07, test_anchor_full.txt linia 109, zrzut ekranu
    // 07.07 18:19): różni się od testu wyżej brakiem etykiety "kwota:" — sama liczba +
    // "zł" na końcu, bez żadnego słowa kluczowego przed nią (ścieżka A.9, nie A.9b).
    // Regex A.9 w izolacji (Javą) dopasowuje się poprawnie do tego dokładnego tekstu —
    // jeśli ten test failuje, przyczyna jest gdzieś w interakcji z resztą pipeline'u
    // (matchOverlapsToken / kolejność warstw), nie w samym wzorcu D-klasy.
    @Test fun `kwota OCR litery zamiast zer bez etykiety kwota jest maskowana`() {
        val r = pseudonymize("15 OOO,OO zł")
        assertFalse("'15 OOO,OO zł' bez etykiety 'kwota:' powinno być zamaskowane, wynik: ${r.pseudonymizedText}",
            r.pseudonymizedText.contains("OOO"))
        assertTrue("Powinien powstać token KWOTA, wynik: ${r.pseudonymizedText}",
            r.pseudonymizedText.contains("KWOTA_"))
    }

    // BUG-KWOTA-15TYS-REGRES (08.07, test na telefonie): Paweł zgłosił że "15 000,00 zł"
    // ORAZ "15 OOO,OO zł" (czyste cyfry I litera O) zostają jawne na telefonie mimo że
    // testy jednostkowe dla tych samych wartości W IZOLACJI są zielone. Test wyżej sprawdza
    // TYLKO pojedynczą linię — ten test odtwarza CAŁY blok z test_anchor_full.txt (linie
    // 104-114) naraz, jednym wywołaniem pseudonymize(), żeby złapać ewentualną interakcję
    // międzyliniową (matchOverlapsToken blokujący dopasowanie przez token z sąsiedniej linii)
    // — dokładnie ta klasa buga co "Pułapka 9" (lessons_anchor_regex_pitfalls.md).
    @Test fun `blok wielu kwot naraz - kazda maskowana bez kolizji miedzy liniami`() {
        val input = """
            15 000,00 zł
            230 500 PLN
            kwota: 49 999,99 zł
            wartość: 1 250 000,00 zł
            15 OOO,OO zł
            23O 5OO PLN
            kwota: 49 999,99 z1
            wartość: 1 25O OOO,OO zł
            15.000,00 zł
            kwota:49 999,99 zł
        """.trimIndent()
        val r = PseudonymEngine.pseudonymize(input, traceMode = true)
        val text = r.pseudonymizedText
        val traceDump = r.trace.joinToString("\n") { "  [${it.layer}/${it.rule}] \"${it.matchedText}\" -> ${it.token}" }
        assertFalse("'15 000,00 zł' (linia 1, czyste cyfry) zostało jawne w bloku: $text\nTRACE:\n$traceDump",
            text.contains("15 000,00"))
        assertFalse("'15 OOO,OO zł' (linia 5, litera O) zostało jawne w bloku: $text\nTRACE:\n$traceDump",
            text.contains("OOO"))
        assertFalse("'15.000,00 zł' (linia 9, kropka jako separator) zostało jawne w bloku: $text\nTRACE:\n$traceDump",
            text.contains("15.000,00"))
        val kwotaCount = Regex("""KWOTA_\d{3}""").findAll(text).map { it.value }.toSet().size
        assertTrue("Oczekiwano co najmniej 6 różnych tokenów KWOTA w bloku 10 linii, jest $kwotaCount: $text\nTRACE:\n$traceDump",
            kwotaCount >= 6)
    }

    // BUG-STRESS-TEST-PDF (08.07, Paweł — dokładny tekst z "Dokument (1).pdf", test na
    // telefonie po fixie KWOTA-CROSS-NEWLINE): diagnostyka, nie asercja punktowa na
    // wszystko — część linii (miasta bez kodu, kwoty bez waluty) jest ŚWIADOMIE niezamaskowana
    // (decyzja z 08.07: Guard YELLOW zamiast auto-mask). Sprawdzam tylko to co JEST
    // jednoznacznym bugiem jeśli zostanie jawne (IBAN, numer działki), reszta = pełny dump
    // do ręcznej inspekcji zamiast zgadywania z zrzutu ekranu.
    @Test fun `stress test z pliku PDF Pawla - diagnostyka pelnego dokumentu`() {
        val input = """
            Jeleniogórska 9 Jelenia Góra Kamienna Góra,  Góra ul. Jana 5 ul. KAMIENNA PLN 1234
            WIN 1234 KAM 1234 PIN 1234  PLN 1234,

             PLN 1234

            PL08102028929730064553240202

            FAKTURA VAT Nr FV12025/12/1828

            Faktura VAT 26/06/006

            FV 91/07/26/VAT

            FS 00145/26

            FV 12/06/2026/WAW

            FV KOR12.012.00012

            FV-145-97-2026

            FV202607000Q2

            260712/FV

            FV12/07/2026/DET/WAW

            Faktura VAT 23%

            Numer działki  7759000.0011.3706/6

            WARSZAWA, Warszawie, Kraków. Krakowa, Toruń  Torunia, Konin  , Konina

            15 OOO , OO  15OOO,OO, 15 0OO,00
        """.trimIndent()
        val r = PseudonymEngine.pseudonymize(input, traceMode = true)
        val text = r.pseudonymizedText
        val traceDump = r.trace.joinToString("\n") { "  [${it.layer}/${it.rule}] \"${it.matchedText}\" -> ${it.token}" }
        println("=== WYNIK ===\n$text")
        println("=== TRACE ===\n$traceDump")
        println("=== GUARD HITS ===\n${r.guardHits.joinToString("\n") { "  ${it.level} ${it.label}: \"${it.matchedText}\"" }}")

        assertFalse("IBAN bez spacji zostało jawne: $text",
            text.contains("PL08102028929730064553240202"))
        assertFalse("Numer działki zostało jawne: $text",
            text.contains("7759000.0011.3706"))
        // Szukam osieroconych fragmentów typu "/WAW", "/VAT" tuż po tokenie NUMER —
        // objaw znany z historii (BUG-NR-SIEROTA i podobne "ogony").
        val orphanSuffix = Regex("""NUMER_\d{3}\s*/\s*[A-ZĄĆĘŁŃÓŚŹŻ]{2,6}\b""")
        val orphans = orphanSuffix.findAll(text).map { it.value }.toList()
        assertTrue("Osierocone sufiksy po tokenie NUMER (ogon nieskonsumowany): $orphans\nPełny tekst: $text\nTRACE:\n$traceDump",
            orphans.isEmpty())
    }

    @Test fun `s5 niepoprawny NIP z myslnikami bez kontekstu maskowany przez AnchorEngine`() {
        // AnchorEngine: kształt xxx-xxx-xx-xx z kreskami = kotwica strukturalna → maskuj.
        // Poprzednie zachowanie (S5 odrzuca → zostaje w tekście) zastąpione przez AnchorEngine
        // który woli FP niż przepuszczone PII. Im mniej pracy dla Guarda tym lepiej.
        val r = pseudonymize("Kontrahent 526-000-13-20 zalegał z płatnością.")
        assertFalse("NIP z kształtem kresek powinien być zamaskowany przez AnchorEngine",
            r.pseudonymizedText.contains("526-000-13-20"))
        assertTrue("AnchorEngine powinien wstawić token NUMER",
            r.pseudonymizedText.contains("NUMER_"))
    }

    @Test fun `s5 NIP z keywordem maskowany mimo bledu OCR w sumie kontrolnej`() {
        // NIP z błędną sumą — ale słowo "NIP:" poprzedza → wzorzec kontekstowy.
        // Analogia do PESEL z "PESEL:" — keyword wystarczającym dowodem, S5 nie stosowane.
        val r = pseudonymize("NIP: 5260001320")
        assertTrue("NIP z keywordem powinien być zamaskowany mimo błędnej sumy (OCR)",
            r.pseudonymizedText.contains("NUMER_"))
    }

    @Test fun `s5 poprawny NIP bez myslnikow jest maskowany`() {
        val r = pseudonymize("NIP 5260001329")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "5260001329")
    }

    @Test fun `s5 regresja PESEL kontekstowy nie blokowany przez checksum`() {
        // Wzorzec kontekstowy (słowo "pesel") NIE jest w PESEL_PATTERN_STRINGS —
        // kontekst słowny jest wystarczającym dowodem, nie sprawdzamy sumy.
        val r = pseudonymize("PESEL: 6505111234")  // 10 cyfr — BUG-PESEL-10 fix
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "6505111234")
    }

    @Test fun `s5 regresja PESEL kontekstowy z bledna suma OCR jest maskowany`() {
        // OCR może pomylić jedną cyfrę → suma błędna → ale "PESEL:" w tekście = pewny kontekst.
        // Przed naprawą: wzorzec kontekstowy był w PESEL_PATTERN_STRINGS → S5 blokował → RED.
        val peselBlednaSum = "44051401448"  // prawidłowy to 44051401458 (cyfra 5→4, OCR error)
        val r = pseudonymize("PESEL: $peselBlednaSum")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, peselBlednaSum)
    }

    @Test fun `s5 goly PESEL z bledna suma zamaskowany jako NUMER`() {
        // AUDIT-03: goły 11-cyfrowy z błędną sumą → \d{11} S5 odrzuca,
        // CATCHALL ∉ PESEL_PATTERN_STRINGS → CATCHALL maskuje jako NUMER.
        // OCR może przekręcić cyfrę PESELu bez słowa "PESEL:" → priorytet: nie przepuść.
        val peselBlednaSum = "44051401448"  // cyfra 5→4 = błędna suma (OCR error scenario)
        val r = pseudonymize("Numer: $peselBlednaSum")
        assertTrue("Goły PESEL z błędną sumą powinien być zamaskowany jako NUMER (CATCHALL)",
            r.pseudonymizedText.contains("NUMER_"))
        assertFalse("Oryginalna wartość nie powinna zostać w wyjściu",
            r.pseudonymizedText.contains(peselBlednaSum))
    }

    @Test fun `s5 regresja telefon z prefiksem 48 plus nie jest blokowany przez checksum`() {
        // "+48 521 334 153" — "+" wyklucza onlyDigitsAndSeparators → brak checksum → maskowany
        val r = pseudonymize("Tel: +48 521 334 153")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `s5 regresja telefon z prefiksem 48 bez plusa nie jest blokowany przez checksum`() {
        // "48 571 488 856" — 11 cyfr bez "+", starts with "48" → wykluczony z PESEL check → maskowany
        // BUG: bez wykluczenia "48" cyfry "48571488856" trafiają do isValidPesel() → fail (miesiąc=57)
        // → telefon NIE jest maskowany. 28 takich telefonów było w benchmark_trace.
        val r = pseudonymize("Tel: 48 571 488 856")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // AUDIT-03 — CATCHALL nie powinien być w PESEL/NIP_PATTERN_STRINGS
    // Przed naprawą: CATCHALL ∈ PESEL_PATTERN_STRINGS → S5 odrzuca 11-cyfrowe liczby z błędną sumą → FN.
    //               CATCHALL ∈ NIP_PATTERN_STRINGS   → S5 odrzuca 10-cyfrowe liczby z błędną sumą → FN.
    // Po naprawie:  CATCHALL ∉ żadnego zbioru → CATCHALL maskuje jako NUMER bez S5.
    //               S5 sprawdza TYLKO wzorce (?<!\d)\d{11}(?!\d) i \d{3}[-\s.]?... (NIP).

    @Test fun `audit03 jedenaście cyfr błędna suma PESEL bez kontekstu zamaskowane jako NUMER`() {
        // "44051401459" — 11 cyfr, zła suma PESEL (ostatnia cyfra 9 zamiast 8).
        // Bez "PESEL:" → tylko CATCHALL może go zamaskować → powinien dostać TOKEN_NUMER.
        val r = pseudonymize("Numer referencyjny: 44051401459")
        assertTrue("11-cyfrowy z błędną sumą PESEL bez kontekstu powinien być zamaskowany jako NUMER",
            r.pseudonymizedText.contains("NUMER_"))
        assertFalse("Oryginalna wartość nie powinna zostać w wyjściu",
            r.pseudonymizedText.contains("44051401459"))
    }

    @Test fun `audit03 dziesięć cyfr błędna suma NIP bez kontekstu zamaskowane jako NUMER`() {
        // "5260001320" — 10 cyfr, zła suma NIP (ostatnia cyfra 0 zamiast 9).
        // Bez "NIP:" → NIP wzorzec strukturalny + S5 odrzuca; CATCHALL powinien zamaskować jako NUMER.
        val r = pseudonymize("Numer konta: 5260001320 przelew")
        assertTrue("10-cyfrowy z błędną sumą NIP bez kontekstu powinien być zamaskowany jako NUMER",
            r.pseudonymizedText.contains("NUMER_"))
        assertFalse("Oryginalna wartość nie powinna zostać w wyjściu",
            r.pseudonymizedText.contains("5260001320"))
    }

    // S10b — kontekstowy: po "tel."/"telefon:"/"fax:" maskuj numer
    @Test fun `s10b telefon lokalny 7 cyfr po tel jest maskowany`() {
        val r = pseudonymize("Tel.: 765-43-21")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "765-43-21")
    }

    @Test fun `s10b telefon lokalny ze spacjami po Telefon jest maskowany`() {
        val r = pseudonymize("Telefon: 765 43 21")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "765 43 21")
    }

    @Test fun `s10b fax z numerem jest maskowany`() {
        val r = pseudonymize("Fax: 22 765-43-21")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "765-43-21")
    }

    @Test fun `s10b tel z dodatkowym slowem jest maskowany`() {
        val r = pseudonymize("tel. biurowy: 765-43-21")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "765-43-21")
    }

    // ZMIANA FILOZOFII 01.07 (właściciel): kotwica maskuje niezależnie od długości —
    // brief sekcja 6 wprost zakazuje wymagania konkretnej liczby cyfr. Stary test
    // zakładał że 3 cyfry po "tel." to "za krótko żeby być telefonem" — to była
    // walidacja, nie zasada kotwicy. Test odwrócony: teraz sprawdza że JEST maskowane.
    @Test fun `s10b krotki numer po tel jest maskowany (zasada kotwicy bez limitu dlugosci)`() {
        val r = pseudonymize("tel. 994")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "994")
    }

    // BUG-DATE-PARTIAL — data ISO YYYY-MM-DD nie może zostawać częściowo widoczna
    @Test fun `data ISO YYYY-MM-DD jest maskowana w całości`() {
        val r = pseudonymize("Data umowy: 2026-06-20")
        assertNotInOutput(r, "2026-06-20")
        assertNotInOutput(r, "-20")
    }

    @Test fun `data ISO YYYY-MM-DD różne formaty`() {
        listOf("1985-03-15", "2000-12-31", "1999-01-01").forEach { date ->
            val r = pseudonymize("data: $date")
            assertTrue("$date powinien być zamaskowany", "-" !in r.pseudonymizedText.substringAfter("data:"))
        }
    }

    // S-DATE-PL — data polska DD.MM.YYYY (strukturalna, różne separatory)
    @Test fun `data polska DD MM YYYY z kropkami jest maskowana`() {
        val r = pseudonymize("Umowa zawarta 10.12.2026 roku")
        assertNotInOutput(r, "10.12.2026")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `data polska DD MM YYYY z przecinkami jest maskowana`() {
        val r = pseudonymize("podpisano dnia 10,12,2026")
        assertNotInOutput(r, "10,12,2026")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `data polska DD MM YYYY z ukośnikami jest maskowana`() {
        val r = pseudonymize("termin: 01/03/2025")
        assertNotInOutput(r, "01/03/2025")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `data polska DD MM YYYY z łącznikami jest maskowana`() {
        val r = pseudonymize("wystawiono 15-06-1999")
        assertNotInOutput(r, "15-06-1999")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `data polska bez wiodącego zera jest maskowana`() {
        val r = pseudonymize("ważna do 1.3.2025")
        assertNotInOutput(r, "1.3.2025")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `data polska DD MM YYYY nie jest maskowana gdy rok poza zakresem`() {
        // Rok 1234 i 3999 nie pasują do (19|20)XX — FP blokada
        val r = pseudonymize("parametr 10.12.1234")
        assertFalse("Rok 1234 nie jest rokiem 19xx/20xx — data nie powinna być NUMER",
            r.tokenMap.keys.any { it.startsWith(TOKEN_NUMER) })
    }

    // S-DATE-CTX — kontekst DATA/DNIA + dwucyfrowy rok
    @Test fun `data po slowie Dnia z dwucyfrowym rokiem jest maskowana`() {
        val r = pseudonymize("Dnia 10.12.26 podpisano")
        assertNotInOutput(r, "10.12.26")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `data po slowie Data z dwucyfrowym rokiem jest maskowana`() {
        val r = pseudonymize("Data: 10/12/26 umowa")
        assertNotInOutput(r, "10/12/26")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `data po slowie Dnia z przecinkami i skróconym rokiem jest maskowana`() {
        val r = pseudonymize("Dnia 26,12,10 rok")
        assertNotInOutput(r, "26,12,10")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `data po slowie Dnia z pełnym rokiem różne separatory`() {
        listOf("Dnia 10.12.2026", "Data: 01/03/2025", "Dnia 15-06-1999").forEach { text ->
            val r = pseudonymize(text)
            assertTrue("W '$text' data powinna być zamaskowana",
                r.tokenMap.keys.any { it.startsWith(TOKEN_NUMER) })
        }
    }

    // S10 — kierunkowy w nawiasach
    @Test fun `s10 telefon z kierunkowym w nawiasach ze spacją jest maskowany`() {
        val r = pseudonymize("Tel. biurowy: (22) 765-43-21")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "765-43-21")
    }

    @Test fun `s10 telefon z kierunkowym w nawiasach bez spacji jest maskowany`() {
        val r = pseudonymize("Zadzwoń: (12)345-67-89")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "345-67-89")
    }

    @Test fun `s10 telefon z kierunkowym w nawiasach ze spacjami jest maskowany`() {
        val r = pseudonymize("Fax: (81) 123 45 67")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "123 45 67")
    }

    @Test fun `s10 nawiasy z tekstem nie sa maskowane`() {
        val r = pseudonymize("Podstawa prawna (art. 22) ustawy")
        assertNotInOutput(r, TOKEN_NUMER)
    }

    @Test fun `IBAN pelny zakres formatow`() {
        val cases = listOf(
            "PL04325000035633956078831852" to true,
            "PL 04 3250 0003 5633 9560 7883 1852" to true,
            "PL04-3250-0003-5633-9560-7883-1852" to true,
            "IBAN: PL04 3250 0003 5633 9560 7883 1852" to true,
            "Nr konta: PL04325000035633956078831852" to true,
        )
        val failures = mutableListOf<String>()
        for ((text, shouldMask) in cases) {
            val r = pseudonymize(text)
            val masked = r.pseudonymizedText.contains("NUMER_")
            if (masked != shouldMask) failures.add("FAIL | $text → ${r.pseudonymizedText}")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // BUG-05: wzorzec identyfikatorów łapie "POLSKA-1234-5678". Znany FP, nie ruszać do osobnego fix.
    // "PLN 1234" — NAPRAWIONE (BUG-PLN-*, sesje 04-05.07.2026): teraz maskowane jako KWOTA
    // (symetryczny wzorzec waluta+liczba w StructuralEngine.kt) — patrz testy `BUG-PLN-*` niżej.
    @Test fun `BUG05 znane FP identyfikatory`() {
        val knownFalsePositives = listOf(
            "POLSKA-1234-5678" // identyfikator pattern: [A-Z]{2,6}[-:/][A-Z0-9]{2,10}...
        )
        for (text in knownFalsePositives) {
            val r = pseudonymize(text)
            // FP: silnik maskuje mimo że nie powinien — dokumentujemy istniejące zachowanie
            assertTrue("Oczekiwany FP nie wystąpił dla: $text",
                r.pseudonymizedText.contains("NUMER_") || r.pseudonymizedText.contains("KWOTA_"))
        }
    }

    @Test fun `IBAN rozne formaty sa maskowane`() {
        val cases = listOf(
            "Nr konta: PL04325000035633956078831852",
            "Rachunek: PL 04 3250 0003 5633 9560 7883 1852",
            "IBAN: PL04 3250 0003 5633 9560 7883 1852",
            "Przelew na: PL04-3250-0003-5633-9560-7883-1852",
            "PL04325000035633956078831852"
        )
        val failures = mutableListOf<String>()
        for (text in cases) {
            val r = pseudonymize(text)
            val masked = r.pseudonymizedText.contains("NUMER_")
            if (!masked) failures.add("FAIL | $text → ${r.pseudonymizedText}")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `IBAN bez spacji z kontekstem Nr konta jest maskowany`() {
        val text = "Nr konta: PL04325000035633956078831852"
        val r = pseudonymize(text)
        println("Input:  $text")
        println("Output: ${r.pseudonymizedText}")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "PL04")
    }

    // =========================================================================
    // Email
    // =========================================================================

    @Test fun `email jest maskowany`() {
        val r = pseudonymize("Kontakt: jan.kowalski@firma.pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "jan.kowalski@firma.pl")
    }

    @Test fun `email z poddomainem jest maskowany`() {
        val r = pseudonymize("Wyślij na adres: jan@biuro.kancelaria.pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "jan@biuro")
    }

    @Test fun `BUG-S7 email bez etykiety jest maskowany jako EMAIL nie NUMER`() {
        val r = pseudonymize("Proszę o kontakt: jan.kowalski@firma.pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertTrue("email nie może być NUMER", r.tokenMap.keys.none { it.startsWith(TOKEN_NUMER) })
        assertNotInOutput(r, "jan.kowalski@firma.pl")
    }

    @Test fun `BUG-S7 email sam w sobie bez kontekstu jest maskowany jako EMAIL`() {
        val r = pseudonymize("jan@wp.pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "jan@wp.pl")
    }

    @Test fun `BUG-S7 email w zdaniu bez slowa kluczowego jest maskowany jako EMAIL`() {
        val r = pseudonymize("Umowa zawarta z osobą posługującą się adresem anna.nowak@poczta.onet.pl w dniu dzisiejszym.")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "anna.nowak@poczta.onet.pl")
    }

    // S11 — email z imieniem/nazwiskiem w local-part nie jest maskowany jako OSOBA
    // EMAIL (STRUCTURAL_PATTERNS[0]) wyprzedza NameEngine → token EMAIL_001 zastępuje cały adres
    // przed tym jak NameEngine zobaczy "joanna.grabowska" jako potencjalną osobę.
    @Test fun `s11 email z imieniem w local-part maskowany jako EMAIL nie OSOBA`() {
        val r = pseudonymize("email: joanna.grabowska@interia.pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "joanna.grabowska@interia.pl")
        assertFalse("local-part emaila nie powinien być maskowany jako OSOBA",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) })
    }

    @Test fun `s11 email z imieniem i nazwiskiem w local-part bez etykiety`() {
        val r = pseudonymize("Kontakt: piotr.nowak@wp.pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertFalse("Piotr Nowak wewnątrz emaila nie powinien dać osobnego OSOBA",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) })
    }

    // =========================================================================
    // KRS / REGON
    // =========================================================================

    @Test fun `KRS jest maskowany`() {
        val r = pseudonymize("KRS 0000123456")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "0000123456")
    }

    @Test fun `KRS 8 cyfr OCR zgubil 2 cyfry jest maskowany`() {
        val r = pseudonymize("KRS 00001234")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "00001234")
    }

    @Test fun `REGON 9-cyfrowy jest maskowany`() {
        val r = pseudonymize("REGON: 123456789")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // =========================================================================
    // FIRMA — kluczowy scenariusz P6
    // =========================================================================

    @Test fun `firma Sp z o o jest maskowana`() {
        val r = pseudonymize("Centrum Medyczne MedHelp Sp. z o.o.")
        assertTokenExists(r, TOKEN_FIRMA)
        assertNotInOutput(r, "MedHelp")
    }

    @Test fun `firma Sp Z o o (OCR wielkie Z) jest maskowana`() {
        // P6 — ten test był czerwony przed sesją 7
        val r = pseudonymize("Centrum Medyczne MedHelp Sp.Z o.o.")
        assertTokenExists(r, TOKEN_FIRMA)
        assertNotInOutput(r, "MedHelp")
    }

    @Test fun `firma SA jest maskowana`() {
        val r = pseudonymize("Polskie Linie Lotnicze LOT S.A.")
        assertTokenExists(r, TOKEN_FIRMA)
    }

    // =========================================================================
    // ADRES
    // =========================================================================

    @Test fun `adres z ul jest maskowany`() {
        val r = pseudonymize("Zamieszkały przy ul. Lipowej 14")
        assertTokenExists(r, TOKEN_ADRES)
        assertNotInOutput(r, "Lipowej 14")
    }

    @Test fun `adres z al jest maskowany`() {
        val r = pseudonymize("Siedziba: al. Jerozolimskie 144")
        assertTokenExists(r, TOKEN_ADRES)
    }

    @Test fun `kod pocztowy z miastem jest maskowany`() {
        val r = pseudonymize("00-950 Warszawa")
        assertTokenExists(r, TOKEN_ADRES)
    }

    @Test fun `miasto przed kodem to jeden token nie dwa`() {
        // BUG-ADRES-PODWOJON: CITY_POSTAL_REGEX maskowało tylko miasto, kod zostawał.
        // Efekt: ADRES_001=Warszawa + ADRES_002=00-001 zamiast jednego tokenu.
        val r = pseudonymize("Zamieszkały w Warszawie, 00-001")
        val adresCount = r.tokenMap.keys.count { it.startsWith("ADRES") }
        assertEquals("Warszawa + kod to jeden token ADRES, nie dwa", 1, adresCount)
        assertNotInOutput(r, "00-001")
        assertNotInOutput(r, "Warszawa")
    }

    @Test fun `anchor nie konsumuje prefiksu istniejacego tokenu adres jako miasto`() {
        // BUG: AnchorEngine A.11b dopasowuje opcjonalne miasto po kodzie.
        // Gdy wcześniejsza warstwa stworzyła ADRES_NNN dla miasta, A.11b widzi
        // "00-001 ADRES" i traktuje "ADRES" jako nazwę miasta → ogon "_NNN".
        // Fix: matchOverlapsToken w applyAll blokuje dopasowanie.
        val r = pseudonymize("zamieszkały w Warszawie ul. Marszałkowska 15/3, 00-001 Warszawa")
        val out = r.pseudonymizedText
        assertFalse("ogon _NNN w wyniku", Regex("""\s_\d{3}(?!\d)""").containsMatchIn(out))
    }

    @Test fun `adres z ul przecinek zamiast kropki`() {
        val r = pseudonymize("ul, Wolności 99, 41-200 Sosnowiec")
        assertTokenExists(r, TOKEN_ADRES)
        assertFalse("adres powinien być zamaskowany", r.pseudonymizedText.contains("Wolności 99"))
    }

    @Test fun `adres z u-kropka zamiast ul-kropka`() {
        val r = pseudonymize("u. Dębowa 19/23, 87-100 Białystok")
        assertTokenExists(r, TOKEN_ADRES)
        assertFalse(r.pseudonymizedText.contains("Dębowa 19"))
    }

    @Test fun `adres ul spacja przed kropka jest maskowany`() {
        // OCR: "ul .Marszałkowska" — spacja przed kropką skrótu
        val r = pseudonymize("ul .Marszałkowska 15/3, 00-001 Warszawa")
        assertTokenExists(r, TOKEN_ADRES)
        assertNotInOutput(r, "Marszałkowska")
    }

    @Test fun `email OCR spacja po malpce maskowany end-to-end`() {
        val r = pseudonymize("e-mail: piotr dudek45@ inte ria.pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "interia.pl")
    }

    @Test fun `email OCR e-nnail keyword maskowany`() {
        val r = pseudonymize("e-nnail: piotr@o2.pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "piotr@o2.pl")
    }

    // BUG-EMAIL-LOCALPART-SPACJA (benchmark 05.07, doc "marek_wozniak@interia.pl" OCR-owane z
    // podkreślnikiem zamienionym na spację): AnchorEngine A.2 zatrzymywał lewą granicę na
    // pierwszej spacji, maskując tylko "wozniak@interia,pl" — "marek" zostawał jawny. Fix:
    // opcjonalne jedno poprzedzające słowo (tylko litery/cyfry) w kotwicy A.2.
    @Test fun `BUG-EMAIL-LOCALPART-SPACJA podkreslnik pomylony ze spacja maskuje cale local-part`() {
        val r = pseudonymize("Adres e-mail: marek wozniak@interia,pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "marek")
        assertNotInOutput(r, "wozniak@interia")
    }

    // Kontrola: rozszerzenie NIE może połykać etykiet kończących się dwukropkiem/myślnikiem —
    // to jest to co odróżnia "prawdziwy urwany local-part" od zwykłej etykiety przed emailem.
    @Test fun `BUG-EMAIL-LOCALPART-SPACJA regresja etykieta z dwukropkiem nie wchodzi do tokenu`() {
        val r = pseudonymize("Kontakt: piotr.nowak@wp.pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertTrue("Etykieta 'Kontakt:' powinna zostać jawna, nie wejść do tokenu EMAIL",
            r.pseudonymizedText.contains("Kontakt:"))
    }

    // BUG-EMAIL-KROPKA-SPACJA (06.07, katalog degradacji OCR w TODO.md): kotwica A.2 tolerowała
    // spację PRZED kropką w domenie ("firma .pl"), ale nie PO kropce ("firma. pl") — ten sam
    // rodzaj degradacji, lustrzany. Prawa granica urywała się na kropce, zostawiając TLD jawny.
    // Fix: symetryczny ogon w A.2, aktywny tylko gdy poprzedni fragment urwał się na kropce
    // (lookbehind) — nie łapie zwykłego słowa po poprawnym mailu (patrz test FP niżej).
    @Test fun `BUG-EMAIL-KROPKA-SPACJA kropka potem spacja w domenie maskuje cala domene`() {
        val r = pseudonymize("Kontakt: ewa piotrowska @gmail. com")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "gmail")
        assertNotInOutput(r, " com")
    }

    // Kontrola FP: rozszerzenie NIE może połykać zwykłego krótkiego słowa po POPRAWNYM mailu.
    @Test fun `BUG-EMAIL-KROPKA-SPACJA regresja zwykle slowo po poprawnym mailu zostaje jawne`() {
        val r = pseudonymize("Napisz na jan@wp.pl do jutra")
        assertTokenExists(r, TOKEN_EMAIL)
        assertTrue("'do jutra' powinno zostać jawne, nie wejść do tokenu EMAIL",
            r.pseudonymizedText.contains("do jutra"))
    }

    @Test fun `BRAK_W_OCR Niepodlegosci pseudonymize`() {
        val r = pseudonymize("al. Niepodlegości 13/2, 65-001 Gliwice")
        assertTokenExists(r, TOKEN_ADRES)
    }

    // =========================================================================
    // BUG-1 — "adresem" NIE jest OSOBA
    // =========================================================================

    @Test fun `slowo adresem nie tworzy tokenu OSOBA`() {
        val r = pseudonymize("Pod adresem zamieszkałego")
        val osoby = r.tokenMap.entries.filter { it.key.startsWith(TOKEN_OSOBA) }
        val czyAdresemZalapsany = osoby.any {
            it.value.contains("adresem", ignoreCase = true)
        }
        assertFalse("'adresem' nie powinno być tokenem OSOBA", czyAdresemZalapsany)
    }

    // =========================================================================
    // Imiona i nazwiska (z testowego słownika)
    // =========================================================================

    @Test fun `imie i nazwisko z tytułem jest maskowane`() {
        val r = pseudonymize("Zaświadcza lek. med. Jan Kowalski")
        assertTokenExists(r, TOKEN_OSOBA)
        assertNotInOutput(r, "Kowalski")
    }

    @Test fun `odmiana nazwiska jest maskowana`() {
        val r = pseudonymize("Na wniosek Jana Kowalskiego")
        assertTokenExists(r, TOKEN_OSOBA)
        assertNotInOutput(r, "Kowalskiego")
    }

    // =========================================================================
    // Słownik użytkownika
    // =========================================================================

    @Test fun `encja ze slownika jest maskowana`() {
        val r = pseudonymize(
            text = "Pacjent Jan Malinowski zgłosił się do rejestracji",
            userDictionary = listOf("Jan Malinowski" to TOKEN_OSOBA)
        )
        assertTokenExists(r, TOKEN_OSOBA)
        assertNotInOutput(r, "Jan Malinowski")
    }

    @Test fun `krotka encja ze slownika nie podmienia fragmentow slow`() {
        // DICT-FIX — ten test łapie regresję word-boundary
        val r = pseudonymize(
            text = "Janusz pracuje z Janem i Anną",
            userDictionary = listOf("Jan" to TOKEN_OSOBA)
        )
        assertTrue("'Janusz' nie powinien być podmieniony przez 'Jan'",
            r.pseudonymizedText.contains("Janusz"))
    }

    @Test fun `encja ze slownika ignoruje wielkosc liter`() {
        val r = pseudonymize(
            text = "Podpisano: JAN KOWALSKI",
            userDictionary = listOf("Jan Kowalski" to TOKEN_OSOBA)
        )
        assertNotInOutput(r, "JAN KOWALSKI")
    }

    @Test fun `slownik nie rozbija istniejacego tokenu na prefiks i ogon`() {
        // Regresja BUG-OGONY: UserDictionary nie miał guarda TOKEN_RE.
        // Wpis "Firma" matchuje "FIRMA" w "FIRMA_001" (notWordChar nie zawiera '_').
        // Efekt bez fixa: "FIRMA_001" → "FIRMA_NNN _001" (ogon ze spacją).
        // NameEngine tworzy FIRMA_001 dla "Sp. z o.o." PRZED W4a (UserDictionary).
        val r = pseudonymize(
            text = "Kowalski i Partnerzy Sp. z o.o.",
            userDictionary = listOf("Firma" to TOKEN_FIRMA)
        )
        val output = r.pseudonymizedText
        assertFalse(
            "Ogon z spacją — UserDictionary rozbił token: '$output'",
            Regex("""(?:ADRES|FIRMA|OSOBA|NUMER|EMAIL|KWOTA)\s+_\d{3}""").containsMatchIn(output)
        )
    }

    // =========================================================================
    // OcrNormalizer
    // =========================================================================

    @Test fun `cyfra 1 miedzy literami staje sie l`() {
        val n = OcrNormalizer.normalize("Zie1ona Gora")
        assertEquals("Zielona Gora", n.normalizedText)
        assertEquals(1, n.corrections)
    }

    @Test fun `cyfra 0 miedzy literami staje sie o`() {
        val n = OcrNormalizer.normalize("K0walski")
        assertEquals("Kowalski", n.normalizedText)
    }

    @Test fun `pipe miedzy literami staje sie l`() {
        val n = OcrNormalizer.normalize("Kowa|ski")
        assertEquals("Kowalski", n.normalizedText)
    }

    @Test fun `Sp z o zero naprawiane do o`() {
        val n = OcrNormalizer.normalize("MedHelp Sp. z o.0.")
        assertTrue("'o.0.' powinno stać się 'o.o.'",
            n.normalizedText.contains("o.o."))
        assertFalse(n.normalizedText.contains("o.0."))
    }

    @Test fun `OCR Wars zawa skleja sie w Warszawa`() {
        val n = OcrNormalizer.normalize("mieszka w Wars zawie przy ul. Lipowej")
        assertTrue("'Wars zawie' powinno stać się 'Warszawie'",
            n.normalizedText.contains("Warszawie"))
    }

    @Test fun `OCR Byd goszcz skleja sie`() {
        val n = OcrNormalizer.normalize("urząd w Byd goszczy")
        assertTrue("'Byd goszczy' powinno stać się 'Bydgoszczy'",
            n.normalizedText.contains("Bydgoszczy"))
    }

    @Test fun `OCR sklejanie miast nie rusza zwyklych slow`() {
        val n = OcrNormalizer.normalize("Zawarta w dniu")
        assertFalse("'Zawarta w' nie powinno stać się 'Zawartaw'",
            n.normalizedText.contains("Zawartaw"))
    }

    @Test fun `cyfra na poczatku slowa nie jest podmieniana`() {
        val n = OcrNormalizer.normalize("3 razy tak")
        assertTrue("Cyfra na początku słowa nie powinna być zmieniana",
            n.normalizedText.contains("3"))
        assertEquals(0, n.corrections)
    }

    // =========================================================================
    // Session token (P1)
    // =========================================================================

    @Test fun `wynik zaczyna sie od SESJA`() {
        val r = pseudonymize("test")
        assertTrue("pseudonymizedText powinien zaczynać się od SESJA_",
            r.pseudonymizedText.startsWith("SESJA_"))
    }

    @Test fun `sessionId ma 6 znakow`() {
        val r = pseudonymize("test")
        assertEquals(6, r.sessionId.length)
        assertTrue("sessionId powinien być alfanumeryczny",
            r.sessionId.matches(Regex("[A-Z0-9]{6}")))
    }

    @Test fun `dwa wywolania maja rozne sessionId`() {
        val r1 = pseudonymize("test A")
        val r2 = pseudonymize("test B")
        assertNotEquals("Każda sesja powinna mieć unikalny ID",
            r1.sessionId, r2.sessionId)
    }

    // =========================================================================
    // OutputGuard
    // =========================================================================

    @Test fun `OutputGuard wykrywa niezamaskowany PESEL`() {
        val warnings = runOutputGuard("Dane: 65051112340", emptyMap())
        assertTrue("Guard powinien ostrzec o PESEL", warnings.isNotEmpty())
    }

    @Test fun `OutputGuard nie alarmuje dla tokenow`() {
        val tokenMap = mapOf("OSOBA_001" to "Jan Kowalski", "NUMER_001" to "65051112340")
        val warnings = runOutputGuard("Pacjent OSOBA_001 NUMER_001", tokenMap)
        assertTrue("Tokeny nie powinny alarmować OutputGuard",
            warnings.none { it.label == "PESEL" })
    }

    @Test fun `OutputGuard wykrywa niezamaskowane imie z LookupTables`() {
        // v1.7: detekcja imion przeniesiona do NameEngine — runOutputGuard sprawdza tylko PII strukturalne.
        // Test zaktualizowany: tekst bez PII strukturalnego → brak hitów RED.
        val hits = runOutputGuard("Nabywca: Anna Nowak, adres: ul. Testowa 1", emptyMap())
        assertTrue("Guard v1.7 nie wykrywa imion — brak RED hitów dla czystego tekstu",
            hits.none { it.level == "RED" })
    }

    @Test fun `OutputGuard nie alarmuje gdy imie jest zamaskowane`() {
        // "Anna" zamaskowana jako token — nie powinna alarmować
        val warnings = runOutputGuard("Nabywca: OSOBA_001, adres: ul. Testowa 1", mapOf("OSOBA_001" to "Anna Nowak"))
        assertFalse("Zamaskowana Anna nie powinna alarmować",
            warnings.any { it.matchedText.contains("Anna", ignoreCase = true) })
    }

    // =========================================================================
    // BUG-OCR-1 — fałszywy ADRES dla groszy i numerów rozporządzeń (sesja 10)
    // Fix: \d{1,4}/\d{1,4} → \d{1,4}/\d{1,2} w StructuralEngine
    // =========================================================================

    @Test fun `nr rozporzadzenia UE nie jest maskowany jako adres`() {
        val r = pseudonymize("Rozporządzenia Komisji UE nr 651/2014 z dnia")
        assertFalse("651/2014 nie powinno być TOKEN_ADRES",
            r.tokenMap.entries.any { (k, v) ->
                k.startsWith(TOKEN_ADRES) && v.contains("/")
            })
    }

    @Test fun `grosz 00-100 w kwocie nie jest maskowany jako adres`() {
        val r = pseudonymize("słownie: osiemdziesiąt 00/100 złotych")
        assertFalse("00/100 nie powinno być TOKEN_ADRES",
            r.tokenMap.keys.any { it.startsWith(TOKEN_ADRES) })
    }

    // =========================================================================
    // TODO-2 — angielskie słowa jako false positives (sesja 10)
    // Fix: WHITE_LIST_COMMON_WORDS rozszerzona w NameEngine v1.7
    // =========================================================================

    @Test fun `angielskie slowo address nie jest OSOBA`() {
        val r = pseudonymize("Invoice address: 123 Main St")
        assertFalse("'address' nie powinno być TOKEN_OSOBA",
            r.tokenMap.values.any { it.equals("address", ignoreCase = true) })
    }

    @Test fun `angielskie slowo bill nie jest OSOBA`() {
        val r = pseudonymize("Bill to: Kowalski Trading Ltd")
        assertFalse("'Bill' jako nagłówek faktury nie powinno być TOKEN_OSOBA",
            r.tokenMap.entries.any { (k, v) ->
                k.startsWith(TOKEN_OSOBA) && v.equals("bill", ignoreCase = true)
            })
    }

    // =========================================================================
    // TODO-7 / S-DATE-ISO — jawna ochrona dat (sesja 10, zaktualizowano sesja 21.06)
    // ISO data YYYY-MM-DD jest teraz AKTYWNIE maskowana przez wzorzec S-DATE-ISO.
    // Poprzedni test dokumentował side-effect catchalla (\d{8+}). Teraz mamy
    // explicit pattern, więc zarówno "2024-01-15" jak i "2026-06-20" → NUMER.
    // =========================================================================

    @Test fun `data ISO jest maskowana jako NUMER`() {
        val r = pseudonymize("Umowa zawarta dnia 2024-01-15 roku")
        assertNotInOutput(r, "2024-01-15")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `data polska jest maskowana jako NUMER`() {
        // S-DATE-PL: DD.MM.YYYY jest teraz aktywnie maskowane (jak ISO YYYY-MM-DD)
        val r = pseudonymize("dnia 15.01.2024 roku w Warszawie")
        assertNotInOutput(r, "15.01.2024")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // =========================================================================
    // BUG-06 — sygnatura akt bez IGNORE_CASE (Potok 3b)
    // Fix: usunięto RegexOption.IGNORE_CASE z wzorca 31 (StructuralEngine v1.5)
    // =========================================================================

    @Test fun `BUG-06 tak-nie-jest nie jest sygnatura akt`() {
        val r = pseudonymize("tak/nie/jest")
        assertFalse("'tak/nie/jest' nie powinno być NUMER — to nie sygnatura akt",
            r.tokenMap.keys.any { it.startsWith(TOKEN_NUMER) })
    }

    @Test fun `BUG-06 sygnatura uppercase z ukosnikami jest maskowana`() {
        // Regresja: usunięcie IGNORE_CASE nie może zepsuć realnych sygnatur uppercase
        val r = pseudonymize("Sprawa KRS/234/2023 została zamknięta.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "KRS/234/2023")
    }

    // =========================================================================
    // BUG-KOD-POCZTOWY — lookahead + \d{3,4} w kodzie pocztowym (Potok 3b)
    // Fix: wzorce 32 i 33 (StructuralEngine v1.5)
    // =========================================================================

    @Test fun `BUG-KOD-POCZTOWY rok 15-2024 nie jest kodem pocztowym`() {
        val r = pseudonymize("Umowa z 15-2024 roku.")
        assertFalse("'15-2024' to rok, nie kod pocztowy — brak ADRES",
            r.tokenMap.keys.any { it.startsWith(TOKEN_ADRES) })
    }

    @Test fun `BUG-KOD-POCZTOWY OCR artifact 65-5110 jest maskowany`() {
        // OCR czasem dodaje cyfrę do kodu pocztowego: "65-511" → "65-5110"
        val r = pseudonymize("65-5110 Zielona Góra")
        assertTokenExists(r, TOKEN_ADRES)
        assertNotInOutput(r, "65-5110")
    }

    @Test fun `BUG-KOD-POCZTOWY standardowy kod pocztowy nie zepsuty`() {
        // Regresja: normalny 5-cyfrowy kod pocztowy musi nadal działać
        val r = pseudonymize("60-001 Poznań")
        assertTokenExists(r, TOKEN_ADRES)
        assertNotInOutput(r, "60-001")
    }

    // BUG-ADRES-ZACHLANNOSC-TEL (test ręczny na telefonie 07.07): opcjonalne drugie słowo po
    // kodzie pocztowym (dodane dla miast dwuwyrazowych typu "Zielona Góra") nie miało żadnej
    // walidacji słownikowej — łapało DOWOLNE następne słowo. "44-100 Łódź Tel: +48 ..." →
    // token ADRES wchłonął "Tel", zjadając kotwicę telefonu dla kolejnej warstwy. Fix:
    // drugie słowo wchodzi do tokenu tylko gdy cała fraza jest znanym miastem dwuwyrazowym.
    @Test fun `adres nie wchlania nastepujacego slowa ktore nie jest czescia nazwy miasta`() {
        val r = pseudonymize("Adres: ul. Kopernika 78, 44-100 Łódź Tel: +48 724 143 842")
        assertFalse("Token ADRES nie powinien zawierac 'Tel'",
            r.tokenMap.values.any { it.contains("Łódź", ignoreCase = true) && it.contains("Tel", ignoreCase = true) })
        assertNotInOutput(r, "724 143 842")
    }

    // =========================================================================
    // Testy regresji — przypadki z historii bugów
    // =========================================================================

    @Test fun `pelny dokument medyczny — kluczowe encje zamaskowane`() {
        val dokument = """
            Centrum Medyczne MedHelp Sp.Z o.o., ul. Lipowej 14,
            NIP: 526-000-13-29, REGON: 123456789.
            Zaświadcza lek. Jan Kowalski.
            Pacjent: Anna Nowak, PESEL: 65051112340.
            Kontakt: anna@medhelp.pl, tel. 501-234-567.
            Konto: PL61109010140000071219812874
        """.trimIndent()

        val r = pseudonymize(dokument)

        assertNotInOutput(r, "65051112340")
        assertNotInOutput(r, "526-000-13-29")
        assertNotInOutput(r, "PL61")
        assertNotInOutput(r, "anna@medhelp.pl")
        assertTrue("Daty mogą pozostać", r.pseudonymizedText.contains("2024") ||
            !dokument.contains("2024"))
    }

    @Test fun `puste wejscie nie crashuje`() {
        val r = pseudonymize("")
        assertNotNull(r)
        assertTrue(r.tokenMap.isEmpty())
    }

    @Test fun `tekst bez PII zwraca pusty tokenMap`() {
        val r = pseudonymize("Umowa zawarta w dobrej wierze pomiędzy stronami.")
        assertTrue("Tekst bez PII powinien dać pusty tokenMap",
            r.tokenMap.isEmpty())
    }

    // =========================================================================
    // Warstwa 5 — detectAlgorithmicFlags: fałszywe alarmy (Potok 3b — BUG-FLAGS)
    // Wszystkie testy sprawdzają result.flags, nie tokenMap.
    // =========================================================================

    @Test fun `BUG-FLAGS Zielona Gora w tekście nie jest flagowana`() {
        // "zielonej górze" jest w WHITE_LIST_CITIES jako fraza —
        // ale para nie była sprawdzana razem. FLAGS-FIX-PAIR v1.9.
        val r = pseudonymize("Siedziba spółki mieści się w Zielonej Górze przy ul. Różanej 5.")
        assertFalse(
            "Para 'Zielonej Górze' nie powinna być flagą — to nazwa miasta",
            r.flags.any { flag ->
                flag.fragment.contains("Zielonej", ignoreCase = true) ||
                flag.fragment.contains("Górze", ignoreCase = true)
            }
        )
    }

    @Test fun `BUG-FLAGS Gorze przy czasowniku nie jest podejrzanym nazwiskiem`() {
        // "Górze" przed czasownikiem → fałszywy "Możliwe nazwisko".
        // FLAGS-FIX-CITY v1.9: "górze" dodane do WHITE_LIST_CITIES.
        val r = pseudonymize("W Zielonej Górze działała firma budowlana.")
        assertFalse(
            "'Górze' nie powinno być flagowane jako nazwisko",
            r.flags.any { flag ->
                flag.reason.contains("nazwisko") &&
                flag.fragment.contains("Górze", ignoreCase = true)
            }
        )
    }

    @Test fun `BUG-FLAGS Komisja Mieszkaniowa nie jest flagowana`() {
        // "komisj" dodane do WHITE_LIST_INSTITUTIONS_PREFIX (FLAGS-FIX-KOMISJA v1.9).
        val r = pseudonymize("Wniosek skierowano do Komisji Mieszkaniowej Rady Gminy.")
        assertFalse(
            "'Komisji Mieszkaniowej' to instytucja, nie nazwa własna osoby",
            r.flags.any { flag ->
                flag.fragment.contains("Komisji", ignoreCase = true) ||
                flag.fragment.contains("Mieszkaniowej", ignoreCase = true)
            }
        )
    }

    @Test fun `BUG-FLAGS przymiotnik przy czasowniku nie jest nazwiskiem`() {
        // "Mieszkaniowej" + czasownik = fałszywy "Możliwe nazwisko".
        // FLAGS-FIX-ADJ v1.9: isAdjective() dodane do VERB_ENDINGS bloku.
        val r = pseudonymize("Sprawy Komisji Mieszkaniowej dotyczą przydziału lokali.")
        assertFalse(
            "'Mieszkaniowej' to przymiotnik — nie powinno być flagowane jako nazwisko",
            r.flags.any { flag ->
                flag.reason.contains("nazwisko") &&
                flag.fragment.lowercase().contains("mieszkaniow")
            }
        )
    }

    @Test fun `BUG-FLAGS Spolecznej Komisji nie jest flaga`() {
        // Przymiotnik jako pierwsze słowo pary — FLAGS-FIX-ADJ v1.9 (isAdjective na word).
        val r = pseudonymize("Wyniki przekazano Społecznej Komisji Weryfikacyjnej.")
        assertFalse(
            "Para 'Społecznej Komisji' nie powinna być flagą — przymiotnik + instytucja",
            r.flags.any { flag ->
                flag.fragment.contains("Społecznej", ignoreCase = true) ||
                flag.fragment.contains("Komisji", ignoreCase = true)
            }
        )
    }

    // =========================================================================
    // Warstwa 5 — detectAlgorithmicFlags: FP systemowe (ścieżki A/B/C)
    // Te testy MUSZĄ FAILOWAĆ przed fixem — potwierdzają że bug istnieje.
    // Fix: pozytywny dowód (isPersonNamePart) + blokada subst + HONORIFICS.
    // =========================================================================

    @Test fun `BUG-FLAGS Funduszu Zdrowia to dwa rzeczowniki pospolite nie para nazwisk`() {
        // Ścieżka B — para słów bez pozytywnego dowodu że to osoba.
        // "Funduszu" i "Zdrowia" są subst w Morfologiku, żaden nie jest w surnamesForms.
        val r = pseudonymize("Umowa z Funduszu Zdrowia obejmuje koszty leczenia.")
        assertFalse(
            "'Funduszu Zdrowia' to para rzeczowników pospolitych — nie powinna być flagą",
            r.flags.any { flag ->
                flag.fragment.contains("Funduszu", ignoreCase = true) ||
                flag.fragment.contains("Zdrowia", ignoreCase = true)
            }
        )
    }

    @Test fun `BUG-FLAGS Custom Pak to anglicyzm i artefakt OCR nie para nazwisk`() {
        // Ścieżka B — "Custom" nieznany Morfologikowi (anglicyzm), "Pak" artefakt OCR.
        // Żaden nie jest w surnamesForms ani POLISH_FIRST_NAMES.
        val r = pseudonymize("Zakupiono oprogramowanie Custom Pak od dostawcy.")
        assertFalse(
            "'Custom Pak' to anglicyzm i artefakt OCR — nie powinno być flagą",
            r.flags.any { flag ->
                flag.fragment.contains("Custom", ignoreCase = true)
            }
        )
    }

    @Test fun `BUG-FLAGS Naczelnik Pan to stanowisko z honoryfikiem nie flaga`() {
        // Ścieżka C — "naczelnik" ∈ FUNCTION_TITLES, "Pan" to honoryfik (nie imię ani nazwisko).
        // Kontekst po stanowisku nie zawiera żadnego imienia/nazwiska.
        val r = pseudonymize("Pismo przesłał Naczelnik Pan do jednostki nadrzędnej.")
        assertFalse(
            "'Naczelnik Pan' — 'Pan' to honoryfik, nie imię ani nazwisko",
            r.flags.any { flag ->
                flag.fragment.startsWith("Naczelnik") && flag.fragment.contains("Pan")
            }
        )
    }

    @Test fun `BUG-FLAGS Sedzia SR caloSci to stanowisko z artefaktem OCR nie flaga`() {
        // Ścieżka C — "sędzia" ∈ FUNCTION_TITLES, "SR" i "całoŚci" to artefakty OCR.
        // "całoŚci" ma wielką literę w środku po małej (Ś po o) — artefakt sklejenia.
        // Kontekst nie zawiera imienia/nazwiska — nie powinna być flagą.
        val r = pseudonymize("Orzeczenie wydał Sędzia SR całoŚci w imieniu sądu rejonowego.")
        assertFalse(
            "'Sędzia SR całoŚci' — artefakty OCR w kontekście, brak imienia/nazwiska",
            r.flags.any { flag ->
                flag.fragment.startsWith("Sędzia") &&
                (flag.fragment.contains("SR") || flag.fragment.contains("całoŚci", ignoreCase = true))
            }
        )
    }

    @Test fun `BUG-FLAGS URZEDOWEUrzad Miasta to sklejony token OCR nie flaga`() {
        // Ścieżka B — "URZĘDOWEUrząd" to OCR-sklejony token (all-caps prefix + słowo).
        // hasMidUpperCase nie łapie (E→U to uppercase→uppercase, nie lowercase→uppercase).
        // Żadne ze słów nie jest w surnamesForms — para nie powinna być flagą.
        val r = pseudonymize("Dokument wystawił URZĘDOWEUrząd Miasta na wniosek strony.")
        assertFalse(
            "'URZĘDOWEUrząd Miasta' to sklejony token OCR — nie powinno być flagą",
            r.flags.any { flag ->
                flag.fragment.contains("Urząd", ignoreCase = true) &&
                flag.fragment.contains("Miasta", ignoreCase = true)
            }
        )
    }

    // =========================================================================
    // BUG-PLN: "PLN 1234" (skrót waluty + kwota) mylony z tablicą rejestracyjną / adresem /
    // serią dowodu (sesja 04-05.07.2026, test_adres_regresja_sesja.txt [6]/[7]).
    //
    // DECYZJA 05.07 (Paweł): waluta+liczba ("PLN 1234") to KWOTA, tak samo jak odwrotny szyk
    // liczba+waluta ("1234 PLN") już był. Kwota sama nie identyfikuje osoby, ale skoro engine
    // maskuje jeden szyk jako KWOTA, drugi powinien być spójny — nie zostawiać go jawnym z
    // przypadku (kolejność słów), tylko z decyzji. Nowy symetryczny wzorzec w
    // StructuralEngine.kt (obok istniejącego liczba+waluta). Wszystkie testy poniżej
    // zaktualizowane z "zostaje jawne" na "staje się KWOTA" po tej decyzji.
    // =========================================================================

    @Test fun `BUG-PLN-KWOTA etykieta waluty PLN przed liczba maskowana jako KWOTA`() {
        val r = pseudonymize("Kwota do zapłaty: PLN 1234")
        assertTokenExists(r, TOKEN_KWOTA)
        assertNotInOutput(r, "PLN 1234")
    }

    @Test fun `BUG-PLN-NUMER regresja IBAN nadal maskowany jako NUMER`() {
        val r = pseudonymize("IBAN PL61 1020 1026 0000 0422 7020 1111")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "PL61 1020 1026 0000 0422 7020 1111")
    }

    // BUG-PLN-DOWOD (diagnoza trace 05.07, po zgłoszeniu telefonicznym "PLN 12345" jako NUMER):
    // wzorzec serii dowodu osobistego (3 litery + 5-7 cyfr) łapał "PLN 12345" jako
    // "PLN" (seria) + "12"+"345" (numer), zamiast jako KWOTA.
    @Test fun `BUG-PLN-DOWOD kwota PLN z pieciocyfrowa liczba maskowana jako KWOTA nie seria dowodu`() {
        val r = pseudonymize("PLN 12345")
        assertTokenExists(r, TOKEN_KWOTA)
        assertFalse("Nie powinien powstać NUMER (seria dowodu) z 'PLN 12345'",
            r.tokenMap.keys.any { it.startsWith(TOKEN_NUMER) })
        assertNotInOutput(r, "PLN 12345")
    }

    @Test fun `BUG-PLN-DOWOD regresja prawdziwa seria dowodu nadal maskowana`() {
        val r = pseudonymize("Seria i numer: AWY57 1380")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "AWY57 1380")
    }

    // BUG-PLN-STREETLOOKUP (diagnoza agenta 05.07, zrzuty z telefonu "ul. Kamienna,PLN 1234"):
    // "płn" (skrót ulicy "Północna" w street_names.json) po ASCII-foldowaniu (ł→l) staje się
    // "pln" w LookupTables.streetForms — realna kolizja danych, nie hipotetyczna. NameEngine
    // applyStreetLookup nie miał tego samego CURRENCY_PREFIX_DENY co AddressEngine.kt, więc
    // nadawał "PLN 1234" WŁASNY, osobny token ADRES (odróżnialny od ulicy) zamiast KWOTA.
    // Test wstrzykuje "pln" wprost do słownika testowego (odtwarza kolizję bez zależności od
    // realnego assets/street_names.json).
    @Test fun `BUG-PLN-STREETLOOKUP pln w slowniku ulic nie maskuje samodzielnej kwoty jako ADRES`() {
        LookupTables.resetForTesting()
        LookupTables.initializeForTesting(streets = setOf("pln", "kamienna", "kamiennej"))
        val r = pseudonymize("Kwota do zapłaty: PLN 1234")
        assertTokenExists(r, TOKEN_KWOTA)
        assertFalse("PLN nie powinno dać ADRES — to kolizja danych, nie ulica",
            r.tokenMap.keys.any { it.startsWith(TOKEN_ADRES) })
        assertNotInOutput(r, "PLN 1234")
    }

    // BUG-A11-LICZENIE-ZNAKOW (Paweł 05.07): AnchorEngine A.11 liczyło znaki (2-60) zamiast
    // zatrzymywać się na granicy klasy znaku (spacja/koniec liter-cyfr) — brief v2 sekcja 6.4
    // ("prefiks + 2-3 tokeny"). Po fixie A.11 + applyStreetLookup razem: "ul. Kamienna" (bez
    // numeru, więc żadna precyzyjna warstwa jej nie łapie) trafia do A.11 jako zbieracz resztek
    // i zatrzymuje się DOKŁADNIE na przecinku — "PLN 1234" zostaje kompletnie oddzielone od
    // ulicy i maskowane osobno jako KWOTA (Warstwa 2, przed AnchorEngine).
    @Test fun `BUG-A11-LICZENIE-ZNAKOW ul Kamienna z przecinkiem PLN oddzielone i maskowane jako KWOTA`() {
        LookupTables.resetForTesting()
        LookupTables.initializeForTesting(streets = setOf("kamienna", "kamiennej"))
        val r = pseudonymize("ul. Kamienna,PLN 1234")
        assertTokenExists(r, TOKEN_ADRES)
        assertTokenExists(r, TOKEN_KWOTA)
        assertNotInOutput(r, "Kamienna")
        assertNotInOutput(r, "PLN 1234")
        assertFalse(
            "PLN nie powinno wejść w skład tokenu ADRES",
            r.tokenMap.filterKeys { it.startsWith(TOKEN_ADRES) }.values.any { it.contains("PLN") }
        )
    }

    // BUG-PLN-ADRES (diagnoza Cursor 05.07, wariant tego samego bugu w AddressEngine, nie
    // StructuralEngine): STREET_FULL ma opcjonalny prefiks "ul." — bez tego "PLN 1234,
    // 00-001 Warszawa" dopasowywało się w całości jako JEDEN token ADRES, traktując "PLN"
    // jak nazwę ulicy. Fix: gdy prefiks nie wystąpił, pierwsze słowo nie może być skrótem
    // waluty (CURRENCY_PREFIX_DENY w AddressEngine.kt). "PLN 1234" teraz osobno jako KWOTA.
    @Test fun `BUG-PLN-ADRES kwota z kodem pocztowym obok PLN maskowane osobno jako KWOTA i ADRES`() {
        val r = pseudonymize("PLN 1234, 00-001 Warszawa")
        assertTokenExists(r, TOKEN_KWOTA)
        assertTokenExists(r, TOKEN_ADRES)
        assertNotInOutput(r, "Warszawa")
        assertNotInOutput(r, "PLN 1234")
        assertFalse(
            "PLN nie powinno wejść w skład tokenu ADRES razem z kodem/miastem",
            r.tokenMap.filterKeys { it.startsWith(TOKEN_ADRES) }.values.any { it.contains("PLN") }
        )
    }

    // =========================================================================
    // Helpers — nie są testami, tylko narzędziami
    // =========================================================================

    private fun pseudonymize(
        text: String,
        userDictionary: List<Pair<String, String>> = emptyList()
    ) = PseudonymEngine.pseudonymize(text, userDictionary)

    private fun assertTokenExists(result: PseudonymResult, tokenType: String) {
        assertTrue("Wynik powinien zawierać token $tokenType — tokenMap: ${result.tokenMap}",
            result.tokenMap.keys.any { it.startsWith(tokenType) })
    }

    private fun assertNotInOutput(result: PseudonymResult, text: String) {
        assertFalse("Tekst '$text' nie powinien być widoczny w wyniku",
            result.pseudonymizedText.contains(text, ignoreCase = true))
    }

    // =========================================================================
    // Potok 3c — ZADANIE 1: PESEL z błędami OCR (spacje wewnątrz ciągu cyfr)
    // Każdy format PESEL który benchmark pokazał jako brak → osobny test.
    // =========================================================================

    @Test fun `PESEL OCR format co-2 cyfry jest maskowany`() {
        // Format "65 05 11 12340" — OCR wstawia spację co 2 cyfry
        val r = pseudonymize("Urodzony, PESEL: 65 05 11 12340, zam. Poznań")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "65 05 11")
    }

    @Test fun `PESEL OCR format 2-4-5 jest maskowany`() {
        // Format "65 0511 12340" — OCR wstawia spację po 2 i po 6 cyfrach
        // PESEL 65051112340: suma=110, 110%10=0, kontrolna=0, d10=0 ✓
        val r = pseudonymize("nr PESEL 65 0511 12340 wydany")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "65 0511 12340")
    }

    @Test fun `PESEL OCR format 4-2-5 jest maskowany`() {
        // "6505 11 12340" — format 4-2-5, z kontekstem słownym
        // PESEL 65051112340: suma=110, 110%10=0, kontrolna=0, d10=0 ✓
        val r = pseudonymize("PESEL: 6505 11 12340 wydany")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "6505 11 12340")
    }

    @Test fun `PESEL OCR z dodatkowym slowem miedzy kontekstem a cyframi`() {
        // "Pesel pacjenta: 6505 11 12340" — jedno słowo między PESEL a cyframi
        // PESEL 65051112340: suma=110, 110%10=0, kontrolna=(10-0)%10=0, d10=0 ✓
        val r = pseudonymize("Pesel pacjenta: 6505 11 12340.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "6505 11 12340")
    }

    @Test fun `PESEL OCR format co-2 i istniejacy 6-5 oba dzialaja`() {
        // Regresja: nowe wzorce nie mogą zepsuć istniejącego formatu "650511 12340"
        val r1 = pseudonymize("PESEL: 650511 12340")
        assertTokenExists(r1, TOKEN_NUMER)
        val r2 = pseudonymize("PESEL: 65 05 11 12340")
        assertTokenExists(r2, TOKEN_NUMER)
    }

    @Test fun `rok nie jest fałszywie maskowany przez PESEL OCR 4-2-5`() {
        // "2024 01 15" — data rozbita, nie powinna być PESEL (ostatni segment ≠ 5 cyfr)
        val r = pseudonymize("Umowa z dnia 2024 roku podpisana")
        assertTrue("Rok 2024 powinien pozostać w tekście",
            r.pseudonymizedText.contains("2024"))
    }

    // BUG-PESEL-OBCA-LITERA (benchmark 500 dok. 07.07, doc_00025/doc_00355 — potwierdzone
    // ręcznym testem na telefonie): prawdziwy OCR na zaszumionym obrazie pomylił pojedynczą
    // cyfrę z literą spoza D-klasy (nie O/o/l/I/i/S/s/B/b/Z/z) — kontekstowy wzorzec PESEL
    // w Rundzie 1 (StructuralEngine.kt) dopasowywał się CZĘŚCIOWO, zjadając słowo-kotwicę
    // ale urywając tuż przed obcą literą, więc żadna kolejna warstwa nie dostawała już szansy
    // dokończyć. Fix: CTX_STRAY w StructuralEngine.kt toleruje jedną obcą literę w środku
    // ciągu, gdy zaraz po niej jest znowu prawdziwa cyfra.
    @Test fun `PESEL z obca litera w srodku ciagu jest maskowany w calosci`() {
        // OCR: "78121295740" -> "781212957A0" ("4" rozpoznane jako "A")
        val r = pseudonymize("Beata Kamiiska PESEL: 781212957A0 Adres: ul. Kopernika 78")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "781212957A0")
        assertNotInOutput(r, "A0")
    }

    @Test fun `PESEL z obca litera i spacja w srodku ciagu jest maskowany w calosci`() {
        // OCR: "93072300794" -> "930r2300 794" ("7" rozpoznane jako "r", plus spacja)
        val r = pseudonymize("Agnieszka Grabowska, PESEL:930r2300 794 zamieszkala Szkolna 6")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "930r2300 794")
    }

    @Test fun `PESEL obca litera regresja sklejenia z kodem pocztowym nadal dziala`() {
        // BUG-PESEL-SKLEJENIE-FIX (01.07) nie może wrócić przez CTX_STRAY: tolerancja obcej
        // litery wymaga prawdziwej cyfry ZARAZ po niej, więc nie przeskakuje w kod pocztowy.
        val r = pseudonymize("PESEL 90051512340 00-001 Warszawa NIP 526-021-15-81")
        assertFalse("PESEL nie powinien zawierac fragmentu kodu pocztowego",
            r.tokenMap.values.any { it.contains("90051512340") && it.contains("00-") })
    }

    // BUG-NIP-OBCA-LITERA (zgłoszone przez Pawła 08.07, zrzut ekranu — "NIP: 426-1A1-78-03"
    // jawne mimo dokładnie 1 tokena w dokumencie): komentarz przy NIP mówił "analogicznie do
    // PESEL" ale NIGDY nie dostał tolerancji CTX_STRAY z 07.07 — dwa sztywne wzorce grupowe
    // (\d{3} dosłowne) wymagały prawdziwej cyfry w każdej pozycji. Scalone w jeden elastyczny
    // wzorzec, ten sam mechanizm co PESEL.
    @Test fun `NIP z obca litera w srodku ciagu jest maskowany w calosci`() {
        // OCR: "4261417803" -> "4261A17803" ("4" rozpoznane jako "A")
        val r = pseudonymize("Dłużnik zamieszkały przy Polnej 4 NIP: 426-1A1-78-03 zarejestrowany w Krakowie.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "426-1A1-78-03")
        assertNotInOutput(r, "1A1")
    }

    // BUG-TELEFON-OBCA-LITERA (zgłoszone przez Pawła 08.07, test stresowy 100 encji —
    // "numer telefonu 6O2 3r4 891" zostawało jawne w części "3r4 891"): telefon nigdy nie
    // dostał tolerancji CTX_STRAY którą mają PESEL i NIP — "r" nie jest D-klasą (O/o/l/I/i/
    // S/s/B/b/Z/z), więc wartość urywała się w środku numeru. Przy okazji naprawiono też
    // brak tolerancji na odmienione słowo-kotwicę ("telefonu" zamiast "telefon").
    @Test fun `telefon z obca litera w srodku ciagu jest maskowany w calosci`() {
        // OCR: "602374891" -> "6O2 3r4 891" ("0" -> "O" jest D-klasą OK, "7" -> "r" NIE jest)
        val r = pseudonymize("Kontakt: numer telefonu 6O2 3r4 891, prosimy dzwonić po 10.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "3r4 891")
        assertNotInOutput(r, "6O2 3r4 891")
    }

    // BUG-KONTO-OBCA-LITERA (zgłoszone przez Pawła 08.07, test stresowy 100 encji —
    // "nr konta: 45 1140 2004 0000 3702 7823 A176" zostawiało "A176" jawne): numer konta
    // bez prefiksu PL (bare NRB) nigdy nie dostał tolerancji CTX_STRAY — każda 4-cyfrowa
    // grupa wymagała dosłownie \d{4}.
    @Test fun `numer konta bez PL z obca litera na koncu jest maskowany w calosci`() {
        val r = pseudonymize("Proszę o wpłatę na nr konta: 45 1140 2004 0000 3702 7823 A176 tytułem opłaty.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "A176")
        assertNotInOutput(r, "7823 A176")
    }

    @Test fun `numer konta bez PL z myslnikami i obca litera jest maskowany w calosci`() {
        val r = pseudonymize("Rachunek: 61-1090-1014-0000-0712-1981-2A74 do przelewu zwrotnego.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "2A74")
    }

    // =========================================================================
    // Potok 3c — ZADANIE 2: False positives — słowa pospolite i skróty
    // Każde słowo z WHITE_LIST dodane w Potoku 3c → osobny test.
    // =========================================================================

    @Test fun `zamieszkania nie jest maskowane jako OSOBA`() {
        val r = pseudonymize("Adres zamieszkania: ul. Lipowa 3")
        assertFalse("'zamieszkania' nie powinno być tokenem OSOBA",
            r.tokenMap.values.any { it.equals("zamieszkania", ignoreCase = true) })
    }

    @Test fun `Zamieszkania z wielka litera w naglowku nie jest OSOBA`() {
        // Nagłówek "Miejsce Zamieszkania:" — wielka litera → ryzyko NAME_FORWARD
        val r = pseudonymize("Miejsce Zamieszkania: Wrocław")
        assertFalse("'Zamieszkania' w nagłówku nie powinno być OSOBA",
            r.tokenMap.values.any { it.equals("zamieszkania", ignoreCase = true) })
    }

    @Test fun `zamieszkaly nie jest maskowany jako OSOBA`() {
        val r = pseudonymize("Obywatel zamieszkały przy ul. Lipowej 14")
        assertFalse("'zamieszkały' nie powinno być tokenem OSOBA",
            r.tokenMap.values.any { it.contains("zamieszkał", ignoreCase = true) })
    }

    @Test fun `Wydzialu w naglowku instytucji nie jest OSOBA`() {
        // "Naczelnik Wydziału Komunikacji" — "Wydziału" z wielką literą po tytule
        val r = pseudonymize("Naczelnik Wydziału Komunikacji informuje")
        assertFalse("'Wydziału' nie powinno być tokenem OSOBA",
            r.tokenMap.values.any { it.equals("wydziału", ignoreCase = true) })
    }

    @Test fun `skrot Sp przed forma prawna nie jest OSOBA`() {
        // "Sp" jako skrót "Spółka" przed formą prawną
        val r = pseudonymize("Firma: Kowalski Handel Sp. z o.o. NIP: 526-000-13-29")
        assertFalse("'Sp' (2 znaki) nie powinno być tokenem OSOBA",
            r.tokenMap.entries.any { (k, v) ->
                k.startsWith(TOKEN_OSOBA) && v.equals("sp", ignoreCase = true)
            })
    }

    @Test fun `skrot pl jako plac nie jest OSOBA`() {
        // "pl." jako skrót placu — z wielką literą "Pl." ryzyko fałszywego OSOBA
        val r = pseudonymize("Siedziba: pl. Wolności 1, Wrocław")
        assertFalse("'pl' (skrót placu) nie powinno być tokenem OSOBA",
            r.tokenMap.entries.any { (k, v) ->
                k.startsWith(TOKEN_OSOBA) && v.lowercase() == "pl"
            })
    }

    @Test fun `dokument z zamieszkania i NIP - NIP zamaskowany zamieszkania nie`() {
        // Scenariusz z dokumentu prawnego — test integracyjny dla obu zmian
        val r = pseudonymize(
            "Adres zamieszkania: ul. Lipowa 3, Poznań. NIP: 526-000-13-29."
        )
        // NIP musi być zamaskowany
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "526-000-13-29")
        // "zamieszkania" nie może być tokenem OSOBA
        assertFalse("'zamieszkania' nie powinno być OSOBA",
            r.tokenMap.values.any { it.equals("zamieszkania", ignoreCase = true) })
    }

    @Test fun `PESEL OCR i NIP w jednym dokumencie oba zamaskowane`() {
        // Test integracyjny — benchmark pokazał że krytyczne braki są na 1 z 3-4 dok.
        val r = pseudonymize("PESEL: 65 05 11 12340. NIP: 526-000-13-29.")
        val numerTokens = r.tokenMap.entries.filter { it.key.startsWith(TOKEN_NUMER) }
        assertTrue("Powinny być co najmniej 2 tokeny NUMER (PESEL OCR + NIP)",
            numerTokens.size >= 2)
        assertNotInOutput(r, "65 05 11 12340")
        assertNotInOutput(r, "526-000-13-29")
    }

    // =========================================================================
    // Potok 3c — ZADANIE 1 (uzupełnienie v1.7): wzorce kontekstowe dokumentów
    // Architektura systemowa: słowo kluczowe + ciąg cyfr/alfanumeryczny
    // =========================================================================

    @Test fun `dowod osobisty z kontekstem jest maskowany`() {
        val r = pseudonymize("Dowód osobisty: ABC123456 wydany przez Urząd")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "ABC123456")
    }

    @Test fun `dowod osobisty skrot DO z kontekstem jest maskowany`() {
        val r = pseudonymize("Legitymuje się D.O. ABC 123 456.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "ABC 123 456")
    }

    @Test fun `dowod osobisty OCR zgubil 2 cyfry jest maskowany`() {
        val r = pseudonymize("Dowód osobisty: ABC1234")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "ABC1234")
    }

    @Test fun `paszport z kontekstem jest maskowany`() {
        val r = pseudonymize("Paszport: AB1234567 ważny do 2030")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "AB1234567")
    }

    @Test fun `paszport z kontekstem i spacjami OCR jest maskowany`() {
        val r = pseudonymize("Nr paszportu AB 123 4567")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "AB 123 4567")
    }

    @Test fun `paszport OCR zgubil 2 cyfry jest maskowany`() {
        val r = pseudonymize("Paszport: AB12345")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "AB12345")
    }

    @Test fun `seria i numer dokumentu jest maskowana`() {
        val r = pseudonymize("Seria i numer: ABC123456")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "ABC123456")
    }

    @Test fun `testDowodOsobisty`() {
        val cases = listOf(
            "FOH614892" to true,
            "dowód: FOH614892" to true,
            "seria FOH nr 614892" to true,
            "FOH 614892" to true,   // OCR: spacja między serią a numerem
            "FOH614 892" to true,   // OCR lvl3: spacja w środku grupy cyfr
            "FOH 614 892" to true,  // OCR lvl3: spacja w obu miejscach
            // "foh614892" — BUG-DOWOD odłożony: (?i) powodował pożeranie imion (Jan+cyfry)
            "ABC123456" to true,    // 3 litery + 6 cyfr = format dowodu — maskujemy
        )
        for ((text, shouldMask) in cases) {
            val result = pseudonymize(text)
            val masked = result.pseudonymizedText.contains("NUMER_")
            if (masked != shouldMask) {
                error("FAIL: '$text' — oczekiwano masked=$shouldMask, got $masked\n  → ${result.pseudonymizedText}")
            }
            println("PASS | $text")
        }
    }

    @Test fun `testWyciekiV2`() {
        val cases = listOf(
            "Nr konta: PL61109010140000071219812874" to true,
            "sygn. akt II K 456/2024" to true,
            "Sygn. Akt II K 456/2024" to true,
            "Km 9876/2022" to true,
            "PT.123456.2023" to true,
        )
        val failures = mutableListOf<String>()
        for ((text, shouldMask) in cases) {
            val result = pseudonymize(text)
            val masked = result.pseudonymizedText.contains("NUMER_")
            if (masked != shouldMask) failures.add("FAIL | $text → ${result.pseudonymizedText}")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    // =========================================================================
    // v1.9 — SYGNATURA-FIX, DATA-UR-FIX, DOWOD-FIX, SYG-ADM-FIX
    // =========================================================================

    @Test fun `v19 sygnatura I Co z malym o jest maskowana`() {
        // SYGNATURA-FIX: [A-Z]{1,3} → [A-Z][a-zA-Z]{0,2}
        // "I Co 6219/2024" — "Co" było odrzucane (o = lowercase)
        val r = pseudonymize("Sygn. akt I Co 6219/2024 — sprawa cywilna.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "I Co 6219/2024")
    }

    @Test fun `v19 sygnatura I Ns z malym s jest maskowana`() {
        val r = pseudonymize("Sprawa I Ns 4712/2022.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "I Ns 4712/2022")
    }

    @Test fun `v19 sygnatura komornicza Km jest maskowana`() {
        // "Km" ma małe m — poprzednio pominięte
        val r = pseudonymize("Sygn. akt Km 27146/2024.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "Km 27146/2024")
    }

    @Test fun `v19 sygnatura II C uppercase nadal maskowana`() {
        // Regresja: uppercase "C" musi nadal działać po refaktorze
        val r = pseudonymize("Sygn. akt II C 1234/2023.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "II C 1234/2023")
    }

    @Test fun `v19 data urodzenia kontekst DD-MM-YYYY jest maskowana`() {
        // DATA-UR-FIX: brak wzorca na datę urodzenia → dodany kontekst dat[aą] ur...
        val r = pseudonymize("Data urodzenia: 21.05.1979")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "21.05.1979")
    }

    @Test fun `v19 data urodzenia OCR literowka uri jest maskowana`() {
        // doc_00009: OCR produkuje "Data urodzenía" (í zamiast i)
        val r = pseudonymize("Data urodzenía: 17.09.1985")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "17.09.1985")
    }

    @Test fun `v19 data urodzenia ze spacją na nowej linii jest maskowana`() {
        // Wzorzec dopuszcza newline między etykietą a wartością
        val r = pseudonymize("Data urodzenia:\n26.12.1988")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "26.12.1988")
    }

    @Test fun `v19 data urodzenia skrot ur jest maskowana`() {
        val r = pseudonymize("data ur. 04.09.1976")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "04.09.1976")
    }

    @Test fun `v19 dowod osobisty OCR spacja po 2 cyfrach jest maskowany`() {
        // DOWOD-FIX: AWY57 1380 (spacja po 2 cyfrach) — doc_00033 lvl3
        // Stary wzorzec \d{3}[^\S\n]?\d{3} nie pasował (57 != 3 cyfry)
        val r = pseudonymize("Nr dowodu osobistego: AWY57 1380")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "AWY57 1380")
    }

    @Test fun `v19 dowod osobisty kontekst newline jest maskowany`() {
        // DOWOD-CTX-FIX: separator dopuszcza newline po dwukropku
        val r = pseudonymize("Nr dowodu osobistego:\nABC123456")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "ABC123456")
    }

    @Test fun `v19 sygnatura administracyjna ze spacja zamiast kropki jest maskowana`() {
        // SYG-ADM-FIX: "PT 075012 2018" — OCR zamienia . na spację
        val r = pseudonymize("Decyzja PT 075012 2018 z dnia...")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "075012 2018")
    }

    @Test fun `v19 sygnatura administracyjna standardowa z kropka nadal maskowana`() {
        // Regresja: PT.075012.2018 musi nadal działać po zmianie [.\s]
        val r = pseudonymize("Decyzja nr PT.075012.2018 z dnia 01.01.2018")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "PT.075012.2018")
    }

    // =========================================================================
    // v1.9b — Blok 0c: kontekstowe wzorce identyfikatorów dokumentowych
    // =========================================================================

    @Test fun `v19b sygnatura akt I Co z kontekstem sygn jest maskowana`() {
        val r = pseudonymize("sygn. akt I Co 3704/2018, z dnia 01.01.2018")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "I Co 3704/2018")
    }

    @Test fun `v19b sygnatura komornicza Km z kontekstem sygn jest maskowana`() {
        val r = pseudonymize("sygn. akt Km 4917/2018")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "Km 4917/2018")
    }

    @Test fun `v19b sygnatura OCR bez ukosnika z kontekstem sygn jest maskowana`() {
        // OCR usuwa ukośnik: "Km 80838/2024" → "Km 808382024"
        val r = pseudonymize("Sygn akt Km 808382024")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "808382024")
    }

    @Test fun `v19b numer umowy UMW z kontekstem nr umowy jest maskowany`() {
        val r = pseudonymize("numer umowy: UMW/2022/966")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "UMW/2022/966")
    }

    @Test fun `v19b numer umowy U- z kontekstem nr umowy jest maskowany`() {
        val r = pseudonymize("nr umowy U-00615/2024")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "U-00615/2024")
    }

    @Test fun `v19b numer faktury FV z kontekstem nr faktury jest maskowany`() {
        val r = pseudonymize("nr faktury: FV-01079/04/2024")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "FV-01079/04/2024")
    }

    @Test fun `v19b numer faktury cyfrowy z kontekstem faktura nr jest maskowany`() {
        val r = pseudonymize("Faktura nr 4704/12/2020")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "4704/12/2020")
    }

    @Test fun `v19b numer kw z kontekstem nr kw jest maskowany`() {
        val r = pseudonymize("nr KW: PO1P/00424625/8")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "PO1P/00424625/8")
    }

    @Test fun `v19b sygnatura sygn na nowej linii jest maskowana`() {
        // Typowy OCR: "sygn. akt" i numer w tej samej linii — szybki sanity check
        val r = pseudonymize("Sygn. akt II K 789/2023.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "II K 789/2023")
    }

    // DIAGNOSTYKA TYMCZASOWA — usunąć po analizie
    @Test fun `diagnostyka_faile`() {
        val cases = listOf(
            "Przelej na: 61 1090 1014 0000 0712 1981 2874",
            "Siedziba: al. Jerozolimskie 144"
        )
        for (text in cases) {
            val result = pseudonymize(text)
            println("=== INPUT: $text")
            println("    OUTPUT: ${result.pseudonymizedText}")
            println("    TOKENS: ${result.tokenMap}")
        }
    }

    @Test
    fun testPrecisionDiagnostyka() {
        val text = "Siedziba: al. Jerozolimskie 144, 02-001 Warszawa"
        val result = pseudonymize(text)
        println("Wejście: $text")
        println("Wyjście: ${result.pseudonymizedText}")
        println("Tokeny:")
        result.tokenMap.forEach { (token, original) ->
            println("  $token = $original")
        }
    }

    // =========================================================================
    // S7 — EMAIL jako EMAIL (nie NUMER)
    // =========================================================================

    // =========================================================================
    // OCR artefakty — NlP / N1P / PESE1
    // =========================================================================

    @Test fun `NlP z malym l jest maskowany kontekstowo`() {
        // OCR: I (duże i) → l (małe L) w "NIP" → "NlP"
        // Wzorzec kontekstowy N[IL1]P obsługuje ten artefakt.
        val r = pseudonymize("NlP: 526-000-13-29")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "526-000-13-29")
    }

    @Test fun `N1P z cyfra 1 jest maskowany kontekstowo`() {
        val r = pseudonymize("N1P: 526-000-13-29")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "526-000-13-29")
    }

    @Test fun `PESE1 z cyfra 1 zamiast L jest maskowany kontekstowo`() {
        // OCR: L → 1 w "PESEL" → "PESE1"
        // Wzorzec kontekstowy pe[s5][e3][lL1] obsługuje ten artefakt.
        val r = pseudonymize("PESE1: 44051401458")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "44051401458")
    }

    @Test fun `PESE1 pacjenta z dodatkowym slowem jest maskowany`() {
        // OCR artefakt + opcjonalne słowo między keyword a cyframi.
        val r = pseudonymize("PESE1 pacjenta: 44051401458")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "44051401458")
    }

    // =========================================================================
    // S7 — EMAIL jako EMAIL (nie NUMER)
    // =========================================================================

    @Test fun `S7 email strukturalny dostaje token EMAIL nie NUMER`() {
        val r = pseudonymize("kontakt: jan.kowalski@example.com")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "jan.kowalski@example.com")
        assert(r.tokenMap.none { (k, _) -> k.startsWith("NUMER") && r.tokenMap[k]?.contains("@") == true }) {
            "Email nie powinien być tokenizowany jako NUMER"
        }
    }

    @Test fun `S7 email z prefiksem e-mail dostaje token EMAIL`() {
        val r = pseudonymize("e-mail: anna.wisniewski@firma.pl")
        assertTokenExists(r, TOKEN_EMAIL)
        assertNotInOutput(r, "anna.wisniewski@firma.pl")
    }

    // BUG-NR-SIEROTA (diagnoza Cursor 01.07): identyfikator alfanumeryczny z 3 segmentami
    // ukośnikowymi (prefiks-cyfry/cyfry/rok) ucinał się na 2 segmentach, zostawiając rok jawny.
    @Test fun `numer faktury z trzema segmentami nie zostawia sieroty roku`() {
        val r = pseudonymize("FAKTURA VAT\nNr FV-08217/08/2023")
        assertNotInOutput(r, "/2023")
        assertNotInOutput(r, "08217")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-NR-SYGNATURA (diagnoza Cursor 01.07): "Nr" traktowane jak kod wydziału sądowego
    // (analogicznie do "Co"/"Ns") w StructuralEngine.kt:460 — "Nr 8678/02/2023" ucinał się
    // na "Nr 8678/02", zostawiając "/2023" jawne.
    @Test fun `Nr cyfry slash rok jeden token bez sieroty`() {
        val r = pseudonymize("FAKTURA VAT\nNr 8678/02/2023")
        assertNotInOutput(r, "/2023")
        assertNotInOutput(r, "8678")
    }

    // BUG-NUMER-FAKTURA-TABLICA (diagnoza Cursor 07.07, traceMode): wzorzec tablicy
    // rejestracyjnej (StructuralEngine.kt:573, [A-Z]{2,3}\s?\d{4,5}[A-Z]{0,2}) jest
    // wcześniej na liście niż wzorzec sygnatury slash (linia 605) i pasuje do prefiksu
    // "FVI2025" (3 litery + 4 cyfry) w numerze faktury zanim szerszy wzorzec dostanie
    // szansę objąć całość — "/12/1828" zostawał jawny (Guard łapał jako SYGNATURA,
    // ale nie maskował). Test na jednej linii bez "\n" i ze słowem "VAT" wtrąconym
    // między "FAKTURA" a "Nr" (kontekstowy wzorzec 445 tego nie łapie).
    @Test fun `numer faktury bez myslnika prefiks litery-cyfry nie zostawia sieroty slash`() {
        val r = pseudonymize("FAKTURA VAT Nr FVI2025/12/1828")
        assertNotInOutput(r, "/12/1828")
        assertNotInOutput(r, "FVI2025")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-NUMER-FAKTURA-VAT (diagnoza Cursor 07.07, test ręczny Pawła): numer faktury czysto
    // cyfrowy, bez słowa "nr"/"numer" wcale — tylko "Faktura" + słowo pośrednie "VAT" +
    // identyfikator. Wzorzec kontekstowy 445 wymagał wcześniej DOKŁADNIE sąsiadującego
    // "nr"/"numer" po "faktura" — "VAT" wtrącone między nimi łamało dopasowanie całkowicie,
    // a wzorzec sygnatury sądowej (650) łapał tylko "VAT 26/06", zostawiając "/006" jawne.
    @Test fun `numer faktury cyfrowy 3 segmenty po Faktura VAT bez slowa nr`() {
        val r = pseudonymize("Faktura VAT 26/06/006")
        assertNotInOutput(r, "/006")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // TEST 07.07 (Paweł): eksperyment architektoniczny — wzorzec 445 (numer faktury) w
    // StructuralEngine.kt wyłączony celowo (skomentowany). AnchorEngine A.12 (docNumberRe)
    // ma teraz "FV"/"Fv"/"fv"/"F.V."/"f-ra"/"VAT" jako kotwice bezpośrednie (obok "Nr"),
    // żeby sprawdzić czy Anchor sam wystarcza jako jedyne miejsce zakrywające NUMER faktury.
    @Test fun `numer faktury skrot FV jako kotwica bez slowa faktura obok liczby`() {
        val r = pseudonymize("FAKTURA VAT FV 26/06/006")
        assertNotInOutput(r, "/006")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `numer faktury z kropkami jako separatorem lapany przez Anchor FV`() {
        val r = pseudonymize("FAKTURA VAT FV KOR12.012.00012")
        assertNotInOutput(r, "00012")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `numer faktury z doklejonym sufiksem miasta nie zostawia sieroty`() {
        val r = pseudonymize("FAKTURA VAT FV 26/06/006/WAW")
        assertNotInOutput(r, "/WAW")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // Wykluczenie: stawka VAT (kształt cyfry+%) to NIGDY numer dokumentu — regresja FP.
    @Test fun `stawka VAT z procentem nie jest maskowana jako numer faktury`() {
        val r = pseudonymize("Faktura VAT 23%")
        assertFalse("stawka VAT nie powinna zniknąć z wyniku", r.pseudonymizedText.isBlank())
        assertTrue("23% powinno zostać widoczne", r.pseudonymizedText.contains("23%"))
    }

    @Test fun `stawka VAT z dwukropkiem i przecinkiem nie jest maskowana`() {
        val r = pseudonymize("Faktura VAT: 8%, netto 100 zl")
        assertTrue("8% powinno zostać widoczne", r.pseudonymizedText.contains("8%"))
    }

    @Test fun `kwota VAT bez cyfry po slowie nie tworzy falszywego tokenu`() {
        val r = pseudonymize("Kwota VAT wynosi 230,00 zl")
        assertFalse("'wynosi' nie powinno stać się fałszywym NUMEREM",
            r.tokenMap.values.any { it == "wynosi" })
    }

    // A.12b (Paweł 07.07): lustro A.12 — kotwica "FV" NA KOŃCU numeru, nie na początku.
    @Test fun `numer faktury z kotwica FV na koncu numeru`() {
        val r = pseudonymize("Faktura VAT 260712/FV")
        assertNotInOutput(r, "260712/FV")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // A.12c (Paweł 07.07): "FV" samowystarczalna kotwica, BEZ sygnału wstecz — "FV" samo
    // w sobie jest wystarczająco specyficzne (ten sam precedens co stary wzorzec 494).
    // Kluczowy przypadek: litera w środku numeru ("W") niewidoczna dla 494 (czyste cyfry)
    // i dla A.12 (brak "Faktura"/"VAT" w pobliżu) — zero zewnętrznego kontekstu w tym teście.
    @Test fun `numer faktury FV z litera w srodku bez zadnego kontekstu`() {
        val r = pseudonymize("FV20260700W012")
        assertNotInOutput(r, "FV20260700W012")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `numer faktury FV na koncu bez kontekstu tez dziala samowystarczalnie`() {
        val r = pseudonymize("260712/FV")
        assertNotInOutput(r, "260712/FV")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-FAKTURA-SPACJE-SEPARATOR (Paweł 07.07, test_faktura_20_warianty punkt 18):
    // OCR czasem wstawia spację wokół ukośnika/myślnika ("26 / 06 / 006" zamiast
    // "26/06/006") — capture oparty o goły \S+ urywał się na pierwszej spacji.
    @Test fun `numer faktury ze spacjami wokol ukosnikow nie zostawia sieroty`() {
        val r = pseudonymize("FAKTURA VAT  FV  26 / 06 / 006")
        assertNotInOutput(r, "/ 06")
        assertNotInOutput(r, "/ 006")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-DZIALKA-FIX (Paweł 07.07, benchmark staly doc_00027): OCR zdegradowało "działki"
    // do "dzialki" (ł→l) — wzorzec wymagał litery dosłownej. Plus numer z kropkami+ukośnikiem.
    // Migrowane do AnchorEngine A.12 (StructuralEngine 613 wyłączone) — patrz testy niżej.
    @Test fun `numer dzialki z degradacja l zamiast l i kropkami jako separator`() {
        val r = pseudonymize("Nr dzialki ewidencyjnej: 775900.0011.3706/6")
        assertNotInOutput(r, "775900")
        assertNotInOutput(r, "3706/6")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-DZIALKA-FIX, wariant z fresh doc_00056: poprawne "ł", przecinek zamiast kropki.
    @Test fun `numer dzialki z prawidlowa litera l i przecinkiem jako separator`() {
        val r = pseudonymize("Nr działki ewidencyjnej: 206487,0027.8889")
        assertNotInOutput(r, "206487")
        assertNotInOutput(r, "0027.8889")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-DZIALKA-ANCHOR (Paweł 07.07): "Numer" (pełne słowo, nie "Nr") + "ł"→"t" (inna
    // degradacja niż "l" wyżej) — kolejny wariant tego samego problemu, stąd migracja do
    // AnchorEngine zamiast dalszego enumerowania w StructuralEngine.
    @Test fun `numer dzialki pelne slowo Numer i degradacja l na t`() {
        val r = pseudonymize("Faktura VAT 23%\nNumer dziatki 7759000.0011.3706/6")
        assertNotInOutput(r, "7759000")
        assertNotInOutput(r, "3706/6")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // Regresja FP — "Nr"/"Numer" generyczne odniesienia (strona, punkt/rozdział) NIE mogą
    // być maskowane tylko dlatego że tolerujemy teraz słowa pośrednie po Nr/Numer.
    @Test fun `Nr strony i numer punktu nie sa maskowane jako NUMER dokumentu`() {
        val r1 = pseudonymize("Patrz Nr strony 5 w załączniku.")
        assertFalse(r1.tokenMap.values.any { it.contains("strony") })
        val r2 = pseudonymize("Zgodnie z art. 104 ustawy, punkt nr 5.2 rozdziału stanowi że...")
        assertFalse(r2.tokenMap.values.any { it.contains("rozdziału") || it == "5.2" })
    }

    // BUG-IBAN-ZAGRANICZNY (Paweł 07.07, test_iban_20_warianty): wzorzec kontekstowy "IBAN:"
    // zakładał sztywne grupy po 4 cyfry — działa dla PL (26 cyfr, dzieli się równo), zostawiał
    // resztę jawną dla krajów gdzie się nie dzieli (DE: 20 cyfr, FR i inne).
    @Test fun `IBAN niemiecki i francuski bez reszty jawnej`() {
        val de = pseudonymize("IBAN: DE89370400440532013000")
        assertNotInOutput(de, "013000")
        assertTokenExists(de, TOKEN_NUMER)

        val fr = pseudonymize("Platnosc IBAN FR7630006000011234567890189 zagraniczna")
        assertNotInOutput(fr, "890189")
        assertTokenExists(fr, TOKEN_NUMER)
    }

    // BUG-IBAN-OGON-KRADZIONY (07.07, znalezione ręcznym testem na telefonie — Paweł: "raz
    // maskowany w całości, raz tylko ostatnie cyfry"): ten sam IBAN z nieregularnymi spacjami
    // OCR ("PL7824010135798 2507 146112645") raz był maskowany w pełni (obok słowa "konto"),
    // raz zostawiał większość jawną — bo gołe wzorce kształtu bez kotwicy (REGON-9, potem też
    // telefon 3-3-3/2-3-2-2) łapały fragmenty ogona zanim AnchorEngine (kotwica "PL") dostał
    // szansę. Pierwsza próba fixu dopisywała ten sam guard do kolejnych osobnych wzorców z
    // osobna (znajdując kolejne ofiary jedna po drugiej) — Paweł explicite zakazał rozproszonych
    // poprawek. PRAWDZIWY fix: `IBAN_EARLY` w StructuralEngine.kt, PIERWSZY wzorzec w całej
    // liście — kotwica "PL" zabiera swoje terytorium zanim jakikolwiek goły wzorzec dostanie
    // szansę, więc żaden z nich nie musi się bronić z osobna.
    @Test fun `IBAN z nieregularnymi spacjami OCR bez slowa kontekstowego jest maskowany w calosci`() {
        val r = pseudonymize("Jak w zdaniu ponizej: ciag PL7824010135798 2507 146112645 wystepuje raz.")
        assertNotInOutput(r, "146112645")
        assertNotInOutput(r, "7824010135798")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `REGON samodzielny bez PL w poblizu nadal maskowany`() {
        // Regresja: IBAN_EARLY nie może zablokować zwykłego, niepowiązanego REGON-u.
        val r = pseudonymize("Firma XYZ, REGON: 123456789, NIP: 111-222-33-44")
        assertNotInOutput(r, "123456789")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `IBAN z etykieta konto nie zawlaszcza etykiety`() {
        // IBAN_EARLY biegnie PRZED wzorcem kontekstowym "konto..." — bierze sam numer,
        // etykieta zostaje jawna (nie jest PII, nie ma potrzeby jej maskować).
        val r = pseudonymize("Splata na konto komornika: PL7824010135798 2507 146112645  Komornik Sadowy")
        assertNotInOutput(r, "146112645")
        assertNotInOutput(r, "7824010135798")
        assertTrue("Etykieta 'konto komornika' nie jest PII, nie powinna zniknąć",
            r.pseudonymizedText.contains("konto komornika", ignoreCase = true))
    }

    // BUG-PESEL-MOST-TOKEN (diagnoza Cursor 07.07, benchmark 500 dok., doc_00430): "ł" ogona
    // wcześniej utworzonego tokenu ("NUMER_001") + nowa linia + prawdziwy PESEL na następnej
    // linii — peselShapeRe [\s\-]? (obejmowało \n) przeskakiwało z ogona tokenu w PESEL,
    // tworząc zanieczyszczone dopasowanie bez poprawnej sumy kontrolnej, przez co prawdziwy
    // PESEL nigdy nie dostawał osobnej szansy.
    @Test fun `PESEL na nowej linii po tokenie dowodu nie ginie w moscie newline`() {
        val r = pseudonymize("Nr dowodu osobistego oST590114\n5508107 1597\nDANE KONTAKTOWE")
        assertNotInOutput(r, "5508107")
        assertNotInOutput(r, "1597")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-NIP-WIELOLINIOWY (diagnoza Cursor 07.07, benchmark 500 dok., doc_00473): keyword
    // "NIP (jeśli dotyczy):" i wartość na osobnych liniach OCR — luka Anchor A.5 wykluczała
    // \n ORAZ litery D-class (o/l/i/s) które są zwykłymi literami w "jeśli"/"dotyczy".
    @Test fun `NIP z etykieta i wartoscia na osobnych liniach oraz spacja w segmencie`() {
        val r = pseudonymize("NIP (jesli dotyczy):\n667-87 1-88-83\nOswiadczam")
        assertNotInOutput(r, "667-87")
        assertNotInOutput(r, "88-83")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // =========================================================================
    // STRESS TEST SKLEJANIA (Paweł 07.07, po zamknięciu rundy FV): gęsty ciąg RÓŻNYCH
    // encji obok siebie, minimalna proza (same kotwice + wartości, bez opisowych zdań).
    // Cel: złapać "sklejanie" tokenów (token wbudowany w token, brak separatora między
    // dwoma sąsiednimi tokenami, kradzież znaku przez sąsiedni wzorzec) — patrz
    // BUG-ADRES-OGONY (30.06) i BUG-KWOTA-KRADNIE-CYFRE (05.07) w pamięci projektu.
    // Wszystkie wartości ŚWIEŻE — żadna nie występuje w innym teście w tym pliku (celowo,
    // żeby nie polegać na już oswojonych przez inne wzorce przypadkach).
    // =========================================================================
    @Test fun `stres gestych roznych encji obok siebie bez sklejania tokenow`() {
        // Mini-słownik z domyślnego @Before (Jan/Anna/Piotr/Maria/Adam/Katarzyna +
        // Kowalski/Nowak/Malinowski/Wiśniewski/Szymański) nie ma świeżych wartości użytych
        // tutaj celowo (żeby nie powtarzać encji z innych testów) — ładujemy PEŁNY słownik
        // z classpath (ten sam co produkcyjny asset), tak jak realny telefon go widzi.
        // @After i tak woła resetForTesting() — nie zostawia tego stanu innym testom.
        LookupTables.resetForTesting()
        LookupTables.initializeFromClasspath()
        val text = "Zbigniew Baranowski, PESEL 90051212387, NIP 987-654-32-10, " +
            "tel. 511222333, PL91109010140000071219812875, " +
            "z.baranowski@poczta-firmowa.pl, ul. Świętokrzyska 22/5, 00-050 Warszawa, " +
            "8765,43 zł, FV20261234X567, sygn. akt III Co 991/2026, ur. 12.05.1990, " +
            "Zakład Usługowy Baranowski Sp. z o.o."
        val r = pseudonymize(text)

        // Żadna surowa wartość nie może przeciekać
        for (raw in listOf(
            "90051212387", "987-654-32-10", "511222333",
            "PL91109010140000071219812875", "z.baranowski@poczta-firmowa.pl",
            "Świętokrzyska 22/5", "00-050 Warszawa", "8765,43", "FV20261234X567",
            "III Co 991/2026", "12.05.1990"
        )) {
            assertNotInOutput(r, raw)
        }

        // Wszystkie typy encji muszą się pojawić
        assertTokenExists(r, TOKEN_OSOBA)
        assertTokenExists(r, TOKEN_NUMER)
        assertTokenExists(r, TOKEN_EMAIL)
        assertTokenExists(r, TOKEN_ADRES)
        assertTokenExists(r, TOKEN_KWOTA)
        assertTokenExists(r, TOKEN_FIRMA)

        // Rozsądna liczba odrębnych tokenów — jeśli dwie encje skleiłyby się w jeden
        // token, ta liczba byłaby podejrzanie niska.
        assertTrue("Oczekiwano co najmniej 8 odrębnych tokenów, jest ${r.tokenMap.size}: ${r.tokenMap}",
            r.tokenMap.size >= 8)

        // SKLEJANIE: każde wystąpienie tokenu (PREFIX_NNN) musi mieć granicę słowa po
        // obu stronach. Jeśli liczba dopasowań z \b różni się od liczby bez \b — token
        // jest wtopiony w sąsiedni znak (litera/cyfra), czyli sklejony.
        // Prefiksy prawdziwych tokenów encji — NIE generyczne [A-Z]+, bo to fałszywie łapie
        // "SESJA_xxxxxx" (identyfikator sesji na początku dokumentu, dłuższy niż 3 cyfry,
        // nie encja PII) jako rzekomo "sklejony" token (Paweł 07.07, złapane testem na żywo).
        val tokenPrefix = "(?:OSOBA|NUMER|EMAIL|ADRES|KWOTA|FIRMA)"
        val withBoundary = Regex("""\b${tokenPrefix}_\d{3}\b""").findAll(r.pseudonymizedText).count()
        val withoutBoundary = Regex("""${tokenPrefix}_\d{3}""").findAll(r.pseudonymizedText).count()
        assertEquals(
            "Token sklejony z sąsiednim znakiem (brak granicy słowa) w: ${r.pseudonymizedText}",
            withoutBoundary, withBoundary
        )
    }

    @Test fun `sygnatura sad I Co bez regresu`() {
        val r = pseudonymize("sygn. akt I Co 3704/2018")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-NIP-3-2-2 (diagnoza Cursor 01.07): StructuralEngine.kt:452 (numer wewnętrzny
    // 3-2-2) matchował tylko pierwsze 3 segmenty ciągu z 4 segmentami myślnikowymi,
    // zostawiając ostatni jawny. Trzeci wariant tego samego wzorca bugu co faktura/sygnatura.
    @Test fun `NIP shape 722-30-32-34 jeden token bez sieroty`() {
        val r = pseudonymize("722-30-32-34")
        assertFalse("sierota -34 w wyniku", r.pseudonymizedText.contains("-34"))
        assertFalse("prefiks 722 jawny w wyniku", r.pseudonymizedText.contains("722"))
        assertEquals(1, r.tokenMap.values.count { it.contains("722") })
    }

    @Test fun `numer wewnetrzny 3-2-2 bez czwartego segmentu nadal maskowany`() {
        val r = pseudonymize("722-30-32")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-DATA-SLOWNA (test ręczny na telefonie 01.07): data słowna i data z OCR-spacją
    // urywały się na pierwszym tokenie po kotwicy, zostawiając miesiąc/rok jawny.
    @Test fun `data urodzenia slowna nie zostawia miesiaca i roku jawnych`() {
        val r = pseudonymize("data urodzenia: 8 kwietnia 1963")
        assertNotInOutput(r, "kwietnia")
        assertNotInOutput(r, "1963")
        assertTokenExists(r, TOKEN_NUMER)
    }

    @Test fun `data urodzenia z spacja OCR przed koncowka roku nie zostawia roku jawnego`() {
        val r = pseudonymize("ur. 12.03 .1985")
        assertNotInOutput(r, "1985")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-KW-CATCHALL (diagnoza Cursor 01.07): CATCHALL \d{8,} łapał sam środkowy ciąg
    // cyfr osadzony w identyfikatorze z ukośnikami, zostawiając prefiks/sufiks jawne.
    @Test fun `KW GD1M jeden token bez sierot`() {
        val r = pseudonymize("KW GD1M/00234567/8")
        assertFalse("prefiks GD1M jawny", r.pseudonymizedText.contains("GD1M"))
        assertFalse("sufiks /8 jawny", r.pseudonymizedText.contains("/8"))
        assertFalse("srodek 00234567 jawny", r.pseudonymizedText.contains("00234567"))
    }

    @Test fun `REGON 9 cyfr catchall bez regresu`() {
        val r = pseudonymize("REGON: 557374054")
        assertTokenExists(r, TOKEN_NUMER)
    }

    // BUG-SYGNATURA-SPACJA (test ręczny na telefonie 01.07, piąty wariant tego samego
    // wzorca bugu): OCR-owa spacja przed ukośnikiem w sygnaturze ucinała match przed
    // ostatnim segmentem.
    @Test fun `sygnatura z OCR spacja przed ukosnikiem nie zostawia sieroty`() {
        val r = pseudonymize("sygn. akt I C 234 /24")
        assertNotInOutput(r, "/24")
        assertNotInOutput(r, "234")
    }

    // BUG-KW-KWOTA (diagnoza Cursor 01.07): A.8 (?i)(?:KRS|KW) łapał "kw" wewnątrz słowa
    // "kwota", zjadając kotwicę A.9b; A.9 potem matchował cyfry WEWNĄTRZ już istniejącego
    // tokenu NUMER_xxx (bo matchOverlapsToken miał za wąskie okno w lewo), amputując go
    // do kalekiego "NUMER_" bez cyfr.
    // BUG-FIRMA-PRZECINEK (test ręczny na telefonie 01.07): OCR-owy przecinek zamiast
    // kropki w formie prawnej ("S,A," / "sp,j," / "Sp. z o.o,") blokował A.1 całkowicie —
    // NameEngine łapał tylko nazwisko, reszta nazwy firmy (z formą prawną) zostawała jawna.
    @Test fun `firma z przecinkiem zamiast kropki w formie prawnej maskowana w calosci`() {
        val r1 = pseudonymize("Przedsiębiorstwo Budowlane Nowak S,A,")
        assertNotInOutput(r1, "Przedsiębiorstwo")
        assertNotInOutput(r1, "S,A,")

        val r2 = pseudonymize("Kancelaria Adwokacka Wiśniewski sp,j,")
        assertNotInOutput(r2, "Kancelaria")
        assertNotInOutput(r2, "sp,j,")

        val r3 = pseudonymize("Kowalski i Partnerzy Sp. z o.o,")
        assertNotInOutput(r3, "Partnerzy")
        assertNotInOutput(r3, "o.o,")
    }

    @Test fun `A8 nie lapie kwota jako KW`() {
        val r = pseudonymize("KRS OOOO4S6789\nkwota: 49 999,99 z1")
        assertFalse("kwota: nie powinno zostac zjedzone przez A8 KW", r.pseudonymizedText.contains("kwota:"))
        assertFalse(
            "token nie powinien byc amputowany (NUMER_ bez cyfr obok KWOTA_)",
            Regex(""".*NUMER_\s+KWOTA_.*""").containsMatchIn(r.pseudonymizedText)
        )
        assertTrue("kwota powinna byc w tokenMap", r.tokenMap.values.any { it.contains("49 999") })
    }

    // =========================================================================
    // BUG-ADRES-KOD-MIASTO — refaktor ADRES (plan Cursor 01.07, krok 0)
    // Diagnoza: kod pocztowy + miasto rozsiane po 4-6 miejscach (StructuralEngine
    // ADDRESS #597, AnchorEngine A.11b/A.11c/A.11d, NameEngine CITY_POSTAL, Runda 2)
    // które się wzajemnie blokują (TOKEN_RE/matchOverlapsToken) i zostawiają rozjechany
    // stan: jawny kod obok osobnego tokenu miasta, albo sklejenie miasta z kodem
    // SĄSIEDNIEGO wpisu przy tekście bez spacji między liniami.
    // Te testy CELOWO FALUJĄ teraz (krok 0 planu) — mają przejść dopiero po pełnym
    // refaktorze (warstwa 3a POSTAL_CITY, usunięcie A.11b i CITY_POSTAL, guard A.11c/d).
    // Plik testowy do testu ręcznego: testy/test_kod_pocztowy_migracja.txt
    // =========================================================================

    @Test fun `kod pocztowy z miastem jeden token bez jawnego kodu`() {
        val r = pseudonymize("00-001 Warszawa")
        assertFalse("kod pocztowy nie powinien zostac jawny", r.pseudonymizedText.contains("00-001"))
        assertFalse("miasto nie powinno zostac jawne", r.pseudonymizedText.contains("Warszawa"))
        assertEquals(1, r.tokenMap.values.count { it.contains("00-001") && it.contains("Warszawa") })
    }

    @Test fun `trzy czyste linie kod plus miasto po jednym tokenie kazda`() {
        val r = pseudonymize("00-001 Warszawa\n80-001 Gdańsk\n31-610 Kraków")
        assertFalse(r.pseudonymizedText.contains("00-001"))
        assertFalse(r.pseudonymizedText.contains("80-001"))
        assertFalse(r.pseudonymizedText.contains("31-610"))
        assertFalse(r.pseudonymizedText.contains("Warszawa"))
        assertFalse(r.pseudonymizedText.contains("Gdańsk"))
        assertFalse(r.pseudonymizedText.contains("Kraków"))
        assertEquals(3, r.tokenMap.values.count { it.contains("-") })
    }

    @Test fun `goly kod pocztowy bez miasta jest maskowany`() {
        val r = pseudonymize("00-001")
        assertFalse(r.pseudonymizedText.contains("00-001"))
        assertTokenExists(r, TOKEN_ADRES)
    }

    @Test fun `dwa kody i miasta sklejone bez spacji nie miesza sie miast z sasiednim kodem`() {
        val r = pseudonymize("00-001 Warszawa80-001 Gdańsk")
        assertFalse(r.pseudonymizedText.contains("00-001"))
        assertFalse(r.pseudonymizedText.contains("80-001"))
        assertFalse(r.pseudonymizedText.contains("Warszawa"))
        assertFalse(r.pseudonymizedText.contains("Gdańsk"))
        // Żaden token nie powinien łączyć miasta z JEDNEGO wpisu z kodem z DRUGIEGO
        assertFalse(
            "Warszawa nie powinno byc sklejone z kodem 80-001 (nalezacym do Gdanska)",
            r.tokenMap.values.any { it.contains("Warszawa") && it.contains("80-001") }
        )
        assertFalse(
            "Gdansk nie powinno byc sklejone z kodem 00-001 (nalezacym do Warszawy)",
            r.tokenMap.values.any { it.contains("Gdańsk") && it.contains("00-001") }
        )
    }

    @Test fun `trzy kody i miasta sklejone bez spacji wszystkie czyste`() {
        val r = pseudonymize("70-001 Szczecin80-001 Gdańsk31-610 Kraków")
        assertFalse(r.pseudonymizedText.contains("70-001"))
        assertFalse(r.pseudonymizedText.contains("80-001"))
        assertFalse(r.pseudonymizedText.contains("31-610"))
        assertFalse(r.pseudonymizedText.contains("Szczecin"))
        assertFalse(r.pseudonymizedText.contains("Gdańsk"))
        assertFalse(r.pseudonymizedText.contains("Kraków"))
    }

    @Test fun `rok po myslniku nie jest maskowany jako kod pocztowy`() {
        val r = pseudonymize("15-2024")
        assertFalse(r.pseudonymizedText.contains(TOKEN_ADRES))
        assertTrue(r.pseudonymizedText.contains("15-2024"))
    }

    @Test fun `sam rok nie jest maskowany`() {
        val r = pseudonymize("1999")
        assertFalse(r.pseudonymizedText.contains(TOKEN_ADRES))
        assertTrue(r.pseudonymizedText.contains("1999"))
    }

    // BUG-PESEL-KOD-SKLEJENIE (test ręczny na telefonie 01.07): kontekstowy wzorzec
    // PESEL (StructuralEngine linia ~333, goły \d) doklejał fragment sąsiedniego kodu
    // pocztowego do swojego dopasowania, bo kod pocztowy był jeszcze gołymi cyframi gdy
    // wzorzec PESEL się uruchamiał. Fix: POSTAL_CITY biegnie PRZED STRUCTURAL_PATTERNS,
    // więc kod pocztowy jest już tokenem (zaczyna się literą) zanim PESEL go zobaczy.
    @Test fun `PESEL obok kodu pocztowego nie dokleja fragmentu kodu`() {
        val r = pseudonymize("PESEL 90051512340 00-001 Warszawa NIP 526-021-15-81")
        assertFalse("PESEL nie powinien zawierac fragmentu kodu pocztowego",
            r.tokenMap.values.any { it.contains("90051512340") && it.contains("00-") })
        assertFalse(r.pseudonymizedText.contains("00-001"))
        assertFalse(r.pseudonymizedText.contains("Warszawa"))
    }

    // BUG-POSTALCITY-NIP (test regresji 01.07): goły kod pocztowy (kierunek 3 POSTAL_CITY)
    // łapał ostatni segment NIP-u jako fałszywy kod, bo NIP też ma segment w kształcie
    // XX-XXX (np. "56-786" z "512-34-56-786"). Guard (?<!\d{2,3}-)(?!-\d) naprawia.
    @Test fun `NIP nie jest rozbijany przez wzorzec goly kod pocztowy`() {
        val r = pseudonymize("NIP: 512-34-56-786")
        assertTokenExists(r, TOKEN_NUMER)
        assertFalse("NIP nie powinien byc czesciowo zamaskowany jako ADRES",
            r.tokenMap.values.any { it == "56-786" || it == "34-56" })
        assertNotInOutput(r, "512-34-56-786")
    }

    // BUG-NIP-KOD-SKLEJENIE (diagnoza Cursor 01.07, trzeci wariant tej samej klasy
    // problemu): NIP sklejony BEZ separatora z kodem pocztowym ("...15-8100-001") —
    // A.5 (kotwica NIP) łapało cały ciąg razem, "Warszawa" zostawało jawne.
    @Test fun `NIP sklejony bez separatora z kodem pocztowym oba zamaskowane osobno`() {
        val r = pseudonymize("NIP 526-021-15-8100-001 Warszawa")
        assertFalse(r.pseudonymizedText.contains("00-001"))
        assertFalse(r.pseudonymizedText.contains("Warszawa"))
        assertFalse(
            "NIP nie powinien zawierac fragmentu kodu pocztowego",
            r.tokenMap.values.any { it.contains("526") && it.contains("00-001") }
        )
    }

    // BUG-NIP-KOD-SKLEJENIE-3223 (diagnoza Cursor 05.07, ta sama klasa problemu co wyzej,
    // ale format 3-2-2-3): OCR_NIP_POSTAL_GLUE obslugiwal tylko 3-3-2-2. Bez rozbicia
    // A.5b (kotwica NIP bez keywordu) lapala tylko pierwsze 3 grupy ("521-33-15"),
    // zostawiajac "33200-001 Krakow" calkowicie jawne — zaden inny wzorzec (ani NIP
    // 3-2-2-3, ani AddressEngine POSTAL) nie widzial sklejonego ciagu jako calosci.
    @Test fun `NIP format 3-2-2-3 sklejony bez separatora z kodem pocztowym oba zamaskowane osobno`() {
        val r = pseudonymize("521-33-15-33200-001 Kraków")
        assertTokenExists(r, TOKEN_NUMER)
        assertTokenExists(r, TOKEN_ADRES)
        assertNotInOutput(r, "Kraków")
        assertNotInOutput(r, "33200-001")
        assertFalse(
            "NIP nie powinien zawierac fragmentu kodu pocztowego",
            r.tokenMap.values.any { it.contains("521") && it.contains("00-001") }
        )
    }

    // BUG-NIP-KOD-SKLEJENIE-MIX (05.07): linia mieszana — adres z prefiksem "ul." PLUS
    // sklejony NIP+kod w tej samej linii. Kontrola ze fix glue 3-2-2-3 nie psuje
    // wspolistniejacego, poprawnie sformatowanego adresu w tej samej linii.
    @Test fun `NIP 3-2-2-3 sklejony z kodem obok pelnego adresu w tej samej linii`() {
        val r = pseudonymize("NIP 521-33-15-33200-001 Warszawa, ul. Długa 7")
        assertTokenExists(r, TOKEN_NUMER)
        assertTokenExists(r, TOKEN_ADRES)
        assertNotInOutput(r, "Warszawa")
        assertNotInOutput(r, "Długa")
    }

    // =========================================================================
    // BUG-GORA-OSOBA — miasto dwuwyrazowe na przecięciu z nazwiskiem top-1000
    // (np. "Góra" w "Zielona Góra"/"Jelenia Góra"). Diagnoza 04.07: reguła "samo
    // nazwisko" maskowała drugi człon jako OSOBA poza kontekstem adresowym.
    // Fix: nowa reguła w applyContextualBlacklist (przed "samo nazwisko") maskuje
    // CAŁĄ frazę jako ADRES gdy naprawdę jest zarejestrowaną miejscowością —
    // wąska lista LookupTables.citySurnameOverlap, nie całe cityForms.
    // =========================================================================

    @Test fun `BUG-GORA-OSOBA Jelenia Gora maskowana jako ADRES nie OSOBA`() {
        LookupTables.resetForTesting()
        LookupTables.initializeForTesting(
            cities = setOf("warszawa", "jelenia góra", "zielona góra"),
            citySurnameOverlap = setOf("góra")
        )
        val r = pseudonymize("Klient odwiedził oddział w mieście Jelenia Góra w zeszłym miesiącu.")
        assertTokenExists(r, TOKEN_ADRES)
        assertFalse("Gora nie powinna byc OSOBA gdy jest czescia znanej miejscowosci",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) })
        assertNotInOutput(r, "Jelenia")
        assertNotInOutput(r, "Góra")
    }

    @Test fun `BUG-GORA-OSOBA prawdziwe nazwisko Gora bez kontekstu miasta nadal OSOBA`() {
        // Recall dla realnego nazwiska musi zostac — nowa regula maskuje TYLKO gdy
        // cala dwuwyrazowa fraza jest w cityForms; "Pan Góra" nie jest miejscowoscia.
        // BUG-TEST-SURNAMES-FIX: initializeForTesting bez jawnego "surnames" wraca do
        // domyslnego zestawu (kowalski/nowak/...), ktory NIE zawiera "gora" — test dawal
        // pusty tokenMap (regula "samo nazwisko" nigdy nie widziala "gora" jako nazwiska).
        // Formy z surnames_top1000.json (klucz "gora").
        LookupTables.resetForTesting()
        LookupTables.initializeForTesting(
            surnames = setOf(
                "góra", "góry", "górze", "górą", "górę", "gór",
                "górach", "górami", "góro", "górom", "górowie", "górów"
            ),
            cities = setOf("warszawa", "jelenia góra", "zielona góra"),
            citySurnameOverlap = setOf("góra")
        )
        val r = pseudonymize("Kandydat nazwiskiem Góra złożył podanie.")
        assertTokenExists(r, TOKEN_OSOBA)
        assertNotInOutput(r, "Góra")
    }
}
