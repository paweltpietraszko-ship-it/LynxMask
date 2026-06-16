package com.lynxmask.app

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BenchmarkService — wykonuje OCR + PseudonymEngine i zapisuje wynik.
 * Plik: app/src/main/java/com/lynxmask/app/BenchmarkService.kt
 * NIE używać w release. Tylko debug/testing.
 */
class BenchmarkService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Ścieżka obliczana z kontekstu — omija EACCES na Android 10+
    private fun benchDir(): File =
        File(getExternalFilesDir(null), "bench").also { it.mkdirs() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fileName = intent?.getStringExtra("file") ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                process(fileName)
            } catch (e: Exception) {
                writeError(e.message ?: "Nieznany błąd")
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun process(fileName: String) {
        val dir = benchDir()
        File(dir, "result.done").delete()
        File(dir, "result.json").delete()

        val inputFile = File(dir, fileName)
        if (!inputFile.exists()) {
            writeError("Brak pliku: ${inputFile.absolutePath}")
            return
        }

        // ── ML Kit OCR ───────────────────────────────────────────────────────
        val bitmap     = BitmapFactory.decodeFile(inputFile.absolutePath)
        val mlImage    = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        val rawOcrText: String = suspendCancellableCoroutine { cont ->
            recognizer.process(mlImage)
                .addOnSuccessListener { result -> cont.resume(result.text) {} }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

        // ── OcrNormalizer + PseudonymEngine ──────────────────────────────────
        val normalized = OcrNormalizer.normalize(rawOcrText)
        val ocrText    = normalized.normalizedText
        val ocrConf    = estimateConf(rawOcrText, bitmap)
        val result     = PseudonymEngine.pseudonymize(ocrText, emptyList())

        // ── Zapis wyniku ─────────────────────────────────────────────────────
        val tokensArray = JSONArray()
        for ((tokenKey, originalValue) in result.tokenMap) {
            val obj = JSONObject()
            obj.put("original", originalValue)
            obj.put("token",    tokenKey)
            obj.put("type",     tokenKey.substringBefore("_"))
            tokensArray.put(obj)
        }

        val json = JSONObject()
        json.put("file",     fileName)
        json.put("ocr_text", ocrText)
        json.put("ocr_conf", ocrConf)
        json.put("tokens",   tokensArray)
        json.put("error",    JSONObject.NULL)

        File(dir, "result.json").writeText(json.toString(2))
        File(dir, "result.done").writeText("ok")
    }

    private fun writeError(msg: String) {
        val dir = benchDir()
        val json = JSONObject()
        json.put("bench_error", msg)
        json.put("tokens", JSONArray())
        File(dir, "result.json").writeText(json.toString(2))
        File(dir, "result.done").writeText("error")
    }

    private fun estimateConf(text: String, bitmap: android.graphics.Bitmap): Double {
        val area    = bitmap.width.toDouble() * bitmap.height
        val density = text.length / (area / 10_000)
        return (density / 1.2 * 100).coerceIn(0.0, 100.0)
    }
}
