package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType

/**
 * Un mensaje. Si la conversación es un mensaje privado o un chat grupal está determinado por [io.zer0.muse.miniphone.data.db.entity.MpThreadEntity.isGroup] decidir,
 * La fila del mensaje en sí no está dividida en dos tablas.
 *
 * [characterId] si**Portavoz de este mensaje**:
 * - mensaje privado:Heng es el personaje de la conversación.,Lo mismo publican los usuarios.(Sólo hay otra persona en la conversación.);
 * - chat grupal:El mensaje que envía el personaje lo escribe el personaje que habla. id;Escrito por el propio usuario.**cadena vacía** —— Un mensaje sólo puede estar dirigido a una persona.,
 *   Debe confiar en él para saber quién está hablando en el grupo.,Y no le corresponde ningún rol.,Al escribir una conversación, el primer miembro registrará "lo que dije" como "lo que dijo".
 * Por lo tanto, al leer esta tabla en Curry,no se puede suponer [characterId] Debe ser un personaje que existe.
 *
 * [createdAt] marca de tiempo en milisegundos,Múltiples botones insertados en el mismo milisegundo [id] Clasificación ascendente.
 * [status] Está enviando el estado:El mensaje del usuario se registra primero en la base de datos como "Enviando",Después de recibir la respuesta, cámbiela a "Enviado".,El error se cambia a "Error al enviar".
 * El valor predeterminado de la columna se escribe en la declaración de creación de la tabla.,Cuando se actualiza la base de datos antigua, también se utiliza para recuperar los datos históricos que se "emiten".
 */
@Entity(
    tableName = "mp_message",
    indices = [Index("threadId"), Index("createdAt")],
)
data class MpMessageEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val characterId: String,
    val sender: MpActorType,
    val type: MpMessageType,
    val content: String,
    val mediaUri: String? = null,
    val voiceDurationMs: Long? = null,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val status: MpMessageStatus = MpMessageStatus.SENT,
)
