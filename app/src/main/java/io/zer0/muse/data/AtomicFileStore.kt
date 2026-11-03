package io.zer0.muse.data

import io.zer0.common.Logger
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Escrituras atómicas y herramientas de aislamiento de corrupción para archivos pequeños en tiempo de ejecución.
 *
 * Primero escriba un archivo temporal que se encuentre en el mismo directorio que el archivo de destino y sincronice el contenido del archivo antes de reemplazarlo;
 * El archivo oficial no se sobrescribirá directamente cuando falle el reemplazo. Funciona con instantáneas de sesión, índices, registros, etc.
 * Estado recuperable, no reemplaza el flujo de exportación SAF ni el descargador de archivos grandes.
 */
object AtomicFileStore {

    private const val TAG = "AtomicFileStore"

    /** Escritura atómica de texto UTF-8 sin sobrescritura directa en el archivo de destino. */
    fun writeText(target: File, text: String) {
        writeBytes(target, text.toByteArray(Charsets.UTF_8))
    }

    /** Para escribir bytes de forma atómica, el archivo temporal y el archivo de destino deben estar en el mismo directorio. */
    fun writeBytes(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: error("atomic file target must have a parent")
        check(parent.exists() || parent.mkdirs()) { "No se puede crear el directorio de archivos atómicos: ${parent.absolutePath}" }
        val temp = File(parent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            moveIntoPlace(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Saque los archivos no resueltos de las rutas oficiales y conserve el contenido original para su diagnóstico o recuperación manual.
     * @return El expediente en cuarentena; devuelve nulo si el objetivo no existe.
     */
    @Suppress("TooGenericExceptionCaught")
    fun quarantine(file: File, reason: String): File? {
        if (!file.exists()) return null
        val suffix = reason.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32).ifEmpty { "invalid" }
        val quarantined = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}-$suffix")
        return try {
            if (file.renameTo(quarantined)) {
                quarantined
            } else {
                // Algunos sistemas de archivos Android/Robolectric no admiten renameTo; elimine los archivos oficiales después de copiarlos en el mismo directorio.
                file.copyTo(quarantined, overwrite = false)
                check(file.delete()) { "No se pueden eliminar archivos para poner en cuarentena: ${file.absolutePath}" }
                quarantined
            }
        } catch (error: Exception) {
            Logger.w(TAG, "Falló la cuarentena del archivo corrupto: ${file.name}", error)
            null
        }
    }

    private fun moveIntoPlace(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            // Algunos sistemas de archivos de Android no son compatibles con ATOMIC_MOVE; todavía uso el mismo reemplazo de directorio,
            // Nunca recurra a writeText directamente para evitar dejar contenido a medio escribir en el archivo de destino.
            Logger.w(TAG, "El sistema de archivos no admite el movimiento atómico, utilice el mismo reemplazo de directorio: ${target.name}", unsupported)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
