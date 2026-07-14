package com.lynxmask.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Benchmark "czysty" — silnik na dokumentach BEZ degradacji OCR (dataset_clean/,
 * generator_clean.py). Mierzy sam silnik maskujący, nie odporność na OCR jak
 * BenchmarkInstrumentedTest.kt (fresh/stały/v2). Zero Androida — JVM, LookupTables
 * z classpath, ta sama metodologia porównania GT<->tokeny (norm/fuzzyMatch/
 * classifyExtraToken) co benchmark androidTest, bez elementów specyficznych dla
 * degradacji OCR (extractNumericRuns, klasyfikacja OCR_ZNIEKSZTAŁCONY itd. — nie
 * mają sensu na czystym tekście).
 *
 * Uruchomienie: gradlew :app:testDebugUnitTest --tests "*.CleanBenchmarkTest"
 * Dataset: python generator_clean.py --count 300 --scope business --seed 42
 * Wynik: benchmark_results/clean/<data_godzina>/report.txt
 */
class CleanBenchmarkTest {

    @Before
    fun setup() {
        LookupTables.resetForTesting()
        LookupTables.initializeFromClasspath()
        if (!LookupTables.initialized) error("LookupTables nie załadowane — sprawdź src/test/resources/*.json")
    }

    // ── Metodologia porównania — ta sama co BenchmarkInstrumentedTest.kt (Warstwa "chory
    // termometr" naprawiona 09.07), rozszerzona o klucze encji z generator_clean.py.
    // NIE unifikowane w jedną wspólną implementację z androidTest (Faza 4, odłożona 09.07,
    // patrz TODO.md) — świadomy duplikat, dopóki reguły się nie ustabilizują.

    private val ENTITY_TYPE_MAP = mapOf(
        "pesel" to "NUMER", "nip" to "NUMER", "nip_sprzedawcy" to "NUMER",
        "nip_nabywcy" to "NUMER", "iban" to "NUMER", "dowod_osobisty" to "NUMER",
        "numer_paszportu" to "NUMER", "regon_sprzedawcy" to "NUMER",
        "telefon" to "NUMER", "numer_faktury" to "NUMER", "numer_klienta" to "NUMER",
        "sygnatura_akt" to "NUMER", "sygnatura_komornicza" to "NUMER",
        "sygnatura_administracyjna" to "NUMER", "data_urodzenia" to "NUMER",
        "imie_nazwisko" to "OSOBA", "imie_nazwisko_nabywcy" to "OSOBA",
        "imie_nazwisko_zleceniodawca" to "OSOBA", "imie_nazwisko_zleceniobiorca" to "OSOBA",
        "autor" to "OSOBA", "osoba" to "OSOBA",
        "adres" to "ADRES", "adres_nabywcy" to "ADRES",
        "adres_zleceniobiorca" to "ADRES", "adres_zleceniodawca" to "ADRES",
        "nip_zleceniobiorca" to "NUMER", "nip_zleceniodawca" to "NUMER",
        "numer_dzialki" to "NUMER", "numer_kw" to "NUMER",
        "numer_umowy" to "NUMER", "pesel_zleceniodawca" to "NUMER",
        "email" to "EMAIL",
        // Rozszerzenie o klucze z generator_clean.py (skarga — jedyny typ prozy przeniesiony
        // do zakresu biznesowego 12.07; mentor/nadawca/... zostają dla ewentualnego --scope all).
        "skarzacy" to "OSOBA", "oskarzony" to "OSOBA", "data" to "NUMER",
        "mentor" to "OSOBA", "nadawca" to "OSOBA", "odbiorca" to "OSOBA",
        "urzednik" to "OSOBA", "narrator" to "OSOBA", "przyjaciel" to "OSOBA",
    )

    private val CRITICAL_KEYS = setOf(
        "pesel", "nip", "nip_sprzedawcy", "nip_nabywcy", "iban",
        "dowod_osobisty", "numer_paszportu",
    )

    private val POLICY_MASK_TYPES = setOf("KWOTA", "NUMER")
    private val UX_FP_DENYLIST = setOf("dane osobowe", "danych osobowych", "dane kontaktowe")

    private fun norm(v: String): String {
        val diacritics = mapOf(
            'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
            'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
            'Ą' to 'a', 'Ć' to 'c', 'Ę' to 'e', 'Ł' to 'l', 'Ń' to 'n',
            'Ó' to 'o', 'Ś' to 's', 'Ź' to 'z', 'Ż' to 'z',
        )
        return v.replace(" ", "").replace("-", "").replace("._", ".")
            .map { diacritics[it] ?: it }.joinToString("").lowercase()
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }

    private fun fuzzyMatch(a: String, b: String): Boolean {
        if (a.length < 9 || b.length < 9) return false
        val maxLen = maxOf(a.length, b.length)
        val maxDist = if (maxLen >= 24) 3 else if (maxLen >= 15) 2 else 1
        if (Math.abs(a.length - b.length) > maxDist) return false
        return levenshtein(a, b) <= maxDist
    }

