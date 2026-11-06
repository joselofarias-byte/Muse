package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import io.zer0.muse.ui.theme.CustomTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/**
 * P2-2 Dividido: Apariencia/Subrepositorio de temas.
 *
 * Compartido con el repositorio de configuración `muse_settings` DataStore archivos,
 * Solo incluye modo de tema, identificación de tema, tema oscuro, cambio programado, selección dinámica de color y temas personalizados.
 * El SettingsRepository posterior se delegará gradualmente a esta clase y, finalmente, solo se conservará la fachada de agregación.
 */
/** Derivar la deduplicación del flujo de configuración:DataStore.data Elimine únicamente los duplicados de todas las Preferencias.map Luego, cada uno se deduplica para suprimir la emisión repetida del mismo valor provocada por claves irrelevantes. */
private fun <T> Flow<Preferences>.mapDistinct(transform: suspend (Preferences) -> T): Flow<T> = map(transform).distinctUntilChanged()

class AppearanceSettingsStore(private val context: Context) {

    private val store get() = context.museSettingsDataStore

    val themeModeFlow: Flow<String> = store.data.mapDistinct { prefs -> prefs[KEY_THEME_MODE] ?: "system" }
    val themeIdFlow: Flow<String> = store.data.mapDistinct { prefs -> prefs[KEY_THEME_ID] ?: "mono" }
    val darkThemeIdFlow: Flow<String> = store.data.mapDistinct { prefs -> prefs[KEY_DARK_THEME_ID] ?: "" }
    val themeScheduleFlow: Flow<ThemeScheduleConfig> = store.data.mapDistinct { prefs ->
        decodePrefsOrNull(prefs[KEY_THEME_SCHEDULE], ThemeScheduleConfig.serializer(), "ThemeSchedule") ?: ThemeScheduleConfig()
    }
    val dynamicColorFlow: Flow<Boolean> = store.data.mapDistinct { prefs -> prefs[KEY_DYNAMIC_COLOR] ?: false }
    val fontSizeScaleFlow: Flow<String> = store.data.mapDistinct { prefs -> prefs[KEY_FONT_SIZE_SCALE] ?: "medium" }
    val defaultHomePageFlow: Flow<Int> = store.data.mapDistinct { prefs -> prefs[KEY_DEFAULT_HOME_PAGE] ?: 0 }
    val onboardingShownFlow: Flow<Boolean> = store.data.mapDistinct { prefs -> prefs[KEY_ONBOARDING_SHOWN] == true }
    val asrTipShownFlow: Flow<Boolean> = store.data.mapDistinct { prefs -> prefs[KEY_ASR_TIP_SHOWN] ?: false }
    val customThemesFlow: Flow<List<CustomTheme>> = store.data.mapDistinct { prefs ->
        decodePrefsOrNull(prefs[KEY_CUSTOM_THEMES], ListSerializer(CustomTheme.serializer()), "CustomThemes") ?: emptyList()
    }

    /** E2: Ruta absoluta al archivo de fuente de texto personalizado(filesDir/fonts/ abajo);null Indica el uso de la fuente predeterminada del sistema. */
    val customFontPathFlow: Flow<String?> = store.data.mapDistinct { prefs -> prefs[KEY_CUSTOM_FONT_PATH] }

    /** H5: Cambio de tema de alto contraste — Mejorar las perspectivas/contraste de fondo,Para usuarios con baja visión(a11y Estrategia). */
    val highContrastFlow: Flow<Boolean> = store.data.mapDistinct { prefs -> prefs[KEY_HIGH_CONTRAST] ?: false }

    /**
     * v2.5.5: modo de vidrio líquido — off/water(vaso de agua)/frost(vidrio esmerilado).
     * Escarcha predeterminada (vidrio esmerilado)—— Los nuevos usuarios obtendrán este valor predeterminado para su primera instalación y las actualizaciones de los usuarios antiguos (que no se han configurado activamente).
     * Compatibilidad de valores clave booleanos antiguos: true→frost, false→off.
     * Nota:Sólo si la clave es nula (nunca se ha escrito) se utilizará. Si el usuario lo desactiva explícitamente, no se sobrescribirá.
     */
    val liquidGlassModeFlow: Flow<String> = store.data.mapDistinct { prefs ->
        when (val legacy = prefs[KEY_LIQUID_GLASS]) {
            null -> prefs[KEY_LIQUID_GLASS_MODE] ?: "frost"
            else -> if (legacy) "frost" else "off"
        }
    }

    /**
     * v2.5.5: resistencia del vidrio(0..100 Porcentaje de números enteros). El valor predeterminado es 55 (correspondiente al rango "medio" de la página de configuración).
     * Migración de la antigua tercera marcha.: bajo→25 en→50 alto→85.
     */
    val liquidGlassStrengthFlow: Flow<Int> = store.data.mapDistinct { prefs ->
        when (val legacy = prefs[KEY_LIQUID_GLASS_LEVEL]) {
            null -> prefs[KEY_LIQUID_GLASS_STRENGTH] ?: 55
            else -> when (legacy) {
                0 -> 25
                2 -> 85
                else -> 50
            }
        }
    }

