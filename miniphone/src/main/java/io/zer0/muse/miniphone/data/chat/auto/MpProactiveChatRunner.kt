package io.zer0.muse.miniphone.data.chat.auto

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.ui.chat.ChatMessageWriter
import io.zer0.muse.miniphone.ui.chat.ChatOutgoingMessage
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/** El resultado de una ronda de mensajes privados proactivos:Enviado, omitido o fallido. Sentencias que se pueden ver en el registro,También se puede afirmar aquí. */
sealed interface MpProactiveChatOutcome {
    /** Mensaje eliminado exitosamente,Y la conversación no está leída +1. */
    data class Sent(val threadId: String, val characterId: String) : MpProactiveChatOutcome

    /** Juzgado como no enviado(o generado como vacío),ver la razón [reason]. */
    data class Skipped(val reason: MpProactiveChatSkipReason) : MpProactiveChatOutcome

    /** Fallos graves como lectura de configuraciones/caída de la biblioteca;[reason] ¿Es el valor escrito en el registro? */
    data class Failed(val reason: String) : MpProactiveChatOutcome
}

/**
 * Un proceso completo de "dejar que el personaje tome la iniciativa de enviar un mensaje privado".
 *
 * secuencia con [MpProactiveChatPlan.decide] consistente:Primero seleccione la conversación y obtenga la "hora en la que el usuario habló por última vez".,Ocho sentencias más,
 * Una vez completado el juicio, seleccione el personaje, ajuste la generación del anfitrión, colóquelo en la biblioteca y registre el tiempo.
 * falló**no lo vuelvas a intentar** —— Los mensajes proactivos no deben interrumpirse repetidamente debido a una falla.,Naturalmente, volverá el próximo ciclo.
 *
 * "Conversaciones activas recientemente"= El último y último mensaje de la conversación con mensajes.:El personaje tomó la línea de la que realmente hablaron.,
 * No abrirás uno nuevo de la nada. El usuario acaba de hablar en esa conversación.(10 en minutos)No hay tiros durante toda la ronda. —— Interrumpir es interrumpir.
 *
 * La persona que llama entrega la hora, la zona horaria, la fuente aleatoria y la clave principal.(El valor predeterminado es el valor del sistema),Entonces este proceso se puede ejecutar completamente en la prueba.
 *
 * El material de apertura es de [material] luchar:¿De qué hemos hablado recientemente? ¿Qué hora es ahora?,Ninguno de ellos es buscado en esta categoría. ——
 * Lo que dice el personaje debería ser como la continuación de lo anterior.,En lugar de simplemente decir algo de la nada.
 *
 * @param now fuente de tiempo,Reloj del sistema predeterminado
 * @param random dados de probabilidad,una ronda a la vez,La prueba se puede clavar
 * @param zone La zona horaria utilizada para determinar "qué hora es ahora"
 * @param newId Generador de claves primarias de mensajes,Predeterminado UUID
 */
