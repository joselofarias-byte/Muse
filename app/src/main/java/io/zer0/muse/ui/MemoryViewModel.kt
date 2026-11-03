package io.zer0.muse.ui
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.fact.FactStore
import io.zer0.memory.space.MemorySpaceEntity
import io.zer0.memory.space.MemorySpaceRepository
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.experience.DEFAULT_EXPERIENCE_CATEGORY
import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.experience.ExperienceRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MuseDateFormats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Etapa 6: Memorizar el modelo de datos de la interfaz de usuario de la página.
 *
 * 4 sistema de memoria de capas(Fact / Summary / Compile / Deep)Proyección de UI unificada para:
 *  - Fact capa:Lista de metahechos(Buscable)
 *  - Summary capa:Lista de resumen de desplazamiento de sesión
 *  - Compile capa:4 producto de compilación de segmentos(facts/today/week/longterm)
 *  - Deep capa:Sin almacenamiento independiente,mostrar estadísticas(Inferido del número total de hechos + hora de la última actualización)
 */

/** F-7: Elementos de filtro de rango de tiempo de memoria(Por creadoEn el número de días desde hoy,ALL significa todo). */
enum class MemoryTimeRange(val days: Long?) {
    ALL(null),
    LAST_7(7),
    LAST_30(30),
    LAST_90(90),
}

data class MemoryUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val factItems: List<MemoryItem> = emptyList(),
    val summaryItems: List<MemoryItem> = emptyList(),
    val compileItems: List<MemoryItem> = emptyList(),
    /** P2-32: Lista de memoria contradictoria para el alcance/espacio actual(Prueba de reflexión diaria,Se puede borrar después de la confirmación del usuario.). */
    val contradictions: List<io.zer0.memory.reflection.MemoryContradictionStore.ContradictionPair> = emptyList(),
    val searchResults: List<MemoryItem> = emptyList(),
    val isSearching: Boolean = false,
    val factCount: Int = 0,
    val summaryCount: Int = 0,
    val lastUpdatedAt: String? = null,
    /** Pila de errores completa(Para visualización desplazable de UI,Conveniente para que los usuarios copien a los desarrolladores para localizar problemas). */
    val errorTrace: String? = null,
    /**
     * v0.51: Recuerde los campos de descripción general del panel.
     *
     *  - [compiledMarkdown]:Resumen de memoria.md recientemente compilada(por [MemoryTicker.readCompiledMemoryMarkdown]
     *    Complete el texto de rebajas después de recortarlo en tokenBudget,suministro"AI comprensión de ti"pantalla plegable de tarjeta).
     *  - [healthMap]:MemoryTicker Instantáneas de salud de cada paso(rollingSummary/compileDaily/compileToday/
     *    rollDailyWindow/compileFacts/deepMemory),Se utiliza para mostrar el estado de la memoria en el tablero.
     *  - [lastCompileTime]:Última hora de compilación exitosa(de [healthMap] El lastSuccessAt toma el valor máximo).
     */
    val compiledMarkdown: String = "",
    val healthMap: Map<String, MemoryTicker.StepHealth> = emptyMap(),
    val lastCompileTime: String? = null,
    /** v4: Copia de estado de sincronización(Como por ejemplo"Última compilación hace 5 minutos,Es posible que las conversaciones recientes aún no estén en la memoria"). */
    val syncStatus: String = "",
    /** v4: ¿Está el estado de sincronización en"Puede que no esté en la memoria"estado de caducidad. */
    val syncStale: Boolean = false,
    /** v5: Memorizar campos estadísticos. */
    val totalFactCount: Int = 0,
    val weekNewCount: Int = 0,
    val monthNewCount: Int = 0,
    /** v5: distribución de importancia (0/1/2 contar). */
    val importanceDistribution: Map<Int, Int> = emptyMap(),
    /** v5: Sesiones más activas(top 5, sessionId → count). */
    val topSessions: List<Pair<String, Int>> = emptyList(),
    /** v5: cerca30Número de nuevas incorporaciones por día(Fecha → Número de artículos). */
    val dailyTrend: List<Pair<String, Int>> = emptyList(),
    /**
     * v1.98: Experimente el estado relacionado con la biblioteca.
     *
     * - [experienceEnabled]:Cambio de biblioteca de experiencias(El usuario cambia a la página de configuración.,reflexión en tiempo real).
     * - [experienceItems]:Lista de elementos de experiencia(Orden descendente por actualizadoEn,Para visualización y edición de UI).
     * - [experienceCount]:Número total de entradas(para métricas del panel).
     */
    val experienceEnabled: Boolean = false,
    val experienceItems: List<MemoryItem> = emptyList(),
    val experienceCount: Int = 0,
    /**
     * v1.0.72: Memoria de chat grupal(Independiente del sistema de memoria principal).
     *
     * visualización en el centro de memoria"chat grupal"Tab,para que los usuarios vean/Eliminar un solo elemento/Borrar con un clic.
     * Los datos provienen de [io.zer0.muse.data.groupchat.GroupChatMemoryRepository].
     */
    val groupChatMemories: List<GroupChatMemoryUiItem> = emptyList(),
    val groupChatMemoriesLoading: Boolean = false,
)

/**
 * Elemento de interfaz de usuario de memoria única — Forma unificada en 4 capas.
 *
 * @param id identificador único(factId / sessionId / sectionKey)
 * @param title Título(resumen de hechos/título de la sesión/nombre de la sección compilada)
 * @param content contenido completo(markdown amigable)
 * @param tags etiqueta(Sólo la capa de hechos tiene)
 * @param time copia de marca de tiempo
 * @param source capa fuente("Fact" / "Summary" / "Compile")
 */
data class MemoryItem(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val time: String? = null,
    val source: String,
    /** v4: importancia(0=ordinario,1=importante,2=clave). Sólo la capa de hechos tiene un valor. */
    val importance: Int = 0,
    /** v4: sesión fuente id(Sólo la capa de hechos tiene un valor,usado para mostrar la fuente). */
    val sessionId: String? = null,
    /** v4: tiempo de almacenamiento ISO 8601(para exhibición"Xdías de almacenamiento por adelantado"). */
    val createdAt: String? = null,
    /** v1.98: Clasificación(Sólo la capa Experiencia tiene un valor,Rellenar el formulario al editar). */
    val category: String? = null,
    /**
     * v8: alcance de la memoria(Sólo la capa de hechos tiene un valor).
     *  - "main":alcance de ayuda principal
     *  - assistantId:sub-asistente/Alcance de los miembros del equipo
     * Se utiliza para que la interfaz de usuario muestre la insignia de alcance(asistente principal=Color predeterminado,sub-asistente=tertiary Color).
     */
    val scope: String? = null,
    /** B4-05: Tiempo de fijación manual ISO 8601,null Indica que no está fijado. */
    val pinnedAt: String? = null,
    /** confianza(0-1). compilar/Los hechos inferidos pueden ser bajos.,solía juzgar“¿Quieres tomar esto en serio?”. */
    val confidence: Float = 1.0f,
    /** La hora del resultado de búsqueda más reciente ISO 8601,null = Nunca golpees. */
    val lastHitAt: String? = null,
    /** Número acumulado de visitas(0 = nunca recuperado). */
    val hitCount: Int = 0,
    /** El espacio de memoria al que pertenece el resumen de la sesión.;Los elementos de datos los conserva el propio almacén de datos. */
    val spaceId: String? = null,
    /** Fusionado en otro recuerdo(No participar en el retiro,La lista aún está visible). */
    val mergedIntoId: Long? = null,
    /** Salió de la recuperación automática(Decaimiento por debajo del umbral,ok todavía está ahí). */
    val suppressed: Boolean = false,
    /** reemplazado por otros recuerdos(Contenido anulado por nuevos recuerdos.). */
    val superseded: Boolean = false,
) {
    /** Proyección estatal compartida por insignias de lista y descripciones detalladas(Los tres estados son independientes entre sí.,Puede golpear simultáneamente). */
    val status: MemoryFactStatus
        get() = MemoryFactStatus(merged = mergedIntoId != null, suppressed = suppressed, superseded = superseded)
}

/**
 * Proyección del estado de memoria "no en uso normal".
 *
 * Los tres estados se determinan de forma independiente. —— Un recuerdo puede aparecer y fusionarse.,Hacer una enumeración mutuamente excluyente haría que la lista
 * Falta una de las verdaderas razones.,La insignia que ve el usuario no es suficiente para explicar por qué no funciona.
 *
 * @param merged Fusionado en otro recuerdo(Apunta a la nueva fila después de la agregación.)
 * @param suppressed Salió de la recuperación automática(Decaimiento por debajo del umbral,ok todavía está ahí)
 * @param superseded reemplazado por otros recuerdos(Contenido anulado por nuevos recuerdos.)
 */
data class MemoryFactStatus(
    val merged: Boolean = false,
    val suppressed: Boolean = false,
    val superseded: Boolean = false,
) {
    /** Ninguno de los tres estados afectó = Todavía en el enlace de recuperación normal. */
    val isInactive: Boolean get() = merged || suppressed || superseded
}

/**
 * La clave de comparación para la colección "reemplazada".
 *
 * Si el fact.id de cada base de datos de sucursal se incrementa de forma independiente, entrará en conflicto con el número.,Sólo la ID desnuda reconocerá la memoria del mismo número en la biblioteca del subasistente que la fila actual.
 */
internal fun memoryFactKey(scope: String?, id: Long): String = "${scope.orEmpty()}:$id"

/**
 * Determinación del estado de una única fila de hechos(función pura,Conveniente para pruebas individuales).
 *
 * @param mergedIntoId nuestro banco merged_into_id
 * @param suppressedAt nuestro banco suppressed_at(Si no está vacío es que ya apareció.)
 * @param supersededKeys por [memoryFactKey] Una colección de "reemplazados por otras filas"
 */
internal fun memoryFactStatusOf(
    scope: String?,
    id: Long,
    mergedIntoId: Long?,
    suppressedAt: String?,
    supersededKeys: Set<String>,
): MemoryFactStatus = MemoryFactStatus(
    merged = mergedIntoId != null,
    suppressed = suppressedAt != null,
    superseded = memoryFactKey(scope, id) in supersededKeys,
)

