package io.zer0.muse.terminal

import io.zer0.common.Logger

/**
 * v2.x Fase terminal II:PTY puente(native autocompilado,ver src/main/jni/pty.c).
 *
 * Toda la semántica fd adopta funciones nativas.(Sin experiencia en lectura o escritura. FileDescriptor),Evite las restricciones de API ocultas.
 * [available] Cuando la biblioteca nativa no está lista false,La persona que llama debe revertir el motor de canalización.
 */
object Pty {

    private const val TAG = "MusePty"

    /** native Motivo del error al cargar la biblioteca(para diagnóstico;Cargando exitosamente como null). */
    val loadError: String? = runCatching {
        System.loadLibrary("pty")
        null
    }.getOrElse { t ->
        val message = "libpty.so Error al cargar: ${t.message}"
        runCatching { Logger.w(TAG, message) }
        message
    }

    /** native ¿La biblioteca se cargó correctamente?(NDK La construcción que no está conectada es false). */
    val available: Boolean = loadError == null

    /**
     * Cree un subproceso pseudo-terminal.
     * @return extremo principal fd(>=0);-1 por falla. Escritura pid del proceso secundario pidArray[0].
     * @param extraPath P1-B: Directorio adjunto al comienzo de PATH(tiempo de ejecución bin);cadena vacía=Sin agregar.
     * @param preloadLib P1-B: LD_PRELOAD El camino de la cuña ejecutiva;cadena vacía=No establecido.
     */
    external fun createSubprocess(execPath: String, cwd: String, pidArray: IntArray, extraPath: String, preloadLib: String): Int

    /** Bloquear la espera de que salga el proceso secundario,devolver código de salida(señal terminada 128+signo;falló -1). */
    external fun waitFor(pid: Int): Int

    /** Leer del maestro(bloqueando);<=0 Indica EOF/Error. */
    external fun read(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    /** escribir al maestro;Devuelve el número de bytes escritos.(<0 error). */
    external fun write(fd: Int, data: ByteArray, offset: Int, length: Int): Int

    /** Número de bytes legibles por el host;Error -1. */
    external fun available(fd: Int): Int

    /** Tamaño de la ventana de sincronización(Para la composición tipográfica del programa en pantalla completa). */
    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    /** Cerrar terminal principal fd(Se utiliza para terminar la sesión.). */
    external fun closeFd(fd: Int)

    /** llamada al sistema más reciente errno(Para depuración y diagnóstico.). */
    external fun errnoValue(): Int
}
