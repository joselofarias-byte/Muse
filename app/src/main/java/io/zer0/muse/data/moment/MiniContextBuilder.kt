package io.zer0.muse.data.moment

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.transformer.LorebookTransformer
import io.zer0.muse.transformer.PromptInjectionTransformer
import io.zer0.muse.transformer.SystemPromptAssembler
import io.zer0.muse.transformer.ThinkTagTransformer
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.transformer.TransformerPipeline
import kotlinx.serialization.json.Json

/**
 * Teléfonos celulares pequeños (conversaciones de mensajes privados/mensajes activos/dinámicas) y similares"generación de bypass"constructor de contexto.
 *
 * Por qué lo necesitas:
 * Estos escenarios anteriormente solo se hacían `"tu eres $name" + Materiales` Concatenación de cadenas de tres partes, sin pasar por completo
 * [SystemPromptAssembler], llevando a**Libro mundial, memoria, retrato de usuario, inyección rápida de palabras, reglas regulares,MOOD,
 * Guía de memes**Todos son ineficaces. Un mismo personaje mostrará dos personalidades en el diálogo principal y en el teléfono celular.
 *
 * Diferencias con el enlace principal:
 * - no tocar [io.zer0.muse.ui.ChatViewModel](3000+ Colección de estados mutables de fila y mantenimiento del alcance de la rutina y el estado de la interfaz de usuario,
 *   Nunca pase a un segundo plano Trabajador/tarea programada)
 * - no corras MemoryInjection / TimeReminder / ContextCompress espera"Historial de conversación relacionado"enlace,
 *   No hay historial de generación de omisiones; ejecutarlas solo generará presupuestos inactivos o incorrectos.
 * - solo corre con"consistencia de personalidad"Tres transformadores directamente relacionados: libro mundial, inyección rápida de palabras,think pelado de etiquetas
 *
 * Modelo de subprocesos: todos los métodos están suspendidos y la lectura interna es una llamada única desde un subproceso, que se puede encontrar en
 * WorkManager Úselo directamente en CoroutineWorker (Koin analizado a través de GlobalContext).
 */
