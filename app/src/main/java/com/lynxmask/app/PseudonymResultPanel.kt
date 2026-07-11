package com.lynxmask.app

// PseudonymResultPanel.kt — UI-2 (MASTER sekcja 17)
// Sticky STATUS → scroll (alerty + podgląd + opis) → dolny pasek (biblioteka + akcje).

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxBrandButton
import com.lynxmask.app.ui.components.LynxFilledButton
import com.lynxmask.app.ui.components.LynxFlatRow
import com.lynxmask.app.ui.components.LynxSuccessButton
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography
import kotlinx.coroutines.launch

enum class PanelMode { SHARE_SHEET }

internal enum class EntityDecision { PENDING, KEEP_HIDDEN, REVEALED }

@Suppress("UNUSED_PARAMETER")
@Composable
fun PseudonymResultPanel(
    result: PseudonymResult,
    mode: PanelMode = PanelMode.SHARE_SHEET,
    onCopy: (String) -> Unit,
    onForward: ((String) -> Unit)? = null,
    onAddToDict: ((String, String) -> Unit)? = null,
    onAddToAllowlist: ((String, String) -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onDebugLog: (() -> Unit)? = null,
    onSaveDescription: ((maskedText: String, description: String) -> Unit)? = null,
    onOpenLibrary: (() -> Unit)? = null,
    // Document Rebuilder: dostępny tylko gdy źródłem był odpowiedni format (DOCX/PDF/XLSX).
    // Przekazujemy gotowy tekst (outputText — ten sam co użytkownik widzi/kopiuje, z
    // uwzględnieniem ręcznych odsłonięć/dodatkowych maskowań) — writer zapisuje go wprost
    // jako nowy plik, bez szukania czegokolwiek w oryginalnym dokumencie (patrz
    // DocxWriter/PdfWriter/XlsxWriter.kt).
    onSaveDocx: ((String) -> Unit)? = null,
    onSavePdf: ((String) -> Unit)? = null,
    onSaveXlsx: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var showDisclaimer by remember { mutableStateOf(false) }
    var pendingCopyAction by remember { mutableStateOf(false) }
    var pendingForwardAction by remember { mutableStateOf(false) }

    val flagDecisions = remember(result.flags) {
        mutableStateMapOf<String, EntityDecision>().also { map ->
            result.flags.forEach { map[it.fragment] = EntityDecision.PENDING }
        }
    }
    var revealedTokens by remember { mutableStateOf(setOf<String>()) }
    var manualMasks by remember { mutableStateOf(mapOf<String, String>()) }

    val maskedOutputText by remember(manualMasks, result) {
        derivedStateOf {
            var t = result.pseudonymizedText
            val notWordChar = """[a-ząćęłńóśźżA-ZŁŚŹĆŃĄĘÓŻ0-9]"""
            manualMasks.forEach { (token, original) ->
                val maskRegex = Regex(
                    "(?<!$notWordChar)${Regex.escape(original)}(?!$notWordChar)",
                    RegexOption.IGNORE_CASE
                )
                t = maskRegex.replace(t, token)
            }
            t
        }
    }

    val outputText by remember(revealedTokens, maskedOutputText, result) {
        derivedStateOf {
            var t = maskedOutputText
            revealedTokens.forEach { tok ->
                result.tokenMap[tok]?.let { original -> t = t.replace(tok, original) }
            }
            t
        }
    }

    val maskedDisplayText by remember(maskedOutputText, result) {
        derivedStateOf { maskedOutputText.removePrefix("SESJA_${result.sessionId}\n") }
    }

    fun nextToken(type: String): String {
        val indices = (result.tokenMap.keys + manualMasks.keys)
            .filter { it.startsWith("${type}_") }
            .mapNotNull { it.removePrefix("${type}_").toIntOrNull() }
        val next = (indices.maxOrNull() ?: 0) + 1
        return "${type}_${next.toString().padStart(3, '0')}"
    }

    val allFlagsHandled by remember(flagDecisions) {
        derivedStateOf { flagDecisions.values.none { it == EntityDecision.PENDING } }
    }

    var dismissedHits by remember { mutableStateOf(setOf<String>()) }

    val activeGuardHits by remember(manualMasks, dismissedHits, result) {
        derivedStateOf {
            result.guardHits.filter { hit ->
                !manualMasks.values.any { it == hit.matchedText } &&
                    hit.matchedText !in dismissedHits
            }.distinctBy { it.matchedText }
        }
    }

    val redHits by remember(activeGuardHits) {
        derivedStateOf { activeGuardHits.filter { it.level == "RED" } }
    }
    val yellowGuardHits by remember(activeGuardHits) {
        derivedStateOf { activeGuardHits.filter { it.level == "YELLOW" } }
    }
    val pendingFlags by remember(flagDecisions, result.flags) {
        derivedStateOf {
            result.flags.filter { flagDecisions[it.fragment] == EntityDecision.PENDING }
        }
    }

    val hasYellowAlerts = yellowGuardHits.isNotEmpty() || pendingFlags.isNotEmpty()
    val panelStatus = resolvePanelStatus(redHits.isNotEmpty(), hasYellowAlerts)
    val maskedTokenCount = result.tokenMap.size + manualMasks.size

    val exportBlockReason = resolveExportBlockReason(
        hasRedHits = redHits.isNotEmpty(),
        hasYellowAlerts = hasYellowAlerts,
        allFlagsHandled = allFlagsHandled
    )
    val canExport = exportBlockReason == ExportBlockReason.NONE

    var copiedDone by remember { mutableStateOf(false) }
    var librarySaved by remember { mutableStateOf(false) }
    var descText by remember { mutableStateOf("") }
    var showTextPreview by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    fun showBlockedHint() {
        val msg = exportBlockReason.userMessage()
        if (msg.isNotBlank()) {
            snackbarScope.launch {
                snackbarHostState.showSnackbar(
                    message = msg,
                    duration = SnackbarDuration.Long
                )
            }
        }
    }

    BackHandler(enabled = showTextPreview) {
        showTextPreview = false
    }

    // BUG-BIALY-PASEK-STATUSU (11.07): jedyny wywołujący (IncomingDocumentFlow.kt) już
    // opakowuje w Box z statusBarsPadding/navigationBarsPadding na pełnoekranowym Surface —
    // tu tylko tło, bez drugiego statusBarsPadding (podwójny odstęp).
    Box(modifier = Modifier.fillMaxSize().background(LynxColors.Background)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        StatusBanner(
            status = panelStatus,
            maskedTokenCount = maskedTokenCount,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LynxSpacing.md, vertical = LynxSpacing.sm)
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LynxSpacing.md),
            // BUG-PUSTKA-POD-OPISEM (11.07): treść krótsza niż ekran zostawiała pustą
            // przestrzeń tylko na dole (kolumna wyrównana do góry z natury). Wyśrodkowanie
            // dzieli nadmiar miejsca po równo góra/dół; przy dłuższej treści (alerty
            // czerwone/żółte) scroll działa normalnie, wyśrodkowanie nie przeszkadza.
            verticalArrangement = Arrangement.Center
        ) {
            result.qualityWarning?.let {
                QualityWarningCard(it)
                Spacer(modifier = Modifier.height(LynxSpacing.sm))
            }

            if (redHits.isNotEmpty()) {
                RedHitsSection(
                    hits = redHits,
                    onMask = { hit ->
                        val type = guardRedLabelToTokenType(hit.label)
                        val token = nextToken(type)
                        manualMasks = manualMasks + (token to hit.matchedText)
                        onAddToDict?.invoke(hit.matchedText, type)
                    },
                    onLeaveRevealed = { hit ->
                        // Tylko ten dokument — bez GuardAllowlist (nie odmaskowuj na stałe).
                        dismissedHits = dismissedHits + hit.matchedText
                    }
                )
                Spacer(modifier = Modifier.height(LynxSpacing.sm))
            }

            if (hasYellowAlerts) {
                YellowAlertsSection(
                    guardHits = yellowGuardHits,
                    flags = pendingFlags,
                    onMaskGuard = { hit ->
                        val type = guardLabelToTokenType(hit.label)
                        val token = nextToken(type)
                        manualMasks = manualMasks + (token to hit.matchedText)
                        onAddToDict?.invoke(hit.matchedText, type)
                    },
                    onAllowlistGuard = if (onAddToAllowlist != null) { hit ->
                        dismissedHits = dismissedHits + hit.matchedText
                        onAddToAllowlist(hit.matchedText, hit.label)
                    } else null,
                    onMaskFlag = { flag ->
                        val token = nextToken(TOKEN_OSOBA)
                        manualMasks = manualMasks + (token to flag.fragment)
                        flagDecisions[flag.fragment] = EntityDecision.KEEP_HIDDEN
                        onAddToDict?.invoke(flag.fragment, TOKEN_OSOBA)
                    },
                    onDismissFlag = { flag ->
                        flagDecisions[flag.fragment] = EntityDecision.KEEP_HIDDEN
                        onAddToAllowlist?.invoke(flag.fragment, "FLAG")
                    }
                )
                Spacer(modifier = Modifier.height(LynxSpacing.sm))
            }

            if (
                panelStatus == PanelStatusKind.GREEN &&
                maskedTokenCount > 0 &&
                !hasYellowAlerts &&
                redHits.isEmpty()
            ) {
                MaskedSummaryCard(tokenCount = maskedTokenCount)
                Spacer(modifier = Modifier.height(LynxSpacing.lg))
            }

            ActionSummaryCard(
                label = "Podgląd tekstu",
                icon = Icons.Outlined.Visibility,
                onClick = { showTextPreview = true }
            )

            Spacer(modifier = Modifier.height(LynxSpacing.lg))

            DescriptionSection(
                value = descText,
                onValueChange = { descText = it; librarySaved = false }
            )

            Spacer(modifier = Modifier.height(LynxSpacing.md))
        }

        BottomActionBar(
            canExport = canExport,
            copied = copiedDone,
            librarySaved = librarySaved,
            showLibrary = onSaveDescription != null,
            showForward = onForward != null,
            onBlockedClick = { showBlockedHint() },
            onLibrary = {
                onSaveDescription?.invoke(maskedOutputText, descText)
                librarySaved = true
            },
            onCopy = {
                if (isDisclaimerAccepted(context)) {
                    onCopy(outputText)
                    copiedDone = true
                } else {
                    pendingCopyAction = true
                    pendingForwardAction = false
                    showDisclaimer = true
                }
            },
            onForward = onForward?.let { forward ->
                {
                    if (isDisclaimerAccepted(context)) {
                        forward(outputText)
                    } else {
                        pendingForwardAction = true
                        pendingCopyAction = false
                        showDisclaimer = true
                    }
                }
            },
            onDebugLog = onDebugLog,
            onOpenLibrary = onOpenLibrary,
            onSaveDocx = onSaveDocx?.let { save -> { save(outputText) } },
            onSavePdf = onSavePdf?.let { save -> { save(outputText) } },
            onSaveXlsx = onSaveXlsx?.let { save -> { save(outputText) } }
        )
    }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 200.dp, start = LynxSpacing.md, end = LynxSpacing.md)
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(LynxShapes.ButtonRadius),
                containerColor = LynxColors.ActiveNav,
                contentColor = LynxColors.TextPrimary
            )
        }
    }

    if (showDisclaimer) {
        DisclaimerDialog(
            onAccepted = {
                markDisclaimerAccepted(context)
                showDisclaimer = false
                if (pendingCopyAction) {
                    onCopy(outputText)
                    copiedDone = true
                    pendingCopyAction = false
                }
                if (pendingForwardAction) {
                    onForward?.invoke(outputText)
                    pendingForwardAction = false
                }
            }
        )
    }

    if (showTextPreview) {
        TextPreviewModal(
            displayText = maskedDisplayText,
            tokenMap = result.tokenMap,
            revealedTokens = revealedTokens,
            onRevealedTokensChange = { revealedTokens = it },
            onMask = { text, type ->
                val token = nextToken(type)
                manualMasks = manualMasks + (token to text)
                onAddToDict?.invoke(text, type)
            },
            onDismiss = { showTextPreview = false },
            tokenLayers = result.tokenLayers
        )
    }
}

