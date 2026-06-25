package com.lynxmask.app

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.KeyboardType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private data class OcrSuggestion(val text: String)

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
    var ocrSuggestion by remember { mutableStateOf<OcrSuggestion?>(null) }
    var showAddWordDialog by remember { mutableStateOf(false) }
    var addWordInput by remember { mutableStateOf("") }

    // Podgląd z faktyczną redakcją — osobny ImageBitmap wymusza odświeżenie w Compose
    var previewImage by remember(bitmap) { mutableStateOf(bitmap.asImageBitmap()) }
    var isPreviewGenerating by remember { mutableStateOf(false) }
    val regionPreviewKey = remember(regions) {
        regions.joinToString("|") { "${it.id}:${it.isBlurred}:${it.rect.left.toInt()},${it.rect.top.toInt()},${it.rect.right.toInt()},${it.rect.bottom.toInt()}" }
    }

    LaunchedEffect(regionPreviewKey) {
        isPreviewGenerating = true
        val redacted = withContext(Dispatchers.Default) {
            try {
                ImageRedactionPipeline.applyRedactions(bitmap, regions)
            } catch (e: Exception) {
                DebugLogBuffer.log("ImageRedact", "Preview BŁĄD: ${e.message}")
                bitmap
            }
        }
        previewImage = redacted.asImageBitmap()
        isPreviewGenerating = false
    }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var userZoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var dragStartBitmap by remember { mutableStateOf<Offset?>(null) }
    var dragCurrentBitmap by remember { mutableStateOf<Offset?>(null) }
    var dragStartScreen by remember { mutableStateOf<Offset?>(null) }
    var isDragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    fun viewport(): PreviewViewport = PreviewViewport(
        boxSize = boxSize,
        bitmapWidth = bitmap.width,
        bitmapHeight = bitmap.height,
        zoom = userZoom,
        pan = panOffset
    )

    fun clampPan(newPan: Offset, zoom: Float): Offset {
        val vp = viewport().copy(zoom = zoom, pan = newPan)
        return vp.clampPan(newPan)
    }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newZoom = (userZoom * zoomChange).coerceIn(1f, 4f)
        userZoom = newZoom
        panOffset = if (newZoom > 1f) clampPan(panOffset + panChange, newZoom) else Offset.Zero
    }

    fun toggleRegionAtScreen(screenX: Float, screenY: Float) {
        if (boxSize == IntSize.Zero) return
        val vp = viewport()
        val minTouchManual = with(density) { if (userZoom > 1.15f) 40.dp.toPx() else 64.dp.toPx() }
        val minTouchFace = with(density) { 48.dp.toPx() }
        // Przy powiększeniu bez „magnesu” — trafiasz dokładnie w pasek, nie sąsiada
        val snapRadius = if (userZoom > 1.15f) 0f else with(density) { 72.dp.toPx() }
        val hit = findRegionAtScreen(
            regions, screenX, screenY,
            vp.effectiveScale, vp.imageLeft, vp.imageTop,
            minTouchManual, minTouchFace, snapRadius
        ) ?: return
        regions = regions.map {
            if (it.id == hit.id) it.copy(isBlurred = !it.isBlurred) else it
        }
        DebugLogBuffer.log(
            "ImageRedact",
            "Toggle ${hit.type} ${hit.id.take(8)}… → blurred=${!hit.isBlurred} (zoom=${"%.1f".format(userZoom)})"
        )
    }

    fun screenToBitmap(x: Float, y: Float): Offset = viewport().screenToBitmap(x, y)

    Column(modifier = Modifier.fillMaxSize()) {

        // Nagłówek
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Sprawdź i wyślij", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Uszczypnij dwoma palcami, by powiększyć i przesunąć. Przy powiększeniu stuknij w pasek — trafiasz precyzyjnie. Dwa stuknięcia = reset zoomu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }

        // Podgląd z zoom — pinch (2 palce) + przeciąganie przy powiększeniu
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { boxSize = it }
                .clipToBounds()
                .transformable(state = transformableState)
        ) {
            if (boxSize != IntSize.Zero) {
                val vp = viewport()
                val imageScreenW = bitmap.width * vp.effectiveScale
                val imageScreenH = bitmap.height * vp.effectiveScale
                val imageLeft = boxSize.width / 2f + panOffset.x - imageScreenW / 2f
                val imageTop = boxSize.height / 2f + panOffset.y - imageScreenH / 2f
                val imageWidthDp = with(density) { imageScreenW.toDp() }
                val imageHeightDp = with(density) { imageScreenH.toDp() }

                Box(
                    Modifier
                        .offset {
                            androidx.compose.ui.unit.IntOffset(
                                imageLeft.roundToInt(),
                                imageTop.roundToInt()
                            )
                        }
                        .size(imageWidthDp, imageHeightDp)
                ) {
                    key(regionPreviewKey) {
                        Image(
                            bitmap = previewImage,
                            contentDescription = "Podgląd z redakcją",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Canvas(Modifier.fillMaxSize()) {
                        val sx = size.width / bitmap.width.toFloat()
                        val sy = size.height / bitmap.height.toFloat()
                        regions.forEach { region ->
                            val l = region.rect.left * sx
                            val t = region.rect.top * sy
                            val w = region.rect.width() * sx
                            val h = region.rect.height() * sy
                            if (region.isBlurred) {
                                drawRect(
                                    color = if (region.type == RegionType.FACE)
                                        Color(0xFF1565C0) else Color(0xFF6A1B9A),
                                    topLeft = Offset(l, t),
                                    size = Size(w, h),
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            } else {
                                drawRect(
                                    color = Color(0xFFE65100),
                                    topLeft = Offset(l, t),
                                    size = Size(w, h),
                                    style = Stroke(width = 3.dp.toPx())
                                )
                                drawRect(
                                    color = Color(0x33E65100),
                                    topLeft = Offset(l, t),
                                    size = Size(w, h)
                                )
                            }
                        }
                        if (isDragging && userZoom <= 1.05f) {
                            val start = dragStartBitmap ?: return@Canvas
                            val end = dragCurrentBitmap ?: return@Canvas
                            drawRect(
                                color = Color(0xFF6A1B9A),
                                topLeft = Offset(minOf(start.x, end.x) * sx, minOf(start.y, end.y) * sy),
                                size = Size(
                                    kotlin.math.abs(end.x - start.x) * sx,
                                    kotlin.math.abs(end.y - start.y) * sy
                                ),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
            }

            if (isPreviewGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).align(Alignment.Center),
                    strokeWidth = 3.dp
                )
            }

            if (userZoom > 1.05f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "${"%.1f".format(userZoom)}×",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = { userZoom = 1f; panOffset = Offset.Zero },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) { Text("Reset", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(regionPreviewKey, boxSize, userZoom) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (userZoom > 1.05f) {
                                    userZoom = 1f
                                    panOffset = Offset.Zero
                                } else {
                                    userZoom = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(regionPreviewKey, boxSize, userZoom) {
                        val tapSlop = 28.dp.toPx()
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStartScreen = offset
                                dragStartBitmap = screenToBitmap(offset.x, offset.y)
                                dragCurrentBitmap = dragStartBitmap
                                isDragging = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragCurrentBitmap = screenToBitmap(change.position.x, change.position.y)
                                val startScr = dragStartScreen ?: return@detectDragGestures
                                if (userZoom > 1.05f) {
                                    panOffset = clampPan(panOffset + dragAmount, userZoom)
                                    return@detectDragGestures
                                }
                                val dx = change.position.x - startScr.x
                                val dy = change.position.y - startScr.y
                                if (!isDragging && dx * dx + dy * dy > tapSlop * tapSlop) {
                                    isDragging = true
                                }
                            },
                            onDragEnd = {
                                val startScr = dragStartScreen ?: return@detectDragGestures
                                val start = dragStartBitmap ?: return@detectDragGestures
                                val end = dragCurrentBitmap ?: start
                                val screenMoved = kotlin.math.hypot(
                                    (end.x - start.x).toDouble() * viewport().effectiveScale,
                                    (end.y - start.y).toDouble() * viewport().effectiveScale
                                ).toFloat()

                                if (userZoom > 1.05f) {
                                    if (screenMoved < tapSlop) {
                                        toggleRegionAtScreen(startScr.x, startScr.y)
                                    }
                                } else {
                                    val newRect = RectF(
                                        minOf(start.x, end.x).coerceIn(0f, bitmap.width.toFloat()),
                                        minOf(start.y, end.y).coerceIn(0f, bitmap.height.toFloat()),
                                        maxOf(start.x, end.x).coerceIn(0f, bitmap.width.toFloat()),
                                        maxOf(start.y, end.y).coerceIn(0f, bitmap.height.toFloat())
                                    )
                                    val drewNewRegion = isDragging && newRect.width() > 10 && newRect.height() > 10
                                    if (drewNewRegion) {
                                        regions = regions + RedactionRegion(rect = newRect, type = RegionType.MANUAL)
                                        scope.launch(Dispatchers.Default) {
                                            val suggestion = ocrRegionForDictionary(bitmap, newRect)
                                            if (suggestion != null) {
                                                withContext(Dispatchers.Main) { ocrSuggestion = suggestion }
                                            }
                                        }
                                    } else {
                                        toggleRegionAtScreen(startScr.x, startScr.y)
                                    }
                                }
                                dragStartBitmap = null
                                dragCurrentBitmap = null
                                dragStartScreen = null
                                isDragging = false
                            },
                            onDragCancel = {
                                dragStartBitmap = null
                                dragCurrentBitmap = null
                                dragStartScreen = null
                                isDragging = false
                            }
                        )
                    }
            )
        }

        // Dialog — dodaj słowo do słownika ręcznie
        if (showAddWordDialog) {
            AlertDialog(
                onDismissRequest = { showAddWordDialog = false; addWordInput = "" },
                title = { Text("Dodaj do ochrony") },
                text = {
                    Column {
                        Text(
                            "Słowo będzie zawsze maskowane w kolejnych dokumentach.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = addWordInput,
                            onValueChange = { addWordInput = it },
                            label = { Text("Imię, nazwisko lub firma") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        )
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            val word = addWordInput.trim()
                            showAddWordDialog = false; addWordInput = ""
                            if (word.isNotEmpty()) scope.launch(Dispatchers.IO) {
                                UserDictionary.add(context, word, "FIRMA")
                                DebugLogBuffer.log("ImageRedact", "Slownik+: '$word' → FIRMA")
                                val newText = ImageRedactionPipeline.detectTextLinesAsRegions(bitmap, UserDictionary.entries)
                                withContext(Dispatchers.Main) {
                                    regions = ImageRedactionPipeline.mergeRegions(regions, newText)
                                }
                            }
                        }, enabled = addWordInput.isNotBlank()) { Text("FIRMA") }
                        Button(onClick = {
                            val word = addWordInput.trim()
                            showAddWordDialog = false; addWordInput = ""
                            if (word.isNotEmpty()) scope.launch(Dispatchers.IO) {
                                UserDictionary.add(context, word, "OSOBA")
                                DebugLogBuffer.log("ImageRedact", "Slownik+: '$word' → OSOBA")
                                val newText = ImageRedactionPipeline.detectTextLinesAsRegions(bitmap, UserDictionary.entries)
                                withContext(Dispatchers.Main) {
                                    regions = ImageRedactionPipeline.mergeRegions(regions, newText)
                                }
                            }
                        }, enabled = addWordInput.isNotBlank()) { Text("OSOBA") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddWordDialog = false; addWordInput = "" }) {
                        Text("Anuluj")
                    }
                }
            )
        }

        // Przyciski + ostrzeżenie odkrytych danych
        Column(modifier = Modifier.padding(12.dp)) {
            val blurredCount  = regions.count { it.isBlurred }
            val revealedCount = regions.count { !it.isBlurred }

            // Sugestia słownikowa po ręcznym zaznaczeniu obszaru
            val suggestion = ocrSuggestion
            if (suggestion != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "💡 Wykryto tekst w zaznaczonym obszarze:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "\"${suggestion.text}\"",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Dodać do słownika? Będzie chronione w kolejnych dokumentach.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { ocrSuggestion = null },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                            ) { Text("Pomiń", style = MaterialTheme.typography.labelSmall) }
                            Button(
                                onClick = {
                                    val txt = suggestion.text
                                    ocrSuggestion = null
                                    scope.launch(Dispatchers.IO) {
                                        UserDictionary.add(context, txt, "FIRMA")
                                        DebugLogBuffer.log("ImageRedact", "Słownik: '$txt' → FIRMA (bez re-scan)")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                            ) { Text("FIRMA", style = MaterialTheme.typography.labelSmall) }
                            Button(
                                onClick = {
                                    val txt = suggestion.text
                                    ocrSuggestion = null
                                    scope.launch(Dispatchers.IO) {
                                        UserDictionary.add(context, txt, "OSOBA")
                                        DebugLogBuffer.log("ImageRedact", "Słownik: '$txt' → OSOBA (bez re-scan)")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                            ) { Text("OSOBA", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

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

                OutlinedButton(
                    onClick = { showAddWordDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing
                ) { Text("+ Słowo") }

                Button(
                    onClick = {
                        isProcessing = true
                        scope.launch(Dispatchers.IO) {
                            val uri = ImageRedactionPipeline.saveToCache(
                                ImageRedactionPipeline.applyRedactions(bitmap, regions),
                                context
                            )
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
                        Text(if (blurredCount > 0) "Udostępnij zamaskowany obraz" else "Udostępnij bez redakcji")
                    }
                }
            }
        }
    }
}

// OCR wyciętego obszaru z oryginału — dla sugestii słownikowej po ręcznym zaznaczeniu.
// Zwraca null gdy tekst za krótki, nieczytelny lub wygląda jak podpis odręczny (śmieci OCR).
private suspend fun ocrRegionForDictionary(bitmap: Bitmap, rect: RectF): OcrSuggestion? =
    withContext(Dispatchers.Default) {
        try {
            val left   = rect.left.coerceIn(0f, bitmap.width.toFloat()).toInt()
            val top    = rect.top.coerceIn(0f, bitmap.height.toFloat()).toInt()
            val width  = (rect.right.coerceIn(0f, bitmap.width.toFloat()) - left).toInt()
            val height = (rect.bottom.coerceIn(0f, bitmap.height.toFloat()) - top).toInt()
            if (width < 10 || height < 10) return@withContext null

            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val result = recognizer.process(InputImage.fromBitmap(cropped, 0)).await()
                val conf = calcOcrConfidence(result)
                val text = result.text.trim().replace(Regex("""\s+"""), " ")
                // Odrzucamy: zbyt krótki tekst lub niski confidence (podpis odręczny = śmieci OCR)
                if (conf < 0.55f || text.length < 4) return@withContext null
                OcrSuggestion(text = text)
            } finally {
                recognizer.close()
                cropped.recycle()
            }
        } catch (e: Exception) {
            DebugLogBuffer.log("ImageRedact", "OCR sugestia BŁĄD: ${e.message}")
            null
        }
    }

private data class PreviewViewport(
    val boxSize: IntSize,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val zoom: Float,
    val pan: Offset
) {
    val baseScale: Float
        get() = if (boxSize == IntSize.Zero) 1f else minOf(
            boxSize.width.toFloat() / bitmapWidth,
            boxSize.height.toFloat() / bitmapHeight
        )

    val effectiveScale: Float get() = baseScale * zoom

    val imageScreenW: Float get() = bitmapWidth * effectiveScale
    val imageScreenH: Float get() = bitmapHeight * effectiveScale

    val imageLeft: Float get() = boxSize.width / 2f + pan.x - imageScreenW / 2f
    val imageTop: Float get() = boxSize.height / 2f + pan.y - imageScreenH / 2f

    fun screenToBitmap(sx: Float, sy: Float): Offset =
        Offset(
            (sx - imageLeft) / effectiveScale.coerceAtLeast(0.001f),
            (sy - imageTop) / effectiveScale.coerceAtLeast(0.001f)
        )

    fun bitmapToScreen(bx: Float, by: Float): Offset =
        Offset(
            bx * effectiveScale + imageLeft,
            by * effectiveScale + imageTop
        )

    fun clampPan(newPan: Offset): Offset {
        if (boxSize == IntSize.Zero || zoom <= 1f) return Offset.Zero
        val maxPanX = maxOf(0f, (imageScreenW - boxSize.width) / 2f + boxSize.width * 0.2f)
        val maxPanY = maxOf(0f, (imageScreenH - boxSize.height) / 2f + boxSize.height * 0.2f)
        return Offset(
            newPan.x.coerceIn(-maxPanX, maxPanX),
            newPan.y.coerceIn(-maxPanY, maxPanY)
        )
    }
}

private fun findRegionAtScreen(
    regions: List<RedactionRegion>,
    screenX: Float,
    screenY: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    minTouchManualPx: Float,
    minTouchFacePx: Float,
    snapRadiusPx: Float
): RedactionRegion? {
    fun screenHitRect(r: RedactionRegion): RectF {
        val sr = RectF(
            r.rect.left * scale + offsetX,
            r.rect.top * scale + offsetY,
            r.rect.right * scale + offsetX,
            r.rect.bottom * scale + offsetY
        )
        val minTouch = if (r.type == RegionType.MANUAL) minTouchManualPx else minTouchFacePx
        val tw = maxOf(sr.width(), minTouch)
        val th = maxOf(sr.height(), minTouch)
        val cx = (sr.left + sr.right) / 2f
        val cy = (sr.top + sr.bottom) / 2f
        return RectF(cx - tw / 2f, cy - th / 2f, cx + tw / 2f, cy + th / 2f)
    }

    val direct = regions
        .filter { screenHitRect(it).contains(screenX, screenY) }
        .minByOrNull { it.rect.width() * it.rect.height() }
    if (direct != null) return direct

    // Palec obok cienkiego paska — wybierz najbliższy region w promieniu snap
    return regions
        .map { r -> r to screenHitRect(r) }
        .map { (r, hit) ->
            val cx = (hit.left + hit.right) / 2f
            val cy = (hit.top + hit.bottom) / 2f
            val dist = kotlin.math.hypot((screenX - cx).toDouble(), (screenY - cy).toDouble()).toFloat()
            Triple(r, dist, r.rect.width() * r.rect.height())
        }
        .filter { it.second <= snapRadiusPx }
        .minWithOrNull(compareBy<Triple<RedactionRegion, Float, Float>> { it.second }.thenBy { it.third })
        ?.first
}
