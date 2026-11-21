package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

object ExpressiveTheme {
    val colors: ExpressiveColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExpressiveColors.current
}

@Composable
fun TerraPathTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    customAccentColorArgb: Long? = null,
    uiBlurEnabled: Boolean = false,
    uiTransparency: Float = 0.25f,
    uiBlurRadiusDp: Float = 20f,
    content: @Composable () -> Unit
) {
    val baseColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDarkColorScheme
        else -> FallbackLightColorScheme
    }

    val clampedTransparency = uiTransparency.coerceIn(0f, 0.85f)
    val clampedBlurRadiusDp = uiBlurRadiusDp.coerceIn(0f, 40f)
    val glassAlpha = (1f - clampedTransparency).coerceIn(0.15f, 1f)

    val appBackground = if (darkTheme) DarkAppBackground else LightAppBackground
    val baseCardSurface = if (darkTheme) DarkCardSurface else LightCardSurface
    val cardSurface = if (uiBlurEnabled) {
        baseCardSurface.copy(alpha = glassAlpha)
    } else {
        baseCardSurface
    }
    val nestedSurface = if (uiBlurEnabled) {
        appBackground.copy(alpha = (glassAlpha - 0.04f).coerceIn(0.12f, 1f))
    } else {
        appBackground
    }
    val dialogSurface = if (uiBlurEnabled) {
        baseCardSurface.copy(alpha = (glassAlpha + 0.14f).coerceIn(0.32f, 0.96f))
    } else {
        baseCardSurface
    }
    val frostedBorder = if (uiBlurEnabled) {
        val borderScale = (0.35f + (1f - clampedTransparency) * 0.65f).coerceIn(0.3f, 1f)
        if (darkTheme) Color.White.copy(alpha = 0.18f * borderScale) else Color.White.copy(alpha = 0.7f * borderScale)
    } else {
        Color.Transparent
    }

    val accentedScheme = if (customAccentColorArgb != null) {
        val rawAccent = Color(customAccentColorArgb.toInt()).copy(alpha = 1f)
        val effectivePrimary = if (darkTheme && rawAccent.luminance() < 0.22f) {
            lerp(rawAccent, Color.White, 0.35f)
        } else if (!darkTheme && rawAccent.luminance() > 0.72f) {
            lerp(rawAccent, Color.Black, 0.35f)
        } else {
            rawAccent
        }
        val onPrimary = if (effectivePrimary.luminance() > 0.45f) Color(0xFF14171A) else Color.White
        val container = if (darkTheme) {
            lerp(rawAccent, Color(0xFF1F2428), 0.62f)
        } else {
            lerp(rawAccent, Color.White, 0.76f)
        }
        val onContainer = if (container.luminance() > 0.45f) Color(0xFF14171A) else Color.White

        baseColorScheme.copy(
            primary = effectivePrimary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            tertiaryContainer = container,
            onTertiaryContainer = onContainer,
            background = appBackground,
            surface = baseCardSurface
        )
    } else {
        baseColorScheme.copy(
            background = appBackground,
            surface = baseCardSurface
        )
    }

    val expressiveColors = ExpressiveColors(
        appBackground = appBackground,
        cardSurface = cardSurface,
        nestedElementSurface = nestedSurface,
        buttonPrimary = accentedScheme.primary,
        buttonTertiarySubtle = accentedScheme.tertiaryContainer.copy(alpha = 0.85f),
        selectorSelected = accentedScheme.primary,
        selectorUnselected = cardSurface,
        uiBlurEnabled = uiBlurEnabled,
        uiTransparency = clampedTransparency,
        uiBlurRadiusDp = clampedBlurRadiusDp,
        frostedBorderColor = frostedBorder,
        dialogSurface = dialogSurface
    )

    CompositionLocalProvider(LocalExpressiveColors provides expressiveColors) {
        MaterialTheme(
            colorScheme = accentedScheme,
            typography = Typography,
            content = content
        )
    }
}
