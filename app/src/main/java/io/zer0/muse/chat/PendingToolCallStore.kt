package io.zer0.muse.chat

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Reanudar la carga desde el punto de interrupción(Recuperación de interrupción de herramientas)— Persistir en las llamadas de herramientas pendientes.
 *
 * fondo:
 *  - Conversación en streaming,LLM Después de tomar decisiones, llame a herramientas_llamadas,ChatViewModel Cada herramienta se ejecuta en secuencia y el mensaje HERRAMIENTA se completa.
 *  - Si el usuario detiene manualmente la transmisión/la aplicación falla/el proceso se cancela,La cola tool_calls que se está ejecutando se descartará,
 *    Como resultado, la IA no puede obtener resultados de herramientas.,Pierde contexto la próxima vez que respondas(El modelo sólo se ve emitiendo tool_calls
 *    Pero no existe una respuesta de herramienta correspondiente.,Algunos proveedores informarán errores directamente).
 *
 * diseño:
 *  - Archivo JSON para persistencia(filesDir/pending_tool_calls.json),No salgas de la habitación.
 *    Razón:Pequeña cantidad de datos(Varios cientos de bytes por tool_call), ciclo de vida corto(Eliminar después de la ejecución),
 *    Evite agregar versión de esquema + costo de migración a MuseDb.
 *  - Todo el archivo IO pasa por la serialización IO Dispatcher + Mutex,Garantizar la seguridad del hilo.
 *  - Mantener original cuando se restablezcan las llamadas de herramientas [PendingToolCall.toolCallId],Deje que LLM pueda corresponder a tool_call_id.
 */
@Suppress("TooManyFunctions")
object PendingToolCallStore {

    private const val TAG = "PendingToolCallStore"
    private const val FILE_NAME = "pending_tool_calls.json"

    /** Lectura y escritura de archivos serializados.,Evite sobrescrituras de escritura simultáneas. */
    private val mutex = Mutex()

    /** Un registro persistente de llamadas a herramientas pendientes. */
    @Serializable
    data class PendingToolCall(
        /** Perteneciente a la sesión id(sessionId),Se utiliza para consultar por dimensión de sesión./Limpiar. */
        val chatId: String,
        /** Sólo llamada de herramienta id(corresponder OpenAI tool_call_id),Debe conservarse al rellenar mensajes de HERRAMIENTA. */
        val toolCallId: String,
        /** Nombre de la herramienta(Skill id o nombre de la herramienta local),Se utiliza para enrutar al reanudar la ejecución. */
        val toolName: String,
        /** Cadena JSON del parámetro de herramienta(LLM el original pasó arguments). */
        val arguments: String,
        /** Crear marca de tiempo(milisegundos),Se utiliza para solucionar problemas y limpiar tiempos de espera. */
        val createdAt: Long,
        /** Fase de ejecución: EXECUTING / APPROVAL_PENDING / ABORTED. Los registros antiguos se tratan como EN EJECUCIÓN de forma predeterminada. */
        val executionState: String = EXECUTING,
        /** Generar identidades intergeneracionales; Los registros antiguos aún se pueden restaurar de forma compatible mediante chatId si están vacíos. */
        val generationId: String? = null,
        val turnId: String? = null,
        /** Cancele el origen o el motivo de la interrupción y no guarde la pila de excepciones ni el contenido sensible al usuario. */
        val abortReason: String? = null,
    )

    /** Ruta del archivo de persistencia(Inicialización diferida,Confirmado en la primera visita. Context). */
    @Volatile
    private var fileRef: File? = null

    /**
     * Pending arguments may contain passwords, clipboard text, or browser input. Keep the
     * recovery file encrypted at rest; tests may inject a deterministic cipher.
     */
    @Volatile
    internal var persistenceCipher: SecureKeyCipher = SecureKeyStore

    /**
     * Ruta del archivo de inicialización. Debe llamarse una vez cuando se inicia la aplicación.(de [MuseApp] O cuando se inyecta el módulo Koin).
     * Se puede usar sin llamar — primer tiempo [loadAll] Utilizará applicationContext para averiguarlo.
     */
    fun init(context: Context) {
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        // prueba/Un entorno multiproceso puede proporcionar diferentes contextos de aplicación sucesivamente; cuando el camino cambia, hay que volver a hacerlo.
        // De lo contrario, la lectura y escritura recaerán en el directorio de archivos antiguo y el aislamiento del archivo dañado y el estado de recuperación se distorsionarán.
        if (fileRef?.absolutePath != target.absolutePath) {
            synchronized(this) {
                if (fileRef?.absolutePath != target.absolutePath) {
                    fileRef = target
                }
            }
        }
    }

    /** interno:Obtener el archivo actual(Si no se inicializa, se informará y devolverá un error. null,La persona que llama debe manejar). */
    private fun file(): File? = fileRef ?: run {
        Logger.w(TAG, "fileRef no inicializado,Por favor llame primero init(context);Esta operación será ignorada.")
        null
    }

