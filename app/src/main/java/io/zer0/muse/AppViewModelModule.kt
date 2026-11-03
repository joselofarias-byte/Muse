package io.zer0.muse

import android.app.Application
import io.zer0.muse.ui.MemoryViewModel
import io.zer0.muse.ui.groupchat.GroupChatViewModel
import io.zer0.muse.ui.memory.MemorySpaceViewModel
import io.zer0.muse.ui.stats.StatsViewModel
import io.zer0.muse.ui.translate.TranslateViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * P2-3 Deslocalización:ViewModel Registre un módulo independiente para reducir el tamaño de AppKoinModule.
 */
val appViewModelModule = module {

    // Etapa 6: MemoryViewModel inyecta los 3 servicios principales del módulo de memoria
    // v0.51: Agregue MemoryTicker para leer HealthFlow y recortarlo. compiledMarkdown
    // v1.98: Agregar configuración + repositorio de experiencias para la biblioteca de experiencias CRUD y cambiar de suscripción
    viewModel {
        MemoryViewModel(
            application = androidContext() as Application,
            factStore = get(),
            summaryManager = get(),
            memoryCompiler = get(),
            memoryTicker = get(),
            settings = get(),
            experienceRepository = get(),
            assistantRepository = get(),
            sessionRepository = get(),
            spaceRepository = get(),
            groupChatMemoryRepository = get(),
            groupChatRepository = get(),
            chatService = get(),
            // F-8: inyectar PinnedMemoryStore,La fijación de páginas de memoria y la herramienta pin_memory comparten la misma fuente de datos
            pinnedMemoryStore = get(),
            // P0-4: inyectar FactDbProvider — El centro de memoria se enruta a la biblioteca sucursal del lado de producción según el alcance.(facts_<id>.db)
            factDbProvider = get(),
            // P2-32: Almacenamiento de lista de contradicciones(Prueba de reflexión diaria → El centro de memoria se puede leer y borrar.)
            contradictionStore = get(),
            // v1.0.92: LLM Consolidador de memoria(manuales"organizar la memoria"Comparte la misma implementación con integración automática diaria.)
            factConsolidator = get(),
        )
    }

    // v1.0.52 P2-2: gestión del espacio de memoria ViewModel
    viewModel {
        io.zer0.muse.ui.memory.MemorySpaceViewModel(
            application = androidContext() as Application,
            spaceRepository = get(),
        )
    }

    // constelación de memoria ViewModel(inyectar FactStore + MemoryLinkDao)
    viewModel {
        io.zer0.muse.ui.memory.MemoryGraphViewModel(
            application = androidContext() as Application,
            factStore = get(),
            memoryLinkDao = get(),
            factDbProvider = get(),
            // v2.6.2(comentarios de los usuarios): Generador de bordes complementario + proveedor de vectores semánticos (para reconstrucción manual de asociaciones)
            edgeBuilder = get(),
            semanticProvider = io.zer0.memory.ai.MemoryEdgeBuilder.SemanticProvider { texts ->
                val cfg = get<io.zer0.muse.data.SettingsRepository>().getRagConfig()
                get<io.zer0.muse.rag.EmbeddingService>().getProvider(cfg).embed(texts)
            },
        )
    }

    // v0.46: Página de estadísticas ViewModel(inyectar MessageDao + SessionDao)
    // SessionDao.count() para el número total de sesiones(Arreglar la versión anterior donde totalSessions siempre es 0 bug)
    // v0.47: Inyectar SettingsRepository + AssistantRepository para inspección inversa del modelo/Nombre para mostrar del asistente
    viewModel {
        StatsViewModel(
            application = androidContext() as Application,
            messageDao = get(),
            sessionDao = get(),
            settingsRepository = get(),
            assistantRepository = get(),
        )
    }

    // v1.97 gap8: traducción independiente ViewModel(inyectar ChatService + TtsManager,Reutilice las capacidades universales de lectura y finalización de textos)
    // v1.0.17: inyectar TranslateHistoryDao,El historial de traducción persiste Room
    // v1.0.30 gap4.6: inyectar GlossaryStore,Agregar directiva de glosario al traducir
    viewModel {
        io.zer0.muse.ui.translate.TranslateViewModel(
            chatService = get(),
            ttsManager = get(),
            appContext = androidContext(),
            translateHistoryDao = get(),
            glossaryStore = get(),
        )
    }

    // Multi-Agent La orquestación visual del flujo de trabajo se ha incorporado a la configuración de colaboración en equipo.,WorkflowEditorViewModel Eliminar junto con páginas inalcanzables
    // v1.30: chat grupal ViewModel(inyectar GroupChatRepository + Scheduler + AssistantRepo + Settings)
    // H-GC2 Reparación: Eliminar el parámetro appScope,init El recopilador de flujo usa viewModelScope para cancelar automáticamente
    // ActivityHub: inyectar GroupChatActivityHub,Suscríbase a sus actividades para obtener la lista actual de actividades de chat grupal para UI
    viewModel {
        GroupChatViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            androidContext(),
        )
    }

    // v1.0.17: registro rápido ViewModel(inyectar QuickNoteDao,Room Persistencia + Papelera de reciclaje)
    // v1.0.18: aumentar androidContext()(ReminderStore + AlarmManager Recordatorio de programación)
    viewModel { io.zer0.muse.ui.quicknotes.QuickNotesViewModel(get(), androidContext()) }
    // P1-2: Worldbook gestión ViewModel
    viewModel { io.zer0.muse.ui.worldbook.WorldBookViewModel(get(), androidContext()) }
}
