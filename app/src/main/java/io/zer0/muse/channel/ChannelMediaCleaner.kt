package io.zer0.muse.channel

import io.zer0.common.Logger
import java.io.File

/**
 * v2.x cierre del legado:filesDir/channel_media/ Limpiador.
 *
 * fondo:Vídeo entrante de WeChat/Después de colocar el archivo en filesDir/channel_media/,Este directorio no tiene ningún mecanismo de limpieza previo.,
 * Continuará creciendo con el uso a largo plazo. Las referencias de mensajes a medios existen sólo en [ChannelInbox] / [ChannelConversationStore]
 * (Desplazamiento retenido, puede borrarse),no se puede determinar de manera confiable"Si todavía se hace referencia a un archivo",Así que adopta una estrategia conservadora.:
 *  - TTL Ten todo en cuenta:última modificación antes [DEFAULT_TTL_MS](30 dia)Todos los archivos serán eliminados.,Ventana de enfriamiento lo suficientemente larga para evitar la eliminación accidental;
 *  - Límite de volumen:El tamaño total de los archivos restantes supera [DEFAULT_MAX_TOTAL_BYTES] tiempo,prensa LRU(No modificado durante mucho tiempo)Eliminado hasta el límite superior.
 *
 * unión de los dos(Eliminar cualquier golpe). Solo llámalo al inicio(ver MuseApp.onCreate).
 *
 * [selectForDeletion] es una función pura(Conveniente para pruebas individuales),El archivo IO está envuelto en [cleanup].
 */
object ChannelMediaCleaner {

    private const val TAG = "ChannelMediaCleaner"

    /**
     * TTL Ten todo en cuenta:30 Días.
     *
     * Elija 30 días en lugar de menos,Es porque no se puede determinar la relación de referencia del mensaje. —— TTL lo suficientemente largo para garantizar la reproducción normal de medios antiguos
     * No eliminado accidentalmente,solo en obvio"obsoleto"Sólo entonces se recicla.
     */
    const val DEFAULT_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** Tamaño máximo total del directorio:512MB(Puede acomodar aproximadamente docenas de videos grandes;Después de exceder, presione LRU para eliminar). */
    const val DEFAULT_MAX_TOTAL_BYTES: Long = 512L * 1024 * 1024

    /** Metainformación de un solo archivo(Limpiar la entrada de juicio). */
    data class FileMeta(val path: String, val lastModified: Long, val size: Long)

    /**
     * función pura:Seleccione la ruta del archivo que se eliminará según la política(No toques el sistema de archivos.,Conveniente para pruebas individuales).
     *
     * 1. TTL rueda:`now - lastModified > ttlMs` Todos los archivos están seleccionados.(Los candidatos restantes pasan a la segunda ronda).
     * 2. rueda de volumen:botones candidatos restantes `lastModified` orden ascendente(Se da prioridad a los que llevan más tiempo sin modificarse.)acumular,súper [maxTotalBytes] de eliminación.
     *
     * @param files Metainformación del archivo en el directorio actual
     * @param now marca de tiempo actual(milisegundos)
     * @param ttlMs TTL umbral(milisegundos)
     * @param maxTotalBytes Bytes totales máximos de archivos restantes
     * @return Lista de rutas de archivos que se eliminarán(Eliminar duplicados)
     */
    fun selectForDeletion(
        files: List<FileMeta>,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        protectedPaths: Set<String> = emptySet(),
    ): List<String> {
        if (files.isEmpty()) return emptyList()
        val toDelete = LinkedHashSet<String>()
        val remaining = ArrayList<FileMeta>(files.size)
        for (file in files) {
            if (file.path in protectedPaths) {
                continue
            }
            if (now - file.lastModified > ttlMs) {
                toDelete.add(file.path)
            } else {
                remaining.add(file)
            }
        }
        var total = 0L
        for (file in remaining) total += file.size
        if (total > maxTotalBytes) {
            for (file in remaining.sortedBy { it.lastModified }) {
                if (total <= maxTotalBytes) break
                toDelete.add(file.path)
                total -= file.size
            }
        }
        return toDelete.toList()
    }

    /**
     * Escanear [dir] archivos bajo,prensa [selectForDeletion] Eliminación de políticas,Devuelve el número de eliminaciones.
     *
     * Regresa silenciosamente si el directorio no existe o no se puede leer. 0(No lanzar excepción,Evite interrumpir el proceso de inicio).
     */
    fun cleanup(
        dir: File,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        protectedPaths: Set<String> = emptySet(),
    ): Int {
        if (!dir.isDirectory) return 0
        val metas = dir.listFiles { f -> f.isFile }?.map {
            FileMeta(it.absolutePath, it.lastModified(), it.length())
        }.orEmpty()
        val victims = selectForDeletion(metas, now, ttlMs, maxTotalBytes, protectedPaths)
        var deleted = 0
        for (path in victims) {
            runCatching { if (File(path).delete()) deleted++ }
        }
        if (deleted > 0) Logger.i(TAG, "channel_media limpiar:Eliminar $deleted archivos")
        return deleted
    }
}
