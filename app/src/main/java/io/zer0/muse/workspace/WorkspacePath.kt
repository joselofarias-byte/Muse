package io.zer0.muse.workspace

/**
 * Una ruta de espacio de trabajo resuelta.
 *
 * @param mountId ID de montaje de destino; si es nulo, significa que no se escribe ningún prefijo y se utiliza el montaje predeterminado.
 * @param relative A la ruta relativa al directorio raíz de montaje se le han eliminado el primer y el último separador, y el directorio raíz es una cadena vacía.
 */
data class WorkspacePath(
    val mountId: String?,
    val relative: String,
) {
    /** Ya sea para montar el directorio raíz. */
    val isMountRoot: Boolean get() = relative.isEmpty()
}

/**
 * Sintaxis de la ruta del espacio de trabajo:`ID de montaje:/ruta relativa`.
 *
 * Todas las rutas sin prefijos caerán en el montaje predeterminado, lo que garantiza que los puntos de llamada antiguos, los datos antiguos y los hábitos del modelo antiguo funcionen como de costumbre.
 * El prefijo solo reconoce los ID de montaje registrados:`álbum de fotos:/a.jpg` Este prefijo chino no se cuenta como montaje y se tratará como una ruta relativa normal.
 */
object WorkspacePathParser {
    /** Separador entre prefijo de montaje y ruta relativa. */
    const val PREFIX_SEPARATOR: String = ":/"

    /** El ID de montaje predeterminado utilizado sin un prefijo de montaje. */
    const val DEFAULT_MOUNT_ID: String = WorkspaceMount.MAIN_ID

    /**
     * Analizar un camino.
     *
     * @param raw Camino original, posiblemente con `mount:/` Prefijo, que también puede ser una cadena vacía (directorio raíz)
     * @param knownMountIds Una colección de ID de montaje registrados actualmente, que se utiliza para determinar si el prefijo es válido.
     */
    fun parse(raw: String, knownMountIds: Set<String>): WorkspacePath {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "/") {
            return WorkspacePath(null, "")
        }
        val colon = trimmed.indexOf(':')
        if (colon > 0) {
            val prefix = trimmed.substring(0, colon)
            if (prefix in knownMountIds) {
                return WorkspacePath(prefix, normalize(trimmed.substring(colon + 1)))
            }
        }
        return WorkspacePath(null, normalize(trimmed))
    }

    /**
     * Normalizar rutas relativas: eliminar el primer y último delimitador y fusionar delimitadores repetidos.
     *
     * no lo hagas `..` digerir——La persona que llama debe realizar nuevamente la determinación de fuera de límites después de unirla a la ruta real.
     * De lo contrario `a/../../b` Este tipo de escritura será "blanqueada" durante la etapa de estandarización.
     */
    fun normalize(relative: String): String = relative.trim().trim('/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    /** Vuelva a colocar el ID de montaje y la ruta relativa en una ruta legible. */
    fun format(mountId: String, relative: String): String {
        val rel = normalize(relative)
        return if (rel.isEmpty()) "$mountId$PREFIX_SEPARATOR" else "$mountId$PREFIX_SEPARATOR$rel"
    }

    /** Si la ruta contiene segmentos fuera de límites. */
    fun hasParentSegment(relative: String): Boolean = relative.split('/').any { it == ".." }
}
