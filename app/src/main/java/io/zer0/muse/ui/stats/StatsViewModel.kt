package io.zer0.muse.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.MessageDao
import io.zer0.muse.data.session.SessionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * v1.104 U8: Enumeración del filtro de rango de tiempo de la página de estadísticas.
 *
 *  - [ALL_TIME]:toda la historia(Predeterminado)
 *  - [THIS_MONTH]:A partir de las 00:00 horas del día 1 de este mes
 *  - [THIS_WEEK]:Desde las 00:00 de este lunes
 *  - [TODAY]:A partir de las 00:00 hoy
 *
 * Tarjeta de descripción general(Total / Respuestas de IA / Mensajes de usuario / Promedio diario / Más activo / Tendencias semanales)Filtrado de respuestas;
 * tarjetas gráficas(modelo/asistente/horas/Top sesión/mapa de calor)Mantener el historial completo,para garantizar la integridad de la tendencia.
 */
enum class StatsTimeRange {
    ALL_TIME,
    THIS_MONTH,
    THIS_WEEK,
    TODAY,
}

/**
 * v0.46: Página de estadísticas ViewModel.
 *
 * fuente de datos:
 *  - [MessageDao.getAllUserMessageTimestamps]:user Marca de tiempo del mensaje → Número de mensajes de usuario por día(mapa de calor /
 *    Día más activo/número de días activos consecutivos,Utilice la vieja lógica para mantener sin cambios el calibre del mapa de calor.).
 *  - [MessageDao.getAllMessageTimestamps]:Todas las marcas de tiempo de los mensajes → Número total de mensajes/semana/mes /
 *    Tendencias medias diarias/semanales.
 *  - [MessageDao.countByRole]:Contar por grupo de roles → AI Número de respuestas.
 *  - [SessionDao.count]:Número total de sesiones → Conversaciones totales(Arreglar la versión anterior donde totalSessions siempre es 0 bug).
 *
 * v0.47 Expandir(migración cero):
 *  - [MessageDao.countByModel]:Agrupar por ID de modelo → El modelo utiliza un gráfico de anillos proporcional.
 *  - [MessageDao.countByAssistant]:Agrupar por ID de asistente → El asistente utiliza gráficos de barras horizontales proporcionales.
 *  - [MessageDao.countByHour]:Grupo por hora → 24 Gráfico de barras raíz.
 *  - [MessageDao.topSessionsByMessageCount]:Top 10 Lista de sesiones activas.
 *
 * La marca de tiempo se convierte a LocalDate según la zona horaria del sistema y luego se usa groupBy para las estadísticas.
 */
