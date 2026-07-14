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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography

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
                    fontFamily = LynxTypography.Sans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = LynxColors.TextPrimary
                )
                Text(
                    "$tokenCount ${tokenCount.tokenWord()} — otwórz podgląd, aby zweryfikować",
                    fontFamily = LynxTypography.Sans,
                    fontSize = 12.sp,
                    color = LynxColors.TextSecondary
                )
            }
        }
    }
}

/**
 * Karta klikalna — ten sam wygląd co MaskedSummaryCard (11.07, żeby "Podgląd tekstu" nie
 * było jedynym elementem bez ramki na ekranie zdominowanym przez karty — wyglądało jak sierota).
 */
@Composable
internal fun ActionSummaryCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LynxShapes.CardRadius),
        colors = CardDefaults.cardColors(containerColor = LynxColors.BlueBg.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, LynxColors.Blue.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = LynxColors.BlueLight, modifier = Modifier.size(20.dp))
            Text(
                label,
                fontFamily = LynxTypography.Sans,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = LynxColors.TextPrimary
            )
        }
    }
}

private fun Int.tokenWord() = when {
    this == 1 -> "element"
    this in 2..4 -> "elementy"
    else -> "elementów"
}
