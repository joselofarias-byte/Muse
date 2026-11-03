package io.zer0.muse.backup

import io.zer0.muse.data.groupchat.GroupChatMemoryEntity
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.data.session.ConversationEventEntity
import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.MessageOutboxEntity
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.SessionBranchHeadEntity
import io.zer0.muse.data.session.ToolRoundEntity
import io.zer0.muse.data.subagent.SubagentThreadEntity
import io.zer0.muse.ui.translate.TranslateHistoryEntity
import io.zer0.muse.worldbook.WorldBookEntryEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0-10 ① Aceptación:round-trip Prueba de recuperación de copia de seguridad — entidad previamente desaparecida(6 Dominio de sesión + Diario + Tabla de 6 usuarios)
 * Serializado mediante copia de seguridad → Viaje de ida y vuelta completo de campos después de la deserialización.,No se pierde ningún dato.
 *
 * Descripción:BackupService Depende de múltiples Room DB/Context,La capa de prueba única utiliza la estructura de respaldo
 * Validación de ida y vuelta serializada(Mismo nivel que BackupKeyExclusionTest);DB Camino de caída
 * Aserción simétrica por applyBackupInternal / applyNdJsonStreaming(Importar campos laterales)
 * Garantizado conjuntamente con pruebas de cobertura de la entidad.
 */
class BackupRoundTripSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `P0-10 entities round trip without data loss`() {
        val quickNote = QuickNoteEntity(
            id = "qn1",
            title = "Título",
            content = "contenido",
            tags = listOf("a", "b"),
            folder = "trabajo",
            contentType = "markdown",
            attachmentsJson = "[]",
        )
        val worldBook = WorldBookEntryEntity(
            id = "wb1",
            name = "entrada",
            content = "Texto",
            keywordsJson = """["palabras clave"]""",
            alwaysActive = true,
            scanDepth = 5,
        )
        val convEvent = ConversationEventEntity(
            sessionId = "s1", eventSeq = 1, eventId = "ev1", turnId = "t1",
            type = "stream_delta", payloadJson = "{}", payloadHash = "h", payloadLength = 2,
            createdAt = 100L,
        )
        val convTurn = ConversationTurnEntity(
            turnId = "t1",
            sessionId = "s1",
            inputUserMessageId = "m1",
            assistantMessageId = "m2",
            phase = "user",
            startedAt = 100L,
            updatedAt = 100L,
        )
        val messagePart = MessagePartEntity(
            messageId = "m1",
            partIndex = 0,
            kind = "text",
            text = "Texto",
            createdAt = 100L,
        )
        val outbox = MessageOutboxEntity(
            id = "o1",
            sessionId = "s1",
            text = "Listo para ir",
            userMessageId = "m1",
            assistantMessageId = "m2",
            createdAt = 100L,
        )
        val translate = TranslateHistoryEntity(
            id = "tr1",
            sourceText = "hello",
            translatedText = "Hola",
            sourceLanguage = "en",
            targetLanguage = "zh",
        )
        val kb = KnowledgeBaseEntity(id = "kb1", name = "base de conocimientos", description = "d")
        val thread = SubagentThreadEntity(
            threadId = "th1",
            parentSessionId = "s1",
            childSessionPath = "/x.json",
            assistantId = "a1",
        )
        val toolRound = ToolRoundEntity(
            id = "r1",
            turnId = "t1",
            roundIndex = 0,
            toolCallId = "c1",
            toolName = "search",
            argsJson = "{}",
            status = "done",
            startedAt = 100L,
        )
        val branchHead = SessionBranchHeadEntity(sessionId = "s1", updatedAt = 100L)
        val groupChatMemory = GroupChatMemoryEntity(
            id = "gm1",
            groupChatId = "g1",
            assistantId = "a1",
            summary = "Resumen",
        )

        val backup = BackupService.Backup(
            version = 3,
            exportedAt = 123L,
            sessions = emptyList(),
            messages = emptyList(),
            quickNotes = listOf(quickNote),
            worldBookEntries = listOf(worldBook),
            conversationEvents = listOf(convEvent),
            conversationTurns = listOf(convTurn),
            messageParts = listOf(messagePart),
            messageOutboxes = listOf(outbox),
            translateHistories = listOf(translate),
            knowledgeBases = listOf(kb),
            subagentThreads = listOf(thread),
            toolRounds = listOf(toolRound),
            sessionBranchHeads = listOf(branchHead),
            groupChatMemories = listOf(groupChatMemory),
        )

        val text = json.encodeToString(BackupService.Backup.serializer(), backup)
        val restored = json.decodeFromString(BackupService.Backup.serializer(), text)

        assertEquals(quickNote, restored.quickNotes.single())
        assertEquals(worldBook, restored.worldBookEntries.single())
        assertEquals(convEvent, restored.conversationEvents.single())
        assertEquals(convTurn, restored.conversationTurns.single())
        assertEquals(messagePart, restored.messageParts.single())
        assertEquals(outbox, restored.messageOutboxes.single())
        assertEquals(translate, restored.translateHistories.single())
        assertEquals(kb, restored.knowledgeBases.single())
        assertEquals(thread, restored.subagentThreads.single())
        assertEquals(toolRound, restored.toolRounds.single())
        assertEquals(branchHead, restored.sessionBranchHeads.single())
        assertEquals(groupChatMemory, restored.groupChatMemories.single())
    }

    @Test
    fun `v26_7 todo and relationship entities round trip`() {
        val todo = io.zer0.muse.data.todo.SessionTodoEntity(
            id = "todo1",
            sessionId = "s1",
            todosJson = """[{"content":"escribir plan","status":"pending"}]""",
            hasOpenItems = true,
            createdAt = 100L,
            updatedAt = 200L,
        )
        val relationship = io.zer0.muse.data.relationship.AssistantRelationshipEntity(
            assistantId = "a1",
            relationLabel = "amigos",
            affinity = 42,
            lastInteractionAt = 300L,
            proactiveLevel = "normal",
            createdAt = 100L,
            updatedAt = 300L,
        )

        val backup = BackupService.Backup(
            exportedAt = 1L,
            sessions = emptyList(),
            messages = emptyList(),
            sessionTodos = listOf(todo),
            assistantRelationships = listOf(relationship),
        )
        val restored = json.decodeFromString(
            BackupService.Backup.serializer(),
            json.encodeToString(BackupService.Backup.serializer(), backup),
        )

        assertEquals(todo, restored.sessionTodos.single())
        assertEquals("Las relaciones de los personajes se exportaron previamente pero no se restauraron, y toda la intimidad se perdió después de cambiar de dispositivo. —— Esto bloquea el lado de recuperación.", relationship, restored.assistantRelationships.single())
    }

    @Test
    fun `empty P0-10 fields serialize as absent for old backups`() {
        // La copia de seguridad anterior no tiene campos nuevos → Deserializar la lista vacía predeterminada,hasAnyData no afectado
        val oldStyle = """{"version":3,"exportedAt":1,"sessions":[],"messages":[]}"""
        val restored = json.decodeFromString(BackupService.Backup.serializer(), oldStyle)
        assertEquals(emptyList<TranslateHistoryEntity>(), restored.translateHistories)
        assertEquals(emptyMap<String, List<*>>(), restored.scopedFacts)
    }
}
