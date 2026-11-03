package io.zer0.muse.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P5-5: La integridad de la copia de seguridad está protegida por dos capas.
 *
 * 1) cobertura de entidad: Recorra la lista de entidades @Database declarada por MuseDb.kt(única fuente de verdad),
 *    Afirma que cada tabla de datos es [BackupService.Backup] incorporar;Si no se realiza una copia de seguridad de la nueva tabla de datos
 *    (Tampoco está incluido explícitamente en la lista de la tabla de operación y mantenimiento.)Fallo directo,Evite la pérdida y regresión de datos al cambiar de dispositivo.
 * 2) Regresión de degradación de contraseña: P0-1 — Contraseña establecida pero almacén de claves perdido(Volver a leer como vacío)debe negarse
 *    Exportar/subir,No se permite la degradación silenciosa a texto claro.
 */
class BackupEntityCoverageTest {

    /** entidad de datos del usuario → Backup Nombre del campo serializado(debe existir). */
    private val dataEntityToField = mapOf(
        "SessionEntity" to "sessions",
        "MessageEntity" to "messages",
        "ArtifactEntity" to "artifacts",
        "AssistantEntity" to "assistants",
        "LorebookEntity" to "lorebooks",
        "QuickMessageEntity" to "quickMessages",
        "PromptInjectionEntity" to "promptInjections",
        "SkillEntity" to "skills",
        "FolderEntity" to "folders",
        "ScheduledTaskEntity" to "scheduledTasks",
        "KnowledgeDocEntity" to "knowledgeDocs",
        "KnowledgeChunkEntity" to "knowledgeChunks",
        "ScheduledTaskExecutionEntity" to "scheduledTaskExecutions",
        "GroupChatEntity" to "groupChats",
        "GroupChatMessageEntity" to "groupChatMessages",
        "GroupChatMemoryEntity" to "groupChatMemories",
        "ExperienceEntity" to "experiences",
        "MilestoneEntity" to "milestones",
        "AgentMessageEntity" to "agentMessages",
        "KnowledgeBaseEntity" to "knowledgeBases",
        "io.zer0.muse.data.knowledge.KnowledgeFolderEntity" to "knowledgeFolders",
        "MessageOutboxEntity" to "messageOutboxes",
        "TranslateHistoryEntity" to "translateHistories",
        "QuickNoteEntity" to "quickNotes",
        "io.zer0.muse.worldbook.WorldBookEntryEntity" to "worldBookEntries",
        "io.zer0.muse.data.subagent.SubagentThreadEntity" to "subagentThreads",
        "ConversationEventEntity" to "conversationEvents",
        "ConversationTurnEntity" to "conversationTurns",
        "ToolRoundEntity" to "toolRounds",
        "MessagePartEntity" to "messageParts",
        "SessionBranchHeadEntity" to "sessionBranchHeads",
        // v2.6.7(P1-1): Estado de relación de rol (activos de usuario, se deben migrar con copia de seguridad)
        "io.zer0.muse.data.relationship.AssistantRelationshipEntity" to "assistantRelationships",
        // v2.6.7: Sesión pendiente (activo de usuario) —— "lo escribi"Si no puedes reemplazarlo, simplemente tíralo)
        "io.zer0.muse.data.todo.SessionTodoEntity" to "sessionTodos",
        // v2.6.8: Entidad de la tarjeta (el único lugar donde la tarjeta acoplada deja el flujo de mensajes. Si no se realiza una copia de seguridad, la tarjeta se perderá para siempre)
        "io.zer0.muse.data.card.CardEntity" to "cardEntities",
        // v2.6.9: Preocupado por el libro mayor —— asistente"Cosas para recordar preguntar", perder la máquina de reemplazo significa que se borra la memoria de relaciones.
        "io.zer0.muse.data.proactive.PendingThoughtEntity" to "pendingThoughts",
        // v2.6.9: cuenta de resultados —— Historial de retroalimentación aprendido, si pierdes intimidad, tienes que empezar de cero.
        "io.zer0.muse.data.proactive.ProactiveOutcomeEntity" to "proactiveOutcomes",
        // v2.6.9: Relación de intimidad y jerarquía.
        "io.zer0.muse.data.proactive.ProactiveRelationEntity" to "proactiveRelations",
    )

