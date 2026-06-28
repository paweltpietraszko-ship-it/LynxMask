package com.lynxmask.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class RegionType { FACE, MANUAL }

data class RedactionRegion(
    val id: String = UUID.randomUUID().toString(),
    val rect: RectF,
    val type: RegionType,
    val isBlurred: Boolean = true,
    val label: String = "Pole"
)

object ImageRedactionPipeline {

    /** Ostatnia średnia pewność OCR (ML Kit) — do bannera w UI. */
    var lastDetectionOcrConfidence: Float? = null
        private set

    private const val LINE_OCR_CONF_MIN = 0.52f
    @Suppress("unused")
    private const val BLUR_PASSES = 3
    @Suppress("unused")
    private const val BLUR_DIVISOR = 8

    private val blackPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    suspend fun detectFacesAsRegions(bitmap: Bitmap): List<RedactionRegion> {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.005f)
            .build()
        val detector = FaceDetection.getClient(options)
        return try {
            val result = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
            DebugLogBuffer.log("FaceDetect", "Wykryto ${result.size} twarzy")
            result.map { face ->
                RedactionRegion(rect = RectF(face.boundingBox), type = RegionType.FACE, label = "Twarz")
            }
        } catch (e: Exception) {
            DebugLogBuffer.log("FaceDetect", "BŁĄD: ${e.message}")
            emptyList()
        } finally {
            detector.close()
        }
    }

    // Wykryj linie tekstu zawierające PII (PESEL, nr doc, data, imię/nazwisko, etc.)
    // Etykiety pól ("PESEL:", "Data urodzenia:") pomijane — Title Case na CAPS etykiet dawał FP w NameEngine.
    suspend fun detectTextLinesAsRegions(
        bitmap: Bitmap,
        userDict: List<Pair<String, String>> = emptyList()
    ): List<RedactionRegion> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            lastDetectionOcrConfidence = calcOcrConfidence(result).takeIf { it > 0f }

            val piiLines = result.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    val raw = line.text.trim()
                    if (raw.length < 3) return@flatMap emptyList()

                    val lineConf = lineConfidence(line)
                    if (lineConf != null && lineConf < LINE_OCR_CONF_MIN) {
                        DebugLogBuffer.log("TextPii", "SKIP conf ${"%.0f%%".format(lineConf * 100)}: \"$raw\"")
                        return@flatMap emptyList()
                    }

                    val textForEngine = extractPiiCheckText(raw) ?: return@flatMap emptyList()
                    val norm = OcrNormalizer.normalize(textForEngine)
                    val engineResult = PseudonymEngine.pseudonymize(norm.normalizedText, userDictionary = userDict)
                    if (engineResult.tokenMap.isEmpty()) return@flatMap emptyList()

                    if (!shouldMaskLine(textForEngine, engineResult)) {
                        DebugLogBuffer.log("TextPii", "SKIP FP: \"$raw\" → ${engineResult.tokenMap.keys}")
                        return@flatMap emptyList()
                    }

                    DebugLogBuffer.log("TextPii", "PII: \"$raw\" → ${engineResult.tokenMap.keys}")
                    val tokenValues = engineResult.tokenMap.values.map { it.trim() }.filter { it.length >= 2 }
                    val label = normalizeDisplayLabel(guessFieldLabel(raw, textForEngine, engineResult))
                    maskBoxesForLine(line, tokenValues).map { rect ->
                        RedactionRegion(rect = rect, type = RegionType.MANUAL, isBlurred = true, label = label)
                    }
                }
            }
            val refined = filterTinyRegions(
                dedupeOverlapping(
                    mergeAdjacentSameLabel(
                        consolidateSameRowRegions(piiLines, bitmap.height),
                        bitmap.height
                    )
                ),
                bitmap.width,
                bitmap.height
            )
            DebugLogBuffer.log(
                "TextDetect",
                "PII regionów: ${refined.size} (z ${piiLines.size} linii, OCR conf=${
                    lastDetectionOcrConfidence?.let { "%.0f%%".format(it * 100) } ?: "?"
                })"
            )
            refined
        } catch (e: Exception) {
            DebugLogBuffer.log("TextDetect", "BŁĄD: ${e.message}")
            emptyList()
        } finally {
            recognizer.close()
        }
    }

    /** Tekst linii do PseudonymEngine — null = pominąć (sam label, bez danych). */
    private fun extractPiiCheckText(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.length < 3) return null

        val colon = Regex("""^(.+?)[:：]\s*(.*)$""").find(trimmed)
        if (colon != null) {
            val label = colon.groupValues[1].trim()
            val value = colon.groupValues[2].trim()
            if (value.isEmpty()) {
                DebugLogBuffer.log("TextPii", "SKIP label (pusta wartość): \"$raw\"")
                return null
            }
            if (isPureFieldLabel(label)) {
                if (value.length < 3 && value.none { it.isDigit() }) return null
                return value
            }
            return value.ifEmpty { trimmed }
        }

        // Etykieta i wartość w jednej linii bez dwukropka (typowe OCR dowodu)
        val gapSplit = trimmed.split(Regex("""\s{2,}"""))
        if (gapSplit.size >= 2) {
            val label = gapSplit[0].trim()
            val value = gapSplit.drop(1).joinToString(" ").trim()
            if (value.isNotEmpty() && isPureFieldLabel(label)) return value
        }

        val (labelOnly, afterPrefix) = peelFieldLabelPrefix(trimmed)
        if (labelOnly) {
            DebugLogBuffer.log("TextPii", "SKIP label: \"$raw\"")
            return null
        }
        if (afterPrefix != trimmed) {
            if (afterPrefix.length < 3 && afterPrefix.none { it.isDigit() }) return null
            return afterPrefix
        }

        if (isPureFieldLabel(trimmed)) {
            DebugLogBuffer.log("TextPii", "SKIP label: \"$raw\"")
            return null
        }
        return trimmed
    }

    /** Jeden prostokąt na linię PII (unia elementów z danymi — bez 14 drobnych pasków). */
    private fun maskBoxesForLine(
        line: com.google.mlkit.vision.text.Text.Line,
        tokenValues: List<String>
    ): List<RectF> {
        val elements = line.elements
        if (elements.isEmpty()) {
            return line.boundingBox?.let { listOf(padRect(RectF(it))) } ?: emptyList()
        }

        fun elemMatchesPii(text: String): Boolean {
            val et = text.trim()
            if (et.length < 2 || isLabelElement(et)) return false
            return tokenValues.any { tv ->
                et.equals(tv, ignoreCase = true) ||
                    (tv.length >= 3 && et.contains(tv, ignoreCase = true)) ||
                    (et.length >= 3 && tv.contains(et, ignoreCase = true))
            }
        }

        val dataElements = elements.filter { !isLabelElement(it.text) }
        val candidates = when {
            dataElements.isNotEmpty() -> {
                val matched = dataElements.filter { elemMatchesPii(it.text) }
                when {
                    matched.isNotEmpty() -> matched
                    dataElements.any { it.text.any { ch -> ch.isDigit() } } ->
                        dataElements.filter { it.text.any { ch -> ch.isDigit() } }
                    else -> dataElements
                }
            }
            else -> elements.filter { elemMatchesPii(it.text) || it.text.any { ch -> ch.isDigit() } }
        }

        val boxes = candidates.mapNotNull { it.boundingBox?.let { b -> RectF(b) } }
        if (boxes.isEmpty()) return emptyList()
        return listOf(padRect(unionRects(boxes)))
    }

    private fun unionRects(boxes: List<RectF>): RectF {
        var left = boxes[0].left
        var top = boxes[0].top
        var right = boxes[0].right
        var bottom = boxes[0].bottom
        for (i in 1 until boxes.size) {
            left = minOf(left, boxes[i].left)
            top = minOf(top, boxes[i].top)
            right = maxOf(right, boxes[i].right)
            bottom = maxOf(bottom, boxes[i].bottom)
        }
        return RectF(left, top, right, bottom)
    }

    /** Scal regiony w tej samej linii (OCR czasem rozbija jedno pole na kilka). */
    private fun consolidateSameRowRegions(regions: List<RedactionRegion>, imageHeight: Int): List<RedactionRegion> {
        if (regions.size <= 1) return regions
        val threshold = imageHeight * 0.018f
        val sorted = regions.sortedBy { it.rect.top }
        val out = mutableListOf<RedactionRegion>()
        for (r in sorted) {
            val cy = (r.rect.top + r.rect.bottom) / 2f
            val last = out.lastOrNull()
            if (last != null) {
                val lcy = (last.rect.top + last.rect.bottom) / 2f
                if (kotlin.math.abs(cy - lcy) <= threshold) {
                    out[out.lastIndex] = last.copy(
                        rect = unionRects(listOf(last.rect, r.rect)),
                        label = pickBetterLabel(last.label, r.label)
                    )
                    continue
                }
            }
            out.add(r)
        }
        return out
    }

    private fun pickBetterLabel(a: String, b: String): String {
        fun score(s: String) = when {
            s in setOf("Pole", "Numer", "Maska ręczna") -> 0
            s.length <= 4 -> 1
            else -> 2
        }
        return if (score(b) > score(a)) b else a
    }

    private fun lineConfidence(line: com.google.mlkit.vision.text.Text.Line): Float? {
        val confs = line.elements.mapNotNull { it.confidence }
        return if (confs.isEmpty()) null else confs.average().toFloat()
    }

    /** Offline: strukturalne PII zawsze; imiona tylko gdy wyglądają jak prawdziwe (nie śmieci OCR). */
    private fun shouldMaskLine(textForEngine: String, engineResult: PseudonymResult): Boolean {
        val text = textForEngine.trim()
        if (text.length < 3) return false

        val tokenTypes = engineResult.tokenMap.keys.map { it.substringBefore('_') }.toSet()
        if (tokenTypes.any { it in STRUCTURAL_TOKEN_TYPES }) return true

        if (text.matches(Regex("""[A-HJ-NPR-Z0-9]{17}""", RegexOption.IGNORE_CASE))) return true
        if (text.matches(Regex("""\d{11}"""))) return true
        if (text.matches(Regex("""[A-Z]{2,3}\s?\d{4,5}[A-Z]{0,2}""", RegexOption.IGNORE_CASE))) return true

        if (tokenTypes.contains("OSOBA") || tokenTypes.contains("FIRMA")) {
            return looksLikePlausiblePersonName(text)
        }
        return false
    }

    private val STRUCTURAL_TOKEN_TYPES = setOf("NUMER", "EMAIL", "ADRES", "KWOTA")

    private fun looksLikePlausiblePersonName(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4) return false
        if (t.any { it.isDigit() }) return false
        val letters = t.count { it.isLetter() }
        if (letters.toFloat() / t.length < 0.65f) return false
        val words = t.split(Regex("""\s+""")).filter { w -> w.any { it.isLetter() } }
        if (words.isEmpty()) return false
        // OCR-śmieci: jedno „słowo” same wielkie bez sensu (< 5 znaków)
        if (words.size == 1 && words[0].length < 5 && words[0] == words[0].uppercase()) return false
        // Zbyt mało samogłosek → losowy OCR
        val vowels = t.lowercase().count { it in "aeiouyąęó" }
        if (vowels.toFloat() / letters < 0.15f) return false
        return true
    }

    private fun normalizeDisplayLabel(label: String): String {
        if (label.equals("vin", ignoreCase = true) || label.equals("ViN", ignoreCase = true)) return "VIN"
        if (label.equals("pesel", ignoreCase = true)) return "PESEL"
        return label
    }

    private fun mergeAdjacentSameLabel(regions: List<RedactionRegion>, imageHeight: Int): List<RedactionRegion> {
        if (regions.size <= 1) return regions
        val threshold = imageHeight * 0.028f
        val sorted = regions.sortedBy { it.rect.top }
        val out = mutableListOf<RedactionRegion>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.label == r.label) {
                val gap = r.rect.top - last.rect.bottom
                if (gap <= threshold) {
                    out[out.lastIndex] = last.copy(rect = unionRects(listOf(last.rect, r.rect)))
                    continue
                }
            }
            out.add(r)
        }
        return out
    }

    private fun dedupeOverlapping(regions: List<RedactionRegion>): List<RedactionRegion> {
        val out = mutableListOf<RedactionRegion>()
        for (r in regions.sortedByDescending { it.rect.width() * it.rect.height() }) {
            if (out.none { regionIoU(it.rect, r.rect) > 0.55f }) out.add(r)
        }
        return out.sortedBy { it.rect.top }
    }

    private fun filterTinyRegions(
        regions: List<RedactionRegion>,
        imageWidth: Int,
        imageHeight: Int
    ): List<RedactionRegion> {
        val minW = imageWidth * 0.025f
        val minH = imageHeight * 0.006f
        return regions.filter { r ->
            r.rect.width() >= minW && r.rect.height() >= minH
        }
    }

    /** Krótka etykieta pola do listy przełączników (VIN, PESEL, nr rej. …). */
    private fun guessFieldLabel(
        rawLine: String,
        checkedText: String,
        engineResult: PseudonymResult
    ): String {
        val rawFolded = foldLabel(rawLine)
        if (rawFolded.contains("vin")) return "VIN"

        val colonSide = Regex("""^(.+?)[:：]\s*""").find(rawLine.trim())?.groupValues?.get(1)?.trim()
        colonSide?.let { side ->
            foldLabel(side).let { folded ->
                FIELD_DISPLAY_PHRASES[folded]?.let { return it }
                FIELD_DISPLAY_PHRASES.entries.firstOrNull { (k, _) -> folded.startsWith(k) }?.value?.let { return it }
            }
        }

        val text = checkedText.replace(Regex("""\s+"""), " ").trim()
        if (text.matches(Regex("""[A-HJ-NPR-Z0-9]{17}""", RegexOption.IGNORE_CASE))) return "VIN"
        if (text.matches(Regex("""\d{11}"""))) return "PESEL"
        if (text.matches(Regex("""[A-Z]{2,3}\s?\d{4,5}[A-Z]{0,2}""", RegexOption.IGNORE_CASE))) return "Nr rejestracyjny"

        val tokenPrefix = engineResult.tokenMap.keys.firstOrNull()?.substringBefore('_')
        when (tokenPrefix) {
            "ADRES" -> return "Adres"
            "FIRMA" -> return "Firma"
            "EMAIL" -> return "E-mail"
            "KWOTA" -> return "Kwota"
            "NUMER" -> return if (text.length == 17) "VIN" else "Numer"
            "OSOBA" -> {
                colonSide?.let { foldLabel(it) }?.let { f ->
                    when {
                        f.contains("nazwisko") -> return "Nazwisko"
                        f.contains("imie") || f.contains("imiona") -> return "Imię"
                        f.contains("wlasciciel") -> return "Właściciel"
                    }
                }
                return if (looksLikePlausiblePersonName(text)) "Imię/nazwisko" else "Pole"
            }
        }

        return text.take(18).trim().ifEmpty { "Pole" }
    }

    private val FIELD_DISPLAY_PHRASES = mapOf(
        "vin" to "VIN",
        "numer vin" to "VIN",
        "n numer vin" to "VIN",
        "e" to "VIN",
        "e vin" to "VIN",
        "numer identyfikacyjny pojazdu" to "VIN",
        "pesel" to "PESEL",
        "numer pesel" to "PESEL",
        "numer rejestracyjny" to "Nr rejestracyjny",
        "nr rejestracyjny" to "Nr rejestracyjny",
        "nr rej" to "Nr rejestracyjny",
        "n rej" to "Nr rejestracyjny",
        "a" to "Nr rejestracyjny",
        "tablica rejestracyjna" to "Nr rejestracyjny",
        "nazwisko" to "Nazwisko",
        "c1.1" to "Nazwisko",
        "c.1.1" to "Nazwisko",
        "nazwisko surname" to "Nazwisko",
        "imie" to "Imię",
        "imiona" to "Imię",
        "c1.2" to "Imię",
        "c.1.2" to "Imię",
        "imie i nazwisko" to "Imię/nazwisko",
        "adres" to "Adres",
        "c1.3" to "Adres",
        "c.1.3" to "Adres",
        "adres zamieszkania" to "Adres",
        "data urodzenia" to "Data urodzenia",
        "data pierwszej rejestracji" to "Data rej.",
        "marka" to "Marka",
        "d.1" to "Marka",
        "d1" to "Marka",
        "model" to "Model",
        "d.3" to "Model",
        "d3" to "Model",
        "marka model" to "Marka/model",
        "numer dowodu" to "Nr dokumentu",
        "seria i numer" to "Seria i numer",
        "wlasciciel" to "Właściciel",
        "wlaściciel" to "Właściciel",
        "nip" to "NIP",
        "regon" to "REGON",
        "rok produkcji" to "Rok prod.",
        "pojemnosc silnika" to "Poj. silnika",
        "moc" to "Moc",
        "masa" to "Masa",
    )

    private fun isLabelElement(text: String): Boolean {
        val t = text.trim().trimEnd(':', '：', '.')
        if (t.length < 2) return true
        return isPureFieldLabel(t)
    }

    private fun padRect(rect: RectF, px: Float = 3f): RectF =
        RectF(rect.left - px, rect.top - px, rect.right + px, rect.bottom + px)

    /** Łączy regiony bez duplikatów (IoU > 50%). */
    fun mergeRegions(existing: List<RedactionRegion>, fresh: List<RedactionRegion>): List<RedactionRegion> {
        val faces = existing.filter { it.type == RegionType.FACE }
        val kept = existing.filter { it.type != RegionType.FACE }.toMutableList()
        for (nr in fresh) {
            if (kept.none { regionIoU(it.rect, nr.rect) > 0.5f }) kept.add(nr)
        }
        return faces + kept
    }

    private fun regionIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Z linii „NAZWISKO SURNAME KOWALSKI” zwraca resztę po znanym prefiksie etykiety. */
    private fun peelFieldLabelPrefix(text: String): Pair<Boolean, String> {
        val folded = foldLabel(text)
        val phrase = FIELD_LABEL_PHRASES
            .filter { folded == it || folded.startsWith("$it ") || folded.startsWith("$it/") }
            .maxByOrNull { it.length }
            ?: return false to text
        if (folded == phrase) return true to ""
        val labelWords = phrase.split(' ').size
        val origWords = text.trim().split(Regex("""\s+"""))
        if (origWords.size <= labelWords) return true to ""
        return false to origWords.drop(labelWords).joinToString(" ")
    }

    private fun foldLabel(s: String): String {
        val map = mapOf(
            'ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
            'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z',
        )
        return s.trim().trimEnd(':', '.', '–', '-')
            .lowercase()
            .map { map[it] ?: it }
            .joinToString("")
            .replace(Regex("""\s+"""), " ")
    }

    private val FIELD_LABEL_PHRASES = setOf(
        "imie i nazwisko", "nazwisko surname", "surname nazwisko", "nazwisko",
        "imie", "imiona", "imiona given names", "given names", "given name",
        "nazwisko rodowe", "name", "names",
        "data urodzenia", "date of birth", "miejsce urodzenia", "place of birth",
        "pesel", "numer pesel", "nip", "regon",
        "seria i numer", "seria i nr", "seria", "numer", "nr dowodu",
        "dowod osobisty", "numer dowodu", "document no", "document number",
        "obywatelstwo", "nationality", "plec", "sex",
        "adres zameldowania", "adres zamieszkania", "adres", "address",
        "data wydania", "date of issue", "organ wydajacy", "termin waznosci",
        "date of expiry", "expiry date",
        "wzrost", "height", "kolor oczu", "colour of eyes", "color of eyes",
        "numer can", "can", "identity card", "dowod",
        "imie ojca", "imie matki", "rodzice", "parents names",
        "rzeczpospolita polska", "republic of poland",
    )

    private val FIELD_LABEL_WORDS = setOf(
        "imie", "nazwisko", "imiona", "data", "urodzenia", "urodzenie", "miejsce",
        "pesel", "nip", "regon", "seria", "numer", "nr", "dowodu", "dowod", "osobisty",
        "obywatelstwo", "plec", "adres", "zameldowania", "zamieszkania", "wydania",
        "organ", "wydajacy", "termin", "waznosci", "wzrost", "kolor", "oczu", "can",
        "rodowe", "ojca", "matki", "rodzice", "i",
        "surname", "given", "names", "name", "birth", "place", "of", "date",
        "sex", "nationality", "address", "height", "colour", "color", "eyes",
        "document", "no", "number", "issue", "expiry", "identity", "card",
        "republic", "poland", "polska", "polish", "rzeczpospolita", "parents",
    )

    private fun isPureFieldLabel(text: String): Boolean {
        val folded = foldLabel(text)
        if (folded.isEmpty()) return true
        if (FIELD_LABEL_PHRASES.contains(folded)) return true
        if (text.any { it.isDigit() }) return false
        val words = folded.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        return words.all { it in FIELD_LABEL_WORDS }
    }

    // Wykryj twarze + PII w tekście. EXIF rotacja naprawiona przed wywołaniem (w ShareTargetActivity).
    suspend fun detectFacesAndTextAsRegions(
        bitmap: Bitmap,
        userDict: List<Pair<String, String>> = emptyList()
    ): List<RedactionRegion> =
        detectFacesAsRegions(bitmap) + detectTextLinesAsRegions(bitmap, userDict)

    fun applyRedactions(source: Bitmap, regions: List<RedactionRegion>): Bitmap {
        val result = ensureSoftwareCopy(source)
        val canvas = Canvas(result)
        val blurred = regions.count { it.isBlurred }
        DebugLogBuffer.log(
            "ApplyRedact",
            "start src=${source.width}x${source.height} cfg=${source.config} → result=${result.width}x${result.height} blurred=$blurred"
        )
        regions.filter { it.isBlurred }.forEach { region ->
            try {
                when (region.type) {
                    // Czarny prostokąt — pixelate na części urządzeń nie renderował się w Compose
                    RegionType.FACE   -> fillBlackRegion(canvas, expandFaceRect(region.rect, result))
                    RegionType.MANUAL -> fillBlackRegion(canvas, region.rect)
                }
            } catch (e: Exception) {
                DebugLogBuffer.log("ApplyRedact", "FAIL ${region.type} ${region.rect}: ${e.message}")
                fillBlackRegion(canvas, region.rect)
            }
        }
        DebugLogBuffer.log("ApplyRedact", "done ${result.width}x${result.height}")
        return result
    }

    /** HARDWARE / immutable → mutable ARGB_8888 (createBitmap w blur wymaga software bitmap). */
    private fun ensureSoftwareCopy(source: Bitmap): Bitmap {
        if (source.config == Bitmap.Config.HARDWARE) {
            DebugLogBuffer.log("ApplyRedact", "HARDWARE bitmap → copy ARGB_8888")
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("copy HARDWARE→ARGB failed")
        }
        if (!source.isMutable || source.config != Bitmap.Config.ARGB_8888) {
            return source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("copy→ARGB failed")
        }
        return source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("copy mutable failed")
    }

    private fun expandFaceRect(rect: RectF, bitmap: Bitmap): RectF {
        val padX = rect.width() * 0.12f
        val padY = rect.height() * 0.12f
        return RectF(
            (rect.left - padX).coerceAtLeast(0f),
            (rect.top - padY).coerceAtLeast(0f),
            (rect.right + padX).coerceAtMost(bitmap.width.toFloat()),
            (rect.bottom + padY).coerceAtMost(bitmap.height.toFloat())
        )
    }

    @Suppress("unused")
    private fun blurRegion(canvas: Canvas, bitmap: Bitmap, rect: RectF) {
        val left   = rect.left.coerceIn(0f, bitmap.width.toFloat()).toInt()
        val top    = rect.top.coerceIn(0f, bitmap.height.toFloat()).toInt()
        val right  = rect.right.coerceIn(0f, bitmap.width.toFloat()).toInt()
        val bottom = rect.bottom.coerceIn(0f, bitmap.height.toFloat()).toInt()
        val w = right - left; val h = bottom - top
        if (w <= 0 || h <= 0) {
            DebugLogBuffer.log("BlurRegion", "SKIP w=$w h=$h bmp=${bitmap.width}x${bitmap.height} rect=$rect")
            return
        }
        DebugLogBuffer.log("BlurRegion", "Pixelate face: left=$left top=$top w=$w h=$h cfg=${bitmap.config}")

        val crop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(crop).drawBitmap(
            bitmap,
            Rect(left, top, right, bottom),
            Rect(0, 0, w, h),
            null
        )

        var pass = crop
        repeat(BLUR_PASSES) {
            val sw = maxOf(1, w / BLUR_DIVISOR)
            val sh = maxOf(1, h / BLUR_DIVISOR)
            val small = Bitmap.createScaledBitmap(pass, sw, sh, false)
            if (pass !== crop) pass.recycle()
            pass = Bitmap.createScaledBitmap(small, w, h, false)
            small.recycle()
        }
        canvas.drawBitmap(pass, left.toFloat(), top.toFloat(), null)
        if (pass !== crop) pass.recycle()
        crop.recycle()
        DebugLogBuffer.log("BlurRegion", "drawBitmap OK at $left,$top")
    }

    // Tekst/podpis/pieczątka: solid czarny prostokąt — standard prawny (FOIA, RODO redakcja)
    private fun fillBlackRegion(canvas: Canvas, rect: RectF) {
        canvas.drawRect(rect, blackPaint)
    }

    fun saveToCache(bitmap: Bitmap, context: Context): Uri {
        val file = writeRedactedJpeg(bitmap, context)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun encodeJpeg(bitmap: Bitmap, quality: Int = 90): ByteArray {
        java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            return out.toByteArray()
        }
    }

    private fun redactedDir(context: Context): File =
        File(context.cacheDir, "redacted_images").also { it.mkdirs() }

    private fun writeRedactedJpeg(bitmap: Bitmap, context: Context): File {
        val file = File(redactedDir(context), "redacted_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file
    }

    /** Usuwa plik cache powiązany z URI FileProvider (po share). */
    fun deleteRedactedUri(context: Context, uri: Uri) {
        val name = uri.lastPathSegment ?: return
        File(redactedDir(context), name).takeIf { it.exists() }?.delete()
    }

    /** Kasuje pliki starsze niż [maxAgeMs] (domyślnie 5 min) — nie w trakcie aktywnego choosera. */
    fun purgeStaleRedactedImages(context: Context, maxAgeMs: Long = 5 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        redactedDir(context).listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }
}
