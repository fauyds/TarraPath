package com.example.domain

import android.util.LruCache
import com.example.data.model.LatLonPoint
import com.example.data.repository.OfflineRegionManager
import com.example.ui.map.DecodedMvtTile
import com.example.ui.map.MvtGeomType
import com.example.ui.map.NoLabelsVectorTileRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

data class RoadSegment(
    val roadId: String,
    val roadClass: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double
)

data class RoadSnapResult(
    val latitude: Double,
    val longitude: Double,
    val snappedToRoad: Boolean,
    val snapDistanceMeters: Double,
    val matchedRoadId: String? = null,
    val segmentBearingDegrees: Float? = null
)

/**
 * Lightweight map-matcher that aligns noisy urban GPS fixes and recorded walking paths onto
 * nearby mapped streets/footpaths extracted directly from zoom-14 vector map tiles (with an
 * offline fallback to downloaded CPU walking graphs). Off-road tracks further than ~42m from
 * any mapped street/path remain untouched.
 */
open class RoadSnapper(
    private val offlineRegionManager: OfflineRegionManager? = null
) {
    private val segmentCache = LruCache<String, List<RoadSegment>>(48)
    private val failedTileUntilMs = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastMatchedRoadId: String? = null

    fun resetContinuity() {
        lastMatchedRoadId = null
    }

    open suspend fun snapPoint(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        headingDegrees: Float?,
        speedKmh: Float
    ): RoadSnapResult {
        val segments = loadNearbyRoadSegments(latitude, longitude, fetchIfMissing = true)
        if (segments.isNotEmpty()) {
            val res = snapPointToSegments(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracyMeters,
                headingDegrees = headingDegrees,
                speedKmh = speedKmh,
                segments = segments,
                lastMatchedRoadId = lastMatchedRoadId
            )
            if (res.snappedToRoad) {
                lastMatchedRoadId = res.matchedRoadId
                return res
            }
        }

        // Offline fallback using downloaded CSR pedestrian road graph if vector tile is unavailable
        val fallbackRegion = offlineRegionManager
        if (fallbackRegion != null) {
            val maxSnap = computeMaxSnapMeters(accuracyMeters)
            val offlineSnap = fallbackRegion.snapToOfflineRoad(latitude, longitude, maxSnap)
            if (offlineSnap != null) {
                val dist = GeoGrid.haversineMeters(
                    latitude,
                    longitude,
                    offlineSnap.latitude,
                    offlineSnap.longitude
                )
                return RoadSnapResult(
                    latitude = offlineSnap.latitude,
                    longitude = offlineSnap.longitude,
                    snappedToRoad = true,
                    snapDistanceMeters = dist,
                    matchedRoadId = "offline_edge"
                )
            }
        }

        lastMatchedRoadId = null
        return RoadSnapResult(
            latitude = latitude,
            longitude = longitude,
            snappedToRoad = false,
            snapDistanceMeters = 0.0,
            matchedRoadId = null
        )
    }

    /**
     * Aligns an entire saved polyline (e.g., an existing track affected by multipath GPS offset)
     * onto mapped roads/footpaths while preserving points that are genuinely off-road.
     */
    open suspend fun snapPolyline(
        points: List<LatLonPoint>,
        maxSnapMeters: Double = 36.0
    ): List<LatLonPoint> {
        if (points.isEmpty()) return emptyList()
        var currentRoadId: String? = null
        val out = ArrayList<LatLonPoint>(points.size)

        for (i in points.indices) {
            val pt = points[i]
            val bearingHint = computeLocalBearingHint(points, i)
            val segments = loadNearbyRoadSegments(pt.latitude, pt.longitude, fetchIfMissing = true)
            var matched: RoadSnapResult? = null
            if (segments.isNotEmpty()) {
                val cand = snapPointToSegments(
                    latitude = pt.latitude,
                    longitude = pt.longitude,
                    accuracyMeters = 18f,
                    headingDegrees = bearingHint,
                    speedKmh = if (bearingHint != null) 4.5f else 0f,
                    segments = segments,
                    lastMatchedRoadId = currentRoadId,
                    maxSnapMetersOverride = maxSnapMeters
                )
                if (cand.snappedToRoad) {
                    matched = cand
                    currentRoadId = cand.matchedRoadId
                }
            }

            if (matched != null) {
                out.add(pt.copy(latitude = matched.latitude, longitude = matched.longitude))
            } else {
                val offlinePt = offlineRegionManager?.snapToOfflineRoad(
                    pt.latitude,
                    pt.longitude,
                    maxSnapMeters
                )
                if (offlinePt != null) {
                    out.add(pt.copy(latitude = offlinePt.latitude, longitude = offlinePt.longitude))
                } else {
                    currentRoadId = null
                    out.add(pt)
                }
            }
        }
        return out
    }

    private suspend fun loadNearbyRoadSegments(
        lat: Double,
        lon: Double,
        fetchIfMissing: Boolean
    ): List<RoadSegment> {
        val searchBox = WalkingNavigationPolicy.boundingBoxForRadius(lat, lon, SEARCH_RADIUS_METERS)
        val minTx = lonToTileX(searchBox.westLon, SNAP_TILE_ZOOM)
        val maxTx = lonToTileX(searchBox.eastLon, SNAP_TILE_ZOOM)
        val minTy = latToTileY(searchBox.northLat, SNAP_TILE_ZOOM)
        val maxTy = latToTileY(searchBox.southLat, SNAP_TILE_ZOOM)

        val result = ArrayList<RoadSegment>(128)
        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                val tileSegs = getOrDecodeTileSegments(SNAP_TILE_ZOOM, tx, ty, fetchIfMissing)
                if (tileSegs.isNotEmpty()) {
                    result.addAll(tileSegs)
                }
            }
        }
        return result
    }

    private suspend fun getOrDecodeTileSegments(
        z: Int,
        x: Int,
        y: Int,
        fetchIfMissing: Boolean
    ): List<RoadSegment> {
        val key = "$z/$x/$y"
        synchronized(segmentCache) {
            segmentCache.get(key)?.let { return it }
        }

        val cachedDecoded = NoLabelsVectorTileRenderer.getCachedDecodedTile(z, x, y)
        if (cachedDecoded != null) {
            val extracted = extractRoadSegmentsFromMvtTile(z, x, y, cachedDecoded)
            synchronized(segmentCache) {
                segmentCache.put(key, extracted)
            }
            return extracted
        }

        if (!fetchIfMissing) return emptyList()

        val now = System.currentTimeMillis()
        val retryAfter = failedTileUntilMs[key] ?: 0L
        if (now < retryAfter) return emptyList()

        val fetchedDecoded = withContext(Dispatchers.IO) {
            NoLabelsVectorTileRenderer.getOrFetchDecodedTile(z, x, y)
        }
        if (fetchedDecoded == null) {
            failedTileUntilMs[key] = now + FAILED_TILE_COOLDOWN_MS
            return emptyList()
        }
        failedTileUntilMs.remove(key)

        val extracted = extractRoadSegmentsFromMvtTile(z, x, y, fetchedDecoded)
        synchronized(segmentCache) {
            segmentCache.put(key, extracted)
        }
        return extracted
    }

    companion object {
        private const val SNAP_TILE_ZOOM = 14
        private const val SEARCH_RADIUS_METERS = 65
        private const val FAILED_TILE_COOLDOWN_MS = 25_000L

        private val EXCLUDED_CLASSES = setOf(
            "motorway",
            "motorway_link",
            "rail",
            "transit",
            "ferry",
            "busway",
            "raceway"
        )

        fun computeMaxSnapMeters(accuracyMeters: Float): Double {
            val acc = if (accuracyMeters.isNaN() || accuracyMeters <= 0f) 16.0 else accuracyMeters.toDouble()
            return maxOf(24.0, minOf(42.0, acc * 1.65))
        }

        fun snapPointToSegments(
            latitude: Double,
            longitude: Double,
            accuracyMeters: Float,
            headingDegrees: Float?,
            speedKmh: Float,
            segments: List<RoadSegment>,
            lastMatchedRoadId: String? = null,
            maxSnapMetersOverride: Double? = null
        ): RoadSnapResult {
            if (segments.isEmpty()) {
                return RoadSnapResult(latitude, longitude, snappedToRoad = false, snapDistanceMeters = 0.0)
            }

            val maxSnapMeters = maxSnapMetersOverride ?: computeMaxSnapMeters(accuracyMeters)
            val cosLat = cos(Math.toRadians(latitude)).coerceAtLeast(0.1)
            val metersPerDegLat = 111_320.0
            val metersPerDegLon = 111_320.0 * cosLat

            val useHeading = headingDegrees != null && !headingDegrees.isNaN() && speedKmh >= 1.8f

            var bestScore = Double.POSITIVE_INFINITY
            var bestPerpDist = Double.POSITIVE_INFINITY
            var bestSnapLat = latitude
            var bestSnapLon = longitude
            var bestRoadId: String? = null
            var bestBearing: Float? = null

            for (seg in segments) {
                if (seg.roadClass in EXCLUDED_CLASSES) continue

                val ax = (seg.startLon - longitude) * metersPerDegLon
                val ay = (seg.startLat - latitude) * metersPerDegLat
                val bx = (seg.endLon - longitude) * metersPerDegLon
                val by = (seg.endLat - latitude) * metersPerDegLat

                // Fast bounding reject
                if (minOf(ax, bx) > maxSnapMeters || maxOf(ax, bx) < -maxSnapMeters) continue
                if (minOf(ay, by) > maxSnapMeters || maxOf(ay, by) < -maxSnapMeters) continue

                val abx = bx - ax
                val aby = by - ay
                val abLenSq = abx * abx + aby * aby
                val t = if (abLenSq <= 1e-6) {
                    0.0
                } else {
                    (-(ax * abx + ay * aby) / abLenSq).coerceIn(0.0, 1.0)
                }
                val px = ax + t * abx
                val py = ay + t * aby
                val perpDist = sqrt(px * px + py * py)
                if (perpDist > maxSnapMeters) continue

                val segBearingDeg = if (abLenSq > 0.25) {
                    (((Math.toDegrees(atan2(abx, aby)) % 360.0) + 360.0) % 360.0).toFloat()
                } else {
                    null
                }

                var score = perpDist

                // Heading alignment penalty at intersections (bidirectional road axis comparison)
                if (useHeading && segBearingDeg != null) {
                    val hDeg = headingDegrees!!
                    val rawDiff = kotlin.math.abs(((segBearingDeg - hDeg + 540f) % 360f) - 180f)
                    val axisDiffDeg = minOf(rawDiff, 180f - rawDiff)
                    score += (axisDiffDeg / 90.0) * 18.0
                }

                // Continuity hysteresis keeps movement steady on current road across cross streets
                if (lastMatchedRoadId != null && seg.roadId == lastMatchedRoadId) {
                    score -= 5.0
                }

                // Slight preference for streets/footpaths over service alleys/trunks on ties
                if (seg.roadClass == "trunk" || seg.roadClass == "service") {
                    score += 2.5
                }

                if (score < bestScore - 1e-4 ||
                    (kotlin.math.abs(score - bestScore) <= 1e-4 && perpDist < bestPerpDist)
                ) {
                    bestScore = score
                    bestPerpDist = perpDist
                    bestSnapLat = seg.startLat + t * (seg.endLat - seg.startLat)
                    bestSnapLon = seg.startLon + t * (seg.endLon - seg.startLon)
                    bestRoadId = seg.roadId
                    bestBearing = segBearingDeg
                }
            }

            return if (bestRoadId != null) {
                RoadSnapResult(
                    latitude = bestSnapLat,
                    longitude = bestSnapLon,
                    snappedToRoad = true,
                    snapDistanceMeters = bestPerpDist,
                    matchedRoadId = bestRoadId,
                    segmentBearingDegrees = bestBearing
                )
            } else {
                RoadSnapResult(
                    latitude = latitude,
                    longitude = longitude,
                    snappedToRoad = false,
                    snapDistanceMeters = 0.0
                )
            }
        }

        internal fun extractRoadSegmentsFromMvtTile(
            z: Int,
            x: Int,
            y: Int,
            tile: DecodedMvtTile
        ): List<RoadSegment> {
            val layer = tile.layersByName["transportation"] ?: return emptyList()
            val extent = layer.extent.coerceAtLeast(1).toDouble()
            val n = (1 shl z).toDouble()
            val out = ArrayList<RoadSegment>(layer.features.size * 3)

            layer.features.forEachIndexed { featIdx, feat ->
                if (feat.type != MvtGeomType.LINESTRING) return@forEachIndexed
                val cls = feat.featureClass.orEmpty().lowercase()
                if (cls in EXCLUDED_CLASSES) return@forEachIndexed

                val roadId = "$z/$x/$y#$featIdx:$cls"
                val cmds = feat.commands
                var idx = 0
                var cx = 0
                var cy = 0
                var subStartLat = 0.0
                var subStartLon = 0.0
                var prevLat = 0.0
                var prevLon = 0.0
                var hasPrev = false

                fun toLatLon(tileCoordX: Int, tileCoordY: Int): Pair<Double, Double> {
                    val fx = x.toDouble() + tileCoordX.toDouble() / extent
                    val fy = y.toDouble() + tileCoordY.toDouble() / extent
                    val lon = fx / n * 360.0 - 180.0
                    val latRad = atan(sinh(PI * (1.0 - 2.0 * fy / n)))
                    return Math.toDegrees(latRad) to lon
                }

                while (idx < cmds.size) {
                    val cmdInt = cmds[idx++]
                    val cmd = cmdInt and 0x7
                    val count = cmdInt ushr 3
                    when (cmd) {
                        1 -> { // MoveTo
                            for (i in 0 until count) {
                                if (idx + 1 >= cmds.size) break
                                cx += zigZagDecode(cmds[idx++])
                                cy += zigZagDecode(cmds[idx++])
                                val (curLat, curLon) = toLatLon(cx, cy)
                                if (i == 0) {
                                    subStartLat = curLat
                                    subStartLon = curLon
                                    prevLat = curLat
                                    prevLon = curLon
                                    hasPrev = true
                                } else {
                                    if (hasPrev && (prevLat != curLat || prevLon != curLon)) {
                                        out.add(RoadSegment(roadId, cls, prevLat, prevLon, curLat, curLon))
                                    }
                                    prevLat = curLat
                                    prevLon = curLon
                                    hasPrev = true
                                }
                            }
                        }
                        2 -> { // LineTo
                            for (i in 0 until count) {
                                if (idx + 1 >= cmds.size) break
                                cx += zigZagDecode(cmds[idx++])
                                cy += zigZagDecode(cmds[idx++])
                                val (curLat, curLon) = toLatLon(cx, cy)
                                if (hasPrev && (prevLat != curLat || prevLon != curLon)) {
                                    out.add(RoadSegment(roadId, cls, prevLat, prevLon, curLat, curLon))
                                }
                                prevLat = curLat
                                prevLon = curLon
                                hasPrev = true
                            }
                        }
                        7 -> { // ClosePath
                            if (hasPrev && (prevLat != subStartLat || prevLon != subStartLon)) {
                                out.add(RoadSegment(roadId, cls, prevLat, prevLon, subStartLat, subStartLon))
                                prevLat = subStartLat
                                prevLon = subStartLon
                            }
                        }
                        else -> break
                    }
                }
            }
            return out
        }

        private fun computeLocalBearingHint(points: List<LatLonPoint>, index: Int): Float? {
            if (points.size < 2) return null
            val prev = points[(index - 1).coerceAtLeast(0)]
            val next = points[(index + 1).coerceAtMost(points.lastIndex)]
            val dist = GeoGrid.haversineMeters(prev.latitude, prev.longitude, next.latitude, next.longitude)
            if (dist < 2.5) return null
            val cosLat = cos(Math.toRadians(prev.latitude)).coerceAtLeast(0.1)
            val dx = (next.longitude - prev.longitude) * 111_320.0 * cosLat
            val dy = (next.latitude - prev.latitude) * 111_320.0
            if (hypot(dx, dy) < 1.0) return null
            return (((Math.toDegrees(atan2(dx, dy)) % 360.0) + 360.0) % 360.0).toFloat()
        }

        private fun zigZagDecode(n: Int): Int = (n ushr 1) xor -(n and 1)

        fun lonToTileX(lon: Double, zoom: Int): Int {
            val n = 1 shl zoom
            return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        }

        fun latToTileY(lat: Double, zoom: Int): Int {
            val clamped = lat.coerceIn(-85.0511, 85.0511)
            val rad = Math.toRadians(clamped)
            val n = 1 shl zoom
            val y = floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * n).toInt()
            return y.coerceIn(0, n - 1)
        }
    }
}
