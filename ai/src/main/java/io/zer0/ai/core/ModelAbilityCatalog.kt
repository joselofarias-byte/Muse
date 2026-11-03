package io.zer0.ai.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Biblioteca de capacidades de modelo(`model-catalog`).
 *
 * poner"Qué puede hacer el modelo"Convergiendo de la codificación rígida dispersa a **un dato** —— La situación actual es
 * `ProviderCompat` lista blanca de hosts,`ModelRegistry` reglas de prefijo de nombre,`KnownModels`
 * Coexisten tres cadenas independientes de diccionarios,Un mismo modelo puede llevar a conclusiones diferentes. Aquí unificados en una sola verdad.
 *
 * El nombre de los campos se alinea con el directorio ascendente,Conveniente para la conducción mutua.;Las fuentes de datos se pueden incorporar JSON,También puede ser reemplazado por un directorio remoto en el futuro.
 * (quedarse [schemaVersion] / [publishedAt] Dos aperturas).
 */
@Serializable
data class ModelCatalog(
    val schemaVersion: Int = 1,
    val publishedAt: String = "",
    /** providerId → (modelId → entrada de habilidad). */
    val providers: Map<String, Map<String, ModelCatalogEntry>> = emptyMap(),
) {
    /** Verifique las entradas de capacidad de un modelo.;provider O el modelo no existe y devuelve nulo. */
    fun entryOf(providerId: String, modelId: String): ModelCatalogEntry? = providers[providerId]?.get(modelId)

    /**
     * Busque globalmente por ID de modelo (primer resultado entre proveedores).
     *
     * Se utiliza en escenarios de proveedores autoconstruidos por el usuario. —— Su ID de proveedor se genera en tiempo de ejecución (provider-xxx),
     * No coincide con el nombre lógico del proveedor en el directorio. En este caso, prevalecerá el nombre del modelo (único globalmente ascendente).
     * índice perezoso,No participa en la serialización.,Construido cuando se consultó por primera vez.
     */
    fun entryByModelId(modelId: String): ModelCatalogEntry? = byModelIdIndex[modelId]

    private val byModelIdIndex: Map<String, ModelCatalogEntry> by lazy {
        buildMap {
            for (models in providers.values) {
                for ((id, entry) in models) putIfAbsent(id, entry)
            }
        }
    }

    /** Todas las entradas de modelos de un determinado proveedor. */
    fun modelsOf(providerId: String): Map<String, ModelCatalogEntry> = providers[providerId] ?: emptyMap()
}

/** Retrato de capacidades de un solo modelo. */
@Serializable
data class ModelCatalogEntry(
    val name: String = "",
    val context: Long? = null,
    val maxOutput: Long? = null,
    val image: Boolean? = null,
    val reasoning: Boolean? = null,
    /** Especificaciones de llamada de herramienta —— Contiene"dialecto",En lugar de adivinar por el prefijo de nombre/host. */
    val toolUse: ToolUseSpec? = null,
    /** Engranajes de inferencia soportados(Como por ejemplo low/medium/high/xhigh/max);Vacío significa que no se admite el equipo de inferencia. */
    val thinkingLevels: List<String> = emptyList(),
    val defaultThinkingLevel: String? = null,
    /** Retrato de compatibilidad(Reemplazo de la lista blanca de hosts codificados). */
    val compat: CompatProfile? = null,
)

/** Especificaciones de llamada de herramienta. */
@Serializable
data class ToolUseSpec(
    val supportsTools: Boolean = false,
    /** dialecto de llamada de herramientas:openai / anthropic / gemini. */
    val dialect: String = "openai",
    /** Formato de devolución de resultados de la herramienta:message / tool-result. */
    val toolResultFormat: String = "message",
)

/** Retrato de compatibilidad. */
@Serializable
data class CompatProfile(
    val thinkingFormat: String? = null,
    val reasoningProfile: String? = null,
)

/**
 * Entrada de carga de la biblioteca de capacidades de modelo.
 *
 * Devolver si falla el análisis**biblioteca vacia**sin lanzar una excepción —— Cuando la persona que llama lee la biblioteca vacía, naturalmente recurrirá a su propia lógica.,
 * Evite que un dato incorrecto destruya todo el enlace(con aguas arriba"No mantenerte viejo snapshot"misma orientación).
 */
object ModelCatalogLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadOrEmpty(raw: String?): ModelCatalog {
        if (raw.isNullOrBlank()) return ModelCatalog()
        return runCatching { json.decodeFromString(ModelCatalog.serializer(), raw) }
            .getOrElse { ModelCatalog() }
    }
}
