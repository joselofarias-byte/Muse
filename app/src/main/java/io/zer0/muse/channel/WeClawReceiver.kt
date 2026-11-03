package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.SecureKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest

internal const val WECLAW_REPLY_CONTEXT_TOKEN_PREFIX = "muse.weclaw.context.v1:"
private const val WECLAW_CURSOR_PREFIX = "muse.weclaw.cursor.v1:"

internal suspend fun protectWeClawReplyContextToken(token: String): String =
    if (token.isBlank()) "" else SecureKeyStore.encrypt(WECLAW_REPLY_CONTEXT_TOKEN_PREFIX + token)

internal suspend fun restoreWeClawReplyContextToken(encryptedToken: String): String? = SecureKeyStore.decryptOrNull(encryptedToken)
    ?.takeIf { it.startsWith(WECLAW_REPLY_CONTEXT_TOKEN_PREFIX) }
    ?.removePrefix(WECLAW_REPLY_CONTEXT_TOKEN_PREFIX)
    ?.takeIf { it.isNotBlank() }

internal suspend fun protectWeClawCursor(cursor: String): String =
    if (cursor.isBlank()) "" else SecureKeyStore.encrypt(WECLAW_CURSOR_PREFIX + cursor)

internal suspend fun restoreWeClawCursor(storedCursor: String): String {
    if (storedCursor.isBlank()) return ""
    val plaintext = SecureKeyStore.decryptOrNull(storedCursor)
        ?: error("No se puede descifrar el cursor de sincronización de WeClaw")
    return when {
        plaintext.startsWith(WECLAW_CURSOR_PREFIX) -> plaintext.removePrefix(WECLAW_CURSOR_PREFIX)
        storedCursor.startsWith("enc_v1:") -> error("WeClaw El formato de texto cifrado del cursor de sincronización no coincide")
        else -> plaintext
    }
}

/**
 * v2.0: WeChat ClawBot(iLink)receptor — Ciclo de votación largo.
 *
 * Llamado cíclicamente cuando hay un canal WECLAW habilitado getupdates(encuesta larga 30s):
 * Escritura de mensajes de usuario [ChannelInbox](Activar enlace de respuesta automática),context_token escribir
 * El cifrado se almacena temporalmente en la bandeja de entrada hasta que se complete la distribución.,Actualizar simultáneamente [WeClawContextCache] Para uso en otras rutas de devolución de datos.
 * Guardar configuración/Llamado por UI después de la eliminación [restart].
 */
class WeClawReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null
    private data class PollCursor(val channelId: String = "", val buffer: String = "")

    /**
     * iniciar el portal:Primero determine de acuerdo con la configuración de persistencia.,Luego siga los cambios de canal posteriores para iniciar y detener.
     *
     * El bucle de recepción solo comienza si hay un canal WECLAW activo —— Implementación anterior incondicional [restart],Cuando no hay canal
     * El bucle se inactivo cada 30 segundos,Cada vez, se debe escribir un mensaje "Sin canal" en el registro de depuración.
     *
     * [ChannelManager.channels] si [kotlinx.coroutines.flow.StateFlow],Pero cuando el proceso comenzó
     * Aún no he leído sobre la configuración de persistencia(el valor es una lista vacía),Entonces primero [ChannelManager.refresh] Rejuicio;
     * Simplemente léelo en la rutina de inicio de la persona que llama.,No abras otro hilo.
     */
    suspend fun startForEnabledChannels() {
        channelManager.refresh()
        applyAvailability(weClawChannelAvailable(channelManager.channels.value))
        appScope.launch {
            // Adición y eliminación de canales/Iniciar y detener seguido de iniciar y detener.StateFlow Sólo devolución de llamada cuando el valor realmente cambia,
            // Y sondear en bucle cada ronda. refresh() Si el contenido de la lista que sale no ha cambiado, esto no se activará.
            channelManager.channels.collect { channels ->
                applyAvailability(weClawChannelAvailable(channels))
            }
        }
    }

    /**
     * Haga clic en "Si hay un canal WECLAW activo" para iniciar y detener.
     *
     * Solo tome medidas cuando "debería ejecutarse" y "ejecutarse" sean inconsistentes,Este es el juicio para evitar la autodesencadenamiento:[stop] Después de eso, el canal no
     * cambiar,El mismo valor no iniciará el ciclo nuevamente. Deshabilite y habilite nuevamente y aún reciba el mensaje —— El canal vuelve a cambiar a "canal habilitado".
     * es una transferencia estatal,irá [restart];[pollLoop] El bolsillo interior de 30 segundos todavía está reservado como segunda capa de seguro.
     */
    @Synchronized
    private fun applyAvailability(available: Boolean) {
        when (weClawPollAction(available, job?.isActive == true)) {
            WeClawPollAction.START -> restart()
            WeClawPollAction.STOP -> stop()
            WeClawPollAction.NONE -> Unit
        }
    }

    /** Empezar a sondear(idempotente;Existe un ciclo de detenerse y luego comenzar de nuevo.). */
    @Synchronized
    fun restart() {
        job?.cancel()
        job = appScope.launch {
            ChannelInbox.attach(context)
            pollLoop()
        }
    }

    /** dejar de sondear(App Cuando el canal se cierra o se elimina). */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pollLoop() {
        var cursor = PollCursor()
        // Si se encontraron canales disponibles en la ronda anterior. Registre solo cuando cambie el estado: la implementación anterior registra un "sin canal" cada 30 segundos.
        // 6 Puede ocupar la mitad del registro de depuración cada hora, enterrando lo que realmente necesita ser visto.
        var lastAvailable: Boolean? = null
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull { it.isUsableWeClaw() }
            val available = config != null
            channelAvailabilityLog(available, lastAvailable)?.let { Logger.i(TAG, it) }
            lastAvailable = available
            if (config == null) {
                // v2.6.7 fix (AUDIT F17): no lo hagas return —— La implementación anterior permitía que todo el trabajo de recepción finalizara sin que nadie lo reiniciara.
                // Después de que el usuario desactive y vuelva a habilitar el canal, ya no recibirá mensajes hasta que reinicie la aplicación o haga clic en Guardar nuevamente.
                delay(30_000L)
                continue
            }
            cursor = pollOnce(config, cursor)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Transport, Keystore, and inbox failures all require retrying the same cursor.
    private suspend fun pollOnce(config: ChannelConfig, previousCursor: PollCursor): PollCursor = try {
        val cursor = if (previousCursor.channelId == config.id) {
            previousCursor
        } else {
            PollCursor(channelId = config.id, buffer = loadBuffer(config.id))
        }
        val updates = WeClawClient.getUpdates(config.appSecret, cursor.buffer).getOrThrow()
        val nextBuffer = updates.buffer.ifBlank { cursor.buffer }
        updates.messages.forEachIndexed { index, msg ->
            WeClawContextCache.put(msg.fromUserId, msg.contextToken)
            val eventId = weClawBatchEventId(cursor.buffer, nextBuffer, index, msg)
            val source = ChannelInbox.Source(
                platform = "WECLAW",
                from = msg.fromUserId,
                channelId = config.id,
                eventId = eventId,
                encryptedReplyContextToken = protectWeClawReplyContextToken(msg.contextToken),
            )
            val media = msg.media
            if (media == null) {
                ChannelInbox.record(source, msg.text, "")
            } else {
                handleMediaMessage(msg, media, source)
            }
        }
        if (nextBuffer != cursor.buffer) saveBuffer(config.id, nextBuffer)
        PollCursor(channelId = config.id, buffer = nextBuffer)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Logger.w(TAG, "WeClaw La actualización no pudo ponerse en cola de manera confiable,Mantener el cursor de sondeo: ${error.message}")
        delay(RETRY_DELAY_MS)
        previousCursor
    }

    private suspend fun loadBuffer(channelId: String): String {
        val cursorFile = cursorFile(channelId)
        if (!cursorFile.exists()) return ""
        return restoreWeClawCursor(cursorFile.readText())
    }

    private suspend fun saveBuffer(channelId: String, buffer: String) {
        AtomicFileStore.writeText(cursorFile(channelId), protectWeClawCursor(buffer))
    }

    private fun cursorFile(channelId: String): File {
        val digest = channelDigest(channelId)
        return File(context.filesDir, "channel_weclaw_cursor_$digest.txt")
    }

    /**
     * v2.0.1: Procesamiento de mensajes multimedia — Descarga de imagen(CDN + AES Descifrar)Y comprimirlo en la base de datos.;
     * Discurso mediante transcripción del lado del servidor(parseMessages Lleno);
     * v2.x (B4): vídeo/Descargue el archivo a un directorio privado y registre la ruta local(El marcador de posición de texto se actualiza para incluir el nombre del archivo./camino).
     */
    private suspend fun handleMediaMessage(msg: WeClawClient.InboundMsg, media: WeClawClient.MediaRef, source: ChannelInbox.Source) {
        val from = source.from
        when (media.kind) {
            "image" -> {
                val bytes = withTimeoutOrNull(MEDIA_DOWNLOAD_TIMEOUT_MS) {
                    WeClawClient.downloadMedia(media).getOrNull()
                }
                val base64 = bytes?.let { ChannelMediaUtils.toCompactImageBase64(it) }
                if (base64 != null) {
                    ChannelInbox.record(
                        source = source,
                        text = "[fotos]",
                        rawPayload = "",
                        media = ChannelInbox.Media(kind = "image", base64 = base64),
                    )
                } else {
                    Logger.w(TAG, "Error al descargar o decodificar la imagen(from=$from)")
                    ChannelInbox.record(
                        source,
                        "[fotos(No se pudo obtener)]",
                        "",
                    )
                }
            }
            "voice" -> {
                // iLink La voz viene con transcripción ASR del lado del servidor;Se proporciona un marcador de posición cuando no hay traducción.
                ChannelInbox.record(
                    source,
                    msg.text.ifBlank { "[Voz]" },
                    "",
                )
            }
            // v2.x (B4): vídeo/Archivo — Descargar + colocar + grabar(Contiene el nombre del archivo y la ruta.)
            "video" -> handleBinaryMedia(media, source, kind = "video", label = "[vídeo]")
            else -> handleBinaryMedia(media, source, kind = "file", label = "[Archivo]")
        }
    }

    /**
     * v2.x (B4): vídeo/Archivo entrante — Descargar y descifrar en [MEDIA_DIR],El registro contiene el nombre del archivo y la ruta local.
     *
     * v2.x cierre del legado:Cambiar a**transmisión**Descargar(Escribe el archivo mientras escribes.),Ya no lee en la memoria todo a la vez;
     * [MAX_MEDIA_SAVE_BYTES] La protección entra en vigor dentro de la secuencia(Cancelar y eliminar inmediatamente el trabajo en curso).
     * falló/por encima del límite/Tiempo de espera reducido a texto de marcador de posición(No lanzar excepción,No bloquear encuestas posteriores).
     */
    @Suppress("TooGenericExceptionCaught") // Cleanup must run for every persistence failure after a media file is created.
    private suspend fun handleBinaryMedia(
        media: WeClawClient.MediaRef,
        source: ChannelInbox.Source,
        kind: String,
        label: String,
    ) {
        val from = source.from
        val channelId = source.channelId
        val eventId = source.eventId
        val target = File(
            File(context.filesDir, MEDIA_DIR),
            "${System.currentTimeMillis()}_${defaultWeClawMediaFileName(media, kind)}",
        )
        val result = withTimeoutOrNull(MEDIA_DOWNLOAD_TIMEOUT_MS) {
            WeClawClient.downloadMediaToFile(media, target, MAX_MEDIA_SAVE_BYTES)
        }
        if (result == null) {
            Logger.w(TAG, "Tiempo de espera de descarga de medios(kind=$kind, from=$from)")
            ChannelInbox.record(
                source,
                "$label(No se pudo obtener)",
                "",
                media = ChannelInbox.Media(kind = kind),
            )
            return
        }
        val written = result.getOrNull()
        if (written == null) {
            val error = result.exceptionOrNull()
            if (error is MediaTooLargeException) {
                Logger.w(TAG, "Los medios exceden el límite de tamaño(kind=$kind, from=$from)")
                ChannelInbox.record(
                    source,
                    "$label(Límite de tamaño excedido ${MAX_MEDIA_SAVE_BYTES / 1024 / 1024}MB,Aún no colocado)",
                    "",
                    ChannelInbox.Media(kind = kind),
                )
            } else {
                Logger.w(TAG, "Falló la descarga de medios(kind=$kind, from=$from, err=${error?.message})")
                ChannelInbox.record(
                    source,
                    "$label(No se pudo obtener)",
                    "",
                    media = ChannelInbox.Media(kind = kind),
                )
            }
            return
        }
        val displayName = media.fileName.ifBlank { target.name }
        try {
            val accepted = ChannelInbox.record(
                source = source,
                text = "$label $displayName",
                rawPayload = "",
                media = ChannelInbox.Media(kind = kind, path = target.absolutePath),
            )
            if (!accepted) target.delete()
        } catch (error: Exception) {
            val eventStored = ChannelInbox.messages.value.any {
                it.sourceChannelId == channelId && it.sourceEventId == eventId
            }
            if (!eventStored) target.delete()
            throw error
        }
    }

    companion object {
        private const val TAG = "WeClawReceiver"

        /** Error al volver a intentar retroceder(milisegundos). */
        private const val RETRY_DELAY_MS = 5_000L

        /** v2.0.1: Tiempo de espera de descarga de medios(milisegundos). */
        private const val MEDIA_DOWNLOAD_TIMEOUT_MS = 60_000L

        /** v2.x (B4): vídeo/Directorio de ubicación de archivos(en archivos privadosDir). */
        private const val MEDIA_DIR = "channel_media"

        /** v2.x (B4): vídeo/Límite de tamaño de archivo(32MB),Más allá de simplemente registrar marcadores de posición. */
        private const val MAX_MEDIA_SAVE_BYTES = 32L * 1024 * 1024
    }
}

