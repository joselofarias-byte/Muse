package io.zer0.muse.miniphone.ui.chat

import android.net.Uri
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Envía mensajes y recibe respuestas.
 *
 * Si es un usuario, inicie sesión primero y luego pregunte sobre el rol.:Incluso si la solicitud falla, no desaparecerá de la lista.;La entrada en blanco no produce ninguna acción.
 * Por favor marque "Dependiente" al colocarlo en stock.,Obtenga la respuesta y cámbiela a "enviado",Al final de la ronda, sin terminar una frase, se cambió a "Error al enviar".——
 * En la interfaz, puedes señalar ese elemento y reenviarlo nuevamente.,No es necesario volver a escribir.
 *
 * El personaje responde y se va. [replyQueue] de consumidores individuales —— Puedes seguir enviando mensajes durante el proceso de generación.,Pero una ronda de respuesta
 * siempre hay solo uno `respond` Correr.
 *
 * Un mensaje de usuario es respondido por varios roles. [respondersFor] determinado:El mensaje privado siempre es el papel de la conversación.;
 * Los chats grupales se basan en pasar lista, conversaciones recientes y selección aleatoria estable. [MAX_GROUP_RESPONDERS] personales(ver
 * [selectGroupResponders]). dos personas**de serie**decir:Cuando este último abrió la boca, las palabras del anterior ya habían sido olvidadas.,
 * También está en la historia que recibió.(ver [chatHistoryOf]). Deja una pausa en el medio,[pauseBetweenResponders]
 * Inyectable,Las pruebas lo utilizan para fijar el ritmo.
 *
 * El mismo mensaje sólo se puede reclamar una vez:[claimedRounds] Bloquear reintentos y múltiples llamadas;Enviado nuevamente mientras aún se estaba ejecutando la última ronda.
 * exactamente la misma frase,tratado como envío duplicado,No más empezar otra ronda.
 *
 * @param writer La única entrada a Luoku.
 * @param media Guardar y registrar imágenes:La publicación de imágenes solo es responsable de "qué imágenes han sido seleccionadas".
 * @param state Estado de la interfaz;Esta categoría sólo escribe "generando", "quién escribe" y "qué está haciendo el personaje".,El título y el avatar son administrados por ViewModel
 * @param pauseBetweenResponders pausa entre dos personajes;Predeterminado 600–1200ms,Inyectar una implementación vacía permite que la prueba no espere
 */
