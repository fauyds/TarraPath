package com.example.data.remote

import com.example.data.model.ApiProvider
import com.example.data.model.LatLonPoint
import com.example.domain.OfflineRoadGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class OnlineWalkingRouteOutcome(
    val points: List<LatLonPoint>,
    val distanceMeters: Double,
    val estimatedSeconds: Long,
    val providerLabel: String
)

/**
 * Calculates walking-only routes (footpaths, pedestrian ways, walkable streets) using online
 * OpenStreetMap routing servers (OSRM Routed-Foot, OSM Valhalla Pedestrian, and optional Geoapify walk).
 */
class WalkingRouteService {

    suspend fun calculateOnlineWalkingRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        apiKey: String = "",
        apiProvider: ApiProvider = ApiProvider.CUSTOM_GENERAL
    ): Result<OnlineWalkingRouteOutcome> = withContext(Dispatchers.IO) {
        val cleanKey = LocationSearchService.sanitizeApiKey(apiKey)

        // 1. Optional Geoapify Walking API if user configured a Geoapify key
        if (cleanKey.isNotEmpty() && apiProvider == ApiProvider.GEOAPIFY) {
            val geoapifyAttempt = runCatching {
                queryGeoapifyWalk(startLat, startLon, endLat, endLon, cleanKey)
            }
            if (geoapifyAttempt.isSuccess) {
                return@withContext geoapifyAttempt
            }
        }

        // 2. Primary free OpenStreetMap pedestrian router (FOSSGIS routed-foot)
        val osrmAttempt = runCatching {
            queryOsrmRoutedFoot(startLat, startLon, endLat, endLon)
        }
        if (osrmAttempt.isSuccess) {
            return@withContext osrmAttempt
        }

        // 3. Secondary free OpenStreetMap pedestrian router (Valhalla pedestrian)
        val valhallaAttempt = runCatching {
            queryValhallaPedestrian(startLat, startLon, endLat, endLon)
        }
        if (valhallaAttempt.isSuccess) {
            return@withContext valhallaAttempt
        }

        val err = osrmAttempt.exceptionOrNull() ?: valhallaAttempt.exceptionOrNull()
        Result.failure(err ?: IllegalStateException("Online walking route server unavailable"))
    }

    private fun queryOsrmRoutedFoot(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): OnlineWalkingRouteOutcome {
        val endpoint = String.format(
            Locale.US,
            "https://routing.openstreetmap.de/routed-foot/route/v1/foot/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=false",
            startLon,
            startLat,
            endLon,
            endLat
        )
        val body = httpGet(endpoint)
        return parseOsrmFootJson(body)
    }

    private fun queryValhallaPedestrian(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): OnlineWalkingRouteOutcome {
        val payload = JSONObject().apply {
            put(
                "locations",
                JSONArray().apply {
                    put(JSONObject().put("lat", startLat).put("lon", startLon))
                    put(JSONObject().put("lat", endLat).put("lon", endLon))
                }
            )
            put("costing", "pedestrian")
            put("directions_options", JSONObject().put("units", "kilometers"))
        }.toString()
        val body = httpPostJson("https://valhalla1.openstreetmap.de/route", payload)
        return parseValhallaJson(body)
    }

    private fun queryGeoapifyWalk(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        apiKey: String
    ): OnlineWalkingRouteOutcome {
        val endpoint = String.format(
            Locale.US,
            "https://api.geoapify.com/v1/routing?waypoints=%.6f,%.6f|%.6f,%.6f&mode=walk&apiKey=%s",
            startLat,
            startLon,
            endLat,
            endLon,
            apiKey
        )
        val body = httpGet(endpoint)
        return parseGeoapifyRoutingJson(body)
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 9000
            readTimeout = 10000
            setRequestProperty("User-Agent", "TerraPath-Android/1.0")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Routing server HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostJson(urlStr: String, jsonBody: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 9000
            readTimeout = 10000
            setRequestProperty("User-Agent", "TerraPath-Android/1.0")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Routing server HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun parseOsrmFootJson(jsonStr: String): OnlineWalkingRouteOutcome {
            val root = JSONObject(jsonStr)
            val code = root.optString("code")
            if (code.isNotEmpty() && !code.equals("Ok", ignoreCase = true)) {
                throw IllegalStateException("OSRM route error: $code")
            }
            val routes = root.optJSONArray("routes")
                ?: throw IllegalStateException("No walking route returned")
            if (routes.length() == 0) {
                throw IllegalStateException("No walking route found")
            }
            val first = routes.getJSONObject(0)
            val distance = first.optDouble("distance", 0.0)
            val duration = first.optDouble("duration", 0.0).toLong().coerceAtLeast(1L)
            val coords = first.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = ArrayList<LatLonPoint>(coords.length())
            for (i in 0 until coords.length()) {
                val pair = coords.getJSONArray(i)
                val lon = pair.getDouble(0)
                val lat = pair.getDouble(1)
                pts.add(LatLonPoint(latitude = lat, longitude = lon))
            }
            if (pts.size < 2) throw IllegalStateException("Walking route geometry too short")
            val resolvedDist = if (distance > 0.0) distance else OfflineRoadGraph.polylineDistanceMeters(pts)
            return OnlineWalkingRouteOutcome(
                points = pts,
                distanceMeters = resolvedDist,
                estimatedSeconds = duration,
                providerLabel = "OSM Foot (Online)"
            )
        }

        fun parseValhallaJson(jsonStr: String): OnlineWalkingRouteOutcome {
            val root = JSONObject(jsonStr)
            val trip = root.getJSONObject("trip")
            val legs = trip.getJSONArray("legs")
            val pts = ArrayList<LatLonPoint>()
            for (i in 0 until legs.length()) {
                val shape = legs.getJSONObject(i).getString("shape")
                val decoded = decodePolyline6(shape)
                if (pts.isEmpty()) pts.addAll(decoded)
                else pts.addAll(decoded.drop(1))
            }
            if (pts.size < 2) throw IllegalStateException("Valhalla walking route too short")
            val summary = trip.optJSONObject("summary")
            val distKm = summary?.optDouble("length", 0.0) ?: 0.0
            val distMeters = if (distKm > 0.0) distKm * 1000.0 else OfflineRoadGraph.polylineDistanceMeters(pts)
            val timeSec = summary?.optDouble("time", 0.0)?.toLong()?.coerceAtLeast(1L)
                ?: (distMeters / OfflineRoadGraph.WALKING_SPEED_MPS).toLong().coerceAtLeast(1L)
            return OnlineWalkingRouteOutcome(
                points = pts,
                distanceMeters = distMeters,
                estimatedSeconds = timeSec,
                providerLabel = "OSM Valhalla Walk (Online)"
            )
        }

        fun parseGeoapifyRoutingJson(jsonStr: String): OnlineWalkingRouteOutcome {
            val root = JSONObject(jsonStr)
            val features = root.getJSONArray("features")
            if (features.length() == 0) throw IllegalStateException("No Geoapify walking route")
            val feature = features.getJSONObject(0)
            val props = feature.optJSONObject("properties")
            val distance = props?.optDouble("distance", 0.0) ?: 0.0
            val timeSec = props?.optDouble("time", 0.0)?.toLong()?.coerceAtLeast(1L) ?: 1L
            val geom = feature.getJSONObject("geometry")
            val type = geom.optString("type")
            val coords = geom.getJSONArray("coordinates")
            val pts = ArrayList<LatLonPoint>()
            if (type.equals("MultiLineString", ignoreCase = true)) {
                for (l in 0 until coords.length()) {
                    val line = coords.getJSONArray(l)
                    for (p in 0 until line.length()) {
                        val pair = line.getJSONArray(p)
                        pts.add(LatLonPoint(pair.getDouble(1), pair.getDouble(0)))
                    }
                }
            } else {
                for (p in 0 until coords.length()) {
                    val pair = coords.getJSONArray(p)
                    pts.add(LatLonPoint(pair.getDouble(1), pair.getDouble(0)))
                }
            }
            if (pts.size < 2) throw IllegalStateException("Geoapify walking route too short")
            val resolvedDist = if (distance > 0.0) distance else OfflineRoadGraph.polylineDistanceMeters(pts)
            return OnlineWalkingRouteOutcome(
                points = pts,
                distanceMeters = resolvedDist,
                estimatedSeconds = timeSec,
                providerLabel = "Geoapify Walk (Online)"
            )
        }

        /**
         * Decodes Google/Valhalla encoded polyline with 1e-6 precision.
         */
        fun decodePolyline6(encoded: String): List<LatLonPoint> {
            val poly = ArrayList<LatLonPoint>()
            var index = 0
            val len = encoded.length
            var lat = 0
            var lon = 0

            while (index < len) {
                var b: Int
                var shift = 0
                var result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index < len)
                val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lat += dlat

                shift = 0
                result = 0
                while (index < len) {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val dlon = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lon += dlon

                poly.add(LatLonPoint(latitude = lat / 1e6, longitude = lon / 1e6))
            }
            return poly
        }
    }
}
