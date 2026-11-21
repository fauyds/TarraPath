package com.example.ui.map

import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PathEffect
import android.graphics.Path as AndroidPath
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.local.ExploredChunkEntity
import com.example.data.local.MapMarkerEntity
import com.example.data.model.ApiProvider
import com.example.data.model.LatLonPoint
import com.example.data.model.LocationTelemetry
import com.example.data.model.MapDisplaySettings
import com.example.data.model.MapPalette
import com.example.data.model.MarkerCategory
import com.example.data.model.MarkerStyle
import com.example.data.model.RoutingMode
import com.example.data.model.SearchPlaceResult
import com.example.data.remote.LocationSearchService
import com.example.domain.GeoGrid
import com.example.ui.components.ColorPickerMath
import com.example.ui.icons.GoogleIconRegistry
import kotlinx.coroutines.delay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.net.URLEncoder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

private val OSM_STANDARD_LIGHT = XYTileSource(
    "OsmStandardLight",
    0,
    19,
    256,
    ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    "© OpenStreetMap contributors"
)

private val OSM_STANDARD_DARK = XYTileSource(
    "OsmStandardDark",
    0,
    19,
    256,
    ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    "© OpenStreetMap contributors"
)

private val OSM_DARK_INVERT_FILTER = ColorMatrixColorFilter(
    floatArrayOf(
        -0.92f, 0f, 0f, 0f, 240f,
        0f, -0.92f, 0f, 0f, 240f,
        0f, 0f, -0.90f, 0f, 245f,
        0f, 0f, 0f, 1.0f, 0f
    )
)

private val NO_LABELS_LIGHT = NoLabelsVectorTileSource(isDarkTheme = false)
private val NO_LABELS_DARK = NoLabelsVectorTileSource(isDarkTheme = true)

data class ResolvedMapTileSpec(
    val tileSource: ITileSource,
    val useDarkInvertFilter: Boolean
)

fun createGeoapifyTileSource(
    isDarkTheme: Boolean,
    apiKey: String
): OnlineTileSourceBase {
    val cleanKey = LocationSearchService.sanitizeApiKey(apiKey)
    val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
    val styleId = if (isDarkTheme) "dark-matter" else "osm-bright"
    val cacheSuffix = (cleanKey.hashCode() and 0x7FFFFFFF).toString(16)
    val sourceName = if (isDarkTheme) {
        "GeoapifyDarkMatter_$cacheSuffix"
    } else {
        "GeoapifyOsmBright_$cacheSuffix"
    }
    return object : OnlineTileSourceBase(
        sourceName,
        0,
        20,
        256,
        ".png",
        arrayOf("https://maps.geoapify.com/v1/tile/$styleId/"),
        "© OpenStreetMap contributors © Geoapify"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${baseUrl}$z/$x/$y$mImageFilenameEnding?apiKey=$encodedKey"
        }
    }
}

fun resolveMapTileSpec(
    isDarkTheme: Boolean,
    showStreetNames: Boolean = true,
    apiKey: String = "",
    apiProvider: ApiProvider = ApiProvider.CUSTOM_GENERAL
): ResolvedMapTileSpec {
    if (!showStreetNames) {
        return ResolvedMapTileSpec(
            tileSource = if (isDarkTheme) NO_LABELS_DARK else NO_LABELS_LIGHT,
            useDarkInvertFilter = false
        )
    }
    val cleanKey = LocationSearchService.sanitizeApiKey(apiKey)
    return if (apiProvider == ApiProvider.GEOAPIFY && cleanKey.isNotEmpty()) {
        ResolvedMapTileSpec(
            tileSource = createGeoapifyTileSource(isDarkTheme = isDarkTheme, apiKey = cleanKey),
            useDarkInvertFilter = false
        )
    } else {
        ResolvedMapTileSpec(
            tileSource = if (isDarkTheme) OSM_STANDARD_DARK else OSM_STANDARD_LIGHT,
            useDarkInvertFilter = isDarkTheme
        )
    }
}

class MapControlCommands {
    var zoomInSeq by mutableLongStateOf(0L)
    var zoomOutSeq by mutableLongStateOf(0L)
    var recenterSeq by mutableLongStateOf(0L)
    var resetRotationSeq by mutableLongStateOf(0L)
    var focusSeq by mutableLongStateOf(0L)
    var focusLat by mutableDoubleStateOf(0.0)
    var focusLon by mutableDoubleStateOf(0.0)
    var focusZoom by mutableDoubleStateOf(16.0)
    var currentOrientation by mutableFloatStateOf(0f)

    fun zoomIn() { zoomInSeq++ }
    fun zoomOut() { zoomOutSeq++ }
    fun recenter() { recenterSeq++ }
    fun resetRotation() { resetRotationSeq++ }
    fun focusOn(lat: Double, lon: Double, zoom: Double = 16.0) {
        focusLat = lat
        focusLon = lon
        focusZoom = zoom
        focusSeq++
    }
}

