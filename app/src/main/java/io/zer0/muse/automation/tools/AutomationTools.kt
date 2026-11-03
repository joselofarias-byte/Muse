package io.zer0.muse.automation.tools

import io.zer0.common.Logger
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.vdisplay.VirtualDisplayClient
import io.zer0.muse.automation.vdisplay.VirtualDisplayLeaseRegistry
import io.zer0.muse.automation.vdisplay.VirtualDisplayServerManager
import io.zer0.muse.tools.ToolOutcome
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.tools.WorkflowJournal
import java.util.UUID

/**
 * UI Conjunto de herramientas de automatización —— poner [AutomationManager] La habilidad está registrada como una herramienta invocable por IA.
 *
 * Las herramientas se dividen en dos categorías.:
 * - Tipo de percepción(leer pantalla/captura de pantalla/Revisa la recepción): bajo riesgo,No se requiere confirmación del usuario
 * - Clase de operación(Haga clic/Deslizar/entrada/Botón/empezarApp): alto riesgo,Controlado por ToolRiskLevel
 *
 * AI Estas herramientas se pueden utilizar para automatizar tareas entre aplicaciones.(Un asistente a nivel de sistema similar a Doubao Mobile).
 */
class AutomationTools(
    private val manager: AutomationManager,
    private val workflowJournal: WorkflowJournal? = null,
    private val virtualDisplayClient: VirtualDisplayClient? = null,
    private val virtualDisplayManager: VirtualDisplayServerManager? = null,
    private val nodeScriptExecutor: (suspend (String, Long) -> io.zer0.muse.tools.script.SkillEngineResult)? = null,
) {
    /** Todas las herramientas de automatización_workflow llaman a la cuenta de arrendamiento compartida y aíslan los derechos de liberación de la pantalla virtual mediante run_id. */
    private val displayLeases = VirtualDisplayLeaseRegistry()

    fun register(registry: ToolRegistry) {
        registerWorkflow(registry)
        // ── Tipo de percepción ──────────────────────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_read",
                description = "Leer el contenido de la pantalla actual. Resumen del árbol de control de retorno(texto/botón/Cuadro de entrada y sus coordenadas.)," +
                    "Se utiliza para determinar qué hay en la interfaz y dónde hacer clic. Esta herramienta debe llamarse antes de realizar cualquier clic.",
                parameters = mapOf(
                    "query" to "Opcional,Sólo se devuelven los controles que contienen esta palabra clave.",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val info = manager.readScreen()
            val query = args["query"]?.takeIf { q -> q.isNotBlank() }
            val nodes = if (query != null) {
                info.nodes.filter { n ->
                    (n.text?.contains(query, ignoreCase = true) == true) ||
                        (n.contentDescription?.contains(query, ignoreCase = true) == true)
                }
            } else {
                info.nodes
            }
            buildString {
                appendLine(info.toSummary(nodes.size.coerceAtMost(50)))
                if (nodes.isNotEmpty()) {
                    appendLine("---")
                    appendLine("Controles clave en los que se puede hacer clic:")
                    nodes.filter { n -> n.isClickable || n.isEditable }
                        .take(15)
                        .forEachIndexed { i, n ->
                            appendLine("[$i] ${n.toShortString()}")
                        }
                }
            }
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_current_app",
                description = "Consulte el nombre del paquete de la aplicación y el nombre de la interfaz que se ejecuta actualmente en primer plano.",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { _ ->
            val info = manager.readScreen()
            "nombre del paquete: ${info.packageName ?: "desconocido"}\ninterfaz: ${info.activityName ?: "desconocido"}\nresolución: ${info.screenWidth}x${info.screenHeight}"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_back",
                description = "Presione la tecla de retorno del sistema,Regresar a la interfaz anterior.",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.back()) "regresó" else "Fallo de devolución(Es posible que se requieran permisos de accesibilidad o Shell)"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_home",
                description = "Presione el botón Inicio,Regrese al escritorio.",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.home()) "Regresado al escritorio" else "Operación fallida"
        }

        // ── Clase de operación ──────────────────────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_tap",
                description = "Haga clic en las coordenadas especificadas en la pantalla.(x,y). Las coordenadas provienen de la posición central del control devuelto por screen_read." +
                    "No adivines las coordenadas de la nada,Screen_read debe confirmarse primero.",
                parameters = mapOf(
                    "x" to "Requerido,Haga clic en la ubicación coordenada X(Píxel)",
                    "y" to "Requerido,Haga clic en la ubicación y coordenada(Píxel)",
                ),
                required = setOf("x", "y"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val x = args["x"]?.toIntOrNull() ?: return@register "error:x Debe ser un número entero"
            val y = args["y"]?.toIntOrNull() ?: return@register "error:y Debe ser un número entero"
            if (manager.tap(x, y)) "Se hizo clic ($x,$y)" else "Error al hacer clic"
        }

        registry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "screen_tap_text",
                description = "Busque un control en la pantalla que contenga el texto especificado y haga clic en su centro." +
                    "Más conveniente que screen_tap,No es necesario que calcules las coordenadas tú mismo; puede desplazarse y buscar dentro de una página larga una cantidad limitada de veces y puede verificar el texto después de hacer clic.",
                parameters = mapOf(
                    "text" to "Requerido,botón para encontrar/Contenido del texto(Admite coincidencias difusas)",
                    "max_swipes" to "Opcional,Número máximo de veces para desplazarse hacia arriba cuando no se encuentra(0-5,Predeterminado 0)",
                    "verify_text" to "Opcional,Texto esperando aparecer después de hacer clic; Se utiliza para confirmar que la acción realmente tiene efecto.",
                ),
                required = setOf("text"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@registerOutcome ToolOutcome.error("error:Parámetro de texto faltante")
            val maxSwipes = args["max_swipes"]?.toIntOrNull()?.coerceIn(0, 5) ?: 0
            val verifyText = args["verify_text"]?.trim()?.takeIf { it.isNotBlank() }
            val outcome = manager.tapByTextWithRetryDetailed(text, maxSwipes = maxSwipes, verifyText = verifyText)
            val details = mapOf(
                "attempts" to outcome.attempts,
                "matched" to outcome.matched,
                "verified" to outcome.verified,
                "matchedLabel" to outcome.matchedLabel,
                "verifyRequested" to !verifyText.isNullOrBlank(),
            )
            if (outcome.success) {
                ToolOutcome.ok("Se hizo clic para incluir\"$text\"controlar", details)
            } else {
                ToolOutcome.error("Contiene no encontrado o no verificado después de hacer clic\"$text\"controlar", details)
            }
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_swipe",
                description = "Desliza el dedo de un punto a otro de la pantalla,Se puede utilizar para desplazarse/Pasa la página.",
                parameters = mapOf(
                    "x1" to "Requerido,punto de partida X",
                    "y1" to "Requerido,punto de partida Y",
                    "x2" to "Requerido,punto final X",
                    "y2" to "Requerido,punto final Y",
                    "duration_ms" to "Opcional,Duración de la diapositiva(milisegundos),Predeterminado 400",
                ),
                required = setOf("x1", "y1", "x2", "y2"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val x1 = args["x1"]?.toIntOrNull() ?: return@register "error:x1 Debe ser un número entero"
            val y1 = args["y1"]?.toIntOrNull() ?: return@register "error:y1 Debe ser un número entero"
            val x2 = args["x2"]?.toIntOrNull() ?: return@register "error:x2 Debe ser un número entero"
            val y2 = args["y2"]?.toIntOrNull() ?: return@register "error:y2 Debe ser un número entero"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 400L
            if (manager.swipe(x1, y1, x2, y2, dur)) "deslizado" else "Error al deslizar"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_pinch",
                description = "Gesto de pellizcar para hacer zoom(Acercar/alejar). dos dedos (center_x, center_y) Abrir y cerrar simétricamente respecto al centro." +
                    "Requiere acceso sin barreras;Shizuku/Root El comando de entrada no admite varios dedos.",
                parameters = mapOf(
                    "center_x" to "Requerido,Coordenada X del centro de zoom",
                    "center_y" to "Requerido,Coordenada Y del centro de zoom",
                    "scale" to "Requerido,factor de ampliación(>1 Acercar,<1 alejar),Como por ejemplo 2.0 / 0.5",
                    "duration_ms" to "Opcional,Duración del gesto(milisegundos),Predeterminado 300",
                ),
                required = setOf("center_x", "center_y", "scale"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val cx = args["center_x"]?.toIntOrNull() ?: return@register "error:center_x Debe ser un número entero"
            val cy = args["center_y"]?.toIntOrNull() ?: return@register "error:center_y Debe ser un número entero"
            val scale = args["scale"]?.toFloatOrNull() ?: return@register "error:scale debe ser un numero"
            if (scale <= 0f) return@register "error:scale debe ser mayor que 0"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 300L
            // Basado en 300px,Convertir la distancia inicial y final de los dedos según la escala(acotado,Prevenir transfronterizos)
            val base = 300
            val end = (base * scale).toInt().coerceIn(60, 2000)
            if (manager.pinch(cx, cy, base, end, dur)) "Ampliado (scale=$scale)" else "El zoom falló(Requiere acceso sin barreras)"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_swipe_path",
                description = "Deslizamiento multisegmento:Mueva un dedo a través de múltiples puntos de referencia en secuencia(al menos 2),Adecuado para desbloquear patrones/Arrastre complejo.",
                parameters = mapOf(
                    "points" to "Requerido,lista de puntos de ruta,Formato \"x1,y1;x2,y2;x3,y3\"(al menos 2 puntos)",
                    "duration_ms" to "Opcional,duración total(milisegundos),Predeterminado 400",
                ),
                required = setOf("points"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val raw = args["points"] ?: return@register "error:desaparecido points"
            val points = raw.split(";").mapNotNull { seg ->
                val p = seg.split(",")
                if (p.size == 2) {
                    val x = p[0].trim().toIntOrNull() ?: return@mapNotNull null
                    val y = p[1].trim().toIntOrNull() ?: return@mapNotNull null
                    x to y
                } else {
                    null
                }
            }
            if (points.size < 2) return@register "error:Requiere al menos 2 puntos legales(Formato x,y;x,y)"
            val dur = args["duration_ms"]?.toLongOrNull() ?: 400L
            if (manager.swipePath(points, dur)) "ejecutado ${points.size} diapositiva de ruta de punto" else "Error al deslizar el camino"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_input",
                description = "Ingrese texto en el cuadro de entrada actualmente enfocado(Soporte chino). Antes de escribir, haga clic en el cuadro de entrada para enfocarlo.",
                parameters = mapOf(
                    "text" to "Requerido,texto para ingresar",
                ),
                required = setOf("text"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val text = args["text"] ?: return@register "error:Parámetro de texto faltante"
            if (manager.inputText(text)) "entró: $text" else "Error de entrada"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_launch_app",
                description = "Inicie la aplicación con el nombre del paquete especificado. Se puede utilizar con screen_current_app para confirmar el nombre del paquete.",
                parameters = mapOf(
                    "package" to "Requerido,Nombre del paquete de la aplicación que se lanzará,Como por ejemplo com.tencent.mm",
                ),
                required = setOf("package"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pkg = args["package"] ?: return@register "error:Argumento de paquete faltante"
            if (manager.launchApp(pkg)) "iniciado $pkg" else "El inicio falló(El nombre del paquete puede ser incorrecto)"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_open_notifications",
                description = "Abrir la barra de notificaciones del sistema,Ver notificaciones.",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { _ ->
            if (manager.openNotifications()) "Barra de notificaciones abierta" else "Operación fallida"
        }

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_permission_status",
                description = "Consultar el estado de activación de los permisos en cada nivel de automatización de la interfaz de usuario.(Accesibilidad/Shell/Root)." +
                    "Se debe llamar a esta herramienta para confirmar las capacidades antes de intentar cualquier operación.",
                parameters = emptyMap(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { _ ->
            // v2.7: Instantánea en vivo —— Es posible que se haya activado la accesibilidad y que el caché no se haya actualizado (consulte AutomationManager.liveSnapshot).
            val state = manager.liveSnapshot()
            val level = manager.highestLevel()
            buildString {
                appendLine("UI Estado del permiso de automatización:")
                appendLine("- Accesibilidad: ${if (state.accessibilityEnabled) "Ya encendido" else "No encendido"}")
                appendLine("- Shell: ${if (state.shellEnabled) "Listo" else state.shizukuMessage}")
                appendLine("- Root: ${if (state.rootEnabled) "Obtenido" else "No obtenido"}")
                appendLine("- Nivel más alto disponible: $level")
            }
        }

        // ── Esperar primitivo (clase de percepción)─────────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "screen_wait",
                description = "Wait before the next action: idle = until the screen stops changing; " +
                    "text = until a text appears (or disappears with appear=false); " +
                    "window = until the foreground app/activity changes. " +
                    "Use after launching an app or navigating so clicks do not fire too early.",
                parameters = mapOf(
                    "mode" to "Optional. idle | text | window (default idle)",
                    "text" to "Required when mode=text. Substring to look for",
                    "appear" to "Optional. true=wait for appearance (default), false=disappearance",
                    "timeout_ms" to "Optional. Max wait ms, default 15000, cap 60000",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val timeout = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1_000L, 60_000L) ?: 15_000L
            val snapshotProvider: suspend () -> io.zer0.muse.automation.core.ScreenSnapshot = {
                io.zer0.muse.automation.core.ScreenSnapshot(info = manager.readScreen())
            }
            when (args["mode"]?.lowercase() ?: "idle") {
                "text" -> {
                    val text = args["text"]?.takeIf { it.isNotBlank() }
                    if (text == null) {
                        "error: text is required when mode=text"
                    } else {
                        val appear = args["appear"]?.lowercase() != "false"
                        val ok = io.zer0.muse.automation.core.WaitPrimitives.waitForText(
                            text = text,
                            appear = appear,
                            snapshotProvider = snapshotProvider,
                            timeoutMs = timeout,
                        )
                        if (ok) "text condition satisfied" else "timeout after ${timeout}ms"
                    }
                }
                "window" -> {
                    val before = manager.readScreen()
                    val ok = io.zer0.muse.automation.core.WaitPrimitives.waitForWindowChange(
                        initialPackage = before.packageName,
                        initialActivity = before.activityName,
                        snapshotProvider = snapshotProvider,
                        timeoutMs = timeout,
                    )
                    if (ok) "foreground window changed" else "timeout after ${timeout}ms"
                }
                else -> {
                    val ok = io.zer0.muse.automation.core.WaitPrimitives.waitForIdle(
                        snapshotProvider = snapshotProvider,
                        timeoutMs = timeout,
                    )
                    if (ok) "screen is idle" else "timeout after ${timeout}ms"
                }
            }
        }

        // ── Canal de mando del dispositivo (Automatización Fase I)─────────────────────────

        registry.register(
            ToolRegistry.ToolDef(
                name = "device_shell",
                description = "La ejecución de un comando de un solo dispositivo (control de línea de comando del teléfono) requiere autorización Shizuku o Root." +
                    "Soporte:input(tap/swipe/text/keyevent/roll/draganddrop/press),am(start/broadcast/force-stop/startservice/kill)," +
                    "pm(list/path/dump/enable/disable/grant/revoke/clear),settings(get/put/delete/list),svc(power/wifi/data/bluetooth)," +
                    "wm(size/density),dumpsys,uiautomator dump,screencap,screendump,content(query/insert/update/delete),cmd,getprop,date." +
                    "Las tuberías no son compatibles/Redirigir/Empalme de comandos múltiples——Escriba un comando completo a la vez. con screen_* Herramientas complementarias:screen_* Es una acción de alto nivel y esta herramienta tiene libertad de línea de comando.",
                parameters = mapOf(
                    "command" to "Requerido. Un comando de dispositivo único, como input tap 540 1200 / input swipe 540 1500 540 500 300 / " +
                        "am start -n com.tencent.mm/.ui.LauncherUI / settings get secure location_mode / uiautomator dump",
                ),
                required = setOf("command"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val command = args["command"]?.trim().orEmpty()
            when (val check = io.zer0.muse.automation.core.DeviceCommandPolicy.validate(command)) {
                is io.zer0.muse.automation.core.DeviceCommandPolicy.Check.Invalid ->
                    "comando rechazado: ${check.reason}"
                io.zer0.muse.automation.core.DeviceCommandPolicy.Check.Valid -> {
                    val result = manager.execTiered(command)
                        ?: return@register "El canal de comando del dispositivo no está disponible: se requiere autorización Shizuku o Root (Configuración → Asistente de configuración de permisos), actualmente ninguno de los archivos está listo."
                    val (tier, detail) = result
                    buildString {
                        append("[").append(tier).append("] exit=").append(detail.exitCode).append('\n')
                        if (detail.output.isNotBlank()) {
                            append(detail.output)
                        } else {
                            append("(Sin salida)")
                        }
                    }
                }
            }
        }
    }

    private fun registerWorkflow(registry: ToolRegistry) {
        registry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "automation_workflow",
                description = "Ejecute flujos de trabajo celulares controlados y reanudables, ejecute secuencialmente acciones semánticas en primer plano y virtual_* acción de pantalla virtual;" +
                    "apoyo launch/tap_text/input_text/swipe/back/home/wait/read, operaciones de pantalla virtual y node_script ejecutando scripts de Node en el entorno limitado de la aplicación." +
                    "node_script Acceso a archivos sandbox de aplicaciones, redes y procesos secundarios; Los resultados del script se guardan en el registro de recuperación mediante el cifrado del almacén de claves. Cada paso se detendrá si falla y se requiere aprobación antes de la ejecución.",
                parameters = mapOf(
                    "steps" to "Matriz JSON requerida, hasta 20 pasos; apoyado " +
                        "virtual_ensure/virtual_launch/virtual_tap/virtual_tap_text/virtual_swipe/" +
                        "virtual_text/virtual_key/virtual_wait/virtual_read/virtual_close;" +
                        "virtual_tap_text Soporte text/exact/maxSwipes/verifyText, desplazamiento limitado en la pantalla virtual y verificación de clics;" +
                        "node_script Pasos para usar code/timeoutMs (1-300 segundos), el fracaso se maneja como efectos secundarios desconocidos",
                    "run_id" to "Opcional. Reanuda un flujo de trabajo previamente interrumpido; dejar en blanco para la primera ejecución generará un nuevo runId",
                    "retry_unknown" to "Opcional. El resultado puede ser desconocido cuando un paso falla; solo se establece como verdadero después de la confirmación para volver a intentar la acción",
                ),
                required = setOf("steps"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val json = args["steps"]?.trim()
                ?: return@registerOutcome ToolOutcome.error("error:Parámetro de pasos faltantes")
            val steps = AutomationWorkflowParser.parse(json).getOrElse { error ->
                return@registerOutcome ToolOutcome.error("error:steps Inválido:${error.message}")
            }
            val runId = args["run_id"]?.trim().takeIf { !it.isNullOrBlank() } ?: "automation-${UUID.randomUUID()}"
            val retryUnknown = args["retry_unknown"]?.equals("true", ignoreCase = true) == true
            AutomationWorkflow(
                manager,
                workflowJournal,
                virtualDisplayClient,
                virtualDisplayManager,
                nodeScriptExecutor,
                displayLeases,
            ).run(steps, runId, retryUnknown)
        }
    }

    /** Actualizar el estado del permiso durante la inicialización. */
    suspend fun initialize() {
        try {
            manager.refreshPermissions()
        } catch (e: Exception) {
            Logger.w(TAG, "initial permission refresh failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AutomationTools"
    }
}
