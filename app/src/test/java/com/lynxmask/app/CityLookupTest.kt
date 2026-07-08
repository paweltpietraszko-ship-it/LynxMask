package com.lynxmask.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Testy ładowania słowników cities_forms.json + medical_facilities.json
 * oraz detekcji miast (ADRES) i braku fałszywych OSOBA.
 *
 * Uruchomienie: gradlew :app:testDebugUnitTest --tests "*.CityLookupTest"
 */
class CityLookupTest {

    @Before
    fun setup() {
        LookupTables.resetForTesting()
        LookupTables.initializeFromClasspath()
        if (!LookupTables.initialized) error("LookupTables nie załadowane")
    }

    // ── Ładowanie słowników ──────────────────────────────────────────────────

    @Test fun `cityForms zaladowany i niepusty`() {
        assertTrue("cityForms puste", LookupTables.cityForms.isNotEmpty())
        println("cityForms: ${LookupTables.cityForms.size} form")
    }

    @Test fun `medForms zaladowany i niepusty`() {
        assertTrue("medForms puste", LookupTables.medForms.isNotEmpty())
        println("medForms: ${LookupTables.medForms.size} terminów")
    }

    @Test fun `cityForms zawiera podstawowe formy miast`() {
        val forms = LookupTables.cityForms
        // mianownik
        assertTrue("brak 'warszawa'",   forms.contains("warszawa"))
        assertTrue("brak 'krakow'",     forms.contains("krakow") || forms.contains("kraków"))
        assertTrue("brak 'gdansk'",     forms.contains("gdansk") || forms.contains("gdańsk"))
        // odmiany (z cities_forms.json)
        assertTrue("brak 'warszawie'",  forms.contains("warszawie"))
        assertTrue("brak 'krakowie'",   forms.contains("krakowie") || forms.contains("krakow"))
        assertTrue("brak 'gdansku'",    forms.contains("gdansku") || forms.contains("gdańsku"))
    }

    @Test fun `medForms zawiera podstawowe terminy`() {
        val forms = LookupTables.medForms
        assertTrue("brak 'szpital'",      forms.contains("szpital"))
        assertTrue("brak 'klinika'",      forms.contains("klinika"))
        assertTrue("brak 'przychodnia'",  forms.contains("przychodnia"))
        assertTrue("brak 'pogotowie'",    forms.contains("pogotowie"))
    }

    // ── Detekcja miast jako ADRES ────────────────────────────────────────────

    @Test fun `miasto po przyimku tagowane jako ADRES`() {
        val cases = listOf(
            "Zamieszkały w Warszawie, ul. Lipowa 3." to "Warszawie",
            "Pacjent przybył z Gdańska." to "Gdańska",
            "Przelew do Krakowa." to "Krakowa"
        )
        for ((input, city) in cases) {
            val result = PseudonymEngine.pseudonymize(input, emptyList())
            val adresTokens = result.tokenMap.filterKeys { it.startsWith("ADRES_") }
            val masked = adresTokens.values.any {
                it.replace(" ", "").lowercase().contains(city.lowercase().take(5))
            }
            if (!masked) {
                println("MISS city='$city' in: $input")
                println("  ADRES tokens: $adresTokens")
            }
            assertTrue("Miasto '$city' nie oznaczone jako ADRES w: $input", masked)
        }
    }

    // BUG-ODMIANA-MIAST-DWUCZLONOWE-FIX (05.07): cities_forms.json miał wcześniej TYLKO
    // mianownik dla nazw dwuwyrazowych ("Jelenia Góra"), nie "Jeleniej Górze" — odmieniony
    // zapis w prozie przechodził przez CITY_PREP niezauważony. generate_city_forms_full.py
    // (Morfeusz2, strategia adj+rzeczownik / rzeczownik+adj / adj+adj) dołożył pełną odmianę.
    @Test fun `dwuczlonowa nazwa miasta odmieniona po przyimku tagowana jako ADRES`() {
        val cases = listOf(
            "Klient mieszka w Jeleniej Górze od lat." to "jeleni",
            "Zamieszkały w Nowym Targu." to "targ",
            "Pochodzi z Białej Podlaskiej." to "podlask",
            "Firma z siedzibą w Dąbrowie Górniczej." to "górnicz"
        )
        for ((input, stem) in cases) {
            val result = PseudonymEngine.pseudonymize(input, emptyList())
            val adresTokens = result.tokenMap.filterKeys { it.startsWith("ADRES_") }
            val masked = adresTokens.values.any { it.lowercase().contains(stem) }
            if (!masked) {
                println("MISS stem='$stem' in: $input")
                println("  ADRES tokens: $adresTokens")
            }
            assertTrue("Odmieniona nazwa miasta ('$stem') nie oznaczona jako ADRES w: $input",
                masked)
        }
    }

