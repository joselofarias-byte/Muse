package io.zer0.muse.tools

/**
 * show_card Herramientas.
 *
 * Agent Genere contenido de tarjeta HTML/SVG y presentelo en línea en la conversación; el renderizado real lo maneja el renderizador de tarjetas en el lado de la interfaz de usuario.
 *
 * ## Propiedad de la identidad de la tarjeta
 *
 * La herramienta no genera ni devuelve la identidad de la tarjeta. La identidad de la tarjeta es**el mensaje que lo lleva id**,
 * Sólo la capa de renderizado lo sabe. Se aprobó el guión de la tarjeta `window.muse.cardId` Léelo.
 *
 * Solía ​​haber una identificación única autogenerada (`c_<ts>_<seq>`) y utilizarlo como clave para el enlace de datos,
 * El resultado es que se almacena una identificación en ambos extremos: la tarjeta se verifica mediante la identificación del mensaje y la herramienta se verifica mediante `c_xxx` escribir,
 * Lo que se lee siempre es nulo y no se informa ningún error. Una identidad sólo puede tener una fuente.
 */
object ShowCardTool {

    /**
     * analizar `update_card_data` La identificación de la tarjeta de destino.
     *
     * Señala cuando card_id se omite del modelo**El mensaje actual del asistente.**Tarjeta encendida: Acaba de terminar show_card,
     * Lo único que sabe con seguridad es "éste", no una identificación que nunca antes haya visto.
     * Si se da explícitamente prevalecerá (cambie la tarjeta en un mensaje anterior), y la verificación quedará en manos de quien llama.
     *
     * El propósito de extraer funciones puras es permitir pruebas únicas: esta lógica es inaccesible cuando está integrada en la lambda registrada por la herramienta.
     */
    internal fun resolveCardTarget(requested: String?, contextMessageId: String?): String =
        requested?.trim().orEmpty().ifBlank { contextMessageId?.trim().orEmpty() }

    fun toolDef() = ToolRegistry.ToolDef(
        name = "show_card",
        description = "Mostrar contenido visual(SVG Gráfico, cuadro o interactivo HTML),Procese en línea en tarjetas interactivas en un WebView de espacio aislado." +
            "Adecuado para diagramas de flujo, paneles de control, tablas de datos y líneas de tiempo.,O cualquier cosa que requiera distribución espacial." +
            "No incluya DOCTYPE,<html>,<head>,<body> etiqueta,Ofrezca sólo fragmentos de contenido." +
            "La tarjeta lee su propia identidad a través de window.muse.cardId;Esta herramienta no devuelve identificadores.",
        parameters = mapOf(
            "title" to "Required. Short snake_case identifier (e.g. 'q4_revenue_chart').",
            "code" to "Required. HTML or SVG fragment. Use CSS variables for theming.",
            "data" to "Optional. JSON bound to this card; the card reads it via window.muse.getData().",
            "height" to "Optional. Card height: \"auto\" (fit content, default), " +
                "\"520\" or \"520dp\" (fixed, content scrolls inside), or \"16:9\" (aspect ratio). " +
                "Prefer giving an explicit size for dashboards and multi-row layouts — " +
                "content-fit measurement underestimates async content.",
        ),
        required = setOf("title", "code"),
        category = "built-in",
        // v2.6.10: Las cartas son puramente acciones de visualización.,Representación en un WebView en espacio aislado,Sin efectos secundarios del dispositivo.
        // Después de regresar a SAFE, ya no se aprobarán uno por uno. —— De lo contrario, en el modo PREGUNTAR, cada tarjeta esperará a que el usuario haga clic y acepte.,
        // 30 Rechazar automáticamente después de un tiempo de espera de segundos,La percepción del usuario es"No se puede generar la tarjeta".
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * Verificar parámetros y dar un resumen ejecutivo.
     *
     * tarjeta `data` No es necesario colocar los parámetros aquí: la capa de renderizado los sacará de los parámetros de la herramienta tal como están y los inyectará en línea.
     * (Ver `parseShowCardArgs` → `inlineCardData`), esa ruta no depende de ninguna convención de identificación.
     * Las actualizaciones posteriores serán proporcionadas por `update_card_data` Escriba según la identificación del mensaje y los usos de la tarjeta. `window.muse.data.onChange`
     * Sólo suscríbete.
     *
     * @param messageId El host inyecta la identificación del mensaje que lleva esta tarjeta. El recibo le dirá al modelo:
     *   Cuando el propio modelo quiere actualizar esta tarjeta, necesita una identificación que le dé un nombre y no puede saberlo desde ningún otro lugar.
     */
    fun execute(args: Map<String, String>, messageId: String? = null): String {
        val title = args["title"]?.trim()
            ?: return "Error: title parameter is required."
        val code = args["code"]?.trim()
            ?: return "Error: code parameter is required."
        if (title.isEmpty() || code.isEmpty()) {
            return "Error: title and code cannot be empty."
        }
        val data = args["data"]
        val dataNote = if (data.isNullOrBlank()) "" else " Bound data: ${data.length} chars."
        val height = args["height"]?.trim()
        val heightNote = if (height.isNullOrBlank() || height == "auto") "" else " Height: $height."
        val idNote = messageId?.takeIf { it.isNotBlank() }
            ?.let { " Card bound to message $it; pass it as card_id to update this card later." }
            .orEmpty()
        return "Card '$title' rendered. Code length: ${code.length} chars.$dataNote$heightNote$idNote"
    }
}