@Composable
private fun StatusBanner(
    status: PanelStatusKind,
    maskedTokenCount: Int,
    modifier: Modifier = Modifier
) {
    val color: Color
    val icon: ImageVector
    val title: String
    val subtitle: String

    when (status) {
        PanelStatusKind.RED -> {
            color = LynxColors.Red
            icon = Icons.Outlined.ErrorOutline
            title = "Wykryto możliwy wyciek danych"
            subtitle = "Zamaskuj czerwone pozycje przed wysłaniem"
        }
        PanelStatusKind.YELLOW -> {
            color = LynxColors.Amber
            icon = Icons.Outlined.WarningAmber
            title = "Sprawdź alerty przed wysłaniem"
            subtitle = "Przejrzyj żółte pozycje poniżej"
        }
        PanelStatusKind.GREEN -> {
            color = LynxColors.Green
            icon = Icons.Outlined.CheckCircle
            title = "Dokument gotowy do wysłania"
            subtitle = if (maskedTokenCount > 0) {
                "Dane zamaskowane — zweryfikuj podgląd przed wysłaniem"
            } else {
                "Nie wykryto danych wymagających uwagi"
            }
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(LynxShapes.CardRadius),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontFamily = LynxTypography.Sans, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = color)
                Text(subtitle, fontFamily = LynxTypography.Sans, fontSize = 12.sp, color = color.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun QualityWarningCard(warning: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LynxShapes.CardRadius),
        colors = CardDefaults.cardColors(containerColor = LynxColors.BlueBg.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, LynxColors.Blue.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = LynxColors.BlueLight, modifier = Modifier.size(16.dp))
            Text(warning, fontFamily = LynxTypography.Sans, fontSize = 13.sp, color = LynxColors.TextSecondary)
        }
    }
}

