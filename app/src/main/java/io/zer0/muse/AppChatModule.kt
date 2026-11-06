package io.zer0.muse

import io.zer0.muse.ui.ChatViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * P2-3 Deslocalización:ToolOrchestrator / Instantánea del árbol de diálogo/módulo independiente registrado ChatViewModel.
 */
val appChatModule = module {

    // Phase 8.2 / 8.4 / 8.5 / 8.6 / 8.7 / 8.8 / 9.1: ChatViewModel Inyectar 20 dependencias
    // v0.30-a: Nuevo systemPromptAssembler
    // v1.43: Nuevo chatGenerationManager / artifactRepository / appContext
    // (chat/settings/ticker/session/image/doc/tool/assistant/webSearch/lorebook/quickMsg/promptInj/ocr/tts/skillRepo/skillExec/folder/notification/assembler/generation/artifacts/context/audit/sessionPermission)
    // v1.92: Cambiar a single — Singleton de nivel de aplicación,Cortar páginas/Las rutas de corte no se destruyen.,La generación es ininterrumpida.
    // usar viewModel{} unirse a NavBackStackEntry,Al regresar de CHAT_DETAIL onCleared
    // Actualice el contenido de la transmisión al ViewModel destruido _state,No se pueden ver nuevas instancias → Percepción"interrumpir".
    // Cambiar a single{} + koinInject() Posteriormente, todas las páginas comparten la misma instancia.,La compilación continúa actualizando el mismo _state.
    // B2-04: Unificar herramientaOrquestador singleton(accessor/taskCardCoordinator Pasado por la persona que llama runLoop)
    single {
        io.zer0.muse.tools.ToolOrchestrator(
            toolRegistry = get(),
            skillRepository = get(),
            skillExecutor = get(),
            assistantRepository = get(),
            sessionRepository = get(),
            context = androidContext(),
            hookRegistry = get(),
            auditLogger = get(),
            // F-08: Agent Run Libro mayor de memoria de recibos
            agentRunTracker = get(),
            // v1.x: Registro del navegador a nivel de sesión(Comparte la misma instancia con la cápsula UI.)
            browserManagerRegistry = get(),
            // Generación unificada/Registro de recursos de ejecución de herramientas
            executionRegistry = get(),
        )
    }

    // F-08: Agent Run Libro mayor de memoria de recibos(Cada vez que la herramienta realiza una escritura,para depurar/Visualización de auditoría)
    single { io.zer0.muse.data.audit.AgentRunTracker() }

    // P0 Árbol de diálogo para seleccionar almacenamiento de instantáneas(Recuperación de selección de sucursal entre reinicios)
    single { io.zer0.muse.data.chat.ConversationTreeSnapshotStore(androidContext()) }

    single {
        ChatViewModel(
            // v2.x: checkpointReader Inyectar con parámetros nombrados —— Las siguientes 40 dependencias están ordenadas por puesto.
            // Insertar parámetros en el medio hará que estén completamente fuera de lugar (los campos pasan de A a B). La inyección nombrada puede evitar este accidente.
            get(), get(), get(), get(),
            checkpointReader = get(),
            get(), get(), get(), get(), get(),
            get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(), get(),
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(),
            // v1.202: deferredResultStore + subagentThreadStore
            get(), get(),
            // v1.x: ConversationSessionManager(Recuento de referencias a nivel de sesión + limpieza inactiva)
            get(),
            // P1-1: HookRegistry(Inject ToolOrchestrator + procesamiento de mensajes Hook)
            get(),
            // v1.0.52 P2-3: MemoryAutoSaveScheduler(AI Memoria guardada automáticamente)
            get(),
            // B0-08: MilestoneChecker(disparador de hito)
            get(),
            // B2-04: ToolOrchestrator(Koin singleton)
            get(),
            // B2-04: ToolApprovalRouter(Puente de aprobación de subagente)
            get(),
            // P0 Árbol de diálogo para seleccionar almacenamiento de instantáneas
            treeSnapshotStore = get(),
            // v1.x: Registro de instancias de navegador a nivel de sesión
            browserManagerRegistry = get(),
            // v1.x: Almacenamiento de políticas de aprobación de herramientas(Consistente con el singleton de AppToolModule,Evite las instancias duales de DataStore)
            toolConfigStore = get(),
            // MCP Registro:Espere a que se complete el servidor vinculado por el asistente antes de enviar el primer mensaje. tools/list
            mcpRegistry = get(),
            // Generación unificada/Registro de recursos de ejecución de herramientas
            executionRegistry = get(),
            // P2-23: Implementación de herramienta de generación de medios.(AppKoinModule singleton) —
            // ChatViewModel Instalarlo durante la inicialización ChatMediaGenHost(Generar resultados escritos en el mensaje de ayuda.)
            mediaGenTools = get(),
            // v2.x: comité — Programador de chat grupal/almacén(Comparte el mismo singleton con la página de chat grupal.)
            groupChatScheduler = get(),
            groupChatRepository = get(),
            conversationSummaryManager = get(),
            // v2.6.7: Almacén relacional (colocado al final, los parámetros con nombre no afectarán los parámetros posicionales anteriores)
            relationshipRepository = getOrNull(),
        )
    }
}
