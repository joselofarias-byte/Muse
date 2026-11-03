package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.tools.reminder.ReminderAlarmScheduler
import io.zer0.muse.tools.reminder.ReminderAlarmReceiver
import io.zer0.muse.tools.reminder.ReminderEntry
import io.zer0.muse.tools.reminder.ReminderStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P1-3b Desdominio: registrador de herramientas de recordatorio programado.
 *
 * Extraído de ToolRegistry.kt schedule_reminder / cancel_reminder / list_reminders,
 * Contiene la lógica de programación ReminderStore y AlarmManager.
 */
class ReminderToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val reminderStore: ReminderStore = ReminderStore(context),
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "schedule_reminder",
                // v1.0.75 fix (Revisión de herramientas 02): Diferenciar de set_alarm/scheduled_task_create + descripción de devolución
                description = "Crear un recordatorio programado local,Aparece una notificación cuando llega el momento. Los usuarios dicen'Recuérdame hacerlo a la hora X Y'cuando lo llaman." +
                    "diferente de: set_alarm=Despertador del sistema(Toca la campana), scheduled_task_create=Tareas periódicas de IA(Ejecución automática prompt)." +
                    "time apoyo '2026-08-16 15:30' o ISO 8601,Debe ser en el futuro. Recordatorio de devolución id,Puede utilizar cancel_reminder para cancelar más tarde.",
                parameters = mapOf(
                    "title" to "Requerido,título del recordatorio",
                    "message" to "Requerido,Texto recordatorio",
                    // B-08: Los ejemplos deben estar en un formato que la implementación pueda analizar. — "2026-07-21T15:30:00"(Sin desplazamiento de zona horaria)
                    // no puedo pasar Instant.parse;Sólo se puede analizar el formato de hora local o ISO con Z/offset.
                    "time" to "Requerido,tiempo de activación,Como 2026-07-21 15:30 o 2026-07-21T15:30:00Z,debe ser en el futuro",
                ),
                required = setOf("title", "message", "time"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val title = args["title"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_reminder_missing_title)
            val message = args["message"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_reminder_missing_message)
            val timeStr = args["time"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_reminder_missing_time)
            val triggerAt = parseReminderTime(timeStr)
                ?: return@register context.getString(R.string.tool_reminder_invalid_time, timeStr)
            if (triggerAt <= System.currentTimeMillis()) {
                return@register context.getString(R.string.tool_reminder_past_time)
            }
            val id = reminderStore.add(
                title = title,
                message = message,
                triggerAtMillis = triggerAt,
                targetType = ReminderAlarmReceiver.TARGET_HOME,
                targetId = "",
            )
            val result = ReminderAlarmScheduler.schedule(
                context = context,
                id = id,
                title = title,
                message = message,
                triggerAtMillis = triggerAt,
                targetType = ReminderAlarmReceiver.TARGET_HOME,
                targetId = "",
            )
            val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(triggerAt))
            if (!result.scheduled) {
                // La programación falla por completo: conserve la entrada (en lugar de eliminarla silenciosamente) y registre el motivo para que list_reminders pueda marcarla en consecuencia.
                reminderStore.markScheduleFailed(id, result.error ?: "alarm_schedule_failed")
                return@register context.getString(R.string.tool_reminder_schedule_failed)
            }
            if (result.needsExactPermission) {
                // Se ha degradado a un despertador no preciso: primero asegúrese de que "sonará" y luego guíe al usuario para que active el permiso "Despertador y recordatorio" para lograr la puntualidad.
                reminderStore.markScheduled(id, exact = false, atMillis = System.currentTimeMillis())
                ReminderAlarmScheduler.requestExactAlarmPermission(context)
                return@register context.getString(R.string.tool_reminder_scheduled_inexact, id, whenText)
            }
            reminderStore.markScheduled(id, exact = true, atMillis = System.currentTimeMillis())
            context.getString(R.string.tool_reminder_scheduled, id, whenText)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "cancel_reminder",
                description = "Cancelar un recordatorio programado creado.",
                parameters = mapOf("id" to "Requerido,recordatorio id(Devuelto por Schedule_reminder)"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val id = args["id"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_reminder_missing_id)
            ReminderAlarmScheduler.cancel(context, id)
            if (reminderStore.remove(id)) {
                context.getString(R.string.tool_reminder_cancelled, id)
            } else {
                context.getString(R.string.tool_reminder_not_found, id)
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "list_reminders",
                description = "Enumere todos los recordatorios programados que actualmente no están activados.",
                parameters = mapOf("limit" to "Opcional,Número máximo de devoluciones,Predeterminado 20"),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("limit" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            // Cuando el archivo de la lista de recordatorios esté dañado, primero transmita al usuario que "la lista está dañada y se ha realizado una copia de seguridad" en lugar de fingir que la lista está vacía.
            reminderStore.consumeError()?.let { return@register it }
            val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
            val list = reminderStore.list().sortedBy { it.triggerAtMillis }.take(limit)
            if (list.isEmpty()) return@register context.getString(R.string.tool_reminder_list_empty)
            val sb = StringBuilder(context.getString(R.string.tool_reminder_list_header, list.size))
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            list.forEach {
                sb.appendLine(
                    context.getString(
                        R.string.tool_reminder_list_item,
                        it.id,
                        sdf.format(Date(it.triggerAtMillis)),
                        it.title,
                        statusText(it),
                    ),
                )
            }
            sb.toString().trimEnd()
        }
    }

    /**
     * El estado legible de un recordatorio: permite a los usuarios distinguir entre "sonará a tiempo", "sólo sonará a tiempo" y "puede que no suene en absoluto".
     */
    private fun statusText(entry: ReminderEntry): String = when {
        entry.scheduleError != null -> context.getString(R.string.tool_reminder_status_unscheduled)
        entry.alarmLive && entry.scheduledExact -> context.getString(R.string.tool_reminder_status_scheduled)
        entry.alarmLive -> context.getString(R.string.tool_reminder_status_inexact)
        else -> context.getString(R.string.tool_reminder_status_lost)
    }

    private fun parseReminderTime(input: String): Long? {
        val trimmed = input.trim()
        val patterns = listOf(
            "yyyy-MM-dd HH:mm" to SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()),
            "yyyy-MM-dd HH:mm:ss" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()),
            "yyyy/MM/dd HH:mm" to SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()),
        )
        patterns.forEach { (_, sdf) ->
            try {
                return sdf.parse(trimmed)?.time
            } catch (_: Exception) { /* ignore */ }
        }
        return try {
            java.time.Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }
}
