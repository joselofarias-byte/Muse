package io.zer0.muse.automation.executors

import android.content.Context
import android.os.Build
import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationExecutor
import io.zer0.muse.automation.core.PermissionLevel
import io.zer0.muse.automation.core.ScreenInfo
import io.zer0.muse.automation.core.UiNode
import io.zer0.muse.tools.system.sanitizeShellCommandForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shell actuador —— Segunda capa de automatización de la interfaz de usuario.
 *
 * Dos modos de trabajo:
 * 1. Dispositivo rooteado: Ejecutar comando a través de su(En este momento por [RootExecutor] Uso heredado)
 * 2. Shizuku/adb: Pase `adb shell` o ejecución del servicio Shizuku
 *
 * Todos los comandos devuelven un error cuando no están autorizados,Pero no se estrellará.
 *
 * Nota: puro `adb shell` No se puede persistir directamente(App El proceso no puede heredar adb shell uid).
 * Pases de implementación actuales [io.zer0.muse.tools.system.ShizukuAuthorizer] Reutilizar el Servicio de Usuario autorizado de Shizuku;
 * RootExecutor Reutilizar esta clase `su` Primitivo como canal de degradación, aplicación común `sh` Ya no se considera un segundo nivel de autoridad.
 */
open class ShellExecutor(
    protected val context: Context,
    /** Shizuku debe ser llevado explícitamente al segundo nivel;null Sólo RootExecutor puede reutilizar la primitiva su de esta clase. */
    protected val shizukuAuthorizer: io.zer0.muse.tools.system.ShizukuAuthorizer? = null,
) : AutomationExecutor {

    override val level = PermissionLevel.SHELL

    /** Utilice únicamente su local cuando reutilice RootExecutor; la segunda capa no pretenderá estar autorizada con pescado ordinario. */
    protected open val shellPrefix: List<String> = listOf("sh")

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (shizukuAuthorizer != null) return@withContext shizukuAuthorizer.checkReady()
        val result = exec("id")
        result.isSuccess && result.getOrDefault("").isNotBlank()
    }

    // ── lectura de pantalla ──────────────────────────────────────────────

    override suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val result = if (shizukuAuthorizer != null) {
                screenshotViaShizuku()
            } else {
                execBinary("screencap -p")
            }
            if (result.isSuccess) result.getOrNull() else null
        } catch (e: Exception) {
            Logger.w(TAG, "screenshot failed: ${e.message}")
            null
        }
    }

    /**
     * v2.6.13: Shizuku Las capturas de pantalla se toman a través del canal de transmisión en lugar de volver a incluir imágenes en cadenas AIDL.
     *
     * La antigua implementación era `screencap -p | gzip -c | base64` ir [ShizukuAuthorizer.execute](AIDL String).
     * 1080p Incluso después de gzip, la captura de pantalla base64 a menudo todavía tiene más de 1 MB, lo que excede el búfer de transacción único de la carpeta.
     * UserService Golpeado directamente hasta la muerte (registro de máquina real:`Transaction failed on small parcel; remote process
     * probably died` + Cada vez posterior `bindUserService falló: UserService Tiempo de espera de conexión`).
     *
     * Usar ahora [ShizukuAuthorizer.executeToPipe]: El comando se ejecuta en el proceso de shell y los bytes se
     * ParcelFileDescriptor La tubería regresa a este proceso para escribir archivos, evitando por completo el límite de tamaño de la carpeta.
     */
    private suspend fun screenshotViaShizuku(): Result<ByteArray> {
        val file = File(context.cacheDir, "muse_shell_shot_${System.currentTimeMillis()}.png")
        return try {
            val result = shizukuAuthorizer?.executeForTool("screencap -p > ${shellQuote(file.absolutePath)}")
            val ok = result != null && result.exitCode == 0 && file.exists() && file.length() > 0
            if (!ok) {
                Logger.w(
                    TAG,
                    "Shizuku Error en la captura de pantalla exit=${result?.exitCode} size=${if (file.exists()) file.length() else -1}",
                )
                file.delete()
                Result.failure(IllegalStateException(result?.stderr?.ifBlank { null } ?: "La captura de pantalla no produjo un archivo válido."))
            } else {
                val bytes = file.readBytes()
                file.delete()
                Result.success(bytes)
            }
        } catch (e: Exception) {
            file.delete()
            Logger.w(TAG, "Shizuku Excepción de captura de pantalla en streaming: ${e.message}")
            Result.failure(e)
        }
    }

    /** Las comillas simples envuelven la ruta y las comillas simples internas se escapan de acuerdo con las reglas POSIX. La ruta proviene de cacheDir, no contiene caracteres especiales y todavía se defiende aquí. */
    private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    override suspend fun readScreen(): ScreenInfo = withContext(Dispatchers.IO) {
        val pkg = currentPackage()
        // dumpsys window Obtener la ventana de enfoque actual y la información de control(más grueso que sin barreras)
        val dump = exec("dumpsys window displays").getOrDefault("")
        val activityName = Regex("mCurrentFocus=.*?\\s+([\\w.]+/[\\w.$]+)")
            .find(dump)?.groupValues?.get(1)

        // uiautomator dump Puedes obtener el árbol de control completo. XML,pero mas lento(aprox. 200-500ms)
        val nodes = dumpUiAutomator()

        val metrics = context.resources.displayMetrics
        ScreenInfo(
            packageName = pkg,
            activityName = activityName,
            nodes = nodes,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            source = if (shizukuAuthorizer != null) "shizuku" else "root",
        )
    }

    /**
     * Lea el árbol de control de la pantalla especificada para que lo utilice el Agente de pantalla virtual.
     *
     * Android El volcado de uiautomator está disponible en sistemas que admiten múltiples pantallas a través de
     * `--display-id` Selecciona la pantalla de fondo; devuelve un árbol vacío en caso de falla en lugar de informar falsamente la pantalla principal al Agente.
     */
    @Suppress("TooGenericExceptionCaught") // Shell/uiautomator failures must degrade to an unavailable display tree.
    suspend fun readScreenOnDisplay(displayId: Int): ScreenInfo = withContext(Dispatchers.IO) {
        if (displayId < 0) return@withContext ScreenInfo(source = "shell-display(invalid)")
        val dumpPath = "/sdcard/muse_window_dump_${System.currentTimeMillis()}_$displayId.xml"
        try {
            val dump = exec("uiautomator dump --display-id $displayId --compressed $dumpPath")
            if (!dump.isSuccess) {
                return@withContext ScreenInfo(source = "shell-display($displayId,unavailable)")
            }
            val xml = exec("cat $dumpPath").getOrDefault("")
            ScreenInfo(
                nodes = parseUiAutomatorXml(xml),
                screenWidth = context.resources.displayMetrics.widthPixels,
                screenHeight = context.resources.displayMetrics.heightPixels,
                source = "shell-display:$displayId",
            )
        } catch (error: Exception) {
            Logger.w(TAG, "uiautomator display dump failed($displayId): ${error.message}")
            ScreenInfo(source = "shell-display($displayId,error)")
        } finally {
            exec("rm $dumpPath")
        }
    }

    override suspend fun currentPackage(): String? = withContext(Dispatchers.IO) {
        val result = exec("dumpsys window | grep mCurrentFocus")
        result.getOrNull()?.let { output ->
            Regex("([\\w.]+)/[\\w.\$]+").find(output)?.groupValues?.get(1)
        }
    }

    // ── acción de entrada ──────────────────────────────────────────────

    override suspend fun tap(x: Int, y: Int): Boolean = exec("input tap $x $y").isSuccess

    override suspend fun longPress(x: Int, y: Int, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        // input swipe Mismas coordenadas + duración simulan una pulsación larga
        exec("input swipe $x $y $x $y $durationMs").isSuccess
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs").isSuccess

    override suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        // input text El chino no es compatible;Y escribir el texto directamente en sh -c corre el riesgo de inyectar comandos.
        // Sólo si el texto es ASCII simple y no contiene metacaracteres de shell($, comilla invertida,;,&,|,<,>)solo vete
        // input text camino rápido;El resto irá al portapapeles. + KEYCODE_PASTE(escritura de texto ClipData,
        // Sin interpolación de texto de shell,Sin superficie de inyección).
        val safeAscii = text.all { it.code < 128 } && !SHELL_META_REGEX.containsMatchIn(text)
        if (safeAscii) {
            val escaped = text.replace(" ", "%s").replace("\"", "\\\"")
            exec("input text \"$escaped\"").isSuccess
        } else {
            // escribir en el portapapeles + KEYCODE_PASTE
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("auto", text))
                // KEYCODE_PASTE = 279
                exec("input keyevent 279").isSuccess
            } catch (e: Exception) {
                Logger.w(TAG, "inputText i18n failed: ${e.message}")
                false
            }
        }
    }

    override suspend fun pressKey(keyCode: Int): Boolean = exec("input keyevent $keyCode").isSuccess

    override suspend fun launchApp(packageName: String): Boolean = if (!PACKAGE_NAME_REGEX.matches(packageName)) {
        Logger.w(TAG, "launchApp Rechazar nombres de paquetes ilegales(Puede contener metacaracteres de shell): $packageName")
        false
    } else {
        exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1").isSuccess
    }

    override suspend fun openNotifications(): Boolean = withContext(Dispatchers.IO) {
        // Expandir la barra de notificaciones: cmd statusbar expand-notifications (API 24+) o service call
        val result = if (Build.VERSION.SDK_INT >= 24) {
            exec("cmd statusbar expand-notifications")
        } else {
            exec("service call statusbar 1")
        }
        result.isSuccess
    }

    override suspend fun openQuickSettings(): Boolean = withContext(Dispatchers.IO) {
        val result = if (Build.VERSION.SDK_INT >= 24) {
            exec("cmd statusbar expand-settings")
        } else {
            exec("service call statusbar 2")
        }
        result.isSuccess
    }

    // ── ejecución de comando ──────────────────────────────────────────────

    data class ExecResult(val exitCode: Int, val stdout: String) {
        fun isSuccess() = exitCode == 0
        fun getOrDefault(default: String) = if (isSuccess()) stdout else default
    }

    /** v2.x: Resultados de ejecución detallados con código de salida(La salida es stdout+stderr combinado,Para uso de los comentarios de device_shell). */
    data class ExecDetail(val exitCode: Int, val output: String)

    /** Ejecutar comando de shell,Devuelve una cadena de salida estándar. */
    suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (shizukuAuthorizer != null) {
            if (!shizukuAuthorizer.checkPermission()) {
                return@withContext Result.failure(IllegalStateException("Shizuku No autorizado"))
            }
            val result = shizukuAuthorizer.execute(command)
            return@withContext if (result.isSuccess) {
                Result.success(result.stdout)
            } else {
                Logger.w(
                    TAG,
                    "Shizuku exec failed (exit=${result.exitCode}, verb=${sanitizeShellCommandForLog(command)})",
                )
                Result.failure(IllegalStateException(result.stderr.ifBlank { "Shizuku La ejecución del comando falló" }))
            }
        }
        runCatching {
            val proc = ProcessBuilder(shellPrefix + listOf("-c", command))
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            if (exit != 0) {
                Logger.w(TAG, "exec failed (exit=$exit, verb=${sanitizeShellCommandForLog(command)})")
            }
            output
        }.onFailure { Logger.w(TAG, "exec error: ${it.message}") }
    }

    /**
     * v2.x Automatización Fase I:Ejecución detallada con código de salida. —— suministro [io.zer0.muse.automation.core.AutomationManager.execTiered]
     * Usado con dispositivo_shell.Shizuku El modo conserva la fragmentación stdout/stderr;locales( su )El modo fusiona flujos de salida.
     */
    suspend fun execDetailed(command: String): ExecDetail = withContext(Dispatchers.IO) {
        if (shizukuAuthorizer != null) {
            if (!shizukuAuthorizer.checkPermission()) {
                return@withContext ExecDetail(-1, "Shizuku No autorizado")
            }
            val result = shizukuAuthorizer.executeForTool(command)
            val merged = buildString {
                append(result.stdout)
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append("[stderr]\n").append(result.stderr)
                }
            }
            return@withContext ExecDetail(result.exitCode, merged)
        }
        runCatching {
            val proc = ProcessBuilder(shellPrefix + listOf("-c", command))
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            ExecDetail(exit, output)
        }.getOrElse { e ->
            Logger.w(TAG, "execDetailed error: ${e.message}")
            ExecDetail(-1, e.message ?: "La ejecución falló")
        }
    }

    private suspend fun execBinary(command: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val proc = ProcessBuilder(shellPrefix + listOf("-c", command))
                .redirectErrorStream(false)
                .start()
            val out = proc.inputStream.readBytes()
            proc.waitFor()
            out
        }
    }

    // ── uiautomator dump ──────────────────────────────────────

    private suspend fun dumpUiAutomator(): List<UiNode> = withContext(Dispatchers.IO) {
        try {
            // uiautomator dump Escribir XML en un archivo,Léelo y analízalo.
            val dumpPath = "/sdcard/window_dump_${System.currentTimeMillis()}.xml"
            exec("uiautomator dump --compressed $dumpPath")
            val xml = exec("cat $dumpPath").getOrDefault("")
            exec("rm $dumpPath") // limpiar
            parseUiAutomatorXml(xml)
        } catch (e: Exception) {
            Logger.w(TAG, "uiautomator dump failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseUiAutomatorXml(xml: String): List<UiNode> {
        if (xml.isBlank()) return emptyList()
        val nodes = mutableListOf<UiNode>()
        // Análisis regular ligero(Sin hacer referencia al analizador XML): Extracto <node ... />
        val nodeRegex = Regex("<node\\b([^>]*?)/?>")
        for (match in nodeRegex.findAll(xml)) {
            val attrs = match.groupValues[1]
            fun attr(name: String): String? = Regex("$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

            val text = attr("text")?.takeIf { it.isNotBlank() }
            val desc = attr("content-desc")?.takeIf { it.isNotBlank() }
            val clickable = attr("clickable") == "true"
            val editable = attr("class")?.contains("EditText") == true
            val scrollable = attr("scrollable") == "true"
            val enabled = attr("enabled") != "false"
            val checked = when (attr("checked")) {
                "true" -> true
                "false" -> false
                else -> null
            }
            // bounds="[x1,y1][x2,y2]"
            val bounds = attr("bounds")?.let { b ->
                Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(b)?.let { m ->
                    intArrayOf(
                        m.groupValues[1].toInt(),
                        m.groupValues[2].toInt(),
                        m.groupValues[3].toInt(),
                        m.groupValues[4].toInt(),
                    )
                }
            }

            // Siempre que haya información sobre el nodo.
            if (text != null || desc != null || clickable || editable) {
                nodes.add(
                    UiNode(
                        text = text,
                        contentDescription = desc,
                        className = attr("class"),
                        viewIdResourceName = attr("resource-id"),
                        boundsLeft = bounds?.get(0) ?: 0,
                        boundsTop = bounds?.get(1) ?: 0,
                        boundsRight = bounds?.get(2) ?: 0,
                        boundsBottom = bounds?.get(3) ?: 0,
                        isClickable = clickable,
                        isEditable = editable,
                        isScrollable = scrollable,
                        isChecked = checked,
                        isEnabled = enabled,
                    ),
                )
            }
        }
        return nodes
    }

    companion object {
        private const val TAG = "ShellExec"

        /** shell Metacaracteres:Aparecer en texto interpolado se considera un riesgo de inyección de comandos,Utilice rutas del portapapeles en su lugar para evitar la interpolación. */
        private val SHELL_META_REGEX = Regex("[\$`;&|<>]")

        /** Lista blanca de nombres de paquetes legales(Sólo puntos alfanuméricos y guiones bajos),Denegar la inyección de nombres de paquetes que contengan metacaracteres de shell. */
        private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_.]*\$")
    }
}
