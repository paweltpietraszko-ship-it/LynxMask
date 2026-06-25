package com.lynxmask.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

    // Iteracje blur dla twarzy: 3× scale do 1/12 + scale z powrotem (Gaussian-like, smooth)
    private const val BLUR_PASSES = 3
    private const val BLUR_DIVISOR = 12

    private val blackPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    suspend fun detectFacesAsRegions(bitmap: Bitmap): List<RedactionRegion> {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.01f)
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
    // ALL-CAPS linie konwertowane na Title Case przed PseudonymEngine — NameEngine wymaga Caps.
    suspend fun detectTextLinesAsRegions(bitmap: Bitmap): List<RedactionRegion> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val piiLines = result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val box = line.boundingBox ?: return@mapNotNull null
                    val raw = line.text.trim()
                    if (raw.length < 3) return@mapNotNull null
                    // Dowody osobiste: tekst CAPS → Title Case żeby NameEngine wykrył imię/nazwisko
                    val textForEngine = if (raw.length > 3 && raw == raw.uppercase()) {
                        raw.split(" ").joinToString(" ") { w ->
                            if (w.isEmpty()) w else w[0] + w.drop(1).lowercase()
                        }
                    } else raw
                    val norm = OcrNormalizer.normalize(textForEngine)
                    val engineResult = PseudonymEngine.pseudonymize(norm.normalizedText)
                    if (engineResult.tokenMap.isNotEmpty()) {
                        DebugLogBuffer.log("TextPii", "PII: \"$raw\" → ${engineResult.tokenMap.keys}")
                        RedactionRegion(rect = RectF(box), type = RegionType.MANUAL, isBlurred = true)
                    } else null
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

    // Wykryj PII w tekście. Face Detection pominięte — zdjęcia legitymacyjne (dowód, paszport)
    // nie są wykrywane przez ML Kit (4-5s oczekiwania, 0 wyników). User zaznacza twarz ręcznie.
    suspend fun detectFacesAndTextAsRegions(bitmap: Bitmap): List<RedactionRegion> =
        detectTextLinesAsRegions(bitmap)

    fun applyRedactions(source: Bitmap, regions: List<RedactionRegion>): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        regions.filter { it.isBlurred }.forEach { region ->
            when (region.type) {
                RegionType.FACE   -> blurRegion(canvas, source, region.rect)
                RegionType.MANUAL -> fillBlackRegion(canvas, region.rect)
            }
        }
        return result
    }

    // Twarze: iteracyjny Gaussian-like blur (wygląda jak Apple/Google — gładko, profesjonalnie)
    private fun blurRegion(canvas: Canvas, source: Bitmap, rect: RectF) {
        val left   = rect.left.coerceIn(0f, source.width.toFloat()).toInt()
        val top    = rect.top.coerceIn(0f, source.height.toFloat()).toInt()
        val right  = rect.right.coerceIn(0f, source.width.toFloat()).toInt()
        val bottom = rect.bottom.coerceIn(0f, source.height.toFloat()).toInt()
        val w = right - left; val h = bottom - top
        if (w <= 0 || h <= 0) return

        var pass = Bitmap.createBitmap(source, left, top, w, h)
        repeat(BLUR_PASSES) {
            val sw = maxOf(1, pass.width / BLUR_DIVISOR)
            val sh = maxOf(1, pass.height / BLUR_DIVISOR)
            val small = Bitmap.createScaledBitmap(pass, sw, sh, true)
            pass.recycle()
            pass = Bitmap.createScaledBitmap(small, w, h, true)
            small.recycle()
        }
        canvas.drawBitmap(pass, left.toFloat(), top.toFloat(), null)
        pass.recycle()
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
