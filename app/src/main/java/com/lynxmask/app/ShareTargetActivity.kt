package com.lynxmask.app

// ShareTargetActivity.kt — Wersja 2.8
//
// ZMIANA v2.5 (sesja 23.06 — bugi biblioteki):
//   - BUG-DESCRIPTION-01: saveResponse() zapisywało opis jako odpowiedź AI.
//     Fix: updateDescription() → kolumna description w tabeli sessions.
//   - Threading: onSaveDescription owrapowany w scope.launch(Dispatchers.IO) —
//     SessionStore.save() + updateDescription() nie mogą być na Main thread.
//
// ZMIANA v2.4 (sesja 23.06 — P1 bugi zakresu):
//   - BUG-SCAN-P1: skan wielostronicowy OCR-ował tylko stronę 1.
//     Fix: pages?.firstOrNull() → pages?.mapNotNull(), LaunchedEffect na List<Uri>.
//     Strony sklejane separatorem "── Strona N ──" (jak PDF).
//   - BUG-DOCX-PARTIAL: tylko word/document.xml — brak nagłówków i stopek.
//     Fix: regex word/(document|header*|footer*).xml — te same <w:t> tagi.
//   - BUG-PDF-LIMIT: MAX_PDF_PAGES 10 → 20.
//
// ZMIANA v2.1: Dodany stan Review między OCR a pseudonimizacją.
//   Od v2.1 WSZYSTKIE źródła przechodzą przez Review (Edytor 2) —
//   daje użytkownikowi szansę korekty przed pseudonimizacją.
//
// ZMIANA v2.2 (sesja 7):
//   - TXT-FIX: pliki .txt otwarte przez menedżera plików (ACTION_VIEW z URI)
//              były czytane z EXTRA_TEXT (null) → pusty tekst. Teraz czyta stream.
//   - ASYNC-FIX: onConfirm w Review uruchamia pseudonymize() w Dispatchers.Default
//                zamiast na wątku głównym — brak ryzyka ANR przy długich dokumentach.
//
// ZMIANA v2.4 (sesja 23.06 — P1 bugi zakresu):
//   - BUG-SCAN-P1: skan wielostronicowy OCR-ował tylko stronę 1.
//     Fix: pages?.firstOrNull() → pages?.mapNotNull(), LaunchedEffect na List<Uri>.
//     Strony sklejane separatorem "── Strona N ──" (jak PDF).
//   - BUG-DOCX-PARTIAL: tylko word/document.xml — brak nagłówków i stopek.
//     Fix: regex word/(document|header*|footer*).xml — te same <w:t> tagi.
//   - BUG-PDF-LIMIT: MAX_PDF_PAGES 10 → 20.
//
// ZMIANA v2.3 (sesja 10):
//   - LOG-FIX: logOcrAnalysis() wywoływane po pseudonimizacji — zarówno w ścieżce
//     Review (onConfirm), jak i w finishWithText (ścieżki bez Review).
//     Poprzednio wywoływano tylko logPseudonymResult() (bez raportu OCR — brak
//     sekcji [1] RAW OCR, [2] NORMALIZACJA). Przycisk "Kopiuj logi" w SHARE_SHEET
//     zwracał niepełny raport. Teraz obie ścieżki mają pełne logowanie.

import android.content.Intent
import android.graphics.Bitmap
// BitmapFactory, Canvas, Color, Matrix, ExifInterface, PdfRenderer → DocumentExtractor.kt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxBrandButton
import com.lynxmask.app.ui.components.LynxPrimaryButton
import com.lynxmask.app.ui.components.LynxSecondaryButton
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxMaskTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// InputImage, TextRecognition, TextRecognizerOptions, ZipInputStream → DocumentExtractor.kt
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "LynxMask_ShareTarget"

/** Skan / zdjęcie dokumentu — OCR → Review. Poniżej progu → IMAGE-REDACT (zdjęcie bez tekstu). */
internal const val MIN_IMAGE_OCR_CHARS_FOR_TEXT_PIPELINE = 15

