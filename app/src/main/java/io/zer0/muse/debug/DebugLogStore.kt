package io.zer0.muse.debug

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Almacenamiento de memoria de registro de depuración — Búfer circular + archivos exportables.
 *
 * objetivos de diseño:
 *  - mantener reciente en la memoria [MAX_ENTRIES] registros,Más viejo que eliminado(Semántica del búfer de anillo)
 *  - Seguridad del hilo:[ConcurrentLinkedDeque] Admite escritura simultánea de subprocesos múltiples(Logger Llamar desde cualquier hilo)
 *  - con [Logger] desacoplamiento:Logger Push a través de devolución de llamada de receptor,Evite la dependencia inversa del módulo común del módulo de la aplicación
 *  - Admite exportación a archivo(Se utiliza para compartir/solucionar problemas)
 *
 * Uso:
 *  - Application.onCreate llamar [init] una vez,Inicialización completa de cacheDir
 *  - Logger.sink Empuje automáticamente cada registro para [log]
 *  - DebugScreen Pase [getAll] leer y mostrar
 *  - Usuario en el punto DebugScreen"Borrar"llamar [clear],"Exportar"llamar [exportToFile]
 */
object DebugLogStore {

    /**
     * límite de memoria intermedia,Más allá de eliminar gradualmente los más antiguos.
     *
     * Tome 2000 en lugar de 1000: un registro de depuración que se ejecuta durante 6 horas suele ser del orden de miles.,1000 Aparecerán barras al exportar
     * Exprime la primera escena,El problema suele empezar desde el principio.
     *
     * Estimación del límite de memoria(prensa [DebugLogEntry] estructura,64 bit + puntero comprimido):
     *  - El coste fijo por línea es de aprox. 50B(id / timestamp / Cuatro referencias + nodos ConcurrentLinkedDeque aprox. 32B);
     *  - tag y el nivel es una cadena constante,Básicamente reutilizado,Casi sin extra;La verdadera gran cosa es message;
     *  - entrada normal(Texto chino de unas 100 palabras.)aprox. 0.3–0.5KB → 2000 tratado 1MB;
     *  - Las entradas con seguimiento de pila se aproximan a una pila de 4 KB 4.5KB → 2000 Acuerdo total 9MB;8KB La pila es de unos 17 MB.
     *
     * Ese es el peor de los casos.(Cada elemento tiene una pila de excepciones.)Del orden de 20 MB. Lógica de eliminación ver [log]:Cada escritura en estado estable.
     * solo [ConcurrentLinkedDeque.pollFirst] una pieza,La longitud del buffer siempre está cerca del límite superior.,no aparecerá
     * "Primero aumente a 2N y luego córtelo por la mitad de una vez", una especie de pico de memoria.
     */
    private const val MAX_ENTRIES = 2000

    /** Nivel de registro constante(Alinear con Logger.Level,Pero use cadenas para evitar dependencias entre módulos.). */
    const val LEVEL_DEBUG = "DEBUG"
    const val LEVEL_INFO = "INFO"
    const val LEVEL_WARN = "WARN"
    const val LEVEL_ERROR = "ERROR"

    /** Todos los niveles(Para uso con filtros de UI,El orden es consistente con la pantalla.). */
    val LEVELS: List<String> = listOf(LEVEL_DEBUG, LEVEL_INFO, LEVEL_WARN, LEVEL_ERROR)

    /** Deque seguro para subprocesos,El último es el último. */
    private val buffer = ConcurrentLinkedDeque<DebugLogEntry>()

    /**
     * Número de buffers (2026-11-01 mejoras de rendimiento).
     *
     * `ConcurrentLinkedDeque.size()` si O(n) Recorrido: cada vez que se escribe el registro, se atraviesan 2000 nodos para realizar una comparación.
     * Aquí reemplácelo con un recuento de incremento automático. —— Puede haber desviaciones temporales bajo la concurrencia. ±número de hilos, pero para"Si se supera el límite superior, se eliminará el más antiguo."Sin impacto.
     */
    private val sizeCounter = AtomicInteger(0)

    /**
     * v1.0.28: Generador de identificación de incremento automático global,Asigne una identificación única a cada registro.
     *
     * clave de elemento para DebugScreen LazyColumn: anterior `timestamp_tag_messageHash`
     * combinado en"El mismo contenido se registra en el mismo milisegundo"(Por ejemplo, MemoryFileWriter se activa varias veces al mismo tiempo. memory.md updated)
     * entrará en conflicto y provocará un bloqueo de IllegalArgumentException. Utilice una identificación de incremento automático en su lugar para garantizar la unicidad,
     * El mismo artículo se puede reconocer de forma estable.(El estado ampliado no se confundirá debido a la adición o eliminación de la lista.).
     */
    private val idGenerator = AtomicInteger(0)

