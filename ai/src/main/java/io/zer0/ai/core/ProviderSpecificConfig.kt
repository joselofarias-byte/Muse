package io.zer0.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Provider configuración específica(sealed class).
 *
 * Phase 8.1 introducir: Encapsule los campos de configuración únicos del proveedor en subclases de clase selladas,
 * Deje que el compilador garantice la seguridad de tipos(when coincidencia exhaustiva de ramas).
 *
 * Estrategia de serialización: Serialización polimórfica utilizando la clase sellada de kotlinx.serialization,
 * Predeterminado con "type" distinguir campo(OpenAI/Anthropic/Gemini).
 * viejo [ProviderConfig] JSON Cuando los datos no tienen este campo,[ProviderConfig.fallbackSpecific]
 * presionará [ProviderType] Crear configuración predeterminada,Garantizar la compatibilidad con versiones anteriores.
 *
 * Escrito de forma independiente.
 */
@Serializable
sealed class ProviderSpecificConfig {

    /**
     * OpenAI Compatible con la configuración específica del proveedor.
     *
     * @param chatCompletionsPath Ruta del punto final de finalización del chat(Predeterminado /chat/completions);
     *   Azure Es posible que sea necesario cambiarlo mientras espera la transferencia. /openai/deployments/{id}/chat/completions
     * @param useResponseApi Si se debe utilizar la nueva versión Responses API(/v1/responses);
     *   false=ir Chat Completions(Predeterminado,Mejor compatibilidad)
     * @param responsesPath Responses API ruta del punto final(Predeterminado /responses);
     *   v1.0.7 Nuevo.Codex OAuth Necesita ser cambiado /codex/responses
     * @param includeHistoryReasoning Si los mensajes históricos contienen contenido de inferencia;
     *   true=Devolver la última ronda de razonamiento como parte del contenido del mensaje del asistente.(Predeterminado true)
     * @param embeddingsPath Embeddings ruta del punto final(Predeterminado /embeddings)
     * @param imagesPath Ruta del punto final de generación de imágenes(Predeterminado /images/generations)
     */
    @Serializable
    @SerialName("OpenAI")
    data class OpenAI(
        val chatCompletionsPath: String = "/chat/completions",
        val useResponseApi: Boolean = false,
        /**
         * v1.0.7: Responses API Ruta del punto final.
         *
         * - Predeterminado "/responses":OpenAI oficial / xAI Grok OAuth / Otros compatibles con OpenAI
         * - "/codex/responses":OpenAI Codex OAuth(ir chatgpt.com/backend-api)
         * - Otras rutas personalizadas:Configurado por el usuario
         *
         * solo si [useResponseApi]=true eficaz cuando.
         */
        val responsesPath: String = "/responses",
        val includeHistoryReasoning: Boolean = true,
        val embeddingsPath: String = "/embeddings",
        val imagesPath: String = "/images/generations",
        /**
         * v1.136: ID de modelo de dibujo predeterminado.
         *
         * Cuando la persona que llama no pasa explícitamente el modelo,ImageService Este campo se utilizará primero.,
         * En segundo lugar, recurra al modelo predeterminado de ImageModelCatalog.(Como por ejemplo dall-e-3).
         * Este par de costumbre/Transit OpenAI compatible con proveedores,Evite el 404 causado por la falta de modelo en el cuerpo de la solicitud.
         */
        val imageModel: String = "",
        /**
         * v1.136: Ruta del punto final de generación de vídeo(Predeterminado /videos/generations).
         *
         * Uso general del proveedor de generación de vídeo compatible con OpenAI;Kling Los proveedores especiales no utilizan este campo.
         */
        val videoGenerationsPath: String = "/videos/generations",
        /**
         * Eliminación del prefijo de identificación del modelo.
         * Transferencia parcial/La plataforma de agregación utiliza el prefijo de proveedor en la lista de modelos locales.(Como por ejemplo opencode-go/glm-5.2),
         * Pero las solicitudes de API reales solo aceptan glm-5.2. Después de configurar esto, OpenAIProvider
         * Elimine este prefijo de model.id. Déjelo en blanco para que no se pele.
         */
        val stripModelPrefix: String = "",
        /**
         * v1.0.6: ¿Es un proveedor de planes de codificación?(*-coding Valores predeterminados de la serie).
         *
         * Coding Plan Características del proveedor:
         *  - baseUrl Utilice un punto final de codificación dedicado(Como por ejemplo https://api.kimi.com/coding/v1)
         *  - apiKey Generalmente con prefijo exclusivo(Como por ejemplo sk-sp- / sk-coding-)
         *  - Programación enfocada en listas de modelos(Como por ejemplo qwen3-coder-plus / kimi-for-coding)
         *  - Algunas partes requieren finalización del tiempo de ejecución headers/reasoning
         *
         * Actualmente solo está disponible como campo de etiqueta,ProviderCompat/ProviderPayloadNormalizer consume diferencias de comportamiento específicas.
         */
        val codingPlan: Boolean = false,
        /** Complemento de nivel de asistente HTTP headers,Fusionado por la capa de aplicación en la tarea de compilación. */
        val customHeaders: Map<String, String> = emptyMap(),
        /** Campos adicionales del cuerpo de solicitud a nivel de asistente,Fusionado por la capa de aplicación en la tarea de compilación. */
        val customBody: Map<String, JsonElement> = emptyMap(),
    ) : ProviderSpecificConfig() {
        /** No escriba encabezados/valores de cuerpo personalizados en los registros,Sólo se conservan los nombres de las claves con fines de diagnóstico. */
        override fun toString(): String = "OpenAI(chatCompletionsPath=$chatCompletionsPath, useResponseApi=$useResponseApi, " +
            "responsesPath=$responsesPath, includeHistoryReasoning=$includeHistoryReasoning, " +
            "embeddingsPath=$embeddingsPath, imagesPath=$imagesPath, imageModel=$imageModel, " +
            "videoGenerationsPath=$videoGenerationsPath, stripModelPrefix=$stripModelPrefix, " +
            "codingPlan=$codingPlan, customHeaderKeys=${customHeaders.keys}, " +
            "customBodyKeys=${customBody.keys})"
    }

