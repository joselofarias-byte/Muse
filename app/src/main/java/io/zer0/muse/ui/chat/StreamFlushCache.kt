package io.zer0.muse.ui.chat

import io.zer0.muse.transformer.InternalMarkupSanitizer

/**
 * Actualización de caché de transmisión.
 *
 * Durante la transmisión, el contenido/razonamiento solo se agregará al revés y no se reescribirá en su totalidad. En base a esto,
 * Hay algunas cosas que no cambian después del primer cálculo, por lo que no es necesario volver a calcular cada cuadro:
 *
 *  1. ¿Existe la etiqueta etiqueta? —— Las etiquetas que han aparecido en el prefijo no desaparecerán sin importar cuánto texto se agregue;
 *  2. mood contenido —— mood El bloque está ubicado al principio y el texto agregado después del cierre ya no afectará los resultados de la extracción;
 *  3. reasoning Resultados de extracción de etiquetas para —— El hecho de que la longitud no haya cambiado significa que no se agrega y se reutiliza directamente.
 *
 * Estas tres cosas ejecutarán originalmente la regularización en el texto completo de cada cuadro, que es la principal sobrecarga durante las respuestas largas.
 * Si alguno de ellos no se establece (el contenido se acorta, el mensaje cambia, la transmisión finaliza), se devuelve la ruta de cálculo original.
 *
 * Restricciones de subprocesos: solo se usa en el mismo subproceso de la devolución de llamada de transmisión, no se realiza ningún procesamiento de seguridad de subprocesos.
 */
internal class StreamFlushCache {
    private var currentId: String? = null

    /** Si la marca de la etiqueta se ha visto en este mensaje (una vez que sea verdadera, no habrá ninguna alternativa). */
    private var tagMarkerSeen = false

    /** mood Resultados extraídos;null Indica que la extracción no ha sido exitosa. */
    private var moodResult: String? = null

    /** mood La longitud del contenido cuando se confirma que el resultado está bloqueado. */
    private var moodSealedLength = -1

    /** reasoning Elimina las longitudes de entrada almacenadas en caché de los resultados. */
    private var reasoningLength = -1
    private var reasoningStripped: String? = null

    /** La longitud del último contenido visto, utilizada para identificar casos en los que el contenido ha sido reemplazado (acortado) en su totalidad. */
    private var lastContentLength = -1

    /**
     * Restablecer al cambiar de mensaje. Es seguro llamarlo repetidamente dentro del mismo mensaje.
     */
    fun begin(messageId: String, contentLength: Int) {
        if (currentId != messageId) {
            currentId = messageId
            tagMarkerSeen = false
            moodResult = null
            moodSealedLength = -1
            reasoningLength = -1
            reasoningStripped = null
            lastContentLength = -1
        } else if (contentLength < lastContentLength) {
            // El contenido se vuelve más corto. = Al sobrescribirse (editarse/regenerarse/limpiarse y reescribirse), todos los cachés quedan invalidados.
            tagMarkerSeen = false
            moodResult = null
            moodSealedLength = -1
        }
        lastContentLength = contentLength
    }

    /**
     * Si el contenido contiene etiquetas internas.
     *
     * Si se presiona una vez, siempre será cierto: cuando el texto solo se agrega, la marca existente no desaparecerá.
     */
    fun hasTagMarker(text: String): Boolean {
        if (tagMarkerSeen) return true
        val hit = InternalMarkupSanitizer.TAG_MARKER_REGEX.containsMatchIn(text)
        if (hit) tagMarkerSeen = true
        return hit
    }

    /**
     * Extraiga el estado de ánimo y reutilice el último resultado directamente después de cerrar el bloque.
     *
     * Se deben cumplir dos condiciones de reutilización al mismo tiempo:
     *  - La longitud actual no es menor que la longitud cuando está bloqueado (sin reversión);
     *  - No hay nuevas etiquetas abiertas después del punto de bloqueo (de lo contrario, significa que el modelo ha escrito otro bloque de estado de ánimo).
     */
    fun mood(content: String, extract: (String) -> String?): String? {
        val cached = moodResult
        if (cached != null && content.length >= moodSealedLength) {
            if (!hasNewOpeningMarker(content, moodSealedLength)) return cached
        }
        val fresh = extract(content)
        moodResult = fresh
        // Bloquee solo cuando el contenido realmente se obtenga: un resultado vacío puede ser simplemente que el bloque aún no se ha escrito.
        moodSealedLength = if (fresh != null) content.length else -1
        return fresh
    }

    /**
     * reasoning La cubierta de la etiqueta se retira y se reutiliza cuando la longitud permanece sin cambios.
     *
     * El razonamiento solo se adjunta durante la transmisión. La misma longitud significa el mismo contenido.
     */
    fun strippedReasoning(raw: String, strip: (String) -> String): String {
        val cached = reasoningStripped
        if (cached != null && raw.length == reasoningLength) return cached
        val result = strip(raw)
        reasoningStripped = result
        reasoningLength = raw.length
        return result
    }

    /** Borre la caché de derivación (conservando los ID de los mensajes) cuando finalice la transmisión o cuando sea necesario un recálculo forzado. */
    fun invalidateDerived() {
        moodResult = null
        moodSealedLength = -1
        reasoningLength = -1
        reasoningStripped = null
    }

    private fun hasNewOpeningMarker(content: String, fromLength: Int): Boolean {
        // La etiqueta abierta más larga "<moodfx>"(8 caracteres), el respaldo 8 garantiza que las etiquetas que cruzan un límite no se cortan.
        val from = (fromLength - 8).coerceAtLeast(0)
        if (from >= content.length) return false
        val tail = content.substring(from).lowercase()
        return tail.contains("<mood") || tail.contains("<mod") ||
            tail.contains("[mood]") || tail.contains("[mod]")
    }
}
