package io.zer0.muse.data.preset

import io.zer0.ai.core.FreeModelConfig
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility

/**
 * P2-5: SiliconFlow Una lista completa de modelos gratuitos en la plataforma.
 *
 * fondo:
 *  - SiliconFlow en `https://api.siliconflow.cn/v1` Proporciona puntos finales compatibles con OpenAI,
 *    Y etiquetar algunos modelos de código abierto. `:free` sufijo(Gratis disponible,Pero normalmente hay límites de RPM/QPS).
 *  - Después de que el usuario obtenga la apiKey de SiliconFlow,Muchas veces porque no lo sé"¿Qué modelos son gratuitos?"Y prueba uno por uno,
 *    Mala experiencia. Este objeto está preestablecido con una lista de modelos gratuitos y disponibles actualmente conocidos.,
 *    en ProviderEditPage(Cuando baseUrl llega a Siliconflow.cn)Proporciona entrada de "relleno de modelos gratuito con un solo clic".
 *
 * diseño:
 *  - Sólo modelos récord [Model.id] y habilidades básicas(supportsVision / abilities / contextWindow),
 *    No se incluye información sobre precios.(Las estrategias de plataforma cambiarán,Sujeto al sitio web oficial.).
 *  - [PROVIDER_ID] Corregido usando el proveedor predeterminado id,Asegúrese de que la etapa predeterminada Model.providerId no esté vacía;
 *    Después de que el usuario se guarde como un proveedor real,ModelRegistry.enrich / ProviderConfig Al persistir
 *    Será sincronizado por Provider.id providerId,No hay necesidad de preocuparse aquí.
 *  - supportsVision Sólo para modelos que realmente admiten la entrada de imágenes(Como la serie Qwen2.5-VL)conjunto true;
 *    Esta lista no contiene actualmente variantes de VL,Por lo tanto todos son false,Reservar campos para su posterior ampliación.
 *  - supportsTools Pase [ModelAbility.TOOL] marca;DeepSeek-R1 es un modelo de inferencia pura,
 *    marca [ModelAbility.REASONING] En lugar de abrir HERRAMIENTA.
 *
 * mantenimiento:
 *  - La lista de modelos cambiará según la estrategia de la plataforma SiliconFlow.(Ajuste de límite de velocidad recién agregado/eliminado),
 *    Comparación periódica `https://siliconflow.cn/zh-cn/models` Solo revíselo una vez.
 */
object SiliconFlowFreeModels {

    /** SiliconFlow Puntos finales compatibles con la plataforma OpenAI. */
    const val BASE_URL = "https://api.siliconflow.cn/v1"

    /** Reparación del proveedor predeterminado id(Consistente con PresetProviders.siliconFlowFree). */
    const val PROVIDER_ID = FreeModelConfig.FREE_PROVIDER_ID

    /**
     * SiliconFlow Lista de modelos disponibles actualmente de forma gratuita en la plataforma(No incluido en el precio,Cambiará con la estrategia de la plataforma).
     *
     * Descripción:
     *  - contextWindow Tome la longitud del contexto oficial publicada por cada fabricante.(unidad:token);
     *    Si hay alguna ambigüedad, tome el valor menor de manera conservadora.,Evite la interfaz de usuario"El contexto ocupa un pequeño círculo."La pantalla es demasiado grande.
     *  - no contiene maxOutputTokens(Déjelo en blanco para permitir que el tiempo de ejecución se declare por upstream/modelos).
     *  - entrada/Valor predeterminado modal de salida ["text"](modelo no visual),El modelo visual se complementa bajo demanda. "image".
     */
    val MODELS: List<Model> = listOf(
        // ── Qwen2.5 Instruct Serie(apoyo tool calling,sin visión)──
        Model(
            id = "Qwen/Qwen2.5-7B-Instruct",
            name = "Qwen2.5 7B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        Model(
            id = "Qwen/Qwen2.5-14B-Instruct",
            name = "Qwen2.5 14B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        Model(
            id = "Qwen/Qwen2.5-32B-Instruct",
            name = "Qwen2.5 32B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        Model(
            id = "Qwen/Qwen2.5-72B-Instruct",
            name = "Qwen2.5 72B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        // ── DeepSeek Serie(V2.5 apoyo tool calling;R1 es un modelo de inferencia pura)──
        Model(
            id = "deepseek-ai/DeepSeek-V2.5",
            name = "DeepSeek V2.5 (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        Model(
            id = "deepseek-ai/DeepSeek-R1",
            name = "DeepSeek R1 (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 65_536,
            // R1 para modelo de inferencia,No compatible tool calling(Las habilidades vacías no ingresan a la rama HERRAMIENTA)
            abilities = setOf(ModelAbility.REASONING),
        ),
        // ── espectro de sabiduría GLM-4-9B-Chat(apoyo tool calling,sin visión)──
        Model(
            id = "THUDM/glm-4-9b-chat",
            name = "GLM-4 9B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        // ── Meta Llama 3.1 8B Instruct(apoyo tool calling,sin visión)──
        Model(
            id = "meta-llama/Meta-Llama-3.1-8B-Instruct",
            name = "Llama 3.1 8B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 131_072,
            abilities = setOf(ModelAbility.TOOL),
        ),
    )
}
