package io.zer0.muse.miniphone.ui.moments

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.moment.MP_LOCAL_ACTOR_ID
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * El estado y las acciones de la página del círculo de amigos.
 *
 * La línea de tiempo es "una ventana cargada",No es una suscripción de mesa completa:El cursor es el número de elementos cargados.,por página [PAGE_SIZE] Artículo,
 * El mismo enfoque que la lista de mensajes privados. La ventana está sólo en [timelineLock] moverse —— Cuando la actualización y el cambio de página aparecen al mismo tiempo,
 * La primera vez que llegué, reemplacé la ventana.,Los que lleguen más tarde seguirán recogiendo según el nuevo número de artículos cargados.
 *
 * Tira hacia abajo para actualizar y deja que el personaje publique una actualización.:Esta es una de las dos unidades de caracteres del módulo.(Otro lugar es [io.zer0.muse.miniphone.work.MpMomentWorker]),
 * Si fallas, detente en silencio.,No hay errores en la interfaz y no se agregan actualizaciones vacías. El cepillado de manos exitoso también se cuenta como una generación. ——
 * La marca de tiempo se escribe en la cuenta anti-vibración de la tarea en segundo plano.,De lo contrario, se podrá agregar otra tarea programada dentro de los 90 minutos.
 *
 * @param settings Cambio de tareas en segundo plano y libro mayor anti-vibración
 * @param material Generar material: de qué hablamos recientemente, qué publicamos la última vez, qué hora es ahora
 * @param onlyMine Solo mira lo que publicas:La línea de tiempo solo verifica la información del usuario local.,La actualización desplegable ya no requiere generación de personajes.
 * @param random Fuente aleatoria para elegir personajes.:Elige aleatoriamente un personaje habilitado cada vez que se actualice,La prueba se puede clavar
 */
