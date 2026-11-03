package io.zer0.muse.tools.reminder

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * v1.136: Almacenamiento de recordatorios programados locales.
 *
 * Lista de recordatorios persistentes usando un archivo JSON,cooperar [ReminderAlarmReceiver] + AlarmManager Activar notificación a la hora especificada.
 * Los recordatorios se eliminan automáticamente del almacenamiento después de activarse.
 *
 * v2.6.x: Además del "recordatorio configurado por el usuario", la entrada también registra si la alarma está realmente programada, si se utiliza una alarma precisa,
 * El motivo original del último fracaso del vuelo. De esta manera [io.zer0.muse.tools.ReminderToolsRegistrar] de `list_reminders`
 * Sólo entonces se le puede decir sinceramente al usuario "Este despertador no está programado y puede que no suene" en lugar de indicarlo como "no activado" independientemente de si el despertador está presente o no.
 */
class ReminderStore(private val context: Context) {

    private val file by lazy { java.io.File(context.filesDir, "reminders/reminders.json") }

    /** Bloqueo compartido de modificación de lectura: la reorganización del inicio y la reorganización del primer plano pueden ser simultáneas para evitar dañar archivos. */
    private val ioLock = Any()

    @Volatile
    private var lastError: String? = null

    /** Consume el último recordatorio de daños.(UI Llamado después de la visualización). */
    fun consumeError(): String? {
        val e = lastError
        lastError = null
        return e
    }

    /** Lea todos los recordatorios. */
    fun list(): List<ReminderEntry> {
        return readAll()
    }

    /** Obtenga un recordatorio único basado en la identificación. */
    fun get(id: String): ReminderEntry? {
        return readAll().find { it.id == id }
    }

    /**
     * Agregue un recordatorio y hágalo persistente (aún no programado,`alarmScheduledAt == 0`).
     *
     * @return recordatorio id
     */
    fun add(
        title: String,
        message: String,
        triggerAtMillis: Long,
        id: String? = null,
        targetType: String = ReminderAlarmReceiver.TARGET_HOME,
        targetId: String = "",
    ): String {
        val entry = ReminderEntry(
            id = id?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString(),
            title = title,
            message = message,
            triggerAtMillis = triggerAtMillis,
            createdAtMillis = System.currentTimeMillis(),
            targetType = targetType,
            targetId = targetId,
        )
        synchronized(ioLock) {
            val all = readAll().toMutableList().apply { add(entry) }
            writeAll(all)
        }
        return entry.id
    }

    /** Elimina el recordatorio con la identificación especificada. */
    fun remove(id: String): Boolean = synchronized(ioLock) {
        val all = readAll().toMutableList()
        val removed = all.removeIf { it.id == id }
        if (removed) writeAll(all)
        removed
    }

