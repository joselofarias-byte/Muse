package io.zer0.muse.tools

import android.content.Context
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.experience.ExperienceRepository
import io.zer0.muse.data.subagent.SubagentThreadStore
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CoroutineScope

/**
 * Agent Registro de herramientas (implementado a partir del sistema de herramientas existente).
 *
 * Registre todas las herramientas implementadas desde el sistema de herramientas existente en ToolRegistry existente:
 *  - pin_memory / unpin_memory(Phase 1C)
 *  - memory_config_get / memory_config_set(Leer y escribir parámetros del sistema de memoria)
 *  - recall_experience / record_experience(Phase 3B)
 *  - search_memory(Phase 1 v6)
 *  - save_memory / delete_memory(v2.x: Banco de memoria autogestionado — escritura explícita/eliminar)
 *  - todo_write(Phase 4A)
 *  - show_card(Phase 4B)
 *  - notify(Phase 4C)
 *  - current_status(Phase 4D)
 *  - subagent_task(Phase 5A / v1.202: launch+reply+close conjunto de tres piezas)
 *  - subagent_run(v1.0.52 P2-1: Niño independiente de bloqueo sincrónico agent,Ciclo completo de herramienta + protocolo XML)
 *  - find_tools(v2.x: Búsqueda de biblioteca de herramientas bajo demanda — Canales de finalización jerárquicamente reducidos,El golpe está cargado (visible en rondas posteriores)
 *  - get_token_usage(v2.x: token Autoexamen de consumo y contexto de ocupación)
 */
