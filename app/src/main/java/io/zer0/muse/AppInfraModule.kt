package io.zer0.muse

import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.backup.BackupService
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.web.CompositeWebSearchService
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchService
import io.zer0.muse.web.createWebSearchClient
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * P2-3 Deslocalización:MCP / Visual/Copia de seguridad/Notificaciones/Actualizaciones/Web/Módulo independiente de registro de infraestructura de memoria.
 */
val appInfraModule = module {

    // Phase 9.5 (M3): MCP server registrador(Administrar múltiples McpClient,puente ToolRegistry)
    // v1.0.79 (C-3): inyectar AssistantRepository — MCP Vincularse automáticamente a la extensión del asistente principal después de una conexión exitosa
    single { io.zer0.muse.mcp.McpRegistry(get(), get(), androidContext(), get()) }

    // Phase 5-I / Phase 7: Exportación de copia de seguridad/Servicios de importación(Contiene memory.db + facts.db)
    // Phase 8.9: Agregar copia de seguridad en la nube/La consulta de saldo depende de

    // v1.135-A: Almacenamiento en caché de resultados de ayuda visual(session nivel + persistencia sidecar)
    single { io.zer0.muse.vision.VisionCache(androidContext()) }

    // v1.25: Puente de asistencia visual(Pasar un modelo de texto plano a través de un modelo visual"ver"fotos)
    single { io.zer0.muse.vision.VisionBridge(get(), get(), get()) }

    /** Libro mayor en la etapa de recuperación de MuseDb/MemoryDb/FactDb/DataStore. */
    single { io.zer0.muse.backup.RestoreJournal(androidContext()) }
    single { io.zer0.muse.backup.RestoreStagingStore(androidContext()) }
    single {
        BackupService(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            ragIndexInvalidator = { get<io.zer0.muse.rag.RagService>().invalidateAfterRestore() },
        )
    }

    // Phase 8.9: Servicio de respaldo en la nube(S3/WebDAV distribuir)
    // v1.0.4 (P3-8): Eliminar el registro de BalanceService Koin — Esta clase nunca es llamada por código comercial.,
    // ProviderSection.kt Consulta de saldo con comentarios de errores localizados implementados en línea,BalanceService código hasta la muerte,Eliminado.
    single { io.zer0.muse.backup.CloudBackupService(get(named("chat"))) }
    // Phase 8.9: CherryStudio/Chatbox Importación de configuración
    single { io.zer0.muse.importer.ConfigImporter(get()) }

    // Flujo de uso: cada vez que el modelo solicita una fila, la página de estadísticas agrega directamente esta tabla.
    single { io.zer0.muse.data.stats.UsageRecorder(get<io.zer0.muse.data.session.MuseDb>().usageRecordDao()) }

    // Phase 8.10: administrador de notificaciones(3 canal:chat_completed/live_update/web_server)
    single { io.zer0.muse.notification.MuseNotificationManager(androidContext()) }

    // v1.133: GitHub Release Comprobación de actualización — Reutilizar named("chat") OkHttpClient(Configuración del agente de usuario aplicada)
    single { io.zer0.muse.update.UpdateChecker(get(named("chat"))) }
    // v1.133: Notificador de actualización(coordinar UpdateChecker + SettingsRepository + MuseNotificationManager)
    single { io.zer0.muse.update.UpdateNotifier(get(), get()) }

    // Phase 8.11: mDNS descubrimiento de servicios(NSD Registro del servicio LAN)
    single { io.zer0.muse.web.MdnsService(androidContext()) }
    // Phase 8.11: Servidor web integrado(Ktor CIO + JWT + mDNS)
    // Los dos últimos parámetros son la fuente de datos del comando de solo lectura de la memoria.(Global FactStore + proveedor de repositorio),El mismo conjunto de rutas que la página de memoria.;
    // Luego viene el conocimiento/Automatizar fuentes de datos de solo lectura para páginas de dos categorías(Tres tablas de base de conocimientos + servicio de incorporación + dos tablas de tareas programadas).
    // Fuente de datos para comandos de solo lectura del estado del sistema(Tamaño de la aplicación/copia de seguridad/capacidades de automatización/potencia).
    single<io.zer0.muse.web.HostSystemStatusSource> {
        io.zer0.muse.web.AndroidHostSystemStatusSource(
            context = androidContext(),
            settings = get(),
            autoBackupLogDao = get(),
        )
    }
    single {
        io.zer0.muse.web.WebServer(
            get(), get(), get(), get(), androidContext(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(), get(), get(), get(), get(),
            systemStatusSource = get(),
        )
    }

    // Phase 8.4: Web Servicio de búsqueda(independiente OkHttpClient,Evite la interacción con conexiones largas SSE)
    // Phase 8.5 Reparación:Agregar calificador para distinguir;config Cambiar a carga diferida,evitar el hilo principal runBlocking
    // v1.39: Utilice la caché @Volatile en su lugar runBlocking,Eliminar hilo principal ANR
    single(named("webSearch")) {
        // v2.6.9: En su lugar, el proxy ha sido asumido por un ProxyProvider dinámico (createWebSearchClient Decoración de interiores
        // ProxySelector), no es necesario leer la variable local proxyConfig aquí.
        createWebSearchClient()
    }
    single<WebSearchService> {
        // config No leer sincrónicamente en la inicialización de Koin(evitar el hilo principal ANR),Déjelo en manos de CompositeWebSearchService para una carga diferida
        CompositeWebSearchService(get(named("webSearch")), WebSearchConfig())
    }

    // MemoryTicker: Regístrese en el módulo de la aplicación(Requiere ajustesRepositorio y conmutadores de memoria)
    // v0.32: Pasar transparentemente el cierre getConfig,Deja que el usuario MemoryConfig(tokenBudget/decay/threshold espera)
    //         Realmente afecta el comportamiento de la memoria.;Los cierres se leen cada vez. settings.memoryConfigCache(@Volatile,Bloqueo cero),
    //         en lugar de almacenar en caché en el momento de la construcción,Asegúrese de que el usuario cambie la página de configuración para que surta efecto inmediatamente.
    single {
        val settings = get<SettingsRepository>()
        val assistants = get<io.zer0.muse.data.assistant.AssistantRepository>()
        val factDbProvider = get<io.zer0.memory.fact.FactDbProvider>()
        MemoryTicker(
            summaryManager = get(),
            compiler = get(),
            deepProcessor = get(),
            dailyStateDao = get(),
            // Phase 3: Aún no hay marca de agua para restablecer la memoria
            getResetAt = { null },
            isMemoryEnabled = { settings.isMemoryEnabled() },
            scope = get(),
            runtimeContext = io.zer0.memory.ticker.MemoryRuntimeContext(
                getConfig = { settings.memoryConfigCache },
                getCurrentSpaceId = { settings.currentSpaceIdFlow.first() },
            ),
            factStore = get(),
            // v12 (T3-1): tarea de reflexión de la memoria — Clasificación diaria(Rellenar claves de entidad/fusionar duplicados/Detección de contradicciones/promoción)
            reflectionRunner = get(),
            // v1.0.92: LLM integración de memoria — Después de eliminar duplicados de las reglas diarias,Proporcionar grupos similares semánticamente repetidos a modelos grandes para fusionarlos.
            factConsolidator = get(),
            // D3-P1: Registro de operaciones de tubería(capa de observación) — Realizar el pedido filesDir/memory/pipeline_log.jsonl
            pipelineLog = io.zer0.memory.observe.PipelineLog.create(
                java.io.File(androidContext().filesDir, "memory"),
            ),
            getFactStoresForMaintenance = {
                (listOf("default") + assistants.getAll().map { it.id })
                    .filter { it.isNotBlank() }
                    .distinct()
                    .map(factDbProvider::getFactStore)
            },
            resolveFactStore = { target ->
                val assistantId = target.assistantId?.takeIf { it.isNotBlank() }
                    ?: target.normalizedScope.takeIf { it != "main" }
                    ?: "default"
                factDbProvider.getFactStore(assistantId)
            },
        )
    }
    // v1.0.92: LLM Consolidador de memoria(manuales"organizar la memoria"Comparte la misma implementación con integración automática diaria.)
    single { io.zer0.memory.fact.LlmFactConsolidator(get()) }
    // sesión/Limpieza de memoria cuando se elimina el asistente(Resumen/punto de control/hechos/compilar producto/Memoria de chat grupal/lista de contradicciones)
    single {
        io.zer0.muse.data.memory.SessionMemoryCleanup(
            summaryDao = get(),
            checkpointDao = get(),
            scopedSectionDao = get(),
            groupChatMemoryDao = get(),
            factDbProvider = get(),
            fileWriter = get(),
            contradictionStore = get(),
        )
    }
    // v1.0.51: Migración de memoria de stock — Iniciar la sesión del historial de copias de seguridad por primera vez después de la actualización rollingSummary
    single {
        io.zer0.muse.data.MemoryBackfillMigration(
            sessionRepository = get(),
            memoryTicker = get(),
            settings = get(),
        )
    }

    // v2.6.2(comentarios de los usuarios): Notificación → Clasificación y programación del fondo de la memoria
    single {
        io.zer0.muse.data.NotificationMemoryScheduler(
            settings = get(),
            digest = get(),
            memoryTicker = get(),
            scope = get(),
        )
    }

    // v2.6.2(auditoría de memoria): La memoria del chat grupal/los mensajes privados del agente se limpian periódicamente
    single {
        io.zer0.muse.data.MemoryHousekeepingScheduler(
            settings = get(),
            groupChatMemoryRepository = get(),
            agentDmRepository = get(),
            scope = get(),
        )
    }

    // v1.0.52 P2-3: AI Gestión automática de la memoria del disco(Extraiga entidades en tiempo real durante las conversaciones./relación/fusionar/Clasificación)
    single {
        io.zer0.memory.ai.MemoryAutoSaveScheduler(
            factDbProvider = get(),
            llmClient = get(),
            scope = get(),
            // v2.6.2(comentarios de los usuarios): Finalice la sesión para complementar los bordes (deje que las constelaciones se conecten en una red)
            edgeBuilder = get(),
            // Proveedor de vectores vecinos semánticos —— Reutilice EmbeddingService de RAG (puede fallar, la capa de borde es tolerante a fallas)
            semanticProvider = io.zer0.memory.ai.MemoryEdgeBuilder.SemanticProvider { texts ->
                val cfg = get<io.zer0.muse.data.SettingsRepository>().getRagConfig()
                get<io.zer0.muse.rag.EmbeddingService>().getProvider(cfg).embed(texts)
            },
            // v2.6.2: LLM profundizar el interruptor —— Leer configuración de usuario (desactivado por defecto)
            edgeEnhanceEnabled = {
                get<io.zer0.muse.data.SettingsRepository>().memoryGraphConfigCache.aiEnhanceEnabled
            },
        )
    }
}