/**
 * v8: Filtros de alcance de memoria(Para renderizar mediante FilterChip encima de la interfaz de usuario).
 *
 *  - [id]:identificador de alcance("main" o assistantId),null expresar"Todos los alcances"(UI marca con todo).
 *    Para facilitar la diferenciación de la interfaz de usuario"Todos"con alcance específico,uso [isAll] marca.
 *  - [displayName]:UI nombre para mostrar("Todos" / "asistente principal" / Nombre del asistente).
 *  - [isMain]:Si es el alcance de ayuda principal(Utilice colores predeterminados frente a colores terciarios para la interfaz de usuario).
 *  - [isAll]:¿Es"Todos"Opciones(para visualización de interfaz de usuario"Todos"y seleccionado por defecto).
 */
data class ScopeOption(
    val id: String?,
    val displayName: String,
    val isMain: Boolean = false,
    val isAll: Boolean = false,
)

/**
 * v1.0.72: Entrada de UI de memoria de chat grupal(centro de memoria"chat grupal"Tab).
 *
 * @param id Memoria de chat grupal id
 * @param groupChatId chat grupal id
 * @param groupChatName Nombre del chat grupal(Analizado desde GroupChatRepository,Repliegue ante el fracaso "chat grupal")
 * @param assistantName Nombre del asistente de voz(Analizar desde AssistantRepository,Volver al original en caso de error id)
 * @param summary texto resumido
 * @param createdAt Crear marca de tiempo
 * @param timeText Formatear copia de hora
 */
data class GroupChatMemoryUiItem(
    val id: String,
    val groupChatId: String,
    val groupChatName: String,
    val assistantName: String,
    val summary: String,
    val createdAt: Long,
    val timeText: String,
)

/**
 * Fusionar manualmente el estado del borrador recordado,null = No hay fusiones en curso.
 *
 * Solo lleva los resultados intermedios de una sesión de fusión.:El borrador del modelo no se guardará hasta que el usuario lo confirme.,
 * Entonces el texto([mergedText])es una entrada de interfaz de usuario editable,[sourceItems] Conserve la entrada original para poder recuperar la identificación al confirmar.
 *
 * @param sources N piezas de texto originales que participan en la fusión(pantalla de solo lectura,Informar a los usuarios qué se ha fusionado)
 * @param mergedText Borrador de fusión generado por el modelo(UI Editable)
 * @param sourceItems La entrada de memoria original seleccionada(Al confirmar la combinación, presione la identificación para colocarla en la biblioteca.)
 */
data class MemoryMergeDraft(
    val sources: List<String>,
    val mergedText: String,
    val sourceItems: List<MemoryItem>,
)

/**
 * Etapa 6: Memorizar la página ViewModel.
 *
 * fuente de datos(Todos registrados vía Koin):
 *  - [FactStore]:Fact Búsqueda de capa + ME GUSTA
 *  - [SessionSummaryManager]:Summary capa
 *  - [MemoryCompiler]:Compile capa(Lea 4 secciones de productos compilados.)
 *
 * Deep Capa sin almacenamiento independiente,Inferir impresiones por total de hechos + marca de tiempo.
 *
 * v8: Nuevo [assistantRepository] inyectar,usado para:
 *  - Cargar [availableScopes](Asistente principal + todos los sub-asistentes)
 *  - [selectedScope] Controlar el filtrado de elementos de datos de la página de memoria por alcance
 *  - [addFact] Utilice la corriente al escribir [selectedScope](null predeterminado "main")
 */
