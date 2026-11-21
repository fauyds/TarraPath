package com.example.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Light & Dark Expressive Base Surfaces (strictly non-glassmorphic solid surfaces)
val LightAppBackground = Color(0xFFE6E6E6) // 10% dark white
val DarkAppBackground = Color(0xFF262626)  // 15% light black
val LightCardSurface = Color(0xFFFFFFFF)   // Pure white
val DarkCardSurface = Color(0xFF404040)    // 10% lighter than dark background

// Fallback Material 3 Color Palette (used on Android < 12 when dynamic Monet is unavailable)
val ForestPrimaryLight = Color(0xFF1B6B52)
val ForestOnPrimaryLight = Color(0xFFFFFFFF)
val ForestPrimaryContainerLight = Color(0xFFA7F2D3)
val ForestOnPrimaryContainerLight = Color(0xFF002117)

val TerracottaSecondaryLight = Color(0xFF8B4A2B)
val TerracottaOnSecondaryLight = Color(0xFFFFFFFF)
val TerracottaSecondaryContainerLight = Color(0xFFFFDBCC)
val TerracottaOnSecondaryContainerLight = Color(0xFF331103)

val SageTertiaryLight = Color(0xFF3F6374)
val SageOnTertiaryLight = Color(0xFFFFFFFF)
val SageTertiaryContainerLight = Color(0xFFC3E8FC)
val SageOnTertiaryContainerLight = Color(0xFF001F2A)

val ForestPrimaryDark = Color(0xFF8CD6B8)
val ForestOnPrimaryDark = Color(0xFF003828)
val ForestPrimaryContainerDark = Color(0xFF00513C)
val ForestOnPrimaryContainerDark = Color(0xFFA7F2D3)

val TerracottaSecondaryDark = Color(0xFFFFB595)
val TerracottaOnSecondaryDark = Color(0xFF53220B)
val TerracottaSecondaryContainerDark = Color(0xFF6F3419)
val TerracottaOnSecondaryContainerDark = Color(0xFFFFDBCC)

val SageTertiaryDark = Color(0xFFA7CCDF)
val SageOnTertiaryDark = Color(0xFF0B3444)
val SageTertiaryContainerDark = Color(0xFF264B5B)
val SageOnTertiaryContainerDark = Color(0xFFC3E8FC)

val FallbackLightColorScheme = lightColorScheme(
    primary = ForestPrimaryLight,
    onPrimary = ForestOnPrimaryLight,
    primaryContainer = ForestPrimaryContainerLight,
    onPrimaryContainer = ForestOnPrimaryContainerLight,
    secondary = TerracottaSecondaryLight,
    onSecondary = TerracottaOnSecondaryLight,
    secondaryContainer = TerracottaSecondaryContainerLight,
    onSecondaryContainer = TerracottaOnSecondaryContainerLight,
    tertiary = SageTertiaryLight,
    onTertiary = SageOnTertiaryLight,
    tertiaryContainer = SageTertiaryContainerLight,
    onTertiaryContainer = SageOnTertiaryContainerLight,
    background = LightAppBackground,
    onBackground = Color(0xFF1A1C1A),
    surface = LightCardSurface,
    onSurface = Color(0xFF1A1C1A),
    surfaceVariant = Color(0xFFDADCD8),
    onSurfaceVariant = Color(0xFF434844),
    outline = Color(0xFF737974)
)

val FallbackDarkColorScheme = darkColorScheme(
    primary = ForestPrimaryDark,
    onPrimary = ForestOnPrimaryDark,
    primaryContainer = ForestPrimaryContainerDark,
    onPrimaryContainer = ForestOnPrimaryContainerDark,
    secondary = TerracottaSecondaryDark,
    onSecondary = TerracottaOnSecondaryDark,
    secondaryContainer = TerracottaSecondaryContainerDark,
    onSecondaryContainer = TerracottaOnSecondaryContainerDark,
    tertiary = SageTertiaryDark,
    onTertiary = SageOnTertiaryDark,
    tertiaryContainer = SageTertiaryContainerDark,
    onTertiaryContainer = SageOnTertiaryContainerDark,
    background = DarkAppBackground,
    onBackground = Color(0xFFE6E8E5),
    surface = DarkCardSurface,
    onSurface = Color(0xFFE6E8E5),
    surfaceVariant = Color(0xFF333533),
    onSurfaceVariant = Color(0xFFC2C7C2),
    outline = Color(0xFF8C928D)
)

@Immutable
data class ExpressiveColors(
    val appBackground: Color,
    val cardSurface: Color,
    val nestedElementSurface: Color,
    val buttonPrimary: Color,
    val buttonTertiarySubtle: Color,
    val selectorSelected: Color,
    val selectorUnselected: Color,
    val uiBlurEnabled: Boolean = false,
    val uiTransparency: Float = 0.25f,
    val uiBlurRadiusDp: Float = 20f,
    val frostedBorderColor: Color = Color.Transparent,
    val dialogSurface: Color = cardSurface
)

val LocalExpressiveColors = staticCompositionLocalOf {
    ExpressiveColors(
        appBackground = LightAppBackground,
        cardSurface = LightCardSurface,
        nestedElementSurface = LightAppBackground,
        buttonPrimary = ForestPrimaryLight,
        buttonTertiarySubtle = SageTertiaryContainerLight.copy(alpha = 0.85f),
        selectorSelected = ForestPrimaryLight,
        selectorUnselected = LightCardSurface,
        uiBlurEnabled = false,
        uiTransparency = 0.25f,
        uiBlurRadiusDp = 20f,
        frostedBorderColor = Color.Transparent,
        dialogSurface = LightCardSurface
    )
}
