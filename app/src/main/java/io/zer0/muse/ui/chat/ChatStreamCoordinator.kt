package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.Logger
import io.zer0.common.ProcessWriteGate
import io.zer0.common.resultOf
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.R
import io.zer0.muse.data.ExperimentsConfig
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.privacy.PiiGuard
import io.zer0.muse.session.WarmupHistory
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.transformer.InternalMarkupSanitizer
import io.zer0.muse.transformer.MoodSkinParser
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.transformer.TransformerPipeline
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.util.MusePatterns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.Uuid

/**
 * v1.131: <think>...</think> La etiqueta regex se ha migrado a io.zer0.muse.util.MusePatterns.THINK_TAG_REGEX.
 */

/**
 * v2.6.x: El calibre de la ventana de contexto circular actual**salida única**.
 *
 * fondo:UI lado [io.zer0.muse.ui.ChatUiState.contextMaxTokens] por
 * `ChatViewModel.refreshContextInfo` prensa**Modelo seleccionado globalmente**Descúbrelo. Las solicitudes reales pueden ser anuladas por sesión /
 * El asistente se une a otro modelo. —— En este punto, 90% de truncamiento agresivo,1.35 Los presupuestos de intercepción dura, calentamiento y compresión serán todos
 * **otro modelo**cálculo de ventana(El modelo de ventana pequeña se trata como una ventana grande. → por encima del límite 400;Los modelos de ventanas grandes se tratan como ventanas pequeñas.
 * → Compresión innecesaria, pérdida de contexto.).
 *
 * Se da prioridad al uso de esta ronda aquí. [StreamRunState.effectiveModel] ventana;Se utiliza cuando el modelo no está declarado.
 * [ModelContextWindowRegistry] Averigüe por identificación;Si no puede obtenerlo, recurrirá al que le pasó la persona que llama.
 * [fallbackTokens](Es decir, el valor calculado a partir del modelo global en el lado de la interfaz de usuario.,mantener el viejo comportamiento).
 */
internal fun effectiveContextWindowTokens(model: Model?, fallbackTokens: Int): Int {
    val declared = model?.contextWindow?.takeIf { it > 0 }
    if (declared != null) return declared
    val inferred = model?.id?.let { ModelContextWindowRegistry.lookup(it) }?.takeIf { it > 0 }
    return inferred ?: fallbackTokens
}

/**
 * El modelo realmente vinculado a esta ronda de solicitudes es el mismo que el del Proveedor ([ChatStreamCoordinator.resolveModelBinding] valor de retorno).
 *
 * poner“Solicitado provider/model”con“Qué está realmente disponible después del análisis Model”Ponlo en el mismo valor,
 * dejar que la ventana de contexto calibre ([effectiveContextWindowTokens]) y herramientas/El enrutamiento visual comparte los mismos resultados de análisis.
 */
internal data class GenerationModelBinding(
    val requestedProviderId: String?,
    val requestedModelId: String?,
    /** Después del análisis, el modelo se envía realmente al nivel ascendente (se ha aplicado prioridad de proveedor activada + copia de seguridad de tres estados). */
    val model: Model?,
    /** con [model] Configuración del proveedor correspondiente (con anulaciones personalizadas de encabezado/cuerpo de nivel auxiliar aplicadas). */
    val providerConfig: io.zer0.ai.core.ProviderConfig?,
)

/**
 * v1.105 Etapa 3: Coordinador auxiliar de streaming extraído de ChatViewModel.
 *
 * Responsabilidades:
 *  - detachStreaming: Cortar páginas/Desactivar la transmisión al cambiar de sesión UI(No detener la generación de fondo)
 *  - notifySessionEndForCurrent: Notificar a MemoryTicker que la sesión actual finaliza
 *  - updateAssistant: Actualizar mensajes del asistente durante la transmisión(Contiene extracción de etiquetas de estado de ánimo/reflexión/pensamiento)
 *  - persistCurrentAssistant: Colocación periódica(Persistencia del progreso en el flujo)
 *  - persistInterruptedAssistant: marca [interrumpido] Y poner parte de la respuesta en el mercado.
 *  - extractThinkContent / extractTagContent: Herramienta de extracción de etiquetas de cadena
 *
 * launchStream sujeto(835 OK)Debido a la captura de una gran cantidad de variables locales y al fuerte acoplamiento con múltiples campos de ChatViewModel,
 * Permanecer en ChatViewModel,Llame al método auxiliar a través de este Coordinador.
 */
