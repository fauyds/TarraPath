package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.example.data.model.AchievementDefinition
import com.example.data.model.MarkerStyle
import com.example.ui.icons.GoogleIconRegistry
import com.example.ui.theme.ExpressiveTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

val LocalBackdropHazeState = compositionLocalOf<HazeState?> { null }
val LocalBottomOverlayPadding = compositionLocalOf { 0.dp }

/**
 * Grouped list card geometry:
 * - Single item: 16.dp on all corners
 * - Grouped list: 16.dp outer corners, 4.dp inner adjacent corners
 */
fun groupedCardShape(index: Int, count: Int): RoundedCornerShape {
    return when {
        count <= 1 -> RoundedCornerShape(16.dp)
        index == 0 -> RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = 4.dp,
            bottomEnd = 4.dp
        )
        index == count - 1 -> RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomStart = 16.dp,
            bottomEnd = 16.dp
        )
        else -> RoundedCornerShape(4.dp)
    }
}

@Composable
fun isBackdropBlurActive(): Boolean {
    return ExpressiveTheme.colors.uiBlurEnabled && LocalBackdropHazeState.current != null
}

@Composable
fun Modifier.applyBackdropBlur(
    shape: Shape,
    tintColor: Color,
    blurRadius: Dp = Dp.Unspecified
): Modifier {
    val hazeState = LocalBackdropHazeState.current
    val resolvedRadius = if (blurRadius == Dp.Unspecified) {
        ExpressiveTheme.colors.uiBlurRadiusDp.dp
    } else {
        blurRadius
    }.coerceAtLeast(0.dp)
    return if (ExpressiveTheme.colors.uiBlurEnabled && hazeState != null) {
        this
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = tintColor.copy(alpha = (tintColor.alpha * 0.35f).coerceIn(0f, 1f)),
                    tint = HazeTint(tintColor),
                    blurRadius = resolvedRadius,
                    noiseFactor = 0.04f
                )
            )
    } else {
        this
    }
}

/**
 * Ambient atmospheric backdrop for non-map menus (`Routes`, `RouteDetail`, `Achievements`, `Settings`).
 * Draws the standard expressive background plus subtle Monet color glows & cartographic contour
 * arcs when UI Blur is enabled so frosted glass cards have real depth and gradients to blur.
 */
@Composable
fun FrostedMenuBackdrop(
    modifier: Modifier = Modifier
) {
    val baseColor = ExpressiveTheme.colors.appBackground
    val blurOn = ExpressiveTheme.colors.uiBlurEnabled
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val isDark = baseColor.luminance() < 0.4f

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(baseColor)
    ) {
        if (!blurOn) return@Canvas

        val w = size.width
        val h = size.height
        val glowAlpha1 = if (isDark) 0.24f else 0.18f
        val glowAlpha2 = if (isDark) 0.20f else 0.16f
        val glowAlpha3 = if (isDark) 0.16f else 0.13f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = glowAlpha1), Color.Transparent),
                center = Offset(w * 0.85f, h * 0.12f),
                radius = w * 0.85f
            ),
            radius = w * 0.85f,
            center = Offset(w * 0.85f, h * 0.12f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tertiary.copy(alpha = glowAlpha2), Color.Transparent),
                center = Offset(w * 0.12f, h * 0.48f),
                radius = w * 0.80f
            ),
            radius = w * 0.80f,
            center = Offset(w * 0.12f, h * 0.48f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = glowAlpha3), Color.Transparent),
                center = Offset(w * 0.78f, h * 0.86f),
                radius = w * 0.75f
            ),
            radius = w * 0.75f,
            center = Offset(w * 0.78f, h * 0.86f)
        )

        // Subtle cartographic contour rings so frosted glass blur radius changes are visibly crisp
        val ringAlpha = if (isDark) 0.11f else 0.10f
        val strokeWidth = 1.5.dp.toPx()
        drawCircle(
            color = primary.copy(alpha = ringAlpha),
            radius = w * 0.44f,
            center = Offset(w * 0.84f, h * 0.15f),
            style = Stroke(width = strokeWidth)
        )
        drawCircle(
            color = tertiary.copy(alpha = ringAlpha * 0.9f),
            radius = w * 0.62f,
            center = Offset(w * 0.84f, h * 0.15f),
            style = Stroke(width = strokeWidth)
        )
        drawCircle(
            color = secondary.copy(alpha = ringAlpha * 0.85f),
            radius = w * 0.48f,
            center = Offset(w * 0.14f, h * 0.60f),
            style = Stroke(width = strokeWidth)
        )
    }
}

