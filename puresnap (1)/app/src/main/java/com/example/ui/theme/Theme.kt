package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val PureSnapDarkColorScheme = darkColorScheme(
    primary = CoralPrimary,
    onPrimary = Color.White,
    primaryContainer = CoralPrimaryContainer,
    onPrimaryContainer = OnCoralPrimaryContainer,
    secondary = CyanShield,
    onSecondary = Color(0xFF04201B),
    secondaryContainer = CyanShieldContainer,
    onSecondaryContainer = OnCyanShieldContainer,
    tertiary = AmberAccent,
    onTertiary = Color(0xFF261900),
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = Color(0xFFFFDFA6),
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceElevated,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = ObsidianOutline,
    error = DangerRed,
    onError = Color.White,
    errorContainer = DangerRedContainer,
    onErrorContainer = Color(0xFFFFDADE)
)

private val PureSnapLightColorScheme = lightColorScheme(
    primary = CoralLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD2),
    onPrimaryContainer = Color(0xFF3E0A00),
    secondary = CyanLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF5EC),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFFB86E00),
    onTertiary = Color.White,
    background = LightBg,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    outline = LightOutline,
    error = Color(0xFFD12C3C),
    onError = Color.White
)

val PureSnapShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PureSnapTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) PureSnapDarkColorScheme else PureSnapLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PureSnapTypography,
        shapes = PureSnapShapes,
        content = content
    )
}
