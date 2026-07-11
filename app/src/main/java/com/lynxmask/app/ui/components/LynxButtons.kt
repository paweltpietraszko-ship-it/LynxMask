package com.lynxmask.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography

private val lynxButtonShape @Composable get() = RoundedCornerShape(LynxShapes.ButtonRadius)

/** Jedna grubość obwódki w całej aplikacji (poza naw. Biblioteka). */
private val LynxButtonBorderWidth = 1.5.dp

private fun lynxButtonBorderAlpha(enabled: Boolean) = if (enabled) 0.72f else 0.28f

@Composable
private fun LynxButtonShell(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = LynxColors.BorderActive,
    containerColor: Color = LynxColors.Surface,
    contentColor: Color = LynxColors.TextPrimary,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = LynxSpacing.TouchTarget),
        enabled = enabled,
        shape = lynxButtonShape,
        color = containerColor,
        border = BorderStroke(
            LynxButtonBorderWidth,
            accent.copy(alpha = lynxButtonBorderAlpha(enabled))
        ),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProvideTextStyle(
                LocalTextStyle.current.copy(
                    fontFamily = LynxTypography.Sans,
                    color = if (enabled) contentColor else LynxColors.TextMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            ) {
                content()
            }
        }
    }
}

/** Główna akcja — jaśniejsza obwódka (BorderActive). */
@Composable
fun LynxPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") showMark: Boolean = false,
    @Suppress("UNUSED_PARAMETER") brandedBackground: Boolean = false,
    content: @Composable RowScope.() -> Unit
) = LynxButtonShell(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    accent = LynxColors.BorderActive,
    content = content
)

/** Akcja wtórna — ta sama forma, spokojniejsza obwódka. */
@Composable
fun LynxSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = LynxColors.Border,
    @Suppress("UNUSED_PARAMETER") showMark: Boolean = false,
    @Suppress("UNUSED_PARAMETER") brandedBackground: Boolean = false,
    content: @Composable RowScope.() -> Unit
) = LynxButtonShell(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    accent = accent,
    content = content
)

@Composable
fun LynxSuccessButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    LynxButtonShell(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        accent = LynxColors.GreenBorder,
        containerColor = LynxColors.Green,
        contentColor = Color.White,
        content = content
    )
}

@Composable
fun LynxGhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = lynxButtonShape,
        content = content
    )
}

@Composable
fun LynxDangerTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String
) {
    LynxGhostButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(
            label,
            fontFamily = LynxTypography.Sans,
            color = LynxColors.Red,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp
        )
    }
}

// ── Wypełniony przycisk / płaski wiersz (11.07, redesign Hub+Login) ──────────
// Zastępują LynxPrimaryButton/LynxSecondaryButton na ekranach przechodzących na nowy
// wzorzec: jedna wypełniona akcja główna zamiast jednakowo obramowanych przycisków.
// Migracja stopniowa ekran po ekranie — stare komponenty wyżej zostają nietknięte
// dopóki wszystkie miejsca ich użycia nie przejdą na nowe.

/** Wypełniony przycisk główny — ikona + etykieta + opcjonalny podpis. */
@Composable
fun LynxFilledButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    caption: String? = null,
    color: Color = LynxColors.Blue,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Surface(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LynxShapes.ButtonRadius),
        color = if (enabled) color else color.copy(alpha = 0.35f),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LynxSpacing.md, vertical = if (caption != null) LynxSpacing.md else 14.dp),
            horizontalArrangement = if (caption != null) Arrangement.Start else Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(LynxSpacing.sm))
                }
                if (caption != null) {
                    Column {
                        Text(label, fontFamily = LynxTypography.Sans, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(caption, fontFamily = LynxTypography.Sans, fontSize = 11.5.sp, color = Color.White.copy(alpha = 0.85f))
                    }
                } else {
                    Text(label, fontFamily = LynxTypography.Sans, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

/** Płaski wiersz bez ramki/wypełnienia — akcja drugorzędna. */
@Composable
fun LynxFlatRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    iconTint: Color = LynxColors.BlueLight,
    labelColor: Color? = null
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // BUG-IKONY-SCHODKI (11.07): karty informacyjne (StatusBanner, MaskedSummaryCard)
                // mają ~14-16dp wewnętrznego marginesu przed ikoną — ten wiersz miał 0, więc jego
                // ikona siedziała wyraźnie bardziej z lewej niż reszta. 14dp wyrównuje lewą krawędź.
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) iconTint else LynxColors.TextDim.copy(alpha = 0.5f)
                )
            }
            Text(
                label,
                fontFamily = LynxTypography.Sans,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (!enabled) LynxColors.TextDim else (labelColor ?: LynxColors.TextPrimary)
            )
        }
    }
}

