package com.lynxmask.app

// AddressEngineTest.kt — Faza A planu migracji ADRES (PLAN_AddressEngine_Claude_2026-07-04.md,
// sekcja A1). Testy sprawdzają wynik CAŁEGO potoku (pseudonymize) — AddressEngine biegnie jako
// Warstwa 0b, przed wszystkim innym, więc jeśli poprawnie skonsumuje adres, reszta potoku nie ma
// już czego dotykać (NameEngine.applyStreetLookup usunięty 07.07 — duplikat; AnchorEngine A.11*
// zostaje aktywny jako kotwica na resztkach, patrz TOKEN_RE guard). Testy celowo NIE sprawdzają konkretnej
// reguły/warstwy (STREET_DICT vs STREET_NO_ZIP itd.) tam gdzie plan dopuszcza kilka poprawnych
// wariantów ("co najmniej 2x ADRES lub 1 duży ADRES") — liczy się brak PII w wyniku, nie
// wewnętrzny mechanizm (zasada CLAUDE.md: silnik MASKUJE, nie POPRAWIA).

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AddressEngineTest {

    @Before
    fun setup() {
        LookupTables.initializeForTesting()
    }

    @After
    fun teardown() {
        LookupTables.resetForTesting()
        resetRegexCache()
    }

    private fun pseudonymize(text: String) = PseudonymEngine.pseudonymize(text)

    private fun assertTokenExists(result: PseudonymResult, tokenType: String) {
        assertTrue("Wynik powinien zawierać token $tokenType — tokenMap: ${result.tokenMap}",
            result.tokenMap.keys.any { it.startsWith(tokenType) })
    }

    private fun assertNotInOutput(result: PseudonymResult, text: String) {
        assertFalse("Tekst '$text' nie powinien być widoczny w wyniku: ${result.pseudonymizedText}",
            result.pseudonymizedText.contains(text, ignoreCase = true))
    }

    // Blok 1 (STREET_DICT) + Blok 4 (POSTAL_K1 dwuwyrazowe miasto).
    // BUG-GORA-OSOBA: "Góra" bez "Zielona" obok nie może zostać osierocone jako OSOBA.
    @Test fun `linia 12 Zielona Gora 3 i kod pocztowy jeden token na pare`() {
        val r = pseudonymize("ul. Zielona Góra 3, 65-001 Zielona Góra")
        assertNotInOutput(r, "Góra")
        assertNotInOutput(r, "Zielona")
        assertFalse("Nie może powstać OSOBA z gołego 'Góra' — tokenMap: ${r.tokenMap}",
            r.tokenMap.keys.any { it.startsWith(TOKEN_OSOBA) })
        assertTokenExists(r, TOKEN_ADRES)
        // co najmniej 2 tokeny ADRES (para ulica+numer, para kod+miasto) — plan A1 dopuszcza
        // też 1 duży token, ale tu obie frazy są rozdzielone przecinkiem więc oczekujemy 2.
        val adresCount = r.tokenMap.keys.count { it.startsWith(TOKEN_ADRES) }
        assertTrue("Oczekiwano co najmniej 2 tokenów ADRES, jest $adresCount — tokenMap: ${r.tokenMap}",
            adresCount >= 2)
    }

    // Blok 3b (STREET_CITY) — ulica+numer+miasto BEZ kodu pocztowego.
    // BUG-SLOWNIK-KRADL-SLOWA (05.07, Paweł złapał): pierwsza wersja tego testu failowała —
    // Blok 1 (STREET_DICT, bez wymogu prefiksu "ul.") biegł jako pierwszy i łapał samo
    // "Długa 7" (bo "długa" jest w domyślnym słowniku testowym), zanim Blok 3b zobaczył całą
    // frazę z miastem — "Gdańsk" zostawał osierocony. Fix: STREET_CITY biegnie teraz PIERWSZY
    // (patrz komentarz w AddressEngine.kt), STREET_DICT PRZENIESIONY na koniec jako zbieracz
    // resztek. Ten test wymaga "gdańsk" (z diakrytykiem, tak jak w realnym słowniku miast) w
    // cityForms — domyślny testowy zestaw ma tylko ascii "gdansk".
    @Test fun `linia 7 Dluga 7 Gdansk bez kodu pocztowego`() {
        LookupTables.resetForTesting()
        LookupTables.initializeForTesting(cities = setOf("warszawa", "gdańsk", "kraków"))
        val r = pseudonymize("ul. Długa 7, Gdańsk")
        assertNotInOutput(r, "Gdańsk")
        assertNotInOutput(r, "Długa")
        assertTokenExists(r, TOKEN_ADRES)
        val adresCount = r.tokenMap.keys.count { it.startsWith(TOKEN_ADRES) }
        assertEquals("Oczekiwano dokładnie 1 token ADRES (cała fraza przez STREET_CITY) — tokenMap: ${r.tokenMap}",
            1, adresCount)
    }

    // Blok 4 K1 — kod pocztowy + miasto DWUWYRAZOWE bez ulicy w ogóle.
    @Test fun `POSTAL K1 dwa slowa miasta`() {
        val r = pseudonymize("10-100 Zielona Góra")
        assertNotInOutput(r, "Góra")
        assertNotInOutput(r, "Zielona")
        assertTokenExists(r, TOKEN_ADRES)
        val adresCount = r.tokenMap.keys.count { it.startsWith(TOKEN_ADRES) }
        assertEquals("Oczekiwano dokładnie 1 token ADRES — tokenMap: ${r.tokenMap}", 1, adresCount)
    }

    // Blok 4 K2 — miasto przed kodem pocztowym.
    @Test fun `POSTAL K2 miasto przed kodem`() {
        val r = pseudonymize("Warszawa, 00-001")
        assertNotInOutput(r, "Warszawa")
        val adresCount = r.tokenMap.keys.count { it.startsWith(TOKEN_ADRES) }
        assertEquals("Oczekiwano dokładnie 1 token ADRES — tokenMap: ${r.tokenMap}", 1, adresCount)
    }

    // Kolejność NO_ZIP przed FULL — match musi zaczynać się od "ul. Jana", nie od środka nazwy.
    @Test fun `Jana Pawla II nie zaczyna match od Pawla`() {
        val r = pseudonymize("ul. Jana Pawła II 10/5 20-001 Lublin")
        assertNotInOutput(r, "Jana Pawła")
        assertNotInOutput(r, "Lublin")
        val streetToken = r.tokenMap.entries.firstOrNull { it.value.trim().startsWith("ul.", ignoreCase = true) }
        assertNotNull("Oczekiwano tokenu zaczynającego się od 'ul. Jana' — tokenMap: ${r.tokenMap}", streetToken)
        assertTrue("Token nie powinien zaczynać się w środku nazwy ulicy (od 'Pawła') — wartość: ${streetToken?.value}",
            streetToken!!.value.trim().startsWith("ul. Jana", ignoreCase = true))
    }

    // Blok 4 K1 — trzy pary kod+miasto w jednej linii, każda osobnym tokenem.
    @Test fun `trzy pary kod miasto w jednej linii`() {
        val r = pseudonymize("00-001 Warszawa, 80-001 Gdańsk, 31-610 Kraków")
        assertNotInOutput(r, "Warszawa")
        assertNotInOutput(r, "Gdańsk")
        assertNotInOutput(r, "Kraków")
        assertNotInOutput(r, "-001")
        assertNotInOutput(r, "31-610")
        val adresCount = r.tokenMap.keys.count { it.startsWith(TOKEN_ADRES) }
        assertEquals("Oczekiwano dokładnie 3 tokenów ADRES — tokenMap: ${r.tokenMap}", 3, adresCount)
    }
}
