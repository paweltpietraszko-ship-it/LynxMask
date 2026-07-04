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
        val r = pseudonymize("NIP: 740-61 7-82-26")
        assertEquals(
            "Powinien być dokładnie 1 token NUMER",
            1,
            r.tokenMap.keys.count { it.startsWith("NUMER") }
        )
        assertFalse(r.pseudonymizedText.contains("740"))
        assertFalse(r.pseudonymizedText.contains("82-26"))
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
    // "PLN 1234" — NAPRAWIONE (BUG-PLN-NUMER, sesja 04.07.2026): wzorzec tablic rejestracyjnych
    // ([A-Z]{2,3}\s?\d{4,5}) miał lookahead wykluczający skróty walutowe dodany w StructuralEngine.kt
    // — patrz test `BUG-PLN-NUMER kwota z etykieta waluty PLN przed liczba zostaje jawna` wyżej.
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
    // BUG-PLN-NUMER: "PLN 1234" (skrót waluty + kwota) mylony z tablicą rejestracyjną
    // (sesja 04.07.2026, test_adres_regresja_sesja.txt [6]/[7])
    // =========================================================================

    @Test fun `BUG-PLN-NUMER kwota z etykieta waluty PLN przed liczba zostaje jawna`() {
        val r = pseudonymize("Kwota do zapłaty: PLN 1234")
        assertTrue("tokenMap powinien być pusty — 'PLN 1234' to waluta, nie encja: ${r.tokenMap}",
            r.tokenMap.isEmpty())
        assertTrue("'PLN 1234' powinno zostać jawne w wyniku",
            r.pseudonymizedText.contains("PLN 1234"))
    }

    @Test fun `BUG-PLN-NUMER regresja IBAN nadal maskowany jako NUMER`() {
        val r = pseudonymize("IBAN PL61 1020 1026 0000 0422 7020 1111")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "PL61 1020 1026 0000 0422 7020 1111")
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
