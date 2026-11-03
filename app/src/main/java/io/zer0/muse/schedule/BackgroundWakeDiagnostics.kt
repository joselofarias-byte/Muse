package io.zer0.muse.schedule

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.proactive.ProactiveWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutionException

/**
 * Superficie observable para despertar en segundo plano.
 *
 * Hay tres razones completamente diferentes por las que una tarea programada "no se ejecutó".,En el pasado, sólo podía confiar en las conjeturas.:
 *  1. Aplicar su propio registro de ejecución([io.zer0.muse.data.schedule.ScheduledTaskExecutionDao])hay failed / skipped / missed → corrió a través;
 *  2. No hay nada en el registro. → El sistema no activa el proceso en absoluto.,O el control total leído por cada cuerpo de ejecución después de despertar es false;
 *  3. WorkManager La tarea periódica aquí no está registrada en absoluto.(No abrí la aplicación después de instalarla.) → Nunca te despiertes.
 *
 * 1 si UI(historia de la misión),2 y 3 sin entrada alguna. Lo que agrega este documento es 2 y 3:
 * Combina las tareas periódicas registradas en WorkManager con [WorkInfo] El estado, el tiempo de ejecución siguiente y los motivos de apagado del sistema se enumeran juntos.,
 * y ofrece una instantánea reproducible de solo texto(Los usuarios pueden simplemente pegarlo al desarrollador.,No es necesario adb).
 *
 * Cosas que no hacer:Sin registro, sin cancelación, sin modificación de ninguna configuración de programación,Solo lectura.
 */
enum class WakeWorkKind {
    SCHEDULED_TASK,
    PROACTIVE,
    DAILY_SUMMARY,
    CLOUD_BACKUP,
    AUTO_BACKUP,
    STATS_CACHE,
}

/** El estado de la tarea en la cola del sistema.`NOT_REGISTERED` No [WorkInfo.State] valor,Es \"Esta tarea no se puede encontrar en el sistema\". */
enum class WakeState {
    ENQUEUED,
    RUNNING,
    BLOCKED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    NOT_REGISTERED,
    UNKNOWN,
    ;

    /** estado final:no será empujado hacia adelante de nuevo(Se cancela la tarea periódica/Después del fracaso, deberá volver a registrarse antes de poder regresar. ENQUEUED). */
    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

/** [WorkInfo.getStopReason] proyección legible de:¿Por qué el sistema detuvo esta tarea? */
enum class WakeStopReason {
    NOT_STOPPED,
    UNKNOWN,
    CANCELLED_BY_APP,
    PREEMPT,
    TIMEOUT,
    DEVICE_STATE,
    CONSTRAINT_BATTERY,
    CONSTRAINT_CHARGING,
    CONSTRAINT_NETWORK,
    DEVICE_IDLE,
    CONSTRAINT_STORAGE,
    QUOTA,
    BACKGROUND_RESTRICTION,
    APP_STANDBY,
    USER,
    SYSTEM_PROCESSING,
    ESTIMATED_APP_LAUNCH_TIME_CHANGED,
    ;

    /** Ya sea \"por el sistema\"/Razones como \"restricciones ambientales\"(Diferente a la iniciativa del usuario de cancelar). */
    val isExternalLimit: Boolean
        get() = this != NOT_STOPPED && this != CANCELLED_BY_APP && this != USER
}

/**
 * [WorkInfo] proyección legible por humanos.
 *
 * Se extrae por separado para que el "mapeo de enumeración de estado" y el "ensamblaje de elementos" se puedan ejecutar en pruebas unitarias de JVM puras.,
 * No se requiere inicialización de Robolectric o WorkManager.
 */
data class WakeWorkSnapshot(
    val uuid: String,
    val stateName: String,
    val nextRunAtMillis: Long?,
    val runAttemptCount: Int,
    val stopReason: WakeStopReason,
    val constrained: Boolean,
)

/** Una tarea periódica registrada(La misma obra única toma el estatus actual.,El número de registros históricos es adicional.). */
data class WakeWorkEntry(
    val kind: WakeWorkKind,
    val uniqueName: String,
    val state: WakeState,
    val nextRunAtMillis: Long?,
    val runAttemptCount: Int,
    val stopReason: WakeStopReason,
    val constrained: Boolean,
    val historyCount: Int,
)

/** Resultados completos de una colección de diagnóstico,También es una fuente de datos para instantáneas replicables. */
data class WakeDiagnostics(
    val entries: List<WakeWorkEntry>,
    val backgroundWorkEnabled: Boolean?,
    val batteryOptimizationExempt: Boolean,
    val exportedAtMillis: Long,
    val appVersion: String,
    val androidVersion: String,
    val device: String,
    val error: String? = null,
)

object BackgroundWakeDiagnostics {
    private const val TAG = "BackgroundWakeDiagnostics"

