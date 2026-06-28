package com.lynxmask.app

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxPrimaryButton
import com.lynxmask.app.ui.components.LynxSecondaryButton
import com.lynxmask.app.ui.components.LynxSuccessButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private data class OcrSuggestion(val text: String)

@Composable
fun ImageRedactionScreen(
    bitmap: Bitmap,
    initialRegions: List<RedactionRegion>,
    onSaveToLibrary: suspend (Bitmap, String) -> Boolean,
    onShare: (android.net.Uri) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var regions by remember { mutableStateOf(initialRegions) }
    var isProcessing by remember { mutableStateOf(false) }
    var descText by remember { mutableStateOf("") }
    var ocrSuggestion by remember { mutableStateOf<OcrSuggestion?>(null) }
    var showAddWordDialog by remember { mutableStateOf(false) }
    var addWordInput by remember { mutableStateOf("") }

    var previewImage by remember(bitmap) { mutableStateOf(bitmap.asImageBitmap()) }
    var isPreviewGenerating by remember { mutableStateOf(false) }
    val regionPreviewKey = remember(regions) {
        regions.joinToString("|") { "${it.id}:${it.isBlurred}:${it.rect.top.toInt()}" }
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

    val sortedRegions = remember(regions) {
        regions.sortedWith(compareBy({ if (it.type == RegionType.FACE) 0 else 1 }, { it.rect.top }))
    }
    val regionDisplayNames = remember(sortedRegions) { displayNamesForRegions(sortedRegions) }

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

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Sprawdź i zapisz", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Każde pole osobno — np. zostaw VIN, resztę zakryj. Domyślnie trafia do biblioteki (szyfrowane). Udostępnij opcjonalnie.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
            val ocrConf = ImageRedactionPipeline.lastDetectionOcrConfidence
            if (ocrConf != null && ocrConf < 0.65f) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Słaby skan (${(ocrConf * 100).toInt()}% pewności OCR) — sprawdź listę pól. Zbędne maski wyłącz lub usuń ręcznie.",
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
                .onSizeChanged { boxSize = it }
                .pointerInput(regionPreviewKey) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStartBitmap = screenToBitmap(offset.x, offset.y)
                            dragCurrentBitmap = dragStartBitmap
                            isDragging = true
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragCurrentBitmap = screenToBitmap(change.position.x, change.position.y)
                        },
                        onDragEnd = {
                            val start = dragStartBitmap ?: return@detectDragGestures
                            val end = dragCurrentBitmap ?: start
                            val newRect = RectF(
                                minOf(start.x, end.x).coerceIn(0f, bitmap.width.toFloat()),
                                minOf(start.y, end.y).coerceIn(0f, bitmap.height.toFloat()),
                                maxOf(start.x, end.x).coerceIn(0f, bitmap.width.toFloat()),
                                maxOf(start.y, end.y).coerceIn(0f, bitmap.height.toFloat())
                            )
                            if (newRect.width() > 10 && newRect.height() > 10) {
                                regions = regions + RedactionRegion(
                                    rect = newRect,
                                    type = RegionType.MANUAL,
                                    label = "Maska ręczna"
                                )
                                scope.launch(Dispatchers.Default) {
                                    val suggestion = ocrRegionForDictionary(bitmap, newRect)
                                    if (suggestion != null) {
                                        withContext(Dispatchers.Main) { ocrSuggestion = suggestion }
                                    }
                                }
                            }
                            dragStartBitmap = null
                            dragCurrentBitmap = null
                            isDragging = false
                        },
                        onDragCancel = {
                            dragStartBitmap = null
                            dragCurrentBitmap = null
                            isDragging = false
                        }
                    )
                }
        ) {
            Image(
                bitmap = previewImage,
                contentDescription = "Podgląd z redakcją",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            if (isPreviewGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).align(Alignment.Center),
                    strokeWidth = 3.dp
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val (scale, ox, oy) = scaleParams()
                fun toScreen(r: RectF) = androidx.compose.ui.geometry.Rect(
                    r.left * scale + ox, r.top * scale + oy,
                    r.right * scale + ox, r.bottom * scale + oy
                )
                regions.forEach { region ->
                    val sr = toScreen(region.rect)
                    if (!region.isBlurred) {
                        // Tylko odkryte — pomarańczowa ramka (zamaskowane = sam czarny pasek, bez fioletu)
                        drawRect(
                            color = Color(0xFFE65100),
                            topLeft = Offset(sr.left, sr.top),
                            size = Size(sr.width, sr.height),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    } else if (region.type == RegionType.FACE) {
                        drawRect(
                            color = Color(0xFF1565C0),
                            topLeft = Offset(sr.left, sr.top),
                            size = Size(sr.width, sr.height),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                if (isDragging) {
                    val start = dragStartBitmap ?: return@Canvas
                    val end = dragCurrentBitmap ?: return@Canvas
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
                }
            }
        }

        if (sortedRegions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 168.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text(
                    "Co wysłać",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                sortedRegions.forEach { region ->
                    RegionToggleRow(
                        label = regionDisplayNames[region.id] ?: region.label,
                        isBlurred = region.isBlurred,
                        onToggle = { blurred ->
                            regions = regions.map {
                                if (it.id == region.id) it.copy(isBlurred = blurred) else it
                            }
                        }
                    )
                }
                val textOnly = sortedRegions.filter { it.type == RegionType.MANUAL }
                if (textOnly.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LynxGhostButton(
                            onClick = {
                                regions = regions.map {
                                    if (it.type == RegionType.MANUAL) it.copy(isBlurred = true) else it
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 32.dp)
                        ) { Text("Zakryj wszystkie", style = MaterialTheme.typography.labelSmall) }
                        LynxGhostButton(
                            onClick = {
                                regions = regions.map {
                                    if (it.type == RegionType.MANUAL) it.copy(isBlurred = false) else it
                                }
                            },
                            modifier = Modifier.defaultMinSize(minHeight = 32.dp)
                        ) { Text("Odkryj wszystkie", style = MaterialTheme.typography.labelSmall) }
                    }
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
                                val newText = ImageRedactionPipeline.detectTextLinesAsRegions(bitmap, UserDictionary.entries)
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
                                val newText = ImageRedactionPipeline.detectTextLinesAsRegions(bitmap, UserDictionary.entries)
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
            val blurredCount = regions.count { it.isBlurred }
            val revealedCount = regions.count { !it.isBlurred }

            ocrSuggestion?.let { suggestion ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Wykryto: \"${suggestion.text}\"", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LynxSecondaryButton(
                                onClick = { ocrSuggestion = null },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Pomiń", style = MaterialTheme.typography.labelSmall)
                            }
                            LynxPrimaryButton(
                                onClick = {
                                    ocrSuggestion = null
                                    scope.launch(Dispatchers.IO) {
                                        UserDictionary.add(context, suggestion.text, "OSOBA")
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Do słownika", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LynxSecondaryButton(onClick = onCancel, modifier = Modifier.weight(1f), enabled = !isProcessing) {
                    Text("Anuluj")
                }
                LynxSecondaryButton(onClick = { showAddWordDialog = true }, modifier = Modifier.weight(1f), enabled = !isProcessing) {
                    Text("+ Słowo")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

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
                enabled = !isProcessing && !isPreviewGenerating
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Zapisz do biblioteki")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

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
                enabled = !isProcessing && !isPreviewGenerating
            ) {
                Text(if (blurredCount > 0) "Udostępnij do innej aplikacji" else "Udostępnij do innej aplikacji")
            }
        }
    }
}

@Composable
private fun RegionToggleRow(label: String, isBlurred: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            if (isBlurred) "Zakryte" else "Odkryte",
            style = MaterialTheme.typography.labelSmall,
            color = if (isBlurred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 8.dp)
        )
        Switch(checked = isBlurred, onCheckedChange = onToggle)
    }
}

/** Etykiety z numerem gdy powtarzają się (np. dwa pola „Numer”). */
private fun displayNamesForRegions(regions: List<RedactionRegion>): Map<String, String> {
    val grouped = regions.groupBy { it.label }
    return regions.associate { region ->
        val group = grouped[region.label].orEmpty().sortedBy { it.rect.top }
        val name = if (group.size <= 1) region.label
        else "${region.label} (${group.indexOf(region) + 1})"
        region.id to name
    }
}

private suspend fun ocrRegionForDictionary(bitmap: Bitmap, rect: RectF): OcrSuggestion? =
    withContext(Dispatchers.Default) {
        try {
            val left = rect.left.coerceIn(0f, bitmap.width.toFloat()).toInt()
            val top = rect.top.coerceIn(0f, bitmap.height.toFloat()).toInt()
            val width = (rect.right.coerceIn(0f, bitmap.width.toFloat()) - left).toInt()
            val height = (rect.bottom.coerceIn(0f, bitmap.height.toFloat()) - top).toInt()
            if (width < 10 || height < 10) return@withContext null
            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    val result = recognizer.process(InputImage.fromBitmap(cropped, 0)).await()
                    val conf = calcOcrConfidence(result)
                    val text = result.text.trim().replace(Regex("""\s+"""), " ")
                    if (conf < 0.55f || text.length < 4) return@withContext null
                    OcrSuggestion(text = text)
                } finally {
                    recognizer.close()
                }
            } finally {
                cropped.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }
