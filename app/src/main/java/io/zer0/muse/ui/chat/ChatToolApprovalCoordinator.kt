package io.zer0.muse.ui.chat
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.ui.PendingToolApproval
import io.zer0.muse.ui.common.feedback.MuseToast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v2.x Refactor S4: orquestación de aprobación de herramientas extraída de ChatViewModel.
 *
 * Ubicación original:ChatViewModel.requestToolApprovalForSession(4218-4331).
 *
 * Propiedades de migración:**extracción pura**——Mueve el código tal como está.,No hay cambios en ninguna lógica./orden/Semántica de excepción.
 * ChatViewModel Mantenga la llamada del delegado en su posición original.
 */
@Suppress(
    "LongParameterList",
    "LongMethod",
    "ComplexMethod",
    "TooGenericExceptionCaught",
    "ReturnCount",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "UnusedPrivateMember",
)
internal class ChatToolApprovalCoordinator(
    private val host: ChatToolApprovalHostBridge,
) {

    /** Constantes migradas desde ChatViewModel(Referencia directa a la cadena de aprobación original.). */
    companion object {
        /** Se agotó el tiempo de aprobación. La cuenta regresiva de la interfaz debe derivarse de aquí., No guarde una copia separada del número. */
        const val TOOL_APPROVAL_TIMEOUT_MS = 30_000L

        /**
         * El límite superior de congelación acumulada durante el período desenfocado/de fondo.
         *
         * Congelar la cuenta regresiva cuando el usuario no pueda ver la tarjeta de aprobación,Suelta/abandona la aplicación y regresa y aun así aprobala;Pero la congelación no puede ser ilimitada,
         * De lo contrario, la rutina y la ronda de herramientas de la sesión se colgarán permanentemente cuando el usuario no regrese. Superar este valor terminará con el tiempo de espera.
         */
        const val APPROVAL_UNFOCUSED_PAUSE_MAX_MS = 10 * 60_000L
    }
    suspend fun requestToolApprovalForSession(
        sessionId: String,
        toolName: String,
        toolCallId: String,
        argsPreview: String,
        args: Map<String, Any?>,
    ): ToolApprovalState {
        // El permiso temporal de esta sesión solo debe omitir la confirmación y aún así debe ser denegado por los parámetros del analizador unificado.
        // Las políticas de persistencia explícitas tienen prioridad para evitar que el permiso temporal pase por alto ALWAYS_DENY del usuario.
        val allowedThisSession = host.sessionPermissionStore.isAllowedThisSession(sessionId, toolName)
        // toolConfigStore no puede ser inyectado(declarado como anulable,Predeterminado null);Cuando no se inyecta, degenera en
        // modo de sesión+Determinación por defecto del nivel de riesgo(ASK NORMAL/ALTO seguirá siendo aprobado),evitar el primer artículo
        // La herramienta de aprobación llama a fallas directas de NPE.
        // tomar solo**Configuración explícita del usuario**estrategia:No configurado debe ser null,En caso contrario, se considerará como
        // "El usuario ha sido liberado.",ASK/STRICT En el modo, se omitirán todas las aprobaciones basadas en niveles de riesgo.
        val configuredPolicy = host.toolConfigStore?.getConfiguredPolicy(toolName)
        val perToolPolicy = io.zer0.muse.tools.effectivePerToolPolicy(configuredPolicy, allowedThisSession)
        val displayedSessionId = host.currentSessionIdForApproval()
        // AUD-20261011-A2A-ALLOWALL: situación general"Aprobar todo durante este período de apertura"Sólo cuando el usuario está mirando la sesión.
        // Actualizado a CONFIABLE.A2A solicitud remota(por chatViewModel.send() Siga la misma cadena de aprobación y el punto final es solo
        // JWT Sin control de enfoque)Volver al modo propio de la sesión cuando se ejecuta una sesión no enfocada —— HIGH Herramientas de dispositivos de riesgo
        // (device_shell / app_clear_data / ui_agent espera)Seguir pasando por la tarjeta de aprobación o rechazado después de un tiempo de espera de 30 segundos,
        // No se ejecutará en silencio cuando no haya nadie frente a la pantalla. Ver el calibre del juicio [resolveApprovalMode].
        val mode = resolveApprovalMode(
            allowAllToolsThisRun = host.stateStore.state.value.appRunAllowAllTools,
            displayedSessionId = displayedSessionId,
            sessionId = sessionId,
            focusedMode = host.stateStore.state.value.sessionPermissionMode,
            persistedModeProvider = {
                host.sessionPermissionStore.getMode(
                    sessionId,
                    host.settings.defaultSessionPermissionModeFlow.first(),
                )
            },
        )
        val risk = host.toolRegistry.getToolRiskLevel(toolName)
        // v1.x: Registro de depuración de decisiones de aprobación — solución de problemas"La delegación completa de poderes no surte efecto/Permitir siempre no válido"problema de clase
        Logger.d(
            "ToolApproval",
            "resolve | tool=$toolName | mode=$mode | risk=$risk | policy=$perToolPolicy" +
                " | allowAllRun=${host.stateStore.state.value.appRunAllowAllTools}",
        )
        // v1.0.53: Transmisión completa args,estrategia paramétrica(open_url/execute_javascript)hacer efecto
        val resolved = ToolPermissionResolver.resolve(toolName, risk, mode, perToolPolicy, args)
        Logger.d(
            "ToolApproval",
            "resolved | tool=$toolName | state=$resolved",
        )
        // Bucle cerrado de máquina de estados:Enumere explícitamente todas las ramas estatales finales,Asegúrese de que ToolApprovalState.Answered tenga una ruta de procesamiento
        when (resolved) {
            is ToolApprovalState.Pending -> { /* Pendiente de aprobación,Continúe siguiendo el proceso de aprobación del usuario a continuación */ }
            is ToolApprovalState.Answered -> {
                // El usuario ha proporcionado una respuesta personalizada.(Ejecución de herramientas alternativas),Volver directamente a esta respuesta
                return resolved
            }
            is ToolApprovalState.Approved, is ToolApprovalState.Auto,
            is ToolApprovalState.Denied,
            -> return resolved
        }

        // Requiere la aprobación del usuario:Agregar a la lista de aprobación pendiente y esperar los resultados
        val deferred = kotlinx.coroutines.CompletableDeferred<ToolApprovalState>()
        host.toolApprovalResults[toolCallId] = deferred
        host.toolApprovalSessions[toolCallId] = sessionId
        val pending =
            PendingToolApproval(
                toolCallId = toolCallId,
                toolName = toolName,
                argumentsPreview = argsPreview,
                // Vista previa solo de los primeros 200 caracteres,Los parámetros completos se proporcionan a la tarjeta juntos.,Deja que le diga al usuario cuánto queda,
                // Y muestre el monto total después de la expansión. Esto también es lo que se usa para juzgar. args,Ambos tienen el mismo origen.
                argumentsFull = args,
            )
        host.pendingToolApprovalRecords[toolCallId] = pending
        // v2.0.1: Recordatorio en segundo plano"Esperando aprobación"(La recepción está en silencio, consulte el juicio interno notifyChatPendingApproval)
        runCatching {
            host.notificationManager.notifyChatPendingApproval(
                io.zer0.muse.ui.chat.ToolCallVisuals.labelFor(toolName, host.appContext.resources),
            )
        }
        if (displayedSessionId == sessionId) {
            host.stateStore.state.update {
                it.copy(pendingToolApprovals = it.pendingToolApprovals + pending)
            }
        }
        // M1.7: Pendiente de aprobación del usuario -> WAITING_APPROVAL punto de control;restaurar/Regreso después del fracaso GENERATING
        host.sessionManager.runtime(sessionId)?.markWaitingApproval()
        return try {
            // CHAT-08: La aprobación se puede suspender si caduca — Cuenta regresiva para esperar por segmento,No se realizará ninguna deducción de tiempo durante el período de suspensión.,Reanudar desde la duración restante;
            // Aprobación del usuario en cualquier momento/Devolver inmediatamente si es rechazado,La semántica es consistente con los años 30 originales.
            // Segundo camino congelado:El usuario ya no podrá ver esta tarjeta de aprobación y no se descontará ningún tiempo.(Corta otras sesiones y retira la aplicación a un segundo plano.).
            // De lo contrario, el usuario saldrá unos minutos y luego volverá.,Sólo verás un registro que ya ha sido denegado.,El modelo también expirará esta vez.
            // Tratado como rechazo del usuario y redirigido. Criterio ver [shouldPauseApprovalTimeout].
            // La congelación tiene límite superior(APPROVAL_UNFOCUSED_PAUSE_MAX_MS):Termine a tiempo cuando los usuarios dejen de regresar,
            // Evite que las corrutinas y las rondas de sesiones se cuelguen permanentemente. Plegar y leer es un comportamiento activo del usuario,No sujeto a este límite superior.
            val outcome =
                awaitApprovalOutcome(
                    timeoutMs = TOOL_APPROVAL_TIMEOUT_MS,
                    pauseBudgetMs = APPROVAL_UNFOCUSED_PAUSE_MAX_MS,
                    pauseSignals = {
                        val folded = host.approvalTimeoutPaused
                        ApprovalPause(
                            folded = folded,
                            paused =
                            shouldPauseApprovalTimeout(
                                foldedPaused = folded,
                                displayedSessionId = host.currentSessionIdForApproval(),
                                sessionId = sessionId,
                                appInForeground = host.appInForeground,
                            ),
                        )
                    },
                    isStillRegistered = { host.toolApprovalResults[toolCallId] === deferred },
                    awaitOutcome = { sliceMs -> withTimeoutOrNull(sliceMs) { deferred.await() } },
                )
            when (outcome) {
                is ApprovalWaitOutcome.Resolved -> outcome.state
                ApprovalWaitOutcome.Abandoned -> {
                    // Se ha cerrado la sesión de aprobación del registro pendiente(Sesión eliminada / generación detenida / volver a registrarse con la misma herramientaCallId),
                    // No habrá ninguna conclusión que regrese. —— Terminar inmediatamente,No quedan corrutinas pendientes.
                    Logger.d("ToolApproval", "abandoned | tool=$toolName | call=$toolCallId")
                    ToolApprovalState.Denied("Generation stopped")
                }
                ApprovalWaitOutcome.TimedOut -> {
                    // P2-1: Borrar las tarjetas de aprobación restantes después del tiempo de espera,Evite "No hay respuesta incluso después de hacer clic en "Permitir"";y preguntar al usuario
                    host.stateStore.state.update {
                        it.copy(
                            pendingToolApprovals = it.pendingToolApprovals.filter { p -> p.toolCallId != toolCallId },
                        )
                    }
                    MuseToast.show(
                        host.appContext.getString(R.string.tool_approval_timeout_hint, toolName),
                    )
                    ToolApprovalState.Denied("Approval timed out")
                }
            }
        } finally {
            host.sessionManager.runtime(sessionId)?.markResumed()
            host.toolApprovalResults.remove(toolCallId)
            host.toolApprovalSessions.remove(toolCallId)
            host.pendingToolApprovalRecords.remove(toolCallId)
        }
    }
}

