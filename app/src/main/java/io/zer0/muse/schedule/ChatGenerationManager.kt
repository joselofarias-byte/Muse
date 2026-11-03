package io.zer0.muse.schedule

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * v1.43: Programador de generación de chat a nivel de aplicación.
 *
 * Resuelva el problema de que la generación de IA sea interrumpida por el ciclo de vida de ViewModel al cambiar de página o de fondo.:
 * - La tarea de construcción continúa [appScope] en vez de [androidx.lifecycle.viewModelScope],No depende del ciclo de vida de la página.
 * - mantenimiento [activeGeneration]/[activeGenerations] Estado de mantenimiento de solo lectura,Para que los servicios front-end estén vinculados al nuevo ViewModel
 *
 * v1.x(El único cierre del libro de trabajo):
 * - Esta categoría**ya no se mantiene/Cancelar cualquier Job**. El único propietario del trabajo generado a nivel de sesión es
 *   [io.zer0.muse.session.ConversationSessionManager] sostenido
 *   `SessionRuntime.generationJob`.
 * - [launchGeneration] Sólo programación a nivel de aplicación:Lea el trabajo de la generación anterior del propietario para su serialización.(El nuevo bloque espera al viejo.
 *   finally fin),Inmediatamente después de iniciar la corrutina, pase la prueba del propietario. `setGenerationJob` Registrarse,reemplazar/Cancelar/Completo
 *   La limpieza la realiza toda el propietario.
 * - estado activo([_activeGenerations])Sólo se utiliza para el servicio de recepción. Mantener vivo y [isStreaming] consulta de solo lectura,
 *   Grabar por ID de sesión"¿Hay alguna construcción sin terminar?",Actuar como un demonio de limpieza como un objeto ActiveGeneration,
 *   No el segundo libro de contabilidad de Job.
 * - [stop] Eliminar solo el estado de mantenimiento de actividad,Cancelar acción delegada a owner `cancelGeneration`.
 */