/**
 * Container for scrollable menu screens that isolates the screen-local card backdrop HazeState
 * (whose source is the sibling [FrostedMenuBackdrop]) from the outer navigation HazeState (whose
 * source is this container box, allowing the bottom navigation bar and toast banners to blur the
 * scrolling menu content).
 */
@Composable
fun FrostedMenuContainer(
    modifier: Modifier = Modifier,
    blurBehindDialog: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val navHazeState = LocalBackdropHazeState.current
    val uiBlurEnabled = ExpressiveTheme.colors.uiBlurEnabled
    val uiBlurRadiusDp = ExpressiveTheme.colors.uiBlurRadiusDp
    val cardHazeState = remember(uiBlurEnabled) {
        if (uiBlurEnabled) HazeState() else null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ExpressiveTheme.colors.appBackground)
            .then(
                if (uiBlurEnabled && navHazeState != null) {
                    Modifier.hazeSource(state = navHazeState)
                } else {
                    Modifier
                }
            )
            .conditionalBlur(
                enabled = uiBlurEnabled && blurBehindDialog,
                radius = (uiBlurRadiusDp * 0.5f).coerceIn(2f, 20f).dp
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        FrostedMenuBackdrop(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (uiBlurEnabled && cardHazeState != null) {
                        Modifier.hazeSource(state = cardHazeState)
                    } else {
                        Modifier
                    }
                )
        )
        CompositionLocalProvider(LocalBackdropHazeState provides cardHazeState) {
            content()
        }
    }
}

@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color = ExpressiveTheme.colors.cardSurface,
    shadowElevation: Dp = 2.dp,
    border: BorderStroke? = null,
    showBorder: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val blurOn = ExpressiveTheme.colors.uiBlurEnabled
    val backdropActive = isBackdropBlurActive()
    val resolvedBorder = if (!showBorder) {
        null
    } else {
        border ?: if (blurOn) {
            BorderStroke(0.9.dp, ExpressiveTheme.colors.frostedBorderColor)
        } else {
            null
        }
    }
    val resolvedElevation = if (blurOn) 0.dp else shadowElevation

    Surface(
        modifier = modifier
            .applyBackdropBlur(shape = shape, tintColor = containerColor)
            .then(
                if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier
            ),
        shape = shape,
        color = if (backdropActive) Color.Transparent else containerColor,
        shadowElevation = resolvedElevation,
        border = resolvedBorder
    ) {
        Column(content = content)
    }
}