class MiniPhoneMomentsViewModel(
    private val repository: MpMomentsRepository,
    private val host: MiniPhoneHost,
    private val settings: MpMomentAutoStore,
    private val material: MpCharacterMaterial,
    private val onlyMine: Boolean = false,
    private val random: Random = Random.Default,
) : ViewModel() {

    private val mutableState = MutableStateFlow(MomentsUiState())

    val uiState: StateFlow<MomentsUiState> = mutableState.asStateFlow()

    /** Lista de personajes dada por el anfitrión.(id → papel),Se utiliza para complementar el avatar del autor. */
    private var characters: Map<String, MpCharacter> = emptyMap()

    private var selfName: String = ""

    /** Puerta de la ventana de la línea de tiempo:No se permite actualizar y pasar página para cambiar la ventana al mismo tiempo. */
    private val timelineLock = Mutex()

    init {
        observeHost()
        viewModelScope.launch { reloadFirstPage() }
    }

    /** Cargue una página más antigua cuando se desplace hacia el final. No hay más, o la página anterior aún está en camino,Simplemente ignóralo. */
    fun loadMore() {
        val current = mutableState.value
        if (!current.hasMore || current.isLoadingMore) return
        mutableState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch { appendPage() }
    }

    /**
     * Tira hacia abajo para actualizar:Primero deja que el personaje envíe una actualización.,Lea la primera pantalla nuevamente.
     *
     * Error de compilación(No hay rol, el host devuelve nulo, el host lanza una excepción que no debería lanzarse)No se inserta nada cuando,
     * Vuelva a leer la línea de tiempo como de costumbre. —— La animación desplegable está limpia.,No habrá más actualizaciones vacías en la página.
     * No generado en modo "Mis Momentos":Las actualizaciones publicadas por el personaje no pertenecen a esta página.,Este desove sólo ocurrirá en otros lugares.
     */
    fun refresh() {
        if (mutableState.value.isRefreshing) return
        mutableState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                if (!onlyMine) generateMoment()
                reloadFirstPage()
            } finally {
                mutableState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /** Envía un mensaje propio:Texto + hasta nueve imágenes;Después de una publicación exitosa, se insertará en la parte superior de la línea de tiempo. */
    fun publish(content: String, imageUris: List<Uri>, visibleToSelf: Boolean = false) {
        viewModelScope.launch {
            val moment = repository.publish(
                content = content,
                imageUris = imageUris,
                authorName = selfName,
                visibleTo = if (visibleToSelf) MP_LOCAL_ACTOR_ID else "",
            ) ?: return@launch
            val row = buildRow(moment)
            mutableState.update { it.copy(moments = listOf(row) + it.moments) }
        }
    }

    /** Me gusta o no,Reemplace convenientemente la lista y el recuento de Me gusta en esta fila con los últimos resultados de Curry. */
    fun toggleLike(momentId: String) {
        viewModelScope.launch {
            val liked = repository.toggleLike(momentId, MpActorType.USER, MP_LOCAL_ACTOR_ID, selfName)
            val likes = repository.likesOf(momentId)
            updateRow(momentId) { row ->
                row.copy(likes = likes.map { like -> like.likerName }, likedByMe = liked)
            }
        }
    }

    /**
     * Actualizaciones de comentarios,O responde a uno de los comentarios.
     *
     * [parentId] Vacío significa comentario directo;[parentId] Responder a un comentario cuando lo señale(Lo mismo ocurre con la respuesta.,
     * Se retrocederá dos niveles durante la visualización.).
     */
    fun comment(momentId: String, parentId: String?, content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            repository.addComment(
                momentId = momentId,
                parentId = parentId,
                senderType = MpActorType.USER,
                senderId = MP_LOCAL_ACTOR_ID,
                senderName = selfName,
                content = content,
            ) ?: return@launch
            val comments = repository.commentsOf(momentId)
            updateRow(momentId) { row -> row.copy(comments = buildCommentTree(comments)) }
        }
    }

    /** Eliminar tus publicaciones;Aquí no se abordan las dinámicas de otras personas. */
    fun deleteMoment(momentId: String) {
        viewModelScope.launch {
            val row = mutableState.value.moments.firstOrNull { moment -> moment.id == momentId } ?: return@launch
            if (!row.isMine) return@launch
            repository.deleteMoment(momentId)
            mutableState.update { state ->
                state.copy(moments = state.moments.filterNot { moment -> moment.id == momentId })
            }
        }
    }

    /**
     * Eliminar un comentario que escribiste:Las respuestas que cuelgan debajo también se eliminarán.
     *
     * Los comentarios de otras personas no serán procesados. —— No hay ninguna entrada en la interfaz.,Guardia aquí de nuevo. Vuelva a leer el árbol de comentarios después de eliminarlo.,
     * Para evitar dejar una línea de respuestas en la interfaz que ya no existe.
     */
    fun deleteComment(momentId: String, commentId: String) {
        viewModelScope.launch {
            repository.deleteComment(momentId, commentId)
            val comments = repository.commentsOf(momentId)
            updateRow(momentId) { row -> row.copy(comments = buildCommentTree(comments)) }
        }
    }

    /** Lista de personajes del anfitrión y datos locales.:Un avatar complementario,Una portada suplementaria y un apodo. */
    private fun observeHost() {
        viewModelScope.launch {
            host.observeCharacters()
                .catch { emit(emptyList()) }
                .collect { list ->
                    characters = list.associateBy { character -> character.id }
                    applyAvatars()
                }
        }
        viewModelScope.launch {
            host.observeProfile()
                .catch { emit(MpProfile(nickname = "")) }
                .collect { profile ->
                    selfName = profile.nickname
                    mutableState.update {
                        it.copy(
                            selfName = profile.nickname,
                            selfAvatarUri = profile.avatarUri?.takeIf(String::isNotBlank),
                            coverImageUri = profile.coverImageUri?.takeIf(String::isNotBlank),
                        )
                    }
                }
        }
    }

    /** Dejar que un personaje habilitado aleatoriamente genere una actualización;No hace nada si no hay personajes o no se generan personajes. */
    private suspend fun generateMoment() {
        val character = characters.values.randomOrNull(random) ?: return
        val hint = material.forMoment(character.id)
        val content = runCatching { host.composeMoment(character.id, hint) }.getOrNull() ?: return
        val moment = repository.insertFromCharacter(character, content) ?: return
        // El cepillado de manos también se considera una generación única:Registro en el libro mayor anti-vibración,No escribir solo significa una capa menos de protección,No debería retrasar esta actualización
        runCatching { settings.markGenerated(moment.createdAt) }
    }

    private suspend fun reloadFirstPage() = timelineLock.withLock {
        val page = pageOf(limit = PAGE_SIZE, offset = 0)
        val rows = buildRows(page)
        mutableState.update {
            it.copy(
                moments = rows,
                hasMore = page.size == PAGE_SIZE,
                isLoadingMore = false,
                isLoaded = true,
            )
        }
    }

    private suspend fun appendPage() = timelineLock.withLock {
        val loaded = mutableState.value.moments.size
        val page = pageOf(limit = PAGE_SIZE, offset = loaded)
        val rows = buildRows(page)
        mutableState.update {
            it.copy(
                moments = it.moments + rows,
                hasMore = page.size == PAGE_SIZE,
                isLoadingMore = false,
            )
        }
    }

    /** línea de tiempo de una página:Los dos modos solo tienen condiciones de consulta diferentes.,La paginación es exactamente igual que el cursor. */
    private suspend fun pageOf(limit: Int, offset: Int): List<MpMomentEntity> =
        if (onlyMine) repository.pageMine(limit, offset) else repository.page(limit, offset)

    /**
     * Una página agrega dinámicamente me gusta y comentarios.
     *
     * Dos consultas por lotes(Me gusta y comenta)En lugar de revisar cada elemento dos veces:Originalmente, veinte actualizaciones requerían cuarenta viajes de ida y vuelta.
     */
    private suspend fun buildRows(entities: List<MpMomentEntity>): List<MomentUi> {
        if (entities.isEmpty()) return emptyList()
        val ids = entities.map { entity -> entity.id }
        val likes = repository.likesByMomentIds(ids)
        val comments = repository.commentsByMomentIds(ids)
        return entities.map { entity ->
            buildMomentUi(
                entity = entity,
                likes = likes[entity.id].orEmpty(),
                comments = comments[entity.id].orEmpty(),
                characters = characters,
            )
        }
    }

    /** noticias individuales(Recién publicado, solo me gusta/Comentario)Completo con cheque único,No es necesario agrupar una acción a la vez. */
    private suspend fun buildRow(entity: MpMomentEntity): MomentUi = buildMomentUi(
        entity = entity,
        likes = repository.likesOf(entity.id),
        comments = repository.commentsOf(entity.id),
        characters = characters,
    )

    private fun updateRow(momentId: String, transform: (MomentUi) -> MomentUi) {
        mutableState.update { state ->
            state.copy(
                moments = state.moments.map { row -> if (row.id == momentId) transform(row) else row },
            )
        }
    }

    /** Agregaré avatares después de que llegue la lista de personajes.;Los campos restantes de la fila no se modifican. */
    private fun applyAvatars() {
        mutableState.update { state ->
            state.copy(
                moments = state.moments.map { row ->
                    val uri = characters[row.authorId]?.avatarUri?.takeIf(String::isNotBlank)
                    if (uri == row.authorAvatarUri) row else row.copy(authorAvatarUri = uri)
                },
            )
        }
    }

    private companion object {
        /** Número de artículos por página:Aproximadamente dos o tres actualizaciones por pantalla,Veinte elementos son suficientes para pasar por varias pantallas. */
        const val PAGE_SIZE: Int = 20
    }
}
