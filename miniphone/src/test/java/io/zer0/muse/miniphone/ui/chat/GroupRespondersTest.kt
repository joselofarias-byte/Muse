package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selección de chat grupal:pasar lista → habló recientemente → estable aleatorio,Máximo dos.
 *
 * función pura,No se requieren bibliotecas ni interfaces:¿Es correcta la regla en sí?,Aquí está detallado punto por punto.
 */
class GroupRespondersTest {

    @Test
    fun aNamedMemberIsAlwaysPickedEvenWithTheNicknamePrefix() {
        val picked = select(members = FIVE, text = "Hermano Ayuan,El que dijiste ayer")

        assertEquals("La persona cuyo nombre se llama viene primero.", "c1", picked.first())
        assertTrue(picked.contains("c1"))
    }

    @Test
    fun thePrefixOfALongNameAlsoCounts() {
        val members = listOf(candidate("c9", "Compañero de clase Xiaoman"), candidate("c1", "Ayuan"))

        val picked = select(members = members, text = "¿Está Xiaoman aquí?")

        assertEquals("Si los dos primeros caracteres del nombre coinciden, lo llamarán.", "c9", picked.first())
    }

    @Test
    fun aSingleCharacterNameIsMatchedOnlyAsIs() {
        val members = listOf(candidate("c1", "ah"), candidate("c2", "Xiaoman"))

        assertEquals("c1", select(members = members, text = "ah,¿Estás ahí?").first())
        assertFalse("Si tu nombre no aparece, no cuenta como llamado.", mentions("ah", "buenas noches"))
    }

    @Test
    fun aNamedMemberPlusTheRecentSpeakerAreExactlyTheTwo() {
        val picked = select(members = FIVE, text = "Xiaoman, ¿qué opinas?", recentSenderIds = listOf("c4"))

        assertEquals("Nombre primero,Añadir a lo que dije recientemente", listOf("c2", "c4"), picked)
    }

    @Test
    fun neverMoreThanTwoEvenInAFiveMemberGroup() {
        val picked = select(members = FIVE, text = "buenas noches a todos")

        assertEquals(2, picked.size)
        assertEquals("No puedes elegir a la misma persona dos veces", picked.size, picked.distinct().size)
        assertTrue("Sólo puedes elegir entre miembros.", FIVE.map { it.characterId }.containsAll(picked))
    }

    @Test
    fun namingThreePeopleStillStopsAtTwo() {
        val picked = select(members = FIVE, text = "Ayuan, Xiaoman y Awu están todos aquí.")

        assertEquals("Tome los dos primeros en orden de miembros", listOf("c1", "c2"), picked)
    }

    @Test
    fun theSameSceneAlwaysPicksTheSamePeople() {
        val seed = responderSeedOf(threadId = "g1", messageId = "m1")

        val first = select(members = FIVE, text = "¿Está ahí?", seed = seed)
        val second = select(members = FIVE, text = "¿Está ahí?", seed = seed)

        assertEquals("Las personas del mismo grupo y el mismo mensaje no pueden cambiar", first, second)
    }

    @Test
    fun recentSpeakersAreDedupedAndOnlyFromTheLastTenMessages() {
        val messages = buildList {
            add(message("old", characterId = "c1", sender = MpActorType.CHARACTER))
            repeat(GROUP_RECENT_SPEAKER_WINDOW) { index ->
                add(message("m$index", characterId = if (index < 3) "c3" else "c2", sender = MpActorType.CHARACTER))
            }
        }

        val recent = recentSpeakerIdsOf(messages)

        assertEquals("Mantenga solo los oradores más recientes,eliminar duplicados,El viejo c1 no está en la ventana.", listOf("c2", "c3"), recent)
    }

    @Test
    fun ownMessagesAreNotRecentSpeakers() {
        val messages = listOf(
            message("u1", characterId = "", sender = MpActorType.USER),
            message("m1", characterId = "c2", sender = MpActorType.CHARACTER),
        )

        assertEquals(listOf("c2"), recentSpeakerIdsOf(messages))
    }

    @Test
    fun anEmptyGroupPicksNobody() {
        assertEquals(emptyList<String>(), selectGroupResponders(emptyList(), "¿Está ahí?", emptyList(), seed = 1L))
    }

    private fun select(
        members: List<GroupResponderCandidate>,
        text: String,
        recentSenderIds: List<String> = emptyList(),
        seed: Long = responderSeedOf("g1", "m1"),
    ): List<String> = selectGroupResponders(members = members, text = text, recentSenderIds = recentSenderIds, seed = seed)

    private fun candidate(id: String, name: String): GroupResponderCandidate = GroupResponderCandidate(characterId = id, name = name)

    private fun message(id: String, characterId: String, sender: MpActorType): ChatMessageUi = ChatMessageUi(
        id = id,
        characterId = characterId,
        sender = sender,
        type = MpMessageType.TEXT,
        content = "No. $id frase",
        createdAt = 1L,
    )

    private companion object {
        val FIVE: List<GroupResponderCandidate> = listOf(
            GroupResponderCandidate(characterId = "c1", name = "Ayuan"),
            GroupResponderCandidate(characterId = "c2", name = "Xiaoman"),
            GroupResponderCandidate(characterId = "c3", name = "Awu"),
            GroupResponderCandidate(characterId = "c4", name = "azen"),
            GroupResponderCandidate(characterId = "c5", name = "Ah Chi"),
        )
    }
}
