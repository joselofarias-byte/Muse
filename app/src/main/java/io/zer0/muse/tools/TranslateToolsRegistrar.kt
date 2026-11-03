package io.zer0.muse.tools

/**
 * P1-3b Dedomaining: registrador de herramientas de traducción.
 *
 * Registrarse traducir. La implementación está ubicada en [TranslateToolsImpl.kt].
 */
class TranslateToolsRegistrar(
    private val toolRegistry: ToolRegistry,
) {
    private val impl = TranslateToolsImpl()

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "translate",
                // v1.0.75 fix (Revisión de herramientas 02): Escenario de activación suplementario(Se utiliza sólo cuando el usuario solicita explícitamente la traducción.)
                description = "Traducir texto(Sigue el proceso de traducción independiente,La calidad es más estable que las respuestas directas.). El usuario solicita explícitamente la traducción./Se llama al cambiar de idioma. Devolver resultados de traducción,No incluye explicación.",
                parameters = mapOf(
                    "text" to "Requerido,Texto original a traducir",
                    "target_language" to "Requerido,idioma de destino(chino/English/日本語 espera)",
                    "source_language" to "Opcional,idioma de origen,Si se deja en blanco, se detectará automáticamente.",
                    "style" to "Opcional,Universales/academico/Negocios/coloquial(estilo de traducción;polaco/Describa los requisitos concisos a la brevedad.)",
                ),
                required = setOf("text", "target_language"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execTranslate(args) }
    }
}
