package io.zer0.muse.proactive

/**
 * Enviar puerta de probabilidad.
 *
 * La posición es "después de que hayan pasado todas las puertas difíciles y antes de que se genere realmente": ríndete primero según la probabilidad y luego decide si gastar este tiempo.
 * Llamada modelo. Lo contrario (generar primero y luego tirar los dados) resultará en una llamada desperdiciada para la ronda que probabilísticamente se abandonó.
 *
 * Las probabilidades son configuraciones visibles para el usuario, por lo que se debe conservar la semántica:
 *
 *  - 100 = Inmediatamente después de pasar (exactamente lo mismo que no tener esta puerta)
 *  - 0   = Nunca envíe (conserve el registro de evaluación para solucionar problemas fácilmente)
 *  - 50  = media probabilidad
 *
 * La fuente de la aleatoriedad no está aquí: la persona que llama la pone una vez `0..99` El resultado de la tirada de dados llega,
 * Utilice aleatoriedad verdadera para la producción y valores fijos para las pruebas. Por lo tanto, esta clase no tiene estado ni efectos secundarios y puede afirmarse directamente.
 */
object ProactiveSendProbability {

    /** El límite superior del rango de lanzamiento de dados (exclusivo). */
    const val ROLL_BOUND = 100

    /**
     * Si se debe enviar este rollo.
     *
     * @param sendProbability Probabilidad de envío configurada por el usuario; los valores sucios que superan 0...100 se manejan como límites.
     * @param rollPercent Un rollo de 0..99.
     */
    fun allows(sendProbability: Int, rollPercent: Int): Boolean {
        val probability = sendProbability.coerceIn(0, ROLL_BOUND)
        return probability >= ROLL_BOUND || (probability > 0 && rollPercent.coerceIn(0, ROLL_BOUND - 1) < probability)
    }
}
