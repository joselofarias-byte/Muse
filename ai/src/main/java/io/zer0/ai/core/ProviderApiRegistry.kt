package io.zer0.ai.core

/**
 * Registro de protocolo(`api` cuerda → Implementar mapeo).
 *
 * poner"¿Qué acuerdo adoptar?"Convergiendo de la rama dura de enumeración a **cadena + mapa**:Aceptará nuevos acuerdos en el futuro.
 * (Responses / Propiedad privada de cada empresa.)Solo agrega una clave,No es necesario cambiar las enumeraciones y todo `when` rama.
 * [ProviderType] Mantener como capa de compatibilidad,Convierta desde y hacia la cadena de protocolo aquí.
 *
 * protocolos conocidos(Igual que el directorio ascendente):
 *  - `openai-completions` → [ProviderType.OPENAI](Compatible con todos los tipos de conexión directa de OpenAI/tránsito)
 *  - `openai-responses`   → [ProviderType.OPENAI_RESPONSES]
 *  - `anthropic-messages` → [ProviderType.ANTHROPIC]
 *  - `gemini`             → [ProviderType.GEMINI]
 */
object ProviderApiRegistry {

    const val OPENAI_COMPLETIONS = "openai-completions"
    const val OPENAI_RESPONSES = "openai-responses"
    const val ANTHROPIC_MESSAGES = "anthropic-messages"
    const val GEMINI = "gemini"

    /** Todas las cadenas de protocolo conocidas. */
    val known: Set<String> = setOf(OPENAI_COMPLETIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES, GEMINI)

    /** cadena de protocolo → Capa de compatibilidad [ProviderType];Devuelve nulo si no se reconoce. */
    fun typeOf(api: String): ProviderType? = when (api.trim().lowercase()) {
        OPENAI_COMPLETIONS -> ProviderType.OPENAI
        OPENAI_RESPONSES -> ProviderType.OPENAI_RESPONSES
        ANTHROPIC_MESSAGES -> ProviderType.ANTHROPIC
        GEMINI -> ProviderType.GEMINI
        else -> null
    }

    /** [ProviderType] → Cadena de protocolo. */
    fun apiOf(type: ProviderType): String = when (type) {
        ProviderType.OPENAI -> OPENAI_COMPLETIONS
        ProviderType.OPENAI_RESPONSES -> OPENAI_RESPONSES
        ProviderType.ANTHROPIC -> ANTHROPIC_MESSAGES
        ProviderType.GEMINI -> GEMINI
    }
}
