package com.lynxmask.app

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Diagnostyczny test OutputGuard — NIE modyfikuje kodu produkcyjnego.
 * Puszcza PseudonymEngine.pseudonymize() na realistycznym korpusie
 * i zbiera result.guardHits z każdego dokumentu.
 *
 * Uruchom: .\gradlew :app:testDebugUnitTest --tests "*.OutputGuardDiagnosticTest"
 * Wyniki: w logach gradle (println widoczny z --info lub w TEST-*.xml)
 */
class OutputGuardDiagnosticTest {

    @Before fun setup()    { LookupTables.initializeForTesting() }
    @After  fun teardown() { LookupTables.resetForTesting() }

    // ── Podstawowy korpus 7 dokumentów — szybka sanity check ─────────────────
    @Test
    fun `GUARD DIAGNOSTIC 7dok sanity check`() {
        val corpus = buildManualCorpus()
        runDiagnostic("SANITY-7dok", corpus)
    }

    // ── Pełny dataset benchmarkowy (68 dok. z ground_truth.json) ─────────────
    @Test
    fun `GUARD DIAGNOSTIC 68dok dataset benchmarkowy`() {
        val gtFile = File("../dataset_fresh/ground_truth.json")
        if (!gtFile.exists()) {
            println("SKIP: ../dataset_fresh/ground_truth.json nie znaleziony — uruchom benchmark raz żeby wygenerować dataset")
            return
        }
        val docs = parseGroundTruth(gtFile.readText())
        val corpus = docs.map { it["doc_type"]!! to buildTextFromEntities(it) }
        runDiagnostic("BENCHMARK-68dok", corpus)
    }

    // ── Silnik diagnostyczny ──────────────────────────────────────────────────

