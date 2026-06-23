package com.lynxmask.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
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

    // 8x scale-down + scale-up bez filtrowania = efekt pixelizacji
    private const val PIXELATE_SCALE = 8

    suspend fun detectFacesAsRegions(bitmap: Bitmap): List<RedactionRegion> {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.05f)
            .build()
        val detector = FaceDetection.getClient(options)
        return try {
            val result = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
            DebugLogBuffer.log("FaceDetect", "Wykryto ${result.size} twarzy")
            result.map { face ->
                RedactionRegion(
                    rect = RectF(face.boundingBox),
                    type = RegionType.FACE
                )
            }
        } catch (e: Exception) {
            DebugLogBuffer.log("FaceDetect", "BŁĄD: ${e.message}")
            emptyList()
        } finally {
            detector.close()
        }
    }

    fun applyRedactions(source: Bitmap, regions: List<RedactionRegion>): Bitmap {
        val result = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        regions.filter { it.isBlurred }.forEach { region ->
            pixelateRegion(canvas, result, region.rect)
        }
        return result
    }

    private fun pixelateRegion(canvas: Canvas, bitmap: Bitmap, rect: RectF) {
        val left   = rect.left.coerceIn(0f, bitmap.width.toFloat()).toInt()
        val top    = rect.top.coerceIn(0f, bitmap.height.toFloat()).toInt()
        val right  = rect.right.coerceIn(0f, bitmap.width.toFloat()).toInt()
        val bottom = rect.bottom.coerceIn(0f, bitmap.height.toFloat()).toInt()
        if (right <= left || bottom <= top) return

        val w = right - left
        val h = bottom - top
        val scaledW = maxOf(1, w / PIXELATE_SCALE)
        val scaledH = maxOf(1, h / PIXELATE_SCALE)

        val region    = Bitmap.createBitmap(bitmap, left, top, w, h)
        val small     = Bitmap.createScaledBitmap(region, scaledW, scaledH, false)
        val pixelated = Bitmap.createScaledBitmap(small, w, h, false)
        region.recycle()
        small.recycle()

        canvas.drawBitmap(pixelated, left.toFloat(), top.toFloat(), null)
        pixelated.recycle()
    }

    fun saveToCache(bitmap: Bitmap, context: Context): Uri {
        val dir = File(context.cacheDir, "redacted_images")
        dir.mkdirs()
        val file = File(dir, "redacted_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
