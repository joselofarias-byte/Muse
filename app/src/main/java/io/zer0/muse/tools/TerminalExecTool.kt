package io.zer0.muse.tools

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v2.x fase terminal uno:terminal_exec Herramientas — Ejecute un único comando de shell dentro del entorno limitado de la aplicación.
 *
 * con [ShellSandboxTool](execute_shell:lista blanca de solo lectura) y device_shell(Shizuku/Root Control de nivel de dispositivo)
 * complementario:Esta herramienta es **App permisos propios** terminales,El directorio de trabajo bloquea el espacio de trabajo de la aplicación,Para procesamiento de archivos, secuencias de comandos,
 * Tareas de sandbox como diagnósticos.HIGH riesgo,Siga la cadena de aprobación existente.
 *
 * límite de seguridad:[ShellCommandBoundary] Dos niveles de criterios. Esta herramienta sólo aplica el conjunto mínimo del primer "absolutamente no permitido".
 * (Eliminar recursivamente el espacio de trabajo/Aplique datos privados, escriba particiones del sistema, formatee, descargue y ejecute, escale privilegios y comandos a nivel de dispositivo, etc.),
 * El modo de aprobación no se puede omitir;El segundo nivel de comandos de "trabajo destructivo pero probablemente normal" se seguirá ejecutando como de costumbre.,Arriesgado por ALTO
 * Responsable de la aprobación uno por uno.,Esta herramienta solo registra un registro de advertencia. Las canalizaciones, redirecciones y asignaciones de variables de entorno no están bloqueadas.
 */
object TerminalExecTool {
    const val NAME = "terminal_exec"
    private const val TAG = "TerminalExec"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun toolDef() = ToolRegistry.ToolDef(
        name = NAME,
        description = "Ejecute un único comando de shell dentro del entorno limitado de la aplicación(El directorio de trabajo es el espacio de trabajo de la aplicación.,App permisos propios)." +
            "Adecuado para el procesamiento de documentos/guión/Diagnóstico;Úselo en su lugar cuando se requieran controles a nivel de dispositivo, como hacer clic, iniciar y detener aplicaciones. device_shell(Requiere autorización Shizuku/Root)." +
            "Devolver el código de salida y completar la salida;La salida larga se guarda en un archivo de aplicación que se puede leer en segmentos;El comando de tiempo de espera finalizará.",
        parameters = mapOf(
            "command" to "Requerido. Comando de shell único,Como por ejemplo ls -la / cat notes.txt / mkdir -p out(Canalizaciones y redirecciones disponibles;" +
                "El límite de seguridad rechazará comandos como eliminar recursivamente todo el espacio de trabajo, escribir en la partición del sistema y ejecutar después de la descarga.)",
            "timeout_ms" to "Opcional. Tiempo de espera en milisegundos,Predeterminado 15000,alcance 1000-60000",
        ),
        required = setOf("command"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, workspaceDir: File, outputDirectory: File): String {
        val command = args["command"]?.trim().orEmpty()
        if (command.isEmpty()) return "Error: command is required."
        // control de seguridad:Comando de primera marcha con límites estrictos(El mismo piso que la lista negra de ejecuta_shell,Los criterios se basan en el poder destructivo más que en la forma de la cuerda.).
        // Colóquelo antes de la ejecución y después de la aprobación.:TRUSTED Ni el modo ni "Permitir siempre esta herramienta" pueden anularlo.
        val forbidden = ShellCommandBoundary.forbidReason(command, workspaceDir)
        if (forbidden != null) {
            Logger.w(TAG, "rechazo de la frontera dura:$forbidden")
            return "Error: Comando rechazado por límite de seguridad — ${forbidden}."
        }
        // Segunda marcha:Trabajo destructivo pero normal.,No te detengas aquí(a cargo de la aprobación caso por caso de ALTO riesgo),Sólo quedan registros rastreables.
        ShellCommandBoundary.cautionReason(command)?.let { note ->
            Logger.w(TAG, "Comando destructivo lanzado(Responsable de la cadena de aprobación.):$note")
        }
        val timeoutMs = (args["timeout_ms"]?.toIntOrNull() ?: 15_000).coerceIn(1_000, 60_000)
        return withContext(Dispatchers.IO) {
            val capture = ToolOutputCapture(outputDirectory, "terminal_exec")
            val process = runCatching {
                ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(workspaceDir)
                    .redirectErrorStream(true)
                    .start()
            }.getOrElse {
                capture.close()
                return@withContext "El inicio falló: ${it.message ?: "error desconocido"}"
            }

            val readerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val readerJob = scope.launch {
                try {
                    val buffer = CharArray(4096)
                    process.inputStream.bufferedReader().use { reader ->
                        while (true) {
                            val n = reader.read(buffer)
                            if (n < 0) break
                            capture.append(String(buffer, 0, n))
                        }
                    }
                } catch (error: Throwable) {
                    readerFailure.set(error)
                }
            }

            val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                readerJob.join()
                readerFailure.get()?.let { error ->
                    capture.close()
                    return@withContext "Se agotó el tiempo de espera del comando y no se pudo guardar el resultado completo.: ${error.message ?: "Error de almacenamiento"}"
                }
                return@withContext capture.finish(
                    header = "Tiempo de espera del comando(${timeoutMs / 1000}s)terminado",
                    emptyMessage = "(Sin salida)",
                )
            }
            readerJob.join()
            readerFailure.get()?.let { error ->
                capture.close()
                return@withContext "El comando finaliza, pero no se puede guardar el resultado completo: ${error.message ?: "Error de almacenamiento"}"
            }
            val exit = process.exitValue()
            capture.finish(header = "exit=$exit", emptyMessage = "(Sin salida)")
        }
    }
}
