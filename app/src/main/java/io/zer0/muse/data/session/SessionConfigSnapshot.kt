package io.zer0.muse.data.session

import io.zer0.common.AppJson
import kotlinx.serialization.Serializable

/**
 * v2.5.3 (P3-2): Instantánea de configuración a nivel de sesión.
 *
 * Problema: La sesión anteriormente solo estaba vinculada al asistente, modelo/Pensar globalmente; Si el usuario corta el modelo global, no se puede recuperar la "configuración actual" de la sesión anterior.
 * Se utilizará el nuevo modelo al reenviar y la respuesta no será coherente con la de entonces.
 *
 * Solución: cuando se genera la sesión por primera vez, tome una instantánea de la configuración actual y guárdela en la base de datos ([SessionEntity.configSnapshotJson]).
 * "Asistente de sobrescritura de instantáneas de sesión" al leer/"Campo global"; Si se elimina el modelo fijado en la instantánea, se revertirá el valor actual del asistente (sin falla grave).
 *
 * Las instantáneas mantienen los campos necesarios al mínimo y cubren solo los elementos que afectan el resultado generado:provider, modelo, nivel de pensamiento, temperatura, rendimiento máximo.
 */
@Serializable
data class SessionConfigSnapshot(
    val providerId: String? = null,
    val modelId: String? = null,
    /** nivel de pensamiento (OFF/AUTO/LOW/MEDIUM/HIGH/XHIGH…),null = No arreglado. */
    val reasoningLevel: String? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
) {
    /** Si la instantánea está vacía (sin campos fijos). */
    fun isEmpty(): Boolean = providerId == null && modelId == null && reasoningLevel == null &&
        temperature == null && maxTokens == null

    companion object {
        /** Serializado a cadena JSON; la instantánea vacía devuelve nulo (sin base de datos, sin valor de columna). */
        fun encode(snapshot: SessionConfigSnapshot?): String? {
            if (snapshot == null || snapshot.isEmpty()) return null
            return runCatching { AppJson.encodeToString(serializer(), snapshot) }.getOrNull()
        }

        /** deserialización;null/en blanco/El JSON incorrecto siempre devuelve nulo (como si no se hubiera tomado ninguna instantánea). */
        fun decode(raw: String?): SessionConfigSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching { AppJson.decodeFromString(serializer(), raw) }.getOrNull()
        }
    }
}
