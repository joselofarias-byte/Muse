package io.zer0.muse.ui.chat

import android.content.Context
import android.net.Uri
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.UIMessage
import io.zer0.ai.image.ImageService
import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.doc.OcrManager
import io.zer0.muse.ui.ChatError
import io.zer0.muse.ui.ChatErrorType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * v1.135: Tipo de devolución de llamada de manejo de archivos adjuntos de video.
 */
private typealias VideoHandler = io.zer0.muse.ui.chat.VideoAttachmentHandler

/**
 * Elija un modelo que realmente pueda producir imágenes.
 *
 * Prefiere el modelo especificado por la persona que llama;No admite la salida de imágenes cuando,Devuelva el primer modelo de este proveedor que admita la salida de imágenes.
 * Devolver nulo indica que este proveedor no tiene un modelo graficable. —— La persona que llama debería informar un error.,En lugar de poner un modelo de texto.
 * Vaya a la interfaz de la imagen para obtener un error ascendente incomprensible.
 *
 * @param provider Proveedores utilizados en este dibujo.(puede ser null,No hay forma de decir la verdad en este momento.)
 * @param requested Modelo preferido de la persona que llama(El modelo de dibujo en la configuración o el modelo seleccionado actualmente,puede ser null)
 */
internal fun resolveImageCapableModel(provider: ProviderConfig?, requested: Model?): Model? =
    requested?.takeIf { it.supportsImageOutput() }
        ?: provider?.models?.firstOrNull { it.supportsImageOutput() }

/**
 * v1.105 Etapa 1: Coordinador relacionado con imágenes extraído de ChatViewModel.
 *
 * Responsabilidades:
 *  - pickImage: Selección de álbum de fotos.(La entrada visual base64/OCR reconoce texto)
 *  - removePendingImage: Eliminar imágenes pendientes
 *  - readImageAsBase64: métodos de herramientas internas
 *  - generateImage: Entrada de dibujo(Sucursal por proveedor)
 *  - generateImageViaOpenAi / generateImageViaGemini: Implementación de dibujo de cada proveedor
 *
 * No retenido state,Pase [accessor] Leer y escribir ChatViewModel state;
 * mantener [imageJob] Controlar una única sesión de dibujo(Se puede cancelar).
 *
 * La firma del método público es exactamente la misma que la del ChatViewModel original.,UI Cambios de capa cero.
 */
