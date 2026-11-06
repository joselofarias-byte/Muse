package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.image.ImageGenParams
import io.zer0.ai.image.ImageService
import io.zer0.ai.video.VideoGenRequest
import io.zer0.ai.video.VideoGenerationService
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.qrcode.QrCodeGenerator
import io.zer0.muse.ui.speech.TtsSynthResult
import io.zer0.muse.ui.speech.VoiceMessageArchiver
import io.zer0.muse.ui.speech.VoiceSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/** P2-23: Tipo de generación de medios (los mensajes de voz siguen el mismo enlace de entrega). */
enum class MediaGenKind { IMAGE, VIDEO, QR_CODE, VOICE }

/**
 * P2-23: Un resultado generado por los medios(suministro [MediaGenSlot] Escribir mensaje de sesión).
 */
sealed interface MediaGenResult {
    /** Generado con éxito.[imageUrls] para originales URL / data URI;[videoFileUri] es la dirección del vídeo;[voiceUri] Cita para archivo de discursos. */
    data class Success(
        val imageUrls: List<String> = emptyList(),
        val videoFileUri: String? = null,
        /** Referencia de audio del mensaje de voz (`file://` + camino absoluto); nulo para mensajes que no son de voz. */
        val voiceUri: String? = null,
        /** Duración de la voz (milisegundos);0 = Desconocido (fallo de la sonda o mensaje sin voz). */
        val voiceDurationMs: Long = 0L,
    ) : MediaGenResult

    /** Error de compilación;[message] Solo razones,El host presiona la copia del prefijo. [MediaGenKind] Conjunto de plantillas de localización. */
    data class Failure(val message: String) : MediaGenResult

    /** Esta ronda de generación está cancelada.(Se canceló la rutina de herramientas.). */
    data object Cancelled : MediaGenResult
}

/**
 * P2-23: Un identificador de entrega de sesión generado por medios.
 *
 * por [MediaGenHost.begin] Anclar mensaje de sesión de destino antes de que comience la generación,Una vez completada la generación, el
 * [MediaGenToolsImpl] llamar [report] Escribe el resultado.
 */
interface MediaGenSlot {
    /**
     * Si esta ronda de generación sigue siendo válida.
     *
     * El token de generación cambia cuando el usuario envía un nuevo mensaje/cambia de sesión,Los resultados de compilación que lleguen tarde no se incluirán en los mensajes de error.
     */
    fun isActive(): Boolean

    /** Devolución de llamada de progreso de tarea larga(La generación de video ocurre cada 5 segundos.). */
    suspend fun onProgress(elapsedSeconds: Int)

    /** Escriba los resultados generados en el mensaje de sesión anclado. */
    suspend fun report(result: MediaGenResult)
}

/**
 * P2-23: Host de entrega de sesiones para resultados de generación de medios.
 *
 * El proceso de diálogo con la interfaz de usuario se implementa mediante la instalación de ChatViewModel.(Agregue el resultado al mensaje actual del asistente y suéltelo en la biblioteca.);
 * Los procesos sin interfaz de usuario, como tareas programadas/chats grupales/subagentes, no instalan hosts,[MediaGenToolsImpl] degenerar en
 * Coloque los resultados en el archivo de caché de la aplicación o devuélvalos directamente URL,Representado por el modelo.,No más depender de la interfaz de usuario para registrarse/Ejecutar.
 */
interface MediaGenHost {
    /**
     * Comienza la construcción:pantalla del anfitrión"generando"Se realiza la entrega con asa y se devuelve.
     *
     * Actualmente no hay mensajes de sesión para escribir(Sin proceso de interfaz de usuario / sin generación activa)Devuelve nulo.
     */
    suspend fun begin(kind: MediaGenKind): MediaGenSlot?

    /** Fin de generación(éxito/falló/Cancelar):El anfitrión borra el estado de ocupación. */
    suspend fun finish(kind: MediaGenKind)
}

