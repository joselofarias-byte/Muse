package io.zer0.muse.miniphone.data.moment

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import java.time.Instant
import java.time.ZoneId

/**
 * "Qué pasó recientemente" compartido por contenido espontáneo de los personajes (actualizaciones de momentos/mensajes privados activos).
 *
 * El contexto de ambos contenidos es exactamente el mismo. —— ¿Qué chateaste con el usuario recientemente y qué hora es ahora? Sólo hay dos diferencias:
 * La publicación tendrá una "última publicación que publiqué" adicional como referencia, y las instrucciones de cierre también estarán redactadas de manera diferente ("Puedo escribir esta vez"./"El motivo de este momento").
 * Por lo tanto, sólo hay un montaje: el orden se fija como "contexto → tiempo → "Comando de finalización", el comando de cierre siempre está en la última frase.
 *
 * El material en sí se selecciona dentro del módulo, y el presentador sólo es responsable de convertirlo en un párrafo del personaje. —— Sabe en qué mesa se encuentra la sesión,
 * El presentador no necesita saber el orden en el que se organizan las dinámicas.
 *
 * Las tres piezas de material son**Opcional**: No hay conversación, no se han enviado actualizaciones, la biblioteca no puede leerlo, a todos les falta un párrafo.
 * No dejaré que nada salga en esta ronda.
 *
 * La persona que llama proporciona la hora y la zona horaria (valores predeterminados del sistema), por lo que "Qué decir a las nueve en punto el martes por la noche" puede ser
 * Clavado en pura prueba.
 *
 * @param now Fuente de hora, reloj del sistema predeterminado
 * @param zone La zona horaria utilizada para determinar "qué hora es ahora"
 */