    /**
     * Anthropic Claude Provider configuración específica.
     *
     * Phase 8.1 (M9): Unirse [promptCaching] + [promptCacheTtl] campo,
     * Habilite el almacenamiento en caché rápido de Claude para guardar en costos de solicitudes duplicadas.
     *
     * @param promptCaching Ya sea para habilitar Prompt Caching(Predeterminado false,compatible con versiones anteriores)
     * @param promptCacheTtl caché TTL: "5m"(5 minutos,Predeterminado)o "1h"(1 horas,Se requiere la versión empresarial).
     *   v1.80 (L-CORE18): Anthropic Sólo se acepta oficialmente "5m" / "1h" dos valores,
     *   Los valores ilegales vuelven a caer "5m" y registrar advertencias(Evite enviar solicitudes al servidor. 400).
     * @param messagesPath Messages API ruta del punto final(Predeterminado /messages)
     * @param modelsPath Ruta del punto final de la lista de modelos(Predeterminado /models)
     */
    @Serializable
    @SerialName("Anthropic")
    data class Anthropic(
        val promptCaching: Boolean = false,
        val promptCacheTtl: String = "5m", // "5m" / "1h"
        val messagesPath: String = "/messages",
        val modelsPath: String = "/models",
    ) : ProviderSpecificConfig() {
        init {
            // L-CORE18: Verifique el avisoCacheTtl solo acepta valores de documentos oficiales,Los valores ilegales vuelven a caer "5m"
            if (promptCacheTtl != "5m" && promptCacheTtl != "1h") {
                io.zer0.common.Logger.w(
                    "ProviderSpecificConfig",
                    "Anthropic.promptCacheTtl valor ilegal '$promptCacheTtl',debería ser '5m' o '1h'." +
                        "El valor no se puede cambiar durante la deserialización.(immutable),La persona que llama debe corregirlo por sí misma.",
                )
            }
        }
    }

    /**
     * Google Gemini Provider configuración específica.
     *
     * Vertex AI Se han implementado dos modos de autenticación.(Phase 9.4 cuenta de servicio / B14-3 Express Mode):
     *  - [useServiceAccount]=true: Punto final regional + cuenta de servicio JWT→OAuth Bearer(necesidad [projectId]);
     *  - [useVertexAI]=true y [useServiceAccount]=false: Express Mode punto final global + API Key,
     *    No se requiere proyecto GCP/OAuth(BYOK Ruta de bajo coste en el escenario.).
     *
     * @param useVertexAI Si se debe utilizar el punto final Vertex AI(Predeterminado false,ir generativelanguage API)
     * @param useServiceAccount Si se debe utilizar la autenticación de cuenta de servicio(Predeterminado false,usar API Key / Express Mode)
     * @param serviceAccountEmail Correo electrónico de la cuenta de servicio(Vertex AI usar)
     * @param privateKey Clave privada de la cuenta de servicio(Vertex AI usar,PEM Formato)
     * @param location Vertex AI área(Como por ejemplo us-central1)
     * @param projectId GCP Proyecto id(Vertex AI usar)
     * @param streamPath Ruta del punto final de transmisión(Predeterminado :streamGenerateContent)
     * @param generatePath Ruta de punto final sin transmisión(Predeterminado :generateContent)
     */
    @Serializable
    @SerialName("Gemini")
    data class Gemini(
        val useVertexAI: Boolean = false,
        val useServiceAccount: Boolean = false,
        val serviceAccountEmail: String = "",
        val privateKey: String = "",
        val location: String = "us-central1",
        val projectId: String = "",
        val streamPath: String = ":streamGenerateContent",
        val generatePath: String = ":generateContent",
    ) : ProviderSpecificConfig() {
        /**
         * v1.80 (H-CORE2): Personalice toString para evitar que se filtre texto sin cifrar de clave privada.
         * Se filtró la clave privada de la cuenta de servicio = GCP El proyecto colapsó por completo.,Más dañino que la clave API.
         */
        override fun toString(): String {
            val maskedKey = if (privateKey.isNotEmpty()) "***(${privateKey.length} chars)" else ""
            return "Gemini(useVertexAI=$useVertexAI, useServiceAccount=$useServiceAccount, " +
                "serviceAccountEmail=$serviceAccountEmail, privateKey=$maskedKey, " +
                "location=$location, projectId=$projectId)"
        }
    }