/**
 * Si la cuenta regresiva de aprobación debe congelarse en este paso.
 *
 * dos caminos independientes:Los usuarios doblan y leen activamente([foldedPaused]),O el usuario ya no puede ver esta tarjeta de aprobación. ——
 * La aplicación no está en primer plano.([appInForeground] para false),O la sesión a la que pertenece la aprobación ya no es la sesión de primer plano.
 * Foco desconocido([displayedSessionId] para null)Fuera de foco en todo momento,Mantenga el comportamiento de sincronización original.
 */
internal fun shouldPauseApprovalTimeout(
    foldedPaused: Boolean,
    displayedSessionId: String?,
    sessionId: String,
    appInForeground: Boolean = true,
): Boolean = foldedPaused || !appInForeground || (displayedSessionId != null && displayedSessionId != sessionId)

/** Tamaño de un solo paso de cuenta regresiva:Intervalo entre sondas durante la pausa,También es la granularidad de la deducción de tiempo. */
private const val APPROVAL_WAIT_STEP_MS = 200L

/** Señal de congelación de un solo paso.[folded] ¿El usuario está doblado para leer?(No sujeto a presupuesto suspendido),[paused] Es si el tiempo está actualmente congelado. */
internal data class ApprovalPause(val folded: Boolean, val paused: Boolean)

