package com.lynxmask.app

// BenchmarkInstrumentedTest.kt — Benchmark v2 — sekcje A-D, bramka OCR, klasyfikacja miss
//
// Uruchamianie: Android Studio → plik → zielony trójkąt przy klasie
//
// Dataset na telefonie (adb push):
//   /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/images/
//   /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/ground_truth_lvl03.json
//
// Raporty:
//   adb pull /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/benchmark_report.txt
//   adb pull /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/benchmark_bugs.txt
//
// Sekcje raportu (BRIEF_Wlasciciel_Benchmark_v2.md §4):
//   A — ENGINE-ONLY: testy JVM (nie run tu)
//   B — IN-SCOPE ACCEPTED: lvl 0/1/3 + bramka OK → GŁÓWNY KPI
//   C — REJECTED: bramka odrzuciła (100% reject oczekiwane)
//   D — OUT-OF-SCOPE: lvl 2 który przeszedł bramkę (informacyjnie)

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BenchmarkInstrumentedTest {

    companion object {
        // ground_truth_lvl03.json — 68 dok., lvl 0–3 — używany dla sekcji B+C+D
        // Sekcja B = lvl 0/1/3 + bramka OK; C = reject; D = lvl2 accepted
        const val GROUND_TRUTH_FILE = "ground_truth_lvl03.json"
    }

    private val context by lazy { InstrumentationRegistry.getInstrumentation().targetContext }
    private val benchDir by lazy { File(context.getExternalFilesDir(null), "bench") }

    // ── Smoke test — regex ICU na urządzeniu ────────────────────────────────
    // Musi przejść PRZED benchmarkiem — incydent 2026-06-25: PatternSyntaxException
    // w OcrNormalizer (ICU nie akceptuje lookbehind z \w*) ubijał cały run.

    @Test
    fun regexSmokeTest() {
        LookupTables.initialize(context)
        resetRegexCache()
        EngineSmoke.runOnce()
        assertFalse("EngineSmoke.failed — regex ICU nie skompilował się na urządzeniu", EngineSmoke.failed)
        println("[SMOKE] OK")
    }

    // ── Główny test ──────────────────────────────────────────────────────────

    @Test
    fun runBenchmark() = runBlocking {
        benchDir.mkdirs()
        println("[BENCH] benchDir: ${benchDir.absolutePath}")

        LookupTables.initialize(context)
        resetRegexCache()
        EngineSmoke.runOnce()
        check(!EngineSmoke.failed) {
            "EngineSmoke FAIL — regex ICU nie skompilował się (patrz logcat). Napraw OcrNormalizer/StructuralEngine."
        }
        println("[SMOKE] OK")

        UserDictionary.load(context)
        UserDictionary.clear(context)
        GuardAllowlist.clear(context)
        println("[BENCH_DEBUG] UserDictionary + GuardAllowlist wyczyszczone przed benchmarkiem")

        val gtFile = File(benchDir, GROUND_TRUTH_FILE)
        check(gtFile.exists()) {
            "Brak $GROUND_TRUTH_FILE — wgraj dataset:\n" +
            "adb push dataset/$GROUND_TRUTH_FILE /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/$GROUND_TRUTH_FILE"
        }

        val groundTruth = JSONArray(gtFile.readText())
        val imagesDir   = File(benchDir, "images")
        check(imagesDir.exists()) {
            "Brak folderu images — wgraj:\nadb push dataset/images /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/images"
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val results    = mutableListOf<DocResult>()

        println("\n[BENCHMARK v2] Dokumentów: ${groundTruth.length()}")
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
            val crit = if (s.criticalMissed > 0) " ⚠CRIT:${s.criticalMissed}" else ""
            val rej  = if (!result.ocrAccepted) " REJECT(conf=${"%.2f".format(result.ocrConf)})" else ""
            val err  = if (result.error != null) " ERR:${result.error}" else ""

            println("  [${(i+1).toString().padStart(3)}] ${fileName.padEnd(20)} " +
                    "[${result.section}] lvl=${result.degLevel} " +
                    "R=$rec$crit$rej$err")
        }

        try {
            saveReports(results)
            printSummary(results)
        } catch (e: Exception) {
            println("[BENCH_FATAL] saveReports: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    // ── Przetwarzanie jednego obrazu ─────────────────────────────────────────

    private suspend fun processImage(
        imgFile: File,
        gt: JSONObject,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
    ): DocResult {
        return try {
            val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
                ?: return analyze(gt, emptyList(), ocrText = "", ocrAccepted = false, ocrConf = 0f,
                                  error = "bitmap null: ${imgFile.name}")

            val mlImage = InputImage.fromBitmap(bitmap, 0)
            val ocrResult: Text = suspendCancellableCoroutine { cont ->
                recognizer.process(mlImage)
                    .addOnSuccessListener { r -> cont.resume(r) {} }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }

            val ocrText     = ocrResult.text
            val ocrAccepted = isOcrQualityAcceptable(ocrResult)
            val ocrConf     = calcOcrConfidence(ocrResult)

            if (!ocrAccepted) {
                return analyze(gt, emptyList(), ocrText = ocrText,
                               ocrAccepted = false, ocrConf = ocrConf, error = null)
            }

            val normalized   = OcrNormalizer.normalize(ocrText)
            val dictEntries  = UserDictionary.entries.toList()
            val engineResult = PseudonymEngine.pseudonymize(normalized.normalizedText, dictEntries, traceMode = true)

            val tokens = engineResult.tokenMap.map { (token, original) ->
                DetectedToken(original = original, token = token, type = token.substringBefore("_"))
            }

            val dumpFile = File(benchDir, "token_dump_doc0.txt")
            if (!dumpFile.exists()) {
                dumpFile.writeText(engineResult.tokenMap.entries.joinToString("\n") { (k, v) -> "$k = $v" })
            }

            analyze(gt, tokens, ocrText,
                normalizedText = normalized.normalizedText,
                ocrAccepted    = true,
                ocrConf        = ocrConf,
                error          = null,
                trace          = engineResult.trace,
                guardRedHits   = engineResult.guardHits.count { it.level == "RED" },
                // BUG-BENCHMARK-BRAK-KONTEKSTU (Paweł 07.07): dotad tylko LICZBA trafien Guard
                // RED trafiala do raportu — zero szczegolow ktory dokument/jaki fragment, wiec
                // diagnoza wymagala recznego adb pull. Zapisujemy label+dopasowany tekst.
                guardRedDetails = engineResult.guardHits
                    .filter { it.level == "RED" }
                    .map { "${it.label}=${it.matchedText.take(40)}" },
            )
        } catch (e: Exception) {
            analyze(gt, emptyList(), ocrText = "", ocrAccepted = false, ocrConf = 0f,
                    error = e.message ?: "błąd")
        }
    }

    // ── Analiza — porównanie z ground truth ──────────────────────────────────

    private val criticalKeys = setOf(
        "pesel", "nip", "nip_sprzedawcy", "nip_nabywcy",
        "iban", "dowod_osobisty", "numer_paszportu",
    )

    private val numericKeys = setOf(
        "pesel", "nip", "nip_sprzedawcy", "nip_nabywcy", "regon_sprzedawcy",
        "iban", "dowod_osobisty", "numer_paszportu", "telefon",
        "numer_faktury", "numer_klienta", "numer_umowy", "numer_kw",
        "sygnatura_akt", "sygnatura_komornicza", "sygnatura_administracyjna",
        "data_urodzenia",
    )

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
        return v.replace(" ", "").replace("-", "").replace("._", ".")
            .map { diacritics[it] ?: it }
            .joinToString("")
            .lowercase()
    }

    private fun normalizeForCompare(s: String) = norm(s)

    // BUG-BENCHMARK-DWIE-DEGRADACJE (Paweł 07.07): stary próg "różnica ≤1 znak" (Hamming dla
    // równej długości, jedno usunięcie dla różnicy 1) łamie się gdy DŁUGA wartość (email, IBAN)
    // ma DWIE NIEZALEŻNE degradacje OCR naraz (np. "wozniak"→"woziak" I "wp.pl"→"wppl" w tym
    // samym adresie — każda z osobna byłaby tolerowana, razem dają odległość edycji 2, ponad
    // stary próg ±1). Silnik prawdopodobnie zamaskował poprawnie (na zdegradowanym tekście),
    // ale benchmark raportował BRAK_W_OCR — potwierdzone ręcznym testem na telefonie: wszystko
    // faktycznie zamaskowane. Fix: prawdziwa odległość Levenshteina (obsługuje kombinacje
    // podstawień/wstawień/usunięć, nie tylko jeden z tych przypadków osobno) + próg skalowany
    // długością wartości — krótkie pola (PESEL, 11 zn.) zostają przy tolerancji 1 (ryzyko FP
    // rośnie nieproporcjonalnie przy krótkich ciągach), długie pola (email, IBAN, 15+ zn.)
    // dostają 2-3 — statystycznie więcej niezależnych pozycji = większa szansa na 2+ literówki
    // naraz, bez utraty odróżnialności od zupełnie innej wartości (zweryfikowane Pythonem:
    // dwa różne PESEL-e/email-e podobnej długości nadal poprawnie odrzucone, odległość rzędu
    // 9-14, daleko ponad próg).
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

    private fun extractNumericRuns(text: String): List<String> =
        Regex("""[A-Z0-9]{9,}""", RegexOption.IGNORE_CASE).findAll(text)
            .map { normalizeForCompare(it.value) }
            .toList()

    // BUG-BENCHMARK-SPACJA-IBAN (Paweł 07.07): extractNumericRuns łamie się na spacji —
    // OCR czasem wstawia spację W ŚRODKU długiego numeru (IBAN, numer_kw), nie tylko na
    // granicy segmentów. "PL7824...798 2507 146112645" → trzy oddzielne runy, żaden nie
    // przechodzi progu długości fuzzyMatch względem pełnej wartości GT → klasyfikacja
    // spada do BRAK_W_OCR mimo że silnik (StructuralEngine 485, dedykowany właśnie na ten
    // przypadek) prawdopodobnie zamaskował to poprawnie. Fix: przesuwane okno o długości
    // wartości GT (±1) na tekście z usuniętymi spacjami W OBRĘBIE OKNA, nie w całym
    // dokumencie (globalne usunięcie spacji sklejałoby też niepowiązane sąsiednie słowa).
    private fun fuzzyContainsIgnoringEmbeddedSpaces(haystack: String, needle: String): Boolean {
        if (needle.length < 9) return false
        val compact = haystack.replace(Regex("""\s"""), "").lowercase()
        for (len in (needle.length - 1)..(needle.length + 1)) {
            if (len < 9 || len > compact.length) continue
            for (start in 0..(compact.length - len)) {
                if (fuzzyMatch(needle, compact.substring(start, start + len))) return true
            }
        }
        return false
    }

    // Okno wokół faktycznej pozycji encji w OCR zamiast stałego cap-u od początku dokumentu —
    // stały .take(N) ucinał tekst PRZED dotarciem do encji w dłuższych dokumentach (faktury z
    // długim wstępem sprzedawcy/nabywcy, akty komornicze). Szuka pierwszych 4 znaków wartości
    // (odporne na drobne różnice OCR w reszcie), fallback: cały tekst gdy nie znaleziono.
    private fun ocrSnippetAround(ocrText: String, value: String, radius: Int = 200): String {
        val needle = value.take(4)
        val idx = if (needle.length >= 3) ocrText.indexOf(needle, ignoreCase = true) else -1
        val snippet = if (idx >= 0) {
            val from = maxOf(0, idx - radius)
            val to = minOf(ocrText.length, idx + value.length + radius)
            ocrText.substring(from, to)
        } else {
            ocrText
        }
        return snippet.replace("\n", " ")
    }

    // Klasyfikacja miss wg §12.2 briefa właściciela
    private fun classifyMiss(key: String, value: String, normalizedText: String): String {
        val normVal = normalizeForCompare(value)
        return when {
            normalizeForCompare(normalizedText).contains(normVal) -> "BUG_SILNIKA"
            key in numericKeys && extractNumericRuns(normalizedText).any { fuzzyMatch(normVal, it) } -> "OCR_ZNIEKSZTAŁCONY"
            key in numericKeys && fuzzyContainsIgnoringEmbeddedSpaces(normalizedText, normVal) -> "OCR_ZNIEKSZTAŁCONY"
            else -> "BRAK_W_OCR"
        }
    }

    private fun analyze(
        gt: JSONObject,
        tokens: List<DetectedToken>,
        ocrText: String,
        normalizedText: String = ocrText,
        ocrAccepted: Boolean,
        ocrConf: Float,
        error: String?,
        trace: List<DetectionTrace> = emptyList(),
        guardRedHits: Int = 0,
        guardRedDetails: List<String> = emptyList(),
    ): DocResult {
        val degLevel = gt.optInt("degradation_level", -1)
        val section: String = when {
            !ocrAccepted -> "C"
            degLevel == 2 -> "D"
            else -> "B"
        }

        val gtEntities = gt.optJSONObject("entities") ?: JSONObject()
        val gtNames    = gtEntities.names()
        val gtNorms    = if (gtNames != null)
            (0 until gtNames.length()).map { norm(gtEntities.get(gtNames.getString(it)).toString()) }
        else emptyList()

        val entities   = mutableListOf<EntityResult>()
        val missLabels = mutableMapOf<String, String>()
        var detected   = 0
        var criticalMissed = 0
        var typeMismatch   = 0

        gtEntities.keys().forEach { key ->
            val value  = gtEntities.getString(key)
            val valN   = norm(value)
            val isCrit = key in criticalKeys

            var matchedTokenType: String? = null
            val found = error == null && ocrAccepted && tokens.any { tok ->
                val on = norm(tok.original)
                // fuzzyMatch (odległość edycji <=1, min. 9 znaków) był ograniczony do numericKeys —
                // literówka/zgubiona litera OCR w środku emaila/nazwiska ("wozniak"→"woziak") łamie
                // proste .contains() mimo że silnik poprawnie zamaskował wartość jako token. Kotwica
                // (np. @) nie waliduje kształtu, więc token często ISTNIEJE — to miernik był ślepy,
                // nie silnik. fuzzyMatch ma już wbudowane zabezpieczenia (długość, max 1 różnica),
                // więc jest bezpieczny dla każdego typu pola, nie tylko numerycznego.
                // Wzorce kontekstowe (np. PESEL/NIP) celowo wchłaniają słowo-kotwicę do tokenu
                // ("PESEL: 12345678901" → jeden token) — dobre dla maskowania, ale psuje
                // fuzzyMatch powyżej: "pesel:12345678901" (17 zn.) vs goła wartość GT (11 zn.)
                // różni się długością o 6, więc próg ±1 znaku nigdy nie przejdzie mimo że token
                // faktycznie zawiera poprawną (lub jedną literą zniekształconą) wartość.
                // Dla pól numerycznych: wyodrębnij sam ciąg alfanumeryczny z dopasowania przed
                // porównaniem, tak jak już robi extractNumericRuns() dla missLabel niżej.
                // Identyfikatory złożone (numer_faktury/umowy/kw/działki: "UMW/2024/291") mają
                // ukośniki, które łamią extractNumericRuns (wymaga ciągłego alnum ≥9 znaków —
                // ukośnik przerywa ciąg na kawałki poniżej progu). Kotwica-etykieta ("nr ", "Nr")
                // zawsze jest PRZED wartością, nigdy po — więc porównanie KOŃCÓWKI tokenu
                // (przycięte do długości GT) z fuzzyMatch bezpiecznie omija nieznaną długość
                // prefiksu niezależnie od typu pola. Znalezione 06.07: "nr UMWI2024/291"
                // (ukośnik odczytany jako "I" przez OCR nawet przy lvl0 "perfect scan" — realne
                // ograniczenie odczytu glifu, nie szum symulowany) + prefiks "nr " razem dawały
                // różnicę 2 znaków, ponad próg fuzzyMatch (±1) mimo że token faktycznie istniał.
                val suffix = if (on.length > valN.length) on.takeLast(valN.length) else on
                val matched = valN == on ||
                    (valN.length >= 6 && (valN.contains(on) || on.contains(valN))) ||
                    fuzzyMatch(valN, on) ||
                    (key in numericKeys && extractNumericRuns(tok.original).any { fuzzyMatch(valN, it) }) ||
                    (valN.length >= 9 && fuzzyMatch(valN, suffix))
                if (matched && matchedTokenType == null) matchedTokenType = tok.type
                matched
            }

            if (found) {
                detected++
                val expectedType = ENTITY_TYPE_MAP[key]
                if (expectedType != null && matchedTokenType != null && expectedType != matchedTokenType)
                    typeMismatch++
            } else {
                missLabels[key] = classifyMiss(key, value, normalizedText)
                if (isCrit) criticalMissed++
            }
            entities.add(EntityResult(key, value, found, isCrit))
        }

        val total = entities.size
        val fpList = mutableListOf<DetectedToken>()
        tokens.forEach { tok ->
            val on = norm(tok.original)
            val isFp = on.length >= 2 && gtNorms.none { gn ->
                on == gn || (on.length >= 6 && (on.contains(gn) || gn.contains(on)))
            }
            if (isFp) fpList.add(tok)
        }
        val fp        = fpList.size
        val recall    = if (total > 0) detected.toDouble() / total else null
        val precision = if (detected + fp > 0) detected.toDouble() / (detected + fp) else null
        val f1        = if (recall != null && precision != null && recall + precision > 0)
            2 * recall * precision / (recall + precision) else null

        val critMisses   = entities.filter { !it.found && it.critical }
        val bugSilnika   = critMisses.count { missLabels[it.key] == "BUG_SILNIKA" }
        val ocrZniek     = critMisses.count { missLabels[it.key] == "OCR_ZNIEKSZTAŁCONY" }
        val brakWOcr     = critMisses.count { missLabels[it.key] == "BRAK_W_OCR" }

        return DocResult(
            file           = gt.getString("file"),
            docType        = gt.optString("doc_type", "?"),
            degLevel       = degLevel,
            qualScore      = gt.optInt("quality_score", -1),
            ocrLen         = ocrText.length,
            ocrText        = ocrText,
            ocrAccepted    = ocrAccepted,
            ocrConf        = ocrConf,
            section        = section,
            error          = error,
            tokens         = tokens,
            entities       = entities,
            fpCount        = fp,
            fpTokens       = fpList,
            missLabels     = missLabels,
            guardRedHits   = guardRedHits,
            guardRedDetails = guardRedDetails,
            summary        = Summary(total, detected, criticalMissed, fp, recall, precision, f1,
                                     typeMismatch, bugSilnika, ocrZniek, brakWOcr),
            trace          = trace,
            normalizedText = normalizedText,
        )
    }

    // ── Raporty ──────────────────────────────────────────────────────────────

    private fun printSummary(results: List<DocResult>) {
        val secB = results.filter { it.section == "B" }
        val secC = results.filter { it.section == "C" }
        val secD = results.filter { it.section == "D" }

        val bEnt      = secB.sumOf { it.summary.total }
        val bDet      = secB.sumOf { it.summary.detected }
        val bFp       = secB.sumOf { it.summary.fp }
        val bRecall   = if (bEnt > 0) bDet.toDouble() / bEnt else 0.0
        val bBugSil   = secB.sumOf { it.summary.bugSilnika }
        val bOcrZniek = secB.sumOf { it.summary.ocrZniekształcony }
        val bGuardRed = secB.sumOf { it.guardRedHits }
        val bCritEnt  = secB.flatMap { r -> r.entities.filter { it.critical } }
        val bCritDet  = bCritEnt.count { it.found }
        val bCritRec  = if (bCritEnt.isNotEmpty()) bCritDet.toDouble() / bCritEnt.size else 0.0
        val cFail     = secC.count { it.tokens.isNotEmpty() }

        println("\n${"═".repeat(60)}")
        println("BENCHMARK v2 — WYNIKI")
        println()
        println("B. IN-SCOPE ACCEPTED (główny KPI release)")
        println("   Dokumenty:                  ${secB.size}")
        println("   Recall ogólny:              ${"%.1f".format(bRecall * 100)}%  (próg: 90%)")
        println("   Recall encje krytyczne:     ${"%.1f".format(bCritRec * 100)}%  (próg: 95%)")
        println()
        println("   BLOKERY RELEASE:")
        println("   BUG_SILNIKA (kryt.):        $bBugSil  ${if (bBugSil == 0) "✓" else "✗ FAIL"}")
        println("   OCR_ZNIEKSZTAŁCONY (kryt.): $bOcrZniek  ${if (bOcrZniek == 0) "✓" else "✗ FAIL"}")
        println("   Guard RED hits:             $bGuardRed  ${if (bGuardRed == 0) "✓" else "✗ FAIL"}")
        println("   FP metryczne:               $bFp  (informacyjnie)")
        println()
        println("C. REJECTED:  ${secC.size} dok.  ${if (cFail == 0) "✓ 100% poprawnie" else "✗ FAIL: $cFail przetworzone"}")
        println("D. OUT-OF-SCOPE (lvl2 accepted): ${secD.size} dok.")
        println()
        println("  Raporty na telefonie:")
        println("    adb pull /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/benchmark_report.txt")
        println("    adb pull /storage/emulated/0/Android/data/com.lynxmask.app/files/bench/benchmark_bugs.txt")
        println("${"═".repeat(60)}\n")
    }

    private val typeMap = mapOf(
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

    private fun saveReports(results: List<DocResult>) {
        val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())

        val secB = results.filter { it.section == "B" }
        val secC = results.filter { it.section == "C" }
        val secD = results.filter { it.section == "D" }

        val bEnt      = secB.sumOf { it.summary.total }
        val bDet      = secB.sumOf { it.summary.detected }
        val bFp       = secB.sumOf { it.summary.fp }
        val bRecall   = if (bEnt > 0) bDet.toDouble() / bEnt else 0.0
        val bPrec     = if (bDet + bFp > 0) bDet.toDouble() / (bDet + bFp) else 0.0
        val bF1       = if (bRecall + bPrec > 0) 2 * bRecall * bPrec / (bRecall + bPrec) else 0.0
        val bBugSil   = secB.sumOf { it.summary.bugSilnika }
        val bOcrZniek = secB.sumOf { it.summary.ocrZniekształcony }
        val bBrakWOcr = secB.sumOf { it.summary.brakWOcr }
        val bGuardRed = secB.sumOf { it.guardRedHits }
        val bErrors   = secB.count { it.error != null }
        val bCritEnt  = secB.flatMap { r -> r.entities.filter { it.critical } }
        val bCritDet  = bCritEnt.count { it.found }
        val bCritRec  = if (bCritEnt.isNotEmpty()) bCritDet.toDouble() / bCritEnt.size else 0.0
        val cFail     = secC.count { it.tokens.isNotEmpty() }

        val perType = mutableMapOf<String, Triple<Int, Int, Int>>()
        secB.forEach { r ->
            r.entities.forEach { e ->
                val t = typeMap[e.key] ?: "?"
                val (d, tot, fp) = perType.getOrDefault(t, Triple(0, 0, 0))
                perType[t] = Triple(if (e.found) d + 1 else d, tot + 1, fp)
            }
        }

        val perLevel = mutableMapOf<Int, Triple<Int, Int, Int>>()
        secB.forEach { r ->
            val (d, tot, docs) = perLevel.getOrDefault(r.degLevel, Triple(0, 0, 0))
            perLevel[r.degLevel] = Triple(d + r.summary.detected, tot + r.summary.total, docs + 1)
        }

        val lvlLabels = mapOf(
            0 to "perfect scan  ", 1 to "light noise   ", 2 to "noise+blur    ",
            3 to "phone (good)  ", 4 to "phone (casual)", 5 to "poor quality  ",
        )

        // ── benchmark_report.txt ─────────────────────────────────────────────
        val sb = StringBuilder()
        sb.appendLine("BENCHMARK LYNXMASK MOBILE v2 — RAPORT")
        sb.appendLine("Data:    $ts")
        sb.appendLine("Dataset: $GROUND_TRUTH_FILE")
        sb.appendLine("═".repeat(60))
        sb.appendLine()
        sb.appendLine("A. ENGINE-ONLY (testy JVM)")
        sb.appendLine("   → gradlew :app:testDebugUnitTest")
        sb.appendLine("   → wynik: patrz Claude_Code.txt (ostatni run JVM)")
        sb.appendLine()
        sb.appendLine("B. IN-SCOPE ACCEPTED — GŁÓWNY KPI RELEASE")
        sb.appendLine("   Dokumenty:                       ${secB.size}")
        sb.appendLine("   Encji w ground truth:            $bEnt")
        sb.appendLine("   Wykrytych i zamaskowanych:       $bDet")
        sb.appendLine("   Pominiętych:                     ${bEnt - bDet}")
        sb.appendLine("   RECALL ogólny:                   ${"%.1f".format(bRecall * 100)}%  (próg: ≥90%)")
        sb.appendLine("   RECALL encje krytyczne:          ${"%.1f".format(bCritRec * 100)}%  (próg: ≥95%)")
        sb.appendLine("   PRECISION:                       ${"%.1f".format(bPrec * 100)}%")
        sb.appendLine("   F1:                              ${"%.1f".format(bF1 * 100)}%")
        sb.appendLine()
        sb.appendLine("   ── BLOKERY RELEASE ─────────────────────────────────")
        sb.appendLine("   BUG_SILNIKA (kryt.):             $bBugSil   ${if (bBugSil == 0) "✓ OK" else "✗ FAIL — blokuje release"}")
        sb.appendLine("   OCR_ZNIEKSZTAŁCONY (kryt.):      $bOcrZniek   ${if (bOcrZniek == 0) "✓ OK" else "✗ FAIL — blokuje release"}")
        sb.appendLine("   Guard RED hits:                  $bGuardRed   ${if (bGuardRed == 0) "✓ OK" else "✗ FAIL — blokuje release"}")
        sb.appendLine("   ── INFORMACYJNIE ───────────────────────────────────")
        sb.appendLine("   BRAK_W_OCR (kryt.):              $bBrakWOcr   (nie blokuje — sufit OCR)")
        sb.appendLine("   FP metryczne (token vs GT):      $bFp   (nie blokuje)")
        sb.appendLine("   Błędy pipeline:                  $bErrors")

        // FP per layer summary
        val fpLayerCounts = mutableMapOf<String, Int>()
        secB.forEach { r ->
            val traceByToken = r.trace.associateBy { it.token }
            r.fpTokens.forEach { tok ->
                val layer = traceByToken[tok.token]?.layer ?: "?"
                fpLayerCounts[layer] = (fpLayerCounts[layer] ?: 0) + 1
            }
        }
        if (fpLayerCounts.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("   FP PER WARSTWA (sekcja B)")
            fpLayerCounts.entries.sortedByDescending { it.value }.forEach { (layer, cnt) ->
                sb.appendLine("   ${layer.padEnd(18)} $cnt")
            }
        }
        sb.appendLine()
        sb.appendLine("   RECALL PER TYP ENCJI (sekcja B)")
        perType.toSortedMap().forEach { (t, triple) ->
            val (d, tot, _) = triple
            val rc  = if (tot > 0) d.toDouble() / tot else 0.0
            val bar = "█".repeat((rc * 16).toInt()) + "░".repeat(16 - (rc * 16).toInt())
            sb.appendLine("   ${t.padEnd(12)} ${"%.1f".format(rc * 100)}%  [$bar]  ($d/$tot)")
        }
        sb.appendLine()
        sb.appendLine("   RECALL PER POZIOM DEGRADACJI (sekcja B)")
        perLevel.toSortedMap().forEach { (lv, triple) ->
            val (d, tot, docs) = triple
            val rc  = if (tot > 0) d.toDouble() / tot else 0.0
            val bar = "█".repeat((rc * 20).toInt()) + "░".repeat(20 - (rc * 20).toInt())
            sb.appendLine("   Lvl $lv ${lvlLabels[lv] ?: ""} [$bar] ${"%.1f".format(rc * 100)}%  docs=$docs ent=$tot")
        }
        sb.appendLine()
        sb.appendLine("C. REJECTED (bramka OCR odrzuciła)")
        sb.appendLine("   Dokumenty odrzucone:             ${secC.size}")
        sb.appendLine("   Status:                          ${if (cFail == 0) "✓ 100% poprawnie odrzucone" else "✗ FAIL: $cFail dokumentów przetworzone mimo reject"}")
        if (secC.isNotEmpty()) {
            val lvlCounts = secC.groupBy { it.degLevel }.mapValues { it.value.size }
            lvlCounts.toSortedMap().forEach { (lv, cnt) ->
                sb.appendLine("   Lvl $lv: $cnt dok.")
            }
        }
        sb.appendLine()
        sb.appendLine("D. OUT-OF-SCOPE (lvl2 accepted — informacyjnie)")
        if (secD.isEmpty()) {
            sb.appendLine("   Brak dokumentów (bramka poprawnie odrzuca lvl2)")
        } else {
            val dEnt = secD.sumOf { it.summary.total }
            val dDet = secD.sumOf { it.summary.detected }
            val dRec = if (dEnt > 0) dDet.toDouble() / dEnt else 0.0
            sb.appendLine("   Dokumenty:                       ${secD.size}  ← sygnał: obniżyć próg bramki?")
            sb.appendLine("   Recall (sufit OCR na lvl2):      ${"%.1f".format(dRec * 100)}%")
        }

        File(benchDir, "benchmark_report.txt").writeText(sb.toString())

        // ── benchmark_bugs.txt ───────────────────────────────────────────────
        val bb = StringBuilder()
        bb.appendLine("BUGS v2 — SEKCJA B (IN-SCOPE ACCEPTED) — $ts")
        bb.appendLine("═".repeat(60))
        bb.appendLine()

        val critMissesB = secB.flatMap { r ->
            r.entities.filter { !it.found && it.critical }.map { r to it }
        }
        val bugSilCases   = critMissesB.filter { (r, e) -> r.missLabels[e.key] == "BUG_SILNIKA" }
        val ocrZniekCases = critMissesB.filter { (r, e) -> r.missLabels[e.key] == "OCR_ZNIEKSZTAŁCONY" }
        val brakOcrCases  = critMissesB.filter { (r, e) -> r.missLabels[e.key] == "BRAK_W_OCR" }

        if (bugSilCases.isNotEmpty()) {
            bb.appendLine("[BUG_SILNIKA] Encje krytyczne — BLOCKER RELEASE: ${bugSilCases.size}")
            bb.appendLine()
            bugSilCases.forEach { (r, e) ->
                bb.appendLine("  ${r.file.substringAfterLast("/")}  lvl=${r.degLevel}  ${e.key}=${e.value.take(40)}")
                val idx = r.normalizedText.indexOf(e.value.take(4), ignoreCase = true)
                if (idx >= 0) {
                    val from = maxOf(0, idx - 10)
                    val to   = minOf(r.normalizedText.length, idx + e.value.length + 15)
                    bb.appendLine("    OCR: «${r.normalizedText.substring(from, to).replace("\n", "↵")}»")
                }
                // Diagnostyka "chorego termometru" (06.07): wartość bywa realnie zamaskowana
                // (potwierdzone ręcznie na telefonie), ale token nie przechodzi porównania z GT
                // z innego powodu niż literówka/kotwica-prefiks. Wypisz WSZYSTKIE tokeny tego
                // dokumentu — pozwala zobaczyć dokładnie co silnik przechwycił.
                if (r.tokens.isNotEmpty()) {
                    bb.appendLine("    Tokeny w dokumencie: " +
                        r.tokens.joinToString(", ") { "${it.type}=«${it.original.take(40)}»" })
                }
            }
            bb.appendLine()
        }

        if (ocrZniekCases.isNotEmpty()) {
            bb.appendLine("[OCR_ZNIEKSZTAŁCONY] Encje krytyczne — BLOCKER RELEASE: ${ocrZniekCases.size}")
            bb.appendLine()
            // BUG-BENCHMARK-BRAK-KONTEKSTU (Paweł 07.07): ta sekcja pokazywała tylko etykietę
            // i wartość GT, zero fragmentu OCR/tokenów — diagnoza wymagała ręcznego adb pull
            // za każdym razem. Dodano ten sam kontekst co [BUG_SILNIKA] wyżej.
            ocrZniekCases.forEach { (r, e) ->
                bb.appendLine("  ${r.file.substringAfterLast("/")}  lvl=${r.degLevel}  ${e.key}=${e.value.take(40)}")
                bb.appendLine("    → encja nie jest exact w OCR, ale fuzzy match — bug silnika/normalizera")
                val idx = r.normalizedText.indexOf(e.value.take(4), ignoreCase = true)
                if (idx >= 0) {
                    val from = maxOf(0, idx - 10)
                    val to   = minOf(r.normalizedText.length, idx + e.value.length + 15)
                    bb.appendLine("    OCR: «${r.normalizedText.substring(from, to).replace("\n", "↵")}»")
                }
                if (r.tokens.isNotEmpty()) {
                    bb.appendLine("    Tokeny w dokumencie: " +
                        r.tokens.joinToString(", ") { "${it.type}=«${it.original.take(40)}»" })
                }
            }
            bb.appendLine()
        }

        val guardRedCases = secB.filter { it.guardRedDetails.isNotEmpty() }
        if (guardRedCases.isNotEmpty()) {
            bb.appendLine("[GUARD RED] Dokumenty z alertem RED — BLOCKER RELEASE: " +
                "${guardRedCases.sumOf { it.guardRedDetails.size }}")
            bb.appendLine()
            guardRedCases.forEach { r ->
                bb.appendLine("  ${r.file.substringAfterLast("/")}  lvl=${r.degLevel}")
                r.guardRedDetails.forEach { bb.appendLine("    GUARD RED: $it") }
            }
            bb.appendLine()
        }

        if (brakOcrCases.isNotEmpty()) {
            bb.appendLine("[BRAK_W_OCR] Encje krytyczne (nie blokuje release): ${brakOcrCases.size}")
            bb.appendLine()
            brakOcrCases.forEach { (r, e) ->
                bb.appendLine("  ${r.file.substringAfterLast("/")}  lvl=${r.degLevel}  qs=${r.qualScore}  ${e.key}=${e.value.take(40)}")
            }
            bb.appendLine()
        }

        if (critMissesB.isEmpty())
            bb.appendLine("✓ Brak krytycznych braków w sekcji B — silnik gotowy do release.\n")

        // Diagnostyka OSOBA
        val osobaMisses = secB.flatMap { r ->
            r.entities.filter { !it.found && (it.key.startsWith("imie_") || it.key == "autor" || it.key == "osoba") }
                .map { r to it }
        }
        if (osobaMisses.isNotEmpty()) {
            bb.appendLine("[OSOBA POMINIĘTE] ${osobaMisses.size} encji:")
            bb.appendLine()
            osobaMisses.forEach { (r, e) ->
                bb.appendLine("  ${r.file.substringAfterLast("/")}  ${e.key}=${e.value}  → ${r.missLabels[e.key] ?: "?"}")
                bb.appendLine("    OCR[300]: ${r.ocrText.take(300).replace("\n", " ")}")
            }
            bb.appendLine()
        }

        // Diagnostyka EMAIL
        val emailMisses = secB.flatMap { r ->
            r.entities.filter { !it.found && it.key == "email" }.map { r to it }
        }
        if (emailMisses.isNotEmpty()) {
            bb.appendLine("[EMAIL POMINIĘTE] ${emailMisses.size} encji:")
            bb.appendLine()
            emailMisses.forEach { (r, e) ->
                bb.appendLine("  ${r.file.substringAfterLast("/")}  ${e.key}=${e.value}  → ${r.missLabels[e.key] ?: "?"}")
                bb.appendLine("    OCR[300]: ${r.ocrText.take(300).replace("\n", " ")}")
                if (r.normalizedText != r.ocrText) {
                    val normSnippet = r.normalizedText.windowed(300, 1, true)
                        .firstOrNull { it.contains("@") }?.take(120) ?: ""
                    if (normSnippet.isNotEmpty())
                        bb.appendLine("    NORM[@]: $normSnippet")
                }
            }
            bb.appendLine()
        }

        // Diagnostyka ADRES
        val adresMisses = secB.flatMap { r ->
            r.entities.filter { !it.found && it.key.startsWith("adres") }.map { r to it }
        }
        if (adresMisses.isNotEmpty()) {
            bb.appendLine("[ADRES POMINIĘTE] ${adresMisses.size} encji:")
            bb.appendLine()
            adresMisses.forEach { (r, e) ->
                bb.appendLine("  ${r.file.substringAfterLast("/")}  ${e.key}=${e.value}  → ${r.missLabels[e.key] ?: "?"}")
                bb.appendLine("    OCR: ${ocrSnippetAround(r.ocrText, e.value)}")
            }
            bb.appendLine()
        }

        // Diagnostyka NUMER — brakowało tej sekcji mimo że NUMER ma najwięcej pominięć (BUG-STALY-NUMER)
        val numerMisses = secB.flatMap { r ->
            r.entities.filter { !it.found && typeMap[it.key] == "NUMER" }.map { r to it }
        }
        if (numerMisses.isNotEmpty()) {
            bb.appendLine("[NUMER POMINIĘTE] ${numerMisses.size} encji:")
            bb.appendLine()
            numerMisses.forEach { (r, e) ->
                bb.appendLine("  ${r.file.substringAfterLast("/")}  ${e.key}=${e.value}  → ${r.missLabels[e.key] ?: "?"}")
                // OCR[200]/[500] za krótkie — ucinały tekst przed dotarciem do encji w dłuższych
                // dokumentach (np. numer faktury po długim wstępie sprzedawcy/nabywcy,
                // doc_00010 staly 06.07). Okno wokół faktycznej pozycji zamiast stałego cap-u.
                bb.appendLine("    OCR: ${ocrSnippetAround(r.ocrText, e.value)}")
            }
            bb.appendLine()
        }

        // FP breakdown by layer
        val fpByLayer = mutableMapOf<String, MutableList<Pair<String, String>>>() // layer → [(original, docFile)]
        secB.forEach { r ->
            val traceByToken = r.trace.associateBy { it.token }
            r.fpTokens.forEach { tok ->
                val trEntry = traceByToken[tok.token]
                val layer = trEntry?.layer ?: "?"
                val rule  = trEntry?.rule  ?: "?"
                val label = "$layer/$rule"
                fpByLayer.getOrPut(label) { mutableListOf() }
                    .add(Pair(tok.original, r.file.substringAfterLast("/")))
            }
        }
        if (fpByLayer.isNotEmpty()) {
            bb.appendLine("[FALSE POSITIVES] Podział wg warstwy (${secB.sumOf { it.fpCount }} łącznie sekcja B):")
            bb.appendLine()
            fpByLayer.entries.sortedByDescending { it.value.size }.forEach { (label, items) ->
                bb.appendLine("  $label: ${items.size}")
                items.take(5).forEach { (orig, file) ->
                    bb.appendLine("    $file  \"${orig.take(60)}\"")
                }
                if (items.size > 5) bb.appendLine("    ... i ${items.size - 5} więcej")
            }
            bb.appendLine()
        }

        // Sekcja C — fail jeśli tokeny mimo reject
        if (cFail > 0) {
            bb.appendLine("[REJECT FAIL] Dokumenty przetworzone mimo odrzucenia przez bramkę OCR:")
            bb.appendLine()
            secC.filter { it.tokens.isNotEmpty() }.forEach { r ->
                bb.appendLine("  ${r.file.substringAfterLast("/")}  conf=${"%.2f".format(r.ocrConf)}  tokens=${r.tokens.size}")
            }
            bb.appendLine()
        }

        File(benchDir, "benchmark_bugs.txt").writeText(bb.toString())

        // Logcat dump (działa gdy adb pull zablokowany przez Android 16)
        println("\n[BUGS_START]")
        bb.toString().lines().forEach { println(it) }
        println("[BUGS_END]")

        generateDictionaryHtml(secB)

        // Trace
        val traceFile = File(benchDir, "benchmark_trace.txt")
        val traceLines = results.flatMap { result ->
            result.trace.map { t ->
                "${result.file}\t${t.layer}\t${t.rule}\t${t.token}\t${t.matchedText}"
            }
        }
        traceFile.writeText("DOC\tLAYER\tRULE\tTOKEN\tMATCHED_TEXT\n" + traceLines.joinToString("\n"))

        // Kopia do Documents (backwards compat) — best-effort; Android 16+ może odrzucić zapis.
        // Nie failuj testu po zapisie raportów do benchDir (incydent: FileAlreadyExistsException).
        try {
            val publicDir = File("/storage/emulated/0/Documents/LynxMask")
            publicDir.mkdirs()
            benchDir.listFiles()?.forEach { file ->
                val dest = File(publicDir, file.name)
                runCatching {
                    if (dest.exists()) dest.delete()
                    file.copyTo(dest, overwrite = true)
                }.onFailure { e ->
                    println("[BENCH_WARN] Kopia ${file.name} → Documents: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("[BENCH_WARN] Kopia do Documents pominięta: ${e.message}")
        }
    }

    // ── HTML — kandydaci do słownika (sekcja B only) ─────────────────────────

    private fun generateDictionaryHtml(secBResults: List<DocResult>) {
        data class Candidate(val value: String, val type: String)

        val dictTypeMap = mapOf(
            "imie_nazwisko" to "OSOBA", "imie_nazwisko_nabywcy" to "OSOBA",
            "autor" to "OSOBA", "osoba" to "OSOBA",
            "adres" to "ADRES", "adres_nabywcy" to "ADRES",
            "adres_zleceniobiorca" to "ADRES", "adres_zleceniodawca" to "ADRES",
            "email" to "EMAIL",
        )
        val dictTypes = setOf("OSOBA", "ADRES", "EMAIL")

        val counts = mutableMapOf<Pair<String, String>, Int>()
        secBResults.forEach { r ->
            r.entities.filter { e ->
                !e.found && dictTypeMap.containsKey(e.key) && dictTypeMap[e.key] in dictTypes && r.ocrLen > 50
            }.forEach { e ->
                val type = dictTypeMap[e.key] ?: return@forEach
                counts[e.value to type] = (counts[e.value to type] ?: 0) + 1
            }
        }

        if (counts.isEmpty()) {
            File(benchDir, "missed_entities.html").writeText(
                "<html><body><h2>Brak kandydatów do słownika.</h2></body></html>"
            )
            return
        }

        val rows = counts.entries.sortedByDescending { it.value }.joinToString("\n") { (pair, cnt) ->
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
  Encje in-scope (sekcja B) pominięte przez silnik. Zaznacz które chcesz dodać i kliknij <b>Eksportuj zaznaczone</b>.
</p>
<div>
  <button onclick="selectAll()">Zaznacz wszystko</button>
  <button class="sec" onclick="selectNone()">Odznacz wszystko</button>
  <button onclick="exportSelected()">Eksportuj zaznaczone</button>
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
  document.getElementById('status').textContent = '✓ Pobrano ' + items.length + ' wpisów.';
}
</script>
</body>
</html>"""

        File(benchDir, "missed_entities.html").writeText(html)
        println("[HTML] missed_entities.html gotowe")
    }

    // ── Data classes ─────────────────────────────────────────────────────────

    data class DetectedToken(val original: String, val token: String, val type: String)

    data class EntityResult(val key: String, val value: String, val found: Boolean, val critical: Boolean)

    data class Summary(
        val total: Int, val detected: Int, val criticalMissed: Int, val fp: Int,
        val recall: Double?, val precision: Double?, val f1: Double?,
        val typeMismatch: Int = 0,
        val bugSilnika: Int = 0,
        val ocrZniekształcony: Int = 0,
        val brakWOcr: Int = 0,
    )

    data class DocResult(
        val file: String, val docType: String, val degLevel: Int, val qualScore: Int,
        val ocrLen: Int, val ocrText: String,
        val ocrAccepted: Boolean, val ocrConf: Float,
        val section: String,
        val error: String?,
        val tokens: List<DetectedToken>, val entities: List<EntityResult>,
        val fpCount: Int,
        val fpTokens: List<DetectedToken> = emptyList(),
        val missLabels: Map<String, String>,
        val guardRedHits: Int,
        val guardRedDetails: List<String> = emptyList(),
        val summary: Summary,
        val trace: List<DetectionTrace> = emptyList(),
        val normalizedText: String = ocrText,
    )
}