class MpProactiveChatRunner(
    private val host: MiniPhoneHost,
    private val threadDao: MpThreadDao,
    private val messageDao: MpMessageDao,
    private val store: MpMomentAutoStore,
    private val material: MpCharacterMaterial,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: () -> Float = { Random.nextFloat() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** Corre una ronda. Puede fallar pero**No lanzar excepción**:Todas las excepciones están normalizadas. [MpProactiveChatOutcome]. */
    suspend fun run(): MpProactiveChatOutcome {
        val at = now()
        val enabled = resultOf { store.isChatProactiveEnabled() }
            .onError { msg, t -> fail("settings_unreadable", t, msg) }
            .getOrNull()
            ?: return MpProactiveChatOutcome.Failed(FAIL_SETTINGS)
        val lastProactiveAt = resultOf { store.lastProactiveChatAt() }
            .onError { msg, t -> fail("settings_unreadable", t, msg) }
            .getOrNull()
        val characters = resultOf { host.observeCharacters().first() }
            .onError { msg, t -> fail("characters_unreadable", t, msg) }
            .getOrNull()
            .orEmpty()
        val thread = latestActiveThread()
        val lastUserMessageAt = thread?.let { lastUserMessageAt(it.id) }
        val decision = MpProactiveChatPlan.decide(
            nowMillis = at,
            enabled = enabled,
            lastUserMessageAtMillis = lastUserMessageAt,
            characterCount = characters.size,
            hasEligibleSession = thread != null,
            lastProactiveAtMillis = lastProactiveAt,
            random = random,
            zone = zone,
        )
        return when (decision) {
            is MpProactiveChatDecision.Skip -> skip(decision.reason)
            MpProactiveChatDecision.Send -> send(characters, thread, at)
        }
    }

    /** Juzgado como no enviado:Deje un registro que muestre el motivo.,Entonces se acabó. */
    private fun skip(reason: MpProactiveChatSkipReason): MpProactiveChatOutcome {
        Logger.d(TAG, "Saltar mensajes privados activos | reason=${reason.value}")
        return MpProactiveChatOutcome.Skipped(reason)
    }

    /** Elige un papel → Deja que el anfitrión genere → Cayó en la biblioteca → no leído +1 → Tome nota de la hora esta vez. Si algún paso no funciona, detente. */
    private suspend fun send(characters: List<MpCharacter>, thread: MpThreadEntity?, at: Long): MpProactiveChatOutcome {
        val target = thread ?: return skip(MpProactiveChatSkipReason.NO_HISTORY)
        // Es posible que los personajes de la sesión se hayan eliminado en el lado del anfitrión.:Entonces no habrá quien pueda hablar.
        val character = characters.firstOrNull { it.id == target.characterId }
        // La salida en blanco no cae en la biblioteca:Un mensaje vacío es peor que ningún mensaje,Y se comerá el intervalo de 4 horas en vano.
        val content = if (character == null) null else compose(character, at)
        return when {
            character == null -> skip(MpProactiveChatSkipReason.NO_CHARACTER)
            content.isNullOrBlank() -> {
                Logger.w(TAG, "Saltar mensajes privados activos | reason=${MpProactiveChatSkipReason.NO_CONTENT.value}")
                MpProactiveChatOutcome.Skipped(MpProactiveChatSkipReason.NO_CONTENT)
            }

            !write(target, content) -> MpProactiveChatOutcome.Failed(FAIL_WRITE)
            else -> sent(target, content, at)
        }
    }

    /** generar una oración:El material muestra de qué hablamos recientemente y qué hora es ahora.,La última frase es el motivo de este momento.;Devuelve nulo en caso de error. */
    private suspend fun compose(character: MpCharacter, at: Long): String? {
        val hint = resultOf { material.forProactiveChat(character.id, focusFor(at)) }
            .onError { msg, t -> fail("material_error", t, msg) }
            .getOrNull()
        return resultOf { host.composeProactiveMessage(character.id, hint) }
            .onError { msg, t -> fail("compose_error", t, msg) }
            .getOrNull()
    }

    /** Descartado + No leído +1. Ambos pasos son posibles,O tratar esta ronda como un fracaso(Se regenerará en la siguiente ronda.). */
    private suspend fun write(thread: MpThreadEntity, content: String): Boolean {
        val appended = resultOf {
            ChatMessageWriter(
                threadId = thread.id,
                messageDao = messageDao,
                threadDao = threadDao,
                now = now,
                newId = newId,
                // No hay ninguna interfaz colgada en segundo plano.:La propia interfaz recibe este mensaje de Room's Flow.,No se requiere devolución de llamada
                onStored = {},
            ).append(
                ChatOutgoingMessage(
                    threadId = thread.id,
                    characterId = thread.characterId,
                    sender = MpActorType.CHARACTER,
                    type = MpMessageType.TEXT,
                    content = content,
                ),
            )
            threadDao.increaseUnread(thread.id)
        }.onError { msg, t -> fail("insert_error", t, msg) }
        return appended.isSuccess
    }

    /** Sólo recuerdo el momento después de que lo envié.:No se registrarán las fallas al generar y colocar en la biblioteca.,Podemos volver a hacerlo en la próxima ronda. */
    private suspend fun sent(thread: MpThreadEntity, content: String, at: Long): MpProactiveChatOutcome {
        // No escribir marcas de tiempo no afecta los mensajes que se han colocado en la biblioteca:Como máximo, se necesitará una protección antivibración menos para la siguiente ronda.
        resultOf { store.markProactiveChat(at) }
            .onError { msg, t -> fail("settings_write", t, msg) }
        Logger.d(TAG, "Enviar activamente mensajes privados a la base de datos. | threadId=${thread.id} | characterId=${thread.characterId} | len=${content.length}")
        return MpProactiveChatOutcome.Sent(threadId = thread.id, characterId = thread.characterId)
    }

    /**
     * El último y último mensaje de la conversación con mensajes.;Una conversación sin un solo mensaje no se considera "conversable".
     *
     * Las conversaciones grupales siempre se omiten:Un mensaje privado activo es "Cierto personaje te busca solo",El ID de personaje de la sesión grupal es solo el primer miembro. ——
     * Si lo usas para hablar, insertarás una oración como persona del grupo que no tiene nada que ver con el contexto.
     */
    private suspend fun latestActiveThread(): MpThreadEntity? {
        val threads = resultOf { threadDao.observeThreads().first() }
            .onError { msg, t -> fail("threads_unreadable", t, msg) }
            .getOrNull()
        return threads?.filter { it.lastMessageAt > 0L && !it.isGroup }?.maxByOrNull { it.lastMessageAt }
    }

    /** Seleccione la hora del último mensaje \"enviado por el usuario\" en la conversación;Si no, nulo. */
    private suspend fun lastUserMessageAt(threadId: String): Long? {
        val sent = resultOf { messageDao.lastMessageAt(threadId, MpActorType.USER.value) }
            .onError { msg, t -> fail("messages_unreadable", t, msg) }
            .getOrNull()
        return sent?.takeIf { it > 0L }
    }

    /** Da una razón para hablar por horas.,Entrégaselo al generador del anfitrión como instrucción final para el material. */
    private fun focusFor(atMillis: Long): String = when (MpProactiveChatPlan.hourOf(atMillis, zone)) {
        in 8..10 -> "Recién me desperté por la mañana,Saluda casualmente"
        in 11..13 -> "descanso del mediodía,¿Preguntar si has comido?"
        in 14..17 -> "Pensar el uno en el otro por la tarde,tener una pequeña charla"
        else -> "noche,Charla casualmente"
    }

    private fun fail(reason: String, throwable: Throwable?, message: String) {
        Logger.w(TAG, "Error en el mensaje privado no solicitado | reason=$reason | ${throwable?.message ?: message}", throwable)
    }

    private companion object {
        const val TAG: String = "MiniPhoneChat"

        const val FAIL_SETTINGS: String = "settings_unreadable"

        const val FAIL_WRITE: String = "insert_error"
    }
}