    data class DetectedToken(val original: String, val token: String, val type: String)

    private fun classifyExtraToken(tok: DetectedToken, gtEntities: JSONObject): String {
        val tokN = norm(tok.original)
        val overlapsGtSameType = gtEntities.keys().asSequence().any { key ->
            ENTITY_TYPE_MAP[key] == tok.type && norm(gtEntities.getString(key)).let { gn ->
                tokN == gn || (tokN.length >= 6 && (tokN.contains(gn) || gn.contains(tokN)))
            }
        }
        return when {
            overlapsGtSameType -> "EXTRA_MASK_OK"
            tok.type in POLICY_MASK_TYPES -> "EXTRA_MASK_POLICY"
            (tok.type == "OSOBA" || tok.type == "FIRMA") && UX_FP_DENYLIST.any { tokN.contains(it) } -> "UX_FP"
            else -> "REVIEW"
        }
    }

    private data class DocResult(
        val file: String, val docType: String,
        val total: Int, val detected: Int, val criticalMissed: Int, val fp: Int,
        val missing: List<String>, val fpBreakdown: Map<String, Int>, val fpSamples: List<String>,
    )

    // Pokazuje co się STAŁO z brakującą wartością w wyniku — czy zostaje jawna, czy jest
    // częściowo/błędnie zamaskowana. Kotwica = 15 znaków PRZED wartością w tekście źródłowym
    // (powinny przetrwać bez zmian, bo tylko sama wartość jest zastępowana tokenem).
    private fun contextSnippet(rawText: String, pseudoText: String, value: String): String {
        val idx = rawText.indexOf(value)
        if (idx < 0) return "(wartość GT nie znaleziona w tekście źródłowym — literówka w generatorze?)"
        val anchor = rawText.substring(maxOf(0, idx - 20), idx).takeLast(15)
        if (anchor.isBlank()) return "(brak kotwicy kontekstowej — wartość na początku dokumentu)"
        val pIdx = pseudoText.indexOf(anchor)
        if (pIdx < 0) return "(kotwica '${anchor.replace("\n", "⏎")}' zniknęła z wyniku — coś wcześniej ją skonsumowało)"
        val end = minOf(pseudoText.length, pIdx + anchor.length + 40)
        return pseudoText.substring(pIdx, end).replace("\n", "⏎")
    }

    private fun findDatasetDir(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, "dataset_clean")
            if (File(candidate, "ground_truth.json").exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error(
            "Nie znaleziono dataset_clean/ground_truth.json (szukano od " +
                "${System.getProperty("user.dir")} w górę) — uruchom najpierw: " +
                "python generator_clean.py --count 300 --scope business --seed 42"
        )
    }

    @Test
    fun `benchmark czysty na dataset_clean bez OCR`() {
        val datasetDir = findDatasetDir()
        val repoRoot = datasetDir.parentFile ?: error("Brak katalogu nadrzędnego dla ${datasetDir.path}")
        val gt = JSONArray(File(datasetDir, "ground_truth.json").readText(Charsets.UTF_8))

        val results = mutableListOf<DocResult>()

        for (i in 0 until gt.length()) {
            val entry = gt.getJSONObject(i)
            val relFile = entry.getString("file")
            val docType = entry.getString("doc_type")
            val gtEntities = entry.getJSONObject("entities")

            val docFile = File(datasetDir, relFile)
            if (!docFile.exists()) error("Brakujący plik dokumentu: ${docFile.path}")
            val rawText = docFile.readText(Charsets.UTF_8)

            val engineResult = PseudonymEngine.pseudonymize(rawText, emptyList(), traceMode = true)
            val tokens = engineResult.tokenMap.map { (token, original) ->
                DetectedToken(original = original, token = token, type = token.substringBefore("_"))
            }

            var detected = 0
            var criticalMissed = 0
            val missing = mutableListOf<String>()

            gtEntities.keys().forEach { key ->
                val value = gtEntities.getString(key)
                val valN = norm(value)
                val isCrit = key in CRITICAL_KEYS
                val found = tokens.any { tok ->
                    val on = norm(tok.original)
                    on == valN || (valN.length >= 6 && (on.contains(valN) || valN.contains(on))) || fuzzyMatch(valN, on)
                }
                if (found) detected++ else {
                    val snippet = contextSnippet(rawText, engineResult.pseudonymizedText, value)
                    missing += "$key='$value' → $snippet"
                    if (isCrit) criticalMissed++
                }
            }

            val gtNorms = gtEntities.keys().asSequence().map { norm(gtEntities.getString(it)) }.toList()
            val fpBreakdown = mutableMapOf<String, Int>()
            val fpSamples = mutableListOf<String>()
            var fp = 0
            tokens.forEach { tok ->
                val on = norm(tok.original)
                val isFp = on.length >= 2 && gtNorms.none { gn ->
                    on == gn || (on.length >= 6 && (on.contains(gn) || gn.contains(on)))
                }
                if (isFp) {
                    fp++
                    val bucket = classifyExtraToken(tok, gtEntities)
                    fpBreakdown[bucket] = (fpBreakdown[bucket] ?: 0) + 1
                    fpSamples += "[$bucket] ${tok.type} '${tok.original}'"
                }
            }

            results += DocResult(
                file = relFile, docType = docType,
                total = gtEntities.length(), detected = detected, criticalMissed = criticalMissed,
                fp = fp, missing = missing, fpBreakdown = fpBreakdown, fpSamples = fpSamples,
            )
        }

        // ── Agregacja (mikro-średnia, tak jak w BenchmarkInstrumentedTest.kt) ──
        val totalEnt = results.sumOf { it.total }
        val totalDet = results.sumOf { it.detected }
        val totalFp = results.sumOf { it.fp }
        val totalCritMissed = results.sumOf { it.criticalMissed }
        val recall = if (totalEnt > 0) totalDet.toDouble() / totalEnt else 0.0
        val precision = if (totalDet + totalFp > 0) totalDet.toDouble() / (totalDet + totalFp) else 0.0
        val f1 = if (recall + precision > 0) 2 * recall * precision / (recall + precision) else 0.0

        val byType = results.groupBy { it.docType }.mapValues { (_, docs) ->
            val ent = docs.sumOf { it.total }
            val det = docs.sumOf { it.detected }
            val fp = docs.sumOf { it.fp }
            val r = if (ent > 0) det.toDouble() / ent else 0.0
            val p = if (det + fp > 0) det.toDouble() / (det + fp) else 0.0
            Triple(r, p, docs.size)
        }

        val fpByBucket = results.flatMap { it.fpBreakdown.entries }
            .groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }

