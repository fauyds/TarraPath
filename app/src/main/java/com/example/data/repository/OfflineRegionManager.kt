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
        val clampedRadius = radiusMeters.coerceIn(500, 6000)
        val bbox = WalkingNavigationPolicy.boundingBoxForRadius(centerLat, centerLon, clampedRadius)

        _downloadProgress.value = OfflineDownloadProgress(
            isDownloading = true,
            regionName = cleanName,
            stage = "Fetching walkable streets & footpaths…",
            progressFraction = 0.1f,
            errorMessage = null
        )

        return@withContext try {
            val overpassJson = fetchOverpassWalkJson(bbox)
            _downloadProgress.value = _downloadProgress.value.copy(
                stage = "Building CPU walking graph…",
                progressFraction = 0.42f
            )

            val graph = withContext(Dispatchers.Default) {
                OfflineRoadGraph.parseOverpassWalkGraph(overpassJson)
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
                progressFraction = 0.50f
            )

            val (tileCount, tileBytes) = cacheMapTilesForBbox(
                bbox = bbox,
                isDarkTheme = isDarkTheme,
                showStreetNames = showStreetNames,
                apiKey = apiKey,
                apiProvider = apiProvider
            ) { fraction ->
                _downloadProgress.value = _downloadProgress.value.copy(
                    stage = "Caching offline map tiles…",
                    progressFraction = 0.50f + fraction * 0.46f
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

    private fun fetchOverpassWalkJson(bbox: BoundingBox): String {
        val bboxStr = String.format(
            Locale.US,
            "%.6f,%.6f,%.6f,%.6f",
            bbox.southLat,
            bbox.westLon,
            bbox.northLat,
            bbox.eastLon
        )
        val query = """
            [out:json][timeout:35];
            (
              way["highway"~"^(footway|path|pedestrian|living_street|residential|unclassified|tertiary|secondary|primary|service|track|steps|cycleway|bridleway|corridor)$"]
                 ["access"!~"^(no|private)$"]
                 ["foot"!="no"]($bboxStr);
            );
            (._;>;);
            out body;
        """.trimIndent()
        val encodedBody = "data=${URLEncoder.encode(query, "UTF-8")}"

        val endpoints = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter"
        )
        var lastError: Throwable? = null
        for (endpoint in endpoints) {
            val attempt = runCatching {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 35_000
                    setRequestProperty("User-Agent", "TerraPath-Android/1.0")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                try {
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                        writer.write(encodedBody)
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("Overpass HTTP $code")
                    }
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }
            if (attempt.isSuccess) return attempt.getOrThrow()
            lastError = attempt.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("Could not reach Overpass street data server")
    }

    private fun cacheMapTilesForBbox(
        bbox: BoundingBox,
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

        val tilesToDownload = ArrayList<Long>()
        val maxTilesBudget = 160
        for (zoom in 13..16) {
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

        if (tilesToDownload.isEmpty()) {
            tileWriter.onDetach()
            return 0 to 0L
        }

        var savedCount = 0
        var savedBytes = 0L

        try {
            tilesToDownload.forEachIndexed { idx, tileIndex ->
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
                if (idx % 4 == 0 || idx == tilesToDownload.lastIndex) {
                    onProgress((idx + 1).toFloat() / tilesToDownload.size.toFloat())
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
