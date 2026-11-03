package io.zer0.muse.miniphone.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.chat.MpConversationActions
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.chat.canCreateGroup
import io.zer0.muse.miniphone.data.chat.groupTitleOf
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * La única fuente de estado del shell.
 *
 * cinco corrientes(Tabla de conversación, rol, datos locales, cambio de envío automático a Momentos, cambio de mensaje privado activo de rol)Haz una copia aquí [ShellUiState];
 * La interfaz obtiene los datos combinados.,No retenido DAO,Tampoco hay una biblioteca de cheques en Composable.
 *
 * Escribir acción en la línea de conversación(Fijar arriba, borrar no leídos, eliminar conversación, borrar)transferir a [MpConversationActions]:
 * La lista en sí es un flujo de tablas de sesiones.,No es necesario actualizar manualmente después de escribir. Crea un grupo y transfiérelo a [MpGroupActions],
 * Deletrea el nombre del grupo aquí —— La lista de personajes proporcionada por el anfitrión ya se encuentra en este estado.,La capa de escritura no tiene que volver a preguntar.
 *
 * Retenido después de que todos los suscriptores abandonan el sitio [KEEP_ALIVE_MS] Deténgase río arriba nuevamente:Al cambiar a la página de chat y regresar,
 * No es necesario esperar a que se vuelvan a ejecutar los cuatro flujos ascendentes.
 */
class MiniPhoneShellViewModel(
    private val threadDao: MpThreadDao,
    private val store: MpConversationActions,
    private val groupStore: MpGroupActions,
    host: MiniPhoneHost,
    private val settings: MpMomentAutoStore,
) : ViewModel() {

    /** Nombre de la versión del host:Sin cambios durante toda la estancia.,Solo pregunta una vez al ingresar a la página. */
    private val appVersionName: String = host.appVersionName()

    /** puerta de sesión:La libreta de direcciones no puede crear dos conversaciones ni siquiera con dos clics. */
    private val openThreadMutex = Mutex()

    /** Lista de personajes recibidos más recientemente:Al crear un grupo, utilícelo para reemplazar la identificación seleccionada con el nombre utilizado para el nombre del grupo. */
    private var charactersById: Map<String, MpCharacter> = emptyMap()

    val uiState: StateFlow<ShellUiState> = combine(
        combine(threadDao.observeThreads(), host.observeCharacters(), host.observeProfile(), ::assembleWithCharacterCache),
        settings.enabledFlow,
        settings.chatProactiveEnabledFlow,
    ) { state, autoMomentEnabled, chatProactiveEnabled ->
        state.copy(
            autoMomentEnabled = autoMomentEnabled,
            chatProactiveEnabled = chatProactiveEnabled,
            appVersionName = appVersionName,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(KEEP_ALIVE_MS),
        initialValue = ShellUiState(),
    )

    /**
     * Se utiliza al hacer clic en un carácter en la libreta de direcciones.:Si ya tienes una conversación, solo dala directamente. id,Si no, abra una sesión vacía.
     *
     * "Buscar por función" es la única base para juzgar —— La misma persona siempre corresponderá sólo a una conversación 1:1 en la lista de conversaciones.,
     * Los puntos repetidos no dejarán una línea de diálogo en blanco. Todo eso de "comprobar primero y luego escribir" es [openThreadMutex] hazlo adentro:
     * Al conectar puntos, esperaré hasta que termine el anterior antes de volver a comprobarlo.,El que encontré fue el que acababan de construir.
     *
     * @return sesión id;Devuelto cuando la identificación del rol está vacía null(La interfaz no debe utilizar un rol vacío para abrir una sesión.)
     */
    suspend fun openOrCreateThread(characterId: String): String? {
        if (characterId.isBlank()) return null
        return openThreadMutex.withLock { existingOrNewThread(characterId) }
    }

    private suspend fun existingOrNewThread(characterId: String): String {
        threadDao.findThreadByCharacter(characterId)?.let { existing -> return existing.id }
        val created = MpThreadEntity(id = UUID.randomUUID().toString(), characterId = characterId)
        threadDao.upsert(created)
        return created.id
    }

    /**
     * Crear un grupo y devolver su sesión. id;No hay suficientes personas para elegir(O la identificación del rol está vacía)Devuelve nulo.
     *
     * Los nombres de los grupos se unen en el orden de selección.(terminando con "yo"). El nombre se toma de la lista de personajes más reciente.:
     * La lista aún no ha llegado o se ha eliminado un determinado personaje.,Degenera en un mensaje en lugar de un nombre vacío. ——
     * Una vez que el nombre del grupo se escribe en la base de datos, no se volverá a calcular.,Prefiero escribir un nombre que se pueda entender aquí.
     *
     * @param characterIds rol seleccionado id,El orden es el orden en el grupo.
     */
    suspend fun createGroup(characterIds: List<String>): String? {
        val members = characterIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (!canCreateGroup(members.size)) return null
        val names = members.map { id ->
            charactersById[id]?.name?.takeIf(String::isNotBlank) ?: UNKNOWN_CHARACTER_NAME
        }
        return groupStore.createGroup(members, groupTitleOf(names))
    }

    /** Estado sintético,Por cierto, deja la lista de personajes en [charactersById] en(Para crear un grupo, use un nombre,No puedes volver a preguntarle al anfitrión.). */
    private fun assembleWithCharacterCache(
        threads: List<MpThreadEntity>,
        characters: List<MpCharacter>,
        profile: MpProfile,
    ): ShellUiState {
        charactersById = characters.associateBy { it.id }
        return assembleShellState(threads, characters, profile)
    }

    /**
     * Activa "Publicar automáticamente en Momentos".
     *
     * Escribir solo configuraciones:El trabajador en segundo plano leerá este interruptor primero en cada ronda.,Si está desactivado, se omitirá toda la ronda y la red no se enviará ni una sola vez.
     * La tarea programada en sí no se cancela —— Restaurar inmediatamente al reabrir,No es necesario esperar a que el anfitrión vuelva a ajustar el registro.
     */
    fun setAutoMomentEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setEnabled(enabled) }
    }

    /**
     * Corta "Mensaje privado activo del personaje".
     *
     * También solo escribe configuraciones.:Worker Lea este cambio primero en cada ronda.,Si está desactivado, se saltará toda la ronda. Desactivado predeterminado,Por lo tanto, los usuarios nunca serán molestados a menos que lo enciendan.
     */
    fun setChatProactiveEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setChatProactiveEnabled(enabled) }
    }

    /** Anclar/desanclar una conversación. */
    fun setThreadPinned(threadId: String, pinned: Boolean) {
        viewModelScope.launch { store.setPinned(threadId, pinned) }
    }

    /** Borrar no leídos:\"Marcar como leído\" en la línea de conversación. */
    fun markThreadRead(threadId: String) {
        viewModelScope.launch { store.markRead(threadId) }
    }

    /** Eliminar una conversación y todos sus mensajes. */
    fun deleteThread(threadId: String) {
        viewModelScope.launch { store.deleteThread(threadId) }
    }

    /** Borrar historial de chat(Todas las conversaciones y mensajes.);Otros datos como Momentos se mantienen sin cambios. */
    fun clearConversations() {
        viewModelScope.launch { store.clearAll() }
    }

    private companion object {
        const val KEEP_ALIVE_MS: Long = 5_000L
    }
}