    // BUG-DIAKRYTYKI-GRANICA + BUG-CITY-PREP-CASE-FIX (08.07, test telefon
    // test_b_word_boundary_diacritics_08_07.txt, sekcje B7-B9): miasto po przyimku w formie
    // NIEODMIENIONEJ kończącej się na polską literę diakrytyczną (ń, ź) zostawało jawne —
    // dwa nałożone bugi: \b nie rozpoznawał granicy po diakrytyku (WORD_END_UNICODE fix),
    // i globalne (?i) na całym CITY_PREP_REGEX pozwalało "opcjonalnemu drugiemu słowu"
    // złapać dowolne małe słowo z dalszej części zdania ("Toruń dnia"), co psuło lookup
    // w cityForms i cofało cały match.
    @Test fun `miasto nieodmienione po przyimku z konczaca sie diakrytykiem nie zostaje jawne`() {
        val cases = listOf(
            "Umowa zawarta w Toruń dnia 01.01.2025." to "toruń",
            "Zamawiający zamieszkały w Poznań od 2019 roku." to "poznań",
            "Dostawa towaru do Łódź w terminie 14 dni." to "łódź"
        )
        for ((input, city) in cases) {
            val result = PseudonymEngine.pseudonymize(input, emptyList())
            assertFalse("Miasto '$city' zostało jawne (osierocone) w wyniku: ${result.pseudonymizedText}",
                result.pseudonymizedText.lowercase().contains(city))
            val adresTokens = result.tokenMap.filterKeys { it.startsWith("ADRES_") }
            assertTrue("Miasto '$city' nie oznaczone jako ADRES w: $input  tokeny=$adresTokens",
                adresTokens.values.any { it.lowercase().contains(city.take(4)) })
        }
    }

    @Test fun `miasto przed kodem pocztowym tagowane jako ADRES`() {
        val cases = listOf(
            "Warszawa, 00-001" to "Warszawa",
            "Kraków 30-002" to "Kraków",
            "Wrocław, 50-100" to "Wrocław"
        )
        for ((input, city) in cases) {
            val result = PseudonymEngine.pseudonymize(input, emptyList())
            val adresTokens = result.tokenMap.filterKeys { it.startsWith("ADRES_") }
            val masked = adresTokens.values.any {
                it.lowercase().contains(city.lowercase().take(5))
            }
            if (!masked) {
                println("MISS city='$city' in: $input")
                println("  ADRES tokens: $adresTokens")
            }
            assertTrue("Miasto '$city' nie oznaczone jako ADRES w: $input", masked)
        }
    }

    // ── Blocklist OSOBA — miasto nie jest osobą ──────────────────────────────

    @Test fun `miasto nie jest tagowane jako OSOBA`() {
        val cases = listOf(
            "Miejscowość: Warszawa",
            "Siedziba: Gdańsk",
            "Lokalizacja: Wrocław",
            "Adres zamieszkania: Kraków"
        )
        for (input in cases) {
            val result = PseudonymEngine.pseudonymize(input, emptyList())
            val osobaTokens = result.tokenMap.filterKeys { it.startsWith("OSOBA_") }
            if (osobaTokens.isNotEmpty()) {
                println("FALSE POSITIVE OSOBA w: $input → $osobaTokens")
            }
            assertTrue("Fałszywy OSOBA w: $input  tokeny=$osobaTokens", osobaTokens.isEmpty())
        }
    }

    @Test fun `prawdziwe imie z nazwy miasta nie blokowane`() {
        // "Anna" to miasto (Annopol) ale też imię — gdy jest z nazwiskiem, dalej OSOBA
        val result = PseudonymEngine.pseudonymize("Zleceniodawca: Anna Kowalska", emptyList())
        val osoba = result.tokenMap.filterKeys { it.startsWith("OSOBA_") }
        assertTrue("Anna Kowalska powinna być OSOBA, tokeny=$osoba", osoba.isNotEmpty())
    }

    // ── Brak regresu — złote przypadki ──────────────────────────────────────

    @Test fun `regres - imie i nazwisko nadal maskowane`() {
        val cases = mapOf(
            "Dłużnik: Jan Kowalski" to "kowalsk",
            "Pełnomocnik: Anna Wiśniewska" to "wiśniewsk",
            "Pan Piotr Szymański złożył wniosek." to "szymańsk"
        )
        for ((input, stem) in cases) {
            val result = PseudonymEngine.pseudonymize(input, emptyList())
            val osoba = result.tokenMap.filterKeys { it.startsWith("OSOBA_") }
            val found = osoba.values.any { it.lowercase().contains(stem) }
            assertTrue("Regres OSOBA: '$stem' nie zamaskowane w '$input'  tokeny=$osoba", found)
        }
    }

    @Test fun `regres - ulica nadal maskowana`() {
        val result = PseudonymEngine.pseudonymize(
            "zamieszkały przy ul. Lipowej 3", emptyList()
        )
        val adres = result.tokenMap.filterKeys { it.startsWith("ADRES_") }
        assertTrue("Regres ADRES: ul. Lipowej 3 nie zamaskowana  tokeny=$adres",
            adres.values.any { it.lowercase().contains("lipow") })
    }
}
