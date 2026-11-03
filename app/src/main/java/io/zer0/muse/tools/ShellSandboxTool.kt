package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.tools.system.sanitizeShellCommandForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * v1.0.47 P2-6: Herramienta de zona de pruebas de Shell local(Sólo disponible en modo agente).
 *
 * Android Shell sin root tiene capacidades limitadas,Pero de solo lectura en la lista blanca de ejecutables./comando de seguridad,
 * Permitir que la IA consulte el estado del dispositivo(lista de archivos/disco/proceso, etc.),Ayuda al Modo Agente a tomar decisiones autónomas.
 *
 * Diseño seguro:
 *  - Lista blanca de comandos:solo permitido ls/cat/grep/echo/wc/head/tail/find/file/stat/df/du/uname/whoami/date/pwd
 *  - Deshabilitar la conexión a comandos peligrosos(Como por ejemplo | sh,| bash), prohibir la redirección a directorios del sistema(> /system/...)
 *  - prohibido &,&&,||,; separador de comandos(Evitar la inyección del segundo comando.)
 *  - Directorio de trabajo bloqueado para la aplicación filesDir(desactivar cd a externo)
 *  - tiempo de espera 10s,La salida larga se escribe completamente en el disco y proporciona lectura paginada.
 *  - Sólo registrado para nivel de riesgo ALTO,Agent Mode + La ejecución requiere la aprobación del usuario
 *
 * Nota:Android Es posible que los comandos anteriores no estén disponibles(toybox darse cuenta),Devuelve un error explícito cuando falla la ejecución.
 */
object ShellSandboxTool {

    const val NAME = "execute_shell"

    /** Lista blanca de comandos(solo lectura/comando de seguridad). */
    private val ALLOWED_COMMANDS = setOf(
        "ls", "cat", "grep", "echo", "wc", "head", "tail", "find", "file", "stat",
        "df", "du", "uname", "whoami", "date", "pwd", "tree",
    )

    /** C-26: find Prefijos del directorio del sistema cuyo escaneo está prohibido(coincidencia de prefijos,La coincidencia exacta se puede omitir mediante /system/bin). */
    private val SYSTEM_DIR_PREFIXES = listOf(
        "/system", "/proc", "/sys", "/dev", "/etc", "/bin", "/sbin", "/vendor",
        "/data", "/storage/emulated", "/sdcard", "/mnt", "/apex", "/product",
    )

    /** C-26: Los parámetros de ruta deben estar dentro del entorno limitado(filesDir)Lea los comandos de archivos que contiene. */
    private val SANDBOX_PATH_COMMANDS = setOf(
        "ls", "cat", "head", "tail", "file", "stat", "du", "grep", "tree", "wc", "find",
    )

    /** personajes prohibidos(Prevenir la inyección de comandos). */
    private val FORBIDDEN_CHARS = setOf('&', '|', ';', '`', '$', '(', ')', '{', '}', '<', '>')

