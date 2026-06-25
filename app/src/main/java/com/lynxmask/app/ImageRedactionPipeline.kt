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
    val isBlurred: Boolean = true
)

object ImageRedactionPipeline {

    // Pixelate blur — zachowane na przyszłość; twarze maskowane fillBlackRegion (Compose)
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
                RedactionRegion(rect = RectF(face.boundingBox), type = RegionType.FACE)
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
            val piiLines = result.textBlocks.flatMap { block ->
                block.lines.flatMap { line ->
                    val raw = line.text.trim()
                    val textForEngine = extractPiiCheckText(raw) ?: return@flatMap emptyList()
                    val norm = OcrNormalizer.normalize(textForEngine)
                    val engineResult = PseudonymEngine.pseudonymize(norm.normalizedText, userDictionary = userDict)
                    if (engineResult.tokenMap.isEmpty()) return@flatMap emptyList()
                    DebugLogBuffer.log("TextPii", "PII: \"$raw\" → ${engineResult.tokenMap.keys}")
                    val tokenValues = engineResult.tokenMap.values.map { it.trim() }.filter { it.length >= 2 }
                    maskBoxesForLine(line, tokenValues).map { rect ->
                        RedactionRegion(rect = rect, type = RegionType.MANUAL, isBlurred = true)
                    }
                }
            }
            DebugLogBuffer.log("TextDetect", "PII linii: ${piiLines.size}/${result.textBlocks.sumOf { it.lines.size }}")
            piiLines
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

    /** Maskuje tylko elementy OCR z danymi — nie całą linię (etykiety zostają widoczne). */
    private fun maskBoxesForLine(
        line: com.google.mlkit.vision.text.Text.Line,
        tokenValues: List<String>
    ): List<RectF> {
        val elements = line.elements
        if (elements.isEmpty()) {
            return line.boundingBox?.let { listOf(padRect(RectF(it))) } ?: emptyList()
        }

        val labelElements = elements.filter { isLabelElement(it.text) }
        val dataElements = elements.filter { !isLabelElement(it.text) }

        fun elemMatchesPii(text: String): Boolean {
            val et = text.trim()
            if (et.length < 2 || isLabelElement(et)) return false
            return tokenValues.any { tv ->
                et.equals(tv, ignoreCase = true) ||
                    (tv.length >= 3 && et.contains(tv, ignoreCase = true)) ||
                    (et.length >= 3 && tv.contains(et, ignoreCase = true))
            }
        }

        val matched = (if (dataElements.isNotEmpty()) dataElements else elements)
            .filter { elemMatchesPii(it.text) }

        if (matched.isNotEmpty()) {
            return matched.mapNotNull { it.boundingBox?.let { b -> padRect(RectF(b)) } }
        }

        // Imię/nazwisko w kilku elementach — tylko gdy linia ma max 4 elementy danych
        if (dataElements.isNotEmpty() && dataElements.size <= 4) {
            return dataElements.mapNotNull { it.boundingBox?.let { b -> padRect(RectF(b)) } }
        }

        // Ostatnia deska: elementy z cyframi (PESEL, data, nr dowodu)
        val numeric = elements.filter { it.text.any { ch -> ch.isDigit() } }
        if (numeric.isNotEmpty()) {
            return numeric.mapNotNull { it.boundingBox?.let { b -> padRect(RectF(b)) } }
        }

        return emptyList()
    }

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
