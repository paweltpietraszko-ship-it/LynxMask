package com.lynxmask.app

// PseudonymResultPanel.kt — Wersja 1.6
//
// Zmiany vs v1.5:
//
//   POTOK 6 — Zadanie 0b (08.06.2026):
//   Migracja palety kolorów: usunięto internal object LynxColors (linie 77–85),
//   dodano import com.lynxmask.app.ui.theme.LynxColors.
//
//   Tabela zamian (liczba wystąpień w raporcie):
//     brand   → Sidebar    (~17)
//     accent  → Blue       (~20)
//     accent2 → BlueBg     (~1)
//     safe    → Green      (~9)
//     warning → Amber      (~10)
//     danger  → Red        (~1)
//     neutral → TextMuted  (~2)
//
//   UWAGA (raport): brand → Sidebar (0xFF0D0F14) to kolor tła paska bocznego,
//   nie kolor tekstu. W starym kodzie brand (0xFF1A3562) był ciemnym granatem
//   używanym jako akcent na jasnym tle. W nowym ciemnym motywie LynxColors.Blue
//   jest prawie czarny — tekst nim kolorowany będzie niewidoczny na LynxColors.Background.
//   Mapping zgodny z briefem. Jeśli kolory wyglądają źle: zamienić Sidebar → BlueBg
//   lub TextSecondary w miejscach użycia jako kolor tekstu.
//
//   ZADANIE 6: Stringsłowne — w v1.5 już użyte "Zamaskuj", "Maskuj".
//   Nie znaleziono pozostałych słów do zamiany (0 wystąpień "Pseudonimizuj" itp.).
//
// Zmiany vs v1.4:
//
//   REDESIGN LAYOUTU — modal-first (Potok 1, 07.06.2026):
//   Ekrany telefonów są za małe na "ekran w ekranie" — każda funkcja
//   dostaje własny fullscreen Dialog z możliwością nawigowania.
//
//   Główny ekran (SHARE_SHEET) — teraz hub nawigacyjny:
//     - RiskBanner (zawsze widoczny)
//     - QualityWarningCard (opcjonalnie)
//     - Kompaktowy TextPreviewCard (min 80dp, max 200dp) + ⛶ do pełnego widoku
//     - Spacer(weight) — przyciski zawsze przy dolnej krawędzi
//     - Row: przycisk "Encje" (dynamiczny kolor/label) + "Dodaj fragment"
//     - ActionSection
//   Główny ekran NIE jest scrollowalny — wszystko mieści się na ekranie.
//
//   EncjeDialog (fullscreen Dialog):
//     - EntityChipRow
//     - FlagsSection (domyślnie rozwinięta — użytkownik otworzył po to)
//     - Każda flaga otwiera EntityDecisionFullScreen (Box overlay w dialogu)
//     - Stan entityDialogFor lokalny w EncjeDialog
//
//   ManualDialog (fullscreen Dialog):
//     - ManualTokenSection z horizontalScroll na chipach (BUG fix)
//     - selectedText z zewnątrz pre-wypełnia pole
//     - Użytkownik może dodać wiele fragmentów zanim zamknie dialog
//
//   BUG FIX — ManualTokenSection: Row z FilterChipami owinięty w
//   horizontalScroll — 5 chipów (incl. TOKEN_KWOTA) mieści się na każdym
//   ekranie bez ucięcia.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.theme.LynxColors

// ─────────────────────────────────────────────────────────────────────────────
// Typy publiczne
// ─────────────────────────────────────────────────────────────────────────────

enum class PanelMode { SHARE_SHEET }

// ─────────────────────────────────────────────────────────────────────────────
// Typy wewnętrzne
// ─────────────────────────────────────────────────────────────────────────────

internal enum class EntityDecision { PENDING, KEEP_HIDDEN, REVEALED }

