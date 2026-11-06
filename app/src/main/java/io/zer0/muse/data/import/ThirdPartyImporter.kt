package io.zer0.muse.data.`import`

import android.content.Context
import android.net.Uri
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.util.copyZipEntryWithLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlin.uuid.Uuid

/**
 * v1.61-A: Resultados de importación de datos de terceros.
 */
// i18n Evaluado(Suspendida la reorganización estructural)— errors: List<String> Todo el relleno ha sido reemplazado con
// context.getString(R.string.import_error_* / import_default_*),Los mensajes visibles para el usuario están localizados.
// más List<String> Cambiar a List<Int>(Recursos ID)Es necesario cambiar la estructura ImportResult + la persona que llama UI simultáneamente
// + Contiene información de parámetros String.format,Los cambios abarcan archivos y contienen parámetros dinámicos.(e.message / skippedMessages),
// Más riesgoso,Suspendido.i18n Meta alcanzada,Sólo se difieren las preferencias estructurales.
data class ImportResult(
    val providersImported: Int = 0,
    val assistantsImported: Int = 0,
    val conversationsImported: Int = 0,
    val messagesImported: Int = 0,
    val errors: List<String> = emptyList(),
)

/**
 * v1.61-A: Importador de datos de terceros.
 * Admite los formatos de copia de seguridad RikkaHub y Kelivo.
 */
object ThirdPartyImporter {

    private const val TAG = "ThirdPartyImporter"

    // Se han eliminado las restricciones de seguridad a la descompresión.(Necesidades del usuario:Eliminar completamente el límite de tamaño de datos de importación).
    // Preservar márgenes extremadamente altos para evitar bombas ZIP maliciosas,Pero los usuarios normales no lo alcanzarán.
    private const val MAX_TOTAL_SIZE = 4L * 1024 * 1024 * 1024 // 4GB(Antibomba en el fondo de tu bolsillo)
    private const val MAX_SINGLE_FILE = 1L * 1024 * 1024 * 1024 // 1GB(Antibomba en el fondo de tu bolsillo)
    private const val MAX_FILE_COUNT = 100000 // 10millones(Antibomba en el fondo de tu bolsillo)

    /**
     * v1.0.74 fix: Número máximo de bytes para importación JSON simple(200MB).
     * ChatGPT Conversaciones oficiales de exportación.json común 50-200MB,Tres copias del archivo completo readBytes
     * En el montón de Android(256MB-1GB)Reunión OOM. Si se excede este límite, se informará claramente un error.,No entrar en accidente.
     */
    private const val MAX_JSON_IMPORT_BYTES = 200L * 1024 * 1024

    /**
     * v2.4.6: ChatGPT La nueva exportación divide la sesión en conversations-000.json / conversations-001.json ...
     * coincide con el nombre(Se utiliza para descomprimir listas blancas y fusionar fragmentos.).
     */
    private val CONVERSATIONS_SHARD_REGEX = Regex("conversations-\\d+\\.json", RegexOption.IGNORE_CASE)

