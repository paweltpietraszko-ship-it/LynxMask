package com.lynxmask.app

// PseudonymResultPanel.kt — UI-2 (MASTER sekcja 17)
// Układ scrollowalny: RED → Podgląd → YELLOW → Kopiuj → Biblioteka → Opis.
// TextPreviewModal.kt + AlertListSection.kt — osobne pliki.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.theme.LynxColors

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
    onSaveDescription: ((maskedText: String, description: String) -> Unit)? = null
) {
    val context = LocalContext.current
    var showDisclaimer by remember { mutableStateOf(false) }
    var pendingCopyAction by remember { mutableStateOf(false) }

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

    val canAct = (result.flags.isEmpty() && result.riskScore == RiskScore.GREEN) || allFlagsHandled
    val canSend = canAct && redHits.isEmpty()

    var copiedDone by remember { mutableStateOf(false) }
    var librarySaved by remember { mutableStateOf(false) }
    var descText by remember { mutableStateOf("") }
    var showTextPreview by remember { mutableStateOf(false) }

    val pendingCount = flagDecisions.values.count { it == EntityDecision.PENDING }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        RiskBanner(
            riskScore = result.riskScore,
            pendingCount = pendingCount,
            hasRedHits = redHits.isNotEmpty()
        )

        result.qualityWarning?.let {
            Spacer(modifier = Modifier.height(8.dp))
            QualityWarningCard(it)
        }

        if (redHits.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            RedHitsSection(
                hits = redHits,
                onMask = { hit ->
                    val type = guardRedLabelToTokenType(hit.label)
                    val token = nextToken(type)
                    manualMasks = manualMasks + (token to hit.matchedText)
                    onAddToDict?.invoke(hit.matchedText, type)
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { showTextPreview = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Podgląd tekstu", fontSize = 14.sp)
        }

        if (yellowGuardHits.isNotEmpty() || pendingFlags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
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
                    onAddToAllowlist?.invoke(flag.fragment, TOKEN_OSOBA)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        ActionSection(
            canAct = canAct,
            canSend = canSend,
            copied = copiedDone,
            onCopy = {
                if (isDisclaimerAccepted(context)) {
                    onCopy(outputText); copiedDone = true
                } else {
                    pendingCopyAction = true
                    showDisclaimer = true
                }
            },
            onCancel = onCancel,
            onDebugLog = onDebugLog
        )

        if (onSaveDescription != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    onSaveDescription(maskedOutputText, descText)
                    librarySaved = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSend,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canSend) LynxColors.Green
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(if (librarySaved) "✓ Dodano do biblioteki" else "Dodaj do biblioteki")
            }
            if (librarySaved) {
                Text(
                    "Zapisano — widoczny w Bibliotece",
                    style = MaterialTheme.typography.labelSmall,
                    color = LynxColors.Green,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            DescriptionSection(
                value = descText,
                onValueChange = { descText = it; librarySaved = false }
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
            onDismiss = { showTextPreview = false }
        )
    }
}

@Composable
private fun RiskBanner(riskScore: RiskScore, pendingCount: Int, hasRedHits: Boolean = false) {
    val color: Color
    val emoji: String
    val title: String
    val subtitle: String

    when {
        hasRedHits -> {
            color = LynxColors.Red
            emoji = "✕"
            title = "Wykryto możliwy wyciek danych"
            subtitle = "Zamaskuj czerwone pozycje poniżej przed wysłaniem"
        }
        pendingCount > 0 -> {
            color = LynxColors.Amber
            emoji = "⚠️"
            title = "Sprawdź $pendingCount ${pendingCount.flagWord()} przed wysłaniem"
            subtitle = "Przejrzyj żółte alerty poniżej"
        }
        riskScore == RiskScore.GREEN -> {
            color = LynxColors.Green
            emoji = "✓"
            title = "Dokument gotowy do wysłania"
            subtitle = "Nie wykryto danych wymagających uwagi"
        }
        riskScore == RiskScore.YELLOW -> {
            color = LynxColors.Amber
            emoji = "⚠️"
            title = "Znaleziono dane wrażliwe"
            subtitle = "Przejrzyj żółte alerty poniżej"
        }
        else -> {
            color = LynxColors.Red
            emoji = "✕"
            title = "Dokument zawiera dane osobowe"
            subtitle = "Upewnij się że chcesz wysłać tę wersję"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 16.sp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = color)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.75f))
            }
        }
    }
}

private fun Int.flagWord() = when (this) {
    1       -> "miejsce"
    in 2..4 -> "miejsca"
    else    -> "miejsc"
}

@Composable
private fun QualityWarningCard(warning: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = LynxColors.BlueBg.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text("⚠", fontSize = 14.sp, color = LynxColors.Blue)
            Text(warning, style = MaterialTheme.typography.labelMedium, color = LynxColors.TextSecondary)
        }
    }
}

@Composable
private fun DescriptionSection(
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Opis dokumentu (opcjonalnie)",
            style = MaterialTheme.typography.labelSmall,
            color = LynxColors.TextDim
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("np. Umowa najmu, Sąd — pozwoli znaleźć sesję w bibliotece") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(2.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LynxColors.Blue,
                unfocusedBorderColor = LynxColors.Border
            )
        )
    }
}

@Composable
private fun ActionSection(
    canAct: Boolean,
    canSend: Boolean = canAct,
    copied: Boolean,
    onCopy: () -> Unit,
    onCancel: (() -> Unit)?,
    onDebugLog: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onCopy,
            modifier = Modifier.fillMaxWidth(),
            enabled = canSend,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (copied) "✓ Skopiowano" else "Kopiuj dokument")
        }

        if (!canAct) {
            Text(
                "Obsłuż oznaczone pozycje aby odblokować",
                style = MaterialTheme.typography.labelSmall,
                color = LynxColors.Amber,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Anuluj", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (onDebugLog != null) {
            TextButton(onClick = onDebugLog, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Kopiuj logi diagnostyczne",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun DisclaimerDialog(onAccepted: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},  // celowo zablokowane — wymaga świadomej akceptacji
        title = {
            // AUDYT-PRAWNIK: tytuł do zatwierdzenia przez prawnika
            Text("Sprawdź wynik przed wysłaniem", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // AUDYT-PRAWNIK: treść do zatwierdzenia przez prawnika przed wdrożeniem produkcyjnym.
                // Obecny tekst to placeholder — może nie spełniać wymogów RODO art. 5 ust. 1 lit. f.
                Text(
                    "LynxMask automatycznie maskuje dane osobowe, lecz nie gwarantuje wykrycia każdego elementu.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Przed skopiowaniem lub wysłaniem przeczytaj zamaskowany tekst i upewnij się, że nie zawiera danych osobowych.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Klikając \"Rozumiem\" potwierdzasz, że zapoznałeś/-aś się z wynikiem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onAccepted,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Rozumiem — sprawdziłem/-am wynik")
            }
        }
    )
}
