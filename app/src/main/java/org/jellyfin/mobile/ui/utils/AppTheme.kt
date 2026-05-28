package org.jellyfin.mobile.ui.utils

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.jellyfin.mobile.R

@Suppress("MagicNumber")
object PiggieTvColors {
    val Focus = Color(0xFF7DD6FF)
    val FocusSoft = Color(0xFF9FE7FF)
    val Violet = Color(0xFFA78BFA)
    val VioletSoft = Color(0xFFC9B5FF)
    val VioletDeep = Color(0xFF2C1746)
    val Accent = Color(0xFFFF8DC3)
    val AccentSoft = Color(0xFFFF9FD0)
    val Night = Color(0xFF05050C)
    val Panel = Color(0xFF140B24)
    val PanelHigh = Color(0xFF2C1746)
    val Border = Color(0x66C9B5FF)
    val TextPrimary = Color(0xFFF8F6FF)
    val TextSecondary = Color(0xFFC5BED8)
    val Error = Color(0xFFFF8DC3)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = remember {
        darkColors(
            primary = PiggieTvColors.Focus,
            primaryVariant = PiggieTvColors.VioletDeep,
            secondary = PiggieTvColors.Accent,
            background = PiggieTvColors.Night,
            surface = PiggieTvColors.Panel,
            error = PiggieTvColors.Error,
            onPrimary = PiggieTvColors.TextPrimary,
            onSecondary = PiggieTvColors.Night,
            onBackground = PiggieTvColors.TextPrimary,
            onSurface = PiggieTvColors.TextPrimary,
            onError = PiggieTvColors.Night,
        )
    }
    MaterialTheme(
        colors = colors,
        shapes = Shapes(
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(0.dp),
        ),
        content = content,
    )
}

@Composable
fun PiggieTvBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.background(PiggieTvColors.Night),
    ) {
        Image(
            painter = painterResource(R.drawable.ptv_splash_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alpha = 0.36f,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            PiggieTvColors.Night.copy(alpha = 0.60f),
                            PiggieTvColors.Panel.copy(alpha = 0.82f),
                            PiggieTvColors.Night.copy(alpha = 0.96f),
                        ),
                    ),
                ),
        )
        content()
    }
}