/**
 * Disponibilidad del canal + si se está ejecutando actualmente → Qué hacer.
 *
 * "Si ambas partes están de acuerdo, NINGUNO" es todo el secreto para evitar la autodesencadenamiento:stop El canal no ha cambiado desde entonces.,El mismo valor vuelve a entrar. NONE,
 * No reiniciará el ciclo que acaba de detenerse.;Realmente necesito levantarlo de nuevo.,Sólo nos queda esperar hasta que la configuración del canal realmente cambie a "canales habilitados".
 */
internal fun weClawPollAction(available: Boolean, running: Boolean): WeClawPollAction = when {
    available && !running -> WeClawPollAction.START
    !available && running -> WeClawPollAction.STOP
    else -> WeClawPollAction.NONE
}

/** [weClawPollAction] de tres resultados. */
internal enum class WeClawPollAction { START, STOP, NONE }

/** ¿Puede esta configuración de canal admitir sondeos largos de WECLAW?(enabled + Coincidencia de plataforma + tiene clave). */
private fun ChannelConfig.isUsableWeClaw(): Boolean =
    enabled && platform == ChannelPlatform.WECLAW && appSecret.isNotBlank()

/**
 * ¿Existe alguna lista de canales que pueda ejecutar encuestas largas de WECLAW?
 *
 * El propósito de extraer funciones puras es permitir que el "control de acceso de activación" se alimente directamente con configuraciones falsas mediante pruebas únicas.,En lugar de depender de la verificación de una máquina real:
 * Una lista vacía, solo canales deshabilitados, una clave vacía o una plataforma que no sea WECLAW no deberían desencadenar un bucle de recepción.
 */
