package io.zer0.ai.image

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger

/**
 * v1.0.18: Centro de registro de proveedores de generación de imágenes.
 *
 * registro de adaptadores(AdapterRegistry)modo:
 *  - cada uno [ImageProvider] La clase de implementación está registrada en este centro al inicio.;
 *  - la persona que llama pasa [selectFor] prensa [ProviderConfig] Seleccione automáticamente el apropiado provider;
 *  - Seleccionar botón de estrategia specId / baseUrl host / type Tres niveles de emparejamiento,OpenAIImageProvider encubierto.
 *
 * Seleccionar prioridad:
 *  1. specId / id coincidencia exacta(Como por ejemplo specId="agnes" → AgnesImageProvider);
 *  2. baseUrl host Contiene palabras clave del proveedor.(Si está incluido "agnes" → AgnesImageProvider);
 *  3. type partido(GEMINI → GeminiImageProvider;OPENAI / OPENAI_RESPONSES → OpenAIImageProvider);
 *  4. Ten todo en cuenta OpenAIImageProvider(Amigable con puntos de tránsito desconocidos,Siga el protocolo compatible con OpenAI).
 *
 * Gemini Ya independiente [GeminiImageProvider](B14-2,Utilice la salida de imagen nativa de generateContent);
 * Anthropic Sin generación de imágenes nativas API(Solo comprensión de imágenes),Por lo tanto, todavía llegaré al fondo del asunto. [OpenAIImageProvider],
 * El error HTTP que devuelve naturalmente avisa al usuario.
 */
class ImageProviderRegistry {

    private val providers = mutableMapOf<String, ImageProvider>()

    /** Registrar un [ImageProvider](con su [ImageProvider.providerId] para key,Este último cubre el primero.). */
    fun register(provider: ImageProvider) {
        providers[provider.providerId] = provider
        Logger.i(TAG, "registered image provider: ${provider.providerId}")
    }

    /** Regístrese por ID de proveedor [ImageProvider]. */
    fun get(providerId: String): ImageProvider? = providers[providerId]

    /** Todos los proveedores registrados. */
    fun all(): Collection<ImageProvider> = providers.values

    /**
     * Según [ProviderConfig] Seleccione automáticamente el apropiado [ImageProvider].
     *
     * Consulte las notas de clase para conocer el orden de selección. Devuelve nulo sólo si el registro está vacío(Lanzado sin registrar ningún provider).
     */
    fun selectFor(config: ProviderConfig): ImageProvider? {
        // 1. specId / id coincidencia exacta
        val key = config.specId?.takeIf { it.isNotBlank() }
            ?: config.id.removePrefix("preset_").takeIf { it.isNotBlank() }
        if (key != null) {
            providers[key]?.let { return it }
        }

        // 2. baseUrl host concordancia de palabras clave
        val host = runCatching {
            java.net.URI(config.baseUrl).host?.lowercase()
        }.getOrNull().orEmpty()
        if (host.isNotEmpty()) {
            // Contiene "agnes" → Agnes(Alineación ENDPOINT_AGNES=apihub.agnes-ai.com)
            if (host.contains("agnes")) {
                providers[AgnesImageProvider.PROVIDER_ID]?.let { return it }
            }
        }

        // 3. type partido:GEMINI → GeminiImageProvider;OPENAI / OPENAI_RESPONSES → OpenAIImageProvider
        if (config.type == ProviderType.GEMINI) {
            providers[GeminiImageProvider.PROVIDER_ID]?.let { return it }
        }
        if (config.type == ProviderType.OPENAI || config.type == ProviderType.OPENAI_RESPONSES) {
            providers[OpenAIImageProvider.PROVIDER_ID]?.let { return it }
        }

        // 4. Ten todo en cuenta OpenAIImageProvider(Amigable con puntos de tránsito desconocidos)
        return providers[OpenAIImageProvider.PROVIDER_ID]
    }

    companion object {
        private const val TAG = "ImageProviderRegistry"
    }
}
