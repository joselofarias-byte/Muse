package io.zer0.memory.ticker

import io.zer0.common.ErrorCode
import io.zer0.common.toMessage
import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln

/**
 * v0.32: Configuración avanzada del sistema de memoria(memory.* Configuración).
 *
 * Diseño de elementos de configuración:
 *   memory:
 *     enabled: true
 *     token_budget: 2500
 *     decay_per_day: 0.02
 *     hit_bonus: 5
 *     base_importance: 10
 *     compile_threshold: 4.5
 *     forget_speed: 1.0
 *
 * Muse Promocione este conjunto de parámetros numéricos de codificados a configurables,Permitir a los usuarios:
 *  - Ajustar el presupuesto del token de memoria.md(Afecta la cantidad de memoria inyectada en el indicador del sistema.)
 *  - Controla la velocidad del olvido(decay_per_day λ,Cuanto más grande te haces, más rápido te olvidas)
 *  - Bonificación de golpe ajustada(hit_bonus,Los recuerdos que se mencionan a menudo no son fáciles de desaparecer.)
 *  - Establecer umbral de compilación(compile_threshold,No se ingresarán memorias con puntuaciones inferiores a esta. memory.md)
 *  - Establecer proporción de olvido(forget_speed,1.0 normales,2.0 Olvídate el doble de rápido)
 *
 * ubicación de almacenamiento:DataStore de memory_config_json(Persistente por el repositorio de configuración).
 * Leído por MemoryTicker en cada envío,Afecta la caída de la partitura y las opciones de compilación.
 */