/** [awaitApprovalOutcome] El final. */
internal sealed interface ApprovalWaitOutcome {
    /** El usuario o la ruta externa da la conclusión. */
    data class Resolved(val state: ToolApprovalState) : ApprovalWaitOutcome

    /** cuenta regresiva agotada(Presupuesto agotado con pausa). */
    data object TimedOut : ApprovalWaitOutcome

    /** Se ha cerrado la sesión de aprobación del registro pendiente,No habrá ninguna conclusión a la que regresar. */
    data object Abandoned : ApprovalWaitOutcome
}

/**
 * Está pendiente una cuenta regresiva de aprobación pausable.
 *
 * Espera cada párrafo [awaitOutcome] dar conclusión,tiempo de reasentamiento:Segmentos descongelados de [timeoutMs] Botón,
 * No se deducen los gajos congelados. Por lo tanto, después de una pausa de 5 minutos, todavía queda el tiempo restante original.,en lugar de rellenar.
 * La sección congelada todavía está presionada. [APPROVAL_WAIT_STEP_MS] explorar la vida,aprobación externa/La negativa no tiene que esperar a que regrese la recuperación.
 *
 * @param timeoutMs Tiempo total en estado descongelado
 * @param pauseBudgetMs Límite superior acumulativo para congelaciones sin colapso;Terminar con tiempo de espera después del agotamiento.,Evite quedarse atascado permanentemente
 * @param pauseSignals Congelar señal para cada paso
 * @param isStillRegistered ¿Aún está pendiente la aprobación en el registro?;false Dijo que no habría más conclusiones.
 * @param awaitOutcome Espere la cantidad dada de milisegundos,Volver a la conclusión si hay una conclusión.,Esta sección caduca y regresa. null
 */