internal fun shouldRouteImageToTextPipeline(
    ocrCharCount: Int,
    forceImageRedact: Boolean,
    faceCount: Int = 0,
    identityDocument: Boolean = false
): Boolean =
    !forceImageRedact &&
        faceCount == 0 &&
        !identityDocument &&
        ocrCharCount >= MIN_IMAGE_OCR_CHARS_FOR_TEXT_PIPELINE

/** Dowód, legitymacja, PJ — obraz + tekst → maskowanie pikseli (MASTER §9). */
internal fun looksLikeIdentityDocument(ocrText: String): Boolean {
    if (ocrText.isBlank()) return false
    val folded = ocrText.lowercase()
        .replace('ł', 'l').replace('ó', 'o').replace('ą', 'a')
        .replace('ę', 'e').replace('ś', 's').replace('ź', 'z')
        .replace('ż', 'z').replace('ć', 'c').replace('ń', 'n')
    val markers = listOf(
        "dowod osobist", "d.o.", "dow. os", "dowod os",
        "legitymac", "legitymacj", "school id", "student id", "student card",
        "identity card", "id card", "document no", "document number",
        "numer dowodu", "seria i numer", "nr dowodu",
        "rzeczpospolita polska", "republic of poland",
        "prawo jazdy", "driving licence", "driving license",
        "dowod rejestracyjny", "dowod rej", "certyfikat rejestracji",
        "karta pobytu", "paszport", "passport", "residence permit"
    )
    return markers.any { folded.contains(it) }
}

/** Wymusza maskowanie pikseli (udostępnij zdjęcie), pomija OCR. */
const val EXTRA_FORCE_IMAGE_REDACT = "com.lynxmask.app.FORCE_IMAGE_REDACT"

class ShareTargetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        LookupTables.initialize(this)
        resetRegexCache()
        EngineSmoke.runOnce()
        ImageRedactionPipeline.purgeStaleRedactedImages(this)
        setContent {
            LynxMaskTheme {
                ShareTargetScreen(intent = intent, onFinished = { finish() })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLogBuffer.clearOnExit()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Document Scanner helper — BEZ ZMIAN
// ─────────────────────────────────────────────────────────────────────────────

private fun startDocumentScanner(
    activity: androidx.activity.ComponentActivity,
    launcher: ActivityResultLauncher<IntentSenderRequest>
) {
    val options = GmsDocumentScannerOptions.Builder()
        .setScannerMode(SCANNER_MODE_FULL)
        .setResultFormats(RESULT_FORMAT_JPEG)
        .setPageLimit(5)
        .build()
    GmsDocumentScanning.getClient(options)
        .getStartScanIntent(activity)
        .addOnSuccessListener { intentSender ->
            launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            DebugLogBuffer.log("DocScanner", "Scanner uruchomiony")
        }
        .addOnFailureListener { e ->
            DebugLogBuffer.log("DocScanner", "Błąd uruchomienia skanera: ${e.message} — fallback do bezpośredniego OCR")
        }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stan ekranu — ZMIANA v2.1: dodany Review dla ścieżek OCR
// ─────────────────────────────────────────────────────────────────────────────

private sealed class ShareScreenState {
    object Loading : ShareScreenState()
    data class Review(
        val rawText: String,
        val ocrConfidence: Float? = null  // null = brak OCR lub model nie zwrócił confidence
    ) : ShareScreenState()
    data class ImageRedact(
        val bitmap: android.graphics.Bitmap,
        val regions: List<RedactionRegion>
    ) : ShareScreenState()
    data class Scanned(
        val result: PseudonymResult,
        val sourceText: String,
        val ocrConfidence: Float? = null
    ) : ShareScreenState()
    data class Error(val message: String) : ShareScreenState()
    data class OcrRejected(val conf: Float?) : ShareScreenState()
}

// ─────────────────────────────────────────────────────────────────────────────
// Główny composable ekranu
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShareTargetScreen(intent: Intent, onFinished: () -> Unit) {
    val context = LocalContext.current
    val activity = context as androidx.activity.ComponentActivity
    val clipboard = LocalClipboard.current
    var state by remember { mutableStateOf<ShareScreenState>(ShareScreenState.Loading) }
    var progressLabel by remember { mutableStateOf("Wczytuję...") }
    // ASYNC-FIX v2.2: scope do uruchomienia pseudonymize() poza wątkiem głównym
    val scope = rememberCoroutineScope()

    fun publishState(newState: ShareScreenState) {
        scope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) { state = newState }
    }

    // Słowniki — ładowane raz, dostępne we wszystkich ścieżkach pseudonimizacji
    // BUG-DICT-ACTIVITY FIX v2.8: nie cachuj przez remember — entries czytane świeżo przy każdym pseudonymize.
    // remember zamiast LaunchedEffect: synchroniczne wykonanie gwarantuje załadowanie przed przetwarzaniem intentu.
    remember { UserDictionary.load(context) }   // tylko inicjalizacja, wynik porzucony
    remember { GuardAllowlist.load(context) }   // tylko inicjalizacja, wynik porzucony

    var docScanUris by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    var docScanError by remember { mutableStateOf(false) }
    var filePickerUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var savedLibrarySessionId by remember { mutableStateOf<String?>(null) }

    // File picker — "Wybierz plik z dysku"
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) filePickerUri = uri else onFinished()
    }

    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // BUG-SCAN-P1: pobierz WSZYSTKIE strony, nie tylko pierwszą
            val uris = GmsDocumentScanningResult
                .fromActivityResultIntent(result.data)
                ?.pages?.mapNotNull { it.imageUri }
            if (!uris.isNullOrEmpty()) {
                DebugLogBuffer.log("DocScanner", "Skan: ${uris.size} stron")
                docScanUris = uris
            } else {
                DebugLogBuffer.log("DocScanner", "Brak URI w wyniku — fallback")
                docScanError = true
            }
        } else {
            DebugLogBuffer.log("DocScanner", "Anulowano przez użytkownika")
            onFinished()
        }
    }

    LaunchedEffect(Unit) {
        try {
            DebugLogBuffer.clear()
            DebugLogBuffer.log("ShareTarget", "Start — action: ${intent.action} MIME: ${intent.type}")

            // ACTION_VIEW — plik otwarty bezpośrednio z menedżera plików
            if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
                val uri = intent.data!!
                val mime = intent.type
                    ?: context.contentResolver.getType(uri)
                    ?: "application/octet-stream"
                DebugLogBuffer.log("ShareTarget", "ACTION_VIEW: $uri MIME: $mime")
                if (mime.startsWith("image/")) {
                    withContext(Dispatchers.IO) {
                        routeImageInput(
                            uri = uri,
                            context = context,
                            forceImageRedact = intent.getBooleanExtra(EXTRA_FORCE_IMAGE_REDACT, false),
                            onProgress = { label -> scope.launch(Dispatchers.Main.immediate) { progressLabel = label } },
                            userDictionary = UserDictionary.entries,
                            setState = ::publishState
                        )
                    }
                    return@LaunchedEffect
                }
                val syntheticIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                }
                val _extracted = withContext(Dispatchers.IO) {
                    extractRawText(syntheticIntent, context) { label -> scope.launch(Dispatchers.Main.immediate) { progressLabel = label } }
                }
                val (rawText, isOcr, ocrConf) = _extracted
                finishWithText(rawText, syntheticIntent, goToReview = isOcr, userDictionary = UserDictionary.entries, guardAllowlist = GuardAllowlist.entries, mlKitConfidence = ocrConf) { publishState(it) }
                return@LaunchedEffect
            }

            // Brak share intentu — otwórz file picker zamiast Error
            if (intent.action != Intent.ACTION_SEND || intent.type == null) {
                withContext(Dispatchers.Main) {
                    filePickerLauncher.launch(arrayOf(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/pdf",
                        "text/plain",
                        "image/*"
                    ))
                }
                return@LaunchedEffect
            }

            if (intent.type?.startsWith("image/") == true) {
                val imageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (imageUri == null) {
                    state = ShareScreenState.Error("Brak obrazu do przetworzenia")
                    return@LaunchedEffect
                }
                withContext(Dispatchers.IO) {
                    routeImageInput(
                        uri = imageUri,
                        context = context,
                        forceImageRedact = intent.getBooleanExtra(EXTRA_FORCE_IMAGE_REDACT, false),
                        onProgress = { label -> scope.launch(Dispatchers.Main.immediate) { progressLabel = label } },
                        userDictionary = UserDictionary.entries,
                        setState = ::publishState
                    )
                }
                return@LaunchedEffect
            }
            val (rawText, isOcr, ocrConf) = withContext(Dispatchers.IO) {
                extractRawText(intent, context) { label -> scope.launch(Dispatchers.Main.immediate) { progressLabel = label } }
            }
            finishWithText(rawText, intent, goToReview = isOcr, userDictionary = UserDictionary.entries, guardAllowlist = GuardAllowlist.entries, mlKitConfidence = ocrConf) { publishState(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd: ${e.message}", e)
            DebugLogBuffer.log("ShareTarget", "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            state = ShareScreenState.Error("Błąd: ${e.message ?: "nieznany"}")
        }
    }

    // Plik wybrany przez file picker
    LaunchedEffect(filePickerUri) {
        val uri = filePickerUri ?: return@LaunchedEffect
        try {
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            if (mimeType.startsWith("image/")) {
                withContext(Dispatchers.IO) {
                    routeImageInput(
                        uri = uri,
                        context = context,
                        forceImageRedact = intent.getBooleanExtra(EXTRA_FORCE_IMAGE_REDACT, false),
                        onProgress = { label -> scope.launch(Dispatchers.Main.immediate) { progressLabel = label } },
                        userDictionary = UserDictionary.entries,
                        setState = ::publishState
                    )
                }
            } else {
                progressLabel = "Wczytuję plik..."
                val syntheticIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                }
                val _extracted = withContext(Dispatchers.IO) {
                    extractRawText(syntheticIntent, context) { label -> scope.launch(Dispatchers.Main.immediate) { progressLabel = label } }
                }
                val (rawText, isOcr, ocrConf) = _extracted
                finishWithText(rawText, syntheticIntent, isOcr, UserDictionary.entries, GuardAllowlist.entries, mlKitConfidence = ocrConf) { publishState(it) }
            }
        } catch (e: Exception) {
            state = ShareScreenState.Error("Błąd odczytu pliku: ${e.message}")
        }
    }

    // BUG-SCAN-P1: wszystkie strony skanu — OCR każdej strony i sklejenie wyników
    LaunchedEffect(docScanUris) {
        val uris = docScanUris ?: return@LaunchedEffect
        try {
            progressLabel = "Rozpoznaję tekst ze skanu..."
            val pages = withContext(Dispatchers.IO) {
                uris.mapIndexed { i, uri ->
                    val (text, conf) = ocrFromImageUri(uri, context)
                    val pageText = if (uris.size > 1) "── Strona ${i + 1} ──\n$text\n\n" else text
                    pageText to conf
                }
            }
            val rawText = pages.joinToString("") { it.first }
            val avgConf = pages.mapNotNull { it.second }.takeIf { it.isNotEmpty() }
                ?.average()?.toFloat()
            DebugLogBuffer.log("DocScanner", "OCR: ${rawText.length} znaków (${uris.size} stron), conf=${avgConf?.let { "%.0f%%".format(it * 100) } ?: "N/A"}")
            state = if (isOcrQualityAcceptable(avgConf, rawText.length))
                ShareScreenState.Review(rawText, ocrConfidence = avgConf)
            else
                ShareScreenState.OcrRejected(avgConf)
        } catch (e: Exception) {
            state = ShareScreenState.Error("Błąd OCR: ${e.message}")
        }
    }

    // ZMIANA v2.1: fallback OCR → też Review
    LaunchedEffect(docScanError) {
        if (!docScanError) return@LaunchedEffect
        try {
            progressLabel = "Skaner niedostępny — OCR bezpośredni..."
            val uri: android.net.Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            val (rawText, ocrConf) = withContext(Dispatchers.IO) {
                if (uri != null) ocrFromImageUri(uri, context) else "" to null
            }
            state = if (isOcrQualityAcceptable(ocrConf, rawText.length))
                ShareScreenState.Review(rawText, ocrConfidence = ocrConf)
            else
                ShareScreenState.OcrRejected(ocrConf)
        } catch (e: Exception) {
            state = ShareScreenState.Error("Błąd OCR: ${e.message}")
        }
    }

    // ── Renderowanie stanu ───────────────────────────────────────────────────
    BackHandler(enabled = state is ShareScreenState.Scanned) {
        val scanned = state as ShareScreenState.Scanned
        state = ShareScreenState.Review(
            rawText = scanned.sourceText,
            ocrConfidence = scanned.ocrConfidence
        )
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
                    "Sesja zapisana. Możesz ją otworzyć w Bibliotece lub zamknąć ten ekran.",
                    color = LynxColors.TextSecondary
                )
            },
            confirmButton = {
                LynxBrandButton(onClick = {
                    context.openMainToLibrary(sesjaId)
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

    Surface(modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background) {
        when (val s = state) {
            is ShareScreenState.Loading ->
                ShareLoadingContent(label = progressLabel)

            is ShareScreenState.Review ->
                ShareReviewContent(
                    rawText = s.rawText,
                    ocrConfidence = s.ocrConfidence,
                    onConfirm = { correctedText ->
                        // ASYNC-FIX v2.2: pseudonymize() na Dispatchers.Default
                        progressLabel = "Pseudonimizuję..."
                        state = ShareScreenState.Loading
                        scope.launch(Dispatchers.Default) {
                            val result = PseudonymEngine.pseudonymize(
                                correctedText,
                                userDictionary   = UserDictionary.entries,
                                guardAllowlist   = GuardAllowlist.entries,
                                mlKitConfidence  = s.ocrConfidence
                            )
                            DebugLogBuffer.log("Review", "Pseudonimizacja po korekcie: ${correctedText.length} znaków")
                            DebugLogBuffer.logOcrAnalysis(correctedText, result)
                            withContext(Dispatchers.Main) {
                                state = ShareScreenState.Scanned(
                                    result = result,
                                    sourceText = correctedText,
                                    ocrConfidence = s.ocrConfidence
                                )
                            }
                        }
                    },
                    onCancel = onFinished
                )

            is ShareScreenState.ImageRedact ->
                ImageRedactionScreen(
                    bitmap         = s.bitmap,
                    initialRegions = s.regions,
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
                                withContext(Dispatchers.Main) {
                                    savedLibrarySessionId = sesjaId
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        "Błąd zapisu — spróbuj ponownie",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            saved
                        }
                    },
                    onShare        = { uri ->
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

            is ShareScreenState.OcrRejected ->
                ShareOcrRejectedContent(conf = s.conf, onDismiss = onFinished)

            is ShareScreenState.Error ->
                ShareErrorContent(message = s.message, onDismiss = onFinished)

            is ShareScreenState.Scanned ->
                PseudonymResultPanel(
                    result = s.result,
                    mode = PanelMode.SHARE_SHEET,
                    onCopy = { text ->
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("masked", text))
                            )
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
                    onAddToDict = { word, type ->
                        UserDictionary.add(context, word, type)
                        DebugLogBuffer.log("UserDict", "Zapamiętano: ${word.length} znaków jako $type — łącznie ${UserDictionary.entries.size}")
                    },
                    onAddToAllowlist = { value, ruleType ->
                        GuardAllowlist.add(context, value, ruleType)
                        DebugLogBuffer.log("GuardAllowlist", "Nie maskuj: ${value.length} znaków ($ruleType) — łącznie ${GuardAllowlist.entries.size}")
                    },
                    onSaveDescription = { maskedText, description ->
                        val cleanText = maskedText
                            .removePrefix("SESJA_${s.result.sessionId}\n")
                        // I/O na Dispatchers.IO — save() szyfruje + INSERT, updateDescription() UPDATE
                        scope.launch(Dispatchers.IO) {
                            val saved = SessionStore.save(
                                context      = context,
                                sesjaId      = s.result.sessionId,
                                tokenMapJson = s.result.tokenMapJson(),
                                tokenCount   = s.result.tokenMap.size,
                                maskedText   = cleanText
                            )
                            if (!saved) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context,
                                        "Błąd zapisu sesji — dane mogą być niedostępne w bibliotece",
                                        Toast.LENGTH_LONG).show()
                                }
                                return@launch
                            }
                            // BUG-DESCRIPTION-01: saveResponse() zapisywało opis jako odpowiedź AI.
                            // Fix: updateDescription() → kolumna description w tabeli sessions.
                            if (description.isNotBlank()) {
                                SessionStore.updateDescription(context, s.result.sessionId, description)
                            }
                            DebugLogBuffer.log("SessionStore", "Zapisano sesję ${s.result.sessionId} z opisem: '$description'")
                        }
                    },
                    onDebugLog = if (BuildConfig.DEBUG) ({
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("debug_log", DebugLogBuffer.getAll()))
                            )
                        }
                        Toast.makeText(context, "Logi skopiowane (${DebugLogBuffer.size()} wpisów)", Toast.LENGTH_SHORT).show()
                    }) else null,
                    onOpenLibrary = {
                        context.openMainToLibrary(s.result.sessionId)
                        onFinished()
                    }
                )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ekran Review — v2.1
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShareReviewContent(
    rawText: String,
    ocrConfidence: Float? = null,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var editableText by remember { mutableStateOf(rawText) }
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        // Banner jakości OCR — widoczny tylko gdy ML Kit zwrócił confidence
        if (ocrConfidence != null) {
            val pct = (ocrConfidence * 100).toInt()
            val isLow = ocrConfidence < 0.5f
            val cardColor = if (isLow) LynxColors.Red else LynxColors.Amber
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor.copy(alpha = 0.12f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(if (isLow) "✕" else "⚠", fontSize = 14.sp, color = cardColor)
                    Column {
                        Text(
                            if (isLow) "Słaba jakość skanu ($pct%) — możliwe błędy rozpoznawania"
                            else       "Niska jakość skanu ($pct%) — sprawdź tekst dokładnie",
                            style = MaterialTheme.typography.labelMedium,
                            color = cardColor,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (isLow) Text(
                            "Rozważ nowe zdjęcie. Możesz też poprawić tekst ręcznie poniżej.",
                            style = MaterialTheme.typography.labelSmall,
                            color = cardColor.copy(alpha = 0.8f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(
            "Sprawdź tekst przed pseudonimizacją",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Klawiatura może sugerować korekty błędów skanowania. " +
            "Nie akceptuj zmian w nazwiskach i nazwach własnych.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = editableText,
            onValueChange = { editableText = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            label = { Text("Tekst ze skanera") }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LynxSecondaryButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Anuluj")
            }
            LynxPrimaryButton(
                onClick = { onConfirm(editableText) },
                modifier = Modifier.weight(2f)
            ) {
                Text("Pseudonimizuj")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Wspólna logika końcowa — ZMIANA v2.1: goToReview dla ścieżek OCR
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun finishWithText(
    rawText: String,
    intent: Intent,
    goToReview: Boolean = false,
    userDictionary: List<Pair<String, String>> = emptyList(),
    guardAllowlist: List<Pair<String, String>> = emptyList(),
    mlKitConfidence: Float? = null,
    setState: (ShareScreenState) -> Unit
) {
    if (rawText.isBlank()) {
        DebugLogBuffer.log("ShareTarget", "BŁĄD: pusty tekst po ekstrakcji")
        setState(ShareScreenState.Error(
            "Nie udało się odczytać treści dokumentu.\n" +
            "Obsługiwane formaty: DOCX, PDF, obrazy, tekst."
        ))
        return
    }
    DebugLogBuffer.log("ShareTarget", "Tekst wyodrębniony: ${rawText.length} znaków")
    if (goToReview) {
        setState(ShareScreenState.Review(rawText, ocrConfidence = mlKitConfidence))
        return
    }
    val result = withContext(Dispatchers.Default) {
        PseudonymEngine.pseudonymize(rawText,
            userDictionary  = userDictionary,
            guardAllowlist  = guardAllowlist,
            mlKitConfidence = mlKitConfidence
        )
    }
    DebugLogBuffer.logOcrAnalysis(rawText, result)
    setState(ShareScreenState.Scanned(
        result = result,
        sourceText = rawText,
        ocrConfidence = mlKitConfidence
    ))
}

// ─────────────────────────────────────────────────────────────────────────────
// Obraz: skan papieru → OCR tekst; dowód/legitymacja/zdjęcie → IMAGE-REDACT
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun routeImageInput(
    uri: Uri,
    context: android.content.Context,
    forceImageRedact: Boolean,
    onProgress: (String) -> Unit,
    userDictionary: List<Pair<String, String>>,
    setState: (ShareScreenState) -> Unit
) {
    if (forceImageRedact) {
        DebugLogBuffer.log("ShareTarget", "Obraz: wymuszone maskowanie pikseli (EXTRA_FORCE_IMAGE_REDACT)")
        openImageRedactScreen(uri, context, onProgress, userDictionary, setState)
        return
    }

    onProgress("Analizuję obraz...")
    val bmp = loadBitmapExifAware(context, uri)
    if (bmp == null) {
        setState(ShareScreenState.Error("Nie udało się wczytać obrazu"))
        return
    }

    try {
        UserDictionary.load(context)

        // Twarze najpierw — bez pełnego OCR gdy zdjęcie osoby/dowodu ze zdjęciem
        val faces = withContext(Dispatchers.Default) {
            ImageRedactionPipeline.detectFacesAsRegions(bmp)
        }

        if (faces.isNotEmpty()) {
            DebugLogBuffer.log("ShareTarget", "Obraz: twarze=${faces.size} → maskowanie (1× OCR linii)")
            onProgress("Przygotowuję maskowanie dokumentu ze zdjęciem...")
            val textRegions = withContext(Dispatchers.Default) {
                ImageRedactionPipeline.detectTextLinesAsRegions(bmp, userDictionary)
            }
            setState(ShareScreenState.ImageRedact(bitmap = bmp, regions = faces + textRegions))
            return
        }

        val (text, conf) = withContext(Dispatchers.Default) { ocrFromBitmap(bmp) }
        val trimmedLen = text.trim().length
        val identityDoc = looksLikeIdentityDocument(text)
        val useTextPipeline = shouldRouteImageToTextPipeline(
            ocrCharCount = trimmedLen,
            forceImageRedact = false,
            faceCount = 0,
            identityDocument = identityDoc
        )

        DebugLogBuffer.log(
            "ShareTarget",
            "Obraz: OCR=$trimmedLen zn., dowód/legitymacja=$identityDoc → " +
                if (useTextPipeline) "ścieżka tekstowa" else "maskowanie obrazu"
        )

        if (useTextPipeline) {
            bmp.recycle()
            setState(
                if (isOcrQualityAcceptable(conf, trimmedLen))
                    ShareScreenState.Review(text.trim(), ocrConfidence = conf)
                else
                    ShareScreenState.OcrRejected(conf)
            )
            return
        }

        onProgress(
            if (identityDoc) "Przygotowuję maskowanie dokumentu..."
            else "Wykrywam dane na obrazie..."
        )
        val textRegions = withContext(Dispatchers.Default) {
            ImageRedactionPipeline.detectTextLinesAsRegions(bmp, userDictionary)
        }
        setState(ShareScreenState.ImageRedact(bitmap = bmp, regions = textRegions))
    } catch (e: Exception) {
        DebugLogBuffer.log("ShareTarget", "routeImageInput BŁĄD: ${e.message}")
        bmp.recycle()
        setState(ShareScreenState.Error("Błąd przetwarzania obrazu: ${e.message ?: "nieznany"}"))
    }
}

private suspend fun openImageRedactScreen(
    uri: Uri,
    context: android.content.Context,
    onProgress: (String) -> Unit,
    userDictionary: List<Pair<String, String>>,
    setState: (ShareScreenState) -> Unit
) {
    onProgress("Wykrywam twarze i tekst...")
    UserDictionary.load(context)
    val bmp = loadBitmapExifAware(context, uri)
    if (bmp == null) {
        setState(ShareScreenState.Error("Nie udało się wczytać obrazu"))
        return
    }
    try {
        val faces = withContext(Dispatchers.Default) {
            ImageRedactionPipeline.detectFacesAsRegions(bmp)
        }
        val textRegions = withContext(Dispatchers.Default) {
            ImageRedactionPipeline.detectTextLinesAsRegions(bmp, userDictionary)
        }
        setState(ShareScreenState.ImageRedact(bitmap = bmp, regions = faces + textRegions))
    } catch (e: Exception) {
        bmp.recycle()
        setState(ShareScreenState.Error("Błąd maskowania: ${e.message ?: "nieznany"}"))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ekstrakcja tekstu — zwraca Triple<String, Boolean, Float?>
// Boolean = true → pokaż Review (ścieżka OCR/DOCX)
// Float?  = mlKitConfidence (null dla ścieżek bez OCR)
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun extractRawText(
    intent: Intent,
    context: android.content.Context,
    onProgress: (String) -> Unit
): Triple<String, Boolean, Float?> {
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

    DebugLogBuffer.log("ShareTarget", "Plik: \"$fileName\" MIME: $mimeType isDocx: $isDocx")

    return when {
        isDocx -> {
            if (uri == null) return Triple("", false, null)
            onProgress("Czytam umowę DOCX...")
            Triple(extractTextFromDocx(uri, context), true, null)
        }
        mimeType == "text/plain" -> {
            onProgress("Odczytuję tekst...")
            val text = if (uri != null) {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: ""
            } else {
                intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            }
            Triple(text, true, null)
        }
        mimeType == "application/pdf" -> {
            if (uri == null) return Triple("", false, null)
            onProgress("Otwieram PDF...")
            val (text, conf) = ocrFromPdfUri(uri, context, onProgress)
            Triple(text, true, conf)
        }
        mimeType.startsWith("image/") -> {
            if (uri == null) return Triple("", false, null)
            onProgress("Rozpoznaję tekst z obrazu...")
            val (text, conf) = ocrFromImageUri(uri, context)
            Triple(text, true, conf)
        }
        else -> {
            DebugLogBuffer.log("ShareTarget", "Nieznany typ — próba DOCX jako fallback")
            val text = if (uri != null) extractTextFromDocx(uri, context)
                       else (intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
            Triple(text, false, null)
        }
    }
}

// extractTextFromDocx, ocrFromImageUri, ocrFromBitmap, ocrFromPdfUri, loadBitmapExifAware
// → DocumentExtractor.kt (wydzielone w v2.8 — czyste IO, brak zależności od UI)

// ─────────────────────────────────────────────────────────────────────────────
// Ekrany pomocnicze — BEZ ZMIAN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShareLoadingContent(label: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShareErrorContent(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Nie udało się przetworzyć", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))
        LynxPrimaryButton(onClick = onDismiss) { Text("Zamknij") }
    }
}

@Composable
private fun ShareOcrRejectedContent(conf: Float?, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Dokument zbyt słabej jakości",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Nie możemy zagwarantować bezpiecznego maskowania danych na tym obrazie — ryzyko pominięcia danych osobowych.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (conf != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Jakość OCR: ${"%.0f%%".format(conf * 100)} (wymagane min. ${"%.0f%%".format(OCR_CONF_THRESHOLD * 100)})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Jak poprawic jakość:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("• Dobre oświetlenie, bez cieni i odblasków", fontSize = 13.sp)
                Text("• Aparat prostopadle do dokumentu (nie pod kątem)", fontSize = 13.sp)
                Text("• Cały tekst widoczny w kadrze", fontSize = 13.sp)
                Text("• Dokument na płaskim podłożu bez zagięć", fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        LynxPrimaryButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("Zamknij i zrób nowe zdjęcie")
        }
    }
}
