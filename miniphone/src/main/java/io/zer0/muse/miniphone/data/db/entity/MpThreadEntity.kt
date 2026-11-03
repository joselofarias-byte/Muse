package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * una conversación:Mensaje privado o chat grupal.
 *
 * [characterId] Al personaje cuyo mensaje privado es la conversación.;Sí al chat grupal**primer miembro**de id —— Cuando buscas alguien con quien hablar en el grupo.
 * Eso es todo,La lista completa de miembros está en [MpThreadMemberEntity].
 *
 * [lastMessageAt] Sólo se utiliza para ordenar y mostrar listas.,No participa en la determinación no leída.[pinned] luo kuwei 0/1,
 * La columna tiene un valor predeterminado de 0: tanto las actualizaciones a la base de datos anterior como las escrituras sin esta columna quedarán "no fijadas".
 *
 * [isGroup] con [title] Estas son las dos columnas que trae el chat grupal,mensaje privado ok `false` con cadena vacía:Todas las sesiones existentes después de actualizar la base de datos anterior
 * Todos caen en el lado de los "mensajes privados".,Las interfaces y consultas no necesitan tener una rama separada para ellas.
 * [title] Sólo leer en el grupo. —— El nombre del grupo no cambia en tiempo real con los nombres de los miembros.,Se finalizó en el momento en que se formó el grupo.
 * [titleCustomized] Nota "¿El usuario ha cambiado manualmente el nombre del grupo?":Para los grupos que no se han modificado, el nombre del grupo se volverá a calcular en función de los miembros después de agregar personas.,
 * Todo lo que haya cambiado seguirá igual. —— El nombre dado especialmente por el usuario no se puede utilizar como empalme de nombres.
 */
@Entity(
    tableName = "mp_thread",
    indices = [Index("characterId"), Index("lastMessageAt")],
)
data class MpThreadEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    val unreadCount: Int = 0,
    val lastMessagePreview: String? = null,
    val lastMessageAt: Long = 0L,
    val muted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isGroup: Boolean = false,
    @ColumnInfo(defaultValue = "''") val title: String = "",
    @ColumnInfo(defaultValue = "0") val titleCustomized: Boolean = false,
)