    private fun runDiagnostic(label: String, corpus: List<Pair<String, String>>) {
        data class HitRecord(val doc: String, val hit: GuardHit, val snippet: String)

        val allHits   = mutableListOf<HitRecord>()
        val tokenRe   = Regex("""\b[A-Z]+_\d{3}\b""")

        corpus.forEach { (docName, rawText) ->
            val result = PseudonymEngine.pseudonymize(rawText)
            val pseudo = result.pseudonymizedText
            result.guardHits.forEach { hit ->
                val from    = maxOf(0, hit.startIndex - 30)
                val to      = minOf(pseudo.length, hit.endIndex + 30)
                val snippet = pseudo.substring(from, to).replace("\n", " ↵ ")
                allHits.add(HitRecord(docName, hit, snippet))
            }
        }

        val sep = "═".repeat(70)
        println("\n$sep")
        println("OUTPUT GUARD DIAGNOSTIC [$label] — ${corpus.size} dok., ${allHits.size} hitów")
        println(sep)

        println("\n[1] PODSUMOWANIE PER DOKUMENT")
        corpus.forEach { (docName, _) ->
            val docHits  = allHits.filter { it.doc == docName }
            val redCount = docHits.count { it.hit.level == "RED" }
            val yelCount = docHits.count { it.hit.level == "YELLOW" }
            if (redCount > 0 || yelCount > 0)
                println("  ${docName.padEnd(30)} RED=$redCount  YELLOW=$yelCount  łącznie=${docHits.size}")
        }
        val silentDocs = corpus.count { (n, _) -> allHits.none { it.doc == n } }
        println("  ... ${silentDocs} dokumentów: 0 hitów (PII poprawnie zamaskowane)")

        println("\n[2] PODSUMOWANIE PER REGUŁA")
        allHits.groupBy { it.hit.label }
            .entries.sortedByDescending { it.value.size }
            .forEach { (lbl, hits) ->
                val redC = hits.count { it.hit.level == "RED" }
                val yelC = hits.count { it.hit.level == "YELLOW" }
                val ex   = hits.take(3).joinToString(" | ") { "'${it.hit.matchedText}'" }
                println("  ${lbl.padEnd(16)} total=${hits.size.toString().padStart(3)}  " +
                    "RED=${redC.toString().padStart(2)}  YELLOW=${yelC.toString().padStart(2)}  np: $ex")
            }

        println("\n[3] WSZYSTKIE RED HITY (powinny być tylko prawdziwe wycieki)")
        val redHits = allHits.filter { it.hit.level == "RED" }
        if (redHits.isEmpty()) {
            println("  (brak — silnik zamaskował wszystkie wykryte PII przed Guard)")
        } else {
            redHits.forEach { r ->
                println("  [RED/${r.hit.label.padEnd(12)}] '${r.hit.matchedText}'")
                println("    kontekst: «${r.snippet}»")
                println("    dok: ${r.doc}")
            }
        }

        println("\n[4] WSZYSTKIE YELLOW HITY")
        allHits.filter { it.hit.level == "YELLOW" }.forEach { r ->
            val hasToken = tokenRe.containsMatchIn(r.snippet)
            val flag = if (hasToken) " [TOKEN W KONTEKŚCIE]" else ""
            println("  [YELLOW/${r.hit.label.padEnd(14)}] '${r.hit.matchedText}'$flag")
            println("    kontekst: «${r.snippet}»")
            println("    dok: ${r.doc}")
        }

        println("\n[5] HITY ZAWIERAJĄCE WŁASNY TOKEN W MATCHEDTEXT (exclusion bug check)")
        val hitsWithToken = allHits.filter { tokenRe.containsMatchIn(it.hit.matchedText) }
        if (hitsWithToken.isEmpty()) {
            println("  (brak — Guard nie flaguje własnych tokenów w matchedText)")
        } else {
            hitsWithToken.forEach { r ->
                println("  !! [${r.hit.level}/${r.hit.label}] '${r.hit.matchedText}' ← ${r.doc}")
            }
        }

        println("\n[6] STATYSTYKI KOŃCOWE")
        println("  Łączne hity:           ${allHits.size}")
        println("  RED:                   ${allHits.count { it.hit.level == "RED" }}")
        println("  YELLOW:                ${allHits.count { it.hit.level == "YELLOW" }}")
        println("  Token w matchedText:   ${hitsWithToken.size}")
        println("  Hity per dokument avg: ${"%.1f".format(allHits.size.toDouble() / corpus.size)}")
        println(sep)
    }

    // ── Parser ground_truth.json (bez zewnętrznej biblioteki) ────────────────

