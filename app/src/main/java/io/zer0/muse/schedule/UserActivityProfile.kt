package io.zer0.muse.schedule

import android.content.Context
import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar
import kotlin.math.pow

/**
 * Retrato de actividad del usuario — Registre la frecuencia de actividad horaria del usuario en los últimos 7 días,Impulsar la programación de mensajes proactiva y adaptativa.
 *
 * Registre recuentos de actividad por hora utilizando una matriz de 24 dimensiones,Factor de atenuación de aplicación diaria([DECAY_FACTOR]=0.85),hacer datos
 * reflexión aproximada"últimos 7 días"distribución activa(7 peso de la reina ≈ 0.32,Los datos antiguos se desvanecen de forma natural).
 *
 * arranque en frio:Datos insuficientes para nuevos usuarios en los primeros 7 días,Usar imagen predeterminada universal(8-10 a.m. / 20-22 p.m. Altamente activo,
 * Activo 12-14pm,Otra baja actividad)Llega al fondo de esto. período de transición(0-7 dia)Botón de retrato predeterminado y retrato real
 * "días/7"mezcla de peso,Después de 7 días, la imagen real se utilizará por completo. Ver detalles [getActiveProbability].
 *
 * Persistencia simultánea [lastConversationEndType] con [lastKnownMood],suministro [ProactiveMessageRunner]
 * Leer al calcular el intervalo de activación adaptativo(Factor de continuidad del diálogo/Factor emocional).
 *
 * persistir en SharedPreferences(JSON serialización),App Las imágenes no se pierden después de reiniciar.
 *
 * llamador:
 *  - [ProactiveMessageRunner] leer [isHighActiveHour] / [getNextActiveWindow] /
 *    [getLastConversationEndType] / [getLastKnownMood] Determinar el próximo tiempo de activación
 *  - [io.zer0.muse.ui.ChatViewModel] Llamado cuando el usuario envía un mensaje. [recordActivity] Actualizar retrato,
 *    y actualizar según el contenido del mensaje [lastConversationEndType]
 */
