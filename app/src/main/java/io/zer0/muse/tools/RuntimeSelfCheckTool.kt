package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.muse.runtime.MuseRuntime
import io.zer0.muse.runtime.PythonRuntime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * v2.x Tiempo de ejecución extendido (P0 Pruebas): herramienta de autoprueba integrada de Node Sandbox.
 *
 * Los usuarios pueden pedirle al asistente que llame a esta herramienta en el chat para verificar el tiempo de ejecución integrado (libmuse_node.so + npm datos)
 * Disponibilidad en dispositivos reales. Excepto por la descompresión de datos única, todas las comprobaciones son de solo lectura y no tienen otros efectos secundarios.
 */
object RuntimeSelfCheckTool {

    const val NAME = "runtime_selfcheck"

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        description = "Consulte el tiempo de ejecución de Node integrado de Muse(Entorno de ejecución dentro del entorno limitado de la aplicación)Estado:node versión,npm Estado de versión y descompresión de datos." +
            "Cuando el usuario pregunta sobre el \"tiempo de ejecución integrado\" y la \"autoprueba del tiempo de ejecución\"Node Caja de arena\"MCP \"Entorno de ejecución\" o se llama cuando necesita solucionar problemas de tiempo de ejecución integrados." +
            "Regresar: Texto del resultado de la subinspección.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    suspend fun execute(
        context: Context,
        /** v2.6.7: Inyección opcional. Si es necesario, agregue la sección \"Canal de tres niveles de automatización de equipos\" —— El autoexamen debe realizarse de una sola vez. */
        automation: io.zer0.muse.automation.core.AutomationManager? = null,
    ): String {
        val sb = StringBuilder("[Autoprueba de tiempo de ejecución incorporada]")

        val node = MuseRuntime.nodeBinary(context)
        sb.append("\n- Node binario: ").append(
            if (node.isFile) {
                "existir (${node.length() / 1024 / 1024}MB)"
            } else {
                "desaparecido (${MuseRuntime.nativeLibDir(context)})"
            },
        )

        val nodeV = runCatching { MuseRuntime.execNode(context, listOf("-v"), timeoutMs = 20_000) }.getOrNull()
        sb.append("\n- node -v: ").append(
            when {
                nodeV == null -> "excepción de ejecución"
                nodeV.exitCode == 0 -> "${nodeV.stdout} ✓"
                else -> {
                    val detail = if (nodeV.stderr.isNotBlank()) " ${nodeV.stderr.take(120)}" else ""
                    "falló (exit=${nodeV.exitCode}$detail)"
                }
            },
        )

        val data = MuseRuntime.ensureData(context)
        sb.append("\n- datos de tiempo de ejecución: ").append(
            if (data.isSuccess) {
                "Listo (${MuseRuntime.RUNTIME_DATA_VERSION})"
            } else {
                "La descompresión falló (${data.exceptionOrNull()?.message})"
            },
        )

        val npmCli = MuseRuntime.npmCli(context)
        if (npmCli.isFile) {
            val npmV = runCatching {
                MuseRuntime.execNode(context, listOf(npmCli.absolutePath, "-v"), timeoutMs = 60_000)
            }.getOrNull()
            sb.append("\n- npm -v: ").append(
                when {
                    npmV == null -> "excepción de ejecución"
                    npmV.exitCode == 0 -> "${npmV.stdout} ✓"
                    else -> "falló (exit=${npmV.exitCode})"
                },
            )
        } else {
            sb.append("\n- npm: No listo")
        }

        sb.append(buildCpuSection(probeCpu(context)))

        sb.append("\n- Conclusión: ").append(
            if (nodeV?.exitCode == 0 && MuseRuntime.isReady(context)) {
                "Listo en tiempo de ejecución ✓"
            } else {
                "Incompleto en tiempo de ejecución, consulte los detalles arriba"
            },
        )
        sb.append("\n\n")
        appendPythonSection(context, sb)
        // v2.6.7: Crea el tercer canal. —— Autoexamen del asistente: "runtime_selfcheck Solo me registré en Node/Python,
        // Los tres canales de automatización de la interfaz de usuario no se informan y la autoprueba no se realiza, por lo que es necesario ajustarla dos veces". Ahora repórtalo todo de una vez.
        automation?.let { appendChannelSection(it, sb) }
        return sb.toString()
    }

    /**
     * CPU Detección: Nodo incorporado `os.cpus()` / `os.availableParallelism()` Ya sea para obtener el número central.
     *
     * De hecho, es posible que no se pueda obtener el entorno sandbox (libuv La lista de CPU no se puede leer), por lo que solo informa advertencias y no participa en la conclusión general.
     */
    private suspend fun probeCpu(context: Context): CpuProbe? {
        val r = runCatching { MuseRuntime.execNode(context, listOf("-e", CPU_PROBE_SCRIPT), timeoutMs = 20_000) }.getOrNull()
        if (r == null || r.exitCode != 0) return null
        val line = r.stdout.lineSequence().lastOrNull { it.trim().startsWith("{") }?.trim() ?: return null
        return runCatching {
            val obj = AppJson.parseToJsonElement(line) as? JsonObject ?: return@runCatching null
            CpuProbe(
                entries = (obj["cpus"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                parallelism = (obj["ap"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                envCount = (obj["env"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            )
        }.getOrNull()
    }

    /** Representa elementos de detección de CPU (función pura, conveniente para pruebas individuales). Si no se obtiene el valor, se informará una advertencia, que no afecta la conclusión general. */
    internal fun buildCpuSection(probe: CpuProbe?): String {
        val prefix = "\n- CPU detección: "
        if (probe == null) return prefix + "warn(La detección no se ejecutó con éxito; el hecho de no obtener el número central en el sandbox no afecta la conclusión general)"
        val entries = probe.entries ?: 0
        val parallelism = probe.parallelism ?: 0
        val detail =
            "os.cpus().length=$entries, os.availableParallelism()=$parallelism, " +
                "MUSE_CPU_COUNT=${probe.envCount ?: "No inyectado"}"
        return if (entries > 0 && parallelism > 0) {
            prefix + detail + " ✓"
        } else {
            prefix + detail + " warn(No se puede obtener la cantidad de núcleos; los scripts que dependen de la cantidad de CPU se degradarán, pero eso no afecta la conclusión general)"
        }
    }

    /** Resultado de detección de CPU del lado del nodo integrado (nulo si falta el campo). */
    internal data class CpuProbe(val entries: Int?, val parallelism: Int?, val envCount: Int?)

    private const val CPU_PROBE_SCRIPT =
        "const os=require('node:os');" +
            "console.log(JSON.stringify({cpus:os.cpus().length," +
            "ap:os.availableParallelism(),env:process.env.MUSE_CPU_COUNT||null}))"

    /** Segmento del canal de control del dispositivo (accesible / Shizuku / Root + Solicitud de permiso de tiempo de ejecución único). */
    private suspend fun appendChannelSection(automation: io.zer0.muse.automation.core.AutomationManager, sb: StringBuilder) {
        val state = automation.refreshPermissions()
        sb.append("\n\n[Autoprueba del canal de control del equipo]")
        sb.append("\n- Accesibilidad: ").append(if (state.accessibilityEnabled) "Ya encendido ✓" else "No encendido")
        sb.append("\n- Shizuku/ADB: ").append(
            if (state.shellEnabled) "Listo ✓" else "No disponible (${state.shizukuMessage})",
        )
        sb.append("\n- Root: ").append(if (state.rootEnabled) "Obtenido ✓" else "No obtenido")
        sb.append("\n- Nivel más alto disponible: ").append(state.highestLevelName())
        sb.append("\n- Conclusión: ").append(
            if (state.anyEnabled) {
                "Se pueden realizar operaciones del dispositivo; por favor llame a las herramientas específicas disponibles. tool_status"
            } else {
                "Sin canal de control de dispositivo, pantalla/Solicitud/shell Todas las herramientas no están disponibles; sólo se pueden realizar operaciones dentro de la aplicación"
            },
        )
    }

    /** v2.7: Se agregó una sección de autoverificación del tiempo de ejecución de Python incorporada (eliminada para controlar la longitud y la complejidad de una sola función). */
    private suspend fun appendPythonSection(context: Context, sb: StringBuilder) {
        sb.append("[Autoprueba integrada del tiempo de ejecución de Python]")
        val pyBin = PythonRuntime.pythonBinary(context)
        sb.append("\n- Python stub: ").append(
            if (pyBin.isFile) "existir (${pyBin.length() / 1024}KB)" else "desaparecido (${PythonRuntime.nativeLibDir(context)})",
        )
        val pyV = runCatching { PythonRuntime.execPython(context, listOf("-V"), timeoutMs = 30_000) }.getOrNull()
        sb.append("\n- python -V: ").append(
            when {
                pyV == null -> "excepción de ejecución"
                pyV.exitCode == 0 -> "${(pyV.stdout + pyV.stderr).trim()} ✓"
                else -> "falló (exit=${pyV.exitCode} ${pyV.stderr.take(120)})"
            },
        )
        val pyData = PythonRuntime.ensureData(context)
        sb.append("\n- Python datos: ").append(
            if (pyData.isSuccess) "Listo (${PythonRuntime.PYTHON_DATA_VERSION})" else "La descompresión falló (${pyData.exceptionOrNull()?.message})",
        )
        if (pyV?.exitCode == 0 && pyData.isSuccess) {
            val probe = runCatching {
                PythonRuntime.execPython(
                    context,
                    listOf("-c", "import ssl,sqlite3,zlib,json;print('ext-ok')"),
                    timeoutMs = 30_000,
                )
            }.getOrNull()
            sb.append("\n- Extensiones de biblioteca estándar: ").append(
                if (probe?.exitCode == 0 && probe.stdout.contains("ext-ok")) "ssl/sqlite3/zlib/json ✓" else "Cargando excepción",
            )
        }
        sb.append("\n- Python Conclusión: ").append(
            if (pyV?.exitCode == 0 && PythonRuntime.isReady(context)) "Listo en tiempo de ejecución ✓" else "Incompleto en tiempo de ejecución, consulte los detalles arriba",
        )
    }
}

/**
 * v2.x Tiempo de ejecución extendido (P0): Registro de herramienta de autoprueba (init El bloque se registra automáticamente en ToolRegistry).
 */
class RuntimeSelfCheckToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
    /** v2.6.7: Después de la inyección, la autoprueba informará el estado de los tres canales juntos. */
    private val automationManager: io.zer0.muse.automation.core.AutomationManager? = null,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(RuntimeSelfCheckTool.toolDef()) { _ ->
            RuntimeSelfCheckTool.execute(context, automationManager)
        }
    }
}