    /**
     * Detecta automáticamente el formato de la copia de seguridad e importa.
     * @param backupUri SAF URI(backup_*.zip / kelivo_backup_*.zip / ChatGPT conversations.json)
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        settings: SettingsRepository,
        assistantRepo: AssistantRepository,
        sessionRepo: SessionRepository,
    ): ImportResult = withContext(Dispatchers.IO) {
        val ctx = context

        // Copia en streaming al archivo de caché,evitar readBytes() Leer todo el archivo en la memoria a la vez provoca OOM.
        val tempFile = java.io.File.createTempFile("import_", ".tmp", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: run {
                    tempFile.delete()
                    return@withContext ImportResult(
                        errors = listOf(context.getString(R.string.import_error_read_file_failed)),
                    )
                }
            input.use { source ->
                tempFile.outputStream().use { output -> source.copyTo(output) }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "No se puede leer el archivo de importación", e)
            tempFile.delete()
            return@withContext ImportResult(errors = listOf(context.getString(R.string.import_error_read_file_failed)))
        }

        // v1.0.74: Determinar si ZIP(PK numero magico). Pruebe sin ZIP con texto sin formato(ChatGPT conversations.json espera).
        val isZip = isZipFile(tempFile)
        if (!isZip) {
            // v1.0.74 fix: ReadBytes de archivos completos + análisis de árboles completos,Gran paquete de exportación(50-200MB)Tres copias deben ser OOM.
            // Aumentar límite de tamaño:Informar explícitamente un error cuando se excede el límite,Sin fallos de OOM.
            if (tempFile.length() > MAX_JSON_IMPORT_BYTES) {
                val sizeMb = tempFile.length() / 1024 / 1024
                tempFile.delete()
                return@withContext ImportResult(
                    errors = listOf(
                        context.getString(R.string.import_error_file_too_large, sizeMb),
                    ),
                )
            }
            val text = tempFile.inputStream().bufferedReader(StandardCharsets.UTF_8).use { reader ->
                reader.readText().removePrefix("\uFEFF")
            }
            tempFile.delete()
            return@withContext importJsonText(
                ctx,
                text,
                settings,
                assistantRepo,
                sessionRepo,
            )
        }

        // Reparación de auditoría (6.7): zip Descomprima los archivos internos en archivos temporales del disco,El mapa de memoria ya no reside por completo.
        // Las entradas de implementación originales se conservan en su totalidad.(ChatGPT Exportar conversaciones.json docenas de MB + varias imágenes),
        // El límite superior de 4 GB supera con creces el montón de procesos de Android,Importar modelos antiguos requiere OOM.
        // Guarde solo los archivos que necesita: settings.json / chats.json / conversations.json(cualquiera).
        val extracted = mutableMapOf<String, java.io.File>()
        var totalSize = 0L
        try {
            ZipInputStream(tempFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // M-IMP2: Comprobar nombre de entrada,Evitar el cruce de caminos
                        if (entry.name.contains("..") || entry.name.startsWith("/")) {
                            Logger.w(TAG, "Saltar entradas de rutas sospechosas: ${entry.name}")
                            entry = zis.nextEntry
                            continue
                        }
                        val name = entry.name.substringAfterLast('/')
                        // v2.4.6: Admite exportación jerárquica de la nueva versión de ChatGPT — La sesión se divide en
                        // conversations.json o fragmentos de conversaciones-000.json / conversaciones-001.json.
                        val keep = name == "settings.json" || name == "chats.json" ||
                            name.endsWith("conversations.json") || name == "conversations.json" ||
                            CONVERSATIONS_SHARD_REGEX.matches(name)
                        // Límite de cobertura de una sola fila:Ubicación de transmisión,Evite leer entradas grandes en el montón de Android en su conjunto.
                        if (keep) {
                            val tmp = java.io.File.createTempFile("zip_${name}_", ".tmp", context.cacheDir)
                            try {
                                val entryBytes = tmp.outputStream().buffered().use { output ->
                                    copyZipEntryWithLimit(zis, output, MAX_SINGLE_FILE, entry.name)
                                }
                                totalSize += entryBytes
                                if (totalSize > MAX_TOTAL_SIZE) {
                                    throw IllegalStateException("El tamaño total del contenido descomprimido supera el límite.(${MAX_TOTAL_SIZE / 1024 / 1024 / 1024}GB)")
                                }
                                extracted.remove(name)?.delete()
                                extracted[name] = tmp
                            } catch (e: Exception) {
                                tmp.delete()
                                throw e
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: IllegalStateException) {
            Logger.w(TAG, "Descompresión terminada: ${e.message}")
            extracted.values.forEach { it.delete() }
            tempFile.delete()
            return@withContext ImportResult(errors = listOf(e.message ?: context.getString(R.string.import_error_unzip_size_exceeded)))
        } catch (e: Exception) {
            Logger.w(TAG, "No se puede descomprimir el archivo", e)
            extracted.values.forEach { it.delete() }
            tempFile.delete()
            return@withContext ImportResult(errors = listOf(context.getString(R.string.import_error_unzip_failed, e.message)))
        } finally {
            tempFile.delete()
        }

        // Formato de sentencia:
        //  - ChatGPT Exportar: conversations.json(matriz de nivel superior o {conversations: [...]})O nueva versión de fragmentación conversations-000.json ...
        //  - Kelivo: chats.json + settings.json
        //  - RikkaHub: settings.json(y ninguno chats.json)
        val settingsFile = extracted["settings.json"]
        val chatsFile = extracted["chats.json"]
        val hasChats = chatsFile != null
        // v2.4.6: Recoge todos los archivos de conversaciones(Contiene fragmentos),Ordenar por nombre(conversations.json Prioridad,La fragmentación viene después)fusionar
        val conversationFiles = extracted.entries
            .filter { it.key == "conversations.json" || it.key.endsWith("conversations.json") || CONVERSATIONS_SHARD_REGEX.matches(it.key) }
            .sortedBy { it.key }
            .map { it.value }

        try {
            val settingsJson = settingsFile?.let { it.readText() }
            val chatsJson = chatsFile?.let { it.readText() }
            // v2.4.6: Fusionar varios fragmentos en una única cadena de matriz JSON(El contenido de los fragmentos son todos matrices de nivel superior.).
            // Cuando utilice un solo fragmento, utilice el texto original directamente.,Evite empalmes adicionales por encima.
            val conversationsJson = if (conversationFiles.isEmpty()) {
                null
            } else if (conversationFiles.size == 1) {
                conversationFiles.first().readText()
            } else {
                mergeConversationShards(conversationFiles)
            }

            when {
                hasChats -> importKelivo(
                    ctx,
                    settingsJson,
                    chatsJson,
                    settings,
                    assistantRepo,
                    sessionRepo,
                )
                settingsJson != null -> importRikkaHub(
                    ctx,
                    settingsJson,
                    settings,
                    assistantRepo,
                )
                conversationsJson != null -> importConversationsJson(
                    ctx,
                    conversationsJson,
                    assistantRepo,
                    sessionRepo,
                )
                else -> ImportResult(
                    errors = listOf(context.getString(R.string.import_error_unknown_format)),
                )
            }
        } finally {
            // Borrar archivos temporales descomprimidos
            extracted.values.forEach { it.delete() }
        }
    }

    /**
     * v2.4.6: Fusione varios fragmentos de conversaciones exportados por la nueva versión de ChatGPT.
     *
     * Cada fragmento es una matriz JSON de nivel superior,El elemento es un objeto de sesión única.;Aquí los juntamos en una matriz.
     * Uso de empalme de texto en streaming:Elimine los corchetes al principio y al final de cada segmento y conéctelos con comas.,Evite poner todo el conjunto grande
     * Analizar en JsonElement y reorganizar(2026-10 La exportación real medida puede alcanzar decenas de MB,El análisis aumentará la memoria exponencialmente).
     */
    internal fun mergeConversationShards(files: List<java.io.File>): String = buildString {
        append('[')
        var first = true
        files.forEach { f ->
            val raw = f.readText().removePrefix("\uFEFF").trim()
            if (raw.isEmpty()) return@forEach
            // Retire los corchetes exteriores
            val inner = when {
                raw.startsWith("[") && raw.endsWith("]") -> raw.substring(1, raw.length - 1).trim()
                else -> raw
            }
            if (inner.isEmpty()) return@forEach
            if (!first) append(',')
            append(inner)
            first = false
        }
        append(']')
    }

