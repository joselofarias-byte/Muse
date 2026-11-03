package io.zer0.muse.miniphone.data.moment

import android.net.Uri
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.dao.MpMomentCommentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentDao
import io.zer0.muse.miniphone.data.db.dao.MpMomentLikeDao
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import java.util.UUID

/** Un mensaje dinámico puede contener hasta nueve imágenes.:No importa cuántas pantallas haya, no puedo verlo.,Jiugongge es el límite superior. */
internal const val MOMENT_MAX_IMAGES: Int = 9

/** La identificación unificada de los usuarios locales en todas partes: los autores dinámicos, los que me gustan y los comentaristas la comparten. */
const val MP_LOCAL_ACTOR_ID: String = "local"

/**
 * Leer y escribir en el círculo de amigos.
 *
 * Los detalles sobre cómo publicar, dar me gusta, comentar y eliminar están todos aquí.,La capa superior sólo describe "quién hizo qué y qué actividad".
 * No hay restricciones de clave externa entre las tres tablas.(Room No abierto foreignKeys),Por tanto, esta clase es responsable de eliminar información dinámica.
 * Sus me gusta y comentarios se borrarán juntos. —— Dejar una fila huérfana aumentará la cantidad de Me gusta de la nada cuando una nueva dinámica reutilice la identificación.
 *
 * @param now fuente de tiempo,Reloj del sistema predeterminado;prueba clavada con eso `createdAt`
 * @param newId generador de clave primaria,Predeterminado UUID;Pruebe usarlo para hacer dinámico./Se espera la identificación del comentario.
 */