    /** Tiempo de espera del comando ms. */
    private const val TIMEOUT_MS = 10_000L

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        // v1.0.75 fix (Revisión de herramientas 02): Escenarios de activación complementarios y formatos de devolución
        // C-26: Ejemplo cambiado a ruta dentro de la zona de pruebas(/sdcard Espere hasta que la ruta externa haya sido bloqueada por restricciones de ruta)
        description = "Ejecutar comandos de shell incluidos en la lista blanca dentro del entorno limitado de la aplicación,Se utiliza para consultar el estado del dispositivo.(Archivo/disco/proceso)tomar decisiones más tarde(solo Agent Mode)." +
            "comandos permitidos:ls/cat/grep/echo/wc/head/tail/find/file/stat/df/du/uname/whoami/date/pwd/tree." +
            "El directorio de trabajo es el directorio de datos de la aplicación.,El parámetro de ruta solo está permitido dentro del directorio de datos de la aplicación.;Deshabilitar separadores de comandos(&;|)y redirigir(<>),El tiempo de espera es de 10 segundos." +
            "La salida larga se guarda en un archivo de aplicación que se puede leer en segmentos." +
            "Regresar: éxito=salida del comando,falló=[error]Razón.",
        parameters = mapOf(
            "command" to "Requerido,comando para ejecutar(Como por ejemplo 'ls -la' o 'cat notes.txt')",
        ),
        required = setOf("command"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    /**
     * Ejecutar comandos de shell.
     *
     * @param command Usuario/AI La cadena de comando proporcionada
     * @param workDir directorio de trabajo(Solicitud filesDir)
     */
    suspend fun execute(command: String, workDir: File): String = withContext(Dispatchers.IO) {
        // Control de seguridad 0: Línea de defensa fronteriza dura gobernada
        // v1.0.52: Llame a ToolPermissionResolver.isUnsafeCommand para realizar la primera intercepción,
        // incluso lista blanca/El modo permiso tiene bug,lista negra(rm/sudo/git/curl/wget espera)y sintaxis insegura
        // (inyección de nueva línea/comodín)Aún así será interceptado. esto es"borde duro"——TRUSTED El patrón tampoco se puede omitir.
        if (ToolPermissionResolver.isUnsafeCommand(command)) {
            return@withContext "[error] El comando llega a la lista negra de límites estrictos(Sintaxis ejecutable peligrosa o insegura),Se ha negado la ejecución"
        }

        // Control de seguridad 1: los caracteres peligrosos están prohibidos
        val forbidden = command.firstOrNull { it in FORBIDDEN_CHARS }
        if (forbidden != null) {
            return@withContext "[error] El comando contiene caracteres prohibidos '$forbidden'(&;|`$(){}<>,Prevenir la inyección)"
        }

        // Comprobación de seguridad 2: análisis de nombres de comandos,consultar lista blanca
        val parts = command.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts[0].isBlank()) {
            return@withContext "[error] el comando está vacío"
        }
        val cmdName = parts[0]
        if (cmdName !in ALLOWED_COMMANDS) {
            return@withContext "[error] comando '$cmdName' No en la lista blanca. permitir: ${ALLOWED_COMMANDS.joinToString("/")}"
        }

        // Comprobación de seguridad 3: encontrar rutas de límites de comandos(Deshabilite el análisis completo de directorios del sistema como /)
        // C-26: Las coincidencias exactas pueden ser "/system/bin" bypass,Cambiar coincidencia de prefijo
        if (cmdName == "find" && parts.any { arg -> arg == "/" || SYSTEM_DIR_PREFIXES.any { arg.startsWith(it) } }) {
            return@withContext "[error] find Deshabilitar el escaneo de directorios del sistema(/,/system,/proc,/sys espera)"
        }

        // Comprobación de seguridad 3.5: los parámetros de ruta de los comandos de lectura de archivos deben resolverse en la zona de pruebas(filesDir)dentro,
        // Evitar que cat /data/... o rutas relativas escapen a una lectura no autorizada;Los comandos de sólo lectura no se ven afectados.
        if (cmdName in SANDBOX_PATH_COMMANDS) {
            val badArg = parts.drop(1)
                .filter { it.isNotBlank() && !it.startsWith("-") }
                .firstOrNull { arg ->
                    val resolved = if (arg.startsWith("/")) File(arg) else File(workDir, arg)
                    runCatching {
                        // C-07: Para límites de directorio"ruta de destino == raíz del sandbox o ruta de destino que termina en 'raíz de sandbox + separador' comienzo"juez,
                        // No puedo simplemente usar startsWith(raíz de la caja de arena) — De lo contrario, /data/user2 se juzgará erróneamente como una subruta de /data/user.
                        val root = workDir.canonicalPath
                        val target = resolved.canonicalPath
                        !(target == root || target.startsWith(root + File.separator))
                    }.getOrDefault(true)
                }
            if (badArg != null) {
                return@withContext "[error] camino '$badArg' Fuera del alcance de la zona de pruebas(Solo permitido dentro del directorio de datos de la aplicación)"
            }
        }

        runCatching {
            val builder = ProcessBuilder(parts)
                .directory(workDir)
                .redirectErrorStream(true)

            val process = builder.start()
            val capture = ToolOutputCapture(File(workDir, TOOL_OUTPUTS_DIR), "execute_shell")
            val readFailure = AtomicReference<Throwable?>(null)
            val reader = thread(name = "muse-shell-output") {
                try {
                    process.inputStream.bufferedReader().use { stream ->
                        val buffer = CharArray(4_096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            capture.append(String(buffer, 0, count))
                        }
                    }
                } catch (error: Throwable) {
                    readFailure.set(error)
                }
            }
            val finished = process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join()
                readFailure.get()?.let { throw it }
                return@withContext capture.finish(
                    header = "[tiempo de espera] comando ${TIMEOUT_MS / 1000}s No completado,terminado",
                    emptyMessage = "(Sin salida)",
                )
            }
            reader.join()
            readFailure.get()?.let { throw it }
            val exitCode = process.exitValue()
            val result = capture.finish(
                header = if (exitCode == 0) "" else "[código de salida $exitCode]",
                emptyMessage = if (exitCode == 0) "[éxito] Ejecución del comando completada,Sin salida" else "(Sin salida)",
            )

            Logger.i(
                "ShellSandbox",
                "execute verb=${sanitizeShellCommandForLog(command)} → exit=$exitCode",
            )
            result
        }.getOrElse {
            Logger.w("ShellSandbox", "execute falló: ${it.message}", it)
            "[error] La ejecución falló: ${it.message}"
        }
    }
}

/**
 * v1.0.47 P2-6: ShellSandboxTool Registrador.
 */
class ShellSandboxToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val workDir: File,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(ShellSandboxTool.toolDef()) { args ->
            val command = args["command"] ?: return@register "[error] Falta el parámetro requerido command"
            ShellSandboxTool.execute(command, workDir)
        }
    }
}