class ChatGenerationManager(
    private val appScope: CoroutineScope,
    private val sessionManager: io.zer0.muse.session.ConversationSessionManager,
    private val appContext: Context? = null,
) {

    /** La sesión de compilación actualmente activa.null Indica que no se está generando. */
    data class ActiveGeneration(
        val sessionId: String,
        val assistantId: String,
        val sessionTitle: String,
        /** Identidad generacional estable; comparte el mismo ID de cadena con el recurso StreamRunState/ejecución. */
        val generationId: String,
        /** La identidad de esta ronda de conversación gira; la antigua llamada en segundo plano puede estar vacía. */
        val turnId: String? = null,
        val isStreaming: Boolean = true,
        val lastUpdatedAt: Long = System.currentTimeMillis(),
        /** v2.5.0: Número de palabras generadas(El hilo del latido se comparte con el hilo de la interfaz de usuario.),Para que la notificación en la recepción muestre el progreso real. */
        val currentChars: Int = 0,
    )

    private val _activeGeneration = MutableStateFlow<ActiveGeneration?>(null)
    val activeGeneration: StateFlow<ActiveGeneration?> = _activeGeneration.asStateFlow()

    /** Instantánea de solo lectura de todas las tareas de compilación activas, key es ID de sesión. */
    private val _activeGenerations = MutableStateFlow<Map<String, ActiveGeneration>>(emptyMap())
    val activeGenerations: StateFlow<Map<String, ActiveGeneration>> = _activeGenerations.asStateFlow()

    private val lock = Any()
    /**
     * Inicie la tarea de compilación en una rutina a nivel de aplicación,Y registrado en el libro mayor de nivel de sesión del propietario único.
     *
     * Orden de programación:
     * 1. Leer la misma sesión de la generación anterior del propietario. Job(Base de serialización),y cancelarlo;
     * 2. Escribir sincrónicamente el estado de mantenimiento activo;
     * 3. bloque de inicio(primero `join` generación anterior finally,Evite dos generaciones de transmisiones escribiendo la misma sesión al mismo tiempo);
     * 4. Pase `sessionManager.setGenerationJob` Registrar nuevo Job(owner Cancelará el anterior y registrará el nuevo.);
     * 5. Regresar Job(Compatible con personas mayores,puede ser ignorado).
     *
     * @param sessionId sesión id(Chat único como conversación id,El chat grupal es "group:$chatId")
     */
    /** Compatible con llamantes antiguos: el administrador genera y asigna generaciones para fondos que no están conectados a la identidad unificada. */
    fun launchGeneration(sessionId: String, assistantId: String, sessionTitle: String, block: suspend () -> Unit): Job = launchGeneration(
        sessionId = sessionId,
        assistantId = assistantId,
        sessionTitle = sessionTitle,
        generationId = UUID.randomUUID().toString(),
        block = block,
    )

    /**
     * Generación iniciada con identidad generacional unificada explícita.
     *
     * La sobrecarga de cinco parámetros se conserva y es compatible con los llamantes existentes; utilice la sobrecarga extendida a continuación cuando se requiera identidad de giro.
     */
    fun launchGeneration(
        sessionId: String,
        assistantId: String,
        sessionTitle: String,
        generationId: String,
        block: suspend () -> Unit,
    ): Job = launchGeneration(
        sessionId = sessionId,
        assistantId = assistantId,
        sessionTitle = sessionTitle,
        generationId = generationId,
        turnId = null,
        block = block,
    )

    /** Entrada de generación unificada de host/nativo con GenerationId y turnId. */
    @Suppress("LongParameterList")
    fun launchGeneration(
        sessionId: String,
        assistantId: String,
        sessionTitle: String,
        generationId: String,
        turnId: String?,
        block: suspend () -> Unit,
    ): Job {
        synchronized(lock) {
            // Lea el trabajo de la generación anterior del propietario único. Si la generación anterior se ha completado normalmente,owner Borrado → null(No es necesario serializar).
            val previousJob = sessionManager.runtime(sessionId)?.generationJob
            previousJob?.cancel()

            val generation = ActiveGeneration(
                sessionId = sessionId,
                assistantId = assistantId,
                sessionTitle = sessionTitle,
                generationId = generationId,
                turnId = turnId,
                isStreaming = true,
                lastUpdatedAt = System.currentTimeMillis(),
            )
            // Primero escriba el estado activo de forma sincrónica.ON_STOP Puede ocurrir inmediatamente después de enviar una acción.;El servicio de mantenimiento de la recepción se determina en consecuencia.
            _activeGenerations.value = _activeGenerations.value + (sessionId to generation)
            _activeGeneration.value = generation

            val job = appScope.launch(start = CoroutineStart.LAZY) {
                previousJob?.join()
                val heartbeatJob = launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        touch(sessionId)
                    }
                }
                try {
                    block()
                } finally {
                    heartbeatJob.cancel()
                    // touch()/updateSessionTitle() usará copy() Los campos de visualización de actualización no se pueden juzgar por la identidad del objeto.
                    // Solo comparar el ID de generación inmutable puede garantizar que la generación anterior finalmente no borre la nueva generación.
                    // Al mismo tiempo, se garantiza que el mapa activo se eliminará después de su finalización normal.
                    synchronized(lock) {
                        if (_activeGenerations.value[sessionId]?.generationId == generation.generationId) {
                            _activeGenerations.value = _activeGenerations.value - sessionId
                            val next = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
                            _activeGeneration.value = next ?: generation.copy(
                                isStreaming = false,
                                lastUpdatedAt = System.currentTimeMillis(),
                            )
                        }
                    }
                    Logger.i("ChatGenMgr", "generation finished: $sessionId")
                }
            }

            // Registro de propietario único:Cancelar generación anterior(idempotente), registrar nuevos trabajos, limpiar cuando se complete + programación inactiva.
            sessionManager.setGenerationJob(sessionId, job)
            job.start()

            // Una vez registrado, ingresará al servicio de recepción.,Evite condiciones de carrera entre ON_STOP y el inicio de la tarea.
            appContext?.let { context ->
                runCatching { ChatGenerationService.start(context) }
                    .onFailure { Logger.w("ChatGenMgr", "No se pudo iniciar el servicio en primer plano cuando comienza la compilación", it) }
            }
            return job
        }
    }

    /**
     * El usuario detiene la generación de la sesión especificada. Las acciones de cancelación se delegan al propietario único a nivel de sesión.
     *
     * @param sessionId sesión para detener id;Si no, todo será cancelado.(Compatible con personas mayores)
     */
    fun stop(sessionId: String? = null) {
        synchronized(lock) {
            if (sessionId != null) {
                _activeGenerations.value = _activeGenerations.value - sessionId
                if (_activeGeneration.value?.sessionId == sessionId) {
                    _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
                }
                sessionManager.cancelGeneration(sessionId)
                Logger.i("ChatGenMgr", "generation stopped: $sessionId")
            } else {
                val ids = _activeGenerations.value.keys.toList()
                _activeGenerations.value = emptyMap()
                _activeGeneration.value = null
                ids.forEach { sessionManager.cancelGeneration(it) }
                Logger.i("ChatGenMgr", "all generations stopped")
            }
        }
    }

    /** Actualizar el latido de la sesión especificada;Compatible con llamadas antiguas cuando son nulas,Actualizar tareas recientes. */
    fun touch(sessionId: String? = null) {
        synchronized(lock) {
            val targetId = sessionId ?: _activeGeneration.value?.sessionId ?: return
            val current = _activeGenerations.value[targetId] ?: return
            val touched = current.copy(lastUpdatedAt = System.currentTimeMillis())
            _activeGenerations.value = _activeGenerations.value + (targetId to touched)
            _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
        }
    }

    /**
     * v2.5.0: Actualizar el recuento de palabras generadas,Para que la notificación en la recepción muestre el progreso real.
     * Llamado por una devolución de llamada delta de transmisión(Después de estrangular),La capa de servicio lee la instantánea y detalla la copia de la notificación.
     */
    fun updateChars(sessionId: String, chars: Int) {
        synchronized(lock) {
            val current = _activeGenerations.value[sessionId] ?: return
            val touched = current.copy(currentChars = chars, lastUpdatedAt = System.currentTimeMillis())
            _activeGenerations.value = _activeGenerations.value + (sessionId to touched)
            _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
        }
    }

    /** Actualizar el título de la notificación generada recientemente(Se llama después de que el chat grupal obtiene de forma asincrónica el nombre del chat grupal.). */
    fun updateSessionTitle(title: String) {
        val sessionId = synchronized(lock) { _activeGeneration.value?.sessionId }
        if (sessionId != null) updateSessionTitle(sessionId, title)
    }

    /** Actualiza el título de la notificación para la sesión especificada. */
    fun updateSessionTitle(sessionId: String, title: String) {
        synchronized(lock) {
            val current = _activeGenerations.value[sessionId] ?: return
            val updated = current.copy(sessionTitle = title, lastUpdatedAt = System.currentTimeMillis())
            _activeGenerations.value = _activeGenerations.value + (sessionId to updated)
            _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
        }
    }

    /** Se registrará para generar una identidad de giro de asociación suplementaria, manteniendo compatible la antigua persona que llama con cinco parámetros. */
    fun setTurnId(sessionId: String, generationId: String, turnId: String) {
        synchronized(lock) {
            val current = _activeGenerations.value[sessionId] ?: return
            if (current.generationId != generationId) return
            val updated = current.copy(turnId = turnId, lastUpdatedAt = System.currentTimeMillis())
            _activeGenerations.value = _activeGenerations.value + (sessionId to updated)
            _activeGeneration.value = _activeGenerations.value.values.maxByOrNull { it.lastUpdatedAt }
        }
    }

    /** estado de solo lectura:Especifica si el sessionId tiene una generación pendiente. */
    fun isStreaming(sessionId: String): Boolean {
        synchronized(lock) {
            return _activeGenerations.value.containsKey(sessionId)
        }
    }

    private companion object {
        /** Demuestre periódicamente que la rutina está activa incluso cuando la tarea no tiene salida de texto.,Evite que las llamadas de herramientas largas se consideren erróneamente como atascadas. */
        const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}