class MemoryViewModel(
    application: Application,
    private val factStore: FactStore,
    private val summaryManager: SessionSummaryManager,
    private val memoryCompiler: MemoryCompiler,
    /** v0.51: Inyecte MemoryTicker para leer HealthFlow y compilar Markdown recortado. */
    private val memoryTicker: MemoryTicker,
    /** v1.98: Inyecte SettingsRepository para leer el cambio de biblioteca de experiencias. */
    private val settings: SettingsRepository,
    /** v1.98: Inyecte ExperienceRepository para el repositorio de experiencias CRUD + observeAll. */
    private val experienceRepository: ExperienceRepository,
    /** v8: Inject AssistantRepository para cargar availableScopes(Asistente principal + lista de sub-asistentes). */
    private val assistantRepository: AssistantRepository,
    private val sessionRepository: SessionRepository,
    /** v1.0.52 P2-2: Inyecte MemorySpaceRepository para alternar espacio + lista. */
    private val spaceRepository: MemorySpaceRepository,
    /** v1.0.72: Inyecte GroupChatMemoryRepository para mostrar la memoria del chat grupal/borrar. */
    private val groupChatMemoryRepository: io.zer0.muse.data.groupchat.GroupChatMemoryRepository,
    /** v1.0.72: Inyecte GroupChatRepository para resolver los nombres de los chats grupales. */
    private val groupChatRepository: io.zer0.muse.data.groupchat.GroupChatRepository,
    /** v1.0.72: Inyecte ChatService para LLM para fusionar recuerdos duplicados. */
    private val chatService: io.zer0.ai.ChatService,
    /**
     * F-8: Inyectar almacenamiento de memoria superior — El pin de la interfaz de usuario de la página de memoria se escribe en la misma fuente de datos que la herramienta pin_memory
     * (filesDir/pinned_memory), para garantizar que el contenido fijado en la parte superior de la interfaz de usuario realmente ingrese a la sección de "memoria fija" del indicador del sistema.
     */
    private val pinnedMemoryStore: io.zer0.memory.pin.PinnedMemoryStore,
    /**
     * P0-4: inyectar FactDbProvider — lado de producción(DeepMemoryProcessor / MemoryAutoSaveScheduler /
     * SystemPromptAssembler)Escribir hechos según la base de datos del asistente.(facts_<id>.db),El centro de la memoria debe seguir el mismo camino. provider
     * leer/Cambie a la biblioteca que realmente usa la IA. La adición, eliminación y modificación del alcance del subasistente anteriormente se encontraban en la biblioteca predeterminada.,No válido contra IA.
     */
    private val factDbProvider: io.zer0.memory.fact.FactDbProvider,
    /** P2-32: Almacenamiento de lista de contradicciones(Reflexión diaria sobre los resultados de las pruebas.;null = prueba/Degrada a nulo cuando no está ensamblado). */
    private val contradictionStore: io.zer0.memory.reflection.MemoryContradictionStore? = null,
    /**
     * v1.0.92: LLM Consolidador de memoria — manuales"organizar la memoria"Comparte la misma implementación con integración automática diaria.
     * (Clústeres similares se entregan a grandes modelos para su fusión).null = prueba/Omita la integración de LLM cuando no esté ensamblado.
     */
    private val factConsolidator: io.zer0.memory.fact.LlmFactConsolidator? = null,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    // ── P0-4: Enrutamiento de rama de memoria — con lado de escritura de producción(FactDbProvider)Compartir la misma fuente de datos ──

    /**
     * Analice el uso real en el lado de la producción de acuerdo con el alcance seleccionado actualmente. FactStore:
     *  - null / "main" → Biblioteca global predeterminada(hechos de ayudante maestro,scope="main")
     *  - sub-asistente id → FactDbProvider biblioteca sucursal(facts_<id>.db)
     *
     * antes de la reparación:UI Utilice siempre la biblioteca predeterminada + la columna de alcance,El hecho de que la base de datos de la sucursal subasistente no se pueda leer,UI Nuevo/La eliminación no tiene ningún efecto sobre la IA.
     */
    private fun storeForScope(scope: String?): FactStore = when {
        scope == null || scope == "main" -> factStore
        else -> factDbProvider.getFactStore(scope)
    }

    /** P0-4: seleccionado actualmente store(scope Cuando es nulo, recurre a la biblioteca predeterminada del asistente principal.). */
    private val currentStore: FactStore
        get() = storeForScope(_selectedScope.value)

    /**
     * P0-4/SEC-04: prensa (scope, id) La tienda a la que pertenece el hecho de ubicación.
     *
     * Los ID de aumento automático de cada rama entrarán en conflicto entre sí.——La misma identificación puede ser un registro legal tanto en la base de datos principal como en una base de datos secundaria.
     * Por lo tanto, la persona que llama debe intentar dar [scope]; Solo cuando no se pueda obtener el alcance, se recurrirá a la tienda seleccionada actualmente.
     * **No más escaneos mediante ID simple entre fragmentos**: Eso equivale a usar la identificación para adivinar la propiedad, y el editor/Eliminar/El pin recayó en otros asistentes.
     */
    private suspend fun storeForFact(id: Long, scope: String? = null): FactStore {
        val scoped = scope?.takeIf { it.isNotBlank() }?.let { storeForScope(it) }
        if (scoped != null && runCatching { scoped.getById(id) }.getOrNull() != null) return scoped
        return scoped ?: currentStore
    }

    /** P0-4: "Todos"ver — Biblioteca predeterminada + unión de subbibliotecas de cada subasistente(filtrar por espacio). */
    private suspend fun loadFactsForAllScopes(spaceId: String): List<FactStore.Fact> {
        val main = resultOf { factStore.getBySpace(spaceId) }.getOrNull() ?: emptyList()
        val scoped = subAssistantStores().flatMap { (id, store) ->
            resultOf { store.getBySpace(spaceId) }
                .onError { msg, t -> Logger.w("MemoryViewModel", "No se pudo leer la biblioteca de memoria del subasistente($id): $msg", t) }
                .getOrNull() ?: emptyList()
        }
        return (main + scoped).distinctBy { it.scope to it.id }
    }

    /** P0-4: "Todos"ver — Búsqueda de texto completo en bases de datos(filtrar por espacio). */
    private suspend fun searchAllScopes(query: String, spaceId: String): List<FactStore.Fact> {
        val main = resultOf { factStore.searchFullTextBySpace(query, spaceId) }.getOrNull() ?: emptyList()
        val scoped = subAssistantStores().flatMap { (id, store) ->
            resultOf { store.searchFullTextBySpace(query, spaceId) }
                .onError { msg, t -> Logger.w("MemoryViewModel", "No se pudo buscar la biblioteca de memoria del subasistente($id): $msg", t) }
                .getOrNull() ?: emptyList()
        }
        return (main + scoped).distinctBy { it.scope to it.id }
    }

    /** P0-4: Lista de tiendas auxiliares(scope id → store),Sólo asistentes no principales. */
    private fun subAssistantStores(): List<Pair<String, FactStore>> = _availableScopes.value
        .mapNotNull { option ->
            val id = option.id
            if (id == null || id == "main" || id.isBlank()) null else id to factDbProvider.getFactStore(id)
        }

    // Reparación de auditoría (3.2): Registre la rutina de loadAll Job,Cancele la rutina anterior antes de cargar la nueva.,Evite que los resultados antiguos sobrescriban el nuevo estado
    private var loadJob: Job? = null

    // Reparación de auditoría (3.3): Corrutina que registra la búsqueda Job,Cancelar la rutina anterior antes de realizar una nueva búsqueda,Evite que los resultados antiguos sobrescriban el nuevo estado
    private var searchJob: Job? = null

    /**
     * v8: El alcance de la memoria actualmente seleccionado.
     *  - null:Todos los alcances(Predeterminado,UI seleccionado"Todos" chip)
     *  - "main":Mostrar solo datos sobre el alcance del asistente principal
     *  - assistantId:Mostrar solo datos sobre el alcance de este subayudante
     *
     * Se activará después de cambiar de alcance. [loadAll] Extraiga los datos nuevamente.
     */
    private val _selectedScope = MutableStateFlow<String?>(null)
    val selectedScope: StateFlow<String?> = _selectedScope.asStateFlow()

    /** v1.x: Eliminar completamente el reingreso y evitar el reingreso. */
    private var dedupInFlight = false

    /** v1.x: Compile inmediatamente para evitar el reingreso.(La compilación consume llamadas LLM,Evite la ejecución repetida de puntos conectados). */
    private var compileInFlight = false

    /** v1.0.90: Estado de compilación en progreso,Proporciona páginas de memoria para mostrar comentarios explícitos. */
    private val _compilingState = MutableStateFlow(false)
    val compilingState: StateFlow<Boolean> = _compilingState.asStateFlow()

    /** v1.0.90: Estado de deduplicación en curso,Para páginas de memoria para desactivar botones y mostrar comentarios. */
    private val _dedupState = MutableStateFlow(false)
    val dedupState: StateFlow<Boolean> = _dedupState.asStateFlow()

    /** unificar“organizar la memoria”El estado de la acción evita que los usuarios tengan que comprender varios botones subyacentes. */
    private val _organizeRunning = MutableStateFlow(false)
    val organizeRunning: StateFlow<Boolean> = _organizeRunning.asStateFlow()
    private val _organizeStage = MutableStateFlow<String?>(null)
    val organizeStage: StateFlow<String?> = _organizeStage.asStateFlow()
    private val _organizeResult = MutableStateFlow<String?>(null)
    val organizeResult: StateFlow<String?> = _organizeResult.asStateFlow()

    /** Borradores combinados manualmente;null = No hay fusiones en curso. */
    private val _mergeDraft = MutableStateFlow<MemoryMergeDraft?>(null)
    val mergeDraft: StateFlow<MemoryMergeDraft?> = _mergeDraft.asStateFlow()

    /** Fusión manual en curso(Se configuran las secciones Generar borrador y Drop-in.,Proporciona interfaz de usuario para desactivar botones y evitar puntos de conexión.). */
    private val _mergeRunning = MutableStateFlow(false)
    val mergeRunning: StateFlow<Boolean> = _mergeRunning.asStateFlow()

    /** Mensaje de error de fusión(UI Limpiar después del consumo),null = Sin errores. */
    private val _mergeError = MutableStateFlow<String?>(null)
    val mergeError: StateFlow<String?> = _mergeError.asStateFlow()

    /**
     * Organización de memoria unificada: realice la deduplicación después de compilar el resumen.
     * Las dos acciones subyacentes aún mantienen API independientes para compatibilidad con portales antiguos y tareas en segundo plano; la nueva interfaz de usuario solo expone este método.
     */
    fun organizeMemory() {
        if (_organizeRunning.value) return
        _organizeRunning.value = true
        _organizeStage.value = "prepare"
        viewModelScope.launch {
            try {
                _organizeStage.value = "compile"
                val compileOk = withContext(Dispatchers.IO) {
                    resultOf { memoryTicker.forceCompileNow() }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "La compilación de la memoria falló: ${t?.message ?: msg}") }
                        .getOrNull() != null
                }
                if (!compileOk) {
                    _organizeResult.value = "failed:compile"
                    return@launch
                }
                _organizeStage.value = "dedup"
                val merged = withContext(Dispatchers.IO) {
                    val scopes = memoryScopesForMaintenance(_selectedScope.value, _availableScopes.value)
                    val spaceId = _selectedSpaceId.value.ifBlank { "default" }
                    resultOf {
                        var total = 0
                        for (scope in scopes) {
                            total += storeForScope(scope).dedupPass(scope = scope, spaceId = spaceId)
                        }
                        total
                    }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "No se pudo organizar la memoria y eliminar duplicados: ${t?.message ?: msg}") }
                        .getOrNull()
                }
                if (merged == null) {
                    _organizeResult.value = "failed:dedup"
                } else {
                    // v1.0.92: Una vez completada la deduplicación de reglas, se agrega la integración semántica de LLM.(Misma implementación que ruta automática diaria).
                    // Saltar silenciosamente el fracaso,No afecta los resultados de la deduplicación de reglas.
                    val llmMerged = withContext(Dispatchers.IO) {
                        resultOf { llmMergeDuplicates() }
                            .onError { msg, t -> Logger.w("MemoryViewModel", "organizar:LLM La integración falló: ${t?.message ?: msg}") }
                            .getOrNull() ?: 0
                    }
                    _organizeStage.value = "complete"
                    _organizeResult.value = "done:${merged + llmMerged}"
                    loadAll(silent = true)
                }
            } finally {
                _organizeRunning.value = false
                if (_organizeStage.value != "complete") _organizeStage.value = null
            }
        }
    }

    fun consumeOrganizeResult() {
        _organizeResult.value = null
        if (!_organizeRunning.value) _organizeStage.value = null
    }

    // ── Fusionar recuerdos manualmente:Hechos seleccionados → borrador del modelo → Después de que el usuario confirma, la transacción se coloca en la base de datos. ──

    /**
     * El ámbito al que pertenece el asiento de hecho:Traiga sus propios artículos primero scope,Volver a la selección actual si falta(Mismo calibre que eliminar ruta).
     */
    private fun mergeScopeOf(item: MemoryItem): String =
        item.scope?.takeIf { it.isNotBlank() }
            ?: _selectedScope.value?.takeIf { it.isNotBlank() }
            ?: "main"

    /** El espacio de memoria al que pertenece la entrada de hechos.:Traiga sus propios artículos primero spaceId,Devuelve la selección actual si falta. */
    private fun mergeSpaceOf(item: MemoryItem): String =
        item.spaceId?.takeIf { it.isNotBlank() } ?: _selectedSpaceId.value.ifBlank { "default" }

    /**
     * El primer paso en la fusión manual:Solo permitir que se redacte el modelo.,No cambies la biblioteca.
     *
     * Solo se aceptan entradas con capa de hechos e identificación resoluble,y debe caer dentro**el mismo FactStore**(mismo alcance + mismo space):
     * [FactStore.mergeFacts] Inserte nuevas filas en una transacción y escriba la fila de origen merged_into_id,
     * Las entradas entre bases de datos no pueden estar cubiertas por una sola transacción,Es más seguro detenerse temprano que terminar más tarde.
     */
    fun prepareMerge(items: List<MemoryItem>) {
        _mergeError.value = null
        if (_mergeRunning.value) return
        val targets = items.filter { it.source == "Fact" && it.id.toLongOrNull() != null }
        if (targets.size < 2) {
            _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_min_items)
            return
        }
        val consolidator = factConsolidator
        if (consolidator == null) {
            _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_no_model)
            return
        }
        val scopes = targets.map(::mergeScopeOf).toSet()
        val spaces = targets.map(::mergeSpaceOf).toSet()
        if (scopes.size > 1 || spaces.size > 1) {
            _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_mixed_scope)
            return
        }
        _mergeRunning.value = true
        viewModelScope.launch {
            try {
                val texts = targets.map { it.content }
                val merged = withContext(Dispatchers.IO) {
                    resultOf { consolidator.synthesize(texts) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "Falló la compilación de fusión manual: ${t?.message ?: msg}") }
                        .getOrNull()
                }
                if (merged.isNullOrBlank()) {
                    _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_generate_failed)
                } else {
                    _mergeDraft.value = MemoryMergeDraft(
                        sources = texts,
                        mergedText = merged,
                        sourceItems = targets,
                    )
                }
            } finally {
                _mergeRunning.value = false
            }
        }
    }

    /**
     * Confirmar fusión:usar(Puede haber sido modificado por el usuario.)[editedText]Hacer texto combinado,Salidas de origen,Inserte un nuevo hecho.
     *
     * Actualiza silenciosamente la lista después del éxito.:Igual que la eliminación por lotes,Evite reemplazar la vista haciendo que la posición de desplazamiento vuelva a la parte superior.
     * Mantener borrador en caso de falla,El usuario puede volver a intentarlo después de realizar cambios.
     */
    fun confirmMerge(editedText: String) {
        val draft = _mergeDraft.value ?: return
        if (editedText.isBlank()) {
            _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_empty_result)
            return
        }
        if (_mergeRunning.value) return
        val ids = draft.sourceItems.mapNotNull { it.id.toLongOrNull() }.distinct()
        val scope = draft.sourceItems.firstOrNull()?.let(::mergeScopeOf) ?: "main"
        val spaceId = draft.sourceItems.firstOrNull()?.let(::mergeSpaceOf) ?: "default"
        _mergeRunning.value = true
        viewModelScope.launch {
            try {
                val newId = withContext(Dispatchers.IO) {
                    resultOf {
                        storeForScope(scope).mergeFacts(ids, editedText, scope = scope, spaceId = spaceId)
                    }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "Error al fusionar y soltar manualmente: ${t?.message ?: msg}") }
                        .getOrNull()
                }
                if (newId == null) {
                    _mergeError.value = getApplication<Application>().getString(R.string.memory_merge_error_commit_failed)
                } else {
                    _mergeDraft.value = null
                    _mergeError.value = null
                    loadAll(silent = true)
                }
            } finally {
                _mergeRunning.value = false
            }
        }
    }

    /** Abandonar esta fusión:Borrar borradores y mensajes de error,Biblioteca sin mover. */
    fun cancelMerge() {
        _mergeDraft.value = null
        _mergeError.value = null
    }

    /** UI Se llama después de consumir mensajes de error de combinación,Borre el mensaje. */
    fun consumeMergeError() {
        _mergeError.value = null
    }

    /**
     * F-7: Filtrado de clientes — Importancia + plazo(Basado en el número de días creado desde hoy).
     * No modificar el enlace de consulta,Filtrar solo a nivel de presentación;Las estadísticas y la deduplicación todavía se basan en datos completos.
     */
    private fun filterFacts(facts: List<FactStore.Fact>): List<FactStore.Fact> {
        val importance = _importanceFilter.value
        val timeRange = _timeRangeFilter.value
        if (importance == null && timeRange == null) return facts
        val cut = timeRange?.days?.let { days -> Instant.now().minus(days, ChronoUnit.DAYS) }
        return facts.filter { f ->
            (importance == null || f.importance == importance) &&
                (cut == null || runCatching { Instant.parse(f.createdAt).isAfter(cut) }.getOrDefault(false))
        }
    }

    /**
     * F-7: Alternar filtro de importancia(null=Todos, 0=ordinario, 1=importante, 2=clave).
     */
    fun selectImportanceFilter(importance: Int?) {
        if (_importanceFilter.value == importance) return
        _importanceFilter.value = importance
        loadAll()
    }

    /**
     * F-7: Cambiar filtro de rango de tiempo(null/ALL=Todos, cerca7dia/cerca30dia/cerca90dia).
     */
    fun selectTimeRangeFilter(range: MemoryTimeRange?) {
        if (_timeRangeFilter.value == range) return
        _timeRangeFilter.value = range
        loadAll()
    }

    /**
     * v8: Lista opcional de ámbitos(Responsivo).
     * siempre contiene"Todos" + "asistente principal";Los elementos restantes provienen de [assistantRepository.observeAll].
     * El usuario crea uno nuevo en la página de configuración del asistente./Al eliminar un subasistente,Esta lista se actualiza automáticamente.
     */
    private val _availableScopes = MutableStateFlow<List<ScopeOption>>(emptyList())
    val availableScopes: StateFlow<List<ScopeOption>> = _availableScopes.asStateFlow()

    /**
     * v1.0.52 P2-2: El ID del espacio de memoria seleccionado actualmente.
     * Leer desde SettingsRepository.currentSpaceIdFlow,Escribir después de que el usuario cambie de espacio.
     * factItems prensa (scope + spaceId) Doble filtrado.
     */
    private val _selectedSpaceId = MutableStateFlow(MemorySpaceEntity.DEFAULT_SPACE_ID)
    val selectedSpaceId: StateFlow<String> = _selectedSpaceId.asStateFlow()

    /** F-7: Filtro de importancia(null=Todos, 0/1/2=ordinario/importante/clave). */
    private val _importanceFilter = MutableStateFlow<Int?>(null)
    val importanceFilter: StateFlow<Int?> = _importanceFilter.asStateFlow()

    /** F-7: Filtro de rango de tiempo(null=Todos). */
    private val _timeRangeFilter = MutableStateFlow<MemoryTimeRange?>(null)
    val timeRangeFilter: StateFlow<MemoryTimeRange?> = _timeRangeFilter.asStateFlow()

    /**
     * v1.0.52 P2-2: Lista de espacios disponibles(Responsivo).
     * de [spaceRepository.observeSpaces],Los usuarios se actualizan automáticamente después de CRUD en la página de administración del espacio.
     */
    private val _availableSpaces = MutableStateFlow<List<MemorySpaceEntity>>(emptyList())
    val availableSpaces: StateFlow<List<MemorySpaceEntity>> = _availableSpaces.asStateFlow()

    /**
     * v1.0.51: Progreso de la migración de la memoria de stock — Después de la actualización, el resumen de la sesión del historial se reflejará en tiempo real cuando se inicie la ejecución de recuperación por primera vez.
     * null Indica que no se está migrando;Muestra una barra de progreso en la parte superior de MemoryScreen cuando no es nula.
     * Reenviar directamente MemoryTicker.backfillProgressFlow,No es necesario que ViewModel pase al estado de transición.
     */
    val backfillProgress: StateFlow<MemoryTicker.BackfillProgress?> = memoryTicker.backfillProgressFlow

    /** v1.0.51: Claro progreso migratorio(Llamado por MemoryScreen después de que el usuario haya visto el mensaje de finalización). */
    fun clearBackfillProgress() = memoryTicker.clearBackfillProgress()

    init {
        // v0.51: Recoge MemoryTicker healthFlow,Refleja el estado de salud de la memoria en la interfaz de usuario en tiempo real.
        // Cada paso es exitoso/El tiempo de falla se mostrará en la tarjeta del tablero.,No se requiere actualización manual por parte del usuario.
        viewModelScope.launch {
            memoryTicker.healthFlow.collect { health ->
                val lastCompileTime = health.values
                    .mapNotNull { it.lastSuccessAt }
                    .maxOrNull()
                _state.update {
                    it.copy(
                        healthMap = health,
                        lastCompileTime = lastCompileTime,
                        syncStatus = computeSyncStatus(lastCompileTime),
                        syncStale = computeSyncStale(lastCompileTime),
                    )
                }
            }
        }
        // v1.98: Cambio de biblioteca de experiencia de suscripción,La página de configuración de reflexión en tiempo real cambia a UI(Determina si se mostrarán las tarjetas de la biblioteca de experiencias.).
        viewModelScope.launch {
            settings.experienceEnabledFlow.collect { enabled ->
                _state.update { it.copy(experienceEnabled = enabled) }
            }
        }
        // v1.98: Suscríbase al flujo de entrada de la base de experiencias,CRUD Actualizar automáticamente después UI(No se requiere manual loadAll).
        viewModelScope.launch {
            experienceRepository.observeAll().collect { experiences ->
                val items = experiences.map { it.toMemoryItem() }
                _state.update {
                    it.copy(
                        experienceItems = items,
                        experienceCount = experiences.size,
                    )
                }
            }
        }
        // v8: Suscríbete AssistantRepository.observeAll,Actualizar los alcances disponibles en tiempo real.
        // El usuario crea uno nuevo en la página de configuración del asistente./Al eliminar un subasistente,FilterChip Las listas se sincronizan automáticamente.
        // Incluido por defecto"Todos" + "asistente principal",Los elementos restantes se representan mediante AssistantEntity.id/name.
        viewModelScope.launch {
            assistantRepository.observeAll.collect { assistants ->
                _availableScopes.value = buildScopes(assistants)
            }
        }
        // v1.0.52 P2-2: Suscríbete currentSpaceIdFlow,Sincronización en tiempo real después de que el usuario cambia el espacio en la página de memoria o en la página de configuración.
        // Después de cambiar de espacio, se activa loadAll para filtrar la lista de hechos nuevamente por spaceId.
        viewModelScope.launch {
            settings.currentSpaceIdFlow.collect { spaceId ->
                val changed = _selectedSpaceId.value != spaceId
                _selectedSpaceId.value = spaceId
                if (changed) loadAll(silent = true)
            }
        }
        // v1.0.52 P2-2: Suscríbete a la lista de espacios,El usuario sincroniza automáticamente el menú desplegable del conmutador después de CRUD en la página de administración de espacio.
        viewModelScope.launch {
            spaceRepository.observeSpaces().collect { spaces ->
                _availableSpaces.value = spaces
            }
        }
        loadAll()
    }

    /**
     * v8: Lista de opciones de alcance de construcción(siempre contiene"Todos" + "asistente principal" + Todos los subayudantes).
     *
     * "default" Los ayudantes son tratados como"Asistente principal predeterminado",Su alcance se asigna a "main"(Consistente con el valor predeterminado de FactEntity),
     * Evite los datos históricos(default Los hechos escritos por el asistente todavía tienen scope="main")Inconsistente con las opciones de la interfaz de usuario.
     * Otros ID de asistente usan su ID directamente como alcance.
     */
    private fun buildScopes(assistants: List<AssistantEntity>): List<ScopeOption> {
        val all = ScopeOption(
            id = null,
            displayName = getApplication<Application>().getString(R.string.memory_scope_all),
            isAll = true,
        )
        val main = ScopeOption(
            id = "main",
            displayName = getApplication<Application>().getString(R.string.memory_scope_main),
            isMain = true,
        )
        // excluir "default" asistente — Se ha mapeado en datos históricos a "main",Evite la visualización repetida de la interfaz de usuario.
        val subScopes = assistants
            .filter { it.id.isNotBlank() && it.id != "default" }
            .map { ScopeOption(id = it.id, displayName = it.name.ifBlank { it.id }) }
        return listOf(all, main) + subScopes
    }

    /**
     * v8: Cambia el alcance seleccionado actualmente.null expresar"Todos".
     * Activado inmediatamente después del cambio [loadAll] Vuelva a recuperar datos filtrados por alcance.
     */
    fun selectScope(scope: String?) {
        _selectedScope.value = scope
        loadAll()
    }

    /**
     * v1.0.52 P2-2: Cambia el espacio de memoria actualmente seleccionado.
     * persistir en SettingsRepository,Conservado entre sesiones.
     * currentSpaceIdFlow Los suscriptores activarán automáticamente loadAll para volver a recuperar los datos.
     */
    fun selectSpace(spaceId: String) {
        viewModelScope.launch {
            resultOf { settings.saveCurrentSpaceId(spaceId) }
                .onError { msg, t -> Logger.w("MemoryViewModel", "saveCurrentSpaceId falló: $msg", t) }
        }
    }

    /**
     * v1.78 (#7): Activar manualmente la compilación inmediata(controlar MemoryTicker.forceCompileNow).
     * El usuario está en el punto de la página de memoria."compilar ahora"Llamado cuando botón,No espere a que el teletipo se programe automáticamente.
     */
    fun compileNow() {
        if (compileInFlight) return
        compileInFlight = true
        _compilingState.value = true
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                resultOf { memoryTicker.forceCompileNow() }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "forceCompileNow falló: $msg", t) }
                    .isSuccess
            }
            // v1.0.72: Inmediatamente después de completar la compilación, realice la fusión y deduplicación de LLM(Fusionar recuerdos duplicados en uno,
            //   diferentes hechos no se mueven;El error no afecta los resultados de la compilación.)
            if (success) {
                llmMergeDuplicates()
            }
            compileInFlight = false
            _compilingState.value = false
            // v1.x: Comentarios sobre los resultados de la compilación(éxito/falló),evitar"Se hizo clic pero no hubo respuesta."
            _compileResult.value = if (success) "done" else "failed"
            // Actualizar silenciosamente después de completar la compilación:No activado isLoading,Evite reemplazar la vista actual(línea de tiempo/lista)causando pantalla de bienvenida
            loadAll(silent = true)
        }
    }

    /**
     * v1.0.72 / v1.0.92: LLM Fusionar recuerdos duplicados — grupos similares(≥2 Artículo)Dale el modelo grande y combínalo en uno.,
     * Mantenga toda la información crítica;Otros hechos no se ven afectados. Saltar silenciosamente el fracaso(La deduplicación de reglas todavía se realiza mediante tareas diarias).
     *
     * v1.0.92: Implementación delegada a [io.zer0.memory.fact.LlmFactConsolidator] — Integre con la automatización diaria
     * compartir la misma implementación;Devuelve el número real de clústeres fusionados.,suministro"organizar la memoria"Estadísticas de redacción de resultados.
     */
    private suspend fun llmMergeDuplicates(scope: String? = _selectedScope.value): Int {
        val consolidator = factConsolidator ?: return 0
        val targets = memoryScopesForMaintenance(scope, _availableScopes.value)
        val spaceId = _selectedSpaceId.value.ifBlank { "default" }
        var merged = 0
        for (target in targets) {
            merged += resultOf {
                consolidator.consolidate(storeForScope(target), target, spaceId)
            }
                .onError { msg, t -> Logger.w("MemoryViewModel", "LLM No se pudieron fusionar recuerdos duplicados($target): $msg", t) }
                .getOrNull() ?: 0
        }
        return merged
    }

    /** v1.x: Mensaje de resultado de compilación(UI LaunchedEffect Limpiar después del consumo). */
    private val _compileResult = MutableStateFlow<String?>(null)
    val compileResult: StateFlow<String?> = _compileResult.asStateFlow()

    /** UI Se llama después de consumir los resultados de la compilación. */
    fun consumeCompileResult() {
        _compileResult.value = null
    }

    /** v1.x: Activar manualmente la deduplicación completa — Fusionar hechos casi duplicados dentro del alcance actual,y actualizar la lista.
     * Resultado aprobado [dedupResult] Regresar,UI Utilice LaunchedEffect para consumir y avisar.
     */
    private val _dedupResult = MutableStateFlow<String?>(null)
    val dedupResult: StateFlow<String?> = _dedupResult.asStateFlow()

    fun dedupNow() {
        if (dedupInFlight) return
        dedupInFlight = true
        _dedupState.value = true
        viewModelScope.launch {
            val merged = withContext(Dispatchers.IO) {
                val scopes = memoryScopesForMaintenance(_selectedScope.value, _availableScopes.value)
                val spaceId = _selectedSpaceId.value.ifBlank { "default" }
                resultOf {
                    var total = 0
                    for (scope in scopes) {
                        total += storeForScope(scope).dedupPass(scope = scope, spaceId = spaceId)
                    }
                    total
                }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "Falló la deduplicación de memoria: ${t?.message ?: msg}") }
                    .getOrNull()
            }
            dedupInFlight = false
            _dedupState.value = false
            _dedupResult.value = if (merged != null) "merged:$merged" else "failed"
            loadAll(silent = true)
        }
    }

    /** UI Llamado después de consumir el resultado de la deduplicación.,Borre el mensaje. */
    fun consumeDedupResult() {
        _dedupResult.value = null
    }

    /**
     * Extraiga las 4 capas de datos(memory La capa del Repositorio del módulo es toda suspend,Ninguno Flow,Necesidad de tomar la iniciativa pull).
     * Capture la pila de fallos completa para state.errorTrace,Para visualización desplazable de UI(Conveniente para que los usuarios copien a los desarrolladores).
     *
     * v8: Fact Botón de capa [_selectedScope] filtrar:
     *  - null:Todos los alcances(Compatible con versiones anteriores)
     *  - "main" / assistantId:Extraiga solo hechos para ese alcance
     * Summary / Compile Sin distinción entre capas scope(Compilado globalmente por MemoryTicker,No aislado por asistente).
     *
     * v1.0.52 P2-2: Fact Presione más capas [_selectedSpaceId] filtrar:
     *  - scope cuando es nulo:Consulta por spaceId(getBySpace)
     *  - scope Cuando no es nulo:Consulta doble por alcance + spaceId(getByScopeAndSpace)
     *  - Al cambiar de espacio de esta manera,factItems Mostrar solo datos sobre el espacio actual
     */
    fun loadAll(silent: Boolean = false) {
        // Reparación de auditoría (3.2): Cancelar la última rutina de carga,Al cambiar rápidamente de ámbito, la antigua rutina ya no sobrescribe el nuevo estado.
        loadJob?.cancel()
        // silent=true no se activa cuando isLoading,Evite reemplazar la vista actual una vez completada la compilación, lo que provocaría una pantalla de presentación
        if (!silent) {
            _state.update { it.copy(isLoading = true, errorTrace = null) }
        }
        loadJob = viewModelScope.launch {
            try {
                val scope = _selectedScope.value
                val spaceId = _selectedSpaceId.value
                val facts = withContext(Dispatchers.IO) {
                    // P0-4: Ruta a la biblioteca del lado de producción según el alcance. —
                    //  null(Todos)= Biblioteca predeterminada + unión de subbibliotecas de cada subasistente;"main" = Biblioteca predeterminada;"<id>" = facts_<id>.db
                    when {
                        scope == null -> loadFactsForAllScopes(spaceId)
                        scope == "main" -> factStore.getByScopeAndSpace("main", spaceId)
                        else -> factDbProvider.getFactStore(scope).getByScopeAndSpace(scope, spaceId)
                    }
                }
                // F-7: Filtrado de clientes(Importancia + plazo);Las estadísticas todavía se basan en el volumen total facts
                val visibleFacts = filterFacts(facts)
                val summaries = withContext(Dispatchers.IO) {
                    summaryManager.getAllSummaries().filter { summary ->
                        summary.matchesConversationMemoryFilter(scope, spaceId)
                    }
                }
                val sessionsById = withContext(Dispatchers.IO) {
                    sessionRepository.getSessionsByIds(summaries.map { it.sessionId }).associateBy { it.id }
                }
                // P2-33: Sección compilada antes de leer y escribir clavada. main/default,Alcance del asistente Kiriko / otro espacio después
                // 「AI Te entiendo" editó el espacio equivocado(desalineado con la ruta de lectura inyectada). Presione el actual
                // scope(Todas las vistas regresan al asistente principal)+ space Lectura de ruta.
                val sectionScope = scope ?: "main"
                val compileFacts = withContext(Dispatchers.IO) {
                    // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.FACTS, sectionScope, spaceId) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(FACTS) falló: $msg", t) }
                        .getOrNull() ?: ""
                }
                val compileToday = withContext(Dispatchers.IO) {
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.TODAY, sectionScope, spaceId) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(TODAY) falló: $msg", t) }
                        .getOrNull() ?: ""
                }
                val compileWeek = withContext(Dispatchers.IO) {
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.WEEK, sectionScope, spaceId) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(WEEK) falló: $msg", t) }
                        .getOrNull() ?: ""
                }
                val compileLongterm = withContext(Dispatchers.IO) {
                    resultOf { memoryCompiler.readSection(MemoryCompiler.Section.LONGTERM, sectionScope, spaceId) }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readSection(LONGTERM) falló: $msg", t) }
                        .getOrNull() ?: ""
                }
                // v0.51: Leer el recorte compiledMarkdown(suministro"AI comprensión de ti"pantalla plegable de tarjeta).
                // Mismo origen que el mensaje del sistema de inyección ChatService,Reflejando la verdadera naturaleza de la IA"ver"memoria.
                // P2-33: Lea también según el alcance/espacio actual,Evite mostrar la sección de compilación del asistente principal.
                val compiledMarkdown = withContext(Dispatchers.IO) {
                    resultOf {
                        memoryTicker.readCompiledMemoryMarkdown(
                            locale = "es",
                            scope = sectionScope,
                            spaceId = spaceId,
                            assistantId = if (sectionScope == "main") "default" else sectionScope,
                        )
                    }
                        .onError { msg, t -> Logger.w("MemoryViewModel", "readCompiledMemoryMarkdown falló: $msg", t) }
                        .getOrNull() ?: ""
                }
                // P2-32: Leer la lista de memoria en conflicto del ámbito/espacio actual(Reflexión diaria)
                val contradictions = withContext(Dispatchers.IO) {
                    if (contradictionStore == null) {
                        emptyList<io.zer0.memory.reflection.MemoryContradictionStore.ContradictionPair>()
                    } else {
                        resultOf { contradictionStore.list(sectionScope, spaceId) }
                            .onError { msg, t -> Logger.w("MemoryViewModel", "No se pudo leer la lista de conflictos: $msg", t) }
                            .getOrNull() ?: emptyList()
                    }
                }

                // insignia de estado: "reemplazado"= Esta fila está apuntada por el id_supersedes de otra fila.,Necesito comprobarlo de nuevo.
                // Utilice el conjunto completo de hechos en lugar de visibleFacts —— El sustituto podrá ser sustituido por la importancia/bloqueo del filtro de tiempo,
                // Es un error utilizar su propia visibilidad para determinar si se debe marcar al partido reemplazado.key Con alcance para evitar colisiones de ID entre ramas.
                val supersededKeys = facts.mapNotNull { row ->
                    row.supersedesId?.let { memoryFactKey(row.scope, it) }
                }.toSet()

                val factItems = visibleFacts.map { fact ->
                    val status = memoryFactStatusOf(
                        scope = fact.scope,
                        id = fact.id,
                        mergedIntoId = fact.mergedIntoId,
                        suppressedAt = fact.suppressedAt,
                        supersededKeys = supersededKeys,
                    )
                    MemoryItem(
                        id = fact.id.toString(),
                        title = fact.fact.take(60),
                        content = fact.fact,
                        tags = fact.tags,
                        time = fact.time,
                        source = "Fact",
                        importance = fact.importance,
                        sessionId = fact.sessionId,

                        confidence = fact.confidence,
                        lastHitAt = fact.lastHitAt,
                        hitCount = fact.hitCount,
                        createdAt = fact.createdAt,
                        // v8: paso a través scope,Para que la interfaz de usuario muestre insignias(asistente principal=Color predeterminado,sub-asistente=tertiary Color)
                        scope = fact.scope,
                        // v2.6.2(auditoría de memoria): paso a través spaceId —— La implementación anterior no estableceUI Información de propiedad perdida del artículo.
                        spaceId = fact.spaceId,
                        // B4-05: Tiempo de transmisión transparente
                        pinnedAt = fact.pinnedAt,
                        // v9: paso a través category,Proporciona una nueva interfaz de usuario para filtrar y mostrar por categoría
                        category = fact.category,
                        // insignia de estado: Fusionado/Ya leí este campo de fila,Reemplazado para ver la colección de búsqueda inversa.
                        mergedIntoId = fact.mergedIntoId,
                        suppressed = status.suppressed,
                        superseded = status.superseded,
                    )
                }
                val summaryItems = projectConversationMemoryItems(
                    summaries = summaries,
                    sessionsById = sessionsById,
                    fallbackTitle = { sessionId ->
                        getApplication<Application>().getString(
                            R.string.memory_summary_title,
                            sessionId.take(8),
                        )
                    },
                ).map(ConversationMemoryItem::toMemoryItem)
                // v2.6.7: El producto compilado de cuatro secciones supera los 140 caracteres por línea y se cambia a una estructura basada en tablas.
                val compileSections = listOf(
                    Triple("facts", R.string.memory_compile_section_facts, compileFacts),
                    Triple("today", R.string.memory_compile_section_today, compileToday),
                    Triple("week", R.string.memory_compile_section_week, compileWeek),
                    Triple("longterm", R.string.memory_compile_section_longterm, compileLongterm),
                )
                val app = getApplication<Application>()
                val compileItems = compileSections.mapNotNull { (key, labelRes, content) ->
                    if (content.isNotBlank()) {
                        MemoryItem(key, app.getString(labelRes), content, source = "Compile")
                    } else {
                        null
                    }
                }

                // v5: Estadística computacional
                val now = Instant.now()
                val weekAgo = now.minus(7, java.time.temporal.ChronoUnit.DAYS)
                val monthAgo = now.minus(30, java.time.temporal.ChronoUnit.DAYS)
                val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE.withZone(java.time.ZoneId.systemDefault())
                val total = facts.size
                val weekNew = facts.count { f ->
                    runCatching { Instant.parse(f.createdAt).isAfter(weekAgo) }.getOrDefault(false)
                }
                val monthNew = facts.count { f ->
                    runCatching { Instant.parse(f.createdAt).isAfter(monthAgo) }.getOrDefault(false)
                }
                val impDist = facts.groupBy { it.importance.coerceIn(0, 2) }.mapValues { it.value.size }
                val topSessionsList = facts.groupBy { it.sessionId ?: "unknown" }
                    .mapValues { it.value.size }
                    .entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .map { it.key to it.value }
                // cerca30tendencia diaria
                val dailyMap = mutableMapOf<String, Int>()
                facts.forEach { f ->
                    runCatching {
                        val day = dateFormat.format(Instant.parse(f.createdAt))
                        dailyMap[day] = (dailyMap[day] ?: 0) + 1
                    }
                }
                val dailyTrendList = dailyMap.entries
                    .sortedBy { it.key }
                    .takeLast(30)
                    .map { it.key to it.value }

                _state.update {
                    it.copy(
                        isLoading = false,
                        factItems = factItems,
                        summaryItems = summaryItems,
                        compileItems = compileItems,
                        contradictions = contradictions,
                        factCount = visibleFacts.size,
                        summaryCount = summaries.size,
                        lastUpdatedAt = facts.maxByOrNull { it.createdAt }?.createdAt,
                        compiledMarkdown = compiledMarkdown,
                        syncStatus = computeSyncStatus(it.lastCompileTime),
                        syncStale = computeSyncStale(it.lastCompileTime),
                        totalFactCount = total,
                        weekNewCount = weekNew,
                        monthNewCount = monthNew,
                        importanceDistribution = impDist,
                        topSessions = topSessionsList,
                        dailyTrend = dailyTrendList,
                    )
                }
            } catch (e: CancellationException) {
                // v1.78 (H1): La señal de cancelación de rutina debe volver a lanzarse.,De lo contrario, se romperá la semántica de cancelación de la rutina.
                throw e
            } catch (t: Throwable) {
                // Capture información de pila completa(nombre de clase + mensaje + stack trace),
                // Para que la interfaz de usuario se muestre en un cuadro desplazable,Los usuarios pueden deslizar el dedo hacia arriba y hacia abajo para ver el error completo y copiarlo al desarrollador.
                val sw = java.io.StringWriter()
                t.printStackTrace(java.io.PrintWriter(sw))
                val fullTrace = buildString {
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_header))
                    val nowText = java.text.SimpleDateFormat(
                        MuseDateFormats.DATE_TIME_FULL_SEC,
                        java.util.Locale.getDefault(),
                    ).format(java.util.Date())
                    appendLine(
                        getApplication<Application>().getString(R.string.memory_error_trace_time) + nowText,
                    )
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_exception) + t.javaClass.name)
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_message) + t.message)
                    val deviceText = "${android.os.Build.BRAND} ${android.os.Build.MODEL} " +
                        "(Android ${android.os.Build.VERSION.RELEASE}, SDK ${android.os.Build.VERSION.SDK_INT})"
                    appendLine(
                        getApplication<Application>().getString(R.string.memory_error_trace_device) + deviceText,
                    )
                    appendLine()
                    appendLine(getApplication<Application>().getString(R.string.memory_error_trace_stack_header))
                    append(sw.toString())
                }
                _state.update { it.copy(isLoading = false, errorTrace = fullTrace) }
            }
        }
    }

    /**
     * consulta de búsqueda(onValueChange La antivibración es manejada por la capa UI,Ejecutar directamente aquí).
     * La consulta vacía borra los resultados de la búsqueda,Búsqueda LIKE no vacía.
     */
    fun search(query: String) {
        _state.update { it.copy(query = query) }
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }
        // Reparación de auditoría (3.3): Cancelar la última rutina de búsqueda,Evite que los resultados de búsqueda antiguos sobrescriban los nuevos
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Revisión de corrección (B-19): Búsqueda filtrada por ámbito/espacio actual,Mismo calibre que loadAll —
            // scope ir no nulo searchFullTextScoped(scope+space doble),scope=null(Todos)
            // ir searchFullTextBySpace(Aislar solo por espacio),spaceId filtración constante,Evite fugas entre espacios/alcance.
            val scope = _selectedScope.value
            val spaceId = _selectedSpaceId.value
            val results = withContext(Dispatchers.IO) {
                // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                resultOf {
                    // P0-4: La búsqueda se dirige a la biblioteca sucursal del lado de producción por alcance.(scope=null Unión entre fragmentos)
                    if (scope == null) {
                        searchAllScopes(query, spaceId)
                    } else {
                        storeForScope(scope).searchFullTextScoped(query, scope, spaceId)
                    }
                }.onError { msg, t -> Logger.w("MemoryViewModel", "searchFullTextScoped falló: $msg", t) }
                    .getOrNull() ?: emptyList()
            }
            // F-7: Los resultados de búsqueda también aplican niveles de importancia./Filtro de rango de tiempo,Mismo calibre que la lista.
            val filteredResults = filterFacts(results)
            val items = filteredResults.map { fact ->
                MemoryItem(
                    id = fact.id.toString(),
                    title = fact.fact.take(60),
                    content = fact.fact,
                    tags = fact.tags,
                    time = fact.time,
                    source = "Fact",
                    importance = fact.importance,
                    sessionId = fact.sessionId,

                    confidence = fact.confidence,
                    lastHitAt = fact.lastHitAt,
                    hitCount = fact.hitCount,
                    createdAt = fact.createdAt,
                    // v8: paso a través scope,Insignias de elementos de la lista de coincidencias de resultados de búsqueda
                    scope = fact.scope,
                    // B4-05: Tiempo de transmisión transparente
                    pinnedAt = fact.pinnedAt,
                    // v9: paso a través category,Los resultados de la búsqueda también se muestran por categoría.
                    category = fact.category,
                )
            }
            _state.update { it.copy(searchResults = items, isSearching = false) }
        }
    }

    /**
     * Eliminar un solo elemento Fact(Solo la capa de hechos admite la eliminación).
     *
     * S-04: Llamado inmediatamente después de la eliminación. [MemoryCompiler.purgeTombstonedFacts],Pon el contenido de la lápida golpeada.
     * Excluir de la sección HECHOS compilados — El enlace inyectado entra en vigor inmediatamente.,No esperes a la próxima compilación programada.
     */
    /**
     * Eliminar datos de forma masiva.
     *
     * Borralos uno por uno, pero**Actualizar solo una vez**: Cada vez que se elimina una única ruta, loadAll(), la lista se restablecerá al principio,
     * Si desea eliminar varios elementos seguidos, debe deslizarlos nuevamente a la posición original repetidamente. Las rutas por lotes se actualizan uniformemente y finalizan en silencio.
     * No se cargará rápidamente ni retrocederá la posición de desplazamiento.
     */
    fun deleteFacts(items: List<MemoryItem>) {
        val targets = items.filter { it.source == "Fact" && it.id.toLongOrNull() != null }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val purgeTargets = mutableSetOf<io.zer0.memory.compile.MemoryCompileTarget>()
                targets.forEach { item ->
                    val id = item.id.toLongOrNull() ?: return@forEach
                    val requestedScope =
                        item.scope?.takeIf { it.isNotBlank() } ?: _selectedScope.value ?: "main"
                    val requestedAssistantId = if (requestedScope == "main") "default" else requestedScope
                    val requestedSpaceId = _selectedSpaceId.value.ifBlank { "default" }
                    resultOf {
                        storeForFact(id, item.scope)
                            .delete(id, requestedAssistantId, requestedScope, requestedSpaceId)
                    }.onSuccess { deleted ->
                        if (deleted) {
                            purgeTargets += io.zer0.memory.compile.MemoryCompileTarget(
                                assistantId = requestedAssistantId,
                                scope = requestedScope,
                                spaceId = requestedSpaceId,
                            )
                        }
                    }.onError { msg, _ ->
                        Logger.w("MemoryVM", "Error al eliminar datos por lotes: $msg")
                    }
                }
                purgeTargets.forEach { target ->
                    resultOf { memoryCompiler.purgeTombstonedFacts(target) }
                        .onError { msg, t -> Logger.w("MemoryVM", "purgeTombstonedFacts falló: $msg", t) }
                }
            }
            loadAll(silent = true)
        }
    }

    fun deleteFact(factId: String, scope: String? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id != null) {
                    // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                    // P0-4: Localice la ubicación real por factId store("Todos"De hecho, el subasistente también se puede eliminar en la vista)
                    val requestedScope = scope?.takeIf { it.isNotBlank() } ?: _selectedScope.value ?: "main"
                    val requestedAssistantId = if (requestedScope == "main") "default" else requestedScope
                    val requestedSpaceId = _selectedSpaceId.value.ifBlank { "default" }
                    val store = storeForFact(id, scope)
                    resultOf { store.delete(id, requestedAssistantId, requestedScope, requestedSpaceId) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_failed, msg)) }
                        .onSuccess { deleted ->
                            if (deleted) {
                                // S-04: Eliminar datos eliminados de productos compilados,prevenir"Todavía inyectando después de la eliminación/Resurrección de lo abstracto"
                                val target = io.zer0.memory.compile.MemoryCompileTarget(
                                    assistantId = requestedAssistantId,
                                    scope = requestedScope,
                                    spaceId = requestedSpaceId,
                                )
                                resultOf { memoryCompiler.purgeTombstonedFacts(target) }
                                    .onError { msg, t -> Logger.w("MemoryVM", "purgeTombstonedFacts falló: $msg", t) }
                            }
                        }
                }
            }
            loadAll()
        }
    }

    /**
     * P2: Eliminar un solo elemento Summary(Según sessionId).
     */
    /** B4-05: Alterna el estado superior manual de un solo hecho. */
    /** Historial de revisiones (“toda la historia”El panel muestra el hecho que se está viendo actualmente). */
    private val _factRevisions = MutableStateFlow<List<io.zer0.memory.fact.FactRevisionEntity>>(emptyList())
    val factRevisions: StateFlow<List<io.zer0.memory.fact.FactRevisionEntity>> = _factRevisions.asStateFlow()

    /**
     * Cargar el historial de revisión de un hecho.
     *
     * Hechos editados/Al fusionar, un registro de revisión (valor anterior/nuevo valor/tiempo/Motivo), aquí simplemente lo leemos en voz alta para que el usuario lo vea. ——
     * “¿Qué ha cambiado en este recuerdo?”Puede ser auditado a partir de ahora.
     */
    fun loadFactRevisions(factId: String, scope: String? = null) {
        viewModelScope.launch {
            val id = factId.toLongOrNull() ?: return@launch
            _factRevisions.value = runCatching { storeForFact(id, scope).getRevisions(id) }
                .getOrDefault(emptyList())
        }
    }

    /**
     * Retroceder a la revisión especificada.
     *
     * La reversión en sí recordará una nueva revisión (tome la ruta de actualización), por lo que aún puede retroceder después de la reversión, que no existe.“no puedo volver”.
     */
    fun revertFactToRevision(factId: String, revisionId: Long, scope: String? = null) {
        viewModelScope.launch {
            val id = factId.toLongOrNull() ?: return@launch
            val ok = runCatching { storeForFact(id, scope).revertToRevision(id, revisionId) }
                .getOrDefault(false)
            MuseToast.show(
                getApplication<Application>().getString(
                    if (ok) R.string.memory_revision_reverted else R.string.memory_revision_revert_failed,
                ),
            )
            if (ok) loadFactRevisions(factId, scope)
        }
    }

    fun toggleFactPinned(factId: String, scope: String? = null) {
        viewModelScope.launch {
            val id = factId.toLongOrNull() ?: return@launch
            val fact = withContext(Dispatchers.IO) { storeForFact(id, scope).getById(id) } ?: return@launch
            val pinned = fact.pinnedAt == null
            try {
                if (pinned) {
                    pinFact(id, fact.fact, scope)
                } else {
                    unpinFact(id, fact.fact, scope)
                }
            } catch (e: CancellationException) {
                // v1.78 (H3): La señal de cancelación de rutina debe volver a lanzarse.,De lo contrario, se romperá la semántica de cancelación de la rutina.
                throw e
            } catch (t: Throwable) {
                Logger.w("MemoryViewModel", "toggleFactPinned anormal: ${t.message}", t)
                val msg = if (pinned) R.string.memory_pin_failed else R.string.memory_unpin_failed
                MuseToast.show(getApplication<Application>().getString(msg))
            }
            loadAll()
        }
    }

    /**
     * Revisión de corrección (B-18): fijar hacia arriba — escribe primero PinnedMemoryStore(add éxito),setFijado de nuevo.
     * Pedido garantizado: lado de inyección(Segmento de memoria fijo)La coherencia con fact.pinnedAt toma PinnedMemoryStore primero;
     * Si setPinned falla, la reversión se realiza correctamente add,Evite las inconsistencias de la interfaz de usuario con la inyección.
     */
    private suspend fun pinFact(id: Long, content: String, scope: String? = null) {
        val pinnedAssistantId = scope?.takeIf { it.isNotBlank() && it != "main" } ?: "default"
        // escribe primero PinnedMemoryStore(add éxito)otra vez setPinned
        val addResult = resultOf { pinnedMemoryStore.add(content, assistantId = pinnedAssistantId) }
            .onError { msg, t -> Logger.w("MemoryViewModel", "PinnedMemoryStore.add falló: $msg", t) }
        if (addResult.isError) {
            // add falló,No escrito en ninguna fuente.,aviso directo,Estado de publicación poco claro
            MuseToast.show(getApplication<Application>().getString(R.string.memory_pin_failed))
            return
        }
        val setResult = resultOf { storeForFact(id, scope).setPinned(id, pinned = true) }
            .onError { msg, t -> Logger.w("MemoryViewModel", "toggleFactPinned.setPinned falló: $msg", t) }
        if (setResult.isError) {
            // Reversión exitosa add,mantener ambos lados consistentes
            resultOf {
                pinnedMemoryStore.removeByContentFlexibleForAssistant(
                    content = content,
                    assistantId = pinnedAssistantId,
                    includeGlobal = false,
                )
            }
                .onError { m2, t2 -> Logger.w("MemoryViewModel", "PinnedMemoryStore Error en la reversión fijada: $m2", t2) }
            MuseToast.show(getApplication<Application>().getString(R.string.memory_pin_failed))
        }
    }

    /**
     * Revisión de corrección (B-18): Desanclar — Primero asegúrese de que PinnedMemoryStore se haya eliminado correctamente,Luego aclare los hechos.pinnedAt.
     * emparejar conservadoramente removeByContentFlexible(La precisión primero,Degenera para incluir coincidencia en caso de falla),
     * Sobrescribir los restos del contenido fijado reescrito;Si setPinned falla, el lado de inyección no se cambiará.,Ambas partes siguen siendo consistentes.
     */
    private suspend fun unpinFact(id: Long, content: String, scope: String? = null) {
        val pinnedAssistantId = scope?.takeIf { it.isNotBlank() && it != "main" } ?: "default"
        val removeResult = resultOf {
            pinnedMemoryStore.removeByContentFlexibleForAssistant(
                content = content,
                assistantId = pinnedAssistantId,
                includeGlobal = pinnedAssistantId == "default",
            )
        }
            .onError { msg, t -> Logger.w("MemoryViewModel", "PinnedMemoryStore.removeByContentFlexible falló: $msg", t) }
        if (removeResult.isError) {
            MuseToast.show(getApplication<Application>().getString(R.string.memory_unpin_failed))
            return
        }
        val setResult = resultOf { storeForFact(id, scope).setPinned(id, pinned = false) }
            .onError { msg, t -> Logger.w("MemoryViewModel", "toggleFactPinned.setPinned falló: $msg", t) }
        if (setResult.isError) {
            MuseToast.show(getApplication<Application>().getString(R.string.memory_unpin_failed))
        }
    }
    fun deleteSummary(sessionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                resultOf { summaryManager.delete(sessionId) }
                    .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_failed, msg)) }
            }
            loadAll()
        }
    }

    /**
     * P2: Borrar segmento único Compilar producto(Según sectionKey:facts/today/week/longterm).
     */
    fun deleteCompile(sectionKey: String) {
        val section = MemoryCompiler.Section.ALL.firstOrNull { it.key == sectionKey }
            ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                resultOf { memoryCompiler.clearSection(section) }
                    .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_failed, msg)) }
            }
            loadAll()
        }
    }

    /**
     * P2: Editar un único contenido de hecho.
     */
    fun editFact(factId: String, newContent: String, scope: String? = null, onResult: (Boolean) -> Unit = {}) {
        if (newContent.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id == null) {
                    false
                } else {
                    // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                    // P0-4: Localice la ubicación real por factId store
                    resultOf { storeForFact(id, scope).update(id, newContent) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_edit_failed, msg)) }
                        .getOrNull() == true
                }
            }
            if (saved) loadAll()
            onResult(saved)
        }
    }

    /**
     * v4: Actualizar la importancia de un solo hecho.
     * @param factId fact cadena id
     * @param importance 0=ordinario,1=importante,2=clave
     */
    fun setFactImportance(factId: String, importance: Int, scope: String? = null) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val id = factId.toLongOrNull()
                if (id != null) {
                    // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                    // P0-4: Localice la ubicación real por factId store
                    resultOf { storeForFact(id, scope).setImportance(id, importance) }
                        .onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_set_failed, msg)) }
                }
            }
            loadAll()
        }
    }

    /**
     * Agregue manualmente un metadato.
     *
     * v8: El alcance del hecho nuevo se toma como el actual. [_selectedScope],Si null(Todos)entonces el valor predeterminado "main".
     * Esto es diferente de cuando el usuario cambia el alcance en la interfaz de usuario y luego"Nuevo"intuición consistente — Después de que el usuario cambia a un ámbito de subasistente,,
     * Los hechos recién agregados pertenecen al subasistente.;Cortar a"Todos"asistente maestro de propiedad del tiempo(Predeterminado).
     *
     * v1.0.52 P2-2: El spaceId del nuevo hecho es el actual. [_selectedSpaceId],
     * Los hechos recién agregados pertenecen al espacio seleccionado actualmente.
     */
    fun addFact(content: String, onResult: (Boolean) -> Unit = {}) {
        if (content.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            // v1.78 (H6): loadAll mudarse runCatching,Evite cuando la adición se realiza correctamente pero loadAll falla
            // Mensaje de error mostrado"Error al agregar"(atribución errónea)
            val scope = _selectedScope.value ?: "main"
            val spaceId = _selectedSpaceId.value
            val ok = withContext(Dispatchers.IO) {
                // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                // P0-4: Escribir enrutamiento a la biblioteca sucursal del lado de producción — El alcance del ayudante infantil cae dentro facts_<id>.db,AI leer realmente
                resultOf {
                    storeForScope(scope).add(FactStore.Fact(fact = content.trim()), scope = scope, spaceId = spaceId)
                }.onError { msg, t ->
                    // v2.6.7: División de línea para hacerla demasiado larga (la línea única original supera los 140 caracteres);trace Debe evaluarse antes de copiar.
                    val trace = getApplication<Application>().getString(R.string.memory_add_failed, msg)
                    _state.update {
                        it.copy(errorTrace = (it.errorTrace ?: "") + "\n" + trace)
                    }
                }.isSuccess
            }
            if (ok) loadAll()
            onResult(ok)
        }
    }

    /**
     * P2: Edite un único contenido de resumen.
     */
    fun editSummary(sessionId: String, newContent: String, onResult: (Boolean) -> Unit = {}) {
        if (newContent.isBlank()) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                resultOf {
                    val existing = summaryManager.getSummary(sessionId) ?: return@resultOf false
                    summaryManager.saveSummary(
                        sessionId,
                        existing.copy(
                            summary = newContent.trim(),
                            updatedAt = Instant.now().toString(),
                        ),
                    )
                    true
                }
                    .onError { msg, _ ->
                        MuseToast.show(getApplication<Application>().getString(R.string.memory_edit_failed, msg))
                    }
                    .getOrNull() ?: false
            }
            if (saved) loadAll()
            onResult(saved)
        }
    }

    /**
     * P2: Edite el contenido de un único producto Compile.
     * P2-33: Cambiar el destino de escritura al seleccionado actualmente scope/space(previamente crucificado main/default,Error de edición del subasistente).
     */
    fun editCompile(sectionKey: String, newContent: String) {
        if (newContent.isBlank()) return
        val section = MemoryCompiler.Section.ALL.firstOrNull { it.key == sectionKey }
            ?: return
        val sectionScope = _selectedScope.value ?: "main"
        val sectionSpace = _selectedSpaceId.value
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                resultOf { memoryCompiler.writeSection(section, newContent.trim(), sectionScope, sectionSpace) }
            }
            loadAll()
        }
    }

    /**
     * P2-32: Confirmación de usuario/Limpia un par de recuerdos conflictivos.(Eliminar de la lista;Los hechos no se eliminan automáticamente.,Respetar el criterio del usuario).
     */
    fun dismissContradiction(pair: io.zer0.memory.reflection.MemoryContradictionStore.ContradictionPair) {
        val store = contradictionStore ?: return
        viewModelScope.launch {
            val scope = _selectedScope.value ?: "main"
            withContext(Dispatchers.IO) {
                resultOf { store.removePair(scope, _selectedSpaceId.value, pair) }
                    .onError { msg, t -> Logger.w("MemoryViewModel", "No se pudo borrar la lista de conflictos: $msg", t) }
            }
            loadAll(silent = true)
        }
    }

    /**
     * v4: Según lastCompileTime(ISO 8601)Calcular la copia del estado de sincronización.
     *  - null: Aún no compilado
     *  - < 10 minutos: Recuerdos sincronizados
     *  - >= 10 minutos: Es posible que las conversaciones recientes aún no estén en la memoria
     */
    private fun computeSyncStatus(lastCompileTime: String?): String {
        if (lastCompileTime == null) {
            return getApplication<Application>().getString(R.string.memory_sync_never)
        }
        return try {
            val minutes = Duration.between(
                Instant.parse(lastCompileTime),
                Instant.now(),
            ).toMinutes()
            if (minutes < 10) {
                getApplication<Application>().getString(R.string.memory_sync_recent, minutes)
            } else {
                getApplication<Application>().getString(R.string.memory_sync_stale, minutes)
            }
        } catch (e: Exception) {
            ""
        }
    }
    private fun computeSyncStale(lastCompileTime: String?): Boolean {
        if (lastCompileTime == null) return false
        return try {
            Duration.between(
                Instant.parse(lastCompileTime),
                Instant.now(),
            ).toMinutes() >= 10
        } catch (e: Exception) {
            false
        }
    }

    private fun isToday(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            itemDate == today
        } catch (e: Exception) {
            false
        }
    }

    private fun isThisWeek(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            !itemDate.isBefore(weekStart) && !itemDate.isAfter(today)
        } catch (e: Exception) {
            false
        }
    }

    private fun isThisMonth(createdAt: String?): Boolean {
        if (createdAt == null) return false
        return try {
            val date = java.time.Instant.parse(createdAt)
            val today = java.time.LocalDate.now()
            val itemDate = date.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            itemDate.year == today.year && itemDate.month == today.month
        } catch (e: Exception) {
            false
        }
    }

    // ── v1.98: Biblioteca de experiencias CRUD ──────────────────────────────────────────────

    /**
     * v1.98: Añade una experiencia manualmente.
     * @param title Título(breve descripción)
     * @param content Detalles(Texto de experiencia)
     * @param category Clasificación(Predeterminado"Universales")
     * @param tags lista de etiquetas(Vacío predeterminado)
     */
    fun addExperience(title: String, content: String, category: String = DEFAULT_EXPERIENCE_CATEGORY, tags: List<String> = emptyList()) {
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val entity = ExperienceEntity(
                    id = "exp_${now}_${(0..9999).random()}",
                    title = title.trim(),
                    content = content.trim(),
                    category = category.trim().ifBlank { DEFAULT_EXPERIENCE_CATEGORY },
                    tagsJson = if (tags.isEmpty()) "[]" else AppJson.encodeToString(tags),
                    source = "manual",
                    createdAt = now,
                    updatedAt = now,
                )
                // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                resultOf { experienceRepository.upsert(entity) }
                    .onError { msg, t ->
                        MuseToast.show(
                            getApplication<Application>().getString(R.string.memory_add_experience_failed, msg),
                        )
                    }
            }
            // observeAll Flow Se actualizará automáticamente UI,No se requiere manual loadAll
        }
    }

    /**
     * v1.98: Editar entradas de experiencias existentes(Título/contenido/Clasificación/etiqueta).
     */
    fun editExperience(
        id: String,
        title: String,
        content: String,
        category: String = DEFAULT_EXPERIENCE_CATEGORY,
        tags: List<String> = emptyList(),
    ) {
        if (title.isBlank() || content.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                resultOf {
                    val existing = experienceRepository.getById(id) ?: return@resultOf
                    val updated = existing.copy(
                        title = title.trim(),
                        content = content.trim(),
                        category = category.trim().ifBlank { DEFAULT_EXPERIENCE_CATEGORY },
                        tagsJson = if (tags.isEmpty()) "[]" else AppJson.encodeToString(tags),
                        updatedAt = System.currentTimeMillis(),
                    )
                    experienceRepository.upsert(updated)
                }.onError { msg, t -> MuseToast.show(getApplication<Application>().getString(R.string.memory_edit_experience_failed, msg)) }
            }
        }
    }

    /**
     * v1.98: Eliminar una sola experiencia.
     */
    fun deleteExperience(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // v1.78 (H6): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
                resultOf { experienceRepository.delete(id) }
                    .onError { msg, t ->
                        MuseToast.show(
                            getApplication<Application>().getString(R.string.memory_delete_experience_failed, msg),
                        )
                    }
            }
        }
    }

    /**
     * v1.98: ExperienceEntity → MemoryItem Convertir(Reutilizar el formulario unificado de UI).
     *
     * source Los campos están etiquetados "Experience",Para que la capa UI distinga las fuentes.
     * tags de tagsJson(JSON cadena de matriz)analizado como List<String>.
     */
    private fun ExperienceEntity.toMemoryItem(): MemoryItem {
        val parsedTags = runCatching {
            AppJson.decodeFromString<List<String>>(tagsJson)
        }.getOrDefault(emptyList())
        val timeText = runCatching {
            java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, java.util.Locale.CHINA)
                .format(java.util.Date(updatedAt))
        }.getOrNull()
        return MemoryItem(
            id = id,
            title = title,
            content = content,
            tags = parsedTags,
            time = timeText,
            source = "Experience",
            category = category,
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // v1.0.72: Memoria de chat grupal(centro de memoria"chat grupal"Tab)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * v1.0.72: Cargar todos los recuerdos del chat grupal(Orden descendente por tiempo).
     * visualización en el centro de memoria"chat grupal"Tab,para que los usuarios vean/borrar.
     */
    fun loadGroupChatMemories() {
        viewModelScope.launch {
            _state.update { it.copy(groupChatMemoriesLoading = true) }
            val memories = withContext(Dispatchers.IO) {
                resultOf { groupChatMemoryRepository.getAll() }.getOrNull() ?: emptyList()
            }
            val assistants = resultOf { assistantRepository.observeAll.first() }.getOrNull() ?: emptyList()
            val groupChats = resultOf { groupChatRepository.observeChats().first() }.getOrNull() ?: emptyList()
            val groupChatNameById = groupChats.associateBy({ it.id }, { it.name })
            val assistantNameById = assistants.associateBy({ it.id }, { it.name })
            val items = memories.map { m ->
                GroupChatMemoryUiItem(
                    id = m.id,
                    groupChatId = m.groupChatId,
                    groupChatName = groupChatNameById[m.groupChatId] ?: "chat grupal",
                    assistantName = assistantNameById[m.assistantId] ?: m.assistantId.take(8),
                    summary = m.summary,
                    createdAt = m.createdAt,
                    timeText = runCatching {
                        java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL_SEC, java.util.Locale.CHINA)
                            .format(java.util.Date(m.createdAt))
                    }.getOrNull() ?: "",
                )
            }
            _state.update { it.copy(groupChatMemories = items, groupChatMemoriesLoading = false) }
        }
    }

    /**
     * v1.0.72: Eliminar una única memoria de chat grupal.
     *
     * @param id Memoria de chat grupal id
     */
    fun deleteGroupChatMemory(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf { groupChatMemoryRepository.deleteById(id) }
                    .onError { msg, t ->
                        Logger.w("MemoryViewModel", "No se pudo eliminar la memoria del chat grupal: $msg", t)
                        MuseToast.show(getApplication<Application>().getString(R.string.memory_delete_group_chat_failed, msg))
                    }
            }
            // Actualizar lista después de la eliminación
            _state.update { it.copy(groupChatMemories = it.groupChatMemories.filterNot { m -> m.id == id }) }
        }
    }

    /**
     * v1.0.72: Borrar todos los recuerdos del chat grupal(Un clic).
     */
    fun clearAllGroupChatMemories() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                resultOf { groupChatMemoryRepository.deleteAll() }
                    .onError { msg, t ->
                        Logger.w("MemoryViewModel", "No se pudo borrar la memoria del chat grupal: $msg", t)
                        MuseToast.show(getApplication<Application>().getString(R.string.memory_clear_group_chat_failed, msg))
                    }
            }
            _state.update { it.copy(groupChatMemories = emptyList()) }
        }
    }
}
