package io.zer0.muse.tools.defaultTool

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.tools.system.AccessibilityClient
import java.io.File

/**
 * v1.0.52 P3-3: UI Conjunto de herramientas de automatización — Capacidades de control de dispositivos expuestas a la IA.
 *
 * depender de [AccessibilityClient](Canales de atención sin barreras),10 herramientas proporcionadas:
 *  - [getPageInfo]: Obtener el nivel de UI de la página actual(árbol de nodos,Contiene coordenadas/texto/Capacidad de hacer clic)
 *  - [click] / [longPress]: Haga clic en las coordenadas/Pulsación larga
 *  - [swipe]: gesto de deslizar(Especificar coordenadas de inicio y fin + duración)
 *  - [setText]: en el nodo especificado(Identificado por ruta)Introducir texto
 *  - [screenshot]: Guardar captura de pantalla en un archivo(necesidad Android 14+)
 *  - [back] / [home]: Teclas de acceso directo de acciones globales
 *  - [globalAction]: Realizar cualquier acción de accesibilidad global
 *  - [getCurrentApp]: Consultar la aplicación actual en primer plano.
 *
 * seguro:
 *  - Todos los niveles de riesgo ALTO(Controle directamente los dispositivos del usuario,Puede desencadenar operaciones irreversibles.)
 *  - Requiere servicios de accesibilidad habilitados(ACCESSIBILITY y arriba)
 *  - El sistema de permiso de sesión requerirá aprobación.(Modo no CONFIABLE)
 *
 * Formato de devolución:
 *  - éxito: texto legible(JSON o texto estructurado)
 *  - falló: "[error] ..." forma
 */
object UIToolsSkill {

    private const val TAG = "UIToolsSkill"

    // ── Constante de nombre de herramienta ────────────────────────────────────────────────────────────

    const val GET_PAGE_INFO = "ui_get_page_info"
    const val CLICK = "ui_click"
    const val LONG_PRESS = "ui_long_press"
    const val SWIPE = "ui_swipe"
    const val SET_TEXT = "ui_set_text"
    const val SCREENSHOT = "ui_screenshot"
    const val BACK = "ui_back"
    const val HOME = "ui_home"
    const val GLOBAL_ACTION = "ui_global_action"
    const val GET_CURRENT_APP = "ui_get_current_app"

