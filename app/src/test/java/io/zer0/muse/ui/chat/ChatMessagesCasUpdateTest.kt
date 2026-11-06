package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Escrituras simultáneas sin perder actualizaciones:`updateMessages` Regresión semántica CAS.
 *
 * Antecedentes:`ChatStateAccessor.updateMessages` La implementación es `_messages.update(transform)`,
 * Usado en conflicto**último valor**Reproducir transformación.CAS Sólo tiene sentido cuando la transformación realmente consume los parámetros de entrada.——
 * `updateMessages { staleSnapshot }`(Ignorar parámetros de entrada) Igual a la asignación, protección cero.
 *
 * Este documento cubre dos capas:
 *  1. Capa semántica: utilice el dispositivo StateFlow de CAS real para simular "después de leer la instantánea, otro escritor ha escrito", bloquear
 *     Agregar/cambiar una entrada única por ID/filtrar la semántica de combinación de tres tipos de escritura y bloquear de forma inversa los dos métodos de escritura degenerados, lo que provocará la pérdida de datos.
 *  2. Capa de cableado: afirme que los dos archivos de producción están escritos en forma de parámetros de entrada del consumidor (volver al método de escritura anterior fallará).
 *
 * ¿Por qué no hacerlo de un extremo a otro? Estas transformaciones son ChatViewModel / ChatGenerationController
 * La lambda en línea en el método súper largo se basa en el estado completo de almacenamiento y transmisión de Android, y no se puede introducir sin introducir
 * Robolectric Convocatoria directa bajo la premisa de luminaria completa. La capa semántica bloquea el contrato con la transformación equivalente,
 * El formulario del código de producción de bloqueo de la capa de cableado, los dos juntos cubren "corregir el error y ponerse rojo".
 */
class ChatMessagesCasUpdateTest {

    // ── Accesorio: contenedor de mensajes CAS verdadero ───────────────────────────────

    private class CasAccessor(initial: List<UIMessage> = emptyList()) : ChatStateAccessor {
        val flow = MutableStateFlow(initial)

        override val snapshot: ChatUiState get() = ChatUiState()

        override fun update(transform: (ChatUiState) -> ChatUiState) = Unit

        override val messagesSnapshot: List<UIMessage> get() = flow.value

        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = flow.update(transform)

        override val coroutineScope: CoroutineScope get() = CoroutineScope(Dispatchers.Unconfined)
    }

    private fun msg(content: String, role: MessageRole = MessageRole.ASSISTANT): UIMessage = UIMessage(role = role, content = content)

    // ── agregar clase (A1 / A2) ──────────────────────────────────────────

    @Test
    fun `append via CAS keeps writes that landed inside the window`() {
        val m1 = msg("base")
        val accessor = CasAccessor(listOf(m1))
        // El escritor A lee la instantánea (simula la ventana de tiempo después de que ChatViewModel lee _messages.value)
        val staleSnapshot = accessor.messagesSnapshot

        // El escritor B primero agrega (interlude recuperación de inyección/punto de interrupción)
        accessor.updateMessages { it + msg("interlude-b") }

        // El escritor A continúa agregando —— Los parámetros de entrada deben consumirse; de lo contrario, la instantánea anterior sobrescribirá la adición de B.
        accessor.updateMessages { it + msg("interlude-a") }

        assertEquals(
            listOf("base", "interlude-b", "interlude-a"),
            accessor.messagesSnapshot.map { it.content },
        )
        // De hecho, la instantánea es antigua, lo que demuestra que no se trata de una ejecución vacía "sin concurrencia".
        assertEquals(listOf("base"), staleSnapshot.map { it.content })
    }

    @Test
    fun `ignoring the incoming list loses the concurrent append`() {
        val accessor = CasAccessor(listOf(msg("base")))
        val staleSnapshot = accessor.messagesSnapshot

        accessor.updateMessages { it + msg("interlude-b") }
        // Escritura degenerada: ignorar los parámetros de entrada = tarea,CAS en vano
        accessor.updateMessages { staleSnapshot + msg("interlude-a") }

        assertEquals(
            listOf("base", "interlude-a"),
            accessor.messagesSnapshot.map { it.content },
        )
        assertFalse(accessor.messagesSnapshot.any { it.content == "interlude-b" })
    }

    // ── Cambiar orden por id (A3 / A6 / A8) ────────────────────────────────