@Composable
private fun DescriptionSection(
    value: String,
    onValueChange: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Opis dokumentu (opcjonalnie)",
            fontFamily = LynxTypography.Sans,
            fontSize = 12.sp,
            color = LynxColors.TextDim
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "np. Umowa najmu, Sąd — pozwoli znaleźć sesję w bibliotece",
                    fontFamily = LynxTypography.Sans,
                    fontSize = 13.sp
                )
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = LynxTypography.Sans, fontSize = 13.sp, color = LynxColors.TextPrimary),
            shape = RoundedCornerShape(LynxShapes.ButtonRadius),
            // BUG-OPIS-SZARY (11.07): Surface (#2A2F38) odstawało jako jasna karta na
            // bardzo ciemnym tle — Sidebar (#0D0F14) zamiast tego, zgodnie z prośbą "niech
            // będzie czarny".
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LynxColors.Sidebar,
                unfocusedContainerColor = LynxColors.Sidebar,
                focusedBorderColor = LynxColors.Blue,
                unfocusedBorderColor = LynxColors.Border.copy(alpha = 0.7f)
            ),
            trailingIcon = if (value.isNotBlank()) {
                {
                    IconButton(onClick = { focusManager.clearFocus() }) {
                        Icon(Icons.Outlined.Check, contentDescription = "Zatwierdź nazwę", tint = LynxColors.BlueLight)
                    }
                }
            } else null
        )
    }
}

