package io.zer0.muse.data.memory

import io.zer0.common.Logger
import io.zer0.memory.compile.MemoryFileWriter
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.reflection.MemoryContradictionStore
import io.zer0.memory.summary.ContextCheckpointDao
import io.zer0.memory.summary.ScopedCompiledSectionDao
import io.zer0.memory.summary.SessionSummaryDao
import io.zer0.muse.data.groupchat.GroupChatMemoryDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * sesión/Entrada de limpieza de memoria después de que se elimina el asistente.
 *
 * Los datos del lado de la memoria no siguen la tabla de sesiones.:Resumen(`session_summaries`), punto de control de contexto
 * (`context_checkpoints`), hechos(`facts`,Dividir biblioteca por asistente), producto compilado(`compiled_sections_scoped`,
 * `filesDir/memory/<scope>/`), memoria de chat grupal(`group_chat_memories`), lista de contradicciones
 * (`filesDir/memory_contradictions.json`)Guarde un juego de cada uno. Simplemente elimine la línea de sesión.,Eje de memoria de la página de memoria.
 * Sigue presionando `session_summaries` con `facts` renderizado,Para que los usuarios puedan seguir viéndolo después de eliminar la conversación.
 * `### hechos importantes` / `Summary` tarjetas.
 *
 * Dos duras limitaciones:
 *  - **idempotente**:No se informará ningún error si se realizan llamadas repetidas y no se realizará ninguna eliminación repetida.(Las filas eliminadas se eliminarán nuevamente y solo se eliminarán 0 filas.,Las lápidas se escriben con IGNORE);
 *  - **Aislamiento de fallas**:Capture cada paso de forma independiente,Solo recuerda el fracaso warn,No afecta los pasos posteriores. —— La limpieza es el toque final,
 *    No puede ser por un determinado vínculo.(Por ejemplo, una determinada biblioteca sucursal está ocupada)El fallo arrastrará todo el proceso de eliminación.
 *
 * La eliminación de hechos debe escribir una lápida:Lado de compilación([io.zer0.memory.compile.MemoryCompiler])Resumen de filtrado por lápida
 * viejas frases de,Lápida faltante,La próxima ronda de compilación tratará el contenido recién eliminado como"Resumen huérfano"Vuelva a ingresar el formulario(resurrección).
 */
