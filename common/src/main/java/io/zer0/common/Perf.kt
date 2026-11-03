package io.zer0.common

/**
 * v1.56: Herramienta ligera de seguimiento del rendimiento.
 *
 * objetivos de diseño:
 *  - Cero dependencias(solo depende de Logger),Disponible en todos los módulos
 *  - Cero gastos generales:cuando está cerrado track() La lambda dentro del bloque todavía se ejecuta,Pero no cronometres ni registres
 *  - conciso:track("name") { ... } Hecho en una línea
 *  - controlable:Pase [enabled] Cambiar control global(Predeterminado true,release Se puede apagar)
 *
 * Ejemplo de uso:
 *  ```
 *  val result = Perf.track("rag-index") {
 *      ragService.indexDocument(docId, content, config)
 *  }
 *  ```
 *
 * Formato de registro: `[Perf] rag-index: 1234ms`
 * operación lenta(>500ms)Se grabará con nivel WARN,De lo contrario DEPURAR.
 */
object Perf {

    @Volatile
    var enabled: Boolean = true

    /** Umbral de operación lenta(ms),Superar este valor se registra con un nivel WARN. */
    const val SLOW_THRESHOLD_MS = 500L

    /**
     * Datos de rendimiento sink,para exterior(Como por ejemplo PerformanceReporter)Suscríbete para recibir pagos cada vez.
     *
     * La devolución de llamada se ejecuta sincrónicamente en el hilo que llama.,debe ser liviano(solo juez/Únete al equipo,IO asincrónico).
     * Internamente try-catch,Las excepciones no afectarán a la persona que llama oculta.
     */
    @Volatile
    var sink: ((name: String, elapsedMs: Long) -> Unit)? = null

    /** Registro enterrado de rendimiento único(suministro [snapshotRecent] salida,Utilizado para diagnóstico ANR y otros escenarios.). */
    data class PerfRecord(
        val name: String,
        val elapsedMs: Long,
        val timestamp: Long,
    )

    /** Capacidad del buffer circular de registro de puntos recientemente enterrados(Para que los diagnósticos ANR lean datos de rendimiento recientes). */
    private const val MAX_RECENT = 50

    /** Registro reciente de puntos enterrados.(Seguridad del hilo,Cerradura interna). */
    private val recentRecords = mutableListOf<PerfRecord>()

    /** Obtenga lo último [MAX_RECENT] Una instantánea de los registros de puntos enterrados(copia segura para subprocesos,Para uso diagnóstico ANR). */
    fun snapshotRecent(): List<PerfRecord> = synchronized(recentRecords) { recentRecords.toList() }

    /**
     * Ejecución cronometrada [block] Y registre el tiempo empleado.
     *
     * @param name Nombre de la operación(Como por ejemplo "rag-index" / "stream-first-token")
     * @param block bloque de código al tiempo
     * @return block valor de retorno
     */
    inline fun <T> track(name: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val elapsed = System.currentTimeMillis() - start
            log(name, elapsed)
        }
    }

    /**
     * Ejecución cronometrada suspend [block] Y registre el tiempo empleado.
     *
     * @param name Nombre de la operación
     * @param block suspender el bloque de código al tiempo
     * @return block valor de retorno
     */
    suspend inline fun <T> trackSuspend(name: String, crossinline block: suspend () -> T): T {
        if (!enabled) return block()
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val elapsed = System.currentTimeMillis() - start
            log(name, elapsed)
        }
    }

    /**
     * Registre manualmente un punto que requiere mucho tiempo(Se utiliza para escenarios donde es inconveniente envolverlo con pista.).
     *
     * @param name Nombre de la operación
     * @param elapsedMs Consume mucho tiempo(milisegundos)
     */
    fun log(name: String, elapsedMs: Long) {
        if (!enabled) return
        // Escribir en el búfer circular de registros recientes(Para diagnósticos ANR, etc. para leer datos de rendimiento recientes)
        synchronized(recentRecords) {
            recentRecords.add(PerfRecord(name, elapsedMs, System.currentTimeMillis()))
            if (recentRecords.size > MAX_RECENT) recentRecords.removeAt(0)
        }
        // Notificar externo sink(Para que PerformanceReporter se suscriba a operaciones lentas);try-catch Evitar que las excepciones del receptor afecten a la persona que llama
        sink?.let { cb ->
            try {
                cb(name, elapsedMs)
            } catch (t: Throwable) {
                Logger.w("Perf", "sink Excepción de devolución de llamada: ${t.message}", t)
            }
        }
        if (elapsedMs >= SLOW_THRESHOLD_MS) {
            Logger.w("Perf", "$name: ${elapsedMs}ms (slow)")
        } else {
            Logger.d("Perf", "$name: ${elapsedMs}ms")
        }
    }

    /**
     * Crear un temporizador(Se utiliza para escenarios que requieren segmentación.).
     *
     * Ejemplo:
     *  ```
     *  val t = Perf.start("stream-round")
     *  // ... primera etapa ...
     *  t.split("embedding")
     *  // ... segunda etapa ...
     *  t.split("search")
     *  // ... La tercera etapa ...
     *  t.end()  // Registrar el tiempo total + cada segmento
     *  ```
     */
    fun start(name: String): Timer = Timer(name)

    /**
     * Temporizador dividido.
     */
    class Timer internal constructor(private val name: String) {
        private val startTime = System.currentTimeMillis()
        private var lastSplitTime = startTime
        private val splits = mutableListOf<Pair<String, Long>>()

        /**
         * PD: No es que lleve mucho tiempo;"¿Cuántas veces ha sucedido esto en esta ronda?/cuantos milisegundos"Cuente campos como este.
         *
         * Razón de existencia:`Perf` solo ≥500ms Sólo los segmentos se ingresarán en el archivo de registro con WARN.
         * y `chat-first-token`(Por lo general, unos cientos de milisegundos) Este tipo de punto oculto no aparecerá en el registro en absoluto;
         * Cuélgalo hasta que se haya caído. `chat-turn` No puedo averiguarlo hasta que me conecte.
         */
        private val annotations = mutableListOf<String>()

        /** Agregue un campo de recuento que no requiera mucho tiempo (p. ej. firstToken=312,uiFlushes=7). */
        fun annotate(key: String, value: Any?) {
            if (!enabled) return
            annotations.add("$key=$value")
        }

        /**
         * Registre un punto de parada.
         * @param splitName Nombre del segmento(Como por ejemplo "embedding" / "search")
         */
        fun split(splitName: String) {
            if (!enabled) return
            val now = System.currentTimeMillis()
            splits.add(splitName to (now - lastSplitTime))
            lastSplitTime = now
        }

        /**
         * Temporizador de finalización,Registre el tiempo total empleado y cada segmento.
         */
        fun end() {
            if (!enabled) return
            val total = System.currentTimeMillis() - startTime
            if (splits.isEmpty()) {
                log(name, total)
            } else {
                val splitStr = (splits.joinToString(", ") { "${it.first}=${it.second}ms" } +
                    if (annotations.isEmpty()) "" else ", " + annotations.joinToString(", ")).trimStart(',', ' ')
                if (total >= SLOW_THRESHOLD_MS) {
                    Logger.w("Perf", "$name: ${total}ms (slow) [$splitStr]")
                } else {
                    Logger.d("Perf", "$name: ${total}ms [$splitStr]")
                }
            }
        }
    }
}