class StatsViewModel(
    application: Application,
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao,
    private val settingsRepository: SettingsRepository,
    private val assistantRepository: AssistantRepository,
) : AndroidViewModel(application) {

    data class StatsUiState(
        val isLoading: Boolean = true,
        /** v1.104 U8: Filtrar por rango de tiempo actual(Por defecto todo el historial). */
        val timeRange: StatsTimeRange = StatsTimeRange.ALL_TIME,
        /** Número de mensajes de usuario por día,key = LocalDate(yyyy-MM-dd),value = Número de mensajes(Para mapas de calor). */
        val messagesPerDay: Map<LocalDate, Int> = emptyMap(),
        /** Conversaciones totales(comprobar SessionDao.count()). */
        val totalSessions: Int = 0,
        /** Número total de mensajes(Todas las novedades de personajes.). */
        val totalMessages: Int = 0,
        /** AI Número de respuestas(ASSISTANT papel). */
        val totalAiMessages: Int = 0,
        /** Número de mensajes de usuario(USER papel,Consistente con la fuente de datos del mapa de calor). */
        val totalUserMessages: Int = 0,
        /** día más activo(USER La mayoría de los mensajes). */
        val mostActiveDay: Pair<LocalDate, Int>? = null,
        /** Número de días activos consecutivos(Contando desde hoy,El número de días consecutivos con mensajes de usuario.). */
        val streakDays: Int = 0,
        /** Número promedio de mensajes por día(Total de mensajes/número de días desde el primer mensaje,Incluyendo el principio y el final.). */
        val avgMessagesPerDay: Double = 0.0,
        /** Número de mensajes esta semana(Todas las novedades a partir de las 00:00 de este lunes). */
        val messagesThisWeek: Int = 0,
        /** Número de mensajes este mes(Todas las novedades a partir de las 00:00 horas del día 1 de este mes.). */
        val messagesThisMonth: Int = 0,
        /** v1.0.30: Número acumulado de caracteres recibidos (ASSISTANT noticias,≈ token estimación). */
        val totalCharsReceived: Long = 0,
        /** Número de mensajes diarios en los últimos 7 días(para gráficos de barras de tendencias semanales, oldest → today). */
        val weeklyTrend: List<Pair<LocalDate, Int>> = emptyList(),

        // ── Estadísticas de uso (de use_records, ya no atraviesa mensajes para resolver JSON)──
        /** El número de solicitudes en el intervalo de tiempo actual. */
        val usageRequests: Int = 0,
        /** Ingrese el total del token. */
        val usagePromptTokens: Long = 0,
        /** Genere el número total de tokens (incluido el razonamiento). */
        val usageCompletionTokens: Long = 0,
        /** Total de tokens de inferencia (solo visualización). */
        val usageReasoningTokens: Long = 0,
        /** caché**leer** token Total (solo visualización). */
        val usageCachedTokens: Long = 0,
        /** caché**escribir** token Total (solo visualización). Y lea desglose: el precio unitario de los dos es diferente y no se combinan en un número de \"caché\". */
        val usageCacheWriteTokens: Long = 0,
        /** Retraso promedio en la primera palabra (milisegundos);0 = Sin datos. Este es el indicador más directo de la percepción de velocidad del usuario. */
        val avgFirstTokenMs: Long = 0,
        /** Dividido por fuente (conversaciones/Compresión/Título/Traducción/memoria/OCR…). */
        val usageBySource: List<io.zer0.muse.data.stats.UsageBySource> = emptyList(),
        /** Clasificación de modelos (orden descendente por tokens totales). */
        val usageTopModels: List<io.zer0.muse.data.stats.UsageByModel> = emptyList(),
        /** Tendencias de uso por día. */
        val usageByDay: List<io.zer0.muse.data.stats.UsageByDay> = emptyList(),
        /** v0.47: Proporción de uso por modelo(gráfico de anillos),Orden descendente por cantidad. */
        val modelCounts: List<ModelUsage> = emptyList(),
        /** v0.47: Según proporción de uso del asistente(gráfico de barras horizontales),Orden descendente por cantidad. */
        val assistantCounts: List<AssistantUsage> = emptyList(),
        /** v0.47: Distribución activa por hora(24 elemento,El índice 0-23 corresponde a la hora.). */
        val hourlyDistribution: List<Int> = emptyList(),
        /** v0.47: Top 10 sesión activa(Orden descendente por número de mensajes). */
        val topSessions: List<TopSessionInfo> = emptyList(),
    )

    /** v0.47: Proporción de uso del modelo artículo único(Nombre para mostrar + cantidad + porcentaje). */
    data class ModelUsage(
        val modelName: String,
        val count: Int,
        val percentage: Float,
    )

    /** v0.47: Proporción de uso del asistente elemento único(Nombre para mostrar + cantidad). */
    data class AssistantUsage(
        val assistantName: String,
        val count: Int,
    )

    /** v0.47: Top Elementos activos de la sesión(Título + número de mensajes). */
    data class TopSessionInfo(
        val sessionId: String,
        val title: String,
        val count: Int,
    )

    /** Datos sin procesar extraídos de DAO de una sola vez(Rellenar dentro del hilo IO). */
    private class RawStats(
        val userTimestamps: List<Long>,
        val assistantTimestamps: List<Long>,
        val allTimestamps: List<Long>,
        val sessionCount: Int,
    )

    /** v0.47: Ampliar datos originales(4 Resultados DAO para nuevas dimensiones estadísticas). */
    private class RawExtendedStats(
        val modelCounts: List<io.zer0.muse.data.session.ModelCount>,
        val assistantCounts: List<io.zer0.muse.data.session.AssistantCount>,
        val hourCounts: List<io.zer0.muse.data.session.HourCount>,
        val topSessions: List<io.zer0.muse.data.session.SessionMessageCount>,
        /** v1.0.30: ASSISTANT El número acumulado de caracteres del mensaje. */
        val totalAssistantChars: Long,
    )

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        loadStats()
    }

    /**
     * v1.104 U8: Cambiar filtro de rango de tiempo,Activa una recarga.
     *
     * El mismo valor no se cargará repetidamente. Establecer isLoading al cambiar true,Evite que la interfaz de usuario muestre datos antiguos.
     */
    fun setTimeRange(range: StatsTimeRange) {
        if (_state.value.timeRange == range) return
        _state.update { it.copy(timeRange = range, isLoading = true) }
        loadStats()
    }

    /** Cargar estadísticas(Extraer marca de tiempo de DAO → groupBy Fecha → Calcular indicadores derivados.). */
    fun loadStats() {
        viewModelScope.launch {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now()
            val currentRange = _state.value.timeRange

            // Utilice una clase de datos para empaquetar todos los resultados originales extraídos del subproceso IO en el contexto del subproceso principal.
            data class IoResult(
                val raw: RawStats,
                val ext: RawExtendedStats,
                val providers: List<ProviderConfig>,
                val assistantNames: Map<String, String>,
                val sessionTitles: Map<String, String>,
                // Estadísticas de uso (usage_records Agregación, sin atravesar mensajes)
                val usageTotals: io.zer0.muse.data.stats.UsageTotals?,
                val usageBySource: List<io.zer0.muse.data.stats.UsageBySource>,
                val usageTopModels: List<io.zer0.muse.data.stats.UsageByModel>,
                val usageByDay: List<io.zer0.muse.data.stats.UsageByDay>,
            )

            val io = withContext(Dispatchers.IO) {
                val userTs = resultOf { messageDao.getAllUserMessageTimestamps() }.getOrNull() ?: emptyList()
                // v1.104 U8: Utilice marcas de tiempo en lugar de agregación countByRole,para filtrar por rango de tiempo en la memoria de ViewModel
                val assistantTs = resultOf { messageDao.getAllAssistantMessageTimestamps() }.getOrNull() ?: emptyList()
                val allTs = resultOf { messageDao.getAllMessageTimestamps() }.getOrNull() ?: emptyList()
                val sessions = resultOf { sessionDao.count() }.getOrNull() ?: 0
                val raw = RawStats(userTs, assistantTs, allTs, sessions)

                // v0.47: Extraiga 4 nuevas dimensiones estadísticas(mantener la cantidad total,No responder timeRange — Para tarjetas de gráficos)
                val modelCounts = resultOf { messageDao.countByModel() }.getOrNull() ?: emptyList()
                val assistantCounts = resultOf { messageDao.countByAssistant() }.getOrNull() ?: emptyList()
                val hourCounts = resultOf { messageDao.countByHour() }.getOrNull() ?: emptyList()
                val topSessions = resultOf { messageDao.topSessionsByMessageCount(10) }.getOrNull() ?: emptyList()
                val totalAssistantChars = resultOf { messageDao.sumAssistantContentLength() }.getOrNull() ?: 0L
                val ext = RawExtendedStats(modelCounts, assistantCounts, hourCounts, topSessions, totalAssistantChars)

                // Solía ​​​​buscar nombres:providers(Contiene lista de modelos)+ Mapeo de nombres de asistente
                val providers = resultOf { settingsRepository.providersFlow.first() }.getOrNull() ?: emptyList()
                val assistantNames = (
                    resultOf { assistantRepository.getAll() }
                        .getOrNull() ?: emptyList()
                    )
                    .associate { it.id to it.name }

                // Verifique el título de la conversación principal(sessionDao.getById si suspend,Llamado secuencialmente dentro de IO,Hasta 10 veces sin preocupaciones N+1)
                val sessionTitles = ext.topSessions.associate { it.sessionId to (sessionDao.getById(it.sessionId)?.title ?: "") }

                // Estadísticas de uso: agrega directamente use_records.
                // El rango de tiempo se convierte a sinceMs en el lado de ViewModel (0 = todos) para evitar comprobar cada uno de los 4 rangos una vez.
                // Estadísticas de uso: agrega directamente use_records.
                // DAO Utilice el contenedor global para obtenerlo y evitar cambiar la cadena de construcción por una derivación de solo lectura; cuando no se pueda obtener, cada dimensión se degradará a vacía.
                val usageDao = runCatching {
                    org.koin.core.context.GlobalContext.get()
                        .get<io.zer0.muse.data.session.MuseDb>()
                        .usageRecordDao()
                }.getOrNull()
                val usageTotals = usageDao?.let { runCatching { it.totalsSince(0L) }.getOrNull() }
                val usageBySource = usageDao?.let { runCatching { it.bySourceSince(0L) }.getOrDefault(emptyList()) } ?: emptyList()
                val usageTopModels = usageDao?.let { runCatching { it.topModelsSince(0L, 8) }.getOrDefault(emptyList()) } ?: emptyList()
                val usageByDay = usageDao?.let { runCatching { it.byDaySince(0L) }.getOrDefault(emptyList()) } ?: emptyList()

                IoResult(raw, ext, providers, assistantNames, sessionTitles, usageTotals, usageBySource, usageTopModels, usageByDay)
            }

            val raw = io.raw

            // v1.104 U8: Calcular el punto de inicio del filtrado según el rango de tiempo(milisegundos),null = toda la historia
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val monthStart = today.withDayOfMonth(1)
            val rangeStartMs: Long? = when (currentRange) {
                StatsTimeRange.ALL_TIME -> null
                StatsTimeRange.THIS_MONTH -> monthStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
                StatsTimeRange.THIS_WEEK -> weekStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
                StatsTimeRange.TODAY -> today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            }

            // filtrado timestamps(Para tarjetas de descripción general);null Utilice la lista original directamente(evitar innecesario copy)
            val filteredUserTs = if (rangeStartMs == null) raw.userTimestamps else raw.userTimestamps.filter { it >= rangeStartMs }
            val filteredAssistantTs =
                if (rangeStartMs == null) {
                    raw.assistantTimestamps
                } else {
                    raw.assistantTimestamps.filter { it >= rangeStartMs }
                }
            val filteredAllTs = if (rangeStartMs == null) raw.allTimestamps else raw.allTimestamps.filter { it >= rangeStartMs }

            // user Mensajes agrupados por día(El mapa de calor utiliza la cantidad total,Mantenga el panorama de 53 semanas;más activo/La actividad continua también se basa en el volumen total.)
            val userPerDay = raw.userTimestamps
                .map { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
                .groupBy { it }
                .mapValues { it.value.size }

            // Todos los mensajes agrupados por día.(esta semana/este mes/tendencias semanales/Uso diario promedio,Después de filtrar según)
            val allPerDay = filteredAllTs
                .map { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
                .groupBy { it }
                .mapValues { it.value.size }

            val totalUser = filteredUserTs.size
            val totalAll = filteredAllTs.size
            val totalAi = filteredAssistantTs.size
            // Día más activo:Basado en volumen completo userPerDay,pero solo considera fechas dentro del rango de tiempo
            val mostActive = userPerDay
                .filterKeys { rangeStartMs == null || it.atStartOfDay(zoneId).toInstant().toEpochMilli() >= rangeStartMs }
                .maxByOrNull { it.value }?.let { it.key to it.value }
            // Número de días activos consecutivos:indicadores historicos,mantener la cantidad total(De lo contrario filtrar"hoy"Si no hay noticias hoy,,streak=0 Al contrario, induce a error)
            val streak = calculateStreak(userPerDay)

            // Número de mensajes esta semana(ventana fija,No responder timeRange — De lo contrario filtrar"hoy"esta semana=hoy,Superposición semántica)
            val weekStartMs = weekStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val messagesThisWeek = raw.allTimestamps.count { it >= weekStartMs }

            // Número de mensajes este mes(ventana fija)
            val monthStartMs = monthStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val messagesThisMonth = raw.allTimestamps.count { it >= monthStartMs }

            // Tendencias en los últimos 7 días(basado en filtrado allPerDay;Si filtra"este mes",Las tendencias aún se muestran en los últimos 7 días)
            val weeklyTrend = (0..6).map { offset ->
                val date = today.minusDays((6 - offset).toLong())
                date to (allPerDay[date] ?: 0)
            }

            // promedio diario:Total de mensajes después del filtrado/días de rango de tiempo
            val avgPerDay = if (totalAll == 0) {
                0.0
            } else {
                val days = when (currentRange) {
                    StatsTimeRange.ALL_TIME -> {
                        val firstTs = filteredAllTs.min()
                        val firstDate = Instant.ofEpochMilli(firstTs).atZone(zoneId).toLocalDate()
                        ChronoUnit.DAYS.between(firstDate, today) + 1
                    }
                    StatsTimeRange.THIS_MONTH -> ChronoUnit.DAYS.between(monthStart, today) + 1
                    StatsTimeRange.THIS_WEEK -> ChronoUnit.DAYS.between(weekStart, today) + 1
                    StatsTimeRange.TODAY -> 1
                }
                if (days > 0) totalAll.toDouble() / days else 0.0
            }

            // ── v0.47: Ensamblando 4 nuevas dimensiones estadísticas(mantener la cantidad total) ──

            // Relación de uso del modelo:Consulte proveedores para obtener "Nombre del proveedor/modelo",Pantalla de verificación inversa fallida modelId
            val modelNameById = buildMap {
                io.providers.forEach { provider ->
                    provider.models.forEach { model ->
                        put(model.id, "${provider.displayName} / ${model.name}")
                    }
                }
            }
            val modelTotal = io.ext.modelCounts.sumOf { it.cnt }
            val modelUsages = io.ext.modelCounts
                .sortedByDescending { it.cnt }
                .map { mc ->
                    val name = modelNameById[mc.modelId]
                        ?: mc.modelId.ifBlank { getApplication<Application>().getString(R.string.stats_unknown_model) }
                    val pct = if (modelTotal > 0) mc.cnt.toFloat() / modelTotal else 0f
                    ModelUsage(modelName = name, count = mc.cnt, percentage = pct)
                }

            // Proporción de uso del asistente:Contrainvestigación assistantNames,Visualización de fallos "Asistente predeterminado"
            val assistantUsages = io.ext.assistantCounts
                .sortedByDescending { it.cnt }
                .map { ac ->
                    AssistantUsage(
                        assistantName =
                        io.assistantNames[ac.assistantId]
                            ?: getApplication<Application>()
                                .getString(R.string.stats_default_assistant),
                        count = ac.cnt,
                    )
                }

            // Distribución activa horaria:Completa 0-23, un total de 24 espacios.,complemento faltante 0
            val hourlyArray = IntArray(24)
            io.ext.hourCounts.forEach { hc ->
                val hour = hc.hour.toIntOrNull() ?: return@forEach
                if (hour in 0..23) hourlyArray[hour] = hc.cnt
            }
            val hourlyDistribution = hourlyArray.toList()

            // Top 10 sesión activa:Comprueba el título,Visualización de fallos "Conversación eliminada"
            val topSessionInfos = io.ext.topSessions.map { smc ->
                TopSessionInfo(
                    sessionId = smc.sessionId,
                    title =
                    io.sessionTitles[smc.sessionId]?.ifBlank { null }
                        ?: getApplication<Application>()
                            .getString(R.string.stats_deleted_session),
                    count = smc.cnt,
                )
            }

            _state.update {
                StatsUiState(
                    isLoading = false,
                    timeRange = currentRange,
                    messagesPerDay = userPerDay,
                    totalSessions = raw.sessionCount,
                    totalMessages = totalAll,
                    totalAiMessages = totalAi,
                    totalUserMessages = totalUser,
                    mostActiveDay = mostActive,
                    streakDays = streak,
                    avgMessagesPerDay = avgPerDay,
                    messagesThisWeek = messagesThisWeek,
                    messagesThisMonth = messagesThisMonth,
                    totalCharsReceived = io.ext.totalAssistantChars,
                    weeklyTrend = weeklyTrend,
                    modelCounts = modelUsages,
                    assistantCounts = assistantUsages,
                    hourlyDistribution = hourlyDistribution,
                    topSessions = topSessionInfos,
                    // Estadísticas de uso
                    usageRequests = io.usageTotals?.requests ?: 0,
                    usagePromptTokens = io.usageTotals?.promptTokens ?: 0L,
                    usageCompletionTokens = io.usageTotals?.completionTokens ?: 0L,
                    usageReasoningTokens = io.usageTotals?.reasoningTokens ?: 0L,
                    usageCachedTokens = io.usageTotals?.cachedTokens ?: 0L,
                    usageCacheWriteTokens = io.usageTotals?.cacheWriteTokens ?: 0L,
                    avgFirstTokenMs = io.usageTotals?.avgFirstTokenMs ?: 0L,
                    usageBySource = io.usageBySource,
                    usageTopModels = io.usageTopModels,
                    usageByDay = io.usageByDay,
                )
            }
        }
    }

    /** Calcule el número de días activos consecutivos contando desde hoy(Piénsalo si hay novedades hoy. 1,De lo contrario directamente 0). */
    private fun calculateStreak(perDay: Map<LocalDate, Int>): Int {
        var streak = 0
        var date = LocalDate.now()
        while (perDay.containsKey(date)) {
            streak++
            date = date.minusDays(1)
        }
        return streak
    }
}
