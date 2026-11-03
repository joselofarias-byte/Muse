package io.zer0.muse.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AUD-20261011-NDJSON-FOLDERS: NDJSON Restaurar la guardia de integridad de la distribución.
 *
 * Antecedentes de defectos (.audit REPORT_DATA F1): v2.4.6 Solo se agrega la línea de exportación al conectar la carpeta de la base de conocimientos
 * (writeTypedLines "knowledgeFolder") y meta recuento, falta applyNdJsonStreaming
 * de when(type) Ramas de distribución y buffers de recuperación —— No se puede reponer después de eliminar todo en la fase de compensación.
 * Cada vez que se restaura NDJSON, se borrarán todas las carpetas de la base de conocimientos del usuario y la conciliación B-34 solo cuenta el número de líneas analizadas, no las verifica ni las almacena, y se pierde silenciosamente.
 *
 * Este guardia utiliza la lista de campos de clase de datos Backup.Backup como fuente de verdad, afirmando el tipo de cada fila de exportación NDJSON.
 * Hay casos correspondientes en el distribuidor de recuperación para evitar"Exportado pero no se puede volver a importar"Sucedió de nuevo.
 */
class BackupNdJsonFolderRestoreTest {

    /** Exportar tipo de fila lateral → La entidad DAO/ID de búfer que debe aparecer correspondiente a la rama de distribución de recuperación. */
    private val exportedTypeToRestoreMarker = mapOf(
        "session" to "sessionBuf",
        "message" to "messageBuf",
        "assistant" to "assistantBuf",
        "lorebook" to "lorebookBuf",
        "skill" to "skillBuf",
        "artifact" to "artifactBuf",
        "quickMessage" to "quickMsgBuf",
        "promptInjection" to "promptInjBuf",
        "folder" to "folderBuf",
        "groupChat" to "groupChatBuf",
        "groupChatMessage" to "groupChatMsgBuf",
        "scheduledTask" to "schedTaskBuf",
        "scheduledTaskExecution" to "schedExecBuf",
        "experience" to "experienceBuf",
        "milestone" to "milestoneBuf",
        "agentMessage" to "agentMsgBuf",
        "quickNote" to "quickNoteBuf",
        "worldBookEntry" to "worldBookBuf",
        "conversationEvent" to "convEventBuf",
        "conversationTurn" to "convTurnBuf",
        "messagePart" to "messagePartBuf",
        "messageOutbox" to "messageOutboxBuf",
        "translateHistory" to "translateHistoryBuf",
        "knowledgeBase" to "knowledgeBaseBuf",
        // AUD-20261011-NDJSON-FOLDERS: Brecha reparada esta vez —— La distribución de recuperación debe tener conocimientoRama de carpetas
        "knowledgeFolder" to "knowledgeFolderBuf",
        "subagentThread" to "subagentThreadBuf",
        "toolRound" to "toolRoundBuf",
        "sessionBranchHead" to "sessionBranchHeadBuf",
        "groupChatMemory" to "groupChatMemoryBuf",
        "knowledgeChunk" to "knowChunkBuf",
        "knowledgeDoc" to "knowDocBuf",
        // v2.6.7 fix (AUDIT F1): La rama de recuperación de la relación de tareas pendientes y roles debe tener los buffers correspondientes.
        "sessionTodo" to "sessionTodoBuf",
        "assistantRelationship" to "assistantRelationshipBuf",
        // v2.6.8: Entidad de tarjeta. La tarjeta acoplada solo existe en esta tabla después de abandonar el flujo de mensajes.
        "cardEntity" to "cardEntityBuf",
        // v2.6.9: Mensaje activo tres tablas. Espero que el libro de contabilidad y la intimidad se conserven después de cambiar de dispositivo.
        "pendingThought" to "pendingThoughtBuf",
        "proactiveOutcome" to "proactiveOutcomeBuf",
        "proactiveRelation" to "proactiveRelationBuf",
    )

    /** Extraiga todos los tipos de línea de writeTypedLines en el lado de exportación del código fuente. */
    private fun exportedTypes(): List<String> {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()
        return Regex("writeTypedLines\\(\\s*writer,\\s*\"([a-zA-Z]+)\"")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `every exported ndjson type has a restore dispatch branch`() {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()
        val start = source.indexOf("private suspend fun applyNdJsonStreaming(")
        val end = source.indexOf(
            "private suspend fun applyNdJsonStreamingWithImageCleanup",
            startIndex = start,
        )
        require(start >= 0 && end > start) { "No se puede localizar el límite del método de restauración NDJSON" }
        val dispatch = source.substring(start, end)

        val missing = exportedTypes()
            .filter { it != "meta" && it != "settings" && it != "scopedFact" && it != "fileStore" }
            .mapNotNull { type ->
                val marker = exportedTypeToRestoreMarker[type]
                    ?: return@mapNotNull "$type(Al mapa de prueba le falta el ID de la rama de recuperación,Por favor complete el formulario)"
                if (dispatch.contains("\"$type\" ->") && dispatch.contains(marker)) {
                    null
                } else {
                    "$type(Escasez de distribución \" $type\" -> case o buffer $marker)"
                }
            }
        assertEquals(
            "NDJSON Restaurar la distribución de sucursales a las que les faltan tipos de exportación(No se puede reponer después de la limpieza,Recuperar datos perdidos):\n" + missing.joinToString("\n"),
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `ndjson restore flushes knowledge folders after import`() {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()
        val start = source.indexOf("private suspend fun applyNdJsonStreaming(")
        val end = source.indexOf(
            "private suspend fun applyNdJsonStreamingWithImageCleanup",
            startIndex = start,
        )
        val dispatch = source.substring(start, end)
        assertTrue(
            "knowledgeFolder El búfer debe vaciarse dentro de la transacción de recuperación (knowledgeFolderDao().upsert)",
            dispatch.contains("knowledgeFolderDao().upsert"),
        )
    }

    /**
     * AUD-20261011-NDJSON-FOLDERS (REPORT_DATA F2): buildBackup() La construcción de respaldo
     * Se deben pasar DbSnapshot.knowledgeFolders —— Esta función también es la fuente de instantánea del punto de recuperación.
     * (writeRecoveryPoint → applyBackupInternal ruta de reversión), transmisión perdida=Si la recuperación falla, todas las carpetas se borrarán y se revertirán.
     */
    @Test
    fun `buildBackup passes knowledgeFolders from snapshot into Backup`() {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()
        val start = source
            .indexOf("private suspend fun buildBackup(includeSecrets: Boolean = false): Backup {")
            .let { if (it >= 0) it else source.indexOf("private suspend fun buildBackup(): Backup {") }
        require(start >= 0) { "No se puede localizar el método buildBackup" }
        val end = source.indexOf("private suspend fun", startIndex = start + 40)
        val body = source.substring(start, if (end > start) end else source.length)

        assertTrue(
            "buildBackup Se debe leer la instantánea knowledgeFolderDao().getAll()",
            body.contains("knowledgeFolderDao().getAll()"),
        )
        // La lista de parámetros del constructor debe pasarse explícitamente. knowledgeFolders =(De lo contrario, DbSnapshot no se leerá en la copia de seguridad)
        assertTrue(
            "buildBackup de Backup(...) La construcción debe pasar el parámetro KnowledgeFolders." +
                "(recovery point Rollback depende de él para rellenar la carpeta)",
            Regex("knowledgeFolders\\s*=\\s*snap\\.knowledgeFolders").containsMatchIn(body),
        )
    }
}
