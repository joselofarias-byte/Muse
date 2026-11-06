package io.zer0.muse.importer

import android.content.Context
import android.net.Uri
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase 8.9: Importador de configuración de clientes de terceros.
 *
 * Soporta dos formatos(olfateo automático,No es necesario que el usuario especifique la fuente):
 *  - **CherryStudio**: Exportar JSON con `providers` matriz,Los campos son
 *    `id` / `name` / `apiHost` / `apiKey` / `models[]`(Cada artículo `{id,name}` o cuerda simple)
 *  - **Chatbox**: Exportar JSON con `providers` matriz o `settings.<providerId>` map,
 *    Los campos son similares(`endpoint` o `apiHost` como baseUrl)
 *
 * diseño:
 *  - cadena simple/JSON analizar,No depende de ninguna biblioteca de esquemas externa
 *  - Cada proveedor se convierte a [ProviderConfig] y escribe [SettingsRepository.addProvider]
 *  - id Saltar el conflicto(No sobrescribir existente Provider)
 *  - inferencia de tipos:baseUrl Contiene `anthropic.com` → ANTHROPIC;Contiene `gemini`/`googleapis` → GEMINI;Otros → OPENAI
 *  - No hay capacidad para importar modelos./Visión/marcado de transmisión(Posteriormente, los usuarios mejoran manualmente)
 *
 * Límite:
 *  - No importar el historial de conversaciones(CherryStudio/Chatbox Los respectivos formatos de sesión son incompatibles con SessionEntity de muse.)
 *  - no importar Assistant / QuickMessage / Lorebook y otras configuraciones avanzadas(Los campos varían mucho entre los clientes.)
 *  - CherryStudio de `isSystem` bandera ignorada(Procesamiento omitido por conflicto de identificación)
 *
 * @param settings muse Configurar almacén(usado para addProvider)
 */