    // ── Definición de herramienta ──────────────────────────────────────────────────────────────

    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        ToolRegistry.ToolDef(
            name = GET_PAGE_INFO,
            description = "Obtenga la información del nivel de UI de la pantalla actual(árbol de nodos). Cada nodo contiene un identificador de ruta.(Como por ejemplo 0.1.2)," +
                "class,text,bounds(rango de coordenadas),clickable/editable/focused estado." +
                "Se utiliza para saber qué elementos interactivos y sus coordenadas se encuentran en la página actual. Primero es necesario habilitar los servicios de accesibilidad.",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = CLICK,
            description = "Haga clic en las coordenadas especificadas en la pantalla. Las coordenadas son píxeles absolutos en la pantalla." +
                "Se recomienda utilizar ui_get_page_info para obtener los límites del elemento de destino antes de hacer clic en el punto central.",
            parameters = mapOf(
                "x" to "Requerido,x coordenada del clic(píxeles de la pantalla)",
                "y" to "Requerido,coordenada y del clic(píxeles de la pantalla)",
            ),
            required = setOf("x", "y"),
            category = "built-in",
            parameterTypes = mapOf("x" to "integer", "y" to "integer"),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = LONG_PRESS,
            description = "Realice una pulsación larga en las coordenadas especificadas en la pantalla(Mantenga presionado 500ms). A menudo se utiliza para abrir menús contextuales.",
            parameters = mapOf(
                "x" to "Requerido,coordenada x de pulsación larga",
                "y" to "Requerido,coordenada y de pulsación larga",
            ),
            required = setOf("x", "y"),
            category = "built-in",
            parameterTypes = mapOf("x" to "integer", "y" to "integer"),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = SWIPE,
            description = "Realice un gesto de deslizamiento en la pantalla. Deslizarse desde el punto inicial hasta el punto final,duration controlar la velocidad(Cuanto más se alarga, más lento se vuelve)." +
                "Desliza hacia arriba para pasar páginas:startY gran final pequeño;Lo contrario ocurre con las caídas.",
            parameters = mapOf(
                "start_x" to "Requerido,Coordenada x del punto de partida",
                "start_y" to "Requerido,Punto de partida coordenada y",
                "end_x" to "Requerido,Coordenada x del punto final",
                "end_y" to "Requerido,Punto final coordenada y",
                "duration" to "Opcional,Duración de la diapositiva(milisegundos),Predeterminado 300,Cuanto más grande, más lento",
            ),
            required = setOf("start_x", "start_y", "end_x", "end_y"),
            category = "built-in",
            parameterTypes = mapOf(
                "start_x" to "integer",
                "start_y" to "integer",
                "end_x" to "integer",
                "end_y" to "integer",
                "duration" to "integer",
            ),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = SET_TEXT,
            description = "Establecer texto en el nodo especificado(Pase ACTION_SET_TEXT)." +
                "node_id Identificador de ruta devuelto por ui_get_page_info(Como por ejemplo 0.1.2)O el nodo enfocado en ui_get_page_info." +
                "Se utiliza para completar el contenido en el cuadro de entrada.",
            parameters = mapOf(
                "node_id" to "Requerido,ruta del nodo objetivo(de ui_get_page_info [path])",
                "text" to "Requerido,texto para ingresar",
            ),
            required = setOf("node_id", "text"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = SCREENSHOT,
            description = "Tome una captura de pantalla de la pantalla actual y guárdela como una imagen. necesidad Android 14+(API 34)." +
                "Devuelve la ruta de guardado. Si no se pasa la ruta, se guardará en el directorio de caché de la aplicación.",
            parameters = mapOf(
                "path" to "Opcional,guardar ruta(camino absoluto),Guardar en el directorio de caché de forma predeterminada",
                "format" to "Opcional,Formato de imagen PNG(Predeterminado) o JPEG",
            ),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = BACK,
            description = "Simular presionando la tecla de retorno(acción global GLOBAL_ACTION_BACK).",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = HOME,
            description = "Simular presionando el botón Inicio(Volver al escritorio,GLOBAL_ACTION_HOME).",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = GLOBAL_ACTION,
            description = "Realizar acciones de accesibilidad global. de uso común action_id: 1=BACK, 2=HOME, 3=RECENTS(tareas recientes)," +
                " 4=NOTIFICATIONS, 5=QUICK_SETTINGS, 6=POWER_DIALOG, 7=TOGGLE_NOTIFICATION, 10=DISMISS_NOTIFICATION_SHADE.",
            parameters = mapOf(
                "action_id" to "Requerido,acción global ID(Como por ejemplo 1=Regresar, 2=HOME)",
            ),
            required = setOf("action_id"),
            category = "built-in",
            parameterTypes = mapOf("action_id" to "integer"),
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = GET_CURRENT_APP,
            description = "Obtener la aplicación actual en primer plano/Activity nombre del componente(Como por ejemplo com.android.settings/.Settings)." +
                "Se utiliza para saber en qué aplicación se encuentra actualmente el usuario.",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
    )

    // ── lógica de ejecución ──────────────────────────────────────────────────────────────

    suspend fun getPageInfo(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "[error] Los servicios de accesibilidad no están habilitados"
        val info = client.getPageInfo()
        return info.ifBlank { "[error] No se puede obtener la jerarquía de la interfaz de usuario(Puede que no haya ninguna ventana activa)" }
    }

    suspend fun click(client: AccessibilityClient, x: Int, y: Int): String {
        if (!ensureConnected(client)) return "[error] Los servicios de accesibilidad no están habilitados"
        val ok = client.click(x, y)
        return if (ok) "[éxito] Se hizo clic ($x, $y)" else "[falló] Haga clic en Sin terminar(Las coordenadas están fuera de límites o el gesto se cancela.)"
    }

    suspend fun longPress(client: AccessibilityClient, x: Int, y: Int): String {
        if (!ensureConnected(client)) return "[error] Los servicios de accesibilidad no están habilitados"
        val ok = client.longPress(x, y)
        return if (ok) "[éxito] presionado durante mucho tiempo ($x, $y)" else "[falló] La pulsación larga no se completa"
    }

    suspend fun swipe(client: AccessibilityClient, startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): String {
        if (!ensureConnected(client)) return "[error] Los servicios de accesibilidad no están habilitados"
        val ok = client.swipe(startX, startY, endX, endY, duration)
        return if (ok) "[éxito] deslizado ($startX,$startY) -> ($endX,$endY)" else "[falló] Deslizamiento no completado"
    }

    suspend fun setText(client: AccessibilityClient, nodeId: String, text: String): String {
        if (!ensureConnected(client)) return "[error] Los servicios de accesibilidad no están habilitados"
        if (nodeId.isBlank()) return "[error] Falta el parámetro node_id"
        val ok = client.setText(nodeId, text)
        return if (ok) "[éxito] Ya en el nodo $nodeId Introducir texto" else "[falló] Error al configurar el texto(El nodo no es editable o no existe)"
    }

    suspend fun screenshot(client: AccessibilityClient, context: Context, path: String?, format: String): String {
        if (!ensureConnected(client)) return "[error] Los servicios de accesibilidad no están habilitados"
        val targetPath = path?.takeIf { it.isNotBlank() } ?: run {
            val ext = if (format.equals("JPEG", ignoreCase = true)) ".jpg" else ".png"
            File(context.cacheDir, "ui_screenshot_${System.currentTimeMillis()}$ext").absolutePath
        }
        val ok = client.screenshot(targetPath, format)
        if (ok) return "[éxito] Captura de pantalla guardada: $targetPath"
        // v2.6.13: distinguir“No obtuve la habilidad”con“Lo conseguí pero fallé esta vez.”De lo contrario, el modelo sólo puede adivinar la causa a ciegas.
        val hint = if (!client.canTakeScreenshot()) {
            "El servicio no tiene la capacidad de realizar capturas de pantalla. Apague y luego vuelva a habilitar el servicio de accesibilidad de Muse en la configuración del sistema."
        } else {
            "Requiere Android 14+ o las capturas de pantalla están prohibidas en la página actual (como pago/página de contraseña)"
        }
        return "[falló] Error en la captura de pantalla($hint)"
    }

    suspend fun back(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "[error] Los servicios de accesibilidad no están habilitados"
        val ok = client.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        return if (ok) "[éxito] regresó" else "[falló] La acción de devolución falló"
    }

    suspend fun home(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "[error] Los servicios de accesibilidad no están habilitados"
        val ok = client.globalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        return if (ok) "[éxito] Regresado al escritorio" else "[falló] HOME La acción falló"
    }

    suspend fun globalAction(client: AccessibilityClient, actionId: Int): String {
        if (!ensureConnected(client)) return "[error] Los servicios de accesibilidad no están habilitados"
        val ok = client.globalAction(actionId)
        return if (ok) "[éxito] acción global $actionId ejecutado" else "[falló] acción global $actionId falló"
    }

    suspend fun getCurrentApp(client: AccessibilityClient): String {
        if (!ensureConnected(client)) return "[error] Los servicios de accesibilidad no están habilitados"
        val name = client.currentActivityName()
        return name.ifBlank { "[Consejos] La aplicación en primer plano no se puede determinar actualmente" }
    }

    // ── herramientas internas ──────────────────────────────────────────────────────────────

    private suspend fun ensureConnected(client: AccessibilityClient): Boolean {
        // P3-3: Bajo esquema de instancia estática,Las conexiones son gestionadas por el sistema. — Una vez habilitado el servicio, la instancia está disponible automáticamente.,
        // app No se puede tomar la iniciativa bindService(del esquema AIDL original connect() Eliminado).
        // Aquí solo se realizan comprobaciones de estado:Liberar si está conectado,De lo contrario, deje que la persona que llama regrese"No habilitado"Error.
        return client.isConnected()
    }
}

/**
 * UI Registrador de herramientas — Registre 10 herramientas de interfaz de usuario para [ToolRegistry].
 *
 * depender de ToolRegistry + AccessibilityClient + Context.
 * Creado cuando se inicializa AppKoinModule(Las herramientas solo están disponibles si los servicios de accesibilidad están habilitados,Pero el registro siempre se hace.,
 * ejecutado por [UIToolsSkill.ensureConnected] Verificar el estado de la conexión).
 */
class UIToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val accessibilityClient: AccessibilityClient,
    private val context: Context,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        val client = accessibilityClient
        // Nombre de la herramienta -> Ejecutar mapeo de lambda,Análisis de parámetros unificados
        val executors: List<Pair<ToolRegistry.ToolDef, suspend (Map<String, String>) -> String>> = listOf(
            UIToolsSkill.toolDefs()[0] to { UIToolsSkill.getPageInfo(client) },
            UIToolsSkill.toolDefs()[1] to { args ->
                val x = args["x"]?.toIntOrNull() ?: return@to "[error] Argumento x faltante o no válido"
                val y = args["y"]?.toIntOrNull() ?: return@to "[error] Argumento y faltante o no válido"
                UIToolsSkill.click(client, x, y)
            },
            UIToolsSkill.toolDefs()[2] to { args ->
                val x = args["x"]?.toIntOrNull() ?: return@to "[error] Argumento x faltante o no válido"
                val y = args["y"]?.toIntOrNull() ?: return@to "[error] Argumento y faltante o no válido"
                UIToolsSkill.longPress(client, x, y)
            },
            UIToolsSkill.toolDefs()[3] to { args ->
                val sx = args["start_x"]?.toIntOrNull() ?: return@to "[error] desaparecido start_x"
                val sy = args["start_y"]?.toIntOrNull() ?: return@to "[error] desaparecido start_y"
                val ex = args["end_x"]?.toIntOrNull() ?: return@to "[error] desaparecido end_x"
                val ey = args["end_y"]?.toIntOrNull() ?: return@to "[error] desaparecido end_y"
                val dur = args["duration"]?.toLongOrNull() ?: 300L
                UIToolsSkill.swipe(client, sx, sy, ex, ey, dur)
            },
            UIToolsSkill.toolDefs()[4] to { args ->
                val nodeId = args["node_id"] ?: return@to "[error] desaparecido node_id"
                val text = args["text"] ?: return@to "[error] desaparecido text"
                UIToolsSkill.setText(client, nodeId, text)
            },
            UIToolsSkill.toolDefs()[5] to { args ->
                val path = args["path"]
                val format = args["format"]?.takeIf { it.isNotBlank() } ?: "PNG"
                UIToolsSkill.screenshot(client, context, path, format)
            },
            UIToolsSkill.toolDefs()[6] to { UIToolsSkill.back(client) },
            UIToolsSkill.toolDefs()[7] to { UIToolsSkill.home(client) },
            UIToolsSkill.toolDefs()[8] to { args ->
                val actionId = args["action_id"]?.toIntOrNull() ?: return@to "[error] Falta o no es válida action_id"
                UIToolsSkill.globalAction(client, actionId)
            },
            UIToolsSkill.toolDefs()[9] to { UIToolsSkill.getCurrentApp(client) },
        )
        executors.forEach { (def, fn) ->
            toolRegistry.register(def, fn)
        }
        Logger.i("UIToolsRegistrar", "Registrado ${executors.size} herramientas de interfaz de usuario")
    }
}
