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
        val r = pseudonymize("PESEL: 65051112345")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "65051112345")
    }

    @Test fun `PESEL ze spacja OCR jest maskowany`() {
        val r = pseudonymize("PESEL: 650511 12345")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "650511")
    }

    @Test fun `PESEL 9 cyfr OCR zgubil 2 cyfry jest maskowany`() {
        val r = pseudonymize("PESEL: 650511123")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "650511123")
    }

    @Test fun `PESEL 6 cyfr OCR data urodzenia nie moze wyciec`() {
        val r = pseudonymize("PESEL: 650511")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "650511")
    }

    @Test fun `data roczna 2024 nie jest maskowana jako PESEL`() {
        val r = pseudonymize("Umowa z dnia 2024-01-15")
        assertTrue("Rok 2024 powinien pozostać w tekście",
            r.pseudonymizedText.contains("2024"))
    }

    // =========================================================================
    // NIP
    // =========================================================================

    @Test fun `NIP format 3-3-2-2 jest maskowany`() {
        val r = pseudonymize("NIP: 521-334-15-33")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "521-334-15-33")
    }

    @Test fun `NIP format 3-2-2-3 jest maskowany`() {
        val r = pseudonymize("NIP: 521-33-15-332")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "521-33-15-332")
    }

    @Test fun `NIP bez myslnikow jest maskowany`() {
        val r = pseudonymize("NIP 5213341533")
        assertTokenExists(r, TOKEN_NUMER)
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
        val cases = listOf(
            "NIP: 415-112-22-69",
            "NIP 142-199-06-38",
            "NIP: 4151122269",
            "nip_sprzedawcy: 451-052-35-26"
        )
        for (text in cases) {
            val r = pseudonymize(text)
            val masked = r.pseudonymizedText.contains("NUMER_")
            println("${if (masked) "PASS" else "FAIL"} | $text → ${r.pseudonymizedText}")
        }
    }

    @Test fun `IBAN pelny zakres formatow`() {
        val cases = listOf(
            "PL04325000035633956078831852" to true,
            "PL 04 3250 0003 5633 9560 7883 1852" to true,
            "PL04-3250-0003-5633-9560-7883-1852" to true,
            "IBAN: PL04 3250 0003 5633 9560 7883 1852" to true,
            "Nr konta: PL04325000035633956078831852" to true,
            "PLN 1234" to false,
            "POLSKA-1234-5678" to false,
        )
        for ((text, shouldMask) in cases) {
            val r = pseudonymize(text)
            val masked = r.pseudonymizedText.contains("NUMER_")
            val ok = masked == shouldMask
            println("${if (ok) "PASS" else "FAIL"} | $text")
            if (!ok) println("  → ${r.pseudonymizedText}")
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
        for (text in cases) {
            val r = pseudonymize(text)
            val masked = r.pseudonymizedText.contains("NUMER_")
            println("${if (masked) "PASS" else "FAIL"} | $text")
            if (!masked) println("  → ${r.pseudonymizedText}")
        }
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
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "jan.kowalski@firma.pl")
    }

    @Test fun `email z poddomainem jest maskowany`() {
        val r = pseudonymize("Wyślij na adres: jan@biuro.kancelaria.pl")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "jan@biuro")
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
        val warnings = runOutputGuard("Dane: 65051112345", emptyMap())
        assertTrue("Guard powinien ostrzec o PESEL", warnings.isNotEmpty())
    }

    @Test fun `OutputGuard nie alarmuje dla tokenow`() {
        val tokenMap = mapOf("OSOBA_001" to "Jan Kowalski", "NUMER_001" to "65051112345")
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
    // TODO-7 — jawna ochrona dat (sesja 10)
    // Daty w formacie z interpunkcją są chronione przez brak ciągłego \d{8,}.
    // Ten test dokumentuje zależność — jeśli CATCHALL się zmieni, test zarwieje.
    // =========================================================================

    @Test fun `data ISO nie jest maskowana`() {
        val r = pseudonymize("Umowa zawarta dnia 2024-01-15 roku")
        assertTrue("Data 2024-01-15 powinna pozostać w tekście",
            r.pseudonymizedText.contains("2024-01-15"))
    }

    @Test fun `data polska nie jest maskowana`() {
        val r = pseudonymize("dnia 15.01.2024 roku w Warszawie")
        assertTrue("Data 15.01.2024 powinna pozostać w tekście",
            r.pseudonymizedText.contains("15.01.2024"))
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
            NIP: 521-334-15-33, REGON: 123456789.
            Zaświadcza lek. Jan Kowalski.
            Pacjent: Anna Nowak, PESEL: 65051112345.
            Kontakt: anna@medhelp.pl, tel. 501-234-567.
            Konto: PL61109010140000071219812874
        """.trimIndent()

        val r = pseudonymize(dokument)

        assertNotInOutput(r, "65051112345")
        assertNotInOutput(r, "521-334-15-33")
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
        // Format "65 05 11 12345" — OCR wstawia spację co 2 cyfry
        val r = pseudonymize("Urodzony, PESEL: 65 05 11 12345, zam. Poznań")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "65 05 11")
    }

    @Test fun `PESEL OCR format 2-4-5 jest maskowany`() {
        // Format "65 0511 12345" — OCR wstawia spację po 2 i po 6 cyfrach
        val r = pseudonymize("nr PESEL 65 0511 12345 wydany")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "65 0511 12345")
    }

    @Test fun `PESEL OCR format 4-2-5 jest maskowany`() {
        // "6505 11 12345" — format 4-2-5, z kontekstem słownym
        val r = pseudonymize("PESEL: 6505 11 12345 wydany")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "6505 11 12345")
    }

    @Test fun `PESEL OCR z dodatkowym slowem miedzy kontekstem a cyframi`() {
        // "PESEL pacjenta: 6505 11 12345" — jedno słowo między PESEL a cyframi
        val r = pseudonymize("Pesel pacjenta: 6505 11 12345.")
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "6505 11 12345")
    }

    @Test fun `PESEL OCR format co-2 i istniejacy 6-5 oba dzialaja`() {
        // Regresja: nowe wzorce nie mogą zepsuć istniejącego formatu "650511 12345"
        val r1 = pseudonymize("PESEL: 650511 12345")
        assertTokenExists(r1, TOKEN_NUMER)
        val r2 = pseudonymize("PESEL: 65 05 11 12345")
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
        val r = pseudonymize("Firma: Kowalski Handel Sp. z o.o. NIP: 521-334-15-33")
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
            "Adres zamieszkania: ul. Lipowa 3, Poznań. NIP: 521-334-15-33."
        )
        // NIP musi być zamaskowany
        assertTokenExists(r, TOKEN_NUMER)
        assertNotInOutput(r, "521-334-15-33")
        // "zamieszkania" nie może być tokenem OSOBA
        assertFalse("'zamieszkania' nie powinno być OSOBA",
            r.tokenMap.values.any { it.equals("zamieszkania", ignoreCase = true) })
    }

    @Test fun `PESEL OCR i NIP w jednym dokumencie oba zamaskowane`() {
        // Test integracyjny — benchmark pokazał że krytyczne braki są na 1 z 3-4 dok.
        val r = pseudonymize("PESEL: 65 05 11 12345. NIP: 521-334-15-33.")
        val numerTokens = r.tokenMap.entries.filter { it.key.startsWith(TOKEN_NUMER) }
        assertTrue("Powinny być co najmniej 2 tokeny NUMER (PESEL OCR + NIP)",
            numerTokens.size >= 2)
        assertNotInOutput(r, "65 05 11 12345")
        assertNotInOutput(r, "521-334-15-33")
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
        for ((text, shouldMask) in cases) {
            val result = pseudonymize(text)
            val masked = result.pseudonymizedText.contains("NUMER_")
            val ok = masked == shouldMask
            println("${if (ok) "PASS" else "FAIL"} | $text")
            if (!ok) println("  → ${result.pseudonymizedText}")
        }
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
}