    /**
     * Configuración personalizada específica del proveedor(usado para"custom"Opciones de proveedores/estaciones de tránsito).
     *
     * restricción: "Provider addition must include a 'custom' option to use the original
     * blank form". estación de transferencia(preset categoría de relevos en)y los proveedores definidos por el usuario utilizan esta configuración,
     * Permite a los usuarios anular chatCompletionsPath, agregar encabezados de solicitud personalizados y campos del cuerpo de la solicitud.
     *
     * Nota:ai modulares [io.zer0.ai.openai.OpenAIProvider] Pase `resolvedSpecific()
     * as? OpenAI ?: OpenAI()` Ten todo en cuenta,Custom Actualmente, los campos no influyen directamente en el comportamiento del proveedor.
     * (Mapeo bajo demanda por parte de la capa de aplicación antes de llamar);Este tipo está reservado para que la capa de aplicación UI(ProviderEditDialog)
     * Utilice PresetProviders para conservar estos parámetros personalizados.
     *
     * v1.134 P2-2: Nuevo [requestTemplate] / [responsePath] / [streamResponsePath] campo,
     * por [io.zer0.ai.plugin.ProviderPluginRegistry.toProviderConfig] Pasado de forma transparente desde el complemento JSON.
     * La implementación actual del proveedor del módulo ai aún no consume estos campos,El soporte de OpenAIProvider se puede ampliar en el futuro
     * Personalice la plantilla de solicitud y la ruta de respuesta.
     *
     * @param chatCompletionsPath Ruta del punto final de finalización del chat(Predeterminado /chat/completions)
     * @param customHeaders Agregar encabezados de solicitud HTTP(key→value)
     * @param customBody Campos adicionales del cuerpo de la solicitud(key→JsonElement,Fusionar en solicitud de nivel superior JSON)
     * @param requestTemplate Plantilla JSON del cuerpo completo de la solicitud(marcador de posición {{prompt}} {{model}} espera);
     *   Déjelo en blanco para usar la plantilla predeterminada de Finalizaciones de chat de OpenAI.
     * @param responsePath Ruta de extracción JSON de respuesta sin transmisión(Como por ejemplo $.choices[0].message.content);
     *   Si se deja en blanco, tome la ruta predeterminada.
     * @param streamResponsePath Ruta de extracción JSON de respuesta de transmisión(Como por ejemplo $.choices[0].delta.content);
     *   Si se deja en blanco, tome la ruta predeterminada.
     */
    @Serializable
    @SerialName("Custom")
    data class Custom(
        val chatCompletionsPath: String = "/chat/completions",
        val customHeaders: Map<String, String> = emptyMap(),
        val customBody: Map<String, JsonElement> = emptyMap(),
        val requestTemplate: String = "",
        val responsePath: String = "",
        val streamResponsePath: String = "",
    ) : ProviderSpecificConfig() {
        /** No escriba encabezados/valores de cuerpo personalizados en los registros,Sólo se conservan los nombres de las claves con fines de diagnóstico. */
        override fun toString(): String =
            "Custom(chatCompletionsPath=$chatCompletionsPath, requestTemplateLength=${requestTemplate.length}, " +
                "responsePath=$responsePath, streamResponsePath=$streamResponsePath, " +
                "customHeaderKeys=${customHeaders.keys}, customBodyKeys=${customBody.keys})"
    }

    companion object {
        /** prensa [ProviderType] Tome la configuración específica predeterminada. */
        fun defaultFor(type: ProviderType): ProviderSpecificConfig = when (type) {
            ProviderType.OPENAI -> OpenAI()
            ProviderType.ANTHROPIC -> Anthropic()
            ProviderType.GEMINI -> Gemini()
            ProviderType.OPENAI_RESPONSES -> OpenAI(useResponseApi = true)
        }
    }
}
