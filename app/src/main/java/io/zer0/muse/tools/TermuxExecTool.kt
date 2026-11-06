package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.terminal.TermuxChannel

/**
 * v2.2.1 Termux canal:termux_exec — instalado por el usuario Termux(Entorno Linux completo)Ejecute el comando en .
 *
 * División del trabajo con los otros dos canales de ejecución:
 *  - terminal_exec:Zona de pruebas de aplicaciones(App permisos propios),archivos livianos/guión;
 *  - device_shell:Línea de comando a nivel de dispositivo(necesidad Shizuku/Root);
 *  - termux_exec(esta herramienta):Entorno Linux completo(bash/apt/pip/gcc/ffmpeg……),Requiere instalación del usuario Termux
 *    y presione [TermuxChannel.describe] El guía completa la autorización.;Sinceramente, regrese a la copia de orientación cuando no esté lista.
 *
 * HIGH riesgo,Siga la cadena de aprobación existente.
 *
 * límite de seguridad:[ShellCommandBoundary] primera marcha —— con [TerminalExecTool] Compartiendo el mismo conjunto de "absolutamente no se permite aparecer"
 * conjunto mínimo(Eliminar datos privados de la aplicación/Partición del sistema, escritura de dispositivos de bloque, formato, descarga y ejecución, escalada de privilegios y comandos a nivel de dispositivo, etc.),
 * TRUSTED El modo y "Permitir siempre esta herramienta" no se pueden omitir. Aquí la segunda marcha no está bloqueada.:Comando destructivo pero probablemente funcional.
 * Realizar como de costumbre,a cargo de aprobaciones caso por caso de ALTO riesgo.
 */
object TermuxExecTool {
    const val NAME = "termux_exec"
    private const val TAG = "TermuxExec"

    fun toolDef() = ToolRegistry.ToolDef(
        name = NAME,
        description = "en Termux(Requiere instalación y autorización del usuario.)Ejecute un único comando bash en un entorno Linux completo y devuelva el resultado." +
            "Adecuado para tareas completas de cadena de herramientas como apt/pip/gcc/ffmpeg;Para comandos ligeros en el sandbox terminal_exec," +
            "Control de nivel de dispositivo(Haga clic/Iniciar y detener aplicaciones)Utilice dispositivo_shell. Devuelve el código de salida completo con stdout/stderr;" +
            "Regrese al inicio de configuración cuando el canal no esté listo.",
        parameters = mapOf(
            "command" to "Requerido.bash comando,Como por ejemplo pkg list-installed / python myscript.py / ls ~",
            "timeout_ms" to "Opcional. Tiempo de espera en milisegundos,Predeterminado 60000,alcance 1000-600000",
            "workdir" to "Opcional. directorio de trabajo(Predeterminado Termux home)",
        ),
        required = setOf("command"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, context: Context): String {
        val command = args["command"]?.trim().orEmpty()
        if (command.isEmpty()) return "Error: command is required."
        // El límite estricto se coloca antes de tomar el canal.:Cuando se activa la primera marcha, ni siquiera el proceso en el lado de Termux se iniciará.
        boundaryRejection(command)?.let { return it }
        val timeoutMs = (args["timeout_ms"]?.toLongOrNull() ?: TermuxChannel.DEFAULT_TIMEOUT_MS)
            .coerceIn(1_000L, TermuxChannel.MAX_TIMEOUT_MS)
        val workdir = args["workdir"]?.trim()?.takeIf { it.isNotEmpty() }

        val channel = TermuxChannel.get(context)
        val availability = channel.availability()
        if (availability !is TermuxChannel.Availability.Ready) {
            return TermuxChannel.describe(availability)
        }

        val result = channel.exec(command, timeoutMs, workdir)
        if (result.error != null) {
            return buildString {
                append("Termux Ejecución no completada: ").append(result.error)
                append("\nSi aún no se ha configurado la llamada externa de Termux,Por favor ejecute en Termux ")
                append("`echo 'allow-external-apps = true' >> ~/.termux/termux.properties` y reiniciar Termux,")
                append("y en la configuración del sistema→Solicitud→Termux→Se otorgan permisos adicionales para \"Ejecutar comandos de Termux\".")
            }
        }
        return buildString {
            append("[Termux] exit=").append(result.exitCode).append('\n')
            if (result.stderr.isNotBlank()) {
                append("--- stderr ---\n").append(result.stderr).append('\n')
            }
            val out = result.stdout
            if (out.isBlank()) {
                append("(Sin salida)")
            } else {
                append(out)
            }
        }
    }

    /**
     * Comando de primera marcha con límites estrictos:Pulsar rechazar copia devuelta al modelo,Devuelve nulo en caso de error.
     *
     * `workspaceDir` Pasar nulo: esta herramienta se ejecuta en el propio árbol de directorios de Termux,El directorio actual también puede ser `workdir` Reescritura de parámetros,
     * El espacio de trabajo de la aplicación no es su alcance.,Tomar el espacio de trabajo de la aplicación como punto de referencia juzgará mal "en el espacio de trabajo".null No puedo conseguir ningún espacio de trabajo
     * exención:Cada ruta absoluta encontrada por el comando se juzga según el punto de aterrizaje del dispositivo.(partición del sistema/Datos privados de la aplicación/volumen de almacenamiento/Los nodos de equipo todavía están bloqueados).
     * La única concesión es que la trayectoria relativa del punto de aterrizaje no se puede determinar y no participa en la interceptación.,Esa parte corre a cargo del texto original de la orden en la tarjeta de aprobación paso a paso de ALTO riesgo.
     */
    internal fun boundaryRejection(command: String): String? {
        val reason = ShellCommandBoundary.forbidReason(command, workspaceDir = null) ?: return null
        Logger.w(TAG, "rechazo de la frontera dura:$reason")
        return "Error: Comando rechazado por límite de seguridad — $reason."
    }
}
