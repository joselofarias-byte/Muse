package io.zer0.muse.backup

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * El punto de puesta en escena/recuperación de persistencia del proceso de recuperación.
 *
 * staging Guarde la copia de seguridad de destino lista para su envío.recovery Guarde una instantánea previa a la importación. ambos estan ubicados
 * filesDir Directorio privado y escrito a través de AtomicFileStore, el proceso se encuentra en medio de una confirmación entre bases de datos.
 * Después de ser asesinado, el libro de contabilidad aún puede localizarlo. La ruta del archivo o el texto no se escriben aquí en el registro.
 */
class RestoreStagingStore(context: Context) {

    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    fun writeTarget(entry: RestoreJournal.Entry, backup: BackupService.Backup) {
        write(fileFor(entry.stagingFileName), backup)
    }

    fun writeRecoveryPoint(entry: RestoreJournal.Entry, backup: BackupService.Backup) {
        write(fileFor(entry.recoveryFileName), backup)
    }

    fun readTarget(entry: RestoreJournal.Entry): BackupService.Backup? = read(fileFor(entry.stagingFileName))

    fun readRecoveryPoint(entry: RestoreJournal.Entry): BackupService.Backup? = read(fileFor(entry.recoveryFileName))

    /**
     * Transmita grandes puntos de recuperación NDJSON al disco sin dividir el texto en una cadena JSON.
     */
    suspend fun writeRecoveryNdJson(entry: RestoreJournal.Entry, write: suspend (BufferedWriter) -> Unit) = withContext(Dispatchers.IO) {
        val target = fileFor(entry.recoveryFileName)
        directory.mkdirs()
        val temp = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                val writer = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
                try {
                    write(writer)
                    writer.flush()
                    output.fd.sync()
                } finally {
                    writer.close()
                }
            }
            moveIntoPlace(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Consuma puntos de recuperación NDJSON mientras el archivo aún está abierto, evitando leer todo el punto de recuperación en la memoria. */
    suspend fun <T> readRecoveryNdJson(entry: RestoreJournal.Entry, read: suspend (Sequence<String>) -> T): T =
        withContext(Dispatchers.IO) {
            fileFor(entry.recoveryFileName)
                .bufferedReader(StandardCharsets.UTF_8)
                .use { reader -> read(reader.lineSequence()) }
        }

    /** Borre la copia de recuperación privada después de completar o abandonar la recuperación; si la eliminación falla, solo se registrará el registro y el contenido del archivo no quedará expuesto. */
    fun cleanup(entry: RestoreJournal.Entry) {
        listOf(entry.stagingFileName, entry.recoveryFileName)
            .filter(String::isNotBlank)
            .forEach { name ->
                val file = fileFor(name)
                if (file.exists() && !file.delete()) {
                    Logger.w(TAG, "Error al limpiar la copia de recuperación: ${file.name}")
                }
            }
        if (directory.exists() && directory.listFiles().isNullOrEmpty()) {
            directory.delete()
        }
    }

    private fun write(file: File, backup: BackupService.Backup) {
        directory.mkdirs()
        val text = json.encodeToString(BackupService.Backup.serializer(), backup)
        AtomicFileStore.writeText(file, text)
    }

    private fun read(file: File): BackupService.Backup? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(BackupService.Backup.serializer(), file.readText())
        }.onFailure {
            Logger.w(TAG, "Error al analizar la copia de recuperación: ${file.name}", it)
        }.getOrNull()
    }

    private fun fileFor(name: String): File {
        require(name.isNotBlank() && File(name).name == name && name != "." && name != "..") {
            "invalid restore artifact name"
        }
        return File(directory, name)
    }

    private fun moveIntoPlace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TAG = "RestoreStagingStore"
        const val DIRECTORY_NAME = "restore-staging"
    }
}
