package io.zer0.common

import kotlinx.serialization.json.Json

/**
 * Compartido globalmente [Json] Ejemplo.
 *
 * - Ignorar campos desconocidos(Compatible con la evolución del campo del lado del servidor)
 * - análisis relajado(Permitir no estándar JSON)
 * - codificación predeterminada(Asegúrese de que los resultados de la serialización sean estables)
 * - No generar campos nulos explícitamente
 * - Forzar la conversión de valores predeterminados ilegales en valores legales
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}
