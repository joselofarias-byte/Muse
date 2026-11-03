package io.zer0.muse.tools

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2-6 BrowserAutomationTool:Conjunto de herramientas de automatización del navegador(AI invocable).
 *
 * poner [BrowserManager] capacidades empaquetadas en 6 herramientas independientes,Regístrese para [ToolRegistry]:
 *  1. [TOOL_NAVIGATE]   — browser_navigate(url)        Navegar a URL
 *  2. [TOOL_CLICK]      — browser_click(selector)       elemento de clic
 *  3. [TOOL_TYPE]       — browser_type(selector, text)  Introducir texto
 *  4. [TOOL_EXTRACT]    — browser_extract(selector)     Extraer texto
 *  5. [TOOL_SCROLL_BOTTOM] — browser_scroll_bottom()    desplazarse hacia abajo
 *  6. [TOOL_GET_HTML]   — browser_get_html()            Obtener página actual HTML(Truncar)
 *
 * Instrucciones de puente(Lo mismo [CodeExecutionTool]):
 *  - [executeFromArgs] es una función sincrónica,Adaptar la firma ToolFn de ToolRegistry `(Map<String,String>) -> String`
 *  - Para uso interno [runBlocking] puente hacia [BrowserManager] La función de suspensión — porque [ToolRouteExecutionGuard.executeFromJson]
 *    Llamado en rutina IO,Bloquear el hilo IO esperando el hilo principal
 *    WebView devolución de llamada,El hilo principal no está bloqueado.,Sin riesgo de estancamiento
 *
 * Método de registro(prensa [ToolRegistry.init] Registro de CodeExecutionTool en):
 * ```kotlin
 * BrowserAutomationTool.toolDefs().forEach { def ->
 *     toolRegistry.register(def) { args ->
 *         BrowserAutomationTool.executeFromArgs(def.name, args, browserManager)
 *     }
 * }
 * ```
 */
object BrowserAutomationTool {

    private const val TAG = "BrowserAutomationTool"

    /** Constante de nombre de herramienta(Registrado en ToolRegistry id). */
    const val TOOL_NAVIGATE = "browser_navigate"
    const val TOOL_CLICK = "browser_click"
    const val TOOL_TYPE = "browser_type"
    const val TOOL_EXTRACT = "browser_extract"
    const val TOOL_SCROLL_BOTTOM = "browser_scroll_bottom"
    const val TOOL_GET_HTML = "browser_get_html"
    const val TOOL_SNAPSHOT = "browser_snapshot"
    const val TOOL_SCREENSHOT = "browser_screenshot"

