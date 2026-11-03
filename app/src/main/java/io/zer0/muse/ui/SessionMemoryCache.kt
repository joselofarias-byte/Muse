package io.zer0.muse.ui

import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import java.util.LinkedHashMap

/**
 * Caché LRU en memoria de mensajes de sesión.
 *
 * fondo:v1.93 poner [ChatViewModel] Después de cambiar a Koin singleton,La lista de mensajes de la sesión nunca se publica,
 * El uso prolongado acumulará un alto uso de memoria. Este caché comienza con LRU(Usado menos recientemente)La política mantiene las últimas N sesiones.
 * una copia de memoria del mensaje,Una vez superado el límite superior, la sesión a la que no se haya accedido durante más tiempo será desalojada automáticamente.,Cuando la sesión expulsada se vuelve a conectar
 * Recargar desde la base de datos.
 *
 * Puntos de diseño:
 * - Basado en [LinkedHashMap](accessOrder = true) + [removeEldestEntry] implementar secuencia de acceso LRU:
 *   cada vez [get]/[put] El elemento visitado se moverá al final de la lista vinculada.,El encabezado de la lista vinculada es el elemento al que no se ha accedido durante más tiempo.(eldest).
 * - Pase [Synchronized] Garantizar la seguridad del hilo:La lista de mensajes tiene múltiples subprocesos en la salida de transmisión, el corte de páginas, la carga de más, etc.
 *   Se leerá y escribirá en escenarios concurrentes.,Debe estar cerrado.
 * - [evictListener]:Devolución de llamada cuando se desaloja una entrada,Registro predeterminado [Logger.d] Facilita la depuración y el seguimiento del rendimiento.
 *
 * Seguridad de datos:Lo que se almacena en caché es una instantánea de la memoria de la lista de mensajes de la sesión.(Cotización),La salida de streaming se ha sincronizado y colocado en la biblioteca.
 * (ver [ChatViewModel] dentro sessionRepository.appendMessage/upsertMessage llamar),
 * Entonces, incluso si la entrada de caché es desalojada o invalidada,Los datos no se perderán,Solo una consulta más a la base de datos la próxima vez que ingrese.
 *
 * Nota:Lo que se almacena es [List] citar en lugar de copiar,depender de [ChatViewModel] interno a los mensajes
 * "Copiar para actualizar"modo(Cada modificación pasa _state.update { it.copy(messages = ...) } generar nueva lista),
 * Las listas almacenadas en caché no se modifican in situ,Por lo tanto, compartir referencias es seguro.
 */
class SessionMemoryCache(
    /**
     * v2.x: proveedor de límite de caché — Leer en tiempo real cada vez que se juzga un desalojo(Soporte para configuración de actualización en caliente,
     * Ver la configuración del chat para la entrada.→Avanzado"Límite de caché de sesión"),Predeterminado [MAX_CACHE_SIZE].
     */
    private val maxSizeProvider: () -> Int = { MAX_CACHE_SIZE },
) {
    companion object {
        /**
         * Límite de caché LRU predeterminado(Mantiene una copia de memoria de los mensajes de las últimas 5 sesiones.).
         *
         * v2.x: Actualización en caliente de la configuración accedida — Los usuarios pueden chatear con la configuración.→Ajustes avanzados(3/5/8/12),
         * límite superior [io.zer0.muse.data.SettingsRepository.sessionCacheSizeFlow] Leer en tiempo real;
         * Esta constante solo se utiliza como valor predeterminado/Llega al fondo de esto.
         */
        const val MAX_CACHE_SIZE = 5

        private const val TAG = "SessionCache"
    }

    /**
     * Orden de acceso al mapa subyacente LRU.
     *
     * accessOrder = true tiempo,[get] con [put] Moverá la entrada de hit al final de la lista vinculada.,
     * [removeEldestEntry] en [put] publicar disparador,Devuelve verdadero para desalojar al jefe de la lista vinculada(Visitado más largo)entrada.
     */
    private val cache = object : LinkedHashMap<String, List<UIMessage>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<UIMessage>>): Boolean {
            // Nota:Aquí el tamaño es la capacidad después de insertar nuevas entradas.,Exceder el tamaño máximo antes del desalojo
            val shouldEvict = size > maxSizeProvider().coerceAtLeast(1)
            if (shouldEvict) {
                evictListener?.invoke(eldest.key, eldest.value)
            }
            return shouldEvict
        }
    }

    /**
     * Devolución de llamada cuando se desaloja una entrada(en [put] gatillo [removeEldestEntry] llamada sincrónica).
     *
     * Registro de implementación predeterminado [Logger.d]. Nota:Devolución de llamada al [Synchronized] ejecutar dentro del bloqueo,
     * No vuelva a llamar el método de sincronización de este caché dentro de la implementación.(JVM synchronized reentrante,Pero evite anidar para mayor claridad.).
     */
    var evictListener: ((sessionId: String, messages: List<UIMessage>) -> Unit)? = null

    init {
        // Oyente de desalojo predeterminado:Registre la identificación y la cantidad de mensajes de la sesión desalojada,Conveniente para depurar efectos de administración de memoria.
        // Nota:removeEldestEntry el mayor en realidad no ha sido eliminado cuando se activó,Por lo tanto, cache.size contiene entradas desalojadas.,
        // El tamaño real después del desalojo es el tamaño - 1.
        evictListener = { sessionId, messages ->
            Logger.d(TAG, "LRU sesión de desalojo: id=$sessionId, Número de mensajes=${messages.size}, Tamaño de caché después del desalojo=${cache.size - 1}")
        }
    }

    /**
     * Escribir o actualizar la instantánea del mensaje de sesión.
     *
     * Si ya existe, actualice el valor y promuévalo al valor accedido más recientemente.;Si una nueva entrada provoca que se supere el límite, se desalojará la entrada que lleve más tiempo sin visitarse.
     */
    @Synchronized
    fun put(sessionId: String, messages: List<UIMessage>) {
        cache[sessionId] = messages
    }

    /**
     * Leer la instantánea del mensaje de la sesión. Las visitas se promocionan a visitas recientes.(LRU Semántica),Devuelve nulo en caso de error.
     */
    @Synchronized
    fun get(sessionId: String): List<UIMessage>? = cache[sessionId]

    /**
     * Determine si la sesión especificada está almacenada en caché.
     *
     * Nota:[containsKey] no cambia el orden de acceso(No promocionado a visitado recientemente),Sólo se hacen juicios existenciales.
     */
    @Synchronized
    fun contains(sessionId: String): Boolean = cache.containsKey(sessionId)

    /**
     * Elimina las entradas de caché para una sesión específica(Sesión eliminada/Llamado al archivar).
     */
    @Synchronized
    fun remove(sessionId: String) {
        cache.remove(sessionId)
    }

    /**
     * Borre todas las entradas de caché.
     */
    @Synchronized
    fun clear() {
        cache.clear()
    }

    /**
     * Número actual de entradas de caché(Depuración/Para monitoreo).
     */
    @Synchronized
    fun size(): Int = cache.size
}
