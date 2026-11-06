package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Un miembro del chat grupal.
 *
 * Solo hay un personaje seguido,La clave principal es(sesión, papel):El mismo personaje entra al mismo grupo y solo sale una línea.,La escritura repetida equivale a sobrescribir.
 * El usuario local no está en la tabla. —— Los únicos personajes del grupo son "otros".,Por su parte, la interfaz se utiliza para dibujar de acuerdo con los datos locales.
 *
 * [joinedAt] Es hora de unirse al grupo.,También sirve como secuencia de visualización.:Al establecer un grupo en un mismo lote, aumentar el orden de selección.,
 * Por lo tanto, la página de miembros del grupo se pone en cola de manera constante según el orden de entrada del grupo.,No depende del orden alfabético de los identificadores de funciones.
 */
@Entity(
    tableName = "mp_thread_member",
    primaryKeys = ["threadId", "characterId"],
    indices = [Index("threadId")],
)
data class MpThreadMemberEntity(
    val threadId: String,
    val characterId: String,
    val joinedAt: Long,
)
