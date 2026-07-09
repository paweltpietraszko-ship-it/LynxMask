package com.lynxmask.app

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.document.DocumentArtifact
import com.lynxmask.app.document.DocxArtifact
import com.lynxmask.app.document.extractDocxArtifact
import com.lynxmask.app.document.rememberDocxExportLauncher
import com.lynxmask.app.ui.components.*
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LynxMask_IncomingDoc"

private sealed class IncomingDocState {
    object Loading : IncomingDocState()
    data class Review(
        val rawText: String,
        val ocrConfidence: Float? = null,
        // Document Rebuilder: przeżywa ręczną korektę, żeby DOCX export był dostępny po
        // Scanned. Jeśli user zmieni encję podczas review — DocxWriter i tak odmówi zapisu
        // fail-closed (patrz writeDocxArtifact), nie trzeba tego blokować tutaj.
        val artifact: DocumentArtifact? = null,
        val sourceUri: Uri? = null
    ) : IncomingDocState()
    data class ImageRedact(
        val bitmap: Bitmap,
        val regions: List<RedactionRegion>,
        val profile: RedactionProfile = RedactionProfile.GENERAL,
        val weakScan: Boolean = false
    ) : IncomingDocState()

    data class Scanned(
        val result: PseudonymResult,
        val sourceText: String,
        val ocrConfidence: Float? = null,
        val artifact: DocumentArtifact? = null,
        val sourceUri: Uri? = null
    ) : IncomingDocState()
    data class Error(val message: String) : IncomingDocState()
    data class OcrRejected(val conf: Float?) : IncomingDocState()
}

