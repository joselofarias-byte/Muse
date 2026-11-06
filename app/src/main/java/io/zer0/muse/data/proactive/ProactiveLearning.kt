package io.zer0.muse.data.proactive

/**
 * Aprendizaje por retroalimentación para mensajes proactivos.
 *
 * No tiene nada que ver con calificaciones: aquí solo tratamos ", respondió el usuario./¿Cómo cambiará el nivel de intimidad después de tacharlo?
 * Y qué tipo de contenido es crónicamente impopular. La propia decisión de envío ya no depende de ello.
 */
object ProactiveLearning {

    fun intimacyDelta(state: OutcomeState): Float = when (state) {
        OutcomeState.REPLIED -> 0.05f
        OutcomeState.OPENED -> 0.01f
        OutcomeState.DISMISSED -> -0.03f
        OutcomeState.EXPIRED -> -0.01f
        OutcomeState.PENDING -> 0f
    }

    /** Nuevo nivel de intimidad después de aplicar la retroalimentación una vez. */
    fun applyOutcome(intimacy: Float, state: OutcomeState): Float =
        (intimacy + intimacyDelta(state)).coerceIn(0f, 1f)

    /**
     * Si un género se ha vuelto "menos popular".
     *
     * Los criterios son deliberadamente conservadores (se deben haber publicado al menos 4 elementos y más de la mitad de ellos tachados).
     * Evite sacar conclusiones basadas en muy pocas muestras —— El precio del error de juicio es cerrar permanentemente un tipo de noticias proactivas significativas.
     */
    fun isKindUnpopular(dismissed: Int, total: Int): Boolean =
        total >= 4 && dismissed * 2 > total
}
