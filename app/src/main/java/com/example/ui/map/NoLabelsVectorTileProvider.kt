package com.example.ui.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import org.json.JSONObject
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class NoLabelsVectorTileSource(
    val isDarkTheme: Boolean
) : BitmapTileSourceBase(
    if (isDarkTheme) "OpenFreeMapNoLabelsDark" else "OpenFreeMapNoLabelsLight",
    0,
    20,
    256,
    ".png"
)

internal enum class MvtGeomType {
    UNKNOWN, POINT, LINESTRING, POLYGON
}

internal data class MvtFeature(
    val type: MvtGeomType,
    val featureClass: String?,
    val commands: IntArray
)

internal data class MvtLayer(
    val name: String,
    val extent: Int,
    val features: List<MvtFeature>
)

internal data class DecodedMvtTile(
    val layersByName: Map<String, MvtLayer>
)

internal object MvtTileParser {
    private val geometryLayerNames = setOf(
        "landcover",
        "landuse",
        "park",
        "water",
        "waterway",
        "aeroway",
        "building",
        "boundary",
        "transportation"
    )

    fun parse(rawBytes: ByteArray): DecodedMvtTile {
        val bytes = maybeGunzip(rawBytes)
        val layers = LinkedHashMap<String, MvtLayer>()
        var pos = 0
        val len = bytes.size
        while (pos < len) {
            val tag = readVarint32(bytes, pos).also { pos = it.nextPos }.value
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 3 && wire == 2) {
                val msgLen = readVarint32(bytes, pos).also { pos = it.nextPos }.value
                val end = (pos + msgLen).coerceAtMost(len)
                val layer = parseLayer(bytes, pos, end)
                if (layer != null) {
                    layers[layer.name] = layer
                }
                pos = end
            } else {
                pos = skipField(bytes, pos, len, wire)
            }
        }
        return DecodedMvtTile(layers)
    }

    private fun parseLayer(bytes: ByteArray, start: Int, end: Int): MvtLayer? {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<String?>()
        val rawFeatures = ArrayList<Pair<Int, Int>>()

        var pos = start
        while (pos < end) {
            val tag = readVarint32(bytes, pos).also { pos = it.nextPos }.value
            val field = tag ushr 3
            val wire = tag and 0x7
            when {
                field == 1 && wire == 2 -> {
                    val sLen = readVarint32(bytes, pos).also { pos = it.nextPos }.value
                    val sEnd = (pos + sLen).coerceAtMost(end)
                    name = String(bytes, pos, sEnd - pos, Charsets.UTF_8)
                    pos = sEnd
                }
                field == 2 && wire == 2 -> {
                    val fLen = readVarint32(bytes, pos).also { pos = it.nextPos }.value
                    val fEnd = (pos + fLen).coerceAtMost(end)
                    rawFeatures.add(pos to fEnd)
                    pos = fEnd
                }
                field == 3 && wire == 2 -> {
                    val kLen = readVarint32(bytes, pos).also { pos = it.nextPos }.value
                    val kEnd = (pos + kLen).coerceAtMost(end)
                    keys.add(String(bytes, pos, kEnd - pos, Charsets.UTF_8))
                    pos = kEnd
                }
                field == 4 && wire == 2 -> {
                    val vLen = readVarint32(bytes, pos).also { pos = it.nextPos }.value
                    val vEnd = (pos + vLen).coerceAtMost(end)
                    values.add(parseValueString(bytes, pos, vEnd))
                    pos = vEnd
                }
                field == 5 && wire == 0 -> {
                    extent = readVarint32(bytes, pos).also { pos = it.nextPos }.value.coerceAtLeast(1)
                }
                else -> pos = skipField(bytes, pos, end, wire)
            }
        }

        if (name !in geometryLayerNames) return null

        val classKeyIdx = keys.indexOf("class")
        val features = ArrayList<MvtFeature>(rawFeatures.size)
        for ((fStart, fEnd) in rawFeatures) {
            val feat = parseFeature(bytes, fStart, fEnd, classKeyIdx, values)
            if (feat != null) features.add(feat)
        }
        return MvtLayer(name = name, extent = extent, features = features)
    }

    private fun parseValueString(bytes: ByteArray, start: Int, end: Int): String? {
        var pos = start
        var strVal: String? = null
        while (pos < end) {
            val tag = readVarint32(bytes, pos).also { pos = it.nextPos }.value
            val field = tag ushr 3
            val wire = tag and 0x7
            if (field == 1 && wire == 2) {
                val sLen = readVarint32(bytes, pos).also { pos = it.nextPos }.value
                val sEnd = (pos + sLen).coerceAtMost(end)
                strVal = String(bytes, pos, sEnd - pos, Charsets.UTF_8)
                pos = sEnd
            } else {
                pos = skipField(bytes, pos, end, wire)
            }
        }
        return strVal
    }

    private fun parseFeature(
        bytes: ByteArray,
        start: Int,
        end: Int,
        classKeyIdx: Int,
        values: List<String?>
    ): MvtFeature? {
        var pos = start
        var geomType = MvtGeomType.UNKNOWN
        var featureClass: String? = null
        val tagsList = ArrayList<Int>(8)
        val geomList = ArrayList<Int>(32)

        while (pos < end) {
            val tag = readVarint32(bytes, pos).also { pos = it.nextPos }.value
            val field = tag ushr 3
            val wire = tag and 0x7
            when {
                field == 2 && wire == 2 -> {
                    val pLen = readVarint32(bytes, pos).also { pos = it.nextPos }.value
                    val pEnd = (pos + pLen).coerceAtMost(end)
                    while (pos < pEnd) {
                        tagsList.add(readVarint32(bytes, pos).also { pos = it.nextPos }.value)
                    }
                }
                field == 2 && wire == 0 -> {
                    tagsList.add(readVarint32(bytes, pos).also { pos = it.nextPos }.value)
                }
                field == 3 && wire == 0 -> {
                    val t = readVarint32(bytes, pos).also { pos = it.nextPos }.value
                    geomType = when (t) {
                        1 -> MvtGeomType.POINT
                        2 -> MvtGeomType.LINESTRING
                        3 -> MvtGeomType.POLYGON
                        else -> MvtGeomType.UNKNOWN
                    }
                }
                field == 4 && wire == 2 -> {
                    val pLen = readVarint32(bytes, pos).also { pos = it.nextPos }.value
                    val pEnd = (pos + pLen).coerceAtMost(end)
                    while (pos < pEnd) {
                        geomList.add(readVarint32(bytes, pos).also { pos = it.nextPos }.value)
                    }
                }
                field == 4 && wire == 0 -> {
                    geomList.add(readVarint32(bytes, pos).also { pos = it.nextPos }.value)
                }
                else -> pos = skipField(bytes, pos, end, wire)
            }
        }

        if (geomType != MvtGeomType.LINESTRING && geomType != MvtGeomType.POLYGON) return null
        if (geomList.isEmpty()) return null

        if (classKeyIdx >= 0) {
            var i = 0
            while (i + 1 < tagsList.size) {
                if (tagsList[i] == classKeyIdx) {
                    val vIdx = tagsList[i + 1]
                    if (vIdx in values.indices) {
                        featureClass = values[vIdx]
                    }
                    break
                }
                i += 2
            }
        }

        return MvtFeature(
            type = geomType,
            featureClass = featureClass,
            commands = geomList.toIntArray()
        )
    }

    private fun maybeGunzip(raw: ByteArray): ByteArray {
        if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(raw)).use { gzip ->
                return gzip.readBytes()
            }
        }
        return raw
    }

    private data class VarintResult(val value: Int, val nextPos: Int)

    private fun readVarint32(bytes: ByteArray, start: Int): VarintResult {
        var pos = start
        var result = 0
        var shift = 0
        while (pos < bytes.size && shift < 35) {
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) {
                return VarintResult(result, pos)
            }
            shift += 7
        }
        return VarintResult(result, pos)
    }

    private fun skipField(bytes: ByteArray, start: Int, end: Int, wireType: Int): Int {
        return when (wireType) {
            0 -> {
                var pos = start
                while (pos < end) {
                    val b = bytes[pos++].toInt() and 0xFF
                    if ((b and 0x80) == 0) break
                }
                pos
            }
            1 -> (start + 8).coerceAtMost(end)
            2 -> {
                val r = readVarint32(bytes, start)
                (r.nextPos + r.value).coerceAtMost(end)
            }
            5 -> (start + 4).coerceAtMost(end)
            else -> end
        }
    }
}