internal class ChatSendController(
    private val threadId: String,
    private val writer: ChatMessageWriter,
    private val threadDao: MpThreadDao,
    private val host: MiniPhoneHost,
    private val media: ChatMediaWriter,
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val pauseBetweenResponders: suspend () -> Unit = { delay(responderGapMillis()) },
    private val groupCandidates: suspend () -> List<GroupResponderCandidate> = { emptyList() },
) {

    /** Respuesta a generar:Consumo secuencial de un solo consumidor,[Channel.UNLIMITED] No espere una respuesta antes de enviar. */
    private val replyQueue = Channel<PendingReply>(Channel.UNLIMITED)

    /** Número de rondas de respuesta en cola,Determinar \"La otra parte está escribiendo…\"Cuándo guardarlo\". */
    private var pendingReplies: Int = 0

    /** ID de mensaje del usuario que ha reclamado la respuesta: el mismo mensaje no se enviará dos veces debido a reenvíos o llamadas múltiples. */
    private val claimedRounds: MutableSet<String> = mutableSetOf()

    /** Comience a consumir la cola de respuestas;ViewModel Ajústelo una vez después de conectar el estado. */
    fun start() {
        scope.launch { consumeReplies() }
    }

    /** ViewModel Terminar cuando sea destruido:cerrar la cola,El ciclo de consumo termina. */
    fun close() {
        replyQueue.close()
    }

    /**
     * Envía un mensaje de texto.
     *
     * Primero abandone la biblioteca y pregunte sobre el rol más tarde.:Los mensajes de usuario no dependen de los resultados de la red. La entrada en blanco no produce ninguna acción,
     * La apariencia gris de la interfaz es solo un recordatorio visual.
     */
    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        scope.launch {
            val thread = currentThread()
            val messageId = writer.append(
                ChatOutgoingMessage(
                    threadId = threadId,
                    characterId = storedCharacterIdOf(thread),
                    sender = MpActorType.USER,
                    type = MpMessageType.TEXT,
                    content = content,
                    status = MpMessageStatus.SENDING,
                ),
            )
            // Antes de que terminara la última ronda, volví a enviar exactamente la misma frase.:Tratar como envío duplicado,No más rondas
            if (isDuplicateSubmission(content, messageId)) {
                writer.updateStatus(messageId, MpMessageStatus.SENT)
                return@launch
            }
            val started = startRound(messageId, respondersFor(content, messageId))
            when (started) {
                // La página está siendo destruida.:La cola está cerrada.,Ese se ha marcado como que no se pudo enviar en startRound.
                RoundStart.STARTED, RoundStart.QUEUE_CLOSED -> Unit
                // Envío duplicado o nadie podrá aceptarlo.:Este artículo ha sido eliminado.,No es necesario colgar "Enviando"
                RoundStart.ALREADY_STARTED, RoundStart.NO_RESPONDER ->
                    writer.updateStatus(messageId, MpMessageStatus.SENT)
            }
        }
    }

    /**
     * Reenviar un mensaje de texto que enviaste.
     *
     * Vuelva a la misma línea que "Enviando" y revísela nuevamente para responder.,Vuelva a cambiarlo a "Emitido" después del éxito.—— No habrá más en la lista.
     * Una burbuja idéntica. Solo acepta mensajes de texto tuyos,resto de noticias(Personajes, imágenes, voces.)Simplemente ignóralo.
     * Los mensajes que hayan sido respondidos en una ronda no se reenviarán ni se ejecutarán nuevamente.:Reclamar y ponerse al frente,El estado no se detendrá en "Enviando".
     */
    fun resend(messageId: String) {
        val message = state.value.messages.firstOrNull { it.id == messageId } ?: return
        if (!message.canResend) return
        scope.launch {
            if (startRound(messageId, respondersFor(message.content, messageId)) != RoundStart.STARTED) return@launch
            writer.updateStatus(messageId, MpMessageStatus.SENDING)
        }
    }

    /**
     * envía algunas fotos,Suelte uno en orden de selección.
     *
     * Publicar fotos**No**Solicitar respuesta de rol:El modelo en el enlace actual no puede ver la imagen todavía.,Una respuesta dura sólo será irrelevante.,
     * Espere hasta que se conecte la multimodalidad antes de abrir este paso. La tarjeta que no puedo guardar [ChatMediaWriter] saltar,No afecta a las imágenes restantes.
     */
    fun sendImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch { media.storePickedImages(uris, storedCharacterIdOf(currentThread())) }
    }

    /** Línea de conversación de Curry:Los oradores y las etiquetas de grupo se basan en él.,Puede obtenerlo incluso si el estado aún no está completo. */
    private suspend fun currentThread(): MpThreadEntity? = threadDao.getThread(threadId)

    /** La identificación del rol se toma del estado actual.;Si el estado no ha sido completado, pregunte a la base de datos una vez.,Evite mensajes colgados de roles vacíos. */
    private suspend fun resolveCharacterId(): String =
        state.value.characterId.ifEmpty { threadDao.getThread(threadId)?.characterId.orEmpty() }

    /**
     * Quién debe responder a este mensaje de usuario.
     *
     * El mensaje privado solo tiene una persona en la conversación —— Este cambio no convertirá las respuestas individuales en respuestas multijugador.
     * Déjalo en manos del grupo. [selectGroupResponders]:Pasar lista, hablado recientemente, estable y aleatorio,Máximo dos.
     */
    private suspend fun respondersFor(content: String, messageId: String): List<String> {
        val current = state.value
        if (!current.isGroup) return listOfNotNull(resolveCharacterId().takeIf(String::isNotEmpty))
        val members = current.members
            .map { GroupResponderCandidate(characterId = it.characterId, name = it.name) }
            // Cuando acabas de unirte al grupo y los miembros aún no han aparecido en la interfaz,Compénsalo una vez desde la biblioteca.,No podemos dejar que este mensaje quede sin respuesta por esto.
            .ifEmpty { groupCandidates() }
        return selectGroupResponders(
            members = members,
            text = content,
            recentSenderIds = recentSpeakerIdsOf(current.messages),
            seed = responderSeedOf(threadId, messageId),
        )
    }

    /** ¿Es una presentación repetida?:Ya hay uno con la misma frase, todavía esperando respuesta. */
    private fun isDuplicateSubmission(content: String, messageId: String): Boolean = state.value.messages.any { message ->
        message.isMine && message.id != messageId && message.content == content && message.status == MpMessageStatus.SENDING
    }

    /**
     * Reclama la respuesta a un mensaje y lo pone en cola.
     *
     * @return Reclamó con éxito y se unió al equipo.;[RoundStart.ALREADY_STARTED] Indica que este mensaje ha sido;
     *   [RoundStart.QUEUE_CLOSED] Indica que la página está siendo destruida.,Ese ha sido marcado como "Error al enviar"
     */
    private suspend fun startRound(messageId: String, responders: List<String>): RoundStart {
        if (!claimedRounds.add(messageId)) return RoundStart.ALREADY_STARTED
        if (responders.isEmpty()) {
            claimedRounds.remove(messageId)
            return RoundStart.NO_RESPONDER
        }
        pendingReplies += 1
        state.update { it.copy(isGenerating = true) }
        if (replyQueue.trySend(PendingReply(messageId = messageId, responders = responders)).isSuccess) {
            return RoundStart.STARTED
        }
        // La cola está cerrada.(La pagina esta siendo destruida):Éste no recibirá respuesta ni esperará a la siguiente ronda.,Falló la marca directa
        claimedRounds.remove(messageId)
        writer.updateStatus(messageId, MpMessageStatus.FAILED)
        settleReply()
        return RoundStart.QUEUE_CLOSED
    }

    private suspend fun consumeReplies() {
        for (reply in replyQueue) {
            try {
                deliverRound(reply)
            } finally {
                settleReply()
            }
        }
    }

    /** Finaliza una ronda de respuesta(El éxito, el fracaso y la cancelación cuentan):salir de la cola,Si no hay nadie en la fila, guarde el mensaje de entrada. */
    private fun settleReply() {
        pendingReplies -= 1
        state.update { it.copy(isGenerating = pendingReplies > 0, typingName = null, typingAvatarUri = null) }
    }

    /**
     * una ronda de respuesta:Deje que los personajes reclamados hablen por turnos.
     *
     * el orden es importante —— Es el turno de la siguiente persona después del párrafo anterior.,Aquí termina el choque de "dos personas hablando al mismo tiempo";
     * La pausa en el medio crea un ritmo de escritura entre las dos burbujas.,No aparecen dos mensajes al mismo tiempo.
     * ¿Quién está escribiendo? [showTyping] escribir estado,La interfaz reemplaza el nombre y el avatar de la línea "Entrada" en consecuencia.
     */
    private suspend fun deliverRound(reply: PendingReply) {
        var delivered = 0
        reply.responders.forEachIndexed { index, characterId ->
            if (index > 0) pauseBetweenResponders()
            showTyping(characterId)
            // El acuerdo de anfitrión falló Failed;una capa mas,Esto evita que una implementación genere una excepción y detenga toda la cola.
            delivered += runCatching { collectReply(characterId) }
                .onFailure { state.update { current -> current.copy(activity = null) } }
                .getOrDefault(0)
        }
        state.update { it.copy(typingName = null, typingAvatarUri = null) }
        if (delivered == 0) {
            // Todavía tiene sentido intentarlo de nuevo esta vez.:Volver a reclamar,El reenvío puede comenzar nuevamente desde el lugar original.
            claimedRounds.remove(reply.messageId)
            writer.updateStatus(reply.messageId, MpMessageStatus.FAILED)
            writer.append(
                ChatOutgoingMessage(
                    threadId = threadId,
                    characterId = reply.responders.firstOrNull().orEmpty(),
                    sender = MpActorType.SYSTEM,
                    type = MpMessageType.SYSTEM,
                    content = NO_REPLY_TEXT,
                ),
            )
        } else {
            writer.updateStatus(reply.messageId, MpMessageStatus.SENT)
        }
    }

    /** ¿Quién debería decir lo siguiente?:El nombre y el avatar se toman de la lista de miembros.,Los mensajes privados degeneran unos en otros. */
    private fun showTyping(characterId: String) {
        val member = state.value.members.firstOrNull { it.characterId == characterId }
        state.update { current ->
            current.copy(
                typingName = member?.name ?: current.peerName.takeIf(String::isNotEmpty),
                typingAvatarUri = if (member != null) member.avatarUri else current.peerAvatarUri,
            )
        }
    }

    /**
     * Completa una ronda de generación de un personaje.,Regresé esta vez y dejé algunos mensajes.
     *
     * La actividad en el medio solo cambia la línea de redacción en la interfaz.,No entrar en la lista de mensajes;Cuando termine, sin importar el éxito o el fracaso, guárdelo,
     * Para evitar que esa línea se atasque en la interfaz cuando falla. historia en**antes de cada solicitud**Retirar ahora:La biblioteca de la que acaba de salir la última persona está dentro.
     */
    private suspend fun collectReply(characterId: String): Int {
        var stored = 0
        try {
            host.respond(characterId, chatHistoryOf(state.value)).collect { event ->
                when (event) {
                    is MpReplyEvent.Activity -> state.update { it.copy(activity = event.kind) }
                    is MpReplyEvent.Text -> if (storeText(characterId, event.content)) stored += 1
                    is MpReplyEvent.Voice -> {
                        storeVoice(characterId, event)
                        stored += 1
                    }
                    // Si fallas esta ronda, no perderás nada.,Determinar si se deben compensar las indicaciones del sistema según la cantidad de entradas
                    MpReplyEvent.Failed -> Unit
                }
            }
        } finally {
            // Cierra esa línea para los tres caminos del éxito, el fracaso y la cancelación.,No dejes ninguna pista en la interfaz.
            state.update { it.copy(activity = null) }
        }
        return stored
    }

    /** Suelta un texto de carácter;No queda ningún párrafo en blanco(El modelo ocasionalmente solo da una nueva línea.,No es necesario ocupar una línea). */
    private suspend fun storeText(characterId: String, content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return false
        writer.append(
            ChatOutgoingMessage(
                threadId = threadId,
                characterId = characterId,
                sender = MpActorType.CHARACTER,
                type = MpMessageType.TEXT,
                content = text,
            ),
        )
        return true
    }

    /**
     * Deja la voz de un personaje:Solo se guarda la copia preliminar del texto principal.,La referencia del audio y la duración ingresan en sus propios campos.
     *
     * Voz del lado del usuario(Grabación + Transcripción)Aún no recogido,Después de conectarse, siga el mismo camino para escribir,Simplemente cambie el remitente y la fuente de audio.
     */
    private suspend fun storeVoice(characterId: String, voice: MpReplyEvent.Voice) {
        writer.append(
            ChatOutgoingMessage(
                threadId = threadId,
                characterId = characterId,
                sender = MpActorType.CHARACTER,
                type = MpMessageType.VOICE,
                content = VOICE_PREVIEW_TEXT,
                mediaUri = voice.uri,
                voiceDurationMs = voice.durationMs,
            ),
        )
    }

    /** El resultado del reclamo de respuesta de un mensaje. */
    private enum class RoundStart {
        /** Reclamados y en cola. */
        STARTED,

        /** Este mensaje ha sido reclamado antes.:No más carreras de segunda ronda. */
        ALREADY_STARTED,

        /** No hay nadie para contestar el teléfono. */
        NO_RESPONDER,

        /** La cola está cerrada.,La página está siendo destruida. */
        QUEUE_CLOSED,
    }

    /** Se generará una respuesta en cola;[messageId] síguelo,Confíe en él para escribir el estado de envío cuando reciba la respuesta. */
    private data class PendingReply(
        val messageId: String,
        val responders: List<String>,
    )

    private companion object {
        /** Un mensaje del sistema que aparece en la lista cuando el personaje no responde. */
        const val NO_REPLY_TEXT: String = "La otra parte no respondió."

        /** Texto del mensaje de voz:El audio no se almacena en la biblioteca.,Guarde solo esta copia preliminar,Se utiliza tanto para la lista de conversaciones como para la vista previa de no leídos. */
        const val VOICE_PREVIEW_TEXT: String = "[Voz]"
    }
}

/** Los límites inferior y superior de pausa entre dos personajes.(milisegundos). */
internal const val RESPONDER_GAP_MIN_MS: Long = 600L
internal const val RESPONDER_GAP_MAX_MS: Long = 1_200L

/** hacer una pausa:Cuánto tiempo hay que esperar entre que la última persona termine de hablar y la siguiente persona que hable. */
internal fun responderGapMillis(): Long = Random.nextLong(RESPONDER_GAP_MIN_MS, RESPONDER_GAP_MAX_MS + 1)

/**
 * El ID de carácter del mensaje de usuario cuando se coloca en la base de datos.
 *
 * Recuerda el papel en la conversación en el mensaje privado. —— Sólo hay otra persona en la conversación.,Esta columna se utiliza como "¿A quién pertenece este artículo?"
 * Recuerda la cadena vacía en el grupo.:eres tu quien habla,No corresponde a ningún rol;Si está registrado como el primer miembro,En noticias históricas
 * "Dije" se leerá como "Él dijo". ¿Quién respondió la llamada? [selectGroupResponders] Elige otro.
 */
internal fun storedCharacterIdOf(thread: MpThreadEntity?): String = if (thread?.isGroup == true) "" else thread?.characterId.orEmpty()
