package io.zer0.muse.session

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * M1.7: Punto de control para un solo turno/Restaurar estado.
 *
 * ciclo de vida(camino principal):
 * ```
 * NOT_STARTED -> GENERATING -> WAITING_TOOL -> GENERATING -> COMPLETED
 *                  |  ^             |
 *                  |  └-- WAITING_APPROVAL(Volverá después de la aprobación. GENERATING)
 *                  +-> CANCELLED / FAILED
 * ```
 * - [GENERATING]:LLM Solicitud de transmisión en progreso(Contiene solicitudes de conexión entre ruedas de herramientas.).
 * - [WAITING_TOOL]:Ejecución de herramienta en progreso(onToolStart -> onToolFinish).
 * - [WAITING_APPROVAL]:Las herramientas de alto riesgo esperan la aprobación del usuario(requestToolApproval punto de suspensión).
 * - [RESUMABLE]:La marca de giro restaurada por Generation_checkpoints después de que se finalizó el proceso.
 * - estado final([COMPLETED]/[CANCELLED]/[FAILED])grabar solo,No más migración dentro del mismo turno.;
 *   siguiente [SessionRuntime.beginTurn] Se reinicia explícitamente al iniciar un nuevo turno.
 */
enum class TurnPhase {
    NOT_STARTED,
    GENERATING,
    WAITING_TOOL,
    WAITING_APPROVAL,
    RESUMABLE,
    COMPLETED,
    CANCELLED,
    FAILED,
}

/**
 * M1.1: Un objeto de tiempo de ejecución estable para una sola sesión.
 *
 * por [ConversationSessionManager] Crear y mantener por sessionId,Entrar a la misma sesión varias veces
 * (cambiar/delante y detrás/ViewModel reconstruir)Reutilizar la misma instancia;Cuando el recuento de referencia llega a cero y no se generan tareas
 * Limpiado por segador inactivo.
 *
 * Límites de responsabilidad:
 *  - solo llevar"sucediendo"estado de ejecución(turn Puntos de control, generación de empleo, indicadores de cancelación, recuento de referencias).
 *  - noticias/redondo/parts La fuente del hecho de persistencia sigue siendo Room(ConversationEvent/Turn/ToolRound/MessagePart),
 *    Esta clase no almacena en caché el contenido del mensaje.,Evite un segundo conjunto de fuentes de verdad.
 *  - Cambiar entre el anverso y el reverso solo cambia las suscripciones de la interfaz de usuario,no cancelar [generationJob];ViewModel La destrucción no equivale a la cancelación de turno.
 *
 * Seguridad del hilo:phase ir StateFlow,Los campos restantes utilizan tipos atómicos./@Volatile,Se puede leer y escribir en cualquier hilo.
 */
class SessionRuntime(val sessionId: String) {

    private val _phase = MutableStateFlow(TurnPhase.NOT_STARTED)

    /** Estado actual del punto de control de turno(UI observable,Consejos para la recuperación/Panel de depuración). */
    val phase: StateFlow<TurnPhase> = _phase.asStateFlow()

    /** actual turn id(Misma fuente que StreamRunState.turnId/conversation_turns.turn_id). */
    val currentTurnId = AtomicReference<String?>(null)

    /** Crear tareas para la sesión actual;Completo/Cuando se reemplaza por [ConversationSessionManager] Mantenimiento. */
    @Volatile
    var generationJob: Job? = null
        internal set

    /** Cancelación de solicitud de usuario(stop),Generar cierres que se puedan leer para diferenciar."usuario detenido"con"Falló con excepción". */
    @Volatile
    var cancelRequested: Boolean = false
        private set

    /** Recuento de titulares activos(acquire +1 / release -1),Restablecer a cero activa un programa de limpieza inactivo. */
    val refCount = AtomicInteger(0)

    /** Hora de la última actividad(acquire/Generar actualización de latidos),Proporciona información para realizar juicios ociosos. */
    val lastActiveAt = AtomicLong(System.currentTimeMillis())

    /** Actualizar tiempo activo(generar latidos del corazón/Llamada de devolución de llamada de herramienta). */
    fun touch() {
        lastActiveAt.set(System.currentTimeMillis())
    }

