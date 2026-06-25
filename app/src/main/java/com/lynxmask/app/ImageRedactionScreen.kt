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
            Text("Sprawdź i wyślij", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "Dane osobiste zakryte automatycznie. Przeciągnij palcem by zakryć twarz lub coś co pominięto. Stuknij zakryty obszar by odsłonić.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
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
                            val threshold = 30f
                            if (!isDragging &&
                                dragAmount.x * dragAmount.x + dragAmount.y * dragAmount.y > threshold * threshold) {
                                isDragging = true
                            }
                        },
                        onDragEnd = {
                            val start = dragStartBitmap ?: return@detectDragGestures
                            val end   = dragCurrentBitmap ?: start
                            if (!isDragging) {
                                // Tap — toggle blur/odkryj (padding 24px dla precyzji palca)
                                val PAD = 24f
                                val hit = regions.firstOrNull { r ->
                                    val p = RectF(r.rect.left - PAD, r.rect.top - PAD, r.rect.right + PAD, r.rect.bottom + PAD)
                                    p.contains(start.x, start.y)
                                }
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
                                    // OCR z oryginału — zaproponuj dodanie do słownika
                                    scope.launch(Dispatchers.Default) {
                                        val suggestion = ocrRegionForDictionary(bitmap, newRect)
                                        if (suggestion != null) {
                                            withContext(Dispatchers.Main) {
                                                ocrSuggestion = suggestion
                                            }
                                        }
                                    }
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
                                    regions = regions.filter { it.type == RegionType.FACE } + newText
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
                                    regions = regions.filter { it.type == RegionType.FACE } + newText
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
                                        DebugLogBuffer.log("ImageRedact", "Słownik: '$txt' → FIRMA")
                                        val newText = ImageRedactionPipeline.detectTextLinesAsRegions(bitmap, UserDictionary.entries)
                                        withContext(Dispatchers.Main) {
                                            regions = regions.filter { it.type == RegionType.FACE } + newText
                                        }
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
                                        DebugLogBuffer.log("ImageRedact", "Słownik: '$txt' → OSOBA")
                                        val newText = ImageRedactionPipeline.detectTextLinesAsRegions(bitmap, UserDictionary.entries)
                                        withContext(Dispatchers.Main) {
                                            regions = regions.filter { it.type == RegionType.FACE } + newText
                                        }
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
