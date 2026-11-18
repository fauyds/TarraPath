package com.example

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.ApiProvider
import com.example.data.model.MarkerCategory
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.LocationSearchService
import com.example.ui.components.ColorPickerMath
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
    }
}