/** Jedyna ścieżka przetwarzania dokumentu (share, plik, tekst z huba). */
@Composable
fun IncomingDocumentFlow(
    intent: Intent,
    isExpress: Boolean,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var state by remember(intent) { mutableStateOf<IncomingDocState>(IncomingDocState.Loading) }
    var progressLabel by remember { mutableStateOf("Wczytuję...") }
    var savedLibrarySessionId by remember { mutableStateOf<String?>(null) }
    val saveDocx = rememberDocxExportLauncher(scope)

    remember { UserDictionary.load(context) }
    remember { GuardAllowlist.load(context) }

    fun publishState(newState: IncomingDocState) {
        scope.launch(Dispatchers.Main.immediate) { state = newState }
    }

    LaunchedEffect(intent) {
        try {
            LynxAppInit.ensureReady(context.applicationContext)
            DebugLogBuffer.clear()
            DebugLogBuffer.log("IncomingDoc", "Start — action: ${intent.action} MIME: ${intent.type}")
            processIncomingIntent(
                intent = intent,
                context = context,
                onProgress = { label -> progressLabel = label },
                setState = ::publishState
            )
        } catch (e: Exception) {
            Log.e(TAG, "Błąd: ${e.message}", e)
            DebugLogBuffer.log("IncomingDoc", "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            state = IncomingDocState.Error("Błąd: ${e.message ?: "nieznany"}")
        }
    }

    BackHandler {
        when (val s = state) {
            is IncomingDocState.Scanned -> state = IncomingDocState.Review(
                rawText = s.sourceText,
                ocrConfidence = s.ocrConfidence,
                artifact = s.artifact,
                sourceUri = s.sourceUri
            )
            else -> onFinished()
        }
    }

    savedLibrarySessionId?.let { sesjaId ->
        AlertDialog(
            onDismissRequest = {
                savedLibrarySessionId = null
                onFinished()
            },
            title = { Text("Zapisano w bibliotece", color = LynxColors.TextPrimary) },
            text = {
                Text(
                    "Sesja zapisana. Możesz ją otworzyć w Bibliotece lub wrócić do ekranu głównego.",
                    color = LynxColors.TextSecondary
                )
            },
            confirmButton = {
                LynxBrandButton(onClick = {
                    LynxPendingNav.requestLibrary(sesjaId)
                    savedLibrarySessionId = null
                    onFinished()
                }) { Text("Otwórz bibliotekę") }
            },
            dismissButton = {
                LynxGhostButton(onClick = {
                    savedLibrarySessionId = null
                    onFinished()
                }) { Text("Zamknij") }
            },
            containerColor = LynxColors.Surface
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (val s = state) {
            is IncomingDocState.Loading ->
                IncomingLoadingContent(label = progressLabel)

            is IncomingDocState.Review ->
                IncomingReviewContent(
                    rawText = s.rawText,
                    ocrConfidence = s.ocrConfidence,
                    onConfirm = { correctedText ->
                        progressLabel = "Pseudonimizuję..."
                        state = IncomingDocState.Loading
                        scope.launch(Dispatchers.Default) {
                            LynxAppInit.ensureReady(context.applicationContext)
                            val result = PseudonymEngine.pseudonymize(
                                correctedText,
                                userDictionary = UserDictionary.entries,
                                guardAllowlist = GuardAllowlist.entries,
                                mlKitConfidence = s.ocrConfidence
                            )
                            DebugLogBuffer.log("Review", "Pseudonimizacja: ${correctedText.length} znaków")
                            DebugLogBuffer.logOcrAnalysis(correctedText, result)
                            withContext(Dispatchers.Main) {
                                state = IncomingDocState.Scanned(
                                    result = result,
                                    sourceText = correctedText,
                                    ocrConfidence = s.ocrConfidence,
                                    artifact = s.artifact,
                                    sourceUri = s.sourceUri
                                )
                            }
                        }
                    },
                    onCancel = onFinished
                )


            is IncomingDocState.ImageRedact ->
                ImageRedactionScreen(
                    bitmap = s.bitmap,
                    initialRegions = s.regions,
                    redactionProfile = s.profile,
                    allowLibrarySave = !isExpress,
                    weakScanWarning = s.weakScan,
                    onSaveToLibrary = { redactedBitmap, description ->
                        withContext(Dispatchers.IO) {
                            val sesjaId = java.util.UUID.randomUUID().toString()
                            val jpeg = ImageRedactionPipeline.encodeJpeg(redactedBitmap)
                            val saved = SessionStore.saveRedactedImage(
                                context = context,
                                sesjaId = sesjaId,
                                jpegBytes = jpeg,
                                description = description.ifBlank { "Zamaskowany obraz" }
                            )
                            if (saved) {
                                SessionStore.recordAudit(context, sesjaId, "image_saved")
                                withContext(Dispatchers.Main) { savedLibrarySessionId = sesjaId }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Błąd zapisu — spróbuj ponownie", Toast.LENGTH_LONG).show()
                                }
                            }
                            saved
                        }
                    },
                    onShare = { uri ->
                        val fwd = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(fwd, "Udostępnij bezpieczny obraz"))
                        onFinished()
                    },
                    onCancel = onFinished
                )

            is IncomingDocState.OcrRejected ->
                IncomingOcrRejectedContent(conf = s.conf, onDismiss = onFinished)

            is IncomingDocState.Error ->
                IncomingErrorContent(message = s.message, onDismiss = onFinished)

            is IncomingDocState.Scanned ->
                PseudonymResultPanel(
                    result = s.result,
                    mode = PanelMode.SHARE_SHEET,
                    onCopy = { text ->
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("masked", text)))
                        }
                        onFinished()
                    },
                    onForward = { text ->
                        val fwd = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(fwd, "Prześlij bezpieczną wersję"))
                        onFinished()
                    },
                    onAddToDict = { word, type -> UserDictionary.add(context, word, type) },
                    onAddToAllowlist = { value, ruleType -> GuardAllowlist.add(context, value, ruleType) },
                    onSaveDescription = if (isExpress) null else { maskedText, description ->
                        scope.launch(Dispatchers.IO) {
                            val cleanText = maskedText.removePrefix("SESJA_${s.result.sessionId}\n")
                            val saved = SessionStore.save(
                                context = context,
                                sesjaId = s.result.sessionId,
                                tokenMapJson = s.result.tokenMapJson(),
                                tokenCount = s.result.tokenMap.size,
                                maskedText = cleanText
                            )
                            if (!saved) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "Błąd zapisu sesji — dane mogą być niedostępne w bibliotece",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                return@launch
                            }
                            if (description.isNotBlank()) {
                                SessionStore.updateDescription(context, s.result.sessionId, description)
                            }
                        }
                    },
                    onDebugLog = if (BuildConfig.DEBUG) ({
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("debug_log", DebugLogBuffer.getAll())))
                        }
                        Toast.makeText(context, "Logi skopiowane (${DebugLogBuffer.size()} wpisów)", Toast.LENGTH_SHORT).show()
                    }) else null,
                    onOpenLibrary = if (isExpress) null else {
                        {
                            LynxPendingNav.requestLibrary(s.result.sessionId)
                            onFinished()
                        }
                    },
                    onSaveDocx = (s.artifact as? DocxArtifact)?.let { docxArtifact ->
                        { effectiveTokenMap: Map<String, String> -> saveDocx(docxArtifact, effectiveTokenMap) }
                    }
                )
        }
    }
}

