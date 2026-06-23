package com.lynxmask.app

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageRedactionScreen(
    bitmap: Bitmap,
    initialRegions: List<RedactionRegion>,
    onShare: (android.net.Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var regions by remember { mutableStateOf(initialRegions) }
    var isProcessing by remember { mutableStateOf(false) }

    // Podgląd z faktycznym pixelate blur — aktualizowany przy każdej zmianie regionów
    var previewBitmap by remember { mutableStateOf(bitmap) }
    var isPreviewGenerating by remember { mutableStateOf(false) }

    LaunchedEffect(regions) {
        isPreviewGenerating = true
        previewBitmap = withContext(Dispatchers.Default) {
            ImageRedactionPipeline.applyRedactions(bitmap, regions)
        }
        isPreviewGenerating = false
    }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStartBitmap by remember { mutableStateOf<Offset?>(null) }
    var dragCurrentBitmap by remember { mutableStateOf<Offset?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    fun scaleParams(): Triple<Float, Float, Float> {
        if (boxSize == IntSize.Zero) return Triple(1f, 0f, 0f)
        val scale = minOf(
            boxSize.width.toFloat() / bitmap.width,
            boxSize.height.toFloat() / bitmap.height
        )
        val ox = (boxSize.width - bitmap.width * scale) / 2f
        val oy = (boxSize.height - bitmap.height * scale) / 2f
        return Triple(scale, ox, oy)
    }

    fun screenToBitmap(x: Float, y: Float): Offset {
        val (scale, ox, oy) = scaleParams()
        return Offset((x - ox) / scale, (y - oy) / scale)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // Nagłówek
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Redakcja obrazu", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Stuknij zaznaczony obszar żeby odkryć. Przeciągnij żeby dodać nowy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (initialRegions.isEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Nie wykryto twarzy automatycznie. Przeciągnij żeby ręcznie zaznaczyć obszar.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Podgląd — faktyczny pixelate blur na żywo
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { boxSize = it }
                .pointerInput(regions) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStartBitmap = screenToBitmap(offset.x, offset.y)
                            dragCurrentBitmap = dragStartBitmap
                            isDragging = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragCurrentBitmap = screenToBitmap(change.position.x, change.position.y)
                            val threshold = 8f
                            if (!isDragging &&
                                dragAmount.x * dragAmount.x + dragAmount.y * dragAmount.y > threshold * threshold) {
                                isDragging = true
                            }
                        },
                        onDragEnd = {
                            val start = dragStartBitmap ?: return@detectDragGestures
                            val end   = dragCurrentBitmap ?: start
                            if (!isDragging) {
                                // Tap — toggle blur/odkryj
                                val hit = regions.firstOrNull { r -> r.rect.contains(start.x, start.y) }
                                if (hit != null) {
                                    regions = regions.map {
                                        if (it.id == hit.id) it.copy(isBlurred = !it.isBlurred) else it
                                    }
                                }
                            } else {
                                // Drag — nowy ręczny prostokąt (podpis, pieczątka)
                                val newRect = RectF(
                                    minOf(start.x, end.x).coerceIn(0f, bitmap.width.toFloat()),
                                    minOf(start.y, end.y).coerceIn(0f, bitmap.height.toFloat()),
                                    maxOf(start.x, end.x).coerceIn(0f, bitmap.width.toFloat()),
                                    maxOf(start.y, end.y).coerceIn(0f, bitmap.height.toFloat())
                                )
                                if (newRect.width() > 10 && newRect.height() > 10) {
                                    regions = regions + RedactionRegion(rect = newRect, type = RegionType.MANUAL)
                                }
                            }
                            dragStartBitmap = null
                            dragCurrentBitmap = null
                            isDragging = false
                        },
                        onDragCancel = {
                            dragStartBitmap = null; dragCurrentBitmap = null; isDragging = false
                        }
                    )
                }
        ) {
            // Obraz z faktycznym pixelate blur
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = "Podgląd z redakcją",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // Spinner gdy trwa generowanie podglądu
            if (isPreviewGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).align(Alignment.Center),
                    strokeWidth = 3.dp
                )
            }

            // Overlay — kontury regionów + podgląd nowego prostokąta podczas drag
            Canvas(modifier = Modifier.fillMaxSize()) {
                val (scale, ox, oy) = scaleParams()

                fun bitmapRectToScreen(r: RectF): androidx.compose.ui.geometry.Rect =
                    androidx.compose.ui.geometry.Rect(
                        left   = r.left * scale + ox,
                        top    = r.top * scale + oy,
                        right  = r.right * scale + ox,
                        bottom = r.bottom * scale + oy
                    )

                regions.forEach { region ->
                    val sr = bitmapRectToScreen(region.rect)
                    if (region.isBlurred) {
                        // Kontur zakrytego regionu (blur widoczny na obrazie)
                        drawRect(
                            color = if (region.type == RegionType.FACE)
                                Color(0xFF1565C0) else Color(0xFF6A1B9A),
                            topLeft = Offset(sr.left, sr.top),
                            size = Size(sr.width, sr.height),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    } else {
                        // Odkryty — pomarańczowe ostrzeżenie
                        drawRect(
                            color = Color(0xFFE65100),
                            topLeft = Offset(sr.left, sr.top),
                            size = Size(sr.width, sr.height),
                            style = Stroke(width = 3.dp.toPx())
                        )
                        drawRect(
                            color = Color(0x33E65100),
                            topLeft = Offset(sr.left, sr.top),
                            size = Size(sr.width, sr.height)
                        )
                    }
                }

                // Prostokąt rysowany w trakcie drag (nowy region)
                if (isDragging) {
                    val start = dragStartBitmap ?: return@Canvas
                    val end   = dragCurrentBitmap ?: return@Canvas
                    val l = minOf(start.x, end.x) * scale + ox
                    val t = minOf(start.y, end.y) * scale + oy
                    val r = maxOf(start.x, end.x) * scale + ox
                    val b = maxOf(start.y, end.y) * scale + oy
                    drawRect(
                        color = Color(0xFF6A1B9A),
                        topLeft = Offset(l, t),
                        size = Size(r - l, b - t),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawRect(
                        color = Color(0x226A1B9A),
                        topLeft = Offset(l, t),
                        size = Size(r - l, b - t)
                    )
                }
            }
        }

        // Przyciski + ostrzeżenie odkrytych danych
        Column(modifier = Modifier.padding(12.dp)) {
            val blurredCount  = regions.count { it.isBlurred }
            val revealedCount = regions.count { !it.isBlurred }

            if (revealedCount > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        "⚠ $revealedCount ${if (revealedCount == 1) "obszar odkryty" else "obszary odkryte"} — dane będą widoczne w wysłanym obrazie",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing
                ) { Text("Anuluj") }

                Button(
                    onClick = {
                        isProcessing = true
                        scope.launch(Dispatchers.IO) {
                            val uri = ImageRedactionPipeline.saveToCache(previewBitmap, context)
                            DebugLogBuffer.log(
                                "ImageRedact",
                                "Udostępniono (blur=$blurredCount, odkryto=$revealedCount): $uri"
                            )
                            withContext(Dispatchers.Main) { onShare(uri) }
                        }
                    },
                    modifier = Modifier.weight(2f),
                    enabled = !isProcessing && !isPreviewGenerating
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(if (blurredCount > 0) "Udostępnij bezpiecznie" else "Udostępnij bez redakcji")
                    }
                }
            }
        }
    }
}