    /** interno:Leer toda la lista de pendientes del expediente(Se devolverá un archivo vacío o una tabla vacía si falla el análisis). */
    private suspend fun loadAll(): List<PendingToolCall> = withContext(Dispatchers.IO) {
        val f = file() ?: return@withContext emptyList()
        if (!f.exists()) return@withContext emptyList()
        resultOf {
            val stored = f.readText()
            val text = persistenceCipher.decryptOrNull(stored)
                ?: error("pending tool-call file could not be decrypted")
            if (text.isBlank()) {
                emptyList()
            } else {
                AppJson.decodeFromString(ListSerializer(PendingToolCall.serializer()), text)
            }
        }.onError { msg, t ->
            AtomicFileStore.quarantine(f, "pending_tool_calls_parse")
            Logger.w(TAG, "loadAll El análisis falló: $msg(El archivo se pone en cuarentena y las llamadas pendientes no se descartan silenciosamente)", t)
        }.getOrNull() ?: emptyList()
    }

    /** interno:Escriba toda la lista pendiente en el archivo(Escritura atómica:Después de escribir el archivo temporal rename,Evite la corrupción a media escritura). */
    private suspend fun saveAllInternal(list: List<PendingToolCall>) = withContext(Dispatchers.IO) {
        val f = file() ?: return@withContext
        resultOf {
            val text = AppJson.encodeToString(
                ListSerializer(PendingToolCall.serializer()),
                list,
            )
            val stored = persistenceCipher.encrypt(text)
            // Aprobación/El estado del punto de interrupción también requiere fsync + reemplazo atómico para evitar escribir la mitad de JSON cuando se detiene o se finaliza el proceso.
            AtomicFileStore.writeText(f, stored)
        }.onError { msg, t ->
            Logger.e(TAG, "saveAllInternal Error de escritura: $msg", t)
        }.getOrThrow()
    }

    /**
     * Guardar una única llamada de herramienta pendiente(Agregar a la lista existente).
     * Llamado inmediatamente después de que la decisión de LLM llame a tool_calls,Garantiza que se registren incluso los fallos de ejecución posteriores.
     */
    suspend fun save(pending: PendingToolCall) = mutex.withLock {
        val current = loadAll()
        // La misma herramientaCallId se considera un duplicado.(En teoría no sucederá,LLM tool_call_id es único cada vez),Seguro de deduplicación
        val merged = current.filterNot { it.toolCallId == pending.toolCallId } + pending
        saveAllInternal(merged)
    }

    /**
     * Guardar por lotes varias llamadas de herramientas pendientes(Adición única,Reducir los tiempos de E/S de archivos).
     * Utilice este método para escribir el lote completo a la vez después de recibir llamadas a herramientas.
     */
    suspend fun saveAll(pendings: List<PendingToolCall>) = mutex.withLock {
        if (pendings.isEmpty()) return@withLock
        val current = loadAll()
        val newIds = pendings.map { it.toolCallId }.toSet()
        // Eliminar duplicados:Igual que toolCallId, el que sea más reciente
        val merged = current.filterNot { it.toolCallId in newIds } + pendings
        saveAllInternal(merged)
    }

    /**
     * Obtenga todas las llamadas de herramientas pendientes para una sesión específica(Ordenar por creadoEn ascendente,Mantener el orden de llamadas de LLM).
     * Se utiliza para comprobar si se requiere recuperación al cambiar a esta sesión.
     */
    suspend fun getForChat(chatId: String): List<PendingToolCall> = mutex.withLock {
        loadAll().filter { it.chatId == chatId }.sortedBy { it.createdAt }
    }

    /** Actualiza la fase de persistencia de la llamada a la herramienta especificada; devuelve falso para llamadas desconocidas. */
    suspend fun updateState(toolCallId: String, executionState: String, abortReason: String? = null): Boolean = mutex.withLock {
        val current = loadAll()
        var changed = false
        val updated = current.map { pending ->
            if (pending.toolCallId != toolCallId) return@map pending
            changed = true
            pending.copy(
                executionState = executionState,
                abortReason = abortReason?.take(MAX_REASON_LENGTH),
            )
        }
        if (changed) saveAllInternal(updated)
        changed
    }

    /**
     * Elimine el registro pendiente con el toolCallId especificado.
     * Una vez completada la ejecución de la herramienta(Obtenga resultados y rellénelos en el historial de conversaciones)llamado más tarde.
     */
    suspend fun remove(toolCallId: String) = mutex.withLock {
        val current = loadAll()
        val filtered = current.filterNot { it.toolCallId == toolCallId }
        if (filtered.size != current.size) {
            saveAllInternal(filtered)
        }
    }

    /**
     * Borre todos los registros pendientes de la sesión especificada.
     * Selección de usuario"descartar"llamado cuando,O limpiar cuando se elimine la sesión.
     */
    suspend fun clearForChat(chatId: String) = mutex.withLock {
        val current = loadAll()
        val filtered = current.filterNot { it.chatId == chatId }
        if (filtered.size != current.size) {
            saveAllInternal(filtered)
        }
    }

    /**
     * Deja todo sin terminar pending(Grupo por ID de chat).
     * Llamada a la herramienta utilizada para verificar globalmente qué sesiones están pendientes de restauración cuando se inicia la aplicación.
     */
    suspend fun getAllPending(): Map<String, List<PendingToolCall>> = mutex.withLock {
        loadAll().groupBy { it.chatId }
    }

    const val EXECUTING = "EXECUTING"
    const val APPROVAL_PENDING = "APPROVAL_PENDING"
    const val ABORTED = "ABORTED"
    private const val MAX_REASON_LENGTH = 256
}