@Composable
fun ExpressivePillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    subtle: Boolean = false,
    containerColorOverride: Color? = null,
    contentColorOverride: Color? = null
) {
    val containerColor = containerColorOverride ?: if (subtle) {
        ExpressiveTheme.colors.buttonTertiarySubtle
    } else {
        ExpressiveTheme.colors.buttonPrimary
    }
    val contentColor = contentColorOverride ?: if (subtle) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(percent = 50),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier.defaultMinSize(minHeight = 48.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Horizontal Segmented Selector adhering strictly to Material 3 Expressive geometry:
 * - Spacing between segments: 4.dp
 * - Selected segment: Fully rounded corners (percent = 50)
 * - Unselected segment: Inner corners connected to other items are 4.dp, outer exposed corners are 50%
 */
@Composable
fun <T> ExpressiveHorizontalSegmentedSelector(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    labelProvider: (T) -> String,
    modifier: Modifier = Modifier,
    iconProvider: ((T) -> ImageVector)? = null,
    testTagPrefix: String = "segment"
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selectedOption
            val shape = when {
                isSelected -> RoundedCornerShape(percent = 50)
                options.size == 1 -> RoundedCornerShape(percent = 50)
                index == 0 -> RoundedCornerShape(
                    topStartPercent = 50,
                    bottomStartPercent = 50,
                    topEndPercent = 12,
                    bottomEndPercent = 12
                )
                index == options.lastIndex -> RoundedCornerShape(
                    topStartPercent = 12,
                    bottomStartPercent = 12,
                    topEndPercent = 50,
                    bottomEndPercent = 50
                )
                else -> RoundedCornerShape(4.dp)
            }
            // Exact 4.dp inner corners for first/last unselected items
            val exactShape = when {
                isSelected -> RoundedCornerShape(percent = 50)
                options.size == 1 -> RoundedCornerShape(percent = 50)
                index == 0 -> RoundedCornerShape(
                    topStart = 50.dp,
                    bottomStart = 50.dp,
                    topEnd = 4.dp,
                    bottomEnd = 4.dp
                )
                index == options.lastIndex -> RoundedCornerShape(
                    topStart = 4.dp,
                    bottomStart = 4.dp,
                    topEnd = 50.dp,
                    bottomEnd = 50.dp
                )
                else -> RoundedCornerShape(4.dp)
            }

            val blurOn = ExpressiveTheme.colors.uiBlurEnabled
            val backdropActive = isBackdropBlurActive()
            val bgColor by animateColorAsState(
                targetValue = when {
                    isSelected -> ExpressiveTheme.colors.selectorSelected
                    backdropActive -> Color.Transparent
                    else -> ExpressiveTheme.colors.selectorUnselected
                },
                label = "segment_bg"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                label = "segment_fg"
            )

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 48.dp)
                    .then(
                        if (!isSelected) {
                            Modifier.applyBackdropBlur(
                                shape = exactShape,
                                tintColor = ExpressiveTheme.colors.selectorUnselected
                            )
                        } else {
                            Modifier.clip(exactShape)
                        }
                    )
                    .clip(exactShape)
                    .clickable(role = Role.RadioButton) { onOptionSelected(option) }
                    .testTag("${testTagPrefix}_${index}"),
                shape = exactShape,
                color = bgColor,
                shadowElevation = if (blurOn) 0.dp else if (isSelected) 2.dp else 1.dp,
                border = if (blurOn && !isSelected) {
                    BorderStroke(0.9.dp, ExpressiveTheme.colors.frostedBorderColor)
                } else {
                    null
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (iconProvider != null) {
                        Icon(
                            imageVector = iconProvider(option),
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = labelProvider(option),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Vertical Segmented Selector with 4.dp spacing, 50% pill when selected,
 * 4.dp inner adjacent corners and 50% outer corners when unselected.
 */
@Composable
fun <T> ExpressiveVerticalSegmentedSelector(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    labelProvider: (T) -> String,
    subtitleProvider: ((T) -> String)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selectedOption
            val shape = when {
                isSelected -> RoundedCornerShape(percent = 50)
                options.size == 1 -> RoundedCornerShape(percent = 50)
                index == 0 -> RoundedCornerShape(
                    topStart = 50.dp,
                    topEnd = 50.dp,
                    bottomStart = 4.dp,
                    bottomEnd = 4.dp
                )
                index == options.lastIndex -> RoundedCornerShape(
                    topStart = 4.dp,
                    topEnd = 4.dp,
                    bottomStart = 50.dp,
                    bottomEnd = 50.dp
                )
                else -> RoundedCornerShape(4.dp)
            }
            val blurOn = ExpressiveTheme.colors.uiBlurEnabled
            val backdropActive = isBackdropBlurActive()
            val bgColor by animateColorAsState(
                targetValue = when {
                    isSelected -> ExpressiveTheme.colors.selectorSelected
                    backdropActive -> Color.Transparent
                    else -> ExpressiveTheme.colors.selectorUnselected
                },
                label = "vsegment_bg"
            )
            val fgColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                label = "vsegment_fg"
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .then(
                        if (!isSelected) {
                            Modifier.applyBackdropBlur(
                                shape = shape,
                                tintColor = ExpressiveTheme.colors.selectorUnselected
                            )
                        } else {
                            Modifier.clip(shape)
                        }
                    )
                    .clip(shape)
                    .clickable(role = Role.RadioButton) { onOptionSelected(option) },
                shape = shape,
                color = bgColor,
                shadowElevation = if (blurOn) 0.dp else if (isSelected) 2.dp else 1.dp,
                border = if (blurOn && !isSelected) {
                    BorderStroke(0.9.dp, ExpressiveTheme.colors.frostedBorderColor)
                } else {
                    null
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = labelProvider(option),
                        style = MaterialTheme.typography.titleSmall,
                        color = fgColor
                    )
                    if (subtitleProvider != null) {
                        Text(
                            text = subtitleProvider(option),
                            style = MaterialTheme.typography.bodySmall,
                            color = fgColor.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AchievementUnlockedBanner(
    achievement: AchievementDefinition?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(achievement?.id) {
        if (achievement != null) {
            delay(4000L)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = achievement != null,
        enter = slideInVertically(
            initialOffsetY = { -it },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        if (achievement != null) {
            val badgeScale by animateFloatAsState(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "badge_scale"
            )
            val blurOn = ExpressiveTheme.colors.uiBlurEnabled
            val backdropActive = isBackdropBlurActive()
            val bannerShape = RoundedCornerShape(16.dp)
            val bannerTint = if (blurOn) {
                MaterialTheme.colorScheme.primaryContainer.copy(
                    alpha = (1f - ExpressiveTheme.colors.uiTransparency).coerceIn(0.28f, 0.92f)
                )
            } else {
                MaterialTheme.colorScheme.primaryContainer
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .applyBackdropBlur(shape = bannerShape, tintColor = bannerTint),
                shape = bannerShape,
                color = if (backdropActive) Color.Transparent else bannerTint,
                shadowElevation = if (blurOn) 0.dp else 8.dp,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .scale(badgeScale)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.EmojiEvents,
                            contentDescription = "Achievement unlocked",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ACHIEVEMENT UNLOCKED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = achievement.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = achievement.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss achievement banner",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MarkerStylePreviewBadge(
    iconName: String,
    colorArgb: Long,
    markerStyle: MarkerStyle,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 42.dp
) {
    val density = LocalDensity.current.density
    val fillColor = Color(colorArgb.toInt())
    val fillArgb = fillColor.toArgb()
    val outlineColor = ColorPickerMath.adaptiveOutlineColor(fillColor)
    val contentColor = ColorPickerMath.contrastTextColor(fillColor)

    when (markerStyle) {
        MarkerStyle.ICON_ONLY -> {
            val px = ((badgeSize.value * 0.72f) * density).roundToInt().coerceAtLeast(24)
            val bmp = androidx.compose.runtime.remember(iconName, px, fillArgb) {
                GoogleIconRegistry.renderStyledIconBitmap(
                    iconName = iconName,
                    sizePx = px,
                    tintColor = fillArgb,
                    outlineColor = null
                )
            }
            Box(
                modifier = modifier.size(badgeSize),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = iconName,
                    modifier = Modifier.size(badgeSize * 0.72f)
                )
            }
        }
        MarkerStyle.ICON_OUTLINE -> {
            val px = ((badgeSize.value * 0.78f) * density).roundToInt().coerceAtLeast(28)
            val outlineArgb = outlineColor.toArgb()
            val strokePx = 3.2f * density
            val bmp = androidx.compose.runtime.remember(iconName, px, fillArgb, outlineArgb, strokePx) {
                GoogleIconRegistry.renderStyledIconBitmap(
                    iconName = iconName,
                    sizePx = px,
                    tintColor = fillArgb,
                    outlineColor = outlineArgb,
                    outlineWidthPx = strokePx
                )
            }
            Box(
                modifier = modifier.size(badgeSize),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = iconName,
                    modifier = Modifier.size(badgeSize * 0.78f)
                )
            }
        }
        MarkerStyle.CIRCLE_ICON -> {
            val px = ((badgeSize.value * 0.52f) * density).roundToInt().coerceAtLeast(20)
            val contentArgb = contentColor.toArgb()
            val bmp = androidx.compose.runtime.remember(iconName, px, contentArgb) {
                GoogleIconRegistry.renderStyledIconBitmap(
                    iconName = iconName,
                    sizePx = px,
                    tintColor = contentArgb,
                    outlineColor = null
                )
            }
            Surface(
                shape = CircleShape,
                color = fillColor,
                border = BorderStroke(2.dp, outlineColor),
                modifier = modifier.size(badgeSize)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = iconName,
                        modifier = Modifier.size(badgeSize * 0.52f)
                    )
                }
            }
        }
    }
}

fun Modifier.conditionalBlur(enabled: Boolean, radius: Dp = 12.dp): Modifier {
    return if (enabled && radius > 0.dp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.blur(radius = radius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
    } else {
        this
    }
}

@Composable
fun ApplyDialogBlurIfEnabled(blurRadiusPx: Int? = null) {
    val uiBlurEnabled = ExpressiveTheme.colors.uiBlurEnabled
    val uiBlurRadiusDp = ExpressiveTheme.colors.uiBlurRadiusDp
    val resolvedBlurPx = (blurRadiusPx ?: (uiBlurRadiusDp * 2f).roundToInt()).coerceIn(0, 80)
    if (!uiBlurEnabled || resolvedBlurPx <= 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return

    DisposableEffect(dialogWindow, uiBlurEnabled, resolvedBlurPx) {
        dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        val params = dialogWindow.attributes
        params.blurBehindRadius = resolvedBlurPx
        dialogWindow.attributes = params
        onDispose {
            dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            val resetParams = dialogWindow.attributes
            resetParams.blurBehindRadius = 0
            dialogWindow.attributes = resetParams
        }
    }
}
