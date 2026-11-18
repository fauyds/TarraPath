package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.ExpressiveTheme
import com.example.ui.theme.TelemetryTextStyle
import java.util.Locale
import kotlin.math.roundToInt

object ColorPickerMath {
    fun colorToHsv(color: Color): FloatArray {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        return hsv
    }

    fun hsvToColor(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): Color {
        val clampedHue = ((hue % 360f) + 360f) % 360f
        val clampedSat = saturation.coerceIn(0f, 1f)
        val clampedVal = value.coerceIn(0f, 1f)
        val argb = android.graphics.Color.HSVToColor(
            (alpha.coerceIn(0f, 1f) * 255f).roundToInt(),
            floatArrayOf(clampedHue, clampedSat, clampedVal)
        )
        return Color(argb)
    }

    fun formatHexRgb(color: Color): String {
        val rgb = color.toArgb() and 0xFFFFFF
        return String.format(Locale.US, "#%06X", rgb)
    }

    fun parseHexColor(raw: String): Color? {
        val clean = raw.trim().removePrefix("#")
        if (clean.length != 6 && clean.length != 8) return null
        val parsed = clean.toLongOrNull(16) ?: return null
        val argb = if (clean.length == 6) {
            (0xFF000000L or parsed).toInt()
        } else {
            parsed.toInt()
        }
        return Color(argb)
    }

    fun contrastTextColor(background: Color): Color {
        return if (background.luminance() > 0.45f) Color(0xFF14171A) else Color.White
    }

    fun adaptiveOutlineColor(fillColor: Color): Color {
        val lum = fillColor.luminance()
        return if (lum > 0.42f) {
            lerpColor(fillColor, Color(0xFF0E1116), 0.80f)
        } else {
            lerpColor(fillColor, Color.White, 0.88f)
        }
    }

    private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
        val t = fraction.coerceIn(0f, 1f)
        return Color(
            red = start.red + (stop.red - start.red) * t,
            green = start.green + (stop.green - start.green) * t,
            blue = start.blue + (stop.blue - start.blue) * t,
            alpha = 1f
        )
    }
}

private val QUICK_SWATCH_COLORS = listOf(
    0xFF1B6B52L,
    0xFF2563EBL,
    0xFF7C3AEDL,
    0xFFDB2777L,
    0xFFE11D48L,
    0xFFEA580CL,
    0xFFCA8A04L,
    0xFF0D9488L
)