// ── Wejście: jedna funkcja, ustalona kolejność ───────────────────────────────

private suspend fun processIncomingIntent(
    intent: Intent,
    context: android.content.Context,
    onProgress: (String) -> Unit,
    setState: (IncomingDocState) -> Unit
) {
    val forceRedact = intent.getBooleanExtra(LynxNavExtras.FORCE_IMAGE_REDACT, false)

    // Tekst z huba — pierwszeństwo, bez ACTION_SEND / URI
    if (intent.action == LynxNavExtras.ACTION_HUB_TEXT) {
        val text = intent.getStringExtra(LynxNavExtras.EXTRA_HUB_TEXT)
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: ""
        onProgress("Pseudonimizuję...")
        finishWithText(text, goToReview = false, mlKitConfidence = null, setState, context)
        return
    }

    if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
        val uri = intent.data!!
        val mime = intent.type
            ?: context.contentResolver.getType(uri)
            ?: "application/octet-stream"
        DebugLogBuffer.log("IncomingDoc", "ACTION_VIEW: $uri MIME: $mime")
        if (mime.startsWith("image/")) {
            routeImageInput(uri, context, forceRedact, onProgress, setState)
            return
        }
        val synthetic = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        val extracted = extractRawText(synthetic, context, onProgress)
        finishWithText(
            extracted.text, extracted.goToReview, extracted.confidence, setState, context,
            extracted.artifact, extracted.sourceUri
        )
        return
    }

    if (intent.action != Intent.ACTION_SEND) {
        val fallbackText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!fallbackText.isNullOrBlank()) {
            finishWithText(fallbackText, goToReview = false, mlKitConfidence = null, setState, context)
            return
        }
        setState(IncomingDocState.Error("Nieobsługiwane źródło dokumentu"))
        return
    }

    // SEND: sam tekst w EXTRA (bez pliku) — np. share z innej apki
    val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
    if (streamUri == null) {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        if (sharedText.isNotBlank()) {
            onProgress("Pseudonimizuję...")
            finishWithText(sharedText, goToReview = false, mlKitConfidence = null, setState, context)
            return
        }
    }

    if (intent.type?.startsWith("image/") == true) {
        val imageUri = streamUri
        if (imageUri == null) {
            setState(IncomingDocState.Error("Brak obrazu do przetworzenia"))
            return
        }
        routeImageInput(imageUri, context, forceRedact, onProgress, setState)
        return
    }

    val extracted = extractRawText(intent, context, onProgress)
    finishWithText(
        extracted.text, extracted.goToReview, extracted.confidence, setState, context,
        extracted.artifact, extracted.sourceUri
    )
}

private suspend fun finishWithText(
    rawText: String,
    goToReview: Boolean,
    mlKitConfidence: Float?,
    setState: (IncomingDocState) -> Unit,
    context: android.content.Context,
    artifact: DocumentArtifact? = null,
    sourceUri: Uri? = null
) {
    if (rawText.isBlank()) {
        setState(IncomingDocState.Error(
            "Nie udało się odczytać treści dokumentu.\nObsługiwane formaty: DOCX, PDF, obrazy, tekst."
        ))
        return
    }
    DebugLogBuffer.log("IncomingDoc", "Tekst: ${rawText.length} znaków, review=$goToReview")
    if (goToReview) {
        setState(IncomingDocState.Review(rawText, mlKitConfidence, artifact, sourceUri))
        return
    }
    LynxAppInit.ensureReady(context.applicationContext)
    val result = withContext(Dispatchers.Default) {
        PseudonymEngine.pseudonymize(
            rawText,
            userDictionary = UserDictionary.entries,
            guardAllowlist = GuardAllowlist.entries,
            mlKitConfidence = mlKitConfidence
        )
    }
    DebugLogBuffer.logOcrAnalysis(rawText, result)
    setState(IncomingDocState.Scanned(result, rawText, mlKitConfidence, artifact, sourceUri))
}