@Composable
fun TerraMapCanvas(
    palette: MapPalette,
    isDarkTheme: Boolean,
    mapSettings: MapDisplaySettings,
    apiKey: String = "",
    apiProvider: ApiProvider = ApiProvider.CUSTOM_GENERAL,
    centerLat: Double,
    centerLon: Double,
    followUser: Boolean,
    controlCommands: MapControlCommands,
    exploredChunks: List<ExploredChunkEntity>,
    activeRoutePoints: List<LatLonPoint>,
    highlightedRoutePoints: List<LatLonPoint>,
    highlightedRouteColor: Color?,
    walkingRoutePoints: List<LatLonPoint> = emptyList(),
    walkingTargetPoint: LatLonPoint? = null,
    markers: List<MapMarkerEntity>,
    searchedPlace: SearchPlaceResult?,
    telemetry: LocationTelemetry,
    showFogOfWar: Boolean = true,
    revealedMarkerId: Long? = null,
    onUserPan: () -> Unit,
    onLongPressCoordinate: (lat: Double, lon: Double) -> Unit,
    onMarkerClick: (MapMarkerEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    val currentOnUserPan by rememberUpdatedState(onUserPan)
    val currentOnLongPress by rememberUpdatedState(onLongPressCoordinate)
    val currentOnMarkerClick by rememberUpdatedState(onMarkerClick)

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val recentChunkMap = remember(exploredChunks) {
        val cutoff = System.currentTimeMillis() - 1200L
        exploredChunks.filter { it.unlockedAt >= cutoff }.associate { it.chunkKey to it.unlockedAt }
    }
    LaunchedEffect(recentChunkMap.size) {
        if (recentChunkMap.isNotEmpty()) {
            repeat(20) {
                nowMillis = System.currentTimeMillis()
                delay(50L)
            }
        }
    }

    val exploredSet = remember(exploredChunks) {
        exploredChunks.map { it.chunkKey }.toHashSet()
    }
    val unlockTimes = remember(exploredChunks) {
        exploredChunks.associate { it.chunkKey to it.unlockedAt }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "gps_pulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_ring"
    )

    val terraOverlay = remember {
        TerraOsmOverlay(
            density = density.density,
            onLongPress = { lat, lon -> currentOnLongPress(lat, lon) },
            onMarkerTap = { marker -> currentOnMarkerClick(marker) },
            onOrientationObserved = { angle ->
                controlCommands.currentOrientation = angle
            }
        )
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var isProgrammaticMove by remember { mutableStateOf(false) }

    val tileSpec = remember(isDarkTheme, mapSettings.showStreetNames, apiKey, apiProvider) {
        resolveMapTileSpec(
            isDarkTheme = isDarkTheme,
            showStreetNames = mapSettings.showStreetNames,
            apiKey = apiKey,
            apiProvider = apiProvider
        )
    }

    val mapView = remember {
        MapView(context, TerraMapTileProvider(context, tileSpec.tileSource)).apply {
            setTileSource(tileSpec.tileSource)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            setUseDataConnection(true)
            minZoomLevel = 4.0
            maxZoomLevel = 20.5
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(centerLat, centerLon))
            val rotationOverlay = RotationGestureOverlay(this).apply {
                isEnabled = true
            }
            overlays.add(rotationOverlay)
            overlays.add(terraOverlay)
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    controlCommands.currentOrientation = mapOrientation
                    if (!isProgrammaticMove && (event?.x != 0 || event?.y != 0)) {
                        currentOnUserPan()
                    }
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    controlCommands.currentOrientation = mapOrientation
                    return false
                }
            })
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        mapViewRef = mapView
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            mapView.onResume()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
            mapViewRef = null
        }
    }

    // Update native light/dark/no-labels tile source, offline network mode, & loading surface colors automatically
    LaunchedEffect(
        tileSpec.tileSource.name(),
        tileSpec.useDarkInvertFilter,
        isDarkTheme,
        mapSettings.showStreetNames,
        mapSettings.routingMode,
        mapView
    ) {
        mapView.setUseDataConnection(mapSettings.routingMode != RoutingMode.OFFLINE)
        if (mapView.tileProvider.tileSource.name() != tileSpec.tileSource.name()) {
            mapView.tileProvider.clearTileCache()
            mapView.setTileSource(tileSpec.tileSource)
        }
        mapView.overlayManager.tilesOverlay.apply {
            setColorFilter(if (tileSpec.useDarkInvertFilter) OSM_DARK_INVERT_FILTER else null)
            loadingBackgroundColor = if (isDarkTheme) 0xFF1A1E24.toInt() else 0xFFF2EFE9.toInt()
            loadingLineColor = if (isDarkTheme) 0xFF2B3036.toInt() else 0xFFD9D4CA.toInt()
        }
        mapView.invalidate()
    }

    LaunchedEffect(centerLat, centerLon, followUser, mapView) {
        if (followUser) {
            isProgrammaticMove = true
            mapView.controller.animateTo(GeoPoint(centerLat, centerLon))
            delay(250L)
            isProgrammaticMove = false
        }
    }

    LaunchedEffect(highlightedRoutePoints, mapView) {
        if (highlightedRoutePoints.isNotEmpty()) {
            isProgrammaticMove = true
            val first = highlightedRoutePoints.first()
            mapView.controller.animateTo(GeoPoint(first.latitude, first.longitude))
            delay(250L)
            isProgrammaticMove = false
        }
    }

    LaunchedEffect(controlCommands.zoomInSeq) {
        if (controlCommands.zoomInSeq > 0L) {
            mapViewRef?.controller?.zoomIn()
        }
    }

    LaunchedEffect(controlCommands.zoomOutSeq) {
        if (controlCommands.zoomOutSeq > 0L) {
            mapViewRef?.controller?.zoomOut()
        }
    }

    LaunchedEffect(controlCommands.recenterSeq) {
        if (controlCommands.recenterSeq > 0L) {
            isProgrammaticMove = true
            mapViewRef?.controller?.animateTo(GeoPoint(centerLat, centerLon))
            delay(250L)
            isProgrammaticMove = false
        }
    }

    LaunchedEffect(controlCommands.resetRotationSeq) {
        if (controlCommands.resetRotationSeq > 0L) {
            val view = mapViewRef
            if (view != null) {
                view.setMapOrientation(0f, true)
                controlCommands.currentOrientation = 0f
                view.invalidate()
            }
        }
    }

    LaunchedEffect(controlCommands.focusSeq) {
        if (controlCommands.focusSeq > 0L) {
            isProgrammaticMove = true
            val view = mapViewRef
            if (view != null) {
                if (view.zoomLevelDouble < controlCommands.focusZoom - 0.8) {
                    view.controller.setZoom(controlCommands.focusZoom)
                }
                view.controller.animateTo(GeoPoint(controlCommands.focusLat, controlCommands.focusLon))
            }
            delay(350L)
            isProgrammaticMove = false
        }
    }

    SideEffect {
        terraOverlay.updateState(
            palette = palette,
            isDarkTheme = isDarkTheme,
            mapSettings = mapSettings,
            exploredSet = exploredSet,
            unlockTimes = unlockTimes,
            nowMillis = nowMillis,
            activeRoutePoints = activeRoutePoints,
            highlightedRoutePoints = highlightedRoutePoints,
            highlightedRouteColor = highlightedRouteColor,
            walkingRoutePoints = walkingRoutePoints,
            walkingTargetPoint = walkingTargetPoint,
            markers = markers,
            searchedPlace = searchedPlace,
            telemetry = telemetry,
            pulseProgress = pulseProgress,
            showFogOfWar = showFogOfWar,
            revealedMarkerId = revealedMarkerId
        )
        mapView.invalidate()
    }

    AndroidView(
        factory = { mapView },
        update = { view -> view.invalidate() },
        modifier = modifier.fillMaxSize()
    )
}

