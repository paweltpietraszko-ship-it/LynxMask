package com.lynxmask.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing

private val lynxButtonShape @Composable get() = RoundedCornerShape(LynxShapes.ButtonRadius)

/**
 * Główna akcja — ten sam styl co [LynxSecondaryButton] (obramowany, Surface).
 * UI wyniku (UI-2) ustalił wzorzec: akcje obramowane, nie wypełnione.
 */
@Composable
fun LynxPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) = LynxSecondaryButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    accent = LynxColors.Blue,
    content = content
)

@Composable
fun LynxSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = LynxColors.Blue,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = LynxSpacing.TouchTarget),
        enabled = enabled,
        shape = lynxButtonShape,
        border = BorderStroke(1.dp, accent.copy(alpha = if (enabled) 0.45f else 0.2f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = LynxColors.Surface,
            contentColor = LynxColors.TextPrimary,
            disabledContainerColor = LynxColors.Surface.copy(alpha = 0.5f),
            disabledContentColor = LynxColors.TextMuted
        ),
        content = content
    )
}

@Composable
fun LynxSuccessButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = LynxSpacing.TouchTarget),
        enabled = enabled,
        shape = lynxButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = LynxColors.Green,
            contentColor = Color.White,
            disabledContainerColor = LynxColors.ActiveNav,
            disabledContentColor = LynxColors.TextMuted
        ),
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
        Text(label, color = LynxColors.Red, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun LynxNavButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = LynxSpacing.TouchTarget),
        shape = lynxButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) LynxColors.Blue else LynxColors.ActiveNav,
            contentColor = if (selected) Color.White else LynxColors.TextMuted
        )
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}