@Suppress("ReturnCount")
internal suspend fun awaitApprovalOutcome(
    timeoutMs: Long,
    pauseBudgetMs: Long,
    pauseSignals: () -> ApprovalPause,
    isStillRegistered: () -> Boolean,
    awaitOutcome: suspend (Long) -> ToolApprovalState?,
): ApprovalWaitOutcome {
    var remainingMs = timeoutMs
    var pauseElapsedMs = 0L
    while (true) {
        val pause = pauseSignals()
        val sliceMs = if (pause.paused) APPROVAL_WAIT_STEP_MS else minOf(APPROVAL_WAIT_STEP_MS, remainingMs)
        awaitOutcome(sliceMs)?.let { return ApprovalWaitOutcome.Resolved(it) }
        // Cancelar rutas con concurrente(cancelPendingApprovalsForSession Quitar primero y luego complete)Hay una muy pequeña
        // ventana:Si la exploración se produjo en medio de dos pasos,Abandonado será devuelto aquí sin llegar a la conclusión completa.
        // El resultado final de ambos caminos es Denied,Sólo porque de qué lado se toma la cuerda,Para ello no es necesario bloquear ni reorganizar.
        if (!isStillRegistered()) return ApprovalWaitOutcome.Abandoned
        if (!pause.paused) {
            remainingMs -= sliceMs
            if (remainingMs <= 0L) return ApprovalWaitOutcome.TimedOut
        } else if (!pause.folded) {
            // La lectura plegable es un comportamiento activo de los usuarios,No incluido en el presupuesto de suspensión;Fuera de foco/Debe haber un límite superior para la congelación en el backend.
            pauseElapsedMs += sliceMs
            if (pauseElapsedMs >= pauseBudgetMs) return ApprovalWaitOutcome.TimedOut
        }
    }
}
