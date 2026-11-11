package com.dzienisz.zureqshopper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

private val Ink = Color(0xFF151719)
private val Paper = Color(0xFFFCFCFC)
private val Accent = Color(0xFF47D6EE)
private val AccentText = Color(0xFF08758B)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Color(0xFFD5F5FB),
    onPrimaryContainer = Color(0xFF06414D),
    secondary = AccentText,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F5FB),
    onSecondaryContainer = Color(0xFF06414D),
    tertiary = Color(0xFF815700),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF4DC),
    onTertiaryContainer = Color(0xFF815700),
    error = Color(0xFFE40014),
    onError = Color.White,
    errorContainer = Color(0xFFFFE5E7),
    onErrorContainer = Color(0xFF8C000C),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9EDEF),
    onSurfaceVariant = Color(0xFF62676C),
    surfaceTint = Ink,
    inverseSurface = Color(0xFF292D31),
    inverseOnSurface = Color(0xFFF5F6F6),
    inversePrimary = Accent,
    outline = Color(0xFFA3A9AE),
    outlineVariant = Color(0xFFE6E8E9),
    surfaceBright = Paper,
    surfaceDim = Color(0xFFE9EDEF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9F9),
    surfaceContainer = Color(0xFFF5F6F6),
    surfaceContainerHigh = Color(0xFFEFF1F2),
    surfaceContainerHighest = Color(0xFFE9EDEF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF1F3F4),
    onPrimary = Ink,
    primaryContainer = Color(0xFF0B4A57),
    onPrimaryContainer = Color(0xFFC9F3FA),
    secondary = Accent,
    onSecondary = Color(0xFF06262D),
    secondaryContainer = Color(0xFF0B4A57),
    onSecondaryContainer = Color(0xFFC9F3FA),
    tertiary = Color(0xFFE9BD68),
    onTertiary = Color(0xFF3A2800),
    tertiaryContainer = Color(0xFF4A3600),
    onTertiaryContainer = Color(0xFFFFF4DC),
    error = Color(0xFFFF8A8F),
    onError = Color(0xFF5C0008),
    errorContainer = Color(0xFF5C0008),
    onErrorContainer = Color(0xFFFFDADB),
    background = Color(0xFF0F1112),
    onBackground = Color(0xFFF1F3F4),
    surface = Color(0xFF0F1112),
    onSurface = Color(0xFFF1F3F4),
    surfaceVariant = Color(0xFF292D31),
    onSurfaceVariant = Color(0xFFA3A9AE),
    surfaceTint = Color(0xFFF1F3F4),
    inverseSurface = Color(0xFFF1F3F4),
    inverseOnSurface = Ink,
    inversePrimary = AccentText,
    outline = Color(0xFF62676C),
    outlineVariant = Color(0xFF292D31),
    surfaceBright = Color(0xFF292D31),
    surfaceDim = Color(0xFF0F1112),
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Color(0xFF17191B),
    surfaceContainer = Color(0xFF1A1D1F),
    surfaceContainerHigh = Color(0xFF212427),
    surfaceContainerHighest = Color(0xFF292D31),
)

private val Base = Typography()
private val ZureqTypography = Base.copy(
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.em),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val ZureqShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Brand palette shared with the ZUREQ Shopper Chrome extension; wallpaper-based dynamic colour is deliberately off. */
@Composable
fun ZureqTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ZureqTypography,
        shapes = ZureqShapes,
        content = content,
    )
}
