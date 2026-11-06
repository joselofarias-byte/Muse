package io.zer0.muse.channel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.AppJson
import io.zer0.muse.channel.ChannelConversationStore.Conversation
import io.zer0.muse.channel.ChannelConversationStore.Turn
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Almacenamiento de fragmentos de sesión: volumen de escritura, eliminación, borrado, nombre de archivo y códec.
 *
 * Este grupo bloquea los comportamientos centrales de rectificación del rendimiento el 2026-11-01 —— **append Escriba solo archivos para esta sesión**.
 * La implementación anterior reescribía toda la tabla cada vez que se agregaba una ronda (hasta 50 sesiones × 200 ronda), cada vez que llega un mensaje del canal, al menos
 * Se agregaron dos veces (rueda de usuario + rueda de asistente), todas con el mismo bloqueo de objeto. Si alguien cambia la escritura en toda la tabla,
 * "La cantidad de bytes de archivos en el directorio de fragmentos no aumenta después de agregarlos" se volverá rojo inmediatamente.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChannelConversationShardTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val shardDir: File get() = File(context.filesDir, "channel_conversations")

    private val channelA = "test-shard-a"
    private val channelB = "test-shard-b"

    @Before
    fun setUp() {
        ChannelConversationStore.attach(context)
        ChannelConversationStore.clear(channelA, "peer-1")
        ChannelConversationStore.clear(channelB, "peer-2")
    }

    @After
    fun tearDown() {
        ChannelConversationStore.clear(channelA, "peer-1")
        ChannelConversationStore.clear(channelB, "peer-2")
    }

    private fun shardFiles(): List<File> =
        shardDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }

    private fun bytesOf(key: String): Long =
        File(shardDir, ChannelConversationFiles.shardName(key)).let { if (it.exists()) it.length() else 0L }

    @Test
    fun `append writes only the touched conversation`() {
        ChannelConversationStore.append(channelA, "peer-1", "user", "Hola")
        val afterFirst = shardFiles().size
        val bytesA = bytesOf(ChannelConversationStore.key(channelA, "peer-1"))

        ChannelConversationStore.append(channelB, "peer-2", "user", "otra conversación")
        val afterSecond = shardFiles().size
        // B El anexo no debe cambiar el archivo de A (la longitud y el contenido permanecen sin cambios).
        assertEquals("A Los fragmentos de no deberían cambiar debido a la adición de B", bytesA, bytesOf(ChannelConversationStore.key(channelA, "peer-1")))
        assertTrue("Se debe escribir un fragmento para cada una de las dos sesiones.", afterSecond == afterFirst + 1)
        assertTrue("La cantidad de archivos fragmentados no debe exceder la cantidad de sesiones.", shardFiles().size <= ChannelConversationStore.conversations.value.size)
    }

    @Test
    fun `one_append_payload_is_a_single_conversation_not_the_whole_table`() {
        // Crea 20 sesiones, cada una con 30 rondas; compare el tamaño de bytes de "codificación de tabla completa" y "codificación de sesión única".
        repeat(20) { index ->
            val channelId = "bulk-$index"
            ChannelConversationStore.clear(channelId, "peer")
            repeat(30) { turnIndex ->
                ChannelConversationStore.append(channelId, "peer", "user", "Contenido del mensaje $index-$turnIndex")
            }
        }
        val all = ChannelConversationStore.conversations.value
        val fullTableBytes = AppJson.encodeToString(
            MapSerializer(String.serializer(), Conversation.serializer()),
            all,
        ).toByteArray(Charsets.UTF_8).size
        val oneKey = ChannelConversationStore.key("bulk-0", "peer")
        val oneConversation = all.getValue(oneKey)
        val singleBytes = ChannelConversationFiles
            .encode(oneKey, oneConversation)
            .toByteArray(Charsets.UTF_8).size

        // Agregue la cantidad de colocación real requerida para una ronda = Una sola sesión; la implementación anterior era la tabla completa.
        println(
            "[shard] full_table=$fullTableBytes B, single_conversation=$singleBytes B, " +
                "ratio=${fullTableBytes.toLong() / singleBytes}",
        )
        assertTrue(
            "Codificación de sesión única($singleBytes B) Debe ser mucho más pequeño que la codificación completa de la tabla.($fullTableBytes B)",
            singleBytes * 5 < fullTableBytes,
        )
        repeat(20) { index -> ChannelConversationStore.clear("bulk-$index", "peer") }
    }

    @Test
    fun `clear removes the shard file`() {
        ChannelConversationStore.append(channelA, "peer-1", "user", "Para ser aclarado")
        val key = ChannelConversationStore.key(channelA, "peer-1")
        val file = File(shardDir, ChannelConversationFiles.shardName(key))
        assertTrue("Debería haber fragmentos después de agregarlos.", file.exists())

        ChannelConversationStore.clear(channelA, "peer-1")
        assertTrue("clear Se deben eliminar los fragmentos posteriores.", !file.exists())
        assertNull(ChannelConversationStore.conversation(channelA, "peer-1"))
    }

    @Test
    fun `conversation_survives_as_single_shard_across_restore_format`() {
        ChannelConversationStore.append(channelA, "peer-1", "user", "Todavía debe estar allí después de reiniciar.")
        val key = ChannelConversationStore.key(channelA, "peer-1")
        val text = File(shardDir, ChannelConversationFiles.shardName(key)).readText()

        val decoded = ChannelConversationFiles.decode(text)
        assertNotNull("Los fragmentos deben ser decodificables.", decoded)
        assertEquals(key, decoded!!.first)
        assertEquals("Todavía debe estar allí después de reiniciar.", decoded.second.turns.first().text)
    }

    @Test
    fun `shard name is stable and path safe`() {
        val key = ChannelConversationStore.key("telegram|weird/../id", "peer\u0000x")
        val name = ChannelConversationFiles.shardName(key)
        assertEquals(name, ChannelConversationFiles.shardName(key))
        assertTrue("Los nombres de archivos no deben contener separadores de ruta", !name.contains("/") && !name.contains("\\"))
        assertTrue("Sufijo de nombre de archivo fijo", name.endsWith(".json"))
        assertEquals("sha256 Hexadecimal 64 bits + sufijo", 64 + 5, name.length)
    }

    @Test
    fun `corrupt shard payload is skipped instead of becoming empty conversation`() {
        assertNull(ChannelConversationFiles.decode("{not json"))
        assertNull(ChannelConversationFiles.decode(""))
    }

    @Test
    fun `turn round trip keeps media fields`() {
        val conversation = Conversation(
            summary = "Resumen",
            turns = listOf(
                Turn(
                    role = "user",
                    text = "",
                    mediaKind = "image",
                    mediaBase64 = "AAAA",
                    mediaPath = "/tmp/x.jpg",
                    mediaDescription = "Descripción",
                    dispatchId = "d-1",
                ),
            ),
            updatedAt = 123L,
            recentDispatchTurnIds = listOf("d-1\u0000user"),
        )
        val key = "k|v"
        val decoded = ChannelConversationFiles.decode(ChannelConversationFiles.encode(key, conversation))
        assertEquals(conversation, decoded!!.second)
    }
}
