package io.zer0.muse.miniphone.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShell
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTab
import io.zer0.muse.miniphone.ui.shell.MiniPhoneTags
import io.zer0.muse.miniphone.ui.shell.MiniPhoneThreadUi
import io.zer0.muse.miniphone.ui.shell.ShellUiState
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Representación de verificación de la página de chat:Columnas izquierda y derecha, separación de tiempo, disponibilidad de campos de entrada, plegado extralargo,y navegación dentro y fuera de la lista de conversaciones.
 *
 * La afirmación solo recae en "¿La posición es correcta/se puede presionar/expandir?" El estado es alimentado directamente por la prueba.,
 * no pasar ViewModel —— ¿De dónde provienen los datos? [MiniPhoneChatViewModelTest] Sea responsable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneChatUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<String>()
    private val resent = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    @Test
    fun ownBubbleSitsRightAndPeerBubbleSitsLeft() {
        render(
            chatState(
                message("m1", MpActorType.CHARACTER, "en", createdAt = MINUTE_MS),
                message("m2", MpActorType.USER, "¿Está ahí?", createdAt = MINUTE_MS + 1_000L),
            ),
        )

        val rootWidth = widthOf(compose.onRoot().getBoundsInRoot())
        val peer = compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).getBoundsInRoot()
        val own = compose.onNodeWithTag(MiniPhoneChatTags.bubble("m2")).getBoundsInRoot()

        assertTrue("La burbuja del oponente debería caer en la mitad izquierda.", centerX(peer) < rootWidth / 2f)
        assertTrue("Tu propia burbuja debería aterrizar en la mitad derecha.", centerX(own) > rootWidth / 2f)
        assertTrue("El punto de partida de tu propia burbuja está detrás de la del oponente.", own.left > peer.right)
    }

    @Test
    fun timeDividerSitsBetweenMessagesThatAreMinutesApart() {
        render(
            chatState(
                message("m1", MpActorType.CHARACTER, "vamos primero", createdAt = MINUTE_MS),
                message("m2", MpActorType.USER, "he llegado", createdAt = MINUTE_MS + TIME_GAP_MS),
            ),
        )

        val divider = compose.onNodeWithTag(MiniPhoneChatTags.timeDivider(MINUTE_MS + TIME_GAP_MS)).getBoundsInRoot()
        val earlier = compose.onNodeWithTag(MiniPhoneChatTags.message("m1")).getBoundsInRoot()
        val later = compose.onNodeWithTag(MiniPhoneChatTags.message("m2")).getBoundsInRoot()

        assertTrue("La línea de tiempo está ordenada después de la anterior.", divider.top >= earlier.bottom)
        assertTrue("La línea de tiempo se ordena antes que la posterior.", divider.bottom <= later.top)
    }

    @Test
    fun sendButtonIsDisabledUntilSomethingIsTyped() {
        render(chatState())

        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).assertIsNotEnabled()
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).performClick()
        assertEquals(emptyList<String>(), sent)

        compose.onNodeWithTag(MiniPhoneChatTags.INPUT_FIELD).performTextInput("¿Está ahí?")
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).assertIsEnabled()
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).performClick()

        assertEquals(listOf("¿Está ahí?"), sent)
    }

    @Test
    fun workingCopyReplacesTheTypingRowWhileTheCharacterDoesSomething() {
        render(
            chatState(message("m1", MpActorType.USER, "Ayúdame a encontrarlo", createdAt = MINUTE_MS))
                .copy(isGenerating = true, activity = MpActivityKind.SEARCHING),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.ACTIVITY).assertExists()
        compose.onNodeWithText("La otra parte está hurgando…").assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.TYPING).assertDoesNotExist()
    }

    @Test
    fun systemNoticeIsCenteredAndLongTextCollapses() {
        render(
            chatState(
                message("sys", MpActorType.SYSTEM, "La otra parte no respondió.", createdAt = MINUTE_MS, type = MpMessageType.SYSTEM),
                message("long", MpActorType.CHARACTER, LONG_TEXT, createdAt = MINUTE_MS + 1_000L),
            ),
        )

        val rootWidth = widthOf(compose.onRoot().getBoundsInRoot())
        val noticeCenter = centerX(compose.onNodeWithTag(MiniPhoneChatTags.message("sys")).getBoundsInRoot())
        val middle = rootWidth / 2f
        assertTrue("Mensajes del sistema centrados", noticeCenter > middle - rootWidth * NOTICE_CENTER_TOLERANCE)
        assertTrue("Mensajes del sistema centrados", noticeCenter < middle + rootWidth * NOTICE_CENTER_TOLERANCE)

        compose.onNodeWithTag(MiniPhoneChatTags.expand("long")).assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.expand("long")).performClick()
        compose.onNodeWithText(COLLAPSE_ACTION).assertExists()
    }

    @Test
    fun tappingThreadRowOpensChatAndLeavingItReturnsToTheList() {
        val shellState = ShellUiState(
            threads = listOf(
                MiniPhoneThreadUi(
                    id = "t1",
                    name = "ali",
                    preview = "¿Está ahí?",
                    lastMessageAt = MINUTE_MS,
                    unreadCount = 2,
                    pinned = false,
                ),
            ),
        )
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneShell(
                    state = shellState,
                    chatContent = { threadId, onBack ->
                        Column {
                            Text(text = "chat:$threadId", modifier = Modifier.testTag(FAKE_CHAT_TAG))
                            Text(
                                text = "Volver a la lista",
                                modifier = Modifier
                                    .testTag(FAKE_CHAT_BACK_TAG)
                                    .clickable(onClick = onBack),
                            )
                        }
                    },
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).performClick()
        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).performClick()

        compose.onNodeWithTag(FAKE_CHAT_TAG).assertTextEquals("chat:t1")
        // La barra de pestañas no se muestra en la página de chat.,Sólo se pueden organizar los campos de entrada
        compose.onNodeWithTag(MiniPhoneTags.tab(MiniPhoneTab.MESSAGES)).assertDoesNotExist()

        compose.onNodeWithTag(FAKE_CHAT_BACK_TAG).performClick()

        compose.onNodeWithTag(FAKE_CHAT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneTags.thread("t1")).assertExists()
    }

    @Test
    fun longPressOnOwnTextOffersCopyDeleteAndResend() {
        render(chatState(message("m1", MpActorType.USER, "¿Está ahí?", createdAt = MINUTE_MS)))

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.MENU).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.COPY)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.RESEND)).assertIsDisplayed()
    }

    @Test
    fun longPressOnCharacterTextHasNoResend() {
        render(chatState(message("m1", MpActorType.CHARACTER, "en", createdAt = MINUTE_MS)))

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.COPY)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.RESEND)).assertDoesNotExist()
    }

    @Test
    fun longPressOnImageOffersDeleteButNotCopy() {
        render(chatState(message("img", MpActorType.USER, "[fotos]", createdAt = MINUTE_MS, type = MpMessageType.IMAGE)))

        compose.onNodeWithTag(MiniPhoneChatTags.image("img")).performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.COPY)).assertDoesNotExist()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.RESEND)).assertDoesNotExist()
    }

    @Test
    fun longPressOnVoiceOffersDeleteButNotCopy() {
        // La barra de voz necesita ser obtenida por el jugador de Koin.;Sólo echa un vistazo al menú aquí.,Regale un controlador que en realidad no se reproducirá
        startKoin { modules(module { single { MiniPhoneVoicePlaybackController() } }) }
        try {
            render(
                chatState(
                    message("voice", MpActorType.CHARACTER, "[Voz]", createdAt = MINUTE_MS, type = MpMessageType.VOICE),
                ),
            )

            compose.onNodeWithTag(MiniPhoneChatTags.voiceUnavailable("voice")).performTouchInput { longClick() }
            compose.waitForIdle()

            compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).assertIsDisplayed()
            compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.COPY)).assertDoesNotExist()
        } finally {
            stopKoin()
        }
    }

    @Test
    fun deletingAMessageAsksForConfirmationFirst() {
        render(chatState(message("m1", MpActorType.USER, "¿Está ahí?", createdAt = MINUTE_MS)))

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.DELETE_DIALOG).assertIsDisplayed()
        assertEquals("No se puede eliminar antes de confirmar", emptyList<String>(), deleted)

        compose.onNodeWithTag(MiniPhoneChatTags.DELETE_CANCEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.DELETE_DIALOG).assertDoesNotExist()
        assertEquals(emptyList<String>(), deleted)

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.DELETE)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.DELETE_CONFIRM).performClick()
        compose.waitForIdle()

        assertEquals(listOf("m1"), deleted)
    }

    @Test
    fun resendingFromTheMenuReportsTheMessageId() {
        render(chatState(message("m1", MpActorType.USER, "¿Está ahí?", createdAt = MINUTE_MS)))

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.menuAction(ChatMenuAction.RESEND)).performClick()
        compose.waitForIdle()

        assertEquals(listOf("m1"), resent)
    }

    @Test
    fun failedMessageShowsARetryMarkBesideIt() {
        render(
            chatState(
                message("m1", MpActorType.USER, "¿Está ahí?", createdAt = MINUTE_MS, status = MpMessageStatus.FAILED),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.status("m1")).assertIsDisplayed()

        compose.onNodeWithTag(MiniPhoneChatTags.status("m1")).performClick()
        compose.waitForIdle()

        assertEquals(listOf("m1"), resent)
    }

    @Test
    fun sendingMessageShowsALightProgressWordNotACover() {
        render(
            chatState(
                message("m1", MpActorType.USER, "¿Está ahí?", createdAt = MINUTE_MS, status = MpMessageStatus.SENDING),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.status("m1")).assertTextEquals("Enviando")
        // El texto principal no puede ser oscurecido por el estado progresista.
        compose.onNodeWithText("¿Está ahí?").assertIsDisplayed()
    }

    @Test
    fun newMessagesPillAppearsOnlyAfterLeavingTheBottom() {
        var state by mutableStateOf(chatState(*manyMessages(count = 40)))
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(state = state, onBack = {}, onLoadOlder = {})
            }
        }
        // La página se detendrá en la última,No reproducir indicaciones
        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).assertDoesNotExist()

        // Retroceder a una posición anterior(Desplazamiento programado,ni un gesto)
        compose.onNodeWithTag(MiniPhoneChatTags.MESSAGE_LIST).performScrollToIndex(20)
        compose.waitForIdle()

        state = state.copy(
            messages = state.messages + message("arrived", MpActorType.CHARACTER, "lo que acabo de enviar", createdAt = LEAVING_BOTTOM_MS),
        )
        compose.waitForIdle()

        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).assertIsDisplayed()
        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).assertTextEquals("1 nuevos mensajes")

        // Haga clic para regresar a lo último:Recuerda guardarlo
        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).assertDoesNotExist()
    }

    @Test
    fun newMessagesPillStaysHiddenWhileReaderIsAtTheBottom() {
        var state by mutableStateOf(chatState(*manyMessages(count = 40)))
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(state = state, onBack = {}, onLoadOlder = {})
            }
        }

        state = state.copy(
            messages = state.messages + message("arrived", MpActorType.CHARACTER, "lo que acabo de enviar", createdAt = LEAVING_BOTTOM_MS),
        )
        compose.waitForIdle()

        // gente en el fondo:Los nuevos mensajes son visibles directamente,Interrumpir la lectura sin reproducir indicaciones
        compose.onNodeWithTag(MiniPhoneChatTags.NEW_MESSAGES_PILL).assertDoesNotExist()
        compose.onNodeWithText("lo que acabo de enviar").assertIsDisplayed()
    }

    private fun render(state: ChatUiState) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(
                    state = state,
                    onBack = {},
                    onLoadOlder = {},
                    input = ChatInputActions(onSend = { sent += it }),
                    messageActions = ChatMessageActions(onResend = { resent += it }, onDelete = { deleted += it }),
                )
            }
        }
    }

    /** El centro horizontal del elemento. `getBoundsInRoot()` Dado es el rectángulo Dp,No existe un centro ya preparado. */
    private fun centerX(bounds: DpRect): Dp = bounds.left + widthOf(bounds) / 2f

    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun chatState(vararg messages: ChatMessageUi): ChatUiState = ChatUiState(
        threadId = "t1",
        characterId = "c1",
        peerName = "ali",
        messages = messages.toList(),
    )

    private fun message(
        id: String,
        sender: MpActorType,
        content: String,
        createdAt: Long,
        type: MpMessageType = MpMessageType.TEXT,
        status: MpMessageStatus = MpMessageStatus.SENT,
    ): ChatMessageUi = ChatMessageUi(
        id = id,
        sender = sender,
        type = type,
        content = content,
        createdAt = createdAt,
        status = status,
    )

    /** Una secuencia de mensajes en orden ascendente de tiempo.:suficiente para soportar el rodamiento,Es conveniente comprobar el mensaje después de \"dejar el final\". */
    private fun manyMessages(count: Int): Array<ChatMessageUi> = Array(count) { index ->
        message(
            id = "m$index",
            sender = if (index % 2 == 0) MpActorType.CHARACTER else MpActorType.USER,
            content = "No. $index frase",
            createdAt = (index + 1) * MINUTE_MS,
        )
    }

    private companion object {
        const val MINUTE_MS: Long = 60_000L
        const val LEAVING_BOTTOM_MS: Long = 41 * MINUTE_MS
        const val TIME_GAP_MS: Long = 10 * MINUTE_MS
        const val FAKE_CHAT_TAG: String = "mp-fake-chat"
        const val FAKE_CHAT_BACK_TAG: String = "mp-fake-chat-back"
        const val COLLAPSE_ACTION: String = "cerrar"

        /** tolerancia de centrado:Permita una desviación del 10 % del ancho de la pantalla. */
        const val NOTICE_CENTER_TOLERANCE: Float = 0.1f

        /** 300 Texto con más de 10 palabras.,Se utiliza para verificar el plegado. */
        val LONG_TEXT: String = "Esta es una frase muy larga.".repeat(40)
    }
}
