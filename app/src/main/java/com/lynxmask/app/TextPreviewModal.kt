package com.lynxmask.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lynxmask.app.ui.components.LynxFilledButton
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxTypography
import kotlinx.coroutines.launch

private const val TOKEN_ANNOTATION = "TOKEN"

@Composable
internal fun TextPreviewModal(
    displayText: String,
    tokenMap: Map<String, String>,
    revealedTokens: Set<String>,
    onRevealedTokensChange: (Set<String>) -> Unit,
    onMask: (text: String, type: String) -> Unit,
    onDismiss: () -> Unit,
    tokenLayers: Map<String, String> = emptyMap()  // AddressEngine v0 diagnostyka — token→layer, tylko DEBUG
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Zamknij",
                            tint = LynxColors.TextSecondary
                        )
                    }
                    Text(
                        "Podgląd tekstu",
                        fontFamily = LynxTypography.Sans,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = LynxColors.Blue
                    )
                    Text(
                        "${displayText.length} zn.",
                        fontFamily = LynxTypography.Sans,
                        fontSize = 11.sp,
                        color = LynxColors.TextDim,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
                HorizontalDivider(color = LynxColors.Blue.copy(alpha = 0.15f))

                Text(
                    "Dotknij tokenu (np. OSOBA_001) aby odkryć — dotknij ponownie aby ukryć. Zaznacz fragment i wklej poniżej aby zamaskować.",
                    fontFamily = LynxTypography.Sans,
                    fontSize = 11.sp,
                    color = LynxColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // AddressEngine v0 diagnostyka (04.07.2026) — tylko DEBUG, usunąć po zamknięciu testu.
                if (BuildConfig.DEBUG && tokenLayers.isNotEmpty()) {
                    Text(
                        "Zielony = AddressEngine (nowy) | Niebieski = stary silnik | Czerwony = odkryty",
                        fontFamily = LynxTypography.Sans,
                        fontSize = 11.sp,
                        color = LynxColors.TextMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    ClickableTokenText(
                        text = displayText,
                        tokenMap = tokenMap,
                        revealedTokens = revealedTokens,
                        tokenLayers = tokenLayers,
                        onTokenClick = { token ->
                            onRevealedTokensChange(
                                if (token in revealedTokens) revealedTokens - token
                                else revealedTokens + token
                            )
                        }
                    )
                }

                HorizontalDivider(color = LynxColors.Blue.copy(alpha = 0.15f))

                ManualTokenSection(
                    onMask = onMask,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ClickableTokenText(
    text: String,
    tokenMap: Map<String, String>,
    revealedTokens: Set<String>,
    onTokenClick: (String) -> Unit,
    tokenLayers: Map<String, String> = emptyMap()  // AddressEngine v0 diagnostyka, tylko DEBUG
) {
    val annotated = remember(text, tokenMap, revealedTokens, tokenLayers) {
        buildAnnotatedString {
            var lastIndex = 0
            TOKEN_RE.findAll(text).forEach { match ->
                if (match.range.first > lastIndex) {
                    append(text.substring(lastIndex, match.range.first))
                }
                val token = match.value
                val isRevealed = token in revealedTokens
                val display = if (isRevealed) tokenMap[token] ?: token else token
                val fromAddressEngine = tokenLayers[token] == LAYER_ADDRESS_ENGINE
                val color = when {
                    isRevealed -> LynxColors.Red
                    fromAddressEngine -> LynxColors.Green
                    else -> LynxColors.Blue
                }
                pushStringAnnotation(tag = TOKEN_ANNOTATION, annotation = token)
                withStyle(
                    SpanStyle(
                        color = color,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (isRevealed) TextDecoration.Underline else TextDecoration.None
                    )
                ) {
                    append(display)
                }
                pop()
                lastIndex = match.range.last + 1
            }
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface
        ),
        onClick = { offset ->
            annotated.getStringAnnotations(TOKEN_ANNOTATION, offset, offset)
                .firstOrNull()
                ?.let { onTokenClick(it.item) }
        }
    )
}

@Composable
internal fun ManualTokenSection(
    selectedText: String = "",
    onMask: (text: String, type: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember(selectedText) { mutableStateOf(selectedText) }
    var selectedType by remember { mutableStateOf(TOKEN_OSOBA) }
    val types = listOf(TOKEN_OSOBA, TOKEN_FIRMA, TOKEN_ADRES, TOKEN_NUMER, TOKEN_KWOTA)
    // BUG-BRAK-AUTOWYPELNIANIA (11.07): prawdziwa "automatyka" po zaznaczeniu tekstu nie jest
    // bezpiecznie osiągalna (Compose nie daje dostępu do aktywnego zaznaczenia w
    // SelectionContainer, a ciche czytanie schowka w tle Android 10+ traktuje jako zagrożenie
    // prywatności i pokazuje systemowy komunikat). Kompromis: jeden świadomy dotyk zamiast
    // ręcznego wpisywania — zaznacz → systemowe "Kopiuj" → "Wklej zaznaczenie" tutaj.
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = LynxColors.ActiveNav)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Ręczne maskowanie",
                fontFamily = LynxTypography.Sans,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = LynxColors.TextSecondary
            )
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tekst do zamaskowania", fontFamily = LynxTypography.Sans) },
                placeholder = { Text("Wklej zaznaczony fragment", fontFamily = LynxTypography.Sans) },
                singleLine = true,
                // BUG-POLE-KWADRATOWE (11.07): brak jawnego shape → domyślny (mniej zaokrąglony)
                // róg Material3, niepasujący do karty (12dp) która to pole otacza.
                shape = RoundedCornerShape(12.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = LynxTypography.Sans, fontSize = 14.sp, color = LynxColors.TextPrimary),
                trailingIcon = {
                    IconButton(onClick = {
                        scope.launch {
                            val clip = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!clip.isNullOrBlank()) inputText = clip.trim()
                        }
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "Wklej zaznaczenie", tint = LynxColors.BlueLight)
                    }
                }
            )
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
                        label = { Text(type, fontFamily = LynxTypography.Sans) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LynxColors.Blue.copy(alpha = 0.15f),
                            selectedLabelColor = LynxColors.Blue
                        )
                    )
                }
            }
            LynxFilledButton(
                label = "Maskuj",
                onClick = {
                    if (inputText.isNotBlank()) {
                        onMask(inputText.trim(), selectedType)
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