    /** El nombre de trabajo único de cada tipo de tarea en WorkManager. El punto de registro está en MuseApp.onCreate,El cambio del punto de registro debe sincronizarse aquí. */
    internal fun candidateWorkNames(kind: WakeWorkKind): List<String> = when (kind) {
        WakeWorkKind.SCHEDULED_TASK -> listOf(ScheduledTaskWorker.UNIQUE_WORK_NAME)
        WakeWorkKind.PROACTIVE -> listOf(ProactiveWorker.UNIQUE_WORK_NAME)
        // El resumen diario se registra uno por uno según el período de tiempo configurado unique work,El usuario puede cambiar el período de tiempo a cualquier hora.,Entonces el candidato tiene 24 horas.
        WakeWorkKind.DAILY_SUMMARY -> (0..23).map { hour -> DailySummaryWorker.uniqueWorkName(hour, 0) }
        WakeWorkKind.CLOUD_BACKUP -> listOf(CloudBackupWorker.UNIQUE_WORK_NAME)
        WakeWorkKind.AUTO_BACKUP -> listOf(AutoBackupWorker.UNIQUE_WORK_NAME)
        WakeWorkKind.STATS_CACHE -> listOf(StatsCacheWorker.UNIQUE_WORK_NAME)
    }

    /** [WorkInfo.State] nombre → [WakeState]. Nombre desconocido regresado a [WakeState.UNKNOWN] En lugar de colapsar. */
    internal fun mapWakeState(stateName: String?): WakeState = when (stateName?.uppercase(Locale.ROOT)) {
        null -> WakeState.NOT_REGISTERED
        "ENQUEUED" -> WakeState.ENQUEUED
        "RUNNING" -> WakeState.RUNNING
        "BLOCKED" -> WakeState.BLOCKED
        "SUCCEEDED" -> WakeState.SUCCEEDED
        "FAILED" -> WakeState.FAILED
        "CANCELLED" -> WakeState.CANCELLED
        else -> WakeState.UNKNOWN
    }

    /**
     * La misma obra única también puede conservar una cadena histórica.:El viejo se ha ido SUCCEEDED,El nuevo todavía está en ENQUEUED.
     * Lo que les importa a los usuarios es "¿Este artículo se activará ahora?",Por lo tanto, se prefieren los estados no finales.,Solo toma el último cuando todos hayan terminado.
     */
    internal fun pickCurrent(snapshots: List<WakeWorkSnapshot>): WakeWorkSnapshot? {
        if (snapshots.isEmpty()) return null
        val live = snapshots.lastOrNull { snapshot -> !mapWakeState(snapshot.stateName).isTerminal }
        return live ?: snapshots.last()
    }

