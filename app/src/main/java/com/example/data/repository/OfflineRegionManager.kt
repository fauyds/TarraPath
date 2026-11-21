package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.example.data.local.OfflineRegionDao
import com.example.data.local.OfflineRegionEntity
import com.example.data.model.ApiProvider
import com.example.data.model.OfflineDownloadProgress
import com.example.data.remote.OnlineWalkingRouteOutcome
import com.example.domain.BoundingBox
import com.example.domain.OfflineRoadGraph
import com.example.domain.WalkingNavigationPolicy
import com.example.domain.WalkingNavigationPolicy.covers
import com.example.ui.map.NoLabelsVectorTileRenderer
import com.example.ui.map.NoLabelsVectorTileSource
import com.example.ui.map.resolveMapTileSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

class OfflineRegionManager(
    private val context: Context,
    private val regionDao: OfflineRegionDao
) {
    val allRegions: Flow<List<OfflineRegionEntity>> = regionDao.observeAllRegions()

    private val _downloadProgress = MutableStateFlow(OfflineDownloadProgress())
    val downloadProgress: StateFlow<OfflineDownloadProgress> = _downloadProgress.asStateFlow()

    private val graphCache = LruCache<String, OfflineRoadGraph>(4)

    private fun regionsDir(): File {
        return File(context.filesDir, "offline_regions").apply { mkdirs() }
    }

    suspend fun downloadOfflineArea(
        regionName: String,
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Int,
        isDarkTheme: Boolean,
        showStreetNames: Boolean,
        apiKey: String,
        apiProvider: ApiProvider
    ): Result<OfflineRegionEntity> = withContext(Dispatchers.IO) {
        val cleanName = regionName.trim().ifEmpty { "Offline Area" }
        val clampedRadius = radiusMeters.coerceIn(1000, 25_000)
        val bbox = WalkingNavigationPolicy.boundingBoxForRadius(centerLat, centerLon, clampedRadius)

        _downloadProgress.value = OfflineDownloadProgress(
            isDownloading = true,
            regionName = cleanName,
            stage = "Fetching walkable streets & footpaths…",
            progressFraction = 0.05f,
            errorMessage = null
        )

        return@withContext try {
            val overpassChunks = fetchOverpassWalkChunks(bbox, clampedRadius) { chunkDone, chunkTotal ->
                val frac = if (chunkTotal > 0) chunkDone.toFloat() / chunkTotal.toFloat() else 0f
                _downloadProgress.value = _downloadProgress.value.copy(
                    stage = if (chunkTotal > 1) {
                        "Downloading walkable streets ($chunkDone/$chunkTotal)…"
                    } else {
                        "Fetching walkable streets & footpaths…"
                    },
                    progressFraction = 0.05f + frac * 0.45f
                )
            }
            _downloadProgress.value = _downloadProgress.value.copy(
                stage = "Building CPU walking graph…",
                progressFraction = 0.54f
            )

            val graph = withContext(Dispatchers.Default) {
                OfflineRoadGraph.parseOverpassWalkGraphChunks(overpassChunks)
            }
            if (graph.nodeCount < 2 || graph.edgeCount == 0) {
                val emptyErr = IllegalStateException("No walkable paths found in this area")
                _downloadProgress.value = OfflineDownloadProgress(
                    isDownloading = false,
                    regionName = cleanName,
                    errorMessage = emptyErr.message
                )
                return@withContext Result.failure(emptyErr)
            }

            val fileName = "walk_region_${System.currentTimeMillis()}.bin"
            val graphFile = File(regionsDir(), fileName)
            FileOutputStream(graphFile).use { fos ->
                graph.writeTo(fos)
            }
            graphCache.put(fileName, graph)

            _downloadProgress.value = _downloadProgress.value.copy(
                stage = "Caching offline map tiles…",
                progressFraction = 0.58f
            )

            val (tileCount, tileBytes) = cacheMapTilesForBbox(
                bbox = bbox,
                centerLat = centerLat,
                centerLon = centerLon,
                isDarkTheme = isDarkTheme,
                showStreetNames = showStreetNames,
                apiKey = apiKey,
                apiProvider = apiProvider
            ) { fraction ->
                _downloadProgress.value = _downloadProgress.value.copy(
                    stage = "Caching offline map tiles…",
                    progressFraction = 0.58f + fraction * 0.39f
                )
            }

            val entity = OfflineRegionEntity(
                name = cleanName,
                centerLat = centerLat,
                centerLon = centerLon,
                radiusMeters = clampedRadius,
                southLat = bbox.southLat,
                westLon = bbox.westLon,
                northLat = bbox.northLat,
                eastLon = bbox.eastLon,
                nodeCount = graph.nodeCount,
                edgeCount = graph.edgeCount / 2,
                tileCount = tileCount,
                sizeBytes = graphFile.length() + tileBytes,
                graphFileName = fileName,
                downloadedAt = System.currentTimeMillis()
            )
            val insertedId = regionDao.insertRegion(entity)
            val savedEntity = entity.copy(id = insertedId)

            _downloadProgress.value = OfflineDownloadProgress(
                isDownloading = false,
                regionName = cleanName,
                stage = "Ready offline",
                progressFraction = 1f,
                errorMessage = null
            )
            Result.success(savedEntity)
        } catch (t: Throwable) {
            _downloadProgress.value = OfflineDownloadProgress(
                isDownloading = false,
                regionName = cleanName,
                stage = "",
                progressFraction = 0f,
                errorMessage = t.localizedMessage ?: "Failed to download offline area"
            )
            Result.failure(t)
        }
    }

    fun clearDownloadError() {
        if (!_downloadProgress.value.isDownloading) {
            _downloadProgress.value = OfflineDownloadProgress()
        }
    }

    suspend fun deleteRegion(region: OfflineRegionEntity) = withContext(Dispatchers.IO) {
        graphCache.remove(region.graphFileName)
        runCatching {
            val f = File(regionsDir(), region.graphFileName)
            if (f.exists()) f.delete()
        }
        regionDao.deleteRegionById(region.id)
    }

    suspend fun calculateOfflineWalkingRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): Result<OnlineWalkingRouteOutcome> = withContext(Dispatchers.Default) {
        val regions = withContext(Dispatchers.IO) { regionDao.getAllRegionsOnce() }
        if (regions.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("No offline areas downloaded. Download an area first.")
            )
        }

        // Prefer smallest/newest region covering BOTH endpoints
        val coveringBoth = regions
            .filter { it.covers(startLat, startLon) && it.covers(endLat, endLon) }
            .sortedWith(compareBy<OfflineRegionEntity> { it.radiusMeters }.thenByDescending { it.downloadedAt })

        val candidateRegions = if (coveringBoth.isNotEmpty()) {
            coveringBoth
        } else {
            val coveringEither = regions.filter { it.covers(startLat, startLon) || it.covers(endLat, endLon) }
            if (coveringEither.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Outside downloaded offline areas. Download this area to route offline.")
                )
            }
            coveringEither
        }

        for (region in candidateRegions) {
            val graph = loadGraph(region.graphFileName) ?: continue
            val result = graph.findWalkingRoute(
                startLat = startLat,
                startLon = startLon,
                endLat = endLat,
                endLon = endLon
            )
            if (result != null && result.points.size >= 2) {
                return@withContext Result.success(
                    OnlineWalkingRouteOutcome(
                        points = result.points,
                        distanceMeters = result.distanceMeters,
                        estimatedSeconds = result.estimatedSeconds,
                        providerLabel = "Offline CPU (${region.name})"
                    )
                )
            }
        }

        Result.failure(
            IllegalStateException("No connected walking path in downloaded area covering both points.")
        )
    }

    private fun loadGraph(fileName: String): OfflineRoadGraph? {
        graphCache.get(fileName)?.let { return it }
        val file = File(regionsDir(), fileName)
        if (!file.exists()) return null
        return runCatching {
            FileInputStream(file).use { fis ->
                OfflineRoadGraph.readFrom(fis)
            }
        }.getOrNull()?.also { loaded ->
            graphCache.put(fileName, loaded)
        }
    }

    private fun fetchOverpassWalkChunks(
        bbox: BoundingBox,
        radiusMeters: Int,
        onChunkProgress: (done: Int, total: Int) -> Unit
    ): List<String> {
        val gridDiv = kotlin.math.ceil((radiusMeters * 2.0) / 5000.0).toInt().coerceIn(1, 5)
        val subBoxes = ArrayList<BoundingBox>(gridDiv * gridDiv)
        val latStep = (bbox.northLat - bbox.southLat) / gridDiv
        val lonStep = (bbox.eastLon - bbox.westLon) / gridDiv
        val pad = 0.0004 // slight overlap so ways crossing tile edges share connecting nodes

        for (r in 0 until gridDiv) {
            for (c in 0 until gridDiv) {
                val s = (bbox.southLat + r * latStep - if (r > 0) pad else 0.0).coerceIn(-85.0, 85.0)
                val n = (bbox.southLat + (r + 1) * latStep + if (r < gridDiv - 1) pad else 0.0).coerceIn(-85.0, 85.0)
                val w = (bbox.westLon + c * lonStep - if (c > 0) pad else 0.0).coerceIn(-180.0, 180.0)
                val e = (bbox.westLon + (c + 1) * lonStep + if (c < gridDiv - 1) pad else 0.0).coerceIn(-180.0, 180.0)
                subBoxes.add(BoundingBox(s, w, n, e))
            }
        }

        val collected = ArrayList<String>(subBoxes.size)
        subBoxes.forEachIndexed { idx, cell ->
            val cellResults = fetchOverpassCellWithQuadFallback(cell, depth = 0, mirrorOffset = idx)
            collected.addAll(cellResults)
            onChunkProgress(idx + 1, subBoxes.size)
        }
        return collected
    }

    private fun fetchOverpassCellWithQuadFallback(
        bbox: BoundingBox,
        depth: Int,
        mirrorOffset: Int
    ): List<String> {
        val endpoints = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        )
        val query = buildOverpassWalkQuery(bbox)
        val encodedBody = "data=${URLEncoder.encode(query, "UTF-8")}"

        var lastError: Throwable? = null
        for (attemptIdx in endpoints.indices) {
            val endpoint = endpoints[(mirrorOffset + attemptIdx) % endpoints.size]
            val result = runCatching {
                executeOverpassPost(endpoint, encodedBody)
            }
            if (result.isSuccess) {
                return listOf(result.getOrThrow())
            }
            lastError = result.exceptionOrNull()
            Thread.sleep(350L * (attemptIdx + 1))
        }

        val latSpan = bbox.northLat - bbox.southLat
        val lonSpan = bbox.eastLon - bbox.westLon
        if (depth < 2 && latSpan > 0.012 && lonSpan > 0.012) {
            val midLat = (bbox.southLat + bbox.northLat) * 0.5
            val midLon = (bbox.westLon + bbox.eastLon) * 0.5
            val pad = 0.0002
            val quads = listOf(
                BoundingBox(bbox.southLat, bbox.westLon, midLat + pad, midLon + pad),
                BoundingBox(bbox.southLat, midLon - pad, midLat + pad, bbox.eastLon),
                BoundingBox(midLat - pad, bbox.westLon, bbox.northLat, midLon + pad),
                BoundingBox(midLat - pad, midLon - pad, bbox.northLat, bbox.eastLon)
            )
            val out = ArrayList<String>(4)
            quads.forEachIndexed { qIdx, qBox ->
                out.addAll(fetchOverpassCellWithQuadFallback(qBox, depth + 1, mirrorOffset + qIdx))
            }
            return out
        }

        throw lastError ?: IllegalStateException("Could not reach Overpass street data server")
    }

    private fun buildOverpassWalkQuery(bbox: BoundingBox): String {
        val bboxStr = String.format(
            Locale.US,
            "%.6f,%.6f,%.6f,%.6f",
            bbox.southLat,
            bbox.westLon,
            bbox.northLat,
            bbox.eastLon
        )
        return """
            [out:json][timeout:45];
            way["highway"~"^(footway|path|pedestrian|living_street|residential|unclassified|tertiary|secondary|primary|service|track|steps|cycleway|bridleway|corridor)$"]
               ["access"!~"^(no|private)$"]
               ["foot"!="no"]($bboxStr);
            (._;>;);
            out skel qt;
        """.trimIndent()
    }

    private fun executeOverpassPost(endpoint: String, encodedBody: String): String {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", "TerraPath-Android/1.0")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(encodedBody)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Overpass HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("{")) {
                throw IllegalStateException("Invalid Overpass response format")
            }
            if (trimmed.contains("\"remark\"") &&
                (trimmed.contains("timed out", ignoreCase = true) ||
                    trimmed.contains("out of memory", ignoreCase = true))
            ) {
                throw IllegalStateException("Overpass query timed out for this cell")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun cacheMapTilesForBbox(
        bbox: BoundingBox,
        centerLat: Double,
        centerLon: Double,
        isDarkTheme: Boolean,
        showStreetNames: Boolean,
        apiKey: String,
        apiProvider: ApiProvider,
        onProgress: (Float) -> Unit
    ): Pair<Int, Long> {
        val spec = resolveMapTileSpec(
            isDarkTheme = isDarkTheme,
            showStreetNames = showStreetNames,
            apiKey = apiKey,
            apiProvider = apiProvider
        )
        val tileSource = spec.tileSource
        val tileWriter = runCatching { SqlTileWriter() }.getOrNull() ?: return 0 to 0L
        val expirationNever = Long.MAX_VALUE

        val tilesToDownload = LinkedHashSet<Long>()
        val maxTilesBudget = 320

        // 1. Overview zooms across entire downloaded bounding box (z12..z14)
        for (zoom in 12..14) {
            val minX = lonToTileX(bbox.westLon, zoom)
            val maxX = lonToTileX(bbox.eastLon, zoom)
            val minY = latToTileY(bbox.northLat, zoom)
            val maxY = latToTileY(bbox.southLat, zoom)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    if (tilesToDownload.size >= maxTilesBudget) break
                    tilesToDownload.add(MapTileIndex.getTileIndex(zoom, x, y))
                }
                if (tilesToDownload.size >= maxTilesBudget) break
            }
            if (tilesToDownload.size >= maxTilesBudget) break
        }

        // 2. Crisp z15 street tiles near the center of the region with remaining budget
        if (tilesToDownload.size < maxTilesBudget) {
            val coreBox = WalkingNavigationPolicy.boundingBoxForRadius(centerLat, centerLon, 2500)
            val zoom = 15
            val minX = lonToTileX(coreBox.westLon, zoom)
            val maxX = lonToTileX(coreBox.eastLon, zoom)
            val minY = latToTileY(coreBox.northLat, zoom)
            val maxY = latToTileY(coreBox.southLat, zoom)
            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    if (tilesToDownload.size >= maxTilesBudget) break
                    tilesToDownload.add(MapTileIndex.getTileIndex(zoom, x, y))
                }
                if (tilesToDownload.size >= maxTilesBudget) break
            }
        }

        if (tilesToDownload.isEmpty()) {
            tileWriter.onDetach()
            return 0 to 0L
        }

        var savedCount = 0
        var savedBytes = 0L
        val tileList = tilesToDownload.toList()

        try {
            tileList.forEachIndexed { idx, tileIndex ->
                runCatching {
                    val bytes: ByteArray? = when (tileSource) {
                        is NoLabelsVectorTileSource -> {
                            val bmp = NoLabelsVectorTileRenderer.renderTileBitmap(
                                z = MapTileIndex.getZoom(tileIndex),
                                x = MapTileIndex.getX(tileIndex),
                                y = MapTileIndex.getY(tileIndex),
                                isDarkTheme = tileSource.isDarkTheme
                            )
                            if (bmp != null) {
                                ByteArrayOutputStream(8192).use { out ->
                                    bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                                    out.toByteArray()
                                }
                            } else null
                        }
                        is OnlineTileSourceBase -> {
                            val url = tileSource.getTileURLString(tileIndex)
                            downloadBytes(url)
                        }
                        else -> null
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        ByteArrayInputStream(bytes).use { stream ->
                            if (tileWriter.saveFile(tileSource, tileIndex, stream, expirationNever)) {
                                savedCount++
                                savedBytes += bytes.size
                            }
                        }
                    }
                }
                if (idx % 4 == 0 || idx == tileList.lastIndex) {
                    onProgress((idx + 1).toFloat() / tileList.size.toFloat())
                }
            }
        } finally {
            runCatching { tileWriter.onDetach() }
        }
        return savedCount to savedBytes
    }

    private fun downloadBytes(urlStr: String): ByteArray? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 8000
            setRequestProperty("User-Agent", "TerraPath-Android/1.0")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val clamped = lat.coerceIn(-85.0511, 85.0511)
        val rad = Math.toRadians(clamped)
        val n = 1 shl zoom
        val y = floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).toInt()
        return y.coerceIn(0, n - 1)
    }
}
