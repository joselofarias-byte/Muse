package io.zer0.muse.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v2.6.2(comentarios de los usuarios): Herramienta de colocación de imágenes de fondo del chat —— Fondo de tres capas (sesión/asistente/global) compartido.
 *
 * Por qué se debe descargar: si la imagen de fondo se almacena en las Preferencias de DataStore como base64, se reescribirá por completo cada vez que se edite.
 * Inflamación del archivo de instalación, arranques en frío lentos y riesgo de ANR. Aquí, filesDir se escribe después de una compresión unificada.
 * DataStore sólo mantener "file://..." Cadena de ruta.
 *
 * Estrategia de compresión: gorra de lado largo 1920,JPEG Calidad 82 (un equilibrio entre tamaño y apariencia, suficiente para papeles pintados).
 */
internal object ChatBackgroundStore {

    private const val MAX_SIDE = 1920
    private const val JPEG_QUALITY = 82

    /** Nombre de archivo de fondo predeterminado global (para mantener la compatibilidad heredada). */
    private const val GLOBAL_FILE = "chat_background.jpg"

    /**
     * Comprima la imagen seleccionada por el usuario y suéltela en el disco, luego regrese "file://" El camino inicial; devuelve nulo en caso de error.
     *
     * @param slot Nombre del archivo en el disco (excluyendo la extensión). situación general/asistente/Cada sesión utiliza un espacio diferente,
     *   Evite sobrescribir unos a otros; pase null para usar el nombre predeterminado global (compatible con el comportamiento anterior).
     * @param unique Si se debe agregar una marca de tiempo después del nombre del archivo (v2.6.2).
     *   Al volver a seleccionar la misma ranura, si la ruta permanece sin cambios,SmartImage Llegará al caché antiguo sin actualizarlo;
     *   Agregar una marca de tiempo hace que la ruta sea diferente cada vez, lo que obliga a una recarga. Se borrarán los archivos antiguos.
     */
    fun saveFromUri(context: Context, uri: Uri, slot: String? = null, unique: Boolean = false): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== bmp) scaled.recycle()
        bmp.recycle()
        val base = if (slot.isNullOrBlank()) GLOBAL_FILE else "$slot.jpg"
        val fileName = if (unique) {
            val stem = base.substringBeforeLast('.')
            "${stem}_${System.currentTimeMillis()}.jpg"
        } else {
            base
        }
        // Limpie archivos antiguos en la misma ranura (incluidos archivos históricos con marcas de tiempo) para evitar una acumulación infinita
        runCatching {
            val stem = base.substringBeforeLast('.')
            context.filesDir.listFiles()?.forEach { f ->
                val n = f.name
                if (n == base || (n.startsWith("${stem}_") && n.endsWith(".jpg"))) f.delete()
            }
        }
        val target = File(context.filesDir, fileName)
        target.writeBytes(out.toByteArray())
        out.close()
        "file://" + target.absolutePath
    }.getOrNull()

    /** Elimine el archivo de fondo correspondiente a una ranura (llamado al borrar el fondo, incluidos los archivos de historial con marcas de tiempo). */
    fun deleteSlot(context: Context, slot: String?) {
        runCatching {
            val base = if (slot.isNullOrBlank()) GLOBAL_FILE else "$slot.jpg"
            val stem = base.substringBeforeLast('.')
            context.filesDir.listFiles()?.forEach { f ->
                val n = f.name
                if (n == base || (n.startsWith("${stem}_") && n.endsWith(".jpg"))) f.delete()
            }
        }
    }

    /** Ranura para archivos de fondo a nivel de sesión. */
    fun sessionSlot(sessionId: String): String = "chat_bg_session_${sessionId.sanitized()}"

    /** Ranura para archivos de fondo de nivel asistente. */
    fun assistantSlot(assistantId: String): String = "chat_bg_assistant_${assistantId.sanitized()}"

    /** Seguridad del nombre de archivo: mantenga solo guiones bajos alfanuméricos. */
    private fun String.sanitized(): String = replace(Regex("[^A-Za-z0-9_-]"), "_")
}
