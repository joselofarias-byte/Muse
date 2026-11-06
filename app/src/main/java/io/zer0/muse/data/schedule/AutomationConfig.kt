package io.zer0.muse.data.schedule

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * v1.137: Configuración automatizada compleja(Activación condicional, tareas en cadena, tipos de acciones múltiples).
 *
 * Persistente en formato JSON en el campo de extensión de ScheduledTaskEntity,
 * Mantener la compatibilidad con versiones anteriores de tareas programadas(valor nulo = Comportamiento predeterminado = ejecutar siempre ai_prompt).
 */
object AutomationConfig {

    /** Tipo de condición. */
    @Serializable
    data class Condition(
        @SerialName("type") val type: String = "always",
        @SerialName("config") val config: JsonObject = JsonObject(emptyMap()),
    ) {
        companion object {
            const val ALWAYS = "always"
            const val NETWORK_AVAILABLE = "network_available"
            const val TIME_RANGE = "time_range"
            const val CONTAINS = "contains"
            const val QUICK_NOTE_EXISTS = "quick_note_exists"

            /** v1.0.17: Condición de umbral de potencia,config["minLevel"] es el porcentaje mínimo de batería(Predeterminado 20). */
            const val BATTERY_LEVEL = "battery_level"

            /** v1.0.17: Condiciones del estado de carga,config["mustCharging"] Si es necesario cargar(Predeterminado true). */
            const val CHARGING = "charging"
        }
    }

    /** Configuración de acciones. */
    @Serializable
    data class Action(
        @SerialName("type") val type: String = "ai_prompt",
        @SerialName("config") val config: JsonObject = JsonObject(emptyMap()),
    ) {
        companion object {
            const val AI_PROMPT = "ai_prompt"
            const val CREATE_QUICK_NOTE = "create_quick_note"
            const val CALL_TOOL = "call_tool"
            const val NOTIFY = "notify"
        }
    }

    fun Condition.toJson(): String = try {
        AppJson.encodeToString(Condition.serializer(), this)
    } catch (e: Exception) {
        Logger.w("AutomationConfig", "Condition encode failed: ${e.message}")
        ""
    }

    fun String.toCondition(): Condition = if (this.isBlank()) {
        Condition()
    } else {
        try {
            AppJson.decodeFromString(Condition.serializer(), this)
        } catch (e: Exception) {
            Logger.w("AutomationConfig", "Condition decode failed: ${e.message}")
            Condition()
        }
    }

    fun Action.toJson(): String = try {
        AppJson.encodeToString(Action.serializer(), this)
    } catch (e: Exception) {
        Logger.w("AutomationConfig", "Action encode failed: ${e.message}")
        ""
    }

    fun String.toAction(): Action = if (this.isBlank()) {
        Action()
    } else {
        try {
            AppJson.decodeFromString(Action.serializer(), this)
        } catch (e: Exception) {
            Logger.w("AutomationConfig", "Action decode failed: ${e.message}")
            Action()
        }
    }

    fun List<String>.toIdsJson(): String = try {
        AppJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                kotlinx.serialization.json.JsonElement.serializer(),
            ),
            this.map {
                kotlinx.serialization.json.JsonPrimitive(it)
            },
        )
    } catch (e: Exception) {
        Logger.w("AutomationConfig", "Ids encode failed: ${e.message}")
        "[]"
    }

    fun String.toIdsList(): List<String> = if (this.isBlank()) {
        emptyList()
    } else {
        try {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()),
                this,
            )
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        } catch (e: Exception) {
            Logger.w("AutomationConfig", "Ids decode failed: ${e.message}")
            emptyList()
        }
    }

    inline fun <reified T> JsonObject.getValue(key: String): T? = try {
        AppJson.decodeFromJsonElement<T>(this[key] ?: JsonObject(emptyMap()))
    } catch (e: Exception) {
        null
    }

    inline fun <reified T> JsonObject.getString(key: String): String? = this[key]?.toString()?.trim('"')
}