// Este registrador agrega múltiples dependencias de dominio de herramientas. La división requiere un ajuste simultáneo del ciclo de vida del registro de la herramienta y se reservan temporalmente exenciones parciales.
@Suppress("LongParameterList")
class AgentToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val pinnedMemoryStore: PinnedMemoryStore,
    private val experienceRepository: ExperienceRepository,
    private val factStore: FactStore,
    /** Seleccione el fact.db correspondiente según el asistenteId del contexto de ejecución; si está vacío, recurra al archivo principal Facts.db. */
    private val factDbProvider: FactDbProvider? = null,
    private val settings: SettingsRepository,
    private val notificationManager: MuseNotificationManager,
    private val context: Context,
    // v1.202: SubagentTool dependencias requeridas — Ejecución delegada/gestión de subprocesos/comentarios de resultados asincrónicos/antecedentes Scope
    private val skillExecutor: SkillExecutor,
    private val subagentThreadStore: SubagentThreadStore,
    private val deferredResultStore: DeferredResultStore,
    private val appScope: CoroutineScope,
    // v1.0.52 P2-1: SubagentRunSkill requerido — Corredor de agente infantil independiente de bloqueo sincrónico
    private val subagentRunner: SubagentRunner,
    // v2.0: Enlace de datos de tarjeta — show_card Escritura de datos + herramienta update_card_data
    private val cardDataStore: io.zer0.muse.data.card.CardDataStore? = null,
    /**
     * v2.6.7: tool_status Necesita leer el estado de preparación del canal de tres capas.
     *
     * null para no registrar la herramienta (entorno de prueba o host con módulos de automatización no ensamblados)——
     * Es mejor no proporcionarlo que proporcionar un producto falso que no se puede obtener en su estado original.
     */
    private val automationManager: io.zer0.muse.automation.core.AutomationManager? = null,
    /** v2.6.9: Preocupado por el libro mayor —— note_thought Las herramientas están escritas para impulsar la programación proactiva de mensajes. */
    private val proactiveThoughtStore: io.zer0.muse.data.proactive.ProactiveThoughtRepository? = null,
    /** v2.x: token Autocontrol de dosis — El registro de uso es un puerto de sólo lectura; cuando es nulo, el informe de la herramienta no está disponible y no se utilizan datos vacíos para fingir. */
    private val usageRecordDao: io.zer0.muse.data.stats.UsageRecordDao? = null,
    /** Este lector de uso de sesión (el flujo de uso no tiene una dimensión de sesión, este número se toma del campo medido de la línea de mensaje). */
    private val sessionUsageReader: (suspend (String) -> TokenUsageTool.SessionUsage?)? = null,
    /** El contexto actual ocupa (ocupa, Límite superior); vinculado por ChatViewModel, toda la línea en el lado de la herramienta se omite cuando no está vinculado. */
    private val contextOccupancy: (() -> Pair<Int, Int>?)? = null,
    /** facts Compile el resumen de reserva cuando no se pueda encontrar la tabla; cuando search_memory está vacío, solo se informan los resultados de la tabla de hechos. */
    private val compiledSummarySearch: SearchMemoryTool.CompiledSummarySearch? = null,
) {
    init {
        registerAll()
    }

    /**
     * Regístrese en la herramienta de contabilidad de cuidados.
     *
     * Cuando el libro mayor no está listo, también le da al modelo una respuesta verdadera en lugar de generar una excepción:
     * La lista de herramientas se comparte globalmente y es normal que una herramienta no esté disponible en un host específico.
     * La función de extracción separada se debe a que la complejidad ciclomática de RegisterAll ya está cerca del límite superior.
     * Si empujas otra rama hacia adentro, explotará.
     */
    private fun registerNoteThought() {
        toolRegistry.registerWithContext(NoteThoughtTool.toolDef()) { args, executionContext ->
            proactiveThoughtStore?.let { store ->
                NoteThoughtTool.execute(args, store, executionContext)
            } ?: "Error: El libro mayor no está listo"
        }
    }

    /**
     * token Autocontrol de dosis (get_token_usage).
     *
     * Tres fuentes de datos se degradan de forma independiente: falta el flujo de uso → Informado explícitamente como no disponible; falta el lector de sesión → Sólo falta una línea;
     * Sondeo de contexto no vinculado (tarea en segundo plano/conversación no abierta)→ Se omite toda la línea. Ninguno será programado a 0.
     */
    private fun registerTokenUsage() {
        toolRegistry.registerWithContext(TokenUsageTool.toolDef()) { args, executionContext ->
            TokenUsageTool.execute(
                args = args,
                context = executionContext,
                dao = usageRecordDao,
                sessionUsage = sessionUsageReader,
                contextOccupancy = contextOccupancy,
            )
        }
    }

    fun registerAll() {
        // v2.6.7: Resumen de disponibilidad de herramientas —— Dígale al modelo una vez "lo que realmente se puede utilizar ahora".
        // Autoexamen del asistente: "206 una herramienta y ningún mapa”. No se trata de más herramientas, se trata de límites.
        automationManager?.let { manager ->
            toolRegistry.register(ToolStatusTool.toolDef()) { args ->
                ToolStatusTool.execute(args, toolRegistry, manager)
            }
        }

        // Phase 1C: Memoria de pines
        toolRegistry.registerWithContext(PinMemoryTool.toolDef()) { args, executionContext ->
            PinMemoryTool.execute(args, pinnedMemoryStore, executionContext.assistantId)
        }
        // Parámetros del sistema de memoria: anteriormente, solo se podía leer y escribir contenido, y los parámetros solo se podían arrastrar manualmente a la página de configuración.
        toolRegistry.registerWithContext(MemoryConfigTool.getDef()) { _, _ ->
            MemoryConfigTool.get(settings)
        }
        toolRegistry.registerWithContext(MemoryConfigTool.setDef()) { args, _ ->
            MemoryConfigTool.set(args, settings)
        }
        toolRegistry.registerWithContext(UnpinMemoryTool.toolDef()) { args, executionContext ->
            UnpinMemoryTool.execute(args, pinnedMemoryStore, executionContext.assistantId)
        }

        // Phase 3B: herramienta de experiencia
        toolRegistry.register(RecallExperienceTool.toolDef()) { args ->
            RecallExperienceTool.execute(args, experienceRepository)
        }
        toolRegistry.register(RecordExperienceTool.toolDef()) { args ->
            RecordExperienceTool.execute(args, experienceRepository)
        }

        // Phase 1 v6: Búsqueda de memoria a largo plazo
        toolRegistry.registerWithContext(SearchMemoryTool.toolDef()) { args, executionContext ->
            val scopedStore = factDbProvider?.getFactStore(executionContext.assistantId ?: "default") ?: factStore
            SearchMemoryTool.execute(args, scopedStore, executionContext, compiledSummarySearch)
        }

        // v2.x: Escritura explícita del banco de memoria./Eliminar — Capacidad de memoria de autogestión del asistente.(Alcance/contexto de ejecución espacial,Inolvidable)
        toolRegistry.registerWithContext(SaveMemoryTool.toolDef()) { args, executionContext ->
            val scopedStore = factDbProvider?.getFactStore(executionContext.assistantId ?: "default") ?: factStore
            SaveMemoryTool.execute(args, scopedStore, executionContext)
        }
        toolRegistry.registerWithContext(DeleteMemoryTool.toolDef()) { args, executionContext ->
            val scopedStore = factDbProvider?.getFactStore(executionContext.assistantId ?: "default") ?: factStore
            DeleteMemoryTool.execute(args, scopedStore, executionContext)
        }

        // v2.x escenario3:Búsqueda de biblioteca de herramientas bajo demanda(Golpear y cargar,Visible para rondas posteriores;cargar ≠ liberar)
        toolRegistry.registerWithContext(FindToolsTool.toolDef()) { args, executionContext ->
            FindToolsTool.execute(args, toolRegistry, executionContext)
        }

        // v2.x fase terminal uno:Aplicar comandos de terminal sandbox(directorio de trabajo = Espacio de trabajo de la aplicación)
        toolRegistry.register(TerminalExecTool.toolDef()) { args ->
            TerminalExecTool.execute(
                args,
                java.io.File(context.filesDir, "workspace").apply { mkdirs() },
                java.io.File(context.filesDir, TOOL_OUTPUTS_DIR),
            )
        }
        // v2.2.1 Termux canal:Ejecución completa de comandos del entorno Linux(Los usuarios deben instalar Termux y autorizarlo.;Volver al inicio de configuración cuando no esté listo)
        toolRegistry.register(TermuxExecTool.toolDef()) { args ->
            TermuxExecTool.execute(args, context)
        }

        // en chat normal“pide un deseo”: Vincula el asistente actual al programador de mensajes activo.
        toolRegistry.registerWithContext(ProactiveWishTool.toolDef()) { args, executionContext ->
            ProactiveWishTool.execute(args, settings, executionContext)
        }

        // v2.6.9: Preocupado por la contabilidad —— El principal combustible del nuevo mecanismo de mensajería proactiva.
        // En las conversaciones diarias, anota cosas que "te harán más agradable si vuelves a preguntar después de unos días".
        // Sin él, el libro mayor está vacío y los mensajes no solicitados se devuelven a los saludos programados.
        // La función de extracción separada se debe a que RegisterAll ya está cerca del límite superior de complejidad ciclomática.
        // Si empujas otra rama hacia adentro, explotará.
        registerNoteThought()

        // Phase 4A: Tareas pendientes
        // v2.6.7: Toma la entrada de la persistencia —— Anteriormente, sólo se escribía la memoria del proceso y se perdía la sesión.
        // v2.6.7 fix (AUDIT F5): En su lugar, el contexto del host inyecta la identificación de la sesión ——
        // La implementación anterior requiere que el modelo pase el session_id en sí, pero el modelo no tiene forma de conocer el UUID de la sesión actual.
        toolRegistry.registerWithContext(TodoTool.toolDef()) { args, executionContext ->
            val sessionId = executionContext.sessionId?.trim().orEmpty()
            if (sessionId.isEmpty()) {
                "Error: current session is unavailable."
            } else {
                TodoTool.executePersistedForSession(sessionId, args)
            }
        }

        // v2.6.7: Leer un archivo en el dispositivo del usuario (SAF, el usuario debe hacer clic)
        toolRegistry.register(ReadUserFileTool.toolDef()) { args ->
            ReadUserFileTool.execute(args)
        }

        // v2.6.7: Aprendizaje de preferencias del usuario —— El usuario dijo "No…"El almacenamiento de tiempo se convierte en una dura limitación
        toolRegistry.register(UserPreferenceTool.toolDef()) { args ->
            val store =
                org.koin.core.context.GlobalContext.get()
                    .get<io.zer0.muse.data.PreferenceStore>()
            UserPreferenceTool.execute(args, store)
        }

        // Phase 4B:Mostrar tarjeta(v2.0: Admite datos estructurados vinculantes)
        //
        // v2.6.13: Utilice el registro de versión de contexto en su lugar —— La identidad de una tarjeta es el id del mensaje que la lleva,
        // El modelo no puede obtenerlo durante la generación y debe ser inyectado por el host ([io.zer0.muse.tools.ToolExecutionContext.messageId]).
        // Si incluyes este DNI en el recibo, el modelo tendrá base para actualizar la misma tarjeta en la siguiente ronda.
        toolRegistry.registerWithContext(ShowCardTool.toolDef()) { args, ctx ->
            ShowCardTool.execute(args, ctx.messageId)
        }
        // v2.x: bounded chart/table output rendered directly in the tool result card.
        toolRegistry.register(RenderDataTool.toolDef()) { args ->
            RenderDataTool.execute(args)
        }

        // v2.0: Enlace de datos de tarjeta — Actualizar datos de tarjetas mostradas
        //
        // v2.6.13: clave cambiada a**noticias id**.
        //
        // La antigua descripción del parámetro dice:“show_card Card_id devuelto (en el formato c_xxx)”, pero la capa de renderizado lo inyecta en la tarjeta.
        // window.muse.cardId es la noticia id —— Dos campos de identificación diferentes. Al pasar c_xxx según las instrucciones antiguas, la escritura cae en
        // Una clave que el lado del renderizado nunca verificará, por lo que“Los datos se han actualizado pero la tarjeta no ha cambiado.”y nunca informa un error.
        // Ahora unifícalo con el ID del mensaje: la tarjeta dice window.muse.cardId y eso es lo que obtienes.
        // El modelo sólo necesita saber“tarjeta en la que mensaje”, en lugar de una identificación única que no puede obtener de show_card.
        toolRegistry.registerWithContext(
            ToolRegistry.ToolDef(
                name = "update_card_data",
                description = "Actualice los datos estructurados vinculados a la tarjeta mostrada." +
                    "la tarjeta pasará window.muse.getData() Vuelva a leer con window.muse.data.onChange." +
                    "Cuando se omite card_id, la tarjeta del mensaje actual se actualizará de forma predeterminada.",
                parameters = mapOf(
                    "card_id" to "Optional. The message id carrying the card. Omit it to update the card on the current message.",
                    "data" to "Required. JSON text with the new data.",
                ),
                required = setOf("data"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args, ctx ->
            val requested = args["card_id"]?.trim().orEmpty()
            val data = args["data"].orEmpty()
            // Cuando se omite card_id, apunta a este mensaje del asistente en esta ronda: El modelo acaba de terminar show_card y quiere cambiar los datos.
            // Lo único que sabe con certeza es "el mensaje actual", no una identificación que nunca antes haya visto.
            val cardId = ShowCardTool.resolveCardTarget(requested, ctx.messageId)
            when {
                data.isBlank() -> "Error: data is required."
                cardId.isBlank() -> "Error: card_id is required when the current message id is unavailable."
                cardDataStore == null -> "Error: card data store unavailable."
                else -> {
                    // Antes de escribir, eche un vistazo a esta ID para ver si se ha vinculado a datos antes:
                    // Si escribe en una clave que nadie lee, no se informará ningún error. El rendimiento es simplemente "la tarjeta no se mueve", lo cual es extremadamente difícil de solucionar.
                    val previouslyBound = cardDataStore.get(cardId) != null
                    cardDataStore.put(cardId, data)
                    if (previouslyBound || cardId == ctx.messageId) {
                        "Card '$cardId' data updated (${data.length} chars)."
                    } else {
                        "Wrote ${data.length} chars to '$cardId', but nothing was ever bound to that id. " +
                            "If the card does not change, the id is wrong — omit card_id to target the current message."
                    }
                }
            }
        }

        // Phase 4C: Notificación
        toolRegistry.register(NotifyTool.toolDef()) { args ->
            NotifyTool.execute(args, notificationManager)
        }

        // Phase 4D:Estado actual
        toolRegistry.register(CurrentStatusTool.toolDef()) { args ->
            CurrentStatusTool.execute(args, context)
        }

        // token Autoexamen de uso: Deje que el modelo responda "cuánto gasté" y "cuánto queda en el contexto".
        registerTokenUsage()

        // v1.202: Herramienta de tareas de subagente(launch / reply / close conjunto de tres piezas + status / cancel / list)
        // La falta de registro anterior provocó que la herramienta subagent_task nunca estuviera disponible,Completa aquí
        toolRegistry.register(SubagentTool.toolDef()) { args ->
            SubagentTool.execute(
                args = args,
                skillExecutor = skillExecutor,
                subagentThreadStore = subagentThreadStore,
                deferredResultStore = deferredResultStore,
                appScope = appScope,
            )
        }

        // v1.0.52 P2-1: Niño independiente de bloqueo sincrónico agent(Bucle de herramientas completo + límite maxToolCalls + protocolo XML)
        // con subagent_task(asíncrono sin bloqueo)complementario:Adecuado para tareas de investigación cortas.,El agente maestro espera y recibe la información estructurada. XML
        toolRegistry.register(SubagentRunSkill.toolDef()) { args ->
            SubagentRunSkill.execute(args, subagentRunner)
        }

        // v1.0.53 Phase 1: subagent_close — Cerrar activamente hilos de agentes secundarios
        // El agente principal utiliza esta herramienta para liberar subprocesos que ya no son necesarios.;Está prohibido llamar dentro del subagente.(SubagentRunner RECURSIVE_FORBIDDEN_TOOLS)
        toolRegistry.register(SubagentCloseTool.toolDef()) { args ->
            SubagentCloseTool.execute(args, subagentThreadStore)
        }
    }
}
