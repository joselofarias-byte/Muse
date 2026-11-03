package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.schedule.ScheduledTaskRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * P1-3b Cancelación de registro: implementación de la herramienta de tareas programadas (migrada desde ToolRegistry.kt tal como está).
 * Registrado en ToolRegistry por ScheduledTaskToolsRegistrar.
 */
class ScheduledTaskToolsImpl(private val context: Context) {
    private val FMT_DATETIME_MIN = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // ── v1.0.17: Herramienta de tareas programadas ──────────────────────────────────────────

    /** Dar formato a la marca de tiempo como una cadena legible(<=0 considerado discapacitado). */
    fun formatTimestamp(ts: Long): String {
        return if (ts > 0) FMT_DATETIME_MIN.get()?.format(Date(ts)) ?: "desconocido" else "Discapacitado"
    }

    /** Formatee la tarea programada en un único resumen. */
    fun formatScheduledTask(t: ScheduledTaskEntity): String {
        val intervalDesc = when (t.interval) {
            "once" -> "soltero"
            "hourly" -> "cada hora"
            "daily" -> "todos los dias"
            "weekly" -> "semanal"
            "cron" -> "Cron: ${t.cronExpr}"
            else -> t.interval
        }
        return "• ${t.name}[id=${t.id}]\n  intervalo: $intervalDesc | la próxima vez: ${formatTimestamp(t.nextRunAt)} | ${if (t.enabled) "habilitar" else "Desactivar"}"
    }

