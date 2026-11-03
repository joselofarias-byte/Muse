package io.zer0.muse.data

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Provider Difusión en proceso después de escribir la configuración.
 *
 * La capa de datos ha escrito el proveedor./Configuración del modelo(Contiene URL base,apiKey, lista de modelos)Notificar una vez más tarde,
 * Los consumidores que tengan "caché derivado de la configuración del proveedor" caducarán automáticamente. De esta forma la capa de datos no necesita dependencias inversas.
 * Servicio de nivel superior(Como en la bolsa de trapo EmbeddingService),La lógica de invalidación de la caché no se distribuirá entre varios puntos de llamada.
 *
 * suscriptores actuales:[io.zer0.muse.rag.EmbeddingService](Provider Caché de instancia).
 * La devolución de llamada se ejecuta sincrónicamente en la rutina de escritura.,Las implementaciones deben ser no bloqueantes, reentrantes e idempotentes.
 */
object ProviderConfigChangeBus {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Registrar cambio de devolución de llamada;Sólo se conservará una copia del registro repetido de la misma instancia. */
    fun register(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    /** Devolución de llamada de cambio de cierre de sesión(Se utiliza cuando se realizan pruebas o se destruyen objetos con un ciclo de vida prolongado.). */
    fun unregister(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Notificar a todos los suscriptores sobre los cambios de configuración. Una excepción lanzada por un único suscriptor no afecta a otros suscriptores ni al proceso de escritura. */
    fun notifyChanged() {
        listeners.forEach { listener ->
            runCatching { listener() }
        }
    }
}
