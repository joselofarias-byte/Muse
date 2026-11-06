package io.zer0.muse.web

import io.zer0.common.Logger
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

/**
 * Libro mayor de acceso a archivos multimedia: opaco token ↔ Ruta del archivo multimedia nativo.
 *
 * El navegador sólo obtiene `/api/media/<token>`, no puede obtener la ruta absoluta. Usar la ruta misma como parámetro de consulta directamente
 * El recorrido del directorio se entrega a la parte solicitante (`?path=../../shared_prefs/x.xml`); el token aleatorio no se puede adivinar y no contiene información de ruta.
 * Y sólo es válido dentro del ámbito registrado en el servidor.
 *
 * **Sólo reconoce tres directorios**:[imageDir](Directorio de ubicación de imágenes de mensajes),[voiceDir](directorio de archivo de voz),
 * [avatarDir](Directorio de avatar del asistente).
 * El registro y la lectura se verifican una vez que "la ruta canónica se encuentra dentro del directorio permitido": el lado de registro determina qué referencias se pueden convertir en enlaces.
 * El lado de la lectura es la última puerta. —— El directorio puede cambiar de nombre, reemplazarse o vincularse a otro lugar, y la sentencia en el momento del registro no representa la sentencia en este momento.
 *
 * **LRU límite superior**:Por defecto [DEFAULT_MAX_ENTRIES] banda. Cada libro mayor solo almacena el token y la cadena de ruta (aproximadamente 200 bytes).
 * 2048 Aproximadamente 400 KB; una mayor ampliación no lo hará más seguro (el navegador solo hace referencia a la pantalla de medios más reciente a la vez),
 * Pero las noticias que hace tiempo se pasaron por alto han ido ocupando el lugar. Cuando se supere el límite, se eliminará el que lleve más tiempo sin usarse, y su token será 404 inmediatamente.
 *
 * **Reutilizar el mismo archivo token**: utilice la ruta canónica como clave principal. La emisión repetida no generará nuevos tokens.
 * De este modo, el navegador accede a su propia caché HTTP y no vuelve repetidamente al origen cuando se desplaza por la lista de mensajes.
 *
 * **desnudo base64**: Cuando la imagen se deja en el mensaje en formato base64 (datos cortos que no se han colocado en el disco,`data:` URI),
 * El navegador tampoco puede obtenerlo. Aquí, coloque el disco de acuerdo con el hash de contenido para [imageDir] Si te registras nuevamente, el archivo con el mismo nombre ya existe y no se volverá a escribir.
 * Por lo tanto, el coste de redacción sólo se paga una vez por la misma imagen. Si el contenido no puede reconocer el formato multimedia, será rechazado. Evite escribir texto en archivos multimedia.
 */