internal object NoLabelsVectorTileRenderer {
    private const val TILE_PX = 256
    private const val MAX_NATIVE_ZOOM = 14
    private const val TILEJSON_URL = "https://tiles.openfreemap.org/planet"
    private const val FALLBACK_TEMPLATE = "https://tiles.openfreemap.org/planet/{z}/{x}/{y}.pbf"

    private val templateLock = Any()

    @Volatile
    private var cachedTemplate: String? = null

    private val decodedCache = object : LruCache<String, DecodedMvtTile>(48) {}

    fun renderTileBitmap(z: Int, x: Int, y: Int, isDarkTheme: Boolean): Bitmap? {
        val srcZ = z.coerceIn(0, MAX_NATIVE_ZOOM)
        val dz = (z - srcZ).coerceAtLeast(0)
        val scale = 1 shl dz
        val srcX = x shr dz
        val srcY = y shr dz
        val subX = x and (scale - 1)
        val subY = y and (scale - 1)

        val tile = loadOrFetchDecodedTile(srcZ, srcX, srcY) ?: return null
        return drawTileBitmap(
            tile = tile,
            z = z,
            scale = scale.toFloat(),
            subX = subX.toFloat(),
            subY = subY.toFloat(),
            isDarkTheme = isDarkTheme
        )
    }

