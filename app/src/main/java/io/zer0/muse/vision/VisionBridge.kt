package io.zer0.muse.vision

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.VisionCapabilities
import io.zer0.ai.core.inferFromMessage
import io.zer0.ai.core.providerError
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.util.retryOnNetworkError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anomalías del análisis visual.
 *
 * v1.135: La ayuda visual ya no regresa silenciosamente cuando falla. null,En lugar de lanzar esta excepción,
 * por la persona que llama(Como por ejemplo [io.zer0.muse.ui.ChatViewModel])Capture e inyecte avisos de falla e imágenes claras,
 * Evite enviar la imagen original al modelo de texto sin formato, lo que generará HTTP 400.
 */
class VisionAnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Cuando el texto está vacío, el razonamiento devuelto por el proveedor se conserva para evitar que el resultado del modelo visual se juzgue erróneamente como vacío. */
internal fun effectiveVisionResponseText(text: String, reasoningContent: String?): String = text.ifBlank { reasoningContent.orEmpty() }

/** Proporciona causas diagnosticables de proveedores, modelos y registros para discrepancias en la capacidad de visión. */
internal fun visionUnsupportedReason(providerName: String, modelId: String, inputModalities: Set<String>): String {
    val detected = inputModalities.sorted().joinToString(", ").ifBlank { "unknown" }
    return "proveedor $providerName modelo $modelId La entrada de imágenes no es compatible(Capacidades identificadas: $detected, Cambie a un modelo que realmente admita la entrada visual.)"
}

/**
 * v1.0.4: Ayuda visual a los resultados de la fase de preparación.
 *
 * cuando tiene éxito [text] con inyectado `<vision-context>` Descripción,[images] Borrar(Evite publicar imágenes en modelos de solo texto).
 * en el fracaso [text] Contiene mensaje de error,[images] Borrar,Déjele saber claramente al modelo de texto que no hay imágenes en esta ronda.
 *
 * @param text Texto de mensaje de usuario procesado(Con descripción visual o mensaje de error.)
 * @param images Lista de imágenes procesadas(éxito/Borrado en caso de error,Porque el modelo de texto plano no puede recibir imágenes.)
 * @param success Análisis exitoso(El texto en caso de error es el mensaje de degradación)
 * @param descriptionCount Número de imágenes analizadas con éxito(En caso de fracaso 0)
 */
data class VisionPrepareResult(
    val text: String,
    val images: List<String>,
    val success: Boolean,
    val descriptionCount: Int,
)

/**
 * Puente de asistencia visual — Pasar un modelo de texto plano a través de un modelo visual"ver"fotos.
 *
 * # v1.0.5 reescribir
 *
 * Principio de funcionamiento:
 *  1. Compruebe si el modelo actual admite entrada visual([supportsVision])
 *  2. Si no es compatible y las ayudas visuales están habilitadas,Analizar imágenes con modelos de visión configurados.
 *  3. Obtener una descripción de texto estructurado(Contiene respuestas específicas de user_request),a `<vision-context>` Las etiquetas inyectan mensajes de usuario
 *  4. Borrar la lista de imágenes del mensaje original.,Evite enviar imágenes a modelos de texto sin formato, lo que resultará en HTTP 400
 *
 * # v1.0.5 Reescribir problemas solucionados
 *
 *  1. **integrar [VisionImagePreprocessor]**:Las imágenes se comprimen antes de enviarlas al modelo visual.(2000×2000 + JPEG 80),
 *     Las imágenes de gran tamaño ya no se descartarán directamente,Pero comprimirlo en el presupuesto.
 *  2. **La semántica de la clave de caché es clara**:Usar en su lugar [VisionCache.CacheKey] clase de datos,Ya no reutilice el complemento de campo visionModelId suffix
 *  3. **Quitar mimeType dead parameter**:[analyzeImage] Ya no acepta parámetros mimeType no utilizados
 *  4. **grounding Mejoras**:JSON Cuando el análisis falla, utilice la respuesta original como `image_overview` en vez de `evidence`,
 *     Deje que el modelo de texto lea la información principal.(7 campos originales completados"Ninguno"Baja utilización de la información)
 *  5. **visionCache Cambiar a no nulo**:Quitar `visionCache: VisionCache? = null` diseño vacío,reducir `?.` defensa
 *
 * # ventaja retenida
 *
 *  - **Análisis de concurrencia**:Varias imágenes `coroutineScope { async }.awaitAll` concurrencia
 *  - **tiempo de espera+Inténtalo de nuevo**:`withTimeout(60s)` + `retryOnNetworkError(3veces)`
 *  - **streamChat Rebajar**:Provider Bajar de categoría streamChat cuando no se admita completeText.
 *  - **Progreso StateFlow**:`progressFlow` Exponer el progreso del análisis para visualización en tiempo real mediante la interfaz de usuario
 */
