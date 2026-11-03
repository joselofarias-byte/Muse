package io.zer0.ai.plugin

import kotlinx.serialization.Serializable

/**
 * P2-10: Provider Declaración del modelo de complemento.
 *
 * Cada complemento describe un proveedor de protocolo compatible con OpenAI a través de un archivo JSON,
 * Los usuarios pueden acceder a servicios de modelos de tránsito personalizados/autohospedados sin escribir código.
 *
 * @param id Identificador único del complemento(Al mismo tiempo, sirve como prefijo de identificación para generar ProviderConfig.)
 * @param displayName nombre para mostrar
 * @param description Introducción
 * @param baseUrl Conceptos básicos de API ascendentes URL(Como por ejemplo https://relay.example.com/v1)
 * @param apiKeyPattern API Key Regularidad de verificación(Dejar en blanco para indicar que no hay verificación)
 * @param apiKeyHint API Key Copia del mensaje del cuadro de entrada
 * @param models Lista de modelos declarados por este complemento
 * @param headers Encabezado de solicitud personalizado(Inyectar en solicitudes HTTP ascendentes)
 * @param requestTemplate Plantilla JSON del cuerpo de la solicitud,marcador de posición {{prompt}} {{model}} Espere a ser reemplazado por la persona que llama;
 *   Déjelo en blanco para usar la plantilla predeterminada de Finalizaciones de chat de OpenAI.
 * @param responsePath Ruta de extracción JSON de respuesta sin transmisión(Predeterminado $.choices[0].message.content)
 * @param streamResponsePath Ruta de extracción JSON de respuesta de transmisión(Predeterminado $.choices[0].delta.content)
 */
@Serializable
data class ProviderPlugin(
    val id: String,
    val displayName: String,
    val description: String = "",
    val baseUrl: String,
    val apiKeyPattern: String = "",
    val apiKeyHint: String = "",
    val models: List<PluginModel> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val requestTemplate: String = "",
    val responsePath: String = "$.choices[0].message.content",
    val streamResponsePath: String = "$.choices[0].delta.content",
)

/**
 * P2-10: Declaración del modelo de complemento.
 *
 * Alineación del significado del campo [io.zer0.ai.core.Model](Tome solo el subconjunto que le interesa a la interfaz de usuario),
 * por [ProviderPluginRegistry.toProviderConfig] convertir a completo [io.zer0.ai.core.Model].
 *
 * @param id modelo id(campos del modelo pasados a la API ascendente)
 * @param displayName nombre para mostrar(El valor predeterminado es el mismo que id)
 * @param contextWindow tamaño de la ventana de contexto(token numero,Predeterminado 4096)
 * @param supportsVision Ya sea para admitir información visual
 * @param supportsTools Ya sea para admitir llamadas a herramientas(function calling)
 */
@Serializable
data class PluginModel(
    val id: String,
    val displayName: String = id,
    val contextWindow: Int = 4096,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
)
