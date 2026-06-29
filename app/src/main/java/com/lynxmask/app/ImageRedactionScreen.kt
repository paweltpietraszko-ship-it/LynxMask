package com.lynxmask.app

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxPrimaryButton
import com.lynxmask.app.ui.components.LynxSecondaryButton
import com.lynxmask.app.ui.components.LynxSuccessButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 8f

/** Odwrotna transformacja: współrzędne ekranu → piksele bitmapy (fit + zoom + przesunięcie). */
private fun screenToBitmapCoords(
    tap: Offset,
    boxSize: IntSize,
    bitmap: Bitmap,
    zoom: Float,
    pan: Offset
): Offset {
    if (boxSize == IntSize.Zero) return Offset.Zero
    val fitScale = minOf(
        boxSize.width.toFloat() / bitmap.width,
        boxSize.height.toFloat() / bitmap.height
    )
    val ox = (boxSize.width - bitmap.width * fitScale) / 2f
    val oy = (boxSize.height - bitmap.height * fitScale) / 2f
    val unzoomed = Offset((tap.x - pan.x) / zoom, (tap.y - pan.y) / zoom)
    return Offset((unzoomed.x - ox) / fitScale, (unzoomed.y - oy) / fitScale)
}

private fun hitRegionAt(
    regions: List<RedactionRegion>,
    bitmapX: Float,
    bitmapY: Float,
    tapSlopPx: Float = 18f
): RedactionRegion? =
    regions.filter { regionContains(it.rect, bitmapX, bitmapY, tapSlopPx) }
        .maxByOrNull { it.rect.width() * it.rect.height() }

private fun regionContains(rect: RectF, x: Float, y: Float, slop: Float): Boolean =
    x >= rect.left - slop && x <= rect.right + slop &&
        y >= rect.top - slop && y <= rect.bottom + slop