class MpMomentsRepository(
    private val momentDao: MpMomentDao,
    private val likeDao: MpMomentLikeDao,
    private val commentDao: MpMomentCommentDao,
    private val imageStore: MpImageStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Tomar una página en orden inverso(nuevo → viejo).
     *
     * El rango visible no se filtra en la consulta.:Este es mi propio círculo de amigos.,Dinámica "visible sólo para ti" de tu lado
     * La lista aparece como de costumbre. En el futuro, realmente necesitaremos adaptarlo a los lectores.,Agregue condiciones aquí.
     */
    suspend fun page(limit: Int, offset: Int): List<MpMomentEntity> = momentDao.pageTimeline(limit, offset)

    /**
     * Una página de "Mis Momentos":Siempre que las actualizaciones publicadas por los usuarios locales,nuevo → viejo.
     *
     * La condición de autor es parte de la consulta.,No se trata de tomar toda la página y volver a examinarla. —— Después del filtrado, la cantidad de entradas válidas por página se reducirá.,
     * Cursor de página(Número de artículos cargados)También seguido de desalineación.
     */
    suspend fun pageMine(limit: Int, offset: Int): List<MpMomentEntity> = momentDao.pageTimelineByAuthor(MP_LOCAL_ACTOR_ID, limit, offset)

    /** Me gusta una determinada publicación,Ordenar por tiempo de me gusta. */
    suspend fun likesOf(momentId: String): List<MpMomentLikeEntity> = likeDao.likesOf(momentId)

    /** Comentarios sobre una determinada actividad.,en orden cronológico;El nivel se presiona desde el nivel superior. `parentId` Armar. */
    suspend fun commentsOf(momentId: String): List<MpMomentCommentEntity> = commentDao.commentsOf(momentId)

    /** Una página de me gusta dinámicos:Después de una consulta, presione `momentId` grupo,Las publicaciones a las que no les haya gustado no aparecerán en los resultados. */
    suspend fun likesByMomentIds(momentIds: List<String>): Map<String, List<MpMomentLikeEntity>> {
        if (momentIds.isEmpty()) return emptyMap()
        return likeDao.likesOfMomentIds(momentIds).groupBy { like -> like.momentId }
    }

    /** Una página de comentarios dinámicos.:Después de una consulta, presione `momentId` Grupo. */
    suspend fun commentsByMomentIds(momentIds: List<String>): Map<String, List<MpMomentCommentEntity>> {
        if (momentIds.isEmpty()) return emptyMap()
        return commentDao.commentsOfMomentIds(momentIds).groupBy { comment -> comment.momentId }
    }

    /**
     * Envía un mensaje propio.
     *
     * La imagen seleccionada se copia primero en el directorio privado del módulo.,Sólo las rutas locales se almacenan en la biblioteca.;Si no puedes leer uno determinado, omite este.,
     * No dejes que una mala imagen te quite toda la historia. El texto y las imágenes están todos en la biblioteca.,Devuelve nulo.
     *
     * [visibleTo] Déjalo en blanco para que sea visible para todos.;Escribe el nombre del usuario local cuando sólo quieras verlo por ti mismo id(Varios pueden separarse por comas. id,
     * En esta etapa, solo habrá uno de ustedes.). En esta etapa sólo se requiere escribir, no leer.,La línea de tiempo enumera esto como de costumbre.
     */
    suspend fun publish(content: String, imageUris: List<Uri>, authorName: String, visibleTo: String = ""): MpMomentEntity? {
        val text = content.trim()
        val images = imageUris.take(MOMENT_MAX_IMAGES).mapNotNull { uri -> imageStore.import(uri) }
        if (text.isEmpty() && images.isEmpty()) return null
        return insert(
            MpMomentEntity(
                id = newId(),
                authorId = MP_LOCAL_ACTOR_ID,
                authorName = authorName.ifBlank { DEFAULT_SELF_NAME },
                content = text,
                imagesJson = encodeMomentImages(images),
                visibleToJson = visibleTo.trim(),
                createdAt = now(),
                source = MpMomentSource.USER,
            ),
        )
    }

    /**
     * Actualizaciones del personaje de Luo Yitiao.(Se llama después de que el host se genere exitosamente.).
     *
     * Los resultados generados con texto vacío no se guardarán en la base de datos.:Una actualización que no dice nada no merece una cronología.
     */
    suspend fun insertFromCharacter(character: MpCharacter, content: String): MpMomentEntity? {
        val text = content.trim()
        if (text.isEmpty()) return null
        return insert(
            MpMomentEntity(
                id = newId(),
                authorId = character.id,
                authorName = character.name.ifBlank { DEFAULT_CHARACTER_NAME },
                content = text,
                imagesJson = encodeMomentImages(emptyList()),
                visibleToJson = "",
                createdAt = now(),
                source = MpMomentSource.AI,
            ),
        )
    }

    /**
     * Me gusta/Cancelar me gusta,Después de regresar a la operación, "¿Le ha gustado a esta persona?"
     *
     * Clave primaria compuesta `(momentId, likerType, likerId)` Asegúrese de que la misma persona tenga solo una línea para la misma actualización,
     * Entonces el estado solo depende de si existe o no.:Eliminar si está presente, insertar si está ausente,Al hacer clic dos veces definitivamente volverá a ser no deseado.
     */
    suspend fun toggleLike(momentId: String, likerType: MpActorType, likerId: String, likerName: String): Boolean {
        if (likeDao.isLiked(momentId, likerType, likerId)) {
            likeDao.delete(momentId, likerType, likerId)
            return false
        }
        likeDao.insert(
            MpMomentLikeEntity(
                momentId = momentId,
                likerType = likerType,
                likerId = likerId,
                likerName = likerName.ifBlank { DEFAULT_SELF_NAME },
                createdAt = now(),
            ),
        )
        return true
    }

    /**
     * Comentar una publicación,O responde a uno de los comentarios.
     *
     * [parentId] Vacío significa comentar directamente la noticia.;No vacío significa responder a ese comentario. El contenido en blanco no se incluirá en la biblioteca.
     */
    suspend fun addComment(
        momentId: String,
        parentId: String?,
        senderType: MpActorType,
        senderId: String,
        senderName: String,
        content: String,
    ): MpMomentCommentEntity? {
        val text = content.trim()
        if (text.isEmpty()) return null
        val comment = MpMomentCommentEntity(
            id = newId(),
            momentId = momentId,
            parentId = parentId?.takeIf(String::isNotBlank),
            senderType = senderType,
            senderId = senderId,
            senderName = senderName.ifBlank { DEFAULT_SELF_NAME },
            content = text,
            createdAt = now(),
        )
        commentDao.insert(comment)
        return comment
    }

    /** Eliminar una actualización,Incluso sus me gusta y comentarios se eliminarán. */
    suspend fun deleteMoment(momentId: String) {
        likeDao.deleteAllOf(momentId)
        commentDao.deleteAllOf(momentId)
        momentDao.delete(momentId)
    }

    /**
     * Eliminar un comentario que escribiste,Las respuestas a continuación también se eliminarán.
     *
     * Los comentarios de otras personas también serán bloqueados aquí.:La interfaz no proporciona entrada.,Escribe la capa y guárdala de nuevo. —— La persona que llamó pasó el mensaje equivocado. id,
     * No deberías poder borrar lo que dicen los demás.
     */
    suspend fun deleteComment(momentId: String, commentId: String) {
        val comments = commentDao.commentsOf(momentId)
        val target = comments.firstOrNull { comment -> comment.id == commentId } ?: return
        if (target.senderType != MpActorType.USER || target.senderId != MP_LOCAL_ACTOR_ID) return
        commentDao.deleteByIds(commentSubtreeIds(commentId, comments))
    }

    private suspend fun insert(moment: MpMomentEntity): MpMomentEntity {
        momentDao.insert(moment)
        return moment
    }

    private companion object {
        const val DEFAULT_SELF_NAME: String = "yo"
        const val DEFAULT_CHARACTER_NAME: String = "papel"
    }
}

/**
 * Un comentario y todas las respuestas debajo. id(incluirte a ti mismo),En orden de padre a hijo.
 *
 * El número de niveles de comentarios no es fijo.([parentId] Siempre puedes apuntar al nivel anterior.),Así que utilice colas para recopilarlas capa por capa.;
 * Hay bucles en los datos.(padre señalando al niño)El tiempo depende de [visited] Detener,Aquí no damos vueltas y vueltas.
 */
internal fun commentSubtreeIds(commentId: String, comments: List<MpMomentCommentEntity>): List<String> {
    val childrenByParent = comments.filter { it.parentId != null }.groupBy { it.parentId.orEmpty() }
    val visited = mutableSetOf<String>()
    val ordered = mutableListOf<String>()
    val pending = ArrayDeque(listOf(commentId))
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (!visited.add(id)) continue
        ordered += id
        childrenByParent[id].orEmpty().forEach { child -> pending.addLast(child.id) }
    }
    return ordered
}
