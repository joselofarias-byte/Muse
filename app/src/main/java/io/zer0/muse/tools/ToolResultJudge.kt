package io.zer0.muse.tools

/**
 * Determinante del éxito o fracaso de la ejecución de la herramienta(P2-22).
 *
 * Extraído de ChatTaskCardCoordinator,Proporciona tarjetas de tareas, subagentes, tareas programadas y pruebas.**compartir la misma implementación**:
 * Cada ruta de ejecución proporciona exactamente la misma conclusión de éxito o fracaso para la misma cadena de retorno de herramienta.,evitar
 * "El subagente utiliza un conjunto de subcadenas chinas y la tarjeta de tareas utiliza otro conjunto", lo que provoca una desviación de juicio.
 *
 * Acuerdo:Cuando la herramienta falla, las devoluciones comienzan con una descripción del error(o Error:/[tiempo de espera] etc. prefijo del paquete)cadena;
 * El fracaso estructurado es {"error": "..."} JSON(stdout paso a través).
 *
 * v2.6.2 Corrección(Auditoría de usuario):Implementación original"error/falló/incapaz/esta vacio/No disponible"Espere la coincidencia incondicional de prefijos de palabras desnudas en chino,
 * voluntad**contenido normal**mal juzgado como fracaso —— read_file documentos leídos,web_fetch Artículos capturados, fragmentos de resultados de búsqueda
 * Simplemente comienza con estas palabras.(Como "Guía de manejo de errores" y "Aprendizaje fallido".),será sentenciado FAILED,La tarjeta de tarea muestra el fracaso y da
 * Inyección de modelo"[La llamada a la herramienta no pudo iniciar] Por favor corrígelo y vuelve a intentarlo.",Provoca reintentos innecesarios.
 *
 * Ahora estratificado por confiabilidad:
 *  - **fuerte señal de falla**(marca del paquete `[error]/[tiempo de espera]`,`error:`,y mensajes de error dedicados para la síntesis de varias palabras)Fracaso incondicional;
 *  - **palabras débiles de fracaso**(error/falló/incapaz/esta vacio/No configurado/Palabras habladas de un carácter como no disponible):Sólo si la devolución es**etiqueta corta**
 *    (longitud ≤ [WEAK_PREFIX_MAX_CHARS])o la palabra va seguida de un separador(dos puntos/paréntesis/coma, etc.)El fracaso se juzga sólo cuando;
 *    texto largo(Texto del documento/texto de la página web)Incluso si comienza con estas palabras,También manéjelo como contenido normal.
 */
internal object ToolResultJudge {

    private val BRACKET_FAILURE_MARKERS = listOf(
        "[tiempo de espera]",
        "[interrumpir]",
        "[La salida de la herramienta está truncada",
        "[error]",
        "[falló]",
        "[Cancelar]",
    )
    private val STRUCTURED_ERROR_PATTERN = Regex("\"error\"\\s*:\\s*\"[^\"]")

    /**
     * palabras débiles de fracaso:Gran ambigüedad al aparecer solo(Probablemente solo el comienzo del texto normal.),Necesidad de cooperar"texto corto"o"seguido por separador"Se consideró un fracaso.
     */
    private val WEAK_FAILURE_PREFIXES = listOf(
        "error", "falló", "anormal", "tiempo de espera", "Cancelar", "No configurado", "esta vacio", "No disponible", "incapaz", "No se puede", "No compatible",
    )

    /**
     * Prefijo de error fuerte:Mensaje de error especial para síntesis de varias palabras,Semántica clara,Es casi imposible iniciar un texto normal.
     */
    private val STRONG_FAILURE_PREFIXES = listOf(
        "La herramienta no existe", "Excepción de ejecución de herramienta", "Falló el análisis de parámetros", "skill excepción de ejecución",
        "Anulación de ruta", "Parámetro faltante", "Archivo demasiado grande", "El archivo no existe", "desconocido skill",
        "URL debe", "Sesión no encontrada", "sub-asistente",
    )

    /** Las palabras de error débiles van seguidas inmediatamente de estos delimitadores.,Explique que es una etiqueta y no parte del texto. */
    private val WEAK_DELIMITERS = setOf(
        ':', ':', '(', '(', '[', '【', ',', ',', ',', '.', '!', '!', '?', '?',
    )

    /** Umbral de etiqueta corta:Sólo las devoluciones que no excedan esta longitud podrán ser juzgadas con un prefijo de palabra de error débil. */
    private const val WEAK_PREFIX_MAX_CHARS = 120

    /** Determine si el resultado de la ejecución de la herramienta es exitoso. */
    fun isSuccess(result: String): Boolean {
        val text = result.trimStart()
        if (text.startsWith("error:", ignoreCase = true)) return false
        // Bracket markers are the production shapes for orchestrator-synthesised
        // terminal states; the previous bare-word list never matched them.
        if (BRACKET_FAILURE_MARKERS.any { text.startsWith(it) }) return false
        if (STRONG_FAILURE_PREFIXES.any { text.startsWith(it) }) return false
        if (startsWithWeakFailure(text)) return false
        // Structured failures: {"error": "..."} payloads from hooks and MCP bridges.
        if (text.startsWith("{") && STRUCTURED_ERROR_PATTERN.containsMatchIn(text.take(200))) {
            return false
        }
        return true
    }

    /**
     * Determinación de palabras de fracaso débil:palabra al principio,y(El todo es una etiqueta corta o una palabra seguida de un separador.).
     *
     * Devuelto cuando un texto largo comienza con una palabra de error débil false,Regrese al procesamiento normal de contenido.
     */
    private fun startsWithWeakFailure(text: String): Boolean = WEAK_FAILURE_PREFIXES.any { prefix ->
        if (!text.startsWith(prefix)) return@any false
        text.length <= WEAK_PREFIX_MAX_CHARS ||
            text.getOrNull(prefix.length)?.let { it in WEAK_DELIMITERS } ?: true
    }
}
