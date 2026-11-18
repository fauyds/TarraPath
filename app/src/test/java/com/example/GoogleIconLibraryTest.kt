package com.example

import android.content.Context
import android.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.MarkerCategory
import com.example.ui.icons.GoogleIconRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GoogleIconLibraryTest {

    @Test
    fun loadAllIcons_containsOverTwoThousandGoogleMaterialIcons() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entries = GoogleIconRegistry.loadAllIcons(context)
        assertTrue(
            "Expected at least 2000 Google Material icons, got ${entries.size}",
            entries.size >= 2000
        )
    }

    @Test
    fun resolveIcon_resolvesEveryIndexedGoogleIconWithoutBrokenReflection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val entries = GoogleIconRegistry.loadAllIcons(context)
        val broken = mutableListOf<String>()

        for (entry in entries) {
            val resolved = GoogleIconRegistry.resolveIcon(entry.name)
            if (entry.name != "Place" && resolved === Icons.Filled.Place) {
                broken.add(entry.name)
            }
            if (resolved.root.size == 0) {
                broken.add("${entry.name}(empty)")
            }
        }

        assertTrue(
            "Found icons failing to resolve: ${broken.take(15)}",
            broken.isEmpty()
        )
    }

    @Test
    fun renderCenteredIconBitmap_producesSizedBitmapCenteredInBounds() {
        val sampleIcons = MarkerCategory.entries.map { it.defaultIconName } + listOf(
            "Coffee",
            "DirectionsBike",
            "_360",
            "Star"
        )
        val targetSize = 48

        for (iconName in sampleIcons) {
            val bitmap = GoogleIconRegistry.renderCenteredIconBitmap(iconName, targetSize)
            assertEquals(targetSize, bitmap.width)
            assertEquals(targetSize, bitmap.height)

            var minX = targetSize
            var maxX = -1
            var minY = targetSize
            var maxY = -1
            var litPixels = 0

            for (y in 0 until targetSize) {
                for (x in 0 until targetSize) {
                    if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                        litPixels++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }

            if (litPixels > 0) {
                val centerX = (minX + maxX) / 2f
                val centerY = (minY + maxY) / 2f
                assertTrue(
                    "$iconName X center $centerX should be close to ${targetSize / 2f}",
                    abs(centerX - (targetSize - 1) / 2f) <= 6f
                )
                assertTrue(
                    "$iconName Y center $centerY should be close to ${targetSize / 2f}",
                    abs(centerY - (targetSize - 1) / 2f) <= 6f
                )
            }
        }
    }
}
