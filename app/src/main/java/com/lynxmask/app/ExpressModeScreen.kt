package com.lynxmask.app

// ExpressModeScreen.kt — v1.0
// Darmowy tryb bez logowania, biblioteki i depseudonimizacji.
// Flow: wklej tekst → pseudonimizuj → kopiuj wynik.
// Cel: pierwsze doświadczenie z LynxMask przed pełną wersją.
//
// Brak: SessionStore, biblioteki, depseudo, UserDictionary, GuardAllowlist.
// Silnik: PseudonymEngine.pseudonymize() — ten sam co w pełnej wersji.

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxPrimaryButton
import com.lynxmask.app.ui.components.LynxSecondaryButton
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExpressModeScreen(onExit: () -> Unit) {
    val context   = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope     = rememberCoroutineScope()

    var inputText    by remember { mutableStateOf("") }
    var resultText   by remember { mutableStateOf("") }
    var tokenCount   by remember { mutableIntStateOf(0) }
    var isProcessing by remember { mutableStateOf(false) }
    var copied       by remember { mutableStateOf(false) }
    var maskInput    by remember { mutableStateOf("") }
    var maskFeedback by remember { mutableStateOf("") }

    fun reset() {
        inputText = ""; resultText = ""; tokenCount = 0
        copied = false; maskInput = ""; maskFeedback = ""
    }

    Column(modifier = Modifier.fillMaxSize().background(LynxColors.Background)) {

        // ── Nagłówek ─────────────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth().background(LynxColors.Sidebar)
        ) {
            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LynxSpacing.md, vertical = LynxSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
                ) {
                    Text(
                        "PSEUDONIMIZUJ",
                        fontFamily    = LynxTypography.Mono,
                        fontSize      = 12.sp,
                        color         = LynxColors.Blue,
                        letterSpacing = 1.5.sp
                    )
                    Surface(
                        shape  = RoundedCornerShape(4.dp),
                        color  = LynxColors.BlueBg,
                        border = BorderStroke(0.5.dp, LynxColors.Amber)
                    ) {
                        Text(
                            "⚡ Express",
                            fontFamily    = LynxTypography.Mono,
                            fontSize      = 10.sp,
                            color         = LynxColors.Amber,
                            letterSpacing = 0.5.sp,
                            modifier      = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                LynxGhostButton(onClick = onExit) {
                    Text("← Zaloguj się", fontSize = 13.sp, color = LynxColors.TextDim)
                }
            }
        }

        when {
            isProcessing -> {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = LynxColors.Blue)
                }
            }

            resultText.isNotEmpty() -> {
                // ── EKRAN 2: wynik ────────────────────────────────────────────
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(LynxSpacing.md)
                    ) {
                        val annotated = remember(resultText) { tokenAnnotated(resultText) }
                        SelectionContainer {
                            Text(
                                text       = annotated,
                                modifier   = Modifier.verticalScroll(rememberScrollState()),
                                fontSize   = 14.sp,
                                lineHeight = 22.sp
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LynxColors.Surface)
                            .navigationBarsPadding()
                            .padding(LynxSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(LynxSpacing.sm)
                    ) {
                        if (tokenCount > 0) {
                            Text(
                                "$tokenCount wykrytych encji",
                                fontFamily = LynxTypography.Mono,
                                fontSize   = 11.sp,
                                color      = LynxColors.Green
                            )
                        }

                        // Ręczne maskowanie (np. fraza pominięta przez silnik)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value         = maskInput,
                                onValueChange = { maskInput = it; maskFeedback = "" },
                                modifier      = Modifier.weight(1f),
                                placeholder   = {
                                    Text(
                                        "Zamaskuj frazę...",
                                        fontSize = 12.sp,
                                        color    = LynxColors.TextDim
                                    )
                                },
                                singleLine = true,
                                shape      = RoundedCornerShape(LynxShapes.CardRadius),
                                colors     = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor   = LynxColors.Blue,
                                    unfocusedBorderColor = LynxColors.Border
                                )
                            )
                            LynxSecondaryButton(
                                onClick  = {
                                    val phrase = maskInput.trim()
                                    if (phrase.isBlank()) return@LynxSecondaryButton
                                    val occurrences = resultText.split(phrase).size - 1
                                    resultText = resultText.replace(phrase, "█████")
                                    maskFeedback = if (occurrences > 0) "Zamaskowano $occurrences wyst." else "Nie znaleziono"
                                    maskInput = ""; copied = false
                                },
                                enabled = maskInput.isNotBlank()
                            ) {
                                Text("Zamaskuj", fontSize = 13.sp)
                            }
                        }

                        if (maskFeedback.isNotEmpty()) {
                            Text(maskFeedback, fontSize = 12.sp, color = LynxColors.TextDim)
                        }

                        LynxPrimaryButton(
                            onClick  = {
                                clipboard.setText(AnnotatedString(resultText))
                                copied = true
                                Toast.makeText(context, "Skopiowano do schowka", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (copied) "✓ Skopiowano" else "Kopiuj wynik",
                                fontSize   = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color      = if (copied) LynxColors.Green else LynxColors.TextPrimary
                            )
                        }

                        LynxGhostButton(
                            onClick  = { reset() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Nowy tekst", fontSize = 13.sp, color = LynxColors.TextDim)
                        }
                    }
                }
            }

            else -> {
                // ── EKRAN 1: wklejanie ────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(LynxSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(LynxSpacing.md)
                ) {
                    Text(
                        "TEKST DO PSEUDONIMIZACJI",
                        fontFamily    = LynxTypography.Mono,
                        fontSize      = 10.sp,
                        color         = LynxColors.Blue,
                        letterSpacing = 1.sp
                    )
                    OutlinedTextField(
                        value         = inputText,
                        onValueChange = { inputText = it },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp),
                        placeholder   = {
                            Text(
                                "Wklej tekst z danymi osobowymi — imiona, PESEL, NIP, adresy, numery telefonów...",
                                color      = LynxColors.TextDim,
                                fontSize   = 13.sp,
                                lineHeight = 18.sp
                            )
                        },
                        shape  = RoundedCornerShape(LynxShapes.CardRadius),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = LynxColors.Blue,
                            unfocusedBorderColor = LynxColors.Border
                        )
                    )

                    if (inputText.isNotBlank()) {
                        Text(
                            "${inputText.length} znaków",
                            fontSize = 12.sp,
                            color    = LynxColors.TextDim
                        )
                    }

                    LynxPrimaryButton(
                        onClick  = {
                            val text = inputText.trim()
                            if (text.isBlank()) return@LynxPrimaryButton
                            isProcessing = true
                            scope.launch {
                                val result = withContext(Dispatchers.Default) {
                                    PseudonymEngine.pseudonymize(text)
                                }
                                resultText = result.pseudonymizedText
                                tokenCount = result.tokenMap.size
                                isProcessing = false
                            }
                        },
                        enabled  = inputText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pseudonimizuj", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }

                    HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)

                    Text(
                        "Express Mode nie zapisuje sesji ani nie umożliwia odkrycia danych. Jeśli potrzebujesz depseudonimizacji — zaloguj się.",
                        fontSize   = 12.sp,
                        color      = LynxColors.TextDim,
                        lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

private fun tokenAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    var lastIndex = 0
    TOKEN_RE.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            withStyle(SpanStyle(color = LynxColors.TextPrimary)) {
                append(text.substring(lastIndex, match.range.first))
            }
        }
        withStyle(
            SpanStyle(
                color      = LynxColors.Blue,
                fontWeight = FontWeight.Medium,
                background = LynxColors.BlueBg
            )
        ) {
            append(match.value)
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        withStyle(SpanStyle(color = LynxColors.TextPrimary)) {
            append(text.substring(lastIndex))
        }
    }
}
