package io.zer0.muse.proactive

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puerta volante única en proceso para inspección activa de mensajes.
 *
 * Hay cuatro fuentes desencadenantes para la inspección (cronometraje durante el proceso,WorkManager, volver a la recepción, página de configuración manualmente),
 * Fueron iniciados de forma independiente y sin conocimiento mutuo. Cuando no hay una puerta compartida, ambas vías pueden entrar para inspección al mismo tiempo.
 * Cada uno leyó la misma "hora del último envío", por lo que cada uno pensó "ya ha pasado suficiente tiempo desde el último".
 * Finalmente, se registraron dos mensajes con contenido muy similar en la misma ventana de tiempo.
 *
 * Aquí se cuidan dos cosas:
 *
 *  1. **marca de carrera**: Sólo se permite ejecutar una ronda de inspección al mismo tiempo. Los desencadenantes que llegan más tarde no se ponen en cola.
 *     Simplemente conviértete en nulo y vete. —— No tiene sentido hacer cola. El cronometraje se volverá a juzgar después de la ronda anterior.
 *     Aquellos que esperan sólo obtendrán la conclusión "recién dicha" cuando se despierten.
 *
 *  2. **intenta grabar**: registre una hora de inicio en la que esta ronda realmente comience a generarse y manténgala en todas las rondas.
 *     La cuenta de resultados tiene que esperar a que se guarde el mensaje antes de escribirlo, y la generación puede tardar hasta decenas de segundos; si solo lees la cuenta de resultados durante este tiempo,
 *     La ronda que se genera no existe en la capa de datos. La hora de inicio compensará esta brecha.
 *
 * El registro se realiza en "Ingresar a Generación" en lugar de "Ingresar a Inspección": Rondas bloqueadas por No Molestar, Cuota y Cambio
 * No se hizo nada y no debería quedar ningún rastro en la cuenta que pudiera suprimir las inspecciones normales posteriores.
 *
 * De la misma manera, si no se envía nada al final de una ronda (el modelo dice que no hay nada que recibir, la generación falla y el drop-in falla),
 * Este intento será revertido. —— No genera noticias, no crea duplicaciones y no debe cambiar la cadencia de revisión.
 */
class ProactiveCycleGuard {

    /**
     * Credenciales de ingreso para una ronda de inspección.
     *
     * [previousAttemptAt] es este ciclo**antes**El momento de ese intento de generación: es necesario saberlo al juzgar el momento
     * "¿Cuándo corriste en la última ronda?" Pero no puedes considerar el tiempo que acabas de anotar en esta ronda como la ronda anterior.
     * De lo contrario, te bloquearás en cada ronda. También es el punto de aterrizaje para la reversión.
     */
    data class Entry(
        /** La marca de tiempo de esta ronda de inspección. */
        val enteredAt: Long,
        /** La marca de tiempo del intento de generación antes de esta ronda,0 Dijo que aún no ha sucedido. */
        val previousAttemptAt: Long,
    )

    private val inFlight = AtomicBoolean(false)

    /** La hora de la última entrada real de generación, que se conserva a lo largo de las rondas. */
    @Volatile
    private var attemptAt = 0L

    /** ¿Se está realizando ya una ronda de inspecciones en este momento? */
    val isInFlight: Boolean get() = inFlight.get()

    /**
     * Intente ingresar a una ronda de inspección.
     *
     * @return Obtener las credenciales significa que puede ejecutar;null Indica que ya hay una ronda en ejecución y este activador debe omitirse.
     */
    fun tryEnter(now: Long): Entry? {
        if (!inFlight.compareAndSet(false, true)) return null
        return Entry(enteredAt = now, previousAttemptAt = attemptAt)
    }

    /**
     * Registre un intento de compilación.
     *
     * Esta ronda lo llama una vez antes de llamar al modelo. Después de escribir y antes del final de esta ronda,
     * Este registro es válido. —— Si llegan inspecciones durante este período (el trabajador de procesos cruzados pasa por alto la puerta volante única)
     * (como fue el caso), verá "Recién probado" en lugar de ver una cuenta vacía.
     */
    fun markAttempt(now: Long) {
        attemptAt = now
    }

    /**
     * Salir de una ronda de inspección.
     *
     * Simplemente borre la marca de carrera. "Todavía está en marcha en esta ronda" y "Se ha probado en esta ronda" son dos cosas diferentes.
     * Mezclarlo hará que la carrera pierda inmediatamente su rastro.
     *
     * @param sent Si esta ronda realmente envió un mensaje. Antes de enviar, intente retroceder a
     *             [Entry.previousAttemptAt], para que el ritmo de la próxima revisión sea el mismo que antes del cambio.
     */
    fun leave(entry: Entry, sent: Boolean) {
        if (!sent) attemptAt = entry.previousAttemptAt
        inFlight.set(false)
    }
}
