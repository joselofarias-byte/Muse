package io.zer0.muse.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.museSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/**
 * Catálogo de piel de burbuja — pura logica(Sin dependencias de Android),Responsable de análisis de piel./filtrar/Elige la semántica.
 *
 * con [BubbleSkinStore] Razón de la división:Necesidades de la capa de almacenamiento DataStore/Context,
 * y"¿Qué pieles se pueden aplicar? ¿Qué hacer si seleccionas una skin inexistente?"Esta estrategia debe ser verificable en una prueba única de JVM pura.
 *
 * invariante:
 *  - El valor predeterminado incorporado siempre está representado por [BubbleSkinResolver.defaultSkin] proporcionar,No ingresar a la lista personalizada;
 *  - La verificación falló(Incluir la relación de contraste no está a la altura del estándar.)Todas las pieles están filtradas.,Por lo tanto, el lado del consumidor vuelve a recurrir a la default;
 *  - No seleccionado/La identificación seleccionada no existe/Devuelto cuando se selecciona la identificación incorporada null,La persona que llama pasa null para mantener la representación existente.
 */
object BubbleSkinCatalog {

    /** Máscara predeterminada incorporada id,con [BubbleSkinResolver.defaultSkin] La identificación sigue siendo consistente. */
    const val DEFAULT_SKIN_ID = "builtin-default"

    /** Número máximo de máscaras personalizadas,Evite que la clave única de DataStore crezca indefinidamente. */
    const val MAX_CUSTOM_SKINS = 32

    /** Analizar la lista de máscaras personalizadas JSON;Cualquier error de análisis devuelve una lista vacía en lugar de arrojarla. */
    fun decode(raw: String?): List<BubbleSkin> {
        if (raw.isNullOrBlank()) return emptyList()
        val decoded = runCatching {
            AppJson.decodeFromString(ListSerializer(BubbleSkin.serializer()), raw)
        }.getOrNull() ?: return emptyList()
        return sanitize(decoded)
    }

    fun encode(skins: List<BubbleSkin>): String = AppJson.encodeToString(ListSerializer(BubbleSkin.serializer()), sanitize(skins))

    /** Filtrar máscaras ilegales/identificación incorporada/duplicada id,y limitar la cantidad;Se garantiza que el contenido de la lista será seguro de procesar en todo momento. */
    fun sanitize(skins: List<BubbleSkin>): List<BubbleSkin> = skins
        .filter { it.id != DEFAULT_SKIN_ID && BubbleSkinValidator.isValid(it) }
        .distinctBy { it.id }
        .take(MAX_CUSTOM_SKINS)

    /** Agregar o sobrescribir una máscara con la misma ID;Las máscaras ilegales no se incluirán en la lista.(La persona que llama mantiene el status quo → revertir default). */
    fun upsert(current: List<BubbleSkin>, skin: BubbleSkin): List<BubbleSkin> {
        if (skin.id == DEFAULT_SKIN_ID || !BubbleSkinValidator.isValid(skin)) return sanitize(current)
        return sanitize(current.filterNot { it.id == skin.id } + skin)
    }

    fun remove(current: List<BubbleSkin>, id: String): List<BubbleSkin> = sanitize(current.filterNot { it.id == id })

    /**
     * Analizar máscaras individuales importadas JSON(compartir texto/SAF Archivo).
     *
     * Se devolverá un esquema ilegal, un rango de valores ilegal o un contraste deficiente. null,La interfaz de usuario indica el error y se conserva el aspecto actual.
     */
    fun parseSkin(raw: String?): BubbleSkin? {
        if (raw.isNullOrBlank()) return null
        val skin = runCatching {
            AppJson.decodeFromString(BubbleSkin.serializer(), raw)
        }.getOrNull() ?: return null
        if (skin.id == DEFAULT_SKIN_ID || !BubbleSkinValidator.isValid(skin)) return null
        return skin
    }

    /**
     * Seleccione la máscara actualmente activa.
     *
     * @return Máscaras personalizadas renderizables;La identificación incorporada / identificación vacía / no encontrada / se devolverá si falla la verificación. null,
     * La persona que llama pasa null al componente de burbuja para mantener la apariencia predeterminada.
     */
    fun select(skins: List<BubbleSkin>, selectedId: String): BubbleSkin? {
        if (selectedId == DEFAULT_SKIN_ID || selectedId.isBlank()) return null
        return sanitize(skins).firstOrNull { it.id == selectedId }
    }

    /**
     * Opciones después de fusionar máscaras personalizadas y máscaras de complementos.
     *
     * Las máscaras personalizadas mantienen las prioridades existentes:Gana la máscara importada por el usuario con la misma identificación.,Instalación/Desinstalar el complemento no cambiará el usuario
     * Apariencia ya seleccionada;Recurra al aspecto del complemento solo si falta la lista personalizada. La máscara del complemento ha sido verificada por la capa del complemento.,
     * Consulta nuevamente aquí,Se garantiza que las máscaras ilegales nunca entrarán en la ruta de renderizado.
     *
     * @return Piel renderizable;La identificación incorporada / identificación vacía / no encontrada en ambos lugares / nulo se devolverá si falla la verificación.
     */
    fun select(customSkins: List<BubbleSkin>, pluginSkins: List<BubbleSkin>, selectedId: String): BubbleSkin? {
        if (selectedId == DEFAULT_SKIN_ID || selectedId.isBlank()) return null
        select(customSkins, selectedId)?.let { return it }
        return pluginSkins.firstOrNull { it.id == selectedId && BubbleSkinValidator.isValid(it) }
    }
}

