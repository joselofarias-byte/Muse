package io.zer0.muse.automation.tools

import io.zer0.common.AppJson
import io.zer0.muse.automation.appcontrol.AppControlCore
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.DeviceCommandPolicy
import io.zer0.muse.automation.vdisplay.VirtualDisplayClient
import io.zer0.muse.automation.vdisplay.VirtualDisplayInputCommand
import io.zer0.muse.automation.vdisplay.VirtualDisplayLeaseRegistry
import io.zer0.muse.automation.vdisplay.VirtualDisplaySemanticActions
import io.zer0.muse.automation.vdisplay.VirtualDisplayServerManager
import io.zer0.muse.tools.NodeScriptTool
import io.zer0.muse.tools.ToolOutcome
import io.zer0.muse.tools.WorkflowJournal
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val DISPLAY_REQUIRED_VIRTUAL_ACTIONS = setOf(
    "virtual_launch",
    "virtual_tap",
    "virtual_tap_text",
    "virtual_swipe",
    "virtual_text",
    "virtual_key",
    "virtual_wait",
    "virtual_read",
)
private val CACHED_DISPLAY_ID_REGEX = Regex("""displayId=(\d+)""")

/**
 * Flujo de trabajo del dispositivo administrado —— Combine primitivas de acción celular segura en una única llamada auditable.
 *
 * Esta es la primera parte de la capa de orquestación del Agente: el conjunto de acciones está explícitamente en la lista blanca y no acepta ningún shell; se detiene ante el fracaso en cada paso.
 * Los resultados se guardan tanto para el resumen del modelo como para el diseño./UI Detalles estructurados a utilizar.
 */
