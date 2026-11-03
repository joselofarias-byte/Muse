package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.tools.defaultTool.UIToolsRegistrar

/**
 * Tool Registrar comienza a iniciarse.
 *
 * Cada registro de herramienta es un único de carga diferida en Koin y solo se ejecutará cuando se cree una instancia.
 * `init { registerAll() }` y escribe la herramienta [ToolRegistry].
 * Esta clase se inyecta una vez cuando se inicia la aplicación, lo que obliga a crear instancias de todos los registros.
 * Evite que la página de administración de herramientas muestre solo una pequeña cantidad de herramientas integradas en ToolRegistry.
 *
 * Número de parámetros y exención "no utilizada": esta clase es un punto de agregación de inyección de dependencia pura —— Cada parámetro de construcción existe con el único propósito de
 * Es para activar el efecto secundario de registro inicial del Registrador correspondiente, y la clase en sí no hace referencia a ellos.LongParameterList /
 * UnusedPrivateProperty Esto es así por diseño, por lo que está explícitamente exento a nivel de clase (los nuevos registros en el futuro no necesitan actualizar la línea base).
 */
@Suppress("LongParameterList", "UnusedPrivateProperty")
class ToolRegistrarBootstrapper(
    private val toolRegistry: ToolRegistry,
    encodingToolsRegistrar: EncodingToolsRegistrar,
    coreToolsRegistrar: CoreToolsRegistrar,
    weatherToolsRegistrar: WeatherToolsRegistrar,
    clipboardToolsRegistrar: ClipboardToolsRegistrar,
    networkTextToolsRegistrar: NetworkTextToolsRegistrar,
    reminderToolsRegistrar: ReminderToolsRegistrar,
    calendarToolsRegistrar: CalendarToolsRegistrar,
    phoneToolsRegistrar: PhoneToolsRegistrar,
    systemToolsRegistrar: SystemToolsRegistrar,
    resourceToolsRegistrar: ResourceToolsRegistrar,
    quickNoteToolsRegistrar: QuickNoteToolsRegistrar,
    scheduledTaskToolsRegistrar: ScheduledTaskToolsRegistrar,
    translateToolsRegistrar: TranslateToolsRegistrar,
    ttsToolsRegistrar: TtsToolsRegistrar,
    agentToolsRegistrar: AgentToolsRegistrar,
    conversationSearchToolsRegistrar: ConversationSearchToolsRegistrar,
    workspaceToolsRegistrar: WorkspaceToolsRegistrar,
    fileToolsRegistrar: FileToolsRegistrar,
    pdfVisionToolsRegistrar: PdfVisionToolsRegistrar,
    shellSandboxToolRegistrar: ShellSandboxToolRegistrar,
    uiToolsRegistrar: UIToolsRegistrar,
    // P2-23: herramientas de generación de medios(fotos/vídeo/código qr),Registrado originalmente en la página de chat por ChatViewModel
    mediaGenToolsRegistrar: MediaGenToolsRegistrar,
    // v1.0.92: Herramientas del canal de mensajes(Envío de mensajería instantánea externa)
    channelToolsRegistrar: ChannelToolsRegistrar,
    // v2.0: OAuth herramienta conectora(connector_list / call_connector)
    connectorToolsRegistrar: ConnectorToolsRegistrar,
    // v2.0.1: Herramienta de mercado de complementos(Instalación de recuperación/aprobación)
    pluginMarketToolsRegistrar: PluginMarketToolsRegistrar,
    // v2.x Tiempo de ejecución extendido(P0):Autoprueba integrada de la zona de pruebas de Node(solo lectura)
    runtimeSelfCheckToolRegistrar: RuntimeSelfCheckToolRegistrar,
    // v2.7 Tiempo de ejecución extendido(Python):Python Ejecución de script
    pythonScriptToolRegistrar: PythonScriptToolRegistrar,
    // v2.6.2: Gestión de la base de conocimientos (eliminar documentos/Eliminar base de datos/Construir una base de datos/hacer una lista)
    knowledgeManageToolsRegistrar: KnowledgeManageToolsRegistrar,
    // v2.6.2: Lea el texto completo de la base de conocimientos.
    knowledgeReadToolRegistrar: KnowledgeReadToolRegistrar,
    // v2.6.2: Gestión de sesiones (eliminar/Cambiar título/Mover carpeta/Archivo)
    sessionManageToolsRegistrar: SessionManageToolsRegistrar,
    // v2.6.2: Gestión de Habilidades (lista/Iniciar y detener/eliminar)
    skillManageToolsRegistrar: SkillManageToolsRegistrar,
    // v2.6.2: Autogestión del asistente (permisos limitados)
    assistantManageToolsRegistrar: AssistantManageToolsRegistrar,
) {
    init {
        // El conteo toma la entrada unificada ([ToolRegistry.countTools]), para evitar calcular lo mismo en todas partes
        val registered = toolRegistry.countTools(sessionId = null).registeredTotal
        Logger.i(
            "ToolRegistrarBootstrapper",
            "El registro de herramientas está todo inicializado y el número de herramientas registradas localmente=$registered(hora de inicio;MCP, los complementos y las habilidades de sesión aún no se han registrado)",
        )
        // La verificación de cobertura de clasificación debe realizarse aquí. —— Se ha ejecutado todo el inicio del registrador.,
        // En este momento, toolRegistry solo contiene todas las herramientas integradas.(Ver detalles ToolRegistry.assertBuiltInCategoryCoverage).
        toolRegistry.assertBuiltInCategoryCoverage()
    }
}