class UserActivityProfile(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var state: ProfileState = load()

    // ══════════════════════════════════════════════════════════════════════
    // Registro de actividad
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Registrar la actividad de un usuario(Generalmente se llama cuando el usuario envía un mensaje.).
     *
     * @param timestamp Marca de tiempo de actividad,Hora actual predeterminada
     */
    @Synchronized
    fun recordActivity(timestamp: Long = System.currentTimeMillis()) {
        applyDecayIfNeeded(timestamp)
        val hour = hourOf(timestamp)
        val counts = state.hourlyCounts.toMutableList()
        counts[hour] = counts[hour] + 1
        // arranque en frio:Cuando se registra la actividad por primera vez,Récord de días de inicio(utilizado para el cálculo"¿Cuántos días de datos se han acumulado?",
        // Impulsar la combinación de pesos del retrato predeterminado y el retrato real,lleno [DEFAULT_PROFILE_DAYS] La Reina utiliza por completo retratos reales.)
        val currentDay = (timestamp / (24 * 60 * 60 * 1000L)).toInt()
        val firstDay = if (state.firstRecordDay == 0) currentDay else state.firstRecordDay
        state = state.copy(
            hourlyCounts = counts,
            totalRecords = state.totalRecords + 1,
            lastRecordAt = timestamp,
            firstRecordDay = firstDay,
        )
        save()
    }

    /**
     * Devuelve la probabilidad de actividad durante una hora determinada.(normalizado a 0-1).
     *
     * Arranque en frío y período de transición(Datos acumulados < [DEFAULT_PROFILE_DAYS] dia):
     *  - Retrato predeterminado([DEFAULT_HOURLY_COUNTS])Mezclar con imágenes reales por peso.
     *  - peso real = Número de días acumulados / [DEFAULT_PROFILE_DAYS];Peso predeterminado = 1 - peso real
     *  - Reemplazado gradualmente con datos reales,lleno [DEFAULT_PROFILE_DAYS] La Reina utiliza por completo retratos reales.
     *
     * Si el número total después de mezclar es 0(Agotado por falta de uso a largo plazo),Devuelve distribución uniforme(1/24 ≈ 0.042).
     */
    @Synchronized
    fun getActiveProbability(hour: Int): Float {
        applyDecayIfNeeded(System.currentTimeMillis())
        val h = hour.coerceIn(0, 23)
        val daysCollected = getDaysCollected()
        // 7 días o más:Utilizando completamente imágenes reales.
        if (daysCollected >= DEFAULT_PROFILE_DAYS) {
            val counts = state.hourlyCounts
            val total = counts.sum().toFloat()
            if (total <= 0f) return DEFAULT_PROBABILITY
            return (counts[h] / total).coerceIn(0f, 1f)
        }
        // arranque en frio/período de transición:Imagen por defecto + datos reales mezclados según peso
        val realWeight = daysCollected.toFloat() / DEFAULT_PROFILE_DAYS
        val defaultWeight = 1f - realWeight
        val realCounts = state.hourlyCounts
        var blendedTotal = 0f
        var blendedHour = 0f
        for (i in 0 until 24) {
            val blended = realWeight * realCounts[i] + defaultWeight * DEFAULT_HOURLY_COUNTS[i]
            blendedTotal += blended
            if (i == h) blendedHour = blended
        }
        if (blendedTotal <= 0f) return DEFAULT_PROBABILITY
        return (blendedHour / blendedTotal).coerceIn(0f, 1f)
    }

    /**
     * Determinar si una determinada hora pertenece a"Periodo de alta actividad".
     *
     * umbral = Probabilidad activa promedio × [ACTIVE_THRESHOLD_FACTOR](1.5).
     * arranque en frio/Período de transición pasado [getActiveProbability] La combinación de retratos predeterminada garantiza que los nuevos usuarios también puedan obtenerla
     * Períodos razonables de alta actividad.(Mañana 8-10/noche 20-22),en lugar del viejo"Activo en todo momento"Estrategia.
     */
    @Synchronized
    fun isHighActiveHour(hour: Int): Boolean {
        applyDecayIfNeeded(System.currentTimeMillis())
        val prob = getActiveProbability(hour)
        val avgProb = 1f / 24f
        return prob >= avgProb * ACTIVE_THRESHOLD_FACTOR
    }

    /**
     * Regresar de [fromTime] La hora de inicio del próximo período de alta actividad.(a la hora).
     *
     * - si [fromTime] Actualmente muy activo y dentro del periodo permitido.,Regresar [fromTime](disparar inmediatamente)
     * - De lo contrario, escanee las próximas 48 horas,Encuentra el siguiente para satisfacer al mismo tiempo."Actividad alta + durante las horas permitidas"todo el punto
     * - Volver a cuando no se encuentra [fromTime] + 1 horas(Ten todo en cuenta,Evita retrasos infinitos)
     *
     * @param fromTime marca de tiempo de inicio
     * @param allowedHourStart Horas de inicio de la ventana de envío permitidas(0-23),Alineado con ProactiveMessageConfig
     * @param allowedHourEnd Horas de finalización de la ventana de envío permitidas(0-23,Soporte nocturno,Como indica 22-822Haga clic para pasar al día siguiente8punto)
     */
    @Synchronized
    fun getNextActiveWindow(fromTime: Long, allowedHourStart: Int = 8, allowedHourEnd: Int = 22): Long {
        applyDecayIfNeeded(fromTime)
        val currentHour = hourOf(fromTime)
        // Actualmente muy activo y dentro del periodo permitido. → Regresar inmediatamente
        if (isInAllowedWindow(currentHour, allowedHourStart, allowedHourEnd) &&
            isHighActiveHour(currentHour)
        ) {
            return fromTime
        }
        // Escanee las próximas 48 horas,Encuentra la próxima hora alta de actividad
        for (offset in 1..48) {
            val hour = (currentHour + offset) % 24
            if (!isInAllowedWindow(hour, allowedHourStart, allowedHourEnd)) continue
            if (!isHighActiveHour(hour)) continue
            // Calcular la próxima marca de tiempo horaria de la hora
            val candidate = Calendar.getInstance().apply {
                timeInMillis = fromTime
                add(Calendar.HOUR_OF_DAY, offset)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return candidate.timeInMillis
        }
        // Ten todo en cuenta:fromTime + 1 horas
        return fromTime + 60 * 60 * 1000L
    }

    // ══════════════════════════════════════════════════════════════════════
    // continuidad del diálogo
    // ══════════════════════════════════════════════════════════════════════

    /** Devuelve el tipo de finalización de la conversación grabada más recientemente(persistencia). */
    fun getLastConversationEndType(): ConversationEndType {
        return runCatching {
            ConversationEndType.valueOf(state.lastConversationEndType)
        }.getOrDefault(ConversationEndType.NATURAL_FADE)
    }

    /** Actualizar tipo de fin de conversación(persistencia). Llamado por ChatViewModel/ProactiveMessageRunner. */
    @Synchronized
    fun setConversationEndType(type: ConversationEndType) {
        if (state.lastConversationEndType == type.name) return
        state = state.copy(lastConversationEndType = type.name)
        save()
    }

    // ══════════════════════════════════════════════════════════════════════
    // continuidad emocional
    // ══════════════════════════════════════════════════════════════════════

    /** Devuelve la opinión del usuario registrada más recientemente.(persistencia,Actualizado por ProactiveMessageRunner en patrulla). */
    fun getLastKnownMood(): String = state.lastKnownMood

    /** Actualizar sentimientos conocidos recientes(persistencia). */
    @Synchronized
    fun setLastKnownMood(mood: String) {
        if (state.lastKnownMood == mood) return
        state = state.copy(lastKnownMood = mood)
        save()
    }

    // ══════════════════════════════════════════════════════════════════════
    // interno:Mezcla de retratos con arranque en frío
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Calcular el número de días de datos reales acumulados.(Para la fusión de retratos con inicio en frío).
     *
     * - [ProfileState.firstRecordDay] para 0(Actividad nunca registrada)→ Regresar 0(Imagen predeterminada pura)
     * - Después de la decadencia [ProfileState.totalRecords] ≤ 0(Los datos decaen debido a la falta de uso a largo plazo)→ Regresar 0(Volver a la imagen predeterminada)
     * - De lo contrario regresar currentDay - firstRecordDay + 1(1-indexed,el primer dia 1)
     */
    private fun getDaysCollected(): Int {
        if (state.firstRecordDay == 0) return 0
        if (state.totalRecords <= 0) return 0
        val currentDay = (System.currentTimeMillis() / (24 * 60 * 60 * 1000L)).toInt()
        return (currentDay - state.firstRecordDay + 1).coerceAtLeast(1)
    }

    // ══════════════════════════════════════════════════════════════════════
    // interno:decadencia y persistencia
    // ══════════════════════════════════════════════════════════════════════

    /**
     * decadencia por día(0.85/dia). Compruébalo cada vez que leas o escribas,Si abarca varios días, se compensará la atenuación.
     */
    private fun applyDecayIfNeeded(timestamp: Long) {
        val currentDay = (timestamp / (24 * 60 * 60 * 1000L)).toInt()
        val lastDay = state.lastDecayDay
        if (lastDay == 0) {
            // primer tiempo:Registra solo el día actual,Sin atenuación
            state = state.copy(lastDecayDay = currentDay)
            return
        }
        if (currentDay <= lastDay) return
        val elapsedDays = (currentDay - lastDay).coerceAtMost(MAX_DECAY_DAYS)
        if (elapsedDays <= 0) return
        val factor = DECAY_FACTOR.pow(elapsedDays)
        val decayed = state.hourlyCounts.map { (it * factor).toInt() }
        val newTotal = decayed.sum()
        state = state.copy(
            hourlyCounts = decayed,
            totalRecords = newTotal,
            lastDecayDay = currentDay,
        )
        Logger.d(TAG, "Decaimiento del perfil de actividad: elapsedDays=$elapsedDays, factor=$factor, total=$newTotal")
    }

    private fun load(): ProfileState {
        val raw = prefs.getString(KEY_STATE, null) ?: return ProfileState()
        return runCatching {
            json.decodeFromString<ProfileState>(raw)
        }.onFailure { t ->
            Logger.w(TAG, "No se pudo cargar el perfil de actividad,Usar valor predeterminado: ${t.message}")
        }.getOrDefault(ProfileState())
    }

    @Synchronized
    private fun save() {
        prefs.edit().putString(KEY_STATE, json.encodeToString(ProfileState.serializer(), state)).apply()
    }

    private fun hourOf(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    /** Determinar si una hora determinada está dentro del período de envío permitido(Soporte nocturno,Como por ejemplo 22-8). */
    private fun isInAllowedWindow(hour: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }

    companion object {
        private const val TAG = "UserActivityProfile"
        private const val PREFS_NAME = "user_activity_profile"
        private const val KEY_STATE = "profile_state"
        private const val DEFAULT_PROBABILITY = 1f / 24f

        /** Factor de umbral de actividad alto:probabilidad ≥ probabilidad promedio × Este factor se considera muy activo. */
        private const val ACTIVE_THRESHOLD_FACTOR = 1.5f

        /** factor de decaimiento diario(0.85 → 7 peso de la reina ≈ 0.32). */
        private const val DECAY_FACTOR = 0.85f

        /** El número máximo de días de descomposición para un solo recálculo.(Evite que la aplicación se deteriore después de mucho tiempo sin abrirse 0). */
        private const val MAX_DECAY_DAYS = 30

        /**
         * Días de cobertura de imagen predeterminada de inicio en frío — Después de que los datos reales acumulados alcancen esta cantidad de días, las imágenes reales se utilizarán por completo.
         * Cuando menos de este número de días,Botón de retrato predeterminado y retrato real"días/[DEFAULT_PROFILE_DAYS]"Mezcla de pesos.
         */
        private const val DEFAULT_PROFILE_DAYS = 7

        /**
         * Imagen predeterminada de arranque en frío — 24 Recuento de actividad por hora(peso relativo,valor no absoluto).
         *
         * Plantilla de horario general de multitudes,La programación para los primeros 7 días de nuevos usuarios se basa en este retrato,Reemplace gradualmente a medida que se acumulen datos reales:
         *  - 8-10 a.m.(8,9,10):Altamente activo(3.0)— ir al trabajo/empezar a trabajar
         *  - 12-14 del mediodía(12,13,14):medio activo(1.5)— pausa para el almuerzo
         *  - 20-22 hs(20,21,22):Altamente activo(3.0)— Ocio nocturno
         *  - otros períodos de tiempo:baja actividad(0.3)— madrugada/horas de trabajo
         */
        private val DEFAULT_HOURLY_COUNTS: List<Float> = listOf(
            // 0-6: Baja actividad a primera hora de la mañana.
            0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f,
            // 7: despertar transición
            0.5f,
            // 8-10: Hora punta de la mañana
            3.0f, 3.0f, 3.0f,
            // 11: transición antes del almuerzo
            0.5f,
            // 12-14: activo durante la pausa del almuerzo
            1.5f, 1.5f, 1.5f,
            // 15-19: Baja actividad en el trabajo por la tarde.
            0.3f, 0.3f, 0.3f, 0.3f, 0.3f,
            // 20-22: Hora punta de la tarde
            3.0f, 3.0f, 3.0f,
            // 23: transición a la hora de dormir
            0.5f,
        )

        /**
         * Palabras clave para que los usuarios finalicen activamente la conversación.(usado para [ConversationEndType.USER_EXPLICIT_END] juicio).
         */
        val END_KEYWORDS = listOf(
            "buenas noches", "Adiós", "primero así", "Adiós", "ido", "Charla la próxima vez", "Nos vemos luego",
            "bye", "goodbye", "good night", "gn", "see you", "cya",
        )

        /**
         * Determine si el contenido del mensaje contiene la palabra clave de fin de conversación.
         */
        fun containsEndKeyword(content: String): Boolean {
            val lower = content.lowercase().trim()
            return END_KEYWORDS.any { lower.contains(it) }
        }
    }
}

/**
 * Tipo de fin de conversación — influencia [ProactiveMessageRunner] Intervalo de disparo adaptativo.
 *
 * - [USER_EXPLICIT_END]:El usuario termina activamente(dijo"buenas noches"/"Adiós"espera)→ intervalo ×1.5,No me molestes con prisa
 * - [NATURAL_FADE]:Agent El usuario no volvió a hablar tras responder.(final natural)→ intervalo normal
 * - [UNFINISHED_QUESTION]:Agent Hizo una pregunta pero el usuario no respondió. → intervalo ×0.6,Seguimiento rápido
 */
enum class ConversationEndType {
    USER_EXPLICIT_END,
    NATURAL_FADE,
    UNFINISHED_QUESTION,
}

/** Estado de persistencia del retrato de actividad. */
@Serializable
private data class ProfileState(
    /** 24 Recuento de actividad por hora(El índice 0-23 corresponde a la hora.). */
    val hourlyCounts: List<Int> = List(24) { 0 },
    /** Número total acumulado de actividades(Después de la decadencia). */
    val totalRecords: Int = 0,
    /** Marca de tiempo de la última actividad. */
    val lastRecordAt: Long = 0L,
    /** Número de días desde 1970-01-01,Utilizado para el decaimiento diario. */
    val lastDecayDay: Int = 0,
    /**
     * Número de días desde que se registró la actividad por primera vez(distancia 1970-01-01),Se utiliza para la fusión de retratos con inicio en frío.
     * 0 Indica que aún no se ha registrado ninguna actividad;Una vez configurado, no se puede cambiar.
     * Calculado en base al número actual de días."¿Cuántos días de datos se han acumulado?",Impulsa la combinación ponderada del retrato predeterminado y el retrato real.
     */
    val firstRecordDay: Int = 0,
    /** Tipo de fin de conversación reciente([ConversationEndType] de name). */
    val lastConversationEndType: String = "NATURAL_FADE",
    /** Emociones recientemente conocidas([io.zer0.muse.data.proactive.Mood] de name). */
    val lastKnownMood: String = "UNKNOWN",
)