@Composable
fun CustomColorPickerDialog(
    initialColorArgb: Long,
    title: String = "Custom Color Picker",
    subtitle: String = "Pick any hue, saturation, brightness, or hex color",
    showResetToDefault: Boolean = false,
    onResetToDefault: (() -> Unit)? = null,
    onConfirm: (colorArgb: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val startColor = remember(initialColorArgb) { Color(initialColorArgb.toInt()) }
    val startHsv = remember(startColor) { ColorPickerMath.colorToHsv(startColor) }

    var hue by remember(initialColorArgb) { mutableFloatStateOf(startHsv[0]) }
    var saturation by remember(initialColorArgb) { mutableFloatStateOf(startHsv[1]) }
    var value by remember(initialColorArgb) { mutableFloatStateOf(startHsv[2]) }
    var hexInput by remember(initialColorArgb) {
        mutableStateOf(ColorPickerMath.formatHexRgb(startColor))
    }

    val currentColor = ColorPickerMath.hsvToColor(hue, saturation, value)
    val currentArgb = currentColor.toArgb().toLong() and 0xFFFFFFFFL
    val red = (currentColor.red * 255f).roundToInt()
    val green = (currentColor.green * 255f).roundToInt()
    val blue = (currentColor.blue * 255f).roundToInt()

    fun syncHexFromHsv(h: Float, s: Float, v: Float) {
        hexInput = ColorPickerMath.formatHexRgb(ColorPickerMath.hsvToColor(h, s, v))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .padding(vertical = 20.dp),
        containerColor = ExpressiveTheme.colors.dialogSurface,
        shape = RoundedCornerShape(24.dp),
        title = {
            ApplyDialogBlurIfEnabled()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = currentColor,
                        border = BorderStroke(2.dp, Color.White),
                        shadowElevation = 2.dp,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Palette,
                                contentDescription = null,
                                tint = ColorPickerMath.contrastTextColor(currentColor),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close color picker"
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Chrome-style 2D Saturation x Value Spectrum Canvas
                SaturationValueSpectrumBox(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onSaturationValueChange = { s, v ->
                        saturation = s
                        value = v
                        syncHexFromHsv(hue, s, v)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .testTag("color_picker_sv_box")
                )

                // Hue Rainbow Spectrum Bar
                HueSpectrumSlider(
                    hue = hue,
                    onHueChange = { h ->
                        hue = h
                        syncHexFromHsv(h, saturation, value)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .testTag("color_picker_hue_bar")
                )

                // Live Preview Swatch + Editable #RRGGBB Hex Field + RGB Telemetry
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ExpressiveTheme.colors.nestedElementSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = currentColor,
                            border = BorderStroke(2.dp, Color.White),
                            shadowElevation = 2.dp,
                            modifier = Modifier.size(48.dp)
                        ) {}

                        OutlinedTextField(
                            value = hexInput,
                            onValueChange = { raw ->
                                val filtered = raw.uppercase(Locale.US).take(9)
                                hexInput = filtered
                                val parsed = ColorPickerMath.parseHexColor(filtered)
                                if (parsed != null) {
                                    val hsv = ColorPickerMath.colorToHsv(parsed)
                                    hue = hsv[0]
                                    saturation = hsv[1]
                                    value = hsv[2]
                                }
                            },
                            label = { Text("Hex") },
                            singleLine = true,
                            textStyle = TelemetryTextStyle.copy(fontSize = 14.sp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("color_picker_hex_input")
                        )

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "RGB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$red, $green, $blue",
                                style = TelemetryTextStyle.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Quick Preset Swatches
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Quick Swatches",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        QUICK_SWATCH_COLORS.forEach { argb ->
                            val swatchColor = Color(argb.toInt())
                            val selected = currentArgb == argb
                            Surface(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        val hsv = ColorPickerMath.colorToHsv(swatchColor)
                                        hue = hsv[0]
                                        saturation = hsv[1]
                                        value = hsv[2]
                                        hexInput = ColorPickerMath.formatHexRgb(swatchColor)
                                    },
                                shape = CircleShape,
                                color = swatchColor,
                                border = if (selected) {
                                    BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                                } else {
                                    BorderStroke(1.5.dp, Color.White)
                                }
                            ) {}
                        }
                    }
                }
            }
        },
        confirmButton = {
            ExpressivePillButton(
                text = "Apply Color",
                onClick = { onConfirm(currentArgb) },
                modifier = Modifier.testTag("color_picker_apply_button")
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showResetToDefault && onResetToDefault != null) {
                    TextButton(
                        onClick = onResetToDefault,
                        modifier = Modifier.testTag("color_picker_reset_button")
                    ) {
                        Text("Reset Default")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun SaturationValueSpectrumBox(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValueChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val pureHueColor = remember(hue) { ColorPickerMath.hsvToColor(hue, 1f, 1f) }

    fun updateFromOffset(offset: Offset) {
        if (boxSize.width <= 0 || boxSize.height <= 0) return
        val s = (offset.x / boxSize.width.toFloat()).coerceIn(0f, 1f)
        val v = 1f - (offset.y / boxSize.height.toFloat()).coerceIn(0f, 1f)
        onSaturationValueChange(s, v)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures { offset -> updateFromOffset(offset) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    updateFromOffset(change.position)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, pureHueColor)
                )
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black)
                )
            )

            val cx = (saturation.coerceIn(0f, 1f)) * size.width
            val cy = (1f - value.coerceIn(0f, 1f)) * size.height
            val center = Offset(cx, cy)

            drawCircle(
                color = Color.Black.copy(alpha = 0.45f),
                radius = 12.dp.toPx(),
                center = center,
                style = Stroke(width = 3.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = 10.dp.toPx(),
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
    }
}

@Composable
private fun HueSpectrumSlider(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val rainbow = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000)
        )
    }

    fun updateFromOffset(offset: Offset) {
        if (barSize.width <= 0) return
        val fraction = (offset.x / barSize.width.toFloat()).coerceIn(0f, 1f)
        onHueChange(fraction * 360f)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .onSizeChanged { barSize = it }
            .pointerInput(Unit) {
                detectTapGestures { offset -> updateFromOffset(offset) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    updateFromOffset(change.position)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                brush = Brush.horizontalGradient(rainbow),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
            )
            val thumbRadius = size.height / 2f - 2.dp.toPx()
            val usableWidth = (size.width - thumbRadius * 2f).coerceAtLeast(1f)
            val fraction = (hue.coerceIn(0f, 360f) / 360f)
            val cx = thumbRadius + fraction * usableWidth
            val cy = size.height / 2f

            drawCircle(
                color = ColorPickerMath.hsvToColor(hue, 1f, 1f),
                radius = thumbRadius,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color.White,
                radius = thumbRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 3.dp.toPx())
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.25f),
                radius = thumbRadius + 1.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}
