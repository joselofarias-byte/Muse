package io.zer0.ai.video

import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger

/**
 * v1.137: Video Provider Centro de registro(con [io.zer0.ai.image.ImageProviderRegistry] estilo consistente).
 *
 * registro de adaptadores(AdapterRegistry)esquema + existente ImageProviderRegistry:
 *  - cada uno [VideoProvider] La clase de implementación se pasa al inicio. [register] Regístrate en este centro;
 *  - la persona que llama pasa [selectFor] prensa [ProviderConfig] Seleccione automáticamente el apropiado provider;
 *  - Seleccione la estrategia de acuerdo con la coincidencia de tres capas del host specId / baseUrl,Ten todo en cuenta [GenericOpenAiVideoProvider].
 *
 * Seleccionar prioridad:
 *  1. specId / id(Ir al prefijo preset_)coincidencia exacta(Como por ejemplo specId="agnes" → AgnesVideoProvider);
 *  2. baseUrl host Contiene palabras clave del proveedor.(Si está incluido "agnes-ai" → AgnesVideoProvider);
 *  3. Ten todo en cuenta [genericProvider](Protocolo universal compatible con OpenAI,Amigable con puntos de tránsito desconocidos).
 *
 * Arreglar el enrutamiento de ID de proveedor para v1.136 bug:
 *  - Prensa de implementación antigua [ProviderConfig.id] partido duro map key,causa "preset_kling" ≠ "kling" El enrutamiento falla cuando
 *  - Nuevo botón de implementación specId / id(Ir al prefijo preset_)enrutamiento,No tiene nada que ver con identificadores definidos por el usuario.
 *
 * @param genericProvider Bolsillo universal Provider(Regresar en caso de error)
 */
class VideoProviderRegistry(
    private val genericProvider: GenericOpenAiVideoProvider,
) {

    private val providers = mutableMapOf<String, VideoProvider>()

    /** Registrar un [VideoProvider](con su [VideoProvider.providerId] para key,Este último cubre el primero.). */
    fun register(provider: VideoProvider) {
        providers[provider.providerId] = provider
        Logger.i(TAG, "registered video provider: ${provider.providerId}")
    }

    /** Regístrese por ID de proveedor [VideoProvider]. */
    fun get(providerId: String): VideoProvider? = providers[providerId]

    /** Todos registrados provider(no contiene genericProvider). */
    fun all(): Collection<VideoProvider> = providers.values

    /**
     * Según [ProviderConfig] Seleccione automáticamente el apropiado [VideoProvider].
     *
     * Consulte las notas de clase para conocer el orden de selección. nunca regresar null([genericProvider] Ten todo en cuenta).
     */
    fun selectFor(config: ProviderConfig): VideoProvider {
        // 1. specId / id(Ir al prefijo preset_)coincidencia exacta
        //    Alinear ImageProviderRegistry: Manejo preset_kling → kling esperando la historia id
        val key = config.specId?.takeIf { it.isNotBlank() }
            ?: config.id.removePrefix("preset_").takeIf { it.isNotBlank() }
        if (key != null) {
            providers[key]?.let { return it }
        }

        // 2. baseUrl host concordancia de palabras clave
        val host = runCatching {
            java.net.URI(config.resolvedBaseUrl()).host?.lowercase()
        }.getOrNull().orEmpty()
        if (host.isNotEmpty()) {
            for ((hostPattern, providerId) in HOST_PATTERNS) {
                if (host.contains(hostPattern, ignoreCase = true)) {
                    providers[providerId]?.let { return it }
                }
            }
        }

        // 3. Universales Provider(Amigable con puntos de tránsito desconocidos,Siga el protocolo compatible con OpenAI)
        return genericProvider
    }

    companion object {
        private const val TAG = "VideoProviderRegistry"

        /**
         * baseUrl host modo → providerId mapeo(Ordenar por prioridad).
         *
         * host La coincidencia aproximada se utiliza para anular la baseUrl definida por el usuario, pero deja el specId en blanco.
         */
        private val HOST_PATTERNS: List<Pair<String, String>> = listOf(
            // Agnes coincidencia de prioridad(Evite ser interceptado por Propósito General)
            "agnes-ai" to AgnesVideoProvider.PROVIDER_ID,
            // Kling
            "klingai" to KlingVideoProvider.PROVIDER_ID,
            "kuaishou" to KlingVideoProvider.PROVIDER_ID,
        )

        /**
         * Extracto de URL host(minúscula),Para diagnóstico de registros.
         */
        internal fun extractHost(baseUrl: String): String {
            if (baseUrl.isBlank()) return ""
            return runCatching {
                java.net.URI(baseUrl).host?.lowercase() ?: ""
            }.getOrElse {
                val afterScheme = baseUrl.substringAfter("://", baseUrl)
                afterScheme.substringBefore('/').lowercase()
            }
        }
    }
}
