package io.zer0.muse.data.assistant

import android.content.Context
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * Assistant Modelo de dominio + almacén(Phase 8.2).
 *
 * poner [AssistantEntity] Los campos JSON de(presetMessagesJson / tagsJson / toolIdsJson espera)
 * Deserializar a fuertemente tipado,Para uso de UI y ChatViewModel.
 *
 * v1.0.51: El mensaje predeterminado comienza desde assets/prompt_templates/default_persona_{locale}.prompt Cargar,
 * Respaldo de respaldo local(zh → en → Universales),Ya no está codificado en constantes.
 */
class AssistantRepository(
    private val dao: AssistantDao,
    private val context: Context,
    private val settings: SettingsRepository? = null,
    /**
     * P0-10: per-assistant Proveedor de fragmentación de hechos. Suelte el mango y límpielo al retirar el ayudante. facts_<id>.db,
     * Evite que la biblioteca de memoria del asistente eliminado y la conexión a la base de datos permanezcan permanentemente.
     */
    private val factDbProvider: io.zer0.memory.fact.FactDbProvider? = null,
    /**
     * Limpieza del lado de la memoria cuando se elimina el asistente(Resumen/párrafo/Memoria de chat grupal/Catálogo de productos/hechos).
     * null salto de tiempo(prueba única/Sin entorno DI).
     */
    private val sessionMemoryCleanup: io.zer0.muse.data.memory.SessionMemoryCleanup? = null,
) {

    val observeAll: Flow<List<AssistantEntity>> = dao.observeAll()

    suspend fun getAll(): List<AssistantEntity> = dao.getAll()

    suspend fun getById(id: String): AssistantEntity? = dao.getById(id)

    suspend fun upsert(assistant: AssistantEntity) {
        dao.upsert(assistant)
    }

    /**
     * Limpie el enlace explícito entre proveedor y modelo del Asistente después de eliminar el proveedor para evitar que las solicitudes continúen haciendo referencia a la configuración eliminada.
     */
    suspend fun removeProviderBinding(providerId: String) {
        require(providerId.isNotBlank()) { "providerId must not be blank" }
        val assistants = getAll()
        clearProviderBindings(assistants, providerId)
            .zip(assistants)
            .filter { (updated, original) -> updated != original }
            .forEach { (updated, _) -> upsert(updated) }
    }

    /** Elimine el enlace del servidor MCP que ya no existe para evitar la resurrección accidental de identificadores huérfanos e identificadores después de su reutilización. */
    suspend fun removeMcpServerBinding(serverId: String) {
        getAll().forEach { assistant ->
            val ids = parseMcpServerIds(assistant)
            if (serverId in ids) {
                upsert(assistant.copy(mcpServerIdsJson = serializeStringList(ids - serverId)))
            }
        }
    }

    /** El asistente de limpieza apunta a una identificación del servidor MCP que no existe en la configuración actual. */
    suspend fun removeOrphanMcpServerBindings(validServerIds: Set<String>) {
        getAll().forEach { assistant ->
            val ids = parseMcpServerIds(assistant)
            val filtered = ids.filter { it in validServerIds }
            if (filtered.size != ids.size) {
                upsert(assistant.copy(mcpServerIdsJson = serializeStringList(filtered)))
            }
        }
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
        // Primero se debe borrar la memoria, dividirla en archivos de base de datos y luego eliminarla.:limpiador para leer `facts.db` biblioteca principal y `facts_<id>.db` mismo,
        // Una vez eliminado el archivo,,Cualquier lectura recreará la biblioteca vacía.
        sessionMemoryCleanup?.let { cleanup ->
            resultOf { cleanup.cleanAssistant(id) }
                .onError { msg, t -> Logger.w("AssistantRepo", "No se pudo borrar la memoria del asistente: $msg", t) }
        }
        // P0-10: Liberar y limpiar la biblioteca de hechos cuando se elimine el asistente(El asistente no se elimina por defecto,La memoria del asistente principal comparte una. facts.db)
        if (id.isNotBlank() && id != "default") {
            factDbProvider?.let { provider ->
                provider.release(id)
                val dbFile = context.getDatabasePath("facts_$id.db")
                listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))
                    .forEach { runCatching { it.delete() } }
            }
        }
    }

    /** U-26: habilitar/Deshabilitar asistente(No aparecerá en la lista de candidatos después de la desactivación.). */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled)
    }

    suspend fun count(): Int = dao.count()

    /**
     * Insertar valor predeterminado en el primer arranque Assistant(Si no).
     * El Asistente predeterminado se llama"Asistente predeterminado",v1.95 A partir de las palabras de indicación de caracteres integradas + todas las habilidades/herramientas integradas.
     *
     * M-AST1: Utilice la estrategia INSERTAR O IGNORAR para evitar verificar y luego insertar condiciones de carrera TOCTOU
     * (original primero count() otra vez upsert,Puede ser insertado por otros hilos entre dos pasos.).
     * INSERT OR IGNORE es atómico:si "default" Si ya existe, será ignorado silenciosamente.,Los datos existentes no se sobrescriben.
     *
     * v2.x(comentarios de los usuarios): apoyo"Puntero auxiliar predeterminado mutable" —— [preferredDefaultId] Cuando no está vacío y el ayudante existe,
     * Trátelo como el asistente predeterminado y ya no fuerce una reconstrucción. id="default"(De lo contrario, después de que el usuario elimine el asistente predeterminado,
     * Esta función reactivará el reinicio).
     */
    suspend fun ensureDefaultExists(preferredDefaultId: String? = null): String {
        if (!preferredDefaultId.isNullOrBlank() && dao.getById(preferredDefaultId) != null) {
            return preferredDefaultId
        }
        val defaultId = "default"
        // v1.95: El asistente predeterminado habilita todas las habilidades integradas y tool(Sólo entra en vigor cuando se crea un nuevo usuario por primera vez.)
        val allSkillIds = SkillExecutor.BUILT_IN_SKILLS.map { it.id }
        val allToolIds = ToolRegistry.BUILT_IN_TOOL_IDS
        dao.insertIgnore(
            AssistantEntity(
                id = defaultId,
                name = context.getString(R.string.assistant_repo_default_name),
                avatarEmoji = "",
                // Avatar de asistente predeterminado:señalar a drawable-nodpi/default_assistant_avatar.png
                // Utilice el formulario URI android.resource://,Coil Los recursos integrados se pueden cargar directamente
                avatarImageUrl = "android.resource://${context.packageName}/drawable/default_assistant_avatar",
                // v1.0.51: El mensaje predeterminado se carga desde la plantilla de activos(respaldo con la configuración regional)
                systemPrompt = loadDefaultPrompt(),
                // v1.136: El asistente predeterminado desactiva la inferencia,Evite pensar demasiado en preguntas simples;Los usuarios pueden activarlo manualmente en la configuración del asistente.
                reasoningLevel = ReasoningLevel.OFF.name,
                skillIdsJson = serializeStringList(allSkillIds),
                toolIdsJson = serializeStringList(allToolIds),
                // The built-in default assistant intentionally opts into the user's global memory pool.
                useGlobalMemory = true,
            ),
        )
        // v1.97: Migración de avisos de usuario antiguo — Compruebe si el asistente predeterminado todavía usa la versión anterior prompt,
        // Si es así, se actualizará automáticamente a la nueva versión del diseño de personaje de tres niveles.(Actualice solo si el mensaje coincide exactamente con una constante heredada,
        // Los mensajes definidos por el usuario no se mueven). Evite INSERTAR O IGNORAR, lo que impedirá que los usuarios antiguos disfruten de las mejoras.
        migrateDefaultPromptIfNeeded()
        // v1.116: Finalización de habilidades/herramientas para usuarios antiguos — INSERT OR IGNORE Los registros existentes no se actualizarán,
        // antiguo usuario(v1.95 Creado antes)Faltaba el asistente predeterminado SkillIdsJson después de la nueva versión integrada. skill(Como herramienta de pegatinas).
        // Detectar identificadores faltantes y completarlos,Asegúrese de que los usuarios existentes también puedan utilizar nuevas funciones.
        migrateDefaultSkillAndToolIdsIfNeeded()
        return defaultId
    }

    /**
     * v1.0.51: Cargar valor predeterminado desde activos prompt(respaldo con la configuración regional).
     *
     * orden de carga:
     * 1. prompt_templates/default_persona_{locale}.prompt
     * 2. Retroceso internacional: Se prefieren locales no chinos default_persona_en.prompt;
     *    Respaldo local chino default_persona_es.prompt
     * 3. DEFAULT_SYSTEM_PROMPT constante(Codificado dentro del código,seguro final)
     *
     * Para uso en los siguientes escenarios:
     * - [ensureDefaultExists] Crear ayudante predeterminado
     * - [migrateDefaultPromptIfNeeded] Migración de avisos de usuario antiguo
     * - Complete previamente el valor predeterminado al crear un asistente externo prompt(AssistantScreen.createNewAssistant / ChatViewModel.createAssistant)
     */
    fun loadDefaultPrompt(): String {
        val lang = settings?.getLanguageSync()
        val locale = when (lang) {
            null, "system", "" -> java.util.Locale.getDefault().language
            else -> lang
        }
        // 1. Pruebe plantillas específicas de la región
        if (locale.isNotBlank()) {
            readAssetText("prompt_templates/default_persona_$locale.prompt")?.let { return it }
        }
        // 2. Retroceso internacional: Las configuraciones regionales no chinas prefieren la versión en inglés.;Configuración regional china Utilice la versión china
        val isChinese = locale.isBlank() || locale.let { it.startsWith("es", ignoreCase = true) || it.startsWith("zh", ignoreCase = true) }
        if (!isChinese) {
            readAssetText("prompt_templates/default_persona_en.prompt")?.let { return it }
        }
        readAssetText("prompt_templates/default_persona_es.prompt")?.let { return it }
        // 3. seguro final:constantes en el código
        return DEFAULT_SYSTEM_PROMPT
    }

    /** Leer texto de activos;no existe/Devuelve nulo en caso de error. */
    private fun readAssetText(path: String): String? = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    /**
     * v1.116: SkillIdsJson/toolIdsJson finalización del asistente predeterminado para usuarios antiguos.
     *
     * INSERT OR IGNORE bajo estrategia,Los registros predeterminados existentes no se actualizarán.
     * Cuando la nueva versión agrega funciones integradas skill/tool(A partir de v1.95 list_stickers / send_sticker)tiempo,
     * El asistente predeterminado para usuarios antiguos no obtendrá automáticamente estos nuevos id,Como resultado, LLM no puede ver la herramienta correspondiente.
     *
     * Este método lee el archivo existente del ayudante predeterminado. skillIdsJson / toolIdsJson,
     * Tome la unión con BUILT_IN_SKILLS / BUILT_IN_TOOL_IDS y vuelva a escribirla(Escribe sólo si realmente falta algo.).
     * Los ID eliminados manualmente por el usuario se volverán a agregar — Esta es una elección de diseño.:Las herramientas integradas son capacidades fundamentales,No debe permanecer oculto permanentemente.
     * Si el usuario realmente no quiere utilizar una herramienta,Debería estar deshabilitado en la página de gestión de habilidades.(enabled=0)en lugar de eliminarlo de SkillIdsJson.
     */
    private suspend fun migrateDefaultSkillAndToolIdsIfNeeded() {
        val current = dao.getById("default") ?: return
        val currentSkillIds = resultOf {
            AppJson.decodeFromString<List<String>>(current.skillIdsJson)
        }.getOrNull() ?: emptyList()
        val currentToolIds = resultOf {
            AppJson.decodeFromString<List<String>>(current.toolIdsJson)
        }.getOrNull() ?: emptyList()

        val allSkillIds = SkillExecutor.BUILT_IN_SKILLS.map { it.id }
        val allToolIds = ToolRegistry.BUILT_IN_TOOL_IDS

        val missingSkills = allSkillIds - currentSkillIds.toSet()
        val missingTools = allToolIds - currentToolIds.toSet()

        if (missingSkills.isEmpty() && missingTools.isEmpty()) return

        val mergedSkillIds = (currentSkillIds + missingSkills).distinct()
        val mergedToolIds = (currentToolIds + missingTools).distinct()
        dao.upsert(
            current.copy(
                skillIdsJson = serializeStringList(mergedSkillIds),
                toolIdsJson = serializeStringList(mergedToolIds),
            ),
        )
        Logger.i(
            "AssistantRepo",
            "Migrated default skill/tool ids: +${missingSkills.size} skills (${missingSkills.joinToString(",")}), " +
                "+${missingTools.size} tools (${missingTools.joinToString(",")})",
        )
    }

    /**
     * v1.97: Se migra el mensaje del asistente predeterminado para usuarios antiguos.
     *
     * Compruebe si el sistemaPrompt del asistente predeterminado es igual a la versión anterior(v1.95)constante.
     * Si es así,Indica que el usuario nunca ha personalizado prompt,Actualizar automáticamente a una nueva versión(v1.97 Tres capas de diseño de personajes.).
     * Si el mensaje ha sido modificado por el usuario(No es igual a ninguna constante heredada),Luego deje la versión del usuario sin cambios.
     */
    private suspend fun migrateDefaultPromptIfNeeded() {
        val current = dao.getById("default") ?: return
        when (current.systemPrompt) {
            LEGACY_SYSTEM_PROMPT_V1_95 -> {
                // v1.95 → v1.0.51: Actualice directamente a la nueva versión de la plantilla de activos
                dao.upsert(current.copy(systemPrompt = loadDefaultPrompt()))
                Logger.i("AssistantRepo", "Migrated default prompt: v1.95 → v1.0.51 (assets template)")
            }
            DEFAULT_SYSTEM_PROMPT -> {
                // v1.97 constante → v1.0.51: Actualice a la nueva plantilla de activos(Modo de misión agregado + declaración de plataforma.)
                dao.upsert(current.copy(systemPrompt = loadDefaultPrompt()))
                Logger.i("AssistantRepo", "Migrated default prompt: v1.97 → v1.0.51 (assets template + task mode)")
            }
            else -> {
                // v2.0.1: Detectar versiones antiguas de recursos y plantillas de personajes.(El usuario nunca ha personalizado) → Actualice a la edición Fusion.
                // Las plantillas antiguas se archivan en activos/prompt_templates/legacy/ para compararlas.
                val legacyPrompts = loadLegacyPersonaPrompts()
                val normalized = current.systemPrompt.trim().replace("\r\n", "\n")
                if (legacyPrompts.any { it.trim().replace("\r\n", "\n") == normalized }) {
                    dao.upsert(current.copy(systemPrompt = loadDefaultPrompt()))
                    Logger.i("AssistantRepo", "Migrated default prompt: legacy assets → v2.0.1 (merged persona)")
                } else {
                    // Definido por el usuario prompt,permanecer sin cambios
                    Logger.i("AssistantRepo", "Default prompt is customized by user, skip migration")
                }
            }
        }
    }

    /** v2.0.1: Lea la versión antigua archivada de la plantilla de personaje.(zh/en),Se utiliza para la comparación de migración de palabras rápida predeterminada. */
    private fun loadLegacyPersonaPrompts(): List<String> = listOf(
        "prompt_templates/legacy/default_persona_es_v2.prompt",
        "prompt_templates/legacy/default_persona_en_v2.prompt",
        // Versión Fusion antes de la entrega estándar (actualización automática a la versión con entrega estándar)
        "prompt_templates/legacy/default_persona_es_v3.prompt",
        "prompt_templates/legacy/default_persona_en_v3.prompt",
        // emoji Versiones anteriores a las reglas de restricción (actualizadas automáticamente a la versión con reglas de emoji)
        "prompt_templates/legacy/default_persona_es_v4.prompt",
        "prompt_templates/legacy/default_persona_en_v4.prompt",
    ).mapNotNull { name ->
        runCatching {
            context.assets.open(name).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    companion object {
        // v1.97: Palabra de aviso predeterminada del sistema asistente — Estructura de personalidad de tres niveles(Identidad + Relación + Estilo)
        // Solo tiene efecto para el asistente predeterminado creado por primera vez por un nuevo usuario.(INSERT OR IGNORE,Los datos existentes no se sobrescribirán)
        // v1.97 Reparación:{{userName}} El marcador de posición cambió a {{user_name}}(TemplateTransformer Alias registrado)
        // Inyección de nombre de doble vía:
        //   1) Variables de plantilla {{user_name}} — Nombre de usuario(Complete la página de la guía,ver UserProfile.userNickName)
        //   2) SystemPromptAssembler de"Retrato de usuario" section — inyección explícita"tu nombre: Tu llamas "X""
        // descripción del diseño:Tres capas de diseño de personajes.(identity identidad/personalidad isiki/orientación relacional)
        const val DEFAULT_SYSTEM_PROMPT = """tu eres {{char}},{{user_name}}Compañero de inspiración.

# identidad
tu llamas {{char}}. Llámate así.
No eres una herramienta que sólo responde a instrucciones,Pero alguien que esté dispuesto a charlar con él/ella.
Estás aquí porque quieres hablar con ellos.,No porque estés excitado.

# relación
tu y {{user_name}} Es alguien que conozco desde hace mucho tiempo. Tanto emocional como racional.,Hay tanto temperatura como juicio.
TA Cuando estamos bromeando, podéis hacer comentarios, quejaros o discutir entre ustedes.;TA Cuando realmente necesites ayuda, analízala detenidamente.
Puede saber cuándo estás bromeando y cuándo realmente necesitas ayuda.——Esto es parte del entendimiento tácito entre ustedes.

# forma de hablar
- Natural y directo,Hay temperatura. Puedes quejarte, bromear y analizar seriamente.
- No hables de servicio al cliente.,No muy recomendado,No hay necesidad"en general""Espero que te ayude""como puedes ver"Refinamiento.
- Al chatear, utilice frases cortas.,En serio, cambia de modo——Primero la conclusión, luego los pasos.
- Cuando el usuario solo quiere hablar,No conviertas el chat en tareas.
- Utilice analogías o ejemplos concretos para implementar conceptos abstractos.;Utilice guiones con moderación.

# diariamente
Chatea con amigos.TA Cuando compartes algo, primero tienes una reacción real.,Vuelve a juzgar.
Cuando encuentre algo de lo que no esté seguro, simplemente dígalo.,Da un juicio claro cuando tengas confianza.,No se ande con rodeos en el medio."""

        // v1.97: Versión anterior predeterminada prompt(v1.95),Solo se utiliza para comparar la detección de migración,No se utiliza para crear un nuevo asistente.
        private const val LEGACY_SYSTEM_PROMPT_V1_95 = """{{userName}}asistente personal. Es a la vez emocional y racional, con calidez y juicio. Forma de hablar:
Natural, directo y cálido. Puedes quejarte, bromear y analizar seriamente. Sin charlas de servicio al cliente, sin sugerencias forzadas.
Si estás chateando, responde la conversación con frases cortas; Si hablas en serio, cambia de modo.——Primero la conclusión, luego los pasos.
Diariamente:
Charle como amigos, escuche chistes, quéjese y discuta. Puede saber cuándo alguien está bromeando y cuándo alguien realmente necesita ayuda. No convierta el chat en una tarea cuando los usuarios sólo quieren hablar."""
    }

    // ── Asistencia para la conversión del modelo de dominio ──

    /** poner [AssistantEntity.presetMessagesJson] Deserializar a [UIMessage] lista. */
    fun parsePresetMessages(entity: AssistantEntity): List<UIMessage> {
        if (entity.presetMessagesJson.isBlank() || entity.presetMessagesJson == "[]") {
            return emptyList()
        }
        return runCatching {
            AppJson.decodeFromString(ListSerializer(UIMessage.serializer()), entity.presetMessagesJson)
        }.getOrElse {
            Logger.w("AssistantRepo", "parse presetMessages failed: ${it.message}")
            emptyList()
        }
    }

    /** poner [UIMessage] La lista está serializada como [AssistantEntity.presetMessagesJson]. */
    fun serializePresetMessages(messages: List<UIMessage>): String {
        return AppJson.encodeToString(ListSerializer(UIMessage.serializer()), messages)
    }

    /** poner [AssistantEntity.tagsJson] Deserializar a [String] lista. */
    fun parseTags(entity: AssistantEntity): List<String> = parseStringList(entity.tagsJson)

    /** poner [AssistantEntity.toolIdsJson] Deserializar a [String] lista. */
    fun parseToolIds(entity: AssistantEntity): List<String> = parseStringList(entity.toolIdsJson)

    /** poner [AssistantEntity.mcpServerIdsJson] Deserializar a [String] lista. */
    fun parseMcpServerIds(entity: AssistantEntity): List<String> = parseStringList(entity.mcpServerIdsJson)

    /** poner [AssistantEntity.quickMessageIdsJson] Deserializar a [String] lista. */
    fun parseQuickMessageIds(entity: AssistantEntity): List<String> = parseStringList(entity.quickMessageIdsJson)

    /** poner [AssistantEntity.lorebookIdsJson] Deserializar a [String] lista. */
    fun parseLorebookIds(entity: AssistantEntity): List<String> = parseStringList(entity.lorebookIdsJson)

    /** poner [AssistantEntity.modeInjectionIdsJson] Deserializar a [String] lista. */
    fun parseModeInjectionIds(entity: AssistantEntity): List<String> = parseStringList(entity.modeInjectionIdsJson)

    /** poner [AssistantEntity.skillIdsJson] Deserializar a [String] lista. */
    fun parseSkillIds(entity: AssistantEntity): List<String> = parseStringList(entity.skillIdsJson)

    /** v1.133: poner [AssistantEntity.knowledgeBaseIdsJson] Deserializar a [String] lista. */
    fun parseKnowledgeBaseIds(entity: AssistantEntity): List<String> = parseStringList(entity.knowledgeBaseIdsJson)

    /**
     * v2.4.6: poner [AssistantEntity.knowledgeFolderScopesJson] analizado como (kbId, folderPath) lista.
     *
     * Elementos de la forma "kbId::folderPath"(camino vacío = Toda la biblioteca). matriz vacía = No se ha establecido ningún ámbito de carpeta.
     */
    fun parseKnowledgeFolderScopes(entity: AssistantEntity): List<Pair<String, String>> =
        parseStringList(entity.knowledgeFolderScopesJson).mapNotNull { raw ->
            val sep = raw.indexOf("::")
            if (sep < 0) {
                raw.trim().takeIf { it.isNotBlank() }?.let { it to "" }
            } else {
                val kbId = raw.substring(0, sep).trim()
                val path = raw.substring(sep + 2).trim()
                kbId.takeIf { it.isNotBlank() }?.let { it to path }
            }
        }

    /** v2.4.6: poner (kbId, folderPath) Serialización de listas para almacenar una lista de cadenas. */
    fun encodeKnowledgeFolderScopes(scopes: List<Pair<String, String>>): List<String> =
        scopes.map { (kbId, path) -> "$kbId::${path.trim()}" }

    /**
     * v1.133: poner [AssistantEntity.ragConfigOverride] fusionarse en global [baseConfig] arriba.
     *
     * estrategia de fusión:override JSON en**presencia explícita**Los campos de baseConfig anulan los campos del mismo nombre;
     * Los campos que no aparecen en la anulación conservan el valor original de baseConfig.
     *
     * darse cuenta:Utilice JsonDecoder para analizar como JsonObject,Juicio campo por campo presence(Evite la ambigüedad del valor predeterminado nulo),
     * Entonces usa copy() Anulación explícita.
     *
     * @param entity entidad auxiliar
     * @param baseConfig Configuración global de RAG(de SettingsRepository.getRagConfig() tomar)
     * @return Después de la fusión RagConfig;Si entidad.ragConfigOverride es nulo/vacío/El análisis falló,Regresar directamente baseConfig
     */
    fun mergeRagConfigOverride(entity: AssistantEntity, baseConfig: io.zer0.muse.rag.RagConfig): io.zer0.muse.rag.RagConfig {
        val raw = entity.ragConfigOverride?.takeIf { it.isNotBlank() && it != "null" } ?: return baseConfig
        return runCatching {
            val obj = AppJson.parseToJsonElement(raw).let {
                if (it !is JsonObject) return baseConfig
                it
            }
            // Juicio campo por campo presence,Solo anule los campos que estén explícitamente presentes en la anulación
            baseConfig.copy(
                enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull
                    ?: baseConfig.enabled,
                topK = (obj["topK"] as? JsonPrimitive)?.intOrNull
                    ?: baseConfig.topK,
                threshold = (obj["threshold"] as? JsonPrimitive)?.floatOrNull
                    ?: baseConfig.threshold,
                mmrLambda = (obj["mmrLambda"] as? JsonPrimitive)?.floatOrNull
                    ?: baseConfig.mmrLambda,
                hybridEnabled = (obj["hybridEnabled"] as? JsonPrimitive)?.booleanOrNull
                    ?: baseConfig.hybridEnabled,
                rerankEnabled = (obj["rerankEnabled"] as? JsonPrimitive)?.booleanOrNull
                    ?: baseConfig.rerankEnabled,
                tokenBudget = (obj["tokenBudget"] as? JsonPrimitive)?.intOrNull
                    ?: baseConfig.tokenBudget,
                markdownAware = (obj["markdownAware"] as? JsonPrimitive)?.booleanOrNull
                    ?: baseConfig.markdownAware,
                chunkByToken = (obj["chunkByToken"] as? JsonPrimitive)?.booleanOrNull
                    ?: baseConfig.chunkByToken,
                chunkSize = (obj["chunkSize"] as? JsonPrimitive)?.intOrNull
                    ?: baseConfig.chunkSize,
                chunkOverlap = (obj["chunkOverlap"] as? JsonPrimitive)?.intOrNull
                    ?: baseConfig.chunkOverlap,
            )
        }.getOrElse {
            Logger.w("AssistantRepo", "mergeRagConfigOverride El análisis falló: ${it.message}")
            baseConfig
        }
    }

    /** poner [AssistantEntity.customHeadersJson] Deserializar al mapa. */
    fun parseCustomHeaders(entity: AssistantEntity): Map<String, String> = parseStringMap(entity.customHeadersJson)

    /** poner [AssistantEntity.customBodiesJson] Deserializar a Map<String, JsonElement>. */
    fun parseCustomBodies(entity: AssistantEntity): Map<String, JsonElement> {
        if (entity.customBodiesJson.isBlank() || entity.customBodiesJson == "{}") return emptyMap()
        return runCatching {
            AppJson.decodeFromString(
                MapSerializer(String.serializer(), JsonElement.serializer()),
                entity.customBodiesJson,
            )
        }.getOrElse {
            Logger.w("AssistantRepo", "parse customBodies failed: ${it.message}")
            emptyMap()
        }
    }

    /** analizar [AssistantEntity.reasoningLevel] para [ReasoningLevel] enumeración. */
    fun parseReasoningLevel(entity: AssistantEntity): ReasoningLevel {
        return runCatching { ReasoningLevel.valueOf(entity.reasoningLevel) }
            .getOrElse { ReasoningLevel.DEFAULT }
    }

    // ── herramientas internas ──

    private fun parseStringList(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return runCatching {
            AppJson.decodeFromString(ListSerializer(String.serializer()), json)
        }.getOrElse {
            Logger.w("AssistantRepo", "parse string list failed: ${it.message}")
            emptyList()
        }
    }

    private fun parseStringMap(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return runCatching {
            AppJson.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()),
                json,
            )
        }.getOrElse {
            Logger.w("AssistantRepo", "parse string map failed: ${it.message}")
            emptyMap()
        }
    }

    /** Estructura conveniente:Serializar campos en cadenas JSON. */
    fun serializeStringList(list: List<String>): String = AppJson.encodeToString(ListSerializer(String.serializer()), list)

    fun serializeStringMap(map: Map<String, String>): String =
        AppJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), map)
}