    /** Crea una tarea programada. */
    suspend fun execScheduledTaskCreate(args: Map<String, String>): String {
        val name = args["name"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: name"
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: prompt"
        val interval = args["interval"]?.takeIf { it.isNotBlank() } ?: "daily"
        val cronExpr = args["cron_expr"] ?: ""
        val assistantId = args["assistant_id"]?.takeIf { it.isNotBlank() } ?: "default"
        val actionType = args["action_type"]?.takeIf { it.isNotBlank() } ?: "ai_prompt"
        val conditionType = args["condition_type"]?.takeIf { it.isNotBlank() } ?: "always"

        if (interval == "cron" && cronExpr.isBlank()) {
            return "interval=cron debe proporcionarse cuando cron_expr"
        }

        val conditionJson =
            buildConditionJson(
                conditionType = conditionType,
                params = conditionParamsOf(args),
            ) ?: return conditionErrorHint(conditionType)

        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val task = ScheduledTaskEntity(
            id = id,
            name = name,
            prompt = prompt,
            assistantId = assistantId,
            interval = interval,
            cronExpr = cronExpr,
            enabled = true,
            nextRunAt = ScheduledTaskRunner.initialNextRunAt(interval, cronExpr, now),
            createdAt = now,
            updatedAt = now,
            actionType = actionType,
            conditionJson = conditionJson,
            createdBy = "assistant",
        )
        MuseDb.get(context).scheduledTaskDao().upsert(task)
        return "Tarea programada creada: ${task.name}[id=$id] (intervalo=$interval, Condiciones=${describeCondition(
            conditionJson,
        )}, Ejecutar la próxima vez=${formatTimestamp(task.nextRunAt)})"
    }

    /** Listar las tareas programadas. */
    suspend fun execScheduledTaskList(args: Map<String, String>): String {
        val enabledOnly = args["enabled"]?.let { it.equals("true", ignoreCase = true) }
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 20
        val all = MuseDb.get(context).scheduledTaskDao().getAll()
        val filtered = when (enabledOnly) {
            true -> all.filter { it.enabled }
            false -> all.filter { !it.enabled }
            null -> all
        }.take(limit)
        if (filtered.isEmpty()) return "Aún no hay tareas programadas"
        val sb = StringBuilder("tareas programadas (${filtered.size}):\n")
        filtered.forEach { sb.appendLine(formatScheduledTask(it)) }
        return sb.toString().trimEnd()
    }

    /** Actualizar tareas programadas. Pase solo los campos que deben modificarse,Los campos no transmitidos conservan sus valores originales. */
    suspend fun execScheduledTaskUpdate(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "Tarea no encontrada: $id"
        val name = args["name"] ?: existing.name
        val prompt = args["prompt"] ?: existing.prompt
        val interval = args["interval"] ?: existing.interval
        val cronExpr = args["cron_expr"] ?: existing.cronExpr
        val enabled = args["enabled"]?.let { it.equals("true", ignoreCase = true) } ?: existing.enabled

        if (interval == "cron" && cronExpr.isBlank()) {
            return "interval=cron debe proporcionarse cuando cron_expr"
        }

        val now = System.currentTimeMillis()
        // Condiciones:Cambie condition_type solo después de pasar explícitamente condition_type;Al cargar, se requieren parámetros completos.,No se permite escribir solo tipografía.
        val conditionUpdate = resolveConditionUpdate(args, existing.conditionJson)
        if (conditionUpdate.error != null) return conditionUpdate.error
        val newConditionJson = conditionUpdate.json
        // Vuelva a calcular el siguiente tiempo de ejecución cuando cambie el intervalo o la expresión cron;Solo alternar habilitado sin recálculo
        val nextRunAt = if (interval != existing.interval || cronExpr != existing.cronExpr) {
            ScheduledTaskRunner.initialNextRunAt(interval, cronExpr, now)
        } else {
            existing.nextRunAt
        }
        val updated = existing.copy(
            name = name,
            prompt = prompt,
            interval = interval,
            cronExpr = cronExpr,
            enabled = enabled,
            nextRunAt = nextRunAt,
            updatedAt = now,
            conditionJson = newConditionJson,
        )
        dao.upsert(updated)
        return "Tareas programadas actualizadas: ${updated.name}[id=$id]"
    }

    /** Análisis de resultados de actualizaciones condicionales:O dar nuevo JSON,O dar los motivos del rechazo. */
    private data class ConditionUpdate(val json: String, val error: String? = null)

    /** Analice los cambios de condición en la actualización. Si no se pasa condition_type, se conservará el valor original. */
    private fun resolveConditionUpdate(args: Map<String, String>, current: String): ConditionUpdate {
        val type = args["condition_type"]?.takeIf { it.isNotBlank() }
        val json = type?.let { buildConditionJson(conditionType = it, params = conditionParamsOf(args)) }
        return when {
            type == null -> ConditionUpdate(current)
            json == null -> ConditionUpdate(current, conditionErrorHint(type))
            else -> ConditionUpdate(json)
        }
    }

    /** Eliminar tareas programadas. */
    suspend fun execScheduledTaskDelete(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "Tarea no encontrada: $id"
        dao.delete(id)
        return "Tarea programada eliminada: ${existing.name}[id=$id]"
    }

    /** Ejecutar una tarea programada inmediatamente(Establecer next_run_at en ahora,Próxima ronda de ejecución de encuestas). */
    suspend fun execScheduledTaskExecute(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: id"
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "Tarea no encontrada: $id"
        if (!existing.enabled) return "Tarea deshabilitada,Por favor habilítelo antes de ejecutar: $id"
        // Activar la siguiente ronda de ejecución de encuestas(Recogido por ScheduledTaskRunner en hasta 60 segundos)
        dao.triggerNextTasks(listOf(id))
        return "Ejecución de tarea activada: ${existing.name}[id=$id],será ejecutado en la próxima ronda de votación"
    }

    /** Consultar el historial de ejecución de tareas programadas. */
    suspend fun execScheduledTaskGetHistory(args: Map<String, String>): String {
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return "Falta el parámetro requerido: id"
        val limit = args["limit"]?.toIntOrNull()?.takeIf { it > 0 } ?: 10
        val dao = MuseDb.get(context).scheduledTaskDao()
        val existing = dao.getById(id) ?: return "Tarea no encontrada: $id"
        val records = MuseDb.get(context).scheduledTaskExecutionDao().queryByTaskId(id)
        if (records.isEmpty()) return "Tarea ${existing.name}[id=$id] Aún no hay historial de ejecución"
        val sb = StringBuilder("historial de ejecución (Tarea=${existing.name}):\n")
        records.take(limit).forEach { e ->
            val time = FMT_DATETIME_MIN.get()?.format(Date(e.executedAt)) ?: "desconocido"
            val detail = when (e.status) {
                "success" -> "éxito: ${e.replySummary.take(80)}"
                "failed" -> "falló: ${e.errorMessage}"
                "skipped" -> "saltar(Condición no cumplida)"
                else -> e.status
            }
            sb.appendLine("• [$time] $detail")
        }
        return sb.toString().trimEnd()
    }

    companion object {
        private val A = io.zer0.muse.data.schedule.AutomationConfig.Condition

        /** Obtenga parámetros de condición de los parámetros de la herramienta. */
        internal fun conditionParamsOf(args: Map<String, String>): ConditionParams = ConditionParams(
            startHour = args["condition_start_hour"],
            endHour = args["condition_end_hour"],
            keyword = args["condition_keyword"],
            tag = args["condition_tag"],
            minLevel = args["condition_min_level"],
        )

        /** Colección de parámetros condicionales. Utilice objetos con nombre en lugar de 6 parámetros paralelos,El pulsador no se extraviará. */
        internal data class ConditionParams(
            val startHour: String? = null,
            val endHour: String? = null,
            val keyword: String? = null,
            val tag: String? = null,
            val minLevel: String? = null,
        )

        /**
         * Deletrear conditionJson por tipo de condición.
         *
         * Anteriormente todos los tipos que no siempre escribían `{"type":"x"}`,Faltan todos los parámetros:
         * time_range Sin startHour/endHour, degenerará en 0..23(Hengzhen),contains Si falta la palabra clave, siempre será falsa.
         * El modelo supone que se establecen las condiciones.,En realidad no está configurado —— Pertenece a "Pensé que funcionaría, pero no funcionó".
         *
         * @return null Indica que el tipo carece de los parámetros requeridos.,La persona que llama debe rechazar la creación y preguntarle cómo solucionarla.
         */
        internal fun buildConditionJson(conditionType: String, params: ConditionParams = ConditionParams()): String? {
            val type = conditionType.trim().ifBlank { A.ALWAYS }
            return if (type == A.ALWAYS) "" else buildNonAlwaysCondition(type, params)
        }

        /** rama no siempre:Devolver si los parámetros no están calificados null,Devuelve JSON completo si califica. */
        private fun buildNonAlwaysCondition(type: String, params: ConditionParams): String? {
            val config = configFor(type, params) ?: return null
            return kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive(type))
                put("config", configJson(config))
            }.toString()
        }

        /** objeto de parámetro de condición;Los parámetros numéricos se escriben como números.,Runtime Presione lateralmente toString y luego analice,Puedo leer ambos. */
        private fun configJson(config: Map<String, String>): kotlinx.serialization.json.JsonObject =
            kotlinx.serialization.json.buildJsonObject {
                config.forEach { (k, v) ->
                    val asInt = v.toIntOrNull()
                    put(
                        k,
                        if (asInt != null) {
                            kotlinx.serialization.json.JsonPrimitive(asInt)
                        } else {
                            kotlinx.serialization.json.JsonPrimitive(v)
                        },
                    )
                }
            }

        /**
         * Verificación de parámetros y selección de valores para cada tipo de condición.
         *
         * @return null Indica que los parámetros no están calificados.;Un mapa vacío indica que el tipo no requiere parámetros adicionales.
         */
        private fun configFor(type: String, params: ConditionParams): Map<String, String>? = when (type) {
            A.TIME_RANGE -> timeRangeConfig(params)
            A.CONTAINS -> params.keyword?.trim().takeUnless { it.isNullOrEmpty() }?.let { mapOf("keyword" to it) }
            A.QUICK_NOTE_EXISTS -> quickNoteConfig(params)
            A.BATTERY_LEVEL -> batteryConfig(params)
            A.CHARGING, A.NETWORK_AVAILABLE -> emptyMap()
            else -> null
        }

        private fun timeRangeConfig(params: ConditionParams): Map<String, String>? {
            val start = params.startHour?.trim()?.toIntOrNull()
            val end = params.endHour?.trim()?.toIntOrNull()
            val valid = start != null && end != null && start in 0..23 && end in 0..23
            return if (valid) mapOf("startHour" to start.toString(), "endHour" to end.toString()) else null
        }

        private fun quickNoteConfig(params: ConditionParams): Map<String, String>? {
            val keyword = params.keyword?.trim().orEmpty()
            val tag = params.tag?.trim().orEmpty()
            // Al menos uno de los dos,De lo contrario, "cualquier nota existe" siempre es cierta
            if (keyword.isEmpty() && tag.isEmpty()) return null
            return buildMap {
                if (keyword.isNotEmpty()) put("keyword", keyword)
                if (tag.isNotEmpty()) put("tag", tag)
            }
        }

        private fun batteryConfig(params: ConditionParams): Map<String, String>? {
            val level = params.minLevel?.trim()?.toIntOrNull()
            return level?.takeIf { it in 1..100 }?.let { mapOf("minLevel" to it.toString()) }
        }

        /** Proporciona una guía ejecutable al modelo cuando faltan parámetros condicionales.,En lugar de decir \"el parámetro es ilegal\". */
        private fun conditionErrorHint(conditionType: String): String = when (conditionType.trim()) {
            A.TIME_RANGE ->
                "condition_type=time_range Requiere condition_start_hour y condition_end_hour(0-23)"
            A.CONTAINS ->
                "condition_type=contains necesitar condition_keyword"
            A.QUICK_NOTE_EXISTS ->
                "condition_type=quick_note_exists Requiere al menos una de condition_keyword o condition_tag"
            A.BATTERY_LEVEL ->
                "condition_type=battery_level necesitar condition_min_level(1-100)"
            else -> "Tipo de condición no admitida: $conditionType(Disponible: always/network_available/time_range/contains/quick_note_exists/battery_level/charging)"
        }

        /** a los usuarios/Un resumen de una línea de las condiciones que analiza el modelo. */
        private fun describeCondition(conditionJson: String): String {
            if (conditionJson.isBlank()) return "ejecutar siempre"
            return runCatching {
                val cond = A.serializer()
                val parsed = io.zer0.common.AppJson.decodeFromString(cond, conditionJson)
                val cfg = parsed.config.entries.joinToString(",") { "${it.key}=${it.value.toString().trim('"')}" }
                if (cfg.isBlank()) parsed.type else "${parsed.type}($cfg)"
            }.getOrDefault("configurado")
        }
    }
}
