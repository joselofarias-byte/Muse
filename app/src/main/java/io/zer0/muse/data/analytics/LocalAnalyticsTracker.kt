package io.zer0.muse.data.analytics

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.analyticsDataStore: DataStore<Preferences> by preferencesDataStore(name = "muse_analytics")

/**
 * Phase 6 6E: Primer análisis local — Realice un seguimiento de métricas principales como DAU/MAU, tasa de finalización de conversaciones, recuento diario de chats, etc.
 *
 * Privacidad: Todos los datos almacenados localmente,Sólo se pueden cargar datos agregados anónimos.
 *
 * Métricas de seguimiento:
 * - DAU/MAU: Actividad diaria/actividad mensual
 * - Tasa de finalización de la primera conversación: Porcentaje de nuevos usuarios que completan su primera conversación
 * - Número de chats por día: Número de mensajes enviados por día
 * - Tasa de adopción del sistema de memoria: Porcentaje de usuarios que utilizan la función de memoria
 * - tasa de accidentes: recuento de accidentes/Número de salidas
 * - Distribución del uso de funciones.: Frecuencia de uso de cada función
 * - retener: D1/D7/D30
 */
/** Derivar la deduplicación del flujo de configuración:DataStore.data Elimine únicamente los duplicados de todas las Preferencias.map Luego, cada uno se deduplica para suprimir la emisión repetida del mismo valor provocada por claves irrelevantes. */
private fun <T> Flow<Preferences>.mapDistinct(transform: suspend (Preferences) -> T): Flow<T> = map(transform).distinctUntilChanged()

