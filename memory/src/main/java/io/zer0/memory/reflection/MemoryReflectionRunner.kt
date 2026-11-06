package io.zer0.memory.reflection

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v12 (T3-1/3-2/3-3): tarea de reflexión de la memoria — La capa de organización activa del sistema de memoria.
 *
 * gatillo: Fin del pipeline diario(deepMemory después)ejecución de baja frecuencia;El fallo no bloquea el proceso principal.
 * Responsabilidades(versión de regla determinista,LLM Mejorar y permanecer para seguimiento):
 *  1. Rellenar claves de entidad históricas(entity_key Datos de inventario que son nulos)
 *  2. Fusionar hechos duplicados con la misma clave de entidad(resolver"3 artículos con el mismo nombre de usuario"limpieza de inventario)
 *  3. Detectar afirmaciones contradictorias sobre la misma entidad → Generar lista por confirmar(No eliminar automáticamente)
 *  4. Repetir confirmación de promoción(Múltiples hechos sobre la misma entidad → mayor importancia)
 *  5. D6 Número 2: Detección de múltiples afirmaciones para la misma entidad → generar“Combinar sugerencias”candidato(Sólo estadísticas,Sin fusión automática)
 *
 * Posicionamiento: con [FactStore] Compruebe si hay plagio mientras escribe(camino rápido)complementario — Repetición histórica imparable durante la escritura,
 * Organizado periódicamente en segundo plano mediante tareas de reflexión.,forma\"Escribir para evitar incrementos + reflexionar sobre el inventario\"Bucle cerrado de doble capa.
 */
class MemoryReflectionRunner(
    private val factStore: FactStore,
    /**
     * P2-32: Exportación de lista de conflictos(scope, spaceId, par contradictorio) — La implementación de persistencia la inyecta el host,
     * Las contradicciones detectadas se almacenan en el centro de memoria para su visualización.null = No inyectado(entorno de prueba,Solo devuelve estadísticas).
     */
    private val contradictionSink: (suspend (String, String, List<Pair<FactStore.Fact, FactStore.Fact>>) -> Unit)? = null,
    /**
     * v2.6.2(auditoría de memoria): prensa (scope, spaceId) Analizar el FactStore de destino.
     *
     * La implementación anterior solo inyecta la biblioteca predeterminada [factStore], pero la canalización diaria reutiliza el objetivo actual
     * scope/space Ajustar esta categoría —— Las solicitudes de reflexión para subayudantes u otros espacios se ejecutarán en la biblioteca predeterminada.
     * El resultado está vacío o la biblioteca está organizada incorrectamente. Retroceso cuando está vacío [factStore](Mantenga el comportamiento anterior compatible con las pruebas).
     */
    private val storeResolver: ((String, String) -> FactStore)? = null,
) {

    private fun resolveStore(scope: String, spaceId: String): FactStore = storeResolver?.invoke(scope, spaceId) ?: factStore

    /**
     * Realice una ronda completa de reflexión.
     *
     * @param scope alcance de la memoria(Predeterminado main)
     * @param spaceId espacio de memoria(Predeterminado default)
     * @return Estadísticas para esta ronda
     */
    suspend fun runReflection(scope: String = "main", spaceId: String = "default"): ReflectionResult = withContext(Dispatchers.IO) {
        val store = resolveStore(scope, spaceId)
        // 1. Rellenar claves de entidad(Requisitos previos para la fusión)
        val backfilled = resultOf { store.backfillEntityKeys(scope, spaceId) }
            .onError { msg, t -> Logger.w(TAG, "Error en la clave de entidad de relleno de reflexión: ${t?.message ?: msg}") }
            .getOrNull() ?: 0

        // 2. Fusionar duplicados con las mismas entidades
        val merged = resultOf { store.mergeSameEntityDuplicates(scope, spaceId) }
            .onError { msg, t -> Logger.w(TAG, "Fallo duplicado en la fusión de reflejos: ${t?.message ?: msg}") }
            .getOrNull() ?: 0

        // 3. Detección de contradicciones(grabar solo,No eliminar automáticamente — Proceso después de la confirmación del usuario)
        val contradictions = resultOf { store.detectContradictions(scope, spaceId) }
            .onError { msg, t -> Logger.w(TAG, "Reflexión sobre el fracaso en la detección de conflictos: ${t?.message ?: msg}") }
            .getOrNull() ?: emptyList()
        // P2-32: No más contradicciones“ralentí”(Sólo inicia sesión) — a través del fregadero,La interfaz de usuario del Centro de memoria es visible y borrable
        // v18: Los resultados vacíos también se almacenarán en la biblioteca. —— Los conflictos que han sido arbitrados por reemplaza al escribir ya no aparecen en la lista,
        // En caso contrario, el usuario confirmará repetidamente el conflicto resuelto.(La limpieza por sí sola no eliminará ninguna memoria.).
        contradictionSink?.let { sink ->
            resultOf { sink(scope, spaceId, contradictions) }
                .onError { msg, t -> Logger.w(TAG, "La lista de conflictos no se pudo colocar en la base de datos: ${t?.message ?: msg}") }
        }

        // 4. Repetir confirmación de promoción(Misma entidad ≥2 Artículo → importancia +1)
        val promoted = resultOf { store.promoteRepeatedFacts(scope, spaceId) }
            .onError { msg, t -> Logger.w(TAG, "Reflexión sobre el fracaso de la promoción: ${t?.message ?: msg}") }
            .getOrNull() ?: 0

        // 5. D6 Número 2: Detección de múltiples afirmaciones para la misma entidad — generar“Combinar sugerencias”candidato(certeza,Sólo las estadísticas no se fusionan automáticamente)
        val mergeSuggestions = resultOf { store.findSameEntityMultiAssertionGroups(scope, spaceId) }
            .onError { msg, t -> Logger.w(TAG, "Error en la detección de múltiples afirmaciones para la misma entidad: ${t?.message ?: msg}") }
            .getOrNull()?.size ?: 0
        if (mergeSuggestions > 0) {
            Logger.i(TAG, "Múltiples afirmaciones para la misma entidad: $mergeSuggestions Las entidades pueden ser consideradas para fusionarse.(Sólo recomendado,No se han cambiado datos)")
        }

        val result = ReflectionResult(
            backfilled = backfilled,
            merged = merged,
            contradictions = contradictions.size,
            promoted = promoted,
            mergeSuggestions = mergeSuggestions,
        )
        if (result.hasWork) {
            Logger.i(TAG, "Reflexión de la memoria completada.: Rellenar claves de entidad=$backfilled, fusionar duplicados=$merged, par contradictorio=$contradictions.size, promoción=$promoted")
        }
        result
    }

    data class ReflectionResult(
        val backfilled: Int = 0,
        val merged: Int = 0,
        val contradictions: Int = 0,
        val promoted: Int = 0,
        /** D6 Número 2: Número de entidades con múltiples afirmaciones para la misma entidad(Fusionar candidatos de propuesta,Sin fusión automática). */
        val mergeSuggestions: Int = 0,
    ) {
        val hasWork: Boolean get() = backfilled + merged + contradictions + promoted > 0
    }

    private companion object {
        const val TAG = "MemoryReflection"
    }
}