    /** Registre un cronograma exitoso (para `list_reminders` Determinado como \"programado\"). */
    fun markScheduled(id: String, exact: Boolean, atMillis: Long) = synchronized(ioLock) {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) {
            all[idx] = all[idx].copy(
                alarmScheduledAt = atMillis,
                scheduledExact = exact,
                scheduleError = null,
            )
            writeAll(all)
        }
    }

    /** Registrar un error de programación (por `list_reminders` Determine \"puede que no suene\"). */
    fun markScheduleFailed(id: String, error: String) = synchronized(ioLock) {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) {
            all[idx] = all[idx].copy(alarmScheduledAt = 0L, scheduleError = error)
            writeAll(all)
        }
    }

    /**
     * Reiniciar/Todos los recordatorios se reprogramarán después de la recuperación (todos los despertadores en AlarmManager se perderán después de reiniciar el proceso).
     *
     * Reglas:
     *  - Tiempo de activación en el futuro: reprogramación (el mismo PendingIntent, idempotente, no se activará repetidamente).
     *  - Caducado (debería haber sonado pero se perdió):**Una notificación más**Luego se retira del almacenamiento y nunca se repondrá por segunda vez.
     *    Las notificaciones de reemplazo reutilizan el ID de notificación del recordatorio. Incluso si se reprograman simultáneamente, solo se sobrescribirán entre sí en el mismo y no actualizarán la pantalla.
     *
     * El orden es "realizar el pedido primero y luego enviar la notificación", no "primero enviar la notificación y luego realizar el pedido".
     * Si lo escribe al revés, si el proceso muere entre los dos, verá el recordatorio vencido la próxima vez que reprograme.
     * Así que hazlo de nuevo —— El usuario recibe dos idénticos "(suplemento)” Aviso, y esto es lo último que el diseño quiere que suceda.
     * Al cambiar al orden actual, el peor de los casos es simplemente perder una notificación (el proceso finaliza justo después de la colocación pero antes de la notificación).
     * Y "enviar un mensaje menos" es más aceptable que "enviar varios mensajes".
     *
     * @return Las estadísticas de esta reorganización sirven como referencia para los registros y las personas que llaman.
     */
    fun rescheduleAll(now: Long = System.currentTimeMillis()): RescheduleSummary {
        // La transmisión de arranque y el arranque en frío pueden provocar una reprogramación al mismo tiempo; utilice la exclusión mutua durante el proceso para evitar la reemisión repetida del mismo lote de recordatorios vencidos.
        if (!rescheduleGuard.compareAndSet(false, true)) {
            Logger.i(TAG, "La reorganización ya está en progreso, omita esta vez")
            return RescheduleSummary(
                rescheduled = 0,
                catchUpNotified = 0,
                failed = 0,
                exact = ReminderAlarmScheduler.canScheduleExact(context),
            )
        }
        try {
            return rescheduleAllLocked(now)
        } finally {
            rescheduleGuard.set(false)
        }
    }

    private fun rescheduleAllLocked(now: Long): RescheduleSummary {
        val snapshot = synchronized(ioLock) { readAll() }
        if (snapshot.isEmpty()) {
            return RescheduleSummary(
                rescheduled = 0,
                catchUpNotified = 0,
                failed = 0,
                exact = ReminderAlarmScheduler.canScheduleExact(context),
            )
        }
        var rescheduled = 0
        var caughtUp = 0
        var failed = 0
        val remaining = mutableListOf<ReminderEntry>()
        val overdue = mutableListOf<ReminderEntry>()
        for (entry in snapshot) {
            if (entry.triggerAtMillis <= now) {
                // Caducado: solo recolectar, no se enviará ninguna notificación aquí (no se agregará ningún resto).
                overdue.add(entry)
                caughtUp++
            } else {
                val res = ReminderAlarmScheduler.schedule(
                    context = context,
                    id = entry.id,
                    title = entry.title,
                    message = entry.message,
                    triggerAtMillis = entry.triggerAtMillis,
                    targetType = entry.targetType,
                    targetId = entry.targetId,
                )
                if (res.scheduled) {
                    rescheduled++
                    remaining.add(
                        entry.copy(alarmScheduledAt = now, scheduledExact = res.exact, scheduleError = null),
                    )
                } else {
                    failed++
                    remaining.add(entry.copy(alarmScheduledAt = 0L, scheduleError = res.error ?: "alarm_schedule_failed"))
                }
            }
        }
        // Coloque primero: elimine las entradas caducadas del almacenamiento antes de enviar notificaciones.
        synchronized(ioLock) { writeAll(remaining) }
        overdue.forEach { catchUpNotify(it) }
        Logger.i(
            TAG,
            "Reprogramación de recordatorio completada: éxito=$rescheduled, reedición=$caughtUp, falló=$failed",
        )
        return RescheduleSummary(
            rescheduled = rescheduled,
            catchUpNotified = caughtUp,
            failed = failed,
            exact = ReminderAlarmScheduler.canScheduleExact(context),
        )
    }

    /**
     * Para reenviar un recordatorio perdido: agregue "(suplemento)”, hasta agotar existencias.
     *
     * La persona que llama debe eliminar la entrada del almacenamiento antes de llamarla (consulte [rescheduleAllLocked] descripción de la secuencia),
     * De lo contrario, la muerte del proceso provocará que la misma línea se complemente por segunda vez.
     */
    private fun catchUpNotify(entry: ReminderEntry) {
        val title = context.getString(R.string.tool_reminder_catchup_title, entry.title)
        val target = if (
            entry.targetType == ReminderAlarmReceiver.TARGET_QUICK_NOTE && entry.targetId.isNotBlank()
        ) {
            MuseNotificationTarget.QuickNote(entry.targetId)
        } else {
            MuseNotificationTarget.Home
        }
        MuseNotificationManager(context).notifyReminder(
            title = title,
            message = entry.message,
            notificationId = entry.id.hashCode(),
            target = target,
        )
        Logger.i(TAG, "Reemitir recordatorios perdidos: id=${entry.id}, title=${entry.title}")
    }

    /** Leer todos los recordatorios internamente(Devuelve una lista vacía si el archivo no existe). */
    private fun readAll(): List<ReminderEntry> {
        return try {
            if (!file.exists()) return emptyList()
            AppJson.decodeFromString(ListSerializer(ReminderEntry.serializer()), file.readText())
        } catch (e: Exception) {
            backupCorruptFile()
            lastError = "El archivo de la lista de recordatorios está dañado. Se realizó una copia de seguridad y se reconstruyó el archivo original."
            Logger.e(TAG, "No se pudo leer la lista de recordatorios, se realizó una copia de seguridad de los archivos defectuosos: ${e.message}", e)
            emptyList()
        }
    }

    private fun backupCorruptFile() {
        runCatching {
            if (file.exists()) {
                val backup = java.io.File(file.parentFile, "${file.name}.bak-${System.currentTimeMillis()}")
                file.copyTo(backup, overwrite = true)
                Logger.w(TAG, "Se ha realizado una copia de seguridad de los archivos corruptos en ${backup.absolutePath}")
            }
        }.onFailure { Logger.w(TAG, "Error en la copia de seguridad de archivos dañados: ${it.message}") }
    }

    /** Escribe todos los recordatorios internamente.(Primero escriba el archivo temporal y luego cámbiele el nombre para evitar fallar en medio de la escritura y dejar archivos dañados.). */
    private fun writeAll(list: List<ReminderEntry>) {
        try {
            file.parentFile?.mkdirs()
            val payload = AppJson.encodeToString(ListSerializer(ReminderEntry.serializer()), list)
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                // Algunos sistemas de archivos no podrán cambiar el nombre a un destino existente: recurrirán a la sobrescritura.
                file.writeText(payload)
                tmp.delete()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo escribir en la lista de recordatorios: ${e.message}")
        }
    }

    /** Estadísticas de resultados reorganizadas. */
    data class RescheduleSummary(
        val rescheduled: Int,
        val catchUpNotified: Int,
        val failed: Int,
        val exact: Boolean,
    )

    companion object {
        private const val TAG = "ReminderStore"

        /** Exclusión mutua de reordenamiento entre instancias (Receiver y MuseApp tienen cada una una nueva Tienda, que debe compartir esta etiqueta). */
        private val rescheduleGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

@Serializable
data class ReminderEntry(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("message") val message: String,
    @SerialName("trigger_at") val triggerAtMillis: Long,
    @SerialName("created_at") val createdAtMillis: Long,
    /** Notifique el tipo de destino al que saltar después de hacer clic (home / quick_note). */
    @SerialName("target_type") val targetType: String = ReminderAlarmReceiver.TARGET_HOME,
    /** Identificación del objetivo (identificación de nota para una grabación rápida). */
    @SerialName("target_id") val targetId: String = "",
    /** La marca de tiempo de la última programación exitosa;0 Indica que aún no se ha programado (o la programación ha fallado). */
    @SerialName("alarm_scheduled_at") val alarmScheduledAt: Long = 0L,
    /** Si se utilizó el despertador exacto para el último horario;false La representación se ha rebajado a imprecisa y puede sufrir retrasos. */
    @SerialName("scheduled_exact") val scheduledExact: Boolean = true,
    /** El motivo original del último error de programación;null Indica que no hay ningún registro de fallas. */
    @SerialName("schedule_error") val scheduleError: String? = null,
) {
    /** Si la alarma está actualmente activa: programada y sin fallas registradas. */
    val alarmLive: Boolean get() = alarmScheduledAt > 0L && scheduleError == null
}
