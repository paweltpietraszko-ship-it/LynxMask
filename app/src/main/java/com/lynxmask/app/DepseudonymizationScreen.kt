package com.lynxmask.app

// DepseudonymizationScreen.kt — v2.1
// LOGIKA ZACHOWANA bez zmian (LaunchedEffects, autodetekt, dwa tryby).
// UI przepisane na dwa ekrany: Ekran 1 (wklej/sesja) → Ekran 2 (wynik).
// Usunięte: header PSE, chipy jako główna nawigacja, przycisk "← Zamaskuj".
// Dodane: Zapisz w bibliotece, Pobierz plik.
// Przyciski zaokrąglone — zachowane jako wzorzec dla następcy.

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxPrimaryButton
import com.lynxmask.app.ui.components.LynxScreenHeader
import com.lynxmask.app.ui.components.LynxSecondaryButton
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepseudonymizationScreen(
    preselectedSessionId: String? = null,
    initialMode: DepseudoMode    = DepseudoMode.AI_RESPONSE,
    fromLibrary: Boolean         = false,
    onBack: () -> Unit,
    onRestoreOriginal: (() -> Unit)? = null
) {
    val context        = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val clipboard = LocalClipboard.current

    // ── Stan (logika bez zmian) ───────────────────────────────────────────────
    var currentMode       by remember(initialMode, fromLibrary) {
        mutableStateOf(if (fromLibrary) initialMode else DepseudoMode.AI_RESPONSE)
    }
    var inputText         by remember { mutableStateOf("") }
    var selectedSessionId by remember { mutableStateOf(preselectedSessionId ?: "") }
    var dropdownExpanded  by remember { mutableStateOf(false) }
    var sessionList       by remember { mutableStateOf<List<SessionStore.SessionRecord>>(emptyList()) }
    var restoredText      by remember { mutableStateOf("") }
    var errorMessage      by remember { mutableStateOf("") }
    var isProcessing      by remember { mutableStateOf(false) }
    var savedDone         by remember { mutableStateOf(false) }
    val tokenMapCache = remember { mutableMapOf<String, Map<String, String>?>() }

    // BUG-LIB-6: CreateDocument zamiast File(Downloads) — działa na Android 11+ (Scoped Storage)
    var pendingDownloadText by remember { mutableStateOf<String?>(null) }
    var showDownloadWarning by remember { mutableStateOf(false) }
    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingDownloadText ?: return@rememberLauncherForActivityResult
        pendingDownloadText = null
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Plik zapisany", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Błąd zapisu: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // SESJA_ header — we wszystkich trybach (kopiowany tekst zawiera header)
    val detectedSessionId by remember(inputText) {
        derivedStateOf { Deanonymizer.detectSessionId(inputText) }
    }

    // Fingerprint: gdy brak SESJA_, szukaj sesji po tokenach w tekście (IO)
    var fingerprintSessionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(inputText, detectedSessionId) {
        if (detectedSessionId != null) { fingerprintSessionId = null; return@LaunchedEffect }
        val tokens = Deanonymizer.extractTokens(inputText)
        if (tokens.isEmpty()) { fingerprintSessionId = null; return@LaunchedEffect }
        fingerprintSessionId = withContext(Dispatchers.IO) {
            SessionStore.listSessions(context).firstOrNull { record ->
                val map = tokenMapCache.getOrPut(record.sesjaId) {
                    SessionStore.loadTokenMap(context, record.sesjaId)
                }
                map?.keys?.any { it in tokens } == true
            }?.sesjaId
        }
    }

    val activeSessionId: String? = when (currentMode) {
        DepseudoMode.AI_RESPONSE     ->
            detectedSessionId ?: fingerprintSessionId ?: selectedSessionId.takeIf { it.isNotEmpty() }
        DepseudoMode.SOURCE_DOCUMENT,
        DepseudoMode.MASKED_VIEW     ->
            preselectedSessionId ?: detectedSessionId ?: fingerprintSessionId ?: selectedSessionId.takeIf { it.isNotEmpty() }
    }

    LaunchedEffect(Unit) {
        sessionList = withContext(Dispatchers.IO) { SessionStore.listSessions(context) }
    }

    // Auto-wybór: gdy brak SESJA_ i brak preselect → domyślnie najnowsza sesja
    LaunchedEffect(sessionList) {
        if (currentMode == DepseudoMode.AI_RESPONSE &&
            preselectedSessionId == null &&
            selectedSessionId.isEmpty() &&
            sessionList.isNotEmpty()
        ) {
            selectedSessionId = sessionList.first().sesjaId
        }
    }

    // Sync dropdown z fingerprint gdy tokeny znalezione w tekście
    LaunchedEffect(fingerprintSessionId) {
        val fid = fingerprintSessionId ?: return@LaunchedEffect
        selectedSessionId = fid
    }

    // Tryb AI_RESPONSE — debounce + przywracanie
    LaunchedEffect(activeSessionId, inputText, currentMode) {
        if (currentMode != DepseudoMode.AI_RESPONSE) return@LaunchedEffect
        if (activeSessionId == null || inputText.isBlank()) {
            restoredText = ""; errorMessage = ""; return@LaunchedEffect
        }
        delay(300)
        isProcessing = true; errorMessage = ""
        val tokenMap = if (activeSessionId in tokenMapCache) tokenMapCache[activeSessionId]
        else {
            val loaded = withContext(Dispatchers.IO) { SessionStore.loadTokenMap(context, activeSessionId) }
            tokenMapCache[activeSessionId] = loaded; loaded
        }
        if (tokenMap == null) {
            restoredText = ""; errorMessage = "Nie znaleziono mapy dla tej sesji"
        } else {
            restoredText = Deanonymizer.restore(inputText, tokenMap); errorMessage = ""
            withContext(Dispatchers.IO) { SessionStore.recordAudit(context, activeSessionId, "depseudonymized") }
        }
        isProcessing = false; savedDone = false
    }

    // Tryb SOURCE_DOCUMENT — auto-load
    LaunchedEffect(activeSessionId, currentMode) {
        if (currentMode != DepseudoMode.SOURCE_DOCUMENT) return@LaunchedEffect
        val sesId = activeSessionId ?: return@LaunchedEffect
        isProcessing = true; errorMessage = ""; restoredText = ""
        val maskedText = withContext(Dispatchers.IO) { SessionStore.loadMaskedText(context, sesId) }
        if (maskedText == null) {
            errorMessage = "Brak tekstu \u017ar\u00f3d\u0142owego dla tej sesji"
            isProcessing = false; return@LaunchedEffect
        }
        val tokenMap = if (sesId in tokenMapCache) tokenMapCache[sesId]
        else {
            val loaded = withContext(Dispatchers.IO) { SessionStore.loadTokenMap(context, sesId) }
            tokenMapCache[sesId] = loaded; loaded
        }
        if (tokenMap == null) {
            errorMessage = "Nie znaleziono mapy token\u00f3w dla tej sesji"
        } else {
            restoredText = Deanonymizer.restore(maskedText, tokenMap); errorMessage = ""
            withContext(Dispatchers.IO) { SessionStore.recordAudit(context, sesId, "source_depseudonymized") }
        }
        isProcessing = false; savedDone = false
    }


    // Tryb MASKED_VIEW — podglad zamaskowanego tekstu bez odwracania tokenow
    LaunchedEffect(activeSessionId, currentMode) {
        if (currentMode != DepseudoMode.MASKED_VIEW) return@LaunchedEffect
        val sesId = activeSessionId ?: return@LaunchedEffect
        isProcessing = true; errorMessage = ""; restoredText = ""
        val maskedText = withContext(Dispatchers.IO) { SessionStore.loadMaskedText(context, sesId) }
        if (maskedText == null) {
            errorMessage = "Brak zamaskowanego tekstu dla tej sesji"
            isProcessing = false; return@LaunchedEffect
        }
        restoredText = maskedText
        isProcessing = false; savedDone = false
    }
    val autoLoadFromLibrary = fromLibrary && preselectedSessionId != null &&
        (initialMode == DepseudoMode.MASKED_VIEW || initialMode == DepseudoMode.SOURCE_DOCUMENT)
    val screenTitle = currentMode.screenTitle(fromLibrary)

    // ── UI — dwa ekrany ───────────────────────────────────────────────────────
    Column(modifier = Modifier.fillMaxSize().background(LynxColors.Background)) {

        LynxScreenHeader(
            title = screenTitle,
            sectionLabel = when {
                fromLibrary -> "DOKUMENT"
                else        -> "WKLEJ ODPOWIEDŹ"
            },
            backLabel = if (fromLibrary) "Dokument" else "Hub",
            onBack = onBack
        )

        if (restoredText.isEmpty() && !isProcessing) {
            if (autoLoadFromLibrary) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(LynxSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(LynxSpacing.md)
                ) {
                    if (errorMessage.isNotEmpty()) {
                        Text(errorMessage, fontSize = 14.sp, color = LynxColors.Red, lineHeight = 20.sp)
                    } else {
                        CircularProgressIndicator(color = LynxColors.Blue, modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text(
                            "Ładowanie…",
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                            color = LynxColors.TextMuted,
                            fontSize = 14.sp
                        )
                    }
                }
            } else if (fromLibrary && currentMode == DepseudoMode.AI_RESPONSE) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(LynxSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(LynxSpacing.md)
                ) {
                    Text(
                        "Wklej odpowiedź z asystenta AI. Tekst musi zawierać tokeny z tej sesji.",
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = LynxColors.TextSecondary
                    )
                    SessionStatusRow(
                        label = preselectedSessionId ?: activeSessionId ?: "—",
                        ok = activeSessionId != null
                    )
                    OutlinedTextField(
                        value         = inputText,
                        onValueChange = { inputText = it },
                        modifier      = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        placeholder   = {
                            Text("Wklej tutaj odpowiedź AI…", color = LynxColors.TextDim, fontSize = 13.sp)
                        },
                        shape  = RoundedCornerShape(LynxShapes.CardRadius),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = LynxColors.Blue,
                            unfocusedBorderColor = LynxColors.Border
                        )
                    )
                    if (errorMessage.isNotEmpty()) {
                        Text(errorMessage, fontSize = 13.sp, color = LynxColors.Red)
                    }
                    if (inputText.isBlank()) {
                        Text(
                            "Po wklejeniu wynik pojawi się automatycznie.",
                            fontSize = 13.sp,
                            color = LynxColors.TextMuted
                        )
                    }
                }
            } else if (!fromLibrary) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(LynxSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(LynxSpacing.md)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(LynxShapes.CardRadius),
                    colors = CardDefaults.cardColors(containerColor = LynxColors.ActiveNav.copy(alpha = 0.55f))
                ) {
                    Column(Modifier.padding(LynxSpacing.md), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Wklej odpowiedź AI z tokenami (OSOBA_001…). LynxMask odblokuje dane na telefonie.",
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = LynxColors.TextPrimary
                        )
                        Text(
                            "Cały dokument? Biblioteka → dokument → Podgląd zamaskowanego lub Przywróć oryginał.",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = LynxColors.TextSecondary
                        )
                    }
                }

                Text("TEKST Z TOKENAMI", fontFamily = LynxTypography.Mono,
                    fontSize = 10.sp, color = LynxColors.Blue, letterSpacing = 1.sp)
                OutlinedTextField(
                    value         = inputText,
                    onValueChange = { inputText = it },
                    modifier      = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    placeholder   = {
                        Text(
                            "Wklej odpowiedź z ChatGPT / Claude / Gemini…",
                            color = LynxColors.TextDim, fontSize = 13.sp, lineHeight = 18.sp
                        )
                    },
                    shape  = RoundedCornerShape(LynxShapes.CardRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = LynxColors.Blue,
                        unfocusedBorderColor = LynxColors.Border
                    )
                )

                Text("SESJA", fontFamily = LynxTypography.Mono,
                    fontSize = 10.sp, color = LynxColors.Blue, letterSpacing = 1.sp)

                when {
                    detectedSessionId != null -> {
                        val desc = sessionList
                            .find { it.sesjaId == detectedSessionId }
                            ?.description?.takeIf { it.isNotEmpty() }
                        SessionStatusRow(
                            label = if (desc != null) "$desc  ·  $detectedSessionId" else detectedSessionId!!,
                            ok    = true
                        )
                    }
                    fingerprintSessionId != null -> {
                        val desc = sessionList
                            .find { it.sesjaId == fingerprintSessionId }
                            ?.description?.takeIf { it.isNotEmpty() }
                        SessionStatusRow(
                            label = "Rozpoznano: ${if (desc != null) "$desc  ·  $fingerprintSessionId" else fingerprintSessionId!!}",
                            ok    = true
                        )
                    }
                    else -> {
                        SessionDropdown(
                            selectedSessionId = selectedSessionId,
                            expanded          = dropdownExpanded,
                            sessionList       = sessionList,
                            onExpandChange    = { dropdownExpanded = it },
                            onSelect          = { selectedSessionId = it; dropdownExpanded = false }
                        )
                    }
                }

                if (errorMessage.isNotEmpty()) {
                    Text(errorMessage, fontSize = 13.sp, color = LynxColors.Red)
                }

                Text(
                    when {
                        inputText.isBlank() -> "Wklej tekst — wynik pojawi się automatycznie."
                        activeSessionId == null -> "Wybierz sesję pasującą do tokenów."
                        else -> "Przetwarzam po wklejeniu…"
                    },
                    fontSize = 13.sp,
                    color = LynxColors.TextMuted
                )
            }
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    LynxGhostButton(onClick = onBack) {
                        Text(
                            if (fromLibrary) "← Dokument" else "← Hub",
                            fontSize = 14.sp,
                            color = LynxColors.BlueLight
                        )
                    }
                }
            }

        } else {
            // ── EKRAN 2: wynik ────────────────────────────────────────────────
            Column(modifier = Modifier.fillMaxSize()) {
                // Tekst odkryty — przewijalny, zajmuje większość ekranu
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(LynxSpacing.md)
                ) {
                    if (isProcessing) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator(color = LynxColors.Blue)
                        }
                    } else {
                        Text(
                            text       = restoredText,
                            modifier   = Modifier.verticalScroll(rememberScrollState()),
                            fontSize   = 14.sp,
                            color      = LynxColors.TextPrimary,
                            lineHeight = 22.sp
                        )
                    }
                }

                // Przyciski akcji — zaokrąglone jako wzorzec dla następcy
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LynxColors.Surface)
                        .navigationBarsPadding()
                        .padding(LynxSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
                ) {
                    if (currentMode == DepseudoMode.MASKED_VIEW && fromLibrary) {
                        onRestoreOriginal?.let { restore ->
                            LynxPrimaryButton(
                                onClick = restore,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Przywróć oryginał")
                            }
                        }
                        LynxSecondaryButton(
                            onClick = {
                                coroutineScope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText("masked", restoredText))
                                    )
                                    Toast.makeText(context, "Skopiowano", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Kopiuj zamaskowany tekst")
                        }
                    }

                    if (currentMode == DepseudoMode.AI_RESPONSE) {
                    LynxPrimaryButton(
                        onClick  = {
                            val sesId = activeSessionId ?: return@LynxPrimaryButton
                            coroutineScope.launch(Dispatchers.IO) {
                                SessionStore.saveResponse(context, sesId, restoredText)
                                withContext(Dispatchers.Main) {
                                    savedDone = true
                                    Toast.makeText(context,
                                        "Zapisano w bibliotece pod sesją $sesId",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled  = !savedDone && activeSessionId != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (savedDone) "Zapisano" else if (fromLibrary) "Zapisz odpowiedź AI" else "Zapisz w bibliotece",
                            color = if (savedDone) LynxColors.Green else LynxColors.TextPrimary
                        )
                    }
                    }

                    if (currentMode != DepseudoMode.MASKED_VIEW) {
                    LynxSecondaryButton(
                        onClick  = { showDownloadWarning = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (currentMode == DepseudoMode.SOURCE_DOCUMENT) "Pobierz oryginał" else "Pobierz plik"
                        )
                    }
                    }

                    LynxGhostButton(
                        onClick  = {
                            if (fromLibrary) {
                                onBack()
                            } else {
                                restoredText = ""
                                inputText = ""
                                savedDone = false
                                errorMessage = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (fromLibrary) "← Dokument" else "Wklej inny tekst",
                            fontSize = 14.sp,
                            color = if (fromLibrary) LynxColors.BlueLight else LynxColors.TextDim
                        )
                    }
                }
            }
        }
    }

    if (showDownloadWarning) {
        AlertDialog(
            onDismissRequest = { showDownloadWarning = false },
            title = { Text("Pobierasz odkryty tekst") },
            text  = { Text("Plik będzie zawierał oryginalne dane osobowe w formie jawnej (PII plaintext). Upewnij się, że zapisujesz go w bezpiecznym miejscu.") },
            confirmButton = {
                LynxPrimaryButton(onClick = {
                    showDownloadWarning = false
                    val fileName = "odkryty_${activeSessionId ?: "dokument"}_${System.currentTimeMillis()}.txt"
                    pendingDownloadText = restoredText
                    saveFileLauncher.launch(fileName)
                }) { Text("Pobierz") }
            },
            dismissButton = {
                LynxGhostButton(onClick = { showDownloadWarning = false }) { Text("Anuluj") }
            }
        )
    }
}

