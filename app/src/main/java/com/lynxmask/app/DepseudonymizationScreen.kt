package com.lynxmask.app

// DepseudonymizationScreen.kt — v2.0
// LOGIKA ZACHOWANA bez zmian (LaunchedEffects, autodetekt, dwa tryby).
// UI przepisane na dwa ekrany: Ekran 1 (wklej/sesja) → Ekran 2 (wynik).
// Usunięte: header PSE, chipy jako główna nawigacja, przycisk "← Zamaskuj".
// Dodane: Zapisz w bibliotece, Pobierz plik.
// Przyciski zaokrąglone — zachowane jako wzorzec dla następcy.

import android.os.Environment
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepseudonymizationScreen(
    preselectedSessionId: String? = null,
    initialMode: DepseudoMode    = DepseudoMode.AI_RESPONSE,
    onBack: () -> Unit
) {
    val context        = LocalContext.current
    val clipManager    = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // ── Stan (logika bez zmian) ───────────────────────────────────────────────
    var currentMode       by remember { mutableStateOf(initialMode) }
    var inputText         by remember { mutableStateOf("") }
    var selectedSessionId by remember { mutableStateOf(preselectedSessionId ?: "") }
    var dropdownExpanded  by remember { mutableStateOf(false) }
    var sessionList       by remember { mutableStateOf<List<SessionStore.SessionRecord>>(emptyList()) }
    var restoredText      by remember { mutableStateOf("") }
    var errorMessage      by remember { mutableStateOf("") }
    var isProcessing      by remember { mutableStateOf(false) }
    var savedDone         by remember { mutableStateOf(false) }
    val tokenMapCache = remember { mutableMapOf<String, Map<String, String>?>() }

    val detectedSessionId by remember(inputText, currentMode) {
        derivedStateOf {
            if (currentMode == DepseudoMode.AI_RESPONSE)
                Deanonymizer.detectSessionId(inputText)
            else null
        }
    }

    val activeSessionId: String? = when (currentMode) {
        DepseudoMode.AI_RESPONSE     -> detectedSessionId ?: selectedSessionId.takeIf { it.isNotEmpty() }
        DepseudoMode.SOURCE_DOCUMENT -> preselectedSessionId ?: selectedSessionId.takeIf { it.isNotEmpty() }
    }

    LaunchedEffect(Unit) {
        sessionList = withContext(Dispatchers.IO) { SessionStore.listSessions(context) }
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

    // ── UI — dwa ekrany ───────────────────────────────────────────────────────
    Column(modifier = Modifier.fillMaxSize().background(LynxColors.Background)) {

        // Nagłówek
        Column(modifier = Modifier.fillMaxWidth().background(LynxColors.Sidebar)) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Text(
                "ODKRYJ DANE",
                fontFamily    = LynxTypography.Mono,
                fontSize      = 12.sp,
                color         = LynxColors.Blue,
                letterSpacing = 1.5.sp,
                modifier      = Modifier.padding(horizontal = LynxSpacing.md, vertical = LynxSpacing.sm)
            )
        }

        if (restoredText.isEmpty() && !isProcessing) {
            // ── EKRAN 1: wklej / wybierz sesję ───────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(LynxSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(LynxSpacing.md)
            ) {
                // Przełącznik trybu — zachowany jako wzorzec dla następcy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
                ) {
                    FilterChip(
                        selected = currentMode == DepseudoMode.SOURCE_DOCUMENT,
                        onClick  = {
                            if (currentMode != DepseudoMode.SOURCE_DOCUMENT) {
                                currentMode = DepseudoMode.SOURCE_DOCUMENT
                                restoredText = ""; errorMessage = ""
                            }
                        },
                        label  = { Text("Dokument \u017ar\u00f3d\u0142owy", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LynxColors.Blue,
                            selectedLabelColor     = LynxColors.TextPrimary,
                            containerColor         = LynxColors.Surface,
                            labelColor             = LynxColors.TextDim
                        )
                    )
                    FilterChip(
                        selected = currentMode == DepseudoMode.AI_RESPONSE,
                        onClick  = {
                            if (currentMode != DepseudoMode.AI_RESPONSE) {
                                currentMode = DepseudoMode.AI_RESPONSE
                                restoredText = ""; errorMessage = ""
                            }
                        },
                        label  = { Text("Odpowied\u017a AI", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LynxColors.Blue,
                            selectedLabelColor     = LynxColors.TextPrimary,
                            containerColor         = LynxColors.Surface,
                            labelColor             = LynxColors.TextDim
                        )
                    )
                }

                HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)

                // Tryb AI_RESPONSE: pole tekstowe + sesja
                if (currentMode == DepseudoMode.AI_RESPONSE) {
                    Text("TEKST Z TOKENAMI", fontFamily = LynxTypography.Mono,
                        fontSize = 10.sp, color = LynxColors.Blue, letterSpacing = 1.sp)
                    OutlinedTextField(
                        value         = inputText,
                        onValueChange = { inputText = it },
                        modifier      = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        placeholder   = {
                            Text(
                                "Wklej odpowied\u017a AI z tokenami FIRMA_001, OSOBA_001...",
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

                    if (detectedSessionId != null) {
                        SessionStatusRow(label = detectedSessionId!!, ok = true)
                    } else {
                        SessionDropdown(
                            selectedSessionId = selectedSessionId,
                            expanded          = dropdownExpanded,
                            sessionList       = sessionList,
                            onExpandChange    = { dropdownExpanded = it },
                            onSelect          = { selectedSessionId = it; dropdownExpanded = false }
                        )
                    }
                }

                // Tryb SOURCE_DOCUMENT: sesja
                if (currentMode == DepseudoMode.SOURCE_DOCUMENT) {
                    Text("SESJA", fontFamily = LynxTypography.Mono,
                        fontSize = 10.sp, color = LynxColors.Blue, letterSpacing = 1.sp)
                    when {
                        activeSessionId != null -> SessionStatusRow(label = activeSessionId, ok = true)
                        else -> {
                            Text("Wybierz sesj\u0119 z biblioteki.",
                                fontSize = 13.sp, color = LynxColors.Amber)
                            SessionDropdown(
                                selectedSessionId = selectedSessionId,
                                expanded          = dropdownExpanded,
                                sessionList       = sessionList,
                                onExpandChange    = { dropdownExpanded = it },
                                onSelect          = { selectedSessionId = it; dropdownExpanded = false }
                            )
                        }
                    }
                }

                if (errorMessage.isNotEmpty()) {
                    Text(errorMessage, fontSize = 13.sp, color = LynxColors.Red)
                }

                // Przycisk Odkryj — zachowany styl zaokrąglony jako wzorzec
                Button(
                    onClick  = { /* LaunchedEffect wyzwala automatycznie po zmianie stanu */ },
                    enabled  = activeSessionId != null && (inputText.isNotBlank() || currentMode == DepseudoMode.SOURCE_DOCUMENT),
                    modifier = Modifier.fillMaxWidth().height(LynxSpacing.TouchTarget),
                    shape    = RoundedCornerShape(50),  // zaokrąglony — wzorzec dla następcy
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = LynxColors.Blue,
                        disabledContainerColor = LynxColors.Surface
                    )
                ) {
                    Text("Odkryj dane", fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
                    // Zapisz w bibliotece
                    Button(
                        onClick  = {
                            val sesId = activeSessionId ?: return@Button
                            coroutineScope.launch(Dispatchers.IO) {
                                SessionStore.saveResponse(context, sesId, restoredText)
                                withContext(Dispatchers.Main) {
                                    savedDone = true
                                    Toast.makeText(context,
                                        "Zapisano w bibliotece pod sesj\u0105 $sesId",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled  = !savedDone && activeSessionId != null,
                        modifier = Modifier.fillMaxWidth().height(LynxSpacing.TouchTarget),
                        shape    = RoundedCornerShape(50),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor         = LynxColors.Blue,
                            disabledContainerColor = LynxColors.ActiveNav
                        )
                    ) {
                        Text(
                            if (savedDone) "\u2713 Zapisano" else "Zapisz w bibliotece",
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color      = if (savedDone) LynxColors.Green else LynxColors.TextPrimary
                        )
                    }

                    // Pobierz plik
                    OutlinedButton(
                        onClick  = {
                            try {
                                val fileName = "odkryty_${activeSessionId ?: "dokument"}_${System.currentTimeMillis()}.txt"
                                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                File(downloads, fileName).writeText(restoredText)
                                Toast.makeText(context,
                                    "Zapisano w Pobrane: $fileName",
                                    Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Toast.makeText(context,
                                    "B\u0142\u0105d zapisu: ${e.message}",
                                    Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(LynxSpacing.TouchTarget),
                        shape    = RoundedCornerShape(50),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = LynxColors.TextMuted)
                    ) {
                        Text("Pobierz plik", fontSize = 14.sp)
                    }

                    // Nowe odkrycie
                    TextButton(
                        onClick  = { restoredText = ""; inputText = ""; savedDone = false; errorMessage = "" },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Odkryj nowy tekst", fontSize = 13.sp, color = LynxColors.TextDim)
                    }
                }
            }
        }
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
            modifier      = Modifier.fillMaxWidth().menuAnchor(),
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
