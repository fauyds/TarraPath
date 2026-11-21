package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.ApiProvider
import com.example.data.model.MapDisplaySettings
import com.example.data.model.MarkerStyle
import com.example.data.model.RoutingMode
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "terrapath_prefs")

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CUSTOM_ACCENT_COLOR_ARGB = longPreferencesKey("custom_accent_color_argb")
        val API_KEY = stringPreferencesKey("custom_api_key")
        val API_PROVIDER = stringPreferencesKey("custom_api_provider")
        val STEP_COUNTER_BASELINE = longPreferencesKey("step_counter_baseline")
        val HIDDEN_MARKER_CATEGORIES = stringSetPreferencesKey("hidden_marker_categories")
        val EASY_MODE_ENABLED = booleanPreferencesKey("easy_mode_enabled")
        val SHOW_GRID_LINES = booleanPreferencesKey("show_grid_lines")
        val SHOW_STREET_NAMES = booleanPreferencesKey("show_street_names")
        val SHOW_MARKER_LABELS = booleanPreferencesKey("show_marker_labels")
        val SHOW_SEARCH_LABELS = booleanPreferencesKey("show_search_labels")
        val SHOW_SECTOR_LABELS = booleanPreferencesKey("show_sector_labels")
        val SHOW_SMOOTH_FADE = booleanPreferencesKey("show_smooth_fade")
        val DISABLE_CREATED_MARKER_LABELS = booleanPreferencesKey("disable_created_marker_labels")
        val UI_BLUR_ENABLED = booleanPreferencesKey("ui_blur_enabled")
        val UI_TRANSPARENCY = floatPreferencesKey("ui_transparency")
        val UI_BLUR_RADIUS_DP = floatPreferencesKey("ui_blur_radius_dp")
        val SHOW_LOCATION_OUTLINE = booleanPreferencesKey("show_location_outline")
        val MARKER_STYLE = stringPreferencesKey("marker_style")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM.name
        ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeMode.SYSTEM
    }

    val stepCounterBaseline: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[Keys.STEP_COUNTER_BASELINE]
    }

    val hiddenMarkerCategories: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.HIDDEN_MARKER_CATEGORIES] ?: emptySet()
    }

    val mapDisplaySettings: Flow<MapDisplaySettings> = context.dataStore.data.map { prefs ->
        val rawMarkerStyle = prefs[Keys.MARKER_STYLE] ?: MarkerStyle.CIRCLE_ICON.name
        val parsedMarkerStyle = MarkerStyle.entries.firstOrNull { it.name == rawMarkerStyle }
            ?: MarkerStyle.CIRCLE_ICON
        val rawRoutingMode = prefs[Keys.ROUTING_MODE] ?: RoutingMode.ONLINE.name
        val parsedRoutingMode = RoutingMode.entries.firstOrNull { it.name == rawRoutingMode }
            ?: RoutingMode.ONLINE
        MapDisplaySettings(
            easyModeEnabled = prefs[Keys.EASY_MODE_ENABLED] ?: false,
            showGridLines = prefs[Keys.SHOW_GRID_LINES] ?: true,
            showStreetNames = prefs[Keys.SHOW_STREET_NAMES] ?: true,
            showMarkerLabels = prefs[Keys.SHOW_MARKER_LABELS] ?: true,
            disableCreatedMarkerLabels = prefs[Keys.DISABLE_CREATED_MARKER_LABELS] ?: false,
            showSearchLabels = prefs[Keys.SHOW_SEARCH_LABELS] ?: true,
            showSectorLabels = prefs[Keys.SHOW_SECTOR_LABELS] ?: false,
            smoothFadeEnabled = prefs[Keys.SHOW_SMOOTH_FADE] ?: true,
            uiBlurEnabled = prefs[Keys.UI_BLUR_ENABLED] ?: false,
            uiTransparency = (prefs[Keys.UI_TRANSPARENCY] ?: 0.25f).coerceIn(0f, 0.85f),
            uiBlurRadiusDp = (prefs[Keys.UI_BLUR_RADIUS_DP] ?: 20f).coerceIn(0f, 40f),
            showLocationOutline = prefs[Keys.SHOW_LOCATION_OUTLINE] ?: true,
            markerStyle = parsedMarkerStyle,
            routingMode = parsedRoutingMode
        )
    }

    val customAccentColorArgb: Flow<Long?> = context.dataStore.data.map { prefs ->
        prefs[Keys.CUSTOM_ACCENT_COLOR_ARGB]
    }

    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.API_KEY] ?: ""
    }

    val apiProvider: Flow<ApiProvider> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.API_PROVIDER] ?: ApiProvider.CUSTOM_GENERAL.name
        ApiProvider.entries.firstOrNull { it.name == raw } ?: ApiProvider.CUSTOM_GENERAL
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setCustomAccentColor(colorArgb: Long?) {
        context.dataStore.edit { prefs ->
            if (colorArgb == null) {
                prefs.remove(Keys.CUSTOM_ACCENT_COLOR_ARGB)
            } else {
                prefs[Keys.CUSTOM_ACCENT_COLOR_ARGB] = colorArgb
            }
        }
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { prefs ->
            val trimmed = key.trim()
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.API_KEY)
            } else {
                prefs[Keys.API_KEY] = trimmed
            }
        }
    }

    suspend fun setApiProvider(provider: ApiProvider) {
        context.dataStore.edit { it[Keys.API_PROVIDER] = provider.name }
    }

    suspend fun setStepCounterBaseline(baseline: Long) {
        context.dataStore.edit { it[Keys.STEP_COUNTER_BASELINE] = baseline }
    }

    suspend fun toggleMarkerCategoryVisibility(categoryName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.HIDDEN_MARKER_CATEGORIES] ?: emptySet()
            prefs[Keys.HIDDEN_MARKER_CATEGORIES] = if (categoryName in current) {
                current - categoryName
            } else {
                current + categoryName
            }
        }
    }

    suspend fun showAllMarkerCategories() {
        context.dataStore.edit { it[Keys.HIDDEN_MARKER_CATEGORIES] = emptySet() }
    }

    suspend fun setEasyModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.EASY_MODE_ENABLED] = enabled }
    }

    suspend fun setShowGridLines(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_GRID_LINES] = show }
    }

    suspend fun setShowStreetNames(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_STREET_NAMES] = show }
    }

    suspend fun setShowMarkerLabels(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_MARKER_LABELS] = show }
    }

    suspend fun setShowSearchLabels(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_SEARCH_LABELS] = show }
    }

    suspend fun setShowSectorLabels(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_SECTOR_LABELS] = show }
    }

    suspend fun setSmoothFadeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_SMOOTH_FADE] = enabled }
    }

    suspend fun setDisableCreatedMarkerLabels(disabled: Boolean) {
        context.dataStore.edit { it[Keys.DISABLE_CREATED_MARKER_LABELS] = disabled }
    }

    suspend fun setUiBlurEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.UI_BLUR_ENABLED] = enabled }
    }

    suspend fun setUiTransparency(transparency: Float) {
        context.dataStore.edit { it[Keys.UI_TRANSPARENCY] = transparency.coerceIn(0f, 0.85f) }
    }

    suspend fun setUiBlurRadiusDp(radiusDp: Float) {
        context.dataStore.edit { it[Keys.UI_BLUR_RADIUS_DP] = radiusDp.coerceIn(0f, 40f) }
    }

    suspend fun setShowLocationOutline(show: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_LOCATION_OUTLINE] = show }
    }

    suspend fun setMarkerStyle(style: MarkerStyle) {
        context.dataStore.edit { it[Keys.MARKER_STYLE] = style.name }
    }

    suspend fun setRoutingMode(mode: RoutingMode) {
        context.dataStore.edit { it[Keys.ROUTING_MODE] = mode.name }
    }
}
