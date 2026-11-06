package io.zer0.muse.tools

import android.content.Context

/**
 * P1-3b Deslocalización:TTS Registrador de herramientas.
 *
 * Regístrese hablar_texto. La implementación está ubicada en [TtsToolsImpl.kt].
 */
class TtsToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = TtsToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "speak_text",
                // v1.0.75 fix (Revisión de herramientas 02): Escenarios de activación complementarios e instrucciones de devolución
                description = "Utilice el sistema TTS para leer el texto en voz alta.(Requisitos del usuario'leer en voz alta/Léelo/transmitir'llamado cuando). Devuelve un texto de confirmación de si la lectura fue exitosa.,Los archivos de audio no se devuelven.",
                parameters = mapOf(
                    "text" to "Requerido,Texto para leer en voz alta",
                    "language" to "Opcional,código de idioma,Como por ejemplo zh-CN/en-US,Sigue el sistema por defecto",
                    "rate" to "Opcional,Multiplicador de velocidad de voz,Como por ejemplo 0.8/1.0/1.5,Predeterminado 1.0",
                ),
                required = setOf("text"),
                category = "built-in",
                parameterTypes = mapOf("rate" to "number"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.speak(args) }
    }
}
