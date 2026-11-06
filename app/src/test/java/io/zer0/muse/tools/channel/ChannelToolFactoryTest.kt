package io.zer0.muse.tools.channel

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2: ChannelToolFactory Prueba de integración de tres piezas del chat grupal.
 *
 * Bloquear el contrato de salida utilizado por GroupChatScheduler:Nombre de definición del conjunto de tres piezas, asignación de ejecutor por nombre,
 * Y la semántica de los parámetros de los tres enlaces de devolución de llamada respuesta/paso/read_context(trim / abrazadera de rango/predeterminado).
 */
class ChannelToolFactoryTest {

    @Test
    fun `factory exposes the three channel tools with matching executors`() = runTest {
        val (defs, executors) = ChannelToolFactory.createChannelToolDefinitions(
            groupChatId = "chat-1",
            senderAssistantId = "agent-1",
            onReply = {},
            onPass = {},
            contextProvider = { "ctx" },
        )

        // orden:reply → pass → read_context
        assertEquals(
            listOf("channel_reply", "channel_pass", "channel_read_context"),
            defs.map { it.name },
        )
        // Cada definición tiene un ejecutor correspondiente.(Asignar por nombre)
        assertEquals(defs.map { it.name }.toSet(), executors.keys)
        // El esquema de parámetros no filtra campos vinculados al marco(chatId/assistantId No aprobado por LLM)
        val replySchema = defs.first { it.name == "channel_reply" }.parametersJsonSchema
        assertTrue(replySchema.contains("content"))
        assertTrue(!replySchema.contains("chatId"))
        assertTrue(!replySchema.contains("assistantId"))
        // read_context El límite se declara como integer
        val readSchema = defs.first { it.name == "channel_read_context" }.parametersJsonSchema
        assertTrue(readSchema.contains("limit"))
        assertTrue(readSchema.contains("integer"))
    }

    @Test
    fun `reply forwards trimmed content and rejects empty`() = runTest {
        var reply: String? = null
        val (_, exec) = ChannelToolFactory.createChannelToolDefinitions(
            groupChatId = "chat-1",
            senderAssistantId = "agent-1",
            onReply = { reply = it },
            onPass = {},
            contextProvider = { "ctx" },
        )
        val channelReply = exec.getValue("channel_reply")

        channelReply.invoke(mapOf("content" to "  hello group  "))
        assertEquals("hello group", reply)

        // El contenido vacío no activa el envío,Devolver texto de error(Evite que caigan burbujas de aire en la biblioteca.)
        reply = null
        assertEquals("error: content no puede estar vacío", channelReply.invoke(emptyMap()))
        assertNull(reply)
    }

    @Test
    fun `pass forwards reason and drops blank`() = runTest {
        var reason: String? = "unset"
        val (_, exec) = ChannelToolFactory.createChannelToolDefinitions(
            groupChatId = "chat-1",
            senderAssistantId = "agent-1",
            onReply = {},
            onPass = { reason = it },
            contextProvider = { "ctx" },
        )
        val channelPass = exec.getValue("channel_pass")

        channelPass.invoke(mapOf("reason" to "  Espera a que otros hablen primero.  "))
        assertEquals("Espera a que otros hablen primero.", reason)

        channelPass.invoke(mapOf("reason" to "   "))
        assertNull(reason)
    }

    @Test
    fun `read_context clamps limit into the 1 to 50 range and returns provider text`() = runTest {
        var seenLimit = -1
        val (_, exec) = ChannelToolFactory.createChannelToolDefinitions(
            groupChatId = "chat-1",
            senderAssistantId = "agent-1",
            onReply = {},
            onPass = {},
            contextProvider = { limit ->
                seenLimit = limit
                "hist-$limit"
            },
        )
        val readContext = exec.getValue("channel_read_context")

        // Predeterminado limit → 20
        assertEquals("hist-20", readContext.invoke(emptyMap()))
        assertEquals(20, seenLimit)
        // Sujetado más allá del límite superior 50
        readContext.invoke(mapOf("limit" to "999"))
        assertEquals(50, seenLimit)
        // Abrazadera por debajo del límite inferior 1
        readContext.invoke(mapOf("limit" to "0"))
        assertEquals(1, seenLimit)
        // El valor ilegal vuelve a caer por defecto 20
        readContext.invoke(mapOf("limit" to "abc"))
        assertEquals(20, seenLimit)
    }
}
