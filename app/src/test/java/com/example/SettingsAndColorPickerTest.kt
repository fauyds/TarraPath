package com.example

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ApiProvider
import com.example.data.model.MarkerCategory
import com.example.data.model.MarkerStyle
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.LocationSearchService
import com.example.ui.components.ColorPickerMath
import com.example.ui.icons.GoogleIconRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsAndColorPickerTest {

    @Test
    fun colorPickerMath_roundTripsHexAndHsvAccurately() {
        val parsed = ColorPickerMath.parseHexColor("#3B82F6")
        assertNotNull(parsed)
        assertEquals("#3B82F6", ColorPickerMath.formatHexRgb(parsed!!))

        val hsv = ColorPickerMath.colorToHsv(parsed)
        val reconstructed = ColorPickerMath.hsvToColor(hsv[0], hsv[1], hsv[2])
        assertEquals("#3B82F6", ColorPickerMath.formatHexRgb(reconstructed))

        assertNull(ColorPickerMath.parseHexColor("#123"))
        assertNull(ColorPickerMath.parseHexColor("ZZZZZZ"))

        assertEquals(Color.White, ColorPickerMath.contrastTextColor(Color(0xFF111827)))
        assertEquals(Color(0xFF14171A), ColorPickerMath.contrastTextColor(Color(0xFFFDE047)))
    }

    @Test
    fun parseGeoapifyJson_parsesFeaturesAndInferredCategory() {
        val sampleGeoapify = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "properties": {
                    "place_id": "abc123",
                    "name": "Central Park",
                    "address_line2": "New York, NY, United States",
                    "lat": 40.7829,
                    "lon": -73.9654,
                    "category": "leisure",
                    "result_type": "park"
                  }
                }
              ]
            }
        """.trimIndent()

        val results = LocationSearchService.parseGeoapifyJson(sampleGeoapify)
        assertEquals(1, results.size)
        assertEquals("Central Park", results[0].title)
        assertEquals(MarkerCategory.PARK, results[0].markerCategory)
        assertEquals(40.7829, results[0].latitude, 0.0001)
    }

    @Test
    fun preferences_persistApiKeyProviderAndCustomAccentColor() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(context)

        prefs.setApiProvider(ApiProvider.GEOAPIFY)
        prefs.setApiKey("  geo-key-9988  ")
        assertEquals(ApiProvider.GEOAPIFY, prefs.apiProvider.first())
        assertEquals("geo-key-9988", prefs.apiKey.first())

        val pickedColor = Color(0xFFE11D48).toArgb().toLong() and 0xFFFFFFFFL
        prefs.setCustomAccentColor(pickedColor)
        assertEquals(pickedColor, prefs.customAccentColorArgb.first())

        prefs.setCustomAccentColor(null)
        prefs.setApiKey("")
        assertNull(prefs.customAccentColorArgb.first())
        assertTrue(prefs.apiKey.first().isEmpty())

        MarkerStyle.entries.forEach { style ->
            prefs.setMarkerStyle(style)
            assertEquals(style, prefs.mapDisplaySettings.first().markerStyle)
        }

        prefs.setUiBlurEnabled(true)
        prefs.setDisableCreatedMarkerLabels(true)
        val updatedSettings = prefs.mapDisplaySettings.first()
        assertTrue(updatedSettings.uiBlurEnabled)
        assertTrue(updatedSettings.disableCreatedMarkerLabels)

        prefs.setUiBlurEnabled(false)
        prefs.setDisableCreatedMarkerLabels(false)
        val resetSettings = prefs.mapDisplaySettings.first()
        assertTrue(!resetSettings.uiBlurEnabled)
        assertTrue(!resetSettings.disableCreatedMarkerLabels)
    }

    @Test
    fun adaptiveOutlineColor_adaptsPolarityAndHueToMarkerColor() {
        val brightYellow = Color(0xFFFDE047)
        val deepBlue = Color(0xFF1E3A8A)
        val crimson = Color(0xFFE11D48)

        val yellowOutline = ColorPickerMath.adaptiveOutlineColor(brightYellow)
        val blueOutline = ColorPickerMath.adaptiveOutlineColor(deepBlue)
        val redOutline = ColorPickerMath.adaptiveOutlineColor(crimson)

        // Bright fill should produce a dark outline; dark fills should produce light outlines
        assertTrue(yellowOutline.red < 0.35f && yellowOutline.green < 0.35f && yellowOutline.blue < 0.35f)
        assertTrue(blueOutline.red > 0.80f && blueOutline.green > 0.80f && blueOutline.blue > 0.80f)
        assertTrue(redOutline.red > 0.80f && redOutline.green > 0.80f && redOutline.blue > 0.80f)
        // Different input colors produce distinct hue-tinted outlines
        assertTrue(blueOutline != redOutline)
    }

    @Test
    fun renderStyledIconBitmap_generatesDistinctBitmapsForAllStyles() {
        val colorInt = android.graphics.Color.rgb(37, 99, 235)
        val altColorInt = android.graphics.Color.rgb(225, 29, 72)
        val outlineInt = android.graphics.Color.WHITE

        val iconOnly = GoogleIconRegistry.renderStyledIconBitmap(
            iconName = "Home",
            sizePx = 64,
            tintColor = colorInt,
            outlineColor = null
        )
        val iconOutline = GoogleIconRegistry.renderStyledIconBitmap(
            iconName = "Home",
            sizePx = 64,
            tintColor = colorInt,
            outlineColor = outlineInt,
            outlineWidthPx = 6f
        )
        val iconAltTint = GoogleIconRegistry.renderStyledIconBitmap(
            iconName = "Home",
            sizePx = 64,
            tintColor = altColorInt,
            outlineColor = null
        )

        assertEquals(64, iconOnly.width)
        assertEquals(64, iconOnly.height)
        assertEquals(64, iconOutline.width)
        assertEquals(64, iconOutline.height)
        assertEquals(android.graphics.Bitmap.Config.ARGB_8888, iconOutline.config)
        assertTrue(iconOnly !== iconOutline)
        assertTrue(iconOnly !== iconAltTint)
    }

    @Test
    fun verifyApiKey_handlesBlankAndGeneralProviderCleanly() = runTest {
        val service = LocationSearchService()

        val blankResult = service.verifyApiKey("   ", ApiProvider.GEOAPIFY)
        assertTrue(blankResult.isFailure)
        assertEquals("Enter an API key first.", blankResult.exceptionOrNull()?.message)

        val generalResult = service.verifyApiKey("my-personal-key", ApiProvider.CUSTOM_GENERAL)
        assertTrue(generalResult.isSuccess)
        assertTrue(generalResult.getOrThrow().contains("saved locally", ignoreCase = true))
    }

    @Test
    fun searchRemotePlacesWithProvider_shortQueryAndRejectedException() = runTest {
        val service = LocationSearchService()
        val shortOutcome = service.searchRemotePlacesWithProvider(
            query = "a",
            apiKey = "fake",
            apiProvider = ApiProvider.GEOAPIFY
        )
        assertTrue(shortOutcome.isSuccess)
        assertEquals("OpenStreetMap", shortOutcome.getOrThrow().providerLabel)
        assertTrue(shortOutcome.getOrThrow().results.isEmpty())

        // LocationIQ returns {"error":"Unable to geocode"} on empty 404 or bad object payloads
        assertTrue(LocationSearchService.parseNominatimJson("""{"error":"Unable to geocode"}""").isEmpty())

        val rejected = com.example.data.remote.ApiKeyRejectedException(
            provider = ApiProvider.LOCATION_IQ,
            httpCode = 401,
            message = "LocationIQ rejected this token (HTTP 401)."
        )
        assertEquals(ApiProvider.LOCATION_IQ, rejected.provider)
        assertEquals(401, rejected.httpCode)
        assertTrue(rejected.message!!.contains("HTTP 401"))

        assertEquals(
            "abc123def456",
            LocationSearchService.sanitizeApiKey("  \"abc123\ndef456\"  ")
        )
        assertEquals(
            "Invalid apiKey",
            LocationSearchService.parseErrorDetailFromBody("""{"statusCode":401,"error":"Unauthorized","message":"Invalid apiKey"}""")
        )
        assertEquals(
            "Invalid key",
            LocationSearchService.parseErrorDetailFromBody("""{"error":"Invalid key"}""")
        )
    }

    @Test
    fun preferences_persistAllMapLabelToggles() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(context)

        assertTrue(prefs.mapDisplaySettings.first().showLocationOutline)

        prefs.setShowStreetNames(false)
        prefs.setShowMarkerLabels(false)
        prefs.setShowSearchLabels(false)
        prefs.setShowSectorLabels(true)
        prefs.setShowLocationOutline(false)
        val labelDisabled = prefs.mapDisplaySettings.first()
        assertTrue(!labelDisabled.showStreetNames)
        assertTrue(!labelDisabled.showMarkerLabels)
        assertTrue(!labelDisabled.showSearchLabels)
        assertTrue(labelDisabled.showSectorLabels)
        assertTrue(!labelDisabled.showLocationOutline)

        prefs.setShowStreetNames(true)
        prefs.setShowMarkerLabels(true)
        prefs.setShowSearchLabels(true)
        prefs.setShowSectorLabels(false)
        prefs.setShowLocationOutline(true)
        val restored = prefs.mapDisplaySettings.first()
        assertTrue(restored.showStreetNames)
        assertTrue(restored.showMarkerLabels)
        assertTrue(restored.showSearchLabels)
        assertTrue(!restored.showSectorLabels)
        assertTrue(restored.showLocationOutline)
    }

    @Test
    fun resolveMapTileSpec_handlesStreetNamesToggleAndProvidersCorrectly() {
        val sampleIndex = org.osmdroid.util.MapTileIndex.getTileIndex(16, 38012, 23084)

        val geoDark = com.example.ui.map.resolveMapTileSpec(
            isDarkTheme = true,
            showStreetNames = true,
            apiKey = "test-geo-key",
            apiProvider = ApiProvider.GEOAPIFY
        )
        assertTrue(!geoDark.useDarkInvertFilter)
        val geoDarkOnline = geoDark.tileSource as org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
        assertEquals(
            "https://maps.geoapify.com/v1/tile/dark-matter/16/38012/23084.png?apiKey=test-geo-key",
            geoDarkOnline.getTileURLString(sampleIndex)
        )

        val geoLight = com.example.ui.map.resolveMapTileSpec(
            isDarkTheme = false,
            showStreetNames = true,
            apiKey = "test-geo-key",
            apiProvider = ApiProvider.GEOAPIFY
        )
        assertTrue(!geoLight.useDarkInvertFilter)
        val geoLightOnline = geoLight.tileSource as org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
        assertEquals(
            "https://maps.geoapify.com/v1/tile/osm-bright/16/38012/23084.png?apiKey=test-geo-key",
            geoLightOnline.getTileURLString(sampleIndex)
        )

        val fallbackDark = com.example.ui.map.resolveMapTileSpec(
            isDarkTheme = true,
            showStreetNames = true,
            apiKey = "",
            apiProvider = ApiProvider.CUSTOM_GENERAL
        )
        assertTrue(fallbackDark.useDarkInvertFilter)
        val fallbackOnline = fallbackDark.tileSource as org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
        val fallbackUrl = fallbackOnline.getTileURLString(sampleIndex)
        assertTrue(fallbackUrl.startsWith("https://tile.openstreetmap.org/"))
        assertTrue(!fallbackUrl.contains("cartocdn"))

        // Disabling Street & Map Names switches to label-free vector tiles regardless of provider
        val noLabelsGeoDark = com.example.ui.map.resolveMapTileSpec(
            isDarkTheme = true,
            showStreetNames = false,
            apiKey = "test-geo-key",
            apiProvider = ApiProvider.GEOAPIFY
        )
        assertTrue(noLabelsGeoDark.tileSource is com.example.ui.map.NoLabelsVectorTileSource)
        assertEquals("OpenFreeMapNoLabelsDark", noLabelsGeoDark.tileSource.name())
        assertTrue(!noLabelsGeoDark.useDarkInvertFilter)

        val noLabelsOsmLight = com.example.ui.map.resolveMapTileSpec(
            isDarkTheme = false,
            showStreetNames = false,
            apiKey = "",
            apiProvider = ApiProvider.CUSTOM_GENERAL
        )
        assertTrue(noLabelsOsmLight.tileSource is com.example.ui.map.NoLabelsVectorTileSource)
        assertEquals("OpenFreeMapNoLabelsLight", noLabelsOsmLight.tileSource.name())
        assertTrue(!noLabelsOsmLight.useDarkInvertFilter)
    }

    @Test
    fun mvtTileParser_retainsGeometryLayersAndStripsLabelLayers() {
        fun varint(value: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var v = value
            while ((v and 0x7F.inv()) != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v and 0x7F)
            return out.toByteArray()
        }

        fun lengthDelimited(field: Int, payload: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(varint((field shl 3) or 2))
            out.write(varint(payload.size))
            out.write(payload)
            return out.toByteArray()
        }

        fun buildLayer(name: String, includeLineFeature: Boolean): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            out.write(lengthDelimited(1, name.toByteArray(Charsets.UTF_8)))
            if (includeLineFeature) {
                val feat = java.io.ByteArrayOutputStream()
                feat.write(varint((3 shl 3) or 0)) // type tag
                feat.write(varint(2)) // LINESTRING
                val geom = intArrayOf(9, 0, 0, 10, 20, 20) // MoveTo(0,0), LineTo(+10,+10)
                val geomBytes = java.io.ByteArrayOutputStream()
                geom.forEach { geomBytes.write(varint(it)) }
                feat.write(lengthDelimited(4, geomBytes.toByteArray()))
                out.write(lengthDelimited(2, feat.toByteArray()))
            }
            return out.toByteArray()
        }

        val tileBytes = java.io.ByteArrayOutputStream().apply {
            write(lengthDelimited(3, buildLayer("transportation", includeLineFeature = true)))
            write(lengthDelimited(3, buildLayer("transportation_name", includeLineFeature = true)))
            write(lengthDelimited(3, buildLayer("place", includeLineFeature = true)))
        }.toByteArray()

        val decoded = com.example.ui.map.MvtTileParser.parse(tileBytes)
        assertTrue(decoded.layersByName.containsKey("transportation"))
        assertEquals(1, decoded.layersByName["transportation"]?.features?.size)
        assertTrue(!decoded.layersByName.containsKey("transportation_name"))
        assertTrue(!decoded.layersByName.containsKey("place"))
    }

    @Test
    fun speedEstimator_filtersIndoorDriftAndTracksRealMovementCleanly() {
        val base = com.example.data.sensors.SpeedFixSample(
            latitude = 46.9926,
            longitude = 28.8307,
            accuracyMeters = 16f,
            reportedSpeedMps = 0.65f, // ~2.34 km/h noisy drift while coordinates didn't move
            speedAccuracyMps = 2.8f,
            elapsedRealtimeMs = 1_000L
        )
        val stationaryNext = com.example.data.sensors.SpeedFixSample(
            latitude = 46.992602,
            longitude = 28.830702, // < 0.3m moved
            accuracyMeters = 16f,
            reportedSpeedMps = 0.7f, // ~2.5 km/h phantom drift
            speedAccuracyMps = 3.0f,
            elapsedRealtimeMs = 2_500L
        )
        val driftSpeed = com.example.data.sensors.SpeedEstimator.computeSpeedKmh(
            current = stationaryNext,
            previous = base,
            previousSmoothedKmh = 0f
        )
        assertEquals(0f, driftSpeed, 0.001f)

        // Real walking fix with good accuracy and reported speed (1.5 m/s = 5.4 km/h)
        val walkingFix = com.example.data.sensors.SpeedFixSample(
            latitude = 46.99266,
            longitude = 28.83076,
            accuracyMeters = 6f,
            reportedSpeedMps = 1.5f,
            speedAccuracyMps = 0.4f,
            elapsedRealtimeMs = 4_000L
        )
        val walkingSpeed = com.example.data.sensors.SpeedEstimator.computeSpeedKmh(
            current = walkingFix,
            previous = stationaryNext,
            previousSmoothedKmh = 0f
        )
        assertTrue(walkingSpeed in 3.0f..5.5f)

        // Fallback calculation when reportedSpeedMps is null (~22m in 4s = ~19.8 km/h)
        val bikeStart = com.example.data.sensors.SpeedFixSample(
            latitude = 46.99260,
            longitude = 28.83070,
            accuracyMeters = 5f,
            reportedSpeedMps = null,
            speedAccuracyMps = null,
            elapsedRealtimeMs = 10_000L
        )
        val bikeEnd = com.example.data.sensors.SpeedFixSample(
            latitude = 46.99280, // ~22.2m north
            longitude = 28.83070,
            accuracyMeters = 5f,
            reportedSpeedMps = null,
            speedAccuracyMps = null,
            elapsedRealtimeMs = 14_000L
        )
        val derivedBikeSpeed = com.example.data.sensors.SpeedEstimator.computeSpeedKmh(
            current = bikeEnd,
            previous = bikeStart,
            previousSmoothedKmh = 0f
        )
        assertTrue(derivedBikeSpeed in 10f..22f)
    }

    @Test
    fun headingEstimator_normalizesSmoothsAndFusesCompassAndGpsBearings() {
        assertEquals(350f, com.example.data.sensors.HeadingEstimator.normalizeDegrees(-10f), 0.01f)
        assertEquals(15f, com.example.data.sensors.HeadingEstimator.normalizeDegrees(375f), 0.01f)

        // Smoothing across the 359 -> 1 boundary stays near North instead of swinging through 180°
        val wrappedSmooth = com.example.data.sensors.HeadingEstimator.smoothAngle(
            previousDegrees = 358f,
            newDegrees = 2f,
            alpha = 0.5f
        )
        assertEquals(0f, wrappedSmooth, 0.1f)

        // Moving directly East gives ~90° bearing
        val eastBearing = com.example.data.sensors.HeadingEstimator.computeCourseBearingDegrees(
            prevLat = 46.9926,
            prevLon = 28.8307,
            curLat = 46.9926,
            curLon = 28.8315
        )
        assertNotNull(eastBearing)
        assertTrue(eastBearing!! in 88f..92f)

        // Sub-jitter movement (< 2m) yields null course bearing
        assertNull(
            com.example.data.sensors.HeadingEstimator.computeCourseBearingDegrees(
                prevLat = 46.9926,
                prevLon = 28.8307,
                curLat = 46.992602,
                curLon = 28.830702
            )
        )

        // Walking / stationary prefers live compass over old GPS bearing
        assertEquals(
            42f,
            com.example.data.sensors.HeadingEstimator.resolveHeadingDegrees(
                compassDegrees = 42f,
                gpsBearingDegrees = 180f,
                speedKmh = 4.2f
            )!!,
            0.01f
        )

        // Fast movement prefers GPS course bearing over phone compass
        assertEquals(
            180f,
            com.example.data.sensors.HeadingEstimator.resolveHeadingDegrees(
                compassDegrees = 42f,
                gpsBearingDegrees = 180f,
                speedKmh = 18.5f
            )!!,
            0.01f
        )

        // Device without compass falls back to GPS course bearing cleanly
        assertEquals(
            180f,
            com.example.data.sensors.HeadingEstimator.resolveHeadingDegrees(
                compassDegrees = null,
                gpsBearingDegrees = 180f,
                speedKmh = 4.0f
            )!!,
            0.01f
        )
    }
}
