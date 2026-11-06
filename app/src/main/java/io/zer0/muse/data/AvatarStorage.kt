@file:Suppress("ReturnCount")

package io.zer0.muse.data

import android.content.Context
import android.net.Uri
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Límites de almacenamiento privado para imágenes de perfil.
 *
 * Photo Picker Es posible que el URI de contenido devuelto solo tenga autorización a corto plazo y no se pueda escribir directamente. DataStore
 * como recurso a largo plazo. Todos los URI legibles se copian aquí en filesDir/avatar antes de conservar la ruta.
 */
object AvatarStorage {

    private const val MAX_AVATAR_BYTES = 10L * 1024 * 1024

    /**
     * Copie los recursos del avatar en el directorio privado de la aplicación y devuelva una ruta utilizable a largo plazo.
     *
     * Photo Picker El URI de contenido sólo puede tener permiso de lectura temporal; externo file URI
     * También puede dejar de ser válido después de reiniciar la aplicación, por lo que ambos deben copiarse en filesDir/avatar.
     */
    suspend fun persist(context: Context, uri: String?): String? {
        if (uri.isNullOrBlank()) return uri
        if (uri.startsWith("android.resource://") || uri.startsWith("data:image/")) {
            return uri
        }

        val normalized = uri.removePrefix("file://")
        if (normalized.startsWith("/")) {
            val sourceFile = File(normalized)
            if (!sourceFile.exists() || !sourceFile.isFile) {
                Logger.w("AvatarStorage", "El archivo de avatar no existe: $normalized")
                return null
            }
            if (isPrivateAvatarFile(context, sourceFile)) {
                return sourceFile.absolutePath
            }
            return copyFile(context, sourceFile)
        }
        return copyContentUri(context, Uri.parse(uri))
    }

    /** Convierta la ruta del avatar local en un modelo que Coil pueda cargar directamente. */
    fun imageModel(uri: String): Any = when {
        uri.startsWith("/") -> File(uri)
        uri.startsWith("file://") -> Uri.parse(uri).path?.let(::File) ?: uri
        else -> uri
    }

    private fun isPrivateAvatarFile(context: Context, file: File): Boolean = try {
        val avatarDir = File(context.filesDir, "avatar").canonicalFile
        file.canonicalFile.parentFile == avatarDir
    } catch (e: IOException) {
        Logger.w("AvatarStorage", "No se puede determinar si el archivo de avatar pertenece a un directorio privado", e)
        false
    }

    private suspend fun copyFile(context: Context, sourceFile: File): String? = withContext(Dispatchers.IO) {
        try {
            sourceFile.inputStream().use { input ->
                copyInputStream(context, input, sourceFile.extension)
            }
        } catch (e: IOException) {
            Logger.e("AvatarStorage", "Error al copiar el archivo de avatar", e)
            null
        } catch (e: SecurityException) {
            Logger.e("AvatarStorage", "Falló el permiso de lectura del archivo Avatar", e)
            null
        }
    }

    private suspend fun copyContentUri(context: Context, sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        try {
            val input = resolver.openInputStream(sourceUri)
            if (input == null) {
                Logger.w("AvatarStorage", "No se puede leer avatar URI: $sourceUri")
                return@withContext null
            }
            val extension = when (resolver.getType(sourceUri)?.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
            input.use { copyInputStream(context, it, extension) }
        } catch (e: IOException) {
            Logger.e("AvatarStorage", "Error en la copia del avatar", e)
            null
        } catch (e: SecurityException) {
            Logger.e("AvatarStorage", "Error de permiso de lectura de avatar", e)
            null
        }
    }

    private fun copyInputStream(context: Context, input: InputStream, extension: String): String? {
        val dir = File(context.filesDir, "avatar")
        if (!dir.exists() && !dir.mkdirs()) {
            Logger.e("AvatarStorage", "No se puede crear el directorio de avatar: ${dir.absolutePath}")
            return null
        }
        val normalizedExtension = when (extension.lowercase()) {
            "png", "webp", "gif" -> extension.lowercase()
            else -> "jpg"
        }
        val target = File(dir, "avatar_${UUID.randomUUID()}.$normalizedExtension")
        val temp = File(dir, "${target.name}.tmp")
        try {
            val copied = temp.outputStream().use { output -> copyStream(input, output) }
            if (!copied) {
                temp.delete()
                Logger.w("AvatarStorage", "Avatar supera ${MAX_AVATAR_BYTES / 1024 / 1024}MB límite superior,Rechazado")
                return null
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                Logger.e("AvatarStorage", "Error al enviar el archivo de avatar: ${target.absolutePath}")
                return null
            }
            return target.absolutePath
        } catch (e: IOException) {
            temp.delete()
            Logger.e("AvatarStorage", "Falló la escritura del avatar", e)
            return null
        } catch (e: SecurityException) {
            temp.delete()
            Logger.e("AvatarStorage", "Falló el permiso de escritura del directorio de Avatar", e)
            return null
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream): Boolean {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            total += read
            if (total > MAX_AVATAR_BYTES) return false
            output.write(buffer, 0, read)
        }
    }
}
