package com.lynxmask.app

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * FP/TP testy do przepisania OutputGuard v2.0.
 * Wszystkie przypadki negatywne (FP-check) pochodzą z realnych fragmentów
 * korpusu benchmarkowego lub akt prawnych — nie wymyślone przykłady.
 */
class OutputGuardRedesignFpTpTest {

    @Before fun setup()    { LookupTables.initializeForTesting() }
    @After  fun teardown() { LookupTables.resetForTesting() }

    private fun guard(text: String): List<GuardHit> = runOutputGuard(text, emptyMap())
    private fun red(hits: List<GuardHit>)    = hits.filter { it.level == "RED" }
    private fun yellow(hits: List<GuardHit>) = hits.filter { it.level == "YELLOW" }
    private fun hasLabel(hits: List<GuardHit>, label: String) = hits.any { it.label == label }

    // ════════════════════════════════════════════════════════════════════════
    // RED: PESEL_SPACE
    // ════════════════════════════════════════════════════════════════════════

    @Test fun peselSpaceSplit6plus5Flagged() {
        // klasyczny podział OCR po 6 cyfrach
        assertTrue(hasLabel(red(guard("PESEL: 850315 12345")), "PESEL_SPACE"))
    }

    @Test fun peselSpaceSplit3Flagged() {
        // podział po 3 cyfrach — rzadszy artefakt OCR
        assertTrue(hasLabel(red(guard("Nr: 850 31512345")), "PESEL_SPACE"))
    }

    @Test fun peselDashSplitFlagged() {
        // myślnik jako separator OCR — dowolna pozycja podziału
        assertTrue(hasLabel(red(guard("PESEL: 850315-12345")), "PESEL_SPACE"))
    }

    @Test fun peselSpaceFP_phoneNineDigits() {
        // 512 345 678 — tylko 9 cyfr (telefon), za mało na PESEL (11)
        assertFalse("9 cyfr to za mało na PESEL_SPACE",
            hasLabel(red(guard("Tel: 512 345 678")), "PESEL_SPACE"))
    }

    @Test fun peselSpaceFP_amountWithThousandSeparator() {
        // "3 500,00 PLN" — przecinek przerywa ciąg cyfr po 4 cyfrach
        assertFalse("3 500,00 ma tylko 4 cyfry przed przecinkiem",
            hasLabel(red(guard("Wynagrodzenie: 3 500,00 PLN brutto")), "PESEL_SPACE"))
    }

    @Test fun peselSpaceFP_zipCodeWithDash() {
        // "60-001" — 5 cyfr z myślnikiem, za mało na PESEL
        assertFalse("60-001 to kod pocztowy, nie PESEL",
            hasLabel(red(guard("ul. Lipowa 14/3, 60-001 Poznan")), "PESEL_SPACE"))
    }

    @Test fun peselSpaceFP_contractRef() {
        // numer umowy — żaden podciąg nie ma 11 cyfr
        assertFalse("UMW/2025/852 nie zawiera 11-cyfrowej sekwencji",
            hasLabel(red(guard("Umowa nr UMW/2025/852 z dnia 01.03.2026")), "PESEL_SPACE"))
    }

