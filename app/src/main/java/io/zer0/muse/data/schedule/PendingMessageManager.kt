package io.zer0.muse.data.schedule

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val Context.pendingMessageStore: DataStore<Preferences> by preferencesDataStore(name = "pending_messages")

/**
 * Phase 3 3E: Administrador de mensajes programados — Almacenar mensajes programados para enviar,Enviado automáticamente después del vencimiento.
 *
 * Uso:
 * 1. El usuario mantiene presionado el botón enviar → Seleccionar hora → llamar [scheduleMessage]
 * 2. ScheduledTaskRunner El bucle de sondeo llama [checkAndDeliver] Comprobar mensajes de caducidad
 * 3. UI Pase [pendingMessagesFlow] mostrar "Message scheduled for HH:MM" pancarta
 */
/** Derivar la deduplicación del flujo de configuración:DataStore.data Elimine únicamente los duplicados de todas las Preferencias.map Luego, cada uno se deduplica para suprimir la emisión repetida del mismo valor provocada por claves irrelevantes. */
private fun <T> Flow<Preferences>.mapDistinct(transform: suspend (Preferences) -> T): Flow<T> = map(transform).distinctUntilChanged()

class PendingMessageManager(
    private val context: Context,
) {
    private val store get() = context.pendingMessageStore

    companion object {
        private const val TAG = "PendingMessage"
        private val KEY_PENDING_MESSAGES = stringPreferencesKey("pending_messages_json")
    }

    /**
     * Lista de mensajes a enviar Flow(Para que la interfaz de usuario muestre un banner).
     */
    val pendingMessagesFlow: Flow<List<PendingMessage>> = store.data.mapDistinct { prefs ->
        val json = prefs[KEY_PENDING_MESSAGES] ?: return@mapDistinct emptyList()
        try {
            AppJson.decodeFromString(ListSerializer(PendingMessage.serializer()), json)
        } catch (e: Exception) {
            Logger.w(TAG, "Parse pending messages failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Programe un mensaje programado.
     *
     * @param content texto del mensaje
     * @param sessionId sesión objetivo ID
     * @param assistantId asistente ID
     * @param sendAtMillis tiempo de entrega planificado(marca de tiempo de milisegundos)
     */
    suspend fun scheduleMessage(content: String, sessionId: String, assistantId: String, sendAtMillis: Long): PendingMessage {
        val msg = PendingMessage(
            id = "pm-${java.util.UUID.randomUUID()}",
            content = content,
            sessionId = sessionId,
            assistantId = assistantId,
            sendAtMillis = sendAtMillis,
            createdAtMillis = System.currentTimeMillis(),
        )
        store.edit { prefs ->
            val current = prefs[KEY_PENDING_MESSAGES]?.let { json ->
                try {
                    AppJson.decodeFromString(ListSerializer(PendingMessage.serializer()), json)
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: emptyList()
            prefs[KEY_PENDING_MESSAGES] = AppJson.encodeToString(
                ListSerializer(PendingMessage.serializer()),
                current + msg,
            )
        }
        Logger.i(TAG, "Scheduled message ${msg.id} for ${msg.sendAtMillis}")
        return msg
    }

    /**
     * Cancelar un mensaje programado.
     */
    suspend fun cancelMessage(messageId: String) {
        store.edit { prefs ->
            val current = prefs[KEY_PENDING_MESSAGES]?.let { json ->
                try {
                    AppJson.decodeFromString(ListSerializer(PendingMessage.serializer()), json)
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: emptyList()
            prefs[KEY_PENDING_MESSAGES] = AppJson.encodeToString(
                ListSerializer(PendingMessage.serializer()),
                current.filter { it.id != messageId },
            )
        }
        Logger.i(TAG, "Cancelled message $messageId")
    }

    /**
     * Verificar y devolver todos los mensajes caducados(Retirar del almacenamiento y devolver).
     *
     * Llamado por el ciclo de sondeo de ScheduledTaskRunner,La persona que llama es responsable de enviar el mensaje devuelto.
     */
    suspend fun drainDueMessages(): List<PendingMessage> {
        val now = System.currentTimeMillis()
        var due: List<PendingMessage> = emptyList()
        store.edit { prefs ->
            val current = prefs[KEY_PENDING_MESSAGES]?.let { json ->
                try {
                    AppJson.decodeFromString(ListSerializer(PendingMessage.serializer()), json)
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: emptyList()
            due = current.filter { it.sendAtMillis <= now }
            val remaining = current.filter { it.sendAtMillis > now }
            if (remaining.isEmpty()) {
                prefs.remove(KEY_PENDING_MESSAGES)
            } else {
                prefs[KEY_PENDING_MESSAGES] = AppJson.encodeToString(
                    ListSerializer(PendingMessage.serializer()),
                    remaining,
                )
            }
        }
        if (due.isNotEmpty()) {
            Logger.i(TAG, "Drained ${due.size} due messages")
        }
        return due
    }

    /**
     * Obtener todos los mensajes pendientes(No modificar el almacenamiento).
     */
    suspend fun getAllPending(): List<PendingMessage> {
        val json = store.data.first()[KEY_PENDING_MESSAGES] ?: return emptyList()
        return try {
            AppJson.decodeFromString(ListSerializer(PendingMessage.serializer()), json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Mensajes programados para enviar.
 */
@Serializable
data class PendingMessage(
    val id: String,
    val content: String,
    val sessionId: String,
    val assistantId: String,
    val sendAtMillis: Long,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