// ─────────────────────────────────────────────────────────────────────────────
// Główny composable — hub nawigacyjny
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("UNUSED_PARAMETER") // Publiczne API — ShareTargetActivity przekazuje mode; zarezerwowane na przyszłość
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
    // Analogiczne do pola "Opis dokumentu" na desktopie.
    // Null = nie pokazuj pola (tryb gdzie opis nie jest potrzebny).
    onSaveDescription: ((maskedText: String, description: String) -> Unit)? = null
) {
    val flagDecisions = remember(result.flags) {
        mutableStateMapOf<String, EntityDecision>().also { map ->
            result.flags.forEach { map[it.fragment] = EntityDecision.PENDING }
        }
    }
    var revealedTokens by remember { mutableStateOf(setOf<String>()) }
    var manualMasks by remember { mutableStateOf(mapOf<String, String>()) }

    val outputText by remember(revealedTokens, manualMasks, result) {
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
            revealedTokens.forEach { tok ->
                result.tokenMap[tok]?.let { original -> t = t.replace(tok, original) }
            }
            t
        }
    }

    val displayText by remember(outputText, result) {
        derivedStateOf { outputText.removePrefix("SESJA_${result.sessionId}\n") }
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
            }.distinctBy { it.label to it.matchedText }
        }
    }

    val canAct = (result.flags.isEmpty() && result.riskScore == RiskScore.GREEN) || allFlagsHandled
    var copiedDone by remember { mutableStateOf(false) }
    var selectedText by remember { mutableStateOf("") }
    val pendingCount = flagDecisions.values.count { it == EntityDecision.PENDING }

    // Stan dialogów
    var showEncjeDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showTextPreviewDialog by remember { mutableStateOf(false) }

    // ── Główny ekran — hub nawigacyjny ───────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Status — zawsze widoczny u góry
        RiskBanner(
            riskScore = result.riskScore,
            pendingCount = pendingCount,
            hasRedHits = activeGuardHits.any { it.level == "RED" }
        )

        result.qualityWarning?.let {
            Spacer(modifier = Modifier.height(8.dp))
            QualityWarningCard(it)
        }

        if (activeGuardHits.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            GuardHitsSection(
                guardHits = activeGuardHits,
                onMask = { matchedText ->
                    selectedText = matchedText
                    showManualDialog = true
                },
                onAllowlist = if (onAddToAllowlist != null) { hit ->
                    dismissedHits = dismissedHits + hit.matchedText
                    onAddToAllowlist(hit.matchedText, hit.label)
                } else null
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { showTextPreviewDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("📄 Podgląd tekstu", fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Przyciski nawigacji do funkcji ───────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Przycisk Encje — kolor i etykieta zależne od stanu flag
            if (pendingCount > 0) {
                Button(
                    onClick = { showEncjeDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LynxColors.Amber)
                ) {
                    Text(
                        "⚠ $pendingCount do sprawdzenia",
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = { showEncjeDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "👁 ${result.tokenMap.size} ukrytych",
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }
            }

            // Przycisk Dodaj fragment
            FilledTonalButton(
                onClick = { showManualDialog = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("✏️ Dodaj", fontSize = 13.sp, maxLines = 1)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Przyciski akcji
        ActionSection(
            canAct = canAct,
            canSend = canAct && activeGuardHits.none { it.level == "RED" },
            copied = copiedDone,
            onCopy = { onCopy(outputText); copiedDone = true },
            onForward = if (onForward != null) ({ onForward(outputText) }) else null,
            onCancel = onCancel,
            onDebugLog = onDebugLog
        )

        // ── Pole opisu dokumentu — analogiczne do desktopa ───────────────────
        // Widoczne tylko gdy ShareTargetActivity przekazuje onSaveDescription.
        if (onSaveDescription != null) {
            Spacer(modifier = Modifier.height(12.dp))
            DescriptionSection(onSave = { desc -> onSaveDescription(outputText, desc) })
        }
    }

    // ── Dialog: Encje i flagi ─────────────────────────────────────────────────
    if (showEncjeDialog) {
        EncjeDialog(
            result = result,
            flagDecisions = flagDecisions,
            revealedTokens = revealedTokens,
            onRevealedTokensChange = { revealedTokens = it },
            onAddToDict = onAddToDict,
            onAddToAllowlist = if (onAddToAllowlist != null) { value ->
                onAddToAllowlist(value, "OSOBA")
            } else null,
            onMaskFragment = { fragment, type ->
                val token = nextToken(type)
                manualMasks = manualMasks + (token to fragment)
            },
            onDismiss = { showEncjeDialog = false }
        )
    }

    // ── Dialog: Ręczne dodawanie fragmentów ──────────────────────────────────
    if (showManualDialog) {
        ManualDialog(
            selectedText = selectedText,
            onMask = { text, type ->
                val token = nextToken(type)
                manualMasks = manualMasks + (token to text)
                onAddToDict?.invoke(text, type)
            },
            onDismiss = { showManualDialog = false }
        )
    }

    // ── Dialog: Podgląd tekstu ────────────────────────────────────────────────
    if (showTextPreviewDialog) {
        Dialog(
            onDismissRequest = { showTextPreviewDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showTextPreviewDialog = false }) {
                            Text("← Zamknij", style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            "Bezpieczna wersja",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LynxColors.Blue
                        )
                        Text(
                            "${displayText.length} zn.",
                            style = MaterialTheme.typography.labelSmall,
                            color = LynxColors.TextDim,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                    HorizontalDivider(color = LynxColors.Blue.copy(alpha = 0.15f))
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialog: Encje i flagi
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EncjeDialog(
    result: PseudonymResult,
    flagDecisions: MutableMap<String, EntityDecision>,
    revealedTokens: Set<String>,
    onRevealedTokensChange: (Set<String>) -> Unit,
    onAddToDict: ((String, String) -> Unit)?,
    onAddToAllowlist: ((String) -> Unit)? = null,
    onMaskFragment: (fragment: String, type: String) -> Unit,
    onDismiss: () -> Unit
) {
    // Stan decyzji encji lokalny dla dialogu
    var entityDialogFor by remember { mutableStateOf<PseudonymFlag?>(null) }
    val pendingCount = flagDecisions.values.count { it == EntityDecision.PENDING }

    Dialog(
        onDismissRequest = {
            // Jeśli EntityDecisionFullScreen otwarty — zamknij go, nie dialog
            if (entityDialogFor != null) entityDialogFor = null
            else onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Główna zawartość dialogu
                Column(modifier = Modifier.fillMaxSize()) {

                    // Nagłówek
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("← Zamknij", style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            "Encje i flagi",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleMedium,
                            color = LynxColors.Blue
                        )
                        // Status badge
                        if (pendingCount > 0) {
                            Text(
                                "$pendingCount ◉",
                                style = MaterialTheme.typography.labelMedium,
                                color = LynxColors.Amber,
                                modifier = Modifier.padding(end = 12.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.width(72.dp))
                        }
                    }

                    HorizontalDivider(color = LynxColors.Blue.copy(alpha = 0.15f))

                    // Zawartość — scrollowalna
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Chipy encji
                        if (result.tokenMap.isNotEmpty()) {
                            EntityChipRow(
                                tokenMap = result.tokenMap,
                                revealedTokens = revealedTokens,
                                flags = result.flags,
                                flagDecisions = flagDecisions,
                                onChipTap = { flag -> entityDialogFor = flag }
                            )
                        } else {
                            Text(
                                "Brak wykrytych encji",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Flagi — domyślnie rozwinięte (użytkownik otworzył dialog po to)
                        if (result.flags.isNotEmpty()) {
                            FlagsSection(
                                flags = result.flags,
                                decisions = flagDecisions,
                                defaultExpanded = true,
                                onFlagTap = { flag -> entityDialogFor = flag }
                            )
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = LynxColors.Green.copy(alpha = 0.08f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("✓", fontSize = 20.sp, color = LynxColors.Green)
                                    Text(
                                        "Nie wykryto fragmentów wymagających decyzji",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LynxColors.Green
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // Overlay: pełnoekranowa decyzja encji
                entityDialogFor?.let { flag ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        EntityDecisionFullScreen(
                            flag = flag,
                            onKeepHidden = {
                                onMaskFragment(flag.fragment, "OSOBA")
                                flagDecisions[flag.fragment] = EntityDecision.KEEP_HIDDEN
                                entityDialogFor = null
                            },
                            onReveal = {
                                val token = result.tokenMap.entries.firstOrNull { (_, v) ->
                                    v == flag.fragment ||
                                    flag.fragment.contains(v) ||
                                    v.contains(flag.fragment)
                                }?.key
                                if (token != null) onRevealedTokensChange(revealedTokens + token)
                                flagDecisions[flag.fragment] = EntityDecision.REVEALED
                                entityDialogFor = null
                            },
                            onAddToDict = if (onAddToDict != null) ({ type: String ->
                                onAddToDict(flag.fragment, type)
                                onMaskFragment(flag.fragment, type)
                                flagDecisions[flag.fragment] = EntityDecision.KEEP_HIDDEN
                                entityDialogFor = null
                            }) else null,
                            onAddToAllowlist = if (onAddToAllowlist != null) ({
                                onAddToAllowlist(flag.fragment)
                                flagDecisions[flag.fragment] = EntityDecision.KEEP_HIDDEN
                                entityDialogFor = null
                            }) else null,
                            onDismiss = { entityDialogFor = null }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dialog: Ręczne dodawanie fragmentów
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ManualDialog(
    selectedText: String,
    onMask: (text: String, type: String) -> Unit,
    onDismiss: () -> Unit
) {
    // Lista dodanych w tej sesji dialogu — informacja zwrotna dla użytkownika
    var maskedInSession by remember { mutableStateOf(listOf<Pair<String, String>>()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Nagłówek
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("← Zamknij", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        "Dodaj fragment",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        color = LynxColors.Blue
                    )
                    Spacer(modifier = Modifier.width(80.dp))
                }

                HorizontalDivider(color = LynxColors.Blue.copy(alpha = 0.15f))

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Wpisz lub wklej fragment który silnik pominął. " +
                        "Wybierz typ i kliknij Maskuj. " +
                        "Możesz dodać kilka fragmentów zanim zamkniesz okno.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Sekcja dodawania
                    ManualTokenSection(
                        selectedText = selectedText,
                        onMask = { text, type ->
                            onMask(text, type)
                            maskedInSession = maskedInSession + (text to type)
                        }
                    )

                    // Historia dodanych w tej sesji
                    if (maskedInSession.isNotEmpty()) {
                        Text(
                            "Dodane w tej sesji:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = LynxColors.TextSecondary
                        )
                        maskedInSession.forEach { (text, type) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "✓",
                                    color = LynxColors.Green,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "\"$text\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    type,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LynxColors.Blue
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sekcja wycieeków OutputGuard — widoczna gdy guardHits niepusta
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GuardHitsSection(
    guardHits: List<GuardHit>,
    onMask: (String) -> Unit,
    onAllowlist: ((GuardHit) -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = LynxColors.Red.copy(alpha = 0.07f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            guardHits.forEach { hit ->
                val isRed = hit.level == "RED"
                val color = if (isRed) LynxColors.Red else LynxColors.Amber
                val prefix = if (isRed) "⚠ WYCIEK: " else ""
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "$prefix${hit.label} — ${hit.matchedText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = color
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onMask(hit.matchedText) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Maskuj", fontSize = 11.sp, color = color)
                        }
                        if (!isRed && onAllowlist != null) {
                            TextButton(
                                onClick = { onAllowlist(hit) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("Nie maskuj", fontSize = 11.sp, color = LynxColors.TextMuted)
                            }
                        }
                    }
                }
                if (hit != guardHits.last()) {
                    HorizontalDivider(color = color.copy(alpha = 0.12f))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Baner statusu
// ─────────────────────────────────────────────────────────────────────────────

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
            subtitle = "Dotknij czerwonej pozycji poniżej aby zamaskować"
        }
        pendingCount > 0 -> {
            color = LynxColors.Amber
            emoji = "⚠️"
            title = "Sprawdź $pendingCount ${pendingCount.flagWord()} przed wysłaniem"
            subtitle = "Dotknij \"⚠ $pendingCount do sprawdzenia\" aby przejrzeć"
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
            subtitle = "Sprawdź encje przed wysłaniem"
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

// ─────────────────────────────────────────────────────────────────────────────
// Ostrzeżenie jakości OCR
// ─────────────────────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────────────────────
// Chipy encji — używane w EncjeDialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EntityChipRow(
    tokenMap: Map<String, String>,
    revealedTokens: Set<String>,
    flags: List<PseudonymFlag>,
    flagDecisions: Map<String, EntityDecision>,
    onChipTap: (PseudonymFlag) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "${tokenMap.size} danych ukrytych",
            style = MaterialTheme.typography.labelMedium,
            color = LynxColors.TextSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tokenMap.entries.forEach { (token, original) ->
                val isRevealed = revealedTokens.contains(token)
                val matchingFlag = flags.firstOrNull { flag ->
                    original == flag.fragment ||
                    original.contains(flag.fragment) ||
                    flag.fragment.contains(original)
                }
                val isPending = matchingFlag != null &&
                    flagDecisions[matchingFlag.fragment] == EntityDecision.PENDING

                val chipColor = when {
                    isPending  -> LynxColors.Amber
                    isRevealed -> LynxColors.TextMuted
                    else       -> LynxColors.Blue
                }

                SuggestionChip(
                    onClick = { matchingFlag?.let { onChipTap(it) } },
                    label = {
                        Text(
                            tokenTypeIcon(token) + " " +
                            (original.take(18) + if (original.length > 18) "…" else ""),
                            fontSize = 12.sp
                        )
                    },
                    enabled = matchingFlag != null,
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = chipColor.copy(alpha = 0.12f),
                        labelColor = chipColor
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = chipColor.copy(alpha = 0.35f)
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sekcja flag — używana w EncjeDialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FlagsSection(
    flags: List<PseudonymFlag>,
    decisions: Map<String, EntityDecision>,
    defaultExpanded: Boolean = true,
    onFlagTap: (PseudonymFlag) -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val pendingCount = decisions.values.count { it == EntityDecision.PENDING }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = LynxColors.ActiveNav
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (pendingCount > 0) LynxColors.Amber else LynxColors.Green)
                    )
                    Text(
                        if (pendingCount > 0) "$pendingCount do sprawdzenia"
                        else "Wszystko sprawdzone",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (pendingCount > 0) LynxColors.Amber else LynxColors.Green
                    )
                }
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (expanded) "Zwiń" else "Rozwiń",
                        style = MaterialTheme.typography.labelMedium,
                        color = LynxColors.Blue
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(spring()),
                exit = shrinkVertically(spring())
            ) {
                Column {
                    HorizontalDivider(color = LynxColors.Blue.copy(alpha = 0.15f))
                    flags.forEach { flag ->
                        val decision = decisions[flag.fragment] ?: EntityDecision.PENDING
                        FlagRow(flag = flag, decision = decision, onTap = { onFlagTap(flag) })
                        if (flag != flags.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = LynxColors.Blue.copy(alpha = 0.08f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlagRow(flag: PseudonymFlag, decision: EntityDecision, onTap: () -> Unit) {
    val indicatorColor = when (decision) {
        EntityDecision.PENDING     -> LynxColors.Amber
        EntityDecision.KEEP_HIDDEN -> LynxColors.Green
        EntityDecision.REVEALED    -> LynxColors.TextMuted
    }
    val indicatorEmoji = when (decision) {
        EntityDecision.PENDING     -> "○"
        EntityDecision.KEEP_HIDDEN -> "✓"
        EntityDecision.REVEALED    -> "👁"
    }
    val textAlpha = if (decision == EntityDecision.REVEALED) 0.5f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = decision == EntityDecision.PENDING, onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            indicatorEmoji,
            fontSize = 14.sp,
            color = indicatorColor,
            modifier = Modifier.width(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                flag.fragment.take(45) + if (flag.fragment.length > 45) "…" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (decision == EntityDecision.PENDING) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha)
            )
            Text(
                flag.reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha)
            )
        }
        if (decision == EntityDecision.PENDING) {
            Text("›", fontSize = 20.sp, color = LynxColors.Blue)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pełnoekranowa decyzja encji — overlay w EncjeDialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EntityDecisionFullScreen(
    flag: PseudonymFlag,
    onKeepHidden: () -> Unit,
    onReveal: () -> Unit,
    onAddToDict: ((String) -> Unit)?,
    onAddToAllowlist: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var selectedType by remember { mutableStateOf(TOKEN_OSOBA) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Text("← Wróć", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                "Decyzja",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                color = LynxColors.Blue
            )
            Spacer(modifier = Modifier.width(72.dp))
        }

        HorizontalDivider(color = LynxColors.Blue.copy(alpha = 0.15f))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = LynxColors.Blue.copy(alpha = 0.08f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Fragment dokumentu:",
                    style = MaterialTheme.typography.labelMedium,
                    color = LynxColors.TextMuted
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    flag.fragment,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = LynxColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    flag.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            "Co chcesz zrobić z tym fragmentem?",
            style = MaterialTheme.typography.titleSmall,
            color = LynxColors.Blue
        )

        Button(
            onClick = onKeepHidden,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LynxColors.Green)
        ) {
            Text("🔒  Zostaw ukryte", fontSize = 15.sp)
        }

        OutlinedButton(
            onClick = onReveal,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("👁  Odkryj — wyślę te dane świadomie", fontSize = 15.sp)
        }

        if (onAddToDict != null) {
            HorizontalDivider(color = LynxColors.Blue.copy(alpha = 0.15f))

            Text(
                "Zamaskuj i zapamiętaj jako:",
                style = MaterialTheme.typography.labelMedium,
                color = LynxColors.TextSecondary
            )

            // Selektor typu — BUG-16 FIX
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(TOKEN_OSOBA, TOKEN_FIRMA, TOKEN_ADRES, TOKEN_NUMER).forEach { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(type) }
                    )
                }
            }

            TextButton(
                onClick = { onAddToDict(selectedType) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📚  Zamaskuj i zapamiętaj na przyszłość", fontSize = 15.sp)
            }
        }

        if (onAddToAllowlist != null) {
            HorizontalDivider(color = LynxColors.Blue.copy(alpha = 0.15f))

            Text(
                "To nie są dane osobowe:",
                style = MaterialTheme.typography.labelMedium,
                color = LynxColors.TextSecondary
            )

            TextButton(
                onClick = onAddToAllowlist,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "✗  Nie maskuj — to śmieci, zapamiętaj",
                    fontSize = 15.sp,
                    color = LynxColors.TextMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pole opisu dokumentu — widoczne po pseudonimizacji w ShareTargetActivity
// Analogiczne do "Opis dokumentu (szyfrowany)" na desktopie Pseudominizer
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DescriptionSection(onSave: (String) -> Unit) {
    var descText  by remember { mutableStateOf("") }
    var descSaved by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Opis dokumentu (opcjonalnie)",
            style = MaterialTheme.typography.labelSmall,
            color = LynxColors.TextDim
        )
        OutlinedTextField(
            value         = descText,
            onValueChange = { descText = it; descSaved = false },
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text("np. Umowa najmu, Sąd — pozwoli znaleźć sesję w bibliotece") },
            singleLine    = true,
            textStyle     = MaterialTheme.typography.bodySmall,
            shape         = RoundedCornerShape(2.dp),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = LynxColors.Blue,
                unfocusedBorderColor = LynxColors.Border
            ),
            trailingIcon = {
                if (descText.isNotBlank()) {
                    IconButton(onClick = { onSave(descText); descSaved = true }) {
                        Text(
                            if (descSaved) "✓" else "↓",
                            fontSize = 14.sp,
                            color    = if (descSaved) LynxColors.Green else LynxColors.Blue
                        )
                    }
                }
            }
        )
        if (descSaved) {
            Text(
                "Zapisano — widoczny w Bibliotece",
                style = MaterialTheme.typography.labelSmall,
                color = LynxColors.Green
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ręczne maskowanie — używane w ManualDialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ManualTokenSection(
    selectedText: String = "",
    onMask: (text: String, type: String) -> Unit
) {
    var inputText by remember(selectedText) { mutableStateOf(selectedText) }
    var selectedType by remember { mutableStateOf(TOKEN_OSOBA) }
    // BUG-17 FIX: dodano TOKEN_KWOTA
    val types = listOf(TOKEN_OSOBA, TOKEN_FIRMA, TOKEN_ADRES, TOKEN_NUMER, TOKEN_KWOTA)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = LynxColors.ActiveNav
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tekst do zamaskowania") },
                placeholder = { Text("np. Jan Kowalski") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )

            // BUG FIX: horizontalScroll — 5 chipów mieści się na każdym ekranie
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                types.forEach { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(type) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LynxColors.Blue.copy(alpha = 0.15f),
                            selectedLabelColor = LynxColors.Blue
                        )
                    )
                }
            }

            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onMask(inputText.trim(), selectedType)
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = LynxColors.Blue,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text("Maskuj", fontSize = 15.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Przyciski akcji
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActionSection(
    canAct: Boolean,
    canSend: Boolean = canAct,
    copied: Boolean,
    onCopy: () -> Unit,
    onForward: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    onDebugLog: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onForward != null) {
            Button(
                onClick = onForward,
                modifier = Modifier.fillMaxWidth(),
                enabled = canSend,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canSend) LynxColors.Blue
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text("Wy\u015blij do Claude")
            }
        }

        OutlinedButton(
            onClick = onCopy,
            modifier = Modifier.fillMaxWidth(),
            enabled = canSend,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(if (copied) "\u2713 Skopiowano" else "Kopiuj dokument")
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
                    "🔧 Kopiuj logi diagnostyczne",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pomocnicze
// ─────────────────────────────────────────────────────────────────────────────

private fun tokenTypeIcon(token: String) = when {
    token.startsWith("OSOBA") -> "👤"
    token.startsWith("ADRES") -> "📍"
    token.startsWith("NUMER") -> "🔢"
    token.startsWith("KWOTA") -> "💰"
    token.startsWith("FIRMA") -> "🏢"
    else                       -> "•"
}
