package io.zer0.muse.automation.vdisplay

import android.content.Context
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.DeviceCommandPolicy
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import kotlinx.coroutines.delay
import java.io.File

/**
 * v2.2.1 pantalla virtual:AI Herramientas `virtual_screen`.
 *
 * Registrar la capacidad de pantalla virtual como herramienta:ensure(Pantalla de construcción)/ open(Abrir aplicaciones en pantalla)/ read(árbol de control)/ shot(captura de pantalla)/
 * close(destruir)/ status(Estado). Las acciones de entrada también se registran como de alto riesgo. `virtual_screen_input`,
 * Construido y verificado uniformemente por esta clase. `input -d <displayId> ...`, para evitar que el Agente regrese device_shell
 * Empalme los comandos usted mismo.
 */
class VirtualDisplayTool(
    private val context: Context,
    private val client: VirtualDisplayClient,
    private val manager: VirtualDisplayServerManager,
    private val automationManager: AutomationManager,
) {
    fun register(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "virtual_screen",
                description =
                "pantalla virtual(Ocultar pantalla en segundo plano):Ejecute la aplicación en una pantalla independiente,captura de pantalla/La operación no ocupa la recepción y no molesta a los usuarios." +
                    "action=ensure Crear o reutilizar pantallas virtuales(Regresar displayId);action=open Abre la aplicación en la pantalla virtual.(necesidad package);" +
                    "action=shot Capturar pantalla virtual(Guardar como archivo JPEG,camino de regreso);action=close Destruir la pantalla virtual;" +
                    "action=status Estado de la consulta. Haga clic en la pantalla virtual/Deslizar/Botón:Ejecutar con dispositivo_shell " +
                    "`input -d <displayId> tap/swipe/keyevent ...`. Requiere acceso Shizuku o Root(ajustes→Asistente de permisos).",
                parameters =
                mapOf(
                    "action" to "Requerido:ensure | open | read | shot | close | status",
                    "package" to "open nombre del paquete de aplicación(Como por ejemplo com.android.settings)",
                    "width" to "Opcional:ancho de pantalla virtual(Predeterminado 720)",
                    "height" to "Opcional:Altura de la pantalla virtual(Predeterminado 1280)",
                    "dpi" to "Opcional:Densidad de pantalla virtual(Predeterminado 320)",
                    "display" to "Opcional:Especificar displayId(El valor predeterminado es la última garantía)",
                ),
                required = setOf("action"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            when (val action = args["action"]?.trim()?.lowercase()) {
                "ensure" -> handleEnsure(args)
                "open" -> handleOpen(args)
                "read" -> handleRead(args)
                "shot" -> handleShot(args)
                "close" -> handleClose(args)
                "status" -> handleStatus()
                null, "" -> "error:Parámetro de acción faltante(ensure/open/read/shot/close/status)"
                else -> "error:desconocido action '$action'(ensure/open/read/shot/close/status)"
            }
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "virtual_screen_input",
                description = "Ejecute una acción de entrada en la pantalla virtual de fondo. Primero use virtual_screen shot para confirmar la pantalla." +
                    "Luego llame, toque/deslice/envíe mensajes de texto/tecla/espera; la acción solo actúa en la pantalla especificada y no cambia el primer plano del usuario." +
                    "Requiere Shizuku o Root, que es una operación entre aplicaciones de alto riesgo y requiere aprobación antes de cada ejecución.",
                parameters = mapOf(
                    "action" to "Requerido:tap | swipe | text | key | wait",
                    "display" to "Opcional:displayId, el valor predeterminado es la última pantalla virtual asegurada",
                    "x" to "tap La coordenada X de",
                    "y" to "tap Coordenada Y de",
                    "x1" to "swipe punto de partida X",
                    "y1" to "swipe punto de partida Y",
                    "x2" to "swipe punto final X",
                    "y2" to "swipe punto final Y",
                    "duration_ms" to "swipe Dura en milisegundos, por defecto 400",
                    "text" to "text Ingrese contenido, hasta 500 caracteres",
                    "key" to "key Nombre o número de clave, como BACK/HOME/ENTER/KEYCODE_4",
                    "ms" to "wait espera milisegundos,50-10000",
                ),
                required = setOf("action"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> handleInput(args) }
    }

    private suspend fun handleEnsure(args: Map<String, String>): String {
        val width = args["width"]?.toIntOrNull() ?: 720
        val height = args["height"]?.toIntOrNull() ?: 1280
        val dpi = args["dpi"]?.toIntOrNull() ?: 320
        if (width !in 320..1920 || height !in 320..2560) {
            return "error:Tamaño fuera de rango(ancho 320-1920,alto 320-2560)"
        }
        return client.ensureDisplay(width, height, dpi).fold(
            onSuccess = {
                "La pantalla virtual está lista:displayId=${it.displayId}(${it.width}x${it.height})." +
                    "Abra la aplicación con virtual_screen abierta,usar device_shell `input -d ${it.displayId} ...` Hacer entrada."
            },
            onFailure = { "Falló la creación de la pantalla virtual:${it.message}" },
        )
    }

    private suspend fun handleOpen(args: Map<String, String>): String {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isBlank()) return "error:open Requiere parámetro de paquete"
        val explicitDisplayId = args["display"]?.toIntOrNull()
        val displayId = explicitDisplayId ?: manager.lastDisplayId
        if (displayId < 0) {
            val ensured = handleEnsure(args)
            if (!ensured.startsWith("La pantalla virtual está lista")) return ensured
        }
        val id = if (displayId >= 0) displayId else manager.lastDisplayId
        return if (client.openApp(pkg, id)) {
            val actualId = explicitDisplayId ?: manager.lastDisplayId
            "Ya en la pantalla virtual $actualId abierto $pkg(Espere 1-2 segundos para que se renderice la toma.)"
        } else {
            "Error al abrir:No se puede analizar $pkg actividad de lanzamiento(La aplicación no está instalada ni restringida por el sistema.)"
        }
    }

    private suspend fun handleShot(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: manager.lastDisplayId
        val bytes =
            client.screenshot(displayId)
                ?: return "Error en la captura de pantalla:La pantalla virtual no se crea o el servidor no está listo.(Ejecutar primero action=ensure)"
        return runCatching {
            val dir = File(context.filesDir, "vd").apply { mkdirs() }
            val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            "Captura de pantalla guardada:${file.absolutePath}(${bytes.size / 1024}KB,displayId=$displayId)"
        }.getOrElse { "No se pudo guardar la captura de pantalla:${it.message}" }
    }

    private suspend fun handleRead(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: manager.lastDisplayId
        val info = automationManager.readScreenOnDisplay(displayId)
            ?: return "Error de lectura:La lectura de pantalla semántica de pantalla virtual requiere Shizuku o canal raíz"
        return buildString {
            appendLine(info.toSummary(50))
            if (info.nodes.isNotEmpty()) {
                appendLine("---")
                appendLine("Controles interactivos:")
                info.nodes.filter { it.isClickable || it.isEditable }.take(20).forEachIndexed { index, node ->
                    appendLine("[$index] ${node.toShortString()}")
                }
            }
        }
    }

    private suspend fun handleClose(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: manager.lastDisplayId
        return if (client.destroy(displayId)) "pantalla virtual $displayId Destruido" else "No hay pantalla virtual que destruir"
    }

    private suspend fun handleStatus(): String {
        val proxy = manager.existingLiveProxy()
        val channel = manager.channelState()
        return buildString {
            appendLine("Canal de permiso:$channel")
            appendLine("Servidor:${if (proxy != null) "en línea" else "no corriendo(Iniciar automáticamente cuando se llama por primera vez)"}")
            append("pantalla virtual:${if (manager.lastDisplayId >= 0) "displayId=${manager.lastDisplayId}" else "No creado"}")
        }
    }

    private suspend fun handleInput(args: Map<String, String>): String {
        val displayId = args["display"]?.toIntOrNull() ?: run {
            client.ensureCompatibilityDisplay().getOrElse {
                return "error:Error al actualizar la pantalla virtual:${it.message ?: "El servidor no está listo"}"
            }.displayId
        }
        if (displayId < 0) {
            return "error:La pantalla virtual no ha sido creada, ejecútela primero virtual_screen action=ensure"
        }
        val action = args["action"]?.trim()?.lowercase().orEmpty()
        val command = VirtualDisplayInputCommand.build(displayId, action, args)
        return when {
            command == null -> "error:Parámetro de entrada no válido(action=$action)"
            command.waitMs != null -> {
                delay(command.waitMs)
                "pantalla virtual $displayId Ya esperando ${command.waitMs}ms"
            }
            else -> executeInputCommand(displayId, command)
        }
    }

    private suspend fun executeInputCommand(displayId: Int, command: VirtualDisplayInputCommand): String {
        return when (val check = DeviceCommandPolicy.validate(command.shellCommand)) {
            is DeviceCommandPolicy.Check.Invalid -> "error:El comando de entrada fue rechazado por la política.:${check.reason}"
            DeviceCommandPolicy.Check.Valid -> {
                val result = manager.exec(command.shellCommand)
                if (result.exitCode == 0) {
                    "pantalla virtual $displayId ${command.description}éxito"
                } else {
                    "pantalla virtual $displayId ${command.description}falló(exit=${result.exitCode}):${result.output.take(500)}"
                }
            }
        }
    }
}