private suspend fun routeImageInput(
    uri: Uri,
    context: android.content.Context,
    forceImageRedact: Boolean,
    onProgress: (String) -> Unit,
    setState: (IncomingDocState) -> Unit
) {
    if (forceImageRedact) {
        openImageRedact(uri, context, onProgress, setState)
        return
    }

    onProgress("Analizuję obraz...")
    val bmp = loadBitmapExifAware(context, uri)
    if (bmp == null) {
        setState(IncomingDocState.Error("Nie udało się wczytać obrazu"))
        return
    }

    try {
        UserDictionary.load(context)
        val faces = withContext(Dispatchers.Default) {
            ImageRedactionPipeline.detectFacesAsRegions(bmp)
        }
        val ocrScan = withContext(Dispatchers.Default) {
            ImageRedactionPipeline.runOcrOnce(bmp)
        }

        val routeCtx = ImageRouteContext(
            ocrCharCount = ocrScan.plainText.length,
            ocrText = ocrScan.plainText,
            faceCount = faces.size,
            forceImageRedact = false
        )
        val kind = classifyImageInput(routeCtx)
        val profile = redactionProfileFor(kind, ocrScan.plainText)

        DebugLogBuffer.log(
            "IncomingDoc",
            "Obraz: OCR=${ocrScan.plainText.length} zn., twarze=${faces.size}, trasa=$kind, profil=$profile"
        )

        when (kind) {
            ImageInputKind.PAGE -> {
                bmp.recycle()
                finishWithText(ocrScan.plainText, goToReview = true, ocrScan.confidence, setState, context)
            }
            ImageInputKind.CARD -> {
                onProgress("Przygotowuję maskowanie dokumentu...")
                val regions = withContext(Dispatchers.Default) {
                    ImageRedactionPipeline.detectRedactionRegions(
                        bitmap = bmp,
                        userDict = UserDictionary.entries,
                        guardAllowlist = GuardAllowlist.entries,
                        profile = profile,
                        ocr = ocrScan
                    )
                }
                val weakScan = ocrScan.confidence != null && ocrScan.confidence < 0.68f
                setState(IncomingDocState.ImageRedact(bmp, regions, profile, weakScan))
            }
        }
    } catch (e: Exception) {
        DebugLogBuffer.log("IncomingDoc", "routeImageInput BŁĄD: ${e.message}")
        bmp.recycle()
        setState(IncomingDocState.Error("Błąd przetwarzania obrazu: ${e.message ?: "nieznany"}"))
    }
}

private suspend fun openImageRedact(
    uri: Uri,
    context: android.content.Context,
    onProgress: (String) -> Unit,
    setState: (IncomingDocState) -> Unit
) {
    onProgress("Wykrywam twarze i dane...")
    UserDictionary.load(context)
    val bmp = loadBitmapExifAware(context, uri)
    if (bmp == null) {
        setState(IncomingDocState.Error("Nie udało się wczytać obrazu"))
        return
    }
    try {
        val ocrScan = withContext(Dispatchers.Default) { ImageRedactionPipeline.runOcrOnce(bmp) }
        val profile = redactionProfileFor(ImageInputKind.CARD, ocrScan.plainText)
        val regions = withContext(Dispatchers.Default) {
            ImageRedactionPipeline.detectRedactionRegions(
                bitmap = bmp,
                userDict = UserDictionary.entries,
                guardAllowlist = GuardAllowlist.entries,
                profile = profile,
                ocr = ocrScan
            )
        }
        val weak = ocrScan.confidence != null && ocrScan.confidence < 0.68f
        setState(IncomingDocState.ImageRedact(bmp, regions, profile, weak))
    } catch (e: Exception) {
        bmp.recycle()
        setState(IncomingDocState.Error("Błąd maskowania: ${e.message ?: "nieznany"}"))
    }
}