    /**
     * Lista de todas las definiciones de herramientas(suministro [ToolRegistry.register] Registro por lotes).
     *
     * @return 6 un [ToolRegistry.ToolDef] lista de
     */
    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        ToolRegistry.ToolDef(
            name = TOOL_NAVIGATE,
            // v1.0.75 fix (Revisión de herramientas 02): Declaración complementaria de dependencia temporal
            description = "automatización del navegador:Navegue hasta la URL especificada. Esta herramienta debe llamarse primero para completar la navegación.,reutilizar browser_click/browser_type/browser_extract Página de operación. Todas las herramientas del navegador regresan JSON,Contiene éxito y datos/error." +
                "URL Se puede omitir scheme(Por ejemplo, example.com agrega automáticamente https://). El tiempo de espera es de 10 segundos.",
            parameters = mapOf(
                "url" to "Requerido,objetivo URL,Como https://example.com o example.com",
            ),
            required = setOf("url"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_CLICK,
            description = "automatización del navegador:Haga clic en el elemento de destino. Elija uno de los dos métodos de posicionamiento:" +
                "① selector(CSS seleccionador,La gramática es la misma que document.querySelector);" +
                "② numberId(browser_snapshot Número de elemento devuelto,Recomendado——No es necesario conocer la estructura de la página.).",
            parameters = mapOf(
                "selector" to "Opcional,CSS seleccionador,Como por ejemplo '#login-btn' o 'button.submit'",
                "numberId" to "Opcional,Número de elemento de instantánea(Elige uno de los dos con selector,Prioridad proporcionada)",
            ),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_TYPE,
            description = "automatización del navegador:Ingrese texto en el área de entrada/texto de destino." +
                "Elija uno de los dos métodos de posicionamiento:selector(CSS seleccionador)o numberId(número de instantánea,Recomendado)." +
                "Activa automáticamente eventos de entrada + cambio para que sean compatibles con marcos como React/Vue.",
            parameters = mapOf(
                "selector" to "Opcional,CSS seleccionador,Como por ejemplo '#search-input'",
                "numberId" to "Opcional,Número de elemento de instantánea(Elige uno de los dos con selector,Prioridad proporcionada)",
                "text" to "Requerido,El contenido del texto a ingresar.",
            ),
            required = setOf("text"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_EXTRACT,
            // v1.0.75 fix (Revisión de herramientas 02): Diferenciar de parse_link
            description = "automatización del navegador:Extrae el contenido de texto del elemento afectado por un selector CSS." +
                "Si falla, se devuelve una cadena vacía. Se puede utilizar para capturar precios de productos, texto de artículos, listas de artículos, etc." +
                "Diferente de parse_link: esta herramienta lee la página actualmente cargada por el navegador,parse_link Obtenga páginas individuales de las URL.",
            parameters = mapOf(
                "selector" to "Requerido,CSS seleccionador,Como por ejemplo '.price' o 'article h1'",
            ),
            required = setOf("selector"),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_SCROLL_BOTTOM,
            description = "automatización del navegador:Desplácese hasta el final de la página. Comúnmente utilizado en escenarios de desplazamiento infinito para cargar más contenido.(Como lista de productos, flujo dinámico.).",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_GET_HTML,
            description = "automatización del navegador:Obtenga el código fuente HTML completo de la página actual." +
                "Se puede utilizar para analizar la estructura de la página, extraer campos de formulario, etc.;Los resultados largos proporcionarán entradas de lectura segmentadas.",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        snapshotToolDef(),
        screenshotToolDef(),
    )

    /** Definición de la herramienta de instantáneas de la estructura de la página. */
    private fun snapshotToolDef() = ToolRegistry.ToolDef(
        name = TOOL_SNAPSHOT,
        description = "automatización del navegador:Extraer una instantánea estructurada de la página actual — Título/URL/Elementos interactivos" +
            "(enlace/botón/Cuadro de entrada,con números consecutivos)/Vista previa del texto. Úselo para comprender la estructura de la página.,reutilizar " +
            "browser_click/browser_type El numberId opera por número.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * v2.6.0: Definición de la herramienta de captura de pantalla de representación de página(extraído por separado,evitar [toolDefs] demasiado tiempo).
     * para que el modelo pueda realmente“mira”a píxeles de página,Y no sólo HTML/texto.
     */
    private fun screenshotToolDef() = ToolRegistry.ToolDef(
        name = TOOL_SCREENSHOT,
        description = "automatización del navegador:Capture una representación visual de la página actual.(píxeles reales,Con fotos/CSS Diseño)," +
            "Páselo de nuevo al modelo en forma de imagen. Cuando el contenido de la página requiere“mira”(Como imágenes, gráficos, códigos de verificación, efectos tipográficos," +
            "canvas/mapa/fotograma de vídeo)No sólo cuando se mira la estructura HTML.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * Puente sincrónico:Adapte la firma ToolFn de ToolRegistry.
     *
     * Para uso interno [runBlocking] llamar [BrowserManager] La función de suspensión:
     *  - [ToolRouteExecutionGuard.executeFromJson] Llamado en rutina IO,
     *    El hilo actual es un hilo IO,El hilo principal está inactivo cuando se bloquea esperando la devolución de llamada de WebView del hilo principal,Sin riesgo de estancamiento
     *
     * @param toolName Nombre de la herramienta(para su distribución al correspondiente BrowserManager API)
     * @param args parámetros map
     * @param browserManager Administrador del navegador Singleton
     * @return JSON cuerda,Contiene campo de éxito(true/false)con campo de datos/error
     */
    fun executeFromArgs(toolName: String, args: Map<String, String>, browserManager: BrowserManager): String = runBlocking {
        try {
            when (toolName) {
                TOOL_NAVIGATE -> {
                    val url = args["url"]?.takeIf { it.isNotBlank() }
                        ?: return@runBlocking buildResult(success = false, error = "Falta la URL del parámetro o está vacía")
                    browserManager.navigate(url).fold(
                        onSuccess = { buildResult(success = true, data = "Navegado a: $url") },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "Error de navegación") },
                    )
                }
                TOOL_CLICK -> {
                    val numberId = args["numberId"]?.trim()?.toIntOrNull()
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                    when {
                        numberId != null -> browserManager.evaluateJs(buildNumberedClickJs(numberId)).fold(
                            onSuccess = { raw ->
                                val ok = raw.contains("OK")
                                buildResult(
                                    success = true,
                                    data = if (ok) "Número en el que se hizo clic $numberId elemento" else "No. $numberId no encontrado(Es posible que la página se haya actualizado.,Por favor inténtalo de nuevo browser_snapshot)",
                                )
                            },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "Error al hacer clic") },
                        )
                        selector != null -> browserManager.click(selector).fold(
                            onSuccess = { hit -> buildResult(success = true, data = if (hit) "Elemento hecho clic: $selector" else "elemento perdido: $selector") },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "Error al hacer clic") },
                        )
                        else -> buildResult(success = false, error = "El selector de parámetros o numberId requiere al menos uno")
                    }
                }
                TOOL_TYPE -> {
                    val numberId = args["numberId"]?.trim()?.toIntOrNull()
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                    val text = args["text"] ?: return@runBlocking buildResult(success = false, error = "Falta el texto del parámetro")
                    when {
                        numberId != null -> browserManager.evaluateJs(buildNumberedTypeJs(numberId, text)).fold(
                            onSuccess = { raw ->
                                val ok = raw.contains("OK")
                                buildResult(
                                    success = true,
                                    data = if (ok) "ya numerado $numberId texto de entrada del elemento" else "No. $numberId no encontrado(Es posible que la página se haya actualizado.,Por favor inténtalo de nuevo browser_snapshot)",
                                )
                            },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "Error de entrada") },
                        )
                        selector != null -> browserManager.type(selector, text).fold(
                            onSuccess = { hit -> buildResult(success = true, data = if (hit) "Texto ingresado en: $selector" else "elemento perdido: $selector") },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "Error de entrada") },
                        )
                        else -> buildResult(success = false, error = "El selector de parámetros o numberId requiere al menos uno")
                    }
                }
                TOOL_EXTRACT -> {
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                        ?: return@runBlocking buildResult(success = false, error = "Falta el selector de parámetros o está vacío")
                    browserManager.extractText(selector).fold(
                        onSuccess = { text -> buildResult(success = true, data = text) },
                        onFailure = { e ->
                            Logger.w(TAG, "extractText falló: selector=$selector | url=${browserManager.currentUrl.value} | err=${e.message}")
                            buildResult(success = false, error = e.message ?: "No se pudo extraer")
                        },
                    )
                }
                TOOL_SCROLL_BOTTOM -> {
                    browserManager.scrollToBottom().fold(
                        onSuccess = { buildResult(success = true, data = "Desplazado hasta el final") },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "desplazamiento fallido") },
                    )
                }
                TOOL_SNAPSHOT -> {
                    browserManager.evaluateJs(SNAPSHOT_JS).fold(
                        onSuccess = { json -> buildResult(success = true, data = json) },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "Error al extraer la instantánea") },
                    )
                }
                TOOL_GET_HTML -> {
                    val html = browserManager.currentHtml.value
                    buildResult(success = true, data = html)
                }
                TOOL_SCREENSHOT -> {
                    browserManager.captureScreenshot().fold(
                        onSuccess = { base64 ->
                            if (base64.isBlank()) {
                                buildResult(success = false, error = "El contenido de la captura de pantalla está vacío (es posible que la página aún no se haya mostrado)")
                            } else {
                                // v2.6.0: Los resultados de la captura de pantalla se guardan en campos especiales y el organizador añade una imagen con un
                                // Mensaje de usuario (los resultados de la herramienta en sí no pueden transportar imágenes al modelo, consulte ToolOrchestrator).
                                // data Solo proporcione al modelo un mensaje de marcador de posición y use el campo de captura de pantalla para la imagen real.
                                val sizeKb = base64.length / 1024
                                buildScreenshotResult(
                                    data = "Se ha capturado la página actual (aprox. ${sizeKb}KB, foto adjunta)",
                                    screenshotBase64 = base64,
                                )
                            }
                        },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "Error en la captura de pantalla") },
                    )
                }
                else -> buildResult(success = false, error = "herramienta desconocida: $toolName")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "executeFromArgs anormal: toolName=$toolName, msg=${e.message}", e)
            buildResult(success = false, error = e.message ?: "excepción de ejecución")
        }
    }

    /**
     * Construya el resultado de la ejecución JsonObject y serialícelo en una cadena.
     *
     * convención de campo:
     *  - success: Boolean — Si la ejecución fue exitosa
     *  - data: String? — Valor de retorno en caso de éxito(Navegación/Haga clic/Ingrese como texto de solicitud;Extraer como contenido;get_html como código fuente HTML)
     *  - error: String? — Mensaje de error en caso de falla
     */
    private fun buildResult(success: Boolean, data: String? = null, error: String? = null): String {
        val obj: JsonObject = buildJsonObject {
            put("success", JsonPrimitive(success))
            if (data != null) {
                put("data", JsonPrimitive(data))
            } else {
                put("data", JsonNull)
            }
            if (error != null) {
                put("error", JsonPrimitive(error))
            } else {
                put("error", JsonNull)
            }
        }
        return obj.toString()
    }

    /**
     * v2.6.0: Resultados exclusivos de captura de pantalla —— Agregar a resultados estándar `screenshot` campo(Base64 PNG).
     *
     * Los resultados de la herramienta solo pueden contener texto cuando se rellenan en el modelo a través de la API.(function_call_output Cadena limitada),
     * Entonces el editor reconocerá este campo.,Agregue la imagen a la conversación como un mensaje de usuario separado con la imagen.
     *
     * convención de campo:
     *  - success: Boolean
     *  - data: String — Indicaciones de texto para el modelo.(Como por ejemplo“La página actual ha sido capturada.”)
     *  - screenshot: String — Base64 PNG(Ninguno dato: prefijo)
     */
    private fun buildScreenshotResult(data: String, screenshotBase64: String): String {
        val obj: JsonObject = buildJsonObject {
            put("success", JsonPrimitive(true))
            put("data", JsonPrimitive(data))
            put("error", JsonNull)
            // Los nombres de los campos de etiqueta son consistentes con la convención del organizador.(Vea el procesamiento de resultados de captura de pantalla de ToolOrchestrator).
            put(SCREENSHOT_RESULT_FIELD, JsonPrimitive(screenshotBase64))
        }
        return obj.toString()
    }

    /**
     * v2.6.0: El resultado de la captura de pantalla es el nombre del campo en el JSON devuelto por la herramienta.
     * Cuando el orquestador lee este campo, agrega la imagen a la conversación como un mensaje de usuario con imagen.
     */
    const val SCREENSHOT_RESULT_FIELD = "screenshot"

    /**
     * v1.0.92: Script de instantánea estructurada de página — Extraiga elementos interactivos y guárdelos en caché window.__museEls,
     * Proporciona operación de tipo click/type en modo numberId por número(La caché se invalida después de actualizar la página,Necesito volver a tomar la instantánea).
     *
     * presupuesto: enlace≤40 / botón≤30 / Cuadro de entrada≤20,Vista previa del texto≤3000 personajes.
     */
    private const val SNAPSHOT_JS = """(function(){
  try {
    function textOf(el){ return (el && (el.innerText || el.textContent) || '').replace(/\s+/g,' ').trim(); }
    var els = [];
    var links = [], buttons = [], inputs = [];
    document.querySelectorAll('a[href]').forEach(function(a){
      if (links.length >= 40) return;
      var t = textOf(a); if (!t) return;
      links.push({id: els.length, text: t.substring(0, 80), href: a.href});
      els.push(a);
    });
    document.querySelectorAll('button,[role=button],input[type=submit],input[type=button]').forEach(function(b){
      if (buttons.length >= 30) return;
      var t = textOf(b) || b.value || ''; if (!t) return;
      buttons.push({id: els.length, text: t.substring(0, 80)});
      els.push(b);
    });
    document.querySelectorAll('input:not([type=hidden]),textarea,select').forEach(function(inp){
      if (inputs.length >= 20) return;
      inputs.push({id: els.length, name: inp.name || '', placeholder: inp.placeholder || '', type: inp.type || 'text'});
      els.push(inp);
    });
    window.__museEls = els;
    var body = textOf(document.body);
    return {
      url: location.href,
      title: document.title,
      links: links,
      buttons: buttons,
      inputs: inputs,
      bodyPreview: body.substring(0, 3000),
      truncated: body.length > 3000
    };
  } catch (e) { return {error: String((e && e.message) || e)}; }
})()"""

    /** v1.0.92: numberId Script de clic modal. */
    private fun buildNumberedClickJs(index: Int): String = "(function(){var el=(window.__museEls||[])[$index];" +
        "if(!el){return 'MISS';}" +
        "try{el.scrollIntoView({block:'center'});}catch(e){/* scroll is best-effort */}" +
        "el.click();return 'OK';})()"

    /** v1.0.92: numberId Script de entrada de modo(text Antiinyección mediante codificación JSON). */
    private fun buildNumberedTypeJs(index: Int, text: String): String {
        val encoded = AppJson.encodeToString(String.serializer(), text)
        return "(function(){var el=(window.__museEls||[])[$index];" +
            "if(!el){return 'MISS';}" +
            "el.focus();el.value=$encoded;" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));return 'OK';})()"
    }
}