    suspend fun saveThemeMode(mode: String) {
        store.edit { it[KEY_THEME_MODE] = mode }
    }
    suspend fun saveThemeId(id: String) {
        store.edit { it[KEY_THEME_ID] = id }
    }
    suspend fun saveDarkThemeId(id: String) {
        store.edit { it[KEY_DARK_THEME_ID] = id }
    }
    suspend fun saveThemeSchedule(config: ThemeScheduleConfig) {
        store.edit { it[KEY_THEME_SCHEDULE] = AppJson.encodeToString(ThemeScheduleConfig.serializer(), config) }
    }
    suspend fun saveDynamicColor(enabled: Boolean) {
        store.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }
    suspend fun saveFontSizeScale(scale: String) {
        store.edit { it[KEY_FONT_SIZE_SCALE] = scale }
    }
    suspend fun saveDefaultHomePage(page: Int) {
        store.edit { it[KEY_DEFAULT_HOME_PAGE] = page.coerceIn(0, 2) }
    }
    suspend fun markOnboardingShown() {
        store.edit { it[KEY_ONBOARDING_SHOWN] = true }
    }
    suspend fun saveAsrTipShown(shown: Boolean) {
        store.edit { it[KEY_ASR_TIP_SHOWN] = shown }
    }
    suspend fun saveCustomThemes(themes: List<CustomTheme>) {
        store.edit { it[KEY_CUSTOM_THEMES] = AppJson.encodeToString(ListSerializer(CustomTheme.serializer()), themes) }
    }
    suspend fun saveCustomFontPath(path: String?) {
        store.edit { prefs ->
            if (path == null) prefs.remove(KEY_CUSTOM_FONT_PATH) else prefs[KEY_CUSTOM_FONT_PATH] = path
        }
    }

    /** H5: Guarde el cambio de tema de alto contraste. */
    suspend fun saveHighContrast(enabled: Boolean) {
        store.edit { it[KEY_HIGH_CONTRAST] = enabled }
    }

    /** v2.5.0: Guardar modo vidrio líquido(off/water/frost). */
    suspend fun saveLiquidGlassMode(mode: String) {
        store.edit {
            it[KEY_LIQUID_GLASS_MODE] = mode
            it.remove(KEY_LIQUID_GLASS) // Limpiar viejas claves booleanas
        }
    }

    /** v2.5.0: Preservar la resistencia del vidrio(0..100). */
    suspend fun saveLiquidGlassStrength(strength: Int) {
        store.edit { it[KEY_LIQUID_GLASS_STRENGTH] = strength.coerceIn(0, 100) }
    }

    suspend fun upsertCustomTheme(theme: CustomTheme) {
        store.edit { prefs ->
            val current = decodePrefsOrNull(prefs[KEY_CUSTOM_THEMES], ListSerializer(CustomTheme.serializer()), "CustomThemes(upsert)") ?: emptyList()
            val updated = current.filterNot { it.id == theme.id } + theme
            prefs[KEY_CUSTOM_THEMES] = AppJson.encodeToString(ListSerializer(CustomTheme.serializer()), updated)
        }
    }

    suspend fun deleteCustomTheme(id: String) {
        store.edit { prefs ->
            val current = decodePrefsOrNull(prefs[KEY_CUSTOM_THEMES], ListSerializer(CustomTheme.serializer()), "CustomThemes(delete)") ?: emptyList()
            prefs[KEY_CUSTOM_THEMES] = AppJson.encodeToString(ListSerializer(CustomTheme.serializer()), current.filterNot { it.id == id })
        }
    }

    private fun <T> decodePrefsOrNull(value: String?, serializer: kotlinx.serialization.KSerializer<T>, label: String): T? {
        if (value.isNullOrBlank()) return null
        return runCatching { AppJson.decodeFromString(serializer, value) }
            .onFailure { e -> android.util.Log.w("AppearanceStore", "$label El análisis falló", e) }
            .getOrNull()
    }

    companion object {
        /** tema predeterminado id — Consistente con MainActivity/ThemeSection(R-UI-03). */
        const val DEFAULT_THEME_ID = "mono"

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_THEME_ID = stringPreferencesKey("theme_id")
        private val KEY_DARK_THEME_ID = stringPreferencesKey("dark_theme_id")
        private val KEY_THEME_SCHEDULE = stringPreferencesKey("theme_schedule_json")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_FONT_SIZE_SCALE = stringPreferencesKey("font_size_scale")
        private val KEY_DEFAULT_HOME_PAGE = intPreferencesKey("default_home_page")
        private val KEY_ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
        private val KEY_ASR_TIP_SHOWN = booleanPreferencesKey("asr_tip_shown")
        private val KEY_CUSTOM_THEMES = stringPreferencesKey("custom_themes_json")
        private val KEY_CUSTOM_FONT_PATH = stringPreferencesKey("custom_font_path")
        private val KEY_HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        private val KEY_LIQUID_GLASS = booleanPreferencesKey("liquid_glass_enabled")
        private val KEY_LIQUID_GLASS_MODE = stringPreferencesKey("liquid_glass_mode")
        private val KEY_LIQUID_GLASS_LEVEL = intPreferencesKey("liquid_glass_level")
        private val KEY_LIQUID_GLASS_STRENGTH = intPreferencesKey("liquid_glass_strength")
    }
}
