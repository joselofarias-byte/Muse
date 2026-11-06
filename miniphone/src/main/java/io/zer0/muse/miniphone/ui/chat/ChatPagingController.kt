package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Una ventana de mensaje de sesión:Avance de página, primera pantalla, avance de página, borrado de no leídos.
 *
 * El cursor es el número de elementos cargados. —— La información de la página es siempre la más reciente.,Tómalo antes por detrás.
 * Así que cada vez que muevo la ventana, es [ChatMessageWriter] Entonces hazlo en la puerta.:
 * Si se inserta un nuevo mensaje entre el cursor de lectura y la consulta,Mover toda la ventana de orden inverso una posición,Esta página será la misma que la página anterior.
 *
 * Cuando se lee la línea de conversación, en el estado se escribe "¿Es un grupo y cuál es el nombre del grupo?".:Estas dos cosas sólo se pueden leer aquí.,
 * Y las ramas de la interfaz que no tienen nada que ver con si son un grupo o no dependen de ellas.
 *
 * @param state Estado de la interfaz;Tres campos relacionados con la ventana.(messages / hasMore / isLoadingMore)escrito por esta clase
 * @param onThreadOpened Devolución de llamada después de leer la línea de sesión,La interfaz completa el nombre del personaje, el avatar y los miembros del grupo en consecuencia.
 */
internal class ChatPagingController(
    private val threadId: String,
    private val messageDao: MpMessageDao,
    private val threadDao: MpThreadDao,
    private val writer: ChatMessageWriter,
    private val state: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val onThreadOpened: () -> Unit,
) {

    /** página adelante:Obtenga la identificación del personaje en la sesión, borre la página no leída y extraiga la página más reciente. */
    fun open() {
        scope.launch { openThread() }
    }

    /** Cargue una página anterior cuando se desplace hacia la parte superior. No hay más, o la página anterior aún está en camino,Simplemente ignóralo. */
    fun loadOlder() {
        val current = state.value
        if (!current.hasMore || current.isLoadingMore) return
        state.update { it.copy(isLoadingMore = true) }
        scope.launch { loadOlderPage() }
    }

    /**
     * Borre la página como no leída.
     *
     * Salir y volver a entrar a la misma sesión reutilizará la misma ViewModel,[open] No volverá a correr,
     * Por lo tanto, "borrar páginas no leídas después de ingresar" no puede depender solo del momento en que las abra.
     */
    fun markRead() {
        scope.launch { threadDao.clearUnread(threadId) }
    }

    private suspend fun openThread() {
        val thread = threadDao.getThread(threadId)
        if (thread != null) {
            state.update {
                it.copy(characterId = thread.characterId, isGroup = thread.isGroup, groupTitle = thread.title)
            }
            onThreadOpened()
        }
        threadDao.clearUnread(threadId)
        loadFirstPage()
    }

    /**
     * Por encima del pliegue:Tome una página en orden inverso y gírela hacia la derecha.;`hasMore` Sólo comprueba si esta página está llena.
     *
     * Las interfaces de consulta y escritura reescrita están en la misma puerta. —— Un nuevo mensaje apareció a mitad de camino.,Escribirlo lo cubrirá.
     */
    private suspend fun loadFirstPage() = writer.withWriteLock {
        val page = messageDao.pageMessages(threadId, CHAT_PAGE_SIZE, offset = 0)
        state.update {
            it.copy(
                messages = page.asReversed().map { item -> item.toChatMessageUi() },
                hasMore = page.size == CHAT_PAGE_SIZE,
                isLoadingMore = false,
            )
        }
    }

    /** La página anterior continúa antes de la sección cargada.;El cursor también se lee en la puerta. */
    private suspend fun loadOlderPage() = writer.withWriteLock {
        val current = state.value
        val page = messageDao.pageMessages(threadId, CHAT_PAGE_SIZE, offset = current.messages.size)
        val older = page.asReversed().map { item -> item.toChatMessageUi() }
        state.update {
            it.copy(
                messages = older + current.messages,
                hasMore = page.size == CHAT_PAGE_SIZE,
                isLoadingMore = false,
            )
        }
    }
}
