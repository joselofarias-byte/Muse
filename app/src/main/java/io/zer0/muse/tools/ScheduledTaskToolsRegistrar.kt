package io.zer0.muse.tools

import android.content.Context

/**
 * P1-3b Desdominio: registrador de herramientas de tareas programadas.
 *
 * Registrarse scheduled_task_create / list / update / delete / execute / get_history.
 * La implementación está ubicada en [ScheduledTaskToolsImpl.kt].
 */
class ScheduledTaskToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = ScheduledTaskToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_create",
                // v1.0.75 fix (Revisión de herramientas 01): Instrucciones requeridas y ejemplos para complementar las condiciones cron
                // v2.6.7: Eliminar "solo siempre confiable"——Se han completado los parámetros condicionales.,Esta oración hará que el modelo evite activamente todas las condiciones.
                description = "Crear una tarea programada,Después de llegar al punto, la IA ejecutará el mensaje." +
                    "Como recordatorio a las 9 en punto todos los días.: interval='daily' o interval='cron' + cron_expr='0 9 * * *'." +
                    "La ejecución se puede omitir condicionalmente(condition_type),Los parámetros condicionales deben darse juntos.,De lo contrario la creación será rechazada.",
                parameters = mapOf(
                    "name" to "Requerido,Nombre de la tarea",
                    "prompt" to "Requerido,Palabra de indicación de tarea",
                    "interval" to "Opcional,once/hourly/daily/weekly/cron,Predeterminado daily",
                    "cron_expr" to "Opcional,interval='cron' Requerido cuando sea necesario. Estándar 5 segmentos cron,Como por ejemplo '0 9 * * *'(9 en punto todos los días),'0 */2 * * *'(cada 2 horas). Ignorado en modo no cron",
                    "assistant_id" to "Opcional,asistente de ejecución,Predeterminado default",
                    "action_type" to "Opcional,ai_prompt/create_quick_note/call_tool/notify",
                    "condition_type" to CONDITION_TYPE_DESC,
                    "condition_start_hour" to "Opcional,condition_type=time_range Requerido cuando,0-23",
                    "condition_end_hour" to "Opcional,condition_type=time_range Requerido cuando,0-23",
                    "condition_keyword" to "Opcional,condition_type=contains Requerido cuando;quick_note_exists Cuando con condition_tag dé al menos uno",
                    "condition_tag" to "Opcional,condition_type=quick_note_exists etiquetas de notas",
                    "condition_min_level" to "Opcional,condition_type=battery_level Requerido cuando,1-100",
                ),
                required = setOf("name", "prompt"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskCreate(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_list",
                description = "Listar las tareas programadas.",
                parameters = mapOf(
                    "enabled" to "Opcional,true/false Estado del filtro habilitado",
                    "limit" to "Opcional,Cantidad de devolución,Predeterminado 20",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execScheduledTaskList(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_update",
                description = "Actualizar tareas programadas. Pase solo los campos a cambiar;Al cambiar condition_type, todos los parámetros de condición de este tipo deben proporcionarse al mismo tiempo.",
                parameters = mapOf(
                    "id" to "Requerido,Tarea id",
                    "name" to "Opcional,nuevo nombre",
                    "prompt" to "Opcional,nueva palabra rápida",
                    "interval" to "Opcional,nuevo intervalo",
                    "cron_expr" to "Opcional,Nueva expresión cron",
                    "enabled" to "Opcional,true/false",
                    "condition_type" to CONDITION_TYPE_DESC,
                    "condition_start_hour" to "Opcional,time_range Requerido cuando,0-23",
                    "condition_end_hour" to "Opcional,time_range Requerido cuando,0-23",
                    "condition_keyword" to "Opcional,contains Requerido cuando;quick_note_exists Cuando con condition_tag dé al menos uno",
                    "condition_tag" to "Opcional,quick_note_exists etiquetas de notas",
                    "condition_min_level" to "Opcional,battery_level Requerido cuando,1-100",
                ),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskUpdate(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_delete",
                description = "Eliminar tareas programadas.",
                parameters = mapOf("id" to "Requerido,Tarea id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskDelete(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_execute",
                description = "Activa una tarea programada inmediatamente.",
                parameters = mapOf("id" to "Requerido,Tarea id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execScheduledTaskExecute(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "scheduled_task_get_history",
                description = "Consultar el historial de ejecución de tareas programadas.",
                parameters = mapOf(
                    "id" to "Requerido,Tarea id",
                    "limit" to "Opcional,Cantidad de devolución,Predeterminado 10",
                ),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execScheduledTaskGetHistory(args) }
    }

    private companion object {
        /**
         * Descripción del tipo de condición.
         *
         * debe ser con ScheduledTaskToolsImpl.buildConditionJson Correspondencia uno a uno entre los tipos admitidos ——
         * Enumerar tipos que el motor no admite hará que el modelo cree tareas que nunca cumplirán las condiciones.
         */
        const val CONDITION_TYPE_DESC =
            "Opcional,El valor predeterminado es siempre.always=ejecutar siempre;network_available=cuando hay internet;" +
                "time_range=dentro del periodo de tiempo especificado(necesidad condition_start_hour/end_hour);" +
                "contains=prompt Al contener palabras clave(necesidad condition_keyword);" +
                "quick_note_exists=Cuando hay notas coincidentes(Requiere condition_keyword o condition_tag);" +
                "battery_level=Cuando el nivel de la batería es superior al umbral(necesidad condition_min_level);charging=Mientras carga"
    }
}