/**
 * Almacenamiento ligero de piel de burbuja.(DataStore Preferences,con [io.zer0.muse.data.AppearanceSettingsStore]
 * compartir `muse_settings` Archivo).
 *
 * Guarda solo dos claves:ID de máscara seleccionada actualmente + lista JSON de máscaras personalizadas. No escrito desactualizado
 * [selectedSkinIdFlow] Predeterminado predeterminado incorporado,[customSkinsFlow] Lista vacía predeterminada,
 * [selectedSkinFlow] Por lo tanto null,ChatScreen/La página de chat grupal mantiene la ruta de renderizado antes de la transformación.
 *
 * Phase 4 Se puede inyectar desde [PluginSkinSource]:Devuelve el skin cuando la identificación seleccionada llega al skin que tiene instalado el complemento ui-skin.;
 * El complemento está deshabilitado/Desinstalar/Cuando se revoca la confianza o la piel es ilegal,La capa de complemento ya no devuelve esta entrada,Naturalmente, esto vuelve al valor predeterminado incorporado.
 */
class BubbleSkinStore(
    private val context: Context,
    /** Fuente de máscara de complemento;null Indica que no se consumen máscaras de complementos.(El comportamiento de las pruebas individuales existentes y de los llamantes que no están conectados al complemento permanece sin cambios.). */
    private val pluginSkinSource: PluginSkinSource? = null,
) {

    private val store get() = context.museSettingsDataStore

    val customSkinsFlow: Flow<List<BubbleSkin>> = store.data.map { prefs ->
        BubbleSkinCatalog.decode(prefs[KEY_CUSTOM_SKINS])
    }

    val selectedSkinIdFlow: Flow<String> = store.data.map { prefs ->
        prefs[KEY_SELECTED_SKIN_ID] ?: BubbleSkinCatalog.DEFAULT_SKIN_ID
    }

    /** Señal de cambio de registro de complemento;Constante cuando no hay una fuente de complemento,No cambia la semántica combinada existente. */
    private val pluginRevisionFlow: Flow<Int> = pluginSkinSource?.revisionFlow ?: flowOf(0)

    /**
     * Máscara de complemento instalada(Contiene artículos no disponibles,Para mostrar en la página de configuración).
     *
     * Leer volverá a comprobar el paquete del complemento y leerá el disco.,Corte unificado a hilo IO,Evite iniciar sesión en el hilo principal./Cálculo resumido.
     */
    val pluginSkinsFlow: Flow<List<InstalledSkin>> = pluginRevisionFlow
        .map { pluginSkinSource?.listInstalledSkins(includeDisabled = true).orEmpty() }
        .flowOn(Dispatchers.IO)

    /** Piel actualmente activa;null = Utilice el incorporado default(Completamente consistente con el comportamiento antes de la transformación.). */
    val selectedSkinFlow: Flow<BubbleSkin?> = combine(
        customSkinsFlow,
        selectedSkinIdFlow,
        pluginRevisionFlow,
    ) { skins, id, _ ->
        BubbleSkinCatalog.select(
            customSkins = skins,
            pluginSkins = pluginSkinSource?.listInstalledSkins().orEmpty().map { it.skin },
            selectedId = id,
        )
    }.flowOn(Dispatchers.IO)

    suspend fun saveSelectedSkinId(id: String) {
        store.edit { prefs -> prefs[KEY_SELECTED_SKIN_ID] = id }
    }

    /** importar/Actualizar una máscara;Devolver falso indica que la máscara es ilegal y rechazada.(Las listas existentes no se ven afectadas). */
    suspend fun upsertSkin(skin: BubbleSkin): Boolean {
        if (!BubbleSkinValidator.isValid(skin) || skin.id == BubbleSkinCatalog.DEFAULT_SKIN_ID) {
            Logger.w("BubbleSkinStore", "Negarse a salvar máscaras de burbujas ilegales: ${skin.id}")
            return false
        }
        store.edit { prefs ->
            val current = BubbleSkinCatalog.decode(prefs[KEY_CUSTOM_SKINS])
            prefs[KEY_CUSTOM_SKINS] = BubbleSkinCatalog.encode(BubbleSkinCatalog.upsert(current, skin))
        }
        return true
    }

    /** quitar la piel;Si se elimina el elemento actualmente seleccionado,Elija recurrir al valor predeterminado integrado. */
    suspend fun deleteSkin(id: String) {
        store.edit { prefs ->
            val current = BubbleSkinCatalog.decode(prefs[KEY_CUSTOM_SKINS])
            prefs[KEY_CUSTOM_SKINS] = BubbleSkinCatalog.encode(BubbleSkinCatalog.remove(current, id))
            if (prefs[KEY_SELECTED_SKIN_ID] == id) prefs.remove(KEY_SELECTED_SKIN_ID)
        }
    }

    /** Analizar y guardar máscaras importadas JSON;null Indica contenido ilegal,La persona que llama indica el error. */
    suspend fun importSkin(raw: String?): BubbleSkin? {
        val skin = BubbleSkinCatalog.parseSkin(raw) ?: return null
        return skin.takeIf { upsertSkin(it) }
    }

    private companion object {
        val KEY_SELECTED_SKIN_ID = stringPreferencesKey("bubble_skin_selected_id")
        val KEY_CUSTOM_SKINS = stringPreferencesKey("bubble_skin_custom_json")
    }
}