        // Wszystkie próbki REVIEW (bucket wymagający realnej oceny — nie NUMER/KWOTA-overmask,
        // nie już rozpoznany UX_FP-denylist) z przypisaniem do dokumentu, żeby ocenić czy to
        // słowniki imion/nazwisk (powtarzające się te same wartości) czy encje spoza GT.
        data class FpEntry(val file: String, val docType: String, val sample: String)
        val reviewEntries = results.flatMap { d -> d.fpSamples.filter { it.startsWith("[REVIEW]") }.map { FpEntry(d.file, d.docType, it) } }
        val reviewByValueCount = reviewEntries
            .groupBy { it.sample.substringAfter("'").substringBeforeLast("'") }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date())
        val reportDir = File(repoRoot, "benchmark_results/clean/$timestamp")
        reportDir.mkdirs()

        val report = buildString {
            appendLine("BENCHMARK CZYSTY (bez OCR) — $timestamp")
            appendLine("Dataset: ${datasetDir.path} (${results.size} dokumentów)")
            appendLine("=".repeat(60))
            appendLine()
            appendLine("Recall:    ${"%.1f".format(recall * 100)}%  ($totalDet/$totalEnt)")
            appendLine("Precision: ${"%.1f".format(precision * 100)}%")
            appendLine("F1:        ${"%.3f".format(f1)}")
            appendLine("Krytyczne niewykryte (PESEL/NIP/IBAN/dowód/paszport): $totalCritMissed")
            appendLine("FP razem: $totalFp  ${fpByBucket.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            appendLine()
            appendLine("── Wg typu dokumentu ──")
            byType.entries.sortedByDescending { it.value.third }.forEach { (type, v) ->
                val (r, p, n) = v
                appendLine("  %-28s n=%-4d recall=%.1f%%  precision=%.1f%%".format(type, n, r * 100, p * 100))
            }
            val misses = results.filter { it.missing.isNotEmpty() }
            if (misses.isNotEmpty()) {
                appendLine()
                appendLine("── Braki (recall) ──")
                misses.forEach { d ->
                    appendLine("  ${d.file} [${d.docType}]: ${d.missing.joinToString("; ")}")
                }
            }
            if (reviewEntries.isNotEmpty()) {
                appendLine()
                appendLine("── FP bucket REVIEW — wymaga oceny czy to bug czy encja spoza GT ──")
                appendLine("(${reviewEntries.size} wystąpień, ${reviewByValueCount.size} unikalnych wartości;")
                appendLine(" wartość powtarzająca się w wielu dokumentach = podejrzenie słownika, nie GT-gap)")
                appendLine()
                reviewByValueCount.take(40).forEach { (value, count) ->
                    val example = reviewEntries.first { it.sample.substringAfter("'").substringBeforeLast("'") == value }
                    appendLine("  x$count  $value  (np. ${example.file} [${example.docType}])")
                }
                if (reviewByValueCount.size > 40) appendLine("  ... i ${reviewByValueCount.size - 40} innych wartości (obcięte, patrz kod jeśli potrzeba pełnej listy)")
            }
        }

        File(reportDir, "report.txt").writeText(report, Charsets.UTF_8)
        println(report)
        println("Raport zapisany: ${File(reportDir, "report.txt").path}")

        if (results.isEmpty()) error("Dataset pusty — brak dokumentów do analizy")
        if (totalEnt == 0) error("Ground truth nie ma żadnych encji — sprawdź dataset_clean/ground_truth.json")
    }
}
