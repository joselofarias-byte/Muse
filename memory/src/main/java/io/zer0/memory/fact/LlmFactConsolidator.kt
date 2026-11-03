package io.zer0.memory.fact

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.0.92: LLM Consolidador de memoria — "Integración de modelo regular"La implementación central de(Clústeres similares se entregan a grandes modelos para su fusión).
 *
 * fondo: Anteriormente, la línea de montaje diaria solo tenía reglas para eliminar duplicados.([FactStore.dedupPass],Similitud de personajes/Clave de entidad),
 * Memoria con semántica repetida pero expresiones muy diferentes.(Como por ejemplo"Mañana prueba CET-4" vs "mañana por la mañana9Prueba de inglés CET-4")
 * Se acumulará durante mucho tiempo. Esta categoría maneja [FactStore.findSimilarGroups] Los grupos similares reunidos se entregan al modelo grande y se fusionan en uno solo.,
 * Mantenga toda la información clave;con manuales"organizar la memoria"compartir la misma implementación,Activado automáticamente por la canalización diaria.
 *
 * política de seguridad(Ning Le es bueno):
 *  - Falló el LLM de un solo clúster/tiempo de espera → omitir este grupo,No afecta a otros grupos ni al proceso principal.;
 *  - La modelo regresa sospechosa"Salida no fusionada tal como está"(número de residuo / __SKIP__) → abandonar el grupo;
 *  - La longitud del resultado combinado supera la longitud total de todos los textos originales del grupo. → tratado como salida anormal,abandonar el grupo;
 *  - Los recuerdos fijados no se incluyen en la combinación(Usuario fijado activamente);
 *  - Los cambios clave en la memoria son capturados por el historial de revisiones de FactStore,Se puede rastrear.
 */
