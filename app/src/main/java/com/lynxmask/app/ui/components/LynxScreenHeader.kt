package com.lynxmask.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lynxmask.app.ui.theme.LynxColors
import com.lynxmask.app.ui.theme.LynxSpacing
import com.lynxmask.app.ui.theme.LynxTypography

@Composable
fun LynxScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    sectionLabel: String? = null,
    subtitle: String? = null,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(LynxColors.Sidebar)
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        if (onBack != null && backLabel != null) {
            LynxGhostButton(
                onClick = onBack,
                modifier = Modifier.padding(start = LynxSpacing.xs, top = LynxSpacing.xs)
            ) {
                Text(
                    "← $backLabel",
                    fontFamily = LynxTypography.Sans,
                    fontSize = 14.sp,
                    color = LynxColors.BlueLight
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LynxSpacing.md, vertical = LynxSpacing.sm)
        ) {
            sectionLabel?.let {
                Text(
                    it,
                    fontFamily = LynxTypography.Mono,
                    fontSize = 9.sp,
                    color = LynxColors.Blue,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                title,
                fontFamily = LynxTypography.Sans,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = LynxColors.TextPrimary
            )
            subtitle?.let {
                Text(
                    it,
                    fontFamily = LynxTypography.Sans,
                    fontSize = 12.sp,
                    color = LynxColors.TextDim,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        HorizontalDivider(color = LynxColors.Border, thickness = 0.5.dp)
    }
}
