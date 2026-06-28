package com.lynxmask.app

// DocumentExtractor.kt — czyste funkcje ekstrakcji tekstu z plików
// Wydzielone z ShareTargetActivity v2.8 — brak zależności od UI/Compose/Activity.
// Wszystkie funkcje są suspend i mogą być wywołane z dowolnego korutynowego kontekstu.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

internal const val MAX_PDF_PAGES = 20
internal const val PDF_RENDER_SCALE = 2

// ─────────────────────────────────────────────────────────────────────────────
// DOCX → tekst
// ─────────────────────────────────────────────────────────────────────────────

internal suspend fun extractTextFromDocx(uri: Uri, context: Context): String =
    withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    // Regex wyciąga <w:t> content LUB marker końca paragrafu (PARA_BREAK).
                    // </w:p> jest jedynym pewnym znacznikiem końca paragrafu w OOXML.
                    val textRegex  = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>|PARA_BREAK""")
                    val breakRegex = Regex("""</w:p>|<w:br[^/]*/?>""")
                    // BUG-DOCX-PARTIAL: przetwarzaj też nagłówki i stopki (ten sam format <w:t>)
                    val docxTargetPattern = Regex("""word/(document|(header|footer)\d*)\.xml""")
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name.matches(docxTargetPattern)) {
                            val xml = zip.readBytes().toString(Charsets.UTF_8)
                            val withBreaks = breakRegex.replace(xml, "PARA_BREAK")
                            textRegex.findAll(withBreaks).forEach { match ->
                                if (match.value == "PARA_BREAK") {
                                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                                } else {
                                    sb.append(match.groupValues[1])
                                }
                            }
                            if (name != "word/document.xml") sb.append("\n")
                            DebugLogBuffer.log("DOCX", "Wyodrębniono z $name (łącznie ${sb.length} znaków)")
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            sb.toString()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace(Regex(""" {2,}"""), " ")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        } catch (e: Exception) {
            DebugLogBuffer.log("DOCX", "BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
            ""
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// OCR z obrazka — zwraca Pair<tekst, confidence?>
// ─────────────────────────────────────────────────────────────────────────────

internal suspend fun ocrFromImageUri(uri: Uri, context: Context): Pair<String, Float?> =
    withContext(Dispatchers.IO) {
        try {
            val bitmap: Bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@withContext "" to null
            try {
                ocrFromBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            DebugLogBuffer.log("OCR", "BŁĄD: ${e.message}")
            "" to null
        }
    }

internal suspend fun ocrFromBitmap(bitmap: Bitmap): Pair<String, Float?> {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val conf = calcOcrConfidence(result).takeIf { it > 0f }
        DebugLogBuffer.log("OCR", "Obraz — ${result.text.length} znaków, conf=${conf?.let { "%.0f%%".format(it * 100) } ?: "N/A"}")
        result.text to conf
    } finally {
        recognizer.close()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// OCR z PDF — zwraca Pair<tekst, średnie confidence?>
// ─────────────────────────────────────────────────────────────────────────────

// confidence uśrednione ze stron z tekstem
internal suspend fun ocrFromPdfUri(
    uri: Uri,
    context: Context,
    onProgress: (String) -> Unit
): Pair<String, Float?> = withContext(Dispatchers.IO) {
    val sb = StringBuilder()
    val pageConfs = mutableListOf<Float>()
    try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val renderer = PdfRenderer(descriptor)
            val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES)
            DebugLogBuffer.log("OCR", "PDF — ${renderer.pageCount} stron, skanuję $pageCount")
            for (i in 0 until pageCount) {
                onProgress("Skanuję stronę ${i + 1} z $pageCount...")
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(
                    page.width * PDF_RENDER_SCALE, page.height * PDF_RENDER_SCALE,
                    Bitmap.Config.ARGB_8888
                )
                Canvas(bitmap).drawColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    val ocr = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                    if (ocr.text.isNotBlank()) {
                        sb.append("── Strona ${i + 1} ──\n${ocr.text}\n\n")
                        val pageConf = calcOcrConfidence(ocr)
                        if (pageConf > 0f) pageConfs.add(pageConf)
                        DebugLogBuffer.log("OCR", "Strona ${i + 1}: ${ocr.text.length} znaków, conf=${if (pageConf > 0f) "%.0f%%".format(pageConf * 100) else "N/A"}")
                    }
                } finally {
                    recognizer.close()
                    bitmap.recycle()
                }
            }
            if (renderer.pageCount > MAX_PDF_PAGES)
                sb.append("\n[LynxMask: ${renderer.pageCount} stron, przeskanowano $MAX_PDF_PAGES]")
            renderer.close()
        }
    } catch (e: Exception) {
        DebugLogBuffer.log("OCR", "BŁĄD PDF: ${e.message}")
    }
    val avgConf = pageConfs.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    sb.toString() to avgConf
}

// ─────────────────────────────────────────────────────────────────────────────
// Wczytanie obrazu z uwzględnieniem EXIF rotacji
// ─────────────────────────────────────────────────────────────────────────────

// BitmapFactory.decodeStream ignoruje EXIF — bez tej funkcji zdjęcia pionowe trafiają do ML Kit
// "na boku" co skutkuje 0 wykrytych twarzy i błędnym OCR.
internal fun loadBitmapExifAware(context: Context, uri: Uri): Bitmap? {
    val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: return null
    val degrees = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) {
        DebugLogBuffer.log("ImageLoad", "EXIF err: ${e.message}")
        0
    }
    if (degrees == 0) return raw
    DebugLogBuffer.log("ImageLoad", "EXIF rotate ${degrees}°")
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { raw.recycle() }
}