class VisionBridge(
    private val chatService: ChatService,
    private val settings: SettingsRepository,
    private val visionCache: VisionCache,
) {

    companion object {
        private const val TAG = "VisionBridge"

        /** v1.0.2 (P0): Tiempo de espera del análisis visual de una sola imagen(milisegundos). */
        private const val ANALYSIS_TIMEOUT_MS = 60_000L

        /** v1.0.2 (P0): Parámetros de reintento de red(Reintentar sólo para IOException). */
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 500L

        /**
         * v1.0.5: VISION_PROMPT número de versión,Se utiliza para almacenar en caché las claves.
         *
         * Al modificar el contenido de la palabra del mensaje, este número de versión se incrementará simultáneamente.,Invalida automáticamente los cachés antiguos.
         * v1.0.5 reescribir:Ajuste rápido de palabras + integración VisionImagePreprocessor,El número de versión aumentó de 2 a 3.
         */
        private const val VISION_PROMPT_VERSION = 3

        /** v1.0.4: El número máximo de caracteres para una sola nota visual. */
        private const val MAX_NOTE_CHARS = 3200

        /** v1.0.4: visual primitives Cantidad máxima. */
        private const val MAX_VISUAL_PRIMITIVES = 16

        /** v1.0.4: primitive ref El número máximo de caracteres para la etiqueta. */
        private const val MAX_PRIMITIVE_REF_CHARS = 96

        /**
         * v1.0.4: note-only palabras de indicación de ruta(El modelo no es compatible grounding).
         *
         * Se requiere que el modelo se genere mediante 8 campos fijos.
         * Mejoras clave:Unirse `user_request` campo,Pasar las preguntas específicas del usuario al modelo visual.,
         * Deje que el modelo visual responda preguntas específicas(en lugar de una descripción general).
         */
        private val VISION_PROMPT_NOTE_ONLY = """
            Por favor analice esta imagen.,Genere una descripción estructurada para otro modelo de solo texto.
            Utilice la siguiente salida de campo fijo,Cada campo está en su propia línea.,Agregue dos puntos después del nombre del campo:

            image_overview: Una descripción general del contenido general de la imagen.
            visible_text: Texto importante visible en la imagen.(OCR),Al menos escribe"Ninguno"
            objects_and_layout: Objetos importantes, ubicaciones, cantidades y sus relaciones.
            charts_or_data: cuadro/mesa/Detalles de datos,Al menos escribe"Ninguno"
            user_request: Reformule la solicitud del usuario en una oración.
            user_request_answer: Responder a las solicitudes de los usuarios según el contenido de la imagen.
            evidence: Evidencia visual para respaldar la respuesta anterior.
            uncertainty: Contenido incierto, oscurecido o que requiere conjeturas.

            No menciones que eres una herramienta o modelo independiente.,Simplemente envíe el contenido del campo directamente.

            solicitud de usuario:
            %s
        """.trimIndent()

        /**
         * v1.0.4: structured+primitives palabras de indicación de ruta(Soporte de modelo grounding).
         *
         * Solicitar devolución estricta JSON,Contiene 8 campos + matriz visual_primitives(marco de coordenadas).
         * El formato de coordenadas viene dado por [primitivePromptShape] Derivado del formato de salida del modelo.
         */
        private val VISION_PROMPT_WITH_GROUNDING = """
            Por favor analice esta imagen.,Genere una descripción JSON estructurada para otro modelo de solo texto.
            Retorno estricto JSON(Sin bloques de código de rebajas),Contiene los siguientes campos:

            {
              "image_overview": "Descripción general del contenido general de la imagen.",
              "visible_text": ["Texto importante visible en la imagen.1", "texto2"],
              "objects_and_layout": "Objetos importantes, ubicaciones, cantidades y sus relaciones.",
              "charts_or_data": "cuadro/mesa/Detalles de datos,Al menos escribe none",
              "user_request": "Reformule la solicitud del usuario en una oración.",
              "user_request_answer": "Responder a las solicitudes de los usuarios según el contenido de la imagen.",
              "evidence": "Evidencia visual para respaldar la respuesta anterior.",
              "uncertainty": "Contenido incierto, oscurecido o que requiere conjeturas.",
              "visual_primitives": %s
            }

            Las coordenadas utilizan el sistema de coordenadas normalizado 0-1000.(Origen de la esquina superior izquierda).
            No menciones que eres una herramienta o un modelo independiente.

            solicitud de usuario:
            %s
        """.trimIndent()
    }

    /**
     * v1.0.2 (P1): Progreso del análisis visual StateFlow,Para visualización de UI en tiempo real.
     */
    private val _progressFlow = MutableStateFlow(VisionProgress(idle = true, index = 0, total = 0))
    val progressFlow: StateFlow<VisionProgress> = _progressFlow.asStateFlow()

    /**
     * Comprueba si el modelo dado admite la entrada directa de imágenes.
     *
     * v2.6.1: **Las configuraciones editadas explícitamente por el usuario tienen prioridad sobre todo lo demás.**.
     * El usuario lo ha guardado en "Editar capacidad del modelo".(`userEdited=true`)tiempo,adoptar directamente su
     * [Model.supportsVisionInput] Declaración,No más registro/veredicto del directorio — Reparación
     * “Después de encender el interruptor multimodal y guardar,Aún así se consideró que la imagen no estaba enderezada y se le dijo a la modelo que no se podía ver la imagen.”.
     *
     * M2.1/M2.8: Sentencia cuando no está editada [io.zer0.ai.registry.ModelCapabilityQuery] fallo de tres estados —
     *  - SUPPORTED(Modelo visual de confirmación de registro)→ Liberar directamente;
     *  - UNSUPPORTED(Texto sin formato de validación de registro,Como por ejemplo GLM-4.6)→ rechazar,Incluso aguas arriba/tránsito
     *    mal etiquetado supportsVision=true Tampoco envíes fotos directamente.(La persona que llama necesita ayuda visual para bajar de categoría.);
     *  - UNKNOWN(Modelos no incluidos/Nombre de implementación privada)→ Utilice de forma conservadora la propia declaración del modelo.
     *    (Los usuarios pueden incluirlo manualmente en la página de configuración visual.,El registro no anula las configuraciones del usuario.).
     */
    fun supportsVision(model: Model): Boolean {
        // La configuración de la capacidad de los usuarios para editar explícitamente tiene la máxima prioridad.,Basado directamente en su declaración.
        if (model.userEdited) {
            return model.supportsVisionInput()
        }
        val snapshot = io.zer0.ai.registry.ModelCapabilityQuery.snapshot(
            modelId = model.id,
            providerId = model.providerId,
        )
        return when (snapshot.visionInput) {
            io.zer0.ai.registry.CapabilitySupport.SUPPORTED -> true
            io.zer0.ai.registry.CapabilitySupport.UNSUPPORTED -> false
            io.zer0.ai.registry.CapabilitySupport.UNKNOWN -> model.supportsVisionInput()
        }
    }

    /** Seguridad vacía y juicio conveniente.:Devuelto cuando el modelo no se analiza false(La persona que llama no necesita determinar el valor nulo por sí misma.,Evite la hinchazón de las ramas). */
    fun supportsVisionModel(model: Model?): Boolean = model != null && supportsVision(model)

    /**
     * v1.0.4: Ayuda visual para preparar la entrada al escenario.
     *
     * Llamado antes de enviar un mensaje al modelo de texto sin formato.,poner fotos"Traducción"Inyectar mensajes de usuario en descripciones textuales,
     * y borrar la lista de imágenes(evitar HTTP 400).
     *
     * v1.0.5 mejorar:
     *  - integrar [VisionImagePreprocessor.prepareBatch],La imagen primero se comprime a 2000×2000 + JPEG 80
     *  - Error en el preprocesamiento(Presupuesto total excedido)Tome el camino de degradación,Borrar la imagen + Aviso de fallo de inyección
     *
     * en el fracaso(El modelo visual no está disponible/tiempo de espera/Limitación de corriente)Tome el camino de degradación:
     *  - Borrar imágenes(Los modelos de solo texto no pueden recibir imágenes)
     *  - Inyecte mensaje de error antes del texto del mensaje del usuario,Dejar que el modelo de texto lo sepa explícitamente"No hay contenido de imagen en esta ronda."
     *  - Regresar [VisionPrepareResult](success=false),No lanzar excepción
     *
     * Error de configuración(La ayuda visual no está habilitada/No configurado/El modelo no soporta la visión.)Todavía tirando [VisionAnalysisException],
     * Porque este es un problema de configuración del usuario.,No se debe degradar silenciosamente.
     *
     * @param text Texto del mensaje de usuario
     * @param images Lista de imágenes base64(Ninguno dato: prefijo)
     * @param userRequest Solicitud específica del usuario(Generalmente el texto del mensaje del usuario.),Páselo al modelo visual para un análisis específico.
     * @param sessionId sesión actual id(para almacenamiento en caché);null No usar almacenamiento en caché
     * @param messageId v1.0.16: Mensajes en análisis id,La interfaz de usuario del controlador solo muestra el progreso en este mensaje
     * @param onProgress v1.0.20: Devolución de llamada de progreso (current, total),Se activa cada vez que se completa una imagen.
     *   con [progressFlow] Disparador paralelo,Esto permite a la persona que llama actualizar directamente el estado de la interfaz de usuario.,No es necesario recopilar Flow por separado.
     *   La devolución de llamada se activa en el hilo IO,Si la persona que llama necesita actualizar la interfaz de usuario, debería cambiar al hilo principal por sí solo.
     * @return prepare resultado(Éxito con la descripción.,Error con el mensaje de degradación)
     */
    suspend fun prepare(
        text: String,
        images: List<String>,
        userRequest: String,
        sessionId: String? = null,
        /** v1.0.16: Mensajes en análisis id,La interfaz de usuario del controlador solo muestra el progreso en este mensaje */
        messageId: String? = null,
        /** v1.0.20: Devolución de llamada de progreso,Se activa cada vez que se completa una imagen. (current=Completado, total=Número total de imágenes) */
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): VisionPrepareResult {
        if (images.isEmpty()) {
            return VisionPrepareResult(text, images, success = true, descriptionCount = 0)
        }

        // v1.0.20: Asincronización de ayuda visual — prepare red interna IO(Preprocesamiento de imágenes + llamada de modelo visual),
        //   Cambie a la ejecución de IO Dispatcher durante todo el proceso,Evite bloquear el hilo principal en la ruta crítica de launchStream.
        //   Retraso significativo al tomar varias fotografías,La interfaz de usuario se puede mostrar inmediatamente después de la asincronización."Analizar imágenes 0/N…"progreso.
        return withContext(Dispatchers.IO) {
            // v1.0.5: Preprocesamiento de imágenes(Compresión + rastreo MIME + control de presupuesto)
            val preparedImages: List<VisionImagePreprocessor.PreparedImage> = try {
                VisionImagePreprocessor.prepareBatch(images)
            } catch (e: VisionImagePreprocessException) {
                Logger.w(TAG, "Error en el preprocesamiento de la imagen,Tome el camino de degradación: ${e.message}")
                val notice = buildFailureNotice(e.message)
                return@withContext VisionPrepareResult(
                    text = "$notice\n\n$text",
                    images = emptyList(),
                    success = false,
                    descriptionCount = 0,
                )
            }

            if (preparedImages.isEmpty()) {
                return@withContext VisionPrepareResult(text, images, success = true, descriptionCount = 0)
            }

            try {
                // v1.0.20: Inicialización del progreso — devolución de llamada inmediatamente (0, total),Deja que la interfaz de usuario se muestre"Analizar imágenes 0/N…"
                onProgress?.invoke(0, preparedImages.size)
                val descriptions = analyzeImages(preparedImages, userRequest, sessionId, messageId, onProgress)
                if (descriptions.isEmpty()) {
                    val notice = buildFailureNotice("El análisis visual devuelve resultados vacíos")
                    VisionPrepareResult("$notice\n\n$text", emptyList(), success = false, descriptionCount = 0)
                } else {
                    val visionPrefix = buildVisionContext(descriptions)
                    VisionPrepareResult(
                        text = visionPrefix + text,
                        images = emptyList(),
                        success = true,
                        descriptionCount = descriptions.size,
                    )
                }
            } catch (e: VisionAnalysisException) {
                Logger.w(TAG, "La preparación de la ayuda visual falló,Tome el camino de degradación: ${e.message}")
                val notice = buildFailureNotice(e.message)
                VisionPrepareResult(
                    text = "$notice\n\n$text",
                    images = emptyList(),
                    success = false,
                    descriptionCount = 0,
                )
            }
        }
    }

    /**
     * v1.0.5: Analizar una única imagen preprocesada,Regresar `<vision-context>` Una descripción de texto del paquete.
     *
     * mejorar:
     *  - recibir [VisionImagePreprocessor.PreparedImage] en lugar de desnudo base64,Contiene internamente datos comprimidos y reales. MIME
     *  - Eliminar no utilizado mimeType dead parameter
     *
     * @param preparedImage imágenes preprocesadas
     * @param userRequest Solicitud específica del usuario,Páselo al modelo visual para un análisis específico.
     * @return texto de descripción visual(Contiene `<vision-context>` etiqueta)
     * @throws VisionAnalysisException Se produce cuando está deshabilitado, no configurado, el modelo no admite la visión o el análisis falla
     */
    private suspend fun analyzeImage(preparedImage: VisionImagePreprocessor.PreparedImage, userRequest: String = ""): String {
        // 1-3. Análisis de modelos y proveedores de visión(Interruptor asistido por visión/configuración del modelo/inspección de tres pasos de la capacidad visual)
        val (visionModel, visionProvider) = resolveVisionModelStrict()

        // 4. Seleccione la ruta de la palabra solicitada según si se admite la conexión a tierra
        val useGrounding = visionModel.supportsVisionGrounding()
        val prompt = if (useGrounding) {
            val primitiveShape = primitivePromptShape(visionModel.visionCapabilities)
            VISION_PROMPT_WITH_GROUNDING.format(primitiveShape, userRequest.ifBlank { "(Sin solicitud explícita,Por favor describe la imagen en su totalidad.)" })
        } else {
            VISION_PROMPT_NOTE_ONLY.format(userRequest.ifBlank { "(Sin solicitud explícita,Por favor describe la imagen en su totalidad.)" })
        }

        val userMessage = UIMessage(
            role = MessageRole.USER,
            content = prompt,
            imageBase64List = listOf(preparedImage.base64),
        )

        return try {
            Logger.i(TAG, "Iniciar análisis visual: model=${visionModel.id}, grounding=$useGrounding, userRequest=${userRequest.take(50)}")
            val rawResponse = withTimeout(ANALYSIS_TIMEOUT_MS) {
                retryOnNetworkError(maxRetries = MAX_RETRIES, initialDelayMs = INITIAL_RETRY_DELAY_MS) {
                    callVisionModel(userMessage, visionModel, visionProvider)
                }
            }.trim()

            if (rawResponse.isBlank()) {
                throw VisionAnalysisException("El modelo de visión devuelve una descripción vacía")
            }

            // 5. grounding Ruta analizando JSON y normalizando;note-only La ruta utiliza directamente el texto original.
            val description = if (useGrounding) {
                parseGroundingResponse(rawResponse, visionModel.visionCapabilities)
            } else {
                truncateNote(rawResponse)
            }

            Logger.i(TAG, "Análisis visual completado,Descripción longitud: ${description.length}, grounding=$useGrounding")
            "<vision-context>\n$description\n</vision-context>"
        } catch (e: VisionAnalysisException) {
            throw e
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                Logger.w(TAG, "Tiempo de espera del análisis visual(${ANALYSIS_TIMEOUT_MS}ms)")
                throw VisionAnalysisException("Tiempo de espera de respuesta del modelo de visión(${ANALYSIS_TIMEOUT_MS / 1000}s),Inténtelo de nuevo más tarde o cambie el modelo visual.", e)
            }
            throw e
        } catch (e: Exception) {
            val friendlyMsg = friendlyVisionError(e)
            Logger.w(TAG, "El análisis visual falló: ${e.message} -> $friendlyMsg", e)
            throw VisionAnalysisException(friendlyMsg, e)
        }
    }

    /**
     * v2.2.1: Preguntas generales con imágenes. —— Proporciona enlaces de llamada y análisis de modelos de reutilización de toma de decisiones visuales para el anillo del Agente GUI.
     *
     * con [analyzeImage] diferencia:Rápida construcción de palabras y análisis de descripción sin conexión a tierra.,Devuelve el texto del modelo sin cambios.
     *
     * @throws VisionAnalysisException La visión no está habilitada/No configurado/El modelo no soporta la visión./Se lanza cuando falla la llamada.
     */
    suspend fun askWithImage(
        prompt: String,
        imageBase64: String,
        timeoutMs: Long = ANALYSIS_TIMEOUT_MS,
        systemPrompt: String? = null,
    ): String {
        val (visionModel, visionProvider) = resolveVisionModelStrict()
        val messages = buildVisionMessages(prompt, imageBase64, systemPrompt)
        return withTimeout(timeoutMs) {
            retryOnNetworkError(maxRetries = MAX_RETRIES, initialDelayMs = INITIAL_RETRY_DELAY_MS) {
                callVisionModel(messages, visionModel, visionProvider)
            }
        }.trim()
    }

    /**
     * Análisis de modelos visuales.(Inspección en tres pasos del interruptor/configuración/capacidad). Lanzarse al fracaso [VisionAnalysisException].
     *
     * [analyzeImage] con [askWithImage] compartir;Prefijo de registro mantenido"análisis visual[..]"calibre.
     */
    private suspend fun resolveVisionModelStrict(): Pair<Model, ProviderConfig> {
        val enabled = settings.visionEnabledFlow.first()
        if (!enabled) {
            Logger.w(TAG, "análisis visual[saltar]: El interruptor de asistencia visual no está encendido")
            throw VisionAnalysisException("La ayuda visual no está habilitada(Por favor configure-Encienda el interruptor principal en la ayuda visual.)")
        }

        val visionModelId = settings.visionModelIdFlow.first()
        val visionProviderId = settings.visionProviderIdFlow.first()
        if (visionModelId.isNullOrBlank() || visionProviderId.isNullOrBlank()) {
            Logger.w(TAG, "análisis visual[saltar]: modelId=$visionModelId providerId=$visionProviderId")
            throw VisionAnalysisException("Modelo de visión o proveedor no configurado(Por favor configure-Seleccione entre ayudas visuales)")
        }

        val allProviders = settings.getAllProviders()
        val visionProvider: ProviderConfig = allProviders.firstOrNull { it.id == visionProviderId }
            ?: throw VisionAnalysisException("Proveedor de visión $visionProviderId no encontrado(puede haber sido eliminado)").also {
                Logger.w(TAG, "análisis visual[saltar]: providerId=$visionProviderId aquí no ${allProviders.size} de proveedores")
            }

        val rawVisionModel: Model = visionProvider.models.firstOrNull { it.id == visionModelId }
            ?: throw VisionAnalysisException("modelo visual $visionModelId no encontrado(proveedor ${visionProvider.displayName} total ${visionProvider.models.size} modelos)").also {
                Logger.w(TAG, "análisis visual[saltar]: modelId=$visionModelId No en el proveedor ${visionProvider.displayName} en la lista de modelos")
            }

        val visionModel = ModelRegistry.enhanceModel(rawVisionModel)
        Logger.i(
            TAG,
            "análisis visual[Configuración]: model=${visionModel.id} supportsVision=${visionModel.supportsVisionInput()} " +
                "inputModalities=${visionModel.inputModalities}",
        )

        if (!visionModel.supportsVisionInput()) {
            throw VisionAnalysisException(
                visionUnsupportedReason(
                    providerName = visionProvider.displayName,
                    modelId = visionModel.id,
                    inputModalities = visionModel.inputModalities,
                ),
            )
        }
        return visionModel to visionProvider
    }

    /**
     * v1.0.4: Según [VisionCapabilities.outputFormat] Sugerencia de esquema JSON para primitiva derivada.
     *
     * Diferentes modelos tienen diferentes formatos de coordenadas nativas:
     *  - "gemini": box_2d: [ymin, xmin, ymax, xmax](Gemini Nativo yxyx,normalización 0-1000)
     *  - "qwen": bbox_2d: [x1, y1, x2, y2] + point_2d: [x, y](Qwen-VL Nativo)
     *  - "anchor": visual_anchors traer role/center/box(Claude computer-use estilo)
     *  - "muse-box"/null: box: [x1, y1, x2, y2](Formato de coordenadas unificado,xyxy + norm-1000)
     */
    private fun primitivePromptShape(caps: VisionCapabilities?): String {
        return when (resolveOutputFormat(caps?.outputFormat)) {
            "gemini" -> """
                [
                  {"type": "box", "box_2d": [ymin, xmin, ymax, xmax], "ref": "breve descripción del objeto", "confidence": 0.9}
                ]
                box_2d uso [ymin, xmin, ymax, xmax] orden,normalizado a 0-1000
            """.trimIndent()
            "qwen" -> """
                [
                  {"type": "box", "bbox_2d": [x1, y1, x2, y2], "ref": "breve descripción del objeto", "confidence": 0.9},
                  {"type": "point", "point_2d": [x, y], "ref": "Descripción de la ubicación del punto"}
                ]
                bbox_2d Usar con point_2d [x, y] orden,normalizado a 0-1000
            """.trimIndent()
            "anchor" -> """
                [
                  {"role": "button|text|object|region", "center": [x, y], "box": [x1, y1, x2, y2], "ref": "Descripción del elemento"}
                ]
                visual_anchors Las coordenadas de están normalizadas a 0-1000,box uso [x1, y1, x2, y2] orden
            """.trimIndent()
            else -> """
                [
                  {"type": "box", "box": [x1, y1, x2, y2], "ref": "breve descripción del objeto", "confidence": 0.9}
                ]
                box uso [x1, y1, x2, y2] orden(Origen de la esquina superior izquierda),normalizado a 0-1000
            """.trimIndent()
        }
    }

    /**
     * v1.0.4: Analizar la respuesta a tierra,coordenadas normalizadas,generar que contiene `<visual-primitives>` Descripción del bloque.
     *
     * v1.0.5 mejorar:Cuando el análisis falla, utilice la respuesta original como `image_overview`(en vez de `evidence`),
     * Deje que el modelo de texto lea la información principal.(7 campos originales completados"Ninguno"Baja utilización de la información).
     */
    private fun parseGroundingResponse(rawResponse: String, caps: VisionCapabilities?): String {
        // Intenta extraer JSON(Los modelos pueden devolver bloques de código de rebajas envueltos en JSON)
        val jsonText = extractJson(rawResponse) ?: run {
            Logger.w(TAG, "grounding La respuesta no es JSON,Sigue el camino de la nota")
            return formatInvalidStructuredNote(rawResponse)
        }

        return try {
            val jsonObj = io.zer0.common.AppJson.parseToJsonElement(jsonText).let {
                it as? kotlinx.serialization.json.JsonObject ?: return formatInvalidStructuredNote(rawResponse)
            }

            val builder = StringBuilder()
            builder.append("image_overview: ").append(jsonField(jsonObj, "image_overview")).append('\n')
            builder.append("visible_text: ").append(jsonField(jsonObj, "visible_text")).append('\n')
            builder.append("objects_and_layout: ").append(jsonField(jsonObj, "objects_and_layout")).append('\n')
            builder.append("charts_or_data: ").append(jsonField(jsonObj, "charts_or_data")).append('\n')
            builder.append("user_request: ").append(jsonField(jsonObj, "user_request")).append('\n')
            builder.append("user_request_answer: ").append(jsonField(jsonObj, "user_request_answer")).append('\n')
            builder.append("evidence: ").append(jsonField(jsonObj, "evidence")).append('\n')
            builder.append("uncertainty: ").append(jsonField(jsonObj, "uncertainty")).append('\n')

            val primitives = jsonObj["visual_primitives"]
            if (primitives is kotlinx.serialization.json.JsonArray) {
                builder.append("<visual-primitives coord=\"norm-1000\" box_order=\"xyxy\" grounding=\"native\">\n")
                primitives.take(MAX_VISUAL_PRIMITIVES).forEachIndexed { idx, prim ->
                    val normalized = normalizePrimitive(prim, caps)
                    if (normalized != null) {
                        builder.append(
                            "- v${idx + 1} | type: ${normalized.type} | ${if (normalized.type == "point") "point" else "box"}: ${normalized.point ?: normalized.box} | ref: ${normalized.ref} | confidence: ${normalized.confidence}\n",
                        )
                    }
                }
                builder.append("</visual-primitives>")
            }

            truncateNote(builder.toString())
        } catch (e: Exception) {
            Logger.w(TAG, "Error al analizar JSON de conexión a tierra,caminar alrededor: ${e.message}")
            formatInvalidStructuredNote(rawResponse)
        }
    }

    /** v1.0.4: Extraiga texto JSON de las respuestas que puedan contener bloques de código de rebajas. */
    private fun extractJson(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) return trimmed
        val codeBlockRegex = Regex("```(?:json)?\\s*\\n?(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
        return codeBlockRegex.find(trimmed)?.groupValues?.getOrNull(1)
    }

    /** v1.0.4: Extraiga de forma segura campos de cadena de JsonObject(Convertir matriz a separada por comas). */
    private fun jsonField(obj: kotlinx.serialization.json.JsonObject, key: String): String {
        val element = obj[key] ?: return "Ninguno"
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> element.content
            is kotlinx.serialization.json.JsonArray -> element.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            }.joinToString(", ")
            else -> element.toString()
        }
    }

    /** v1.0.4: normalización primitive(Los diferentes formatos de modelos se unifican en xyxy + norm-1000). */
    private data class NormalizedPrimitive(
        val type: String,
        val box: String,
        /** v1.0.7: point Coordenadas de 2 tuplas específicas del tipo "[x, y]". */
        val point: String? = null,
        val ref: String,
        val confidence: String,
    )

    /** v1.0.4: sencillo normalizado primitive,Devolver nulo indica que no se puede analizar. */
    private fun normalizePrimitive(prim: kotlinx.serialization.json.JsonElement, caps: VisionCapabilities?): NormalizedPrimitive? {
        val obj = prim as? kotlinx.serialization.json.JsonObject ?: return null
        val format = resolveOutputFormat(caps?.outputFormat)

        val ref = (obj["ref"] ?: obj["label"] ?: obj["text"])
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.take(MAX_PRIMITIVE_REF_CHARS) ?: "unknown"

        val confidence = (obj["confidence"])
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "0.5"

        // v1.0.7: Unifica todas las sucursales para regresar NormalizedPrimitive(El gemini/else original se deconstruye usando Pair,
        //   La compilación falla debido a una inconsistencia con el tipo NormalizedPrimitive de qwen/anchor;
        //   Y al final NormalizedPrimitive(type, box, ref, confidence) Parámetros posicionales fuera de lugar
        //   —— ref se llenará en el espacio de puntos,confidence se llenará en el espacio de árbitro)
        return when (format) {
            "gemini" -> {
                val box2d = obj["box_2d"] as? JsonArray
                if (box2d == null || box2d.size < 4) return null
                val ymin = box2d[0].jsonPrimitive.intOrNull ?: return null
                val xmin = box2d[1].jsonPrimitive.intOrNull ?: return null
                val ymax = box2d[2].jsonPrimitive.intOrNull ?: return null
                val xmax = box2d[3].jsonPrimitive.intOrNull ?: return null
                NormalizedPrimitive("box", "[$xmin, $ymin, $xmax, $ymax]", null, ref, confidence)
            }
            "qwen" -> {
                val bbox = obj["bbox_2d"] as? JsonArray
                if (bbox != null && bbox.size >= 4) {
                    NormalizedPrimitive("box", "[${bbox[0]}, ${bbox[1]}, ${bbox[2]}, ${bbox[3]}]", null, ref, confidence)
                } else {
                    val point = obj["point_2d"] as? JsonArray
                    if (point != null && point.size >= 2) {
                        val x = point[0].jsonPrimitive.intOrNull ?: return null
                        val y = point[1].jsonPrimitive.intOrNull ?: return null
                        // v1.0.7: point Salida 2-tupla "[x, y]",
                        // La versión original copió por error puntos en 4 tuplas. "[x, y, x, y]" cuando box,El modelo de texto no puede analizar las coordenadas de los puntos correctamente
                        NormalizedPrimitive("point", "[$x, $y]", "[$x, $y]", ref, confidence)
                    } else {
                        null
                    }
                }
            }
            "anchor" -> {
                val box = obj["box"] as? JsonArray
                val role = (obj["role"]?.let { (it as? JsonPrimitive)?.content }) ?: "object"
                if (box != null && box.size >= 4) {
                    NormalizedPrimitive(role, "[${box[0]}, ${box[1]}, ${box[2]}, ${box[3]}]", null, ref, confidence)
                } else {
                    val center = obj["center"] as? JsonArray
                    if (center != null && center.size >= 2) {
                        val x = center[0].jsonPrimitive.intOrNull ?: return null
                        val y = center[1].jsonPrimitive.intOrNull ?: return null
                        // v1.0.7: point Salida 2-tupla(Igual que la corrección de la rama qwen)
                        NormalizedPrimitive(role, "[$x, $y]", "[$x, $y]", ref, confidence)
                    } else {
                        null
                    }
                }
            }
            else -> {
                val box = obj["box"] as? kotlinx.serialization.json.JsonArray
                if (box == null || box.size < 4) return null
                NormalizedPrimitive("box", "[${box[0]}, ${box[1]}, ${box[2]}, ${box[3]}]", null, ref, confidence)
            }
        }
    }

    /**
     * v1.0.5: JSON Qué hacer cuando falla el análisis note(Conservar la información de respuesta original).
     *
     * mejorar:Trate la respuesta original como `image_overview`(Deje que el modelo de texto lea la información principal.),
     * en lugar del original `evidence`(7 Complete todos los campos"Ninguno").
     */
    /** Formato de salida de análisis, compatible con valores antiguos y mapa a "muse-box". */
    private fun resolveOutputFormat(raw: String?): String = when (raw) {
        "hanako" -> "muse-box"
        else -> raw ?: "muse-box"
    }

    private fun formatInvalidStructuredNote(rawResponse: String): String {
        val truncated = if (rawResponse.length > 1200) rawResponse.take(1200) + "[truncated]" else rawResponse
        return buildString {
            append("image_overview: ").append(truncated).append('\n')
            append("visible_text: El modelo visual no vuelve estructurado. JSON,Lo anterior es la respuesta original.\n")
            append("objects_and_layout: Ninguno\n")
            append("charts_or_data: Ninguno\n")
            append("user_request: Ninguno\n")
            append("user_request_answer: Ninguno\n")
            append("evidence: Ninguno\n")
            append("uncertainty: El modelo visual no vuelve estructurado. JSON")
        }
    }

    /** v1.0.4: truncar nota a [MAX_NOTE_CHARS]. */
    private fun truncateNote(note: String): String {
        return if (note.length > MAX_NOTE_CHARS) note.take(MAX_NOTE_CHARS) + "\n[truncated]" else note
    }

    /**
     * v1.0.2 (P3): Llame al modelo visual,Prioridad completeText,Bajar de categoría streamChat cuando no sea compatible.
     */
    private suspend fun callVisionModel(userMessage: UIMessage, visionModel: Model, visionProvider: ProviderConfig): String =
        callVisionModel(listOf(userMessage), visionModel, visionProvider)

    private suspend fun callVisionModel(messages: List<UIMessage>, visionModel: Model, visionProvider: ProviderConfig): String {
        return try {
            val completion = chatService.completeText(
                messages = messages,
                model = visionModel,
                providerConfig = visionProvider,
                tools = null,
            )
            // v1.0.74 fix: despegar <think> etiqueta de inferencia,Evite que el contenido de pensamiento se mezcle con los resultados de visualización de imágenes/OCR
            io.zer0.muse.transformer.stripThinkTags(
                effectiveVisionResponseText(completion.text, completion.reasoningContent),
            )
        } catch (e: UnsupportedOperationException) {
            Logger.w(TAG, "completeText No compatible,Rebajar streamChat: ${e.message}")
            collectStreamText(messages, visionModel, visionProvider)
        }
    }

    /**
     * v1.0.2 (P3): Recopile el texto de respuesta completo a través de streamChat(Ruta de degradación).
     */
    private suspend fun collectStreamText(userMessage: UIMessage, visionModel: Model, visionProvider: ProviderConfig): String =
        collectStreamText(listOf(userMessage), visionModel, visionProvider)

    private suspend fun collectStreamText(messages: List<UIMessage>, visionModel: Model, visionProvider: ProviderConfig): String {
        val builder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        chatService.streamChat(
            messages = messages,
            model = visionModel,
            providerConfig = visionProvider,
            tools = null,
        ).collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> builder.append(event.delta)
                is ChatStreamEvent.ReasoningDelta -> reasoningBuilder.append(event.delta)
                is ChatStreamEvent.Error -> throw ProviderException(
                    providerError = event.providerError ?: ProviderError.Unknown(displayMessage = event.message),
                    cause = event.throwable,
                )
                is ChatStreamEvent.Done -> return@collect
                else -> Unit
            }
        }
        return io.zer0.muse.transformer.stripThinkTags(
            effectiveVisionResponseText(builder.toString(), reasoningBuilder.toString()),
        )
    }

    /**
     * v1.0.1 (P2): Normalice las excepciones subyacentes en mensajes de error de análisis visual fáciles de usar.
     *
     * v1.0.27 Phase 5-B: Priorizar la ruta de tipo `(e as? ProviderException)?.providerError`,
     * Úselo solo como encubrimiento. [inferFromMessage] Inferencia de cuerdas.
     */
    private fun friendlyVisionError(e: Throwable): String {
        val message = e.message.orEmpty()
        val providerError = (e as? ProviderException)?.providerError
            ?: inferFromMessage(message, e)
        return when (providerError) {
            is ProviderError.AuthError -> "La clave API del modelo visual no es válida o no tiene permisos suficientes.(401/403),Por favor verifique la configuración del proveedor"
            // v2.6.8: Recordatorio separado por saldo insuficiente —— El viejo camino caerá hacia“Error de parámetro”, lleva al usuario a ajustar los parámetros
            is ProviderError.InsufficientQuota -> "Saldo insuficiente en la cuenta del modelo visual(402), vaya al backend del proveedor para recargar y vuelva a intentarlo."
            is ProviderError.RateLimit -> "Límite actual del modelo visual(429),Inténtelo de nuevo más tarde o cambie los modelos visuales."
            is ProviderError.ServerError -> "El servidor de modelos visuales no está disponible temporalmente(${providerError.httpCode}),Por favor inténtalo de nuevo más tarde."
            is ProviderError.Network -> "Error en la conexión de red del modelo de visión,Por favor verifique la red o la dirección del proveedor"
            is ProviderError.InvalidRequest -> {
                if (providerError.httpCode == 413 || message.contains("413", ignoreCase = true) ||
                    message.contains("too large", ignoreCase = true) ||
                    message.contains("payload too large", ignoreCase = true)
                ) {
                    "Imagen demasiado grande(413),Comprima e intente nuevamente o cambie el modelo visual que admite imágenes grandes."
                } else {
                    "Error de parámetro de solicitud de modelo visual: ${providerError.displayMessage}"
                }
            }
            is ProviderError.Cancelled -> "Análisis visual cancelado"
            is ProviderError.Unknown -> "El análisis visual falló: ${providerError.displayMessage}"
            null -> "El análisis visual falló: $message"
        }
    }

    /**
     * v1.0.5: Análisis por lotes de múltiples imágenes preprocesadas,Devuelve una lista de todas las descripciones exitosas.
     *
     * mejorar:
     *  - recibir [VisionImagePreprocessor.PreparedImage] en lugar de desnudo base64
     *  - Clave de caché en su lugar [VisionCache.CacheKey] clase de datos,La semántica del campo es clara.
     *  - Clave de caché agregada userRequest hash(No compartas la misma imagen sino diferentes preguntas.)
     *
     * Reservar:
     *  - Ejecución concurrente(coroutineScope { async }.awaitAll)
     *  - Progreso StateFlow
     *  - La clave de caché contiene el número de versión del mensaje
     *
     * @param preparedImages Lista de imágenes preprocesadas
     * @param userRequest Solicitud específica del usuario,Páselo al modelo visual para un análisis específico.
     * @param sessionId sesión actual id;Si es nulo, no utilice el almacenamiento en caché
     * @param messageId v1.0.16: Mensajes en análisis id,La interfaz de usuario del controlador solo muestra el progreso en este mensaje
     * @param onProgress v1.0.20: Devolución de llamada de progreso (current, total),con [progressFlow] Disparador paralelo
     * @return Lista de descripción visual(Contiene `<vision-context>` etiqueta)
     * @throws VisionAnalysisException Lanzado cuando falla todo el análisis de imágenes.
     */
    private suspend fun analyzeImages(
        preparedImages: List<VisionImagePreprocessor.PreparedImage>,
        userRequest: String = "",
        sessionId: String? = null,
        /** v1.0.16: Mensajes en análisis id,La interfaz de usuario del controlador solo muestra el progreso en este mensaje */
        messageId: String? = null,
        /** v1.0.20: Devolución de llamada de progreso,Se activa cada vez que se completa una imagen. (current=Completado, total=Número total de imágenes) */
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<String> {
        if (preparedImages.isEmpty()) return emptyList()

        val visionModelId = resultOf { settings.visionModelIdFlow.first() }.getOrNull() ?: ""
        val userRequestHash = VisionImagePreprocessor.hashShort(userRequest)

        val lastError = java.util.concurrent.atomic.AtomicReference<VisionAnalysisException?>(null)
        val successCount = java.util.concurrent.atomic.AtomicInteger(0)

        _progressFlow.value = VisionProgress(idle = false, index = 0, total = preparedImages.size, messageId = messageId)

        /** v1.0.20: Progreso de actualización unificado — Actualizar las devoluciones de llamadas de ProgressFlow y onProgress simultáneamente */
        fun reportProgress(done: Int) {
            _progressFlow.value = VisionProgress(idle = false, index = done, total = preparedImages.size, messageId = messageId)
            onProgress?.invoke(done, preparedImages.size)
        }

        try {
            val results = coroutineScope {
                preparedImages.mapIndexed { index, prepared ->
                    async {
                        try {
                            // v1.0.5: Las claves de caché utilizan la clase de datos CacheKey,La semántica del campo es clara.
                            val cacheKey = VisionCache.CacheKey(
                                // get() Cálculo interno
                                imageHash = "",
                                modelId = visionModelId,
                                userRequestHash = userRequestHash,
                                promptVersion = VISION_PROMPT_VERSION,
                            )

                            val cached = if (sessionId != null && visionModelId.isNotBlank()) {
                                visionCache.get(sessionId, prepared.base64, cacheKey)
                            } else {
                                null
                            }

                            if (cached != null) {
                                Logger.d(TAG, "No. ${index + 1}/${preparedImages.size} La imagen llega al caché visual")
                                val done = successCount.incrementAndGet()
                                reportProgress(done)
                                "<vision-context>\n$cached\n</vision-context>"
                            } else {
                                val description = analyzeImage(prepared, userRequest = userRequest)

                                if (sessionId != null && visionModelId.isNotBlank()) {
                                    val rawDescription = description
                                        .removePrefix("<vision-context>\n")
                                        .removeSuffix("\n</vision-context>")
                                    visionCache.put(sessionId, prepared.base64, cacheKey, rawDescription)
                                }

                                val done = successCount.incrementAndGet()
                                reportProgress(done)
                                description
                            }
                        } catch (e: VisionAnalysisException) {
                            lastError.set(e)
                            Logger.w(TAG, "No. ${index + 1}/${preparedImages.size} El análisis de la imagen falló: ${e.message}")
                            null
                        }
                    }
                }.map { it.await() }
            }

            val descriptions = results.filterNotNull()
            val error = lastError.get()
            if (descriptions.isEmpty() && error != null) {
                throw error
            }
            return descriptions
        } finally {
            // Asegúrese de que ProgressFlow también se restablezca cuando se cancele la rutina,Evite que la interfaz de usuario se quede atascada"analizando"
            _progressFlow.value = VisionProgress(idle = true, index = preparedImages.size, total = preparedImages.size, messageId = null)
        }
    }

    /**
     * Crear prefijo de contexto visual,Inyectado al comienzo de los mensajes de los usuarios.
     */
    fun buildVisionContext(descriptions: List<String>): String {
        if (descriptions.isEmpty()) return ""
        return descriptions.joinToString("\n\n") + "\n\n"
    }

    /**
     * Construir texto de falla de ayuda visual.
     *
     * Inyectar información de error en los mensajes de los usuarios.,
     * Hazlo explícito en el modelo de solo texto."No hay contenido de imagen en esta ronda.",y le indica que solicite al usuario que vuelva a intentar o verificar la configuración.
     */
    fun buildFailureNotice(reason: String?): String {
        val detail = if (reason.isNullOrBlank()) "" else "($reason)"
        return """
            <vision-context>
            [El análisis de la imagen falló:Los modelos de visión auxiliar no están disponibles temporalmente,Esta ronda no pasará el contenido de la imagen al modelo de texto. Por favor, deja claro que no viste la imagen.,y pedirle al usuario que vuelva a intentarlo más tarde o verifique la configuración del modelo visual$detail.]
            </vision-context>
        """.trimIndent()
    }
}

/**
 * v1.0.2 (P1): Clase de datos de progreso de análisis visual.
 *
 * @param idle ¿Es gratis?(true=No analizando,false=analizando)
 * @param index Número de imágenes completadas(0..total)
 * @param total Número total de imágenes
 */
data class VisionProgress(
    val idle: Boolean,
    val index: Int,
    val total: Int,
    /** v1.0.16: Mensajes en análisis id,Se utiliza en la interfaz de usuario para mostrar el progreso solo en este mensaje.,Evitar que todos los mensajes de USUARIO se muestren al mismo tiempo"analizando" */
    val messageId: String? = null,
) {
    val isActive: Boolean get() = !idle && total > 0
    val ratio: Float get() = if (total > 0) index.toFloat() / total else 0f
}

/** Keep durable model instructions in a system role, separate from screen/user-controlled content. */
internal fun buildVisionMessages(prompt: String, imageBase64: String, systemPrompt: String?): List<UIMessage> = buildList {
    systemPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let { instruction ->
        add(UIMessage(role = MessageRole.SYSTEM, content = instruction))
    }
    add(
        UIMessage(
            role = MessageRole.USER,
            content = prompt,
            imageBase64List = listOf(imageBase64),
        ),
    )
}
