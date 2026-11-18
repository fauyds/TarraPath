package com.example

import com.example.data.local.MapMarkerEntity
import com.example.data.model.MarkerCategory
import com.example.data.model.SearchResultSource
import com.example.data.remote.LocationSearchService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocationSearchTest {

    @Test
    fun parseCoordinateQuery_parsesValidCoordinatesAndRejectsOutOfRange() {
        val parsed = LocationSearchService.parseCoordinateQuery("37.7749, -122.4194")
        assertNotNull(parsed)
        assertEquals(37.7749, parsed!!.latitude, 0.0001)
        assertEquals(-122.4194, parsed.longitude, 0.0001)
        assertEquals(SearchResultSource.COORDINATE, parsed.source)

        assertNull(LocationSearchService.parseCoordinateQuery("95.0, -122.0"))
        assertNull(LocationSearchService.parseCoordinateQuery("Golden Gate Park"))
    }

    @Test
    fun parseNominatimJson_parsesPlacesAndInfersMarkerCategories() {
        val sampleJson = """
            [
              {
                "place_id": 1001,
                "lat": "37.7694",
                "lon": "-122.4862",
                "name": "Golden Gate Park",
                "display_name": "Golden Gate Park, San Francisco, California, United States",
                "category": "leisure",
                "type": "park"
              },
              {
                "place_id": 1002,
                "lat": "37.7765",
                "lon": "-122.4175",
                "name": "Blue Bottle Coffee",
                "display_name": "Blue Bottle Coffee, Market Street, San Francisco, CA",
                "category": "amenity",
                "type": "cafe"
              }
            ]
        """.trimIndent()

        val results = LocationSearchService.parseNominatimJson(sampleJson)
        assertEquals(2, results.size)

        assertEquals("Golden Gate Park", results[0].title)
        assertEquals(MarkerCategory.PARK, results[0].markerCategory)
        assertEquals(SearchResultSource.OPENSTREETMAP, results[0].source)

        assertEquals("Blue Bottle Coffee", results[1].title)
        assertEquals(MarkerCategory.RESTAURANT, results[1].markerCategory)
    }

    @Test
    fun searchLocalMarkers_matchesTitleAndNoteCaseInsensitively() {
        val markers = listOf(
            MapMarkerEntity(
                id = 1L,
                title = "Home Base",
                note = "Near Baker Street",
                categoryName = MarkerCategory.HOME.name,
                colorArgb = MarkerCategory.HOME.defaultColorArgb,
                latitude = 51.5237,
                longitude = -0.1585,
                createdAt = 1000L
            ),
            MapMarkerEntity(
                id = 2L,
                title = "Corner Bakery",
                note = "Best croissants",
                categoryName = MarkerCategory.RESTAURANT.name,
                colorArgb = MarkerCategory.RESTAURANT.defaultColorArgb,
                latitude = 51.5240,
                longitude = -0.1590,
                createdAt = 2000L
            )
        )

        val matches = LocationSearchService.searchLocalMarkers("baker", markers)
        assertEquals(2, matches.size)
        assertEquals(SearchResultSource.SAVED_PIN, matches[0].source)
    }
}
