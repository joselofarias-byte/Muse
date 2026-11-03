package io.zer0.muse.channel

/**
 * Dar forma al texto de las respuestas del canal: dividir en segmentos + suprimir líneas en blanco.
 *
 * Por qué lo necesita: los cuerpos de mensajes de las plataformas de chat grupal y de chat único son en su mayoría**texto plano**, en la salida del modelo
 * markdown Línea en blanco de párrafo (`\n\n`) se mostrará como un espacio en blanco grande; mientras que el robot oficial QQ
 * El máximo permitido para un mensaje entrante es **5 veces**La respuesta pasiva es el momento perfecto para dividir una respuesta larga en varios mensajes.
 * Se lee como el texto continuo de una persona real y, por cierto, se elimina el problema de las líneas en blanco.
 *
 * Realice únicamente cirugía plástica conservadora y no reescriba el contenido:
 *  - No cambie las etiquetas de rebajas (`**`,`#`, bloque de código) para evitar romper fragmentos de código
 *  - No truncar el número de palabras (el truncamiento hará que la semántica se rompa en media oración, dejando que la plataforma se limite a sí misma)
 */
object ChannelReplyFormatter {

    /** QQ Oficial: Un mensaje entrante se puede responder pasivamente hasta 5 veces. Si excede el límite, se considerará por encima del límite. */
    const val MAX_SEGMENTS: Int = 5

    /**
     * Divida una respuesta en varios mensajes.
     *
     * Priorizar la segmentación por líneas en blanco (párrafos), si el número de párrafos excede [max] Al fusionar la cola con el último párrafo,
     * en lugar de cortar con fuerza el texto —— Un corte brusco la romperá en la mitad de la oración, lo cual es más desagradable que cortar una línea.
     *
     * @return Segmentos divididos; cuando todos están en blanco, se devuelve una lista vacía (la persona que llama determina que no hay contenido para enviar)
     */
    fun segments(text: String, max: Int = MAX_SEGMENTS): List<String> {
        val blocks = text
            .replace("\r\n", "\n")
            .split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        // Exportación única: cuando el número de párrafos excede el límite superior, la cola se fusionará en lugar de cortar el texto. ——
        // Un corte brusco la romperá en la mitad de la oración, lo cual es más desagradable que cortar una línea.
        return when {
            max <= 0 || blocks.isEmpty() -> emptyList()
            blocks.size <= max -> blocks
            else -> blocks.take(max - 1) + blocks.drop(max - 1).joinToString("\n")
        }
    }

    /**
     * Suprime las líneas en blanco consecutivas y los espacios en blanco iniciales y finales dentro del párrafo.
     *
     * Sólo se utiliza en el camino de "transmisión directa sin segmentación"; Después de dividirlo en segmentos, cada segmento ya está limpio.
     * Esto es principalmente para evitar residuos en respuestas de un solo párrafo. `\n\n\n` Esta es una línea en blanco que ni siquiera le importa al modelo en sí.
     */
    fun flatten(text: String): String = text
        .replace("\r\n", "\n")
        .replace(Regex("\n{2,}"), "\n")
        .trim()

    /**
     * La forma final de la respuesta de párrafo completo: aplanar un solo párrafo, dividir varios párrafos.
     *
     * Corresponde a la persona que llama decidir si envía un mensaje o varios mensajes para evitar escribir una serie de juicios en dos lugares.
     */
    fun plan(text: String, max: Int = MAX_SEGMENTS): List<String> {
        val segs = segments(text, max)
        return if (segs.size <= 1) segs.map { flatten(it) }.filter { it.isNotEmpty() } else segs
    }
}