/**
 * v2.x (B3): Anulación del host de entrega de medios dentro del alcance de la rutina.
 *
 * El enlace del chat grupal no tiene un host de interfaz de usuario;Utilizado antes de la ejecución de la herramienta. `withContext(MediaGenHostContext(host)) { ... }`
 * Inyecte este elemento,[MediaGenToolsImpl] tómalo primero,Devolver el gráfico generado como archivo adjunto al mensaje de chat grupal,
 * sin tocar la situación general [MediaGenToolsImpl.installHost] Host de interfaz de usuario instalado(charla privada/Seguridad de simultaneidad de chat grupal).
 */
class MediaGenHostContext(val host: MediaGenHost) :
    AbstractCoroutineContextElement(MediaGenHostContext) {
    companion object Key : CoroutineContext.Key<MediaGenHostContext>
}

/**
 * P2-23: Implementación de herramienta de generación de medios.(Imágenes / Vídeos / Códigos QR),Nada que ver con ViewModel.
 *
 * Desde ChatViewModel `execGenerateImage / execGenerateVideo / execGenerateQrCode` se mudó
 * Implementación de herramientas,por [MediaGenToolsRegistrar] Regístrese cuando se inicie la aplicación [ToolRegistry],Por lo tanto
 * También están disponibles enlaces sin interfaz de usuario, como tareas programadas, chats grupales y subagentes.
 *
 * Las dependencias sólo conservan lo que realmente se necesita para construir.:
 *  - [settings]:Dibujo/Configuración de persistencia para generación de video.(Parámetros de imagen, proveedores, modelos.)con lista de proveedores;
 *  - [imageService] / [videoGenerationService]:llamada de construcción real;
 *  - [context]:Directorio de redacción publicitaria y colocación de resultados localizado sin interfaz de usuario.
 *
 * Originario de `ChatViewModel._state` Leer el estado temporal dentro de la sesión(Parámetros de dibujo, imágenes de referencia.)Cambiar para leer la configuración de persistencia:
 *  - Tamaño/calidad/estilo/Cantidad/Formato de devolución → `SettingsRepository.imageGenConfigFlow`([ImageGenConfig]);
 *  - La imagen de referencia solo toma los parámetros de la herramienta. —— El álbum está seleccionado temporalmente para no persistir.,No se transmite a través de procesos.
 *
 * Los mensajes de sesión se escriben a través de [MediaGenHost] El puerto se implementa en el lado de la interfaz de usuario.,Esta clase no conoce el modelo de mensaje.
 */