private data class ExtractedDocument(
    val text: String,
    val goToReview: Boolean,
    val confidence: Float?,
    val artifact: DocumentArtifact? = null,
    val sourceUri: Uri? = null
)

private suspend fun extractRawText(
    intent: Intent,
    context: android.content.Context,
    onProgress: (String) -> Unit
): ExtractedDocument {
    val mimeType = intent.type ?: ""
    val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    val fileName = uri?.let { u ->
        context.contentResolver.query(u, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        }
    } ?: ""

    val isDocx = mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        || fileName.endsWith(".docx", ignoreCase = true)
        || (mimeType == "application/octet-stream" && fileName.endsWith(".docx", ignoreCase = true))

    return when {
        isDocx -> {
            if (uri == null) return ExtractedDocument("", false, null)
            onProgress("Czytam umowę DOCX...")
            // Document Rebuilder: nowy parser z pamięcią pozycji (do "Zapisz DOCX" po
            // maskowaniu). Fallback do starego regexu jeśli nowy zawiedzie (np. plik bez
            // word/document.xml w oczekiwanej formie) — nigdy nie regresuj poniżej tego co
            // działało wcześniej, DOCX export po prostu nie będzie dostępny dla tego pliku.
            val artifact = extractDocxArtifact(uri, context)
            if (artifact != null) {
                ExtractedDocument(artifact.plainText, true, null, artifact, uri)
            } else {
                ExtractedDocument(extractTextFromDocx(uri, context), true, null)
            }
        }
        mimeType == "text/plain" -> {
            onProgress("Odczytuję tekst...")
            val text = if (uri != null) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            } else {
                intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            }
            ExtractedDocument(text, true, null)
        }
        mimeType == "application/pdf" -> {
            if (uri == null) return ExtractedDocument("", false, null)
            onProgress("Otwieram PDF...")
            val (text, conf) = ocrFromPdfUri(uri, context, onProgress)
            ExtractedDocument(text, true, conf)
        }
        else -> {
            val text = if (uri != null) extractTextFromDocx(uri, context)
            else (intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
            ExtractedDocument(text, text.isNotBlank(), null)
        }
    }
}

// ── UI pomocnicze ────────────────────────────────────────────────────────────

@Composable
private fun IncomingLoadingContent(label: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IncomingReviewContent(
    rawText: String,
    ocrConfidence: Float? = null,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var editableText by remember { mutableStateOf(rawText) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (ocrConfidence != null) {
            val pct = (ocrConfidence * 100).toInt()
            val isLow = ocrConfidence < 0.5f
            val cardColor = if (isLow) LynxColors.Red else LynxColors.Amber
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor.copy(alpha = 0.12f))
            ) {
                Text(
                    if (isLow) "Słaba jakość skanu ($pct%)" else "Niska jakość skanu ($pct%) — sprawdź tekst",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = cardColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text("Sprawdź tekst przed pseudonimizacją", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = editableText,
            onValueChange = { editableText = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text("Tekst ze skanera") }
        )

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LynxSecondaryButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Anuluj") }
            LynxPrimaryButton(onClick = { onConfirm(editableText) }, modifier = Modifier.weight(2f)) {
                Text("Pseudonimizuj")
            }
        }
    }
}


@Composable
private fun IncomingErrorContent(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Nie udało się przetworzyć", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))
        LynxPrimaryButton(onClick = onDismiss) { Text("Zamknij") }
    }
}

@Composable
private fun IncomingOcrRejectedContent(conf: Float?, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Dokument zbyt słabej jakości", fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Nie możemy zagwarantować bezpiecznego maskowania — zrób nowe zdjęcie.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (conf != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Jakość OCR: ${"%.0f%%".format(conf * 100)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        LynxPrimaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Zamknij i zrób nowe zdjęcie")
        }
    }
}