class LocalAnalyticsTracker(
    private val context: Context,
) {
    private val store get() = context.analyticsDataStore

    companion object {
        private const val TAG = "Analytics"

        // v1.131: SimpleDateFormat Cambiar a caché ThreadLocal,Evite crear otros nuevos cada vez que se llame a todayStr/monthStr.
        // SimpleDateFormat No es seguro para subprocesos,no se puede mencionar directamente como estático val;ThreadLocal Asegurar una instancia independiente por hilo.
        // El punto enterrado es la ruta de alta frecuencia.(enviar cada mensaje/Activado por el cambio de sesión),La implementación anterior tiene una presión de GC obvia.
        private val DAY_FMT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
        private val MONTH_FMT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM", Locale.getDefault()) }
        private val KEY_DAU_TODAY = intPreferencesKey("dau_today")
        private val KEY_MAU_MONTH = intPreferencesKey("mau_month")
        private val KEY_TOTAL_SESSIONS = intPreferencesKey("total_sessions")
        private val KEY_TOTAL_MESSAGES = intPreferencesKey("total_messages")
        private val KEY_FIRST_CHAT_COMPLETED = intPreferencesKey("first_chat_completed")
        private val KEY_CRASH_COUNT = intPreferencesKey("crash_count")
        private val KEY_LAUNCH_COUNT = intPreferencesKey("launch_count")
        private val KEY_LAST_ACTIVE_DATE = stringPreferencesKey("last_active_date")
        private val KEY_LAST_ACTIVE_MONTH = stringPreferencesKey("last_active_month")
        private val KEY_FIRST_LAUNCH_DATE = longPreferencesKey("first_launch_date")
        private val KEY_D1_RETENTION = intPreferencesKey("d1_retention")
        private val KEY_D7_RETENTION = intPreferencesKey("d7_retention")
        private val KEY_D30_RETENTION = intPreferencesKey("d30_retention")
        private val KEY_FEATURE_USAGE = stringPreferencesKey("feature_usage_json")
        private val KEY_MEMORY_ADOPTED = intPreferencesKey("memory_adopted")
    }

    /** Analizar el flujo de datos. */
    val analyticsFlow: Flow<AnalyticsSnapshot> = store.data.mapDistinct { prefs ->
        AnalyticsSnapshot(
            dauToday = prefs[KEY_DAU_TODAY] ?: 0,
            mauMonth = prefs[KEY_MAU_MONTH] ?: 0,
            totalSessions = prefs[KEY_TOTAL_SESSIONS] ?: 0,
            totalMessages = prefs[KEY_TOTAL_MESSAGES] ?: 0,
            firstChatCompleted = (prefs[KEY_FIRST_CHAT_COMPLETED] ?: 0) > 0,
            crashCount = prefs[KEY_CRASH_COUNT] ?: 0,
            launchCount = prefs[KEY_LAUNCH_COUNT] ?: 0,
            lastActiveDate = prefs[KEY_LAST_ACTIVE_DATE] ?: "",
            d1Retention = prefs[KEY_D1_RETENTION] ?: 0,
            d7Retention = prefs[KEY_D7_RETENTION] ?: 0,
            d30Retention = prefs[KEY_D30_RETENTION] ?: 0,
            memoryAdopted = (prefs[KEY_MEMORY_ADOPTED] ?: 0) > 0,
        )
    }

    /** Grabar el inicio de la aplicación(Primera vez por día + número acumulado de inicios). */
    suspend fun trackLaunch() {
        val today = todayStr()
        val month = monthStr()
        store.edit { prefs ->
            // Primera fecha de lanzamiento
            if (prefs[KEY_FIRST_LAUNCH_DATE] == null) {
                prefs[KEY_FIRST_LAUNCH_DATE] = System.currentTimeMillis()
            }
            // DAU: Si no lo has grabado hoy,Significa que es un nuevo día
            if (prefs[KEY_LAST_ACTIVE_DATE] != today) {
                prefs[KEY_DAU_TODAY] = 1
                prefs[KEY_LAST_ACTIVE_DATE] = today
                // Verificar retención D1/D7/D30
                checkRetention(prefs)
            }
            // MAU: Si no hay registro este mes
            if (prefs[KEY_LAST_ACTIVE_MONTH] != month) {
                prefs[KEY_MAU_MONTH] = 1
                prefs[KEY_LAST_ACTIVE_MONTH] = month
            }
            // Inicios acumulados
            prefs[KEY_LAUNCH_COUNT] = (prefs[KEY_LAUNCH_COUNT] ?: 0) + 1
        }
    }

    /** Grabar mensajes de envío. */
    suspend fun trackMessageSent() {
        store.edit { prefs ->
            prefs[KEY_TOTAL_MESSAGES] = (prefs[KEY_TOTAL_MESSAGES] ?: 0) + 1
        }
    }

    /** El registro crea una nueva sesión. */
    suspend fun trackSessionCreated() {
        store.edit { prefs ->
            prefs[KEY_TOTAL_SESSIONS] = (prefs[KEY_TOTAL_SESSIONS] ?: 0) + 1
            // Primera conversación completada
            if ((prefs[KEY_TOTAL_SESSIONS] ?: 0) == 1) {
                prefs[KEY_FIRST_CHAT_COMPLETED] = 1
            }
        }
    }

    /** Fallo de registro. */
    suspend fun trackCrash() {
        store.edit { prefs ->
            prefs[KEY_CRASH_COUNT] = (prefs[KEY_CRASH_COUNT] ?: 0) + 1
        }
    }

    /** Registre el uso del sistema de memoria. */
    suspend fun trackMemoryUsed() {
        store.edit { prefs ->
            if ((prefs[KEY_MEMORY_ADOPTED] ?: 0) == 0) {
                prefs[KEY_MEMORY_ADOPTED] = 1
            }
        }
    }

    /** Uso de la función de registro(Contar por nombre de función). */
    suspend fun trackFeatureUsage(featureName: String) {
        store.edit { prefs ->
            val raw = prefs[KEY_FEATURE_USAGE] ?: "{}"
            val mapSerializer = MapSerializer(String.serializer(), Int.serializer())
            val usage = try {
                io.zer0.common.AppJson.decodeFromString(mapSerializer, raw).toMutableMap()
            } catch (_: Exception) {
                mutableMapOf<String, Int>()
            }
            usage[featureName] = (usage[featureName] ?: 0) + 1
            prefs[KEY_FEATURE_USAGE] = io.zer0.common.AppJson.encodeToString(mapSerializer, usage)
        }
    }

    /** Obtenga una instantánea del análisis actual. */
    suspend fun getSnapshot(): AnalyticsSnapshot = analyticsFlow.first()

    /**
     * P3-2: Obtener recuento de uso de funciones Map(featureName → count),Retornar en orden descendente por conteo.
     *
     * Se utiliza para la visualización del panel de análisis de datos local. Top N,No se informa nada.
     */
    suspend fun getFeatureUsage(): List<Pair<String, Int>> {
        val raw = store.data.first()[KEY_FEATURE_USAGE] ?: "{}"
        val mapSerializer = MapSerializer(String.serializer(), Int.serializer())
        val usage = try {
            io.zer0.common.AppJson.decodeFromString(mapSerializer, raw)
        } catch (_: Exception) {
            emptyMap()
        }
        return usage.entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
    }

    /** Comprobar retención(D1/D7/D30). */
    private fun checkRetention(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val firstLaunch = prefs[KEY_FIRST_LAUNCH_DATE] ?: return
        val daysSinceFirst = (System.currentTimeMillis() - firstLaunch) / 86_400_000L
        when {
            daysSinceFirst >= 1 && (prefs[KEY_D1_RETENTION] ?: 0) == 0 -> {
                prefs[KEY_D1_RETENTION] = 1
            }
            daysSinceFirst >= 7 && (prefs[KEY_D7_RETENTION] ?: 0) == 0 -> {
                prefs[KEY_D7_RETENTION] = 1
            }
            daysSinceFirst >= 30 && (prefs[KEY_D30_RETENTION] ?: 0) == 0 -> {
                prefs[KEY_D30_RETENTION] = 1
            }
        }
    }

    private fun todayStr(): String = DAY_FMT.get()?.format(Date()) ?: ""
    private fun monthStr(): String = MONTH_FMT.get()?.format(Date()) ?: ""
}

/** Analizar instantáneas de datos. */
@Serializable
data class AnalyticsSnapshot(
    val dauToday: Int = 0,
    val mauMonth: Int = 0,
    val totalSessions: Int = 0,
    val totalMessages: Int = 0,
    val firstChatCompleted: Boolean = false,
    val crashCount: Int = 0,
    val launchCount: Int = 0,
    val lastActiveDate: String = "",
    val d1Retention: Int = 0,
    val d7Retention: Int = 0,
    val d30Retention: Int = 0,
    val memoryAdopted: Boolean = false,
) {
    val crashRate: Float
        get() = if (launchCount > 0) crashCount.toFloat() / launchCount else 0f
}
