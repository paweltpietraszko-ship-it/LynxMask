package com.lynxmask.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.lynxmask.app.ui.theme.LynxColors

private const val TOKEN_ANNOTATION = "TOKEN"

@Composable
internal fun TextPreviewModal(
    displayText: String,
    tokenMap: Map<String, String>,
    revealedTokens: Set<String>,
    onRevealedTokensChange: (Set<String>) -> Unit,
    onMask: (text: String, type: String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
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
                    TextButton(onClick = onDismiss) {
                        Text("← Zamknij", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        "Podgląd tekstu",
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
                    "Dotknij tokenu (np. OSOBA_001) aby odkryć wartość. Zaznacz fragment tekstu i wklej poniżej aby zamaskować.",
                    style = MaterialTheme.typography.labelSmall,
                    color = LynxColors.TextMuted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

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
    onTokenClick: (String) -> Unit
) {
    val annotated = remember(text, tokenMap, revealedTokens) {
        buildAnnotatedString {
            var lastIndex = 0
            TOKEN_RE.findAll(text).forEach { match ->
                if (match.range.first > lastIndex) {
                    append(text.substring(lastIndex, match.range.first))
                }
                val token = match.value
                val isRevealed = token in revealedTokens
                val display = if (isRevealed) tokenMap[token] ?: token else token
                pushStringAnnotation(tag = TOKEN_ANNOTATION, annotation = token)
                withStyle(
                    SpanStyle(
                        color = if (isRevealed) LynxColors.Red else LynxColors.Blue,
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
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = LynxColors.TextSecondary
            )
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tekst do zamaskowania") },
                placeholder = { Text("Wklej zaznaczony fragment") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
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
