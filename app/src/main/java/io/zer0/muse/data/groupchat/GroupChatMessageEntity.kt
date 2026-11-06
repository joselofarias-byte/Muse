package io.zer0.muse.data.groupchat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Entidad de mensaje de chat grupal.
 *
 * Graba cada mensaje en el chat grupal(El usuario habla/el agente habla).
 * - [senderType] Tipo de remitente: "user" o "assistant"
 * - [senderId] remitente id(userId o assistantId)
 * - [senderName] Nombre para mostrar del remitente(caché,Evite volver a consultar cada vez AssistantRepository)
 * - [mood] Agent emociones(Opcional,Déjelo para la siguiente visualización de la interfaz de usuario.)
 *
 * @param id el unico mensaje id
 * @param chatId Pertenece al chat grupal id
 * @param senderType Tipo de remitente: "user" / "assistant"
 * @param senderId remitente id
 * @param senderName Nombre para mostrar del remitente
 * @param body Texto del mensaje
 * @param imageBase64Json Lista base64 de archivos adjuntos de imágenes(JSON cuerda,Predeterminado "[]")
 * @param timestamp Enviar marca de tiempo
 * @param mood Agent emociones(Opcional)
 * @param reasoning Agent proceso de pensamiento(Opcional)
 */
@Entity(
    tableName = "group_chat_messages",
    indices = [Index(value = ["chatId"])],
)
@Serializable
data class GroupChatMessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderType: String,
    val senderId: String,
    val senderName: String,
    val body: String,
    @ColumnInfo(defaultValue = "[]") val imageBase64Json: String = "[]",
    /**
     * Dirección de resultados de generación de video(assistant noticias,generate_video escribir).
     *
     * Chatea con Dan [io.zer0.muse.data.session.MessageEntity.videoFileUri] Alineación:Antes no había ninguna columna de vídeo en el chat grupal.,
     * generate_video Sólo se puede bloquear en chats grupales. Después de la lista complementaria, el vídeo generado en el chat grupal se guardará con el mensaje y se
     * [io.zer0.muse.ui.MessageBubbleTail.AssistantVideoCard] Renderiza, haz clic para reproducir.
     * Predeterminado NULL(null = Sin vídeo),Compatible con datos antiguos.
     */
    @ColumnInfo(defaultValue = "NULL") val videoFileUri: String? = null,
    @ColumnInfo(defaultValue = "0") val timestamp: Long = System.currentTimeMillis(),
    val mood: String? = null,
    val reasoning: String? = null,
    // ── v2.x Mejoras en el chat grupal ──
    /**
     * objetivo susurro AI id(null = información pública;no null = Mensajes privados visibles solo para la IA objetivo).
     *
     * - Mensajes privados enviados por los usuarios.:[senderType]="user", [whisperTargetId]=objetivo assistantId
     * - AI al recibir,Anotación en el mensaje del sistema"Este es un mensaje privado.";Otras IA no pueden ver este mensaje body
     * - UI El icono de candado que se muestra en está etiquetado"susurrar"
     */
    @ColumnInfo(name = "whisper_target_id", defaultValue = "NULL") val whisperTargetId: String? = null,
    /**
     * Citando el mensaje objetivo de la respuesta. id(null = noticias generales;no null = Cita el mensaje especificado.).
     * UI Mostrar bloque de vista previa de referencia en,Haga clic para saltar al mensaje original.
     */
    @ColumnInfo(name = "reply_to_id", defaultValue = "NULL") val replyToId: String? = null,
    /**
     * etiqueta de tipo de mensaje:
     * - "normal": noticias generales(Predeterminado)
     * - "vote": votar votar(Cada IA vota según su personalidad,body para votar contenido)
     * - "summary": Resumen de conclusiones(generado por resumidor)
     * - "system": Aviso del sistema(Como por ejemplo"XXX inició una votación")
     */
    @ColumnInfo(defaultValue = "normal") val messageType: String = "normal",
    @ColumnInfo(defaultValue = "[]") val fileAttachmentsJson: String = "[]",
)