class ConfigImporter(
    private val settings: SettingsRepository,
) {

    /**
     * Importa los resultados.
     * @param imported Número de proveedores importados correctamente
     * @param skipped Número de proveedores omitidos(id Conflicto o campo faltante)
     * @param providers Lista de nombres de proveedores importados(para indicaciones de la interfaz de usuario)
     * @param skippedProviders pregunta7.5: Lista de nombres de proveedores que se deben omitir(id Escena de conflicto),
     *   Proporcionar mensajes emergentes en la interfaz de usuario"El siguiente proveedor ya existe,saltado:xxx"
     */
    data class Result(
        val imported: Int,
        val skipped: Int,
        val providers: List<String>,
        val skippedProviders: List<String> = emptyList(),
    )

    /**
     * Leer e importar la configuración desde SAF URI.
     * @return [Result];Cuando falla el análisis imported=0
     */
    suspend fun importFromUri(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        // Límite de tamaño eliminado(Necesidades del usuario:Eliminar completamente el límite de tamaño de datos de importación),Mantenga 2 GB por seguridad OOM
        val text = resultOf {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val MAX_READ_BYTES = 2L * 1024 * 1024 * 1024 // 2GB Ten todo en cuenta
                val sb = StringBuilder()
                val buffer = CharArray(8192)
                var total = 0L
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val read = reader.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > MAX_READ_BYTES) {
                            error("Archivo demasiado grande,exceder ${MAX_READ_BYTES / 1024 / 1024 / 1024}GB Límite")
                        }
                        sb.append(buffer, 0, read)
                    }
                }
                sb.toString()
            }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Logger.w("ConfigImporter", "No se pudo leer el archivo de importación o el contenido está vacío")
            return@withContext Result(0, 0, emptyList())
        }
        importFromJson(text)
    }

    /**
     * Analizar e importar proveedor desde una cadena JSON.
     * Huele automáticamente los formatos CherryStudio/Chatbox.
     * @return [Result]
     */
    suspend fun importFromJson(jsonText: String): Result = withContext(Dispatchers.IO) {
        val root = resultOf { AppJson.parseToJsonElement(jsonText) }.getOrNull() ?: run {
            Logger.w("ConfigImporter", "JSON El análisis falló")
            return@withContext Result(0, 0, emptyList())
        }
        if (root !is JsonObject) return@withContext Result(0, 0, emptyList())

        val rawProviders = extractProviderList(root)
        if (rawProviders.isEmpty()) return@withContext Result(0, 0, emptyList())

        var imported = 0
        var skipped = 0
        val names = mutableListOf<String>()
        // pregunta7.5: Registre el nombre del proveedor que se omitió debido a un conflicto de identificación,Proporcionar indicaciones de la interfaz de usuario
        val skippedNames = mutableListOf<String>()
        // Obtener una colección de ID de proveedor existente,Se utiliza para eliminar duplicados.(id Saltar el conflicto,No cubierto)
        val existingIds = settings.providersFlow.first().map { it.id }.toSet()
        for (raw in rawProviders) {
            val cfg = parseProvider(raw) ?: run {
                skipped++
                continue
            }
            if (cfg.id in existingIds) {
                // id conflicto → saltar(No cubierto)
                skipped++
                skippedNames += cfg.displayName
                Logger.i("ConfigImporter", "Skip duplicate provider: ${cfg.id} (${cfg.displayName})")
                continue
            }
            // pregunta7.4: apiKey El riesgo de texto claro ha sido eliminado internamente por SettingsRepository —
            // addProvider → encodeProviders Llamado para cada ProviderConfig.apiKey
            // SecureKeyStore.encrypt(Android Keystore AES-256-GCM),La capa de persistencia es texto cifrado.
            // No se requiere cifrado adicional aquí,Simplemente pase la apiKey en texto plano directamente.
            settings.addProvider(cfg)
            imported++
            names += cfg.displayName
        }
        Result(imported, skipped, names, skippedNames)
    }

    /**
     * Extrae una lista de objetos JSON del proveedor del objeto raíz.
     * Soporta tres estructuras.:
     *  - `providers: [...]`(CherryStudio / nueva versión Chatbox)
     *  - `settings: { <providerId>: { ... } }`(versión antigua Chatbox)
     *  - Directamente una matriz(Algunos formatos de exportación)
     */
    private fun extractProviderList(root: JsonObject): List<JsonObject> {
        // 1. root.providers matriz
        val providers = root["providers"]
        if (providers is JsonArray) {
            return providers.mapNotNull { it as? JsonObject }
        }
        // 2. root.settings.<id> map
        val settingsObj = root["settings"]
        if (settingsObj is JsonObject) {
            // Inyecte la clave del mapa en el objeto como la identificación del proveedor
            return settingsObj.entries.mapNotNull { (id, v) ->
                if (v is JsonObject) mergeProviderId(v, id) else null
            }
        }
        // 3. root Directamente una matriz(Teóricamente la raíz ya está JsonObject,Aquí está el resultado final)
        return emptyList()
    }

    /** Fusionar la clave del mapa en el objeto como campo de identificación(Si el objeto no tiene id). */
    private fun mergeProviderId(obj: JsonObject, id: String): JsonObject {
        val map = obj.toMutableMap()
        if (map["id"] == null) map["id"] = JsonPrimitive(id)
        return JsonObject(map)
    }

    /**
     * Analizar un único objeto JSON de proveedor en [ProviderConfig].
     * Campos compatibles:
     *  - id / name / displayName
     *  - apiHost / endpoint / baseUrl
     *  - apiKey / key
     *  - models: [{id,name}] / [string] / [{id}]
     */
    private fun parseProvider(obj: JsonObject): ProviderConfig? {
        val id = strField(obj, "id") ?: return null
        val displayName = strField(obj, "name") ?: strField(obj, "displayName") ?: id
        val baseUrl = strField(obj, "apiHost") ?: strField(obj, "endpoint") ?: strField(obj, "baseUrl") ?: ""
        val apiKey = strField(obj, "apiKey") ?: strField(obj, "key") ?: ""
        val type = inferType(baseUrl, id)
        val models = parseModels(obj["models"], id)
        // Omita los vacíos sin apiKey ni modelos Provider(Comúnmente visto con elementos integrados que no están habilitados)
        if (apiKey.isBlank() && models.isEmpty()) return null
        return ProviderConfig(
            id = sanitizeId(id),
            displayName = displayName,
            type = type,
            baseUrl = baseUrl,
            apiKey = apiKey,
            models = models,
        )
    }

    /** El tipo de proveedor se infiere en función de baseUrl/id. */
    private fun inferType(baseUrl: String, id: String): ProviderType {
        val combined = (baseUrl + id).lowercase()
        return when {
            "anthropic" in combined || "claude" in combined -> ProviderType.ANTHROPIC
            "gemini" in combined || "googleapis" in combined -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }
    }

    /**
     * Analiza el campo de modelos.
     * apoyo:Los elementos de la matriz son string / {id,name} / {id}.
     */
    private fun parseModels(element: JsonElement?, providerId: String): List<Model> {
        if (element !is JsonArray) return emptyList()
        return element.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> Model(id = item.content, providerId = providerId)
                is JsonObject -> {
                    val mid = strField(item, "id") ?: return@mapNotNull null
                    val mname = strField(item, "name") ?: mid
                    Model(id = mid, name = mname, providerId = providerId)
                }
                else -> null
            }
        }
    }

    /** Obtener campo de cadena de JsonObject(apoyo JsonPrimitive.content). */
    private fun strField(obj: JsonObject, key: String): String? {
        val v = obj[key] ?: return null
        return (v as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    /**
     * Convierte cualquier cadena a legal Provider id(Evite los separadores de caminos/espacio).
     * Mantener alfanumérico + guión + subrayado + punto,El resto se reemplaza con guiones bajos.
     */
    private fun sanitizeId(raw: String): String =
        raw.lowercase().replace(INVALID_ID_CHAR_REGEX, "_").take(64).ifBlank { "imported_${System.currentTimeMillis()}" }

    companion object {
        // L6: Reutilizar Regex como objeto complementario,Evite crear una nueva instancia cada vez que se llame a sanitizeId
        private val INVALID_ID_CHAR_REGEX = Regex("[^a-z0-9._-]")
    }
}
