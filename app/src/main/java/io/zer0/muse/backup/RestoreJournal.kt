package io.zer0.muse.backup

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * Libro mayor de fases de respaldo y recuperación.
 *
 * Muse La biblioteca de sesión, la biblioteca de memoria, la biblioteca de hechos y el DataStore no pueden compartir una transacción; este libro mayor
 * Registre el paso actual de la recuperación entre almacenamientos para que el próximo inicio pueda identificar la recuperación inconclusa en lugar de
 * Confundir el estado de semi-recuperación con la finalización normal. No guarda el cuerpo de la copia de seguridad ni ningún valor confidencial.
 */
class RestoreJournal(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    /** Estado de recuperación actual; la ausencia del archivo significa que no hay recuperación en curso. */
    fun read(): Entry? = if (!file.exists()) {
        null
    } else {
        runCatching { AppJson.decodeFromString(Entry.serializer(), file.readText()) }
            .onFailure { error ->
                Logger.w(TAG, "Falló el análisis del libro mayor de recuperación: ${error.message}", error)
                AtomicFileStore.quarantine(file, "restore_journal_parse")
            }
            .getOrNull()
    }

    /** Si hay una recuperación pendiente; utilizado por las páginas de diagnóstico y autoprueba de inicio. */
    fun hasIncompleteRestore(): Boolean = read()?.phase?.let { phase ->
        phase != Phase.COMPLETED
    } == true

    /** Devuelve registros de recuperación inacabados para que la capa de inicio muestre etapas específicas. */
    fun incompleteEntry(): Entry? = read()?.takeUnless { it.phase == Phase.COMPLETED }

    /** Cree un nuevo registro de recuperación. */
    @Synchronized
    fun begin(restoreId: String, sourceHash: String, backupVersion: Int, recoveryFormat: RecoveryFormat = RecoveryFormat.JSON): Entry =
        update(
            Entry(
                restoreId = restoreId,
                sourceHash = sourceHash,
                backupVersion = backupVersion,
                recoveryFormat = recoveryFormat,
                phase = Phase.PREPARING,
                artifactId = UUID.randomUUID().toString(),
            ),
        )

    /** Actualice las etapas de recuperación y el almacenamiento completado. */
    @Synchronized
    fun advance(entry: Entry, phase: Phase, completedStores: Set<Store> = entry.completedStores, failureReason: String? = null): Entry =
        update(
            entry.copy(
                phase = phase,
                completedStores = completedStores,
                failureReason = failureReason,
                updatedAt = System.currentTimeMillis(),
            ),
        )

    /** Marque la recuperación como exitosa y elimine el libro mayor para evitar que el estado anterior afecte el próximo inicio. */
    @Synchronized
    fun complete(entry: Entry) {
        val completed = entry.copy(
            phase = Phase.COMPLETED,
            updatedAt = System.currentTimeMillis(),
        )
        update(completed)
        if (!file.delete()) {
            Logger.w(TAG, "Recuperación completada pero falla la eliminación del libro mayor: ${file.absolutePath}")
        }
    }

    /** Marcar los errores de recuperación y conservar el libro de contabilidad para realizar diagnósticos; la persona que llama es responsable de decidir si realizar la reversión. */
    @Synchronized
    fun fail(entry: Entry, error: Throwable): Entry = advance(
        entry = entry,
        phase = Phase.FAILED,
        failureReason = error.message?.take(MAX_REASON_LENGTH) ?: error::class.java.simpleName,
    )

    private fun update(entry: Entry): Entry {
        file.parentFile?.mkdirs()
        AtomicFileStore.writeText(file, AppJson.encodeToString(Entry.serializer(), entry))
        return entry
    }

    @Serializable
    data class Entry(
        val restoreId: String,
        /** Solo se guarda el resumen de los datos de origen y no se guarda el texto de la copia de seguridad. */
        val sourceHash: String,
        val backupVersion: Int,
        /** El prefijo del nombre del archivo privado de la copia de recuperación, sin datos del usuario. Compatible por recoveryId cuando falta el libro mayor antiguo. */
        val artifactId: String = restoreId,
        /** Formato de texto del punto de recuperación; El libro mayor antiguo tiene como valor predeterminado JSON. */
        val recoveryFormat: RecoveryFormat = RecoveryFormat.JSON,
        val phase: Phase,
        val completedStores: Set<Store> = emptySet(),
        val failureReason: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
    ) {
        /** staging/recovery El nombre del archivo se compone únicamente de identificadores aleatorios internos y no puede atravesar directorios. */
        val stagingFileName: String get() = "target-$artifactId.json"
        val recoveryFileName: String
            get() = "recovery-$artifactId.${if (recoveryFormat == RecoveryFormat.NDJSON) "ndjson" else "json"}"
    }

    @Serializable
    enum class RecoveryFormat {
        JSON,
        NDJSON,
    }

    @Serializable
    enum class Phase {
        PREPARING,
        STAGING,
        VALIDATING,
        COMMITTING,
        REBUILDING,
        COMPLETED,
        ROLLING_BACK,
        FAILED,
    }

    @Serializable
    enum class Store {
        MUSE_DB,
        MEMORY_DB,
        FACT_DB,
        SETTINGS,
        FTS,
    }

    private companion object {
        const val TAG = "RestoreJournal"
        const val FILE_NAME = "restore-journal.json"
        const val MAX_REASON_LENGTH = 512
    }
}