@Composable
fun ImageRedactionScreen(
    bitmap: Bitmap,
    initialRegions: List<RedactionRegion>,
    redactionProfile: RedactionProfile = RedactionProfile.GENERAL,
    allowLibrarySave: Boolean = true,
    weakScanWarning: Boolean = false,
    onSaveToLibrary: suspend (Bitmap, String) -> Boolean,
    onShare: (android.net.Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var regions by remember { mutableStateOf(initialRegions) }
    var isProcessing by remember { mutableStateOf(false) }
    var descText by remember { mutableStateOf("") }
    var showAddWordDialog by remember { mutableStateOf(false) }
    var addWordInput by remember { mutableStateOf("") }
    var highlightedRegionId by remember { mutableStateOf<String?>(null) }
    var manualMaskMode by remember { mutableStateOf(false) }
    var dragStartBitmap by remember { mutableStateOf<Offset?>(null) }
    var dragCurrentBitmap by remember { mutableStateOf<Offset?>(null) }

    val baseImage = remember(bitmap) { bitmap.asImageBitmap() }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val zoomState = remember { mutableFloatStateOf(1f) }
    val panState = remember { mutableStateOf(Offset.Zero) }
    val zoom = zoomState.floatValue
    val pan = panState.value

    fun fitScaleParams(): Triple<Float, Float, Float> {
        if (boxSize == IntSize.Zero) return Triple(1f, 0f, 0f)
        val scale = minOf(
            boxSize.width.toFloat() / bitmap.width,
            boxSize.height.toFloat() / bitmap.height
        )
        val ox = (boxSize.width - bitmap.width * scale) / 2f
        val oy = (boxSize.height - bitmap.height * scale) / 2f
        return Triple(scale, ox, oy)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Sprawdź i zapisz", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                if (manualMaskMode) {
                    "Przeciągnij palcem po polu do domaskowania. Powiększ szczypnięciem (2 palce)."
                } else {
                    "Szczypnij (2 palce), aby powiększyć. Dotknij pola — odkryjesz lub zakryjesz. Brakuje maski? Naciśnij „+ Maska”."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            val ocrConf = ImageRedactionPipeline.lastDetectionOcrConfidence
            if (weakScanWarning || (ocrConf != null && ocrConf < 0.68f)) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Słabe zdjęcie (${ocrConf?.let { "${(it * 100).toInt()}% OCR" } ?: "niska pewność"}) — program mógł pominąć pola. " +
                        "Bezpieczniej zrobić nowe zdjęcie; jeśli zostajesz przy tym — domaskuj ręcznie (+ Maska, przeciągnij palcem).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    lineHeight = 16.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { boxSize = it }
                .pointerInput(manualMaskMode) {
                    if (!manualMaskMode) {
                        detectTransformGestures { centroid, panChange, zoomChange, _ ->
                            val prev = zoomState.floatValue
                            val next = (prev * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            if (next <= MIN_ZOOM + 0.001f) {
                                zoomState.floatValue = MIN_ZOOM
                                panState.value = Offset.Zero
                            } else {
                                val oldPan = panState.value
                                panState.value =
                                    (centroid - (centroid - oldPan) * (next / prev)) + panChange
                                zoomState.floatValue = next
                            }
                        }
                    }
                }
                .pointerInput(manualMaskMode, regions, boxSize) {
                    if (manualMaskMode) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStartBitmap = screenToBitmapCoords(
                                    offset, boxSize, bitmap, zoomState.floatValue, panState.value
                                )
                                dragCurrentBitmap = dragStartBitmap
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragCurrentBitmap = screenToBitmapCoords(
                                    change.position, boxSize, bitmap, zoomState.floatValue, panState.value
                                )
                            },
                            onDragEnd = {
                                val start = dragStartBitmap ?: return@detectDragGestures
                                val end = dragCurrentBitmap ?: start
                                val newRect = if (
                                    kotlin.math.abs(end.x - start.x) > 12f ||
                                    kotlin.math.abs(end.y - start.y) > 12f
                                ) {
                                    RectF(
                                        minOf(start.x, end.x).coerceIn(0f, bitmap.width.toFloat()),
                                        minOf(start.y, end.y).coerceIn(0f, bitmap.height.toFloat()),
                                        maxOf(start.x, end.x).coerceIn(0f, bitmap.width.toFloat()),
                                        maxOf(start.y, end.y).coerceIn(0f, bitmap.height.toFloat())
                                    )
                                } else {
                                    val w = bitmap.width * 0.22f
                                    val h = bitmap.height * 0.06f
                                    val left = (end.x - w / 2f).coerceIn(0f, (bitmap.width - w).coerceAtLeast(0f))
                                    val top = (end.y - h / 2f).coerceIn(0f, (bitmap.height - h).coerceAtLeast(0f))
                                    RectF(left, top, left + w, top + h)
                                }
                                if (newRect.width() > 8f && newRect.height() > 8f) {
                                    regions = regions + RedactionRegion(
                                        rect = newRect,
                                        type = RegionType.MANUAL,
                                        label = "Maska ręczna"
                                    )
                                }
                                dragStartBitmap = null
                                dragCurrentBitmap = null
                                manualMaskMode = false
                            },
                            onDragCancel = {
                                dragStartBitmap = null
                                dragCurrentBitmap = null
                            }
                        )
                    } else {
                        detectTapGestures { tap ->
                            val pt = screenToBitmapCoords(
                                tap, boxSize, bitmap, zoomState.floatValue, panState.value
                            )
                            if (pt.x < 0f || pt.y < 0f ||
                                pt.x > bitmap.width || pt.y > bitmap.height
                            ) return@detectTapGestures
                            val hit = hitRegionAt(regions, pt.x, pt.y) ?: return@detectTapGestures
                            regions = regions.map {
                                if (it.id == hit.id) it.copy(isBlurred = !it.isBlurred) else it
                            }
                            highlightedRegionId = hit.id
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoomState.floatValue
                        scaleY = zoomState.floatValue
                        translationX = panState.value.x
                        translationY = panState.value.y
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
            ) {
                Image(
                    bitmap = baseImage,
                    contentDescription = "Podgląd dokumentu",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val (scale, ox, oy) = fitScaleParams()
                    fun toScreen(r: RectF) = androidx.compose.ui.geometry.Rect(
                        r.left * scale + ox, r.top * scale + oy,
                        r.right * scale + ox, r.bottom * scale + oy
                    )
                    regions.forEach { region ->
                        val sr = toScreen(region.rect)
                        if (region.isBlurred) {
                            val fill = when (region.type) {
                                RegionType.FACE -> Color(0xCC1A237E)
                                RegionType.MANUAL -> Color.Black
                            }
                            drawRect(
                                color = fill,
                                topLeft = Offset(sr.left, sr.top),
                                size = Size(sr.width, sr.height)
                            )
                            val border = when (region.type) {
                                RegionType.FACE -> Color(0xFF64B5F6)
                                RegionType.MANUAL -> Color(0xFF424242)
                            }
                            drawRect(
                                color = border,
                                topLeft = Offset(sr.left, sr.top),
                                size = Size(sr.width, sr.height),
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        } else {
                            drawRect(
                                color = Color(0xFFE65100),
                                topLeft = Offset(sr.left, sr.top),
                                size = Size(sr.width, sr.height),
                                style = Stroke(width = 2.5.dp.toPx())
                            )
                        }
                        if (region.id == highlightedRegionId) {
                            drawRect(
                                color = Color(0xFFFFAB00),
                                topLeft = Offset(sr.left - 2.dp.toPx(), sr.top - 2.dp.toPx()),
                                size = Size(sr.width + 4.dp.toPx(), sr.height + 4.dp.toPx()),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
                    val dragStart = dragStartBitmap
                    val dragEnd = dragCurrentBitmap
                    if (manualMaskMode && dragStart != null && dragEnd != null) {
                        val l = minOf(dragStart.x, dragEnd.x) * scale + ox
                        val t = minOf(dragStart.y, dragEnd.y) * scale + oy
                        val r = maxOf(dragStart.x, dragEnd.x) * scale + ox
                        val b = maxOf(dragStart.y, dragEnd.y) * scale + oy
                        drawRect(
                            color = Color(0xFF6A1B9A),
                            topLeft = Offset(l, t),
                            size = Size(r - l, b - t),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }

            if (zoomState.floatValue > 1.05f) {
                LynxGhostButton(
                    onClick = {
                        zoomState.floatValue = MIN_ZOOM
                        panState.value = Offset.Zero
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text("Przywróć widok", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

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
                        LynxSecondaryButton(onClick = {
                            val word = addWordInput.trim()
                            showAddWordDialog = false; addWordInput = ""
                            if (word.isNotEmpty()) scope.launch(Dispatchers.IO) {
                                UserDictionary.add(context, word, "FIRMA")
                                val newText = ImageRedactionPipeline.detectTextLinesAsRegions(
                                    bitmap, UserDictionary.entries, GuardAllowlist.entries, redactionProfile
                                )
                                withContext(Dispatchers.Main) {
                                    regions = ImageRedactionPipeline.mergeRegions(regions, newText)
                                }
                            }
                        }, enabled = addWordInput.isNotBlank()) { Text("FIRMA") }
                        LynxPrimaryButton(onClick = {
                            val word = addWordInput.trim()
                            showAddWordDialog = false; addWordInput = ""
                            if (word.isNotEmpty()) scope.launch(Dispatchers.IO) {
                                UserDictionary.add(context, word, "OSOBA")
                                val newText = ImageRedactionPipeline.detectTextLinesAsRegions(
                                    bitmap, UserDictionary.entries, GuardAllowlist.entries, redactionProfile
                                )
                                withContext(Dispatchers.Main) {
                                    regions = ImageRedactionPipeline.mergeRegions(regions, newText)
                                }
                            }
                        }, enabled = addWordInput.isNotBlank()) { Text("OSOBA") }
                    }
                },
                dismissButton = {
                    LynxGhostButton(onClick = { showAddWordDialog = false; addWordInput = "" }) { Text("Anuluj") }
                }
            )
        }

        Column(modifier = Modifier.padding(12.dp)) {
            val revealedCount = regions.count { !it.isBlurred }

            if (revealedCount > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "⚠ $revealedCount ${if (revealedCount == 1) "obszar odkryty" else "obszary odkryte"} — będzie widoczny na wysłanym obrazie",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!allowLibrarySave) {
                Text(
                    "Tryb Express — biblioteka niedostępna. Udostępnij zamaskowany obraz do innej aplikacji.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (allowLibrarySave) {
                OutlinedTextField(
                    value = descText,
                    onValueChange = { descText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nazwa w bibliotece (opcjonalnie)") },
                    placeholder = { Text("np. Dowód rej. — VIN odkryty") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    enabled = !isProcessing
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LynxSecondaryButton(onClick = onCancel, modifier = Modifier.weight(1f), enabled = !isProcessing) {
                    Text("Anuluj")
                }
                LynxSecondaryButton(
                    onClick = { manualMaskMode = !manualMaskMode },
                    modifier = Modifier.weight(1f),
                    enabled = !isProcessing
                ) {
                    Text(if (manualMaskMode) "Maska…" else "+ Maska")
                }
                LynxSecondaryButton(onClick = { showAddWordDialog = true }, modifier = Modifier.weight(1f), enabled = !isProcessing) {
                    Text("+ Słowo")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (allowLibrarySave) {
                LynxSuccessButton(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            val redacted = withContext(Dispatchers.Default) {
                                ImageRedactionPipeline.applyRedactions(bitmap, regions)
                            }
                            onSaveToLibrary(redacted, descText)
                            isProcessing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Dodaj do biblioteki")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (allowLibrarySave) {
                LynxSecondaryButton(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        val uri = withContext(Dispatchers.IO) {
                            ImageRedactionPipeline.saveToCache(
                                ImageRedactionPipeline.applyRedactions(bitmap, regions),
                                context
                            )
                        }
                        isProcessing = false
                        onShare(uri)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing
            ) {
                Text("Udostępnij do innej aplikacji")
            }
            } else {
                LynxSuccessButton(
                    onClick = {
                        scope.launch {
                            isProcessing = true
                            val uri = withContext(Dispatchers.IO) {
                                ImageRedactionPipeline.saveToCache(
                                    ImageRedactionPipeline.applyRedactions(bitmap, regions),
                                    context
                                )
                            }
                            isProcessing = false
                            onShare(uri)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing
                ) {
                    Text("Udostępnij do innej aplikacji")
                }
            }
        }
    }
}
