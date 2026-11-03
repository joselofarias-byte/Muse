package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.transformer.ContextCompressTransformer
import kotlin.uuid.Uuid

/**
 * v2.x Refactorizar S2: [ChatCompressionCoordinator] Acceda a la interfaz de puente estrecho de ChatViewModel.
 *
 * La orquestación de compresión requiere acceso al lector de puntos de control, al compresor, al estado y algunas devoluciones de llamada de la VM. Usar interfaz explícita
 * reclamar estos accesos,En lugar de pasar el ViewModel como un todo —— La superficie de dependencia es visible, mensurable y reemplazable.
 *
 * Propiedades de migración:Extracción pura.VM Implementado con objetos anónimos.,Todo por**Reenviar directamente**,Sin lógica.
 */
internal interface ChatCompressionHostBridge {

    // ── depender de ───────────────────────────────────────────────────────────

    val stateStore: ChatStateStore
    val appContext: android.content.Context
    val settings: SettingsRepository
    val sessionRepository: SessionRepository
    val checkpointReader: io.zer0.memory.summary.ContextCheckpointReader
    val contextCompressTransformer: ContextCompressTransformer
    val memoryTicker: io.zer0.memory.ticker.MemoryTicker
    val coroutineScope: kotlinx.coroutines.CoroutineScope

    // ── Estado(Marca de agua variable para VM original)───────────────────────────────────────

    /** Originales `checkpointCoveredSessionId`(leer y escribir). */
    var checkpointCoveredSessionId: String?

    /** Originales `checkpointCoveredIds`(leer y escribir). */
    var checkpointCoveredIds: Set<String>

    // ── Reenvío del método de VM original ─────────────────────────────────────────────────

    fun displayedSessionId(): String?
    fun reportError(message: String)
    fun shouldAutoCompress(currentTokens: Int, maxTokens: Int): Boolean
    suspend fun updateContextTokenCount()
    suspend fun refreshCheckpointCoveredIds(sessionId: String)
    suspend fun refreshContextInfo()

    /** Originales `newlyCoveredCount`(Funciones puras que han hundido al compañero). */
    fun newlyCoveredCount(messages: List<UIMessage>, coveredIds: Set<String>, previousBoundaryId: String?): Int
}

/** Alias de tipo de ID de mensaje para puente,Evite repetir el nombre completo en todas partes. */
internal typealias CompMessageId = Uuid
