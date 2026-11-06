package io.zer0.muse.data.quicknote

import android.content.Context
import android.net.Uri
import io.zer0.common.Logger
import java.io.File
import java.util.UUID

/**
 * v2.6.7: Grabe rápidamente el almacenamiento de imágenes adjuntas.
 *
 * ## ¿Por qué abrir un directorio separado en lugar de reutilizar el pequeño álbum de fotos del teléfono celular? mini_album_import
 *
 * El directorio del álbum de fotos importado aparecerá en su totalidad en la página "Álbum pequeño para teléfono celular" (listImported Escanee el directorio).
 * Si la nota adjunta también está escrita allí y el usuario pega una imagen en la nota, aparecerá una imagen adicional en el álbum de la nada. —— Dos funciones
 * Los límites de los datos pueden contaminarse entre sí. Entonces cada uno usa su propio directorio.
 *
 * ## ¿Por qué guardar el archivo en lugar de simplemente URI
 *
 * El URI de contenido del álbum del sistema solo tiene permiso de lectura a corto plazo (y está sujeto al ciclo de vida SAF).
 * Para poder ver los archivos adjuntos durante mucho tiempo en las notas, los bytes deben copiarse en el directorio privado de la aplicación.
 *
 * ## Nomenclatura de archivos
 *
 * `img_<uuid>.<ext>` —— La identificación de la nota no se lleva. La relación entre notas y archivos adjuntos existe.
 * QuickNoteEntity.attachmentsJson(matriz de ruta), al eliminar notas, recicle los archivos de acuerdo con la lista.
 */
class NoteAttachmentStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * Copie la imagen seleccionada en un directorio privado y devuelva una cadena de ruta file:// legible a largo plazo.
     *
     * Error (sin permiso de lectura, archivo vacío,IO Excepción) retorno null —— La persona que llama avisa al usuario en consecuencia,
     * No escriba un registro adjunto que apunte a un archivo que no existe.
     */
    fun importOne(uri: Uri): String? = runCatching {
        val ext = guessExtension(uri)
        val target = File(dir, "img_${UUID.randomUUID()}$ext")
        val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use { stream ->
            target.outputStream().use { output -> stream.copyTo(output) }
        }
        // 0 La descripción del byte leída se rechaza o la fuente está vacía y no puede considerarse una importación exitosa.
        if (target.length() <= 0L) {
            target.delete()
            return@runCatching null
        }
        Uri.fromFile(target).toString()
    }.onFailure { t ->
        Logger.w(TAG, "No se pudieron importar archivos adjuntos de notas: ${t.message}")
    }.getOrNull()

    /**
     * Eliminar un archivo adjunto.
     *
     * Límite de seguridad: solo se permite eliminar archivos de este directorio.attachmentsJson En teoría, los datos se pueden importar desde el exterior.
     * La contaminación y la falta de verificación resultarán en la eliminación arbitraria del archivo.
     */
    fun delete(uriString: String): Boolean = runCatching {
        val path = Uri.parse(uriString).path ?: return@runCatching false
        val file = File(path)
        if (file.parentFile?.absolutePath != dir.absolutePath) {
            Logger.w(TAG, "Negarse a eliminar archivos adjuntos fuera del directorio de notas: ${file.name}")
            return@runCatching false
        }
        file.delete()
    }.onFailure { t ->
        Logger.w(TAG, "No se pudo eliminar la nota adjunta: ${t.message}")
    }.getOrDefault(false)

    /** Reciclar archivos por lista (llamado cuando las notas se eliminan permanentemente o se vacía la papelera de reciclaje). */
    fun deleteAll(uriStrings: List<String>) {
        uriStrings.forEach { delete(it) }
    }

    private fun guessExtension(uri: Uri): String {
        val name = uri.lastPathSegment.orEmpty()
        val dot = name.lastIndexOf('.')
        if (dot > 0 && dot < name.length - 1) {
            val ext = name.substring(dot + 1).lowercase()
            if (ext in ALLOWED_EXTENSIONS) return ".$ext"
        }
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        return when {
            type.contains("png", ignoreCase = true) -> ".png"
            type.contains("gif", ignoreCase = true) -> ".gif"
            type.contains("webp", ignoreCase = true) -> ".webp"
            else -> ".jpg"
        }
    }

    /** Verifique si una ruta es realmente un archivo adjunto administrado por este almacenamiento. */
    fun isManaged(uriString: String): Boolean = uriString.contains("/$DIR_NAME/")

    companion object {
        private const val TAG = "NoteAttachmentStore"
        private const val DIR_NAME = "note_attachments"
        private val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")
    }
}
