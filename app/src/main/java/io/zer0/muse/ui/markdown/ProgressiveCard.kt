package io.zer0.muse.ui.markdown

/**
 * Limitación progresiva del renderizado de tarjetas de streaming.
 *
 * ## ¿Por qué se necesita la "longitud de la instantánea"?
 *
 * Tarjeta de contenido enriquecido (svg / html / chart) deben generarse y mostrarse al mismo tiempo durante la transmisión, y cada vez que se entregue contenido nuevo a
 * WebView Todo de una vez `loadDataWithBaseURL`: Vuelva a analizar toda la página HTML, vuelva a ejecutar el script de la tarjeta,
 * También es necesario reinicializar el gráfico. Este coste es proporcional al "número de actualizaciones" y casi no tiene nada que ver con "unos cuantos personajes más cada vez".
 *
 * La implementación anterior cuantificaba en pasos fijos (reemplazados cada 120 caracteres), por lo que una tarjeta de longitud L era
 * Actualizar durante la generación `L / 120` De segunda categoría. Una tarjeta gráfica de 8 KB equivale a 68 recargas de página completa,
 * Traiga siempre la misma copia de ~25 KB de estilos y scripts —— Esta es la fuente principal de que "toda la conversación se pone caliente tan pronto como aparece la tarjeta".
 *
 * ## Calibre actual: crecimiento geométrico
 *
 * La duración de la instantánea es sólo**doble engranaje**Empujar hacia arriba:`step, 2*step, 4*step, ...`.
 * Entonces, la tarjeta de 8 KB se actualizó solo 7 veces y la cantidad total de HTML entregada a WebView fue de
 * `Σ L_k ≈ L²/(2*step)` hasta `≈ 2L`(unas 17 veces).
 *
 * Visualmente, el incremento generado por cada actualización es mayor, pero el intervalo entre puntos de actualización también es mayor. —— Por "generar"
 * Cuando hay que esperar, los beneficios de la actualización continua en pequeños pasos son mucho menores que el costo de recarga que conlleva.
 *
 * ## frontera
 *
 * - La longitud del contenido es inferior a uno. `step` Devuelve sin cambios: las tarjetas cortas no deben truncarse para quedar vacías.
 * - La longitud de la instantánea nunca excede la longitud del contenido, por lo que nunca se devuelve un prefijo demasiado largo.
 * - Escenarios sin transmisión ([progressive] = false) No pases por aquí, utiliza el contenido completo directamente.
 */
internal object ProgressiveCard {

    /** Si el contenido es más corto que esta longitud, no se realizará la representación progresiva. —— Mostrar media oración de HTML es peor que no mostrarla en absoluto. */
    const val MIN_CHARS: Int = 24

    /**
     * Tamaño del paso de la primera marcha (caracteres).
     *
     * tomar [MIN_CHARS] En lugar de un valor mayor: cada actualización requiere recargar la página completa (que contiene aproximadamente 25 KB de estilos y scripts),
     * El costo no tiene nada que ver con el contenido. Si el primer archivo es 120, el contenido está en 24…119 Cuando sea "más corto que el tamaño del paso, regresar como está" hará
     * Cada fotograma cambia de contenido. —— Y esa es la peor parte: se producen docenas de recargas de página completa dentro de los primeros doscientos milisegundos de la aparición de la tarjeta.
     * A partir de 24, duplicándose por equipo, el primer segmento solo requiere 3 actualizaciones.
     */
    const val DEFAULT_STEP: Int = MIN_CHARS

    /**
     * Calcule el carácter al que se debe representar este marco.
     *
     * @param length Longitud del contenido actual
     * @param step El tamaño del paso de la primera marcha debe ser positivo.
     * @return Longitud de la instantánea, dentro de `[step, 2*step, 4*step, ...]` arriba y `≤ length`;
     *         Contenido más corto que `step` regresar cuando `length`(tal cual)
     */
    fun snapshotLength(length: Int, step: Int = DEFAULT_STEP): Int {
        require(step > 0) { "step must be positive" }
        if (length <= step) return length
        var snap = step
        while (snap.toLong() * 2 <= length) {
            snap *= 2
        }
        return snap
    }

    /**
     * Obtenga el contenido que se debe proporcionar a WebView para este marco.
     *
     * valor de retorno**Identidad**Es tan importante como la longitud: cuando el contenido no cruza el engranaje, se debe devolver un prefijo igual al anterior.
     * De lo contrario aguas arriba `remember(spec, ...)` Se tratará como un cambio de contenido y provocará una recarga innecesaria de la página completa.
     * Lo que se devuelve aquí es siempre `content.take(snapshotLength(...))`, si los valores son iguales, satisface los requisitos de Compose.
     * Juicio de igualdad estructurado.
     */
    fun snapshot(content: String, step: Int = DEFAULT_STEP): String {
        val snap = snapshotLength(content.length, step)
        if (snap >= content.length) return content
        // No cortes por la mitad de un par de proxy:emoji / Los caracteres raros son dos caracteres de UTF-16. Si los truncas desde el medio, obtendrás
        // Un sustituto huérfano que se convierte en un carácter de reemplazo cuando WebView lo codifica. Simplemente retroceda un espacio.
        val end = if (snap > 0 && Character.isHighSurrogate(content[snap - 1])) snap - 1 else snap
        return content.substring(0, end)
    }

    /**
     * La longitud generada es [length] de contenido, cuántas instantáneas se tomarán en total.
     *
     * Sólo se utiliza para pruebas y conciliación del libro mayor: el tamaño de paso fijo es `length / step`, el engranaje geométrico es
     * `floor(log2(length / step)) + 1`.
     */
    fun snapshotCount(length: Int, step: Int = DEFAULT_STEP): Int {
        require(step > 0) { "step must be positive" }
        if (length <= step) return 1
        var snap = step
        var count = 1
        while (snap.toLong() * 2 <= length) {
            snap *= 2
            count += 1
        }
        return count
    }
}
