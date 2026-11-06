package io.zer0.muse

import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.aiModule
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.memoryModule
import io.zer0.muse.data.MemoryLlmClientImpl
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.web.WebSearchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

/**
 * app Montaje de módulos Koin.
 *
 * Acuerdo de orden de registro:
 *  1. appModule: SettingsRepository / ProviderConfigStore / MemoryLlmClient / AppScope /
 *                MuseDb / SessionRepository / OkHttpClient / DocumentParser / ToolRegistry / BackupService y otros componentes básicos
 *  2. aiModule: ChatService + ImageService(depender de ProviderConfigStore + OkHttpClient)
 *  3. memoryModule: Room + Servicios básicos + MemoryTicker(depender de MemoryLlmClient + AppScope)
 *
 * [SettingsRepository] Regístrese simultáneamente para usted y [ProviderConfigStore] darse cuenta,
 * De esta manera, el ChatService / ImageService del módulo ai se puede inyectar a través de la interfaz.
 * [ChatViewModel] Regístrese en viewModel DSL,UI usar koinViewModel() Llevar.
 */
val appModule = module {
    single { SettingsRepository(androidContext(), get(), get()) }
    single<ProviderConfigStore> { get<SettingsRepository>() }
    single<MemoryLlmClient> { MemoryLlmClientImpl(get(), get(), androidContext()) }
    // v12: LLM determinante de deduplicación de hechos — Los candidatos difusos de la misma entidad que no pueden ser determinados por la capa del algoritmo se entregan al modelo grande para su evaluación.
    single<io.zer0.memory.fact.FactDedupJudge> { io.zer0.muse.data.LlmFactDedupJudge(get()) }

    // CoroutineScope a nivel de aplicación: se utiliza para tareas en segundo plano, como el ticker de memoria.
    single<CoroutineScope> {
        CoroutineScope(SupervisorJob() + Dispatchers.IO + io.zer0.muse.util.GlobalCoroutineExceptionHandler)
    }

    // Phase 5: unificar OkHttpClient(ai ChatService/ImageService reutilización de módulos)
    // Phase 8.5 Reparación:Agregue un calificador para distinguirlo del cliente de búsqueda web,Evite que este último sobrescriba al primero y provoque que se agote el tiempo de espera de generación de la imagen.
    // v1.39: Utilice la caché @Volatile en su lugar runBlocking,Eliminar hilo principal ANR
    //
    // v2.6.9: client Sigue siendo un singleton de Koin (construido solo una vez), pero el agente que instala ya no está construido.
    // fijo proxy(), pero de io.zer0.ai.core.ProxyProvider**Dinámico ProxySelector**.
    // OkHttp Cada vez que se establezca una nueva conexión, se le preguntará al selector. Por lo tanto, después de que el usuario cambie el proxy, la nueva conexión pasará inmediatamente por la nueva configuración.
    // No es necesario reiniciar ni reconstruir el cliente (11 No es necesario cambiar todos los puntos de inyección).
    //
    // Este cambio pretende solucionar“El navegador puede iniciar sesión, pero la aplicación no puede conectarse”Informe con Codex
    // Invalid content-type: null Un tipo de causa: bajo la implementación anterior, el cliente puede repararse al inicio.
    // En la configuración de conexión directa, el proxy configurado por el usuario nunca tendrá efecto.
    single(named("chat")) {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // v1.114 Reparación: modelo de pensamiento(Como por ejemplo Claude 3.5 thinking)El retraso de la primera palabra puede exceder los 2 minutos.,
            //   readTimeout 120s Esto hará que la fase de pensamiento se agote sin resultados.;Aquí está 300s(5minutos),El tiempo suficiente para pensar.
            // C-02: Unificar código y comentarios(Anteriormente el comentario estaba escrito 300s pero el código es 120s).
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // v2.6.9: Utilice dinámica en su lugar ProxyProvider —— Después de cambiar el proxy, entrará en vigor la próxima vez que establezca una conexión, no es necesario reiniciar.
            .also { io.zer0.ai.core.ProxyProvider.applyTo(it) }
            .build()
    }

    // Phase 5: app La base de datos de la sala(Persistencia de sesión + mensaje + Assistant)
    single { MuseDb.get(androidContext()) }
    single { get<MuseDb>().sessionDao() }
    single { get<MuseDb>().messageDao() }
    single { get<MuseDb>().artifactDao() } // v1.43: artefacto de conversación
    single { get<MuseDb>().assistantDao() } // Phase 8.2
    single { get<MuseDb>().lorebookDao() } // Phase 8.5
    single { get<MuseDb>().worldBookDao() } // P1-2: Worldbook Libro del mundo dinámico
    single { get<MuseDb>().quickMessageDao() } // Phase 8.5
    single { get<MuseDb>().promptInjectionDao() } // Phase 8.5
    single { get<MuseDb>().skillDao() } // Phase 8.8
    single { get<MuseDb>().folderDao() } // Phase 9.1 (M13)
    single { get<MuseDb>().scheduledTaskDao() } // tareas programadas
    single { get<MuseDb>().knowledgeDocDao() } // base de conocimientos
    single {
        io.zer0.muse.data.knowledge.BuiltInKnowledgeDocSeeder(
            androidContext(),
            get(),
            ragService = get(),
            ragConfigProvider = { get<io.zer0.muse.data.SettingsRepository>().getRagConfig() },
        )
    }
    single { get<MuseDb>().knowledgeChunkDao() } // v1.54: Fragmentación de la base de conocimientos(RAG)
    single { get<MuseDb>().scheduledTaskExecutionDao() } // P1-7: Historial de ejecución de tareas programadas
    single { get<MuseDb>().groupChatDao() } // v1.30: chat grupal
    single { get<MuseDb>().groupChatMessageDao() } // v1.30: Mensajes de chat grupal
    single { get<MuseDb>().groupChatMemoryDao() } // v2.x: Aislamiento de memoria de chat grupal(independiente fact store)
    single { get<MuseDb>().experienceDao() } // v1.98
    single { get<MuseDb>().milestoneDao() } // Phase 2 2B: milestone
    single { get<MuseDb>().agentMessageDao() } // Sistema de herramientas existente port: agent DM
    single { get<MuseDb>().auditLogDao() } // P2-4: Registro de auditoría
    single { get<MuseDb>().quickNoteDao() } // v1.0.17: registro rápido
    // v1.134 P1-1/P1-2: Requerido para acceder a componentes huérfanos DAO(AutoBackupHelper / StatsCacheManager depender de)
    single { get<MuseDb>().autoBackupLogDao() } // Registro de copia de seguridad automática
    single { get<MuseDb>().statsCacheDao() } // Caché de estadísticas
    single { get<MuseDb>().integrityLogDao() } // P3-3: Registro de integridad de la base de datos
    single { get<MuseDb>().translateHistoryDao() } // v1.0.17: Historial de traducción
    single { get<MuseDb>().patrolLogDao() }
    // v2.6.7(P0-1): Omitir el generador de contexto de construcción — Deje que los pequeños mensajes privados del teléfono celular generen enlaces completos
    // (El libro mundial, la memoria, el retrato del usuario y la inyección rápida de palabras tienen efecto), ya no solo se unen tres cadenas.
    single {
        io.zer0.muse.data.moment.MiniContextBuilder(
            systemPromptAssembler = get(),
            lorebookRepository = get(),
            promptInjectionRepository = get(),
            settingsRepository = get(),
        )
    }
    single { AuditLogger(get()) } // P2-4: Registrador de auditoría
    // P3-3: Comprobador de integridad de la base de datos(Para que DebugScreen active la inspección y muestre el último resultado)
    single {
        io.zer0.muse.data.stats.IntegrityChecker(
            integrityLogDao = get(),
            db = get<MuseDb>().openHelper.writableDatabase,
        )
    }
    single { io.zer0.muse.data.milestone.MilestoneChecker(get(), get(), get()) } // Phase 2 2B: milestone checker
    single { io.zer0.muse.data.experience.ExperienceRepository(get()) } // v1.98
    single { io.zer0.muse.data.agentdm.AgentDmRepository(get()) } // Sistema de herramientas existente port: agent DM
    // v1.134 P1-2: Servicio de almacenamiento de imágenes de mensajes.,responsable base64 ↔ Conversión de ruta de archivo,
    // Deja que el panorama general llegue a la mesa filesDir/muse_images/,DB Guardar solo ruta,Evite la expansión del tamaño de fila de la tabla de mensajes
    single {
        io.zer0.muse.data.session.MessageImageStore(
            storageDir = java.io.File(androidContext().filesDir, "muse_images"),
        )
    }
    // +MuseDb: Transacciones entre mesas(H-SESS1); +SessionMemoryCleanup: Eliminar sesión y borrar memoria
    single { SessionRepository(get(), get(), get(), androidContext(), get(), get(), get(), get(), get()) }
    single { io.zer0.muse.data.artifact.ArtifactRepository(get()) } // v1.43: repositorio de productos de sesión
    single {
        AssistantRepository(get(), androidContext(), get(), get(), get())
    } // Phase 8.2 + v1.0.51 inyectar SettingsRepository;P0-10 inyectar FactDbProvider(Eliminar la biblioteca de inventario del asistente);Borrar memoria al borrar
    single { LorebookRepository(get()) } // Phase 8.5
    single { io.zer0.muse.worldbook.WorldBookRepository(get()) } // P1-2: Worldbook Libro del mundo dinámico
    single { QuickMessageRepository(get()) } // Phase 8.5
    single { PromptInjectionRepository(get(), androidContext()) } // Phase 8.5
    single { io.zer0.muse.data.skill.SkillRepository(get()) } // Phase 8.8
    single {
        io.zer0.muse.data.session.FolderRepository(get(), get(), get(), androidContext())
    } // Phase 9.1 (M13) +MuseDb: deleteFolder negocios(M-SESS8)
    single {
        io.zer0.muse.data.groupchat.GroupChatRepository(get(), get(), get(), get(), get())
    } // v1.30: Almacén de chat grupal(Contiene MuseDb para transacciones entre tablas)+ v2.x cierre del legado MessageImageStore
    // v2.x: Almacén de aislamiento de memoria de chat grupal(independiente fact store,No contamina la memoria principal)
    single { io.zer0.muse.data.groupchat.GroupChatMemoryRepository(get()) }
    // v2.6.7(P1-1): Estado de la relación de rol (intimidad/nivel de deseo/interacciones recientes)
    single { get<MuseDb>().assistantRelationshipDao() }
    single { io.zer0.muse.data.relationship.AssistantRelationshipRepository(get()) }
    // v2.6.7: Aprendizaje de preferencias del usuario (precipitación con restricciones estrictas)
    single { io.zer0.muse.data.PreferenceStore(androidContext()) }
    // v2.6.7: Persistencia de tareas pendientes de la sesión (libro mayor entre sesiones)
    single { get<MuseDb>().sessionTodoDao() }
    single { io.zer0.muse.data.todo.SessionTodoRepository(get()) }
    // v1.95: Almacén de paquetes de expresión(Almacenamiento de archivos,no tocar MuseDb)
    single { io.zer0.muse.data.sticker.StickerLibraryRepository(androidContext()) }

    // v1.120: Cargador de datos con licencia de código abierto(Leer el manifiesto de dependencia de activos/licenses/manifest.json)
    single { io.zer0.muse.license.LicenseRepository(androidContext()) }

    // PresetProviders Proveedor predeterminado
    // v2.6.0: La lista de modelos está sujeta al directorio de la nube. —— ModelCatalogStore Acceso ModelCatalogRepository
    // directorio (assets/model-catalog.json + caché remoto),Codex espera“autoridad de directorio”modelo del proveedor
    // Ya no está preestablecido en el código. Los cambios publicados en se volverán a calcular automáticamente después de actualizar el directorio remoto.
    single {
        val catalogRepo: io.zer0.muse.data.catalog.ModelCatalogRepository = get()
        io.zer0.muse.data.preset.ModelCatalogStore(
            androidContext(),
            cloudCatalog = { catalogRepo.current() },
        )
    }
    single { PresetProviders(androidContext(), get()) }

    // P2-11: OAuth Aislamiento de credenciales — cifrado independiente SP(Keystore AES-256-GCM),
    // con ordinario API Key(SettingsRepository.providers Flow)aislamiento físico,
    // solo OAuthManager + UI(revocar el acceso)visita
    single { io.zer0.muse.auth.SecureCredentialStore(androidContext()) }

    // P2-10: Provider Centro de registro de complementos(JSON Proveedor personalizado impulsado por la configuración)
    // v1.134 P1-3: Inyecte filesDir/muse_plugins/ como directorio de persistencia,App Restaurar automáticamente después del reinicio
    single {
        io.zer0.ai.plugin.ProviderPluginRegistry(
            storageDir = java.io.File(androidContext().filesDir, "muse_plugins"),
        )
    }

    // v0.23: Ejecutor de tareas programadas(sondeo de antecedentes,Verifique las tareas pendientes cada 60 segundos y ejecútelas:Ajustar AI + sesión de escritura + notificación)
    // H-SC1: executionDao Eliminado — historial de ejecución+next_run_at Escritura atómica a través de ScheduledTaskDao.@Transaction
    // La ejecución real se cambia a inyección. ChatService / SessionRepository / AssistantRepository(Lo mismo ProactiveMessageRunner)
    single { io.zer0.muse.schedule.ScheduledTaskRunner(get(), get(), get(), get(), androidContext(), get(), get(), get(), get()) }

    // Programación activa de mensajes(El asistente complementario envía mensajes periódicamente a los usuarios + notificaciones emergentes)
    // ── v2.6.9 Reconstrucción: modelo de libro mayor ──
    // El antiguo ProactiveMessageRunner todavía está registrado (es necesario que coexista con la entrada anterior de la página de configuración),
    // va nuevo enlace io.zer0.muse.proactive.ProactiveScheduler, los dos no comparten el estado de programación.
    // v2.6.9: Las tres tablas del libro mayor DAO (MuseDb Ya construido,Koin Cada lado debe registrarse uno por uno)
    single { get<MuseDb>().pendingThoughtDao() }
    single { get<MuseDb>().pendingThoughtWriteDao() }
    single { get<MuseDb>().proactiveOutcomeDao() }
    single { get<MuseDb>().proactiveRelationDao() }
    // Los puertos de lectura y escritura de cada una de las tres tablas (separación de lectura y escritura: la lectura está en el camino caliente para inspección y la escritura está en el camino frío para contabilidad)
    single { io.zer0.muse.data.proactive.ProactiveRelationRepository(get()) }
    single {
        io.zer0.muse.data.proactive.ProactiveThoughtRepository(
            readDao = get(),
            writeDao = get(),
        )
    }
    single {
        io.zer0.muse.data.proactive.ProactiveOutcomeRepository(
            dao = get(),
            relations = get(),
        )
    }
    single {
        io.zer0.muse.proactive.ProactiveStores(
            thoughts = get(),
            relations = get(),
            outcomes = get(),
        )
    }
    single {
        io.zer0.muse.proactive.ProactiveSources(
            factStore = get(),
            milestoneDao = get(),
        )
    }
    single {
        io.zer0.muse.proactive.ProactiveRuntime(
            settings = get(),
            sessions = get(),
            assistants = get(),
            notifier = get(),
            generations = get(),
        )
    }
    single { io.zer0.muse.proactive.ProactiveComposer(chatService = get(), promptAssembler = get()) }
    // Cuatro fuentes de activación(Horario/Trabajador/Regreso a recepción/Manual)Comparte esta puerta de mosca única.
    // Debe ser un singleton: cada uno que toma uno equivale a que cada uno tiene una puerta.,Es como si no hubiera puerta.
    single { io.zer0.muse.proactive.ProactiveCycleGuard() }
    single {
        io.zer0.muse.proactive.ProactiveContextLoader(
            stores = get(),
            runtime = get(),
            sources = get(),
        )
    }
    single {
        io.zer0.muse.proactive.ProactiveCycleRunner(
            stores = get(),
            runtime = get(),
            loader = get(),
            composer = get(),
            guard = get(),
        )
    }
    single {
        io.zer0.muse.proactive.ProactiveScheduler(
            stores = get(),
            cycle = get(),
            appScope = get(),
        )
    }
    // orden de dependencia:SettingsRepository / ChatService(ai módulo) / SessionRepository / AssistantRepository /
    //         MuseNotificationManager / ProactiveScoreEngine / ExperienceRepository / MilestoneDao /
    //         LorebookRepository / FactStore(memory módulo) / UserActivityProfile / Context / AppScope Espere las dependencias del tiempo de ejecución
    // v2.0 Refactorizar:Acceso ExperienceRepository / MilestoneDao / LorebookRepository / FactStore,
    //           Se utiliza para la construcción del contexto de inspección.(5.3/5.10)con nuevos recuerdos/hito/Experimente la detección de diferencias
    // v2.1: Acceso UserActivityProfile,Usar actividad/continuidad del diálogo/Programación adaptativa emocional de tres factores que reemplaza las compensaciones aleatorias
    single { io.zer0.muse.schedule.UserActivityProfile(androidContext()) }

    // v1.x: Generador de recordatorios personalizados de saludo(LLM generar,No volver a la versión de la regla)
    single { io.zer0.muse.schedule.GreetingHintGenerator(get()) }
    // v1.x: La única entrada para la generación de resumen diario: la compensación de la recepción de la página de inicio y WorkManager comparten el mismo servicio.
    single {
        io.zer0.muse.schedule.DailySummaryService(
            settings = get(),
            sessionRepository = get(),
            factStore = get(),
            chatService = runCatching { get<io.zer0.ai.ChatService>() }.getOrNull(),
            notificationManager = runCatching {
                get<io.zer0.muse.notification.MuseNotificationManager>()
            }.getOrNull(),
        )
    }

    // v1.30: Programación de chat grupal(Después de que el usuario envía un mensaje, se activa en serie a cada agente para que se turne para hablar.)
    // v1.111: recoger appScope/appContext/chatGenerationManager,El carrusel de chat grupal continúa appScope,Cortar páginas/Sin interrupciones en segundo plano
    // Modificación 1: recoger SkillExecutor,Delegar cuando el chat grupal está asociado a un equipo y tiene un flujo de trabajo TeamWorkflowExecutor
    // ActivityHub: recoger GroupChatActivityHub,Actualizar el estado del agente en cada etapa de rotación.(UI Mostrar actividad en tiempo real chip)
    // v1.202: recoger DelegationChainTracker,invokeAgent Estado del enlace sincrónico(Proceso de ejecución del chat grupal visible de la interfaz de usuario de la sesión principal)
    single { io.zer0.muse.ui.groupchat.GroupChatActivityHub() }
    // v2.x: Agregar al final GroupChatMemoryRepository,Se utiliza para aislar la memoria del chat grupal.(agent Los resúmenes de respuestas se escriben de forma independiente. fact store)
    // v1.0.53: anexar SystemPromptAssembler,Se utiliza para inyectar memoria a largo plazo y memoria de chat grupal en el mensaje del sistema de chat grupal.
    single {
        io.zer0.muse.schedule.GroupChatScheduler(
            get(),
            get(),
            get(),
            get(),
            get(),
            androidContext(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }

    // v1.43: Administrador de generación de chat a nivel de aplicación(Cortar páginas/Sigue generando en segundo plano sin interrupciones)
    single { io.zer0.muse.schedule.ChatGenerationManager(get(), get(), androidContext()) }

    // v1.x: Administrador de recursos a nivel de sesión(Recuento de referencias + limpieza inactiva),Depende del nivel de aplicación appScope(Koin registrado CoroutineScope)
    single { io.zer0.muse.session.ConversationSessionManager(get()) }
    // v1.x: Registro unificado LLM/Herramientas/Aprobación/Navegador/Los recursos de ejecución, como los agentes secundarios, se utilizan para la cancelación y la delimitación de resultados tardíos.
    single { io.zer0.muse.session.SessionExecutionRegistry() }

    // v1.0.15: Oyente de estado de red(StreamInterrupted Reconexión automática + dependencia de visualización del estado de la red UI)
    single { io.zer0.muse.network.NetworkMonitor(androidContext()) }

    // compresor de diálogo(Resumen de LLM paralelo fragmentado + modelo económico independiente,Para uso de ContextCompressTransformer)
    // depender de ChatService + SettingsRepository,Configure un modelo económico independiente a través de compressModelId
    single { io.zer0.muse.transformer.ConversationCompressor(get(), get()) }

    // v1.98: Programador de carga programada automática de respaldo en la nube(Compruebe la caducidad cada 10 minutos.)
    single { io.zer0.muse.schedule.CloudBackupScheduler(get(), get(), get(), get()) }

    // Phase 3 3E: Administrador de mensajes programados
    single { io.zer0.muse.data.schedule.PendingMessageManager(androidContext()) }

    // Phase 4 4A: Gerente de sistema de cuentas
    // v1.134 P1-3: Quitar CloudSyncManager(Componentes huérfanos originales de la fase 4 4B,TODO implementación vacía,
    // La copia de seguridad real en la nube la realiza CloudBackupScheduler + BackupService.exportToCloud)
    // Phase 4 4C: API administrador de cuotas

    // v1.134 P1-1: Asistente de respaldo automático(Componente huérfano original v1.107,Este acceso a la programación de Koin + WorkManager)
    // depender de AutoBackupLogDao + Context + MessageDao + SettingsRepository,Obtenido diariamente por AutoBackupWorker
    single {
        io.zer0.muse.data.stats.AutoBackupHelper(
            autoBackupLogDao = get(),
            context = androidContext(),
            messageDao = get(),
            settings = get(),
        )
    }
    // v1.134 P1-2: Administrador de caché de estadísticas(Componente huérfano original v1.107,Este acceso a la programación de Koin + WorkManager)
    // depender de StatsCacheDao + MessageDao + SessionDao,Obtenido diariamente por StatsCacheWorker
    single {
        io.zer0.muse.data.stats.StatsCacheManager(
            statsCacheDao = get(),
            messageDao = get(),
            sessionDao = get(),
        )
    }
    // Phase 4 4D: Motor de puntuación de mensajes proactivo

    // v1.0.47 P9: Quitar McpExtensionRegistry(Componentes huérfanos originales de la Fase 5 5E,Las tres extensiones son stub,
    // isAvailable=false Nunca habilitar,execute siempre regresa Error,Sin llamadas externas)

    // Phase 6 6E: Rastreador de análisis locales
    single { io.zer0.muse.data.analytics.LocalAnalyticsTracker(androidContext()) }
    // Almacén de catálogo de capacidad de modelo(Activo incorporado / caché local / museai.ltd remoto tres capas + cadena de degradación)
    single { io.zer0.muse.data.catalog.ModelCatalogRepository(androidContext()) }

    // Implementación del sistema de herramientas existente:administrador de archivos de sesión
    single { io.zer0.muse.data.session.SessionFileManager(androidContext()) }
    // Implementación del sistema de herramientas existente:Almacenamiento de experiencias basado en archivos
    single { io.zer0.muse.data.experience.ExperienceStore(androidContext()) }
    // v2.0: Almacenamiento de datos de la tarjeta(show_card / update_card_data enlace de datos)
    single { io.zer0.muse.data.card.CardDataStore(androidContext()) }
    // Implementación del sistema de herramientas existente:Registrador de herramientas(Registrarse pin/experience/search_memory/todo/card/notify/status Herramientas)
    // v1.202: inyectar SkillExecutor / SubagentThreadStore / DeferredResultStore / appScope,
    //         suministro SubagentTool(launch/reply/close conjunto de tres piezas)uso
    single {
        // v2.x: token La autocomprobación de uso requiere el uso de la dimensión de sesión (usage_records No hay ninguna columna de sesión).
        val sessionRepository = get<SessionRepository>()
        io.zer0.muse.tools.AgentToolsRegistrar(
            toolRegistry = get(),
            pinnedMemoryStore = get(),
            experienceRepository = get(),
            factStore = get(),
            factDbProvider = get(),
            settings = get(),
            notificationManager = get(),
            context = androidContext(),
            skillExecutor = get(),
            subagentThreadStore = get(),
            deferredResultStore = get(),
            appScope = get(),
            subagentRunner = get(),
            cardDataStore = get(),
            // v2.6.7: tool_status Necesita leer el estado de preparación del canal de tres capas.
            // Utilice getOrNull: entorno de prueba/Los hosts que no están equipados con módulos de automatización no deberían colapsar por completo debido a la falta de esta dependencia.
            // Y ToolStatusTool no se registra cuando no puede obtener el administrador (preferiría no proporcionarlo antes que dar un estado falso).
            automationManager = getOrNull(),
            // v2.6.9: Libro mayor de ideas para que note_thinked escriba.
            proactiveThoughtStore = getOrNull(),
            // v2.x: token Dosificación de autocontrol. El flujo de uso y la tabla de mensajes están ambos en MuseDb; la ocupación del contexto está determinada por
            // ChatViewModel Proporcionada por la sonda vinculada, esta línea se omite automáticamente cuando no se abre el cuadro de diálogo.
            usageRecordDao = get<MuseDb>().usageRecordDao(),
            sessionUsageReader = { sessionId ->
                sessionRepository.getUsageTotalsBySession(sessionId).let { row ->
                    io.zer0.muse.tools.TokenUsageTool.SessionUsage(
                        requests = row.requests,
                        promptTokens = row.promptTokens,
                        completionTokens = row.completionTokens,
                    )
                }
            },
            contextOccupancy = { io.zer0.muse.ui.chat.ContextOccupancyProbe.current() },
            // facts Cuando no se pueda encontrar la tabla, recurra al resumen compilado (prosa HOY/SEMANA/LARGO PLAZO con palabras clave inyectadas).
            // Usando getOrNull: prueba única compilada sin memoria ensambladora/No vuelve a caer en el host, en lugar de fallar al inicio.
            compiledSummarySearch = getOrNull<io.zer0.memory.compile.MemoryCompiler>()?.let { compiler ->
                io.zer0.muse.tools.SearchMemoryTool.CompiledSummarySearch { query, scope, spaceId, limit ->
                    compiler.searchCompiledSections(query, scope, spaceId, limit)
                }
            },
        )
    }
    // v2.x: current-session original conversation recall, scoped by host context.
    single {
        io.zer0.muse.tools.ConversationSearchToolsRegistrar(
            toolRegistry = get(),
            sessionRepository = get(),
            settings = get(),
        )
    }
    // v1.0.52 P2-1: Passive Subagent corredor(Niño independiente de bloqueo sincrónico agent,Ciclo completo de herramienta)
    // B2-04: Enrutamiento de aprobación de subagente(Registro de la sesión principal delegate,El subagente reutiliza el mismo enlace de aprobación)
    single { io.zer0.muse.tools.ToolApprovalRouter() }

    // v1.0.53 Phase 1: agregar threadStore(Continuación de la persistencia)+ concurrencyLimiter(Límite actual simultáneo global)
    // v1.0.53 Phase 4: agregar toolConfigStore(deny_on_prompt Estrategia de aprobación)
    single {
        io.zer0.muse.tools.SubagentRunner(
            chatService = get(),
            toolRegistry = get(),
            threadStore = get(),
            concurrencyLimiter = get(),
            toolConfigStore = get(),
            toolApprovalRouter = get(),
            settings = get(),
        )
    }

    // P2-7: Administrador de directorio del espacio de trabajo(directorio raíz /data/data/io.zer0.muse/files/workspace/,
    // Proporciona verificación de seguridad de ruta listDir/readFile/writeFile/delete/mkdir/move/copy)
    single { io.zer0.muse.workspace.WorkspaceManager(androidContext()) }
    // P2-7: Registrador de herramientas de espacio de trabajo(poner workspace_list/read/write/delete/mkdir/move Regístrese para ToolRegistry)
    // depender de ToolRegistry + WorkspaceManager,init Bloquear registro de autocompletar
    single { io.zer0.muse.tools.WorkspaceToolsRegistrar(get(), get()) }
    // v1.0.47 P2: Registrador de herramientas de archivos y enlaces(read_file/create_download/parse_link)
    single {
        io.zer0.muse.tools.FileToolsRegistrar(
            get(),
            androidContext(),
            io.zer0.muse.workspace.WorkspaceManager(androidContext()).rootDir,
        )
    }
    // v1.0.52 P2-4: PDF Análisis visual(PdfRenderer + 4 Concurrencia de carreteras + modelo visual OCR)
    // depender de ChatService + ProviderConfigStore(Consultar disponibilidad del modelo de visión.)+ Context
    single<io.zer0.muse.tools.VisionOcrClient> {
        io.zer0.muse.tools.DefaultVisionOcrClient(
            chatService = get(),
            configStore = get(),
            settings = get(),
        )
    }
    single {
        io.zer0.muse.tools.PdfVisionParser(
            context = androidContext(),
            ocrClient = get(),
        )
    }
    // P2-4: parse_pdf Registrador de herramientas(init Los bloques se registran automáticamente en ToolRegistry)
    single {
        io.zer0.muse.tools.PdfVisionToolsRegistrar(
            toolRegistry = get(),
            parser = get(),
            context = androidContext(),
            workspaceRoot = io.zer0.muse.workspace.WorkspaceManager(androidContext()).rootDir,
        )
    }
    // v1.0.47 P2-6: Shell Registrador de herramientas Sandbox(execute_shell,Sólo disponible en Modo Agente + Aprobación)
    single {
        io.zer0.muse.tools.ShellSandboxToolRegistrar(
            get(),
            androidContext().filesDir,
        )
    }

    // v2.x Tiempo de ejecución extendido(P0):Registrador de autoprueba integrado de Node sandbox(runtime_selfcheck,solo lectura)
    single {
        io.zer0.muse.tools.RuntimeSelfCheckToolRegistrar(
            get(),
            androidContext(),
            // v2.6.7: La autoprueba también informará el estado del canal de tres capas; si no se puede obtener, omita esta sección.
            getOrNull(),
        )
    }

    // v2.7 Tiempo de ejecución extendido(Python):Python Registrador de ejecución de scripts(execute_python_script,alto riesgo)
    single {
        io.zer0.muse.tools.PythonScriptToolRegistrar(
            get(),
            androidContext(),
        )
    }

    // v2.x Registrador de autoprueba de plataforma(platform_selfcheck,solo lectura):Anular el tiempo de ejecución del nodo + estado de cada canal de herramienta
    single {
        io.zer0.muse.tools.PlatformSelfCheckToolRegistrar(
            get(),
            androidContext(),
        )
    }

    // P3-3: Accesibilidad + Shizuku + Enrutamiento raíz de tres canales — UI Base de capacidad de automatización
    // AccessibilityClient: bindService Vincular servicios de accesibilidad,Proporciona operaciones de interfaz de usuario para agentes AIDL.
    single { io.zer0.muse.tools.system.AccessibilityClient(androidContext()) }
    // ShizukuAuthorizer: Shizuku SDK integrar,Ejecutar comandos con privilegios de shell(No es necesario root)
    single { io.zer0.muse.tools.system.ShizukuAuthorizer(androidContext()) }
    // RootAuthorizer: root detectar + su ejecutar(canal de degradación)
    single { io.zer0.muse.tools.system.RootAuthorizer() }
    // instalador(arranque habilitado/Instalación)
    single { io.zer0.muse.tools.system.AccessibilityProviderInstaller(androidContext()) }
    single { io.zer0.muse.tools.system.ShizukuInstaller(androidContext()) }
    // P3-3: UI Registrador de herramientas(init Los bloques registran automáticamente 10 ui_* Llegan las herramientas ToolRegistry,Todos son ALTOS riesgos)
    single {
        io.zer0.muse.tools.defaultTool.UIToolsRegistrar(
            toolRegistry = get(),
            accessibilityClient = get(),
            context = androidContext(),
        )
    }

    // P2-23: herramientas de generación de medios(generate_image / generate_video / generate_qr_code).
    // Implementación independiente de ViewModel,Confíe únicamente en la configuración persistente + ImageService / VideoGenerationService;
    // Sin enlace de interfaz de usuario(tareas programadas/chat grupal/subagente)No instalar el servidor de entrega,El resultado es la ruta del archivo./URL Devolver.
    single {
        // v2.x: Enviar mensaje de voz —— Utilice TtsManager para síntesis y VoiceMessageStore para ubicación.
        // TtsManager No implementar VoiceSynthesizer (criarlo invalidaría la exención a nivel de clase de la línea base de detekt),
        // Aquí, se utiliza el adaptador SAM para conectarlo de una sola vez.
        val tts = get<io.zer0.muse.ui.speech.TtsManager>()
        io.zer0.muse.tools.MediaGenToolsImpl(
            context = androidContext(),
            settings = get(),
            imageService = get(),
            videoGenerationService = get(),
            voiceSynthesizer = { text, file, emotion -> tts.synthesizeTo(text, file, emotion) },
            voiceMessageStore = get<io.zer0.muse.ui.speech.VoiceMessageStore>(),
        )
    }
    // P2-23: Registrador de herramientas de generación de medios(init Los bloques se registran automáticamente en ToolRegistry).
    // La misma instancia de MediaGenToolsImpl que ChatViewModel.installHost instala el host de entrega.
    single {
        io.zer0.muse.tools.MediaGenToolsRegistrar(
            toolRegistry = get(),
            impl = get(),
        )
    }

    // Guía de inicio del registrador de herramientas:Forzar la creación de instancias de todos lazy single,Haga que más de 100 herramientas sean realmente accesibles ToolRegistry
    single {
        io.zer0.muse.tools.ToolRegistrarBootstrapper(
            toolRegistry = get(),
            encodingToolsRegistrar = get(),
            coreToolsRegistrar = get(),
            weatherToolsRegistrar = get(),
            clipboardToolsRegistrar = get(),
            networkTextToolsRegistrar = get(),
            reminderToolsRegistrar = get(),
            calendarToolsRegistrar = get(),
            phoneToolsRegistrar = get(),
            systemToolsRegistrar = get(),
            resourceToolsRegistrar = get(),
            quickNoteToolsRegistrar = get(),
            scheduledTaskToolsRegistrar = get(),
            translateToolsRegistrar = get(),
            ttsToolsRegistrar = get(),
            agentToolsRegistrar = get(),
            conversationSearchToolsRegistrar = get(),
            workspaceToolsRegistrar = get(),
            fileToolsRegistrar = get(),
            pdfVisionToolsRegistrar = get(),
            shellSandboxToolRegistrar = get(),
            uiToolsRegistrar = get(),
            // P2-23: herramientas de generación de medios(fotos/vídeo/código qr)
            mediaGenToolsRegistrar = get(),
            // v1.0.92: Herramientas del canal de mensajes(Envío de mensajería instantánea externa)
            channelToolsRegistrar = get(),
            connectorToolsRegistrar = get(),
            // v2.0.1: Herramienta de mercado de complementos(Instalación de recuperación/aprobación)
            pluginMarketToolsRegistrar = get(),
            // v2.x Tiempo de ejecución extendido(P0):Autoprueba integrada de la zona de pruebas de Node
            runtimeSelfCheckToolRegistrar = get(),
            // v2.7 Tiempo de ejecución extendido(Python):Python Ejecución de script
            pythonScriptToolRegistrar = get(),
            // v2.6.2: Gestión de la base de conocimientos + gestión de sesiones (las operaciones de escritura están sujetas a aprobación)
            knowledgeManageToolsRegistrar = get(),
            knowledgeReadToolRegistrar = get(),
            sessionManageToolsRegistrar = get(),
            skillManageToolsRegistrar = get(),
            assistantManageToolsRegistrar = get(),
        )
    }

    // v2.6.2: Conjunto de herramientas de gestión de la base de conocimientos (eliminar documentos/Eliminar base de datos/Construir una base de datos/hacer una lista)
    single {
        io.zer0.muse.tools.KnowledgeManageToolsRegistrar(
            toolRegistry = get(),
            tools = io.zer0.muse.tools.KnowledgeManageTools(
                docDao = get(),
                kbDao = get(),
                ragService = get(),
            ),
        )
    }
    // v2.6.2: Lea el texto completo de la base de conocimientos (segmentado)
    single {
        io.zer0.muse.tools.KnowledgeReadToolRegistrar(
            toolRegistry = get(),
            docDao = get(),
        )
    }
    // v2.6.2: Conjunto de herramientas de gestión de sesiones (eliminado)/Cambiar título/Mover carpeta/Archivo)
    single {
        io.zer0.muse.tools.SessionManageToolsRegistrar(
            toolRegistry = get(),
            tools = io.zer0.muse.tools.SessionManageTools(
                sessionRepository = get(),
                folderRepository = get(),
            ),
        )
    }
    // v2.6.2: Conjunto de herramientas de gestión de habilidades (lista/Iniciar y detener/eliminar)
    single {
        io.zer0.muse.tools.SkillManageToolsRegistrar(
            toolRegistry = get(),
            tools = io.zer0.muse.tools.SkillManageTools(
                skillRepository = get(),
            ),
        )
    }
    // v2.6.2: Conjunto de herramientas de autogestión del asistente (permisos restringidos: solo cambie la personalidad, no el enlace)/modelo)
    single {
        io.zer0.muse.tools.AssistantManageToolsRegistrar(
            toolRegistry = get(),
            tools = io.zer0.muse.tools.AssistantManageTools(
                assistantRepository = get(),
            ),
        )
    }

    // Phase 8.8: Skill actuador(Kotlin Realización directa,No hay necesidad QuickJS)
    // v0.24: inyectar WebSearchService / KnowledgeDocDao / SkillRepository para la clase de búsqueda + install_skill
    // v0.46: Inyectar ChatService/AssistantRepository para delegate_agent(Colaboración entre múltiples agentes)
    // v1.30: Inyecte GroupChatRepository para la herramienta de chat grupal(channel_reply / channel_pass / channel_read_context)
    // P1-3b Desterritorialización: Skill Implementación de herramientas de archivos(Llamado por el delegado de SkillExecutor)
    single { io.zer0.muse.tools.SkillFileToolsImpl(androidContext(), get(named("chat"))) }
    // P1-3b Desterritorialización: Skill medios de comunicación/JS/Implementación de herramientas de complemento(Llamado por el delegado de SkillExecutor)
    single {
        io.zer0.muse.tools.SkillMediaToolsImpl(
            androidContext(),
            get(),
            get(),
            {
                get<io.zer0.muse.data.SettingsRepository>().imageGenConfigFlow.first().let { cfg ->
                    (
                        if (cfg.providerId.isBlank()) {
                            null
                        } else {
                            runCatching {
                                kotlinx.coroutines.runBlocking {
                                    get<io.zer0.muse.data.SettingsRepository>().getProviderById(cfg.providerId)
                                }
                            }.getOrNull()
                        }
                        ) to cfg.modelId
                }
            },
            get(),
            // Conmutador maestro enchufable: realice una lectura de ruta activa una vez y no habilite la suscripción.
            { get<io.zer0.muse.data.SettingsRepository>().arePluginsGloballyEnabled() },
        )
    }
    // P1-3b Desterritorialización: Skill Implementación de herramientas de gestión.(Llamado por el delegado de SkillExecutor)
    single { io.zer0.muse.tools.SkillManagementToolsImpl(androidContext(), get()) }
    // Implementación de la herramienta de complemento autoescrita del asistente(Llamado por el delegado de SkillExecutor):Sólo produce borradores sin firmar.
    single { io.zer0.muse.tools.PluginAuthoringToolsImpl(androidContext(), get()) }
    // P1-3b Desterritorialización: Skill Buscar/HTTP Implementación de herramientas(Llamado por el delegado de SkillExecutor)
    single { io.zer0.muse.web.WebSearchCoordinator(get<WebSearchService>()) }
    single {
        io.zer0.muse.tools.SkillSearchToolsImpl(
            androidContext(),
            get(named("chat")),
            get<WebSearchService>(),
            get(),
            get(),
            { get<io.zer0.muse.data.SettingsRepository>().getRagConfig() },
            get<io.zer0.muse.web.WebSearchCoordinator>(),
            {
                get<io.zer0.muse.data.SettingsRepository>().webSearchConfigFlow.first().let { cfg ->
                    io.zer0.muse.web.WebSearchPolicy(
                        maxSearchesPerTurn = cfg.maxSearchesPerTurn.coerceIn(1, 100),
                        maxResults = cfg.maxResults.coerceIn(1, 10),
                    )
                }
            },
            builtInKnowledgeDocSeeder = get(),
        )
    }
    // P1-3e Desterritorialización: Skill Agent Flujo de trabajo/Implementación de herramienta de chat grupal(Llamado por el delegado de SkillExecutor)
    single {
        io.zer0.muse.tools.SkillAgentToolsImpl(
            androidContext(),
            get(),
            get(),
            { runCatching { get<io.zer0.muse.schedule.GroupChatScheduler>() }.getOrNull() },
        )
    }

    // P1-3e Desterritorialización: Implementación de herramienta de traducción(Llamado por el delegado de SkillExecutor)
    single { io.zer0.muse.tools.TranslateToolsImpl() }

    // P1-3e Desterritorialización: delegateAgent darse cuenta(Llamado por el delegado de SkillExecutor)
    single {
        io.zer0.muse.tools.SkillDelegateAgentImpl(
            androidContext(),
            get(),
            get(),
            { get<io.zer0.muse.data.SettingsRepository>().multiAgentConfigCache },
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }

    single {
        io.zer0.muse.tools.SkillExecutor(
            androidContext(),
            get(named("chat")),
            webSearchService = get<WebSearchService>(),
            knowledgeDocDao = get(),
            skillRepository = get(),
            chatService = get(),
            assistantRepository = get(),
            groupChatRepository = get(),
            ragService = get(),
            ragConfigProvider = { get<io.zer0.muse.data.SettingsRepository>().getRagConfig() },
            stickerLibraryRepository = get(),
            imageService = get(),
            imageDrawConfigProvider = suspend {
                val settings = get<io.zer0.muse.data.SettingsRepository>()
                val cfg = settings.imageGenConfigFlow.first()
                val provider = if (cfg.providerId.isNotBlank()) {
                    kotlin.runCatching { settings.getProviderById(cfg.providerId) }.getOrNull()
                } else {
                    null
                }
                val modelId = if (provider != null && cfg.modelId.isNotBlank()) cfg.modelId else null
                provider to modelId
            },
            multiAgentConfigProvider = { get<io.zer0.muse.data.SettingsRepository>().multiAgentConfigCache },
            llmAggregator = get(),
            pauseManager = get(),
            delegationChainTracker = get(),
            agentDmRepository = get(),
            // v1.202 Retrofit 2: infraestructura necesaria para la delegación sin bloqueo(Registrado como singleton a continuación)
            deferredResultStore = get(),
            subagentThreadStore = get(),
            // v1.0.53: Limitador de simultaneidad global de subagente(Compartido por todos los portales de la delegación.)
            agentConcurrencyLimiter = get(),
            // v1.0.53 Phase 2: Registro de recuperación de puntos de interrupción del flujo de trabajo
            journal = get(),
            // v1.0.53 Phase 5: GroupChatScheduler Carga diferida provider(agent_phone Para herramientas).
            // Análisis perezoso con lambda:SkillExecutor Regístrate aquí(bloque único debajo),GroupChatScheduler en
            // line 192 Registrarse(Registrado ante SkillExecutor),Pero dentro de lambda get() Se analiza sólo cuando la herramienta se ejecuta realmente.,
            // En este punto ambos han sido inicializados.,Evite las dependencias circulares.
            // Utilice runCatching para descubrirlo:Devuelto cuando el entorno de prueba o Koin no se inicia null,agent_phone Herramienta rebajada a"No configurado".
            pluginManager = get(),
            fileTools = get(),
            searchTools = get(),
            managementTools = get(),
            mediaTools = get(),
            translateTools = get(),
            pluginAuthoringTools = get(),
            agentTools = get(),
            delegateTools = get(),
            groupChatSchedulerProvider = {
                runCatching { get<io.zer0.muse.schedule.GroupChatScheduler>() }.getOrNull()
            },
        )
    }

    // B6-01: Administrador de complementos externo(importar/Desinstalar/Iniciar y detener/Registro de herramientas)
    single { io.zer0.muse.data.plugin.PluginManager(androidContext(), get()) }
    // Phase 4: La representación de la piel se basa únicamente en interfaces estrechas,UI No contiene directamente la implementación del administrador de complementos.
    single<io.zer0.muse.ui.theme.PluginSkinSource> { get<io.zer0.muse.data.plugin.PluginManager>() }
    // v2.0.1: Componentes compartidos del mercado de complementos — Página de gestión de complementos(UI)y herramientas de backend(plugin_market_search/install)
    // Compartiendo la misma instancia: caché de directorio/La secuencia aceptada tiene un solo estado.,Varias instancias pueden hacer que la protección alternativa se bifurque.
    single { io.zer0.muse.data.plugin.market.PluginMarketSettings(androidContext()) }
    single { io.zer0.muse.data.plugin.market.PluginMarketTrustRoots(get()) }
    single {
        val trustRoots = get<io.zer0.muse.data.plugin.market.PluginMarketTrustRoots>()
        io.zer0.muse.data.plugin.market.PluginCatalogRepository(
            client = io.zer0.muse.data.plugin.market.PluginCatalogClient(),
            cacheDir = java.io.File(androidContext().filesDir, "plugin_market"),
            trustRootKeys = { trustRoots.current() },
        )
    }
    // v1.201: Gestor de pausas delegado(Singleton global,ChatViewModel Compartir con SkillExecutor)
    single { io.zer0.muse.tools.DelegationPauseManager() }
    // v1.201: Rastreador de enlaces delegados(Singleton global,ChatViewModel Compartir con SkillExecutor)
    single { io.zer0.muse.tools.DelegationChainTracker() }
    // v1.201: LLM Agregador de reseñas completo(Para la política TeamWorkflow LLM_REVIEW)
    single { io.zer0.muse.tools.LlmAggregator(get(), get()) }
    // v1.200: Agent enrutamiento automático(Recomendar el mejor asistente según el texto de la tarea + etiqueta de habilidad/equipo)
    // v2.x: Inyecte ChatService para admitir el enrutamiento semántico LLM(El interruptor está apagado de forma predeterminada,ver MultiAgentConfig.llmRoutingEnabled)
    single { io.zer0.muse.tools.AgentRouter(get(), get(), get()) }
    // v1.0.53 Phase 1: Libro mayor de hilos de subagente(Versión persistente,Reemplaza las herramientas antiguas. Versión de memoria SubagentThreadStore)
    //  - Room mesa subagent_threads(MIGRATION_58_59 crear):Metadatos del hilo + estado + runCount
    //  - JSONL Historial de subsesiones(filesDir/subagent_sessions/<threadId>.jsonl):Cada ronda de resultados de la herramienta LLM + se agrega de forma incremental
    //  - Dos rutas de subagente compartidas:camino A(SubagentTool + delegateAgent nonBlocking)+ camino B(SubagentRunner)
    single {
        io.zer0.muse.data.subagent.SubagentSessionStore(
            sessionsDir = java.io.File(androidContext().filesDir, "subagent_sessions").apply { mkdirs() },
            tokenEstimator = io.zer0.muse.util.TokenEstimator,
        )
    }
    single {
        io.zer0.muse.data.subagent.SubagentThreadStore(
            dao = get<io.zer0.muse.data.session.MuseDb>().subagentThreadDao(),
            sessionStore = get(),
        )
    }
    // v1.0.53: Limitador de simultaneidad global de subagente(Todos los portales de delegación comparten la misma cuota)
    single { io.zer0.muse.tools.AgentConcurrencyLimiter() }
    // v1.0.53 Phase 2: Registro de recuperación de puntos de interrupción del flujo de trabajo
    //  - Archivo: filesDir/workflow_journals/<runId>.jsonl
    //  - TeamWorkflowExecutor resume El nodo que llega al caché se devolverá en segundos.,Se reinicia el primer nodo sin caché.
    single {
        io.zer0.muse.tools.WorkflowJournal(
            journalDir = java.io.File(androidContext().filesDir, "workflow_journals").apply { mkdirs() },
            resultCipher = io.zer0.muse.data.SecureKeyStore,
        )
    }
    // v1.202: Comentarios asincrónicos sobre los resultados de las tareas delegadas(Infraestructura central de delegación sin bloqueo,El agente principal regresa inmediatamente. taskId)
    single { io.zer0.muse.tools.DeferredResultStore() }

    // Phase 5-E: analizador de documentos
    single { DocumentParser(get(named("chat"))) }

    // v0.30-a: Ensamblador de avisos del sistema(6 flujo de trabajo paso a paso,Ensamble de 1 a 9 secciones juntas)
    // v0.32 Experimental:Cierre transparente de getExperiments,Deje que el experimento cambie en la página de configuración.
    //         (forceMoodBlock / selfReflection)impacto real system prompt
    //         Los cierres se leen cada vez. settings.experimentsCache(@Volatile,Bloqueo cero),
    //         en lugar de almacenar en caché en el momento de la construcción,Asegúrese de que el usuario cambie la página de configuración para que surta efecto inmediatamente(Según el método de escritura MemoryConfigCache).
    // v1.25: Transmisión transparente simultánea getMultiAgentConfig,Las indicaciones de colaboración del agente leen Settings.multiAgentConfigCache.
    // v1.97: paso a través assistantRepository,Solicite a delegado_agent que inserte una lista de identificadores de asistente disponibles.
}

/**
 * El pequeño host de teléfono celular implementa la inyección.
 *
 * Koin 4.0 No `override<T>` DSL: La misma definición clave se expresa como "el orden de carga del módulo gana".
 * (`KoinApplication` Predeterminado allowOverride = true, la definición cargada más tarde reemplaza la definición cargada primero con la misma clave).
 * Por lo tanto, este módulo debe clasificarse [io.zer0.muse.miniphone.di.miniPhoneModule] Después de eso,
 * Cubre los marcadores de posición en este módulo desde aquí MiniPhoneHost —— ver [allKoinModules].
 */
val miniPhoneHostModule = module {
    // Una ronda de generación de herramientas en mensajes privados:La superficie de la herramienta se estrecha dentro del canal.,Aquí solo se dan el formulario de entrada y registro.
    single {
        io.zer0.muse.data.miniphone.MiniPhoneToolTurnRunner(
            chatService = get<io.zer0.ai.ChatService>(),
            toolRegistry = get<io.zer0.muse.tools.ToolRegistry>(),
        )
    }
    single<io.zer0.muse.miniphone.api.MiniPhoneHost> {
        val tts = get<io.zer0.muse.ui.speech.TtsManager>()
        io.zer0.muse.data.miniphone.AppMiniPhoneHost(
            assistantRepository = get(),
            settingsRepository = get(),
            textGenerator = get(),
            contextBuilder = get(),
            toolTurnRunner = get(),
            // TtsManager No implementa VoiceSynthesizer (criarlo como padre rompería la exención de nivel de clase de detekt),
            // El método de conexión es coherente con el método send_voice de AppMediaModule:SAM Adaptarse una vez
            voiceSynthesizer = { text, file, emotion -> tts.synthesizeTo(text, file, emotion) },
            voiceStore = get<io.zer0.muse.ui.speech.VoiceMessageStore>(),
            cacheDir = androidContext().cacheDir,
        )
    }
    single<io.zer0.muse.data.miniphone.MiniPhoneTextGenerator> {
        io.zer0.muse.data.miniphone.MomentChainTextGenerator(
            chatService = get(),
            contextBuilder = get(),
            settings = get(),
        )
    }
}

/**
 * Todos los módulos Koin se cargan cuando se inicia la aplicación.
 */
val allKoinModules = listOf(
    appModule,
    appChatModule,
    appInfraModule,
    appPromptModule,
    appToolModule,
    appMediaModule,
    appRagModule,
    appViewModelModule,
    // G4: Figura de referencia Puente de verificación de la SSRF — Registrarse RefImageUrlValidator(SsrfGuard.refImageUrlValidator),
    // Inyectado por AgnesImageProvider de aiModule(Debe estar registrado antes de aiModule)
    SsrfBridgeModule,
    aiModule,
    memoryModule,
    // P0: Pequeño módulo de teléfono celular (viene con la biblioteca Room + marcador de posición MiniPhoneHost)
    io.zer0.muse.miniphone.di.miniPhoneModule,
    // Debe clasificarse después de lo anterior: con la misma definición de clave, prevalecerá la que se cargue más tarde, y la implementación del marcador de posición se anula aquí.
    miniPhoneHostModule,
)
