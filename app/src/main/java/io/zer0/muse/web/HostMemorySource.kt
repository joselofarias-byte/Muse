package io.zer0.muse.web

import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.space.MemorySpaceEntity

/**
 * Entradas de memoria en forma intermedia en la capa de protocolo.:El texto no está truncado ni paginado.,Proyección a [HostMemoryReads].
 *
 * Se utiliza una capa separada para"Leer de qué biblioteca"y"como emitir"separar:El primero se basa en el enrutamiento de habitaciones y subbibliotecas.,Solo se puede ejecutar en el dispositivo;
 * Esta última es una función pura.,Las reglas propensas a errores, como la paginación, el truncamiento y la fragmentación, se pueden probar directamente sin la base de datos.
 */
internal data class HostMemoryRecord(
    val id: String,
    val content: String,
    val createdAt: String,
    /** Categoría de fuente,ver el valor [HostMemorySourceKind]. */
    val source: String,
    val pinned: Boolean,
)

/**
 * Fuente de datos de sólo lectura en memoria.
 *
 * Solo abre dos rutas de lectura:Enumere por alcance y busque por palabra clave. La implementación debe tener las capacidades de almacenamiento existentes de la aplicación. ——
 * La página web no crea otro índice ni copia una tabla de memoria.,De lo contrario, tarde o temprano el calibre de ambos lados no coincidirá.(Cambió un recuerdo,
 * El sitio web todavía es antiguo.).
 */
internal interface HostMemorySource {

    /** [assistantId] Todos los elementos del alcance correspondiente;cualquier orden,La paginación se realiza en la capa de proyección. */
    suspend fun entries(assistantId: String?): List<HostMemoryRecord>

    /** Búsqueda de palabras clave,[limit] El límite superior dado por la persona que llama. */
    suspend fun search(query: String, assistantId: String?, limit: Int): List<HostMemoryRecord>

    /** Implementación vacía cuando la fuente de datos no está ensamblada:Recibo del pedido como siempre,Simplemente no hay entradas. */
    object Empty : HostMemorySource {
        override suspend fun entries(assistantId: String?): List<HostMemoryRecord> = emptyList()

        override suspend fun search(query: String, assistantId: String?, limit: Int): List<HostMemoryRecord> =
            emptyList()
    }
}

/**
 * Implementado utilizando la propia tienda de datos de la aplicación. [HostMemorySource].
 *
 * El calibre de enrutamiento es consistente con la página de memoria.:asistente principal(vacío / `default`)leer global `facts.db` + Alcance `main`,
 * asistente de lectura `facts_<id>.db` + El alcance es este ayudante. id —— con lado de escritura([FactDbProvider])es la misma cuenta,
 * Las entradas que se ven en la página web son el lote realmente retirado por el modelo.
 *
 * Buscar directamente [FactStore] Portal de búsqueda de texto completo,No escriba aquí el segundo conjunto de lógica coincidente.(Esa lógica tiene la suya.
 * Calibre de filtrado de caducidad y segmentación de palabras,Si haces una copia, definitivamente será inconsistente con los resultados de búsqueda en la aplicación.).
 */
internal class FactHostMemorySource(
    private val factStore: FactStore,
    private val factDbProvider: FactDbProvider,
) : HostMemorySource {

    override suspend fun entries(assistantId: String?): List<HostMemoryRecord> =
        storeFor(assistantId).getAll(scope = scopeOf(assistantId)).map { it.toRecord() }

    override suspend fun search(query: String, assistantId: String?, limit: Int): List<HostMemoryRecord> {
        val scope = scopeOf(assistantId)
        val store = storeFor(assistantId)
        val hits = mutableListOf<FactStore.Fact>()
        for (spaceId in spacesOf(store, scope)) {
            hits += store.searchFullTextScoped(query, scope, spaceId, limit)
        }
        return hits.distinctBy { it.id }.sortedByDescending { it.id }.take(limit).map { it.toRecord() }
    }

    /**
     * El espacio de memoria donde apareció este alcance en la biblioteca.
     *
     * Botón de búsqueda (scope, space) Tome la entrada a los espacios separados de Curry.,Así que primero enumera los espacios.;Utilice el espacio predeterminado cuando la biblioteca esté vacía
     * Ten todo en cuenta,Asegúrese de que la biblioteca vacía también tome la misma ruta y devuelva una lista vacía,en lugar de abrir otro"sin espacio"cortocircuito.
     */
    private suspend fun spacesOf(store: FactStore, scope: String): List<String> =
        store.listScopeSpacePairs()
            .filter { it.first == scope }
            .map { it.second }
            .ifEmpty { listOf(MemorySpaceEntity.DEFAULT_SPACE_ID) }

    private fun storeFor(assistantId: String?): FactStore {
        val id = assistantId?.trim().orEmpty()
        return if (id.isEmpty() || id == DEFAULT_ASSISTANT_ID) factStore else factDbProvider.getFactStore(id)
    }

    private fun scopeOf(assistantId: String?): String {
        val id = assistantId?.trim().orEmpty()
        return if (id.isEmpty() || id == DEFAULT_ASSISTANT_ID) MAIN_SCOPE else id
    }

    private fun FactStore.Fact.toRecord(): HostMemoryRecord = HostMemoryRecord(
        id = id.toString(),
        content = fact,
        createdAt = createdAt,
        source = HostMemoryReads.sourceKind(sessionId, source),
        pinned = !pinnedAt.isNullOrBlank(),
    )

    private companion object {
        /** ID del asistente principal: Su memoria cae globalmente `facts.db`. */
        const val DEFAULT_ASSISTANT_ID = "default"

        /** El nombre de dominio del asistente principal.,Lo mismo que el lado escrito. */
        const val MAIN_SCOPE = "main"
    }
}
