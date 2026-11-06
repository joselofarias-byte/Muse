package io.zer0.muse.miniphone.ui.chat

import androidx.compose.runtime.Immutable
import io.zer0.muse.miniphone.data.db.MpActorType
import kotlin.random.Random

/** Un mensaje de usuario puede responderse con un máximo de varios caracteres.:Más simplemente deslizará la pantalla. */
internal const val MAX_GROUP_RESPONDERS: Int = 2

/** \"Últimamente hablado\" solo analiza una cantidad limitada de mensajes recientes. */
internal const val GROUP_RECENT_SPEAKER_WINDOW: Int = 10

/** Sólo se permiten los dos primeros caracteres del nombre durante el pase de lista.,De esta manera, se puede considerar que se llaman \"Hermano Ayuan\" y \"Hermana Xiaoman\". */
private const val MENTION_PREFIX_LENGTH: Int = 2

/** Candidatos para atender la convocatoria:ID y nombre del rol,Este último se utiliza para hacer coincidir la lista. */
@Immutable
internal data class GroupResponderCandidate(
    val characterId: String,
    val name: String,
)

/**
 * Elige quién recibe este mensaje.
 *
 * Tres niveles de prioridad,Selección completa [limit] Deténgase cuando las personas o los candidatos estén agotados:
 * 1. pasar lista:El nombre de un miembro aparece en el mensaje.(o los dos primeros caracteres del nombre),el regresara;
 * 2. habló recientemente:Personajes que han hablado en el mensaje más reciente de este grupo.,En orden descendente de tiempo de uso de la palabra;
 * 3. Los miembros restantes se completarán al azar.
 *
 * "Estable" significa que se puede reproducir la misma escena.:La semilla del número aleatorio se deriva de la identificación de la sesión y la identificación de este mensaje de usuario.,
 * El mismo grupo y el mismo mensaje siempre llegan al mismo grupo de personas. De esta manera, no tendrás que cambiar a alguien para que responda la misma oración cada vez.,
 * La prueba también puede escribir "quién respondió a la llamada" como afirmación.
 *
 * @param recentSenderIds Personajes que hablaron recientemente id,De nuevo a viejo;solo en [GROUP_RECENT_SPEAKER_WINDOW] Tomado del artículo
 * @param seed Semilla para relleno aleatorio.,ver [responderSeedOf]
 */
internal fun selectGroupResponders(
    members: List<GroupResponderCandidate>,
    text: String,
    recentSenderIds: List<String>,
    seed: Long,
    limit: Int = MAX_GROUP_RESPONDERS,
): List<String> {
    if (limit <= 0 || members.isEmpty()) return emptyList()
    val picked = LinkedHashSet<String>(limit)
    // 1. La persona nombrada toma asiento primero.,Según el orden de incorporación de los miembros al grupo.,No según el orden en que aparecen los nombres en la oración.
    members.filter { mentions(it.name, text) }.forEach { candidate ->
        if (picked.size < limit) picked += candidate.characterId
    }
    // 2. habló recientemente:nuevo → viejo,Las personas cuyos nombres hayan sido llamados no volverán a ocupar asientos.
    recentSenderIds.forEach { characterId ->
        if (picked.size < limit && members.any { it.characterId == characterId }) picked += characterId
    }
    // 3. Los restantes se barajan y se rellenan según las semillas.
    members.filterNot { it.characterId in picked }
        .shuffled(Random(seed))
        .forEach { candidate ->
            if (picked.size < limit) picked += candidate.characterId
        }
    return picked.toList()
}

/** ¿Se llamó a esta persona en el mensaje?:\"A Yuan\" y \"Brother A Yuan\" cuentan. Los nombres vacíos no se contarán. */
internal fun mentions(name: String, text: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || text.isEmpty()) return false
    if (text.contains(trimmed)) return true
    return trimmed.length >= MENTION_PREFIX_LENGTH && text.contains(trimmed.take(MENTION_PREFIX_LENGTH))
}

/**
 * Semilla de relleno aleatorio:El ID de la sesión y el ID del mensaje del usuario se combinan mediante hash.
 *
 * [String.hashCode] La implementación está determinada por la especificación del lenguaje.,La misma cadena obtiene el mismo valor en cualquier máquina,
 * Por tanto, "quien responde a la llamada" es el mismo grupo de personas en la máquina real y en la prueba.
 */
internal fun responderSeedOf(threadId: String, messageId: String): Long = threadId.hashCode().toLong() * 31L + messageId.hashCode()

/**
 * Personajes que hablaron en un mensaje reciente,De nuevo a antiguo, elimine los duplicados.
 *
 * Solo mira lo último [GROUP_RECENT_SPEAKER_WINDOW] Artículo:Las personas del grupo que acaban de terminar de hablar son las más fáciles de entender.,
 * Las palabras antiguas que se traducen demasiado confundirán a la persona que recibe la llamada.
 */
internal fun recentSpeakerIdsOf(messages: List<ChatMessageUi>): List<String> = messages
    .takeLast(GROUP_RECENT_SPEAKER_WINDOW)
    .asReversed()
    .filter { it.sender == MpActorType.CHARACTER && it.characterId.isNotEmpty() }
    .map { it.characterId }
    .distinct()
