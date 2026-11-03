package io.zer0.muse.data.audit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * F-08: Agent Run Libro mayor de memoria de recibos — mantener reciente [MAX_RECORDS] Recibo de ejecución de la herramienta del artículo.
 *
 * diseño:
 *  - Libro mayor de anillos de memoria(Mantenga solo registros recientes,No persistente;La persistencia va AuditLogger.category="agent_run");
 *  - para la página de depuración/La página de auditoría muestra la cadena de llamadas recientes a la herramienta del Agente(Contiene el nivel parentRunId);
 *  - Seguridad del hilo:todas las operaciones en [MutableStateFlow] Completado dentro de la actualización.
 */
class AgentRunTracker {

    companion object {
        /** Límite de retención de memoria(Últimos N artículos,impedir el crecimiento ilimitado). */
        const val MAX_RECORDS = 200
    }

    private val _records = MutableStateFlow<List<AgentRunRecord>>(emptyList())

    /** Recibo de ejecución reciente de la herramienta(nuevo→viejo). */
    val records: Flow<List<AgentRunRecord>> = _records.asStateFlow()

    /** Registrar un recibo de ejecución de herramienta,Las entradas más antiguas se descartan cuando se excede el límite superior. */
    fun record(entry: AgentRunRecord) {
        _records.update { current ->
            val next = (listOf(entry) + current).take(MAX_RECORDS)
            next
        }
    }

    /** Borrar(para depurar). */
    fun clear() {
        _records.value = emptyList()
    }
}
