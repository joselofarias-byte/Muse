package io.zer0.muse.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mutex en proceso asignado por ruta.
 *
 * La granularidad es "un archivo": leer la misma ruta—cambiar—Escriba en serie y se escriben diferentes rutas en paralelo. El espacio de trabajo es multisesión,
 * Es compartido por subagentes y tareas programadas. Cuando dos lugares modifican el mismo archivo al mismo tiempo, si cada uno lee el suyo y luego escribe el suyo,
 * El que se escriba más tarde sobrescribirá todo el párrafo escrito primero y ambas partes obtendrán "éxito".
 *
 * primer meridiano clave [WorkspacePathParser] Normalizar (`a.txt`,`./a.txt`,`/a.txt` caer en la misma clave),
 * Luego dóblelo en minúsculas como un todo: en un sistema de archivos que no distingue entre mayúsculas y minúsculas `A.TXT` con `a.txt` es el mismo archivo,
 * Se debe compartir un candado; en sistemas de archivos que distinguen entre mayúsculas y minúsculas, se pueden colocar hasta dos archivos diferentes en un candado.
 * Sólo se pierde un poco de paralelismo y no se pierde ninguna actualización.
 *
 * Limitaciones conocidas: la clave es "ID de montaje + ruta relativa", no se realiza ningún análisis de canonicalPath —— SAF La montura no se pudo obtener originalmente.
 * Para rutas reales, solo fusionar montajes locales hará que "cuándo fusionar y cuándo no fusionar" sea impredecible;
 * Por lo tanto, cuando un alias de enlace simbólico y dos montajes apuntan al mismo directorio físico, cada uno toma su propio bloqueo y no se garantiza la exclusión mutua.
 *
 * La tabla de bloqueo está en proceso, presione [WorkspaceManager] Instancia aislada. Actualmente solo hay `AppKoinModule` El singleton en
 * Participar por escrito; Si se produce una segunda instancia de participación por escrito en el futuro, las dos tablas de bloqueo no se conocerán entre sí. —— Antes de crear un nuevo administrador para escribir archivos,
 * Reutilícelo o lleve la tabla de bloqueo al ámbito compartido; de lo contrario, se perderán el tipo de actualizaciones que esta clase está diseñada para evitar.
 *
 * Esta clase es una tabla dentro de un solo proceso y no cruza procesos ni se coloca en el disco.
 *
 * @param acquireTimeoutMs Límite superior de bloqueo de espera (milisegundos); tirar cuando se agota el tiempo [WorkspaceIoException] En lugar de esperar infinitamente,
 *   Una persona que llama y no ha liberado el bloqueo solo ralentizará su propia solicitud y no bloqueará permanentemente las escrituras posteriores.
 */
internal class WorkspacePathLock(
    private val acquireTimeoutMs: Long = DEFAULT_ACQUIRE_TIMEOUT_MS,
) {
    /** Bloquee una clave con el recuento de participantes. */
    private class Entry {
        val mutex = Mutex()

        /** Titulares + número total de camareros; se elimina de la tabla cuando se restablece a cero, para evitar que la tabla se amplíe a medida que se utilizan más rutas. */
        var participants = 0
    }

    /** proteger [entries] sí mismo. */
    private val registry = Mutex()

    private val entries = HashMap<String, Entry>()

    /**
     * Ejecutar dentro del bloqueo de la ruta de destino. [block].
     *
     * El bloqueo no es reentrante: no llame a métodos de bloqueo dentro del bloque de retención de bloqueo (consulte [WorkspaceManager] acuerdo).
     *
     * @param mountId ID de montaje de destino
     * @param relative Ruta relativa dentro del monte
     * @param display La ruta que se muestra a los usuarios, solo se utiliza para la redacción de textos publicitarios con tiempo de espera.
     */
    suspend fun <T> withLock(mountId: String, relative: String, display: String, block: suspend () -> T): T {
        val key = keyOf(mountId, relative)
        val entry = registry.withLock { entries.getOrPut(key) { Entry() }.also { it.participants++ } }
        val acquired = try {
            withTimeoutOrNull(acquireTimeoutMs) { entry.mutex.lock() }
        } catch (e: CancellationException) {
            release(key, entry)
            throw e
        }
        if (acquired == null) {
            release(key, entry)
            throw WorkspaceIoException(
                "Esperando que se agote el tiempo de bloqueo del archivo(${acquireTimeoutMs}ms): Otra sesión o tarea está escribiendo \"$display\"," +
                    "No ejecutado esta vez,Por favor inténtalo de nuevo más tarde.",
            )
        }
        try {
            return block()
        } finally {
            entry.mutex.unlock()
            release(key, entry)
        }
    }

    /** Tecla de bloqueo normalizada: identificación de montaje + ruta relativa normalizada, todo en minúsculas. */
    fun keyOf(mountId: String, relative: String): String = WorkspacePathParser.format(mountId, relative).lowercase()

    /** El participante sale; la mesa solo retiene la llave mientras alguien más la sostiene o espera. */
    private suspend fun release(key: String, entry: Entry) {
        withContext(NonCancellable) {
            registry.withLock {
                entry.participants--
                if (entry.participants <= 0 && entries[key] === entry) entries.remove(key)
            }
        }
    }

    companion object {
        /** Límite superior predeterminado de bloqueo de espera:15 segundos. Una sola escritura tarda milisegundos. Esperar tanto tiempo significa que la excepción de la persona que llama no se ha liberado. */
        const val DEFAULT_ACQUIRE_TIMEOUT_MS: Long = 15_000L
    }
}
