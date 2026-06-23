package com.lynxmask.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.theme.LynxColors

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

// ── RED hity — tylko poziom RED ─────────────────────────────────────────────

@Composable
internal fun RedHitsSection(
    hits: List<GuardHit>,
    onMask: (GuardHit) -> Unit
) {
    if (hits.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = LynxColors.Red.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Wykryto możliwy wyciek — zamaskuj przed wysłaniem",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = LynxColors.Red
            )
            hits.forEach { hit ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "⚠ ${hit.label} — ${hit.matchedText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LynxColors.Red
                    )
                    OutlinedButton(
                        onClick = { onMask(hit) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Maskuj", fontSize = 12.sp, color = LynxColors.Red)
                    }
                }
                if (hit != hits.last()) {
                    HorizontalDivider(color = LynxColors.Red.copy(alpha = 0.12f))
                }
            }
        }
    }
}

// ── YELLOW — Guard YELLOW + flagi NameEngine (jedna lista) ───────────────────

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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = LynxColors.Amber.copy(alpha = 0.07f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onMask,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("Maskuj", fontSize = 11.sp, color = LynxColors.Amber)
            }
            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Nie maskuj", fontSize = 11.sp, color = LynxColors.TextMuted)
                }
            }
        }
    }
}
