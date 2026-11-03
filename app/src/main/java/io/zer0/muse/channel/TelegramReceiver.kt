package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * v2.0: Telegram receptor — Ciclo de votación largo.
 *
 * Llamado cíclicamente cuando hay un canal TELEGRAM habilitado getUpdates(encuesta larga 30s):
 * Escritura de mensajes de usuario [ChannelInbox](Activar enlace de respuesta automática). Conexión saliente,Libre de red pública.
 * Guardar configuración/Llamado por la interfaz de usuario o cuando la aplicación se inicia después de la eliminación [restart].
 */
class TelegramReceiver(
    private val channelManager: ChannelManager,
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    private var job: Job? = null

    /** Empezar a sondear(idempotente;Existe un ciclo de detenerse y luego comenzar de nuevo.). */
    fun restart() {
        job?.cancel()
        job = appScope.launch {
            ChannelInbox.attach(context)
            pollLoop()
        }
    }

    /** Deja de votar. */
    fun stop() {
        job?.cancel()
        job = null
    }

    @Suppress("TooGenericExceptionCaught") // Preserve the current getUpdates offset for any local persistence failure.
    private suspend fun pollLoop() {
        var cursor = TelegramCursor()
        var reportedEmpty = false
        while (currentCoroutineContext().isActive) {
            channelManager.refresh()
            val config = channelManager.channels.value.firstOrNull {
                it.enabled && it.platform == ChannelPlatform.TELEGRAM && it.appSecret.isNotBlank()
            }
            if (config == null) {
                // Registre solo una vez cuando "no ingrese al estado de ningún canal"; luego use un retroceso largo para esperar.
                //
                // La implementación anterior se repite cada 5 segundos en esta rama y registra una línea: los usuarios que no configuren canales verán
                // 12 líneas por minuto de la misma "salida del bucle de recepción" mientras se activan hilos innecesariamente.
                if (!reportedEmpty) {
                    Logger.i(TAG, "No hay canales activos de Telegram,El bucle de recepción se bloquea esperando la configuración")
                    reportedEmpty = true
                }
                delay(NO_CHANNEL_BACKOFF_MS)
                continue
            }
            if (reportedEmpty) {
                Logger.i(TAG, "Telegram El canal está habilitado.,Reanudar recepción")
                reportedEmpty = false
            }
            // v2.6.0 (AUD-CHANNEL-F2): Función de sondeo de ronda única —— Cursor fallido/Anormalidad de la red/
            // Las excepciones de escritura se devuelven uniformemente“tiempo de espera”,Los bucles ya no acumulan declaraciones de salto.
            cursor = pollOnce(config, cursor)
            delay(RETRY_DELAY_MS)
        }
    }

    /**
     * realizar una encuesta,Devuelve el cursor que debe usarse para la siguiente ronda.
     *
     * cualquier local/Las excepciones de red se registran en la función.,No afecta la continuación del ciclo. —— Este es el núcleo de F2:
     * El bucle de recepción no debe salir permanentemente debido a una excepción.
     */
    /** Un mensaje entrante se registra en la base de datos; Las imágenes se pueden descargar en dos pasos. */

    private suspend fun recordInbound(config: ChannelConfig, msg: TelegramClient.InboundMsg) {
        val source = ChannelInbox.Source(
            "TELEGRAM",
            msg.chatId.toString(),
            config.id,
            msg.updateId?.toString().orEmpty(),
        )

        val fileId = msg.photoFileId

        if (fileId.isNullOrBlank()) {
            ChannelInbox.record(source, msg.text, "")

            return
        }

        // Descarga de imágenes en dos pasos —— getFile Reemplace file_path y luego obtenga bytes según la ruta.

        val base64 = TelegramClient.getFile(config.appSecret, fileId)
            .getOrNull()

            ?.let { path -> TelegramClient.downloadFile(config.appSecret, path).getOrNull() }

            ?.let { ChannelMediaUtils.toCompactImageBase64(it) }

        if (base64 != null) {
            ChannelInbox.record(
                source = source,
                text = msg.text.ifBlank { "[fotos]" },
                rawPayload = "",
                media = ChannelInbox.Media(kind = "image", base64 = base64),
            )
        } else {
            Logger.w(TAG, "Telegram Error en la adquisición de imágenes(chat=${msg.chatId})")

            ChannelInbox.record(source, msg.text.ifBlank { "[fotos(No se pudo obtener)]" }, "")
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun pollOnce(config: ChannelConfig, cursor: TelegramCursor): TelegramCursor {
        val next = nextCursorFor(config, cursor) ?: return cursor
        val updates = TelegramClient.getUpdates(config.appSecret, next.offset).getOrNull()
            ?: return next
        return try {
            updates.messages.forEach { msg -> recordInbound(config, msg) }
            val nextOffset = updates.nextOffset
            if (nextOffset != next.offset) {
                saveOffset(config.id, nextOffset)
            }
            TelegramCursor(config.id, nextOffset)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(TAG, "Telegram La actualización no pudo escribir en la bandeja de entrada local,Mantener el cursor de sondeo: ${error.message}")
            next
        }
    }

    /**
     * v2.6.0 (AUD-CHANNEL-F2): offset Ya no arroja un error cuando el archivo está dañado.
     *
     * Implementación original error(...) Lanzarlo fuera del bucle finalizará todo el trabajo de recepción y nadie lo reiniciará. ——
     * Los usuarios solo pueden finalizar el proceso o ingresar a la página de configuración para activarlo manualmente, que aparece como "Telegram No más mensajes."
     * considerado dañado“Sin cursor”Devuelve nulo (comienza desde el último) y puede consumir hasta varios elementos repetidamente.
     * Mejor que estar desconectado permanentemente.
     */
    /**
     * v2.6.0 (AUD-CHANNEL-F2): Resuelva el siguiente cursor de encuesta.
     *
     * Devolver nulo indica que la lectura del cursor local falló.(registrado),Reintento de retroceso por parte de la persona que llama ——
     * La implementación original lee directamente dentro del bucle y arroja un error,Hará que el trabajo receptor salga permanentemente.
     */
    private fun nextCursorFor(config: ChannelConfig, cursor: TelegramCursor): TelegramCursor? {
        if (cursor.channelId == config.id) return cursor
        return runCatching { TelegramCursor(config.id, loadOffset(config.id)) }
            .onFailure { error ->
                Logger.w(TAG, "Telegram Falló la inicialización del cursor: ${error.message}")
            }
            .getOrNull()
    }

    /**
     * v2.6.0 (AUD-CHANNEL-F2): offset Ya no arroja un error cuando el archivo está dañado.
     *
     * considerado dañado“Sin cursor”Devuelve nulo (comienza desde el último) y puede consumir hasta varios elementos repetidamente.
     * Mejor que estar desconectado permanentemente.
     */
    private fun loadOffset(channelId: String): Long? {
        val target = telegramOffsetFile(context, channelId)
        if (!target.exists()) return null
        val parsed = target.readText().trim().toLongOrNull()
        if (parsed == null) {
            Logger.w(TAG, "Telegram offset Archivo corrupto(El contenido no es numérico.),Descarta el cursor y tira de él nuevamente.")
            // Borrar archivos malos,Evita alarmas repetidas cada vez que empiezas
            runCatching { target.delete() }
        }
        return parsed
    }

    private fun saveOffset(channelId: String, offset: Long?) {
        if (offset == null) return
        AtomicFileStore.writeText(telegramOffsetFile(context, channelId), offset.toString())
    }

    companion object {
        private const val TAG = "TelegramReceiver"

        /** Error al volver a intentar retroceder(milisegundos). */
        private const val RETRY_DELAY_MS = 5_000L

        /** Retroceda cuando no haya canales configurados. No participa en la encuesta, solo espera a que aparezca la configuración para poder estirarla. */
        private const val NO_CHANNEL_BACKOFF_MS = 60_000L
    }
}

private data class TelegramCursor(val channelId: String = "", val offset: Long? = null)

internal fun telegramOffsetFile(context: Context, channelId: String): File =
    File(context.filesDir, "channel_telegram_offset_${telegramCursorDigest(channelId)}.txt")

private fun telegramCursorDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