    private fun parseGroundTruth(json: String): List<Map<String, String>> {
        val entries  = mutableListOf<Map<String, String>>()
        val fieldRe  = Regex(""""([^"]+)":\s*"([^"]+)"""")
        val sb       = StringBuilder()
        var depth    = 0
        var inEntry  = false

        for (ch in json) {
            when (ch) {
                '{' -> { depth++; if (depth == 1) { inEntry = true; sb.clear() }; if (inEntry) sb.append(ch) }
                '}' -> { if (inEntry) sb.append(ch); depth--
                    if (depth == 0 && inEntry) {
                        inEntry = false
                        val fields = mutableMapOf<String, String>()
                        fieldRe.findAll(sb).forEach { m -> fields[m.groupValues[1]] = m.groupValues[2] }
                        if (fields["doc_type"] != null) entries.add(fields)
                    }
                }
                else -> if (inEntry) sb.append(ch)
            }
        }
        return entries
    }

    // ── Budowanie tekstu z encji dla każdego typu dokumentu ──────────────────

    private fun buildTextFromEntities(doc: Map<String, String>): String {
        val e = doc.withDefault { "" }
        return when (doc["doc_type"]) {
            "formularz" -> """
                FORMULARZ ZGŁOSZENIOWY
                Imię i nazwisko: ${e["imie_nazwisko"]}
                Adres: ${e["adres"]}
                PESEL: ${e["pesel"]}
                Dowód osobisty: ${e["dowod_osobisty"]}
                Telefon: ${e["telefon"]}
                E-mail: ${e["email"]}
                Data urodzenia: ${e["data_urodzenia"]}
                NIP: ${e["nip"]}
                Nr paszportu: ${e["numer_paszportu"]}
                Nr KW: ${e["numer_kw"]}
                Nr działki: ${e["numer_dzialki"]}
            """.trimIndent()

            "faktura" -> """
                FAKTURA VAT Nr ${e["numer_faktury"]}
                Sprzedawca: NIP ${e["nip_sprzedawcy"]}, REGON ${e["regon_sprzedawcy"]}
                Nabywca: ${e["imie_nazwisko_nabywcy"]}
                Adres nabywcy: ${e["adres_nabywcy"]}
                NIP nabywcy: ${e["nip_nabywcy"]}
                Nr konta bankowego: ${e["iban"]}
                Nr klienta: ${e["numer_klienta"]}
                Kwota: 1 234,56 PLN netto + VAT 23% = 1 518,51 PLN brutto.
            """.trimIndent()

            "wezwanie" -> """
                WEZWANIE DO ZAPŁATY
                Dłużnik: ${e["imie_nazwisko"]}
                Adres: ${e["adres"]}
                PESEL: ${e["pesel"]}
                Sygnatura komornicza: ${e["sygnatura_komornicza"]}
                Nr konta do wpłaty: ${e["iban"]}
                Kwota zadłużenia: 3 500,00 PLN. Termin zapłaty: 7 dni od doręczenia.
            """.trimIndent()

            "umowa" -> """
                UMOWA ZLECENIE Nr ${e["numer_umowy"]}
                Zleceniodawca: ${e["imie_nazwisko_zleceniodawca"]}
                Adres: ${e["adres_zleceniodawca"]}
                NIP: ${e["nip_zleceniodawca"]}
                PESEL: ${e["pesel_zleceniodawca"]}
                Zleceniobiorca: ${e["imie_nazwisko_zleceniobiorca"]}
                Adres: ${e["adres_zleceniobiorca"]}
                NIP: ${e["nip_zleceniobiorca"]}
                Nr konta: ${e["iban"]}
                Wynagrodzenie: 2 500,00 PLN. Wymiar: 1/1 etatu. Na podst. art. 734/1 KC.
            """.trimIndent()

            "notatka" -> """
                NOTATKA SŁUŻBOWA
                Autor: ${e["autor"]}
                Dotyczy osoby: ${e["osoba"]}
                PESEL: ${e["pesel"]}
                Telefon kontaktowy: ${e["telefon"]}
                Adres: ${e["adres"]}
                Data: 20.06.2026. Sprawa wewnętrzna nr 5/2026.
            """.trimIndent()

            "pismo_urzedowe" -> """
                PISMO URZĘDOWE
                Adresat: ${e["imie_nazwisko"]}
                Adres: ${e["adres"]}
                Sygnatura akt: ${e["sygnatura_akt"]}
                PESEL: ${e["pesel"]}
                Telefon: ${e["telefon"]}
                W odpowiedzi na pismo nr 234/2026 z 15.06.2026 informujemy.
            """.trimIndent()

            "decyzja_administracyjna" -> """
                DECYZJA ADMINISTRACYJNA
                Strona: ${e["imie_nazwisko"]}
                Adres: ${e["adres"]}
                PESEL: ${e["pesel"]}
                Sygnatura: ${e["sygnatura_administracyjna"]}
                NIP: ${e["nip"]}
                Nr KW: ${e["numer_kw"]}
                Na podstawie art. 104 k.p.a. niniejszą decyzją orzeka się jak w sentencji.
                Pismo nr 5/2026. Akt nr 3/4 paragrafu.
            """.trimIndent()

            else -> doc.values.joinToString(", ")
        }
    }

    // ── Ręczny korpus 7 dokumentów (szybka sanity check) ─────────────────────

    private fun buildManualCorpus() = listOf(
        "umowa_zlecenie" to """
            Umowa Nr UZ/2026/0088 zawarta dnia 15.03.2026 w Warszawie.
            Zleceniodawca: Jan Kowalski, ul. Lipowa 14/3, 60-001 Poznan.
            PESEL: 85031512345. NIP zleceniodawcy: 521-334-15-33.
            Tel. biurowy: (22) 765-43-21. Kom.: 512 345 678.
            Wynagrodzenie: 3 500,00 PLN brutto. Pkt 3/2: zakres prac.
            Na podstawie art. 734/1 KC strony ustalaja nastepujace warunki.
        """.trimIndent(),

        "faktura_vat" to """
            FAKTURA VAT nr FV/2026/000088
            Sprzedawca: ABC Logistyka Sp. z o.o., ul. Krakowska 5, 00-001 Warszawa
            NIP: 142-199-06-38, REGON: 142199063, KRS: 0000123456
            Nabywca: Marek Nowak, ul. Rozana 7, 61-245 Poznan
            NIP nabywcy: 721-033-51-23
            Nr konta: PL61 1090 1014 0000 0712 1981 2874
            Kwota: 4 500,00 PLN netto + VAT 23%: 1 035,00 PLN.
        """.trimIndent(),

        "pismo_sadowe" to """
            Sygn. akt II K 123/25
            Sad Rejonowy w Poznaniu III Wydzial Karny
            Pozwany: Tomasz Wisniewski, ur. 12.04.1985 w Kaliszu
            Adres: ul. Rozana 7/3, 61-245 Poznan
            PESEL pozwanego: 85041212345
            Dowod osobisty: ABC 123456
            Sygnatura komornicza: KM 4567/2024
        """.trimIndent(),

        "dokument_medyczny" to """
            Karta pacjenta nr 78234/2026
            Pacjent: Krzysztof Wojcik, PESEL 75052812345
            Data urodzenia: 28.05.1975, miejsce: Wroclaw
            Adres: ul. Parkowa 12a, 53-301 Wroclaw. Tel: 601 234 567.
            Badanie nr LAB-2026-0091234
            Nr PWZ lekarza: 1234567
            REGON placowki: 931020980
        """.trimIndent(),

        "akt_notarialny" to """
            Repertorium A nr 4567/2026
            Notariusz Jan Kowalski, ul. Dluga 1, Gdansk
            Sprzedajacy: Maria Kowalska, PESEL: 65051112345
            Konto: PL89 1090 1014 0000 0712 1981 2874
            Kupujacy: Piotr Wisniewski, NIP: 415-112-22-69
            Nr KW: GD1M/00123456/7, dzialka nr 123/4
        """.trimIndent(),

        "umowa_o_prace" to """
            Umowa o prace nr HR/2026/0012
            Pracodawca: XYZ Sp. z o.o., NIP 632-18-04-123, REGON 632180412
            Pracownik: Katarzyna Malinowska, PESEL: 90052712345
            Adres: ul. Wierzbowa 3/15, 80-001 Gdansk
            Wynagrodzenie: 6 500,00 PLN brutto. Wymiar: 1/1 etatu. § 5 ust. 2/3 regulaminu.
            Konto: 61 1090 1014 0000 0712 1981 2874
        """.trimIndent(),

        "pismo_urzedowe_zus" to """
            ZUS Oddzial Poznan, ul. Starolecka 31, 60-001 Poznan
            Znak sprawy: PT.070433.2026, data: 20.06.2026
            Wniosek o emeryture: Ryszard Kaminski, PESEL 52080112345
            Nr ewid. wewn.: 1234567890
            Skladki za 01.01.2026-31.03.2026: 4 521,33 PLN
            Podstawa: art. 24 ust. 1 pkt 5/2 ustawy z 17.12.1998
        """.trimIndent(),
    )
}
