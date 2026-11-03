package io.zer0.muse.crash

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.zer0.common.Logger
import io.zer0.common.Perf
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.audit.AuditLogger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * ANR(La aplicación no responde)detector.
 *
 * mecanismo de detección(v2.3.2 de,Detalles del fallo ver [AnrDetector]):
 *  - Hilo de demonio independiente "AnrWatcher" cada [CHECK_INTERVAL_MS] Entregar un controlador al hilo principal Ping
 *  - Ping Cuando se ejecuta el hilo principal [pongCount] más uno;**El fallo sólo depende de si el recuento ha avanzado.**
 *    (ya no se usa"marca de tiempo desde la última respuesta"hacer resta:Cuando el hilo guardián se cuelga o muere de hambre,Ese algoritmo
 *    Ese período de tiempo se calculó mal porque el hilo principal estaba bloqueado. —— esto es 2026-09-27~29 cinco porciones anr_*.txt La causa directa de los falsos positivos.)
 *  - hilo principal continuo [ANR_TIMEOUT_MS] Sentencia sin ejecutar Ping ANR
 *  - Cuando el tiempo real necesario para esta ronda excede con creces el intervalo esperado(overshoot > [SUSPEND_TOLERANCE_MS]),Esto demuestra que el propio organismo de control no puede
 *    despierta a tiempo(El proceso está congelado/Profundidad Doze)→ considerado como"No se puede determinar esta ronda",Restablecer la línea de base sin informar ANR
 *
 * ANR colección de tiempo:
 *  - Todas las pilas de hilos(hilo principal + todos los demás hilos)
 *  - estado de la memoria(Runtime.totalMemory / freeMemory / maxMemory + Uso del montón)
 *  - Registros enterrados de rendimiento recientes([Perf.snapshotRecent])
 *
 * ANR Escritura de registros filesDir/crash/anr_{timestamp}.txt(con [MuseCrashHandler] Mismo directorio),
 * y reutilizar [MuseCrashHandler.redactSensitive] pila de desensibilización(Evite la fuga de token/apiKey);
 * grabado al mismo tiempo [AuditLogger](Si está disponible)con [Logger.e].
 *
 * Diseño seguro(evitar autoinfligido ANR):
 *  - Hilo de demonio independiente,No depende del hilo principal. Looper,No bloquea el hilo principal en sí.
 *  - Utilizado por el controlador del hilo principal**fuerte referencia**mantener:solo se sostiene Looper/MessageQueue,No retenido Context/Activity,
 *    no goteará;GC reciclará las versiones anteriores conservadas con WeakReference,Conduciendo a un fracaso silencioso del puesto y a un error de juicio permanente
 *    (Para razones y evidencia ver [mainHandler] Comentario)
 *  - Comprobar prioridad del hilo [Thread.MIN_PRIORITY],Reducir el impacto en la programación normal
 *  - ANR Más tarde por [AnrDetector] Eliminar duplicados,Evite las tormentas de troncos
 *  - Todo en el bucle try-catch,Cualquier excepción no debe provocar que el hilo de detección se cierre inesperadamente.
 *
 * Interruptor configurable:[SettingsRepository.anrDetectionCache](Predeterminado true),Pase [SettingsRepository] Lecturas de caché sincrónicas,
 * Admite cambio de tiempo de ejecución(Después de que el usuario cierra la página de configuración,El juicio se detendrá en el siguiente ciclo de detección.).
 *
 * @param appContext contexto de aplicación(utilizado para posicionamiento filesDir/crash)
 * @param settings Configurar almacén(Leer un interruptor de detección)
 * @param auditLogger Registrador de auditoría(Opcional,Omite registros de auditoría cuando son nulos)
 */