// A5: LargeClass exención — El LOC del cuerpo de clase está exactamente en el límite del umbral detekt 600(El volumen histórico se acerca al límite superior,
// A5 Agregar un parámetro de duraciónMs a persistInterruptedAssistant cruzará la línea.). Dividir en tareas de refactorización independientes,
// Nada que ver con esta función.,Explicación de la exención por el momento.(precedente: ToolOrchestrator @Suppress("LongParameterList")).
// v2.x: LongParameterList exento por completo — Este tipo de parámetro constructivo ya ha superado el umbral(15 un),No se informó antes porque
// El límite superior está atrapado en la frontera.;Nuevo checkpointReader(Lectura del punto de control de contexto)Haz que cruce explícitamente la línea. Inyecta dependencias existentes.,
// No es un acoplamiento recién introducido,Dividir los parámetros de construcción es una refactorización que no tiene nada que ver con esta función.
@Suppress("LargeClass", "LongParameterList")
class ChatStreamCoordinator(
    private val accessor: ChatStateAccessor,
    private val sessionRepository: SessionRepository,
    private val memoryTicker: MemoryTicker,
    private val settings: io.zer0.muse.data.SettingsRepository,
    private val appContext: android.content.Context,
    private val notificationManager: io.zer0.muse.notification.MuseNotificationManager,
    private val assistantRepository: io.zer0.muse.data.assistant.AssistantRepository,
    private val visionBridge: io.zer0.muse.vision.VisionBridge,
    private val toolRegistry: ToolRegistry,
    private val skillRepository: SkillRepository,
    private val idListJson: kotlinx.serialization.json.Json,
    private val lorebookRepository: LorebookRepository,
    private val promptInjectionRepository: PromptInjectionRepository,
    private val transformerPipeline: TransformerPipeline,
    private val checkpointReader: io.zer0.memory.summary.ContextCheckpointReader,
    // P1-1: Hook Registro — Llamado después de la ejecución de la canalización PromptFinalizeHook
    private val hookRegistry: io.zer0.muse.hook.HookRegistry? = null,
    // v2.6.2(comentarios de los usuarios): Almacenamiento de políticas de aprobación de herramientas —— Herramientas para deshabilitar usuarios explícitamente
    // Elimínelo del esquema enviado al modelo (anteriormente solo bloqueaba la ejecución, el modelo aún lo ve y lo llama en cada ronda, y vuelve a intentarlo después de ser rechazado).
    private val toolConfigStore: io.zer0.muse.tools.ToolConfigStore? = null,
) {

    /**
     * v2.7 Rendimiento: caché de resultados derivados para actualizaciones de transmisión.
     *
     * Durante la transmisión, el contenido solo se agrega y no se reescribe, por lo que "si la etiqueta existe"mood Extraer resultados
     * 「reasoning Los "resultados de eliminación de etiquetas" no cambiarán después del primer cálculo.
     * Originalmente, estas regularizaciones se ejecutaban en el texto completo de cada cuadro, lo que era la principal fuente de retraso durante las respuestas largas.
     */
    private val streamFlushCache = StreamFlushCache()

    /** MOOD/MOD Hay dos formas de escribir etiquetas: se comparte el cuerpo principal y el canal de razonamiento. */
    private fun containsMoodMarker(text: String): Boolean = text.contains("<mood>", ignoreCase = true) ||
        text.contains("[mood]", ignoreCase = true) ||
        text.contains("<mod>", ignoreCase = true) ||
        text.contains("[mod]", ignoreCase = true)

    private val tag = "ChatVM"

    // Después de cambiar de sesión, la generación anterior seguirá incluyéndose en la biblioteca, pero ya no aparecerá en la lista de mensajes de la interfaz de usuario actual.
    // Limite los registros de frecuencia para el mismo asistente independiente para evitar escribir una advertencia/depuración para cada fragmento de transmisión.
    private val missingAssistantLogAt = java.util.concurrent.ConcurrentHashMap<Uuid, Long>()

    /**
     * v1.0.27 Phase 4-A.2: Constantes de nombre de herramienta de paquete de expresión,Consistente con la colección ChatViewModel del mismo nombre.
     * sticker Tiempo de control de probabilidad,Si no se golpean, estas dos herramientas se filtran de las herramientas.
     */
    private val stickerToolIds = setOf("list_stickers", "send_sticker")

    /**
     * v1.0.27 Phase 4-A.2: id Enumere el asistente de análisis JSON.
     * Semántica consistente con ChatViewModel.parseIdList,Evite las llamadas entre clases.
     */
    private fun parseIdList(json: String): List<String> = runCatching { idListJson.decodeFromString<List<String>>(json) }
        .getOrDefault(emptyList())

    // ── Control del estado de transmisión ──────────────────────────────────────────────────

    /**
     * v1.97: Cortar páginas/Cambiar de sesión/Salga de la transmisión al cambiar de pestaña UI,No detiene la generación de fondo.
     *
     * con stop() la diferencia:
     * - stop():El usuario se detiene activamente,Cancelar conexión SSE + generar corrutina
     * - detachStreaming():Gatillo de corte de página,El cierre generado continúa ejecutándose en appScope
     *   - updateAssistant porque index==-1 Saltar en silencio(No actualizar la sesión de error messages)
     *   - persistCurrentAssistant Utilice contenido de creación para colocarlo directamente en el mercado(no depende de _state.messages)
     *   - Las notificaciones siguen actualizándose normalmente.(notificationManager no depende de _state)
     *   - Cargue el contenido más reciente de la base de datos al volver a la sesión original(Incluye placa de caída intermedia)+ restaurar isStreaming
     */
    fun detachStreaming() {
        accessor.update { it.copy(isStreaming = false) }
    }

    /**
     * Notificar al ticker que la sesión actual finaliza(fire-and-forget).
     *
     * Phase 8.5 Reparación: Implementación original `runBlocking { settings.getSelectedModel() }` Bloquear en el hilo principal,
     * onCleared / createNewSession / switchSession ANR ocurre cuando se llama.
     * Cambiar a:model pasar null(MemoryTicker LaunchTracked interno se ejecuta de forma asincrónica,puede manejar null model
     * escenario de degradación),Elimine runBlocking por completo.
     */
    fun notifySessionEndForCurrent() {
        // v1.136: Agent Para el modo agentSessionId,Para el modo misión currentSessionId
        val state = accessor.snapshot
        val sessionId =
            (if (state.isAgentMode) state.agentSessionId else state.currentSessionId)
                ?: return
        val fallbackMessages = accessor.messagesSnapshot
        if (fallbackMessages.isNotEmpty()) {
            // onCleared cuando viewModelScope está a punto de cancelarse;MemoryTicker usa el tuyo application scope fire-and-forget
            // model pasar null,MemoryTicker La degradación interna utiliza el modelo predeterminado
            memoryTicker.notifySessionEndFromProvider(
                sessionId,
                messagesProvider = {
                    if (!settings.memoryConfigCache.conversationRecallEnabled) {
                        fallbackMessages
                    } else {
                        val dbMessages = sessionRepository.getAllMessagesForWarmup(sessionId)
                        if (dbMessages.isEmpty()) fallbackMessages else dbMessages
                    }
                },
                model = null,
                assistantId = state.currentAssistant?.id ?: "",
            )
        }
    }

    // ── Actualización de noticias ──────────────────────────────────────────────────────

    /**
     * Actualiza los mensajes del asistente durante la transmisión.
     *
     * v1.42: Interruptor de optimización del rendimiento.
     * - true(Transmisión):Toma el camino rápido,Saltar el estado de ánimo/reflexión/pensar en desnudarse regularmente;
     *   Sólo baje a extracción completa si la etiqueta relevante aparece en el contenido.
     * - false(Predeterminado,Finalización de la transmisión o actualización sin transmisión):Realizar extracción y fusión completa de etiquetas,Obtenga el contenido de la presentación final.
     *
     * v1.80 (M-CVM3): Compensaciones de rendimiento explicadas —— Usado aquí messages.toMutableList() hacer O(n) Copiar lista completa,
     * Actualizaciones de alta frecuencia en streaming(cada 40 caracteres)Hay una cierta cantidad de gastos generales. En teoría, cambiar a PersistentList puede evitar la copia,
     * Pero afectará al tipo ChatUiState.messages y a todos los consumidores.(Grandes cambios, altos riesgos).
     * Nivel de mensaje actual(normalmente < cientos)abajo O(n) Copia aceptable,En definitiva, mantenga la solución toMutableList.
     */
    fun updateAssistant(
        id: Uuid,
        content: String,
        reasoning: String? = null,
        imageBase64List: List<String>? = null,
        // v1.80 (M-CVM4): Parámetro imageUrls agregado,Permitir que las imágenes se actualicen simultáneamente en una actualización de estado URL,
        // Evite que la persona que llama actualice por separado después de updateAssistant messages(Copia de lista doble + escritura de doble estado).
        imageUrls: List<String>? = null,
        // v1.135: Video generado con archivo adjunto de mensaje del asistente de soporte URL,MessageBubble Renderice una tarjeta de video reproducible en consecuencia.
        videoFileUri: String? = null,
        isStreaming: Boolean = false,
    ) {
        val messages = accessor.messagesSnapshot
        val index = messages.indexOfFirst { it.id == id }
        // v1.0.21: index==-1 saltar en silencio,No se adjunta ninguna alternativa.
        // v1.125 El anexo alternativo hará que el fondo genere el contenido de transmisión de la sesión anterior después de cambiar de sesión.
        // Agregar a la lista de mensajes de una nueva conversación,Provocando contaminación de mensajes entre sesiones(La causa fundamental de que los mensajes de los usuarios se conviertan en mensajes del asistente).
        // Persistencia generada en segundo plano. persistCurrentAssistant(msg=...) Colocación directa,No depende de _state.messages.
        // Cargue el contenido más reciente de la base de datos al volver a la sesión original(Incluye placa de caída intermedia),No se perderán datos.
        // v1.0.72: Agregar registro — solución de problemas"Generar imágenes/La recepción no aparece después del vídeo."(Actualizaciones perdidas silenciosamente cuando los ID de los mensajes no coinciden)
        // B-26: Después de cambiar de sesión, el fondo genera un mensaje cada 50 ms. warning = tormenta de troncos;La persistencia va
        // persistCurrentAssistant(msg=...) Da el paso,Cargas de actualización en primer plano desde la base de datos,Bajar de categoría para depurar aquí.
        if (index == -1) {
            val now = System.currentTimeMillis()
            val previous = missingAssistantLogAt.put(id, now)
            if (previous == null || now - previous >= 2_000L) {
                val displayedSession =
                    if (accessor.snapshot.isAgentMode) {
                        accessor.snapshot.agentSessionId
                    } else {
                        accessor.snapshot.currentSessionId
                    }
                Logger.d(
                    tag,
                    "updateAssistant saltar detached assistant: id=${id.toString().take(8)}, " +
                        "displayedSession=$displayedSession, " +
                        "contentLength=${content.length}, hasImage=${!imageBase64List.isNullOrEmpty() || !imageUrls.isNullOrEmpty()}, " +
                        "La sesión de destino aún descarta la generación de fondo",
                )
            }
            return
        }
        missingAssistantLogAt.remove(id)
        val msg = messages[index]

        // v2.7: Caché de transmisión del mismo mensaje; se invalida automáticamente cuando el mensaje o contenido se vuelve más corto.

        streamFlushCache.begin(id.toString(), content.length)

        if (!isStreaming) streamFlushCache.invalidateDerived()

        // v1.42: camino rápido — La mayoría de los fragmentos del proceso de transmisión no contienen etiquetas especiales.,Actualizar directamente por índice,Evite recorrer listas completas y expresiones regulares.
        // v1.0.90: El calibre de detección oscila entre"Abrir etiqueta completamente"Cambiar a"Etiqueta de nombre de etiqueta" —— La modelo solo escupe media etiqueta.(<mood)
        // o etiqueta de cierre aislada(</mood>,</think>)En este momento, la vieja lógica juzga erróneamente que el texto ordinario toma el camino rápido.
        // El texto de la etiqueta original aparece en la pantalla. Siempre que llegue a la marca, siga el camino de limpieza completo.
        // v2.7: Si hay un resultado, no se volverá a escanear el texto completo (el texto sólo se agregará, las marcas existentes no desaparecerán).

        val hasSpecialTags =

            streamFlushCache.hasTagMarker(content) ||

                (!reasoning.isNullOrBlank() && streamFlushCache.hasTagMarker(reasoning))
        if (isStreaming && !hasSpecialTags) {
            val updated =
                msg.copy(
                    content = content,
                    reasoning = reasoning ?: msg.reasoning,
                    imageBase64List = imageBase64List ?: msg.imageBase64List,
                    imageUrls = imageUrls ?: msg.imageUrls,
                    videoFileUri = videoFileUri ?: msg.videoFileUri,
                )
            val newMessages = messages.toMutableList().apply { set(index, updated) }
            accessor.updateMessages { newMessages }
            return
        }

        // camino completo:Cuando finaliza la transmisión o el contenido contiene etiquetas especiales,Realizar extracción de estado de ánimo/reflexión/pensamiento.
        val contentAfterMood = content
        // Independientemente de si el modelo utiliza humor o mod, corchetes angulares o corchetes, primero se recopila el mismo campo plegable.
        // Posteriormente, InternalMarkupSanitizer elimina uniformemente el texto para evitar“La etiqueta desaparece pero el ánimo tampoco se salva.”.
        // v2.7: mood El resultado no cambia después de cerrar el bloque y el último resultado de extracción se reutiliza para evitar la regularización de texto completo de cada cuadro.

        val moodContent = streamFlushCache.mood(content) { InternalMarkupSanitizer.extractMood(it) }
        // B6-02: moodfx análisis independiente,No incluido con la aplicación <mood> cruzar
        val (moodSkinContent, contentAfterMoodSkin) =
            if (contentAfterMood.contains("<moodfx>", ignoreCase = true)) {
                MoodSkinParser.extract(contentAfterMood)
            } else {
                null to contentAfterMood
            }
        val (contentAfterReflection, reflectionContent) =
            if (contentAfterMoodSkin.contains("<reflection>", ignoreCase = true)) {
                extractTagContent(contentAfterMoodSkin, "reflection")
            } else {
                contentAfterMoodSkin to null
            }
        val (cleanContentRaw, thinkContent) = extractThinkContent(contentAfterReflection)
        val cleanContent = InternalMarkupSanitizer.stripForDisplay(cleanContentRaw)
        // v1.0.73: mood Ten todo en cuenta — modelo(pensamiento especialmente profundo)puede poner <mood> El manuscrito está escrito en el canal del pensamiento.
        // (reasoning_content),No hay contenido en el texto principal. Extraído del razonamiento en este momento. mood,
        // Deje que el bloque de estado de ánimo se muestre como de costumbre.;Quitar el trozo del razonamiento después de la extracción.(No lo volveré a mostrar en mis pensamientos.).
        var effectiveMood = moodContent
        var effectiveReasoningInput = reasoning
        if (effectiveMood == null && !reasoning.isNullOrBlank() && containsMoodMarker(reasoning)
        ) {
            effectiveMood = InternalMarkupSanitizer.extractMood(reasoning)
            // reasoning Puede haber ambos <mood> y <think>;Sólo elimina el bloqueo del estado de ánimo, no el
            // mood El texto principal continúa mostrándose como razonamiento.
            effectiveReasoningInput =
                InternalMarkupSanitizer.stripContainerTags(
                    InternalMarkupSanitizer.stripMoodBlocks(reasoning),
                )
        }
        // v2.x: MOOD salida de depuración — Extraer resultados/Fuente/Vista previa,y"Hay una marca pero no se puede analizar."alarma,
        // Es conveniente utilizar MoodDebug para filtrar y localizar en logcat cuando ocurren problemas.(No más adivinanzas).
        if (!isStreaming) {
            if (effectiveMood != null) {
                Logger.d(
                    "MoodDebug",
                    "mood extracted | source=${if (moodContent != null) "content" else "reasoning"} | " +
                        "len=${effectiveMood.length} | skin=${moodSkinContent ?: "-"} | " +
                        "reflection=${reflectionContent != null} | preview=${effectiveMood.replace('\n', ' ').take(120)}",
                )
            } else if (containsMoodMarker(content) || (!reasoning.isNullOrBlank() && containsMoodMarker(reasoning))) {
                Logger.w(
                    "MoodDebug",
                    "mood marker present but not parsed | contentLen=${content.length} | reasoningLen=${reasoning?.length ?: 0}",
                )
            }
        }
        // v1.62 Reparación:reasoning Repita la pregunta.
        // vieja lógica existingReasoning + newReasoning + thinkContent Empalme de los tres,
        // Provoca que se duplique al finalizar(existingReasoning==newReasoning),
        // <think> El modo de etiqueta crece linealmente(Cada vez que se empalma ContentDelta thinkContent).
        // nueva lógica:reasoning Si el parámetro no está vacío, se sobrescribirá directamente.(Acumulado de ReasoningDelta,Ya es un valor completo);
        // reasoning Cuando el parámetro es nulo,usar thinkContent(content en <think> Extracto)como fallback;
        // Ninguno conserva el mensaje.razonamiento.
        val combinedReasoning =
            when {
                !effectiveReasoningInput.isNullOrBlank() ->

                    streamFlushCache.strippedReasoning(effectiveReasoningInput) {
                        InternalMarkupSanitizer.stripContainerTags(it)
                    }
                !thinkContent.isNullOrBlank() -> InternalMarkupSanitizer.stripContainerTags(thinkContent)
                else -> msg.reasoning?.let(InternalMarkupSanitizer::stripContainerTags)
            }
        val updated =
            msg.copy(
                content = cleanContent,
                reasoning = combinedReasoning,
                imageBase64List = imageBase64List ?: msg.imageBase64List,
                imageUrls = imageUrls ?: msg.imageUrls,
                videoFileUri = videoFileUri ?: msg.videoFileUri,
                mood = effectiveMood ?: msg.mood,
                moodSkin = moodSkinContent ?: msg.moodSkin,
                reflection = reflectionContent ?: msg.reflection,
            )
        val newMessages = messages.toMutableList().apply { set(index, updated) }
        accessor.updateMessages { newMessages }
    }

    /**
     * Revisión de corrección (2.0 A-03): Medios adjuntos atómicos — leer-cambiar-Toda la coescritura se realiza dentro de la transformación CAS de updateMessages.
     *
     * con [updateAssistant] la diferencia:updateAssistant Lea la instantánea de los mensajes primero y luego reemplácela por completo,
     * Herramienta de generación de gráficos paralelos en la misma ronda.(Como por ejemplo"Generar dos imágenes")Cada uno está construido en base a una instantánea antigua. newMessages,El escritor posterior sobrescribe al escritor anterior.,
     * Las URL de imagen del primer completador se pierden. Este método pone"Tomar existente imageUrls → anexar → distinct"Pasar a la transformación,
     * CAS En caso de competencia, StateFlow.update reproducirá la transformación con el valor más reciente,Asegúrese de que los anexos paralelos no se pierdan entre sí.
     *
     * Nota:Este método no extrae etiquetas de estado de ánimo/pensamiento. — El contenido adjunto a los medios son todos marcadores de posición de texto sin formato generados localmente.,
     * Sin etiquetas de modelo,Equivalente al manejo de rutas completas de updateAssistant para contenido sin etiquetar.
     */
    fun appendMediaToAssistant(
        id: Uuid,
        content: String? = null,
        imageUrls: List<String>? = null,
        videoFileUri: String? = null,
        voiceUri: String? = null,
        voiceDurationMs: Long = 0L,
    ) {
        accessor.updateMessages { list ->
            list.map { msg ->
                if (msg.id != id) {
                    msg
                } else {
                    val mergedContent =
                        when {
                            content == null -> msg.content
                            msg.content.isBlank() -> content
                            msg.content.contains(content) -> msg.content
                            else -> "${msg.content}\n\n$content"
                        }
                    msg.copy(
                        content = mergedContent,
                        imageUrls = if (imageUrls != null) (msg.imageUrls + imageUrls).distinct() else msg.imageUrls,
                        videoFileUri = videoFileUri ?: msg.videoFileUri,
                        voiceUri = voiceUri ?: msg.voiceUri,
                        voiceDurationMs = if (voiceUri != null) voiceDurationMs else msg.voiceDurationMs,
                    )
                }
            }
        }
    }

    /**
     * v1.43: Colocación periódica — Conservar el progreso del flujo de mensajes del asistente actual en la base de datos,
     * cortemos paginas/El nuevo ViewModel detrás de escena puede restaurar el contenido más reciente de la base de datos.
     * Sin extracción de producto(Los productos solo se extraen después de que se completa el flujo.),Ni adjunto citation(Transmisión pending).
     *
     * v1.97 (P1-2): Para colocación periódica skipFts=true Saltar la reconstrucción de FTS(toNgram Caro para textos largos).
     * Ubicación final después de que finalice la transmisión(directo upsertMessage)Estará sincronizado FTS;Las interrupciones van a persistInterruptedAssistant y también se sincronizan.
     * Si la aplicación falla y el FTS se desvía,sureFtsIndexConsistent se reconstruirá automáticamente la próxima vez que se inicie.
     */
    suspend fun persistCurrentAssistant(
        sessionId: String,
        assistantId: Uuid,
        msg: UIMessage? = null,
        addError: (ChatErrorType, String) -> Unit,
    ) {
        // v1.97: msg El parámetro se utiliza en el escenario en el que _state.messages se cambió a una nueva sesión después del corte de página y el ID del asistente original no está en él.
        // Genere cierre y use el constructor para construir UIMessage y pasarlo,Omitir la búsqueda de _state.messages,Asegúrese de que la bandeja no quede interrumpida en el medio.
        val current = msg ?: accessor.messagesSnapshot.firstOrNull { it.id == assistantId } ?: return
        // v1.0.54: Adiós herramienta(content/reasoning/Todas las imágenes están vacías.)No queda en el inventario. — El mensaje de la rueda de herramientas no tiene contenido visible para el usuario,
        //   Los mensajes vacíos permanecerán al reiniciar la carga después de eliminar la biblioteca.(los usuarios ven"conversación vacía UI").
        if (current.content.isBlank() && current.reasoning.isNullOrBlank() &&
            current.imageBase64List.isEmpty() && current.imageUrls.isEmpty()
        ) {
            return
        }
        if (ProcessWriteGate.restoring) {
            Logger.w(tag, "Restauración de copia de seguridad en progreso, se omitió la escritura de instantáneas de Streaming Assistant")
            return
        }
        // v1.80 (L-CVM4): Envolver persistencia con NonCancellable,Asegúrese de que ViewModel esté destruido/Aún se pueden realizar pedidos cuando se cancela la rutina
        // Espere directamente a que se complete la escritura para asegurarse de que una instantánea asincrónica anterior no sobrescriba la inserción final.
        withContext(NonCancellable) {
            try {
                sessionRepository.upsertMessage(sessionId, current, skipFts = true)
            } catch (e: Exception) {
                Logger.e(tag, "persistCurrentAssistant failed", e)
                // v1.65: Comentarios al usuario si falla la persistencia del asistente
                addError(ChatErrorType.UNKNOWN, "No se pudo guardar el estado del asistente: ${e.message ?: "error desconocido"}")
            }
        }
    }

    /**
     * v1.80 (M-CVM2): Marcar el mensaje actual del asistente como [interrumpido] Y poner parte de la respuesta en el mercado.
     *
     * Cancelado durante la transmisión(El usuario hace clic en detener/Cambiar de sesión)O llamado cuando una excepción interrumpe,Asegúrese de que el contenido recibido no se pierda cuando se destruya ViewModel.
     * Por la perseverancia [NonCancellable] paquete,Asegúrese de que la escritura de la base de datos aún pueda completarse cuando se cancele la rutina
     * (De lo contrario, la llamada de suspensión en la rutina cancelada arrojará inmediatamente CancellationException,Colocación fallida).
     *
     * A-12/A-14: Sólo se permiten etiquetas"Mensajes generados en esta ronda" —
     *  - [partialMsg] Construido por la persona que llama utilizando un generador de transmisión(Prioridad)
     *  - De lo contrario sólo en [expectedAssistantId] Este mensaje se utiliza cuando se accede a una instantánea.(Parte de lo que ha escrito la UI de streaming)
     *  - Si no puede conseguir ninguno de los dos, regrese directamente.,Nunca vuelvas a caer"El último mensaje del asistente no vacío."
     *    (Esa podría ser una respuesta antigua y completa.,se agregará permanentemente [interrumpido];Después de cambiar de sesión, la base de datos puede descartarse entre sesiones.).
     */
    suspend fun persistInterruptedAssistant(
        sessionId: String,
        partialMsg: UIMessage? = null,
        expectedAssistantId: Uuid? = null,
        /** A5: tiempo transcurrido en el momento de la interrupción(milisegundos),Seguir [interrumpido] Los mensajes se colocan en la biblioteca para mostrar información en ventanas emergentes. */
        durationMs: Long? = null,
    ) {
        // v1.97: partialMsg El parámetro se utiliza en el escenario en el que _state.messages se ha cambiado a una nueva sesión después de cortar la página.
        // Genere cierre y use el constructor para construir UIMessage y pasarlo,Omita la búsqueda de _state.messages.
        val partial =
            partialMsg ?: expectedAssistantId?.let { id ->
                accessor.messagesSnapshot.firstOrNull { it.id == id }
            } ?: return
        // A-14: No hay nada que retener en esta ronda.(Cancelado antes del primer token)no escribas a veces [interrumpido] mensaje vacío,
        // Evite generar residuos de marcadores de posición;Conservar cuando haya medios disponibles(fotos/Se interrumpió la generación de vídeo.).
        if (partial.content.isBlank() && partial.reasoning.isNullOrBlank() &&
            partial.imageUrls.isEmpty() && partial.imageBase64List.isEmpty() && partial.videoFileUri == null
        ) {
            return
        }
        if (ProcessWriteGate.restoring) {
            Logger.w(tag, "Restauración de copia de seguridad en curso, omitiendo la escritura de instantáneas del asistente de interrupción")
            return
        }
        val interruptedMsg = partial.copy(content = partial.content + "\n\n[interrumpido]", durationMs = durationMs)
        // v1.0.74 fix: anteriormente partialMsg != null(Cortar páginas/Generar escenarios de cierre)Nunca actualizar UI,
        // Como resultado, el usuario no puede verlo en la sesión actual después de detener la generación. [interrumpido] marca,"continuar generando"El botón no aparece.
        // Cambiar a:Actualiza el mensaje mientras todavía está en la lista de conversaciones actual. UI(Escenario de parada normal),Saltar si no está presente(Cortar páginas para evitar la contaminación.).
        if (partialMsg == null || accessor.messagesSnapshot.any { it.id == partial.id }) {
            accessor.updateMessages { messages ->
                messages.map { if (it.id == interruptedMsg.id) interruptedMsg else it }
            }
        }
        // NonCancellable: La realización del pedido aún se completa aunque la corrutina haya sido cancelada.
        withContext(NonCancellable) {
            try {
                sessionRepository.upsertMessage(sessionId, interruptedMsg)
            } catch (e: Exception) {
                Logger.e(tag, "persistInterruptedAssistant upsertMessage failed", e)
            }
        }
    }

    // ── Herramientas de procesamiento de cuerdas ────────────────────────────────────────────────

    /**
     * v1.23: Extracto del contenido de transmisión del asistente `<think>...</think>` Cadena de pensamiento.
     *
     * - etiqueta completa:Extraiga el contenido directamente y sáquelo del texto principal.
     * - Etiqueta no cerrada(Común en streaming):poner `<think>` El contenido posterior se almacena temporalmente en reasoning,
     *   El cuerpo sólo conserva el contenido antes de la etiqueta.,Evite mostrar etiquetas sin formato directamente en burbujas.
     * - ignorar caso,compatibles `<Think>` Variantes.
     *
     * @return Pair<Texto limpio, Contenido de pensamiento extraído(null significa ninguno)>
     */
    /**
     * El lote actual de solicitudes estará ocupado por definiciones de herramientas.
     *
     * Si no puede obtenerlo, trátelo como 0: la lista de herramientas es un elemento de mejora y el presupuesto no se puede calcular simplemente porque no está disponible.
     */
    private fun toolDefinitionTokens(): Int = runCatching {
        io.zer0.muse.util.TokenEstimator.estimateToolDefinitions(
            toolRegistry.listToolsAsToolDefinitions(),
        )
    }.getOrDefault(0)

    private fun extractThinkContent(input: String): Pair<String, String?> {
        if (!input.contains("<think", ignoreCase = true)) return input to null

        val sb = StringBuilder()
        var remaining = input
        var match = MusePatterns.THINK_TAG_REGEX.find(remaining)
        while (match != null) {
            sb.append(match.groupValues[1])
            remaining = remaining.removeRange(match.range)
            match = MusePatterns.THINK_TAG_REGEX.find(remaining)
        }

        // Manejar la situación en la que la etiqueta no se ha cerrado durante la transmisión.
        val openIdx =
            listOf("<think>", "<thinking>")
                .map { remaining.indexOf(it, ignoreCase = true) }
                .filter { it >= 0 }
                .minOrNull() ?: -1
        val partialThink: String? =
            if (openIdx != -1 && !remaining.contains("</think>", ignoreCase = true)) {
                val tagLength =
                    if (remaining.regionMatches(
                            openIdx,
                            "<thinking>",
                            0,
                            "<thinking>".length,
                            ignoreCase = true,
                        )
                    ) {
                        "<thinking>".length
                    } else {
                        "<think>".length
                    }
                val afterTag = remaining.substring(openIdx + tagLength)
                remaining = remaining.substring(0, openIdx)
                afterTag.trim().ifBlank { null }
            } else {
                null
            }

        val fullThink =
            listOfNotNull(
                sb.toString().trim().ifBlank { null },
                partialThink,
            ).joinToString("\n\n").ifBlank { null }

        return remaining.trim() to fullThink
    }

    /**
     * v1.42: Asistencia general para la extracción de contenido de etiquetas(usado para mood/reflection).
     *
     * Lógica consistente con MoodTagTransformer,Pero solo me llamaron al confirmar la etiqueta.,Evite la regularización de alta frecuencia en el streaming.
     */
    private fun extractTagContent(input: String, tagName: String): Pair<String, String?> {
        if (!input.contains("<$tagName>", ignoreCase = true)) return input to null
        val regex = Regex("""<$tagName>([\s\S]*?)</$tagName>""", RegexOption.IGNORE_CASE)
        val sb = StringBuilder()
        var remaining = input
        var match = regex.find(remaining)
        while (match != null) {
            sb.append(match.groupValues[1])
            remaining = remaining.removeRange(match.range)
            match = regex.find(remaining)
        }
        // v1.0.74 fix: Etiqueta no cerrada(Común cuando la salida del modelo está truncada)No se puede extraer,Los encabezados de las etiquetas permanecerán como texto sin formato.
        // Consistente con MoodTagTransformer,Quite los caracteres restantes del encabezado de la etiqueta.
        if (remaining.contains("<$tagName>", ignoreCase = true) &&
            !remaining.contains("</$tagName>", ignoreCase = true)
        ) {
            remaining = remaining.replace(Regex("(?i)<$tagName>\\s*"), "").trim()
        }
        return remaining.trim() to sb.toString().trim().ifBlank { null }
    }

    // ── Phase A: preparación de historia ────────────────────────────────────────────

    /**
     * v1.0.27 Phase 4-A.2: Lógica de preparación del historial extraída de ChatViewModel.
     *
     * Leer configuración/nivel de inferencia/temperatura del asistente / ExperimentsConfig / contextSize,
     * Limpiar a los huérfanos tool_call,Truncar el historial por contextSize.
     */
    internal suspend fun prepareHistory(state: StreamRunState) {
        with(state) {
            // Notificación:Iniciar la transmisión de notificaciones de progreso(LOW importance,silencioso)
            sessionTitle = accessor.snapshot.sessions
                .firstOrNull { it.id == sessionId }?.title ?: appContext.getString(R.string.chat_new_session)
            runCatching {
                notificationManager.updateLiveProgress(
                    sessionTitle,
                    0,
                    true,
                    io.zer0.muse.notification.MuseNotificationTarget.Session(sessionId),
                )
            }.onFailure { Logger.w("ChatVM", "No se pudo iniciar la notificación de progreso: ${it.message}") }
            // v1.133: RAG Recuperar lista de referencias,Adjunto al mensaje del asistente después de que finaliza la transmisión
            pendingRagCitations = emptyList()
            // Phase 8.2: Obtener configuración del Asistente(Si no lo encuentra, use el asistente predeterminado)
            assistant = accessor.snapshot.currentAssistant
                ?: assistantRepository.getById("default")
            // v1.136: Usuario/El nivel de razonamiento solicitado por el asistente.
            // v1.0.47 P5-6: deepThinkingEnabled Utilice el nivel seleccionado por el usuario cuando(Predeterminado HIGH),No más codificación HIGH
            // v2.6.1(comentarios de los usuarios): Prioridad global del "pensamiento forzado" —— Utilice la intensidad especificada incondicionalmente después de la apertura.,
            //   ya no es nivel de asistente reasoningLevel=OFF/AUTO presione hacia fuera;Cuando el modelo no soporta la inferencia
            //   La normalización del nivel de razonamiento a continuación se degrada automáticamente a AUTO,N° 400.
            val chatPrefs = accessor.snapshot.chatPreferences
            requestedReasoningLevel =
                when {
                    chatPrefs.forceReasoning -> chatPrefs.forceReasoningLevel
                    accessor.snapshot.deepThinkingEnabled -> accessor.snapshot.deepThinkingLevel
                    else -> assistant?.let {
                        runCatching { ReasoningLevel.valueOf(it.reasoningLevel) }
                            .getOrElse { ReasoningLevel.DEFAULT }
                            .let { level -> if (level == ReasoningLevel.AUTO) ReasoningLevel.OFF else level }
                    } ?: ReasoningLevel.OFF
                }
            // Retroceso de la temperatura global
            effectiveTemperature = assistant?.temperature
                ?: accessor.snapshot.chatPreferences.globalTemperature
            // v2.6.x: Modelo real de esta ronda./Provider atado en**etapa de preparación**solo arreglalo(salida única
            // [resolveModelBinding]). Lo pongo aquí porque todo el seguimiento."ventana contextual"juicio
            // (Presupuesto de token de calentamiento / 90% de truncamiento antes del envío / 1,35 veces interceptación dura / presupuesto de compresión)
            // Todos los cálculos deben basarse en el modelo realmente solicitado en esta ronda.,En lugar de seleccionar el modelo globalmente en el lado de la interfaz de usuario.
            // El registro de degradación se deja para resolverToolsAndModel como salida para evitar generar dos alarmas idénticas en la misma ronda.
            val binding = resolveModelBinding(state, logBindingIssues = false)
            effectiveModel = binding.model?.let { ModelRegistry.enhanceModel(it) }
            effectiveProviderConfig = binding.providerConfig
            // v0.32 Experimental:leer ExperimentsConfig
            experiments =
                runCatching { settings.experimentsCache }
                    .getOrDefault(ExperimentsConfig())
            // v0.32 Modo de depuración experimental: restablecer debugInfo,Iniciar temporizadores y contadores
            if (experiments.debugMode) {
                accessor.update { it.copy(debugInfo = null) }
                Logger.d(
                    "ChatVM-Debug",
                    "launchStream start | sessionId=$sessionId | assistantId=${assistant?.id} | " +
                        "requestedReasoningLevel=$requestedReasoningLevel",
                )
            }
            streamStartedAt = System.currentTimeMillis()

            // v0.32 LongMemoryCompression experimental: umbral de truncamiento de contexto reducido a la mitad
            contextSize = assistant?.contextMessageSize?.coerceAtLeast(1) ?: 20
            if (experiments.longMemoryCompression) {
                contextSize = (contextSize / 2).coerceAtLeast(5)
                if (experiments.debugMode) {
                    Logger.d("ChatVM-Debug", "longMemoryCompression enabled | contextSize $contextSize (halved)")
                }
            }

            // v2.x Importar calentamiento:importación externa/Primera ronda de sesión de recuperación de copias de seguridad —— Historia completa primero(ver WarmupHistory)
            warmupActive = sessionRepository.getSessionById(sessionId)?.warmupPending == true
            // Quitar marcador de posición assistant;La ronda de calentamiento cambia a DB a todo volumen + nuevas noticias que aún no se han publicado(Eliminar duplicados por id)
            val messagesExceptPlaceholder =
                if (warmupActive) {
                    warmupFullHistory(sessionId)
                } else {
                    accessor.messagesSnapshot.dropLast(1)
                }
            // v1.0.2: Limpieza defensiva de huérfanos tool_call
            rawHistory =
                messagesExceptPlaceholder.filterIndexed { index, msg ->
                    if (msg.role == MessageRole.ASSISTANT && !msg.toolCalls.isNullOrEmpty()) {
                        messagesExceptPlaceholder.getOrNull(index + 1)?.role == MessageRole.TOOL
                    } else {
                        true
                    }
                }
            if (rawHistory.size < messagesExceptPlaceholder.size) {
                Logger.w(
                    "ChatViewModel",
                    "Limpiar a los huérfanos tool_call: ${messagesExceptPlaceholder.size - rawHistory.size} Los mensajes del asistente fueron descartados.",
                )
            }
            // Fusión de punto de control de sesión: poner"Antes de los límites comprimidos"El contenido del lado del asistente se reemplaza con un resumen del punto de control.
            // Debe colocarse antes del truncamiento. —— Este tipo de recorte basado en la cantidad de elementos se aplica al historial comprimido; también debe colocarse en transformer
            // antes del oleoducto —— El resumen debe tener el mismo origen que el mensaje de la solicitud final y no puede afectar únicamente al que ve el propio compresor.
            rawHistory = applySessionCheckpoint(sessionId, rawHistory)
            // v1.x: Truncamiento consciente de la dependencia de la herramienta;La ronda de preparación se cambia a un truncamiento simbólico del presupuesto.(Prioridad de cantidad total)
            truncatedHistory =
                if (warmupActive) {
                    val budget =
                        WarmupHistory.budgetTokensFor(
                            effectiveContextWindowTokens(effectiveModel, accessor.snapshot.contextMaxTokens),
                        )
                    val trimmed =
                        withContext(Dispatchers.Default) {
                            WarmupHistory.trimToBudget(rawHistory, budget)
                        }
                    Logger.i(
                        tag,
                        "warmup first turn: full=${rawHistory.size} kept=${trimmed.history.size} " +
                            "budget=$budget truncated=${trimmed.truncated} | sessionId=$sessionId",
                    )
                    listOf(WarmupHistory.briefingMessage(trimmed.truncated)) + trimmed.history
                } else {
                    buildContextWindow(rawHistory, contextSize)
                }
        }
    }

    /**
     * v2.x: Fusionar el punto de control de la sesión en el historial ("contexto comprimido"punto efectivo).
     *
     * leer punto de control → Verifique que el mensaje de límite al que hace referencia todavía esté en el historial (no será válido si ya no está allí, consulte ContextCheckpointReader)→
     * Reemplace el contenido del lado auxiliar antes del borde con un resumen. Cualquier excepción no bloquea la generación: la falla de compresión es, como mucho, un contexto más largo,
     * Nunca permita que toda la ronda de diálogo no se envíe debido a una falla al leer el punto de control.
     */
    private suspend fun applySessionCheckpoint(sessionId: String, history: List<UIMessage>): List<UIMessage> {
        if (history.isEmpty()) return history
        return try {
            val knownIds = history.mapTo(HashSet()) { it.id.toString() }
            val persisted = checkpointReader.get(sessionId)
            if (persisted == null) return history
            val boundaryInPage =
                persisted.lastCoveredMessageId.isBlank() ||
                    persisted.lastCoveredMessageId in knownIds
            // The chat UI normally holds only a recent page. Validate a missing boundary
            // against the full persisted session before deciding that the checkpoint is stale;
            // otherwise pagination alone would delete a valid compression checkpoint.
            val fullHistoryForValidation =
                if (boundaryInPage) {
                    null
                } else {
                    resultOf { sessionRepository.getAllMessagesForWarmup(sessionId) }
                        .onError { msg, error -> Logger.w(tag, "checkpoint full-history validation failed: $msg", error) }
                        .getOrNull()
                }
            val validationIds =
                fullHistoryForValidation?.mapTo(HashSet()) { it.id.toString() } ?: knownIds
            if (!boundaryInPage && fullHistoryForValidation != null) {
                val coveredIds =
                    io.zer0.muse.transformer.ContextCheckpointMerge.coveredMessageIds(
                        fullHistoryForValidation,
                        persisted,
                    )
                if (coveredIds.isNotEmpty() && persisted.summary.isNotBlank()) {
                    io.zer0.muse.transformer.CompressionSummaryStore.remember(
                        sessionId = sessionId,
                        coveredIds = coveredIds,
                        summary = persisted.summary,
                    )
                }
            }
            val checkpoint =
                if (!boundaryInPage && validationIds.isEmpty()) {
                    persisted
                } else {
                    checkpointReader.getValid(sessionId, validationIds)
                }
            if (checkpoint == null) {
                history
            } else {
                val merged = io.zer0.muse.transformer.ContextCheckpointMerge.apply(history, checkpoint)
                if (merged.applied) {
                    val coveredIds =
                        io.zer0.muse.transformer.ContextCheckpointMerge.coveredMessageIds(history, checkpoint)
                    if (coveredIds.isNotEmpty() && checkpoint.summary.isNotBlank()) {
                        io.zer0.muse.transformer.CompressionSummaryStore.remember(
                            sessionId = sessionId,
                            coveredIds = coveredIds,
                            summary = checkpoint.summary,
                        )
                    }
                    Logger.i(
                        tag,
                        "${history.size} -> ${merged.messages.size} checkpoint applied " +
                            "(coveredSeq=${checkpoint.coveredSeq}, strategy=${checkpoint.strategy})",
                    )
                }
                merged.messages
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Logger.w(tag, "checkpoint merge failed, sending full history: ${t.message}")
            history
        }
    }

    /**
     * v2.x Importar calentamiento:Historia completa de la primera ronda = DB Todos los mensajes válidos + mensajes nuevos en la instantánea de la interfaz de usuario que aún no se han incluido en la biblioteca(Eliminar duplicados por id).
     *
     * DB Volver a la instantánea de la ventana de la interfaz de usuario cuando falla la lectura(haz tu mejor esfuerzo,No bloquear la generación).
     */
    private suspend fun warmupFullHistory(sessionId: String): List<UIMessage> {
        val uiSnapshot = accessor.messagesSnapshot.dropLast(1)
        val dbHistory =
            try {
                sessionRepository.getAllMessagesForWarmup(sessionId)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Logger.w(tag, "warmup full history load failed: ${t.message}")
                return uiSnapshot
            }
        if (dbHistory.isEmpty()) return uiSnapshot
        val dbIds = dbHistory.mapTo(HashSet()) { it.id }
        val pendingUi = uiSnapshot.filter { it.id !in dbIds }
        return if (pendingUi.isEmpty()) dbHistory else dbHistory + pendingUi
    }

    // ── Phase F: PII cubrir ────────────────────────────────────────────

    /**
     * v1.0.27 Phase 4-A.2: Lógica de enmascaramiento de PII extraída de ChatViewModel.
     *
     * Enmascare información confidencial en el último mensaje de USUARIO antes de enviarlo a LLM,
     * AI Los marcadores de posición presentes en las respuestas se restauran con unmaskPii antes de escribir en UI/DB.
     * Bloquear solo los últimos mensajes de usuario(estrategia conservadora,Evite la detección falsa de números normales en mensajes históricos);
     * Marcador de posición retenido en el historial de conversaciones dentro del bucle de llamada de herramienta,Asegúrese de que la próxima ronda de LLM todavía vea un contexto consistente.
     * piiMatches Ya con unmaskPii [StreamRunState] Declaración en,catch El bloque es accesible.
     */
    internal suspend fun applyPiiGuard(state: StreamRunState) {
        with(state) {
            val piiGuardEnabled = settings.piiGuardEnabledCache
            if (piiGuardEnabled) {
                val lastUserIdx = conversationHistory.indexOfLast { it.role == MessageRole.USER }
                if (lastUserIdx >= 0) {
                    val originalContent = conversationHistory[lastUserIdx].content
                    if (originalContent.isNotEmpty()) {
                        val (maskedContent, matches) = PiiGuard.mask(originalContent)
                        if (matches.isNotEmpty()) {
                            conversationHistory[lastUserIdx] =
                                conversationHistory[lastUserIdx].copy(content = maskedContent)
                            piiMatches = matches
                        }
                    }
                }
            }
        }
    }

    // ── Phase G: ayuda visual ────────────────────────────────────────────

    /**
     * M2.8: Registro de diagnóstico visual previo a la inspección — Instantánea de tres estados del registro de capacidad de registro(known/visionInput),
     * Fácil de solucionar problemas"Capacidades visuales de etiquetado incorrecto ascendentes → estación de transferencia 400"tipo de problema.
     * función independiente:prepareVisionContext La complejidad ha sido superada,Las nuevas sucursales deben trasladarse al extranjero.
     */
    private fun visionPreflightDiagnostics(model: Model?, modelSupportsVision: Boolean, historyImageCount: Int): String {
        val snapshot =
            if (model != null) {
                io.zer0.ai.registry.ModelCapabilityQuery.snapshot(model.id)
            } else {
                null
            }
        return "vision preflight: effectiveModel=${model?.id} " +
            "supportsVision=$modelSupportsVision " +
            "registryKnown=${snapshot?.known} registryVision=${snapshot?.visionInput} " +
            "inputModalities=${model?.inputModalities} " +
            "historyImageCount=$historyImageCount"
    }

    /**
     * v1.0.27 Phase 4-A.2: Lógica de asistencia visual extraída de ChatViewModel.
     *
     * Cuando el modelo actual no soporta la visión,Analice imágenes e inyecte descripciones a través de modelos visuales.
     * Si el análisis visual tiene éxito o fracasa,Los mensajes originales deben borrarse. imageBase64List,
     * De lo contrario, el streamChat posterior seguirá enviando imágenes a modelos que no admiten visión.,Hace que el relé devuelva HTTP 400.
     */
    internal suspend fun prepareVisionContext(state: StreamRunState) {
        with(state) {
            val historyImageCount = conversationHistory.count { it.role == MessageRole.USER && it.imageBase64List.isNotEmpty() }
            val effectiveModelLocal = effectiveModel
            val modelSupportsVision = visionBridge.supportsVisionModel(effectiveModelLocal)
            Logger.i("ChatVM", visionPreflightDiagnostics(effectiveModelLocal, modelSupportsVision, historyImageCount))
            if (effectiveModelLocal != null && !visionBridge.supportsVision(effectiveModelLocal)) {
                val userImageIndexes =
                    conversationHistory.indices.filter { idx ->
                        conversationHistory[idx].role == MessageRole.USER &&
                            conversationHistory[idx].imageBase64List.isNotEmpty()
                    }
                Logger.i("ChatVM", "ayuda visual[condiciones cumplidas]: userImageIndexes=${userImageIndexes.size}")
                if (userImageIndexes.isNotEmpty()) {
                    val lastIdx = userImageIndexes.last()
                    val lastUserMsg = conversationHistory[lastIdx]
                    val imageCount = lastUserMsg.imageBase64List.size
                    Logger.i(
                        "ChatVM",
                        "ayuda visual[gatillo]: model=${effectiveModelLocal.id} supportsVision=false " +
                            "Número de fotos=$imageCount Historia por limpiar=${userImageIndexes.size - 1}",
                    )
                    MuseToast.show(appContext.getString(R.string.vision_analysis_starting))
                    val prepareResult =
                        run {
                            accessor.update {
                                it.copy(
                                    visionProgress =
                                    io.zer0.muse.vision.VisionProgress(
                                        idle = false,
                                        index = 0,
                                        total = lastUserMsg.imageBase64List.size,
                                        messageId = lastUserMsg.id.toString(),
                                    ),
                                )
                            }
                            try {
                                visionBridge.prepare(
                                    text = lastUserMsg.content,
                                    images = lastUserMsg.imageBase64List,
                                    userRequest = lastUserMsg.content,
                                    sessionId = sessionId,
                                    messageId = lastUserMsg.id.toString(),
                                    onProgress = { current, total ->
                                        accessor.update {
                                            it.copy(
                                                visionProgress =
                                                io.zer0.muse.vision.VisionProgress(
                                                    idle = false,
                                                    index = current,
                                                    total = total,
                                                    messageId = lastUserMsg.id.toString(),
                                                ),
                                            )
                                        }
                                    },
                                )
                            } finally {
                                accessor.update { it.copy(visionProgress = null) }
                            }
                        }
                    conversationHistory[lastIdx] =
                        lastUserMsg.copy(
                            // M4.3: La inyección de descripción visual está sujeta a un límite superior de ContextBudget unificado(El truncamiento es diagnóstico)
                            content =
                            io.zer0.muse.context.ContextBudget().clampText(
                                io.zer0.muse.context.ContextSection.VISION_DESCRIPTION,
                                prepareResult.text,
                            ),
                            imageBase64List = prepareResult.images,
                        )
                    if (prepareResult.success) {
                        Logger.i("ChatVM", "ayuda visual[éxito]: inyectado ${prepareResult.descriptionCount} descripción visual")
                        MuseToast.show(appContext.getString(R.string.vision_analysis_done, prepareResult.descriptionCount))
                    } else {
                        val failDetail = prepareResult.text.take(200)
                        Logger.w("ChatVM", "ayuda visual[falló]: prepare Rebajar,Detalles=$failDetail")
                        MuseToast.show(appContext.getString(R.string.err_chat_vision_fallback))
                    }

                    accessor.update {
                        it.copy(visionAssistedMessageIds = it.visionAssistedMessageIds + lastUserMsg.id.toString())
                    }

                    for (idx in userImageIndexes) {
                        if (idx == lastIdx) continue
                        val histMsg = conversationHistory[idx]
                        if (histMsg.imageBase64List.isNotEmpty()) {
                            conversationHistory[idx] = histMsg.copy(imageBase64List = emptyList())
                            Logger.d(
                                "ChatVM",
                                "ayuda visual: Mensajes del historial borrados #$idx de ${histMsg.imageBase64List.size} fotos" +
                                    "(Evite ir directamente al modelo de texto plano)",
                            )
                        }
                    }
                } else {
                    Logger.i("ChatVM", "ayuda visual[saltar]: userImageIndexes esta vacio,Mensaje de USUARIO con imagen no encontrada")
                }
            } else {
                Logger.i(
                    "ChatVM",
                    "ayuda visual[saltar]: effectiveModel Está vacío o el modelo admite visualización.,No activa ayudas visuales. " +
                        "effectiveModel=${effectiveModel?.id} supportsVisionInput=$modelSupportsVision",
                )
            }
        }
    }

    // ── Phase C+D: Transformer tubo ──────────────────────────────────

    /**
     * v1.0.27 Phase 4-A.2: Lógica de canalización del transformador extraída de ChatViewModel.
     *
     * 1. Consulta previa de las entradas del Lorebook vinculadas al Asistente
     * 2. Consulta previa de la entrada PromptInjection correspondiente al modo actual
     * 3. Leer retrato de usuario(userNickname / assistantName)Inyectar variables de plantilla
     * 4. estructura TransformContext,Cierre el sistema que ha sido asumido por SystemPromptAssembler memory/time reminder
     * 5. ejecutar transformerPipeline.execute(prefixMessages + truncatedHistory, context)
     * 6. Guarde el contexto para su posterior reutilización mediante applyVisualTransform / applyOnGenerationFinish
     */
    internal suspend fun applyTransformers(state: StreamRunState) {
        with(state) {
            // Phase 8.1 H1: Transformer tubo(convergencia de responsabilidades:Assembler Después de hacerse cargo del mensaje del sistema,
            // MemoryInjection / TimeReminder Deshabilitado en su lugar,sólo mantener Lorebook/PromptInjection/Template/ThinkTag)
            // Phase 8.5: Consulta previa de las entradas del Lorebook vinculadas al Asistente
            val lorebookIds = assistant?.let { parseIdList(it.lorebookIdsJson) } ?: emptyList()
            val lorebookEntries: List<LorebookEntity> =
                if (lorebookIds.isNotEmpty()) {
                    resultOf { lorebookRepository.getByIdsEnabled(lorebookIds) }
                        .getOrNull() ?: emptyList()
                } else {
                    emptyList()
                }
            // Phase 8.5: Realice una consulta previa de la entrada PromptInjection correspondiente al modo actual.
            // default Modo también válido: las inyecciones vinculadas explícitamente por el asistente no se pueden tragar solo porque el modo actual es el predeterminado.
            val currentMode = accessor.snapshot.currentMode
            val injIds = assistant?.let { parseIdList(it.modeInjectionIdsJson) } ?: emptyList()
            val modeInjections: List<PromptInjectionEntity> =
                if (injIds.isNotEmpty()) {
                    (
                        resultOf { promptInjectionRepository.getByIdsEnabled(injIds) }
                            .getOrNull() ?: emptyList()
                        )
                        .filter { it.mode == currentMode }
                } else {
                    resultOf { promptInjectionRepository.getEnabledByMode(currentMode) }
                        .getOrNull() ?: emptyList()
                }
            // v1.97: Leer retrato de usuario,Inyecte apodo_usuario / nombre_asistente en las variables de la plantilla
            val userProfile = resultOf { settings.getUserProfile() }.getOrNull()
            val assistantName =
                userProfile?.assistantName
                    ?: assistant?.name
            val userNickname = userProfile?.userNickName
            val sessionIgnoresMemory =
                accessor.snapshot.sessions.firstOrNull { it.id == sessionId }?.ignoreMemory == true
            val conversationMemoryEnabled =
                (assistant?.memoryEnabled ?: true) && settings.isMemoryEnabled() && !sessionIgnoresMemory
            val context =
                TransformContext(
                    sessionId = sessionId,
                    modelId = assistant?.modelId,
                    temperature = effectiveTemperature,
                    maxTokens = assistant?.maxTokens,
                    extras =
                    mapOf(
                        "conversation_memory_enabled" to conversationMemoryEnabled,
                        "current_scope" to (assistant?.id?.takeIf { it.isNotBlank() && it != "default" } ?: "main"),
                        "current_space" to settings.currentSpaceIdFlow.firstOrNull().orEmpty().ifBlank { "default" },
                        // Phase 8.5
                        "lorebook_entries" to lorebookEntries,
                        "prompt_injections" to modeInjections,
                        // v1.97: Reglas regulares de nivel asistente(preparar,Para uso de RegexMessageTransformer)
                        "regex_rules" to (
                            assistant?.let {
                                io.zer0.muse.transformer.RegexTransformer.parseRules(it)
                            } ?: emptyList()
                            ),
                        // v1.97: Variables de plantilla — Apodo de usuario y nombre del asistente(suministro {{user}} / {{char}} espera)
                        "user_nickname" to userNickname,
                        "assistant_name" to assistantName,
                        // v2.x: Transmisión transparente del interruptor de depuración.(Para transformadores como MoodTag para generar registros de diagnóstico)
                        "debug_mode" to experiments.debugMode,
                        // v0.25: Compresión de contexto largo — Habilitado por defecto,20 gatillo de barra,Conserva los últimos 15 artículos
                        // v0.32 LongMemoryCompression experimental: umbral reducido de 20 a 10,Activar la compresión del resumen antes
                        // v2.3.2: La rueda de precalentamiento salta la compresión — El propósito del precalentamiento es"Historia completa primero"(ver WarmupHistory),
                        // La primera ronda de recompresión resumirá el historial recién cargado.,Reintroducción"Casi amnesia en la primera ronda de sesión de importación"viejo problema,
                        // Espere una llamada de compresión en vano(Retraso de la primera palabra de hasta 20 segundos).
                        "compress_enabled" to !warmupActive,
                        // v1.138: Reparación compress_threshold < compress_keep_recent Como resultado, no se puede activar la compresión.
                        // X-5: umbral/Fuente única de verdad de la Política de compresión reservada (mismo origen que el valor predeterminado de Transformer,
                        // evitar"Cambiar un lugar no tendrá efecto");longMemoryCompression El modo se activa antes pero el número de elementos retenidos aún es < límite.
                        "compress_threshold" to
                            io.zer0.muse.transformer.CompressionPolicy.threshold(experiments.longMemoryCompression),
                        "compress_keep_recent" to
                            io.zer0.muse.transformer.CompressionPolicy.keepRecent(experiments.longMemoryCompression),
                        // El presupuesto simbólico que desencadena la compresión.
                        //
                        // Ya no es una "ventana" × "Proporción fija": ese calibre trata las indicaciones del sistema, las definiciones de herramientas y las reservas de salida como constantes.
                        // Y simplemente crecen con la funcionalidad. Si abre más herramientas, el mismo historial puede cambiar de "no superado" a "superado".
                        // Aquí primero se deducen la reserva de producción y el margen de seguridad, y el resto es la cuota histórica.
                        "compress_char_budget" to
                            WarmupHistory.compressTokenBudgetFor(
                                contextMaxTokens = effectiveContextWindowTokens(
                                    effectiveModel,
                                    accessor.snapshot.contextMaxTokens,
                                ),
                                toolTokens = toolDefinitionTokens(),
                                maxOutputTokens = effectiveModel?.maxOutputTokens ?: 0,
                            ),
                    ),
                )
            // v2.3.2: La "IU de Streaming Compaction" original se elimina en su totalidad. —— Revise y confirme que CompactionState esté escrito en 3 lugares,
            // **0 Leer en**(UI nunca conectado),código muerto;Después de la eliminación"Cancelar/atascado cuando es anormal Compacting"Todos los peligros ocultos desaparecen.
            transformedMessages = transformerPipeline.execute(prefixMessages + truncatedHistory, context)

            // P1-1: llamar PromptFinalizeHook — Realice cambios finales después de la ejecución del proceso pero antes de enviarlos a LLM
            // Usos típicos: restricciones de contexto del piso(P1-4),Worldbook Inyección de desencadenador de palabras clave(P1-2)
            if (hookRegistry != null) {
                val finalizeEvent =
                    io.zer0.muse.hook.PromptFinalizeEvent(
                        preparedHistory = transformedMessages,
                        assistantId = accessor.snapshot.currentAssistant?.id,
                        sessionId = sessionId,
                        transformContext = context,
                    )
                val finalizeResult =
                    hookRegistry.execute(
                        io.zer0.muse.hook.PromptFinalizeHook::class,
                        initial = io.zer0.muse.hook.PromptFinalizeResult(finalizeEvent.preparedHistory),
                    ) { hook, acc ->
                        val event = finalizeEvent.copy(preparedHistory = acc.preparedHistory)
                        hook.beforeFinalizePrompt(event)
                    }
                transformedMessages = finalizeResult.preparedHistory
            }
            // v1.x: acceso con triple gancho — guardar context,Para su posterior reutilización mediante applyVisualTransform / applyOnGenerationFinish
            transformContext = context
        }
    }

    // ── Phase E: Definición de herramientas + análisis de modelos. ──────────────────────────────────

    /**
     * v1.0.27 Phase 4-A.2: Herramientas y lógica de análisis de modelos extraídas de ChatViewModel.
     *
     * 1. Filtrar definiciones de herramientas locales por Assistant.toolIdsJson
     * 2. Carga habilitada Skills(Filtrar por Assistant.skillIdsJson)convertir a ToolDefinition
     * 3. Herramienta de etiqueta de control de probabilidad expuesta
     * 4. Eliminar duplicados por nombre(Las herramientas locales tienen prioridad sobre Skill)
     * 5. Analizar modelo efectivo con effectiveProviderConfig(per-assistant Prioridad,Retroceder globalmente)
     * 6. Enrutamiento del modelo de herramienta:C-12 — Uso exclusivo de la rueda de herramientas toolModel(state.toolModel),modelo maestro
     *   effectiveModel Corregido como modelo de diálogo principal.(Puede apoyar la visión),El ciclo de recuperación final vuelve al modelo principal.
     * 7. Rebaja del nivel de razonamiento:effectiveModel Cuando el razonamiento no es compatible,hasta AUTO/OFF
     * 8. conversationHistory Inicializado como una copia mutable de mensajes transformados
     */
    internal suspend fun resolveToolsAndModel(state: StreamRunState) {
        with(state) {
            // La selección de herramientas para la extensión del asistente realmente debe afectar las capacidades de chat;Las listas vacías conservan la semántica heredada(Todas las herramientas integradas),
            // De esta manera, el asistente histórico y el nuevo asistente no perderán repentinamente sus capacidades. La lista blanca del asistente solo filtra las listas que no están vacías.
            val configuredToolIds = assistant?.let { parseIdList(it.toolIdsJson).toSet() }.orEmpty()
            val configuredMcpServerIds = assistant?.let { parseIdList(it.mcpServerIdsJson).toSet() }.orEmpty()
            val configuredSkillIds = assistant?.let { parseIdList(it.skillIdsJson).toSet() }.orEmpty()
            // v2.6.2(comentarios de los usuarios): Herramientas explícitamente deshabilitadas por el usuario (ALWAYS_DENY) se eliminan del esquema por completo.
            // El comportamiento anterior solo fue rechazado en el nivel de ejecución (ToolPermissionResolver), el modelo todavía ve la herramienta en cada ronda. → sintonizar → rechazado
            // → La siguiente ronda se ajusta nuevamente para formar“Apagado y todavía ajustando”Un bucle infinito. Tiene el mismo origen que la lista blanca y está bloqueado en la capa de exposición.
            val deniedToolNames =
                toolConfigStore?.policiesFlow?.firstOrNull()
                    ?.filterValues { it == io.zer0.muse.tools.ToolApprovalPolicy.ALWAYS_DENY }
                    ?.keys
                    .orEmpty()
            val registeredToolDefs =
                io.zer0.muse.tools.ToolSchemaRules.selectLocalTools(
                    toolRegistry.listToolsAsToolDefinitions(),
                    io.zer0.muse.tools.ToolSchemaRules.Criteria(
                        configuredToolIds = configuredToolIds,
                        mcpServerIds = configuredMcpServerIds,
                        deniedToolNames = deniedToolNames,
                    ),
                )

            // Phase 8.8: Cargar habilidades habilitadas vinculadas al asistente actual y convertirlas a ToolDefinition;
            // SkillIdsJson vacío también es compatible con asistentes antiguos,Indica el uso de todas las habilidades habilitadas globalmente.
            // Reparación de auditoría (A-04/A-05/A-06): La definición y la ejecución no deben estar separadas —
            // ① Las habilidades con el mismo nombre que las herramientas locales se eliminan de la lista habilitada:Al realizar la deduplicación, la definición conserva la versión local.
            //   (Ver abajo distinctBy),Ejecute primero el mapa de habilidades de enrutamiento,Si la habilidad todavía está en el mapa,
            //   voluntad"LLM Ver la definición local y ejecutar la implementación de la habilidad."(generate_image No renderices imágenes,
            //   read_file Semántica dual, conjuntos de parámetros inconsistentes).
            // ② channel_* ChannelToolFactory solo inyecta el chat grupal de tres piezas en el contexto del chat grupal,
            //   Se debe eliminar el SkillMap de la sesión principal.,De lo contrario, la sesión maestra LLM está disponible en la versión de habilidad con cualquier agent
            //   Identidad para hablar en cualquier chat grupal.(Omitir la protección vinculante de identidad de ChannelToolFactory).
            // Hay dos reglas escritas en ToolSchemaRules: la herramienta de palabras rápidas que indexa y cuenta entradas también la usa,No te alinees aquí.
            val localToolNames = toolRegistry.listTools().map { it.name }.toSet()
            val enabledSkills =
                io.zer0.muse.tools.ToolSchemaRules.selectSkills(
                    skillRepository.listEnabled(),
                    localToolNames,
                    io.zer0.muse.tools.ToolSchemaRules.Criteria(configuredSkillIds = configuredSkillIds),
                )
            // caché skill id → SkillEntity mapeo,Se utiliza al ejecutar la herramienta.
            skillMap = enabledSkills.associateBy { it.id }
            // v2.x Etapa de adelgazamiento de herramientas.4:De forma predeterminada, las habilidades se reducen al mismo calibre que OPCIONAL.find_tools debe poder
            // Busque las habilidades y cárguelas; de lo contrario, se reducirán. = Reversión funcional. prensa**sesión actual**Registrar directorio de habilidades,
            // Evite la fuga de habilidades entre sesiones causada por campos globales que se cubren entre sí cuando las sesiones están intercaladas.
            val skillSearchDefs =
                enabledSkills
                    .filter { it.id !in stickerToolIds }
                    .map { sk ->
                        // El nivel de riesgo solo se utiliza como metadatos del directorio.:Esta entrada solo ingresa al directorio de búsqueda find_tools,
                        // No registrar el libro de aprobaciones(No en toolDefs,getToolRiskLevel no puedo encontrar).
                        // Pero la habilidad en sí es el ejecutable.,Nombre por usuario/Recogida automática enchufable,no puedo presionar"suena solo lectura"
                        // Declaración SAFE;Dé NORMAL para que los metadatos sean consistentes con la verdadera ejecutabilidad de la habilidad.
                        ToolRegistry.ToolDef(
                            name = sk.id,
                            description = sk.description,
                            parameters = emptyMap(),
                            category = "skill",
                            riskLevel = io.zer0.muse.tools.ToolRiskLevel.NORMAL,
                        )
                    }
            toolRegistry.setSearchableSkills(state.sessionId, skillSearchDefs)
            // v2.6.1: Registra esta sesión de forma sincrónica“Nombre de la herramienta visible del asistente”——find_tools En consecuencia, el directorio de búsqueda se reduce,
            // No más promesas vacías para herramientas fuera de la lista blanca“Próxima ronda disponible”(Ese tipo de herramienta se encuentra en la etapa de filtrado de lista blanca a continuación.
            // será despedido y solo será reportado durante la ejecución not exposed in this turn).
            // Es más preciso utilizar ToolDefs registrados (incluido el caso especial en la lista blanca + find_tools).
            toolRegistry.setVisibleTools(state.sessionId, registeredToolDefs.map { it.name }.toSet() + enabledSkills.map { it.id })
            // v1.116: Control de probabilidad del paquete de expresiones — Leer caché de configuración,Decida si exponer la herramienta de calcomanías a LLM en esta ronda.
            // stickerEnabled=false:no expuesto en absoluto list_stickers / send_sticker
            // stickerEnabled=true:Tirar dados con pegatinaEnviarProbabilidad probabilidad,Expuesto sólo después del golpe.
            // De esta manera, LLM solo puede ver la herramienta cuando golpea con probabilidad.,Control de probabilidad implementado establecido por los usuarios.
            // v1.0.52: modulación del estado de ánimo — Detectar el sentimiento de conversaciones recientes,La probabilidad de exposición se amplifica cuando las emociones son fuertes(tapado 100%),
            // El diálogo neutral mantiene la probabilidad base establecida por el usuario.
            // v1.0.54: La función de emoticones ha quedado obsoleta. — Las herramientas nunca están expuestas(UI Cerrado,Retención de datos)
            val stickerToolsEnabled = false
            val skillToolDefs =
                enabledSkills.map { sk ->
                    // v1.0.53: send_sticker Guía de probabilidad de inyección dinámica — Deje que el modelo emita pegatinas según la probabilidad establecida por el usuario.
                    //   (El control de probabilidad sólo determina"¿Está la herramienta expuesta?",El envío o no lo determina el modelo.;Después de decir explícitamente la probabilidad
                    //   El modelo llamará activamente de acuerdo con esta probabilidad.,100% Intentaremos enviar todas las respuestas apropiadas.).
                    val stickerProbHint =
                        if (sk.id == "send_sticker") {
                            " La probabilidad de enviar emoticonos establecida por el usuario es ${settings.stickerSendProbabilityCache}%(Ajustar en la configuración)." +
                                "probabilidad ≥ 50% Tome la iniciativa de llamar a esta herramienta en la respuesta adecuada para enviar emoticones.;probabilidad = 100% Se debe intentar enviar cada respuesta."
                        } else {
                            ""
                        }
                    io.zer0.ai.core.ToolDefinition(
                        name = sk.id,
                        description = sk.description + stickerProbHint,
                        parametersJsonSchema = normalizeSkillSchema(sk.parametersJson),
                    )
                }.filter { def ->
                    // Filtre pegatinas cuando no se cumpla la probabilidad Herramientas relacionadas
                    if (stickerToolsEnabled) true else def.name !in stickerToolIds
                }
            // v1.0.4 Reparación HTTP 400 "Tool names must be unique":
            // `generate_image` También registrado como herramienta incorporada de ToolRegistry(P2-23 Origen
            // MediaGenToolsRegistrar Regístrate al inicio,Anteriormente era ChatViewModel.registerMediaTools)
            // y en SkillExecutor.BUILT_IN_SKILLS Skill,Por defecto, dos asistentes están habilitados al mismo tiempo,
            // El empalme directo emitirá duplicados tools,DeepSeek/La estación de transferencia devolverá 400 al verificar estrictamente la unicidad del nombre de la herramienta.
            // Haga clic aquí para eliminar duplicados,ToolDef(Implementación de herramientas locales)Reserva prioritaria,La Habilidad con el mismo nombre se descarta.
            val allToolDefs = (registeredToolDefs + skillToolDefs).distinctBy { it.name }
            // Una única instantánea determina simultáneamente las definiciones visibles del modelo y las rutas de ejecución.(Local > Skill).
            routeSnapshot = io.zer0.muse.tools.RouteTable.snapshot(registeredToolDefs, enabledSkills)
            tools = routeSnapshot.definitions
            // M4.3: Esquema de herramienta de observación del presupuesto total. —— Sólo registra alarmas,Sin recortes silenciosos:
            // Quitar la herramienta silenciosamente hará que el modelo"Si no puedes ver la habilidad, no la llamarás."(respaldo funcional),Ya sea para recortar
            // decisiones de producto,Requiere filtrado de intención explícito/Paquete completo de ruta de prueba(M3 Ya tiene el camino a la colección completa.).
            val schemaTotalChars = allToolDefs.sumOf { it.description.length + it.parametersJsonSchema.length }
            val schemaBudget =
                io.zer0.muse.context.ContextBudget
                    .DEFAULT_LIMITS[io.zer0.muse.context.ContextSection.TOOL_SCHEMA]
            if (schemaBudget != null && schemaTotalChars > schemaBudget) {
                Logger.w(
                    "ChatVM",
                    "tool schema total exceeds budget: $schemaTotalChars/$schemaBudget chars, " +
                        "toolCount=${allToolDefs.size}(observe-only, not trimmed)",
                )
            }

            // v2.6.x: El enlace del modelo está unificado [resolveModelBinding](Con etapa prepareHistory
            // Se utiliza la ventana de contexto de análisis.**misma funcion**, evitar“La ventana se calcula en base al modelo A y la solicitud se envía al modelo B.”).
            val allProviders = accessor.snapshot.providers
            val binding = resolveModelBinding(state)
            val resolvedModel = binding.model
            val assistantProviderConfig = binding.providerConfig

            // v1.60-A: Enrutamiento del modelo de herramienta — Las rondas de llamadas de herramientas utilizan preferentemente un peso ligero configurado por el usuario. toolModel
            // v1.0.53: per-assistant Prioridad — Úselo cuando el asistente configure el propio toolModelId,De lo contrario, recurra al nivel global. toolModelId
            // v2.x: Se agregó enlace de modelo auxiliar.(traer provider)Prioridad — El usuario golpea con precisión el canal después de vincular el "modelo de dispositivo",
            //       El antiguo toolModelId global se reduce al final.(Una vez completada la migración, generalmente se borra.).
            val utilityBinding = runCatching { settings.utilityModelBindingFlow.firstOrNull() }.getOrNull()
            val assistantToolModelId = accessor.snapshot.currentAssistant?.toolModelId?.takeIf { it.isNotBlank() }
            val utilityBoundModelId = utilityBinding?.modelId?.takeIf { it.isNotBlank() }
            val toolModelId = assistantToolModelId ?: utilityBoundModelId ?: accessor.snapshot.toolModelId
            // v2.x: golpe vinculante provider id(nivel asistente/El antiguo camino clave es null,Sigue el camino original"El proveedor del modelo principal tiene prioridad"Estrategia)
            val boundProviderId =
                utilityBinding?.providerId
                    ?.takeIf { assistantToolModelId == null && utilityBoundModelId == toolModelId }
            // v1.0.53: Al analizar, el proveedor del modelo principal tiene prioridad. — La misma identificación de modelo puede existir en múltiples provider,
            //   Directamente flatMap.firstOrNull coincidirá con irrelevante provider(Por ejemplo, kimi-k2.6 coincide con código abierto),
            //   Hace que el modo Agente salte entre proveedores. Primero encuentre dónde está el modelo principal. provider,No puedo encontrar ninguna pista sobre toda la situación.
            val toolModel: Model? =
                toolModelId?.let { tid ->
                    // v2.x: camino vinculante — Golpe exacto por parte del proveedor vinculado
                    val fromBinding =
                        boundProviderId?.let { pid ->
                            allProviders.firstOrNull { it.id == pid }?.models?.firstOrNull { it.id == tid }
                        }
                    fromBinding ?: run {
                        val inMainProvider =
                            resolvedModel?.let { rm ->
                                allProviders.firstOrNull { it.id == rm.providerId }
                                    ?.models?.firstOrNull { it.id == tid }
                            }
                        inMainProvider ?: allProviders.flatMap { it.models }.firstOrNull { it.id == tid }
                    }
                }
            val toolProviderConfig =
                toolModel?.let { m ->
                    allProviders.firstOrNull { it.id == m.providerId }
                }
            // v1.0.53: Disponibilidad de modelo de herramienta — El modelo de herramienta configurado explícitamente por el ayudante permite arbitraria provider;
            //   El modelo de herramienta global para la reversión debe ser el mismo que el modelo principal. provider,Evite que el modo Agente salte entre proveedores
            //   (El diálogo principal usa tokenrhythm, pero la rueda de herramientas usa código abierto. Una apariencia y sensación fragmentadas.).
            val toolModelUsable =
                toolModel != null && (
                    assistantToolModelId != null ||
                        // v2.x: nueva banda explícita vinculante provider,Permita que las ruedas de herramientas se extiendan provider(El usuario ha especificado con precisión el canal.)
                        boundProviderId != null ||
                        resolvedModel == null ||
                        toolModel.providerId == resolvedModel.providerId
                    )
            // C-12: El modelo principal se fija como modelo de diálogo principal.(Puede apoyar la visión),Ya no es completamente reemplazado por toolModel.
            //  effectiveModel Participar directamente en el juicio de ayuda visual.(prepareVisionContext)Enrutamiento con ronda de respuesta final,
            //  evitar"Habilitación de herramientas + configuración toolModel"Después de que todas las rondas pasan por el modelo de herramienta, la visión del modelo principal se degrada a una descripción de texto.
            val rawEffectiveModel = resolvedModel
            // v1.135: Utilice ModelRegistry para mejorar la identificación de capacidades del modelo,Resuelve el problema causado por prefijos como opencode-go/
            // supportsVision / supportsReasoning El problema del error de juicio.ChatService El interior también será mejorado nuevamente.
            effectiveModel = rawEffectiveModel?.let { ModelRegistry.enhanceModel(it) }
            effectiveProviderConfig = assistantProviderConfig
            // C-12: El modelo de herramienta sólo está disponible en"rueda de herramientas"uso(Ronda de continuación del resultado de la última ronda que contiene llamadas a herramientas,
            //  Presionado por streamRound round>1 juicio);El ciclo de recuperación final vuelve al modelo principal.,Preservar las capacidades visuales del modelo principal.
            this.toolModel = if (toolModelUsable) toolModel?.let { ModelRegistry.enhanceModel(it) } else null
            this.toolProviderConfig =
                if (toolModelUsable) {
                    toolProviderConfig?.let { provider -> applyAssistantRequestOverrides(provider, assistant) }
                } else {
                    null
                }

            // v1.136: Si el modelo actual no admite la inferencia,Bajar el nivel de inferencia a AUTO/OFF.
            // Evite enviar razonamiento_esfuerzo a modelos que no razonan, lo que provoca que se piense demasiado en problemas simples.,O devuelva 400 para modelos no compatibles.
            val effectiveModelForReasoning = effectiveModel
            reasoningLevel =
                if (effectiveModelForReasoning != null && !effectiveModelForReasoning.supportsReasoning()) {
                    if (requestedReasoningLevel == ReasoningLevel.OFF) ReasoningLevel.OFF else ReasoningLevel.AUTO
                } else {
                    requestedReasoningLevel
                }

            // Historial de conversaciones acumulativo(Contiene resultados de llamadas a herramientas,Se pueden agregar mensajes de asistente + herramienta en cada ronda)
            conversationHistory = transformedMessages.toMutableList()

            // El esquema de la herramienta tiene el mismo origen que la lista blanca de extensiones del asistente.;Los modelos de herramientas débiles ya no estarán programados para recorte adicional/Herramientas de delegación.
            // Los errores de ejecución reales todavía son manejados por el bucle de herramientas y la capa de permisos.
        }
    }

    /**
     * v1.52: per-assistant Análisis de modelos — Si el asistente está configurado con modelId, se utilizará el modelo exclusivo del asistente.,
     * De lo contrario, recurra a global selectedModelId,De lo contrario, vaya hasta el final para activar el primer modelo del Proveedor.
     * También analiza el modelo al que pertenece. ProviderConfig,Asegúrese de que los modelos auxiliares de todos los proveedores también se enruten correctamente.
     *
     * v2.6.x: de [resolveToolsAndModel] El bloque en línea se extrae como**Sin efectos secundarios**salida única.
     * motivación:apertura de la ventana de contexto (90% Intercepción agresiva/intercepción dura 1,35x/presupuesto de calentamiento y compresión)
     * debe estar en `prepareHistory` El modelo real de esta ronda se conoce en este momento, pero la cadena de herramientas aún no se ha analizado en ese momento.
     * Después de extraer el análisis en una función, los dos lugares comparten la misma lógica y no aparecerán.“Análisis de dos conjuntos de modelos.”.
     *
     * @param logBindingIssues Ya sea para generar el registro de degradación vinculante (prepare Pasa falso en el escenario,
     *   todavía por [resolveToolsAndModel] Salida de pulsador para evitar dos alarmas idénticas en la misma ronda)
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    internal fun resolveModelBinding(state: StreamRunState, logBindingIssues: Boolean = true): GenerationModelBinding {
        val assistant = state.assistant
        val allProviders = accessor.snapshot.providers
        // v1.0.79 (F-1): Los modelos que se cambian manualmente dentro de una sesión tienen prioridad sobre los modelos específicos del asistente
        val taskRouteModel = state.taskRouteSelection?.modelId?.takeIf { it.isNotBlank() }
        val taskRouteProvider = state.taskRouteSelection?.providerId?.takeIf { it.isNotBlank() }
        val sessionOverride =
            taskRouteModel
                ?: state.sessionModelOverride?.takeIf { it.isNotBlank() }
        val sessionProviderOverride =
            taskRouteProvider
                ?: state.sessionProviderOverride?.takeIf { it.isNotBlank() }
        val activeProviderId =
            sessionProviderOverride
                ?: assistant?.providerId?.takeIf { it.isNotBlank() }
                ?: state.fallbackProviderId
                ?: accessor.snapshot.activeProviderId
        val assistantModelId =
            sessionOverride
                ?: assistant?.modelId?.takeIf { it.isNotBlank() }
                ?: state.fallbackModelId
        // El proveedor y el modelo para el cambio de sesión manual deben emparejarse primero y los asistentes o proveedores globales no pueden anularlos.
        val assistantProviderId =
            sessionProviderOverride
                ?: assistant?.providerId?.takeIf { it.isNotBlank() }
                ?: state.fallbackProviderId
        // v2.6.9: Modelo de decisión vinculante para los tres Estados(ver resolveRequestedModelBinding).
        // Escritura original `models.none { it.id == assistantModelId }` Siempre cuando los modelos son una lista vacía true,
        // "La lista de modelos aún no ha sido eliminada"/"La extracción falló" se juzga erróneamente como "el modelo no existe" y se degrada a un modelo de cobertura.
        // (La máquina real muestra que la sesión ha cambiado a GLM-4-9B,La herramienta modelo llama a la malformación.,La generación de la tarjeta falla directamente).
        // ahora:La lista está vacía → Lista no lista,Preservar el enlace de la solicitud sin degradar;La lista no está vacía y no contiene → Realmente desaparecido,Tome la ruta original.
        val requestedBinding =
            resolveRequestedModelBinding(allProviders, assistantProviderId, assistantModelId)
        if (logBindingIssues && requestedBinding.missing) {
            Logger.w(
                "ChatVM",
                "requested model binding unavailable; applying fallback: " +
                    "provider=${assistantProviderId ?: "-"}, model=${assistantModelId ?: "-"}, " +
                    "providerMissing=${requestedBinding.providerMissing}, " +
                    "modelMissing=${requestedBinding.modelMissing}, " +
                    "sessionId=${state.sessionId}, assistantId=${assistant?.id ?: "-"}",
            )
        } else if (logBindingIssues && requestedBinding.listNotReady) {
            // Lista de modelos no lista:No falta,Esto no se puede utilizar para bajar de categoría. Preservar la depuración,Pero no se emitirá ninguna alarma de nivel W.
            Logger.i(
                "ChatVM",
                "requested model binding kept, model list not fetched yet: " +
                    "provider=${assistantProviderId ?: "-"}, model=${assistantModelId ?: "-"}, " +
                    "providerModelCount=0, binding=placeholder(id only, no fallback), " +
                    "sessionId=${state.sessionId}, assistantId=${assistant?.id ?: "-"}",
            )
        }
        val resolvedModel: Model? =
            if (assistantModelId != null && assistantProviderId != null) {
                // golpe preciso → modelo original;Lista no lista → Construir enlace de marcador de posición por ID de solicitud(Sin degradación);
                // Realmente desaparecido → null,Déjalo en la cadena de bolsillo de abajo.(consistente con el comportamiento original).
                requestedBinding.model
            } else {
                // v1.0.53+: Activar proveedor primero — Cuando el asistente solo está equipado con modelId (no proveedorId),
                // Priorice buscar la misma identificación en el modelo que activa el Proveedor para evitar múltiples proveedores con el mismo modelo de identificación.
                // (Por ejemplo, deepseek-v4-flash existe tanto en el canal opencode-go como en el canal oficial) coincidencia global de flatMap
                // Causas de golpear a un proveedor no activado“Después de cambiar de proveedor, las solicitudes de chat aún pasan por el canal anterior.”.
                // Si no se puede encontrar el Proveedor activado (por ejemplo, el asistente está vinculado a un modelo que no existe en el Proveedor), entonces se cubrirá toda la situación.
                assistantModelId?.let { aid ->
                    val inActive =
                        allProviders.firstOrNull { it.id == activeProviderId }
                            ?.models?.firstOrNull { it.id == aid }
                    inActive ?: allProviders.flatMap { it.models }.firstOrNull { it.id == aid }
                }
            } ?: accessor.snapshot.selectedModelId?.let { sid ->
                // La misma estrategia de prioridad del proveedor de activación que la anterior
                val inActive =
                    allProviders.firstOrNull { it.id == activeProviderId }
                        ?.models?.firstOrNull { it.id == sid }
                inActive ?: allProviders.flatMap { it.models }.firstOrNull { it.id == sid }
            }
                // Ten todo en cuenta:selectedModelId Cuando es nulo y el asistente no está equipado con modelId,
                // Priorizar el primer modelo que active el Proveedor,Evite la selección entre proveedores de modelos de otros proveedores por error.
                // v1.0.28 Reparación: Anteriormente, se usaba allProviders.firstOrNull y no se consideraba para la activación. provider,
                // El modelo gratuito de SiliconFlow se selecciona por error en escenarios de múltiples proveedores(Si SiliconFlow está clasificado por delante de todos los proveedores).
                // escena:El usuario cambia a OpenCode pero no hace clic en un modelo específico(Deje que el primero esté seleccionado por defecto),El primer modelo que se debe seleccionar para OpenCode,
                // En lugar del GLM-4-9B de SiliconFlow.
                ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }?.let { p ->
                    p.models.firstOrNull()
                }
                // Cobertura de segundo nivel:Activar proveedor sin modelo(Si no se tira/No completado apiKey),Sólo entonces devolví el primero con un modelo. provider
                ?: allProviders.firstOrNull { it.models.isNotEmpty() }?.let { p ->
                    p.models.firstOrNull()
                }
        val resolvedProviderConfig =
            resolvedModel?.let { m ->
                allProviders.firstOrNull { it.id == m.providerId }
            } ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
                ?: allProviders.firstOrNull { it.models.isNotEmpty() }
        return GenerationModelBinding(
            requestedProviderId = assistantProviderId,
            requestedModelId = assistantModelId,
            model = resolvedModel,
            providerConfig =
            resolvedProviderConfig?.let { provider ->
                applyAssistantRequestOverrides(provider, assistant)
            },
        )
    }

    /** Integre los encabezados/cuerpo personalizados configurados en la página avanzada del asistente en la solicitud real del Proveedor. */
    private fun applyAssistantRequestOverrides(
        provider: io.zer0.ai.core.ProviderConfig,
        assistant: io.zer0.muse.data.assistant.AssistantEntity?,
    ): io.zer0.ai.core.ProviderConfig {
        // Responses API Se deben conservar los campos de protocolo específicos como OpenAI.useResponseApi;
        // La solicitud de personalización avanzada del Asistente actualmente solo tiene efecto para el Proveedor de finalización de chat,evitar poner Responses
        // Reemplace la configuración con Personalizada y cambie silenciosamente el protocolo.
        if (assistant == null || provider.type != io.zer0.ai.core.ProviderType.OPENAI) return provider
        val headers = assistantRepository.parseCustomHeaders(assistant)
        val bodies = assistantRepository.parseCustomBodies(assistant)
        if (headers.isEmpty() && bodies.isEmpty()) return provider
        val specific = provider.resolvedSpecific()
        val updatedSpecific =
            when (specific) {
                is ProviderSpecificConfig.Custom ->
                    specific.copy(
                        customHeaders = specific.customHeaders + headers,
                        customBody = specific.customBody + bodies,
                    )
                is ProviderSpecificConfig.OpenAI ->
                    specific.copy(
                        customHeaders = specific.customHeaders + headers,
                        customBody = specific.customBody + bodies,
                    )
                else -> specific
            }
        return provider.copy(
            specific = updatedSpecific,
        )
    }
}

/**
 * Normalizar el esquema de parámetros de habilidades.
 *
 * OpenAI La compatibilidad de API requiere que los parámetros de la función estén en el esquema JSON `{"type":"object",...}` estructura.
 * Los paquetes de complementos externos (como pelle-d-umore) pueden escribir parámetros como vacíos `{}`(sin campo de tipo),
 * La transmisión transparente directa será rechazada por el upstream (HTTP 400: schema must be a JSON Schema of type object).
 * Cuando falta el objeto vacío/cadena vacía/tipo, se completará el esquema de objeto vacío estándar.
 */
private fun normalizeSkillSchema(raw: String): String {
    if (raw.isBlank()) return """{"type":"object","properties":{}}"""
    return runCatching {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        if (element.containsKey("type")) {
            raw
        } else {
            kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                element.forEach { (k, v) -> put(k, v) }
            }.toString()
        }
    }.getOrElse { """{"type":"object","properties":{}}""" }
}