    private fun resolveUrlTemplate(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            cachedTemplate?.let { return it }
        }
        synchronized(templateLock) {
            if (!forceRefresh) {
                cachedTemplate?.let { return it }
            }
            val fetched = runCatching {
                val conn = (URL(TILEJSON_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    setRequestProperty("User-Agent", "TerraPath/1.0 (Android)")
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                        val tilesArr = JSONObject(jsonStr).optJSONArray("tiles")
                        val first = tilesArr?.optString(0)?.trim().orEmpty()
                        if (first.contains("{z}") && first.contains("{x}") && first.contains("{y}")) {
                            first
                        } else null
                    } else null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull() ?: FALLBACK_TEMPLATE
            cachedTemplate = fetched
            return fetched
        }
    }

    private fun loadOrFetchDecodedTile(z: Int, x: Int, y: Int): DecodedMvtTile? {
        val cacheKey = "$z/$x/$y"
        synchronized(decodedCache) {
            decodedCache.get(cacheKey)?.let { return it }
        }
        var raw = fetchPbfBytes(resolveUrlTemplate(forceRefresh = false), z, x, y)
        if (raw == null) {
            // Planet version folder may have rotated on OpenFreeMap CDN; refresh TileJSON once
            raw = fetchPbfBytes(resolveUrlTemplate(forceRefresh = true), z, x, y)
        }
        val bytes = raw ?: return null
        val decoded = runCatching { MvtTileParser.parse(bytes) }.getOrNull() ?: return null
        synchronized(decodedCache) {
            decodedCache.put(cacheKey, decoded)
        }
        return decoded
    }

    private fun fetchPbfBytes(template: String, z: Int, x: Int, y: Int): ByteArray? {
        val urlStr = template
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "TerraPath/1.0 (Android)")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            try {
                when (conn.responseCode) {
                    in 200..299 -> {
                        val stream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                            GZIPInputStream(conn.inputStream)
                        } else {
                            conn.inputStream
                        }
                        stream.use { it.readBytes() }
                    }
                    // Empty ocean/rural tiles sometimes return 204 or 404
                    204 -> ByteArray(0)
                    else -> null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun drawTileBitmap(
        tile: DecodedMvtTile,
        z: Int,
        scale: Float,
        subX: Float,
        subY: Float,
        isDarkTheme: Boolean
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgColor = if (isDarkTheme) 0xFF1A1E24.toInt() else 0xFFF2EFE9.toInt()
        val parkColor = if (isDarkTheme) 0xFF1E2D24.toInt() else 0xFFDCE8D5.toInt()
        val urbanColor = if (isDarkTheme) 0xFF1E2229.toInt() else 0xFFEAE6DF.toInt()
        val waterColor = if (isDarkTheme) 0xFF15293A.toInt() else 0xFFAAD3DF.toInt()
        val aerowayColor = if (isDarkTheme) 0xFF282E38.toInt() else 0xFFDDD8CE.toInt()
        val buildingFill = if (isDarkTheme) 0xFF232831.toInt() else 0xFFE2DDD4.toInt()
        val buildingStroke = if (isDarkTheme) 0xFF2F3642.toInt() else 0xFFD1CBC0.toInt()
        val boundaryColor = if (isDarkTheme) 0x4494A3B8 else 0x44475569

        val majorCasing = if (isDarkTheme) 0xFF423422.toInt() else 0xFFD49A56.toInt()
        val majorFill = if (isDarkTheme) 0xFF6B5233.toInt() else 0xFFF6C270.toInt()
        val roadCasing = if (isDarkTheme) 0xFF282E38.toInt() else 0xFFCDC7BC.toInt()
        val roadFill = if (isDarkTheme) 0xFF3A4250.toInt() else 0xFFFFFFFF.toInt()
        val minorFill = if (isDarkTheme) 0xFF2E3440.toInt() else 0xFFFAF8F5.toInt()
        val pathColor = if (isDarkTheme) 0xFF29303B.toInt() else 0xFFDAD4C9.toInt()
        val railColor = if (isDarkTheme) 0xFF475569.toInt() else 0xFF94A3B8.toInt()

        canvas.drawColor(bgColor)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()

        // 1. Landuse & Landcover & Park polygons
        for (layerName in listOf("landuse", "landcover", "park")) {
            val layer = tile.layersByName[layerName] ?: continue
            for (feat in layer.features) {
                if (feat.type != MvtGeomType.POLYGON) continue
                val cls = feat.featureClass.orEmpty()
                fillPaint.color = when (cls) {
                    "wood", "forest", "grass", "park", "meadow", "wetland", "garden", "recreation_ground", "cemetery" -> parkColor
                    "residential", "commercial", "industrial", "suburb", "neighbourhood" -> urbanColor
                    else -> if (layerName == "park") parkColor else continue
                }
                buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = true)
                canvas.drawPath(path, fillPaint)
            }
        }

        // 2. Water polygons & waterways
        tile.layersByName["water"]?.let { layer ->
            fillPaint.color = waterColor
            for (feat in layer.features) {
                if (feat.type == MvtGeomType.POLYGON) {
                    buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = true)
                    canvas.drawPath(path, fillPaint)
                }
            }
        }
        tile.layersByName["waterway"]?.let { layer ->
            strokePaint.color = waterColor
            for (feat in layer.features) {
                if (feat.type == MvtGeomType.LINESTRING) {
                    strokePaint.strokeWidth = when (feat.featureClass) {
                        "river", "canal" -> if (z >= 14) 5f else 2.5f
                        else -> if (z >= 14) 2.2f else 1.2f
                    }
                    buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = false)
                    canvas.drawPath(path, strokePaint)
                }
            }
        }

