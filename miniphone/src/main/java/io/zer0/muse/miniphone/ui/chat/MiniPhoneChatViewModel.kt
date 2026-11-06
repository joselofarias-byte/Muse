package io.zer0.muse.miniphone.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.db.dao.MpMessageDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.dao.MpThreadMemberDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import io.zer0.muse.miniphone.data.media.MpImageStore
import io.zer0.muse.miniphone.ui.shell.UNKNOWN_CHARACTER_NAME
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado de la página de chat para una sola sesión.
 *
 * Sólo dos cosas que hacer aquí:Agregar el estado producido por los colaboradores en [uiState],Transfiera la llamada de interfaz a la parte a la que debe dirigirse.
 * Abrir hilos y pasar páginas en [ChatPagingController],Enviar y recibir respuestas en [ChatSendController],
 * Las imágenes se guardan en [ChatMediaWriter],El camino de bajada compartido por las tres ubicaciones está en [ChatMessageWriter].
 * Interfaz de solo lectura [uiState],no tocar DAO,Tampoco decides por ti mismo si debes enviar una solicitud de red o no.
 *
 * @param imageStore bandeja de fotos:La imagen seleccionada se copia primero en el directorio privado.,Sólo las rutas locales se almacenan en la biblioteca.
 * @param groupStore Gestión de miembros del grupo(Cambiar nombre, agregar personas, expulsar personas, disolver)puerto de escritura
 * @param clock Dos fuentes alternativas para deletrear mensajes:¿Qué hora es ahora, próximo lote? id
 * @param pauseBetweenResponders Una pausa entre dos personajes del grupo.;Predeterminado 600–1200ms,Las pruebas que inyectan implementaciones vacías no tienen que esperar
 */