    /**
     * Armar un elemento de tipo tarea.
     *
     * @param candidates unique work nombre → bajo el nombre de este sistema [WorkInfo] proyección;Un valor nulo indica que la consulta falló.
     *  (WorkManager No inicializado/la consulta arroja una excepción),y "Encontrado pero vacío(No registrado)“diferenciar.
     */
    internal fun toEntries(
        kind: WakeWorkKind,
        candidates: Map<String, List<WakeWorkSnapshot>?>,
    ): List<WakeWorkEntry> {
        val present = candidates.filterValues { snapshots -> !snapshots.isNullOrEmpty() }
        if (present.isEmpty()) {
            val firstName = candidates.keys.firstOrNull() ?: return emptyList()
            val queryFailed = candidates.values.any { snapshots -> snapshots == null }
            return listOf(
                WakeWorkEntry(
                    kind = kind,
                    uniqueName = firstName,
                    state = if (queryFailed) WakeState.UNKNOWN else WakeState.NOT_REGISTERED,
                    nextRunAtMillis = null,
                    runAttemptCount = 0,
                    stopReason = WakeStopReason.NOT_STOPPED,
                    constrained = false,
                    historyCount = 0,
                ),
            )
        }
        return present.map { (uniqueName, snapshots) ->
            val list = snapshots.orEmpty()
            val current = pickCurrent(list)
            WakeWorkEntry(
                kind = kind,
                uniqueName = uniqueName,
                state = mapWakeState(current?.stateName),
                nextRunAtMillis = current?.nextRunAtMillis,
                runAttemptCount = current?.runAttemptCount ?: 0,
                stopReason = current?.stopReason ?: WakeStopReason.NOT_STOPPED,
                constrained = current?.constrained == true,
                historyCount = list.size,
            )
        }
    }

    /** soltero [WorkInfo] → proyección.[WorkInfo.getNextScheduleTimeMillis] Devuelve Long.MAX_VALUE para tareas no ENQUEUED. */
    internal fun toSnapshot(info: WorkInfo): WakeWorkSnapshot = WakeWorkSnapshot(
        uuid = info.id.toString(),
        stateName = info.state.name,
        nextRunAtMillis = info.nextScheduleTimeMillis.takeIf { at -> at in 1 until Long.MAX_VALUE },
        runAttemptCount = info.runAttemptCount,
        stopReason = mapStopReason(info.stopReason),
        constrained = hasConstraints(info),
    )

    internal fun mapStopReason(code: Int): WakeStopReason = when (code) {
        WorkInfo.STOP_REASON_NOT_STOPPED -> WakeStopReason.NOT_STOPPED
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> WakeStopReason.CANCELLED_BY_APP
        WorkInfo.STOP_REASON_PREEMPT -> WakeStopReason.PREEMPT
        WorkInfo.STOP_REASON_TIMEOUT -> WakeStopReason.TIMEOUT
        WorkInfo.STOP_REASON_DEVICE_STATE -> WakeStopReason.DEVICE_STATE
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> WakeStopReason.CONSTRAINT_BATTERY
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> WakeStopReason.CONSTRAINT_CHARGING
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> WakeStopReason.CONSTRAINT_NETWORK
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> WakeStopReason.DEVICE_IDLE
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> WakeStopReason.CONSTRAINT_STORAGE
        WorkInfo.STOP_REASON_QUOTA -> WakeStopReason.QUOTA
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> WakeStopReason.BACKGROUND_RESTRICTION
        WorkInfo.STOP_REASON_APP_STANDBY -> WakeStopReason.APP_STANDBY
        WorkInfo.STOP_REASON_USER -> WakeStopReason.USER
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> WakeStopReason.SYSTEM_PROCESSING
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> WakeStopReason.ESTIMATED_APP_LAUNCH_TIME_CHANGED
        else -> WakeStopReason.UNKNOWN
    }

    internal fun hasConstraints(info: WorkInfo): Boolean {
        val constraints = info.constraints
        return constraints.requiresBatteryNotLow() ||
            constraints.requiresCharging() ||
            constraints.requiresDeviceIdle() ||
            constraints.requiresStorageNotLow() ||
            constraints.requiredNetworkType != androidx.work.NetworkType.NOT_REQUIRED
    }

