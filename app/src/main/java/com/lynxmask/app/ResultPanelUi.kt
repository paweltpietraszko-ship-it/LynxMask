package com.lynxmask.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.components.LynxSecondaryButton
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing

/** Stan paska STATUS — tylko aktywne alerty UI, nie legacy riskScore silnika. */
internal enum class PanelStatusKind { RED, YELLOW, GREEN }

/** Dlaczego zablokowano Kopiuj / Wyślij / Biblioteka. */
internal enum class ExportBlockReason {
    NONE,
    RED_HITS,
    YELLOW_ALERTS,
    PENDING_FLAGS
}

internal fun resolvePanelStatus(
    hasRedHits: Boolean,
    hasYellowAlerts: Boolean
): PanelStatusKind = when {
    hasRedHits -> PanelStatusKind.RED
    hasYellowAlerts -> PanelStatusKind.YELLOW
    else -> PanelStatusKind.GREEN
}

internal fun resolveExportBlockReason(
    hasRedHits: Boolean,
    hasYellowAlerts: Boolean,
    allFlagsHandled: Boolean
): ExportBlockReason = when {
    hasRedHits -> ExportBlockReason.RED_HITS
    hasYellowAlerts -> ExportBlockReason.YELLOW_ALERTS
    !allFlagsHandled -> ExportBlockReason.PENDING_FLAGS
    else -> ExportBlockReason.NONE
}

internal fun ExportBlockReason.userMessage(): String = when (this) {
    ExportBlockReason.NONE -> ""
    ExportBlockReason.RED_HITS,
    ExportBlockReason.YELLOW_ALERTS,
    ExportBlockReason.PENDING_FLAGS ->
        "Najpierw obsłuż alerty czerwone i żółte powyżej"
}

@Composable
internal fun BlockedActionSlot(
    enabled: Boolean,
    onBlockedClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()
        if (!enabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBlockedClick
                    )
            )
        }
    }
}

@Composable
internal fun LynxTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: androidx.compose.ui.graphics.Color = LynxColors.Blue,
    content: @Composable RowScope.() -> Unit
) = LynxSecondaryButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    accent = accent,
    content = content
)

@Composable
internal fun MaskedSummaryCard(tokenCount: Int) {
    if (tokenCount <= 0) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LynxShapes.CardRadius),
        colors = CardDefaults.cardColors(containerColor = LynxColors.BlueBg.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, LynxColors.Blue.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Shield,
                contentDescription = null,
                tint = LynxColors.BlueLight,
                modifier = Modifier.size(20.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Zamaskowano w dokumencie",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = LynxColors.TextPrimary
                )
                Text(
                    "$tokenCount ${tokenCount.tokenWord()} — otwórz podgląd, aby zweryfikować",
                    style = MaterialTheme.typography.labelSmall,
                    color = LynxColors.TextSecondary
                )
            }
        }
    }
}

private fun Int.tokenWord() = when {
    this == 1 -> "element"
    this in 2..4 -> "elementy"
    else -> "elementów"
}
