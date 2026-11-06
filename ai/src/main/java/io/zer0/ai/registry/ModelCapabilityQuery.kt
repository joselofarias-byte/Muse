package io.zer0.ai.registry

import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ProviderCompat

/**
 * M2.2/M2.8: Resultados de tres estados para consultas de capacidad.
 *
 * - [SUPPORTED]:Registro/Soporte de verificación de capa de compatibilidad.
 * - [UNSUPPORTED]:La verificación del registro no es compatible(Si un modelo de solo texto solicita información visual).
 * - [UNKNOWN]:Modelo no alcanzado en el registro(nuevo modelo/Nombre de implementación privada),La persona que llama debe hacer
 *   capability preflight O adoptar una estrategia conservadora,En lugar de adivinar el nombre del modelo y luego poner el upstream
 *   El error se lanza al usuario.
 */
enum class CapabilitySupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

/**
 * M2.2: Instantánea de capacidad del modelo — texto/Entrada visual, herramientas, razonamiento, streaming, no streaming, salida estructurada
 * Todas las distinciones explícitas,Devoluciones de modelos desconocidos en cada dimensión [CapabilitySupport.UNKNOWN].
 *
 * app El lado sólo se puede leer a través de esta fachada.,No analices los nombres de los modelos tú mismo(con ModelRegistry
 * La interfaz de consulta pública en conjunto constituye el único límite de capacidad de M2.1).
 */
data class ModelCapabilitySnapshot(
    val modelId: String,
    val textInput: CapabilitySupport,
    val visionInput: CapabilitySupport,
    val toolCalling: CapabilitySupport,
    val reasoning: CapabilitySupport,
    val streaming: CapabilitySupport,
    val nonStreaming: CapabilitySupport,
    val structuredOutput: CapabilitySupport,
    /** Si el registro llega al modelo(UNKNOWN fuente de juicio,Facilita las decisiones de registro y verificación previa). */
    val known: Boolean,
)

/**
 * M2.8: Fachada de verificación previa de capacidad.
 *
 * @param modelId modelo ID(Se permiten prefijos de agregación,Internamente, la misma lógica de eliminación de prefijos que ModelRegistry)
 * @param compat Provider Capa de compatibilidad(Restricciones a nivel de parámetro, como salida estructurada);Dimensión correspondiente al pasar nulo
 *   Regresar [CapabilitySupport.UNKNOWN] En lugar de adivinar.ProviderCompat por
 *   [ProviderCompatRules.resolve] Derivado de ProviderType/host/modelId,
 *   Todos los tipos de proveedores integrados producen objetos de compatibilidad —— Por lo tanto, compat no nulo significa
 *   Este Proveedor tiene canales de streaming.
 * @param providerSupportsNonStreaming Provider Ya sea realizado completeText
 *   (Provider.completeText Lanzar por defecto UnsupportedOperationException,por la persona que llama
 *   Pasar al estado de implementación real.);null Indica que la persona que llama no proporcionó esta información.
 */
object ModelCapabilityQuery {

    private const val TAG = "ModelCapability"

    fun snapshot(
        modelId: String,
        compat: ProviderCompat? = null,
        providerSupportsNonStreaming: Boolean? = null,
        providerId: String? = null,
    ): ModelCapabilitySnapshot {
        val definitions = ModelRegistry.resolveDefinitions(modelId)
        val catalogEntry = ModelRegistry.catalogEntryOf(modelId, providerId)
        val registryKnown = definitions.isNotEmpty()
        val known = registryKnown || catalogEntry != null
        val abilities = definitions.flatMap { it.abilities }.toSet()
        val inputModalities = definitions.flatMap { it.inputModalities }.toSet()
        // v2.x(Comentarios del usuario n.º 3-1): directorio image=false lo único correcto**Proveedor de lógica incorporado**Es un juicio autorizado.
        // Proveedores creados por el usuario (incluidas estaciones de transferencia,providerId Es el proveedor xxx generado en tiempo de ejecución y no está en el directorio integrado)
        // El mismo nombre de modelo que está conectado puede tener capacidades reales que no concuerdan con las entradas integradas (la estación de transferencia a menudo asigna nombres de texto plano a soportes visuales).
        // aguas arriba). anteriormente image=false Juzgar directamente UNSUPPORTED bloqueará estos modelos y los usuarios no podrán usar la visión.
        // Ahora cambiado a:Cuando no es un proveedor integrado, se degradará a DESCONOCIDO y se dejará a la decisión de anulación/declaración ascendente del usuario, y ya no se le bloqueará la autoridad.
        val catalogProviderTrusted = ModelRegistry.isBuiltInCatalogProvider(providerId)
        val visionInput = when (catalogEntry?.image) {
            true -> CapabilitySupport.SUPPORTED
            false -> if (catalogProviderTrusted) {
                CapabilitySupport.UNSUPPORTED
            } else {
                io.zer0.common.Logger.d(
                    TAG,
                    "visionInput degradado a UNKNOWN(Los proveedores no integrados no están bloqueados) | model=$modelId | provider=${providerId ?: "?"}",
                )
                triState(registryKnown, "image" in inputModalities)
            }
            null -> triState(registryKnown, "image" in inputModalities)
        }
        val toolCalling = when (catalogEntry?.toolUse?.supportsTools) {
            true -> CapabilitySupport.SUPPORTED
            false -> CapabilitySupport.UNSUPPORTED
            null -> triState(registryKnown, ModelAbility.TOOL in abilities)
        }
        val reasoning = when (catalogEntry?.reasoning) {
            true -> CapabilitySupport.SUPPORTED
            false -> CapabilitySupport.UNSUPPORTED
            null -> triState(registryKnown, ModelAbility.REASONING in abilities)
        }
        return ModelCapabilitySnapshot(
            modelId = modelId,
            known = known,
            textInput = if (known) CapabilitySupport.SUPPORTED else CapabilitySupport.UNKNOWN,
            visionInput = visionInput,
            toolCalling = toolCalling,
            reasoning = reasoning,
            streaming = if (compat != null) CapabilitySupport.SUPPORTED else CapabilitySupport.UNKNOWN,
            nonStreaming = when (providerSupportsNonStreaming) {
                true -> CapabilitySupport.SUPPORTED
                false -> CapabilitySupport.UNSUPPORTED
                null -> CapabilitySupport.UNKNOWN
            },
            structuredOutput = triStateCompat(compat) { it.supportsJsonMode },
        )
    }

    /** capacidades booleanas simples → tres estados(falta de registro → UNKNOWN). */
    private fun triState(known: Boolean, supported: Boolean): CapabilitySupport = when {
        !known -> CapabilitySupport.UNKNOWN
        supported -> CapabilitySupport.SUPPORTED
        else -> CapabilitySupport.UNSUPPORTED
    }

    /**
     * Provider habilidad de nivel → Tres estados.Provider El protocolo lateral admite tanto streaming como no streaming de forma predeterminada.
     * (Se implementan todos los streamChat/completeText de los proveedores integrados.),La diferencia se concentra en
     * json_mode Soporte a nivel de parámetros,por [ProviderCompat] proporcionar;DESCONOCIDO sin compat.
     */
    private fun triStateCompat(compat: ProviderCompat?, supported: (ProviderCompat) -> Boolean): CapabilitySupport = when {
        compat == null -> CapabilitySupport.UNKNOWN
        supported(compat) -> CapabilitySupport.SUPPORTED
        else -> CapabilitySupport.UNSUPPORTED
    }
}