@Composable
private fun BottomActionBar(
    canExport: Boolean,
    copied: Boolean,
    librarySaved: Boolean,
    showLibrary: Boolean,
    showForward: Boolean,
    onBlockedClick: () -> Unit,
    onLibrary: () -> Unit,
    onCopy: () -> Unit,
    onForward: (() -> Unit)?,
    onDebugLog: (() -> Unit)?,
    onOpenLibrary: (() -> Unit)? = null,
    onSaveDocx: (() -> Unit)? = null,
    onSavePdf: (() -> Unit)? = null,
    onSaveXlsx: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = LynxColors.Surface,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
        shape = RoundedCornerShape(topStart = LynxShapes.CardRadius, topEnd = LynxShapes.CardRadius)
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = LynxSpacing.md, vertical = LynxSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
        ) {
            if (showLibrary) {
                BlockedActionSlot(
                    enabled = canExport,
                    onBlockedClick = onBlockedClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LynxSuccessButton(
                        onClick = onLibrary,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canExport
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (librarySaved) "Dodano do biblioteki" else "Dodaj do biblioteki", fontFamily = LynxTypography.Sans)
                    }
                }
                if (librarySaved) {
                    Text(
                        "Zapisano — widoczny w Bibliotece",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = LynxTypography.Sans),
                        color = LynxColors.Green,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    onOpenLibrary?.let { openLib ->
                        LynxBrandButton(onClick = openLib, modifier = Modifier.fillMaxWidth()) {
                            Text("Otwórz bibliotekę", fontFamily = LynxTypography.Sans)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
            ) {
                BlockedActionSlot(
                    enabled = canExport,
                    onBlockedClick = onBlockedClick,
                    modifier = Modifier.weight(1f)
                ) {
                    LynxGhostButton(
                        onClick = onCopy,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = canExport
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = LynxColors.BlueLight, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (copied) "Skopiowano" else "Kopiuj", fontFamily = LynxTypography.Sans, color = LynxColors.BlueLight, maxLines = 1)
                    }
                }
                if (showForward && onForward != null) {
                    BlockedActionSlot(
                        enabled = canExport,
                        onBlockedClick = onBlockedClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        LynxGhostButton(
                            onClick = onForward,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canExport
                        ) {
                            Icon(Icons.Outlined.Send, contentDescription = null, tint = LynxColors.BlueLight, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Wyślij do AI", fontFamily = LynxTypography.Sans, color = LynxColors.BlueLight, maxLines = 1)
                        }
                    }
                }
            }

            // Tylko jeden z trzech (artefakt wskazuje format źródłowy dokumentu) — ale
            // każdy niezależnie opcjonalny, ten sam wzorzec co onSaveDocx.
            if (onSaveDocx != null) {
                BlockedActionSlot(
                    enabled = canExport,
                    onBlockedClick = onBlockedClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LynxFlatRow(
                        label = "Zapisz DOCX",
                        icon = Icons.Outlined.Description,
                        onClick = onSaveDocx,
                        enabled = canExport,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (onSavePdf != null) {
                BlockedActionSlot(
                    enabled = canExport,
                    onBlockedClick = onBlockedClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LynxFlatRow(
                        label = "Zapisz PDF",
                        icon = Icons.Outlined.Description,
                        onClick = onSavePdf,
                        enabled = canExport,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (onSaveXlsx != null) {
                BlockedActionSlot(
                    enabled = canExport,
                    onBlockedClick = onBlockedClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LynxFlatRow(
                        label = "Zapisz Excel",
                        icon = Icons.Outlined.Description,
                        onClick = onSaveXlsx,
                        enabled = canExport,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (onDebugLog != null) {
                LynxGhostButton(onClick = onDebugLog, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Kopiuj logi diagnostyczne",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = LynxTypography.Sans),
                        color = LynxColors.TextDim
                    )
                }
            }
        }
    }
}

@Composable
private fun DisclaimerDialog(onAccepted: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        shape = RoundedCornerShape(LynxShapes.CardRadius),
        title = { Text("Sprawdź wynik przed wysłaniem", fontFamily = LynxTypography.Sans, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "LynxMask automatycznie maskuje dane osobowe, lecz nie gwarantuje wykrycia każdego elementu.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LynxTypography.Sans)
                )
                Text(
                    "Przed skopiowaniem lub wysłaniem przeczytaj zamaskowany tekst i upewnij się, że nie zawiera danych osobowych.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = LynxTypography.Sans)
                )
                Text(
                    "Klikając \"Rozumiem\" potwierdzasz, że zapoznałeś/-aś się z wynikiem.",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = LynxTypography.Sans),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        },
        confirmButton = {
            LynxFilledButton(
                label = "Rozumiem — sprawdziłem/-am wynik",
                onClick = onAccepted,
                modifier = Modifier.fillMaxWidth()
            )
        }
    )
}