    /**
     * Abra un nuevo turno: registre la identificación del turno, borre la bandera de cancelación anterior e ingrese [TurnPhase.GENERATING].
     * El nuevo turno sobrescribe explícitamente cualquier estado del turno anterior.(Contiene estado final)—— La rigidez del estado final sólo tiene efecto dentro del mismo turno.
     */
    fun beginTurn(turnId: String) {
        currentTurnId.set(turnId)
        cancelRequested = false
        touch()
        _phase.value = TurnPhase.GENERATING
    }

    /** Comienza la ejecución de la herramienta(onToolStart):GENERATING -> WAITING_TOOL. */
    fun markWaitingTool(turnId: String? = null) {
        if (matchesTurn(turnId)) transition(TurnPhase.WAITING_TOOL)
    }

    /** Esperando la aprobación del usuario(requestToolApproval colgar):GENERATING/WAITING_TOOL -> WAITING_APPROVAL. */
    fun markWaitingApproval(turnId: String? = null) {
        if (!matchesTurn(turnId)) return
        if (_phase.value == TurnPhase.GENERATING || _phase.value == TurnPhase.WAITING_TOOL) {
            transition(TurnPhase.WAITING_APPROVAL)
        }
    }

    /**
     * Espere a que el punto de recuperación vuelva a GENERAR: finaliza la ejecución de la herramienta(onToolFinish)o aprobación completada
     * (deferred.await Regresar)llamar después;Solo surte efecto cuando se encuentra actualmente en el estado de espera correspondiente.
     */
    fun markResumed(turnId: String? = null) {
        val waiting = _phase.value == TurnPhase.WAITING_TOOL || _phase.value == TurnPhase.WAITING_APPROVAL
        if (matchesTurn(turnId) && waiting) {
            transition(TurnPhase.GENERATING)
        }
    }

    /** Bandera de recuperación de proceso:Los giros residuales del punto de control están marcados como recuperables.,Y registre la identificación del turno restaurada. */
    fun markResumable(turnId: String? = null) {
        if (turnId != null && currentTurnId.get() == null) {
            currentTurnId.set(turnId)
        }
        if (matchesTurn(turnId)) transition(TurnPhase.RESUMABLE)
    }

    /** turn Liquidación final:Completado normalmente [TurnPhase.COMPLETED] / usuario detenido [TurnPhase.CANCELLED] / anormal [TurnPhase.FAILED]. */
    fun markFinished(phase: TurnPhase, turnId: String? = null) {
        if (turnId != null && currentTurnId.get() != null && currentTurnId.get() != turnId) {
            io.zer0.common.Logger.w(
                TAG,
                "turn id no hay coincidencia,Ignorar el estado final $phase: runtime=${currentTurnId.get()} request=$turnId session=$sessionId",
            )
            return
        }
        transition(phase)
    }

    /** Solicitud de cancelación de generación(stop camino);Idempotente. */
    internal fun requestCancel() {
        cancelRequested = true
    }

    /**
     * migración estatal(La única entrada escrita). El estado final no cambiará durante el turno actual.;turn id no coincidente
     * La migración solo registra registros y no genera excepciones —— Los puntos de control son observaciones./ayuda a la recuperación,No permitido debido a errores de contabilidad de estado
     * Interrumpir una construcción en curso.
     */
    private fun transition(target: TurnPhase) {
        val current = _phase.value
        if (current == target) return
        if (current in TERMINAL_PHASES) {
            io.zer0.common.Logger.w(
                TAG,
                "turn finalizado($current),Ignorar la migración a $target session=$sessionId turn=${currentTurnId.get()}",
            )
            return
        }
        _phase.value = target
        touch()
    }

    /** turn id Determinación del partido:null(Persona que llama desconocida)O soltar cuando sea consistente con el turno actual. */
    private fun matchesTurn(turnId: String?): Boolean = turnId == null || currentTurnId.get() == null || currentTurnId.get() == turnId

    companion object {
        private const val TAG = "SessionRuntime"

        private val TERMINAL_PHASES = setOf(TurnPhase.COMPLETED, TurnPhase.CANCELLED, TurnPhase.FAILED)
    }
}