    /** v1.0.74: Determinar si el archivo es ZIP(PK numero magico). */
    private fun isZipFile(file: java.io.File): Boolean {
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(4)
                val read = input.read(head)
                read == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                    (head[2] == 3.toByte() || head[2] == 5.toByte() || head[2] == 7.toByte())
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * v1.0.74: Importar texto JSON desnudo(olfateo automático ChatGPT / RikkaHub settings / Kelivo chats).
     */
    private suspend fun importJsonText(
        context: Context,
        text: String,
        settings: SettingsRepository,
        assistantRepo: AssistantRepository,
        sessionRepo: SessionRepository,
    ): ImportResult {
        if (text.isBlank()) {
            return ImportResult(errors = listOf(context.getString(R.string.import_error_unknown_format)))
        }
        val root = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
            ?: return ImportResult(errors = listOf(context.getString(R.string.import_error_parse_failed)))
        return when {
            // ChatGPT: Matriz de nivel superior o campo que contiene conversaciones
            root is kotlinx.serialization.json.JsonArray ||
                (root is kotlinx.serialization.json.JsonObject && root.containsKey("conversations")) ->
                importConversationsJson(context, text, assistantRepo, sessionRepo)
            // RikkaHub settings.json(si providers/assistants)
            root is kotlinx.serialization.json.JsonObject &&
                (root.containsKey("providers") || root.containsKey("assistants")) ->
                importRikkaHub(context, text, settings, assistantRepo)
            else -> ImportResult(errors = listOf(context.getString(R.string.import_error_unknown_format)))
        }
    }

    // ── RikkaHub importar ──
    // RikkaHub settings.json es el conjunto completo de clases de datos de configuración JSON,Contiene proveedores y assistants

    private suspend fun importRikkaHub(
        context: Context,
        settingsJson: String,
        settings: SettingsRepository,
        assistantRepo: AssistantRepository,
    ): ImportResult {
        val errors = mutableListOf<String>()
        var providersCount = 0
        var assistantsCount = 0

        try {
            val root = AppJson.parseToJsonElement(settingsJson).jsonObject

            // importar Provider
            val providersArr = root["providers"]?.jsonArray
            if (providersArr != null) {
                for (providerElem in providersArr) {
                    try {
                        val providerObj = providerElem.jsonObject
                        val type = providerObj["type"]?.jsonPrimitive?.contentOrNull ?: "openai"
                        val providerType = when (type) {
                            "openai" -> ProviderType.OPENAI
                            "google" -> ProviderType.GEMINI
                            "claude" -> ProviderType.ANTHROPIC
                            else -> ProviderType.OPENAI
                        }
                        val sourceId = providerObj["id"]?.jsonPrimitive?.contentOrNull ?: Uuid.random().toString()
                        val id = "rikka_$sourceId"
                        val name = providerObj["name"]?.jsonPrimitive?.contentOrNull ?: context.getString(R.string.import_default_provider_name)
                        val apiKey = providerObj["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""
                        val baseUrl = providerObj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: ""
                        val enabled = providerObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true

                        // Analizar lista de modelos
                        val models = mutableListOf<Model>()
                        val modelsArr = providerObj["models"]?.jsonArray
                        if (modelsArr != null) {
                            for (modelElem in modelsArr) {
                                val modelObj = modelElem.jsonObject
                                val modelId = modelObj["modelId"]?.jsonPrimitive?.contentOrNull ?: continue
                                val displayName = modelObj["displayName"]?.jsonPrimitive?.contentOrNull ?: modelId
                                val abilities = mutableSetOf<ModelAbility>()
                                modelObj["abilities"]?.jsonArray?.forEach { ab ->
                                    ab.jsonPrimitive.contentOrNull?.let { ability ->
                                        when (ability) {
                                            "tool" -> abilities.add(ModelAbility.TOOL)
                                            "reasoning" -> abilities.add(ModelAbility.REASONING)
                                        }
                                    }
                                }
                                val inputMods = mutableSetOf("text")
                                modelObj["inputModalities"]?.jsonArray?.forEach { m ->
                                    if (m.jsonPrimitive.contentOrNull == "image") inputMods.add("image")
                                }
                                models.add(
                                    Model(
                                        id = modelId,
                                        name = displayName,
                                        providerId = id,
                                        abilities = abilities,
                                        inputModalities = inputMods,
                                    ),
                                )
                            }
                        }

                        val config = ProviderConfig(
                            id = id,
                            displayName = name,
                            type = providerType,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            models = models,
                            enabled = enabled,
                        )
                        settings.addProvider(config)
                        providersCount++
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        errors.add(context.getString(R.string.import_error_provider_failed, e.message))
                    }
                }
            }

            // Asistente de importación
            val assistantsArr = root["assistants"]?.jsonArray
            if (assistantsArr != null) {
                for (asstElem in assistantsArr) {
                    try {
                        val asstObj = asstElem.jsonObject
                        val name = asstObj["name"]?.jsonPrimitive?.contentOrNull ?: context.getString(R.string.import_default_assistant_name)
                        val systemPrompt = asstObj["systemPrompt"]?.jsonPrimitive?.contentOrNull ?: ""
                        val temperature = asstObj["temperature"]?.jsonPrimitive?.floatOrNull
                        val maxTokens = asstObj["maxTokens"]?.jsonPrimitive?.intOrNull
                        val contextMessageSize = asstObj["contextMessageSize"]?.jsonPrimitive?.intOrNull ?: 20

                        val sourceId = asstObj["id"]?.jsonPrimitive?.contentOrNull ?: Uuid.random().toString()
                        val entity = AssistantEntity(
                            id = "rikka_$sourceId",
                            name = name,
                            systemPrompt = systemPrompt,
                            temperature = temperature,
                            maxTokens = maxTokens,
                            contextMessageSize = contextMessageSize,
                        )
                        assistantRepo.upsert(entity)
                        assistantsCount++
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        errors.add(context.getString(R.string.import_error_assistant_failed, e.message))
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w(TAG, "RikkaHub Error al analizar la copia de seguridad", e)
            errors.add(context.getString(R.string.import_error_rikka_parse_failed, e.message))
        }

        return ImportResult(
            providersImported = providersCount,
            assistantsImported = assistantsCount,
            errors = errors,
        )
    }

    // ── ChatGPT Importación de exportación ──
    // ChatGPT Exportar(settings → Correo electrónico zip)de conversations.json:
    // El nivel superior es una matriz.,Cada artículo {title, create_time, update_time, mapping: {nodeId: {message: {author:{role}, content:{parts:[...]}, create_time}}, parent, children}}, current_node}

    internal suspend fun importChatGPT(
        context: Context,
        conversationsJson: String,
        assistantRepo: AssistantRepository,
        sessionRepo: SessionRepository,
    ): ImportResult {
        val errors = mutableListOf<String>()
        var conversationsCount = 0
        var messagesCount = 0
        var skippedMessages = 0

        try {
            val root = AppJson.parseToJsonElement(conversationsJson)
            val convArr = if (root is kotlinx.serialization.json.JsonArray) {
                root
            } else {
                root.jsonObject["conversations"]?.jsonArray
                    ?: return ImportResult(errors = listOf(context.getString(R.string.import_error_chatgpt_parse_failed)))
            }

            // Asistente predeterminado(Para montaje de sesión)
            val existingAssistantIds: Set<String> = assistantRepo.getAll().map { it.id }.toSet()
            val defaultAssistantId = existingAssistantIds.firstOrNull { it == "default" }
                ?: existingAssistantIds.firstOrNull()
                ?: "default"

            // v1.0.74: GPT Estructura local para análisis de nodos.(Salir del circuito,Evite problemas de inferencia de clases locales dentro de los bucles)
            data class NodeMsg(
                val role: String,
                val content: String,
                val createTime: Long,
            )
            data class ParsedNode(
                val parent: String?,
                val msg: NodeMsg?,
            )

            for (convElem in convArr) {
                try {
                    val convObj = convElem.jsonObject
                    val title = convObj["title"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.import_default_session_title)

                    val mapping = convObj["mapping"]?.jsonObject ?: continue

                    // v1.0.74 fix: La implementación anterior coloca todos los nodos en mapeo.(Contiene ramas abandonadas)Azulejos ordenados por tiempo,
                    // rama/El diálogo editado no funciona. En su lugar, rastrea la rama principal desde current_node a lo largo de la cadena principal.
                    // Analizar todos los nodos primero(Contiene mapeo principal)
                    val parsed = mutableMapOf<String, ParsedNode>()
                    for ((nodeId, nodeElem) in mapping) {
                        try {
                            val node = nodeElem.jsonObject
                            // v1.0.74 fix: ChatGPT Exportar una gran cantidad de campos es JSON null(JsonNull Ejemplo),
                            // JsonNull.jsonObject Lanzará una excepción que conducirá a un error de juicio."El análisis falló";Utilice la verificación de tipos de manera uniforme.
                            val parent = (node["parent"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull
                            val msg = (node["message"] as? kotlinx.serialization.json.JsonObject)
                                ?: run {
                                    parsed[nodeId] = ParsedNode(parent, null)
                                    continue
                                }
                            val author = (msg["author"] as? kotlinx.serialization.json.JsonObject)
                                ?: run {
                                    parsed[nodeId] = ParsedNode(parent, null)
                                    continue
                                }
                            val role = (author["role"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull
                                ?: run {
                                    parsed[nodeId] = ParsedNode(parent, null)
                                    continue
                                }
                            // sólo mantener user / assistant(Saltar mensajes internos del sistema/herramienta,pero mantén la cadena principal)
                            if (role != "user" && role != "assistant") {
                                parsed[nodeId] = ParsedNode(parent, null)
                                continue
                            }
                            val contentObj = (msg["content"] as? kotlinx.serialization.json.JsonObject)
                                ?: run {
                                    parsed[nodeId] = ParsedNode(parent, null)
                                    continue
                                }
                            val contentType = (contentObj["content_type"] as? kotlinx.serialization.json.JsonPrimitive)
                                ?.contentOrNull ?: "text"
                            // contenido del texto: parts matriz;Otros tipos(código/multimodal)Tomar campo de texto u omitir
                            val parts = contentObj["parts"] as? kotlinx.serialization.json.JsonArray
                            val text = if (parts != null) {
                                parts.mapNotNull { p ->
                                    when {
                                        p is kotlinx.serialization.json.JsonPrimitive -> p.contentOrNull
                                        p is kotlinx.serialization.json.JsonObject ->
                                            (p["text"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                                        else -> null
                                    }
                                }.joinToString("\n")
                            } else {
                                (contentObj["text"] as? kotlinx.serialization.json.JsonPrimitive)
                                    ?.contentOrNull ?: ""
                            }
                            // v1.0.74 fix: Nodo de texto en blanco(thoughts/reasoning_recap espera)guardar también parent,Mantenga la cadena intacta
                            if (text.isBlank()) {
                                parsed[nodeId] = ParsedNode(parent, null)
                                continue
                            }
                            // IMP-FIX: ChatGPT create_time Es coma flotante en segundos. La antigua implementación analizada con float.,
                            // 2024+ La marca de tiempo de aproximadamente 1.7e9 está fuera del rango exacto de un entero flotante(2^24),múltiples sesiones de mensajes
                            // se redondea al mismo segundo → El orden de los mensajes tiembla después de la importación. Utilice double en su lugar para preservar la precisión de menos de un segundo.
                            val rawTime = msg["create_time"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                            // Convertir a milisegundos de manera uniforme;0 Los valores permanecen en el orden original.
                            val ts = if (rawTime > 0.0) (rawTime * 1000).toLong() else -1L
                            parsed[nodeId] = ParsedNode(
                                parent = parent,
                                msg = NodeMsg(role, text, ts),
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            skippedMessages++
                            Logger.w(TAG, "ChatGPT Falló el análisis del nodo de mensaje: ${e.message}")
                        }
                    }

                    // Rastree la rama principal desde current_node a lo largo de la cadena principal(Si falta, tome el nodo hoja con el create_time más grande)
                    var cursor = convObj["current_node"]?.jsonPrimitive?.contentOrNull
                    if (cursor == null || !parsed.containsKey(cursor)) {
                        // Ninguno current_node: toma el nodo de mensaje más grande con create_time
                        cursor = parsed.entries
                            .filter { it.value.msg != null }
                            .maxByOrNull { it.value.msg!!.createTime }
                            ?.key
                    }
                    val branchIds = mutableListOf<String>()
                    val seen = mutableSetOf<String>()
                    while (cursor != null && !seen.contains(cursor)) {
                        seen.add(cursor)
                        branchIds.add(cursor)
                        cursor = parsed[cursor]?.parent
                    }
                    branchIds.reverse() // raíz → hojas

                    val nodes = branchIds.mapNotNull { parsed[it]?.msg }
                    if (nodes.isEmpty()) continue

                    // 0 Los nodos de marca de tiempo mantienen un orden relativo,El resto están ordenados por tiempo.
                    val orderedNodes = nodes.sortedBy { it.createTime }

                    // Crear sesión(v2.x Importar calentamiento:La primera ronda de la historia de reposición completa.)
                    val sessionId = sessionRepo.createSession(assistantId = defaultAssistantId, warmupPending = true)
                    sessionRepo.renameSession(sessionId, title)
                    conversationsCount++

                    orderedNodes.forEach { n ->
                        val role = when (n.role) {
                            "assistant" -> MessageRole.ASSISTANT
                            else -> MessageRole.USER
                        }
                        sessionRepo.appendMessage(
                            sessionId,
                            UIMessage(
                                id = Uuid.random(),
                                role = role,
                                content = n.content,
                                createdAt = n.createTime,
                            ),
                        )
                        messagesCount++
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    errors.add(context.getString(R.string.import_error_session_failed, e.message))
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w(TAG, "ChatGPT Error al analizar la exportación", e)
            errors.add(context.getString(R.string.import_error_chatgpt_parse_failed, e.message))
        }

        if (skippedMessages > 0) {
            errors.add(context.getString(R.string.import_error_messages_skipped, skippedMessages))
        }

        return ImportResult(
            conversationsImported = conversationsCount,
            messagesImported = messagesCount,
            errors = errors,
        )
    }

    /**
     * v1.0.90: conversations.json Este nombre de archivo lo utilizan las exportaciones oficiales de ChatGPT y Claude.
     * Envía siempre importChatGPT antes.Claude La exportación no tiene un campo de mapeo y se omitirá silenciosamente, devolviendo 0 elementos.
     * Cambio en el rastreo de contenido: las entradas que contienen chat_messages y no tienen mapeo se consideran Claude.
     */
    private suspend fun importConversationsJson(
        context: Context,
        text: String,
        assistantRepo: AssistantRepository,
        sessionRepo: SessionRepository,
    ): ImportResult {
        val looksClaude = runCatching {
            val arr = AppJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray
            val first = arr?.firstOrNull() as? kotlinx.serialization.json.JsonObject
            first != null && first.containsKey("chat_messages") && !first.containsKey("mapping")
        }.getOrDefault(false)
        return if (looksClaude) {
            importClaude(context, text, assistantRepo, sessionRepo)
        } else {
            importChatGPT(context, text, assistantRepo, sessionRepo)
        }
    }

    /** v1.0.90: Analizar marcas de tiempo ISO-8601(Claude created_at, con forma de 2024-05-01T12:34:56.789012+00:00), devuelve 0 en caso de error. */
    private fun parseIsoEpochMillis(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.Instant.parse(raw).toEpochMilli()
            } catch (e2: Exception) {
                0L
            }
        }
    }

    /**
     * v1.0.90: Claude Exportación oficial(conversations.json).
     *
     * Matriz de nivel superior, por sesión { uuid, name, created_at, chat_messages: [...] },
     * El mensaje es { uuid, text, sender: "human"|"assistant", created_at }.
     * A diferencia de ChatGPT,Claude No hay un árbol de ramas de mapeo, el orden de la matriz es el orden de los mensajes.
     */
    private suspend fun importClaude(
        context: Context,
        conversationsJson: String,
        assistantRepo: AssistantRepository,
        sessionRepo: SessionRepository,
    ): ImportResult {
        val errors = mutableListOf<String>()
        var conversationsCount = 0
        var messagesCount = 0
        var skippedMessages = 0

        data class ClaudeMsg(val role: String, val content: String, val createdAt: Long)

        try {
            val root = AppJson.parseToJsonElement(conversationsJson)
            val convArr = if (root is kotlinx.serialization.json.JsonArray) {
                root
            } else {
                root.jsonObject["conversations"]?.jsonArray
                    ?: return ImportResult(errors = listOf(context.getString(R.string.import_error_chatgpt_parse_failed)))
            }

            val assistantIds = assistantRepo.getAll().map { it.id }
            val defaultAssistantId = assistantIds.firstOrNull { it == "default" }
                ?: assistantIds.firstOrNull()
                ?: "default"

            for (convElem in convArr) {
                try {
                    val convObj = convElem as? kotlinx.serialization.json.JsonObject ?: continue
                    val msgs = convObj["chat_messages"] as? kotlinx.serialization.json.JsonArray ?: continue
                    val title = convObj["name"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.import_default_session_title)

                    val parsed = ArrayList<ClaudeMsg>(msgs.size)
                    for (msgElem in msgs) {
                        try {
                            val msgObj = msgElem as? kotlinx.serialization.json.JsonObject ?: continue
                            val sender = msgObj["sender"]?.jsonPrimitive?.contentOrNull ?: continue
                            val role = when (sender) {
                                "human" -> "user"
                                "assistant" -> "assistant"
                                else -> continue
                            }
                            val content = msgObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (content.isBlank()) continue
                            val ts = parseIsoEpochMillis(msgObj["created_at"]?.jsonPrimitive?.contentOrNull)
                            parsed.add(ClaudeMsg(role, content, ts))
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            skippedMessages++
                            Logger.w(TAG, "Claude Error al analizar el mensaje: ${e.message}")
                        }
                    }
                    if (parsed.isEmpty()) continue

                    // v2.x Importar calentamiento:La primera ronda de la historia de reposición completa.
                    val sessionId = sessionRepo.createSession(assistantId = defaultAssistantId, warmupPending = true)
                    sessionRepo.renameSession(sessionId, title)
                    conversationsCount++
                    parsed.forEach { m ->
                        sessionRepo.appendMessage(
                            sessionId,
                            UIMessage(
                                id = Uuid.random(),
                                role = if (m.role == "assistant") MessageRole.ASSISTANT else MessageRole.USER,
                                content = m.content,
                                createdAt = m.createdAt,
                            ),
                        )
                        messagesCount++
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    errors.add(context.getString(R.string.import_error_session_failed, e.message))
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w(TAG, "Claude Error al analizar la exportación", e)
            errors.add(context.getString(R.string.import_error_chatgpt_parse_failed, e.message))
        }

        if (skippedMessages > 0) {
            errors.add(context.getString(R.string.import_error_messages_skipped, skippedMessages))
        }

        return ImportResult(
            conversationsImported = conversationsCount,
            messagesImported = messagesCount,
            errors = errors,
        )
    }

    // ── Kelivo importar ──
    // Kelivo settings.json es un par clave-valor de SharedPreferences JSON
    // chats.json Contiene conversaciones y messages

    private suspend fun importKelivo(
        context: Context,
        settingsJson: String?,
        chatsJson: String?,
        settings: SettingsRepository,
        assistantRepo: AssistantRepository,
        sessionRepo: SessionRepository,
    ): ImportResult {
        val errors = mutableListOf<String>()
        var providersCount = 0
        var assistantsCount = 0
        var conversationsCount = 0
        var messagesCount = 0
        var skippedMessages = 0

        try {
            if (settingsJson != null) {
                val settingsRoot = AppJson.parseToJsonElement(settingsJson).jsonObject

                val providerConfigsRaw = settingsRoot["provider_configs_v1"]?.jsonPrimitive?.contentOrNull
                if (providerConfigsRaw != null) {
                    val providerConfigs = AppJson.parseToJsonElement(providerConfigsRaw).jsonObject
                    for ((providerKey, providerElem) in providerConfigs) {
                        try {
                            val providerObj = providerElem.jsonObject
                            val providerTypeStr = providerObj["providerType"]?.jsonPrimitive?.contentOrNull ?: "openai"
                            val providerType = when (providerTypeStr) {
                                "openai" -> ProviderType.OPENAI
                                "google" -> ProviderType.GEMINI
                                "claude" -> ProviderType.ANTHROPIC
                                else -> ProviderType.OPENAI
                            }
                            val apiKey = providerObj["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""
                            val baseUrl = providerObj["baseUrl"]?.jsonPrimitive?.contentOrNull ?: ""
                            val enabled = providerObj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                            val name = providerObj["name"]?.jsonPrimitive?.contentOrNull ?: providerKey

                            val providerId = "kelivo_$providerKey"
                            val models = mutableListOf<Model>()
                            val modelsArr = providerObj["models"]?.jsonArray
                            if (modelsArr != null) {
                                for (modelElem in modelsArr) {
                                    val modelObj = modelElem.jsonObject
                                    val modelId = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                                    val displayName = modelObj["displayName"]?.jsonPrimitive?.contentOrNull ?: modelId
                                    models.add(
                                        Model(
                                            id = modelId,
                                            name = displayName,
                                            providerId = providerId,
                                        ),
                                    )
                                }
                            }

                            val config = ProviderConfig(
                                id = providerId,
                                displayName = name,
                                type = providerType,
                                baseUrl = baseUrl,
                                apiKey = apiKey,
                                models = models,
                                enabled = enabled,
                            )
                            settings.addProvider(config)
                            providersCount++
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            errors.add(context.getString(R.string.import_error_provider_with_key_failed, providerKey, e.message))
                        }
                    }
                }

                val assistantsRaw = settingsRoot["assistants_v1"]?.jsonPrimitive?.contentOrNull
                if (assistantsRaw != null) {
                    val assistantsArr = AppJson.parseToJsonElement(assistantsRaw).jsonArray
                    for (asstElem in assistantsArr) {
                        try {
                            val asstObj = asstElem.jsonObject
                            val name = asstObj["name"]?.jsonPrimitive?.contentOrNull ?: context.getString(R.string.import_default_assistant_name)
                            val systemPrompt = asstObj["systemPrompt"]?.jsonPrimitive?.contentOrNull ?: ""
                            val temperature = asstObj["temperature"]?.jsonPrimitive?.floatOrNull
                            val maxTokens = asstObj["maxTokens"]?.jsonPrimitive?.intOrNull
                            val contextMessageSize = asstObj["contextMessageSize"]?.jsonPrimitive?.intOrNull ?: 20

                            val sourceId = asstObj["id"]?.jsonPrimitive?.contentOrNull ?: Uuid.random().toString()
                            val entity = AssistantEntity(
                                id = "kelivo_$sourceId",
                                name = name,
                                systemPrompt = systemPrompt,
                                temperature = temperature,
                                maxTokens = maxTokens,
                                contextMessageSize = contextMessageSize,
                            )
                            assistantRepo.upsert(entity)
                            assistantsCount++
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            errors.add(context.getString(R.string.import_error_assistant_failed, e.message))
                        }
                    }
                }
            }

            if (chatsJson != null) {
                val chatsRoot = AppJson.parseToJsonElement(chatsJson).jsonObject
                val conversationsArr = chatsRoot["conversations"]?.jsonArray
                val messagesArr = chatsRoot["messages"]?.jsonArray

                // Mensajes grupales por conversación
                val messagesByConv = mutableMapOf<String, MutableList<UIMessage>>()
                if (messagesArr != null) {
                    for (msgElem in messagesArr) {
                        try {
                            val msgObj = msgElem.jsonObject
                            val convId = msgObj["conversationId"]?.jsonPrimitive?.contentOrNull ?: continue
                            val roleStr = msgObj["role"]?.jsonPrimitive?.contentOrNull ?: "user"
                            val role = when (roleStr) {
                                "user" -> MessageRole.USER
                                "assistant" -> MessageRole.ASSISTANT
                                "system" -> MessageRole.SYSTEM
                                else -> MessageRole.USER
                            }
                            val content = msgObj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                            val reasoning = msgObj["reasoningText"]?.jsonPrimitive?.contentOrNull
                            val timestamp = msgObj["timestamp"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                                ?: System.currentTimeMillis()

                            val msg = UIMessage(
                                id = Uuid.random(),
                                role = role,
                                content = content,
                                reasoning = reasoning,
                                createdAt = timestamp,
                            )
                            messagesByConv.getOrPut(convId) { mutableListOf() }.add(msg)
                        } catch (e: Exception) {
                            // v1.71: Cuente los mensajes omitidos y registre registros,La deglución silenciosa original hizo que los usuarios no supieran qué mensajes se perdieron.
                            skippedMessages++
                            Logger.w(TAG, "Kelivo El análisis del mensaje falló y se omitió", e)
                        }
                    }
                }

                // Crear una conversación e importar mensajes
                if (conversationsArr != null) {
                    // H-IMP3: Precargar todos los existentes assistantId,para verificación/Remapeo de Kelivo assistantId,
                    // Evite referencias de sesión a inexistentes. assistant(referencia colgante). La lógica de importación original se transmite directamente de forma transparente. Kelivo
                    // de assistantId,Pero la identificación no existe en este proyecto.,Como resultado, la interfaz de usuario no puede cargar información del asistente.
                    val existingAssistantIds: Set<String> = assistantRepo.getAll().map { it.id }.toSet()
                    val defaultAssistantId = existingAssistantIds.firstOrNull { it == "default" }
                        ?: existingAssistantIds.firstOrNull()
                        ?: "default"

                    for (convElem in conversationsArr) {
                        try {
                            val convObj = convElem.jsonObject
                            val convId = convObj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                            val title = convObj["title"]?.jsonPrimitive?.contentOrNull ?: context.getString(R.string.import_default_session_title)
                            val rawAssistantId = convObj["assistantId"]?.jsonPrimitive?.contentOrNull

                            // H-IMP3: verificar assistantId,Si no existe, intente asignarlo al asistente Kelivo importado en este lote.,
                            // Si aún no existe, recurra al asistente predeterminado.,Evite las referencias colgantes.
                            val mappedAssistantId = rawAssistantId?.let { id ->
                                when {
                                    existingAssistantIds.contains(id) -> id
                                    existingAssistantIds.contains("kelivo_$id") -> "kelivo_$id"
                                    else -> {
                                        Logger.w(TAG, "Kelivo sesión assistantId '$id' no existe,Volver al asistente predeterminado")
                                        defaultAssistantId
                                    }
                                }
                            } ?: defaultAssistantId

                            // v2.x Importar calentamiento:La primera ronda de la historia de reposición completa.
                            val sessionId = sessionRepo.createSession(assistantId = mappedAssistantId, warmupPending = true)
                            sessionRepo.renameSession(sessionId, title)
                            conversationsCount++

                            // Importar mensajes de esta conversación
                            val convMessages = messagesByConv[convId] ?: emptyList()
                            for (msg in convMessages) {
                                sessionRepo.appendMessage(sessionId, msg)
                                messagesCount++
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            errors.add(context.getString(R.string.import_error_session_failed, e.message))
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w(TAG, "Kelivo Error al analizar la copia de seguridad", e)
            errors.add(context.getString(R.string.import_error_kelivo_parse_failed, e.message))
        }

        if (skippedMessages > 0) {
            errors.add(context.getString(R.string.import_error_messages_skipped, skippedMessages))
        }

        return ImportResult(
            providersImported = providersCount,
            assistantsImported = assistantsCount,
            conversationsImported = conversationsCount,
            messagesImported = messagesCount,
            errors = errors,
        )
    }
}
