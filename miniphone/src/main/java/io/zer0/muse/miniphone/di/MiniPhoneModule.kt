package io.zer0.muse.miniphone.di

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.chat.MpConversationActions
import io.zer0.muse.miniphone.data.chat.MpConversationStore
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.chat.MpGroupChatStore
import io.zer0.muse.miniphone.data.chat.auto.MpProactiveChatRunner
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.media.miniPhoneImageStore
import io.zer0.muse.miniphone.data.moment.MpCharacterMaterial
import io.zer0.muse.miniphone.data.moment.MpMomentsRepository
import io.zer0.muse.miniphone.data.moment.auto.MpMomentAutoRunner
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import io.zer0.muse.miniphone.data.settings.miniPhoneMomentAutoStore
import io.zer0.muse.miniphone.ui.chat.MiniPhoneChatViewModel
import io.zer0.muse.miniphone.ui.chat.MiniPhoneVoicePlaybackController
import io.zer0.muse.miniphone.ui.moments.MiniPhoneMomentsViewModel
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShellViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Montaje Koin de pequeño módulo de teléfono celular.
 *
 * base de datos,DAO, y [MiniPhoneHost] Implementación de marcador de posición. Las aplicaciones de host utilizan capacidades reales del host
 * `override<MiniPhoneHost> { ... }` Anule aquí la definición del marcador de posición.
 */
val miniPhoneModule: Module = module {

    // ── base de datos ──
    single { MiniPhoneDb.create(androidContext()) }

    // ── DAO ──
    single { get<MiniPhoneDb>().profileDao() }
    single { get<MiniPhoneDb>().threadDao() }
    single { get<MiniPhoneDb>().threadMemberDao() }
    single { get<MiniPhoneDb>().messageDao() }
    single { get<MiniPhoneDb>().momentDao() }
    single { get<MiniPhoneDb>().momentLikeDao() }
    single { get<MiniPhoneDb>().momentCommentDao() }
    single { get<MiniPhoneDb>().diaryDao() }
    single { get<MiniPhoneDb>().albumDao() }

    // ── Ubicación de la imagen:Copie la imagen seleccionada en el directorio privado.,Sólo la ruta se almacena en la biblioteca. ──
    single { miniPhoneImageStore(androidContext()) }

    // ── Reproducción de voz:El único jugador en el módulo.,Común a todas las tiras de voz.(Solo una transmisión al mismo tiempo.) ──
    single { MiniPhoneVoicePlaybackController() }

    // ── Momentos:Publique, dé me gusta, comente y elimine todo a través de esta interfaz de lectura y escritura. ──
    single { MpMomentsRepository(momentDao = get(), likeDao = get(), commentDao = get(), imageStore = get()) }

    // ── El cambio para publicar actualizaciones automáticamente en segundo plano y la hora de la última generación(Propio del módulo DataStore,sin moverse Room schema) ──
    single<MpMomentAutoStore> { miniPhoneMomentAutoStore(androidContext()) }

    // ── Material para contenido generado por personajes.:¿De qué has hablado recientemente, cuál fue tu última publicación, qué hora es ahora?(Compartir actualizaciones y mensajes privados) ──
    single {
        MpCharacterMaterial(
            threadDao = get(),
            messageDao = get(),
            momentDao = get(),
        )
    }

    // ── Publicar actualizaciones automáticamente en segundo plano:Un proceso completo,Worker Sólo responsable de despertarlo. ──
    single { MpMomentAutoRunner(host = get(), repository = get(), store = get(), material = get()) }

    // ── Rol activo mensaje privado:Un proceso completo(juicio → generar → Cayó en la biblioteca → no leído +1) ──
    single { MpProactiveChatRunner(host = get(), threadDao = get(), messageDao = get(), store = get(), material = get()) }

    // ── Escritura de lista de sesiones:Fijar arriba/Borrar no leídos/Eliminar conversación/Borrar ──
    single<MpConversationActions> {
        MpConversationStore(db = get(), threadDao = get(), messageDao = get(), memberDao = get())
    }

    // ── Creación de grupos y gestión de miembros del grupo.:Crear un grupo/Cambiar nombre/Agregar personas/patear a alguien/disolver ──
    single<MpGroupActions> {
        MpGroupChatStore(db = get(), threadDao = get(), memberDao = get(), messageDao = get())
    }

    // ── Habilidad del anfitrión(Marcador de posición,A ser cubierto por el anfitrión) ──
    single<MiniPhoneHost> { PlaceholderMiniPhoneHost() }

    // ── Estado de la interfaz ──
    viewModel { MiniPhoneShellViewModel(threadDao = get(), store = get(), groupStore = get(), host = get(), settings = get()) }
    viewModel { params ->
        MiniPhoneMomentsViewModel(
            repository = get(),
            host = get(),
            settings = get(),
            material = get(),
            onlyMine = params.get(),
        )
    }
    viewModel { params ->
        MiniPhoneChatViewModel(
            threadId = params.get(),
            messageDao = get(),
            threadDao = get(),
            memberDao = get(),
            host = get(),
            imageStore = get(),
            groupStore = get(),
        )
    }
}

/**
 * Marcador de posición antes de que el host implemente el acceso:Siempre "no hay datos",No se lanza ninguna excepción.
 */
private class PlaceholderMiniPhoneHost : MiniPhoneHost {
    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(emptyList())

    override fun observeProfile(): Flow<MpProfile> = flowOf(MpProfile(nickname = ""))

    /** El host de marcador de posición no tiene ninguna aplicación de host para la cual informar una versión. */
    override fun appVersionName(): String = ""

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    /** El anfitrión del marcador de posición no tiene capacidad de reconocimiento.:Todas las grabaciones y transcripciones se tratarán como si no se hubieran escuchado con claridad. */
    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    /** El anfitrión del marcador de posición no tiene capacidad de reconocimiento.,La interfaz debería atenuar el micrófono. */
    override suspend fun supportsVoiceTranscription(): Boolean = false
}
