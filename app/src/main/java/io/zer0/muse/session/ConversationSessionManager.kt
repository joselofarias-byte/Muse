package io.zer0.muse.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.x: Administrador de recursos a nivel de sesión — Conteo de referencias + limpieza automática en ralentí.
 *
 * propósito del diseño:
 *  - En escenarios de conmutación de sesiones múltiples,Seguimiento de cada sesión"titular activo"Cantidad(acquire/release emparejamiento),
 *    Después de que el recuento de referencias llegue a cero y finalice la tarea de compilación [IDLE_TIMEOUT_MS] Limpiar automáticamente los recursos de la sesión dentro,
 *    Evite pérdidas de memoria(La rutina no ha sido cancelada y la referencia no ha sido publicada.).
 *  - Administre centralmente tareas de compilación a nivel de sesión([setGenerationJob] Cancelar automáticamente el anterior),con [io.zer0.muse.schedule.ChatGenerationManager]
 *    complementario:Este último es responsable del nivel de aplicación."Cortar páginas/Sigue generando en segundo plano sin interrupciones",Esta clase es responsable del recuento de referencias a nivel de sesión + liberación inactiva.
 *
 * M1.1 Evolución:El objeto retenido internamente se actualiza desde un SessionRef contado por referencia a [SessionRuntime]
 * (turn Punto de control + Generar trabajo + Cancelar marca + Recuento de referencias). Público externo API
 * (acquire/release/setGenerationJob/cancelGeneration/clearAll)permanecer sin cambios;
 * Nuevo [runtime]/[getOrCreateRuntime]/[beginTurn] Para generar enlaces, leer y escribir puntos de control de giro.
 *
 * Convención de uso:
 *  - ChatViewModel.switchSession / createNewSession Espere la entrada del cambio de sesión:
 *      release(oldSessionId); acquire(newSessionId)
 *  - launchStream Iniciar la construcción:beginTurn(sessionId, turnId) + setGenerationJob(sessionId, job)
 *  - onCleared / release:release(currentSessionId)
 *
 * Seguridad del hilo:[ConcurrentHashMap] + [SessionRuntime] Los tipos atómicos internos garantizan la seguridad de múltiples subprocesos;
 *  idle Los tramos de limpieza pasaron durante la construcción. [scope] en.
 *
 * @param scope idle carrera más limpia CoroutineScope(Se debe utilizar el nivel de aplicación. appScope,Evite la cancelación con el ciclo de vida de ViewModel)
 */