    /** Directorio para exportar archivos de registro(cacheDir/logs,En el mismo directorio que el registro del archivo Logger,Desinstalar la limpieza automática). */
    @Volatile
    private var exportDir: File? = null

    /** Exportar formato de marca de tiempo del nombre del archivo(Evite conflictos de nombres de archivos + facilite la clasificación por tiempo). */
    private val exportDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Inicialice el directorio de exportación. Llamado una vez en Application.onCreate.
     *
     * @param context arbitrario Context,tomar internamente cacheDir
     */
    fun init(context: Context) {
        val dir = File(context.cacheDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        exportDir = dir
    }

    /**
     * Adjunte un registro.Logger.sink Entrada de devolución de llamada.
     *
     * @param level nivel(DEBUG / INFO / WARN / ERROR)
     * @param tag   etiqueta(nombre del módulo)
     * @param message texto del mensaje(Desinfectado a través de Logger.sanitizePii)
     * @param throwable excepción opcional;Convertir al almacenamiento de cadenas de seguimiento de pila cuando no sea nulo
     */
    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val entry = DebugLogEntry(
            id = idGenerator.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable?.let { stackTraceToString(it) },
        )
        buffer.addLast(entry)
        // Cuando se excede el límite superior, el más antiguo(ConcurrentLinkedDeque Sin operaciones atómicas como recortar.,Es seguro agregar primero y luego recortar).
        // Variable de incremento automático para contar:No más llamadas a cada línea O(n) de size().
        var current = sizeCounter.incrementAndGet()
        while (current > MAX_ENTRIES) {
            if (buffer.pollFirst() == null) break
            current = sizeCounter.decrementAndGet()
        }
    }

    /**
     * Obtener todos los registros(orden ascendente por tiempo,viejo → nuevo).UI visualización directa,Nuevo registro en la parte inferior.
     */
    fun getAll(): List<DebugLogEntry> = buffer.toList()

    /** El número actual de entradas del búfer. */
    fun size(): Int = sizeCounter.get()

    /** Borre todos los registros. */
    fun clear() {
        buffer.clear()
        sizeCounter.set(0)
    }

    /**
     * Exporte todos los registros en el búfer actual a un archivo.
     *
     * ruta del archivo:`cacheDir/logs/debug_export_yyyyMMdd_HHmmss.txt`,
     * Expuesto a través de FileProvider,Se puede compartir directamente.
     *
     * @return Archivo exportado;si [init] Devuelto si no se llama o no se escribe null
     */
    fun exportToFile(): File? {
        val dir = exportDir ?: return null
        val entries = buffer.toList()
        if (entries.isEmpty()) return null
        val fileName = "debug_export_${exportDateFormat.format(Date())}.txt"
        val file = File(dir, fileName)
        return try {
            file.bufferedWriter().use { writer ->
                entries.forEach { entry ->
                    writer.write(entry.formatLine())
                    writer.newLine()
                }
            }
            file
        } catch (t: Throwable) {
            // El error de exportación no puede afectar el proceso principal,Retorno de degradación null(DebugScreen Se le solicitará al usuario)
            null
        }
    }

    /** Throwable → stack trace herramientas de cuerda,Consistente con la implementación de Logger.stackTraceToString. */
    private fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { pw -> t.printStackTrace(pw) }
        return sw.toString()
    }
}

/**
 * Una única entrada del registro de depuración.
 *
 * @param timestamp marca de tiempo de milisegundos
 * @param level     nivel(DEBUG / INFO / WARN / ERROR)
 * @param tag       etiqueta
 * @param message   texto del mensaje(Insensibilizado)
 * @param throwable Cadena de seguimiento de pila opcional;Cuando no es nula, la interfaz de usuario será visible después de la expansión.
 */
data class DebugLogEntry(
    /**
     * v1.0.28: Autoincremento único global id(por [DebugLogStore.idGenerator] asignar).
     *
     * usado para UI LazyColumn item key,Evite conflictos entre claves de registro con el mismo milisegundo y el mismo contenido, lo que provocaría fallos.
     */
    val id: Int,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    val throwable: String? = null,
) {
    /** Formato de una sola línea al exportar archivos:nivel de marca de tiempo/etiqueta: noticias [throwable]. */
    fun formatLine(): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        val levelShort = when (level) {
            DebugLogStore.LEVEL_DEBUG -> "D"
            DebugLogStore.LEVEL_INFO -> "I"
            DebugLogStore.LEVEL_WARN -> "W"
            DebugLogStore.LEVEL_ERROR -> "E"
            else -> level.firstOrNull() ?: '?'
        }
        val base = "$ts $levelShort/$tag: $message"
        return if (throwable.isNullOrEmpty()) base else "$base\n$throwable"
    }
}
