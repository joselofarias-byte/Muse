package io.zer0.muse

import io.zer0.muse.data.SettingsRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * P2-3 Desdominio: plantilla de Word rápida, generación de portada,Hook registro,SystemPromptAssembler Registre módulos independientes.
 */
val appPromptModule = module {

    // Phase 12: PromptTemplateLoader — Cargue plantillas de mensajes de texto desde activos/prompt_templates/
    single { io.zer0.muse.transformer.PromptTemplateLoader(androidContext()) }

    // v1.0.53: Biblioteca de portadas + generación de portadas con IA(cubrir el flujo de trabajo)
    single { io.zer0.muse.data.cover.CoverLibraryRepository(androidContext()) }
    single {
        io.zer0.muse.tools.CoverGenerator(
            context = androidContext(),
            templateLoader = get(),
            chatService = get(),
            imageService = getOrNull(),
            coverLibraryRepository = get(),
            okHttpClient = get(named("chat")),
            settingsRepository = get(),
        )
    }

    // P1-1: Hook Registro(Singleton global,Todos los Hooks se registran a través de este)
    single { io.zer0.muse.hook.HookRegistry() }

    single {
        val settings = get<SettingsRepository>()
        io.zer0.muse.transformer.SystemPromptAssembler(
            promptLoader = get(),
            context = androidContext(),
            settings = settings,
            memoryTicker = get(),
            toolRegistry = get(),
            skillRepository = get(),
            getExperiments = { settings.experimentsCache },
            getMultiAgentConfig = { settings.multiAgentConfigCache },
            assistantRepository = get(),
            // v1.98: paso a través experienceRepository,Cuando el interruptor de la biblioteca de experiencias está activado, las entradas de experiencias se inyectan en system prompt
            experienceRepository = get(),
            // v1.202: paso a través agentDmRepository,Inyecte el resumen de la bandeja de entrada cuando el asistente principal genere el mensaje del sistema
            agentDmRepository = get(),
            // v2.x: paso a través groupChatMemoryRepository,El asistente principal inyecta el resumen de la memoria del chat grupal al generar el mensaje del sistema.
            // (usar <group_chat_memory> Etiquetas y memoria principal <long_term_memory> distinguir,No contamina la memoria principal)
            groupChatMemoryRepository = get(),
            // v1.0.52: paso a través sessionRepository,Se inyecta cuando el asistente principal genera un mensaje del sistema. Recent Chats Reference
            // (usar <recent_chats> Etiquetas que envuelven títulos de sesiones recientes+Vista previa,Proporcionar un contexto de continuidad de la conversación.)
            sessionRepository = get(),
            // v12 (T2-2): paso a través factStore,Cuando el asistente principal genere el mensaje del sistema, presione el FTS del problema actual para recuperar recuerdos relevantes.
            factStore = get(),
            // v1.0.86: Las memorias relacionadas son independientes de la selección actual del Asistente facts.db
            factDbProvider = get(),
            // Reparación de auditoría (S-03): paso a través pinnedMemoryStore,Fuente de datos de memoria anclada unificada
            // (El lado previamente inyectado dice que nadie escribió pinned_memories.json,El contenido fijado nunca se inyecta)
            pinnedMemoryStore = get(),
            // P1-1: paso a través hookRegistry,SystemPromptComposeHook Llamado al final de la construcción.
            hookRegistry = get(),
            // v2.x: paso a través stickerRepository,Instrucciones para inyectar emoticones cuando la biblioteca no está vacía y el interruptor está encendido
            stickerRepository = get(),
            // v2.6.7(P1-1): Inyección de estado civil (intimidad/etiqueta de relación/Interacciones recientes)
            relationshipRepository = get(),
            // Política de aprobación de herramientas: el número de "invocables en esta ronda" en la palabra del mensaje tiene la misma fuente que el esquema emitido y las herramientas con ALWAYS_DENY deben excluirse
            toolConfigStore = get(),
        )
    }
}
