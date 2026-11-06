package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.tools.MediaGenHost
import io.zer0.muse.tools.MediaGenKind
import io.zer0.muse.tools.MediaGenResult
import io.zer0.muse.tools.MediaGenSlot
import kotlin.uuid.Uuid

/**
 * P2-23: herramientas de generación de medios(Imágenes / Vídeos / Códigos QR)El anfitrión de entrega en la página de chat.
 *
 * La implementación de la herramienta en sí.([io.zer0.muse.tools.MediaGenToolsImpl])Nada que ver con ViewModel,por
 * `MediaGenToolsRegistrar` Regístrese al iniciar la aplicación;Esta clase solo es responsable de escribir los resultados generados."Actualmente
 * Mensaje de ayuda generado",era el original exec* La parte del método que está acoplada a la UI.:
 *  - Comienza la construcción:Copia de marcador de posición + logotipo de isGeneratingImage / isGeneratingVideo;
 *  - progreso del vídeo:Actualizar cada 5 segundos"Esperó N segundos";
 *  - Fin de generación:Si tiene éxito, agregue los medios al mensaje y guárdelo.(Al cambiar de sesión, inicie sesión directamente en la base de datos de acuerdo con la sesión original.),
 *    falló/Escribir copia correspondiente al cancelar,Y limpie las banderas de marcador de posición.
 *
 * Solo proceso de diálogo UI(ChatViewModel)Este host será instalado;Tareas programadas/chats grupales/subagentes, etc. Ninguno UI
 * el enlace no puede conseguirlo,Las herramientas multimedia recurren a rutas de archivos de retorno / URL,No depende de la interfaz de usuario para registrarse para la ejecución.
 */
