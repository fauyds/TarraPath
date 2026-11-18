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
}