@Serializable
data class MemoryConfig(
    /** memory.md presupuesto de token objetivo(Afecta la longitud inyectada en el indicador del sistema.). */
    val tokenBudget: Int = 2500,
    /**
     * Si se deben permitir canalizaciones de memoria en resúmenes/Vuelva a leer el texto completo de la conversación antes de extraer los hechos.
     *
     * Los mensajes originales se conservan cuando se cierra.,Pero la memoria en segundo plano solo usa la ventana actualmente cargada.;Sólo después de encenderlo
     * Leer la historia completa desde Room,Haga que los detalles anteriores estén disponibles para resumen y extracción de hechos.
     */
    val conversationRecallEnabled: Boolean = true,
    /**
     * coeficiente de caída exponencial λ(days^-1), para el cálculo ver [Companion.factScore].
     *
     * El número de días entre paréntesis a continuación se refiere a la "caída de [compileThreshold] tiempo requerido", es decir ln([baseImportance]/[compileThreshold])/λ:
     * - 0.01 → Aproximadamente 80 días (memoria lenta y larga)
     * - 0.02 → Aproximadamente 40 días (predeterminado)
     * - 0.04 → Unos 20 días (rápido, centrándose en el futuro próximo)
     *
     * El hecho de que hayas acertado contará la bonificación de acierto en la puntuación base, que en realidad se conservará por más tiempo;
     * [Companion.cutoffDays] Este es el criterio para esta conversión. Al cambiar el coeficiente, ambos puntos deben ser correctos al mismo tiempo.
     */
    val decayPerDay: Float = 0.02f,
    /**
     * ¿Cuántos puntos se suman por cada acierto?(Los recuerdos que han sido golpeados restablecen el punto de inicio de la decadencia.).
     *
     * v7: Ya conectado [factScore] / [cutoffDays] con [FactStore.applyDecay].
     * citado/Los hechos fusionados se actualizan. last_hit_at,y con (baseImportance + hitBonus) como punto base válido,
     * Como resultado, se descompone más lentamente y es más difícil de eliminar.
     */
    val hitBonus: Float = 5f,
    /** La puntuación inicial predeterminada para nuevos recuerdos. */
    val baseImportance: Float = 10f,
    /** Los recuerdos con puntuaciones inferiores a este valor no entran en Memory.md. */
    val compileThreshold: Float = 4.5f,
    /** factor de olvido(1.0 = normales,2.0 = Olvídate el doble de rápido). */
    val forgetSpeed: Float = 1.0f,
) {

    companion object {

        /**
         * Calcula la fracción de desintegración de un hecho a una edad determinada.
         *
         * modelo de decadencia(Versión simplificada,Misma dirección que la semántica de configuración propia.):
         *  score = effectiveBase * exp(-λ * ageDays)
         *  entre ellos λ = decayPerDay * forgetSpeed
         *  effectiveBase = baseImportance + (hit ? hitBonus : 0)
         *
         * @param ageDays fact autocreado/Número de días transcurridos desde el impacto(>=0)
         * @param config configuración de memoria
         * @param hit Si ha sido golpeado(La base efectiva es mayor después del golpe.)
         */
        fun factScore(ageDays: Float, config: MemoryConfig, hit: Boolean = false): Float {
            val lambda = (config.decayPerDay * config.forgetSpeed).toDouble()
            val base = (config.baseImportance + if (hit) config.hitBonus else 0f).toDouble()
            return (base * exp(-lambda * ageDays.toDouble())).toFloat()
        }

        /**
         * Calcular la decadencia de hechos para [compileThreshold] número de días requeridos.
         * Utilizado por FactStore.applyDecay para decidir cuándo eliminar un hecho.
         *
         * Derivación:score = compileThreshold
         *  effectiveBase * exp(-λ * d) = compileThreshold
         *  d = ln(effectiveBase / compileThreshold) / λ
         *
         * al valor predeterminado (10/4.5, 0.02, 1.0) unos 40 días;decayPerDay=0.04 → 20 dia;
         * forgetSpeed=2.0 → 20 Días. La base efectiva se hace más grande después del golpe.,Días de retención más largos.
         *
         * @param hit Si ha sido golpeado
         */
        fun cutoffDays(config: MemoryConfig, hit: Boolean = false): Float {
            val lambda = (config.decayPerDay * config.forgetSpeed).toDouble()
            if (lambda <= 0.0) return Float.MAX_VALUE
            val base = (config.baseImportance + if (hit) config.hitBonus else 0f).toDouble()
            val thr = config.compileThreshold.toDouble()
            if (base <= thr) return 0f // base ha caído por debajo del umbral,Elimínalos a todos inmediatamente.
            return (ln(base / thr) / lambda).toFloat()
        }

        /**
         * v1.78: versión segura cutoffDays,Se utiliza para cálculos de decadencia de hechos reales.
         *
         * `cutoffDays` en lambda<=0 regresar cuando `Float.MAX_VALUE`,Pasar directamente a
         * `Instant.now().minus(cutoff.toLong(), DAYS)` debido a `Long.MAX_VALUE` tiro de desbordamiento
         * `DateTimeException`. Devuelve aquí un límite limitado de 36500 días.(100 año),Semánticamente equivalente a"Sin atenuación".
         */
        fun safeCutoffDays(config: MemoryConfig, hit: Boolean = false): Float {
            val c = cutoffDays(config, hit)
            return when {
                c.isInfinite() || c.isNaN() -> 36500f
                c >= Float.MAX_VALUE / 2 -> 36500f
                else -> c
            }
        }

        /**
         * v1.78: Verificar si el valor de configuración es legal,prevenir threshold>=base Configuraciones peligrosas como provocar que se aclaren todos los hechos.
         * Devuelve nulo para indicar legal,De lo contrario, se devuelve una descripción de error.
         */
        fun validate(config: MemoryConfig): String? {
            if (config.tokenBudget < 0) return ErrorCode.MEMORY_TOKEN_BUDGET_INVALID.toMessage("negative", config.tokenBudget)
            if (config.tokenBudget == 0) return ErrorCode.MEMORY_TOKEN_BUDGET_INVALID.toMessage("zero")
            if (config.decayPerDay < 0f) return ErrorCode.MEMORY_CONFIG_INVALID.toMessage("decayPerDay_negative", config.decayPerDay)
            if (config.hitBonus < 0f) return ErrorCode.MEMORY_CONFIG_INVALID.toMessage("hitBonus_negative", config.hitBonus)
            if (config.baseImportance <= 0f) {
                return ErrorCode.MEMORY_CONFIG_INVALID.toMessage(
                    "baseImportance_non_positive",
                    config.baseImportance,
                )
            }
            if (config.compileThreshold < 0f) {
                return ErrorCode.MEMORY_CONFIG_INVALID.toMessage(
                    "compileThreshold_negative",
                    config.compileThreshold,
                )
            }
            if (config.forgetSpeed < 0f) return ErrorCode.MEMORY_CONFIG_INVALID.toMessage("forgetSpeed_negative", config.forgetSpeed)
            if (config.compileThreshold >= config.baseImportance) {
                return ErrorCode.MEMORY_CONFIG_INVALID.toMessage("threshold_ge_base", config.compileThreshold, config.baseImportance)
            }
            return null
        }

        /** Determine si la puntuación actual de un determinado hecho es lo suficientemente alta como para ingresar a memoria.md. */
        fun shouldCompile(score: Float, config: MemoryConfig): Boolean = score >= config.compileThreshold
    }
}
