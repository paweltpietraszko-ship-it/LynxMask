package com.lynxmask.app

// TokenMapJsonTest.kt — testy JVM (test/, nie androidTest/)
//
// Testuje tokenMapJson() extension function — czyste Kotlin, zero Android, zero org.json.
// Uruchamialne przez: ./gradlew test
//
// Lokalizacja: app/src/test/java/com/lynxmask/app/TokenMapJsonTest.kt
//
// Nie wymaga żadnych dodatkowych zależności testowych.

import org.junit.Assert.*
import org.junit.Test

class TokenMapJsonTest {

    private fun wynik(tokenMap: Map<String, String>) = PseudonymResult(
        pseudonymizedText = "SESJA_TEST01\njakiś tekst",
        sessionId         = "TEST01",
        tokenMap          = tokenMap,
        flags             = emptyList(),
        riskScore         = RiskScore.GREEN,
        qualityWarning    = null
    )

    // Parser do asercji — iteracyjny, zero regex, zero backtrackingu
    private fun parseJson(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        fun skipTo(c: Char): Int {
            while (i < json.length && json[i] != c) i++
            return i
        }
        fun readString(): String {
            i++ // pomiń otwierający "
            val sb = StringBuilder()
            while (i < json.length) {
                when (val c = json[i++]) {
                    '"'  -> return sb.toString()
                    '\\' -> when (json[i++]) {
                        '"'  -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n'  -> sb.append('\n')
                        'r'  -> sb.append('\r')
                        't'  -> sb.append('\t')
                        else -> sb.append(json[i - 1])
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
        while (i < json.length) {
            skipTo('"')
            if (i >= json.length) break
            val key = readString()
            skipTo('"')
            if (i >= json.length) break
            val value = readString()
            result[key] = value
        }
        return result
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test fun pusta_mapa_daje_pusty_obiekt_json() {
        assertEquals("{}", wynik(emptyMap()).tokenMapJson())
    }

    @Test fun jeden_token_roundtrip() {
        val parsed = parseJson(wynik(mapOf("OSOBA_001" to "Jan Kowalski")).tokenMapJson())
        assertEquals("Jan Kowalski", parsed["OSOBA_001"])
    }

    @Test fun wiele_tokenow_roznych_typow() {
        val map = mapOf(
            "OSOBA_001" to "Jan Kowalski",
            "FIRMA_001" to "Acme Sp. z o.o.",
            "NUMER_001" to "12345678901",
            "ADRES_001" to "ul. Lipowa 14/3",
            "KWOTA_001" to "1 500,00 PLN"
        )
        val parsed = parseJson(wynik(map).tokenMapJson())
        assertEquals(5, parsed.size)
        assertEquals("Acme Sp. z o.o.", parsed["FIRMA_001"])
        assertEquals("1 500,00 PLN", parsed["KWOTA_001"])
    }

    // ── Ataki na serializację ──────────────────────────────────────────────────

    @Test fun cudzyslow_w_wartosci_nie_lamie_json() {
        val map = mapOf("OSOBA_001" to """Jan "Kowal" Kowalski""")
        val json = wynik(map).tokenMapJson()
        // JSON nie może zawierać raw cudzysłowu wewnątrz wartości
        assertFalse("Raw cudzysłów w JSON", json.contains(""""Jan "Kowal""""))
        // Roundtrip
        val parsed = parseJson(json)
        assertEquals("""Jan "Kowal" Kowalski""", parsed["OSOBA_001"])
    }

    @Test fun backslash_w_wartosci_nie_lamie_json() {
        val map = mapOf("ADRES_001" to """C:\Users\Jan""")
        val json = wynik(map).tokenMapJson()
        val parsed = parseJson(json)
        assertEquals("""C:\Users\Jan""", parsed["ADRES_001"])
    }

    @Test fun polskie_znaki_przezyja_serializacje() {
        val map = mapOf(
            "OSOBA_001" to "Łódź Śródmieście",
            "ADRES_001" to "ul. Żółtego 14"
        )
        val parsed = parseJson(wynik(map).tokenMapJson())
        assertEquals("Łódź Śródmieście", parsed["OSOBA_001"])
        assertEquals("ul. Żółtego 14", parsed["ADRES_001"])
    }

    @Test fun newline_w_wartosci_escapowany() {
        val map = mapOf("ADRES_001" to "ul. Lipowa 14\nWarszawa")
        val json = wynik(map).tokenMapJson()
        // \n musi być escapowane — surowy newline w JSON to błąd składni
        assertFalse("Surowy newline w JSON", json.contains("\n\""))
        assertTrue("Escaped \\n brak w JSON", json.contains("\\n"))
        val parsed = parseJson(json)
        assertEquals("ul. Lipowa 14\nWarszawa", parsed["ADRES_001"])
    }

    @Test fun klucz_z_podkreslnikiem_i_zerami_roundtrip() {
        val map = mapOf("OSOBA_001" to "A", "OSOBA_100" to "B", "ADRES_099" to "C")
        val parsed = parseJson(wynik(map).tokenMapJson())
        assertEquals("A", parsed["OSOBA_001"])
        assertEquals("B", parsed["OSOBA_100"])
        assertEquals("C", parsed["ADRES_099"])
    }

    // ── Granice rozmiaru ───────────────────────────────────────────────────────

    @Test fun duza_mapa_1000_tokenow_parsuje_sie_poprawnie() {
        val map = (1..1000).associate {
            "OSOBA_${it.toString().padStart(3, '0')}" to "Osoba $it Nowak"
        }
        val parsed = parseJson(wynik(map).tokenMapJson())
        assertEquals(1000, parsed.size)
        assertEquals("Osoba 1 Nowak", parsed["OSOBA_001"])
        assertEquals("Osoba 500 Nowak", parsed["OSOBA_500"])
        assertEquals("Osoba 1000 Nowak", parsed["OSOBA_1000"])
    }

    @Test fun bardzo_dluga_wartosc_przezywa_serializacje() {
        val longVal = "A".repeat(5000)
        val parsed = parseJson(wynik(mapOf("OSOBA_001" to longVal)).tokenMapJson())
        assertEquals(5000, parsed["OSOBA_001"]?.length)
    }

    // ── sessionId nie trafia do tokenMap ──────────────────────────────────────

    @Test fun sessionId_nie_jest_w_tokenMapJson() {
        val result = wynik(mapOf("OSOBA_001" to "Jan"))
        val json = result.tokenMapJson()
        assertFalse("sessionId w tokenMapJson", json.contains("SESJA_"))
        assertFalse("sessionId w tokenMapJson", json.contains(result.sessionId))
    }

    // ── Kolejność escapowania ──────────────────────────────────────────────────

    @Test fun backslash_przed_cudzylowem_kolejnosc_krytyczna() {
        // Wartość z obydwoma: backslash i cudzysłów
        // Błędna kolejność: najpierw escapuj " → \" potem \ → \\ dałoby \\\"
        // Poprawna kolejność: najpierw \ → \\ potem " → \" daje \\\"  (czyli \" to cudzysłów w JSON)
        val val1 = """C:\"quoted"\path"""  // C:\"quoted"\path
        val json = wynik(mapOf("OSOBA_001" to val1)).tokenMapJson()
        val parsed = parseJson(json)
        assertEquals(val1, parsed["OSOBA_001"])
    }
}
