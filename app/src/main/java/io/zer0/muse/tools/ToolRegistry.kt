package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.core.ToolDefinition
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 5-H: Registro de herramientas(Versión simplificada del marco MCP).
 *
 * Phase 7 Expandir:Acceso LLM function calling —
 *  - [listToolsAsToolDefinitions] Genere una lista de definiciones de herramientas compatibles con OpenAI
 *  - [executeFromJson] herramienta de ejecución de cadenas JSON de argumentos devuelta por LLM
 *
 * Phase 8.8 Expandir:
 *  - Cambiar a registro dinámico(mutableMap + [register] API),Admite agregar y eliminar herramientas en tiempo de ejecución
 *  - 7 herramientas locales integradas(Juego de herramientas locales de 7 piezas.,Ninguno QuickJS):
 *    1. get_current_time — Obtener la hora actual(Parámetros de zona horaria de soporte)
 *    2. calculator — calculadora sencilla(Cuatro operaciones aritméticas)
 *    3. echo — eco(para probar)
 *    4. clipboard_read — Leer el portapapeles del sistema
 *    5. clipboard_write — Escribir en el portapapeles del sistema
 *    6. screen_time — Obtenga las estadísticas del tiempo de pantalla de hoy(UsageStatsManager)
 *    7. calendar_today — Obtener los eventos del calendario de hoy(CalendarContract)
 *  - Herramientas celulares(10 un,Android sistema API):
 *    8. set_alarm — Establecer alarma(AlarmClock,Soporta la repetición semanal.)
 *    9. set_timer — Establecer cuenta regresiva(AlarmClock)
 *    10. open_app — Abrir aplicación(PackageManager,apoyo Deep Link)
 *    11. share_text — compartir texto(ACTION_SEND)
 *    12. get_location — Obtener una ubicación aproximada(LocationManager,necesidad ACCESS_COARSE_LOCATION)
 *    13. get_device_info — Obtener información del dispositivo(Build/BatteryManager)
 *    14. get_contacts_count — Número de contactos(ContactsContract,necesidad READ_CONTACTS)
 *    15. get_contacts_list — lista de contactos(Versión mejorada,necesidad READ_CONTACTS)
 *    16. send_sms — Enviar SMS(SmsManager,necesidad SEND_SMS)
 *    17. add_contact — Crear nuevo contacto(Intent.ACTION_INSERT)
 *  - Control del sistema y herramientas de correo.(5 un):
 *    18. open_system_setting — Abra la página de configuración del sistema(Admite wifi/bluetooth/pantalla y otras categorías)
 *    19. toggle_wifi — cambiar WiFi(Android 10+ Saltar a la página de configuración,estado legible)
 *    20. toggle_bluetooth — Activar y desactivar Bluetooth(estado legible/cerrar/Vaya a la página de configuración para abrir)
 *    21. send_email — Enviar correo electrónico(ACTION_SENDTO Abra la aplicación de correo)
 *    22. get_battery_info — Obtener información de la batería(poder/estado de carga)
 *  - [listToolsAsToolDefinitions] apoyo [enabledToolIds] Parámetros filtrados por Asistente
 *
 * MCP Evaluación comparativa de protocolos:
 *  - tools/list: [listTools] / [listToolsAsToolDefinitions]
 *  - tools/call: [execute] / [executeFromJson]
 *  - Ampliación posterior:Cargar externo desde la configuración MCP server(stdio/sse,Phase 9)
 *
 * @param context Solicitud Context(Herramientas para requerir servicios del sistema.:Clipboard/UsageStats/Calendar)
 */