    @Test fun peselSpaceFP_dateRange() {
        // zakres dat — cyfry przedzielone kropkami, nie spacjami/myślnikami
        assertFalse("01.01.2026-31.03.2026: kropka nie jest separatorem PESEL_SPACE",
            hasLabel(red(guard("Składki za 01.01.2026-31.03.2026")), "PESEL_SPACE"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // RED: TELEFON_PELNY
    // ════════════════════════════════════════════════════════════════════════

    @Test fun telefonDotSeparatorFlagged() {
        // separator kropkowy (artefakt OCR) — nie łapał stary TELEFON YELLOW
        assertTrue(hasLabel(red(guard("Kontakt: 512.345.678")), "TELEFON_PELNY"))
    }

    @Test fun telefon0048PrefixFlagged() {
        // prefiks 0048 — stary wzorzec znał tylko +48
        assertTrue(hasLabel(red(guard("Tel: 0048 512 345 678")), "TELEFON_PELNY"))
    }

    @Test fun telefonBasicMobileFlagged() {
        // standardowy format mobilny ze spacjami
        assertTrue(hasLabel(red(guard("Telefon: 512 345 678")), "TELEFON_PELNY"))
    }

    @Test fun telefonDashMobileFlagged() {
        // standardowy format mobilny z myślnikami
        assertTrue(hasLabel(red(guard("Kom: 512-345-678")), "TELEFON_PELNY"))
    }

    @Test fun telefonFP_nineDigitCompact() {
        // 9 cyfr BEZ separatora — mandatory separator wymóg blokuje (mogłoby być REGON)
        assertFalse("361364716 bez separatora nie pasuje do TELEFON_PELNY (mandatory sep)",
            hasLabel(red(guard("REGON: 361364716")), "TELEFON_PELNY"))
    }

    @Test fun telefonFP_nipFormat() {
        // NIP format 3-3-2-2 — różny od 3-3-3 / 3-2-2, nie powinien strzelać
        assertFalse("521-334-15-33 to NIP (3-3-2-2), nie telefon",
            hasLabel(red(guard("NIP: 521-334-15-33")), "TELEFON_PELNY"))
    }

    @Test fun telefonFP_amountDecimalSeparator() {
        // kwota — przecinek i spacja nie tworzą formatu telefonicznego
        assertFalse("3 500,00 PLN nie jest telefonem",
            hasLabel(red(guard("Kwota: 3 500,00 PLN")), "TELEFON_PELNY"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // YELLOW: SYGNATURA z kotwicą słowną
    // ════════════════════════════════════════════════════════════════════════

    @Test fun sygnaturaSprawaContextFlagged() {
        // "Sprawa 2024/1234" — istniejący test, musi nadal działać po zawężeniu
        assertTrue(hasLabel(yellow(guard("Sprawa 2024/1234")), "SYGNATURA"))
    }

    @Test fun sygnaturaNrContextFlagged() {
        // hit #1 z diagnostyki (słowo "nr" w kontekście)
        assertTrue(hasLabel(yellow(guard("Karta pacjenta nr 78234/2026")), "SYGNATURA"))
    }

    @Test fun sygnaturaRepertoriumContextFlagged() {
        // hit #3 z diagnostyki ("repertorium" w kontekście)
        assertTrue(hasLabel(yellow(guard("Repertorium A nr 4567/2026")), "SYGNATURA"))
    }

    @Test fun sygnaturaSygnContextFlagged() {
        // skrót "sygn." w kontekście
        assertTrue(hasLabel(yellow(guard("Sygn. akt II K 123/25")), "SYGNATURA"))
    }

    @Test fun sygnaturaFP_noContextEtatu() {
        // FP z poprzedniej wersji: "1/1 etatu" bez słowa kontekstowego
        assertFalse("1/1 etatu: brak nr/sygn/sprawa w oknie — nie SYGNATURA",
            hasLabel(yellow(guard("Wymiar: 1/1 etatu, 40 h/tyg.")), "SYGNATURA"))
    }

    @Test fun sygnaturaFP_noContextKC() {
        // FP z poprzedniej wersji: "734/1 k.c." (art. KC) bez słowa kontekstowego
        assertFalse("734/1 KC: art. nie jest słowem kontekstowym",
            hasLabel(yellow(guard("Na podstawie art. 734/1 KC strony ustalają.")), "SYGNATURA"))
    }

    @Test fun sygnaturaFP_dateRangeFixed() {
        // hit #4 z diagnostyki (POPRZEDNI FP): "2026-31" w zakresie dat
        // "Składki za" nie jest słowem kontekstowym → teraz nie strzela
        assertFalse("2026-31 w zakresie dat bez kontekstu nr/sygn: był FP, teraz naprawiony",
            hasLabel(yellow(guard("Składki za 01.01.2026-31.03.2026: 4521,33 PLN")), "SYGNATURA"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // YELLOW: LICZBA z kotwicą słowną
    // ════════════════════════════════════════════════════════════════════════

    @Test fun liczbaWithNrContextFlagged() {
        // hit #2 z diagnostyki: Nr PWZ lekarza
        assertTrue(hasLabel(yellow(guard("Nr PWZ lekarza: 1234567")), "LICZBA"))
    }

    @Test fun liczbaWithNumerContextFlagged() {
        // "Nr ewid." — z pełnego kontekstu pism ZUS
        assertTrue(hasLabel(yellow(guard("Nr ewid. wewn.: 1234567890")), "LICZBA"))
    }

    @Test fun liczbaWithKartaContextFlagged() {
        // "karta" jako słowo kontekstowe
        assertTrue(hasLabel(yellow(guard("Karta nr 7654321")), "LICZBA"))
    }

    @Test fun liczbaFP_noContextLabValue() {
        // wynik laboratoryjny bez nr/numer/poz/pwz/karta/id
        assertFalse("12345678 j./ml bez kontekstu nr — nie LICZBA",
            hasLabel(yellow(guard("wynik badania: 12345678 j./ml")), "LICZBA"))
    }

    @Test fun liczbaFP_noContextAmount() {
        // kwota — brak kontekstu
        assertFalse("4521 bez kontekstu nr — nie LICZBA",
            hasLabel(yellow(guard("Składki: 4 521,33 PLN")), "LICZBA"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // YELLOW: URODZENIE
    // ════════════════════════════════════════════════════════════════════════

    @Test fun urodzenieKrotkaFormaFlagged() {
        // "ur. DD.MM.RRRR" — typowy skrót w pismach sądowych
        assertTrue(hasLabel(yellow(guard("Pozwany: Jan Kowalski, ur. 12.04.1985 w Kaliszu")), "URODZENIE"))
    }

    @Test fun urodzenieKompletnaFormaFlagged() {
        // "urodzony DD.MM.RRRR" — pełna forma
        assertTrue(hasLabel(yellow(guard("Pacjent urodzony 28.05.1975, miejsce: Wrocław")), "URODZENIE"))
    }

    @Test fun urodzenieKobietaFormaFlagged() {
        // "urodzona" — forma żeńska
        assertTrue(hasLabel(yellow(guard("Sprzedająca: Maria Kowalska, urodzona 01.01.1965")), "URODZENIE"))
    }

    @Test fun urodzienieFP_dataWystawienia() {
        // data wystawienia bez "ur." — nie URODZENIE
        assertFalse("Data wystawienia bez ur. — nie URODZENIE",
            hasLabel(yellow(guard("Data wystawienia: 15.03.2026")), "URODZENIE"))
    }

    @Test fun urodzienieFP_terminPlatnosci() {
        // termin płatności — brak słowa "ur."
        assertFalse("Termin płatności 30.03.2026 bez ur. — nie URODZENIE",
            hasLabel(yellow(guard("Termin płatności: 30.03.2026")), "URODZENIE"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // YELLOW: MIEJSCE_UR
    // ════════════════════════════════════════════════════════════════════════

    @Test fun miejsceUrKrotkaFormaFlagged() {
        // "ur. w Mieście" — bezpośrednio
        assertTrue(hasLabel(yellow(guard("Pozwany ur. w Kaliszu, lat 40")), "MIEJSCE_UR"))
    }

    @Test fun miejsceUrDlugaFormaFlagged() {
        // "urodzony w Mieście"
        assertTrue(hasLabel(yellow(guard("Tomasz, urodzony w Krakowie, zameldowany...")), "MIEJSCE_UR"))
    }

    @Test fun miejsceUrKobietaFlagged() {
        // "urodzona w Mieście"
        assertTrue(hasLabel(yellow(guard("Maria, urodzona w Gdańsku")), "MIEJSCE_UR"))
    }

    @Test fun miejsceUrFP_mieszkaW() {
        // "Mieszka w Mieście" — brak słowa "ur"
        assertFalse("Mieszka w Krakowie: brak ur — nie MIEJSCE_UR",
            hasLabel(yellow(guard("Mieszka w Krakowie od 2010 roku.")), "MIEJSCE_UR"))
    }

    @Test fun miejsceUrFP_prowadzonaW() {
        // "prowadzona w Warszawie" — brak "ur"
        assertFalse("prowadzona w Warszawie: brak ur — nie MIEJSCE_UR",
            hasLabel(yellow(guard("Działalność prowadzona w Warszawie.")), "MIEJSCE_UR"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // YELLOW: EMAIL_FRAGMENT
    // ════════════════════════════════════════════════════════════════════════

    @Test fun emailFragmentPartialFlagged() {
        // częściowy adres bez TLD (nie łapie EMAIL RED, łapie EMAIL_FRAGMENT)
        assertTrue(hasLabel(yellow(guard("Kontakt: jan@firma")), "EMAIL_FRAGMENT"))
    }

    @Test fun emailFragmentMangledTldFlagged() {
        // TLD zniekształcone przez OCR — cyfra zamiast litery — nie łapie EMAIL RED
        assertTrue(hasLabel(yellow(guard("Email: jan@firma.p1")), "EMAIL_FRAGMENT"))
    }

    @Test fun emailFragmentFP_noAtSign() {
        // ciąg bez @ — nie EMAIL_FRAGMENT
        assertFalse("Jan.Kowalski bez @ — nie EMAIL_FRAGMENT",
            hasLabel(yellow(guard("Osoba: Jan.Kowalski, ul. Lipowa 1")), "EMAIL_FRAGMENT"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // NAPRAWIONY TOKEN EXCLUSION
    // ════════════════════════════════════════════════════════════════════════

    @Test fun newTokenFormatOsobaExcluded() {
        // OSOBA_001 (aktualny format) musi być wykluczony przez poprawiony regex
        val hits = guard("Dane osoby OSOBA_001 zostały ukryte")
        assertTrue("OSOBA_001 nie powinien być w matchedText żadnego hitu",
            hits.none { "OSOBA_001" in it.matchedText })
    }

    @Test fun newTokenFormatNumerExcluded() {
        // NUMER_017 — "017" nie powinien wystrzelić jako fragment
        val hits = red(guard("Wartość NUMER_017 została zamaskowana"))
        assertTrue("NUMER_017 nie powinien generować RED hitu",
            hits.none { it.matchedText.contains("017") && it.matchedText.length <= 7 })
    }

    @Test fun newTokenFormatKwotaExcluded() {
        // KWOTA_001
        val hits = guard("Kwota KWOTA_001 PLN zostaje zamaskowana")
        assertTrue("KWOTA_001 nie powinien być w matchedText",
            hits.none { "KWOTA_001" in it.matchedText })
    }

    // ════════════════════════════════════════════════════════════════════════
    // USUNIĘTE REGUŁY — nie powinny strzelać
    // ════════════════════════════════════════════════════════════════════════

    @Test fun regonRuleRemoved() {
        // stary wzorzec REGON \b\d{9}\b — usunięty
        assertTrue("REGON nie powinien być flagowany (reguła usunięta)",
            yellow(guard("REGON firmy: 361364716")).none { it.label == "REGON" })
    }

    @Test fun plPrefixRuleRemoved() {
        // stary wzorzec PL_PREFIX — usunięty
        assertTrue("PL_PREFIX nie powinien być flagowany (reguła usunięta)",
            yellow(guard("Konto PL6110900104000000712")).none { it.label == "PL_PREFIX" })
    }
}