class ImageGenCoordinator(
    private val accessor: ChatStateAccessor,
    private val imageService: ImageService,
    private val settings: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val ocrManager: OcrManager,
    private val chatService: ChatService,
    private val appContext: Context,
) {

    private val tag = "ChatVM"

    /** Tarea de generación de imágenes actual(Puede ser cancelado por nuevas tareas.). */
    private var imageJob: Job? = null

    /** Las imágenes del álbum seleccionadas varias veces se leerán simultáneamente,Utilice bloqueos para garantizar que las imágenes pendientes adjuntas no se sobrescriban entre sí. */
    private val pendingImageMutex = Mutex()

    /**
     * F-09: Asignaciones de medios actuales id(Cada generación se asigna de forma independiente. UUID).
     * Una vez cancelada la tarea anterior, finalmente no debe sobrescribir el estado de la nueva tarea.(isGeneratingImage guardia de carrera).
     */
    private var currentMediaTaskId: String? = null

    companion object {
        /** Ampliar imagen lado más largo(El modelo visual no necesita ser muy claro,Controlar el volumen base64). */
        private const val IMAGE_SCALE_TARGET = 1024

        /** Calidad de compresión de imagen JPEG(0-100). */
        private const val IMAGE_JPEG_QUALITY = 85

        /**
         * El límite superior de imágenes que se enviarán a la vez.
         *
         * El valor anterior está codificado 4 (escrito en la sentencia). Sólo puede seleccionar cuatro imágenes a la vez. Si quieres publicar más, debes abrir el álbum repetidamente.
         * Razones para mencionar 10:
         *  - Memoria: imagen única comprimida en el borde largo 1024 px/JPEG 85, aproximadamente 200-400 KB (base64 después),
         *    10 El tamaño es de aproximadamente 2 a 4 MB, lo que está dentro del rango aceptable;
         *  - Upstream: la mayoría de los modelos visuales admiten entre 10 y 20 imágenes a la vez. Tomar 10 puede cubrir los modelos convencionales.
         *    Cualquier valor superior alcanzará el límite de solicitud única de algunos modelos y el usuario verá un error al enviar en lugar de“Demasiadas opciones”.
         *
         * El límite superior de selecciones múltiples en el selector de álbumes es consistente con este valor para evitar“Se puede seleccionar pero no se puede enviar.”.
         */
        const val MAX_PENDING_IMAGES = 10
    }

    /**
     * Phase 8.6: Seleccionar imagen(álbum de fotos / OCR).
     *
     * @param asOcr true=OCR Reconocer texto y agregarlo al cuadro de entrada;false=modo de entrada visual,Leer unión base64 pendingImages
     */
    fun pickImage(uri: Uri, context: Context, asOcr: Boolean, reportError: (String) -> Unit, addError: (ChatErrorType, String) -> Unit) {
        if (accessor.snapshot.isStreaming) return
        accessor.coroutineScope.launch(AppDispatchers.io) {
            try {
                if (asOcr) {
                    // v1.0.4 (P0): OCR Configuración del período de reconocimiento isOcrProcessing=true,UI mostrar"Reconocer texto de imagen…"
                    accessor.update { it.copy(isOcrProcessing = true) }
                    try {
                        val text = ocrManager.recognize(uri, context)
                        if (text.isBlank()) {
                            reportError(context.getString(R.string.err_image_gen_ocr_failed))
                            return@launch
                        }
                        // Lee atómicamente la última entrada para evitar que las instantáneas antiguas sobrescriban la entrada del usuario antes de que se complete el OCR.
                        accessor.appendInputAtomically(text)
                    } finally {
                        accessor.update { it.copy(isOcrProcessing = false) }
                    }
                } else {
                    val base64 = readImageAsBase64(uri, context, addError)
                    if (base64.isBlank()) {
                        reportError(context.getString(R.string.err_image_gen_read_failed))
                        return@launch
                    }
                    pendingImageMutex.withLock {
                        if (accessor.snapshot.pendingImages.size >= MAX_PENDING_IMAGES) {
                            reportError(context.getString(R.string.err_image_gen_max_images))
                        } else {
                            accessor.update { state ->
                                state.copy(pendingImages = state.pendingImages + base64)
                            }
                        }
                    }
                }
            } catch (t: Exception) {
                Logger.e(tag, "pickImage failed", t)
                reportError(context.getString(R.string.err_image_gen_process_failed, t.message ?: ""))
            }
        }
    }

    /**
     * v1.135: Seleccione video y extraiga fotogramas clave,Únase a imágenes pendientes como varias imágenes.
     *
     * Implementación actual:Para aquellos que no admiten la transmisión directa de vídeo. Provider,Degradar el vídeo a 3 imágenes de fotogramas clave para enviar,
     *          Deja que el modelo visual"ver"Contenido de vídeo. Puede ampliarse a la transmisión de vídeo directa en el futuro.
     */
    fun pickVideo(uri: Uri, context: Context, reportError: (String) -> Unit) {
        if (accessor.snapshot.isStreaming) return
        accessor.coroutineScope.launch(AppDispatchers.io) {
            try {
                // 1. Extraer fotogramas clave(primer fotograma/medio/último fotograma)
                val keyFrames = VideoHandler().extractKeyFrames(uri, context, frameCount = 3)
                if (keyFrames.isEmpty()) {
                    reportError(context.getString(R.string.err_image_gen_video_no_frames))
                    return@launch
                }
                // 2. Verifique el límite superior de imágenes a enviar(No más de 4 imágenes, incluidos fotogramas clave.)
                val current = accessor.snapshot.pendingImages
                if (current.size + keyFrames.size > 4) {
                    reportError(context.getString(R.string.err_image_gen_video_too_many, keyFrames.size, current.size))
                    return@launch
                }
                accessor.update { it.copy(pendingImages = current + keyFrames) }
                Logger.i(tag, "pickVideo: Extraído ${keyFrames.size} Agregar fotogramas clave pendingImages")
            } catch (t: Exception) {
                Logger.e(tag, "pickVideo failed", t)
                reportError(context.getString(R.string.err_image_gen_video_failed, t.message ?: ""))
            }
        }
    }

    /** Phase 8.6: Elimina la imagen que se enviará en el índice especificado. */
    fun removePendingImage(index: Int) {
        val current = accessor.snapshot.pendingImages
        if (index < 0 || index >= current.size) return
        accessor.update { it.copy(pendingImages = current.toMutableList().also { list -> list.removeAt(index) }) }
    }

    /** Phase 8.6: Lea el URI de la imagen como base64(Ninguno dato: prefijo). */
    private suspend fun readImageAsBase64(uri: Uri, context: Context, addError: (ChatErrorType, String) -> Unit): String {
        return withContext(AppDispatchers.io) {
            var errThrowable: Throwable? = null
            resultOf {
                val resolver = context.contentResolver
                val target = IMAGE_SCALE_TARGET

                val bitmap: android.graphics.Bitmap? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val source = android.graphics.ImageDecoder.createSource(resolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(
                        source,
                        object : android.graphics.ImageDecoder.OnHeaderDecodedListener {
                            override fun onHeaderDecoded(
                                decoder: android.graphics.ImageDecoder,
                                info: android.graphics.ImageDecoder.ImageInfo,
                                src: android.graphics.ImageDecoder.Source,
                            ) {
                                val w = info.size.width
                                val h = info.size.height
                                var sample = 1
                                while (w / sample > target || h / sample > target) sample *= 2
                                decoder.setTargetSampleSize(sample)
                            }
                        },
                    )
                } else {
                    @Suppress("DEPRECATION")
                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, opts)
                    }
                    var sample = 1
                    while (opts.outWidth / sample > target || opts.outHeight / sample > target) sample *= 2
                    val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    resolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, decodeOpts)
                    }
                }

                if (bitmap == null) {
                    Logger.e(tag, "readImageAsBase64: bitmap is null, uri=$uri")
                    return@resultOf ""
                }

                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, baos)
                bitmap.recycle()
                val result = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                Logger.i(tag, "readImageAsBase64: success, base64 length=${result.length}")
                result
            }.onError { _, t -> errThrowable = t }
                .getOrNull() ?: run {
                val e = errThrowable
                Logger.e(tag, "readImageAsBase64 failed", e)
                addError(
                    ChatErrorType.UNKNOWN,
                    context.getString(
                        R.string.err_image_gen_read_failed_msg,
                        e?.message ?: context.getString(R.string.err_chat_unknown),
                    ),
                )
                ""
            }
        }
    }

    /**
     * P5-G + Phase 10.2: Genera imágenes.
     *
     * Sucursal por tipo de proveedor:
     *  - OpenAI compatibles: ir ImageService(Punto final independiente /images/generaciones,Regresar URL)
     *  - Gemini:      Tome el camino multimodal de StreamChat(responseModalities Contiene image,recoger ImageDelta base64)
     *  - Anthropic:   No compatible
     *
     * [updateAssistant] Proporcionado por ChatViewModel(Porque este método llama internamente a extractThinkContent y otra lógica relacionada con la transmisión.,
     * Fuerte acoplamiento con el coordinador de streaming,Dejar la transición en ViewModel).
     */
    fun generateImage(
        prompt: String,
        sessionId: String,
        addError: (ChatErrorType, String) -> Unit,
        updateAssistant: (
            id: Uuid,
            content: String,
            reasoning: String?,
            imageBase64List: List<String>?,
            imageUrls: List<String>?,
            isStreaming: Boolean,
        ) -> Unit,
    ) {
        val userMsg = UIMessage(role = MessageRole.USER, content = "[Dibujo] $prompt")
        val assistantMsg = UIMessage(role = MessageRole.ASSISTANT, content = "")
        accessor.updateMessages { it + userMsg + assistantMsg }
        accessor.update {
            it.copy(
                input = "",
                isGeneratingImage = true,
                errors = emptyList(),
            )
        }
        accessor.coroutineScope.launch {
            sessionRepository.appendMessage(sessionId, userMsg)
        }
        imageJob?.cancel()
        // F-09: Ciclo de vida de generación de gestión de identificación de tareas de medios independientes — El mensaje solo hace referencia al resultado.,
        // actualización de estado(isGeneratingImage)Basado en la tarea actual, Las tareas antiguas finalmente no deben sobrescribir las nuevas
        val mediaTaskId = kotlin.uuid.Uuid.random().toString()
        currentMediaTaskId = mediaTaskId
        Logger.i(tag, "Comienza la tarea de dibujo mediaTask=$mediaTaskId | prompt=${prompt.take(60)}")
        imageJob = accessor.coroutineScope.launch(AppDispatchers.io) {
            try {
                val imageGenConfig = settings.imageGenConfigFlow.first()
                val providers = settings.providersFlow.first()
                // v1.0.18: Relájese dibujando cheques de proveedores — OpenAI / OpenAI Responses / Agnes / Cualquiera compatible con OpenAI
                // Ambos se pueden enrutar a través de ImageProviderRegistry;Gemini Tome el camino multimodal de streamChat.
                val hasImageProvider = providers.any {
                    it.type == ProviderType.OPENAI ||
                        it.type == ProviderType.OPENAI_RESPONSES ||
                        it.type == ProviderType.GEMINI ||
                        it.baseUrl.contains("agnes", ignoreCase = true)
                }
                if (!hasImageProvider) {
                    if (currentMediaTaskId == mediaTaskId) {
                        accessor.update {
                            it.copy(
                                errors =
                                listOf(
                                    ChatError(
                                        type = ChatErrorType.UNKNOWN,
                                        message =
                                        appContext.getString(
                                            R.string.err_image_gen_no_provider,
                                        ),
                                    ),
                                ),
                                isGeneratingImage = false,
                            )
                        }
                    }
                    return@launch
                }

                val explicitProvider = if (imageGenConfig.providerId.isNotBlank()) {
                    settings.getProviderById(imageGenConfig.providerId)
                } else {
                    null
                }

                val config = explicitProvider ?: settings.get()
                val requestedModel = if (explicitProvider != null) {
                    explicitProvider.models.firstOrNull { it.id == imageGenConfig.modelId }
                } else {
                    settings.getSelectedModel()
                }
                val model = resolveImageCapableModel(config, requestedModel)
                if (requestedModel != null && model != null && requestedModel.id != model.id) {
                    Logger.w(tag, "modelo de imagen ${requestedModel.id} La salida de imágenes no es compatible,Usar en su lugar ${model.id}")
                }

                if (config == null) {
                    if (currentMediaTaskId == mediaTaskId) {
                        accessor.update {
                            it.copy(
                                errors =
                                listOf(
                                    ChatError(
                                        type = ChatErrorType.UNKNOWN,
                                        message =
                                        appContext.getString(
                                            R.string.err_image_gen_no_provider,
                                        ),
                                    ),
                                ),
                                isGeneratingImage = false,
                            )
                        }
                    }
                    return@launch
                }
                if (model == null) {
                    if (currentMediaTaskId == mediaTaskId) {
                        accessor.update {
                            it.copy(
                                errors =
                                listOf(
                                    ChatError(
                                        type = ChatErrorType.UNKNOWN,
                                        message =
                                        appContext.getString(
                                            R.string.err_image_gen_model_no_output,
                                        ),
                                    ),
                                ),
                                isGeneratingImage = false,
                            )
                        }
                    }
                    return@launch
                }

                // v1.0.18: Gemini Ir a streamChat multimodal;el resto(OpenAI / Agnes / estación de transferencia)ir juntos
                // ImageService → ImageProviderRegistry,El registro selecciona automáticamente el adaptador según lo configurado.
                when (config.type) {
                    ProviderType.GEMINI -> {
                        if (!model.supportsImageOutput()) {
                            if (currentMediaTaskId == mediaTaskId) {
                                accessor.update {
                                    it.copy(
                                        errors =
                                        listOf(
                                            ChatError(
                                                type = ChatErrorType.UNKNOWN,
                                                message =
                                                appContext.getString(
                                                    R.string.err_image_gen_model_no_output,
                                                ),
                                            ),
                                        ),
                                        isGeneratingImage = false,
                                    )
                                }
                            }
                            return@launch
                        }
                        generateImageViaGemini(prompt, sessionId, assistantMsg.id, config, model, addError, updateAssistant)
                    }
                    else -> {
                        generateImageViaOpenAi(prompt, sessionId, assistantMsg.id, config, model, addError)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Exception) {
                Logger.e(tag, "image gen failed mediaTask=$mediaTaskId", t)
                if (currentMediaTaskId == mediaTaskId) {
                    accessor.update {
                        it.copy(
                            errors =
                            listOf(
                                ChatError(
                                    type = ChatErrorType.UNKNOWN,
                                    message =
                                    appContext.getString(
                                        R.string.err_image_gen_failed,
                                        t.message
                                            ?: appContext.getString(R.string.err_chat_unknown),
                                    ),
                                ),
                            ),
                            isGeneratingImage = false,
                        )
                    }
                }
            } finally {
                // 9.1 Reparación: Restablecer en cualquier camino isGeneratingImage,Evite que Gemini devuelva una lista de imágenes vacía, etc.
                // La carga se atasca cuando la ruta incorrecta regresa antes de tiempo.(éxito/falló/La cancelación desencadenará finally)
                // F-09: Sólo se puede restablecer la tarea actual — Finalmente, la tarea anterior no debe sobrescribir la tarea nueva. isGeneratingImage
                if (currentMediaTaskId == mediaTaskId) {
                    accessor.update { it.copy(isGeneratingImage = false) }
                }
            }
        }
    }

    /** Phase 10.2: OpenAI Compatible con dibujo(Ir al punto final independiente de ImageService). */
    private suspend fun generateImageViaOpenAi(
        prompt: String,
        sessionId: String,
        assistantId: Uuid,
        providerConfig: ProviderConfig,
        model: Model,
        addError: (ChatErrorType, String) -> Unit,
    ) {
        // v1.136: Pase el ID del modelo de dibujo seleccionado en la interfaz de usuario ImageService,Evite puntos finales personalizados causados por un modelo vacío 404
        val urls = imageService.generate(
            prompt,
            accessor.snapshot.imageGenParams.copy(model = model.id),
            providerConfig,
        )
        if (urls.isEmpty()) {
            accessor.update {
                it.copy(
                    errors = listOf(
                        ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_image_gen_no_result)),
                    ),
                    isGeneratingImage = false,
                )
            }
            return
        }
        val md = urls.joinToString("\n\n") { "![]($it)" }
        accessor.updateMessages { messages ->
            messages.map { msg ->
                if (msg.id == assistantId) {
                    msg.copy(content = md, imageUrls = urls)
                } else {
                    msg
                }
            }
        }
        accessor.update {
            it.copy(
                isGeneratingImage = false,
                imageGenParams = it.imageGenParams.copy(referenceImageUri = null),
            )
        }
        val finalAssistant = accessor.messagesSnapshot.firstOrNull { it.id == assistantId }
        if (finalAssistant != null) {
            try {
                sessionRepository.upsertMessage(sessionId, finalAssistant)
            } catch (e: Exception) {
                Logger.e(tag, "generateImageViaOpenAi upsertMessage failed", e)
                addError(
                    ChatErrorType.UNKNOWN,
                    appContext.getString(R.string.err_image_gen_save_failed, e.message ?: appContext.getString(R.string.err_chat_unknown)),
                )
            }
        }
    }

    /** Phase 10.2: Gemini Dibujo(Tome el camino multimodal de StreamChat,recoger ImageDelta base64). */
    private suspend fun generateImageViaGemini(
        prompt: String,
        sessionId: String,
        assistantId: Uuid,
        providerConfig: ProviderConfig,
        model: Model,
        addError: (ChatErrorType, String) -> Unit,
        updateAssistant: (
            id: Uuid,
            content: String,
            reasoning: String?,
            imageBase64List: List<String>?,
            imageUrls: List<String>?,
            isStreaming: Boolean,
        ) -> Unit,
    ) {
        val drawMessages = listOf(
            UIMessage(role = MessageRole.USER, content = "Por favor genera una imagen: $prompt"),
        )
        val flow = chatService.streamChat(
            messages = drawMessages,
            model = model,
            tools = null,
            temperature = null,
            maxTokens = null,
            providerConfig = providerConfig,
        )
        val textBuilder = StringBuilder()
        val imageBase64List = mutableListOf<String>()
        val imageMimeTypes = mutableListOf<String>()
        flow.collect { event ->
            when (event) {
                is ChatStreamEvent.ContentDelta -> {
                    textBuilder.append(event.delta)
                    updateAssistant(assistantId, textBuilder.toString(), null, null, null, true)
                }
                is ChatStreamEvent.ImageDelta -> {
                    imageBase64List.add(event.imageBase64)
                    imageMimeTypes.add(event.mimeType.ifBlank { "image/png" })
                    val mime = event.mimeType.ifBlank { "image/png" }
                    val dataUri = "data:$mime;base64,${event.imageBase64}"
                    val currentMsg = accessor.messagesSnapshot.firstOrNull { it.id == assistantId }
                    val currentUrls = currentMsg?.imageUrls ?: emptyList()
                    updateAssistant(
                        assistantId,
                        textBuilder.toString(),
                        null,
                        imageBase64List.toList(),
                        currentUrls + dataUri,
                        true,
                    )
                }
                is ChatStreamEvent.Error -> {
                    throw event.throwable ?: RuntimeException(event.message)
                }
                else -> {}
            }
        }
        if (imageBase64List.isEmpty()) {
            accessor.updateMessages { messages ->
                messages.map { msg ->
                    if (msg.id == assistantId) msg.copy(content = textBuilder.toString()) else msg
                }
            }
            accessor.update {
                it.copy(
                    errors = listOf(
                        ChatError(type = ChatErrorType.UNKNOWN, message = appContext.getString(R.string.err_image_gen_no_image)),
                    ),
                    isGeneratingImage = false,
                )
            }
            val finalAssistant = accessor.messagesSnapshot.firstOrNull { it.id == assistantId }
            if (finalAssistant != null) {
                sessionRepository.upsertMessage(sessionId, finalAssistant)
            }
            return
        }
        val mdImages = imageBase64List.mapIndexed { idx, b64 ->
            val mime = imageMimeTypes.getOrNull(idx) ?: "image/png"
            "![](data:$mime;base64,$b64)"
        }.joinToString("\n\n")
        val finalContent = when {
            textBuilder.isNotBlank() && mdImages.isNotBlank() -> "${textBuilder}\n\n$mdImages"
            mdImages.isNotBlank() -> mdImages
            else -> textBuilder.toString()
        }
        accessor.updateMessages { messages ->
            messages.map { msg ->
                if (msg.id == assistantId) msg.copy(content = finalContent) else msg
            }
        }
        accessor.update {
            it.copy(
                isGeneratingImage = false,
            )
        }
        val finalAssistant = accessor.messagesSnapshot.firstOrNull { it.id == assistantId }
        if (finalAssistant != null) {
            try {
                sessionRepository.upsertMessage(sessionId, finalAssistant)
            } catch (e: Exception) {
                Logger.e(tag, "generateImageViaGemini upsertMessage failed", e)
                addError(
                    ChatErrorType.UNKNOWN,
                    appContext.getString(R.string.err_image_gen_save_failed, e.message ?: appContext.getString(R.string.err_chat_unknown)),
                )
            }
        }
    }
}