class ToolRegistry(
    private val context: Context,
    private val browserManager: BrowserManager = BrowserManager(context),
) {
    /** Definición de herramienta(UI para exhibición,parameters es el nombre del parámetro → Descripción).
     *
     * v1.???: Nuevo [parameterTypes] Admite la especificación de cada parámetro JSON Schema type,resolver
     * execute_javascript timeout_ms y otros números/Problema por el cual LLM transmite erróneamente parámetros booleanos como cadenas.
     * Predeterminado si no se especifica type=string,sigue con lo viejo [parameters] Compatibilidad conductual.
     */
    data class ToolDef(
        val name: String,
        val description: String,
        val parameters: Map<String, String>, // Nombre del parámetro → Descripción
        /** ¿Es necesario? */
        val required: Set<String> = emptySet(),
        /** Clasificación de herramientas(Se utiliza para la visualización del grupo UI):built-in / local / mcp. */
        val category: String = "built-in",
        /** Nombre del parámetro → JSON Schema type(Como por ejemplo integer / boolean),No se ha especificado ninguna cadena predeterminada. */
        val parameterTypes: Map<String, String> = emptyMap(),
        /** Nivel de riesgo de la herramienta,Se utiliza para el sistema de permisos de sesión. Sin valor predeterminado:Cada punto de construcción debe declararse explícitamente.,Falta una escritura significa que la compilación falla. */
        val riskLevel: ToolRiskLevel,
        /** Esquema JSON sin formato.MCP Las herramientas utilizan este campo para preservar la estructura completa de objetos anidados, matrices, enumeraciones, etc. */
        val rawParametersJsonSchema: String? = null,
    )

    /**
     * Función de ejecución de herramientas:parámetros map → Cadena de resultado.
     *
     * v1.134 P0-3: Cambiar para suspender el tipo de función,Elimine el antipatrón runBlocking de McpRegistry.
     * Las herramientas integradas están en su mayoría sincronizadas IO(ContentResolver Consulta/Lectura de LocationManager, etc.),
     * Agregar la palabra clave suspender no afecta la implementación interna,Simplemente haga que el tipo de referencia del método coincida;
     * MCP Las llamadas remotas se pueden ejecutar directamente en el contexto de la rutina.,El tiempo de espera utiliza withTimeoutOrNull en lugar del tiempo de espera del grupo de subprocesos.
     */
    private typealias ToolFn = suspend (Map<String, String>) -> String

    /** Herramientas necesarias para alojar los límites de la sesión,context Inyectado por el enlace de ejecución, el modelo no se puede falsificar. */
    typealias ContextToolFn = suspend (Map<String, String>, ToolExecutionContext) -> String

    /** v1.0.53: Función de ejecución de resultados estructurados.(Regresar [ToolOutcome]). */
    private typealias ToolOutcomeFn = suspend (Map<String, String>) -> ToolOutcome

    /** MCP/JSON Función de ejecución de herramientas,Conserve los tipos originales de parámetros numéricos, booleanos, de matriz y de objeto. */
    internal typealias JsonToolFn = suspend (JsonObject) -> String

    // M-TR2: Usar en su lugar ConcurrentHashMap,Garantizar la seguridad de registro/cancelación de registro/ejecución de concurrencia
    private val tools = ConcurrentHashMap<String, ToolFn>()
    private val contextTools = ConcurrentHashMap<String, ContextToolFn>()

    // v1.0.53: Canal de herramientas de resultados estructurados(tener prioridad sobre [tools] encontrar)
    private val outcomeTools = ConcurrentHashMap<String, ToolOutcomeFn>()
    private val jsonTools = ConcurrentHashMap<String, JsonToolFn>()
    private val toolDefs = ConcurrentHashMap<String, ToolDef>()

    /** A full device task must not interleave with another screen/device action from another session. */
    private val deviceInteractionMutex = Mutex()
    private val _revision = MutableStateFlow(0L)

    /**
     * v2.6.2(Auditoría de herramientas): Registrarse/Anule el registro del bloqueo en serie.
     *
     * Cada registro/cancelación del registro cambia múltiples mapas al mismo tiempo (tools/contextTools/outcomeTools/jsonTools/toolDefs)
     * Con _revisión; el hilo en ejecución puede ver“La definición ha sido cambiada, pero el antiguo controlador sigue ahí.”el estado intermedio,
     * _revision Los incrementos no atómicos también pueden perder incrementos. Todas las rutas de escritura comparten este bloqueo.
     */
    private val registerLock = Any()

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    // ver [listToolsAsToolDefinitions]: La lista de definiciones se almacena en caché por versión del registro.
    private val definitionsLock = Any()
    private var definitionsRevision = Long.MIN_VALUE
    private var definitionsAll: List<ToolDefinition> = emptyList()
    private val definitionsByFilter = LinkedHashMap<List<String>, List<ToolDefinition>>()

    /** Registro dinámico de herramientas/Cancele el registro de la versión de las páginas de herramientas y solicite actualizaciones de la suscripción al ensamblador. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * v2.x: Proveedor de estado de autorización del entorno de ejecución(Inyectado por AutomationInitializer;null = No cableado,Saltar verificación previa de permiso).
     */
    @Volatile
    var permissionStatusProvider: (() -> ToolPermissionStatus)? = null

    /**
     * v2.x Etapa de adelgazamiento de herramientas.4:Canal de descubrimiento de habilidades.
     *
     * Habilidades en [ToolCategories] No existe ninguna clasificación y el valor predeterminado se reduce al mismo calibre que OPCIONAL (consulte
     * [ToolExposurePolicy.filterToolsForRequest]); por lo tanto [FindToolsTool] Debe poder
     * Las habilidades también se buscan y cargan, de lo contrario se reducirán. = Reversión funcional.
     *
     * v2.5.9: de"único proveedor global"Cambiar a**Almacenar por sesión**. El conjunto de habilidades viene con asistente./Cambios de enlace de sesión,
     * El uso de un único campo global se sobrescribirá entre sí cuando las sesiones se intercalen, lo que hará que find_tools recupere habilidades de otras sesiones.
     */
    private val searchableSkillsBySession =
        java.util.concurrent.ConcurrentHashMap<String, List<ToolDef>>()

    /** Registra un directorio de habilidades que se pueden recuperar para una sesión (llamado por la capa de ensamblaje al comienzo de cada ronda). */
    fun setSearchableSkills(sessionId: String?, skills: List<ToolDef>) {
        val key = sessionId?.takeIf { it.isNotBlank() } ?: return
        if (skills.isEmpty()) searchableSkillsBySession.remove(key) else searchableSkillsBySession[key] = skills
    }

    /** Lea el directorio de habilidades que puede recuperar una sesión (vacío si no hay ningún registro). */
    fun searchableSkills(sessionId: String?): List<ToolDef> = sessionId?.let { searchableSkillsBySession[it] }.orEmpty()

    /** Borrar el directorio de habilidades de una sesión (llamado al final de la sesión para evitar residuos). */
    fun clearSearchableSkills(sessionId: String?) {
        sessionId?.let { searchableSkillsBySession.remove(it) }
    }

    /**
     * v2.6.1: Registrado por sesión“Dentro de la configuración de la herramienta auxiliar”Una colección de nombres de herramientas locales.
     *
     * ## por qué es necesario
     *
     * [FindToolsTool] Resultó que la búsqueda era [listTools] Registro completo y lista blanca de asistentes (toolIdsJson)
     * Totalmente fuera de contacto. Cuando la lista blanca de asistentes es un subconjunto reducido,find_tools Puede buscar y regresar“now available
     * from your next call”, pero la herramienta es ChatStreamCoordinator.resolveToolsAndModel
     * La etapa de filtrado de la lista blanca está cortada (y no hay vista de [SessionToolLoadRegistry]),
     * La próxima ronda de llamadas solo recibirá `tool 'X' is not exposed in this turn` —— Una promesa en blanco.
     * Después de registrar la lista blanca,find_tools Cargue únicamente herramientas que realmente puedan ingresar al esquema.
     *
     * null(No registrados, como rutas antiguas como subagentes)= Sin restricciones, manteniendo la compatibilidad con versiones anteriores.
     */
    private val visibleToolNamesBySession =
        java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** Registre el nombre de la herramienta local dentro de la configuración de la herramienta del asistente de sesión (llamada por la capa de ensamblaje al comienzo de cada ronda). */
    fun setVisibleTools(sessionId: String?, names: Set<String>) {
        val key = sessionId?.takeIf { it.isNotBlank() } ?: return
        if (names.isEmpty()) visibleToolNamesBySession.remove(key) else visibleToolNamesBySession[key] = names
    }

    /** Leer el nombre de la herramienta en la configuración de la herramienta del asistente de sesión;null Indica no registrado (sin restricciones). */
    fun visibleTools(sessionId: String?): Set<String>? = sessionId?.let { visibleToolNamesBySession[it] }

    /** Borre el conjunto de herramientas visibles para una sesión (llamado al final de la sesión para evitar residuos). */
    fun clearVisibleTools(sessionId: String?) {
        sessionId?.let { visibleToolNamesBySession.remove(it) }
    }

    /**
     * Entrada unificada para conteo de herramientas de tres velocidades (ver calibre [ToolCounts], ver las reglas [ToolSchemaRules]).
     *
     * Registro de inicio, índice de herramientas de Word rápido,find_tools El recuento se obtiene a partir de aquí: los tres números se corresponden entre sí.
     * Una capa de consumo real ya no necesita escribir un conjunto de implementaciones de detección.
     */
    fun countTools(
        sessionId: String?,
        criteria: ToolSchemaRules.Criteria = ToolSchemaRules.Criteria(),
        enabledSkills: List<io.zer0.muse.data.skill.SkillEntity> = emptyList(),
    ): ToolCounts = ToolCounter.compute(
        ToolCounter.Input(
            localToolDefs = listToolsAsToolDefinitions(),
            registeredTools = listTools(),
            sessionSkillDefs = searchableSkills(sessionId),
            sessionVisibleNames = visibleTools(sessionId),
            enabledSkills = enabledSkills,
            criteria = criteria,
        ),
    )

    // v1.136: Recordatorios periódicos, biblioteca de recursos
    // v1.0.17: Registro rápido para usar Room(MuseDb.get(context).quickNoteDao()),ya no se mantiene QuickNoteStore

    init {
        // Registrarse Juego incorporado de 7 piezas
        // JS caja de arena:Deje que la IA ejecute código JavaScript(cálculos matemáticos/Procesamiento de datos/algoritmo simple)
        // Se utiliza el motor V8 subyacente de WebView.,No hay nuevas grandes dependencias
        register(CodeExecutionTool.toolDef()) { args ->
            CodeExecutionTool.executeFromArgs(args, context)
        }

        // P1-C: Node Ejecución de script(Tiempo de ejecución de nodo incorporado,capacidad total;alto riesgo execute_javascript)
        register(NodeScriptTool.toolDef()) { args ->
            NodeScriptTool.executeFromArgs(args, context)
        }

        // P2-6: Conjunto de herramientas de automatización del navegador(navigate/click/type/extract/scroll_bottom/get_html)
        // Comparte la misma instancia que el singleton de BrowserManager registrado con Koin(Inyectado por AppToolModule),
        // Garantice la sincronización en tiempo real de las operaciones de las herramientas de IA y las cápsulas de estado de la interfaz de usuario.
        BrowserAutomationTool.toolDefs().forEach { def ->
            register(def) { args ->
                BrowserAutomationTool.executeFromArgs(def.name, args, browserManager)
            }
        }
    }

    /**
     * Phase 8.8: herramienta de registro(punto de extensión dinámica,Para carga posterior del servidor MCP).
     * @param def Definición de herramienta
     * @param fn Ejecutar función
     */
    fun register(def: ToolDef, fn: ToolFn) {
        synchronized(registerLock) {
            tools[def.name] = fn
            contextTools.remove(def.name)
            outcomeTools.remove(def.name)
            jsonTools.remove(def.name)
            toolDefs[def.name] = def
            bumpRevision()
        }
    }

    /** Registre herramientas que requieran límites de espacio/alcance de la sesión de alojamiento. */
    fun registerWithContext(def: ToolDef, fn: ContextToolFn) {
        synchronized(registerLock) {
            contextTools[def.name] = fn
            tools.remove(def.name)
            outcomeTools.remove(def.name)
            jsonTools.remove(def.name)
            toolDefs[def.name] = def
            bumpRevision()
        }
    }

    /**
     * v1.0.53: Regístrese para la herramienta de resultados estructurados(Regresar [ToolOutcome]).
     * Prioriza los canales String antiguos;Un registro de cadena con el mismo nombre sobrescribirá el canal anterior.
     */
    fun registerOutcome(def: ToolDef, fn: ToolOutcomeFn) {
        synchronized(registerLock) {
            outcomeTools[def.name] = fn
            tools.remove(def.name)
            contextTools.remove(def.name)
            jsonTools.remove(def.name)
            toolDefs[def.name] = def
            bumpRevision()
        }
    }

    /**
     * Registre una herramienta que conserve los parámetros JSON sin formato.
     *
     * MCP El esquema de entrada permite number / boolean / array / object parámetros;
     * ordinario [ToolFn] Para compatibilidad con las herramientas integradas, los parámetros se comprimirán en String,Por lo tanto, las herramientas MCP deben adoptar este canal.
     */
    internal fun registerJson(def: ToolDef, fn: JsonToolFn) {
        synchronized(registerLock) {
            jsonTools[def.name] = fn
            tools.remove(def.name)
            contextTools.remove(def.name)
            outcomeTools.remove(def.name)
            toolDefs[def.name] = def
            bumpRevision()
        }
    }

    /** Herramienta para cerrar sesión. */
    fun unregister(name: String) {
        synchronized(registerLock) {
            tools.remove(name)
            contextTools.remove(name)
            outcomeTools.remove(name)
            jsonTools.remove(name)
            toolDefs.remove(name)
            bumpRevision()
        }
    }

    // v1.0.53: Verificación del registro de clasificación de herramientas. — Cada herramienta integrada debe tener una categoría.
    // Sólo comprobar category="built-in"(MCP/Exención de herramienta complementaria);debug Lanzar una excepción cuando falta la compilación,release Simplemente inicie sesión.
    // Nota:No se puede ejecutar en ToolRegistry.init —— En ese momento, cada Registrador aún no se había registrado.,Sólo puedes ver los que vienen con esta categoría.
    // pocas herramientas,afirmar está vacío(Más de 20 herramientas faltaron en la lista y nadie se enteró).
    // por [ToolRegistrarBootstrapper.init](Después de crear instancias de todos los registradores)llamar.
    fun assertBuiltInCategoryCoverage() {
        val builtInNames = toolDefs.values.filter { it.category == "built-in" }.map { it.name }.toSet()
        val uncovered = ToolCategories.assertCoverage(builtInNames)
        if (uncovered.isNotEmpty()) {
            val msg = "Falta el registro de clasificación de herramientas integrado: ${uncovered.joinToString(", ")} (ver ToolCategories.kt)"
            if (io.zer0.muse.BuildConfig.DEBUG) {
                throw IllegalStateException(msg)
            } else {
                Logger.w("ToolRegistry", msg)
            }
        }
    }

    /** Listar todas las herramientas disponibles(Evaluación comparativa MCP tools/list). */
    fun listTools(): List<ToolDef> = toolDefs.values.toList()

    /**
     * Obtener el nivel de riesgo de un instrumento específico,Devuelve nulo si no está registrado.
     *
     * suministro [ToolPermissionResolver] Combinado con el modo de permiso de sesión para determinar si se requiere aprobación.
     */
    fun getToolRiskLevel(name: String): ToolRiskLevel? = toolDefs[name]?.riskLevel

    /**
     * Phase 8.8: Filtrar por lista de ID de herramienta(Para usar con un subconjunto de herramientas de encuadernación auxiliares).
     * @param enabledToolIds Lista de identificadores de herramientas habilitadas;null O una lista vacía significa habilitar todos
     */
    fun listTools(enabledToolIds: List<String>? = null): List<ToolDef> {
        val all = toolDefs.values.toList()
        if (enabledToolIds.isNullOrEmpty()) return all
        return all.filter { it.name in enabledToolIds }
    }

    /**
     * Phase 7: Genere una lista de definiciones de herramientas compatibles con OpenAI(suministro ChatService.streamChat(tools=...) uso).
     * Phase 8.8: apoyo [enabledToolIds] Filtrar por Asistente.
     * poner [ToolDef.parameters](Nombre del parámetro → Descripción) Convertir a cadena de esquema JSON.
     *
     * ## ¿Por qué almacenar en caché?
     *
     * Reconstruir una definición completa no es barato: con `rawParametersJsonSchema` herramientas para ser
     * `decodeFromString` Dos veces (una para verificación y otra para recuperación de valor) y luego `encodeToString` cadena de retorno,
     * El resto de herramientas van `buildJsonObject` Lucha parámetro por parámetro. Y esta ruta se llama con más frecuencia de lo que piensas:
     * Verificación de capacidad antes de cada llamada a la herramienta (`ToolRouteExecutionGuard.validateLocal`), cada ronda de solicitudes
     * Se ajustará para el ensamblaje (dos veces), las tareas programadas, las respuestas automáticas del canal y el recuento de herramientas en palabras breves. ——
     * Es equivalente a "cada vez que ajusta una herramienta, paga el costo de definición y reconstrucción de las más de 100 herramientas completas".
     *
     * La clave de caché es el número de versión del registro (se elimina cada vez que se registra o cancela el registro)+ Una lista de nombres para filtrar.
     * Se reconstruirá solo cuando cambie el número de versión y los resultados filtrados se guardarán en otra copia y se eliminarán de acuerdo con el límite superior del número.
     *
     * @param enabledToolIds Lista de identificadores de herramientas habilitadas;null O una lista vacía significa habilitar todos
     */
    fun listToolsAsToolDefinitions(enabledToolIds: List<String>? = null): List<ToolDefinition> = synchronized(definitionsLock) {
        val revision = _revision.value
        if (definitionsRevision != revision) {
            definitionsAll = buildToolDefinitions()
            definitionsRevision = revision
            definitionsByFilter.clear()
        }
        val all = definitionsAll
        if (enabledToolIds.isNullOrEmpty()) {
            all
        } else {
            definitionsByFilter.getOrPut(enabledToolIds.toList()) {
                if (definitionsByFilter.size >= MAX_FILTERED_DEFINITIONS) definitionsByFilter.clear()
                all.filter { it.name in enabledToolIds }
            }
        }
    }

    /** Reconstrucción completa [ToolDefinition] Lista (se toma solo cuando falta el caché). */
    private fun buildToolDefinitions(): List<ToolDefinition> = listTools().map { def ->
        val schema =
            def.rawParametersJsonSchema
                ?.takeIf { raw -> runCatching { AppJson.decodeFromString(JsonObject.serializer(), raw) }.isSuccess }
                ?.let { raw -> AppJson.decodeFromString(JsonObject.serializer(), raw) }
                ?: buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            def.parameters.forEach { (name, desc) ->
                                put(
                                    name,
                                    buildJsonObject {
                                        put("type", def.parameterTypes[name] ?: "string")
                                        put("description", desc)
                                    },
                                )
                            }
                        },
                    )
                    if (def.required.isNotEmpty()) {
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                def.required.map { JsonPrimitive(it) },
                            ),
                        )
                    }
                }
        ToolDefinition(
            name = def.name,
            description = def.description,
            parametersJsonSchema = AppJson.encodeToString(JsonObject.serializer(), schema),
        )
        // v1.0.4 Reparación HTTP 400 "Tool names must be unique":
        // Eliminar duplicados de forma defensiva por nombre,Incluso si ToolRegistry tiene varios registradores que registran herramientas con el mismo nombre, puede interceptarlos.
    }.distinctBy { it.name }

    /**
     * herramienta de ejecución(Evaluación comparativa MCP tools/call).
     *
     * v1.134 P0-3: Cambiar para suspender la función,con [ToolFn] Alineación de tipos.
     * La persona que llama no necesita hacerlo manualmente withContext(Dispatchers.IO) paquete(Pero no hace daño conservarlo.).
     *
     * @param name Nombre de la herramienta
     * @param args parámetros map
     * @return Cadena de resultado de ejecución;La herramienta no existe o los parámetros son incorrectos y se devuelve un mensaje de error.
     */
    suspend fun execute(name: String, args: Map<String, String>, cancellationToken: () -> Boolean = { false }): ToolOutcome =
        executeInternal(name, args, null, cancellationToken)

    /** Ejecutar herramientas con un contexto de host; Las herramientas que requieren límites de aislamiento deben utilizar esta entrada. */
    suspend fun execute(
        name: String,
        args: Map<String, String>,
        executionContext: ToolExecutionContext,
        cancellationToken: () -> Boolean = { false },
    ): ToolOutcome = executeInternal(name, args, executionContext, cancellationToken)

    private suspend fun <T> withDeviceInteractionLock(name: String, block: suspend () -> T): T = if (requiresDeviceInteractionLock(name)) {
        deviceInteractionMutex.withLock { block() }
    } else {
        block()
    }

    private suspend fun executeInternal(
        name: String,
        args: Map<String, String>,
        executionContext: ToolExecutionContext?,
        cancellationToken: () -> Boolean,
    ): ToolOutcome = withDeviceInteractionLock(name) {
        executeInternalUnlocked(name, args, executionContext, cancellationToken)
    }

    private suspend fun executeInternalUnlocked(
        name: String,
        args: Map<String, String>,
        executionContext: ToolExecutionContext?,
        cancellationToken: () -> Boolean,
    ): ToolOutcome {
        jsonTools[name]?.let { fn ->
            return executeJson(name, stringArgsToJson(args), fn, cancellationToken)
        }
        // v1.0.53: Reglas de seguridad a nivel de contenido(Límites estrictos previos a la ejecución,No afectado por el modo de aprobación)
        // Pase la lista de parámetros en lugar de la cadena ensamblada: las reglas de ruta solo deben mirar la ruta y no pueden hacer coincidir el texto.
        ContentSafetyRules.checkArgs(name, args)?.let { rule ->
            Logger.w("ToolRegistry", "Se cumplió la regla de seguridad de contenido: ${rule.ruleId} (tool=$name)")
            return ToolOutcome.error("${rule.reason}(${rule.ruleId})")
        }
        // v1.0.53: cancelar token(Configuración del tiempo de parada de generación true,Las herramientas largas regresan lo antes posible)
        if (cancellationToken()) {
            return ToolOutcome.error("Llamada de herramienta cancelada")
        }
        // P1: Verificación del esquema de parámetros de la herramienta — desaparecido/Los errores de tipo se interceptan antes de la ejecución.,
        // Devuelve un error estructurado a LLM,Evite fallos de herramientas silenciosos o 400 ascendentes.
        val def = toolDefs[name]
        val validation = ToolArgValidator.validate(name, args, def)
        if (!validation.valid) {
            val detail = validation.errors.joinToString("; ")
            Logger.w("ToolRegistry", "Error en la verificación de parámetros de herramienta: $name -> $detail")
            return ToolOutcome.error(
                detail,
                mapOf("errorType" to ToolArgValidator.ERROR_TYPE, "tool" to name, "errors" to validation.errors),
            )
        }
        val validArgs = validation.coercedArgs
        // v2.x: Ejecutar verificación previa de permisos del entorno — Falle rápidamente con un error estructurado cuando el canal requerido no esté listo,
        // En lugar de que la herramienta regrese internamente"Semánticamente exitoso, el contenido no está disponible."resultados engañosos.
        val permissionRequirements = ToolCategories.permissionOf(name)
        if (permissionRequirements.isNotEmpty()) {
            val status = permissionStatusProvider?.invoke()
            if (status != null && !status.satisfiedAny(permissionRequirements)) {
                return ToolOutcome.error(
                    "Herramientas $name necesitar ${status.describe(permissionRequirements)} Autorizar,Actualmente no está listo;" +
                        "Primero complete la autorización(ajustes→Asistente de configuración de permisos)O utilice herramientas alternativas en su lugar.",
                    mapOf(
                        "errorType" to "permission_required",
                        "tool" to name,
                        "required" to permissionRequirements.map { it.name },
                    ),
                )
            }
        }
        contextTools[name]?.let { fn ->
            val executionContext =
                executionContext
                    ?: return ToolOutcome.error("Herramientas $name Requiere contexto de ejecución de sesión")
            return resultOf { fn(validArgs, executionContext) }
                .onError { msg, _ -> Logger.w("ToolRegistry", "Herramientas $name excepción de ejecución: $msg") }
                .getOrNull()?.let { ToolOutcome.ok(it) }
                ?: ToolOutcome.error(this.context.getString(R.string.tool_exec_exception))
        }
        // v1.0.53: Canal estructurado prioritario
        outcomeTools[name]?.let { fn ->
            return resultOf { fn(validArgs) }
                .onError { msg, _ -> Logger.w("ToolRegistry", "Herramientas $name excepción de ejecución: $msg") }
                .getOrNull() ?: ToolOutcome.error(context.getString(R.string.tool_exec_exception))
        }
        val fn =
            tools[name]
                ?: return ToolOutcome.error(context.getString(R.string.tool_not_found, name, tools.keys.joinToString(", ")))
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        return resultOf { fn(validArgs) }
            .onError { msg, _ -> Logger.w("ToolRegistry", "Herramientas $name excepción de ejecución: $msg") }
            .getOrNull()?.let { ToolOutcome.ok(it) }
            ?: ToolOutcome.error(context.getString(R.string.tool_exec_exception))
    }

    /**
     * Phase 7: La cadena JSON de argumentos devuelta por LLM ejecuta la herramienta.
     * @param name Nombre de la herramienta
     * @param argumentsJson LLM Cadena JSON del parámetro devuelto(Como por ejemplo {"expression":"1+2*3"})
     * @return Cadena de resultado de ejecución
     */
    suspend fun executeFromJson(name: String, argumentsJson: String): String = executeFromJsonInternal(name, argumentsJson, null)

    suspend fun executeFromJson(name: String, argumentsJson: String, executionContext: ToolExecutionContext): String =
        executeFromJsonInternal(name, argumentsJson, executionContext)

    private suspend fun executeFromJsonInternal(name: String, argumentsJson: String, executionContext: ToolExecutionContext?): String {
        // M-TR1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        val obj =
            resultOf {
                parseArgumentsLenient(argumentsJson)
            }.onError { msg, _ ->
                Logger.w(
                    "ToolRegistry",
                    "executeFromJson Falló el análisis de parámetros: $msg " +
                        ToolDataPrivacy.safeArgumentsForPreview(name, argumentsJson),
                )
            }.getOrNull() ?: return context.getString(
                R.string.tool_param_parse_failed,
                ToolDataPrivacy.safeArgumentsForPreview(name, argumentsJson),
            )

        jsonTools[name]?.let { fn ->
            val outcome = withDeviceInteractionLock(name) { executeJson(name, obj, fn) }
            val content =
                outcome.content.ifBlank {
                    context.getString(R.string.tool_exec_empty_result, name)
                }
            return if (outcome.isError && !content.startsWith("Error:", ignoreCase = true)) {
                "Error: $content"
            } else {
                content
            }
        }

        val args =
            resultOf {
                obj.entries.associate { (k, v) -> k to jsonArgValue(v) }
            }.onError { msg, _ ->
                Logger.w(
                    "ToolRegistry",
                    "executeFromJson Error en la conversión de parámetros: $msg " +
                        ToolDataPrivacy.safeArgumentsForPreview(name, argumentsJson),
                )
            }.getOrNull() ?: return context.getString(
                R.string.tool_param_parse_failed,
                ToolDataPrivacy.safeArgumentsForPreview(name, argumentsJson),
            )
        // v1.0.53: execute Regresar ToolOutcome,Tome contenido para mantener la semántica de cadenas.
        // Las cadenas vacías no pueden seguir propagándose en sentido ascendente,De lo contrario, la tarjeta de herramientas sólo puede mostrar“ejecutando”Y no hay un estado final.
        val outcome =
            if (executionContext != null) {
                execute(name, args, executionContext)
            } else {
                execute(name, args)
            }
        val content =
            outcome.content.ifBlank {
                context.getString(R.string.tool_exec_empty_result, name)
            }
        return if (outcome.isError && !content.startsWith("Error:", ignoreCase = true)) {
            "Error: $content"
        } else {
            content
        }
    }

    private suspend fun executeJson(
        name: String,
        rawArgs: JsonObject,
        fn: JsonToolFn,
        cancellationToken: () -> Boolean = { false },
    ): ToolOutcome {
        val stringArgs = rawArgs.entries.associate { (k, v) -> k to jsonArgValue(v) }
        ContentSafetyRules.checkArgs(name, stringArgs)?.let { rule ->
            Logger.w("ToolRegistry", "Se cumplió la regla de seguridad de contenido: ${rule.ruleId} (tool=$name)")
            return ToolOutcome.error("${rule.reason}(${rule.ruleId})")
        }
        if (cancellationToken()) return ToolOutcome.error("Llamada de herramienta cancelada")
        // v2.6.2(Auditoría de herramientas): JSON/MCP El canal agrega verificación previa de permisos ambientales —— La implementación anterior sólo hace esto en rutas normales,
        // Usa la misma herramienta execute(Map) con executeFromJson() Obtendrá diferentes resultados de permisos.
        // JSON Las herramientas de registro evitan las comprobaciones de preparación del canal.
        val permissionRequirements = ToolCategories.permissionOf(name)
        if (permissionRequirements.isNotEmpty()) {
            val status = permissionStatusProvider?.invoke()
            if (status != null && !status.satisfiedAny(permissionRequirements)) {
                return ToolOutcome.error(
                    "Herramientas $name necesitar ${status.describe(permissionRequirements)} Autorizar,Actualmente no está listo;" +
                        "Primero complete la autorización(ajustes→Asistente de configuración de permisos)O utilice herramientas alternativas en su lugar.",
                    mapOf(
                        "errorType" to "permission_required",
                        "tool" to name,
                        "required" to permissionRequirements.map { it.name },
                    ),
                )
            }
        }
        val def = toolDefs[name]
        val validation = ToolArgValidator.validate(name, stringArgs, def)
        if (!validation.valid) {
            val detail = validation.errors.joinToString("; ")
            Logger.w("ToolRegistry", "Error en la verificación de parámetros de herramienta: $name -> $detail")
            return ToolOutcome.error(
                detail,
                mapOf("errorType" to ToolArgValidator.ERROR_TYPE, "tool" to name, "errors" to validation.errors),
            )
        }
        return try {
            ToolOutcome.ok(fn(rawArgs))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.w("ToolRegistry", "JSON Herramientas $name excepción de ejecución: ${e.message}")
            ToolOutcome.error(context.getString(R.string.tool_exec_exception))
        }
    }

    private fun stringArgsToJson(args: Map<String, String>): JsonObject = buildJsonObject {
        args.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }

    /**
     * Convierta un valor JSON de un parámetro de herramienta en una cadena que se pasa de forma transparente a la herramienta, conservando su semántica original.
     *
     * - Para primitivas de cadena [JsonPrimitive.content] tomar**después de escapar**el texto de
     *   (`"{\"a\":1}"` → `{"a":1}`, el modelo a menudo pasa objetos anidados a JSON de escape según el esquema de cadena);
     * - objeto/Matriz retenida [JsonElement.toString] Texto JSON legal.
     *
     * Para implementación anterior `toString().trim('"')`: Sólo las comillas más externas se eliminan de la cadena y las comillas internas se eliminan de la cadena. `\"` Escapar no se puede restaurar,
     * Como resultado, falla el análisis de parámetros de objetos anidados (por ejemplo, render_data informa "data Debe ser un objeto JSON").
     */
    private fun jsonArgValue(element: JsonElement): String = (element as? JsonPrimitive)?.content ?: element.toString()

    /**
     * v1.x: Parámetros de la herramienta de análisis de Fault Tolerance JSON.
     *
     * modelo(Especialmente el modelo de razonamiento de las estaciones de tránsito en modo de pensamiento profundo.)Puede generar parámetros con formato incorrecto:
     *  - Concatenar múltiples objetos JSON,Como por ejemplo `{}{\"selector\": \"h1\"}` o `{\"selector\": \"h1\"}{}`
     *  - Espacio en blanco adicional antes y después
     *
     * Estrategia:Análisis estándar primero;En caso de error, divida todos los fragmentos mediante el par de llaves más externo,Analizar y fusionar uno por uno
     * (Este último anula la clave del mismo nombre.),Se devolverá si algún fragmento se analiza correctamente.
     */
    internal fun parseArgumentsLenient(json: String): JsonObject {
        // 1. Análisis estándar
        runCatching { AppJson.decodeFromString(JsonObject.serializer(), json) }
            .getOrNull()?.let { return it }
        // 2. tolerancia a fallas:Presione la capa más externa {} par dividido
        val fragments = mutableListOf<String>()
        var depth = 0
        var start = -1
        for (i in json.indices) {
            when (json[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            fragments += json.substring(start, i + 1)
                            start = -1
                        }
                    }
                }
            }
        }
        if (fragments.size > 1) {
            val merged =
                buildJsonObject {
                    fragments.forEach { frag ->
                        runCatching { AppJson.decodeFromString(JsonObject.serializer(), frag) }
                            .getOrNull()?.forEach { (k, v) -> put(k, v) }
                    }
                }
            if (merged.isNotEmpty()) return merged
        }
        // 3. Ten todo en cuenta:Trate el contenido original como un parámetro de cadena.(key = el primer parámetro declarado o "value")
        return buildJsonObject {
            put("value", JsonPrimitive(json.trim()))
        }
    }

    // ── Implementación de herramientas incorporadas ──────────────────────────────────────────────────────────

    /** Phase 8.8: Leer el texto del portapapeles del sistema. */

    // ── v1.136: Herramientas de la biblioteca de recursos ───────────────────────────────────────────────────

    // ── v1.136: red/codificación/TTS Herramientas ──────────────────────────────────────────

    /** Ping Nombre de dominio o IP especificado. */

    /** TTS Leer en voz alta la implementación(Suspender hasta que la inicialización esté completa y en cola). */

    companion object {
        /** ver [listToolsAsToolDefinitions]: Límite superior de la caché de resultados del filtro. */
        private const val MAX_FILTERED_DEFINITIONS = 8

        /**
         * Tools that observe or mutate the shared foreground device UI. They are serialized across
         * sessions so screenshot -> decision -> action loops and durable workflows cannot interleave.
         */
        internal fun requiresDeviceInteractionLock(name: String): Boolean =
            name in DEVICE_INTERACTION_TOOL_NAMES || name.startsWith("screen_") || name.startsWith("ui_")

        internal val DEVICE_INTERACTION_TOOL_NAMES = setOf(
            "ui_agent", "automation_workflow", "device_shell",
            "screen_read", "screen_current_app", "screen_back", "screen_home", "screen_tap", "screen_tap_text",
            "screen_swipe", "screen_pinch", "screen_swipe_path", "screen_input", "screen_launch_app",
            "screen_open_notifications", "screen_wait",
            "virtual_screen", "virtual_screen_input",
            "ui_get_page_info", "ui_click", "ui_long_press", "ui_swipe", "ui_set_text", "ui_screenshot",
            "ui_back", "ui_home", "ui_global_action", "ui_get_current_app",
            "input_inject", "am_start", "network_toggle", "settings_put",
            "app_launch", "app_settings", "app_force_stop", "app_clear_data", "app_uninstall",
            "open_app", "open_system_setting", "share_text", "send_sms", "send_email", "add_contact",
            "set_alarm", "set_timer", "toggle_wifi", "toggle_bluetooth", "open_url", "make_phone_call", "open_maps",
        )

        // v1.95: Lista de todas las identificaciones de herramientas integradas(Correspondencia uno a uno con las herramientas registradas en el bloque de inicio)
        // Para lectura estática por AssistantRepository.ensureDefaultExists,No se requiere ninguna instancia de ToolRegistry
        val BUILT_IN_TOOL_IDS: List<String> =
            listOf(
                "get_weather", "get_current_time", "calculator", "echo", "clipboard_read", "clipboard_write",
                "screen_time", "calendar_today", "add_calendar_event",
                "set_alarm", "set_timer", "open_app", "share_text", "get_location",
                "get_device_info", "get_contacts_count", "get_contacts_list",
                "send_sms", "add_contact",
                "open_system_setting", "toggle_wifi", "toggle_bluetooth", "send_email",
                "get_battery_info", "get_recent_notifications",
                "open_url", "list_installed_apps", "get_network_info",
                // v1.136: Agregar nuevo sistema/Equipo/herramientas de codificación
                "get_storage_info", "get_memory_info", "get_display_info", "get_cpu_info", "get_sensors_list",
                "get_brightness", "set_brightness", "get_volume", "set_volume", "toggle_flashlight", "vibrate",
                "get_foreground_app", "get_wifi_info", "get_bluetooth_devices", "make_phone_call", "open_maps",
                "url_encode", "url_decode", "base64_encode", "base64_decode", "hash_text", "generate_uuid", "random_number",
                // v1.136: Recordatorios programados y herramientas de biblioteca de recursos
                "schedule_reminder", "cancel_reminder", "list_reminders",
                "resource_add", "resource_list", "resource_search", "resource_get", "resource_delete",
                // v1.136: herramienta de grabación rápida(quick_note_search Sin conexión,ya no está expuesto)
                "quick_note_add", "quick_note_list", "quick_note_get",
                "quick_note_update", "quick_note_delete", "quick_note_pin",
                // v1.136: red/codificación/TTS Herramientas
                "ping_host", "dns_lookup", "get_public_ip", "json_pretty", "generate_password", "speak_text",
                // v1.135: herramientas de generación de medios(ChatViewModel Registrarse,Regístrese aquí para la activación del asistente predeterminado)
                "generate_image", "generate_video", "generate_qr_code",
                // v2.x: El asistente envía un mensaje de voz(MediaGenToolsRegistrar Registrarse,El mismo enlace de entrega de medios que Shengtu.)
                "send_voice",
                // v1.95: Herramienta de biblioteca de emoticonos(SkillExecutor darse cuenta,Regístrate aquí para facilitar la identificación unificada)
                "list_stickers", "send_sticker",
                // Complementar las herramientas integradas
                "pin_memory", "unpin_memory", "save_memory", "delete_memory",
                "memory_config_get", "memory_config_set",
                "recall_experience", "record_experience",
                "todo_write", "show_card", "render_data", "notify", "current_status",
                // v2.x: token Autoevaluación de consumo y contexto de ocupación (AgentToolsRegistrar Registrarse)
                "get_token_usage",
                // v2.0: Enlace de datos de tarjeta(AgentToolsRegistrar Registrarse)
                "update_card_data",
                "subagent_task",
                // JS Herramientas de caja de arena(WebView evaluateJavascript,CodeExecutionTool darse cuenta)
                "execute_javascript",
                // P1-C: Node Ejecución de script(Tiempo de ejecución de nodo incorporado)
                "execute_node_script",
                // P2-6: Herramientas de automatización del navegador(BrowserAutomationTool darse cuenta,headless WebView)
                "browser_navigate", "browser_click", "browser_type",
                "browser_extract", "browser_scroll_bottom", "browser_get_html",
                "browser_snapshot",
                // v1.0.92: Herramientas del canal de mensajes(Envío de mensajería instantánea externa,ChannelToolsRegistrar Registrarse)
                "send_channel_message", "relay_channel_message", "channel_list",
                // v2.0: OAuth herramienta conectora(ConnectorToolsRegistrar Registrarse)
                "connector_list", "call_connector",
                // P2-7: Herramienta de gestión de archivos del espacio de trabajo(WorkspaceToolsRegistrar Registrarse)
                "workspace_list", "workspace_read", "workspace_write",
                "workspace_edit", "workspace_delete", "workspace_mkdir", "workspace_move",
                "workspace_copy", "workspace_mounts",
                "workspace_grep",
                // v1.0.17: Herramienta de tareas programadas(El asistente puede crear/Administrar tareas programadas)
                "scheduled_task_create", "scheduled_task_list", "scheduled_task_update",
                "scheduled_task_delete", "scheduled_task_execute", "scheduled_task_get_history",
                // v1.0.17: herramienta de traducción(Alineado con la habilidad de traducción de SkillExecutor,ir juntos ToolRegistry)
                "translate",
                // MCP Herramientas de gestión(McpRegistry Registrarse,Los asistentes se pueden crear directamente/vinculante/Eliminar servidor MCP).
                // Entre ellos configurar/eliminar pertenece a "TRUSTED "También aprobación obligatoria" establecida y es de ALTO riesgo,
                // ASK/STRICT La próxima vez abriré la tarjeta de aprobación.;URL También existe una verificación de exportación de la SSRF.
                "mcp_mgmt_list", "mcp_mgmt_configure", "mcp_mgmt_remove",
                "mcp_mgmt_bind_assistant", "mcp_mgmt_reconnect",
                // Archivo/enlace/Documentación(Registrado pero no incluido previamente en la lista blanca,No se puede acceder al asistente predeterminado)
                "read_file", "create_download", "parse_link", "parse_pdf",
                // v2.6.7 fix (AUDIT F4): Este lote de nuevas herramientas no se ha registrado en la lista blanca incorporada.
                // Como resultado, no están incluidos en el toolIdsJson del asistente predeterminado. resolveToolsAndModel
                // la etapa se filtra,find_tools No hay manera de evitarlo.
                "read_user_file",
                "remember_preference",
                // Deseo de recuperación de memoria/subagente/mensaje activo(Igual que arriba)
                "search_memory", "search_conversation", "subagent_run", "subagent_close", "proactive_message_wish",
                // v2.x: Búsqueda de biblioteca de herramientas bajo demanda(Canales de finalización jerárquicamente reducidos)
                "find_tools",
                // v2.x: Mercado de complementos autogestionados(Buscar/Instalación/Desinstalar/Iniciar y detener — Herramienta registrada,Complemente la lista blanca para que el asistente predeterminado sea accesible)
                "plugin_market_search", "plugin_market_install",
                "plugin_market_uninstall", "plugin_market_set_enabled",
                // v2.x Automatización Fase I:Canal de línea de comando del dispositivo(Shizuku/Root Ejecución jerárquica)
                "device_shell",
                // v2.x fase terminal uno:Aplicar comandos de terminal sandbox
                "terminal_exec",
                // v2.2.1: Termux canal(Comandos completos del entorno Linux,alto riesgo)
                "termux_exec",
                // v2.2.1: GUI Agent anillo(Manipulación de pantalla de varios pasos impulsada por la visión,alto riesgo)
                "ui_agent",
                // v2.6.2: Conjunto de herramientas de control de aplicaciones (AppControlTools Regístrate, te perdiste el registro antes → El asistente predeterminado no es accesible)
                "app_list", "app_launch", "app_settings",
                "app_force_stop", "app_clear_data", "app_uninstall",
                // v2.6.2: Gestión de base de conocimientos + gestión de sesiones (KnowledgeManageTools / SessionManageTools)
                "knowledge_list_docs", "knowledge_delete_doc", "knowledge_create_kb", "knowledge_delete_kb",
                "knowledge_read_doc",
                "session_list", "session_read", "session_rename", "session_move_folder", "session_archive", "session_delete",
                // v2.6.2: Gestión de habilidades + autogestión asistente (SkillManageTools / AssistantManageTools)
                "skill_manage_list", "skill_manage_toggle", "skill_manage_delete",
                "assistant_manage_list", "assistant_manage_create", "assistant_manage_update_persona", "assistant_manage_delete",
                // v2.0: Root-level system tools
                "settings_get", "settings_put", "am_start", "list_packages", "logcat_tail", "input_inject",
                // v2.x Tiempo de ejecución extendido(P0):Autoprueba integrada de la zona de pruebas de Node(solo lectura,Entrada de verificación)
                "runtime_selfcheck",
                // v2.7 Tiempo de ejecución extendido(Python):Python Ejecución de guiones.
                // Me perdí el registro antes y no está en la lista blanca de toolIdsJson del asistente predeterminado.
                // Se filtra en la etapa resolveToolsAndModel y el modelo no puede ver esta herramienta en absoluto.,
                // Solo puede ir al shell/nodo para encontrar el intérprete (el usuario ha estado inactivo repetidamente).
                "execute_python_script",
            )

        /**
         * v1.131: Tool Formateadores de fecha de uso común — ThreadLocal caché,Evite crear un nuevo SimpleDateFormat con cada llamada a la herramienta LLM.
         * LLM Herramientas de llamadas de alta frecuencia(getCalendarEvents / parseDateTime / getNotifications espera),
         * La implementación anterior siempre `SimpleDateFormat(pattern, Locale).format(...)` Provoca estrés en GC.
         * SimpleDateFormat No es seguro para subprocesos,Utilice ThreadLocal para garantizar instancias independientes por subproceso.
         */
        private val FMT_DATE = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

        /** parseDateTime Todos los formatos probados(Lista segura de subprocesos,SimpleDateFormat interno aislado a través de ThreadLocal). */
        private val PARSE_FORMATS_PATTERNS =
            listOf(
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd",
            )
        private val PARSE_FORMATS_TL =
            ThreadLocal.withInitial {
                PARSE_FORMATS_PATTERNS.map { SimpleDateFormat(it, Locale.getDefault()) }
            }
    }
}