private class TerraOsmOverlay(
    private val density: Float,
    private val onLongPress: (Double, Double) -> Unit,
    private val onMarkerTap: (MapMarkerEntity) -> Unit,
    private val onOrientationObserved: (Float) -> Unit
) : Overlay() {

    private var palette: MapPalette = MapPalette(
        id = -1L,
        name = "Light Map",
        isBuiltIn = true,
        landColor = 0xFFF4F1EA,
        roadColor = 0xFFFFFFFF,
        waterColor = 0xFFAAD3DF,
        fogColor = 0xFFFFFFFF,
        gridStrokeColor = 0x551B6B52,
        routeColor = 0xFF1B6B52
    )
    private var isDarkTheme: Boolean = false
    private var mapSettings: MapDisplaySettings = MapDisplaySettings()
    private var exploredSet: Set<String> = emptySet()
    private var unlockTimes: Map<String, Long> = emptyMap()
    private var nowMillis: Long = 0L
    private var activeRoutePoints: List<LatLonPoint> = emptyList()
    private var highlightedRoutePoints: List<LatLonPoint> = emptyList()
    private var highlightedRouteColor: Color? = null
    private var walkingRoutePoints: List<LatLonPoint> = emptyList()
    private var walkingTargetPoint: LatLonPoint? = null
    private var markers: List<MapMarkerEntity> = emptyList()
    private var searchedPlace: SearchPlaceResult? = null
    private var telemetry: LocationTelemetry = LocationTelemetry()
    private var pulseProgress: Float = 0f
    private var showFogOfWar: Boolean = true
    private var revealedMarkerId: Long? = null

    private val tmpPt1 = Point()
    private val tmpPt2 = Point()
    private val reusablePath = AndroidPath()

    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }
    private val districtGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
    }
    private val routeCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val walkDotEffect: PathEffect = DashPathEffect(
        floatArrayOf(1f, 14f * density),
        0f
    )
    private val walkCasingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = walkDotEffect
    }
    private val walkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = walkDotEffect
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val sectorLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val labelBgRect = RectF()
    private val directionConeRect = RectF()
    private var lastObservedOrientation: Float = 0f

    fun updateState(
        palette: MapPalette,
        isDarkTheme: Boolean,
        mapSettings: MapDisplaySettings,
        exploredSet: Set<String>,
        unlockTimes: Map<String, Long>,
        nowMillis: Long,
        activeRoutePoints: List<LatLonPoint>,
        highlightedRoutePoints: List<LatLonPoint>,
        highlightedRouteColor: Color?,
        walkingRoutePoints: List<LatLonPoint>,
        walkingTargetPoint: LatLonPoint?,
        markers: List<MapMarkerEntity>,
        searchedPlace: SearchPlaceResult?,
        telemetry: LocationTelemetry,
        pulseProgress: Float,
        showFogOfWar: Boolean,
        revealedMarkerId: Long?
    ) {
        this.palette = palette
        this.isDarkTheme = isDarkTheme
        this.mapSettings = mapSettings
        this.exploredSet = exploredSet
        this.unlockTimes = unlockTimes
        this.nowMillis = nowMillis
        this.activeRoutePoints = activeRoutePoints
        this.highlightedRoutePoints = highlightedRoutePoints
        this.highlightedRouteColor = highlightedRouteColor
        this.walkingRoutePoints = walkingRoutePoints
        this.walkingTargetPoint = walkingTargetPoint
        this.markers = markers
        this.searchedPlace = searchedPlace
        this.telemetry = telemetry
        this.pulseProgress = pulseProgress
        this.showFogOfWar = showFogOfWar
        this.revealedMarkerId = revealedMarkerId
    }

    override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
        val geo = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
        onLongPress(geo.latitude, geo.longitude)
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        val tapGeo = proj.fromPixels(e.x.toInt(), e.y.toInt())
        proj.toPixels(tapGeo, tmpPt2)
        val hitRadiusPx = 30f * density
        var closest: MapMarkerEntity? = null
        var closestDist = Float.MAX_VALUE
        for (marker in markers) {
            proj.toPixels(GeoPoint(marker.latitude, marker.longitude), tmpPt1)
            val d = hypot(
                (tmpPt2.x - tmpPt1.x).toFloat(),
                (tmpPt2.y - tmpPt1.y).toFloat()
            )
            if (d <= hitRadiusPx && d < closestDist) {
                closest = marker
                closestDist = d
            }
        }
        if (closest != null) {
            onMarkerTap(closest)
            return true
        }
        return false
    }

    override fun draw(canvas: AndroidCanvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val mapBearing = mapView.mapOrientation
        if (mapBearing != lastObservedOrientation) {
            lastObservedOrientation = mapBearing
            mapView.post { onOrientationObserved(mapBearing) }
        }

        val proj = mapView.projection
        val bbox = mapView.boundingBox

        val startCx = floor(bbox.lonWest / GeoGrid.CHUNK_SIZE_DEG_LON).toInt() - 1
        val endCx = ceil(bbox.lonEast / GeoGrid.CHUNK_SIZE_DEG_LON).toInt() + 1
        val startCy = floor(bbox.latSouth / GeoGrid.CHUNK_SIZE_DEG_LAT).toInt() - 1
        val endCy = ceil(bbox.latNorth / GeoGrid.CHUNK_SIZE_DEG_LAT).toInt() + 1

        val chunkCols = endCx - startCx
        val chunkRows = endCy - startCy

        // 1. Fog of War (Opaque or Easy Mode Transparent) & Smooth Fade Vignette
        if (chunkCols <= 70 && chunkRows <= 70) {
            val baseFogColor = palette.fog
            gridPaint.color = palette.gridStroke.toArgb()
            districtGridPaint.color = palette.gridStroke.copy(alpha = 0.85f).toArgb()
            sectorLabelPaint.color = if (isDarkTheme) 0xAAB8C4D0.toInt() else 0xAA334155.toInt()

            for (cx in startCx..endCx) {
                for (cy in startCy..endCy) {
                    val leftLon = cx * GeoGrid.CHUNK_SIZE_DEG_LON
                    val rightLon = (cx + 1) * GeoGrid.CHUNK_SIZE_DEG_LON
                    val bottomLat = cy * GeoGrid.CHUNK_SIZE_DEG_LAT
                    val topLat = (cy + 1) * GeoGrid.CHUNK_SIZE_DEG_LAT

                    proj.toPixels(GeoPoint(topLat, leftLon), tmpPt1)
                    proj.toPixels(GeoPoint(bottomLat, rightLon), tmpPt2)

                    val left = tmpPt1.x.toFloat()
                    val top = tmpPt1.y.toFloat()
                    val right = tmpPt2.x.toFloat()
                    val bottom = tmpPt2.y.toFloat()

                    val key = "$cx:$cy"
                    val unlockedTime = unlockTimes[key]

                    if (showFogOfWar && key !in exploredSet) {
                        fogPaint.color = baseFogColor.toArgb()
                        canvas.drawRect(left, top, right + 0.8f, bottom + 0.8f, fogPaint)
                    } else if (showFogOfWar && unlockedTime != null) {
                        val age = (nowMillis - unlockedTime).coerceAtLeast(0L)
                        if (age < 900L) {
                            val progress = (age / 900f).coerceIn(0f, 1f)
                            val revealAlpha = (1f - progress) * baseFogColor.alpha
                            fogPaint.color = baseFogColor.copy(alpha = revealAlpha).toArgb()
                            canvas.drawRect(left, top, right + 0.8f, bottom + 0.8f, fogPaint)
                            strokePaint.color = palette.route.copy(alpha = (1f - progress) * 0.7f).toArgb()
                            strokePaint.strokeWidth = 2.5f * density
                            canvas.drawRect(left, top, right, bottom, strokePaint)
                        }
                    }

                    // Smooth Fade Vignette Gradient at Border with Unexplored Fog
                    if (showFogOfWar && key in exploredSet && mapSettings.smoothFadeEnabled) {
                        val cellW = right - left
                        val cellH = bottom - top
                        val edgeFadePx = (14f * density).coerceAtMost(cellW * 0.35f)

                        // Check 4 neighbors for fog
                        val hasLeftFog = "${cx - 1}:$cy" !in exploredSet
                        val hasRightFog = "${cx + 1}:$cy" !in exploredSet
                        val hasTopFog = "$cx:${cy + 1}" !in exploredSet
                        val hasBottomFog = "$cx:${cy - 1}" !in exploredSet

                        if (hasLeftFog) {
                            val fadeColorFrom = baseFogColor.copy(alpha = baseFogColor.alpha * 0.65f).toArgb()
                            val fadeColorTo = baseFogColor.copy(alpha = 0f).toArgb()
                            val shader = android.graphics.LinearGradient(
                                left, top, left + edgeFadePx, top,
                                fadeColorFrom, fadeColorTo, android.graphics.Shader.TileMode.CLAMP
                            )
                            fogPaint.shader = shader
                            canvas.drawRect(left, top, left + edgeFadePx, bottom, fogPaint)
                            fogPaint.shader = null
                        }
                        if (hasRightFog) {
                            val fadeColorFrom = baseFogColor.copy(alpha = baseFogColor.alpha * 0.65f).toArgb()
                            val fadeColorTo = baseFogColor.copy(alpha = 0f).toArgb()
                            val shader = android.graphics.LinearGradient(
                                right, top, right - edgeFadePx, top,
                                fadeColorFrom, fadeColorTo, android.graphics.Shader.TileMode.CLAMP
                            )
                            fogPaint.shader = shader
                            canvas.drawRect(right - edgeFadePx, top, right, bottom, fogPaint)
                            fogPaint.shader = null
                        }
                        if (hasTopFog) {
                            val fadeColorFrom = baseFogColor.copy(alpha = baseFogColor.alpha * 0.65f).toArgb()
                            val fadeColorTo = baseFogColor.copy(alpha = 0f).toArgb()
                            val shader = android.graphics.LinearGradient(
                                left, top, left, top + edgeFadePx,
                                fadeColorFrom, fadeColorTo, android.graphics.Shader.TileMode.CLAMP
                            )
                            fogPaint.shader = shader
                            canvas.drawRect(left, top, right, top + edgeFadePx, fogPaint)
                            fogPaint.shader = null
                        }
                        if (hasBottomFog) {
                            val fadeColorFrom = baseFogColor.copy(alpha = baseFogColor.alpha * 0.65f).toArgb()
                            val fadeColorTo = baseFogColor.copy(alpha = 0f).toArgb()
                            val shader = android.graphics.LinearGradient(
                                left, bottom, left, bottom - edgeFadePx,
                                fadeColorFrom, fadeColorTo, android.graphics.Shader.TileMode.CLAMP
                            )
                            fogPaint.shader = shader
                            canvas.drawRect(left, bottom - edgeFadePx, right, bottom, fogPaint)
                            fogPaint.shader = null
                        }
                    }

                    if (mapSettings.showGridLines) {
                        canvas.drawRect(left, top, right, bottom, gridPaint)
                        if (Math.floorMod(cx, GeoGrid.DISTRICT_SPAN) == 0) {
                            canvas.drawLine(left, top, left, bottom, districtGridPaint)
                        }
                        if (Math.floorMod(cy, GeoGrid.DISTRICT_SPAN) == 0) {
                            canvas.drawLine(left, bottom, right, bottom, districtGridPaint)
                        }
                    }

                    if (mapSettings.showSectorLabels && chunkCols <= 20 && chunkRows <= 20) {
                        val cellW = right - left
                        val cellH = bottom - top
                        if (cellW > 52f * density && cellH > 24f * density) {
                            val label = "S ${Math.floorMod(cx, 100)}:${Math.floorMod(cy, 100)}"
                            canvas.drawText(label, left + 6f * density, top + 14f * density, sectorLabelPaint)
                        }
                    }
                }
            }
        } else if (showFogOfWar) {
            val overviewAlpha = if (mapSettings.easyModeEnabled) 0.34f else 0.85f
            fogPaint.color = palette.fog.copy(alpha = overviewAlpha).toArgb()
            val span = maxOf(mapView.width, mapView.height).toFloat()
            canvas.drawRect(-span, -span, span * 2f, span * 2f, fogPaint)
        }

        // 2. Highlighted Saved Route
        if (highlightedRoutePoints.size >= 2) {
            drawProjectedRoute(
                canvas = canvas,
                mapView = mapView,
                points = highlightedRoutePoints,
                color = (highlightedRouteColor ?: palette.route).toArgb(),
                strokePx = 5.5f * density
            )
        }

        // 2b. Automatic Walking Guidance Line (pedestrian dotted route + target ring)
        if (walkingRoutePoints.size >= 2) {
            drawWalkingGuidanceRoute(
                canvas = canvas,
                mapView = mapView,
                points = walkingRoutePoints,
                targetPoint = walkingTargetPoint
            )
        }

        // 3. Live Active Route Recording Polyline
        if (activeRoutePoints.size >= 2) {
            drawProjectedRoute(
                canvas = canvas,
                mapView = mapView,
                points = activeRoutePoints,
                color = palette.route.toArgb(),
                strokePx = 6.5f * density
            )
        }

        // 4. Custom Map Markers with Selected MarkerStyle & Centered Google Material Icons (upright during camera rotation)
        for (marker in markers) {
            proj.toPixels(GeoPoint(marker.latitude, marker.longitude), tmpPt1)
            val mx = tmpPt1.x.toFloat()
            val my = tmpPt1.y.toFloat()
            if (mx < -150f || mx > mapView.width + 150f || my < -150f || my > mapView.height + 150f) continue

            val cat = MarkerCategory.entries.firstOrNull { it.name == marker.categoryName } ?: MarkerCategory.LANDMARK
            val effectiveIconName = marker.iconName.ifBlank { cat.defaultIconName }
            val markerComposeColor = Color(marker.colorArgb.toInt())
            val markerColorInt = markerComposeColor.toArgb()

            canvas.save()
            if (mapBearing != 0f) {
                canvas.rotate(-mapBearing, mx, my)
            }

            val labelOffsetRadius: Float
            when (mapSettings.markerStyle) {
                MarkerStyle.ICON_ONLY -> {
                    val iconPx = (28f * density).roundToInt().coerceAtLeast(32)
                    val iconBitmap = GoogleIconRegistry.renderStyledIconBitmap(
                        iconName = effectiveIconName,
                        sizePx = iconPx,
                        tintColor = markerColorInt,
                        outlineColor = null
                    )
                    canvas.drawBitmap(
                        iconBitmap,
                        mx - iconBitmap.width / 2f,
                        my - iconBitmap.height / 2f,
                        iconBitmapPaint
                    )
                    labelOffsetRadius = iconBitmap.height / 2f
                }

                MarkerStyle.ICON_OUTLINE -> {
                    val iconPx = (30f * density).roundToInt().coerceAtLeast(34)
                    val outlineColorInt = ColorPickerMath.adaptiveOutlineColor(markerComposeColor).toArgb()
                    val iconBitmap = GoogleIconRegistry.renderStyledIconBitmap(
                        iconName = effectiveIconName,
                        sizePx = iconPx,
                        tintColor = markerColorInt,
                        outlineColor = outlineColorInt,
                        outlineWidthPx = 3.8f * density
                    )
                    canvas.drawBitmap(
                        iconBitmap,
                        mx - iconBitmap.width / 2f,
                        my - iconBitmap.height / 2f,
                        iconBitmapPaint
                    )
                    labelOffsetRadius = iconBitmap.height / 2f
                }

                MarkerStyle.CIRCLE_ICON -> {
                    val radius = 17f * density
                    val outlineColorInt = ColorPickerMath.adaptiveOutlineColor(markerComposeColor).toArgb()
                    val contentTintInt = ColorPickerMath.contrastTextColor(markerComposeColor).toArgb()
                    fillPaint.color = outlineColorInt
                    canvas.drawCircle(mx, my, radius + 2.5f * density, fillPaint)
                    fillPaint.color = markerColorInt
                    canvas.drawCircle(mx, my, radius, fillPaint)

                    val iconPx = (20f * density).roundToInt().coerceAtLeast(24)
                    val iconBitmap = GoogleIconRegistry.renderStyledIconBitmap(
                        iconName = effectiveIconName,
                        sizePx = iconPx,
                        tintColor = contentTintInt,
                        outlineColor = null
                    )
                    canvas.drawBitmap(
                        iconBitmap,
                        mx - iconBitmap.width / 2f,
                        my - iconBitmap.height / 2f,
                        iconBitmapPaint
                    )
                    labelOffsetRadius = radius
                }
            }

            val shouldShowMarkerLabel = (!mapSettings.disableCreatedMarkerLabels && mapSettings.showMarkerLabels) ||
                (mapSettings.disableCreatedMarkerLabels && marker.id == revealedMarkerId)
            if (shouldShowMarkerLabel) {
                val textWidth = labelTextPaint.measureText(marker.title)
                val padX = 7f * density
                val padY = 3.5f * density
                val labelTop = my + labelOffsetRadius + 5f * density
                labelBgRect.set(
                    mx - textWidth / 2f - padX,
                    labelTop,
                    mx + textWidth / 2f + padX,
                    labelTop + 14f * density + padY
                )
                fillPaint.color = if (isDarkTheme) 0xFF2A2E35.toInt() else android.graphics.Color.WHITE
                canvas.drawRoundRect(labelBgRect, 8f * density, 8f * density, fillPaint)
                labelTextPaint.color = if (isDarkTheme) android.graphics.Color.WHITE else 0xFF1A1C1A.toInt()
                canvas.drawText(marker.title, mx, labelTop + 12f * density, labelTextPaint)
            }

            canvas.restore()
        }

        // 5. Searched Location Beacon + Optional Search Label (upright during camera rotation)
        val searchTarget = searchedPlace
        if (searchTarget != null) {
            proj.toPixels(GeoPoint(searchTarget.latitude, searchTarget.longitude), tmpPt1)
            val sx = tmpPt1.x.toFloat()
            val sy = tmpPt1.y.toFloat()

            canvas.save()
            if (mapBearing != 0f) {
                canvas.rotate(-mapBearing, sx, sy)
            }

            val ringRadius = (16f + pulseProgress * 18f) * density
            fillPaint.color = 0xFFE11D48.toInt()
            fillPaint.alpha = ((1f - pulseProgress) * 90f).toInt()
            canvas.drawCircle(sx, sy, ringRadius, fillPaint)
            fillPaint.alpha = 255

            val pinRadius = 15f * density
            fillPaint.color = android.graphics.Color.WHITE
            canvas.drawCircle(sx, sy, pinRadius + 3f * density, fillPaint)
            fillPaint.color = 0xFFE11D48.toInt()
            canvas.drawCircle(sx, sy, pinRadius, fillPaint)

            val searchIconBmp = GoogleIconRegistry.renderCenteredIconBitmap(
                searchTarget.markerCategory.defaultIconName,
                (18f * density).roundToInt().coerceAtLeast(22)
            )
            canvas.drawBitmap(
                searchIconBmp,
                sx - searchIconBmp.width / 2f,
                sy - searchIconBmp.height / 2f,
                iconBitmapPaint
            )

            if (mapSettings.showSearchLabels) {
                val shortTitle = if (searchTarget.title.length > 26) {
                    searchTarget.title.take(25) + "…"
                } else {
                    searchTarget.title
                }
                val textWidth = labelTextPaint.measureText(shortTitle)
                val padX = 8f * density
                val padY = 4f * density
                val labelTop = sy + pinRadius + 6f * density
                labelBgRect.set(
                    sx - textWidth / 2f - padX,
                    labelTop,
                    sx + textWidth / 2f + padX,
                    labelTop + 14f * density + padY
                )
                fillPaint.color = 0xFFE11D48.toInt()
                canvas.drawRoundRect(labelBgRect, 10f * density, 10f * density, fillPaint)
                labelTextPaint.color = android.graphics.Color.WHITE
                canvas.drawText(shortTitle, sx, labelTop + 12f * density, labelTextPaint)
            }

            canvas.restore()
        }

        // 6. Live User GPS Puck + Direction Arrow
        if (telemetry.hasFix) {
            proj.toPixels(GeoPoint(telemetry.latitude, telemetry.longitude), tmpPt1)
            val ux = tmpPt1.x.toFloat()
            val uy = tmpPt1.y.toFloat()

            val pulseRadius = (10f + pulseProgress * 22f) * density
            fillPaint.color = palette.route.copy(alpha = (1f - pulseProgress) * 0.35f).toArgb()
            canvas.drawCircle(ux, uy, pulseRadius, fillPaint)

            val heading = telemetry.headingDegrees
            if (heading != null && !heading.isNaN()) {
                canvas.save()
                canvas.rotate(heading, ux, uy)

                // Soft directional field-of-view beam cone
                val coneRadius = 29f * density
                directionConeRect.set(
                    ux - coneRadius,
                    uy - coneRadius,
                    ux + coneRadius,
                    uy + coneRadius
                )
                fillPaint.shader = android.graphics.RadialGradient(
                    ux,
                    uy,
                    coneRadius,
                    palette.route.copy(alpha = 0.42f).toArgb(),
                    palette.route.copy(alpha = 0f).toArgb(),
                    android.graphics.Shader.TileMode.CLAMP
                )
                canvas.drawArc(directionConeRect, -118f, 56f, true, fillPaint)
                fillPaint.shader = null

                // Crisp navigation chevron arrow ahead of the position dot (+3dp gap)
                val tipY = uy - 22.4f * density
                val wingY = uy - 13.2f * density
                val notchY = uy - 15.6f * density
                val halfW = 5.9f * density
                reusablePath.reset()
                reusablePath.moveTo(ux, tipY)
                reusablePath.lineTo(ux + halfW, wingY)
                reusablePath.lineTo(ux, notchY)
                reusablePath.lineTo(ux - halfW, wingY)
                reusablePath.close()

                if (mapSettings.showLocationOutline) {
                    strokePaint.color = android.graphics.Color.WHITE
                    strokePaint.strokeWidth = 2.6f * density
                    strokePaint.strokeJoin = Paint.Join.ROUND
                    canvas.drawPath(reusablePath, strokePaint)
                }

                fillPaint.color = palette.route.toArgb()
                canvas.drawPath(reusablePath, fillPaint)

                canvas.restore()
            }

            if (mapSettings.showLocationOutline) {
                fillPaint.color = android.graphics.Color.WHITE
                canvas.drawCircle(ux, uy, 10f * density, fillPaint)
            }

            fillPaint.color = palette.route.toArgb()
            canvas.drawCircle(ux, uy, 6.8f * density, fillPaint)
        }
    }

    private fun drawProjectedRoute(
        canvas: AndroidCanvas,
        mapView: MapView,
        points: List<LatLonPoint>,
        color: Int,
        strokePx: Float
    ) {
        val proj = mapView.projection
        reusablePath.reset()
        points.forEachIndexed { idx, pt ->
            proj.toPixels(GeoPoint(pt.latitude, pt.longitude), tmpPt1)
            if (idx == 0) reusablePath.moveTo(tmpPt1.x.toFloat(), tmpPt1.y.toFloat())
            else reusablePath.lineTo(tmpPt1.x.toFloat(), tmpPt1.y.toFloat())
        }

        routeCasingPaint.color = if (isDarkTheme) 0xCC111317.toInt() else android.graphics.Color.WHITE
        routeCasingPaint.strokeWidth = strokePx + 3.5f * density
        canvas.drawPath(reusablePath, routeCasingPaint)

        routePaint.color = color
        routePaint.strokeWidth = strokePx
        canvas.drawPath(reusablePath, routePaint)

        proj.toPixels(GeoPoint(points.first().latitude, points.first().longitude), tmpPt1)
        fillPaint.color = android.graphics.Color.WHITE
        canvas.drawCircle(tmpPt1.x.toFloat(), tmpPt1.y.toFloat(), strokePx * 0.9f, fillPaint)
        fillPaint.color = color
        canvas.drawCircle(tmpPt1.x.toFloat(), tmpPt1.y.toFloat(), strokePx * 0.55f, fillPaint)

        proj.toPixels(GeoPoint(points.last().latitude, points.last().longitude), tmpPt2)
        fillPaint.color = android.graphics.Color.WHITE
        canvas.drawCircle(tmpPt2.x.toFloat(), tmpPt2.y.toFloat(), strokePx * 0.9f, fillPaint)
        fillPaint.color = color
        canvas.drawCircle(tmpPt2.x.toFloat(), tmpPt2.y.toFloat(), strokePx * 0.55f, fillPaint)
    }

    private fun drawWalkingGuidanceRoute(
        canvas: AndroidCanvas,
        mapView: MapView,
        points: List<LatLonPoint>,
        targetPoint: LatLonPoint?
    ) {
        val proj = mapView.projection
        reusablePath.reset()
        points.forEachIndexed { idx, pt ->
            proj.toPixels(GeoPoint(pt.latitude, pt.longitude), tmpPt1)
            if (idx == 0) reusablePath.moveTo(tmpPt1.x.toFloat(), tmpPt1.y.toFloat())
            else reusablePath.lineTo(tmpPt1.x.toFloat(), tmpPt1.y.toFloat())
        }

        val walkColor = 0xFF0EA5E9.toInt() // Crisp navigation sky-blue dots
        walkCasingPaint.color = if (isDarkTheme) 0xE60F172A.toInt() else android.graphics.Color.WHITE
        walkCasingPaint.strokeWidth = 9.5f * density
        canvas.drawPath(reusablePath, walkCasingPaint)

        walkStrokePaint.color = walkColor
        walkStrokePaint.strokeWidth = 6f * density
        canvas.drawPath(reusablePath, walkStrokePaint)

        val endPt = targetPoint ?: points.last()
        proj.toPixels(GeoPoint(endPt.latitude, endPt.longitude), tmpPt2)
        val dx = tmpPt2.x.toFloat()
        val dy = tmpPt2.y.toFloat()

        fillPaint.color = Color(0xFF0EA5E9).copy(alpha = (1f - pulseProgress) * 0.35f).toArgb()
        canvas.drawCircle(dx, dy, (10f + pulseProgress * 16f) * density, fillPaint)

        fillPaint.color = android.graphics.Color.WHITE
        canvas.drawCircle(dx, dy, 8f * density, fillPaint)
        fillPaint.color = walkColor
        canvas.drawCircle(dx, dy, 5f * density, fillPaint)
    }
}

