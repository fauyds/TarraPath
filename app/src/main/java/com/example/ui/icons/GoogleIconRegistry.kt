package com.example.ui.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.util.LruCache
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class GoogleIconEntry(
    val name: String,
    val displayName: String,
    val searchKey: String
)

enum class IconFilterGroup(val label: String, val keywords: List<String>) {
    ALL("All Icons", emptyList()),
    PLACES("Places", listOf("home", "house", "apartment", "store", "shop", "mall", "hotel", "museum", "church", "temple", "mosque", "hospital", "school", "university", "bank", "atm", "local", "place", "location", "map", "pin", "flag", "city", "castle", "tower", "factory", "warehouse", "cottage", "villa", "cabin")),
    FOOD("Food & Drink", listOf("restaurant", "cafe", "coffee", "bakery", "bar", "wine", "liquor", "dining", "lunch", "dinner", "breakfast", "pizza", "cake", "icecream", "fastfood", "kitchen", "tea", "ramen", "soup", "bento", "egg", "cookie", "tapas", "kebab")),
    TRANSIT("Transit", listOf("directions", "train", "tram", "subway", "bus", "car", "taxi", "bike", "flight", "airport", "boat", "ferry", "traffic", "parking", "ev", "gas", "navigation", "compass", "route", "rail", "scooter", "motorcycle", "rocket")),
    NATURE("Nature", listOf("park", "forest", "nature", "tree", "terrain", "landscape", "water", "beach", "waves", "sun", "moon", "star", "cloud", "hiking", "camp", "flower", "eco", "pets", "volcano", "snow", "grass", "yard", "sailing")),
    ACTIVITY("Activity", listOf("run", "walk", "fitness", "sport", "soccer", "basketball", "tennis", "golf", "swim", "pool", "gym", "stadium", "trophy", "medal", "bolt", "skate", "surfing", "kayaking", "rowing", "climbing")),
    SHOPPING("Shopping", listOf("shopping", "cart", "bag", "basket", "store", "storefront", "receipt", "sell", "tag", "discount", "percent", "payment", "credit", "wallet", "gift", "card", "redeem", "currency"))
}

object GoogleIconRegistry {
    @Volatile
    private var cachedEntries: List<GoogleIconEntry>? = null

    private val vectorCache = ConcurrentHashMap<String, ImageVector>()
    private val bitmapCache = LruCache<String, Bitmap>(160)

    fun loadAllIcons(context: Context): List<GoogleIconEntry> {
        cachedEntries?.let { return it }
        synchronized(this) {
            cachedEntries?.let { return it }
            val loaded = runCatching {
                context.assets.open("google_icons.txt")
                    .bufferedReader()
                    .useLines { lines ->
                        lines.map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .map { name ->
                                val readable = formatReadableName(name)
                                GoogleIconEntry(
                                    name = name,
                                    displayName = readable,
                                    searchKey = (name + " " + readable).lowercase(Locale.US)
                                )
                            }
                            .toList()
                    }
            }.getOrDefault(emptyList())
            cachedEntries = loaded
            return loaded
        }
    }

    fun resolveIcon(name: String): ImageVector {
        val clean = name.trim()
        if (clean.isEmpty()) return Icons.Filled.Place
        vectorCache[clean]?.let { return it }

        val resolved = runCatching {
            val clazz = Class.forName("androidx.compose.material.icons.filled.${clean}Kt")
            val method = clazz.getMethod("get$clean", Icons.Filled::class.java)
            method.invoke(null, Icons.Filled) as? ImageVector
        }.getOrNull() ?: Icons.Filled.Place

        vectorCache[clean] = resolved
        return resolved
    }

    fun renderCenteredIconBitmap(iconName: String, sizePx: Int): Bitmap {
        val safeSize = sizePx.coerceAtLeast(16)
        val cacheKey = "${iconName}_$safeSize"
        bitmapCache.get(cacheKey)?.let { return it }

        val vector = resolveIcon(iconName)
        val bitmap = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        val vw = vector.viewportWidth.coerceAtLeast(1f)
        val vh = vector.viewportHeight.coerceAtLeast(1f)
        val scale = minOf(safeSize / vw, safeSize / vh)
        val offsetX = (safeSize - vw * scale) / 2f
        val offsetY = (safeSize - vh * scale) / 2f

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        drawVectorNode(canvas, vector.root, paint)
        canvas.restore()

        bitmapCache.put(cacheKey, bitmap)
        return bitmap
    }

    private fun drawVectorNode(canvas: Canvas, node: VectorNode, paint: Paint) {
        when (node) {
            is VectorGroup -> {
                canvas.save()
                canvas.translate(node.translationX, node.translationY)
                canvas.translate(node.pivotX, node.pivotY)
                canvas.rotate(node.rotation)
                canvas.scale(node.scaleX, node.scaleY)
                canvas.translate(-node.pivotX, -node.pivotY)
                for (i in 0 until node.size) {
                    drawVectorNode(canvas, node[i], paint)
                }
                canvas.restore()
            }
            is VectorPath -> {
                val composePath = PathParser().addPathNodes(node.pathData).toPath()
                val androidPath = composePath.asAndroidPath()
                androidPath.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                    AndroidPath.FillType.EVEN_ODD
                } else {
                    AndroidPath.FillType.WINDING
                }
                paint.alpha = (node.fillAlpha * 255f).toInt().coerceIn(0, 255)
                canvas.drawPath(androidPath, paint)
            }
        }
    }

    private fun formatReadableName(raw: String): String {
        val noPrefix = raw.removePrefix("_")
        val sb = StringBuilder(noPrefix.length + 4)
        for (i in noPrefix.indices) {
            val ch = noPrefix[i]
            if (i > 0 && ch.isUpperCase() && !noPrefix[i - 1].isUpperCase()) {
                sb.append(' ')
            } else if (i > 0 && ch.isDigit() && noPrefix[i - 1].isLetter()) {
                sb.append(' ')
            }
            sb.append(ch)
        }
        return sb.toString()
    }
}