    /**
     * Recoge el estado de todas las tareas periódicas registradas.
     *
     * solo lectura:Llamado una vez para cada trabajo único. `getWorkInfosForUniqueWork`,Sin escritura, sin cancelación, sin reprogramación.
     * todo el proceso off-main(WorkManager El futuro utiliza el bloqueo para obtener el resultado.,La persona que llama está en el hilo IO.).
     *
     * @param backgroundWorkEnabled El valor actual de "Control maestro de tareas en segundo plano" en la configuración;null Indica que no se puede leer.
     *  (Error de lectura). El despertar todavía ocurre cuando el control maestro está apagado,Pero cuando cada cuerpo de ejecución lea falso, regresará directamente. —— esto es
     *  Razones comunes para "despertar pero no ejecutar",Por eso hay que mostrarlos juntos.
     */
    suspend fun collect(context: Context, backgroundWorkEnabled: Boolean?): WakeDiagnostics =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            // El panel no puede detenerse permanentemente en el estado de carga debido a una excepción inesperada.:Si la recopilación falla, también se devolverá un informe con error.,
            // Haga que la interfaz sea explícita sobre los motivos.
            val collected = resultOf { collectOnce(appContext, backgroundWorkEnabled) }
                .onError { message, throwable ->
                    Logger.w(TAG, "No se pudo recopilar el estado de activación en segundo plano: $message", throwable)
                }
            collected.getOrNull() ?: buildDiagnostics(
                appContext = appContext,
                entries = emptyList(),
                backgroundWorkEnabled = backgroundWorkEnabled,
                error = (collected as? io.zer0.common.Result.Error)?.message,
            )
        }

    /**
     * Lea "Control maestro de tareas en segundo plano" de Koin y luego recójalo.
     *
     * La configuración del almacén no está disponible(o lectura fallida)marcar el control maestro como desconocido,En lugar de dejar que toda la colección falle ——
     * "El control maestro no puede leer" en sí mismo es un mensaje de diagnóstico que debe verse.
     */
    suspend fun collectWithSettings(context: Context): WakeDiagnostics {
        val backgroundWorkEnabled = resultOf {
            GlobalContext.get().get<SettingsRepository>().scheduleWorkEnabledFlow.first()
        }.onError { message, throwable ->
            Logger.w(TAG, "No se pudo leer el control maestro de tareas en segundo plano: $message", throwable)
        }.getOrNull()
        return collect(context, backgroundWorkEnabled)
    }

    /**
     * Revise todas las tareas en los libros y elabore un informe.
     *
     * La concurrencia sólo ahorra la espera de ida y vuelta de "nuestro lado":Las consultas para cada obra única se inician al mismo tiempo.,pero WorkManager
     * Internamente, serialTaskExecutor se utiliza para ejecutar estas consultas en serie.,Entonces el nivel de la base de datos no es realmente paralelo.,
     * El consumo de tiempo total está determinado principalmente por el "tiempo de serie total de cada consulta".,No el más largo.
     * En el futuro, cuando creo que aquí puede ser más rápido.,Primero mida el tiempo real dedicado al equipo.,No crea que la concurrencia se está ejecutando solo porque dice asíncrono.
     */
    private suspend fun collectOnce(appContext: Context, backgroundWorkEnabled: Boolean?): WakeDiagnostics {
        var error: String? = null
        val workManager = try {
            WorkManager.getInstance(appContext)
        } catch (e: IllegalStateException) {
            error = e.message
            Logger.w(TAG, "WorkManager no inicializado,No se puede leer el estado de activación", e)
            null
        }
        val entries = coroutineScope {
            WakeWorkKind.values().flatMap { kind ->
                val names = candidateWorkNames(kind)
                val snapshots = names
                    .map { name -> async { if (workManager == null) null else queryUniqueWork(workManager, name) } }
                    .awaitAll()
                toEntries(kind, names.zip(snapshots).toMap())
            }
        }
        return buildDiagnostics(appContext, entries, backgroundWorkEnabled, error)
    }

