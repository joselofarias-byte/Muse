package io.zer0.muse.data.proactive

/**
 * Mensaje activo nivel "nivel activo".
 *
 * Los usuarios sólo necesitan expresar"quiero que sea mas activo", No es necesario comprender los dos parámetros de implementación "intervalo/probabilidad de envío".
 * Esta enumeración empaqueta combinaciones comunes de los dos en cuatro archivos;[from] Admite la derivación inversa de engranajes a partir de tres valores antiguos.
 * Si no se puede inferir (ha sido personalizado por el usuario), se devolverá nulo y la interfaz mostrará "Personalizado".
 *
 * **El número diario no está aquí.**: consiste en [ProactiveIntensity.cap] Control (Silencio 3 / Restricción 8 /
 * Estándar 15/Activo 30/Apego ilimitado). Aquí simplemente nos centramos en "con qué frecuencia pensar en ello y si disparar después de dar en el blanco".
 */
enum class ProactivePace(
    /** Intervalo de disparo (minutos). */
    val intervalMinutes: Int,
    /** probabilidad de envío (0-100). */
    val sendProbability: Int,
) {
    /** Menos: aproximadamente una vez cada medio día, la mitad de probabilidad. */
    LIGHT(720, 50),

    /** Estándar: aproximadamente una vez cada 4 horas, cada vez (predeterminado). */
    STANDARD(240, 100),

    /** Más: aproximadamente una vez cada 2 horas, siempre. */
    HEAVY(120, 100),

    /**
     * Más: aproximadamente una vez cada 30 minutos, siempre.
     *
     * Este es el archivo más denso:30 Los minutos son exactamente el límite inferior del intervalo permitido por la capa de decisión.
     * ([ProactiveTimingEngine.MIN_INTERVAL_MINUTES]), no importa cuán denso sea más allá del límite inferior, no tendrá efecto.
     * El número diario de entradas todavía está determinado por [ProactiveIntensity.cap] decisión.
     */
    EXTREME(30, 100),

    ;

    companion object {
        /** La marcha se deduce del intervalo y la probabilidad; si no coincide ninguna marcha, se devuelve nulo (la interfaz muestra \"Personalizado\"). */
        fun from(intervalMinutes: Int, sendProbability: Int): ProactivePace? = values().firstOrNull {
            it.intervalMinutes == intervalMinutes &&
                it.sendProbability == sendProbability
        }
    }
}
