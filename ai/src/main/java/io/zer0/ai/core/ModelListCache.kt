package io.zer0.ai.core

import io.zer0.common.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.132: Caché de lista de modelos ascendentes — Evite ingresar repetidamente a la página de edición del proveedor e intentar conectarse a la red.
 *
 * Puntos de diseño(Caché a nivel de archivo + caché de memoria):
 *  - caché de memoria(Singleton a nivel de proceso),prensa [CacheKey] índice
 *  - 5 minutos TTL,Después del vencimiento, la siguiente extracción forzará una actualización.
 *  - Devolver directamente cuando sea golpeado y no haya caducado,Saltar solicitudes de red
 *  - [forceFresh] El parámetro permite la actualización manual de la interfaz de usuario para omitir el caché
 *  - [invalidate] Configurar cambios en el proveedor(Cambiar apiKey/baseUrl/eliminar)llamado cuando
 *  - Huella digital de clave de caché:providerId + baseUrl + apiKey últimos 4 dígitos + type(Cualquier cambio invalidará el)
 *
 * Objeto de caché [CachedModels] mantener [models] y [fetchedAt](ms Marca de tiempo).
 * Límite de capacidad 32,La entrada más antigua será eliminada si supera el límite.(Prevenir la acumulación anormal).
 *
 * Nota:Este caché solo almacena en caché"retorno original aguas arriba"lista de modelos;
 * La persona que llama todavía necesita hacer algo con el resultado. [ModelRegistry.enrich] para completar la habilidad/modal/Ventana de contexto.
 */
object ModelListCache {

    private const val TAG = "ModelListCache"

    /** caché TTL(milisegundos).5 Las extracciones repetidas en cuestión de minutos regresarán directamente al caché. */
    private const val CACHE_TTL_MS = 5L * 60 * 1000

    /** Número máximo de entradas de caché,El mayor se elimina si supera el límite.(Prevenir la acumulación anormal). */
    private const val MAX_ENTRIES = 32

    private data class CacheKey(
        val providerId: String,
        val type: String,
        val baseUrl: String,
        val apiKeyTail: String,
    )

    private data class CachedModels(
        val models: List<Model>,
        val fetchedAt: Long,
    )

    private val cache = ConcurrentHashMap<CacheKey, CachedModels>()

    /**
     * generar [CacheKey] — baseUrl/apiKey Los cambios se consideran nuevas entradas.,Invalida automáticamente los cachés antiguos.
     * apiKey Tome solo los últimos 4 dígitos como huella digital.(Evite retener la clave completa en la memoria.).
     */
    private fun keyOf(config: ProviderConfig): CacheKey {
        val base = config.resolvedBaseUrl()
        val tail = if (config.apiKey.length > 4) config.apiKey.takeLast(4) else config.apiKey
        return CacheKey(
            providerId = config.id,
            type = config.type.name,
            baseUrl = base,
            apiKeyTail = tail,
        )
    }

    /**
     * Caché de consultas. Golpeado y devuelto sin caducidad [List];De lo contrario, devuelve nulo.
     *
     * @param forceFresh true se ve obligada a ser considerada una señorita(UI Actualizar manualmente la escena)
     */
    fun get(config: ProviderConfig, forceFresh: Boolean = false): List<Model>? {
        if (forceFresh) return null
        val key = keyOf(config)
        val cached = cache[key] ?: return null
        val age = System.currentTimeMillis() - cached.fetchedAt
        if (age > CACHE_TTL_MS) {
            Logger.i(TAG, "cache expired: ${key.providerId} age=${age}ms")
            return null
        }
        Logger.i(TAG, "cache hit: ${key.providerId} models=${cached.models.size} age=${age}ms")
        return cached.models
    }

    /**
     * Escribir caché. La capacidad excede [MAX_ENTRIES] Se elimina la entrada más antigua.
     */
    fun put(config: ProviderConfig, models: List<Model>) {
        val key = keyOf(config)
        // Reparación de auditoría (7.9): Capacidad de lectura obsoleta-cambiar-escribir bloqueo — La implementación original es ConcurrentHashMap
        // size marcar + ordenado por + eliminar no atómico,La colocación simultánea puede eliminar accidentalmente solo las entradas escritas o eliminarlas repetidamente.
        synchronized(cache) {
            cache[key] = CachedModels(models = models, fetchedAt = System.currentTimeMillis())
            // Retiro de capacidad:Eliminar las entradas más antiguas cuando se exceda el límite superior
            if (cache.size > MAX_ENTRIES) {
                cache.entries
                    .sortedBy { it.value.fetchedAt }
                    .take(cache.size - MAX_ENTRIES)
                    .forEach { cache.remove(it.key) }
            }
        }
    }

    /**
     * Invalidar el caché del proveedor especificado(Cambios de configuración/Llamado al eliminar).
     */
    fun invalidate(providerId: String) {
        val keysToRemove = cache.keys.filter { it.providerId == providerId }
        keysToRemove.forEach { cache.remove(it) }
        if (keysToRemove.isNotEmpty()) {
            Logger.i(TAG, "invalidated ${keysToRemove.size} entries for provider=$providerId")
        }
    }

    /**
     * Borrar todo el caché(Llamado durante el reinicio global,Como cerrar sesión/Cambiar de cuenta).
     */
    fun clear() {
        val n = cache.size
        cache.clear()
        Logger.i(TAG, "cleared $n entries")
    }
}