    @Test
    fun `targeted field update keeps fields written concurrently on the same message`() {
        val target = msg("Texto original").copy(imageUrls = emptyList())
        val other = msg("vecino", role = MessageRole.USER)
        val accessor = CasAccessor(listOf(target, other))
        // Instantáneas antiguas (como traducirMessage al comienzo del objetivo/finalización de finalAssistant)
        val staleTarget = accessor.messagesSnapshot.first { it.id == target.id }

        // Otro escritor en la ventana agregó medios al mismo mensaje (ubicación de foto/video)
        accessor.updateMessages { list ->
            list.map { if (it.id == target.id) it.copy(imageUrls = listOf("https://cdn/x.png")) else it }
        }

        // A3 Formulario: solo copie el transformador/traduzca los campos de salida reales
        accessor.updateMessages { list ->
            list.map { m ->
                if (m.id == staleTarget.id) {
                    m.copy(translations = m.translations + ("chino" to "traducción"))
                } else {
                    m
                }
            }
        }
        // A8 Formulario: copiar solo campos de metadatos
        accessor.updateMessages { list ->
            list.map { m ->
                if (m.id == staleTarget.id) m.copy(durationMs = 1234L, promptTokens = 10) else m
            }
        }

        val merged = accessor.messagesSnapshot.first { it.id == target.id }
        assertEquals(listOf("https://cdn/x.png"), merged.imageUrls)
        assertEquals("traducción", merged.translations["chino"])
        assertEquals(1234L, merged.durationMs)
        assertEquals(10, merged.promptTokens)
        // Los mensajes de vecinos no se ven afectados
        assertEquals("vecino", accessor.messagesSnapshot.first { it.id == other.id }.content)
    }

    @Test
    fun `replacing the whole message with a stale object loses concurrent fields`() {
        val target = msg("Texto original")
        val accessor = CasAccessor(listOf(target))
        val staleTarget = accessor.messagesSnapshot.first { it.id == target.id }

        accessor.updateMessages { list ->
            list.map { if (it.id == target.id) it.copy(imageUrls = listOf("https://cdn/x.png")) else it }
        }

        // A9 Método de escritura trampa: use el objeto antiguo para cubrirlo en su totalidad
        accessor.updateMessages { list ->
            list.map { if (it.id == target.id) staleTarget.copy(content = "correcto") else it }
        }

        val merged = accessor.messagesSnapshot.first { it.id == target.id }
        assertEquals("correcto", merged.content)
        assertTrue("Reemplazar el objeto antiguo en su totalidad inevitablemente perderá los campos multimedia en la ventana.", merged.imageUrls.isEmpty())
    }

    @Test
    fun `transformer output merged by field keeps media and metadata`() {
        val target = msg("<mood>manuscrito</mood>Texto").copy(imageUrls = listOf("https://cdn/v.mp4"))
        val accessor = CasAccessor(listOf(target))
        val staleTarget = accessor.messagesSnapshot.first { it.id == target.id }

        accessor.updateMessages { list ->
            list.map { if (it.id == target.id) it.copy(durationMs = 999L) else it }
        }

        // A9 Forma verdadera:transformer Contenido de salida/estado de ánimo/reflexión/moodSkin + campo de identidad variante
        val transformed = staleTarget.copy(content = "Texto", mood = "manuscrito")
        accessor.updateMessages { list ->
            list.map {
                if (it.id == staleTarget.id) {
                    it.copy(
                        content = transformed.content,
                        mood = transformed.mood,
                        reflection = transformed.reflection,
                        moodSkin = transformed.moodSkin,
                        variantGroupId = transformed.variantGroupId,
                        variantIndex = transformed.variantIndex,
                        variantCount = transformed.variantCount,
                        parentGroupId = transformed.parentGroupId,
                    )
                } else {
                    it
                }
            }
        }

        val merged = accessor.messagesSnapshot.first { it.id == target.id }
        assertEquals("Texto", merged.content)
        assertEquals("manuscrito", merged.mood)
        assertEquals(listOf("https://cdn/v.mp4"), merged.imageUrls)
        assertEquals(999L, merged.durationMs)
    }

    // ── clase de filtro (A7) ───────────────────────────────────────────────

    @Test
    fun `context overflow rollback keeps messages appended inside the window`() {
        val placeholder = msg("")
        val accessor = CasAccessor(listOf(msg("Preguntas de usuario", MessageRole.USER), placeholder))
        val staleSnapshot = accessor.messagesSnapshot

        // Recién agregado en la ventana (tarjeta de herramientas/interludio de subagente)
        accessor.updateMessages { it + msg("Agregar dentro de la ventana") }

        // A7 Formulario: filtrado basado en parámetros de entrada, no basado en instantáneas antiguas
        accessor.updateMessages { list ->
            list.filterNot { m -> m.id == placeholder.id && m.content.isBlank() }
        }

        val contents = accessor.messagesSnapshot.map { it.content }
        assertEquals(listOf("Preguntas de usuario", "Agregar dentro de la ventana"), contents)
        // La instantánea anterior no contiene mensajes adjuntos dentro de la ventana.
        assertEquals(listOf("Preguntas de usuario", ""), staleSnapshot.map { it.content })
    }

    @Test
    fun `filter over a stale snapshot drops the window append`() {
        val placeholder = msg("")
        val accessor = CasAccessor(listOf(msg("Preguntas de usuario", MessageRole.USER), placeholder))
        val staleSnapshot = accessor.messagesSnapshot

        accessor.updateMessages { it + msg("Agregar dentro de la ventana") }
        // Método de escritura degenerado: filtrar la instantánea anterior y asignar el valor general
        accessor.updateMessages { staleSnapshot.filterNot { m -> m.id == placeholder.id && m.content.isBlank() } }

        assertFalse(accessor.messagesSnapshot.any { it.content == "Agregar dentro de la ventana" })
    }