// ── Motyw Yin/Yang — zakładka Biblioteka + akcje „Otwórz bibliotekę” ─────────

private val yinYangNavBrushSelected
    get() = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to Color(0xFF2A2F38),
            0.62f to Color(0xFF2A2F38),
            1f to LynxColors.Blue.copy(alpha = 0.88f)
        )
    )

private val yinYangNavBrushIdle
    get() = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to Color(0xFF2A2F38),
            0.48f to Color(0xFF2A2F38),
            1f to LynxColors.Blue.copy(alpha = 0.78f)
        )
    )

/** Gradient launchera — naw. Biblioteka (także na Hub) i „Otwórz bibliotekę”. */
@Composable
fun LynxBrandButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: Boolean = false,
    compact: Boolean = false,
    showMark: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = LynxSpacing.TouchTarget),
        enabled = enabled,
        shape = lynxButtonShape,
        color = Color.Transparent,
        border = BorderStroke(
            LynxButtonBorderWidth,
            LynxColors.BorderActive.copy(alpha = lynxButtonBorderAlpha(enabled))
        ),
        tonalElevation = if (emphasis && enabled) 2.dp else 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (emphasis) yinYangNavBrushSelected else yinYangNavBrushIdle)
                .padding(
                    horizontal = if (compact) 6.dp else 16.dp,
                    vertical = 12.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            if (showMark && enabled) {
                LynxYinYangMark(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
                    size = if (compact) 22.dp else 26.dp,
                    alpha = if (emphasis) 0.18f else 0.14f
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showMark) {
                    LynxYinYangMark(
                        modifier = Modifier.padding(end = 6.dp),
                        size = if (compact) 16.dp else 18.dp,
                        alpha = if (enabled) {
                            if (emphasis) 0.95f else 0.88f
                        } else {
                            0.35f
                        }
                    )
                }
                ProvideTextStyle(
                    LocalTextStyle.current.copy(
                        fontFamily = LynxTypography.Sans,
                        color = if (enabled) {
                            if (emphasis) Color.White else LynxColors.TextPrimary
                        } else {
                            LynxColors.TextMuted
                        },
                        fontSize = if (compact) 13.sp else 14.sp,
                        fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun LynxNavButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    brandPalette: Boolean = false
) {
    if (brandPalette) {
        LynxBrandButton(
            onClick = onClick,
            modifier = modifier,
            emphasis = selected,
            compact = true,
            showMark = true
        ) {
            Text(label, maxLines = 1)
        }
        return
    }

    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = LynxSpacing.TouchTarget),
        shape = lynxButtonShape,
        color = if (selected) LynxColors.Blue else LynxColors.ActiveNav,
        border = BorderStroke(
            LynxButtonBorderWidth,
            (if (selected) LynxColors.BorderActive else LynxColors.Border)
                .copy(alpha = lynxButtonBorderAlpha(true))
        ),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontFamily = LynxTypography.Sans,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                textAlign = TextAlign.Center,
                color = if (selected) Color.White else LynxColors.TextSecondary
            )
        }
    }
}
