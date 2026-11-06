package io.zer0.muse.data.subagent

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.0.53: Persistencia JSONL del historial de sesiones del agente secundario.
 *
 * camino: filesDir/subagent_sessions/<threadId>.jsonl
 * Un JSON por línea: serialización de UIMessage
 *
 * estrategia de cultivo(Estimado por token):
 *  - usar [TokenEstimator] Estimación acumulada token,Recortar desde el encabezado cuando se excede maxContextTokens
 *  - Mantener mensajes del SISTEMA(Las palabras de aviso del sistema de subagentes nunca se cortarán)
 *
 * tolerancia a fallas:
 *  - Línea ilegal encontrada mientras leía(dañado)Saltar y log warn,No lanzar excepción
 *  - Devuelve una lista vacía si el archivo no existe(Tratar como un hilo nuevo)
 */
class SubagentSessionStore(
    private val sessionsDir: File,
    private val tokenEstimator: TokenEstimator = TokenEstimator,
    private val persistenceCipher: SecureKeyCipher = SecureKeyStore,
) {
    companion object {
        private const val TAG = "SubagentSessionStore"
        private val SAFE_THREAD_ID = Regex("^[a-zA-Z0-9_:-]{1,128}$")
    }

    init {
        if (!sessionsDir.exists()) sessionsDir.mkdirs()
    }

    /**
     * Agregar un lote de mensajes al archivo de sesión del hilo(escritura incremental).
     * Crea automáticamente el archivo si no existe.
     */
    suspend fun append(threadId: String, messages: List<UIMessage>): io.zer0.common.Result<Unit> = withContext(Dispatchers.IO) {
        resultOf {
            if (messages.isEmpty()) return@resultOf
            val file = pathOf(threadId)
            file.parentFile?.mkdirs()
            val encoded = buildString {
                messages.forEach { msg ->
                    val plain = AppJson.encodeToString(UIMessage.serializer(), msg)
                    append(persistenceCipher.encrypt(plain)).append('\n')
                }
            }
            file.appendText(encoded)
        }
    }

    /**
     * Cargar historial de sesión para hilo,Corta el encabezado de acuerdo con el presupuesto del token.
     *
     * @param maxContextTokens límite superior de recorte(Predeterminado 6000,Aproximadamente de la ventana de contexto del subasistente 50%)
     * @return Lista de mensajes recortada(SYSTEM Retención de mensajes + últimos N mensajes que no son del SISTEMA)
     */
    suspend fun load(threadId: String, maxContextTokens: Int = 6000): List<UIMessage> = withContext(Dispatchers.IO) {
        val file = pathOf(threadId)
        if (!file.exists()) return@withContext emptyList()

        val all = mutableListOf<UIMessage>()
        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val parsed = resultOf {
                    val plain = persistenceCipher.decryptOrNull(line)
                        ?: error("subagent session line could not be decrypted")
                    AppJson.decodeFromString(UIMessage.serializer(), plain)
                }.getOrNull()
                if (parsed != null) {
                    all.add(parsed)
                } else {
                    Logger.w(TAG, "Saltar líneas JSONL corruptas: ${line.take(80)}")
                }
            }
        }

        if (all.isEmpty()) return@withContext emptyList()

        // Separe los mensajes del SISTEMA y los que no son del SISTEMA
        val systemMsgs = all.filter { it.role == MessageRole.SYSTEM }
        val nonSystem = all.filter { it.role != MessageRole.SYSTEM }

        // Acumular desde el final hacia adelante token,Detener cuando se excede el límite
        val kept = mutableListOf<UIMessage>()
        var spent = 0
        for (msg in nonSystem.asReversed()) {
            val cost = tokenEstimator.estimate(msg.content) + 4
            if (spent + cost > maxContextTokens && kept.isNotEmpty()) break
            spent += cost
            kept.add(0, msg) // Mantener el orden original
        }

        // SYSTEM antes + no después de recortar SYSTEM
        return@withContext systemMsgs + kept
    }

    /** Eliminar el archivo de sesión del hilo(close llamada opcional). */
    suspend fun delete(threadId: String): io.zer0.common.Result<Unit> = withContext(Dispatchers.IO) {
        resultOf {
            val file = pathOf(threadId)
            if (file.exists()) file.delete()
        }
    }

    /** Diagnóstico:Obtener la ruta del archivo de sesión del hilo(puede que no exista). */
    fun pathOf(threadId: String): File {
        require(threadId.matches(SAFE_THREAD_ID)) {
            "subagent thread id must be a safe filename token"
        }
        val root = sessionsDir.canonicalFile
        val target = File(root, "$threadId.jsonl").canonicalFile
        require(target.parentFile == root) {
            "subagent session path escapes its directory"
        }
        return target
    }
}