    /** Operación y mantenimiento/tabla derivada: Explícitamente no incluido en la copia de seguridad(Bajo costo de reconstrucción, metadatos de la máquina incluidos). Las tablas de datos nuevas no deben pertenecer a esta colección. */
    private val operationalEntities = setOf(
        "StatsCacheEntity",
        "DbIntegrityLogEntity",
        "AutoBackupLogEntity",
        "AuditLogEntity",
        "io.zer0.muse.data.patrol.PatrolLogEntity",
        "GenerationCheckpointEntity",
        "GroupChatGenerationLedgerEntity",
        // Flujo de uso: datos derivados puramente estadísticos, no activos del usuario.
        // No dar marcha atrás es intencional —— Si se restaura junto con la copia de seguridad, el flujo histórico se contará repetidamente y los datos se completarán con la actualización.
        "io.zer0.muse.data.stats.UsageRecordEntity",
    )

    /** Extraiga la entidad de declaración @Database del código fuente de MuseDb.kt(Mantente fiel a la verdadera fuente de Room,Evite el problema del reflejo de anotación invisible.). */
    private fun declaredEntities(): List<String> {
        val source = File("src/main/java/io/zer0/muse/data/session/MuseDb.kt").readText()
        val block = Regex("entities\\s*=\\s*\\[([\\s\\S]*?)\\]").find(source)?.groupValues?.get(1)
            ?: error("No se puede encontrar el bloque de declaración de entidades en MuseDb.kt")
        return Regex("([\\w.]+)::class").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `every muse db data entity is covered by backup`() {
        val backupFields = BackupService.Backup::class.java.declaredFields.map { it.name }.toSet()
        val unknown = mutableListOf<String>()
        val missing = mutableListOf<String>()

        for (entity in declaredEntities()) {
            val field = dataEntityToField[entity]
            when {
                field != null && backupFields.contains(field) -> Unit
                field != null -> missing += "$entity → $field(A la copia de seguridad le falta este campo)"
                entity in operationalEntities -> Unit
                else -> unknown += entity
            }
        }

        assertEquals(
            "La entidad de datos recién agregada no está conectada a la copia de seguridad(O no incluido explícitamente en la tabla de operación y mantenimiento.),Cambiar de dispositivo provocará la pérdida de datos:\n" + missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
        assertEquals(
            "Entidad no reconocida,Por favor acceda a la copia de seguridad o únase operationalEntities(Tabla de operación y mantenimiento.)Lista de verificación:\n" + unknown.joinToString("\n"),
            emptyList<String>(),
            unknown,
        )
    }

    @Test
    fun `every backup field maps to a declared entity or known derived slot`() {
        val declared = declaredEntities().toSet()
        val knownDerived = setOf(
            "settingsSnapshot",
            "scopedFacts",
            // v4: Instantánea de almacenamiento de archivos(canal/conector/Comentario/Bandeja de entrada/complemento),No hay entidad MuseDb correspondiente
            "fileStores",
            // v4.1: Instantánea del archivo del directorio(Nota adjunta/avatar),Ambos son almacenamiento de archivos.,No hay entidad MuseDb correspondiente
            "binaryFiles",
            // memory tabla de módulos(independiente MemoryDb,No en el manifiesto de MuseDb)Exportado junto con la copia de seguridad
            "sessionSummaries",
            // v2.x: Punto de control de compresión de sesión,también vive en MemoryDb
            "contextCheckpoints",
            "dailyStates",
            "compiledSections",
            "scopedCompiledSections",
            "facts",
        )
        val orphans = BackupService.Backup::class.java.declaredFields
            .filter { List::class.java.isAssignableFrom(it.type) || Map::class.java.isAssignableFrom(it.type) }
            .map { it.name }
            .filter { it !in knownDerived && it !in dataEntityToField.values }
            .filterNot { it in operationalEntities }
            .filter { it !in declared }
        assertEquals("El campo de respaldo no tiene una fuente de entidad correspondiente,Por favor verifique:\n" + orphans.joinToString("\n"), emptyList<String>(), orphans)
    }

    @Test
    fun `password degradation is rejected not downgraded`() {
        // Se ha establecido la contraseña + El almacén de claves se pierde y se vuelve a leer como vacío → Denegar exportación/subir
        assertTrue(
            "La contraseña se ha establecido pero se vuelve a leer como vacía, por lo que se debe considerar que no está disponible.(P0-1,Deshabilitar la degradación para borrar texto)",
            BackupService.isBackupPasswordUnavailable(
                CloudBackupConfig(backupPasswordSet = true, backupPassword = ""),
            ),
        )
        // Nunca establezcas una contraseña → El proceso de texto sin formato continúa(Compatible con copias de seguridad antiguas)
        assertFalse(
            BackupService.isBackupPasswordUnavailable(
                CloudBackupConfig(backupPasswordSet = false, backupPassword = ""),
            ),
        )
        // contraseña normal → Disponible
        assertFalse(
            BackupService.isBackupPasswordUnavailable(
                CloudBackupConfig(backupPasswordSet = true, backupPassword = "s3cret"),
            ),
        )
    }
}
