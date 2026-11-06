package io.zer0.muse.miniphone.data.moment

import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.newInMemoryMiniPhoneDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Materiales para el contenido espontáneo de los personajes: sobre qué charlaron recientemente, qué publicaron en el post anterior, qué hora es ahora y qué escriben en este post.
 *
 * Ku Paozhen Room(banco de memoria):Afirmaciones como "Tome los últimos 8 elementos", "Reduzca a 100 palabras", "Arriba y abajo primero, las instrucciones al final"
 * Debe estar entre el verdadero resultado de la consulta y el verdadero orden de empalme.,El uso de un sustituto se convierte en una autocertificación.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpCharacterMaterialTest {

    private lateinit var db: MiniPhoneDb

    @Before
    fun setUp() {
        db = newInMemoryMiniPhoneDb()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `Moments trae las últimas ocho conversaciones y marca al hablante`() = runBlocking {
        seedThread()
        seedMessages(count = 12)

        val hint = material(at = EVENING).forMoment(CHARACTER_ID)

        val turns = hint.lineSequence().filter { it.startsWith("Usuario:") || it.startsWith("Yo:") }.toList()
        assertEquals("Solo toma los últimos 8 artículos.", 8, turns.size)
        assertEquals("El primero fue expulsado.", "Usuario: ¿Estás ahí? 5", turns.first())
        assertEquals("El último está al final.", "Yo: ¿Estás ahí? 12", turns.last())
        assertTrue("Marca denominativa del usuario \"usuario\"", turns[0].startsWith("Usuario:"))
        assertTrue("Las palabras con caracteres están marcadas con \"I\"", turns[1].startsWith("Yo:"))
    }

    @Test
    fun `Déjalo claro cuando no estés chateando`() = runBlocking {
        seedThread()

        val hint = material(at = EVENING).forMoment(CHARACTER_ID)

        assertTrue(hint, hint.contains("Aún no he chateado con el usuario"))
        assertFalse("No deberías inventar el diálogo si no tienes diálogo.", hint.contains("Usuario:"))
    }

    @Test
    fun `Cada conversación está limitada a 100 palabras`() = runBlocking {
        seedThread()
        val longText = (1..40).joinToString("") { index -> "No.${index}segmento." }
        insertMessage(index = 1, sender = MpActorType.USER, content = longText)

        val hint = material(at = EVENING).forMoment(CHARACTER_ID)

        assertTrue("Conserva las primeras 100 palabras", hint.contains(longText.take(100)))
        assertFalse("Descartar después de la palabra 101", hint.contains(longText.take(101)))
    }

    @Test
    fun `Moments utiliza la publicación anterior como referencia para evitar duplicaciones`() = runBlocking {
        seedThread()
        insertMoment(id = "old", content = "Hace mucho tiempo", at = 10L)
        val latest = (1..40).joinToString("") { index -> "No.${index}segmento." }
        insertMoment(id = "new", content = latest, at = 20L)

        val hint = material(at = EVENING).forMoment(CHARACTER_ID)

        assertTrue("Con las primeras 60 palabras del último artículo.", hint.contains(latest.take(60)))
        assertFalse("No más de 60 palabras", hint.contains(latest.take(61)))
        assertTrue(hint.contains("No repitas el mismo tema del anterior."))
        assertFalse("Consulte solo el último", hint.contains("Hace mucho tiempo"))
    }

    @Test
    fun `Los mensajes privados incluyen conversación y tiempo, pero no referencia`() = runBlocking {
        seedThread()
        seedMessages(count = 2)
        insertMoment(id = "m1", content = "hace viento hoy", at = 10L)

        val hint = material(at = EVENING).forProactiveChat(CHARACTER_ID)

        assertTrue(hint, hint.contains("Usuario: ¿Estás ahí? 1"))
        assertTrue(hint, hint.contains("Son las nueve de la noche del martes."))
        assertFalse("Los mensajes privados no tienen nada que ver con Moments", hint.contains("último post que publiqué"))
        assertFalse("No leer actualizaciones en mensajes privados", hint.contains("hace viento hoy"))
    }

    @Test
    fun `El orden del círculo de amigos es contexto, tiempo e instrucciones de cierre`() = runBlocking {
        seedThread()
        seedMessages(count = 2)
        insertMoment(id = "m1", content = "hace viento hoy", at = 10L)

        val hint = material(at = EVENING).forMoment(CHARACTER_ID, focus = "Llámalo una noche y hablemos de hoy casualmente.")

        assertTrue(hint, hint.startsWith("Conversaciones recientes con usuarios (antiguas→nuevo):"))
        assertTrue("La última actualización es después de la conversación.", hint.indexOf("último post que publiqué") > hint.indexOf("Usuario: ¿Estás ahí? 1"))
        assertTrue("tiempo después de la dinámica", hint.indexOf("ya es martes") > hint.indexOf("último post que publiqué"))
        assertTrue("El comando de cierre está en la última frase.", hint.endsWith("Esta vez puedes escribir: Termina el día y hablemos de hoy de manera informal."))
    }

    @Test
    fun `El orden de los mensajes privados es contexto, hora e instrucciones de cierre`() = runBlocking {
        seedThread()
        seedMessages(count = 2)

        val hint = material(at = EVENING).forProactiveChat(CHARACTER_ID, focus = "Por la noche, charlemos informalmente.")

        assertTrue(hint, hint.startsWith("Conversaciones recientes con usuarios (antiguas→nuevo):"))
        assertTrue("tiempo después de la conversación", hint.indexOf("ya es martes") > hint.indexOf("Usuario: ¿Estás ahí? 1"))
        assertTrue("El comando de cierre está en la última frase.", hint.endsWith("El motivo de este momento: Por la noche, una charla informal."))
    }

    @Test
    fun `Trae la hora y el período actual`() = runBlocking {
        val evening = material(at = EVENING).forMoment(CHARACTER_ID)
        val dawn = material(at = DAWN).forMoment(CHARACTER_ID)

        assertTrue(evening, evening.contains("martes"))
        assertTrue(evening, evening.contains("21:00"))
        assertTrue("La tarde pertenece a la \"noche\"", evening.contains("(noche)"))
        assertTrue("La mañana pertenece a \"temprano\"", dawn.contains("(mañana)"))
    }

    private fun material(at: Long): MpCharacterMaterial = MpCharacterMaterial(
        threadDao = db.threadDao(),
        messageDao = db.messageDao(),
        momentDao = db.momentDao(),
        now = { at },
        zone = ZONE,
    )

    private suspend fun seedThread() {
        db.threadDao().upsert(MpThreadEntity(id = THREAD_ID, characterId = CHARACTER_ID, lastMessageAt = 12L))
    }

    private suspend fun seedMessages(count: Int) {
        (1..count).forEach { index ->
            insertMessage(
                index = index,
                sender = if (index % 2 == 1) MpActorType.USER else MpActorType.CHARACTER,
                content = "¿Está ahí? $index",
            )
        }
    }

    private suspend fun insertMessage(index: Int, sender: MpActorType, content: String) {
        db.messageDao().insert(
            MpMessageEntity(
                id = "msg-$index",
                threadId = THREAD_ID,
                characterId = CHARACTER_ID,
                sender = sender,
                type = MpMessageType.TEXT,
                content = content,
                createdAt = index.toLong(),
            ),
        )
    }

    private suspend fun insertMoment(id: String, content: String, at: Long) {
        db.momentDao().insert(
            MpMomentEntity(
                id = id,
                authorId = CHARACTER_ID,
                authorName = CHARACTER_NAME,
                content = content,
                createdAt = at,
                source = MpMomentSource.AI,
            ),
        )
    }

    private companion object {
        const val CHARACTER_ID: String = "c1"
        const val CHARACTER_NAME: String = "ali"
        const val THREAD_ID: String = "t1"

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** Martes 21:00. */
        val EVENING: Long = LocalDateTime.of(2026, 5, 12, 21, 0).atZone(ZONE).toInstant().toEpochMilli()

        /** Martes 06:00. */
        val DAWN: Long = LocalDateTime.of(2026, 5, 12, 6, 0).atZone(ZONE).toInstant().toEpochMilli()
    }
}