class MiniPhoneChatViewModel(
    private val threadId: String,
    private val messageDao: MpMessageDao,
    private val threadDao: MpThreadDao,
    private val memberDao: MpThreadMemberDao,
    private val host: MiniPhoneHost,
    imageStore: MpImageStore,
    groupStore: MpGroupActions,
    clock: ChatWriteClock = ChatWriteClock(),
    private val pauseBetweenResponders: suspend () -> Unit = { delay(responderGapMillis()) },
) : ViewModel() {

    private val mutableState = MutableStateFlow(ChatUiState(threadId = threadId))

    val uiState: StateFlow<ChatUiState> = mutableState.asStateFlow()

    /** Gestión de miembros del grupo:Cambiar nombre, agregar personas, mudarse, disolverse. La interfaz envía esta copia directamente a la página de miembros. */
    internal val groupAdmin: GroupAdminController = GroupAdminController(
        threadId = threadId,
        threadDao = threadDao,
        memberDao = memberDao,
        store = groupStore,
        characters = { peerCharacters },
        state = mutableState,
        scope = viewModelScope,
    )

    /** La última lista de personajes dada por el anfitrión.,Se utiliza para complementar el nombre y el avatar. */
    private var peerCharacters: List<MpCharacter> = emptyList()

    /** Fila de miembros del grupo(la parte del curry),Pulsa para unirte al grupo;Ingrese el estado después de completar el nombre. */
    private var memberRows: List<MpThreadMemberEntity> = emptyList()

    /** Borrar tareas no leídas durante el período visible;Cancelar al salir de la página. */
    private var markReadJob: Job? = null

    private val writer = ChatMessageWriter(
        threadId = threadId,
        messageDao = messageDao,
        threadDao = threadDao,
        now = clock.now,
        newId = clock.newId,
        onStored = { message -> mutableState.update { it.copy(messages = it.messages + message) } },
        onStatusChanged = { messageId, status ->
            mutableState.update { state ->
                state.copy(
                    messages = state.messages.map { message ->
                        if (message.id == messageId) message.copy(status = status) else message
                    },
                )
            }
        },
        onDeleted = { messageId ->
            mutableState.update { state ->
                state.copy(messages = state.messages.filterNot { message -> message.id == messageId })
            }
        },
    )

    private val media = ChatMediaWriter(
        imageStore = imageStore,
        writer = writer,
        threadId = threadId,
    )

    private val paging = ChatPagingController(
        threadId = threadId,
        messageDao = messageDao,
        threadDao = threadDao,
        writer = writer,
        state = mutableState,
        scope = viewModelScope,
        onThreadOpened = ::applyThreadInfo,
    )

    private val sender = ChatSendController(
        threadId = threadId,
        writer = writer,
        threadDao = threadDao,
        host = host,
        media = media,
        state = mutableState,
        scope = viewModelScope,
        pauseBetweenResponders = pauseBetweenResponders,
        groupCandidates = ::responderCandidates,
    )

    init {
        // Dos corrientes del anfitrión.:La lista de personajes se utiliza para completar el nombre y el avatar.,Los datos locales se utilizan para compensar tu propio avatar.
        viewModelScope.launch {
            host.observeCharacters()
                .catch { emit(emptyList()) }
                .collect { characters ->
                    peerCharacters = characters
                    // Para administrar los miembros del grupo, primero debe enumerar las "personas que no están en el grupo".,Por lo tanto, también es necesario introducir la lista completa.
                    mutableState.update { it.copy(characters = characters) }
                    applyThreadInfo()
                }
        }
        // Fila de miembros del grupo:Las conversaciones de mensajes privados no funcionan,Esta secuencia está directamente vacía.,No es necesario tomar otra sucursal.
        viewModelScope.launch {
            memberDao.observeMembers(threadId)
                .catch { emit(emptyList()) }
                .collect { rows ->
                    memberRows = rows
                    applyMembers()
                }
        }
        viewModelScope.launch {
            host.observeProfile()
                .catch { emit(MpProfile(nickname = "")) }
                .collect { profile ->
                    mutableState.update {
                        it.copy(
                            selfName = profile.nickname,
                            selfAvatarUri = profile.avatarUri?.takeIf(String::isNotBlank),
                        )
                    }
                }
        }
        // Solo pregunte una vez si se puede utilizar la entrada de voz.:La hora de entrada a la página y toda la estancia se mantienen sin cambios.,No es necesario vincularse con la página de configuración en tiempo real.
        viewModelScope.launch {
            val supported = host.supportsVoiceTranscription()
            mutableState.update { it.copy(voiceInputAvailable = supported) }
        }
        paging.open()
        sender.start()
    }

    /** Cargue una página anterior cuando se desplace hacia la parte superior. */
    fun loadOlder() {
        paging.loadOlder()
    }

    /** Borrar no leído al ingresar a la página;Salir y volver a entrar en la misma sesión reutilizará este objeto.,La interfaz se ajustará nuevamente en ese momento. */
    fun markRead() {
        paging.markRead()
    }

    /**
     * Visibilidad de la página. Durante el período visible, siempre que cambie la cantidad de mensajes en esta conversación(Ingrese a la página, viene un nuevo mensaje.)Borrar no leídos:
     * Cuando un mensaje privado activo cae en la conversación que estás viendo en segundo plano,Las listas de conversaciones no deben dejar una marca de no leído.
     *
     * Lo que sigo es el flujo de noticias de Curry.,en lugar de la ventana de la interfaz:La escritura en segundo plano no pasa por la devolución de llamada de escritura de esta página.,
     * Sólo se puede ver si la propia tabla cambia. Utilice Flow para recopilar en lugar de consultas programadas:No hay mensajes nuevos, cero escrituras en la base de datos.
     * Cancelar al salir de la página —— También sigo borrando palabras no leídas en otros lugares.,Los usuarios nunca verán mensajes de fondo.
     */
    fun setVisible(visible: Boolean) {
        markReadJob?.cancel()
        markReadJob = if (visible) {
            viewModelScope.launch {
                paging.markRead()
                messageDao.observeMessages(threadId)
                    .map { messages -> messages.size }
                    .distinctUntilChanged()
                    .collect { paging.markRead() }
            }
        } else {
            null
        }
    }

    /** Envía un mensaje de texto. */
    fun send(text: String) {
        sender.send(text)
    }

    /** envía algunas fotos,Suelte uno en orden de selección. */
    fun sendImages(uris: List<Uri>) {
        sender.sendImages(uris)
    }

    /** Reenviar un mensaje de texto que enviaste:Repita el proceso de envío para la misma línea en su lugar. */
    fun resend(messageId: String) {
        sender.resend(messageId)
    }

    /** Eliminar un mensaje localmente;La sesión en sí no se mueve. */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch { writer.delete(messageId) }
    }

    /**
     * Convierte un WAV grabado por el usuario en texto.
     *
     * La capacidad de transcribir pertenece al anfitrión.:El módulo es responsable de la recopilación y visualización.,Aquí solo se pasan bytes.
     * Retorno del fracaso null,El campo de entrada indica "no escuché con claridad"—— La grabación en sí no se deja en la biblioteca.,Tampoco enviado.
     */
    suspend fun transcribeVoice(wavBytes: ByteArray): String? = host.transcribeVoice(wavBytes)

    /**
     * Deja una foto del personaje.
     *
     * En realidad, no se genera ninguna imagen en ninguna parte en esta etapa.,Este método está reservado para pruebas y dibujos de personajes posteriores.;
     * La interfaz no proporciona ninguna entrada de demostración.,La persona que llama es responsable de garantizar que [localFile] ya existe.
     */
    suspend fun insertCharacterImage(threadId: String, characterId: String, localFile: String) {
        media.storeCharacterImage(threadId, characterId, localFile)
    }

    override fun onCleared() {
        sender.close()
        super.onCleared()
    }

    /** Cuando se lee la línea de conversación:Complementa a la otra parte primero,Reponer a los miembros del grupo. Ambas cosas dependen de la lista de personajes.,Dividirlo en dos funciones hace que su lectura sea más sencilla. */
    private fun applyThreadInfo() {
        applyPeer()
        applyMembers()
    }

    /** Cuando llega la lista de roles o la identificación del rol de la sesión está en su lugar,Agregue el nombre y el avatar al estado. */
    private fun applyPeer() {
        val characterId = mutableState.value.characterId
        if (characterId.isEmpty()) return
        val peer = peerCharacters.firstOrNull { it.id == characterId }
        if (peer == null) {
            // Hay una lista pero esta persona no está:El personaje fue eliminado.;Mantenga el status quo hasta que llegue la lista.
            if (peerCharacters.isNotEmpty()) mutableState.update { it.copy(peerName = UNKNOWN_CHARACTER_NAME) }
            return
        }
        mutableState.update {
            it.copy(
                peerName = peer.name.takeIf(String::isNotBlank) ?: UNKNOWN_CHARACTER_NAME,
                peerAvatarUri = peer.avatarUri?.takeIf(String::isNotBlank),
            )
        }
    }

    /**
     * Candidatos a miembros del grupo para atender llamadas.
     *
     * Priorizar el que está en la interfaz.(El nombre ha sido asignado);Antes de que llegue el flujo de membresía, pregunte primero en la biblioteca —— Simplemente haga clic para unirse al grupo y publicar una oración.,
     * Tampoco nadie debe contestar la llamada sólo porque la lista de miembros llega tarde.
     */
    private suspend fun responderCandidates(): List<GroupResponderCandidate> {
        val rendered = mutableState.value.members
        if (rendered.isNotEmpty()) {
            return rendered.map { GroupResponderCandidate(characterId = it.characterId, name = it.name) }
        }
        val byId = peerCharacters.associateBy { it.id }
        val rows = memberRows.ifEmpty { memberDao.membersOf(threadId) }
        return rows.map { row ->
            GroupResponderCandidate(
                characterId = row.characterId,
                name = byId[row.characterId]?.name?.takeIf(String::isNotBlank) ?: UNKNOWN_CHARACTER_NAME,
            )
        }
    }

    /**
     * Haga coincidir los nombres y avatares de los miembros del grupo.
     *
     * Las filas de miembros son las de la biblioteca.,El nombre proviene de la lista de hosts.:Cuando se elimina un carácter, se convierte en un nombre de marcador de posición.,Pero esta línea debería permanecer ——
     * El número de personas del grupo debe coincidir con el del portavoz en la noticia histórica.,La lista no debería acortarse sólo porque una persona desaparezca.
     * La conversación de mensaje privado no tiene fila de miembros,No escribas nada aquí.
     */
    private fun applyMembers() {
        if (!mutableState.value.isGroup) return
        val byId = peerCharacters.associateBy { it.id }
        val members = memberRows.map { row ->
            val character = byId[row.characterId]
            ChatMemberUi(
                characterId = row.characterId,
                name = character?.name?.takeIf(String::isNotBlank) ?: UNKNOWN_CHARACTER_NAME,
                avatarUri = character?.avatarUri?.takeIf(String::isNotBlank),
            )
        }
        mutableState.update { it.copy(members = members) }
    }
}
