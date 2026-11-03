package io.zer0.muse.transformer

import io.zer0.ai.core.UIMessage

/**
 * v2.3.2: En proceso"Marca de agua abstraída"(Revisión de compresión contextual findings C / D).
 *
 * grabar"Qué mensajes de una conversación han sido cubiertos por el resumen",Para uso en dos lugares:
 *  - **C Reutilización resumida**:Después del cambio/recarga de la sesión,No es necesario realizar otra convocatoria de LLM para resumir la misma parte de la historia.
 *    (Los resultados de la compresión se almacenaron originalmente sólo en la memoria. `_messages` adentro,La recarga desde la base de datos recuperará el historial completo → Hay que presionar de nuevo,
 *    Una llamada de LLM desperdiciada,y puede causar un retraso en la primera palabra de hasta 20 segundos.)
 *  - **D Restauración de bloque**:Reconstrucción del árbol del diálogo(`mergeRebuildMessages`)El texto original en el árbol antiguo se volverá a fusionar.,
 *    La coexistencia con el resumen conduce a una doble contabilización de tokens;Simplemente filtre por ID cubiertos aquí
 *
 * El cuerpo del resumen y los límites los conserva ContextCheckpointEntity de MemoryDb; esta tabla es un índice rápido en proceso.
 * Se devolverá después de leer el punto de control persistente. La capacidad se elimina gradualmente por orden escrita.,Evite el crecimiento ilimitado de procesos de larga duración.
 */
object CompressionSummaryStore {
    /** El resultado de una compresión.:texto resumido + cubierto por él(Eliminado del contexto)identificación del mensaje. */
    data class Entry(val summary: String, val coveredIds: Set<String>)

    /** Número máximo de sesiones recordadas(Superando la eliminación de los escritos más antiguos). */
    private const val MAX_ENTRIES = 32

    private val entries = LinkedHashMap<String, Entry>()

    /** Recuerda una compresión(Ignorar si el ID/resumen de la sesión está vacío o no se cubren mensajes). */
    @Synchronized
    fun remember(sessionId: String?, coveredIds: Set<String>, summary: String) {
        if (sessionId.isNullOrBlank() || summary.isBlank() || coveredIds.isEmpty()) return
        // volver a insertar, es decir"Escrito recientemente",Cuando se supere el aforo se eliminará el de mayor antigüedad.
        entries.remove(sessionId)
        entries[sessionId] = Entry(summary, coveredIds)
        if (entries.size > MAX_ENTRIES) {
            entries.keys.take(entries.size - MAX_ENTRIES).toList().forEach { entries.remove(it) }
        }
    }

    /** Leer el registro de compresión más reciente de la sesión.(Si no, regresa null). */
    @Synchronized
    fun entry(sessionId: String?): Entry? = sessionId?.let { entries[it] }

    /** Borrar el registro de una sesión(Se llama cuando se elimina la sesión/se borra el historial). */
    @Synchronized
    fun clear(sessionId: String) {
        entries.remove(sessionId)
    }

    /** Solo para pruebas individuales:Borrar todos los registros. */
    @Synchronized
    internal fun clearAllForTest() {
        entries.clear()
    }
}

/**
 * v2.3.2 (C): ¿Se pueden reutilizar los resúmenes almacenados en caché?
 *
 * condición de golpe:Esta sesión ya tiene un resumen en caché,Y el intervalo a comprimir esta vez.**Todos**cae dentro de su área de cobertura.
 * No se puede reutilizar cuando solo se cubre una parte —— Eso también reemplazará los mensajes descubiertos.(es como perder la historia),
 * En este caso, el compresor se resume.(El nuevo resumen cubrirá una gama más amplia).
 */
internal fun reusableSummary(entry: CompressionSummaryStore.Entry?, toCompress: List<UIMessage>): String? = entry?.summary?.takeIf {
    toCompress.isNotEmpty() && toCompress.all { message -> message.id.toString() in entry.coveredIds }
}