class ConversationSessionManager(
    private val scope: CoroutineScope,
) {

    private val sessions = ConcurrentHashMap<String, SessionRuntime>()
    private val idleReaper = SessionIdleReaper(scope, sessions, IDLE_TIMEOUT_MS)

    companion object {
        /** Después de que el recuento de referencia llegue a cero,Cuántos milisegundos esperar inactivo antes de borrar las entradas de la sesión. */
        private const val IDLE_TIMEOUT_MS = 5_000L
    }

    /**
     * Obtener referencia de sesión(Conteo de referencias +1).
     *
     * Deberías entrar a la sesión.(switchSession/createNewSession/setAgentMode espera)cuando lo llaman.
     * Cancelar la limpieza inactiva pendiente de esta sesión(Si es así).
     */
    fun acquire(sessionId: String) {
        val runtime = getOrCreateRuntime(sessionId)
        runtime.refCount.incrementAndGet()
        runtime.touch()
        idleReaper.cancel(sessionId)
    }

    /**
     * Referencia de la sesión de lanzamiento(Conteo de referencias -1).
     *
     * debe abandonar la sesión(switchSession antigua sesión/onCleared/release espera)cuando lo llaman.
     * Programe una limpieza inactiva cuando el recuento de referencias llegue a cero(espera [IDLE_TIMEOUT_MS] eliminar más tarde,Deje una breve ventana para volver a cambiar).
     */
    fun release(sessionId: String) {
        val runtime = sessions[sessionId] ?: return
        val count = runtime.refCount.decrementAndGet()
        if (count <= 0) {
            idleReaper.schedule(sessionId)
        }
    }

    /**
     * Configurar tareas de compilación(Cancelar automáticamente el anterior).
     *
     * M1.1:La sesión se crea automáticamente cuando no se ha adquirido. [SessionRuntime](chat grupal "group:$chatId" espera
     * Es posible que la generación de la identificación nunca tome la ruta de adquisición,La implementación anterior regresa directamente y hará que la tarea de generación sea independiente de la gestión de referencias.).
     *
     * debería estar en [io.zer0.muse.ui.ChatViewModel.launchStream] Llamado al iniciar la construcción.,
     * Pasar el trabajo devuelto por chatGenerationManager,Esta categoría es responsable de:
     *  - Cancelar la tarea de compilación anterior(Anti-reentrada)
     *  - Limpiar referencias cuando se complete la tarea + programar una limpieza inactiva
     */
    fun setGenerationJob(sessionId: String, job: Job?) {
        val runtime = getOrCreateRuntime(sessionId)
        synchronized(runtime) {
            runtime.generationJob?.cancel()
            runtime.generationJob = job
        }
        // Revisión de corrección(P1):El cierre de devolución de llamada captura su propia job,Después de la finalización sólo en"Lo que se registra en tiempo de ejecución sigue siendo esto. job"Limpiar de vez en cuando.
        // Igual que el reingreso rápido de sesión,La devolución de llamada de finalización del trabajo anterior puede ejecutarse más tarde que la ejecución del registro del nuevo trabajo.
        // (NonCancellable final lento completion);Establecer nulo incondicionalmente borrará la nueva referencia de trabajo,
        // Como resultado, se juzga erróneamente que la generación de fondo termina,cancelGeneration fracaso,idle reaper Limpiado por error.
        job?.invokeOnCompletion {
            synchronized(runtime) {
                if (runtime.generationJob === job) {
                    runtime.generationJob = null
                    runtime.touch()
                }
            }
            // La limpieza inactiva solo se programa cuando el recuento de referencias llega a cero y actualmente no hay tareas generadas.;
            // El nuevo trabajo aún está pendiente.(generationJob != null)reaper También se omitirá después de la activación.
            if (runtime.refCount.get() <= 0) {
                idleReaper.schedule(sessionId)
            }
        }
    }

    /** Cancelar la tarea de compilación de la sesión.(Se llama cuando el punto de usuario se detiene.),Y registre la bandera de cancelación. */
    fun cancelGeneration(sessionId: String) {
        val runtime = sessions[sessionId] ?: return
        runtime.requestCancel()
        runtime.generationJob?.cancel()
    }

    /** M1.1: Leer el tiempo de ejecución de la sesión(Devuelve si no existe null,Leer solo para observación). */
    fun runtime(sessionId: String): SessionRuntime? = sessions[sessionId]

    /** M1.1: Obtener o crear un tiempo de ejecución de sesión — El mismo sessionId siempre reutiliza la misma instancia. */
    fun getOrCreateRuntime(sessionId: String): SessionRuntime = sessions.computeIfAbsent(sessionId) { SessionRuntime(it) }

    /**
     * M1.1: Genere un enlace para abrir una entrada conveniente a un nuevo turno — Creado automáticamente cuando no existe en tiempo de ejecución,
     * Se garantiza que la primera escritura del punto de control dentro del cierre de launchStream no se perderá.
     */
    fun beginTurn(sessionId: String, turnId: String) {
        getOrCreateRuntime(sessionId).beginTurn(turnId)
    }

    /**
     * Limpiar todas las sesiones(App Salir/probar).
     *
     * Cancelar todas las tareas de compilación y rutinas de limpieza inactivas,Borrar [sessions].
     */
    fun clearAll() {
        sessions.values.forEach { it.generationJob?.cancel() }
        sessions.clear()
        idleReaper.cancelAll()
    }
}

/**
 * Limpiador inactivo de sesión:Mantenga como máximo una tarea de limpieza diferida por sesión.
 */
private class SessionIdleReaper(
    private val scope: CoroutineScope,
    private val sessions: ConcurrentHashMap<String, SessionRuntime>,
    private val timeoutMs: Long,
) {

    private val pendingJobs = ConcurrentHashMap<String, Job>()

    fun schedule(sessionId: String) {
        cancel(sessionId)
        pendingJobs[sessionId] = scope.launch {
            delay(timeoutMs)
            val runtime = sessions[sessionId]
            if (runtime != null && runtime.refCount.get() <= 0 && runtime.generationJob == null) {
                sessions.remove(sessionId)
                pendingJobs.remove(sessionId)
            }
        }
    }

    fun cancel(sessionId: String) {
        pendingJobs.remove(sessionId)?.cancel()
    }

    fun cancelAll() {
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
    }
}