class ChatMediaGenHost(
    private val accessor: ChatStateAccessor,
    private val sessionRepository: SessionRepository,
    private val streamCoordinator: ChatStreamCoordinator,
    private val appContext: Context,
    /** Mensaje del asistente correspondiente a la ronda de herramientas actual id(Ancla antes de que comience la generación). */
    private val toolAssistantIdProvider: () -> Uuid?,
    /** La identificación de la sesión correspondiente a la ronda de herramientas actual. */
    private val toolSessionIdProvider: () -> String?,
    /** Generar token generacional:El usuario envía un nuevo mensaje/cambia después de cambiar de sesión,El resultado de llegar tarde no será válido en consecuencia. */
    private val generationTokenProvider: () -> Long,
    /** Escribir copia del mensaje del asistente(Mantener el marcador de posición isStreaming);Corresponde al contenedor ChatViewModel.updateAssistant. */
    private val updateAssistant: suspend (Uuid, String) -> Unit,
    /** Las noticias que contengan medios se colocarán de inmediato.(La propia implementación lo omite cuando se elimina la sesión.). */
    private val persistMediaMessage: suspend (String?, Uuid) -> Unit,
    /** Regístrese para recibir noticias actuales de los medios,Para que las herramientas circulen y terminen, el fondo del bolsillo se coloca sobre la bandeja. */
    private val registerMediaMessage: (Uuid) -> Unit,
) : MediaGenHost {

    override suspend fun begin(kind: MediaGenKind): MediaGenSlot? {
        val assistantId = toolAssistantIdProvider() ?: return null
        // C-02: Establecer el indicador de marcador de posición de generación(ChatScreen Según esto, se muestra la tarjeta que se está generando.)
        when (kind) {
            MediaGenKind.IMAGE -> {
                updateAssistant(assistantId, appContext.getString(R.string.err_chat_img_generating))
                accessor.update { it.copy(isGeneratingImage = true) }
            }
            MediaGenKind.VIDEO -> {
                updateAssistant(assistantId, appContext.getString(R.string.err_chat_video_generating))
                accessor.update { it.copy(isGeneratingVideo = true) }
            }
            // El código QR se genera localmente,No se requieren tarjetas de marcador de posición
            MediaGenKind.QR_CODE -> Unit
            // La síntesis de voz solo toma uno o dos segundos,Tampoco ocupa tarjetas de marcador de posición(falló/La copia exitosa está escrita por informe)
            MediaGenKind.VOICE -> Unit
        }
        return Slot(kind, assistantId, toolSessionIdProvider(), generationTokenProvider())
    }

    override suspend fun finish(kind: MediaGenKind) {
        when (kind) {
            MediaGenKind.IMAGE -> accessor.update { it.copy(isGeneratingImage = false) }
            MediaGenKind.VIDEO -> accessor.update { it.copy(isGeneratingVideo = false) }
            MediaGenKind.QR_CODE -> Unit
            MediaGenKind.VOICE -> Unit
        }
    }

    private inner class Slot(
        private val kind: MediaGenKind,
        private val assistantId: Uuid,
        private val sessionId: String?,
        private val generationToken: Long,
    ) : MediaGenSlot {

        override fun isActive(): Boolean = generationToken == generationTokenProvider()

        override suspend fun onProgress(elapsedSeconds: Int) {
            if (kind != MediaGenKind.VIDEO) return
            updateAssistant(
                assistantId,
                appContext.getString(R.string.err_chat_video_progress, elapsedSeconds),
            )
        }

        override suspend fun report(result: MediaGenResult) {
            when (result) {
                is MediaGenResult.Success -> deliverMedia(result)
                is MediaGenResult.Failure -> updateAssistant(assistantId, failureText(result.message))
                MediaGenResult.Cancelled -> updateAssistant(assistantId, appContext.getString(cancelRes))
            }
        }

        /** éxito:De acuerdo con el enfoque de visualización actual, decida si fusionar la interfaz de usuario o eliminar directamente la base de datos de acuerdo con la sesión original.(El cambio de sesión no cancela la generación.). */
        private suspend fun deliverMedia(result: MediaGenResult.Success) {
            if (kind == MediaGenKind.VOICE) {
                deliverVoice(result)
                return
            }
            val content = appContext.getString(generatedRes)
            // Revisión de corrección (2.0 B-04): El URI de datos base64 largo se coloca a través de MessageImageStore como file://
            // camino(Nombre de archivo con hash de contenido,Las escrituras paralelas no se cubren entre sí.),DB No más almacenamiento MB base64.
            val imageUrls = result.imageUrls.takeIf { it.isNotEmpty() }
                ?.let { sessionRepository.toPersistableImageUrls(assistantId.toString(), it) }
            if (isSessionDisplayed()) {
                // A-03: Fusión atómica,Dibujos paralelos en la misma ronda.(awaitAll)Cada uno se adjunta según la última instantánea.,No cubrirse el uno al otro
                streamCoordinator.appendMediaToAssistant(
                    id = assistantId,
                    content = content,
                    imageUrls = imageUrls,
                    videoFileUri = result.videoFileUri,
                    voiceUri = result.voiceUri,
                    voiceDurationMs = result.voiceDurationMs,
                )
                // Reparación de auditoría (S-01): Las noticias que contengan medios se colocarán de inmediato.,De lo contrario reiniciar/Los medios desaparecen después del corte de página
                persistMediaMessage(sessionId, assistantId)
                // C-17: Regístrese para recibir noticias actuales de los medios,Ubicación inferior del bolsillo de cierre
                registerMediaMessage(assistantId)
            } else {
                // Revisión de corrección (2.0 A-04): El cambio de sesión no se cancela. — Los medios se registran directamente en la biblioteca de acuerdo con la sesión original.,
                // Restaurar la visualización desde la base de datos al volver a la sesión original(UI La ruta de combinación no tiene mensajes para escribir en sesiones que no se muestran)
                sessionRepository.attachMediaToMessage(
                    sessionId = sessionId,
                    messageId = assistantId,
                    content = content,
                    imageUrls = imageUrls,
                    videoFileUri = result.videoFileUri,
                    voiceUri = result.voiceUri,
                    voiceDurationMs = result.voiceDurationMs,
                )
            }
        }

        /**
         * Entrega de voz:Se pueden realizar varios disparos en una ronda.,Pero sólo hay un lugar para los mensajes de voz.
         *
         * Si no hay voz en esta ronda de mensajes del asistente, se utilizará la ruta de entrega original.(Espera);Anótalo cuando ya haya un mensaje de voz.
         * sobrescribirá el anterior,Por tanto, a partir del segundo, cada mensaje de voz será transmitido por un asistente de mensajes independiente. —— nuevas noticias
         * Agregar después del mensaje ancla,Los que entregan primero van primero.
         */
        private suspend fun deliverVoice(result: MediaGenResult.Success) {
            val voiceUri = result.voiceUri ?: return
            val content = appContext.getString(generatedRes)
            val displayed = isSessionDisplayed()
            val anchor = anchorMessage(displayed)
            if (anchor?.voiceUri == null) {
                deliverVoiceTo(assistantId, content, voiceUri, result.voiceDurationMs, displayed)
                return
            }
            deliverVoiceAsOwnMessage(anchor, content, voiceUri, result.voiceDurationMs, displayed)
        }

        /**
         * El estado actual del mensaje de ayuda del ancla.:Leer la lista de mensajes de memoria en la pantalla de sesión(Lo último),De lo contrario, busque en la biblioteca por id.
         * (La lista de memoria de una sesión no visualizada pertenece a otra sesión.,No puedo recibir este mensaje).
         */
        private suspend fun anchorMessage(displayed: Boolean): UIMessage? {
            if (displayed) {
                accessor.messagesSnapshot.firstOrNull { it.id == assistantId }?.let { return it }
            }
            return sessionRepository.getMessageAsUiMessage(assistantId.toString())
        }

        /** Escribe una voz al mensaje especificado.(Mostrar combinación de UI + realizar pedidos inmediatamente,De lo contrario, la sesión original se registrará en la base de datos.). */
        private suspend fun deliverVoiceTo(
            messageId: Uuid,
            content: String,
            voiceUri: String,
            durationMs: Long,
            displayed: Boolean,
        ) {
            if (displayed) {
                streamCoordinator.appendMediaToAssistant(
                    id = messageId,
                    content = content,
                    voiceUri = voiceUri,
                    voiceDurationMs = durationMs,
                )
                persistMediaMessage(sessionId, messageId)
                registerMediaMessage(messageId)
            } else {
                sessionRepository.attachMediaToMessage(
                    sessionId = sessionId,
                    messageId = messageId,
                    content = content,
                    voiceUri = voiceUri,
                    voiceDurationMs = durationMs,
                )
            }
        }

        /** Crea un nuevo mensaje de asistente para transmitir esta voz.:El contenido sigue la misma frase que la copia del marcador de posición.,Cuelga debajo de la pregunta dónde está el mensaje ancla. */
        private suspend fun deliverVoiceAsOwnMessage(
            anchor: UIMessage,
            content: String,
            voiceUri: String,
            durationMs: Long,
            displayed: Boolean,
        ) {
            val voiceMessage = UIMessage(
                id = Uuid.random(),
                role = anchor.role,
                content = content,
                createdAt = nextVoiceCreatedAt(displayed, anchor.createdAt),
                parentGroupId = anchor.parentGroupId,
            )
            if (displayed) {
                // Agregar al final del flujo de mensajes = Manténgase al día con los mensajes ancla(La siguiente ronda de ocupación de streaming se agregará después de esta ronda de ejecución de la herramienta.)
                val carried = voiceMessage.copy(voiceUri = voiceUri, voiceDurationMs = durationMs)
                accessor.updateMessages { list -> list + carried }
                persistMediaMessage(sessionId, carried.id)
                registerMediaMessage(carried.id)
                return
            }
            val targetSession = sessionId ?: return
            // sesión sin visualización:Inserta un mensaje primero,Presione id nuevamente para colgar la voz.(El mismo conjunto de capacidades directas que la ruta de la sesión de corte de gráficos sin procesar)
            sessionRepository.appendMessage(targetSession, voiceMessage)
            deliverVoiceTo(voiceMessage.id, content, voiceUri, durationMs, displayed = false)
        }

        /** A-04: Si la sesión que se muestra actualmente sigue siendo la sesión de destino generada esta vez. */
        private fun isSessionDisplayed(): Boolean {
            val active = sessionId ?: return false
            val snapshot = accessor.snapshot
            val displayed = if (snapshot.isAgentMode) snapshot.agentSessionId else snapshot.currentSessionId
            return active == displayed
        }

        private fun failureText(message: String): String = when (kind) {
            MediaGenKind.IMAGE -> appContext.getString(R.string.err_chat_img_gen_failed, message)
            MediaGenKind.VIDEO -> appContext.getString(R.string.err_chat_video_gen_failed, message)
            MediaGenKind.VOICE -> appContext.getString(R.string.err_chat_voice_gen_failed, message)
            // Si el código QR falla, no se escribirá el mensaje del asistente.(Consistente con la implementación anterior):Los resultados de la herramienta incluyen razones.
            MediaGenKind.QR_CODE -> return ""
        }

        private val generatedRes: Int = when (kind) {
            MediaGenKind.IMAGE -> R.string.err_chat_img_generated
            MediaGenKind.VIDEO -> R.string.err_chat_video_generated
            MediaGenKind.QR_CODE -> R.string.err_chat_qr_generated
            MediaGenKind.VOICE -> R.string.err_chat_voice_generated
        }

        private val cancelRes: Int = when (kind) {
            MediaGenKind.IMAGE -> R.string.err_chat_img_cancelled
            MediaGenKind.VIDEO -> R.string.err_chat_video_cancelled
            MediaGenKind.QR_CODE -> R.string.err_chat_unknown
            MediaGenKind.VOICE -> R.string.err_chat_voice_cancelled
        }
    }

    /** El último mensaje de voz nuevo de esta generación createdAt(punto de partida monótonamente creciente). */
    private var lastVoiceCreatedAt = 0L

    /**
     * Los nuevos mensajes de voz se crean estrictamente después de los mensajes existentes.
     *
     * La clasificación de memoria utiliza createAt para descubrir cuándo falta la secuencia.,Se devolverán dos apuestas consecutivas dentro del mismo milisegundo. Ordenar por identificación.,
     * No coincide con el pedido de entrega;Aquí se garantiza un aumento monótono.
     */
    @Synchronized
    private fun nextVoiceCreatedAt(displayed: Boolean, anchorCreatedAt: Long): Long {
        val tail = if (displayed) accessor.messagesSnapshot.lastOrNull()?.createdAt ?: 0L else 0L
        val createdAt = maxOf(System.currentTimeMillis(), maxOf(anchorCreatedAt, tail, lastVoiceCreatedAt) + 1)
        lastVoiceCreatedAt = createdAt
        return createdAt
    }

    private companion object {
        const val TAG = "ChatMediaGenHost"
    }
}