class AutomationWorkflow(
    private val manager: AutomationManager,
    private val journal: WorkflowJournal? = null,
    private val virtualDisplayClient: VirtualDisplayClient? = null,
    private val virtualDisplayManager: VirtualDisplayServerManager? = null,
    private val nodeScriptExecutor: (suspend (String, Long) -> SkillEngineResult)? = null,
    private val displayLeases: VirtualDisplayLeaseRegistry = VirtualDisplayLeaseRegistry(),
) {
    suspend fun run(steps: List<AutomationWorkflowStep>, runId: String, retryUnknown: Boolean = false): ToolOutcome {
        val durableJournal = journal ?: return ToolOutcome.error("El registro del punto de interrupción del flujo de trabajo no está disponible y se rechaza la realización de acciones irrecuperables del dispositivo.")
        return durableJournal.withRunLock(runId) { runLocked(steps, runId, retryUnknown, durableJournal) }
    }

    private suspend fun runLocked(
        steps: List<AutomationWorkflowStep>,
        runId: String,
        retryUnknown: Boolean,
        durableJournal: WorkflowJournal,
    ): ToolOutcome {
        val results = mutableListOf<AutomationWorkflowStepResult>()
        val expectedKeys = steps.mapIndexed { index, step ->
            val prompt = AppJson.encodeToString(AutomationWorkflowStep.serializer(), step)
            index to durableJournal.computeKey(prompt, "automation_workflow:$index")
        }.toMap()
        val resume = try {
            durableJournal.resume(runId, expectedKeys)
        } catch (error: Exception) {
            return ToolOutcome.error("No se puede leer el punto de interrupción del flujo de trabajo:${error.message ?: "run_id Registro no válido o ilegible"}")
        }
        val resumeFrom = resume.resumeFromSeq
        val previousAtResume = try {
            durableJournal.load(runId).get(resumeFrom)
        } catch (error: Exception) {
            return ToolOutcome.error("No se puede leer el punto de interrupción del flujo de trabajo:${error.message ?: "run_id Registro no válido o ilegible"}")
        }
        val uncertainPrevious = previousAtResume?.takeIf { it.status == STATUS_RUNNING || it.status == STATUS_FAILED }
        if (uncertainPrevious != null && !retryUnknown) {
            val action = steps.getOrNull(resumeFrom)?.action ?: "unknown"
            return ToolOutcome.error(
                "Flujo de trabajo en el capítulo ${resumeFrom + 1} paso($action)Si se interrumpe, es posible que se desconozcan los resultados de la acción; para evitar efectos secundarios repetidos," +
                    "Confirme y utilice el mismo run_id y configúrelo explícitamente. retry_unknown=true Intentar otra vez.",
                mapOf(
                    "runId" to runId,
                    "resumeFrom" to resumeFrom,
                    "outcomeUnknown" to true,
                    "uncertainAction" to action,
                ),
            )
        }
        val nextDisplayActionIndex = steps.indices.firstOrNull { index ->
            index >= resumeFrom && steps[index].action.trim().lowercase() in DISPLAY_REQUIRED_VIRTUAL_ACTIONS
        }
        val nextEnsureIndex = steps.indices.firstOrNull { index ->
            index >= resumeFrom && steps[index].action.trim().equals("virtual_ensure", ignoreCase = true)
        }
        val cachedEnsureToRefresh =
            if (nextDisplayActionIndex != null && (nextEnsureIndex == null || nextDisplayActionIndex < nextEnsureIndex)) {
                steps.indices.lastOrNull { index ->
                    index < resumeFrom && steps[index].action.trim().equals("virtual_ensure", ignoreCase = true)
                }
            } else {
                null
            }
        for ((index, step) in steps.withIndex()) {
            val cached = resume.cached[index]
            val cachedResult = cached
                ?.takeIf { index < resumeFrom && it.status == "done" }
                ?.let { runCatching { AppJson.decodeFromString(AutomationWorkflowStepResult.serializer(), it.result) }.getOrNull() }
            val result = cachedResult?.let { cachedStep ->
                val restored = restoreCachedDisplayLease(
                    runId = runId,
                    index = index,
                    step = step,
                    cached = cachedStep,
                    refreshDisplay = index == cachedEnsureToRefresh,
                )
                if (!restored.success) {
                    restored
                } else if (step.action.trim().equals("node_script", ignoreCase = true)) {
                    restored.copy(message = "Node Resultados del script (recuperación segura de puntos de interrupción): ${restored.message}")
                } else if (step.action.trim().equals("virtual_ensure", ignoreCase = true) && restored.displayId != null) {
                    restored.copy(message = "La pantalla virtual está lista displayId=${restored.displayId}")
                } else {
                    restored.copy(message = "Reanudado desde el punto de interrupción del flujo de trabajo")
                }
            } ?: runStepDurably(durableJournal, runId, index, expectedKeys[index].orEmpty(), step)
            results += result
            if (!result.success) break
        }
        val completed = results.count { it.success }
        val success = results.size == steps.size && completed == steps.size
        val content = buildString {
            append(if (success) "Flujo de trabajo completado" else "Flujo de trabajo en el capítulo ${completed + 1} parada de paso")
            append("($completed/${steps.size})")
            results.forEach { item ->
                appendLine()
                append("[${item.index}] ${item.action}: ${item.message}")
            }
        }
        return if (success) {
            ToolOutcome.ok(
                content,
                mapOf("runId" to runId, "resumeFrom" to resumeFrom, "completed" to completed, "total" to steps.size, "steps" to results),
            )
        } else {
            ToolOutcome.error(
                content,
                mapOf("runId" to runId, "resumeFrom" to resumeFrom, "completed" to completed, "total" to steps.size, "steps" to results),
            )
        }
    }

    /** Persist intent before side effects and completion after; if completion persistence fails, next run blocks. */
    private suspend fun runStepDurably(
        journal: WorkflowJournal,
        runId: String,
        index: Int,
        key: String,
        step: AutomationWorkflowStep,
    ): AutomationWorkflowStepResult {
        try {
            journal.recordRequired(runId, index, key, "", STATUS_RUNNING, WorkflowJournal.NODE_KIND_TOOL_ONLY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(index, step.action, "No se puede conservar el estado de inicio de la acción, no ejecutado:${error.message ?: "Error al escribir el registro"}")
        }
        val result = executeStep(index, step, runId)
        return try {
            journal.recordRequired(
                runId,
                index,
                key,
                resultForJournal(step, result),
                when {
                    result.success -> "done"
                    result.outcomeUnknown -> STATUS_FAILED
                    else -> STATUS_FAILED_KNOWN
                },
                if (step.action.trim().equals("node_script", ignoreCase = true)) {
                    WorkflowJournal.NODE_KIND_SENSITIVE_TOOL
                } else {
                    WorkflowJournal.NODE_KIND_TOOL_ONLY
                },
            )
            result
        } catch (cancelled: CancellationException) {
            // The action may already have taken effect. Keep the durable `running` marker so resume blocks
            // instead of silently replaying an unknown side effect.
            throw cancelled
        } catch (error: Exception) {
            failure(index, step.action, "Se intentó realizar la acción, pero el resultado no pudo persistir y se tratará como un efecto secundario desconocido cuando se restablezca.:${error.message ?: "Error al escribir el registro"}", outcomeUnknown = true)
        }
    }

    private fun resultForJournal(step: AutomationWorkflowStep, result: AutomationWorkflowStepResult): String {
        // Screen snapshots may contain private messages or account data; keep them in the live tool response only.
        if (step.action.trim().lowercase() in setOf("read", "virtual_read")) return ""
        return AppJson.encodeToString(AutomationWorkflowStepResult.serializer(), result)
    }

    private suspend fun executeStep(index: Int, step: AutomationWorkflowStep, runId: String): AutomationWorkflowStepResult {
        val action = step.action.trim().lowercase()
        return runCatching {
            when (action) {
                "launch" -> executeLaunch(index, action, step)
                "tap_text" -> executeTapText(index, action, step)
                "input_text" -> executeInputText(index, action, step)
                "swipe" -> executeSwipe(index, action, step)
                "back" -> result(index, action, manager.back(), "Regresar")
                "home" -> result(index, action, manager.home(), "Volver al escritorio")
                "wait" -> executeWait(index, action, step)
                "read" -> executeRead(index, action)
                "node_script" -> executeNodeScript(index, action, step)
                "virtual_ensure", "virtual_launch", "virtual_tap", "virtual_tap_text", "virtual_swipe", "virtual_text",
                "virtual_key", "virtual_wait", "virtual_read", "virtual_close",
                -> executeVirtualStep(index, action, step, runId)
                else -> failure(index, action, "Acción no admitida")
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }.getOrElse { error -> failure(index, action, "excepción de ejecución:${error.message ?: "unknown"}", outcomeUnknown = true) }
    }

    private suspend fun executeNodeScript(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val code = step.code?.takeIf { it.isNotBlank() }
            ?: return failure(index, action, "desaparecido code")
        if (code.length > NodeScriptTool.MAX_SCRIPT_CHARS) {
            return failure(index, action, "El guión supera la longitud máxima(${NodeScriptTool.MAX_SCRIPT_CHARS} personaje)")
        }
        val executor = nodeScriptExecutor ?: return failure(index, action, "Node El script no se inicializa cuando se ejecuta")
        val timeoutMs = (step.timeoutMs ?: DEFAULT_NODE_SCRIPT_TIMEOUT_MS).coerceIn(1_000L, MAX_NODE_SCRIPT_TIMEOUT_MS)
        return when (val outcome = executor(code, timeoutMs)) {
            is SkillEngineResult.Success -> result(
                index,
                action,
                true,
                NodeScriptTool.formatResultJson(outcome),
            )
            is SkillEngineResult.Error -> failure(
                index,
                action,
                "Node La ejecución del script falló, es posible que se hayan producido efectos secundarios; La repetición automática no se producirá." +
                    NodeScriptTool.formatResultJson(outcome),
                outcomeUnknown = true,
            )
        }
    }

    private suspend fun executeLaunch(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val pkg = step.packageName?.trim().orEmpty()
        return if (!AppControlCore.isValidPackageName(pkg)) {
            failure(index, action, "Nombre de paquete no válido")
        } else {
            result(index, action, manager.launchApp(pkg), "empezar $pkg")
        }
    }

    private suspend fun executeTapText(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val text = step.text?.takeIf { it.isNotBlank() } ?: return failure(index, action, "desaparecido text")
        val outcome = manager.tapByTextWithRetryDetailed(
            text = text,
            exact = step.exact,
            maxSwipes = step.maxSwipes,
            verifyText = step.verifyText?.takeIf { it.isNotBlank() },
        )
        return result(index, action, outcome.success, "partido=${outcome.matched},verificar=${outcome.verified},intentarlo=${outcome.attempts}")
    }

    private suspend fun executeInputText(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val text = step.text ?: return failure(index, action, "desaparecido text")
        return result(index, action, manager.inputText(text), "Se ha introducido texto (${text.length} personajes)")
    }

    private suspend fun executeSwipe(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val points = listOf(step.x1, step.y1, step.x2, step.y2)
        return if (points.any { it == null }) {
            failure(index, action, "desaparecido x1/y1/x2/y2")
        } else {
            result(
                index,
                action,
                manager.swipe(step.x1!!, step.y1!!, step.x2!!, step.y2!!, step.durationMs.coerceIn(50L, 5_000L)),
                "Deslizar",
            )
        }
    }

    private suspend fun executeWait(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val ms = step.durationMs.coerceIn(50L, 10_000L)
        delay(ms)
        return result(index, action, true, "espera ${ms}ms")
    }

    private suspend fun executeRead(index: Int, action: String): AutomationWorkflowStepResult {
        val screen = manager.readScreen()
        return result(index, action, true, screen.toSummary(20))
    }

    private suspend fun executeVirtualStep(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        runId: String,
    ): AutomationWorkflowStepResult {
        val client = virtualDisplayClient ?: return failure(index, action, "El flujo de trabajo de la pantalla virtual no está inicializado")
        val displayManager = virtualDisplayManager ?: return failure(index, action, "El flujo de trabajo de la pantalla virtual no está inicializado")
        if (action == "virtual_ensure") {
            val display = ensureVirtualDisplay(step, client)
                .getOrElse { return failure(index, action, "No se pudo crear la pantalla virtual:${it.message}") }
            displayLeases.acquire(runId, display.displayId)
            return result(
                index,
                action,
                true,
                "La pantalla virtual está lista displayId=${display.displayId} (${display.width}x${display.height})",
                displayId = display.displayId,
            )
        }

        val leasedDisplayId = displayLeases.displayFor(runId)
        if (step.displayId != null && leasedDisplayId != null && !displayLeases.isKnownDisplayId(runId, step.displayId)) {
            return failure(index, action, "displayId=${step.displayId} Una pantalla virtual que no pertenece al run_id actual lease")
        }
        if (action == "virtual_close" && leasedDisplayId == null) {
            return failure(index, action, "No hay una pantalla virtual liberable para el run_id actual lease")
        }
        val displayId = leasedDisplayId ?: step.displayId ?: displayManager.lastDisplayId
        if (displayId < 0) {
            if (action != "virtual_launch") return failure(index, action, "La pantalla virtual no ha sido creada; por favor ejecútelo primero virtual_ensure")
            val display = ensureVirtualDisplay(step, client)
                .getOrElse { return failure(index, action, "No se pudo crear la pantalla virtual:${it.message}") }
            displayLeases.acquire(runId, display.displayId)
            return launchOnVirtualDisplay(index, action, step, client, display.displayId)
        }
        if (leasedDisplayId == null && action != "virtual_close") {
            // Compatible con el flujo de trabajo anterior: los pasos históricos dependen de manager.lastDisplayId,
            // La pantalla compatible se registra como un arrendamiento compartido cuando se usa por primera vez y los cierres posteriores aún están protegidos por el propietario.
            displayLeases.acquire(runId, displayId)
        }

        return when (action) {
            "virtual_launch" -> launchOnVirtualDisplay(index, action, step, client, displayId)
            "virtual_read" -> {
                val screen = manager.readScreenOnDisplay(displayId)
                    ?: return failure(index, action, "La lectura de pantalla semántica de pantalla virtual no está disponible; requiere Shizuku o Root")
                result(index, action, true, screen.toSummary(30))
            }
            "virtual_tap_text" -> executeVirtualTapText(index, action, step, displayManager, displayId)
            "virtual_close" -> {
                val canDestroy = displayLeases.release(runId, displayId)
                if (!canDestroy) {
                    result(index, action, true, "Run_id lanzado para pantalla virtual $displayId arrendamiento, la pantalla todavía es utilizada por otros flujos de trabajo")
                } else {
                    val destroyed = client.destroy(displayId)
                    // A service restart can make the old display disappear before close.
                    // VirtualDisplayClient clears the stale compatibility id in that case;
                    // treat the already-gone display as an idempotent successful close and
                    // never re-acquire a lease for a dead display.
                    val staleDisplayGone = !destroyed && displayManager.lastDisplayId != displayId
                    if (!destroyed && !staleDisplayGone) displayLeases.acquire(runId, displayId)
                    result(
                        index,
                        action,
                        destroyed || staleDisplayGone,
                        if (staleDisplayGone) "pantalla virtual $displayId Ya no existe, el procesamiento se cierra de forma idempotente." else "Destruir la pantalla virtual $displayId",
                    )
                }
            }
            else -> executeVirtualInput(index, action, step, displayManager, displayId)
        }
    }

    private suspend fun restoreCachedDisplayLease(
        runId: String,
        index: Int,
        step: AutomationWorkflowStep,
        cached: AutomationWorkflowStepResult,
        refreshDisplay: Boolean,
    ): AutomationWorkflowStepResult {
        if (!step.action.trim().equals("virtual_ensure", ignoreCase = true)) return cached
        val displayId = cached.displayId
            ?: CACHED_DISPLAY_ID_REGEX.find(cached.message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return cached
        displayLeases.acquire(runId, displayId)
        if (!refreshDisplay) return cached.copy(displayId = displayId)

        val client = virtualDisplayClient ?: return failure(index, step.action, "El flujo de trabajo de la pantalla virtual no está inicializado")
        val display = ensureVirtualDisplay(step, client)
            .getOrElse { return failure(index, step.action, "No se pudo restaurar la pantalla virtual:${it.message}") }
        displayLeases.acquire(runId, display.displayId)
        return cached.copy(
            message = "El servicio de pantalla virtual se ha actualizado después de reiniciar displayId=${display.displayId} (${display.width}x${display.height})",
            displayId = display.displayId,
        )
    }

    private suspend fun ensureVirtualDisplay(
        step: AutomationWorkflowStep,
        client: VirtualDisplayClient,
    ): Result<VirtualDisplayClient.DisplayHandle> {
        val width = step.width ?: 720
        val height = step.height ?: 1280
        val dpi = step.dpi ?: 320
        if (width !in 320..1920 || height !in 320..2560 || dpi !in 120..640) {
            return Result.failure(IllegalArgumentException("El tamaño o la densidad de la pantalla virtual excede el rango seguro"))
        }
        return client.ensureDisplay(width, height, dpi)
    }

    private suspend fun launchOnVirtualDisplay(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        client: VirtualDisplayClient,
        displayId: Int,
    ): AutomationWorkflowStepResult {
        val packageName = step.packageName?.trim().orEmpty()
        if (!AppControlCore.isValidPackageName(packageName)) return failure(index, action, "Nombre de paquete no válido")
        return result(index, action, client.openApp(packageName, displayId), "en pantalla virtual $displayId empezar $packageName")
    }

    /** Semantic interaction stays on the selected virtual display and never falls back to the user's foreground UI. */
    private suspend fun executeVirtualTapText(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        displayManager: VirtualDisplayServerManager,
        displayId: Int,
    ): AutomationWorkflowStepResult {
        val text = step.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: return failure(index, action, "desaparecido text")
        val outcome = VirtualDisplaySemanticActions.tapText(
            manager = manager,
            displayManager = displayManager,
            displayId = displayId,
            text = text,
            exact = step.exact,
            maxSwipes = step.maxSwipes,
            verifyText = step.verifyText,
        )
        return if (outcome.success) {
            result(index, action, true, "partido=${outcome.matched},verificar=${outcome.verified},intentarlo=${outcome.attempts}")
        } else {
            failure(
                index,
                action,
                "partido=${outcome.matched},verificar=${outcome.verified},intentarlo=${outcome.attempts}${outcome.error?.let { ",$it" }.orEmpty()}",
                outcomeUnknown = outcome.outcomeUnknown,
            )
        }
    }

    private suspend fun executeVirtualInput(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        displayManager: VirtualDisplayServerManager,
        displayId: Int,
    ): AutomationWorkflowStepResult {
        val inputAction = action.removePrefix("virtual_")
        val args = buildMap {
            step.x?.let { put("x", it.toString()) }
            step.y?.let { put("y", it.toString()) }
            step.x1?.let { put("x1", it.toString()) }
            step.y1?.let { put("y1", it.toString()) }
            step.x2?.let { put("x2", it.toString()) }
            step.y2?.let { put("y2", it.toString()) }
            put("duration_ms", step.durationMs.toString())
            put("ms", step.durationMs.toString())
            step.text?.let { put("text", it) }
            step.key?.let { put("key", it) }
        }
        val command = VirtualDisplayInputCommand.build(displayId, inputAction, args)
            ?: return failure(index, action, "El parámetro de entrada de la pantalla virtual no es válido")
        if (command.waitMs != null) {
            delay(command.waitMs)
            return result(index, action, true, "pantalla virtual $displayId espera ${command.waitMs}ms")
        }
        when (val check = DeviceCommandPolicy.validate(command.shellCommand)) {
            is DeviceCommandPolicy.Check.Invalid -> return failure(index, action, "El comando de entrada fue rechazado por la política.:${check.reason}")
            DeviceCommandPolicy.Check.Valid -> Unit
        }
        val execution = displayManager.exec(command.shellCommand)
        return result(
            index,
            action,
            execution.exitCode == 0,
            if (execution.exitCode == 0) {
                "pantalla virtual $displayId ${command.description}éxito"
            } else {
                "pantalla virtual $displayId ${command.description}falló(exit=${execution.exitCode})"
            },
        )
    }

    private fun result(index: Int, action: String, success: Boolean, message: String, displayId: Int? = null) =
        AutomationWorkflowStepResult(index, action, success, message, outcomeUnknown = !success, displayId = displayId)

    private fun failure(index: Int, action: String, message: String, outcomeUnknown: Boolean = false) =
        AutomationWorkflowStepResult(index, action, false, message, outcomeUnknown)
}

@Serializable
data class AutomationWorkflowStep(
    val action: String,
    val packageName: String? = null,
    val displayId: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val dpi: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val key: String? = null,
    val text: String? = null,
    val verifyText: String? = null,
    val exact: Boolean = false,
    val maxSwipes: Int = 0,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val durationMs: Long = 400L,
    val code: String? = null,
    val timeoutMs: Long? = null,
)

@Serializable
data class AutomationWorkflowStepResult(
    val index: Int,
    val action: String,
    val success: Boolean,
    val message: String,
    val outcomeUnknown: Boolean = false,
    val displayId: Int? = null,
)

internal object AutomationWorkflowParser {
    private const val MAX_STEPS = 20

    fun parse(json: String): Result<List<AutomationWorkflowStep>> = runCatching {
        val steps = AppJson.decodeFromString(
            ListSerializer(AutomationWorkflowStep.serializer()),
            json,
        )
        require(steps.isNotEmpty()) { "steps no puede estar vacío" }
        require(steps.size <= MAX_STEPS) { "steps Más apoyados $MAX_STEPS paso" }
        steps
    }
}

private const val STATUS_RUNNING = "running"
private const val STATUS_FAILED = "failed"
private const val STATUS_FAILED_KNOWN = "failed_known"
private const val DEFAULT_NODE_SCRIPT_TIMEOUT_MS = 30_000L
private const val MAX_NODE_SCRIPT_TIMEOUT_MS = 300_000L
