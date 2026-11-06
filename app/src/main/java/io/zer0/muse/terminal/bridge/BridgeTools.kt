package io.zer0.muse.terminal.bridge

import android.content.Context
import io.zer0.muse.terminal.TermuxChannel
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * Tools on top of the existing Termux channel.
 *
 * `termux_exec` stays synchronous. Long work uses the job tools, which can be queried and cancelled
 * after Muse is backgrounded. Nightzuku commands go through the detected `rish` and require
 * `uid=2000(shell)` plus an explicit grant for modify / destructive actions.
 */
internal object BridgeTools {
    const val NIGHTZUKU_EXEC = "nightzuku_exec"
    const val JOB_START = "bridge_job_start"
    const val JOB_STATUS = "bridge_job_status"
    const val JOB_CANCEL = "bridge_job_cancel"
    const val DIAGNOSE = "bridge_diagnose"

    fun nightzukuExecDef() = def(
        name = NIGHTZUKU_EXEC,
        description = "Ejecuta un comando en el shell de Nightzuku (uid=2000) vía rish, sin root. " +
            "Runs one command through Nightzuku rish as uid=2000(shell), never as root. " +
            "Lectura permitida tras verificar la identidad; modificar o destruir exige la autorización " +
            "explícita de Ajustes. Para un comando largo usa bridge_job_start.",
        parameters = mapOf(
            "command" to "Obligatorio. Comando para rish -c, por ejemplo dumpsys window o settings get system screen_off_timeout.",
            "timeout_ms" to "Opcional. Milisegundos, 1000-600000, por defecto 60000.",
            "workdir" to "Opcional. Directorio de trabajo dentro de Termux.",
        ),
        required = setOf("command"),
        risk = ToolRiskLevel.HIGH,
        parameterTypes = mapOf("timeout_ms" to "integer"),
    )

    fun jobStartDef() = def(
        name = JOB_START,
        description = "Arranca un comando Termux o Nightzuku y devuelve un id de inmediato. " +
            "Starts a Termux or Nightzuku command and returns a job id immediately. " +
            "Consulta con bridge_job_status y cancela con bridge_job_cancel. " +
            "channel=termux (por defecto) o channel=nightzuku.",
        parameters = mapOf(
            "command" to "Obligatorio. Comando a ejecutar.",
            "channel" to "Opcional. termux o nightzuku. Por defecto termux.",
            "timeout_ms" to "Opcional. Milisegundos, 1000-600000, por defecto 60000.",
            "workdir" to "Opcional. Directorio de trabajo dentro de Termux.",
        ),
        required = setOf("command"),
        risk = ToolRiskLevel.HIGH,
        parameterTypes = mapOf("timeout_ms" to "integer"),
    )

    fun jobStatusDef() = def(
        name = JOB_STATUS,
        description = "Devuelve el estado, el código de salida y la salida de un trabajo del puente. " +
            "Returns state, exit code and output for a bridge job id.",
        parameters = mapOf("id" to "Obligatorio. Id devuelto por bridge_job_start o nightzuku_exec."),
        required = setOf("id"),
        risk = ToolRiskLevel.NORMAL,
    )

    fun jobCancelDef() = def(
        name = JOB_CANCEL,
        description = "Cancela un trabajo del puente que sigue en curso. Cancels a running bridge job.",
        parameters = mapOf("id" to "Obligatorio. Id del trabajo."),
        required = setOf("id"),
        risk = ToolRiskLevel.NORMAL,
    )

    fun diagnoseDef() = def(
        name = DIAGNOSE,
        description = "Informa si Termux, RUN_COMMAND, rish y uid=2000(shell) están listos, " +
            "y el estado de las autorizaciones. Reports Termux, rish, shell identity and grants. " +
            "No ejecuta el comando del usuario.",
        parameters = mapOf("refresh" to "Opcional. false reutiliza la última sonda."),
        required = emptySet(),
        risk = ToolRiskLevel.NORMAL,
        parameterTypes = mapOf("refresh" to "boolean"),
    )

    suspend fun executeNightzuku(args: Map<String, String>, context: Context): String {
        val command = args["command"]?.trim().orEmpty()
        if (command.isEmpty()) return "Error: command is required."
        return BridgeCoordinator.get(context).runNightzuku(command, timeoutOf(args), workdirOf(args))
    }

    suspend fun executeJobStart(args: Map<String, String>, context: Context): String {
        val command = args["command"]?.trim().orEmpty()
        if (command.isEmpty()) return "Error: command is required."
        val channel = channelOf(args["channel"])
        return BridgeCoordinator.get(context).startJob(channel, command, timeoutOf(args), workdirOf(args))
    }

    fun executeJobStatus(args: Map<String, String>, context: Context): String {
        val id = args["id"]?.trim().orEmpty()
        if (id.isEmpty()) return "Error: id is required."
        return BridgeCoordinator.get(context).job(id)
    }

    fun executeJobCancel(args: Map<String, String>, context: Context): String {
        val id = args["id"]?.trim().orEmpty()
        if (id.isEmpty()) return "Error: id is required."
        return BridgeCoordinator.get(context).cancel(id)
    }

    suspend fun executeDiagnose(args: Map<String, String>, context: Context): String {
        val refresh = !args["refresh"].equals("false", ignoreCase = true)
        return BridgeText.diagnose(BridgeCoordinator.get(context).diagnose(refresh))
    }

    private fun channelOf(raw: String?): BridgeChannel {
        return if (raw.equals("nightzuku", ignoreCase = true)) BridgeChannel.NIGHTZUKU else BridgeChannel.TERMUX
    }

    private fun timeoutOf(args: Map<String, String>): Long {
        val requested = args["timeout_ms"]?.toLongOrNull() ?: TermuxChannel.DEFAULT_TIMEOUT_MS
        return requested.coerceIn(1_000L, TermuxChannel.MAX_TIMEOUT_MS)
    }

    private fun workdirOf(args: Map<String, String>): String? {
        return args["workdir"]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun def(
        name: String,
        description: String,
        parameters: Map<String, String>,
        required: Set<String>,
        risk: ToolRiskLevel,
        parameterTypes: Map<String, String> = emptyMap(),
    ) = ToolRegistry.ToolDef(
        name = name,
        description = description,
        parameters = parameters,
        required = required,
        category = "built-in",
        parameterTypes = parameterTypes,
        riskLevel = risk,
    )
}
