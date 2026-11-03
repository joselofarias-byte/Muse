package io.zer0.muse.util

/**
 * v1.131: Administre de forma centralizada las constantes públicas regulares dentro de la aplicación.
 *
 * Originalmente, las siguientes reglas regulares se definían repetidamente en varios archivos.,Las modificaciones deben sincronizarse en varios lugares.:
 *  - Correo electrónico regular:ToolRegistry.kt(variables locales) + AccountScreen.kt(Constantes de nivel de archivo)
 *  - think etiqueta regular:ChatStreamCoordinator.kt + ThinkTagTransformer.kt + GroupChatScheduler.kt
 *  - mood etiqueta regular:MoodTagTransformer.kt + GroupChatScheduler.kt
 *
 * Nota:EmotionTracking.kt de `<mood value="..." label="...">...</mood>` Es un tipo de atributo antiguo. schema,
 * con el contenido aquí `<mood>...</mood>` No compatible,Por lo tanto no fusionado,Mantenga EmotionTracking definido de forma independiente.
 */
object MusePatterns {

    /**
     * Verificación del formato de correo electrónico(sencillo regular,Implementación completa no RFC 5322).
     * usado para:Verificación del destinatario de la herramienta de correo electrónico y verificación del formulario de registro de inicio de sesión de la cuenta.
     */
    val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /**
     * partido `<think>...</think>` bloquear(no codicioso,a través de líneas `[\s\S]`,ignorar caso).
     * grupo de captura 1 = think contenido.
     *
     * usado para:Streaming de tiras de respuesta, bloques de pensamiento,Transformer Pipeline, programador de chat grupal.
     */
    val THINK_TAG_REGEX = Regex("""<think(?:ing)?>([\s\S]*?)</think(?:ing)?>""", RegexOption.IGNORE_CASE)

    /**
     * partido `<mood>...</mood>` o `[mood]...[/mood]` bloquear(no codicioso,a través de líneas `[\s\S]`,ignorar caso).
     * grupo de captura 1 = mood contenido.
     *
     * usado para:MoodTagTransformer embalaje/Desmontaje, desmontaje del programador de chat grupal.
     *
     * v1.0.28 Reparación: Coincide con ambos soportes angulares `<mood>` y corchetes `[mood]` Dos formatos.
     * Razón: prompt Requisitos de plantilla `<mood>`,pero parte LLM(Especialmente la serie Deepseek.)saldrá `[mood]`,
     * Hace que los bloqueos del estado de ánimo no se eliminen,Mostrar directamente a los usuarios,Seguido por [PASS] También permanece como texto.
     */
    val MOOD_TAG_REGEX = Regex(
        """(?:<mood>|\[mood\])([\s\S]*?)(?:</mood>|\[/mood\])""",
        RegexOption.IGNORE_CASE,
    )
}