// ── Helper composables ────────────────────────────────────────────────────────

@Composable
private fun SessionStatusRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (ok) LynxColors.GreenBg else LynxColors.RedBg,
                RoundedCornerShape(LynxShapes.CardRadius)
            )
            .padding(horizontal = LynxSpacing.md, vertical = LynxSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
    ) {
        Text(if (ok) "\u2713" else "\u2715",
            color = if (ok) LynxColors.Green else LynxColors.Red, fontSize = 14.sp)
        Text(label, fontFamily = LynxTypography.Mono, fontSize = 13.sp,
            color = if (ok) LynxColors.Green else LynxColors.Red)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDropdown(
    selectedSessionId: String,
    expanded:          Boolean,
    sessionList:       List<SessionStore.SessionRecord>,
    onExpandChange:    (Boolean) -> Unit,
    onSelect:          (String) -> Unit
) {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandChange) {
        OutlinedTextField(
            value         = selectedSessionId.ifEmpty { "Wybierz sesj\u0119..." },
            onValueChange = {},
            readOnly      = true,
            modifier      = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape  = RoundedCornerShape(LynxShapes.CardRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = LynxColors.Blue,
                unfocusedBorderColor = if (selectedSessionId.isEmpty()) LynxColors.Border else LynxColors.Blue
            )
        )
        ExposedDropdownMenu(
            expanded         = expanded,
            onDismissRequest = { onExpandChange(false) },
            modifier         = Modifier.background(LynxColors.Surface)
        ) {
            if (sessionList.isEmpty()) {
                DropdownMenuItem(
                    text    = { Text("Brak sesji", fontSize = 13.sp, color = LynxColors.TextDim) },
                    onClick = {},
                    enabled = false
                )
            } else {
                sessionList.forEach { session ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(session.description.ifEmpty { session.sesjaId },
                                    fontSize = 13.sp, color = LynxColors.TextPrimary)
                                Text(session.sesjaId,
                                    fontFamily = LynxTypography.Mono, fontSize = 11.sp,
                                    color = LynxColors.TextDim)
                            }
                        },
                        onClick  = { onSelect(session.sesjaId) },
                        modifier = Modifier.heightIn(min = LynxSpacing.TouchTarget)
                    )
                    HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)
                }
            }
        }
    }
}
