package com.example

import android.app.Application
import android.content.Context
import com.example.data.local.TerraPathDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.LocationSearchService
import com.example.data.repository.TerraRepository
import com.example.data.sensors.CompassHeadingTracker
import com.example.data.sensors.LocationTracker
import com.example.data.sensors.StepSensorTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

class AppContainer(application: Application) {
    private val database = TerraPathDatabase.getInstance(application)
    val preferences = UserPreferencesRepository(application)
    val locationTracker = LocationTracker(application)
    val compassHeadingTracker = CompassHeadingTracker(application)
    val stepSensorTracker = StepSensorTracker(application)
    val locationSearchService = LocationSearchService(application)
    val repository = TerraRepository(
        chunkDao = database.chunkDao(),
        routeDao = database.routeDao(),
        markerDao = database.markerDao(),
        paletteDao = database.paletteDao(),
        achievementDao = database.achievementDao()
    )
}

class TerraPathApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        initOsmdroidConfiguration()
        container = AppContainer(this)
        appScope.launch {
            container.repository.ensureDefaultDataSeeded()
        }
    }

    private fun initOsmdroidConfiguration() {
        val osmdroidPrefs = getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
        val config = Configuration.getInstance()
        config.load(applicationContext, osmdroidPrefs)
        config.userAgentValue = "TerraPath/1.0 ($packageName)"
        val baseDir = File(cacheDir, "osmdroid")
        val tileDir = File(baseDir, "tiles")
        baseDir.mkdirs()
        tileDir.mkdirs()
        config.osmdroidBasePath = baseDir
        config.osmdroidTileCache = tileDir
    }
}