class MpCharacterMaterial(
    private val threadDao: MpThreadDao,
    private val messageDao: MpMessageDao,
    private val momentDao: MpMomentDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Un material dinámico para un círculo de amigos.
     *
     * @param characterId Un rol dinámico para publicar
     * @param focus El origen de esta ronda (mensaje de período de tiempo, etc.); si está vacío, termina con el tiempo.
     */
    suspend fun forMoment(characterId: String, focus: String? = null): String = compose(characterId, focus, Kind.MOMENT)

    /**
     * El material de un mensaje privado activo: el mismo contexto que la actualización, pero sin la "última actualización que envié"——
     * Los mensajes privados no son una secuela de Moments. Usar las actualizaciones como referencia sólo hará que los mensajes privados pierdan el sentido.
     *
     * @param characterId El personaje que quiere hablar.
     * @param focus El origen de esta ronda (mensaje de período de tiempo, etc.); si está vacío, termina con el tiempo.
     */
    suspend fun forProactiveChat(characterId: String, focus: String? = null): String = compose(characterId, focus, Kind.CHAT)

    /** Sólo hay dos diferencias entre los dos tipos de contenido: si se incluye una dinámica y cómo se llama la instrucción final. */
    private enum class Kind(val focusLabel: String, val includesLastMoment: Boolean) {
        MOMENT(FOCUS_LABEL_MOMENT, includesLastMoment = true),
        CHAT(FOCUS_LABEL_CHAT, includesLastMoment = false),
    }

    /** La única pieza del rompecabezas: el contexto → tiempo → Instrucciones de cierre, la secuencia es común a ambas categorías. */
    private suspend fun compose(characterId: String, focus: String?, kind: Kind): String = buildString {
        appendLine("Conversaciones recientes con usuarios (antiguas→nuevo):")
        val turns = recentTurns(characterId)
        if (turns.isEmpty()) {
            appendLine("Aún no he chateado con el usuario.")
        } else {
            turns.forEach { turn -> appendLine(turn) }
        }
        if (kind.includesLastMoment) {
            lastMoment(characterId)?.let { own ->
                appendLine("La última actualización que publiqué:$own")
                appendLine("No repitas el mismo tema del anterior.")
            }
        }
        appendLine(timeLine())
        focus?.trim()?.takeIf { it.isNotBlank() }?.let { brief -> appendLine("${kind.focusLabel}$brief") }
    }.trim()

    /**
     * Varias conversaciones recientes, en orden cronológico, cada una en forma de `Usuario:…` / `Yo:…`.
     *
     * Las indicaciones del sistema (como "la otra parte no respondió") no se consideran conversaciones y se filtrarán después de ser recuperadas; el texto está cortado
     * [MAX_TURN_CHARS] Deja sólo la parte donde puedas notar de qué se habla.
     */
    private suspend fun recentTurns(characterId: String): List<String> {
        val threadId = resultOf { threadDao.findThreadByCharacter(characterId) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo leer la conversación de mensaje privado: ${t?.message ?: msg}") }
            .getOrNull()
            ?.id
            ?: return emptyList()
        val newestFirst = resultOf { messageDao.pageMessages(threadId, RECENT_TURN_LIMIT, 0) }
            .onError { msg, t -> Logger.w(TAG, "No se pudieron leer los registros de mensajes privados: ${t?.message ?: msg}") }
            .getOrNull()
            .orEmpty()
        return newestFirst.asReversed()
            .filter { message -> message.sender != MpActorType.SYSTEM && message.content.isNotBlank() }
            .map { message -> "${speakerOf(message)}:${message.content.trim().take(MAX_TURN_CHARS)}" }
    }

    /** La última actualización que publiqué, a partir de [MAX_LAST_MOMENT_CHARS] palabra; si no ha sido enviado es nulo. */
    private suspend fun lastMoment(characterId: String): String? = resultOf { momentDao.lastMomentByAuthor(characterId) }
        .onError { msg, t -> Logger.w(TAG, "No se pudo leer la actualización anterior: ${t?.message ?: msg}") }
        .getOrNull()
        ?.content
        ?.trim()
        ?.take(MAX_LAST_MOMENT_CHARS)
        ?.takeIf { it.isNotBlank() }

    /** \"Son las nueve (noche) del martes por la noche\": día de la semana + declaración del período de tiempo + hora exacta, con el nombre del período de tiempo utilizado para la determinación al final. */
    private fun timeLine(): String {
        val at = Instant.ofEpochMilli(now()).atZone(zone)
        val hour = at.hour
        val hourWord = HOUR_WORDS[if (hour % 12 == 0) 12 else hour % 12]
        // "Las dos en punto" no son "las dos en punto":El 2 en punto se lee como dos.
        val spokenHour = if (hourWord == "dos") "dos" else hourWord
        return "ahora es${WEEKDAY_WORDS[at.dayOfWeek.value - 1]}${clockPrefixOf(hour)}$spokenHour" +
            "punto (${phaseOf(hour)})."
    }

    private fun speakerOf(message: MpMessageEntity): String = if (message.sender == MpActorType.USER) "Usuario" else "yo"

    /** Discurso para un reloj: se lee como el habla humana (\"las nueve de la noche\" en lugar de \"21 punto\"). */
    private fun clockPrefixOf(hour: Int): String = when (hour) {
        in 5..7 -> "temprano en la mañana"
        in 8..11 -> "mañana"
        12, 13 -> "mediodía"
        in 14..17 -> "tarde"
        in 18..19 -> "tarde"
        in 20..22 -> "noche"
        else -> "tarde en la noche"
    }

    /** Nombres de períodos de tiempo utilizados para la determinación: mañana/mañana/mediodía/tarde/tarde/noche/tarde en la noche. */
    private fun phaseOf(hour: Int): String = when (hour) {
        in 5..7 -> "temprano"
        in 8..10 -> "mañana"
        in 11..13 -> "mediodía"
        in 14..17 -> "tarde"
        in 18..19 -> "tarde"
        in 20..22 -> "noche"
        else -> "tarde en la noche"
    }

    private companion object {
        const val TAG: String = "MiniPhoneMaterial"

        /** La redacción de las instrucciones de cierre: el círculo de amigos decía \"puedo escribir esta vez\", y el mensaje privado decía \"el motivo de este momento\". */
        const val FOCUS_LABEL_MOMENT: String = "Esta vez puedes escribir:"
        const val FOCUS_LABEL_CHAT: String = "El motivo de este momento:"

        /** Algunas conversaciones recientes: ninguna cantidad de ellas caben en una actualización de 30 a 80 palabras. */
        const val RECENT_TURN_LIMIT: Int = 8

        /** La duración truncada de una sola conversación. */
        const val MAX_TURN_CHARS: Int = 100

        /** La duración truncada de la dinámica anterior: lo justo para identificar el tema. */
        const val MAX_LAST_MOMENT_CHARS: Int = 60

        val WEEKDAY_WORDS: List<String> = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "Sábado", "domingo")

        /** 1..12 Versión china de (subíndice 0 marcador de posición). */
        val HOUR_WORDS: List<String> = listOf(
            "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce",
        )
    }
}
