package io.zer0.muse.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * v1.0.84: resumen diario Worker — Todos los días a las 09:0012:00,21:00,00:00 Generar resumen.
 *
 * ¿Por qué?:Los usuarios quieren mensajes activos"resumen de hoy"Pujar al menos una vez al día a una hora fija(en lugar de activación probabilística),
 * Deja que el asistente se sienta como un verdadero compañero,Por la noche, revisa lo que hablaste durante el día.
 *
 * darse cuenta:
 *  - OneTimeWorkRequest Activar en el momento objetivo,Tras la ejecución, el plazo se ampliará hasta la misma hora del día siguiente.
 *    (WorkManager El período mínimo de PeriodicWork es de 15 minutos.,No se puede ser preciso en los puntos fijos a nivel horario,
 *    Logre puntos fijos diarios precisos con OneTime + período de autorrenovación)
 *  - Materiales:Día del resumen de objetivos(00:00 fue el dia anterior)Mensajes del usuario + eventos recientes de la memoria principal(fact)
 *  - generar:LLM 2-4 Un resumen natural en una frase.(estilo:Coloquial, no oficial, como un amigo repasando la reseña contigo por la noche.)
 *  - Genera saludos naturales cuando no hay material, asegurando resultados en cuatro puntos de tiempo fijos.
 *  - Devolver el éxito en caso de error sin volver a intentarlo(La autorrenovación se activará nuevamente mañana),Evite los reintentos de WorkManager 30
 *
 * Nota:Worker Si el proceso de la aplicación finaliza cuando se activa,WorkManager Iniciar un nuevo proceso y ejecutarlo.,
 * Koin Inicializado por MuseApp.onCreate(Igual que ProactiveMessageWorker).
 */
class DailySummaryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val koin = resultOf { GlobalContext.get() }.getOrNull()
        if (koin == null) {
            Logger.w(TAG, "Koin no inicializado,Saltar esta ejecución")
            scheduleNext(applicationContext)
            return Result.success()
        }
        val settings = resultOf { koin.get<SettingsRepository>() }.getOrNull()
        if (settings == null) {
            Logger.w(TAG, "SettingsRepository El análisis falló,saltar")
            scheduleNext(applicationContext)
            return Result.success()
        }
        // B-25: Control maestro de programación backend — Omitir el cuerpo ejecutable al apagar,El calendario periódico en sí sigue siendo,Reabrir para restaurar
        val workEnabled =
            scheduleWorkEnabledOrFalse(
                resultOf { settings.scheduleWorkEnabledFlow.first() }.getOrNull(),
            )
        if (!workEnabled) {
            Logger.i(TAG, "Se ha cerrado el control maestro de programación en segundo plano.,Saltar cuerpo de ejecución resumida diaria")
            scheduleNext(applicationContext)
            return Result.success()
        }
        // El interruptor solo controla las notificaciones.,No impide la generación de resúmenes y la visualización de la página de inicio.
        // Dependencia del saludo de la página de inicio dailySummaryFlow;Si te saltas aquí en su totalidad,Los usuarios nunca verán el resumen incluso si no quieren recibir notificaciones.
        val enabled =
            scheduleWorkEnabledOrFalse(
                resultOf { settings.dailySummaryEnabledFlow.first() }.getOrNull(),
            )
        val slotHour = inputData.getInt(KEY_SLOT_HOUR, DEFAULT_HOUR)
        val slotMinute = inputData.getInt(KEY_SLOT_MINUTE, DEFAULT_MINUTE)
        val targetDate = inputData.getString(KEY_TARGET_DATE)
            ?: java.time.LocalDate.now().toString()
        val slotKey = inputData.getString(KEY_SLOT_KEY)
            ?: slotKey(targetDate, slotHour, slotMinute)

        // Periodo de tiempo configurable:Worker Determine si el punto de tiempo actual se activa de acuerdo con la configuración.
        // programación periódica(WorkManager + 4 puntos de tiempo físicos en el proceso)siempre reservado,Aquí solo se realiza la activación del cuerpo de ejecución,
        // Los usuarios no necesitan volver a registrarse después de ajustar el período de tiempo.,Naturalmente, los puntos de tiempo no configurados se omiten.
        // La generación de resúmenes diarios y la visualización de la página de inicio se conservarán cuando se cierre.,Solo se aplica estrictamente según el período configurado.
        val configuredSlotsResult = resultOf { settings.dailySummarySlotsFlow.first() }
            .onError { msg, t ->
                Logger.w(TAG, "No se pudo leer el período de resumen diario,Saltar esta ejecución: ${t?.message ?: msg}")
            }
        if (configuredSlotsResult.isError) {
            // DataStore Un error de lectura transitorio no debería descarrilar permanentemente la única tarea actual;
            // Mantenga la siguiente tarea en el momento actual y espere la siguiente ronda para leer la configuración.
            scheduleNextSlot(applicationContext, slotHour, slotMinute)
            return Result.success()
        }
        val configuredSlots = slotsAfterRead(
            configured = configuredSlotsResult.getOrNull(),
            readFailed = false,
        )
        if (!shouldContinueForConfiguredSlot(slotHour, configuredSlots)) {
            Logger.i(TAG, "punto de tiempo ${slotHour.toString().padStart(2, '0')}:$slotMinute Fuera del periodo de tiempo configurado,Saltar generación: $slotKey")
            // No reprograme la hora anterior para el día siguiente cuando se haya eliminado la configuración;scheduleNext Empezará la próxima vez/
            // Limpie la tarea única correspondiente al guardar la configuración.
            return Result.success()
        }

        // Worker Comparte el mismo servicio con la compensación frontal de la página de inicio; el servicio es internamente responsable de la preferencia, generación y liberación de fallas de DataStore.
        val summaryService = resultOf { koin.get<DailySummaryService>() }
            .onError { msg, t -> Logger.w(TAG, "DailySummaryService El análisis falló: ${t?.message ?: msg}") }
            .getOrNull()
        if (summaryService == null) {
            Logger.w(TAG, "DailySummaryService No listo,Saltar esta ejecución: $slotKey")
        } else {
            resultOf {
                summaryService.generateForSlot(
                    slotKey = slotKey,
                    summaryDate = summaryDateForTarget(targetDate, slotHour),
                    notificationsEnabled = enabled,
                )
            }.onError { msg, t -> Logger.w(TAG, "Error al generar el resumen diario: ${t?.message ?: msg}") }
        }

        // Independientemente del éxito o el fracaso, la próxima vez se renovará hasta la misma hora.
        scheduleNextSlot(applicationContext, slotHour, slotMinute)
        return Result.success()
    }

    companion object {
        private const val TAG = "DailySummaryWorker"
        const val UNIQUE_WORK_NAME = "muse_daily_summary_worker"

        /** Notificación resumida diaria id(Evita saludos 1010 / mensajes no solicitados y otros id). */
        const val NOTIF_ID_DAILY_SUMMARY = 1012

        /** tiempo de resumen(24 reloj de hora). */
        val SUMMARY_SLOTS: List<Pair<Int, Int>> = listOf(
            9 to 0,
            12 to 0,
            21 to 0,
            0 to 0,
        )
        const val DEFAULT_HOUR = 9
        const val DEFAULT_MINUTE = 0
        const val KEY_SLOT_HOUR = "slot_hour"
        const val KEY_SLOT_MINUTE = "slot_minute"
        const val KEY_TARGET_DATE = "target_date"
        const val KEY_SLOT_KEY = "slot_key"

        private const val SCHEDULE_PREFS_NAME = "daily_summary_schedule"
        private const val KEY_SCHEDULED_SLOTS = "scheduled_slots"

        /** Calcule el retraso desde el momento especificado; cuando llegue el momento, se pospondrá para el día siguiente para evitar una ejecución repetida. */
        fun computeDelayToNextTarget(nowMillis: Long, targetHour: Int = DEFAULT_HOUR, targetMinute: Int = DEFAULT_MINUTE): Long {
            require(targetHour in 0..23) { "targetHour out of range" }
            require(targetMinute in 0..59) { "targetMinute out of range" }
            val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
            val target = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(java.util.Calendar.HOUR_OF_DAY, targetHour)
                set(java.util.Calendar.MINUTE, targetMinute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (!target.after(now)) target.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return target.timeInMillis - now.timeInMillis
        }

        /** La fecha local correspondiente al siguiente punto de tiempo objetivo. */
        fun nextTargetDate(nowMillis: Long, targetHour: Int, targetMinute: Int): java.time.LocalDate {
            val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
            val target = java.util.Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(java.util.Calendar.HOUR_OF_DAY, targetHour)
                set(java.util.Calendar.MINUTE, targetMinute)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            if (!target.after(now)) target.add(java.util.Calendar.DAY_OF_YEAR, 1)
            return target.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        }

        fun summaryDateForTarget(targetDate: String, targetHour: Int): java.time.LocalDate {
            val date = java.time.LocalDate.parse(targetDate)
            return if (targetHour == 0) date.minusDays(1) else date
        }

        fun slotKey(targetDate: String, targetHour: Int, targetMinute: Int): String =
            "$targetDate#${targetHour.toString().padStart(2, '0')}${targetMinute.toString().padStart(2, '0')}"

        /** Sólo se permite el uso de números enteros legales mediante la programación de persistencia y pruebas puramente lógicas. */
        internal fun normalizeConfiguredSlots(slots: Collection<Int>): Set<Int> = slots.filter { it in 0..23 }.toSortedSet()

        /** Tareas antiguas por horas que deben eliminarse de WorkManager después de que cambie la configuración actual. */
        internal fun staleScheduledSlots(previouslyScheduled: Collection<Int>, configured: Collection<Int>): Set<Int> =
            normalizeConfiguredSlots(previouslyScheduled) - normalizeConfiguredSlots(configured)

        /** Worker Puerta de configuración antes del período de autorrenovación; La programación en cadena no puede continuar durante las horas eliminadas por el usuario. */
        internal fun shouldContinueForConfiguredSlot(targetHour: Int, configured: Collection<Int>): Boolean =
            targetHour in normalizeConfiguredSlots(configured)

        /**
         * B-10: Registra la siguiente tarea con recursos —— Atravesar los períodos de configuración del usuario.(dailySummarySlotsFlow),
         * No más escribir hasta la muerte [SUMMARY_SLOTS]Regístrese uno por uno,Asegúrese de que el período de tiempo personalizado pueda activarse mediante el registro real
         * (Anteriormente, solo se usaba ConfigureSlots para la adaptación del cuerpo de ejecución.,El cronograma físico todavía está fijo en 4 puntos, lo que hace que el período personalizado nunca se active.).
         */
        suspend fun scheduleNext(context: Context) {
            val configuredSlots = resolvedConfiguredSlots()
            if (configuredSlots.isEmpty()) {
                // DataStore Cuando la lectura falle, no elimine el cronograma existente por error; empiezalo la próxima vez/Cambie la configuración y vuelva a intentarlo.
                return
            }
            val workManager = WorkManager.getInstance(context)
            resultOf { workManager.cancelUniqueWork(UNIQUE_WORK_NAME) }
                .onError { msg, t -> Logger.w(TAG, "La tarea de limpiar el antiguo resumen diario falló.: ${t?.message ?: msg}") }
            // v1.xxx: La versión anterior dividía el trabajo único por horas; cuando la configuración se reduce o reorganiza, se debe limpiar una por una
            // Horas que ya no son necesarias, de lo contrario seguirían autorenovándose en el Trabajador.
            val previouslyScheduled = readPersistedScheduledSlots(context)
                ?: (0..23).toSet()
            staleScheduledSlots(previouslyScheduled, configuredSlots).forEach { hour ->
                resultOf { workManager.cancelUniqueWork(uniqueWorkName(hour, DEFAULT_MINUTE)) }
                    .onError { msg, t ->
                        Logger.w(
                            TAG,
                            "Error en la tarea de limpieza de resumen diario anterior(${hour.toString().padStart(2, '0')}:00): ${t?.message ?: msg}",
                        )
                    }
            }
            // Cada registro se calcula en función del último tiempo de configuración.:variable de periodo de tiempo,Regístrese la próxima vez cuando recorra todas las configuraciones
            configuredSlots.forEach { hour ->
                // App Puede ocurrir un inicio en frío cuando el proceso donde se encuentra el Trabajador acaba de iniciarse y no puede REEMPLAZAR sus propias tareas que se están ejecutando.
                scheduleNextSlot(context, hour, DEFAULT_MINUTE, ExistingWorkPolicy.KEEP)
            }
            persistScheduledSlots(context, configuredSlots)
        }

        /**
         * El llenado a tiempo se activa cuando el proceso está activo.
         * WorkManager Responsable de divulgar los detalles luego de ser asesinado; aquí esperar hasta el tiempo de configuración en el proceso e inmediatamente entregar el Trabajador correspondiente.
         * De esta manera, los procesos en primer plano o aún activos no dependen de la precisión de la programación del ciclo del WorkManager.
         */
        fun startInProcess(context: Context, scope: CoroutineScope): Job = scope.launch {
            val settings = resultOf {
                GlobalContext.get().get<SettingsRepository>()
            }.getOrNull() ?: run {
                Logger.w(TAG, "SettingsRepository No listo,Pausar la programación resumida diaria en proceso")
                return@launch
            }
            // Un único CollectLatest es responsable del ciclo de vida:DataStore El cambio de período de tiempo cancelará el antiguo retraso prolongado.
            // No se inician múltiples bucles a largo plazo cada vez que se guarda la configuración.
            val configuredSlotsFlow: Flow<List<Int>?> = settings.dailySummarySlotsFlow.map { slots -> slots }
            configuredSlotsFlow
                .catch { error ->
                    Logger.w(TAG, "Período de resumen diario Lectura de flujo fallida,Pausar la programación en proceso: ${error.message}", error)
                    emit(null)
                }
                .collectLatest { rawConfigured ->
                    if (rawConfigured == null) return@collectLatest
                    val configured = slotsAfterRead(rawConfigured, readFailed = false)
                    while (isActive) {
                        val now = System.currentTimeMillis()
                        val next = configured
                            .map { hour -> hour to DEFAULT_MINUTE }
                            .minByOrNull { (hour, minute) -> computeDelayToNextTarget(now, hour, minute) }
                            ?: return@collectLatest
                        val delayMillis = computeDelayToNextTarget(now, next.first, next.second)
                        delay(delayMillis.coerceAtLeast(1_000L))
                        enqueueDueSlot(context, next.first, next.second)
                    }
                }
        }

        /**
         * B-10: Leer el período de resumen diario configurado actualmente(hora en punto).
         * Lista vacía explícita de respaldo a los 4 puntos de tiempo predeterminados;Si la lectura falla, se devolverá un conjunto vacío y se suspenderá la programación automática.,
         * Evite los efectos secundarios de fondo que continúan ocurriendo cuando se desconoce la intención del usuario.
         */
        private suspend fun resolvedConfiguredSlots(): List<Int> {
            val configuredResult = resultOf {
                val koin = org.koin.core.context.GlobalContext.get()
                koin.get<io.zer0.muse.data.SettingsRepository>().dailySummarySlotsFlow.first()
            }.onError { msg, t ->
                Logger.w(TAG, "No se pudo leer el período de resumen diario,Pausar la programación automática: ${t?.message ?: msg}")
            }
            val rawConfigured = configuredResult.getOrNull()
            val configured = slotsAfterRead(
                configured = rawConfigured,
                readFailed = configuredResult.isError,
            )
            if (configuredResult.isError) {
                return emptyList()
            }
            if (rawConfigured.isNullOrEmpty()) {
                Logger.w(TAG, "El período de resumen diario está vacío o no se pudo leer,Volver al valor predeterminado ${DEFAULT_SUMMARY_SLOTS.size} punto de tiempo")
            }
            return configured
        }

        /** B-10: Período de resumen diario predeterminado(hora en punto),Una guía para la configuración vacía. */
        private val DEFAULT_SUMMARY_SLOTS: List<Int> = listOf(0, 9, 12, 21)

        internal fun slotsAfterRead(configured: List<Int>?, readFailed: Boolean): List<Int> = when {
            readFailed -> emptyList()
            configured.isNullOrEmpty() -> DEFAULT_SUMMARY_SLOTS
            else -> configured
        }

        private fun enqueueDueSlot(context: Context, targetHour: Int, targetMinute: Int) {
            val targetDate = java.time.LocalDate.now().toString()
            val key = slotKey(targetDate, targetHour, targetMinute)
            val request = OneTimeWorkRequestBuilder<DailySummaryWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SLOT_HOUR to targetHour,
                        KEY_SLOT_MINUTE to targetMinute,
                        KEY_TARGET_DATE to targetDate,
                        KEY_SLOT_KEY to key,
                    ),
                )
                .build()
            resultOf {
                // Cuando se alcanza el punto, la solicitud retrasada original se reemplaza y el punto de tiempo actual se ejecuta inmediatamente.
                // Utilice nombres de tareas de destino independientes y no cancele los trabajadores retrasados ​​que puedan haber comenzado a ejecutarse al mismo tiempo;
                // Finalmente, ClaimDailySummarySlot garantiza que solo se generará una vez.
                WorkManager.getInstance(context).enqueueUniqueWork(
                    dueWorkName(targetHour, targetMinute, targetDate),
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }.onError { msg, t -> Logger.w(TAG, "Error en el activador del resumen diario en proceso($key): ${t?.message ?: msg}") }
        }

        /**
         * Se activa cuando la página de inicio se abre en primer plano.
         *
         * WorkManager Puede retrasarse debido a la política de ahorro de energía en algunas ROM;Si la fecha actual ha pasado un
         * tiempo de resumen,Envíe las tareas que vencen a más tardar.Worker El interior todavía está compuesto por claimDailySummarySlot
         * Hacer preferencia idempotente,Por lo tanto, no se llamará a LLM repetidamente debido a la reorganización de la página de inicio o a múltiples regresos al primer plano.
         */
        suspend fun enqueueCatchUpIfDue(context: Context, settings: SettingsRepository, nowMillis: Long = System.currentTimeMillis()) {
            val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
            val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                now.get(java.util.Calendar.MINUTE)
            // B-10: El activador de compensación de primer plano también se calcula según el período configurado por el usuario.(Si está vacío, vuelve al valor predeterminado),Consistente con la programación física
            val dueSlots =
                try {
                    settings.dailySummarySlotsFlow.first()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Logger.w(TAG, "El disparador suplementario de front-end no pudo leer el período de resumen diario.,saltate esta vez: ${error.message}")
                    return
                }
            val due = (dueSlots.ifEmpty { DEFAULT_SUMMARY_SLOTS })
                .filter { hour -> hour * 60 <= currentMinutes }
                .maxByOrNull { hour -> hour * 60 }
                ?: return
            val targetDate = java.time.Instant.ofEpochMilli(nowMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .toString()
            val key = slotKey(targetDate, due, DEFAULT_MINUTE)
            if (!settings.isDailySummarySlotCompleted(key)) {
                Logger.i(TAG, "El suplemento de recepción activa el resumen diario: $key")
                enqueueDueSlot(context, due, DEFAULT_MINUTE)
            } else {
                Logger.d(TAG, "El disparador de compensación de primer plano omite el punto de tiempo completado: $key")
            }
        }

        fun scheduleNextSlot(
            context: Context,
            targetHour: Int,
            targetMinute: Int,
            policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        ) {
            val now = System.currentTimeMillis()
            val targetDate = nextTargetDate(now, targetHour, targetMinute).toString()
            val key = slotKey(targetDate, targetHour, targetMinute)
            val delayMillis = computeDelayToNextTarget(now, targetHour, targetMinute)
            val request = OneTimeWorkRequestBuilder<DailySummaryWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SLOT_HOUR to targetHour,
                        KEY_SLOT_MINUTE to targetMinute,
                        KEY_TARGET_DATE to targetDate,
                        KEY_SLOT_KEY to key,
                    ),
                )
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            resultOf {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    uniqueWorkName(targetHour, targetMinute),
                    policy,
                    request,
                )
            }.onError { msg, t -> Logger.w(TAG, "Error en la programación del resumen diario($key): ${t?.message ?: msg}") }
        }

        fun uniqueWorkName(targetHour: Int, targetMinute: Int): String =
            "${UNIQUE_WORK_NAME}_${targetHour.toString().padStart(2, '0')}${targetMinute.toString().padStart(2, '0')}"

        private fun readPersistedScheduledSlots(context: Context): Set<Int>? {
            val raw = context.applicationContext
                .getSharedPreferences(SCHEDULE_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SCHEDULED_SLOTS, null)
                ?: return null
            return normalizeConfiguredSlots(
                raw.split(",").mapNotNull { it.trim().toIntOrNull() },
            )
        }

        private fun persistScheduledSlots(context: Context, slots: Collection<Int>) {
            context.applicationContext
                .getSharedPreferences(SCHEDULE_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SCHEDULED_SLOTS, normalizeConfiguredSlots(slots).joinToString(","))
                .apply()
        }

        private fun dueWorkName(targetHour: Int, targetMinute: Int, targetDate: String): String =
            "${uniqueWorkName(targetHour, targetMinute)}_due_$targetDate"
    }
}
