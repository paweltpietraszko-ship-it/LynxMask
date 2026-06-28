package com.lynxmask.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lynxmask.app.R

@Composable
fun LynxYinYangMark(
    modifier: Modifier = Modifier,
    size: Dp? = 22.dp,
    alpha: Float = 1f,
    contentScale: ContentScale = ContentScale.Fit
) {
    val sizedModifier = if (size != null) {
        modifier.size(size).alpha(alpha)
    } else {
        modifier.alpha(alpha)
    }
    Image(
        painter = painterResource(R.drawable.ic_yin_yang_mark),
        contentDescription = null,
        modifier = sizedModifier,
        contentScale = contentScale
    )
}
