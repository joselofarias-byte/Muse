package io.zer0.memory

import androidx.room.Room
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.deep.DeepMemoryProcessor
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactStore
import io.zer0.memory.summary.MEMORY_DB_VERSION
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.MemoryDbArchiveRecovery
import io.zer0.memory.summary.MemoryDbDowngradeGuard
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryTicker
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * memory Montaje de módulos Koin.
 *
 * Dependencias:
 *  - Room: MemoryDb(Resumen/Estado/compilar producto) + FactDb(FTS5 metahechos)
 *  - [io.zer0.memory.llm.MemoryLlmClient] Implementado por el módulo de la aplicación.(confiado a ChatService)
 *  - [CoroutineScope] Proporcionado por el módulo de la aplicación.(application scope,ticker Para tareas en segundo plano)
 *
 * Nota: [MemoryTicker] Registrado por módulo de aplicación(Necesita leer el interruptor de memoria de SettingsRepository),
 * Aquí solo se registran la capa de datos + los servicios principales.
 */
val memoryModule: Module = module {

    // ── Room base de datos ──
    single {
        val context = androidContext()
        MemoryDbDowngradeGuard.archiveIfNewer(
            context = context,
            databaseName = MEMORY_DB_NAME,
            currentVersion = MEMORY_DB_VERSION,
        )
        Room.databaseBuilder(context, MemoryDb::class.java, MEMORY_DB_NAME)
            // v2: session summary Aumente space_id, realice solo migración aditiva y conserve los datos antiguos.
            // v2.x: Se agregaron context_checkpoints (punto de control de compresión de sesión), que también es una tabla puramente incremental.
            .addMigrations(
                io.zer0.memory.summary.MemoryDb.MIGRATION_1_2,
                io.zer0.memory.summary.MemoryDb.MIGRATION_2_3,
                io.zer0.memory.summary.MemoryDb.MIGRATION_3_4,
                io.zer0.memory.summary.MemoryDb.MIGRATION_4_5,
            )
            // La reconstrucción sólo está permitida al bajar de categoría; abriendo repositorios superiores de archivos de preguardia y sidecars WAL.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()
    }
    // Explicit recovery entry for rows preserved in a newer-version archive after downgrade.
    single { MemoryDbArchiveRecovery(androidContext(), get()) }

    // Lectura y determinación de validez de los puntos de control del contexto de la sesión (para inyección por parte del ensamblador de contexto)
    single { io.zer0.memory.summary.ContextCheckpointReader(get()) }

    // v0.22: per-assistant facts.db proveedor(Crear por ID de asistente/Caché independiente FactDb)
    // v12: Determinador de deduplicación transparente de LLM(Los candidatos difusos de la misma entidad se entregan al modelo grande para su evaluación.)
    // Nota: No inyectado FactRevisionDao(Obtenido internamente de db),Evite las dependencias circulares de Koin
    single { io.zer0.memory.fact.FactDbProvider(androidContext(), get<io.zer0.memory.fact.FactDedupJudge>()) }

    // v1.78 (M12): Global FactDb reutiliza FactDbProvider "default" Ejemplo
    // Evite que FactDb.create y FactDbProvider creen cada uno una instancia de Room que apunte al mismo archivo Facts.db
    single {
        get<io.zer0.memory.fact.FactDbProvider>().getFactDb("default")
    }

    // ── DAO ──
    single { get<MemoryDb>().sessionSummaryDao() }
    single { get<MemoryDb>().contextCheckpointDao() }
    single { get<MemoryDb>().dailyStateDao() }
    single { get<MemoryDb>().compiledSectionDao() }
    single { get<MemoryDb>().scopedCompiledSectionDao() }
    single { get<FactDb>().factDao() }
    // v13 (T4-1): expediente de revisión de hechos DAO
    single { get<FactDb>().factRevisionDao() }
    // v1.0.52 P2-2: Aislamiento de múltiples espacios DAO
    single { get<FactDb>().memorySpaceDao() }
    // v1.0.52 P2-3: Borde del gráfico de conocimiento de la memoria DAO
    single { get<FactDb>().memoryLinkDao() }

    // ── Servicios básicos ──
    single { SessionSummaryManager(get(), get()) } // dao + llmClient
    // v6: Los productos de compilación de memoria se envían al sistema de archivos al mismo tiempo.(memory.md + daily/)
    // Revisión de corrección (2.0 C-03): Eliminar el registro duplicado a continuación(Igual que el singleton clave,Este último siempre cubre al primero.,asamblea muerta)
    single { io.zer0.memory.compile.MemoryFileWriter(androidContext().filesDir) }
    // S-04/v15: MemoryCompiler resolves per-assistant FactStore tombstones; the shared JSON file is legacy read-only.
    // v12: Inyectar determinante de deduplicación LLM(Implementado por el módulo de la aplicación.,Noop no está sintonizado por defecto LLM)
    single {
        MemoryCompiler(
            sectionDao = get(),
            llmClient = get(),
            fileWriter = get(),
            factStore = get(),
            scopedSectionDao = get(),
            factDbProvider = get(),
            compileContext = io.zer0.memory.compile.MemoryCompileContext(),
        )
    } // legacy sectionDao + llmClient + fileWriter + factStore + scoped DAO
    single {
        FactStore(
            get(),
            get(),
            java.io.File(androidContext().filesDir, "fact_tombstones.json"),
            get<io.zer0.memory.fact.FactDedupJudge>(),
            get(),
            assistantId = "default",
            clearLegacyTombstonesOnReset = true,
        )
    }
    single { DeepMemoryProcessor(get<io.zer0.memory.fact.FactDbProvider>(), get()) } // factDbProvider + llmClient

    // v2.6.2(comentarios de los usuarios): Notificación → Memory Carder (cardado de modelos de gran herramienta/integrar/Elimine los duplicados y luego suéltelos en la biblioteca).
    single { io.zer0.memory.ai.NotificationMemoryDigest(get(), get()) } // factDbProvider + llmClient

    // v2.6.2(comentarios de los usuarios): Constructor de complementos de constelaciones de memoria (clave de entidad)/Misma sesión/Misma categoria/vecino semántico)
    single { io.zer0.memory.ai.MemoryEdgeBuilder(get(), get()) } // memoryLinkDao + llmClient(LLM para profundizar)

    // v12 (T3-1): tarea de reflexión de la memoria — Clasificación diaria(Rellenar claves de entidad/fusionar duplicados/Detección de contradicciones/promoción)
    // P2-32: Inyectar la lista de conflictos en la base de datos.(No más simplemente iniciar sesión,El centro de memoria se puede leer y borrar.)
    single {
        io.zer0.memory.reflection.MemoryContradictionStore(
            java.io.File(androidContext().filesDir, "memory_contradictions.json"),
        )
    }
    single {
        io.zer0.memory.reflection.MemoryReflectionRunner(
            factStore = get(),
            contradictionSink = { scope, spaceId, pairs ->
                get<io.zer0.memory.reflection.MemoryContradictionStore>()
                    .save(scope, spaceId, pairs.map { it.first.fact to it.second.fact })
            },
            // v2.6.2(auditoría de memoria): Analizar el FactStore del asistente correspondiente por alcance (scope=="main" → biblioteca predeterminada),
            // De lo contrario, subayudante/El reflejo de otros espacios caerá en la biblioteca predeterminada.
            storeResolver = { scope, _ ->
                val provider = get<io.zer0.memory.fact.FactDbProvider>()
                val assistantId = scope.takeIf { it.isNotBlank() && it != "main" } ?: "default"
                provider.getFactStore(assistantId)
            },
        )
    }

    // v1.0.52 P2-2: almacén de espacio de memoria(Space CRUD + migración de hecho)
    single { io.zer0.memory.space.MemorySpaceRepository(get(), get()) }

    // Almacenamiento de memoria de pines
    single { io.zer0.memory.pin.PinnedMemoryStore(java.io.File(androidContext().filesDir, "pinned_memory")) }
}

private const val MEMORY_DB_NAME = "memory.db"