    // ── Capa de cableado: el código de producción debe consumir parámetros de entrada ────────────────────────────────

    private fun source(relative: String): String {
        val candidates = listOf(Path.of(relative), Path.of("app/$relative"))
        val path = candidates.firstOrNull(Files::exists) ?: error("$relative not found")
        return Files.newBufferedReader(path).use { it.readText() }
    }

    private fun viewModel(): String = source("src/main/java/io/zer0/muse/ui/ChatViewModel.kt")

    private fun generationController(): String = source("src/main/java/io/zer0/muse/ui/chat/ChatGenerationController.kt")

    @Test
    fun `interlude injections append through CAS instead of overwriting the snapshot`() {
        val src = viewModel()
        assertTrue(src.contains("updateMessages { it + interludes }"))
        assertTrue(src.contains("updateMessages { it + unownedInterludes }"))
        assertFalse(src.contains("_messages.value = _messages.value + interludes"))
        assertFalse(src.contains("_messages.value = _messages.value + unownedInterludes"))
    }

    @Test
    fun `translation writes update the source message by id through CAS`() {
        val src = viewModel()
        val applyBody = src.substringAfter("fun applyTranslation(text: String) {").substringBefore("applyTranslation(\"\")")
        assertTrue(applyBody.contains("updateMessages { list ->"))
        assertTrue(applyBody.contains("m.translations + (targetLanguage to text)"))
        assertFalse(applyBody.contains("_messages.value"))

        val partialBody = src.substringAfter("val partial = sb.toString().trim()").substringBefore("_state.update {")
        assertTrue(partialBody.contains("updateMessages { list ->"))
        assertTrue(partialBody.contains("m.translations - targetLanguage"))
        assertFalse(partialBody.contains("_messages.value"))

        val emptyBody = src.substringAfter("// Limpiar el espacio vacío,Cambiar al mensaje de error").substringBefore("_state.update {")
        assertTrue(emptyBody.contains("updateMessages { list ->"))
        assertFalse(emptyBody.contains("_messages.value"))
    }

    @Test
    fun `reaction optimistic update goes through CAS`() {
        val src = viewModel()
        val body = src.substringAfter("fun setReaction(messageId: Uuid, reaction: String?) {")
            .substringBefore("v1.104 U7")
        assertTrue(body.contains("updateMessages { list ->"))
        assertTrue(body.contains("msg.copy(reaction ="))
        assertFalse(body.contains("_messages.value ="))
    }

    @Test
    fun `context overflow rollback filters the incoming list`() {
        val src = generationController()
        val body = src.substringAfter("if (state.contextOverflowBlocked) {")
            .substringBefore("deps.messageController.rebuildConversationTree()")
        assertTrue(body.contains("accessor.updateMessages { list ->"))
        assertTrue(body.contains("filterNot { msg -> msg.id == state.currentAssistantId && msg.content.isBlank() }"))
        assertFalse(body.contains("deps.stateStore.messages.value ="))
    }

    @Test
    fun `metadata writeback goes through CAS`() {
        val src = generationController()
        val body = src.substringAfter("// A5: Generar persistencia de metadatos").substringBefore("// Actualizar el contexto después de que finalice la transmisión")
        assertTrue(body.contains("accessor.updateMessages { list ->"))
        assertTrue(body.contains("durationMs = durationMs"))
        assertFalse(body.contains("deps.stateStore.messages.value ="))
    }

    @Test
    fun `finalize writeback merges only the fields the transformer produces`() {
        val src = generationController()
        val body = src.substringAfter("val preservedAssistant =")
            .substringBefore("deps.sessionRepository.upsertMessage(sessionId, preservedAssistant)")
        // Está prohibido sobrescribir todo el objeto con el objeto antiguo.
        assertFalse(body.contains("preservedAssistant else it"))
        assertFalse(body.contains("deps.stateStore.messages.value ="))
        // Cubra solo los cuatro campos realmente emitidos por el transformador.
        listOf(
            "content = preservedAssistant.content",
            "mood = preservedAssistant.mood",
            "reflection = preservedAssistant.reflection",
            "moodSkin = preservedAssistant.moodSkin",
        ).forEach { assertTrue("Falta la combinación de campos: $it", body.contains(it)) }
        // El campo de identidad de variante también se debe volver a escribir (de lo contrario, la estructura de variante se perderá después de reconstruir el objeto transformador)
        listOf(
            "variantGroupId = preservedAssistant.variantGroupId",
            "variantIndex = preservedAssistant.variantIndex",
            "variantCount = preservedAssistant.variantCount",
            "parentGroupId = preservedAssistant.parentGroupId",
        ).forEach { assertTrue("Falta campo de variación: $it", body.contains(it)) }
        // Los campos multimedia no pueden aparecer en la lista de combinación (provienen del último valor del parámetro de entrada y no pueden sobrescribirse con instantáneas antiguas)
        listOf("imageUrls =", "imageBase64List =", "videoFileUri =").forEach {
            assertFalse("Los campos de medios no deben anularse: $it", body.contains(it))
        }
    }
}
