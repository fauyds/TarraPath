package com.example.data.remote

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.example.data.local.MapMarkerEntity
import com.example.data.model.ApiProvider
import com.example.data.model.MarkerCategory
import com.example.data.model.SearchPlaceResult
import com.example.data.model.SearchResultSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class ApiKeyRejectedException(
    val provider: ApiProvider,
    val httpCode: Int,
    message: String
) : IllegalStateException(message)

data class RemoteSearchOutcome(
    val results: List<SearchPlaceResult>,
    val providerLabel: String
)

class LocationSearchService(
    private val context: Context? = null
) {

    suspend fun searchRemotePlaces(
        query: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        apiKey: String = "",
        apiProvider: ApiProvider = ApiProvider.CUSTOM_GENERAL
    ): Result<List<SearchPlaceResult>> {
        return searchRemotePlacesWithProvider(
            query = query,
            biasLat = biasLat,
            biasLon = biasLon,
            apiKey = apiKey,
            apiProvider = apiProvider
        ).map { it.results }
    }

    suspend fun searchRemotePlacesWithProvider(
        query: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        apiKey: String = "",
        apiProvider: ApiProvider = ApiProvider.CUSTOM_GENERAL
    ): Result<RemoteSearchOutcome> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            return@withContext Result.success(RemoteSearchOutcome(emptyList(), "OpenStreetMap"))
        }

        val cleanKey = sanitizeApiKey(apiKey)
        if (cleanKey.isNotEmpty() && apiProvider == ApiProvider.GEOAPIFY) {
            val geoapifyResult = runCatching {
                queryGeoapify(trimmed, cleanKey, biasLat, biasLon)
            }
            if (geoapifyResult.isSuccess) {
                return@withContext Result.success(
                    RemoteSearchOutcome(geoapifyResult.getOrThrow(), "Geoapify (API Key)")
                )
            }
            val err = geoapifyResult.exceptionOrNull()
            if (err is ApiKeyRejectedException) {
                return@withContext Result.failure(err)
            }
        } else if (cleanKey.isNotEmpty() && apiProvider == ApiProvider.LOCATION_IQ) {
            val locationIqResult = runCatching {
                queryLocationIq(trimmed, cleanKey, biasLat, biasLon)
            }
            if (locationIqResult.isSuccess) {
                return@withContext Result.success(
                    RemoteSearchOutcome(locationIqResult.getOrThrow(), "LocationIQ (API Key)")
                )
            }
            val err = locationIqResult.exceptionOrNull()
            if (err is ApiKeyRejectedException) {
                return@withContext Result.failure(err)
            }
        }

        val nominatimResult = runCatching {
            queryNominatim(trimmed, biasLat, biasLon)
        }
        if (nominatimResult.isSuccess) {
            return@withContext Result.success(
                RemoteSearchOutcome(nominatimResult.getOrThrow(), "OpenStreetMap")
            )
        }

        // Fallback to platform Geocoder if available on device
        val fallback = runCatching {
            queryAndroidGeocoder(trimmed)
        }.getOrNull()
        if (!fallback.isNullOrEmpty()) {
            return@withContext Result.success(
                RemoteSearchOutcome(fallback, "Device Geocoder")
            )
        }

        val err = nominatimResult.exceptionOrNull()
        Result.failure(err ?: IllegalStateException("Location search unavailable"))
    }

    suspend fun verifyApiKey(
        apiKey: String,
        provider: ApiProvider
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = sanitizeApiKey(apiKey)
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Enter an API key first."))
        }
        when (provider) {
            ApiProvider.CUSTOM_GENERAL -> {
                Result.success("Key saved locally. Select Geoapify or LocationIQ to authenticate search requests.")
            }
            ApiProvider.GEOAPIFY -> runCatching {
                queryGeoapify("London", cleanKey, null, null)
                "Verified! Geoapify API key is active."
            }
            ApiProvider.LOCATION_IQ -> runCatching {
                queryLocationIq("London", cleanKey, null, null)
                "Verified! LocationIQ token is active."
            }
        }
    }

    private fun queryNominatim(
        query: String,
        biasLat: Double?,
        biasLon: Double?
    ): List<SearchPlaceResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val viewBoxParam = if (biasLat != null && biasLon != null) {
            val left = biasLon - 0.35
            val top = biasLat + 0.35
            val right = biasLon + 0.35
            val bottom = biasLat - 0.35
            "&viewbox=$left,$top,$right,$bottom"
        } else {
            ""
        }

        val endpoint = "https://nominatim.openstreetmap.org/search" +
            "?format=jsonv2&q=$encoded&limit=8&addressdetails=1$viewBoxParam"

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            val pkg = context?.packageName ?: "com.aistudio.terrapath"
            setRequestProperty("User-Agent", "TerraPath/1.0 ($pkg)")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
        }

        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Search service returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseNominatimJson(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun queryLocationIq(
        query: String,
        token: String,
        biasLat: Double?,
        biasLon: Double?
    ): List<SearchPlaceResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val encodedKey = URLEncoder.encode(token, "UTF-8")
        val viewBoxParam = if (biasLat != null && biasLon != null) {
            "&viewbox=${biasLon - 0.35},${biasLat + 0.35},${biasLon + 0.35},${biasLat - 0.35}"
        } else {
            ""
        }
        val endpoint = "https://us1.locationiq.com/v1/search" +
            "?key=$encodedKey&q=$encoded&format=json&limit=8&addressdetails=1$viewBoxParam"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                val detail = extractErrorDetail(conn)
                val suffix = if (detail != null) ": $detail" else ""
                throw ApiKeyRejectedException(
                    ApiProvider.LOCATION_IQ,
                    code,
                    "LocationIQ rejected this token (HTTP $code$suffix). Confirm it is a valid LocationIQ token with no referrer restrictions."
                )
            }
            if (code == 404) {
                // LocationIQ returns 404 {"error":"Unable to geocode"} when no places match
                return emptyList()
            }
            if (code !in 200..299) {
                val detail = extractErrorDetail(conn)
                val suffix = if (detail != null) ": $detail" else ""
                throw IllegalStateException("LocationIQ returned HTTP $code$suffix")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseNominatimJson(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun queryGeoapify(
        query: String,
        apiKey: String,
        biasLat: Double?,
        biasLon: Double?
    ): List<SearchPlaceResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val biasParam = if (biasLat != null && biasLon != null) {
            "&bias=proximity:$biasLon,$biasLat"
        } else {
            ""
        }
        val endpoint = "https://api.geoapify.com/v1/geocode/autocomplete" +
            "?text=$encoded&limit=8&apiKey=$encodedKey$biasParam"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code == 401 || code == 403) {
                val detail = extractErrorDetail(conn)
                val suffix = if (detail != null) ": $detail" else ""
                throw ApiKeyRejectedException(
                    ApiProvider.GEOAPIFY,
                    code,
                    "Geoapify rejected this API key (HTTP $code$suffix). Confirm it is an active Geoapify key with no HTTP referrer restriction."
                )
            }
            if (code !in 200..299) {
                val detail = extractErrorDetail(conn)
                val suffix = if (detail != null) ": $detail" else ""
                throw IllegalStateException("Geoapify returned HTTP $code$suffix")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parseGeoapifyJson(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractErrorDetail(conn: HttpURLConnection): String? {
        val raw = runCatching {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return parseErrorDetailFromBody(raw)
    }

    @Suppress("DEPRECATION")
    private fun queryAndroidGeocoder(query: String): List<SearchPlaceResult> {
        val appContext = context ?: return emptyList()
        if (!Geocoder.isPresent() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return emptyList()
        }
        val geocoder = Geocoder(appContext, Locale.getDefault())
        val addresses = geocoder.getFromLocationName(query, 6) ?: return emptyList()
        return addresses.mapIndexedNotNull { idx, addr ->
            val title = addr.featureName?.takeIf { it.isNotBlank() }
                ?: addr.locality
                ?: addr.subAdminArea
                ?: query
            val subtitleParts = listOfNotNull(
                addr.thoroughfare,
                addr.locality,
                addr.adminArea,
                addr.countryName
            ).distinct().filter { it != title }
            SearchPlaceResult(
                id = "geo_${idx}_${addr.latitude}_${addr.longitude}",
                title = title,
                subtitle = subtitleParts.joinToString(", ").ifBlank {
                    String.format(Locale.US, "%.4f, %.4f", addr.latitude, addr.longitude)
                },
                latitude = addr.latitude,
                longitude = addr.longitude,
                categoryLabel = "Place",
                source = SearchResultSource.OPENSTREETMAP,
                markerCategory = MarkerCategory.LANDMARK
            )
        }
    }

    companion object {
        private val COORD_REGEX = Regex(
            """^\s*([-+]?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*([-+]?\d{1,3}(?:\.\d+)?)\s*$"""
        )

        fun sanitizeApiKey(rawKey: String): String {
            return rawKey
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .replace(Regex("""\s+"""), "")
        }

        fun parseErrorDetailFromBody(rawBody: String): String? {
            val trimmed = rawBody.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith("{")) {
                val obj = runCatching { JSONObject(trimmed) }.getOrNull()
                if (obj != null) {
                    val msg = obj.optString("message", "").trim()
                    if (msg.isNotEmpty()) return msg
                    val err = obj.optString("error", "").trim()
                    if (err.isNotEmpty()) return err
                }
            }
            return trimmed.take(120)
        }

        fun parseCoordinateQuery(rawQuery: String): SearchPlaceResult? {
            val match = COORD_REGEX.matchEntire(rawQuery) ?: return null
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lon = match.groupValues[2].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val formatted = String.format(Locale.US, "%.5f, %.5f", lat, lon)
            return SearchPlaceResult(
                id = "coord_$formatted",
                title = "Coordinates ($formatted)",
                subtitle = "Jump directly to GPS latitude & longitude",
                latitude = lat,
                longitude = lon,
                categoryLabel = "Coordinates",
                source = SearchResultSource.COORDINATE,
                markerCategory = MarkerCategory.LANDMARK
            )
        }

        fun searchLocalMarkers(
            rawQuery: String,
            markers: List<MapMarkerEntity>
        ): List<SearchPlaceResult> {
            val needle = rawQuery.trim().lowercase(Locale.getDefault())
            if (needle.isEmpty()) return emptyList()
            return markers.filter { marker ->
                marker.title.lowercase(Locale.getDefault()).contains(needle) ||
                    marker.note.lowercase(Locale.getDefault()).contains(needle) ||
                    marker.categoryName.lowercase(Locale.getDefault()).contains(needle)
            }.take(4).map { marker ->
                val cat = MarkerCategory.entries.firstOrNull { it.name == marker.categoryName }
                    ?: MarkerCategory.LANDMARK
                SearchPlaceResult(
                    id = "pin_${marker.id}",
                    title = marker.title,
                    subtitle = marker.note.ifBlank {
                        String.format(Locale.US, "Saved %s pin • %.4f, %.4f", cat.label, marker.latitude, marker.longitude)
                    },
                    latitude = marker.latitude,
                    longitude = marker.longitude,
                    categoryLabel = "Saved ${cat.label}",
                    source = SearchResultSource.SAVED_PIN,
                    markerCategory = cat
                )
            }
        }

        fun parseNominatimJson(json: String): List<SearchPlaceResult> {
            val trimmed = json.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("[")) return emptyList()
            val array = JSONArray(trimmed)
            val results = ArrayList<SearchPlaceResult>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val lat = obj.optString("lat").toDoubleOrNull() ?: continue
                val lon = obj.optString("lon").toDoubleOrNull() ?: continue
                val displayName = obj.optString("display_name", "").trim()
                val rawName = obj.optString("name", "").trim()
                val placeId = obj.optLong("place_id", i.toLong())
                val osmCategory = obj.optString("category", obj.optString("class", "")).lowercase(Locale.US)
                val osmType = obj.optString("type", "").lowercase(Locale.US)

                val parts = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val title = rawName.ifBlank { parts.firstOrNull() ?: "Location" }
                val subtitle = if (parts.size > 1) {
                    parts.drop(if (parts.first().equals(title, ignoreCase = true)) 1 else 0)
                        .take(4)
                        .joinToString(", ")
                } else {
                    String.format(Locale.US, "%.4f, %.4f", lat, lon)
                }

                val inferredCat = inferMarkerCategory(osmCategory, osmType)
                val readableType = osmType.replace('_', ' ')
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                    .ifBlank { inferredCat.label }

                results.add(
                    SearchPlaceResult(
                        id = "osm_$placeId",
                        title = title,
                        subtitle = subtitle.ifBlank { displayName },
                        latitude = lat,
                        longitude = lon,
                        categoryLabel = readableType,
                        source = SearchResultSource.OPENSTREETMAP,
                        markerCategory = inferredCat
                    )
                )
            }
            return results
        }

        fun parseGeoapifyJson(json: String): List<SearchPlaceResult> {
            val trimmed = json.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) return emptyList()
            val root = JSONObject(trimmed)
            val features = root.optJSONArray("features") ?: return emptyList()
            val results = ArrayList<SearchPlaceResult>(features.length())
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val props = feature.optJSONObject("properties") ?: continue
                val lat = props.optDouble("lat", Double.NaN)
                val lon = props.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val title = props.optString("name", "").trim()
                    .ifBlank { props.optString("address_line1", "").trim() }
                    .ifBlank { props.optString("formatted", "Location").trim() }
                val subtitle = props.optString("address_line2", "").trim()
                    .ifBlank { props.optString("formatted", "").trim() }
                    .ifBlank { String.format(Locale.US, "%.4f, %.4f", lat, lon) }
                val resultType = props.optString("result_type", "").lowercase(Locale.US)
                val catHint = props.optString("category", "").lowercase(Locale.US)
                val inferredCat = inferMarkerCategory(catHint, resultType)
                val placeId = props.optString("place_id", "geoapify_$i")

                results.add(
                    SearchPlaceResult(
                        id = "geoapify_$placeId",
                        title = title,
                        subtitle = subtitle,
                        latitude = lat,
                        longitude = lon,
                        categoryLabel = resultType.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
                        }.ifBlank { inferredCat.label },
                        source = SearchResultSource.OPENSTREETMAP,
                        markerCategory = inferredCat
                    )
                )
            }
            return results
        }

        private fun inferMarkerCategory(osmCategory: String, osmType: String): MarkerCategory {
            return when {
                osmType in setOf("park", "garden", "nature_reserve", "forest", "playground", "pitch") ||
                    osmCategory == "leisure" || osmCategory == "natural" -> MarkerCategory.PARK
                osmType in setOf("restaurant", "cafe", "bakery", "fast_food", "bar", "pub", "food_court") ->
                    MarkerCategory.RESTAURANT
                osmCategory == "shop" || osmType in setOf("supermarket", "mall", "convenience", "marketplace") ->
                    MarkerCategory.SHOP
                osmCategory == "railway" || osmType in setOf("station", "subway_entrance", "bus_stop", "bus_station", "tram_stop", "ferry_terminal") ->
                    MarkerCategory.TRANSIT
                osmType in setOf("house", "residential", "apartments") ->
                    MarkerCategory.HOME
                else -> MarkerCategory.LANDMARK
            }
        }
    }
}