        // 3. Aeroway
        tile.layersByName["aeroway"]?.let { layer ->
            for (feat in layer.features) {
                when (feat.type) {
                    MvtGeomType.POLYGON -> {
                        fillPaint.color = aerowayColor
                        buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = true)
                        canvas.drawPath(path, fillPaint)
                    }
                    MvtGeomType.LINESTRING -> {
                        strokePaint.color = aerowayColor
                        strokePaint.strokeWidth = if (feat.featureClass == "runway") 6f else 3f
                        buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = false)
                        canvas.drawPath(path, strokePaint)
                    }
                    else -> Unit
                }
            }
        }

        // 4. Buildings (visible z >= 13)
        if (z >= 13) {
            tile.layersByName["building"]?.let { layer ->
                for (feat in layer.features) {
                    if (feat.type != MvtGeomType.POLYGON) continue
                    buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = true)
                    fillPaint.color = buildingFill
                    canvas.drawPath(path, fillPaint)
                    strokePaint.color = buildingStroke
                    strokePaint.strokeWidth = 0.8f
                    canvas.drawPath(path, strokePaint)
                }
            }
        }

        // 5. Administrative boundaries
        tile.layersByName["boundary"]?.let { layer ->
            strokePaint.color = boundaryColor
            strokePaint.strokeWidth = 1.2f
            strokePaint.pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
            for (feat in layer.features) {
                if (feat.type == MvtGeomType.LINESTRING) {
                    buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = false)
                    canvas.drawPath(path, strokePaint)
                }
            }
            strokePaint.pathEffect = null
        }

        // 6. Transportation (two-pass casing + core for clean intersections, NO street text labels)
        tile.layersByName["transportation"]?.let { layer ->
            val zoomBoost = when {
                z >= 17 -> 1.9f
                z >= 15 -> 1.45f
                z >= 13 -> 1.0f
                else -> 0.65f
            }

            // Pass A: Paths & rail
            for (feat in layer.features) {
                if (feat.type != MvtGeomType.LINESTRING) continue
                val cls = feat.featureClass.orEmpty()
                when (cls) {
                    "path", "track", "footway", "cycleway", "bridleway" -> {
                        strokePaint.color = pathColor
                        strokePaint.strokeWidth = 1.5f * zoomBoost
                        buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = false)
                        canvas.drawPath(path, strokePaint)
                    }
                    "rail", "transit" -> {
                        strokePaint.color = railColor
                        strokePaint.strokeWidth = 1.6f * zoomBoost
                        buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = false)
                        canvas.drawPath(path, strokePaint)
                    }
                }
            }

            // Pass B: Road casings
            for (feat in layer.features) {
                if (feat.type != MvtGeomType.LINESTRING) continue
                val cls = feat.featureClass.orEmpty()
                val baseWidth = roadBaseWidth(cls) ?: continue
                strokePaint.color = if (cls == "motorway" || cls == "trunk") majorCasing else roadCasing
                strokePaint.strokeWidth = baseWidth * zoomBoost + 2.0f
                buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = false)
                canvas.drawPath(path, strokePaint)
            }

            // Pass C: Road cores (minor -> primary -> motorway)
            val orderedClasses = listOf(
                listOf("service", "minor", "living_street"),
                listOf("tertiary", "secondary"),
                listOf("primary"),
                listOf("trunk", "motorway")
            )
            for (group in orderedClasses) {
                for (feat in layer.features) {
                    if (feat.type != MvtGeomType.LINESTRING) continue
                    val cls = feat.featureClass.orEmpty()
                    if (cls !in group) continue
                    val baseWidth = roadBaseWidth(cls) ?: continue
                    strokePaint.color = when (cls) {
                        "motorway", "trunk" -> majorFill
                        "primary", "secondary", "tertiary" -> roadFill
                        else -> minorFill
                    }
                    strokePaint.strokeWidth = baseWidth * zoomBoost
                    buildFeaturePath(feat, layer.extent, scale, subX, subY, path, isPolygon = false)
                    canvas.drawPath(path, strokePaint)
                }
            }
        }

        return bitmap
    }

    private fun roadBaseWidth(cls: String): Float? = when (cls) {
        "motorway" -> 5.6f
        "trunk" -> 5.0f
        "primary" -> 4.4f
        "secondary" -> 3.8f
        "tertiary" -> 3.2f
        "minor", "living_street" -> 2.4f
        "service" -> 1.6f
        else -> null
    }

    private fun buildFeaturePath(
        feat: MvtFeature,
        extent: Int,
        scale: Float,
        subX: Float,
        subY: Float,
        outPath: Path,
        isPolygon: Boolean
    ) {
        outPath.reset()
        outPath.fillType = if (isPolygon) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val cmds = feat.commands
        val invExtent = 1f / extent.toFloat()
        val tilePx = TILE_PX.toFloat()

        var idx = 0
        var cx = 0
        var cy = 0
        while (idx < cmds.size) {
            val cmdInt = cmds[idx++]
            val cmd = cmdInt and 0x7
            val count = cmdInt ushr 3
            when (cmd) {
                1 -> { // MoveTo
                    for (i in 0 until count) {
                        if (idx + 1 >= cmds.size) break
                        val dx = zigZagDecode(cmds[idx++])
                        val dy = zigZagDecode(cmds[idx++])
                        cx += dx
                        cy += dy
                        val px = (cx * invExtent * scale - subX) * tilePx
                        val py = (cy * invExtent * scale - subY) * tilePx
                        if (i == 0) outPath.moveTo(px, py) else outPath.lineTo(px, py)
                    }
                }
                2 -> { // LineTo
                    for (i in 0 until count) {
                        if (idx + 1 >= cmds.size) break
                        val dx = zigZagDecode(cmds[idx++])
                        val dy = zigZagDecode(cmds[idx++])
                        cx += dx
                        cy += dy
                        val px = (cx * invExtent * scale - subX) * tilePx
                        val py = (cy * invExtent * scale - subY) * tilePx
                        outPath.lineTo(px, py)
                    }
                }
                7 -> { // ClosePath
                    outPath.close()
                }
                else -> return
            }
        }
    }

    private fun zigZagDecode(n: Int): Int = (n ushr 1) xor -(n and 1)
}