internal fun weClawChannelAvailable(channels: List<ChannelConfig>): Boolean = channels.any { it.isUsableWeClaw() }

private fun defaultWeClawMediaFileName(media: WeClawClient.MediaRef, kind: String): String {
    val sanitized = sanitizeWeClawFileName(media.fileName)
    if (sanitized.isNotBlank()) return sanitized
    return "${kind}${if (kind == "video") ".mp4" else ".bin"}"
}

private fun sanitizeWeClawFileName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
    .filter { it.code >= 0x20 && it.code != 0x7F }
    .trim()
    .take(120)

/**
 * ¿Qué registro se debe registrar cuando cambia la disponibilidad del canal?;Devuelve nulo si no es necesario recordarlo.
 *
 * El bucle de recepción debe reintentar cada 30 segundos.(ver [WeClawReceiver.pollLoop] v2.6.7 comentarios en),
 * Por tanto, "sin canales" es un estado que se repetirá durante mucho tiempo.,en lugar de un evento. Recuérdalo como un evento.,
 * Recuerde solo cuando el estado realmente cambie —— También conserva el momento clave de "¿cuándo empezaré a no recibirlo?",
 * Tampoco se inundará el diario con la misma frase.
 *
 * @param hasChannel ¿Se ha encontrado un canal disponible en esta ronda?
 * @param previousHasChannel Resultados de la ronda anterior;null Indica que esta es la primera ronda del ciclo.
 * @return El texto de este registro.;null Indica que el estado no ha cambiado y no debe registrarse.
 */
internal fun channelAvailabilityLog(hasChannel: Boolean, previousHasChannel: Boolean?): String? = when {
    previousHasChannel == null && hasChannel -> "recibir inicio de ciclo,Actualmente hay canales disponibles."
    previousHasChannel == null -> "No hay canales activos de ClawBot,30 Vuelve a intentarlo en segundos"
    previousHasChannel == hasChannel -> null
    hasChannel -> "recuperación de canal,seguir recibiendo"
    else -> "No hay canales activos de ClawBot,30 Vuelve a intentarlo en segundos"
}

internal fun weClawBatchEventId(requestBuffer: String, responseBuffer: String, index: Int, message: WeClawClient.InboundMsg): String {
    val media = message.media
    val fingerprint = listOf(
        requestBuffer,
        responseBuffer,
        index.toString(),
        message.fromUserId,
        message.text,
        media?.kind.orEmpty(),
        media?.fileName.orEmpty(),
        media?.durationMs?.toString().orEmpty(),
        media?.fullUrl.orEmpty(),
        media?.encryptedQueryParam.orEmpty(),
        media?.aesKeyBase64.orEmpty(),
    ).joinToString("\u0000")
    return "weclaw-${channelDigest(fingerprint)}"
}

private fun channelDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
