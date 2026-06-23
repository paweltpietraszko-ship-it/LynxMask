package com.lynxmask.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Macierz formatów NIP — z i bez słowa kluczowego "NIP". Regresja po BUG-NIP-CTX-3223. */
class NipFormatMatrixTest {

    @Before
    fun setup() {
        LookupTables.initializeForTesting()
    }

    private fun masked(input: String): Boolean {
        val r = PseudonymEngine.pseudonymize(input, emptyList())
        return r.tokenMap.values.any { tok ->
            tok.filter { it.isDigit() }.length >= 10 &&
                (tok.contains("5260001329") || tok.contains("5213340001") ||
                    tok.contains("5211000005") || tok.contains("4151122269") ||
                    tok.replace(Regex("""[\s\-.]"""), "") == "5260001329" ||
                    tok.replace(Regex("""[\s\-.]"""), "") == "5213340001" ||
                    tok.replace(Regex("""[\s\-.]"""), "") == "5211000005" ||
                    tok.replace(Regex("""[\s\-.]"""), "") == "4151122269")
        } || r.tokenMap.isNotEmpty() && !input.filter { it.isDigit() }.let { digits ->
            r.pseudonymizedText.contains(digits.take(10))
        }
    }

    private fun case(label: String, input: String, expectMasked: Boolean) {
        val r = PseudonymEngine.pseudonymize(input, emptyList())
        val numerTokens = r.tokenMap.filterKeys { it.startsWith("NUMER") }
        val digitsInOutput = Regex("""\d{3}[-\s.]?\d{3}[-\s.]?\d{2}[-\s.]?\d{2}""").containsMatchIn(r.pseudonymizedText) ||
            Regex("""\b\d{10}\b""").containsMatchIn(r.pseudonymizedText)
        // Partial mask: NIP podzielony na wiele tokenów = partial. Oryginalne cyfry NIP muszą
        // być przypisane do JEDNEGO klucza NUMER (10 cyfr w jednej wartości tokenMap).
        val nipDigits = input.filter { it.isDigit() }.takeLast(10)
        val maskedAsOne = numerTokens.values.any { v ->
            v.filter { it.isDigit() }.takeLast(10) == nipDigits
        }
        val isMasked = numerTokens.isNotEmpty() && !digitsInOutput &&
            (!expectMasked || maskedAsOne)
        println(
            "${if (isMasked == expectMasked) "OK" else "FAIL"} | $label\n" +
                "  IN:  $input\n" +
                "  OUT: ${r.pseudonymizedText.replace("\n", " ")}\n" +
                "  MAP: $numerTokens"
        )
        assertEquals("[$label] expectMasked=$expectMasked", expectMasked, isMasked)
    }

    @Test
    fun `macierz formatow NIP`() {
        // Poprawne sumy kontrolne:
        // 526-000-13-29, 521-334-00-01, 521-10-00-005, 415-112-22-69

        println("\n=== Z prefiksem NIP ===")
        case("NIP: 3-3-2-2", "NIP: 526-000-13-29", true)
        case("NIP bez dwukropka", "NIP 5260001329", true)
        case("nip lowercase", "nip: 526-000-13-29", true)
        case("NlP artefakt l", "NlP: 526-000-13-29", true)
        case("N1P artefakt cyfra 1", "N1P: 526-000-13-29", true)
        case("NLP artefakt L", "NLP: 526-000-13-29", true)
        case("NIP 3-2-2-3", "NIP: 521-10-00-005", true)
        case("NIP OCR bledna suma 3-3-2-2", "NIP: 5260001320", true) // kontekstowy, bez S5

        println("\n=== BEZ prefiksu NIP (tylko numer) ===")
        case("3-3-2-2 myslniki", "526-000-13-29", true)
        case("10 cyfr ciaglem", "5260001329", true)
        case("spacje 3-3-2-2", "526 000 13 29", true)
        case("kropki 3-3-2-2", "526.000.13.29", true)
        case("3-2-2-3 myslniki", "521-10-00-005", true)
        case("3-2-2-3 ciaglem", "5211000005", true)
        case("PL prefix", "PL526-000-13-29", true)
        case("PL prefix compact", "PL5260001329", true)

        println("\n=== Kontekst bez slowa NIP ===")
        case("Nr:", "Nr: 526-000-13-29", true)
        case("Numer identyfikacji", "Numer identyfikacji podatkowej: 526-000-13-29", true)
        case("Sprzedawca", "Sprzedawca: Kowalski Sp. z o.o., 526-000-13-29", true)
        case("w linii faktury", "Nabywca: Jan Nowak, 415-112-22-69, Warszawa", true)
        case("sam w polu formularza", "526-000-13-29", true)

        case("nip_sprzedawcy", "nip_sprzedawcy: 451-052-35-26", true)

        println("\n=== Negatywne (nie powinno maskowac) ===")
        case("10 cyfr zla suma bez NIP", "5260001320", false)
        case("losowy 10 cyfr zla suma", "1234567890", false)

        println("\n=== Uwagi (inny typ numeru) ===")
        case("9 cyfr = REGON nie NIP", "123456789", true) // wzorzec REGON 9-cyfrowy
    }
}