class AnrWatcher(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val auditLogger: AuditLogger? = null,
) {
    // Controlador de hilo principal.
    //
    // v2.3.2 Reparación:Anteriormente lo tenía con WeakReference,Y el Handler no tiene referencias sólidas.
    // (Comentario original"El hilo principal Looper cambia con el ciclo de vida del proceso.,En realidad no será reciclado"Confundir a Looper con Handler ——
    //  El nivel de proceso es Looper,Handler Sólo un objeto ordinario),No hay ningún mensaje que apunte a él en la cola de mensajes entre los dos pings.
    // Message será reciclado por GC;Luego, la publicación caducará silenciosamente y la marca de tiempo de respuesta se congelará permanentemente.,Un ANR falso positivo por proceso.
    // Handler sólo espera Looper/MessageQueue,No retenido Context/Activity,Las referencias sólidas no se filtrarán.
    private val mainHandler = Handler(Looper.getMainLooper())

    // Número de pings realizados por el hilo principal(monótonamente creciente).
    // v2.3.2: Se cambia la sentencia a"¿Avanza el conteo?",ya no se usa"marca de tiempo desde la última respuesta"hacer resta ——
    // Este último está suspendido en el propio hilo de vigilancia./Cuando muera de hambre, ese tiempo se calculará mal como el bloqueo del hilo principal.
    private val pongCount = AtomicLong(0)

    // bandera corriendo(stop ajuste de tiempo false,El hilo de detección de notificaciones sale)
    @Volatile
    private var running: Boolean = false

    // referencia del hilo del demonio(stop tiempo interrupt + join)
    private var watcherThread: Thread? = null

    // Ping:Publicar en el hilo principal,Si se puede ejecutar, significa que el hilo principal no está bloqueado.(Contado por [AnrDetector] Comparar)
    private val ping = Runnable { pongCount.incrementAndGet() }

    /**
     * Inicie la detección de ANR.
     *
     * si [SettingsRepository.anrDetectionCache] == false Luego vuelve directamente a no arrancar.
     * El hilo del demonio continuará ejecutándose hasta [stop] se llama o el proceso finaliza.
     */
    fun start() {
        if (!settings.anrDetectionCache) {
            Logger.i(TAG, "ANR La detección se ha desactivado a través de la configuración,no comienza")
            return
        }
        if (running) return
        running = true
        watcherThread = Thread({ watchLoop() }, THREAD_NAME).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY // baja prioridad,Reducir el impacto en la programación normal
            start()
        }
        Logger.i(TAG, "ANR Detección iniciada(check=${CHECK_INTERVAL_MS}ms, timeout=${ANR_TIMEOUT_MS}ms)")
    }

    /** Detener la detección de ANR(Interrumpir hilo de demonio). */
    fun stop() {
        running = false
        watcherThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        watcherThread = null
        Logger.i(TAG, "ANR La detección se ha detenido")
    }

    /**
     * ciclo de prueba:cada [CHECK_INTERVAL_MS] Publica un ping y presiona [AnrDetector] La conclusión determina si es ANR.
     *
     * v2.3.2: La entrada de juicio se cambia a(ticket contar, El tiempo real necesario para esta ronda.),No más restar marcas de tiempo ——
     * Ver detalles [AnrDetector] con [mainHandler] Nota.
     *
     * Todo en el bucle try-catch,Cualquier excepción no debe provocar que el hilo de detección se cierre inesperadamente.(De lo contrario, la detección de ANR falla silenciosamente.).
     */
    private fun watchLoop() {
        val detector =
            AnrDetector(
                timeoutMs = ANR_TIMEOUT_MS,
                expectedIntervalMs = CHECK_INTERVAL_MS,
                suspendToleranceMs = SUSPEND_TOLERANCE_MS,
            )
        while (running) {
            try {
                // interruptor de doble control:Admite cambiar de configuración en tiempo de ejecución
                if (!settings.anrDetectionCache) {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    continue
                }
                // Entregar ping al hilo principal(sin bloqueo,Regresar inmediatamente).post El error indica que la cola de mensajes del hilo principal no está disponible,
                // No se emitirá ningún juicio en esta ronda.(De lo contrario será"ping No voté en absoluto"Se calculó mal porque el hilo principal no responde).
                val postedAt = SystemClock.elapsedRealtime()
                val posted = mainHandler.post(ping)
                if (!posted) {
                    Logger.w(TAG, "Ping Entrega fallida(La cola de mensajes del hilo principal no está disponible),Saltar el juicio en esta ronda")
                }
                // Espere un ciclo de detección
                Thread.sleep(CHECK_INTERVAL_MS)
                if (posted) {
                    handleDecision(detector.onCheck(SystemClock.elapsedRealtime(), postedAt, pongCount.get()))
                }
            } catch (e: InterruptedException) {
                // stop() interrupción activada,Salir del bucle normalmente
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                // Cualquier excepción no puede hacer que el hilo de detección salga.(De lo contrario, la detección de ANR falla silenciosamente.)
                Logger.w(TAG, "ANR Detectar anomalías en el bucle: ${t.message}", t)
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    /**
     * Procesar la conclusión de una ronda de pruebas.(v2.3.2: de [watchLoop] Sácalo,Evite demasiadas ramas en el bucle para activar el control de puerta de complejidad).
     *
     * [AnrDetector.Decision.Blocked] Determinar si realizar el pedido según el calibre de v1.0.53:
     * App El sistema congela el hilo principal mientras está en segundo plano./Doze,No responder durante mucho tiempo es un comportamiento normal.,no lo recuerdo ANR
     * (periodo de congelación"Regreso a la recepción para la primera ronda."Los falsos positivos son causados por [AnrDetector] El fallo de colgar está bloqueado).
     */
    private fun handleDecision(decision: AnrDetector.Decision) {
        when (decision) {
            is AnrDetector.Decision.Blocked -> {
                if (isAppInForeground()) {
                    onAnrDetected(decision.silenceMs)
                } else {
                    Logger.d(TAG, "El hilo principal no responde ${decision.silenceMs}ms(Aplicación en segundo plano,no lo recuerdo ANR)")
                }
            }
            AnrDetector.Decision.Suspended -> {
                Logger.d(TAG, "Detectar que el hilo está suspendido en esta ronda.(congelación del proceso/Doze),Restablecimiento de la línea base de ANR")
            }
            AnrDetector.Decision.Suppressed, AnrDetector.Decision.Healthy -> Unit
        }
    }

    /**
     * ANR Qué hacer cuando lo detectan.
     *
     * Recopilar pila de subprocesos + memoria + registro de rendimiento,escribir anr_{timestamp}.txt,
     * y grabado en AuditLogger(Si está disponible)con Logger.e.
     *
     * @param silenceMs Hilo principal sin tiempo de respuesta(milisegundos)
     */
    private fun onAnrDetected(silenceMs: Long) {
        val anrTime = System.currentTimeMillis()
        Logger.e(TAG, "detectado ANR! El hilo principal no responde ${silenceMs}ms")
        // Escribir en el archivo de registro ANR(Desechar después de la desensibilización,El fracaso no tira)
        resultOf { writeAnrLog(anrTime, silenceMs) }
            .onError { msg, t -> Logger.e(TAG, "Error al escribir en el registro ANR: $msg", t) }
        // Iniciar sesión en el registro de auditoría(Si está disponible,fire-and-forget)
        resultOf {
            auditLogger?.log(
                category = "system",
                action = "anr_detected",
                target = "",
                detail = mapOf(
                    "silence_ms" to silenceMs,
                    "time" to anrTime,
                ),
                success = false,
            )
        }
    }

    /**
     * Escribe el registro ANR en filesDir/crash/anr_{timestamp}.txt.
     *
     * Recopilar contenido:
     *  - Información del dispositivo(Brand/Model/Android/ABI)
     *  - estado de la memoria(pila Used/Free/Total/Max/Usage%)
     *  - pila de hilo principal(name/id/state + stackTrace)
     *  - Todas las pilas de hilos(Thread.getAllStackTraces)
     *  - Registros enterrados de rendimiento recientes([Perf.snapshotRecent])
     *
     * Reutilizar después de escribir en el disco [MuseCrashHandler.redactSensitive] Desensibilización,Evite fugas de token/apiKey en la pila;
     * y limpiar registros ANR antiguos(mantener reciente [MAX_ANR_LOGS] porción).
     *
     * @param anrTime ANR Marca de tiempo de ocurrencia
     * @param silenceMs Hilo principal sin tiempo de respuesta(milisegundos)
     */
    private fun writeAnrLog(anrTime: Long, silenceMs: Long) {
        // v1.0.53: escribe primero externalFilesDir(/sdcard/Android/data/io.zer0.muse/files/crash/),
        //   adb Con administrador de archivos directamente accesible;external Recurrir al directorio de archivos privado cuando no esté disponible.
        //   directorio privado(/data/user/0/...)No visible en el administrador de archivos de Android,Solución de problemas de dificultad en una máquina real.
        val externalCrashDir = appContext.getExternalFilesDir("crash")
        val crashDir = (externalCrashDir ?: File(appContext.filesDir, "crash")).apply { mkdirs() }
        val fmt = SimpleDateFormat(TIME_FMT, Locale.US)
        val fileName = "anr_${fmt.format(Date(anrTime))}.txt"
        val logFile = File(crashDir, fileName)

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("===== muse ANR log =====")
            pw.println("Time: ${Date(anrTime)}")
            pw.println("Silence: ${silenceMs}ms (Hilo principal sin tiempo de respuesta)")
            // v2.3.2: Calibre de juicio récord,Facilitar el juicio posterior sobre la credibilidad del informe.(viejo reportado 5~133s "Silence" Mayormente
            // Falsos positivos durante la congelación del proceso,Ver la anotación de AnrDetector)
            pw.println(
                "Detector: ticket(v2.3.2) interval=${CHECK_INTERVAL_MS}ms " +
                    "timeout=${ANR_TIMEOUT_MS}ms suspendTolerance=${SUSPEND_TOLERANCE_MS}ms",
            )
            pw.println("Note: congelar/Doze Los bloqueos resultantes no se cuentan Silence;La siguiente pila es la muestra en el momento de la decisión.")
            pw.println()
            pw.println("----- Device Info -----")
            pw.println("Brand: ${Build.BRAND}")
            pw.println("Model: ${Build.MODEL}")
            pw.println("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println("ABIs: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            pw.println()
            pw.println("----- Memory Status -----")
            val rt = Runtime.getRuntime()
            val total = rt.totalMemory()
            val free = rt.freeMemory()
            val used = total - free
            val max = rt.maxMemory()
            pw.println("Heap Used: ${used / 1024} KB")
            pw.println("Heap Free: ${free / 1024} KB")
            pw.println("Heap Total: ${total / 1024} KB")
            pw.println("Heap Max: ${max / 1024} KB")
            pw.println("Heap Usage: ${if (max > 0) used * 100 / max else -1}%")
            pw.println()
            pw.println("----- Main Thread Stack -----")
            val mainThread = Looper.getMainLooper().thread
            pw.println("Thread: ${mainThread.name} (id=${mainThread.id}, state=${mainThread.state})")
            mainThread.stackTrace.forEach { pw.println("\tat $it") }
            pw.println()
            pw.println("----- All Threads Stacks -----")
            val allStacks = Thread.getAllStackTraces()
            pw.println("Total threads: ${allStacks.size}")
            // Ordenar por ID de hilo en orden descendente,Salida de la pila de cada hilo.
            allStacks.entries.sortedByDescending { it.key.id }.forEach { (thread, stack) ->
                pw.println()
                pw.println("Thread: ${thread.name} (id=${thread.id}, state=${thread.state})")
                stack.forEach { pw.println("\tat $it") }
            }
            pw.println()
            pw.println("----- Recent Perf Records -----")
            val perfRecords = Perf.snapshotRecent()
            if (perfRecords.isEmpty()) {
                pw.println("(Sin registro de puntos enterrados de rendimiento)")
            } else {
                perfRecords.forEach { rec ->
                    pw.println("${rec.name}: ${rec.elapsedMs}ms @ ${Date(rec.timestamp)}")
                }
            }
        }
        // Escribir disco después de la desensibilización.(Reutilice la lógica de desensibilización de MuseCrashHandler,Evite fugas de token/apiKey en la pila)
        val redacted = MuseCrashHandler.redactSensitive(sw.toString())
        logFile.writeText(redacted)

        // Limpiar registros ANR antiguos(Conservar las copias MAX_ANR_LOGS más recientes,Limpiar solo el prefijo anr_,No afecta crash_log)
        crashDir.listFiles { f -> f.name.startsWith("anr_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_ANR_LOGS)
            ?.forEach { it.delete() }

        Logger.e(TAG, "ANR El registro ha sido escrito: ${logFile.absolutePath}")
    }

    companion object {
        private const val TAG = "AnrWatcher"
        private const val THREAD_NAME = "AnrWatcher"

        // Intervalo de detección:Hilo principal Ciclo de entrega de ping(2s)
        private const val CHECK_INTERVAL_MS = 2_000L

        // ANR Umbral de decisión:Si el hilo principal no responde durante más tiempo que este tiempo, será juzgado. ANR(5s)
        private const val ANR_TIMEOUT_MS = 5_000L

        // v2.3.2: tolerancia de suspensión — El tiempo real consumido en esta ronda excede el intervalo de detección por mucho,Es decir, el propio organismo de control se considera suspendido.
        // (congelación del proceso/Profundidad Doze),No se emitirá ningún juicio en esta ronda y se restablecerá la línea de base.,Evite informar erróneamente la duración de la congelación, ya que ANR
        private const val SUSPEND_TOLERANCE_MS = 3_000L

        // ANR Formato de marca de tiempo del nombre del archivo de registro
        private const val TIME_FMT = "yyyyMMdd-HHmmss"

        // Número de copias recientes del registro ANR que se deben conservar(Alineado con MuseCrashHandler.MAX_CRASH_LOGS)
        private const val MAX_ANR_LOGS = 5
    }

    /**
     * v1.0.53: Determinar si la aplicación está en primer plano.(RESUMED).
     *
     * congelación de fondo/Doze Es un comportamiento normal del sistema si el hilo principal no responde durante mucho tiempo.,
     * Anteriormente, habría una gran cantidad de falsos positivos. ANR(hasta 741 segundos),Filtra esto.
     * ProcessLifecycleOwner El estado es @Volatile seguro para subprocesos para lectura.
     */
    private fun isAppInForeground(): Boolean = try {
        androidx.lifecycle.ProcessLifecycleOwner.get()
            .lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    } catch (t: Throwable) {
        // Si no se puede obtener el estado, será procesado en la recepción.(Preferiría grabar,No hay filtración de la verdad ANR)
        true
    }
}