class MiniContextBuilder(
    private val systemPromptAssembler: SystemPromptAssembler,
    private val lorebookRepository: LorebookRepository,
    private val promptInjectionRepository: PromptInjectionRepository,
    private val settingsRepository: SettingsRepository,
) {

    private val idListJson = Json { ignoreUnknownKeys = true }

    /**
     * Cree la lista completa de mensajes necesarios para una generación de omisión.
     *
     * @param assistant Identidad del personaje (incluido el diseño de personajes, encuadernación de libros mundiales, encuadernación por inyección rápida de palabras).null Degenera en el asistente predeterminado.
     * @param userContent Los materiales específicos que se entregarán al modelo en esta ronda (el último mensaje del usuario en la conversación/el origen del mensaje activo/materiales dinámicos)
     * @param sessionId Anulable; solo se usa para la transmisión transparente de TransformContext, la generación de derivación generalmente no tiene una sesión real
     * @param voiceMessageEnabled Si se debe agregar el acuerdo del mensaje de voz al final del comando del sistema. Sólo los pequeños mensajes privados de teléfono celular requieren:
     *        No hay salida de voz para mensajes dinámicos y activos, y las etiquetas solo contaminarán el texto principal, por lo que están desactivadas de forma predeterminada.
     * @return en forma de [system, ..., user] La lista de mensajes se puede entregar directamente a `ChatService.completeText`
     */
    suspend fun buildMessages(
        assistant: AssistantEntity?,
        userContent: String,
        sessionId: String? = null,
        voiceMessageEnabled: Boolean = false,
    ): List<UIMessage> {
        val systemMessages = buildSystemMessages(assistant, voiceMessageEnabled)
        val userMessage = UIMessage(
            role = MessageRole.USER,
            content = userContent,
            createdAt = System.currentTimeMillis(),
        )
        // Libro mundial/Inyección rápida de palabras requerida"Último mensaje de USUARIO"Como texto de concordancia de palabras clave,
        // Por lo tanto, primero se reúne la lista original y luego se entrega a la canalización para su procesamiento.
        val raw = systemMessages + userMessage
        return applyTransformers(raw, assistant, sessionId)
    }

    /**
     * Reciba el mensaje de aviso del sistema.
     *
     * `forSubagent = true` Motivo: La generación de derivación es"Tarea única aislada", no debería formar parte de la plantilla del equipo,
     * Lista de herramientas de delegación,Agent Resumen de la bandeja de entrada——Esos pertenecen sólo al diálogo principal. Pero aún conserva su personalidad básica,
     * Libro mundial e inyección de memoria, esto es lo que compensará esta restauración.
     *
     * convención de voz ([voiceMessageInstruction]) es el último en el mensaje del sistema: El Libro de la Personalidad y el Mundo establece el personaje primero,
     * Las instrucciones para la clase de formato de salida se siguen detalladamente y el modelo es más sensible a restricciones cercanas a los mensajes del usuario.
     */
    private suspend fun buildSystemMessages(assistant: AssistantEntity?, voiceMessageEnabled: Boolean): List<UIMessage> {
        val memoryEnabled = assistant?.memoryEnabled ?: true
        val timeReminderEnabled = assistant?.enableTimeReminder ?: true
        val base = resultOf {
            systemPromptAssembler.build(
                assistant = assistant,
                memoryEnabled = memoryEnabled,
                timeReminderEnabled = timeReminderEnabled,
                forSubagent = true,
            )
        }.onError { msg, t ->
            Logger.w(TAG, "El indicador del sistema de compilación falló y se degradó a un indicador del sistema vacío.: ${t?.message ?: msg}")
        }.getOrNull() ?: emptyList()
        if (!voiceMessageEnabled) return base
        // Cuando el sistema le indique que la compilación falló, aplique la convención como de costumbre: sin ella, el modelo no generará etiquetas y la función de voz no existe.
        return base + UIMessage(role = MessageRole.SYSTEM, content = voiceMessageInstruction())
    }

    /** Ejecute únicamente Transformers relacionados con la coherencia de la personalidad. */
    private suspend fun applyTransformers(messages: List<UIMessage>, assistant: AssistantEntity?, sessionId: String?): List<UIMessage> {
        val lorebookEntries = loadLorebookEntries(assistant)
        val modeInjections = loadPromptInjections(assistant)
        if (lorebookEntries.isEmpty() && modeInjections.isEmpty()) {
            // Cuando no hay contenido inyectable, la etiqueta de pensamiento aún debe eliminarse para evitar que el texto de inferencia se mezcle con el texto principal.
            return runCatching { ThinkTagTransformer().transform(messages, emptyContext(sessionId, assistant)) }
                .getOrElse { messages }
        }

        val pipeline = TransformerPipeline(
            listOf(
                LorebookTransformer(lorebookRepository),
                PromptInjectionTransformer(),
                ThinkTagTransformer(),
            ),
        )
        val context = TransformContext(
            sessionId = sessionId,
            modelId = assistant?.modelId,
            temperature = assistant?.temperature,
            maxTokens = assistant?.maxTokens,
            extras = buildMap {
                put("lorebook_entries", lorebookEntries)
                put("prompt_injections", modeInjections)
                // Retrato de usuario → Variable de plantilla (consistente con la clave de ChatStreamCoordinator)
                val profile = resultOf { settingsRepository.getUserProfile() }.getOrNull()
                put("user_nickname", profile?.userNickName)
                put("assistant_name", profile?.assistantName ?: assistant?.name)
                // Estos no tienen sentido en la generación de derivación y deben desactivarse explícitamente para evitar errores de juicio por parte de Transformer.
                put("memory_enabled", false)
                put("time_reminder_enabled", false)
            },
        )
        return resultOf { pipeline.execute(messages, context) }
            .onError { msg, t -> Logger.w(TAG, "Transformer La canalización falla y se degrada al mensaje original: ${t?.message ?: msg}") }
            .getOrNull() ?: messages
    }

    private fun emptyContext(sessionId: String?, assistant: AssistantEntity?) = TransformContext(
        sessionId = sessionId,
        modelId = assistant?.modelId,
    )

    /** Vuelva a consultar los asientos del libro mundial encuadernados por el asistente (solo se toman los habilitados, de acuerdo con el enlace principal). */
    private suspend fun loadLorebookEntries(assistant: AssistantEntity?): List<LorebookEntity> {
        val ids = parseIdList(assistant?.lorebookIdsJson)
        if (ids.isEmpty()) return emptyList()
        return resultOf { lorebookRepository.getByIdsEnabled(ids) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo leer la entrada del libro mundial: ${t?.message ?: msg}") }
            .getOrNull() ?: emptyList()
    }

    /**
     * Verifique las entradas de inyección de palabras solicitadas encuadernadas por el asistente.
     *
     * La generación de bypass no"Modo actual"concepto, por lo tanto**Sin filtrado de modo**——El ayudante está explícitamente vinculado a un modo.
     * Si el objeto inyectado se ingiere en un teléfono celular pequeño, el usuario pensará"La configuración no tiene efecto". Es mejor inyectar más que desechar en silencio.
     * Vuelve a los elementos globales habilitados en este modo cuando no está vinculado (tampoco se filtra por modo).
     */
    private suspend fun loadPromptInjections(assistant: AssistantEntity?): List<PromptInjectionEntity> {
        val ids = parseIdList(assistant?.modeInjectionIdsJson)
        if (ids.isNotEmpty()) {
            return resultOf { promptInjectionRepository.getByIdsEnabled(ids) }
                .onError { msg, t -> Logger.w(TAG, "Falló la inyección de palabras en el mensaje de lectura: ${t?.message ?: msg}") }
                .getOrNull() ?: emptyList()
        }
        val mode = DEFAULT_PROMPT_MODE
        return resultOf { promptInjectionRepository.getEnabledByMode(mode) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo leer la inyección de palabra de aviso global: ${t?.message ?: msg}") }
            .getOrNull() ?: emptyList()
    }

    /** Misma semántica que ChatViewModel.parseIdList. */
    private fun parseIdList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { idListJson.decodeFromString<List<String>>(json) }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "MiniContext"

        /** El modo de palabra de aviso predeterminado utilizado por la generación de omisión (consistente con el enlace predeterminado del asistente). */
        private const val DEFAULT_PROMPT_MODE = "default"
    }
}

/**
 * Convención de salida de voz para mensajes privados en teléfonos celulares pequeños.
 *
 * Prensa del lado anfitrión `[Voz]……[/Voz]` Segmentación: el contenido de la marca se sintetiza en voz y el contenido fuera de la marca se trata como texto ordinario;
 * Se podrán sintetizar un máximo de 5 párrafos en una sola ronda. Se omitirán los párrafos sin caracteres chinos en las marcas. Estas reglas no se pueden descubrir por sí mismas en el lado del modelo,
 * Por lo tanto, la directiva del sistema debe escribirse explícitamente; de ​​lo contrario, el modelo nunca generará el marcador.
 *
 * Solo se usa para la ruta de mensajes privados: los mensajes dinámicos y activos también usan la misma ruta [MiniContextBuilder], pero no tienen salida de voz.
 */
internal fun voiceMessageInstruction(): String = buildString {
    appendLine("<voice_message>")
    appendLine("Úsalo si quieres hablar líneas. [Voz] comienzo,[/Voz] Envuelva el final con un par de corchetes. El sistema solo sintetiza el contenido de la marca en voz y lo envía. La otra parte escucha el sonido y no puede ver la marca; el texto fuera de la marca se envía como mensaje como de costumbre.")
    appendLine("- Máximo 5 párrafos a la vez.")
    appendLine("- No incluya narraciones, descripciones de acciones, actividades psicológicas ni texto original en inglés: estos no se leerán en voz alta y el párrafo en inglés puro se omitirá directamente.")
    appendLine("- No es necesario hablar cada turno. Úselo sólo cuando tenga emociones fuertes, palabras cortas y quiera que la otra parte lo escuche.")
    appendLine("- No anide etiquetas ni las reutilice entre párrafos: un par de etiquetas para una línea y luego guárdelas después de terminar de hablar.")
    appendLine("</voice_message>")
}.trim()
