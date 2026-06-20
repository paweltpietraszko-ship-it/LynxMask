package com.lynxmask.app

// BenchmarkInstrumentedTest.kt — Benchmark end-to-end na urządzeniu
//
// Uruchamianie: Android Studio → plik → zielony trójkąt przy klasie
// Lokalizacja:  app/src/androidTest/java/com/lynxmask/app/BenchmarkInstrumentedTest.kt
//
// Wymaga na telefonie (wgraj przez adb push):
//   /storage/emulated/0/Documents/LynxMask/bench/images/    — folder z PNG z generatora
//   /storage/emulated/0/Documents/LynxMask/bench/ground_truth.json
//
// Raport zapisywany do:
//   /storage/emulated/0/Documents/LynxMask/bench/benchmark_report.txt
//   /storage/emulated/0/Documents/LynxMask/bench/benchmark_bugs.txt
//
// Pobierz raporty po teście:
//   adb pull /storage/emulated/0/Documents/LynxMask/bench/benchmark_report.txt
//   adb pull /storage/emulated/0/Documents/LynxMask/bench/benchmark_bugs.txt

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BenchmarkInstrumentedTest {

    companion object {
        // "ground_truth_lvl01.json"  — 34 dok., lvl 0+1, ~213 encji ← diagnoza silnika
        // "ground_truth_lvl0.json"   — 17 dok., tylko perfect scan  ← minimalna wersja
        // "ground_truth_lvl03.json"  — 68 dok., lvl 0-3             ← standardowy benchmark
        // "ground_truth.json"        — 100 dok., wszystkie poziomy   ← pełny dataset
        const val GROUND_TRUTH_FILE = "ground_truth_lvl03.json"
    }

    private val context by lazy { InstrumentationRegistry.getInstrumentation().targetContext }
    private val benchDir by lazy { File(context.getExternalFilesDir(null), "bench") }

    // ─────────────────────────────────────────────────────────────────────────
    // Główny test
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun runBenchmark() = runBlocking {
        benchDir.mkdirs()
        println("[BENCH] benchDir: ${benchDir.absolutePath}")

        // Inicjalizacja silnika — musi być przed pierwszym pseudonymize()
        // Bez tego w trybie DEBUG silnik rzuca IllegalStateException i ubija apkę
        LookupTables.initialize(context)
        UserDictionary.load(context)

        val gtFile = File(benchDir, GROUND_TRUTH_FILE)
        check(gtFile.exists()) {
            "Brak $GROUND_TRUTH_FILE — wgraj dataset:\n" +
            "adb push dataset/$GROUND_TRUTH_FILE /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/$GROUND_TRUTH_FILE"
        }

        val groundTruth = JSONArray(gtFile.readText())
        val imagesDir   = File(benchDir, "images")
        check(imagesDir.exists()) {
            "Brak folderu images — wgraj dataset:\n" +
            "adb push dataset/images /storage/emulated/0/Documents/LynxMask/bench/images"
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val results    = mutableListOf<DocResult>()

        UserDictionary.clear(context)
        println("[BENCH_DEBUG] UserDictionary wyczyszczony przed benchmarkiem")

        val dictSize = UserDictionary.entries.size
        println("[BENCH_DEBUG] UserDictionary: $dictSize encji")
        UserDictionary.entries.take(10).forEach { (phrase, type) ->
            println("[BENCH_DEBUG]   '$phrase' → $type")
        }

        println("\n[BENCHMARK] Dokumentów: ${groundTruth.length()}")
        println("─".repeat(72))

        for (i in 0 until groundTruth.length()) {
            val gt       = groundTruth.getJSONObject(i)
            val fileName = File(gt.getString("file")).name
            val imgFile  = File(imagesDir, fileName)

            if (!imgFile.exists()) {
                println("  [${i+1}] BRAK: $fileName")
                continue
            }

            val result = processImage(imgFile, gt, recognizer)
            results.add(result)

            val s    = result.summary
            val rec  = if (s.recall != null) "${(s.recall * 100).toInt()}%" else "N/A"
            val prec = if (s.precision != null) "${(s.precision * 100).toInt()}%" else "N/A"
            val crit = if (s.criticalMissed > 0) " ⚠CRIT:${s.criticalMissed}" else ""
            val err  = if (result.error != null) " ERR:${result.error}" else ""

            println("  [${(i+1).toString().padStart(3)}] ${fileName.padEnd(20)} " +
                    "${result.docType.padEnd(22)} lvl=${result.degLevel} " +
                    "R=$rec P=$prec$crit$err")
        }

        saveReports(results)
        printSummary(results)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Przetwarzanie jednego obrazu
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun processImage(
        imgFile: File,
        gt: JSONObject,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
    ): DocResult {
        return try {
            // ML Kit OCR
            val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                ?: return analyze(gt, emptyList(), ocrText = "", error = "bitmap null: ${imgFile.name}")
            val mlImage  = InputImage.fromBitmap(bitmap, 0)
            val ocrText: String = suspendCancellableCoroutine { cont ->
                recognizer.process(mlImage)
                    .addOnSuccessListener { r -> cont.resume(r.text) {} }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }


            // OcrNormalizer + PseudonymEngine z UserDictionary
            // .toList() jest bezpieczniejsze niż ArrayList() — działa niezależnie
            // od konkretnego typu kolekcji zwracanej przez UserDictionary.entries
            val normalized  = OcrNormalizer.normalize(ocrText)
            val dictEntries = UserDictionary.entries.toList()
            val engineResult = PseudonymEngine.pseudonymize(normalized.normalizedText, dictEntries, traceMode = true)

            // Tokeny: tokenMap to Map<token, original>
            val tokens = engineResult.tokenMap.map { (token, original) ->
                DetectedToken(
                    original = original,
                    token    = token,
                    type     = token.substringBefore("_"),
                )
            }

            // Token dump dla pierwszego dokumentu (plik nie istnieje = pierwszy dok.)
            val dumpFile = File(benchDir, "token_dump_doc0.txt")
            if (!dumpFile.exists()) {
                val tokenDump = engineResult.tokenMap.entries
                    .joinToString("\n") { (k, v) -> "$k = $v" }
                dumpFile.writeText(tokenDump)
            }

            analyze(gt, tokens, ocrText, normalizedText = normalized.normalizedText, error = null, trace = engineResult.trace)
        } catch (e: Exception) {
            analyze(gt, emptyList(), ocrText = "", error = e.message ?: "błąd")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Analiza — porównanie z ground truth
    // ─────────────────────────────────────────────────────────────────────────

    private val criticalKeys = setOf(
        "pesel", "nip", "nip_sprzedawcy", "nip_nabywcy",
        "iban", "dowod_osobisty", "numer_paszportu",
    )

    // Oczekiwany typ tokenu dla każdego klucza ground truth.
    // Używany WYŁĄCZNIE do metryki diagnostycznej type_mismatch_count —
    // NIE wpływa na found (encja zamaskowana dowolnym tokenem = sukces).
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
    )

    private fun norm(v: String): String {
        val diacritics = mapOf(
            'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
            'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
            'Ą' to 'a', 'Ć' to 'c', 'Ę' to 'e', 'Ł' to 'l', 'Ń' to 'n',
            'Ó' to 'o', 'Ś' to 's', 'Ź' to 'z', 'Ż' to 'z'
        )
        // OCR_EMAIL_LOCALSPACE zamienia spację w local-part → '_', a GT ma '.':
        // "malgorzata._kowalska" vs "malgorzata.kowalska" — normalizujemy "._" → "."
        return v.replace(" ", "").replace("-", "").replace("._", ".")
            .map { diacritics[it] ?: it }
            .joinToString("")
            .lowercase()
    }

    // Fuzzy match tylko dla kluczy numerycznych — dla nazwisk byłoby niebezpieczne
    private val numericKeys = setOf(
        "pesel", "nip", "nip_sprzedawcy", "nip_nabywcy", "regon_sprzedawcy",
        "iban", "dowod_osobisty", "numer_paszportu", "telefon",
        "numer_faktury", "numer_klienta", "numer_umowy", "numer_kw",
        "sygnatura_akt", "sygnatura_komornicza", "sygnatura_administracyjna",
        "data_urodzenia",
    )

    private fun fuzzyMatch(a: String, b: String): Boolean {
        if (a.length < 9 || b.length < 9) return false
        if (Math.abs(a.length - b.length) > 1) return false
        val longer  = if (a.length >= b.length) a else b
        val shorter = if (a.length < b.length) a else b
        if (longer.length == shorter.length)
            return longer.zip(shorter).count { (x, y) -> x != y } <= 1
        for (i in longer.indices)
            if (longer.removeRange(i, i + 1) == shorter) return true
        return false
    }

    private fun analyze(
        gt: JSONObject,
        tokens: List<DetectedToken>,
        ocrText: String,
        normalizedText: String = ocrText,
        error: String?,
        trace: List<DetectionTrace> = emptyList(),
    ): DocResult {
        val gtEntities = gt.optJSONObject("entities") ?: JSONObject()
        // names() zwraca null dla pustego JSONObject — null!! = NPE, dlatego null-safe
        val gtNames = gtEntities.names()
        val gtNorms = if (gtNames != null)
            (0 until gtNames.length()).map { norm(gtEntities.get(gtNames.getString(it)).toString()) }
        else
            emptyList()

        val entities = mutableListOf<EntityResult>()
        var detected = 0
        var criticalMissed = 0
        var typeMismatch = 0  // ile razy encja znaleziona, ale innym typem tokenu niż oczekiwany

        gtEntities.keys().forEach { key ->
            val value  = gtEntities.getString(key)
            val valN   = norm(value)
            val isCrit = key in criticalKeys

            // Sukces = encja zamaskowana DOWOLNYM tokenem (type-agnostic).
            // Porażka = encja w ogóle niezamaskowana.
            // Zgodnie z NOTA_FILOZOFIA_MASKOWANIA: typ tokenu jest wtórny.
            var matchedTokenType: String? = null
            val found = error == null && tokens.any { tok ->
                val on = norm(tok.original)
                val matched = valN == on ||
                    (valN.length >= 6 && (valN.contains(on) || on.contains(valN))) ||
                    (key in numericKeys && fuzzyMatch(valN, on))
                if (matched && matchedTokenType == null) matchedTokenType = tok.type
                matched
            }

            if (found) {
                detected++
                // Diagnostyka: czy typ tokenu zgadza się z oczekiwanym?
                val expectedType = ENTITY_TYPE_MAP[key]
                if (expectedType != null && matchedTokenType != null &&
                    expectedType != matchedTokenType) {
                    typeMismatch++
                }
            } else if (isCrit) criticalMissed++
            entities.add(EntityResult(key, value, found, isCrit))
        }

        val total     = entities.size
        var fpCount = 0
        val fpExamples = mutableListOf<String>()
        println("[FP_DEBUG_COUNT] Tokenów do sprawdzenia: ${tokens.size}")
        tokens.forEach { tok ->
            val on = norm(tok.original)
            val isFp = on.length >= 2 && gtNorms.none { gn ->
                on == gn || (on.length >= 6 && (on.contains(gn) || gn.contains(on)))
            }
            if (isFp) {
                if (fpCount < 10) {
                    fpExamples.add("FP: ${tok.token} = '${tok.original}' → norm='${norm(tok.original)}'")
                }
                fpCount++
            }
        }
        println("[FP_DEBUG] Pierwsze 10 FP w tym dokumencie:")
        fpExamples.forEach { println("  $it") }
        val fp = fpCount
        val recall    = if (total > 0) detected.toDouble() / total else null
        val precision = if (detected + fp > 0) detected.toDouble() / (detected + fp) else null
        val f1        = if (recall != null && precision != null && recall + precision > 0)
            2 * recall * precision / (recall + precision) else null

        return DocResult(
            file      = gt.getString("file"),
            docType   = gt.optString("doc_type", "?"),
            degLevel  = gt.optInt("degradation_level", -1),
            qualScore = gt.optInt("quality_score", -1),
            ocrLen    = ocrText.length,
            ocrText   = ocrText,
            error     = error,
            tokens    = tokens,
            entities  = entities,
            fpCount   = fp,
            summary   = Summary(total, detected, criticalMissed, fp, recall, precision, f1,
                                typeMismatch),
            trace     = trace,
            normalizedText = normalizedText,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Raporty
    // ─────────────────────────────────────────────────────────────────────────

    private fun printSummary(results: List<DocResult>) {
        val totalEnt  = results.sumOf { it.summary.total }
        val totalDet  = results.sumOf { it.summary.detected }
        val totalFp   = results.sumOf { it.summary.fp }
        val totalCrit = results.sumOf { it.summary.criticalMissed }
        val totalMismatch = results.sumOf { it.summary.typeMismatch }
        val errors    = results.count { it.error != null }
        val recall    = if (totalEnt > 0) totalDet.toDouble() / totalEnt else 0.0
        val precision = if (totalDet + totalFp > 0) totalDet.toDouble() / (totalDet + totalFp) else 0.0

        println("\n${"═".repeat(60)}")
        println("BENCHMARK ZAKOŃCZONY")
        println("  Recall:          ${"%.1f".format(recall * 100)}%")
        println("  Precision:       ${"%.1f".format(precision * 100)}%")
        println("  Krytyczne braki: $totalCrit")
        println("  False positives: $totalFp")
        println("  Type mismatch:   $totalMismatch  ← zamaskowane złym typem (diagnostyka)")
        println("  Błędy:           $errors")
        println("  Raporty na telefonie:")
        println("    /storage/emulated/0/Documents/LynxMask/bench/benchmark_report.txt")
        println("    /storage/emulated/0/Documents/LynxMask/bench/benchmark_bugs.txt")
        println("  Pobierz: adb pull /storage/emulated/0/Documents/LynxMask/bench/benchmark_report.txt")
        println("${"═".repeat(60)}\n")
    }

    private fun normalizeForCompare(s: String): String {
        val diacritics = mapOf(
            'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
            'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
            'Ą' to 'a', 'Ć' to 'c', 'Ę' to 'e', 'Ł' to 'l', 'Ń' to 'n',
            'Ó' to 'o', 'Ś' to 's', 'Ź' to 'z', 'Ż' to 'z'
        )
        return s.replace(" ", "").replace("-", "").replace("._", ".")
            .map { diacritics[it] ?: it }
            .joinToString("")
            .lowercase()
    }

    private fun saveReports(results: List<DocResult>) {
        val totalEnt  = results.sumOf { it.summary.total }
        val totalDet  = results.sumOf { it.summary.detected }
        val totalFp   = results.sumOf { it.summary.fp }
        val totalCrit = results.sumOf { it.summary.criticalMissed }
        val errors    = results.count { it.error != null }
        val recall    = if (totalEnt > 0) totalDet.toDouble() / totalEnt else 0.0
        val precision = if (totalDet + totalFp > 0) totalDet.toDouble() / (totalDet + totalFp) else 0.0
        val f1        = if (recall + precision > 0) 2 * recall * precision / (recall + precision) else 0.0

        // Per typ encji
        val perType = mutableMapOf<String, Triple<Int, Int, Int>>() // det, total, fp
        val typeMap = mapOf(
            "imie_nazwisko" to "OSOBA", "imie_nazwisko_nabywcy" to "OSOBA",
            "imie_nazwisko_zleceniodawca" to "OSOBA", "imie_nazwisko_zleceniobiorca" to "OSOBA",
            "autor" to "OSOBA", "osoba" to "OSOBA",
            "pesel" to "NUMER", "nip" to "NUMER", "nip_sprzedawcy" to "NUMER",
            "nip_nabywcy" to "NUMER", "regon_sprzedawcy" to "NUMER",
            "telefon" to "NUMER", "iban" to "NUMER", "dowod_osobisty" to "NUMER",
            "numer_faktury" to "NUMER", "numer_klienta" to "NUMER",
            "sygnatura_akt" to "NUMER", "sygnatura_komornicza" to "NUMER",
            "sygnatura_administracyjna" to "NUMER", "data_urodzenia" to "NUMER",
            "adres" to "ADRES", "adres_nabywcy" to "ADRES",
            "adres_zleceniobiorca" to "ADRES", "adres_zleceniodawca" to "ADRES",
            "nip_zleceniobiorca" to "NUMER", "nip_zleceniodawca" to "NUMER",
            "numer_dzialki" to "NUMER", "numer_kw" to "NUMER",
            "numer_umowy" to "NUMER", "pesel_zleceniodawca" to "NUMER",
            "numer_paszportu" to "NUMER",
            "email" to "EMAIL",
        )
        results.forEach { r ->
            r.entities.forEach { e ->
                val t = typeMap[e.key] ?: "?"
                val (d, tot, fp) = perType.getOrDefault(t, Triple(0, 0, 0))
                perType[t] = Triple(if (e.found) d + 1 else d, tot + 1, fp)
            }
            r.tokens.forEach { tok ->
                // fp already counted in summary
            }
        }

        // Per poziom degradacji
        val perLevel = mutableMapOf<Int, Triple<Int, Int, Int>>() // det, total, docs
        results.forEach { r ->
            val (d, tot, docs) = perLevel.getOrDefault(r.degLevel, Triple(0, 0, 0))
            perLevel[r.degLevel] = Triple(
                d + r.summary.detected,
                tot + r.summary.total,
                docs + 1,
            )
        }

        val lvlLabels = mapOf(
            0 to "perfect scan  ", 1 to "light noise   ", 2 to "noise+blur    ",
            3 to "phone (good)  ", 4 to "phone (casual)", 5 to "poor quality  ",
        )

        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        // ── summary.txt ──────────────────────────────────────────────────────
        val sb = StringBuilder()
        sb.appendLine("BENCHMARK LYNXMASK MOBILE — RAPORT")
        sb.appendLine("Data:  $ts")
        sb.appendLine("═".repeat(60))
        sb.appendLine()
        sb.appendLine("OGÓLNE")
        sb.appendLine("  Dokumentów:                    ${results.size}")
        sb.appendLine("  Encji w ground truth:          $totalEnt")
        sb.appendLine("  Wykrytych i zamaskowanych:     $totalDet")
        sb.appendLine("  Pominiętych:                   ${totalEnt - totalDet}")
        sb.appendLine("  False positives:               $totalFp")
        sb.appendLine("  RECALL:    ${"%.1f".format(recall * 100)}%")
        sb.appendLine("  PRECISION: ${"%.1f".format(precision * 100)}%")
        sb.appendLine("  F1:        ${"%.1f".format(f1 * 100)}%")
        sb.appendLine("  Krytyczne braki:               $totalCrit")
        sb.appendLine("  Błędy:                         $errors")
        sb.appendLine()
        sb.appendLine("RECALL PER TYP ENCJI")
        perType.toSortedMap().forEach { (t, triple) ->
            val (d, tot, _) = triple
            val rc  = if (tot > 0) d.toDouble() / tot else 0.0
            val bar = "█".repeat((rc * 16).toInt()) + "░".repeat(16 - (rc * 16).toInt())
            sb.appendLine("  ${t.padEnd(12)} ${"%.1f".format(rc * 100)}%  [$bar]  ($d/$tot)")
        }
        sb.appendLine()
        sb.appendLine("RECALL PER POZIOM DEGRADACJI")
        perLevel.toSortedMap().forEach { (lv, triple) ->
            val (d, tot, docs) = triple
            val rc  = if (tot > 0) d.toDouble() / tot else 0.0
            val bar = "█".repeat((rc * 20).toInt()) + "░".repeat(20 - (rc * 20).toInt())
            sb.appendLine("  Lvl $lv ${lvlLabels[lv] ?: ""} [$bar] ${"%.1f".format(rc * 100)}%  docs=$docs ent=$tot")
        }

        File(benchDir, "benchmark_report.txt").writeText(sb.toString())

        // ── bugs.txt ─────────────────────────────────────────────────────────
        val bb = StringBuilder()
        bb.appendLine("BUGS / ANOMALIE — $ts")
        bb.appendLine("═".repeat(60))
        bb.appendLine()

        val critCases = results.flatMap { r -> r.entities.filter { !it.found && it.critical }.map { r to it } }
        if (critCases.isNotEmpty()) {
            bb.appendLine("[KRYTYCZNE] Pominięte encje wysokiego ryzyka: ${critCases.size}")
            bb.appendLine()
            critCases.take(40).forEach { (r, e) ->
                bb.appendLine("  ${r.file}  lvl=${r.degLevel}  qs=${r.qualScore}  ${e.key}=${e.value.take(35)}")
            }
            bb.appendLine()
        }

        val lowRecall = perType.filter { (_, v) -> v.second >= 5 && v.first.toDouble() / v.second < 0.80 }
        if (lowRecall.isNotEmpty()) {
            bb.appendLine("[RECALL<80%] Typy z niskim recall (min. 5 próbek):")
            bb.appendLine()
            lowRecall.forEach { (t, v) ->
                val rc = v.first.toDouble() / v.second
                bb.appendLine("  ${t.padEnd(12)} ${"%.1f".format(rc * 100)}%  (${v.first}/${v.second})")
            }
            bb.appendLine()
        }

        if (errors > 0) {
            bb.appendLine("[ERRORS] Błędy OCR/silnika: $errors")
            bb.appendLine()
            results.filter { it.error != null }.take(10).forEach { r ->
                bb.appendLine("  ${r.file} → ${r.error}")
            }
            bb.appendLine()
        }

        if (critCases.isEmpty() && lowRecall.isEmpty() && errors == 0)
            bb.appendLine("Brak krytycznych anomalii.")

        // ── Diagnostyka pominiętych OSOBA ────────────────────────────────────
        val osobaCases = results.flatMap { r ->
            r.entities.filter { !it.found && (it.key.startsWith("imie_") || it.key == "autor" || it.key == "osoba") }
                .map { r to it }
        }
        if (osobaCases.isNotEmpty()) {
            bb.appendLine()
            bb.appendLine("[OSOBA POMINIĘTE] ${osobaCases.size} encji:")
            bb.appendLine()
            osobaCases.forEach { (r, e) ->
                val normVal = normalizeForCompare(e.value)
                val inOcr = normalizeForCompare(r.normalizedText).contains(normVal)
                // Czy silnik zamaskował coś co zawiera nazwisko (druga część GT)?
                val surname = e.value.substringAfterLast(" ").lowercase()
                val maskedBySurname = r.tokens.any { tok ->
                    tok.original.lowercase().contains(surname) && tok.original.length >= 4
                }
                val status = when {
                    !inOcr          -> "BRAK_W_OCR"
                    maskedBySurname -> "ODMIANA_ZAMASKOWANA"
                    else            -> "BUG_SILNIKA"
                }
                bb.appendLine("  ${r.file.substringAfterLast("/")}  ${e.key}=${e.value}  → $status")
                bb.appendLine("    OCR[300]: ${r.ocrText.take(300).replace("\n", " ")}")
            }
            bb.appendLine()
        }

        // ── Diagnostyka pominiętych EMAIL ────────────────────────────────────
        val emailCases = results.flatMap { r ->
            r.entities.filter { !it.found && it.key == "email" }
                .map { r to it }
        }
        if (emailCases.isNotEmpty()) {
            bb.appendLine()
            bb.appendLine("[EMAIL POMINIĘTE] ${emailCases.size} encji:")
            bb.appendLine()
            emailCases.forEach { (r, e) ->
                val normVal = normalizeForCompare(e.value)
                val inOcr = normalizeForCompare(r.normalizedText).contains(normVal)
                val status = if (!inOcr) "BRAK_W_OCR" else "BUG_SILNIKA"
                bb.appendLine("  ${r.file.substringAfterLast("/")}  ${e.key}=${e.value}  → $status")
                bb.appendLine("    OCR[300]: ${r.ocrText.take(300).replace("\n", " ")}")
            }
            bb.appendLine()
        }

        // ── Diagnostyka pominiętych ADRES ────────────────────────────────────
        val adresCases = results.flatMap { r ->
            r.entities.filter { !it.found && (it.key == "adres" || it.key.startsWith("adres")) }
                .map { r to it }
        }
        if (adresCases.isNotEmpty()) {
            bb.appendLine()
            bb.appendLine("[ADRES POMINIĘTE] ${adresCases.size} encji:")
            bb.appendLine()
            adresCases.forEach { (r, e) ->
                val normVal = normalizeForCompare(e.value)
                val inOcr = normalizeForCompare(r.normalizedText).contains(normVal)
                val status = if (!inOcr) "BRAK_W_OCR" else "BUG_SILNIKA"
                bb.appendLine("  ${r.file.substringAfterLast("/")}  ${e.key}=${e.value}  → $status")
                bb.appendLine("    OCR[300]: ${r.ocrText.take(300).replace("\n", " ")}")
            }
            bb.appendLine()
        }

        // ── Diagnostyka OCR dla krytycznych braków ───────────────────────────
        if (critCases.isNotEmpty()) {
            bb.appendLine()
            bb.appendLine("[DIAGNOSTYKA OCR] Tekst OCR dla wszystkich dokumentów z brakami krytycznymi:")
            bb.appendLine("Pozwala odróżnić: OCR zgubił encję vs silnik jej nie wykrył.")
            bb.appendLine()
            critCases.map { (r, _) -> r }.distinctBy { it.file }.forEach { r ->
                bb.appendLine("  ── ${r.file}  lvl=${r.degLevel}  qs=${r.qualScore} ──")
                bb.appendLine("  Brakujące encje:")
                r.entities.filter { !it.found && it.critical }.forEach { e ->
                    bb.appendLine("    ${e.key} = ${e.value}")
                    val normVal = normalizeForCompare(e.value)
                    val inOcr = normalizeForCompare(r.normalizedText).contains(normVal)
                    bb.appendLine("    → ${if (inOcr) "✓ JEST w tekście OCR (bug silnika)" else "✗ BRAK w tekście OCR (bug OCR lub zbyt zdegradowany obraz)"}")
                    if (inOcr) {
                        // Pokaż fragment RAW OCR wokół znalezionej encji
                        val idx = r.ocrText.indexOf(e.value.take(3), ignoreCase = true)
                        if (idx >= 0) {
                            val from = maxOf(0, idx - 10)
                            val to   = minOf(r.ocrText.length, idx + e.value.length + 15)
                            bb.appendLine("    OCR fragment: «${r.ocrText.substring(from, to).replace("\n", "↵")}»")
                        }
                    }
                }
                bb.appendLine()
            }
        }

        File(benchDir, "benchmark_bugs.txt").writeText(bb.toString())

        // Drukuj bugs.txt do logcatu — działa nawet gdy adb pull jest zablokowany (Android 16)
        println("\n[BUGS_START]")
        bb.toString().lines().forEach { println(it) }
        println("[BUGS_END]")

        generateDictionaryHtml(results)

        // Zapis trace do pliku
        val traceFile = File(benchDir, "benchmark_trace.txt")
        val traceLines = results.flatMap { result ->
            result.trace.map { t ->
                "${result.file}\t${t.layer}\t${t.rule}\t${t.token}\t${t.matchedText}"
            }
        }
        traceFile.writeText("DOC\tLAYER\tRULE\tTOKEN\tMATCHED_TEXT\n" + traceLines.joinToString("\n"))

        val publicDir = File("/storage/emulated/0/Documents/LynxMask")
        publicDir.mkdirs()
        benchDir.listFiles()?.forEach { file ->
            val dest = File(publicDir, file.name)
            if (dest.exists()) dest.delete()
            file.copyTo(dest, overwrite = true)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Generator HTML do zatwierdzania encji dla UserDictionary
    // ─────────────────────────────────────────────────────────────────────────

    private fun generateDictionaryHtml(results: List<DocResult>) {
        // Zbierz wszystkie niewykryte encje które OCR widział (bug silnika, nie OCR)
        // To są kandydaci do dodania do UserDictionary
        data class Candidate(val value: String, val type: String, val key: String, val count: Int)

        val typeMap = mapOf(
            "imie_nazwisko" to "OSOBA", "imie_nazwisko_nabywcy" to "OSOBA",
            "autor" to "OSOBA", "osoba" to "OSOBA",
            "adres" to "ADRES", "adres_nabywcy" to "ADRES",
            "adres_zleceniobiorca" to "ADRES", "adres_zleceniodawca" to "ADRES",
            "email" to "EMAIL",
        )
        // Tylko typy które mają sens w słowniku — numery są wykrywane przez regex, nie słownik
        val dictTypes = setOf("OSOBA", "ADRES", "EMAIL")

        val counts = mutableMapOf<Pair<String, String>, Int>()
        results.forEach { r ->
            r.entities.filter { e ->
                !e.found &&
                typeMap.containsKey(e.key) &&
                typeMap[e.key] in dictTypes &&
                // Tylko jeśli OCR w ogóle coś widział (nie pusty wynik)
                r.ocrLen > 50
            }.forEach { e ->
                val type = typeMap[e.key] ?: return@forEach
                counts[e.value to type] = (counts[e.value to type] ?: 0) + 1
            }
        }

        if (counts.isEmpty()) {
            File(benchDir, "missed_entities.html").writeText(
                "<html><body><h2>Brak kandydatów do słownika.</h2></body></html>"
            )
            return
        }

        val rows = counts.entries
            .sortedByDescending { it.value }
            .joinToString("\n") { (pair, cnt) ->
                val (value, type) = pair
                val safeValue = value.replace("\"", "&quot;").replace("<", "&lt;")
                """<tr>
                  <td><input type="checkbox" class="cb" data-value="$safeValue" data-type="$type" checked></td>
                  <td class="val">$safeValue</td>
                  <td class="type">$type</td>
                  <td class="cnt">$cnt×</td>
                </tr>"""
            }

        val html = """<!DOCTYPE html>
<html lang="pl">
<head>
<meta charset="UTF-8">
<title>LynxMask — Kandydaci do słownika</title>
<style>
  body { font-family: Arial, sans-serif; max-width: 900px; margin: 40px auto; font-size: 18px; }
  h1 { color: #1a1a2e; }
  p.info { background: #e8f4fd; padding: 12px; border-radius: 6px; }
  table { width: 100%; border-collapse: collapse; margin: 20px 0; }
  th { background: #1a1a2e; color: white; padding: 10px; text-align: left; }
  td { padding: 10px; border-bottom: 1px solid #ddd; }
  tr:hover { background: #f5f5f5; }
  .type { color: #666; font-size: 14px; }
  .cnt  { color: #999; font-size: 14px; }
  .val  { font-weight: bold; }
  input[type=checkbox] { width: 20px; height: 20px; cursor: pointer; }
  button { background: #1a1a2e; color: white; border: none; padding: 14px 28px;
           font-size: 18px; border-radius: 6px; cursor: pointer; margin: 10px 4px; }
  button:hover { background: #2d2d5e; }
  button.sec { background: #555; }
  #status { color: green; font-weight: bold; margin: 10px 0; }
</style>
</head>
<body>
<h1>Kandydaci do słownika LynxMask</h1>
<p class="info">
  Encje wykryte w ground truth ale pominięte przez silnik. Zaznacz które chcesz dodać i kliknij <b>Eksportuj zaznaczone</b>.
  Następnie wgraj plik na telefon komendą z pliku <b>import_dictionary.bat</b>.
</p>
<div>
  <button onclick="selectAll()">Zaznacz wszystko</button>
  <button class="sec" onclick="selectNone()">Odznacz wszystko</button>
  <button onclick="exportSelected()">💾 Eksportuj zaznaczone</button>
</div>
<div id="status"></div>
<table>
  <thead><tr><th></th><th>Wartość</th><th>Typ</th><th>Pominięć</th></tr></thead>
  <tbody>$rows</tbody>
</table>
<script>
function selectAll()  { document.querySelectorAll('.cb').forEach(c => c.checked = true);  }
function selectNone() { document.querySelectorAll('.cb').forEach(c => c.checked = false); }

function exportSelected() {
  const items = [];
  document.querySelectorAll('.cb:checked').forEach(cb => {
    items.push({ value: cb.dataset.value, type: cb.dataset.type });
  });
  if (items.length === 0) { alert('Nic nie zaznaczone.'); return; }
  const blob = new Blob([JSON.stringify(items, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'user_dictionary_additions.json';
  a.click();
  document.getElementById('status').textContent =
    '✓ Pobrano ' + items.length + ' wpisów. Wgraj przez import_dictionary.bat';
}
</script>
</body>
</html>"""

        File(benchDir, "missed_entities.html").writeText(html)
        println("[HTML] Kandydaci do słownika: /storage/emulated/0/Documents/LynxMask/bench/missed_entities.html")
        println("       Pobierz: adb pull /storage/emulated/0/Documents/LynxMask/bench/missed_entities.html")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────────────────────

    data class DetectedToken(val original: String, val token: String, val type: String)

    data class EntityResult(val key: String, val value: String, val found: Boolean, val critical: Boolean)

    data class Summary(
        val total: Int, val detected: Int, val criticalMissed: Int, val fp: Int,
        val recall: Double?, val precision: Double?, val f1: Double?,
        val typeMismatch: Int = 0,  // ile encji zamaskowanych złym typem tokenu (diagnostyka)
    )

    data class DocResult(
        val file: String, val docType: String, val degLevel: Int, val qualScore: Int,
        val ocrLen: Int, val ocrText: String, val error: String?,
        val tokens: List<DetectedToken>, val entities: List<EntityResult>,
        val fpCount: Int, val summary: Summary,
        val trace: List<DetectionTrace> = emptyList(),
        val normalizedText: String = ocrText,
    )
}
