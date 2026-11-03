package io.zer0.muse.ui.speech

/**
 * Cortadora de texto — Corte el texto largo en trozos pequeños adecuados para la lectura TTS según la puntuación.
 *
 * Puntos de implementación:
 *  - Utilice la búsqueda atrás normal `(?<=[. ! ? ,,:;.!?:,\n])` Dividido por puntuación(La puntuación permanece en la pieza delantera.)
 *  - estrategia de acumulación:El segmento actual + el siguiente segmento no excederán [maxChunkLength] Solo sigue acumulando,Cortar nuevas rodajas después de exceder
 *  - Cortar con fuerza el bolsillo:Para párrafos largos sin puntuación chunked(maxChunkLength) Cortar por la fuerza,Evite una sola sección que sea demasiado larga para reventar la síntesis TTS
 *
 * compensaciones de diseño:maxChunkLength = 150 Teniendo en cuenta tanto el "retraso de la síntesis" como la "naturalidad de la segmentación de la oración"
 *  - demasiado corto(<50):¿Cuántas palabras por pieza?,Alta sobrecarga de síntesis,Oraciones rotas
 *  - demasiado tiempo(>300):Síntesis de un solo chip en segundos,Retraso obvio en la primera palabra,Se produjo un error y es necesario reorganizar la película.
 */
object TextChunker {

    /** El número máximo predeterminado de caracteres en un solo chip. */
    const val DEFAULT_MAX_CHUNK_LENGTH = 150

    /**
     * Puntuación al final de la oración(Chino e inglés + salto de línea).
     * lookbehind `(?<=[...])` Coincidir con la posición de ancho cero después de la puntuación,String.split Cortar en esta ubicación,
     * La puntuación en sí permanece en el párrafo anterior.(Haga una pausa natural al leer en voz alta).
     */
    private val sentenceEndRegex = Regex("(?<=[. ! ? ,,:;.!?:,\n])")

    /**
     * poner [text] Una lista de piezas cortadas en trozos adecuados para la lectura TTS.
     *
     * algoritmo:
     *  1. usar [sentenceEndRegex] Dividido por puntuación,obtener el párrafo original(Se ha añadido puntuación al final del párrafo.)
     *  2. Recorrer párrafos para acumular:El segmento actual + el siguiente segmento excede [maxChunkLength] → Enviar pieza actual,nueva película
     *  3. Un solo segmento excede [maxChunkLength](Párrafo largo sin puntuación)→ usar [chunked] corte duro
     *
     * @param text texto original(Se recomienda pasar primero. [TtsManager.stripMarkdown] limpiar Markdown)
     * @param maxChunkLength Número máximo de caracteres en un solo chip
     * @return lista de fragmentos(El texto vacío/todos los espacios en blanco devuelve una lista vacía)
     */
    fun chunk(text: String, maxChunkLength: Int = DEFAULT_MAX_CHUNK_LENGTH): List<String> {
        if (text.isBlank()) return emptyList()
        // Dividido por puntuación(La puntuación permanece en la pieza delantera.),Filtrar espacios en blanco(Evite los párrafos vacíos causados ​​por la puntuación continua)
        val rawSegments = text.split(sentenceEndRegex).filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (segment in rawSegments) {
            // estrategia de acumulación:El segmento actual + el siguiente segmento excede el límite superior,Envíe la película actual primero y luego abra una nueva película.
            if (current.isNotEmpty() && current.length + segment.length > maxChunkLength) {
                result.add(current.toString())
                current.clear()
            }
            // Cortar con fuerza el bolsillo:segmento único(Párrafo largo sin puntuación)Superar el límite superior,Utilice trozos para forzar el corte
            if (segment.length > maxChunkLength) {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                }
                segment.chunked(maxChunkLength).forEach { result.add(it) }
            } else {
                current.append(segment)
            }
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }
}
