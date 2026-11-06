package io.zer0.memory.fact

/**
 * v12: Determinante de LLM de deduplicación de hechos — capa de algoritmo(Similitud de personajes/Clave de entidad)Pares de candidatos difusos indeterminados,
 * Dejemos que el modelo grande determine si se trata del mismo hecho. No importa cuán perfecta sea la capa del algoritmo, todavía habrá peces que se escapen de la red.,
 * Dos hechos que son semánticamente equivalentes pero que tienen expresiones muy diferentes(Como por ejemplo"El usuario tiene un corgi." vs "el tiene un corgi")
 * Sólo el modelo puede identificarse de forma fiable.
 *
 * diseño:
 *  - La interfaz está definida en el módulo de memoria.,darse cuenta(LLM llamar)Inyectar en el módulo de la aplicación,memory Los módulos siguen siendo comprobables de forma independiente.
 *  - FactStore solo en"Hay candidatos para la misma clave de entidad pero falla la determinación del texto"llamada de escena de baja frecuencia,Controlar el consumo de tokens.
 *  - Resultados de juicio con confianza,Prefiere no fusionarse cuando la confianza es baja(Ning Le es bueno).
 *  - El implementador debe expirar el tiempo de espera./La excepción se degrada a"No es lo mismo",No bloquea el proceso principal de escritura en memoria.
 */
interface FactDedupJudge {

    /**
     * Determinar si dos hechos son el mismo hecho.
     *
     * @param a Ya tengo texto de hechos.
     * @param b nuevo texto de hecho
     * @param entityKeyA Clave de entidad para el hecho existente(Disponible)
     * @param entityKeyB Clave de entidad para hecho nuevo(Disponible)
     * @return Resultado del juicio;LLM No disponible/tiempo de espera/Devuelto cuando falla el análisis same=false(Preferiría no fusionarse)
     */
    suspend fun judge(a: String, b: String, entityKeyA: String?, entityKeyB: String?): DedupVerdict
}

/**
 * Resultado del juicio.
 *
 * @param same true=mismo hecho(debería fusionarse),false=No es lo mismo(no fusionar)
 * @param confidence confianza 0.0~1.0;same=true y confidence < 0.7 La persona que llama debe manejar esto de manera conservadora.
 * @param reason breve razón(Se utiliza para el registro y la auditoría posterior.)
 */
data class DedupVerdict(
    val same: Boolean,
    val confidence: Float = 0f,
    val reason: String = "",
) {
    /** Alta confianza idéntica — Fusión segura y automática. */
    val highConfidenceSame: Boolean get() = same && confidence >= 0.7f

    companion object {
        /** LLM No disponible/La rebaja conservadora resulta en caso de fracaso. */
        val NOT_SAME = DedupVerdict(same = false, confidence = 0f, reason = "judge unavailable")
    }
}

/** Implementación predeterminada sin LLM(El comportamiento es exactamente el mismo que cuando el juez no está conectado.). */
object NoopFactDedupJudge : FactDedupJudge {
    override suspend fun judge(a: String, b: String, entityKeyA: String?, entityKeyB: String?): DedupVerdict = DedupVerdict.NOT_SAME
}