@Composable
fun MiniMapPalettePreview(
    palette: MapPalette,
    showGridLines: Boolean,
    modifier: Modifier = Modifier,
    routePoints: List<LatLonPoint> = emptyList(),
    showStreetLabels: Boolean = false,
    showMarkerLabels: Boolean = false,
    showSectorLabels: Boolean = false,
    isDarkTheme: Boolean = false,
    markerStyle: MarkerStyle = MarkerStyle.CIRCLE_ICON
) {
    val density = LocalDensity.current.density
    val textPaint = remember(isDarkTheme, density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * density
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = if (isDarkTheme) 0xFFE2E8F0.toInt() else 0xFF334155.toInt()
        }
    }
    val sectorPaint = remember(isDarkTheme, density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f * density
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
            color = if (isDarkTheme) 0xFF94A3B8.toInt() else 0xFF475569.toInt()
        }
    }

    Canvas(modifier = modifier.background(palette.land)) {
        val w = size.width
        val h = size.height
        val cols = 5
        val rows = 3
        val cellW = w / cols
        val cellH = h / rows

        val riverPath = Path().apply {
            moveTo(0f, h * 0.72f)
            cubicTo(w * 0.3f, h * 0.5f, w * 0.65f, h * 0.88f, w, h * 0.35f)
        }
        drawPath(
            path = riverPath,
            color = palette.water,
            style = Stroke(width = cellH * 0.3f, cap = StrokeCap.Round)
        )

        for (c in 1 until cols) {
            drawLine(
                color = palette.road,
                start = Offset(c * cellW, 0f),
                end = Offset(c * cellW, h),
                strokeWidth = if (c == 2) 10f else 5f
            )
        }
        for (r in 1 until rows) {
            drawLine(
                color = palette.road,
                start = Offset(0f, r * cellH),
                end = Offset(w, r * cellH),
                strokeWidth = if (r == 1) 9f else 5f
            )
        }

        if (showStreetLabels) {
            drawContext.canvas.nativeCanvas.drawText(
                "OAKLAND AVE",
                w * 0.62f,
                cellH - 6f * density,
                textPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                "PARK ST",
                cellW * 2f + 28f * density,
                h * 0.62f,
                textPaint
            )
        }

        val routePath = Path()
        if (routePoints.size >= 2) {
            val minLat = routePoints.minOf { it.latitude }
            val maxLat = routePoints.maxOf { it.latitude }
            val minLon = routePoints.minOf { it.longitude }
            val maxLon = routePoints.maxOf { it.longitude }
            val latSpan = (maxLat - minLat).coerceAtLeast(0.0002)
            val lonSpan = (maxLon - minLon).coerceAtLeast(0.0002)
            val pad = 28f
            routePoints.forEachIndexed { idx, pt ->
                val x = pad + ((pt.longitude - minLon) / lonSpan).toFloat() * (w - pad * 2f)
                val y = (h - pad) - ((pt.latitude - minLat) / latSpan).toFloat() * (h - pad * 2f)
                if (idx == 0) routePath.moveTo(x, y) else routePath.lineTo(x, y)
            }
        } else {
            routePath.apply {
                moveTo(w * 0.16f, h * 0.75f)
                lineTo(w * 0.40f, h * 0.75f)
                lineTo(w * 0.40f, h * 0.34f)
                lineTo(w * 0.72f, h * 0.34f)
            }
        }
        drawPath(
            path = routePath,
            color = if (isDarkTheme) Color(0xCC111317) else Color.White,
            style = Stroke(width = 13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = routePath,
            color = palette.route,
            style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        if (routePoints.isEmpty()) {
            // Sample pin on preview reflecting active MarkerStyle
            val px = w * 0.72f
            val py = h * 0.34f
            val sampleColor = Color(0xFF16A34A)
            val sampleColorInt = sampleColor.toArgb()
            val sampleOutline = ColorPickerMath.adaptiveOutlineColor(sampleColor)
            val sampleContentTint = ColorPickerMath.contrastTextColor(sampleColor).toArgb()
            val previewBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            when (markerStyle) {
                MarkerStyle.ICON_ONLY -> {
                    val bmp = GoogleIconRegistry.renderStyledIconBitmap(
                        iconName = "Park",
                        sizePx = (22f * density).roundToInt().coerceAtLeast(24),
                        tintColor = sampleColorInt,
                        outlineColor = null
                    )
                    drawContext.canvas.nativeCanvas.drawBitmap(
                        bmp,
                        px - bmp.width / 2f,
                        py - bmp.height / 2f,
                        previewBitmapPaint
                    )
                }
                MarkerStyle.ICON_OUTLINE -> {
                    val bmp = GoogleIconRegistry.renderStyledIconBitmap(
                        iconName = "Park",
                        sizePx = (24f * density).roundToInt().coerceAtLeast(26),
                        tintColor = sampleColorInt,
                        outlineColor = sampleOutline.toArgb(),
                        outlineWidthPx = 3f * density
                    )
                    drawContext.canvas.nativeCanvas.drawBitmap(
                        bmp,
                        px - bmp.width / 2f,
                        py - bmp.height / 2f,
                        previewBitmapPaint
                    )
                }
                MarkerStyle.CIRCLE_ICON -> {
                    drawCircle(color = sampleOutline, radius = 11.5f * density, center = Offset(px, py))
                    drawCircle(color = sampleColor, radius = 9.5f * density, center = Offset(px, py))
                    val bmp = GoogleIconRegistry.renderStyledIconBitmap(
                        iconName = "Park",
                        sizePx = (13f * density).roundToInt().coerceAtLeast(16),
                        tintColor = sampleContentTint,
                        outlineColor = null
                    )
                    drawContext.canvas.nativeCanvas.drawBitmap(
                        bmp,
                        px - bmp.width / 2f,
                        py - bmp.height / 2f,
                        previewBitmapPaint
                    )
                }
            }
            if (showMarkerLabels) {
                drawContext.canvas.nativeCanvas.drawText(
                    "Park",
                    px,
                    py + 22f * density,
                    textPaint
                )
            }

            val fogCells = listOf(0 to 0, 4 to 0, 4 to 2, 0 to 2)
            fogCells.forEach { (c, r) ->
                drawRect(
                    color = palette.fog,
                    topLeft = Offset(c * cellW, r * cellH),
                    size = Size(cellW + 1f, cellH + 1f)
                )
            }
        }

        if (showGridLines || (showSectorLabels && routePoints.isEmpty())) {
            for (c in 0 until cols) {
                for (r in 0 until rows) {
                    if (showGridLines) {
                        drawRect(
                            color = palette.gridStroke,
                            topLeft = Offset(c * cellW, r * cellH),
                            size = Size(cellW, cellH),
                            style = Stroke(width = 1.5f)
                        )
                    }
                    if (showSectorLabels && routePoints.isEmpty()) {
                        drawContext.canvas.nativeCanvas.drawText(
                            "S $c:$r",
                            c * cellW + 5f * density,
                            r * cellH + 12f * density,
                            sectorPaint
                        )
                    }
                }
            }
        }
    }
}