class LlmFactConsolidator(
    private val llmClient: MemoryLlmClient,
    /** modelo objetivo,null cuando [MemoryLlmClient] La implementación utiliza el modelo predeterminado. */
    private val model: Model? = null,
) {

    /**
     * Realiza una consolidación de fusión LLM en el alcance especificado.
     *
     * @return El número real de clústeres fusionados.(0 = No hay clústeres para fusionar o todos fallaron)
     */
    suspend fun consolidate(store: FactStore, scope: String, spaceId: String, maxGroups: Int = DEFAULT_MAX_GROUPS): Int =
        withContext(Dispatchers.IO) {
            val groups = resultOf { store.findSimilarGroups(scope, spaceId, maxGroups) }
                .onError { msg, t -> Logger.w(TAG, "No se pudieron encontrar grupos de memoria similares: ${t?.message ?: msg}") }
                .getOrNull() ?: return@withContext 0

            var mergedCount = 0
            for (group in groups) {
                if (group.size < 2) continue
                // Protección de memoria superior: Los clústeres que contienen entradas fijadas no participan en la fusión automática
                if (group.any { it.pinnedAt != null }) continue
                val mergedText = mergeGroupWithLlm(group) ?: continue
                // Mantenga la acción más importante keeper
                val keeper = group.maxByOrNull { it.importance } ?: group.first()
                // v2.6.2(auditoría de memoria): Cambiar a la combinación atómica FactStore.mergeIntoKeeper —— keeper Actualizado con
                // La eliminación del miembro restante se realiza dentro de la misma transacción de Room. antiguo proceso“Primero elimine los elementos uno por uno y luego update keeper”no en el mismo
                // negocios:Cuando la eliminación parcial se realiza correctamente pero la actualización del guardián falla, los datos originales se han perdido.,“escribir de nuevo”Solo agrega uno nuevo,
                // Los campos originales de datos eliminados no se restaurarán/fijar hacia arriba/etiqueta/enlace/Revisión.
                // Otra nota:desafinado update() Deduplicación automática interna,Evite la superposición con la eliminación dentro del grupo“doble eliminación”.
                val removableIds = group.filter { it.id != keeper.id }.map { it.id }
                val removed = resultOf {
                    store.mergeIntoKeeper(
                        keeperId = keeper.id,
                        mergedContent = mergedText,
                        removableIds = removableIds,
                        scope = scope,
                        spaceId = spaceId,
                    )
                }.onError { msg, t -> Logger.w(TAG, "Error en la memoria de fusión atómica: ${t?.message ?: msg}") }
                    .getOrNull()
                if (removed != null && removed >= 0) mergedCount++
            }
            if (mergedCount > 0) {
                Logger.i(TAG, "LLM integración de memoria: fusionar $mergedCount memoria de repetición de grupo (scope=$scope)")
            }
            mergedCount
        }

    /**
     * Recorra todas las combinaciones de alcance + espacio en la biblioteca para realizar la integración(Para uso diario en línea de montaje).
     *
     * @return El número real de clústeres fusionados.
     */
    suspend fun consolidateAll(store: FactStore, maxGroups: Int = DEFAULT_MAX_GROUPS): Int {
        val pairs = resultOf { store.listScopeSpacePairs() }
            .onError { msg, t -> Logger.w(TAG, "No se pudo enumerar el espacio de memoria: ${t?.message ?: msg}") }
            .getOrNull() ?: return 0
        var total = 0
        for ((scope, spaceId) in pairs) {
            total += consolidate(store, scope, spaceId, maxGroups)
        }
        return total
    }

    /**
     * v19: Fusión manual —— Combina varias memorias seleccionadas por el usuario en una**borrador**,Sólo se devuelve el texto sin salir de la biblioteca.
     *
     * Comparta el mismo conjunto de indicaciones y barreras de seguridad con integración automática([mergeTexts]):Mantenga toda la información clave, elimine duplicados,
     * `__SKIP__` Indica que la salida no debe fusionarse, es demasiado larga o parece que se muestra tal cual y se considera no válida. Solo hay dos diferencias:
     *  - El límite de producción es más relajado([MANUAL_MERGE_MAX_TOKENS]):Las escenas manuales pueden seleccionar 5 recuerdos largos a la vez;
     *  - Los elementos fijados ya no están excluidos:El usuario lo seleccionó explícitamente para fusionarlo.,La protección adhesiva solo sirve para rutas automáticas.
     *
     * @param texts Participa en el texto de la memoria fusionada.(Los elementos en blanco se ignoran)
     * @return Fusionar borrador;Se devuelve cuando hay menos de 2 elementos, el modelo se omite explícitamente, el resultado es anormal o se agota el tiempo de espera. null
     */
    suspend fun synthesize(texts: List<String>): String? {
        val cleaned = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.size < 2) return null
        return try {
            mergeTexts(cleaned, MANUAL_MERGE_MAX_TOKENS, MANUAL_MERGE_TIMEOUT_MS)
        } catch (e: TimeoutCancellationException) {
            // También se genera el tiempo de espera del propio cliente. TimeoutCancellationException(Trae el tuyo coroutine),
            // No pertenece a la cancelación del llamante.,prensa“No sintetizado esta vez”Rebajar;Las cancelaciones reales siguen tirando al alza.
            Logger.w(TAG, "Tiempo de espera de compilación de fusión manual: ${e.message}")
            null
        }
    }

    /** Llame a LLM para fusionar un grupo de recuerdos similares en uno;Devuelve nulo en caso de error, omisión explícita o sospecha de no fusión. */
    private suspend fun mergeGroupWithLlm(group: List<FactStore.Fact>): String? =
        mergeTexts(group.map { it.fact }, AUTO_MERGE_MAX_TOKENS, MERGE_TIMEOUT_MS)

    /**
     * Implementación común de integración automática y fusión manual.:El mismo conjunto de indicaciones y el mismo conjunto de barreras de seguridad de salida.
     *
     * @param texts Texto participante en la fusión(Número en orden de visualización prompt)
     * @param maxTokens Límite superior de salida
     * @param timeoutMs El tiempo de espera para esta llamada
     */
    private suspend fun mergeTexts(texts: List<String>, maxTokens: Int, timeoutMs: Long): String? {
        val totalLength = texts.sumOf { it.length }
        val user = buildString {
            appendLine("Son recuerdos con múltiples contenidos repetitivos o muy similares los siguientes:,Por favor fusionarlos en uno:Mantenga toda la información crítica")
            appendLine("(Nombres, horas, lugares, números, eventos.),Eliminar duplicados,Lenguaje natural y conciso.,No omita ningún detalle fáctico.")
            appendLine("Si se descubre que estos recuerdos en realidad tienen contenidos diferentes y no deben fusionarse,Por favor envíe solo una cadena __SKIP__.")
            appendLine()
            texts.forEachIndexed { idx, text -> appendLine("${idx + 1}. $text") }
            appendLine()
            append("Envíe la memoria fusionada directamente.,Sin prefijos, números ni comillas:")
        }
        val result = resultOf {
            withTimeoutOrNull(timeoutMs) {
                llmClient.callText(
                    systemPrompt = SYSTEM_PROMPT,
                    userContent = user,
                    model = model,
                    temperature = 0.3f,
                    maxTokens = maxTokens,
                    timeoutMs = timeoutMs,
                ).trim()
            }
        }.onError { msg, t -> Logger.w(TAG, "LLM No se pudieron fusionar recuerdos: ${t?.message ?: msg}") }
            .getOrNull() ?: return null

        // barandillas heurísticas: Salto explícito/salida de excepción/salida sospechada no fusionada tal como está
        val text = result.trim().trim('"', '\'', '「', '\"', '“', '”').trim()
        val looksUnmerged = text.length > totalLength ||
            text.startsWith("1.") || text.startsWith("1,") ||
            text.contains("\n2.") || text.contains("\n2,")
        return when {
            text.isBlank() || text == SKIP_MARKER || looksUnmerged -> null
            else -> text.takeIf { it.length > 2 }
        }
    }

    companion object {
        private const val TAG = "LlmFactConsolidator"

        /** El número máximo de clústeres similares procesados en una sola integración.(Limitar el número de llamadas de LLM). */
        const val DEFAULT_MAX_GROUPS = 10

        /** modelo de representación explícita"no debe fusionarse"marca. */
        private const val SKIP_MARKER = "__SKIP__"

        /** Tiempo de espera de LLM de un solo clúster(milisegundos). */
        private const val MERGE_TIMEOUT_MS = 30_000L

        /** Integrar automáticamente el límite superior de salida de un solo grupo:La cantidad de elementos en un grupo está limitada por el algoritmo de agrupación.,El contenido suele ser breve. */
        private const val AUTO_MERGE_MAX_TOKENS = 300

        /**
         * Fusionar manualmente límites de salida:El usuario puede seleccionar múltiples memorias largas a la vez.,300 token Se truncará en media frase.,
         * El resultado truncado parece“Fusión exitosa”Pero perdí el hecho,Peor que el fracaso.
         */
        private const val MANUAL_MERGE_MAX_TOKENS = 1_200

        /** Tiempo de espera de fusión manual:Ingrese más tiempo,Dale al modelo un tiempo más amplio que la ruta automática. */
        private const val MANUAL_MERGE_TIMEOUT_MS = 45_000L

        private const val SYSTEM_PROMPT = "Eres un organizador de recuerdos. Tu tarea es fusionar los recuerdos duplicados del mismo grupo en uno:" +
            "Mantenga toda la información crítica,Eliminar duplicados,El lenguaje es natural y conciso. Preferiría no fusionarse,Tampoco inventes ni pierdas información clave."
    }
}
