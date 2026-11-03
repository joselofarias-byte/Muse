package io.zer0.muse.data

import io.zer0.ai.core.ProviderConfig

/**
 * P2-1: Provider conflicto(Configuración duplicada)detector.
 *
 * Cuando el usuario completa "la misma URL base + los mismos últimos 4 dígitos de la clave API" en diferentes entradas,
 * La configuración del proveedor se considera potencialmente duplicada,Se solicita a los usuarios que se fusionen para evitar confusiones.
 *
 * Puntos de diseño:
 *  - baseUrl estandarizar:ir `http(s)://` Prefijo, eliminar barra diagonal, convertir a minúsculas,Reservar host+port+path,
 *    hacer `https://API.OpenAI.com/v1/` con `http://api.openai.com/v1` considerados homólogos.
 *  - apiKey Compara solo los últimos 4 dígitos:evitar en la memoria/Mantenga la clave completa en el registro.,Proteger la privacidad.
 *    Cuando apiKey en ambos lados está vacío, también se considera que "los últimos 4 dígitos son iguales".(Todas son cadenas vacías),Solicita a los usuarios que limpien configuraciones de shell vacías duplicadas.
 *  - La misma baseUrl pero los últimos 4 dígitos de apiKey son diferentes → No es un conflicto(Pueden ser varias cuentas en la misma estación de transferencia.).
 *  - Salida solo para dos configuraciones cuyo resultado de comparación por pares no es él mismo [ProviderCollision].
 *
 * Usuario:[io.zer0.muse.ui.settings.providerListSection] Muestre una tarjeta de advertencia encima de la lista.
 */
object ProviderCollisionDetector {

    /**
     * Detectar conflictos en la lista de proveedores:Lo mismo baseUrl(Después de la normalización,Quitar barra diagonal+minúscula)
     * + apiKey Los últimos 4 dígitos se consideran conflictos.
     *
     * @return lista de conflictos;Una lista vacía significa que no se detectaron duplicados. Cada par de conflictos se devuelve solo una vez(A<B registrado sólo cuando).
     */
    fun detect(providers: List<ProviderConfig>): List<ProviderCollision> {
        if (providers.size < 2) return emptyList()

        // Precalcular claves normalizadas para cada configuración.,evitar en N² La capa interior se repite.
        // Entrada para clave API vacía(Como Ollama/cáscara vacía sin clave)No involucrado en la determinación de conflictos.
        val normalized = providers.mapNotNull { p -> normalizeKey(p)?.let { p to it } }
        val collisions = mutableListOf<ProviderCollision>()
        for (i in normalized.indices) {
            for (j in (i + 1) until normalized.size) {
                val (a, keyA) = normalized[i]
                val (b, keyB) = normalized[j]
                if (keyA == keyB) {
                    collisions += ProviderCollision(
                        providerA = a,
                        providerB = b,
                        reason = "Misma URL base que API Key",
                    )
                }
            }
        }
        return collisions
    }

    /**
     * Normalizar baseUrl:ir `http(s)://` Prefijo, eliminar barra diagonal, convertir a minúsculas,Mantenga host+puerto+ruta.
     * Ejemplo:`https://API.OpenAI.com/v1/` → `api.openai.com/v1`
     *
     * Concatenado con el resumen SHA-256 de apiKey en una clave de comparación unificada.
     * APIKey vacía devuelta null(Esta entrada no participa en la determinación de conflictos.).
     *
     * Utilice resumen en lugar de los últimos 4 dígitos del texto sin formato:Los últimos 4 bits colisionarán(Se considera que diferentes claves tienen la misma configuración.),
     * El resumen evita retener texto plano.,Casi no hay errores de juicio.
     */
    private fun normalizeKey(config: ProviderConfig): String? {
        if (config.apiKey.isBlank()) return null
        val resolved = config.resolvedBaseUrl()
        val stripped = resolved
            .substringAfter("://", missingDelimiterValue = resolved)
            .trimEnd('/')
            .lowercase()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(config.apiKey.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return "$stripped|$digest"
    }
}

/**
 * Un par de configuraciones de proveedor en conflicto.
 *
 * @param providerA una de las partes en conflicto
 * @param providerB una de las partes en conflicto
 * @param reason Motivo del conflicto legible por humanos(Como "Misma baseUrl y clave API")
 */
data class ProviderCollision(
    val providerA: ProviderConfig,
    val providerB: ProviderConfig,
    val reason: String,
)
