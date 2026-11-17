package com.friday.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** FRIDAY design tokens: graphite surfaces, electric-blue/cyan accents, restrained status colors. */
object Fx {
    val Background = Color(0xFF05070B)
    val BackgroundTop = Color(0xFF0A1220)
    val Surface = Color(0xFF0C1118)
    val SurfaceHigh = Color(0xFF121A25)
    val SurfaceHigher = Color(0xFF182231)
    val Outline = Color(0xFF1F2B3A)
    val OutlineStrong = Color(0xFF2C3B4F)
    val Text = Color(0xFFEAF1FA)
    val TextSecondary = Color(0xFF9AABBF)
    val TextTertiary = Color(0xFF64768B)
    val Blue = Color(0xFF3D8BFF)
    val BlueDeep = Color(0xFF1F5FD6)
    val Cyan = Color(0xFF41E2FF)
    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFFFB547)
    val Danger = Color(0xFFFF5C7A)
    val UserBubble = Color(0xFF15233A)
    val CodeBackground = Color(0xFF080C12)
}

private val colors = darkColorScheme(
    primary = Fx.Blue,
    onPrimary = Color.White,
    primaryContainer = Fx.BlueDeep,
    onPrimaryContainer = Color.White,
    secondary = Fx.Cyan,
    onSecondary = Color(0xFF00141A),
    secondaryContainer = Fx.SurfaceHigher,
    onSecondaryContainer = Fx.Text,
    tertiary = Fx.Cyan,
    background = Fx.Background,
    onBackground = Fx.Text,
    surface = Fx.Surface,
    onSurface = Fx.Text,
    surfaceVariant = Fx.SurfaceHigh,
    onSurfaceVariant = Fx.TextSecondary,
    surfaceContainerLowest = Fx.Background,
    surfaceContainerLow = Fx.Surface,
    surfaceContainer = Fx.SurfaceHigh,
    surfaceContainerHigh = Fx.SurfaceHigh,
    surfaceContainerHighest = Fx.SurfaceHigher,
    outline = Fx.OutlineStrong,
    outlineVariant = Fx.Outline,
    error = Fx.Danger,
    onError = Color.White
)

private val base = TextStyle(fontFamily = FontFamily.Default, color = Fx.Text)

private val typography = Typography(
    displaySmall = base.copy(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = base.copy(fontSize = 13.sp, lineHeight = 19.sp, color = Fx.TextSecondary),
    labelLarge = base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = base.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
)

/**
 * Telugu vowel signs extend above/below the Latin line box. Compose drops font padding by default,
 * which clips them at tight line heights, so Telugu text keeps font padding and a taller line.
 */
fun scriptAwareStyle(text: String, base: TextStyle, fontSizeSp: Int): TextStyle {
    val telugu = com.friday.app.core.Script.containsTelugu(text)
    return base.copy(
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * if (telugu) 1.75f else 1.5f).sp,
        platformStyle = PlatformTextStyle(includeFontPadding = telugu)
    )
}

@Composable
fun FridayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