class MediaGenToolsImpl(
    private val context: Context,
    private val settings: SettingsRepository,
    private val imageService: ImageService,
    private val videoGenerationService: VideoGenerationService,
    /** Puerto de síntesis de mensajes de voz ([io.zer0.muse.ui.speech.TtsManager] Implementación; una sola prueba inyecta una implementación falsa). */
    private val voiceSynthesizer: VoiceSynthesizer,
    /** Puerto de archivo de mensajes de voz ([io.zer0.muse.ui.speech.VoiceMessageStore] realizado). */
    private val voiceMessageStore: VoiceMessageArchiver,
) {

    /** UI anfitrión de entrega;Sin persistencia del proceso de UI null(El resultado es la ruta del archivo./URL Regresar). */
    @Volatile
    private var host: MediaGenHost? = null

    /** La cantidad de barras de voz en esta ronda (clave = Identificador de turno inyectado por el anfitrión). */
    private val voiceQuota = TurnVoiceQuota(limit = VOICE_TURN_LIMIT, trackedTurns = VOICE_TURN_WINDOW)

    /** Instalación/Desinstalar el host de entrega de sesiones(Llamado únicamente por el proceso de diálogo de la interfaz de usuario). */
    fun installHost(host: MediaGenHost?) {
        this.host = host
    }

    /**
     * v2.x (B3): Analizar el host de entrega actualmente efectivo — Cobertura del alcance de rutina([MediaGenHostContext])Prioridad,
     * Recurra al host de UI instalado globalmente sin anulación. Tres herramientas multimedia exec* Todos los métodos toman esta entrada.
     */
    private suspend fun currentHost(): MediaGenHost? = coroutineContext[MediaGenHostContext]?.host ?: host

    /**
     * Genera imágenes basadas en descripciones de usuarios.
     *
     * Prioridad de selección de modelo:args.model → persistente imageGenConfig.modelId →
     * provider Modelos cuyas modalidades de salida contienen imágenes en la lista de modelos.(Déjelo en blanco y deje que ImageService se encargue de ello.).
     */
    suspend fun execGenerateImage(args: Map<String, String>): String {
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: prompt"
        // P2-23: La implementación original retrocede cuando faltan parámetros ChatViewModel._state.imageGenParams(Anulación temporal durante la sesión),
        // Ahora cambie a "Configuración de retorno"→Valor predeterminado de persistencia para "Generación de imágenes",Los procesos en segundo plano también son legibles.
        val genConfig = settings.imageGenConfigFlow.first()
        val size = args["size"]?.takeIf { it.isNotBlank() } ?: genConfig.size
        val quality = args["quality"]?.takeIf { it.isNotBlank() } ?: genConfig.quality
        val style = args["style"]?.takeIf { it.isNotBlank() } ?: genConfig.style
        val n = args["n"]?.toIntOrNull()?.coerceAtLeast(1) ?: genConfig.n
        // v1.0.18: Imagen de referencia(Tu Sheng Tu),apoyo URL / base64 / data URI. Obtener solo parámetros de herramienta —
        // La imagen de referencia temporal seleccionada por el usuario en el álbum no persiste,No existe un proceso de interfaz de usuario ni forma de inyectar.
        val referenceImage = args["reference_image"]?.takeIf { it.isNotBlank() }
        val slot = currentHost()?.begin(MediaGenKind.IMAGE)

        return try {
            val providerConfig = genConfig.providerId.takeIf { it.isNotBlank() }
                ?.let { settings.getProviderById(it) }
                ?: settings.get()
                ?: return "Proveedor de generación de imágenes no configurado,Primero agregue soporte para dibujar. Provider(Como por ejemplo OpenAI / Agnes)"
            if (providerConfig.apiKey.isBlank() && !providerConfig.allowMissingApiKey) {
                return "La clave API del proveedor de generación de imágenes está vacía"
            }
            val model = args["model"]?.takeIf { it.isNotBlank() }
                ?: genConfig.modelId.takeIf { it.isNotBlank() }
                ?: providerConfig.models.firstOrNull { it.supportsImageOutput() }?.id
                ?: ""
            val params = ImageGenParams(
                model = model,
                size = size,
                quality = quality,
                style = style,
                responseFormat = genConfig.responseFormat,
                n = n,
                referenceImageUri = referenceImage,
            )
            val urls = imageService.generate(prompt, params, providerConfig)
            if (urls.isEmpty()) {
                slot.safeReport(MediaGenResult.Failure("No se obtuvieron resultados"))
                return "Error al generar la imagen: No se obtuvieron resultados"
            }
            // A-13: La generación de imágenes puede tardar decenas de segundos,Es posible que se hayan publicado nuevos mensajes durante este período. — Los medios no deben escribir ningún mensaje cuando expire el token generacional
            if (slot != null && !slot.isActive()) {
                Logger.w(TAG, "A-13: La generación de la imagen se completa pero la generación ha caducado.(nueva ronda de generación),Saltar la escritura en los medios")
                return "La imagen ha sido generada.,Pero la generación actual ha caducado.,Resultados no mostrados(Puedes volver a solicitar si es necesario)"
            }
            if (slot != null) {
                slot.safeReport(MediaGenResult.Success(imageUrls = urls))
                // v1.0.75 fix (Comentarios de los usuarios): La cadena devuelta al modelo ya no contiene URL —
                //   La imagen ha sido renderizada en la conversación.,La reformulación del modelo de la URL sólo causará"Enlaces incrustados en la página de chat"experiencia.
                "La imagen ha sido generada y mostrada en la conversación."
            } else {
                // P2-23: Sin sesión de interfaz de usuario(tareas programadas/chat grupal/subagente)——Los medios no entran en el diálogo.,Representar ubicaciones accesibles para el modelo.
                "La imagen ha sido generada.(Actualmente no hay ninguna interfaz de diálogo.,No mostrado),Posición del resultado:\n" +
                    externalizeMedia(urls, "img").joinToString("\n")
            }
        } catch (e: CancellationException) {
            slot.safeReport(MediaGenResult.Cancelled)
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: context.getString(R.string.err_chat_unknown)
            slot.safeReport(MediaGenResult.Failure(msg))
            "Error al generar la imagen: $msg"
        } finally {
            safeFinish(MediaGenKind.IMAGE)
        }
    }

    /**
     * Genere videos cortos basados ​​en descripciones de usuarios.
     *
     * Seleccione dinámicamente un proveedor configurado que admita salida de video/modelo:
     *  - Si args se especifica explícitamente provider_id,Priorizar el uso de este proveedor;
     *  - Si args se especifica explícitamente model,Preferir proveedores que incluyan este modelo;
     *  - De lo contrario, utilice primero los persistentes. VideoGenConfig.providerId;
     *  - De lo contrario, se selecciona automáticamente el primer modelo que admita salida de vídeo.
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    suspend fun execGenerateVideo(args: Map<String, String>): String {
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: prompt"
        val duration = args["duration"]?.toIntOrNull()?.let { if (it == 5 || it == 10) it else 5 } ?: 5
        val resolution = args["resolution"]?.takeIf { it.isNotBlank() } ?: "720p"
        val requestedModelId = args["model"]?.takeIf { it.isNotBlank() }
        val requestedProviderId = args["provider_id"]?.takeIf { it.isNotBlank() }
        val referenceImages = parseReferenceImages(args["reference_images"])

        val providers = settings.getAllProviders().filter { it.enabled && it.apiKey.isNotBlank() }
        // Leer usuario en"ajustes→generación de vídeo"Proveedor predeterminado configurado/modelo(LLM Tiene prioridad cuando no se especifica explícitamente)
        val videoGenConfig = settings.videoGenConfigFlow.first()
        val (providerConfig, videoModel) = when {
            requestedProviderId != null -> {
                val config = providers.firstOrNull { it.id == requestedProviderId }
                    ?: return "No se encontró ningún proveedor: $requestedProviderId"
                val model = requestedModelId?.let { id ->
                    config.models.firstOrNull { it.id == id && it.supportsVideoOutput() }
                } ?: config.models.firstOrNull { it.supportsVideoOutput() }
                    ?: return "proveedor ${config.displayName} Ningún modelo admite salida de vídeo"
                config to model
            }
            requestedModelId != null -> {
                val config = providers.firstOrNull { p ->
                    p.models.any { it.id == requestedModelId && it.supportsVideoOutput() }
                } ?: return "No se encontró ningún modelo de soporte $requestedModelId proveedores"
                val model = config.models.first { it.id == requestedModelId && it.supportsVideoOutput() }
                config to model
            }
            // Priorizar los proveedores de vídeo configurados por el usuario(VideoGenConfig.providerId)
            videoGenConfig.providerId.isNotBlank() -> {
                val config = providers.firstOrNull { it.id == videoGenConfig.providerId }
                    ?: return "Proveedor de generación de video no encontrado: ${videoGenConfig.providerId}"
                val model = videoGenConfig.modelId.takeIf { it.isNotBlank() }?.let { id ->
                    config.models.firstOrNull { it.id == id && it.supportsVideoOutput() }
                } ?: config.models.firstOrNull { it.supportsVideoOutput() }
                    ?: return "proveedor ${config.displayName} Ningún modelo admite salida de vídeo"
                config to model
            }
            else -> {
                val config = providers.firstOrNull { p -> p.models.any { it.supportsVideoOutput() } }
                    ?: return "No está configurado un proveedor que admita la generación de vídeo. Por favor vaya a \"Configuración\"→Habilite la capacidad de \"Salida de video\" para un modelo en \"Modelos y servicios\"."
                val model = config.models.first { it.supportsVideoOutput() }
                config to model
            }
        }

        val slot = currentHost()?.begin(MediaGenKind.VIDEO)
        val startedAt = System.currentTimeMillis()
        // v1.135: Actualizar mensaje de progreso cada 5 segundos,Deje que los usuarios sientan que aún hay tareas largas en progreso.
        val progressJob = slot?.let { target ->
            CoroutineScope(coroutineContext).launch {
                while (isActive) {
                    delay(PROGRESS_INTERVAL_MS)
                    target.onProgress(((System.currentTimeMillis() - startedAt) / 1000).toInt())
                }
            }
        }
        return try {
            val request = VideoGenRequest(
                prompt = prompt,
                model = videoModel.id,
                duration = duration,
                resolution = resolution,
                referenceImages = referenceImages,
            )
            // v1.137: Enrutamiento por specId/host a través de VideoProviderRegistry,
            // No más coincidencias difíciles por ID de proveedor(Reparación preset_kling ≠ kling enrutamiento bug)
            val videoUrl = videoGenerationService.generateVideo(providerConfig, request).getOrThrow()
            // A-13: La generación de vídeo lleva un tiempo considerable,Verifique que esta ronda de generación aún esté activa,Evite la contaminación entre sesiones
            if (slot != null && !slot.isActive()) {
                Logger.w(TAG, "A-13: La generación de video se completó pero la generación expiró(nueva ronda de generación),Saltar la escritura en los medios")
                return "El vídeo ha sido generado.,Pero la generación actual ha caducado.,Resultados no mostrados(Puedes volver a solicitar si es necesario)"
            }
            if (slot != null) {
                slot.safeReport(MediaGenResult.Success(videoFileUri = videoUrl))
                "Vídeo generado y mostrado en la conversación."
            } else {
                "El vídeo ha sido generado.(Actualmente no hay ninguna interfaz de diálogo.,No mostrado),Posición del resultado:\n$videoUrl"
            }
        } catch (e: CancellationException) {
            slot.safeReport(MediaGenResult.Cancelled)
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: context.getString(R.string.err_chat_unknown)
            slot.safeReport(MediaGenResult.Failure(msg))
            "Falló la generación de video: $msg"
        } finally {
            progressJob?.cancel()
            safeFinish(MediaGenKind.VIDEO)
        }
    }

    /**
     * Convierte cualquier texto en una imagen de código QR.
     *
     * UI Visualización de ImageUrls del mensaje del asistente actual escrito en la sesión;El proceso sin interfaz de usuario se coloca en el directorio de caché de la aplicación y devuelve la ruta.
     */
    suspend fun execGenerateQrCode(args: Map<String, String>): String {
        val content = args["content"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: content"
        val size = args["size"]?.toIntOrNull()?.coerceIn(128, 1024) ?: 400
        val slot = currentHost()?.begin(MediaGenKind.QR_CODE)

        return try {
            val bitmap = QrCodeGenerator.generateQrBitmap(content, size)
                ?: return "Falló la generación del código QR"
            val bytes = java.io.ByteArrayOutputStream().apply {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this)
            }.toByteArray()
            bitmap.recycle()
            val dataUri = "data:image/png;base64," +
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            // A-13: Verifique que esta ronda de generación aún esté activa,Evite la contaminación entre sesiones
            if (slot != null && !slot.isActive()) {
                Logger.w(TAG, "A-13: La generación del código QR se completa pero la generación ha caducado.(nueva ronda de generación),Saltar la escritura en los medios")
                return "Se ha generado el código QR,Pero la generación actual ha caducado.,Resultados no mostrados(Puedes volver a solicitar si es necesario)"
            }
            if (slot != null) {
                slot.safeReport(MediaGenResult.Success(imageUrls = listOf(dataUri)))
                "Código QR generado exitosamente"
            } else {
                val path = writePngToCache(bytes, "qr")
                if (path == null) "Falló la generación del código QR: Los resultados no se pueden guardar" else "Se ha generado el código QR: $path"
            }
        } catch (e: Exception) {
            "Falló la generación del código QR: ${e.message ?: "error desconocido"}"
        } finally {
            safeFinish(MediaGenKind.QR_CODE)
        }
    }

    /**
     * Sintetice un fragmento de texto en voz y envíelo como el mensaje de voz del asistente.
     *
     * Gráfico en enlace y sin procesar/La misma forma que el vídeo sin formato ([MediaGenHost] puerto + token de generación), la diferencia está en el producto:
     * Audio primero `cacheDir` Archivos temporales (el sistema se puede borrar en cualquier momento) y luego [VoiceMessageArchiver]
     * Movido al directorio persistente, el mensaje solo contiene `file://` Cita.
     *
     * Cada llamada tiene su propia [MediaGenHost.begin] Una vez, sin compartir el mismo espacio con otras llamadas: al enviar varias voces seguidas
     * Cada pieza completa de forma independiente la "síntesis" → Archivo → Delivery", los mangos de las ranuras no se comparten entre sí.
     *
     * Hay dos puertas antes de la síntesis:
     *  - No hay caracteres chinos en el texto. → No sintetizado (la narración en inglés del personaje bilingüe es difícil de escuchar en chino y el crédito se desperdicia);
     *  - Se ha alcanzado el número de líneas de voz en esta ronda [VOICE_TURN_LIMIT] → No sintético.
     *
     * @param args `text`(obligatorio)/ `emotion`(opcional, texto libre,provider Si no lo admites, simplemente ignóralo)
     * @param turnKey El identificador fijo de esta ronda (la identificación del mensaje del asistente inyectada por el anfitrión); si está vacío, no habrá límite en la cantidad de mensajes.
     * @return Redacción publicitaria para modelos;**sin uri** —— La voz ha entrado en la conversación y el camino sólo ocupará el contexto.
     */
    suspend fun execSendVoice(args: Map<String, String>, turnKey: String? = null): String {
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: text"
        voiceBlockReason(text, turnKey)?.let { return it }
        val emotion = args["emotion"]?.takeIf { it.isNotBlank() }
        val slot = currentHost()?.begin(MediaGenKind.VOICE)
        // Los archivos temporales se colocan en cacheDir: VoiceMessageStore se encarga de la persistencia y no se ensuciará si falla a mitad de camino. filesDir
        val tempFile = File(context.cacheDir, "$VOICE_TEMP_PREFIX${Uuid.random()}.wav")

        return try {
            when (val outcome = voiceSynthesizer.synthesizeTo(text, tempFile, emotion)) {
                // Éxito en la nube/sonido del sistema completado directamente: solo informa "Mensaje de voz enviado" como antes de la transformación
                TtsSynthResult.Success -> deliverVoice(slot, tempFile, text, emotion, fallbackReason = null)
                // Regresar al sistema TTS: El audio se puede utilizar, pero el sonido no lo asigna el usuario. Hay que dejarlo claro.
                is TtsSynthResult.SystemFallback ->
                    deliverVoice(slot, tempFile, text, emotion, fallbackReason = outcome.reason)
                is TtsSynthResult.Failure -> reportFailure(slot, VOICE_SYNTH_FAILED)
            }
        } catch (e: CancellationException) {
            slot.safeReport(MediaGenResult.Cancelled)
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: context.getString(R.string.err_chat_unknown)
            slot.safeReport(MediaGenResult.Failure(msg))
            "Error al enviar mensaje de voz: $msg"
        } finally {
            // éxito/falló/Cancele las tres rutas para eliminar archivos temporales (el archivo ya es una copia independiente)
            tempFile.delete()
            safeFinish(MediaGenKind.VOICE)
        }
    }

    // ── herramientas internas ────────────────────────────────────────────────────────

    /**
     * Dos puertas antes de la síntesis.
     *
     * @return null Indica que se puede sintetizar.;De lo contrario, la copia de la descripción se devolverá directamente al modelo.
     */
    private fun voiceBlockReason(text: String, turnKey: String?): String? {
        if (!containsSpeakableText(text)) return VOICE_SKIPPED_NO_SPEECH
        if (!voiceQuota.tryConsume(turnKey)) {
            return "La cantidad de mensajes de voz en esta ronda ha alcanzado el límite superior ($VOICE_TURN_LIMIT ), este artículo no genera voz."
        }
        return null
    }

    /** Hay letras (español, chino u otro idioma) que se pueden leer en voz alta. */
    private fun containsSpeakableText(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetter(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    /** Colocación de voz + detección de duración + entrega; la copia devuelta al modelo no contiene uri.
     *
     * @param fallbackReason La razón por la cual la nube no surte efecto; no nulo significa que esta voz utiliza el tono incorporado del sistema
     */
    private suspend fun deliverVoice(
        slot: MediaGenSlot?,
        tempFile: File,
        text: String,
        emotion: String?,
        fallbackReason: String?,
    ): String {
        // key estable → La síntesis repetida del mismo texto y la misma emoción llegará al mismo archivo y no se acumularán múltiples copias de audio equivalente.
        val voiceUri = voiceMessageStore.persist(tempFile, text + VOICE_KEY_SEPARATOR + (emotion ?: ""))
            ?: return reportFailure(slot, "No se pudo guardar el archivo de voz")
        // La duración solo se utiliza para la visualización de la interfaz de usuario y no se puede detectar (0) también se entregará como de costumbre
        val durationMs = voiceMessageStore.probeDurationMs(tempFile)
        if (slot == null || !slot.isActive()) {
            // A-13 Fallo generacional/sin proceso de UI: ningún mensaje hará referencia a esta voz, elimínela para evitar dejar archivos huérfanos
            voiceMessageStore.delete(voiceUri)
            return "El discurso ha sido sintetizado.,Pero no hay mensajes que entregar,Mensaje de voz no enviado"
        }
        slot.safeReport(MediaGenResult.Success(voiceUri = voiceUri, voiceDurationMs = durationMs))
        return if (fallbackReason == null) {
            "Mensaje de voz enviado"
        } else {
            "Se envió un mensaje de voz, pero actualmente se utiliza el tono integrado del teléfono (TTS en la nube no tiene efecto:$fallbackReason)"
        }
    }

    /** Las fallas se escriben en el mensaje de la sesión (si hay mensajes que se pueden escribir) y el motivo se devuelve como resultado de la herramienta. */
    private suspend fun reportFailure(slot: MediaGenSlot?, message: String): String {
        slot.safeReport(MediaGenResult.Failure(message))
        return message
    }

    /**
     * Publique los resultados en el mensaje de la sesión.
     *
     * paquete [NonCancellable]:La rutina de herramienta en la ruta de cancelación ha sido cancelada.,Las llamadas de suspensión ordinarias se cancelarán inmediatamente.
     * CancellationException,causa"Construcción cancelada"Avisar que el mensaje no se puede escribir.(Requisitos de comportamiento consistentes con la implementación anterior.).
     */
    private suspend fun MediaGenSlot?.safeReport(result: MediaGenResult) {
        val slot = this ?: return
        runCatching { withContext(NonCancellable) { slot.report(result) } }
            .onFailure { Logger.w(TAG, "Error en la entrega del resultado multimedia: ${it.message}") }
    }

    /** generación final(Limpiar el estado del marcador de posición de la interfaz de usuario),La falla no afecta los valores de retorno de la herramienta. */
    private suspend fun safeFinish(kind: MediaGenKind) {
        val target = currentHost() ?: return
        runCatching { withContext(NonCancellable) { target.finish(kind) } }
            .onFailure { Logger.w(TAG, "Error al cerrar la generación de medios(kind=$kind): ${it.message}") }
    }

    /**
     * v1.137: Analizar lista de imágenes de referencia(Opcional,Usado para videos de Tusheng/Vídeo de DuoTuSheng).
     *
     * B-05: data URI Contiene comas(data:image/png;base64,AAA),La división por coma se romperá.
     * Revisión de corrección (2.0 B-05): Admite la combinación de URL y URI de datos — Recorrer sección por sección,a "data:" comienzo
     * párrafo y el párrafo siguiente(base64 cargar)Volver a empalmar con comas(data URI Sólo hay una coma dentro del separador.,
     * base64 El alfabeto no contiene comas.),Los segmentos de URL normales se dejan intactos.
     */
    private fun parseReferenceImages(raw: String?): List<String> = raw?.let { value ->
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            emptyList()
        } else {
            val tokens = trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val rebuilt = mutableListOf<String>()
            var i = 0
            while (i < tokens.size) {
                if (tokens[i].startsWith("data:")) {
                    // data URI:Este párrafo + siguiente párrafo(base64 cargar,No contiene comas)
                    val payload = tokens.getOrNull(i + 1)
                    if (payload != null) {
                        rebuilt.add(tokens[i] + "," + payload)
                        i += 2
                    } else {
                        rebuilt.add(tokens[i])
                        i += 1
                    }
                } else {
                    rebuilt.add(tokens[i])
                    i += 1
                }
            }
            rebuilt
        }
    } ?: emptyList()

    /**
     * Externalización de resultados sin sesión de UI:data URI Colocar en el disco como archivo de caché(Devolver ruta absoluta),
     * http(s) URL Devolver como está. Evite abarrotar MB de base64 en resultados de herramientas que abrumen el contexto del modelo.
     */
    private fun externalizeMedia(urls: List<String>, prefix: String): List<String> = urls.mapNotNull { url ->
        when {
            url.startsWith("data:") -> writeDataUriToCache(url, prefix)
            else -> url
        }
    }.ifEmpty { listOf("(No se puede acceder a ninguna ubicación de resultados)") }

    /** Decodifica el URI de datos base64 y descárgalo al disco;Devuelve nulo en caso de error. */
    private fun writeDataUriToCache(dataUri: String, prefix: String): String? {
        val base64 = dataUri.substringAfter("base64,", "")
        if (base64.isBlank()) return null
        return runCatching {
            writePngToCache(android.util.Base64.decode(base64, android.util.Base64.DEFAULT), prefix)
        }.getOrNull()
    }

    /** Escriba bytes PNG en el directorio de caché de la aplicación,Devolver ruta absoluta;Devuelve nulo en caso de error. */
    private fun writePngToCache(bytes: ByteArray, prefix: String): String? = runCatching {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    private companion object {
        const val TAG = "MediaGenTools"

        /** El directorio donde se eliminan los resultados multimedia cuando no hay sesión de UI(caché de aplicaciones,Puede ser limpiado por el sistema.). */
        const val CACHE_DIR = "mediagen"

        /** Intervalo de actualización del progreso de la generación de video(v1.135 comportamiento:cada 5 segundos). */
        const val PROGRESS_INTERVAL_MS = 5_000L

        /** Prefijo de archivo temporal de mensaje de voz (escribir cacheDir, reproducir/Eliminar después de guardar). */
        const val VOICE_TEMP_PREFIX = "voice-message-"

        /** Delimitador de clave de archivo de voz:`text \u0000 emotion`, para evitar colisiones con diferentes emociones en un mismo texto. */
        const val VOICE_KEY_SEPARATOR = "\u0000"

        /** El número máximo de voces enviadas por el asistente en una ronda (superando el rechazo directo, sin síntesis). */
        const val VOICE_TURN_LIMIT = 5

        /** El límite superior del número de rondas retenidas por el recuento de elementos para evitar el crecimiento ilimitado de la tabla de recuento después de una operación a largo plazo. */
        const val VOICE_TURN_WINDOW = 16

        /** Resultados de la herramienta cuando no hay caracteres chinos en el texto: explique el motivo y solicite utilizar texto en su lugar. */
        const val VOICE_SKIPPED_NO_SPEECH =
            "Este párrafo no tiene texto para leer en voz alta"

        /** La herramienta surge cuando el TTS de la nube y del sistema no se sintetizan (la redacción es la misma que antes de la transformación). */
        const val VOICE_SYNTH_FAILED = "La síntesis de voz falló"
    }
}

/**
 * La cantidad de barras de voz por ronda.
 *
 * La clave es el identificador de ronda inyectado por el anfitrión (fijado dentro de la ronda y la clave se cambiará naturalmente en la siguiente ronda), por lo que no es necesario realizar una devolución de llamada al final de la ronda;
 * Mantenga solo el más reciente [trackedTurns] El recuento de rondas, cuando se introduce una nueva ronda, el recuento anterior se invalidará en su totalidad para evitar un crecimiento ilimitado.
 * El tiempo que supere el límite no se contará (volver a intentar la misma frase seguirá bloqueado).
 */
private class TurnVoiceQuota(private val limit: Int, private val trackedTurns: Int) {

    private val counts = HashMap<String, Int>()

    /** Grabar un consumo de voz; devuelve falso si se excede el límite. No hay límite cuando no existe un identificador de ronda (enlace de no conversación). */
    @Synchronized
    fun tryConsume(turnKey: String?): Boolean {
        val key = turnKey?.takeIf { it.isNotBlank() } ?: return true
        val used = counts[key] ?: 0
        if (used >= limit) return false
        if (used == 0 && counts.size >= trackedTurns) counts.clear()
        counts[key] = used + 1
        return true
    }
}