class NoLabelsVectorTileModuleProvider(
    private val resources: Resources,
    initialTileSource: ITileSource,
    private val tileWriter: IFilesystemCache?
) : MapTileModuleProviderBase(4, 48) {

    @Volatile
    private var currentTileSource: ITileSource? = initialTileSource

    override fun getUsesDataConnection(): Boolean = true
    override fun getName(): String = "OpenFreeMapNoLabelsVector"
    override fun getThreadGroupName(): String = "openfreemap_nolabels"
    override fun getMinimumZoomLevel(): Int = 0
    override fun getMaximumZoomLevel(): Int = 20

    override fun setTileSource(tileSource: ITileSource?) {
        currentTileSource = tileSource
    }

    override fun getTileLoader(): TileLoader = VectorTileLoader()

    inner class VectorTileLoader : TileLoader() {
        override fun loadTile(pMapTileIndex: Long): Drawable? {
            val activeSource = currentTileSource as? NoLabelsVectorTileSource ?: return null
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)

            val bitmap = NoLabelsVectorTileRenderer.renderTileBitmap(
                z = z,
                x = x,
                y = y,
                isDarkTheme = activeSource.isDarkTheme
            ) ?: return null

            if (tileWriter != null) {
                runCatching {
                    val out = ByteArrayOutputStream(8192)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    ByteArrayInputStream(out.toByteArray()).use { stream ->
                        tileWriter.saveFile(activeSource, pMapTileIndex, stream, null)
                    }
                }
            }

            return BitmapDrawable(resources, bitmap)
        }
    }
}

class TerraMapTileProvider(
    context: android.content.Context,
    initialTileSource: ITileSource
) : MapTileProviderBasic(context, initialTileSource) {
    init {
        val vectorModule = NoLabelsVectorTileModuleProvider(
            resources = context.resources,
            initialTileSource = initialTileSource,
            tileWriter = tileWriter
        )
        val insertIndex = mTileProviderList.indexOfFirst {
            it is MapTileApproximater || it is MapTileDownloader
        }
        if (insertIndex >= 0) {
            mTileProviderList.add(insertIndex, vectorModule)
        } else {
            mTileProviderList.add(vectorModule)
        }
    }
}