    private fun buildDiagnostics(
        appContext: Context,
        entries: List<WakeWorkEntry>,
        backgroundWorkEnabled: Boolean?,
        error: String?,
    ): WakeDiagnostics = WakeDiagnostics(
        entries = entries,
        backgroundWorkEnabled = backgroundWorkEnabled,
        batteryOptimizationExempt = isIgnoringBatteryOptimizations(appContext),
        exportedAtMillis = System.currentTimeMillis(),
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        error = error,
    )

    /** Consultar un solo unique work;Retorno del fracaso null(Distinguir de \"Encontrado, pero vacío\"),No arrojado a la persona que llama. */
    private fun queryUniqueWork(workManager: WorkManager, uniqueName: String): List<WakeWorkSnapshot>? = try {
        workManager.getWorkInfosForUniqueWork(uniqueName).get().orEmpty().map { info -> toSnapshot(info) }
    } catch (e: InterruptedException) {
        // Mantener indicadores de interrupción:La persona que llama está en la rutina IO.,La interrupción no debe ser considerada como un fracaso ordinario.
        Thread.currentThread().interrupt()
        Logger.w(TAG, "Se interrumpió la lectura del estado de activación($uniqueName)", e)
        null
    } catch (e: ExecutionException) {
        // WorkManager Error en la consulta interna(base de datos/Excepción de inicialización)Regrese aquí como ExecutionException
        Logger.w(TAG, "No se pudo leer el estado de activación($uniqueName)", e)
        null
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        // No se hace distinción cuando falla la lectura.:Este campo es sólo información auxiliar.,Si no puede obtenerlo, trátelo como si no estuviera exento.
        return resultOf { power.isIgnoringBatteryOptimizations(context.packageName) }.getOrNull() ?: false
    }

    /** Marca de tiempo → texto de hora local(Las instantáneas son para que la gente las lea.,Formato fijo para una fácil alineación). */
    internal fun formatTime(millis: Long?): String {
        if (millis == null) return NONE_TEXT
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    }

    /** Cómo se escribe \"no existe tal valor\" en la instantánea. Los valores faltantes nunca deben imprimirse como 0 o Long.MAX_VALUE: ambos se tratan como marcas de tiempo verdaderas. */
    internal const val NONE_TEXT = "none"

    /**
     * Instantánea copiable de solo texto.
     *
     * usar `key=value` Formato de longitud de línea en lugar de copia localizada:Este texto debe pegarse a los desarrolladores.,
     * Nombre del estado, nombre del motivo de la parada y [WakeState]/[WakeStopReason] Enum con el mismo nombre,No habrá ambigüedad entre idiomas.
     */
    internal fun formatSnapshot(diagnostics: WakeDiagnostics): String {
        val builder = StringBuilder()
        builder.appendLine("# Muse Instantánea de activación en segundo plano")
        builder.appendLine("exported_at=${formatTime(diagnostics.exportedAtMillis)}")
        builder.appendLine("app=${diagnostics.appVersion}")
        builder.appendLine("android=${diagnostics.androidVersion}")
        builder.appendLine("device=${diagnostics.device}")
        builder.appendLine("background_work_enabled=${diagnostics.backgroundWorkEnabled ?: "unknown"}")
        builder.appendLine("battery_optimization_exempt=${diagnostics.batteryOptimizationExempt}")
        diagnostics.error?.let { message ->
            builder.appendLine("work_manager_error=$message")
        }
        builder.appendLine("work_count=${diagnostics.entries.size}")
        diagnostics.entries.forEach { entry ->
            // Escribir cuando no haya tiempo de próxima ejecución next_run=none:sin hacer cola/Las tareas finalizadas no tienen este valor.,
            // No se puede recargar con Sentinel o 0,De lo contrario, la gente que lo lea pensará que es una marca de tiempo real.
            builder.appendLine(
                "${entry.uniqueName} | kind=${entry.kind.name} | state=${entry.state.name} | " +
                    "next_run=${formatTime(entry.nextRunAtMillis)} | attempts=${entry.runAttemptCount} | " +
                    "constrained=${entry.constrained} | stop_reason=${entry.stopReason.name} | " +
                    "history=${entry.historyCount}",
            )
        }
        return builder.toString()
    }
}
