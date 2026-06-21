package com.lynxmask.app

// ShareTargetActivity.kt — Wersja 2.3
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
// ZMIANA v2.3 (sesja 10):
//   - LOG-FIX: logOcrAnalysis() wywoływane po pseudonimizacji — zarówno w ścieżce
//     Review (onConfirm), jak i w finishWithText (ścieżki bez Review).
//     Poprzednio wywoływano tylko logPseudonymResult() (bez raportu OCR — brak
//     sekcji [1] RAW OCR, [2] NORMALIZACJA). Przycisk "Kopiuj logi" w SHARE_SHEET
//     zwracał niepełny raport. Teraz obie ścieżki mają pełne logowanie.

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import com.lynxmask.app.ui.theme.LynxMaskTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

private const val TAG = "LynxMask_ShareTarget"

class ShareTargetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        LookupTables.initialize(this)
        setContent {
            LynxMaskTheme {
                ShareTargetScreen(intent = intent, onFinished = { finish() })
            }
        }
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
    data class Review(val rawText: String) : ShareScreenState()   // v2.1: przed pseudonimizacją
    data class Scanned(val result: PseudonymResult) : ShareScreenState()
    data class Error(val message: String) : ShareScreenState()
}

// ─────────────────────────────────────────────────────────────────────────────
// Główny composable ekranu
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShareTargetScreen(intent: Intent, onFinished: () -> Unit) {
    val context = LocalContext.current
    val activity = context as androidx.activity.ComponentActivity
    val clipboardManager = LocalClipboardManager.current
    var state by remember { mutableStateOf<ShareScreenState>(ShareScreenState.Loading) }
    var progressLabel by remember { mutableStateOf("Wczytuję...") }
    // ASYNC-FIX v2.2: scope do uruchomienia pseudonymize() poza wątkiem głównym
    val scope = rememberCoroutineScope()

    // Słowniki — ładowane raz, dostępne we wszystkich ścieżkach pseudonimizacji
    // BUG-DICT-ACTIVITY FIX v2.8: nie cachuj przez remember — entries czytane świeżo przy każdym pseudonymize.
    // remember zamiast LaunchedEffect: synchroniczne wykonanie gwarantuje załadowanie przed przetwarzaniem intentu.
    remember { UserDictionary.load(context) }   // tylko inicjalizacja, wynik porzucony
    remember { GuardAllowlist.load(context) }   // tylko inicjalizacja, wynik porzucony

    var docScanUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var docScanError by remember { mutableStateOf(false) }
    var filePickerUri by remember { mutableStateOf<android.net.Uri?>(null) }

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
            val uri = GmsDocumentScanningResult
                .fromActivityResultIntent(result.data)
                ?.pages?.firstOrNull()?.imageUri
            if (uri != null) {
                docScanUri = uri
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
                val syntheticIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                }
                val _extracted = withContext(Dispatchers.IO) {
                    extractRawText(syntheticIntent, context) { label -> scope.launch(Dispatchers.Main.immediate) { progressLabel = label } }
                }
                val (rawText, isOcr) = _extracted
                finishWithText(rawText, syntheticIntent, goToReview = isOcr, userDictionary = UserDictionary.entries, guardAllowlist = GuardAllowlist.entries) { state = it }
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
                progressLabel = "Otwieranie skanera dokumentów..."
                withContext(Dispatchers.Main) {
                    startDocumentScanner(activity, scannerLauncher)
                }
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) {
                val (rawText, isOcr) = extractRawText(intent, context) { label -> scope.launch(Dispatchers.Main.immediate) { progressLabel = label } }
                finishWithText(rawText, intent, goToReview = isOcr, userDictionary = UserDictionary.entries, guardAllowlist = GuardAllowlist.entries) { state = it }
            }
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
            progressLabel = "Wczytuję plik..."
            val syntheticIntent = Intent(Intent.ACTION_SEND).apply {
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
            }
            val _extracted = withContext(Dispatchers.IO) {
                extractRawText(syntheticIntent, context) { label -> scope.launch(Dispatchers.Main.immediate) { progressLabel = label } }
            }
            val (rawText, isOcr) = _extracted
            finishWithText(rawText, syntheticIntent, isOcr, UserDictionary.entries, GuardAllowlist.entries) { state = it }
        } catch (e: Exception) {
            state = ShareScreenState.Error("Błąd odczytu pliku: ${e.message}")
        }
    }

    // ZMIANA v2.1: skan → Review
    LaunchedEffect(docScanUri) {
        val uri = docScanUri ?: return@LaunchedEffect
        try {
            progressLabel = "Rozpoznaję tekst ze skanu..."
            val rawText = withContext(Dispatchers.IO) { ocrFromImageUri(uri, context) }
            DebugLogBuffer.log("DocScanner", "OCR wyniku: ${rawText.length} znaków")
            state = ShareScreenState.Review(rawText)
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
            val rawText = withContext(Dispatchers.IO) {
                if (uri != null) ocrFromImageUri(uri, context) else ""
            }
            state = ShareScreenState.Review(rawText)
        } catch (e: Exception) {
            state = ShareScreenState.Error("Błąd OCR: ${e.message}")
        }
    }

    // ── Renderowanie stanu ───────────────────────────────────────────────────
    Surface(modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background) {
        when (val s = state) {
            is ShareScreenState.Loading ->
                ShareLoadingContent(label = progressLabel)

            is ShareScreenState.Review ->
                ShareReviewContent(
                    rawText = s.rawText,
                    onConfirm = { correctedText ->
                        // ASYNC-FIX v2.2: pseudonymize() na Dispatchers.Default
                        progressLabel = "Pseudonimizuję..."
                        state = ShareScreenState.Loading
                        scope.launch(Dispatchers.Default) {
                            val result = PseudonymEngine.pseudonymize(correctedText, userDictionary = UserDictionary.entries, guardAllowlist = GuardAllowlist.entries)
                            DebugLogBuffer.log("Review", "Pseudonimizacja po korekcie: ${correctedText.length} znaków")
                            // LOG-FIX v2.3: pełny raport OCR (Raw + Normalizacja + Tokeny + Flagi)
                            // Poprzednio tylko logPseudonymResult() — brak sekcji [1][2] RAW OCR
                            DebugLogBuffer.logOcrAnalysis(correctedText, result)
                            withContext(Dispatchers.Main) {
                                state = ShareScreenState.Scanned(result = result)
                            }
                        }
                    },
                    onCancel = onFinished
                )

            is ShareScreenState.Error ->
                ShareErrorContent(message = s.message, onDismiss = onFinished)

            is ShareScreenState.Scanned ->
                PseudonymResultPanel(
                    result = s.result,
                    mode = PanelMode.SHARE_SHEET,
                    onCopy = { text ->
                        clipboardManager.setText(AnnotatedString(text))
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
                        DebugLogBuffer.log("UserDict", "Zapamiętano: '$word' jako $type — łącznie ${UserDictionary.entries.size}")
                    },
                    onAddToAllowlist = { value, ruleType ->
                        GuardAllowlist.add(context, value, ruleType)
                        DebugLogBuffer.log("GuardAllowlist", "Nie maskuj: '$value' ($ruleType) — łącznie ${GuardAllowlist.entries.size}")
                    },
                    onSaveDescription = { maskedText, description ->
                        val cleanText = maskedText
                            .removePrefix("SESJA_${s.result.sessionId}\n")
                        SessionStore.save(
                            context      = context,
                            sesjaId      = s.result.sessionId,
                            tokenMapJson = s.result.tokenMapJson(),
                            tokenCount   = s.result.tokenMap.size,
                            maskedText   = cleanText
                        )
                        if (description.isNotBlank()) {
                            SessionStore.saveResponse(context, s.result.sessionId, description)
                        }
                        DebugLogBuffer.log("SessionStore", "Zapisano sesję ${s.result.sessionId} z opisem: '$description'")
                    },
                    onDebugLog = {
                        clipboardManager.setText(AnnotatedString(DebugLogBuffer.getAll()))
                        Toast.makeText(context, "Logi skopiowane (${DebugLogBuffer.size()} wpisów)", Toast.LENGTH_SHORT).show()
                    },
                    onCancel = onFinished
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
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var editableText by remember { mutableStateOf(rawText) }
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

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
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            ) {
                Text("Anuluj")
            }
            Button(
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
    DebugLogBuffer.log("ShareTarget", rawText.take(300).replace("\n", "↵"))
    if (goToReview) {
        setState(ShareScreenState.Review(rawText))
        return
    }
    val result = PseudonymEngine.pseudonymize(rawText, userDictionary = userDictionary, guardAllowlist = guardAllowlist)
    // LOG-FIX v2.3: pełny raport OCR zamiast samego logPseudonymResult
    DebugLogBuffer.logOcrAnalysis(rawText, result)
    setState(ShareScreenState.Scanned(result = result))
}

// ─────────────────────────────────────────────────────────────────────────────
// Ekstrakcja tekstu — zwraca Pair<String, Boolean>
// Boolean = true → pokaż Review (ścieżka OCR/DOCX)
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun extractRawText(
    intent: Intent,
    context: android.content.Context,
    onProgress: (String) -> Unit
): Pair<String, Boolean> {
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
            if (uri == null) return "" to false
            onProgress("Czytam umowę DOCX...")
            extractTextFromDocx(uri, context) to true
        }
        mimeType == "text/plain" -> {
            onProgress("Odczytuję tekst...")
            // TXT-FIX v2.2: plik .txt z URI — czytaj przez stream
            val text = if (uri != null) {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: ""
            } else {
                intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            }
            text to true
        }
        mimeType == "application/pdf" -> {
            if (uri == null) return "" to false
            onProgress("Otwieram PDF...")
            ocrFromPdfUri(uri, context, onProgress) to true
        }
        mimeType.startsWith("image/") -> {
            if (uri == null) return "" to false
            onProgress("Rozpoznaję tekst z obrazu...")
            ocrFromImageUri(uri, context) to true
        }
        else -> {
            DebugLogBuffer.log("ShareTarget", "Nieznany typ — próba DOCX jako fallback")
            if (uri != null) extractTextFromDocx(uri, context) to false
            else (intent.getStringExtra(Intent.EXTRA_TEXT) ?: "") to false
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DOCX → tekst — BEZ ZMIAN
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun extractTextFromDocx(uri: Uri, context: android.content.Context): String =
    withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder()
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "word/document.xml") {
                            val xml = zip.readBytes().toString(Charsets.UTF_8)
                            val withBreaks = xml.replace(Regex("""<w:p[ >]"""), "\n<w:p ")
                            val textRegex = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>""")
                            textRegex.findAll(withBreaks).forEach { match ->
                                sb.append(match.groupValues[1])
                            }
                            DebugLogBuffer.log("DOCX", "Wyodrębniono ${sb.length} znaków z word/document.xml")
                            break
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            sb.toString()
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace(Regex(""" {2,}"""), " ")
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        } catch (e: Exception) {
            DebugLogBuffer.log("DOCX", "BŁĄD: ${e.javaClass.simpleName}: ${e.message}")
            ""
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// OCR z obrazka — BEZ ZMIAN
// ─────────────────────────────────────────────────────────────────────────────

private suspend fun ocrFromImageUri(uri: Uri, context: android.content.Context): String =
    withContext(Dispatchers.IO) {
        try {
            val bitmap: Bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@withContext ""
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            recognizer.close()
            bitmap.recycle()
            DebugLogBuffer.log("OCR", "Obraz — ${result.text.length} znaków")
            result.text
        } catch (e: Exception) {
            DebugLogBuffer.log("OCR", "BŁĄD: ${e.message}")
            ""
        }
    }

// ─────────────────────────────────────────────────────────────────────────────
// OCR z PDF — BEZ ZMIAN
// ─────────────────────────────────────────────────────────────────────────────

private const val MAX_PDF_PAGES = 10
private const val PDF_RENDER_SCALE = 2

private suspend fun ocrFromPdfUri(
    uri: Uri,
    context: android.content.Context,
    onProgress: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    val sb = StringBuilder()
    try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val renderer = PdfRenderer(descriptor)
            val pageCount = minOf(renderer.pageCount, MAX_PDF_PAGES)
            DebugLogBuffer.log("OCR", "PDF — ${renderer.pageCount} stron, skanuję $pageCount")
            for (i in 0 until pageCount) {
                onProgress("Skanuję stronę ${i + 1} z $pageCount...")
                val page = renderer.openPage(i)
                val bitmap = Bitmap.createBitmap(
                    page.width * PDF_RENDER_SCALE, page.height * PDF_RENDER_SCALE,
                    Bitmap.Config.ARGB_8888
                )
                Canvas(bitmap).drawColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val ocr = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                recognizer.close()
                if (ocr.text.isNotBlank()) {
                    sb.append("── Strona ${i + 1} ──\n${ocr.text}\n\n")
                    DebugLogBuffer.log("OCR", "Strona ${i + 1}: ${ocr.text.length} znaków")
                }
                bitmap.recycle()
            }
            if (renderer.pageCount > MAX_PDF_PAGES)
                sb.append("\n[LynxMask: ${renderer.pageCount} stron, przeskanowano $MAX_PDF_PAGES]")
            renderer.close()
        }
    } catch (e: Exception) {
        DebugLogBuffer.log("OCR", "BŁĄD PDF: ${e.message}")
    }
    sb.toString()
}

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
        Button(onClick = onDismiss) { Text("Zamknij") }
    }
}