internal class HostMediaStore(
    private val imageDir: File,
    private val voiceDir: File,
    /** Directorio de avatar asistente (`filesDir/avatar`);null Indica que los avatares no se proporcionan al público. */
    private val avatarDir: File? = null,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val lock = Any()

    /** camino canónico → token;accessOrder=true Es decir, LRU, las lecturas también se renuevan. */
    private val pathToToken = LinkedHashMap<String, String>(64, 0.75f, true)

    /** token → Estandarice la ruta para enrutar la verificación inversa. con [pathToToken] Eliminación sincrónica. */
    private val tokenToPath = HashMap<String, String>()

    /** Anclajes de directorio permitidos (rutas canónicas), corregidos después de la construcción. */
    private val anchors: List<File> = listOfNotNull(imageDir, voiceDir, avatarDir).mapNotNull { dir ->
        runCatching { dir.canonicalFile }.getOrNull()
    }

    /**
     * pon uno `file://` Reemplace la referencia o la ruta absoluta con un token.
     *
     * @return Devuelve nulo cuando la ruta está fuera de los límites, el archivo no existe o no es un archivo normal (la persona que llama elimina la referencia en consecuencia)
     */
    fun tokenForFile(reference: String): String? {
        val raw = reference.trim().removePrefix(FILE_SCHEME).trim()
        if (raw.isEmpty()) return null
        val candidate = anchored(raw) ?: return null
        return register(candidate)
    }

    /**
     * Coloque la imagen base64 en línea en el disco y regístrela.
     *
     * Nomenclatura de archivos `{ownerMessageId}_inline_{contentHash}.{ext}`: prefijo con [MessageImageStore] de
     * El archivo de descarga es el mismo. `{messageId}_` Forma, al borrar un mensaje [MessageImageStore.deleteByMessageId]
     * Escanear los prefijos existentes los borrará todos y no dejará huérfanos. El costo es que si aparece la misma imagen en dos mensajes, se almacenarán dos copias.
     * Pero a cambio, eliminar rutas no requiere contabilidad entre capas.
     *
     * @param declaredMime `data:` URI El tipo declarado ennull Indica base64 básica (solo puede determinarse por el encabezado del archivo)
     * @param ownerMessageId ID del mensaje de atribución, determina el prefijo al limpiar
     * @return Se devuelve cuando el formato multimedia no se puede decodificar, no se puede reconocer el formato multimedia o falla la escritura en el disco. null
     */
    fun tokenForInlineData(base64: String, declaredMime: String?, ownerMessageId: String): String? {
        val bytes = decodeInline(base64) ?: return null
        val mime = HostMediaContentType.detect(bytes) ?: normalizedMime(declaredMime)
        val extension = mime?.let(HostMediaContentType::extensionFor) ?: return null
        val file = File(imageDir, "${safeOwner(ownerMessageId)}_${INLINE_PREFIX}${contentHash(bytes)}.$extension")
        if (!file.isFile && !writeInline(file, bytes)) return null
        return anchored(file.absolutePath)?.let(::register)
    }

    /** Solo se dejan los caracteres seguros del nombre del archivo en la identificación de propiedad (la identificación del mensaje es un UUID y no se cambiará normalmente). */
    private fun safeOwner(ownerMessageId: String): String =
        ownerMessageId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(MAX_OWNER_CHARS)
            .ifEmpty { SHARED_OWNER }

    /** Solo vale la pena colocar base64 cuya longitud esté dentro del rango, decodificable y no vacía. */
    private fun decodeInline(base64: String): ByteArray? {
        if (base64.length < MIN_INLINE_BASE64_LENGTH || base64.length > MAX_INLINE_BASE64_LENGTH) return null
        val bytes = runCatching { Base64.getMimeDecoder().decode(base64) }.getOrNull() ?: return null
        return bytes.takeIf { it.isNotEmpty() && it.size <= MAX_INLINE_BYTES }
    }

    private fun normalizedMime(declared: String?): String? =
        declared?.lowercase(Locale.ROOT)?.substringBefore(';')

    /**
     * Verificación inversa del lado de la ruta:token → Archivos que todavía existen en el directorio permitido en este momento.
     *
     * Token desconocido, el archivo se ha limpiado y la ruta canónica ya no está en el directorio permitido, todos devuelven nulo (404 unificado externo,
     * No distinguir entre "inexistencia" y "transfronterizo" para evitar retroalimentación al detector).
     */
    fun resolve(token: String): File? {
        val path = synchronized(lock) { tokenToPath[token] } ?: return null
        // A pesar de la contrainspección, todavía dejamos anchored():En este momento, se incluyen la verificación del directorio y la verificación de existencia, y no se confía en la conclusión durante el registro.
        val candidate = anchored(path) ?: return null
        return candidate.also { synchronized(lock) { pathToToken[candidate.path] } }
    }

    /** Determine si el archivo se encuentra dentro del directorio permitido (incluidos los subdirectorios) y compárelo según la ruta estándar. */
    private fun isWithinAllowedDirs(canonicalFile: File): Boolean = anchors.any { anchor ->
        val base = anchor.path
        canonicalFile.path == base || canonicalFile.path.startsWith(base + File.separator)
    }

    /**
     * Mapeo preestablecido de prueba única: directamente [path] espera [token] Encendido, no se realiza la verificación de directorio y existencia.
     *
     * El propósito de su existencia es permitir que se verifiquen ramas del lado de lectura como "se ha establecido el mapeo, pero la ruta está fuera de los límites/el archivo ha desaparecido". ——
     * Este mapeo no se puede construir a través de la interfaz pública (el lado de registro lo rechazará) y la verificación en el lado de lectura es para proteger este estado.
     */
    internal fun seedMapping(token: String, path: String) {
        synchronized(lock) {
            tokenToPath[token] = path
            pathToToken[path] = token
        }
    }

    private fun register(canonical: File): String? {
        if (!isWithinAllowedDirs(canonical)) return null
        return synchronized(lock) {
            val existing = pathToToken[canonical.path]
            if (existing != null) {
                tokenToPath[existing] = canonical.path
                existing
            } else {
                val token = newToken()
                pathToToken[canonical.path] = token
                tokenToPath[token] = canonical.path
                evictOverflow()
                token
            }
        }
    }

    /** Eliminar LRU excedido [maxEntries] entrada y retirar su token al mismo tiempo. */
    private fun evictOverflow() {
        while (pathToToken.size > maxEntries) {
            val eldest = pathToToken.entries.firstOrNull() ?: return
            pathToToken.remove(eldest.key)
            tokenToPath.remove(eldest.value)
        }
    }

    /** Ruta canónica + archivo ordinario + verificación de directorio, cualquiera devolverá nulo. */
    private fun anchored(path: String): File? {
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        if (!candidate.isFile) return null
        if (!isWithinAllowedDirs(candidate)) return null
        return candidate
    }

    private fun writeInline(file: File, bytes: ByteArray): Boolean = runCatching {
        if (!imageDir.isDirectory && !imageDir.mkdirs()) error("Error al crear el directorio de imágenes")
        file.writeBytes(bytes)
        true
    }.getOrElse { error ->
        Logger.w(TAG, "Error al colocar medios en línea: ${error.message}")
        false
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    private fun contentHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(HASH_CHARS)

    internal companion object {
        private const val TAG = "HostMediaStore"

        /** LRU Límite de entrada. Ver notas de clase \"LRU límite superior\". */
        const val DEFAULT_MAX_ENTRIES = 2048

        private const val FILE_SCHEME = "file://"

        /** La parte media del nombre del archivo multimedia en línea, que es diferente de `{messageId}_{idx}_{hash}.bin` El cuadro de colocación ordinario. */
        private const val INLINE_PREFIX = "inline_"

        /** El prefijo predeterminado de la identificación de propiedad (aún se encuentra en el directorio de imágenes y no será eliminado por ningún mensaje). */
        private const val SHARED_OWNER = "inline"

        /** La cantidad máxima de caracteres que una identificación de propiedad puede contener en un nombre de archivo. */
        private const val MAX_OWNER_CHARS = 64

        /** Es poco probable que una base64 más corta que esta longitud sea una imagen y no vale la pena comercializarla. */
        private const val MIN_INLINE_BASE64_LENGTH = 32

        /** El límite superior es de aproximadamente 12 MB base64 (correspondiente a 9 MB binarios) y cualquier exceso de datos se considera datos anormales. */
        private const val MAX_INLINE_BASE64_LENGTH = 12 * 1024 * 1024

        /** Límite superior de bytes después de la decodificación (16MB) y el anterior son mutuamente excluyentes para evitar que una entrada larga detenga la decodificación. */
        private const val MAX_INLINE_BYTES = 16L * 1024 * 1024

        /** Longitud del hash del contenido del nombre de archivo en línea (64 bit), la misma imagen debe tener el mismo nombre para evitar ubicaciones duplicadas. */
        private const val HASH_CHARS = 16
    }
}
