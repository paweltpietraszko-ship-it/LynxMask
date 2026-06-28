package com.lynxmask.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.components.LynxGhostButton
import com.lynxmask.app.ui.components.LynxSecondaryButton
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxShapes
import com.lynxmask.app.ui.theme.LynxSpacing

/** Mapowanie label Guard YELLOW → typ tokenu (MASTER sekcja 17). */
internal fun guardLabelToTokenType(label: String): String = when (label) {
    "MIEJSCE_UR" -> TOKEN_ADRES
    "SYGNATURA", "LICZBA", "URODZENIE", "EMAIL_FRAGMENT" -> TOKEN_NUMER
    else -> TOKEN_NUMER
}

/** Mapowanie label Guard RED → typ tokenu przy auto-maskowaniu. */
internal fun guardRedLabelToTokenType(label: String): String = when (label) {
    "EMAIL" -> TOKEN_EMAIL
    else -> TOKEN_NUMER
}

@Composable
internal fun RedHitsSection(
    hits: List<GuardHit>,
    onMask: (GuardHit) -> Unit
) {
    if (hits.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LynxShapes.CardRadius),
        colors = CardDefaults.cardColors(containerColor = LynxColors.RedBg.copy(alpha = 0.65f)),
        border = BorderStroke(1.dp, LynxColors.RedBorder.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Wykryto możliwy wyciek — zamaskuj przed wysłaniem",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = LynxColors.Red
            )
            hits.forEach { hit ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = LynxColors.Red,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "${hit.label} — ${hit.matchedText}",
                            style = MaterialTheme.typography.labelSmall,
                            color = LynxColors.Red
                        )
                    }
                    LynxSecondaryButton(
                        onClick = { onMask(hit) },
                        modifier = Modifier.heightIn(min = 36.dp),
                        accent = LynxColors.Red
                    ) {
                        Text("Maskuj", fontSize = 12.sp, color = LynxColors.Red)
                    }
                }
                if (hit != hits.last()) {
                    HorizontalDivider(color = LynxColors.Red.copy(alpha = 0.15f))
                }
            }
        }
    }
}

@Composable
internal fun YellowAlertsSection(
    guardHits: List<GuardHit>,
    flags: List<PseudonymFlag>,
    onMaskGuard: (GuardHit) -> Unit,
    onAllowlistGuard: ((GuardHit) -> Unit)?,
    onMaskFlag: (PseudonymFlag) -> Unit,
    onDismissFlag: (PseudonymFlag) -> Unit
) {
    if (guardHits.isEmpty() && flags.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(LynxShapes.CardRadius),
        colors = CardDefaults.cardColors(containerColor = LynxColors.Amber.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, LynxColors.Amber.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Do sprawdzenia",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = LynxColors.Amber
            )
            guardHits.forEach { hit ->
                YellowAlertRow(
                    title = hit.matchedText,
                    subtitle = hit.label,
                    onMask = { onMaskGuard(hit) },
                    onDismiss = onAllowlistGuard?.let { { it(hit) } }
                )
                if (hit != guardHits.last() || flags.isNotEmpty()) {
                    HorizontalDivider(color = LynxColors.Amber.copy(alpha = 0.12f))
                }
            }
            flags.forEach { flag ->
                YellowAlertRow(
                    title = flag.fragment,
                    subtitle = flag.reason,
                    onMask = { onMaskFlag(flag) },
                    onDismiss = { onDismissFlag(flag) }
                )
                if (flag != flags.last()) {
                    HorizontalDivider(color = LynxColors.Amber.copy(alpha = 0.12f))
                }
            }
        }
    }
}

@Composable
private fun YellowAlertRow(
    title: String,
    subtitle: String,
    onMask: () -> Unit,
    onDismiss: (() -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title.take(60) + if (title.length > 60) "…" else "",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LynxSpacing.sm)) {
            LynxSecondaryButton(
                onClick = onMask,
                modifier = Modifier.heightIn(min = 36.dp),
                accent = LynxColors.Amber
            ) {
                Text("Maskuj", fontSize = 11.sp, color = LynxColors.Amber)
            }
            if (onDismiss != null) {
                LynxGhostButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 36.dp)
                ) {
                    Text("Nie maskuj", fontSize = 11.sp, color = LynxColors.TextMuted)
                }
            }
        }
    }
}
