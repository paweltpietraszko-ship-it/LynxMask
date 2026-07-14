package com.lynxmask.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Benchmark STRESS — duże pule imion/nazwisk (generator_stress.py).
 *
 * Różnica względem CleanBenchmarkTest: osoby losowane z ~3609 imion i ~39k nazwisk,
 * ze stratifikacją tierów w ground_truth.stress.persons (extended_only / top1000 /
 * collision / full). Raportuje recall OSOBA **osobno per tier** — łatka pod Kowalskiego
 * nie podbije metryki extended_only.
 *
 * Dataset:
 *   python generator_stress.py --count 300 --output dataset_stress --seed 42
 *
 * Uruchomienie:
 *   gradlew :app:testDebugUnitTest --tests "*.StressBenchmarkTest"
 *
 * Wynik: benchmark_results/stress/<data_godzina>/ + kopia w .../stress/latest/
 *
 * ARENA Cursor vs Claude Code:
 *   Test FAILUJE gdy kod nie spelnia progow → punkt dla Cursora (testy).
 *   Test PRZECHODZI → punkt dla CC (kod).
 */
class StressBenchmarkTest {

    @Before
    fun setup() {
        LookupTables.resetForTesting()
        LookupTables.initializeFromClasspath()
        if (!LookupTables.initialized) error("LookupTables nie załadowane — sprawdź src/test/resources/*.json")
    }

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
        "skarzacy" to "OSOBA", "oskarzony" to "OSOBA", "data" to "NUMER",
        "mentor" to "OSOBA", "nadawca" to "OSOBA", "odbiorca" to "OSOBA",
        "urzednik" to "OSOBA", "narrator" to "OSOBA", "przyjaciel" to "OSOBA",
    )

    private val OSOBA_ENTITY_KEYS = ENTITY_TYPE_MAP.filterValues { it == "OSOBA" }.keys

    private val CRITICAL_KEYS = setOf(
        "pesel", "nip", "nip_sprzedawcy", "nip_nabywcy", "iban",
        "dowod_osobisty", "numer_paszportu",
    )

    /** Progi areny — Cursor ustawia, CC musi przejsc. Kalibracja: 12.07.2026 na HEAD. */
    private val THRESHOLD_CRITICAL_MISSED = 0
    private val THRESHOLD_RECALL_OVERALL = 0.97
    private val THRESHOLD_RECALL_NUMER = 0.97
    private val THRESHOLD_RECALL_ADRES = 0.93
    private val THRESHOLD_RECALL_OSOBA = 0.93
    private val THRESHOLD_RECALL_EMAIL = 0.90
    private val THRESHOLD_RECALL_OSOBA_EXTENDED = 0.88

    private val ENTITY_TYPES = listOf("NUMER", "OSOBA", "ADRES", "EMAIL")

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
        if (kotlin.math.abs(a.length - b.length) > maxDist) return false
        return levenshtein(a, b) <= maxDist
    }

    private fun entityDetected(value: String, tokens: List<DetectedToken>): Boolean {
        val valN = norm(value)
        return tokens.any { tok ->
            val on = norm(tok.original)
            on == valN || (valN.length >= 6 && (on.contains(valN) || valN.contains(on))) || fuzzyMatch(valN, on)
        }
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
        val osobaByTier: Map<String, Pair<Int, Int>>,
        val osobaMissesByTier: Map<String, List<String>>,
        val typeDet: Map<String, Int>,
        val typeTot: Map<String, Int>,
        val typeMisses: Map<String, List<String>>,
    )

    private fun findDatasetDir(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(dir, "dataset_stress")
            if (File(candidate, "ground_truth.json").exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        error(
            "Nie znaleziono dataset_stress/ground_truth.json — uruchom najpierw:\n" +
                "  python generator_stress.py --count 300 --output dataset_stress --seed 42"
        )
    }

    @Test
    fun `benchmark stress na dataset_stress z duzymi pulami`() {
        val datasetDir = findDatasetDir()
        val repoRoot = datasetDir.parentFile ?: error("Brak katalogu nadrzędnego")
        val gt = JSONArray(File(datasetDir, "ground_truth.json").readText(Charsets.UTF_8))

        val results = mutableListOf<DocResult>()

        for (i in 0 until gt.length()) {
            val entry = gt.getJSONObject(i)
            val relFile = entry.getString("file")
            val docType = entry.getString("doc_type")
            val gtEntities = entry.getJSONObject("entities")
            val stress = entry.optJSONObject("stress")

            val rawText = File(datasetDir, relFile).readText(Charsets.UTF_8)
            val engineResult = PseudonymEngine.pseudonymize(rawText, emptyList(), traceMode = true)
            val tokens = engineResult.tokenMap.map { (token, original) ->
                DetectedToken(original = original, token = token, type = token.substringBefore("_"))
            }

            var detected = 0
            var criticalMissed = 0
            val missing = mutableListOf<String>()
            val typeDet = mutableMapOf<String, Int>()
            val typeTot = mutableMapOf<String, Int>()
            val typeMisses = mutableMapOf<String, MutableList<String>>()

            gtEntities.keys().forEach { key ->
                val value = gtEntities.getString(key)
                val entityType = ENTITY_TYPE_MAP[key] ?: "UNKNOWN"
                typeTot[entityType] = (typeTot[entityType] ?: 0) + 1
                val found = entityDetected(value, tokens)
                if (found) {
                    detected++
                    typeDet[entityType] = (typeDet[entityType] ?: 0) + 1
                } else {
                    missing += "$key='$value'"
                    if (key in CRITICAL_KEYS) criticalMissed++
                    typeMisses.getOrPut(entityType) { mutableListOf() }.add("$key='$value'")
                }
            }

            // OSOBA per tier (tylko z sekcji stress.persons)
            val tierDet = mutableMapOf<String, Int>()
            val tierTot = mutableMapOf<String, Int>()
            val tierMisses = mutableMapOf<String, MutableList<String>>()

            stress?.optJSONArray("persons")?.let { persons ->
                for (j in 0 until persons.length()) {
                    val p = persons.getJSONObject(j)
                    val entityKey = p.getString("entity_key")
                    val value = p.getString("value")
                    val tier = p.optString("surname_tier", "unknown")
                    if (entityKey !in OSOBA_ENTITY_KEYS) continue
                    tierTot[tier] = (tierTot[tier] ?: 0) + 1
                    if (entityDetected(value, tokens)) {
                        tierDet[tier] = (tierDet[tier] ?: 0) + 1
                    } else {
                        tierMisses.getOrPut(tier) { mutableListOf() }
                            .add("${p.optString("surname_key")} via $entityKey='$value'")
                    }
                }
            }

            val osobaByTier = tierTot.mapValues { (tier, tot) ->
                (tierDet[tier] ?: 0) to tot
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
                osobaByTier = osobaByTier,
                osobaMissesByTier = tierMisses,
                typeDet = typeDet,
                typeTot = typeTot,
                typeMisses = typeMisses,
            )
        }

        val totalEnt = results.sumOf { it.total }
        val totalDet = results.sumOf { it.detected }
        val totalFp = results.sumOf { it.fp }
        val totalCrit = results.sumOf { it.criticalMissed }
        val recall = if (totalEnt > 0) totalDet.toDouble() / totalEnt else 0.0
        val precision = if (totalDet + totalFp > 0) totalDet.toDouble() / (totalDet + totalFp) else 0.0
        val f1 = if (recall + precision > 0) 2 * recall * precision / (recall + precision) else 0.0

        // Agregacja tierów OSOBA
        val tierAgg = mutableMapOf<String, Pair<Int, Int>>()
        results.forEach { d ->
            d.osobaByTier.forEach { (tier, pair) ->
                val (det, tot) = pair
                val cur = tierAgg[tier] ?: (0 to 0)
                tierAgg[tier] = (cur.first + det) to (cur.second + tot)
            }
        }

        val fpByBucket = results.flatMap { it.fpBreakdown.entries }
            .groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }

        // Recall per typ encji (NUMER / OSOBA / ADRES / EMAIL)
        val typeAgg = mutableMapOf<String, Pair<Int, Int>>()
        val typeMissAgg = mutableMapOf<String, MutableList<String>>()
        results.forEach { d ->
            d.typeTot.forEach { (t, tot) ->
                val cur = typeAgg[t] ?: (0 to 0)
                typeAgg[t] = (cur.first + (d.typeDet[t] ?: 0)) to (cur.second + tot)
            }
            d.typeMisses.forEach { (t, misses) ->
                typeMissAgg.getOrPut(t) { mutableListOf() }.addAll(misses.take(3))
            }
        }

        fun recallOf(det: Int, tot: Int) = if (tot > 0) det.toDouble() / tot else 1.0

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).format(Date())
        val reportDir = File(repoRoot, "benchmark_results/stress/$timestamp")
        reportDir.mkdirs()

        val report = buildString {
            appendLine("BENCHMARK STRESS (Arena Cursor vs Claude Code) — $timestamp")
            appendLine("Dataset: ${datasetDir.path} (${results.size} dokumentów)")
            appendLine("Pule: imiona ~3609, nazwiska ~39k, ulice ~11k, miasta ~31k")
            appendLine("=".repeat(60))
            appendLine()
            appendLine("Recall (wszystkie encje): ${"%.1f".format(recall * 100)}%  ($totalDet/$totalEnt)")
            appendLine("Precision: ${"%.1f".format(precision * 100)}%")
            appendLine("F1: ${"%.3f".format(f1)}")
            appendLine("Krytyczne niewykryte: $totalCrit")
            appendLine("FP razem: $totalFp  ${fpByBucket.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            appendLine()
            appendLine("── Recall wg TYPU encji (anty-cheat: nie tylko OSOBA) ──")
            ENTITY_TYPES.forEach { t ->
                val (det, tot) = typeAgg[t] ?: (0 to 0)
                val r = recallOf(det, tot) * 100
                appendLine("  %-8s %4d/%4d  recall=%.1f%%".format(t, det, tot, r))
            }
            appendLine()
            appendLine("── Recall OSOBA wg tieru nazwiska ──")
            appendLine("  (extended_only = tylko 39k, poza top-1000)")
            tierAgg.entries.sortedByDescending { it.value.second }.forEach { (tier, pair) ->
                val (det, tot) = pair
                val r = if (tot > 0) det.toDouble() / tot * 100 else 0.0
                appendLine("  %-16s %3d/%3d  recall=%.1f%%".format(tier, det, tot, r))
            }
            appendLine()
            appendLine("── Przykłady MISS OSOBA per tier (max 5/tier) ──")
            val allMisses = results.flatMap { d ->
                d.osobaMissesByTier.entries.flatMap { (tier, list) ->
                    list.take(5).map { tier to it }
                }
            }
            allMisses.groupBy({ it.first }, { it.second }).forEach { (tier, samples) ->
                appendLine("  [$tier]")
                samples.take(5).forEach { appendLine("    $it") }
            }
            appendLine()
            appendLine("── MISS wg typu (max 5/tier) ──")
            typeMissAgg.entries.sortedByDescending { it.value.size }.forEach { (t, samples) ->
                appendLine("  [$t]")
                samples.take(5).forEach { appendLine("    $it") }
            }
            appendLine()
            val misses = results.filter { it.missing.isNotEmpty() }.take(15)
            if (misses.isNotEmpty()) {
                appendLine("── Braki (wszystkie typy, pierwsze 15 dok.) ──")
                misses.forEach { d ->
                    appendLine("  ${d.file} [${d.docType}]:")
                    d.missing.take(3).forEach { appendLine("    $it") }
                }
            }
            appendLine()
            appendLine("── FP REVIEW (top 10 wartości) ──")
            results.flatMap { d -> d.fpSamples.filter { it.startsWith("[REVIEW]") } }
                .groupingBy { it.substringAfter("'").substringBeforeLast("'") }
                .eachCount()
                .entries.sortedByDescending { it.value }
                .take(10)
                .forEach { (v, c) -> appendLine("  x$c  $v") }
        }

        File(reportDir, "report.txt").writeText(report, Charsets.UTF_8)
        println(report)
        println("Raport: ${File(reportDir, "report.txt").path}")

        // ── ARENA: progi → FAIL = punkt Cursor, PASS = punkt CC ──
        val failures = mutableListOf<String>()
        if (totalCrit > THRESHOLD_CRITICAL_MISSED) {
            failures += "krytyczne niewykryte=$totalCrit (max $THRESHOLD_CRITICAL_MISSED)"
        }
        if (recall < THRESHOLD_RECALL_OVERALL) {
            failures += "recall ogolny ${"%.1f".format(recall * 100)}% < ${THRESHOLD_RECALL_OVERALL * 100}%"
        }
        ENTITY_TYPES.forEach { t ->
            val (det, tot) = typeAgg[t] ?: (0 to 0)
            val r = recallOf(det, tot)
            val threshold = when (t) {
                "NUMER" -> THRESHOLD_RECALL_NUMER
                "ADRES" -> THRESHOLD_RECALL_ADRES
                "OSOBA" -> THRESHOLD_RECALL_OSOBA
                "EMAIL" -> THRESHOLD_RECALL_EMAIL
                else -> 1.0
            }
            if (tot > 0 && r < threshold) {
                failures += "recall $t ${"%.1f".format(r * 100)}% < ${"%.0f".format(threshold * 100)}%"
            }
        }
        val (extDet, extTot) = tierAgg["extended_only"] ?: (0 to 0)
        val extRecall = recallOf(extDet, extTot)
        if (extTot > 0 && extRecall < THRESHOLD_RECALL_OSOBA_EXTENDED) {
            failures += "recall OSOBA extended_only ${"%.1f".format(extRecall * 100)}% < ${THRESHOLD_RECALL_OSOBA_EXTENDED * 100}%"
        }

        val arenaWinner = if (failures.isEmpty()) "CC" else "CURSOR"
        val arenaScore = buildString {
            appendLine("=== ARENA Cursor vs Claude Code ===")
            appendLine("Data: $timestamp")
            appendLine("WYNIK: $arenaWinner ${if (failures.isEmpty()) "+1 (PASSED)" else "+1 (FAILED)"}")
            appendLine()
            appendLine("Metryki:")
            appendLine("  ogolny recall: ${"%.1f".format(recall * 100)}%")
            ENTITY_TYPES.forEach { t ->
                val (det, tot) = typeAgg[t] ?: (0 to 0)
                appendLine("  $t: ${"%.1f".format(recallOf(det, tot) * 100)}% ($det/$tot)")
            }
            appendLine("  OSOBA extended_only: ${"%.1f".format(extRecall * 100)}% ($extDet/$extTot)")
            appendLine("  krytyczne miss: $totalCrit")
            if (failures.isNotEmpty()) {
                appendLine()
                appendLine("Powody FAIL:")
                failures.forEach { appendLine("  - $it") }
            }
        }
        File(reportDir, "arena_score.txt").writeText(arenaScore, Charsets.UTF_8)
        persistLatestCopy(repoRoot, reportDir, timestamp, report, arenaScore)
        updateArenaScoreboard(repoRoot, timestamp, arenaWinner, failures)

        if (failures.isNotEmpty()) {
            Assert.fail("ARENA: CURSOR +1\n${failures.joinToString("\n") { "  - $it" }}")
        }
    }

    private fun persistLatestCopy(
        repoRoot: File,
        reportDir: File,
        timestamp: String,
        report: String,
        arenaScore: String,
    ) {
        val latestDir = File(repoRoot, "benchmark_results/stress/latest")
        latestDir.mkdirs()
        File(latestDir, "report.txt").writeText(report, Charsets.UTF_8)
        File(latestDir, "arena_score.txt").writeText(arenaScore, Charsets.UTF_8)
        File(latestDir, "last_run.txt").writeText(
            "timestamp=$timestamp\nfolder=${reportDir.path}\n",
            Charsets.UTF_8,
        )
        println("Kopia (stala sciezka): ${latestDir.path}")
    }

    private fun updateArenaScoreboard(repoRoot: File, timestamp: String, winner: String, failures: List<String>) {
        val boardFile = File(repoRoot, "benchmark_results/arena_scoreboard.txt")
        boardFile.parentFile?.mkdirs()
        var cc = 0
        var cursor = 0
        if (boardFile.exists()) {
            boardFile.readLines().forEach { line ->
                when {
                    line.startsWith("CC:") -> cc = line.substringAfter(":").trim().toIntOrNull() ?: cc
                    line.startsWith("Cursor:") -> cursor = line.substringAfter(":").trim().toIntOrNull() ?: cursor
                }
            }
        }
        if (winner == "CC") cc++ else cursor++
        val detail = if (failures.isEmpty()) "PASSED" else failures.first()
        boardFile.writeText(
            """
            === SCOREBOARD Cursor vs Claude Code ===
            CC:     $cc
            Cursor: $cursor
            Ostatni: $timestamp → $winner +1 ($detail)
            
            Reguly: Cursor pisze testy/progi, CC pisze kod silnika.
            Odpal: run_stress_benchmark.bat
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
    }
}
