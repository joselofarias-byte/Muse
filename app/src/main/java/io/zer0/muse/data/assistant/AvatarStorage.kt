package io.zer0.muse.data.assistant

import android.content.Context
import android.net.Uri
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * v0.25: Almacenamiento de avatar asistente — Copie la imagen seleccionada por el usuario al almacenamiento interno de la aplicación,Devuelve la ruta absoluta.
 *
 * ¿Por qué no guardar directamente? content:// URI:
 *  - SAF El URI devuelto puede no ser válido después de reiniciar la aplicación(Especialmente no llamado takePersistableUriPermission)
 *  - Después de copiar al almacenamiento interno:① persistencia ② No se requiere permiso ③ A continuación se muestran imágenes al realizar copias de seguridad y restaurar.
 *
 * ubicación del archivo:`filesDir/avatars/assistant_<id>_<timestamp>.<ext>`
 */
object AvatarStorage {

    private const val TAG = "AvatarStorage"
    private const val DIR = "avatars"

    // H-AV1: Tamaño máximo de avatar(10MB),Rechazo de escrituras excedido,Evite imágenes grandes que causen disco/presión de la memoria
    private const val MAX_AVATAR_BYTES = 10L * 1024 * 1024

    /**
     * poner [sourceUri] La imagen puntiaguda se copia al almacenamiento interno.,Devuelve la ruta absoluta. Devuelve nulo en caso de error.
     *
     * H-AV1: Cambiar para suspender la función,interno `withContext(Dispatchers.IO)`,Evite la E/S del disco del hilo principal.
     * M-AV1: Captar explícitamente excepciones OOM e IO,Originales `runCatching { ... }.getOrElse` tragará
     * CancellationException(La corrutina no debe quedar atrapada silenciosamente cuando se cancela)y OOM(JVM estado inestable).
     */
    suspend fun copyToInternal(context: Context, sourceUri: Uri, assistantId: String): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val ext = guessExtension(context, sourceUri)
            val target = File(dir, "assistant_${assistantId}_${System.currentTimeMillis()}.$ext")

            // M-AV1: Tamaño acumulativo al copiar,Deténgase inmediatamente si se excede el límite superior,Evite leer imágenes grandes a la vez OOM
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024)
                    var read: Int
                    var total = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        total += read
                        if (total > MAX_AVATAR_BYTES) {
                            runCatching { target.delete() }
                            Logger.w(TAG, "Avatar supera ${MAX_AVATAR_BYTES / 1024 / 1024}MB límite superior,Rechazado")
                            return@withContext null
                        }
                        output.write(buf, 0, read)
                    }
                }
            } ?: run {
                Logger.w(TAG, "openInputStream Regresar null: $sourceUri")
                return@withContext null
            }

            // Limpiar avatares antiguos del mismo asistente.(Solo mantén lo último)
            dir.listFiles { f -> f.name.startsWith("assistant_${assistantId}_") }
                ?.filter { it.absolutePath != target.absolutePath }
                ?.forEach { runCatching { it.delete() } }
            Logger.i(TAG, "saved avatar → ${target.absolutePath}")
            target.absolutePath
        } catch (e: OutOfMemoryError) {
            // M-AV1: Si copias una imagen grande OOM,grabar y devolver null,No dejes que la aplicación falle
            Logger.e(TAG, "copy avatar OOM", e)
            null
        } catch (e: IOException) {
            Logger.e(TAG, "copy avatar IO failed", e)
            null
        } catch (e: SecurityException) {
            Logger.e(TAG, "copy avatar denied", e)
            null
        }
    }

    private fun guessExtension(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri) ?: ""
        return when {
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("webp", ignoreCase = true) -> "webp"
            mime.contains("gif", ignoreCase = true) -> "gif"
            mime.contains("jpeg", ignoreCase = true) || mime.contains("jpg", ignoreCase = true) -> "jpg"
            else -> "jpg"
        }
    }
}