class SessionMemoryCleanup(
    private val summaryDao: SessionSummaryDao,
    private val checkpointDao: ContextCheckpointDao,
    private val scopedSectionDao: ScopedCompiledSectionDao,
    private val groupChatMemoryDao: GroupChatMemoryDao,
    private val factDbProvider: FactDbProvider,
    private val fileWriter: MemoryFileWriter,
    private val contradictionStore: MemoryContradictionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Limpiar los datos de la memoria de un lote de sesiones.(Resumen, punto de control, hecho y sus filas derivadas).
     *
     * La ruta de eliminación temporal también va aquí. La compensación es clara:La expectativa de que el usuario presione eliminar es"Si lo eliminas, desaparecerá.",
     * Entonces, incluso dentro de los 7 días posteriores a"Eliminado recientemente"reanudar la sesión en,El recuerdo no volverá ——
     * Restaurar restaura solo mensajes,El resumen y los hechos no se reconstruyen.
     *
     * @param sessionIds sesión id;Se ignorarán los ID en blanco y los ID duplicados.,La colección vacía se devuelve directamente.
     */
    suspend fun cleanSessions(sessionIds: Collection<String>) {
        val ids = sessionIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return
        withContext(ioDispatcher) {
            var summaries = 0
            var checkpoints = 0
            var facts = 0
            ids.forEach { sessionId ->
                summaries += step("session_summaries[$sessionId]") { deleteSummary(sessionId) }
                checkpoints += step("context_checkpoints[$sessionId]") { deleteCheckpoint(sessionId) }
                facts += deleteFactsOfSession(sessionId)
            }
            Logger.i(TAG, "Borrado de la memoria de sesión completado: sessions=${ids.size} Resumen=$summaries punto de control=$checkpoints hechos=$facts")
        }
    }

    /**
     * Borrar los datos de la memoria de un asistente.(Resúmenes, párrafos compilados, memorias de chats grupales, catálogos de productos, listas de contradicciones, hechos.).
     *
     * solo borra la memoria,No eliminar líneas de sesión:Se conservan las conversaciones que ya pertenecen al asistente.,Simplemente suelta la encuadernación.
     *
     * @param assistantId asistente id;La identificación en blanco se devuelve directamente
     */
    suspend fun cleanAssistant(assistantId: String) {
        val id = assistantId.trim()
        if (id.isBlank()) return
        val scope = scopeOf(id)
        withContext(ioDispatcher) {
            val summaries = step("session_summaries[assistant=$id]") { summaryDao.deleteByAssistant(id) }
            val sections = step("compiled_sections_scoped[scope=$scope]") { scopedSectionDao.deleteByScope(scope) }
            val groupChat = step("group_chat_memories[$id]") { groupChatMemoryDao.deleteByAssistant(id) }
            val files = step("memory/$scope") { fileWriter.deleteScopeDir(scope) }
            val contradictions = step("memory_contradictions[$scope]") { contradictionStore.clearScope(scope) }
            val facts = deleteFactsOfScope(scope)
            Logger.i(
                TAG,
                "Se completó el borrado de la memoria del asistente: assistant=$id scope=$scope Resumen=$summaries párrafo=$sections " +
                    "Memoria de chat grupal=$groupChat contradicción=$contradictions hechos=$facts Archivo=$files",
            )
        }
    }

    /**
     * El alcance de la memoria del ayudante.
     *
     * asistente principal(`default`)grupo de memoria principal para `main` hacer scope,El alcance de los demás asistentes es tuyo. id ——
     * con lado de compilación([io.zer0.memory.compile.MemoryCompileTarget])El calibre del valor se mantiene constante.
     */
    private fun scopeOf(assistantId: String): String = if (assistantId == MAIN_ASSISTANT_ID) MAIN_SCOPE else assistantId

    /** La tabla de resumen elimina una sola fila por session_id;Léelo una vez primero solo para estadísticas."De hecho, eliminé algunos". */
    private suspend fun deleteSummary(sessionId: String): Int {
        val existed = summaryDao.get(sessionId) != null
        summaryDao.deleteById(sessionId)
        return if (existed) 1 else 0
    }

    /** Puede haber como máximo un punto de control por sesión. */
    private suspend fun deleteCheckpoint(sessionId: String): Int {
        val existed = checkpointDao.get(sessionId) != null
        checkpointDao.deleteById(sessionId)
        return if (existed) 1 else 0
    }

    /**
     * Recorra la base de datos principal y cada base de datos asistente para eliminar los hechos de la sesión.
     *
     * Los hechos se almacenan en cubos auxiliares.,Borrar solo la base de datos principal `facts.db` será extrañado `facts_<assistantId>.db` filas en,
     * Y la página de la memoria"hechos importantes"Es a partir de estas líneas que se lee la tarjeta.
     */
    private suspend fun deleteFactsOfSession(sessionId: String): Int {
        var removed = 0
        factStores().forEach { (assistantId, store) ->
            removed += step("facts[session=$sessionId, db=$assistantId]") { store.deleteBySession(sessionId) }
        }
        return removed
    }

    /** Recorra la biblioteca principal y cada biblioteca auxiliar para eliminar el hecho de que el alcance es(Es posible que la biblioteca principal tenga líneas históricas de asistentes eliminados.). */
    private suspend fun deleteFactsOfScope(scope: String): Int {
        var removed = 0
        factStores().forEach { (assistantId, store) ->
            removed += step("facts[scope=$scope, db=$assistantId]") { store.deleteAllByScope(scope) }
        }
        return removed
    }

    /**
     * Biblioteca principal + subbiblioteca real en disco.
     *
     * La identificación de la rama se enumera a partir del nombre del archivo en lugar de enviarse desde la tabla auxiliar.:Los ayudantes eliminados ya no tienen archivos de biblioteca,
     * Si lo adivinas por id, saldrá de la nada. `Room.databaseBuilder(...).build()` Vacíe la biblioteca y déjela en el disco.
     */
    private fun factStores(): List<Pair<String, FactStore>> =
        (listOf(MAIN_ASSISTANT_ID) + factDbProvider.listAssistantDbIds()).map { it to factDbProvider.getFactStore(it) }

    /**
     * Ejecución en un solo paso:En caso de fallo, avisar y regresar. 0,No afecta los pasos posteriores de la persona que llama.
     *
     * Sólo se tragan las excepciones ordinarias. —— La cancelación de rutina debe seguir siendo lanzada,De lo contrario, el proceso de eliminación continuará completándose después de su cancelación.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun step(label: String, block: suspend () -> Int): Int = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Logger.w(TAG, "Error al borrar la memoria[$label]: ${t.message}", t)
        0
    }

    private companion object {
        private const val TAG = "SessionMemoryCleanup"

        /** asistente principal id(El hecho radica en `facts.db`). */
        private const val MAIN_ASSISTANT_ID = "default"

        /** El nombre de dominio de alcance del hecho auxiliar principal. */
        private const val MAIN_SCOPE = "main"
    }
}
