package io.zer0.muse.mcp

/**
 * MCP Server Configurar plantillas.
 *
 * La plantilla solo proporciona métodos de conexión y consejos de uso.,No se incorporan direcciones, cuentas o tokens de servicios de terceros.
 * Esto reduce los costos de configuración por primera vez.,El punto final de un determinado fabricante tampoco se considerará un contrato permanente.
 */
data class McpServerTemplate(
    val id: String,
    val displayName: String,
    val summary: String,
    val transportType: McpTransportType,
    val urlPlaceholder: String,
    val defaultHeaders: Map<String, String> = emptyMap(),
    /** STDIO El comando predeterminado de la plantilla (transportType == STDIO cuando se utiliza). */
    val commandPlaceholder: String = "",
)

/**
 * oficina común doméstica/Plantilla de configuración de MCP para software de base de conocimientos.
 *
 * entre ellos“remoto MCP”Puede completar directamente el punto final HTTP proporcionado por el fabricante;
 * “puente”Indica la necesidad de ejecutar un MCP adapter,Luego expóngalo como SSE/HTTP transmitible.
 */
object McpServerTemplates {
    const val CUSTOM_ID = "custom"

    val custom = McpServerTemplate(
        id = CUSTOM_ID,
        displayName = "Personalizar MCP Server",
        summary = "Complete manualmente la información de autorización, método de transmisión y punto final",
        transportType = McpTransportType.STREAMABLE_HTTP,
        urlPlaceholder = "https://server.example.com/mcp",
    )

    val all: List<McpServerTemplate> = listOf(
        // P1-A: MCP nativo (tiempo de ejecución de nodo integrado,stdio Transmisión; la primera ejecución requiere una conexión a Internet para descargar los paquetes de dependencia)
        McpServerTemplate(
            id = "local_filesystem",
            displayName = "sistema de archivos local",
            summary = "MCP local: permita que AI lea y escriba carpetas en su teléfono (tiempo de ejecución integrado)",
            transportType = McpTransportType.STDIO,
            urlPlaceholder = "",
            commandPlaceholder = "npx -y @modelcontextprotocol/server-filesystem /sdcard/Documents",
        ),
        McpServerTemplate(
            id = "local_memory",
            displayName = "Gráfico de conocimiento de la memoria",
            summary = "MCP local: memoria a largo plazo basada en gráficos de conocimiento (tiempo de ejecución integrado)",
            transportType = McpTransportType.STDIO,
            urlPlaceholder = "",
            commandPlaceholder = "npx -y @modelcontextprotocol/server-memory",
        ),
        McpServerTemplate(
            id = "local_sequential_thinking",
            displayName = "pensar secuencialmente",
            summary = "MCP nativo: asistencia de razonamiento paso a paso (tiempo de ejecución integrado)",
            transportType = McpTransportType.STDIO,
            urlPlaceholder = "",
            commandPlaceholder = "npx -y @modelcontextprotocol/server-sequential-thinking",
        ),
        McpServerTemplate(
            id = "feishu_remote",
            displayName = "Base de conocimientos de Feishu",
            summary = "MCP remoto: busque y lea documentos de Feishu Cloud",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "Pegue la dirección del servicio MCP remoto de Feishu",
            // Solo lectura predeterminada,Cuando es necesario escribir la memoria, el usuario puede agregar activamente herramientas de actualización/creación a los campos avanzados.
            defaultHeaders = mapOf(
                "X-Lark-MCP-Allowed-Tools" to
                    "search-doc,fetch-doc,list-docs,fetch-file,get-comments",
            ),
        ),
        McpServerTemplate(
            id = "feishu_cli_bridge",
            displayName = "Puente CLI de Feishu",
            summary = "CLI local a través de Desktop Bridge HTTP/SSE",
            transportType = McpTransportType.SSE,
            urlPlaceholder = "http://dirección de la computadora:puerto/sse",
        ),
        McpServerTemplate(
            id = "dingtalk_knowledge",
            displayName = "Base de conocimientos de DingTalk",
            summary = "MCP remoto o puente de construcción propia: lea la documentación y la base de conocimientos de DingTalk",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "Pegue la dirección del servicio DingTalk MCP/Bridge",
        ),
        McpServerTemplate(
            id = "wecom_knowledge",
            displayName = "Base de conocimientos de WeChat empresarial",
            summary = "Puente de construcción propia: conéctese a documentos corporativos de WeChat, robots de grupo o interfaces de aplicaciones",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "Pegue la dirección del puente empresarial WeChat MCP",
        ),
        McpServerTemplate(
            id = "yuque_knowledge",
            displayName = "Base de conocimientos Yuque",
            summary = "Puente de construcción propia: utilice la interfaz abierta de Yuque para encapsular las herramientas de búsqueda y lectura",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "Pegue la dirección del puente Yuque MCP",
        ),
        McpServerTemplate(
            id = "tencent_docs",
            displayName = "Documentos Tencent",
            summary = "Puente de construcción propia: exponer la interfaz de recuperación de documentos de Tencent como MCP",
            transportType = McpTransportType.STREAMABLE_HTTP,
            urlPlaceholder = "Pegue la dirección del puente MCP del documento Tencent",
        ),
    )

    /** Buscar plantilla por ID,Devuelve una plantilla personalizada si no se encuentra. */
    fun find(id: String): McpServerTemplate = all.firstOrNull { it.id == id } ?: custom
}
