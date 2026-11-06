package io.zer0.muse.miniphone.ui.moments

import androidx.compose.runtime.Immutable
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.db.entity.MpMomentLikeEntity
import io.zer0.muse.miniphone.data.moment.MP_LOCAL_ACTOR_ID
import io.zer0.muse.miniphone.data.moment.decodeMomentImages

/**
 * Todos los estados se mostrarán en la página del círculo de amigos.
 *
 * [isLoaded] Se utiliza para distinguir entre "Aún no he terminado de leer" y "Realmente no hay noticias".:Ambos son una lista vacía.,
 * Pero sólo este último debería mostrar un estado vacío.,Lo primero debería dejarse discretamente en blanco.
 */
@Immutable
data class MomentsUiState(
    val coverImageUri: String? = null,
    val selfName: String = "",
    val selfAvatarUri: String? = null,
    val moments: List<MomentUi> = emptyList(),
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoaded: Boolean = false,
)

/**
 * Una línea dinámica en la interfaz.
 *
 * [likes] Conserva solo el nombre de la persona a la que le gustó.(Ordenar por tiempo similar),La interfaz los une;
 * [comments] Estructura de dos niveles ya montada.,Los comentarios de alto nivel vienen con sus propias respuestas.
 */
@Immutable
data class MomentUi(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorAvatarUri: String?,
    val content: String,
    val images: List<String>,
    val createdAt: Long,
    val isMine: Boolean,
    val likes: List<String>,
    val likedByMe: Boolean,
    val comments: List<MomentCommentUi>,
    /** El rango visible permanece como está.:cadena vacía = Visible para todos. Esta etapa sólo se utiliza para marcar la tarjeta como \"Visible sólo para ti\". */
    val visibleToJson: String = "",
)

/** un comentario superior(Los que comentan directamente la noticia.). */
@Immutable
data class MomentCommentUi(
    val id: String,
    val senderName: String,
    val content: String,
    val createdAt: Long,
    val replies: List<MomentReplyUi> = emptyList(),
    val isMine: Boolean = false,
)

/**
 * Una respuesta debajo del comentario superior.
 *
 * [replyToName] Sólo se da si "responder a otra respuesta":La visualización de dos niveles colocará todas las respuestas bajo el mismo comentario de nivel superior.,
 * No indiques quién está en el siguiente nivel.,El lector pensará que ha estado hablando con el cartel original.
 */
@Immutable
data class MomentReplyUi(
    val id: String,
    val senderName: String,
    val replyToName: String?,
    val content: String,
    val createdAt: Long,
    val isMine: Boolean = false,
)

/** ¿A quién apunta actualmente el cuadro de entrada de comentarios?:Responder a un comentario con su id y nombre del autor.,Está vacío al comentar directamente la actualización. */
@Immutable
data class CommentTarget(
    val commentId: String,
    val senderName: String,
)

/**
 * Los tres datos de Curry → Una línea en la interfaz.
 *
 * El avatar y el nombre del autor se toman de la lista de personajes proporcionada por el anfitrión.:El nombre almacenado en la fila dinámica es la instantánea en el momento del lanzamiento.,
 * Si se cambia el nombre del personaje más tarde, el texto histórico no debe reescribirse,Pero el avatar puede seguir la lista actual.
 */
internal fun buildMomentUi(
    entity: MpMomentEntity,
    likes: List<MpMomentLikeEntity>,
    comments: List<MpMomentCommentEntity>,
    characters: Map<String, MpCharacter>,
): MomentUi = MomentUi(
    id = entity.id,
    authorId = entity.authorId,
    authorName = entity.authorName,
    authorAvatarUri = characters[entity.authorId]?.avatarUri?.takeIf(String::isNotBlank),
    content = entity.content,
    images = decodeMomentImages(entity.imagesJson),
    createdAt = entity.createdAt,
    isMine = entity.source == MpMomentSource.USER && entity.authorId == MP_LOCAL_ACTOR_ID,
    likes = likes.map { like -> like.likerName },
    likedByMe = likes.any { like -> like.likerType == MpActorType.USER && like.likerId == MP_LOCAL_ACTOR_ID },
    comments = buildCommentTree(comments),
    visibleToJson = entity.visibleToJson,
)