internal data class VirtualDisplayInputCommand(
    val shellCommand: String,
    val description: String,
    val waitMs: Long? = null,
) {
    companion object {
        private val KEY_REGEX = Regex("^[A-Za-z0-9_]+$")

        fun build(displayId: Int, action: String, args: Map<String, String>): VirtualDisplayInputCommand? {
            return when (action) {
                "tap" -> buildTap(displayId, args)
                "swipe" -> buildSwipe(displayId, args)
                "text" -> buildText(displayId, args)
                "key" -> buildKey(displayId, args)
                "wait" -> buildWait(args)
                else -> null
            }
        }

        private fun buildTap(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val x = args["x"]?.toIntOrNull()
            val y = args["y"]?.toIntOrNull()
            return if (x != null && y != null) command(displayId, "tap $x $y", "Haga clic($x,$y)") else null
        }

        private fun buildSwipe(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val x1 = args["x1"]?.toIntOrNull()
            val y1 = args["y1"]?.toIntOrNull()
            val x2 = args["x2"]?.toIntOrNull()
            val y2 = args["y2"]?.toIntOrNull()
            val duration = args["duration_ms"]?.toLongOrNull()?.coerceIn(50L, 5_000L) ?: 400L
            return if (listOf(x1, y1, x2, y2).any { it == null }) {
                null
            } else {
                command(displayId, "swipe ${x1!!} ${y1!!} ${x2!!} ${y2!!} $duration", "Deslizar")
            }
        }

        private fun buildText(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val text = args["text"]?.takeIf { it.isNotBlank() }?.takeIf { it.length <= 500 } ?: return null
            val unsafe = text.any { it in SHELL_META || it == '\n' || it == '\r' || it == '\\' || it == '"' || it == '\'' }
            return if (unsafe) null else command(displayId, "text ${text.replace(" ", "%s")}", "Introducir texto")
        }

        private fun buildKey(displayId: Int, args: Map<String, String>): VirtualDisplayInputCommand? {
            val key = args["key"]?.trim()?.uppercase()?.takeIf { KEY_REGEX.matches(it) } ?: return null
            return command(displayId, "keyevent $key", "Botón($key)")
        }

        private fun buildWait(args: Map<String, String>): VirtualDisplayInputCommand? {
            val ms = args["ms"]?.toLongOrNull()?.coerceIn(50L, 10_000L) ?: return null
            return VirtualDisplayInputCommand("", "", waitMs = ms)
        }

        private fun command(displayId: Int, input: String, description: String) =
            VirtualDisplayInputCommand("input -d $displayId $input", description)

        private val SHELL_META = setOf(';', '|', '&', '>', '<', '`', '$', '(', ')', '{', '}')
    }
}