/**
 * Comentarios de Tile → Estructura de dos niveles.
 *
 * La capa superior es `parentId` esta vacio(O señalar un comentario que ya no existe)de esos,en orden cronológico;
 * El resto estará vinculado al comentario de nivel superior donde se encuentra. —— Las respuestas también se devuelven a la parte superior.,No más de dos niveles de profundidad.,
 * Puedes leerlo todo en una línea de comentarios.
 */
internal fun buildCommentTree(comments: List<MpMomentCommentEntity>): List<MomentCommentUi> {
    if (comments.isEmpty()) return emptyList()
    val byId = comments.associateBy { it.id }
    val repliesByRoot = groupRepliesByRoot(comments, byId)
    return comments
        .filter { comment -> comment.parentId == null || comment.parentId !in byId }
        .map { root ->
            MomentCommentUi(
                id = root.id,
                senderName = root.senderName,
                content = root.content,
                createdAt = root.createdAt,
                replies = repliesByRoot[root.id].orEmpty(),
                isMine = root.isMine,
            )
        }
}

private fun groupRepliesByRoot(
    comments: List<MpMomentCommentEntity>,
    byId: Map<String, MpMomentCommentEntity>,
): Map<String, List<MomentReplyUi>> {
    val grouped = mutableMapOf<String, MutableList<MomentReplyUi>>()
    comments.forEach { comment ->
        val parentId = comment.parentId
        if (parentId == null || parentId !in byId) return@forEach
        val rootId = rootIdOf(comment, byId)
        grouped.getOrPut(rootId) { mutableListOf() } += MomentReplyUi(
            id = comment.id,
            senderName = comment.senderName,
            replyToName = byId[parentId]?.senderName?.takeIf { comment.parentId != rootId },
            content = comment.content,
            createdAt = comment.createdAt,
            isMine = comment.isMine,
        )
    }
    return grouped
}

/** seguir `parentId` Subir a los comentarios del nivel superior.;Cuando la cadena sea demasiado larga o tenga bucles, deténgase en la actual.,No sigas en círculos. */
private fun rootIdOf(comment: MpMomentCommentEntity, byId: Map<String, MpMomentCommentEntity>): String {
    var current = comment
    repeat(MAX_COMMENT_DEPTH) {
        val parent = current.parentId?.let(byId::get) ?: return current.id
        current = parent
    }
    return current.id
}

/** Encuentre el número máximo de pasos hasta el nivel superior:Para una conversación normal, el nivel dos o tres es suficiente.,Si excede el límite, los datos se consideran corruptos. */
private const val MAX_COMMENT_DEPTH: Int = 8

/** ¿Un comentario está escrito por un usuario local? —— Sólo tú puedes eliminarlo. */
private val MpMomentCommentEntity.isMine: Boolean
    get() = senderType == MpActorType.USER && senderId == MP_LOCAL_ACTOR_ID

/** La lista Me gusta contiene como máximo algunos nombres uno por uno.,Si hay más, deja de decir \"esperando a N personas\". */
internal const val LIKERS_INLINE_MAX: Int = 10

/** El número de nombres enumerados uno por uno después del cierre. */
internal const val LIKERS_SHOWN_WHEN_FOLDED: Int = 9

/**
 * Me gusta la línea en la lista:Terminado en una línea,Sin desplazamiento en el cuadro.
 *
 * Tres personas son "❤️ Un Yuan, Xiao Man, Lao Chen ";exceder [LIKERS_INLINE_MAX] Sólo la primera vez
 * [LIKERS_SHOWN_WHEN_FOLDED] un,Seguido de "esperando a N personas"——N ¿Es el número total de me gusta?,No el número de personas omitidas.
 * Utilice comas chinas entre nombres,Se distingue del texto principal por una coma.
 */
internal fun likersLineOf(names: List<String>): String {
    val shown = names.map(String::trim).filter(String::isNotEmpty)
    if (shown.isEmpty()) return ""
    val folded = shown.size > LIKERS_INLINE_MAX
    val parts = shown.take(if (folded) LIKERS_SHOWN_WHEN_FOLDED else shown.size)
    val namesText = parts.joinToString(LIKERS_SEPARATOR)
    return if (folded) "$LIKERS_MARK$namesText${LIKERS_SEPARATOR}espera ${shown.size} gente" else "$LIKERS_MARK$namesText"
}

private const val LIKERS_MARK: String = "❤️ "
private const val LIKERS_SEPARATOR: String = ","
