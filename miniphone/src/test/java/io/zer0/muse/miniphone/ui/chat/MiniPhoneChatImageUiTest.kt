package io.zer0.muse.miniphone.ui.chat

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Mensajes con imágenes con tu propio avatar:Rama de renderizado, devolución de llamada de imagen rota, apertura y cierre de visualización de imagen en pantalla completa, devolución de llamada de entrada de imagen.
 *
 * El estado se envía directamente a componentes sin estado.,no pasar ViewModel;Si el selector del sistema se puede levantar o no es una cuestión de la capa superior.,
 * Aquí sólo marcamos "Presione para enviar la solicitud".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneChatImageUiTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("mp-image-ui").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun imageMessageRendersAnImageNodeInsteadOfATextBubble() {
        render(
            chatState(
                message("m1", MpActorType.CHARACTER, "mira esto"),
                imageMessage("m2", MpActorType.USER, imageFile().absolutePath),
            ),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.image("m2")).assertExists()
        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m2")).assertDoesNotExist()
        // Las burbujas de texto en la misma pantalla no se ven afectadas.
        compose.onNodeWithTag(MiniPhoneChatTags.bubble("m1")).assertExists()
        compose.onNodeWithText("mira esto").assertExists()
    }

    @Test
    fun missingImageFileShowsTheBrokenPlaceholderInsteadOfCrashing() {
        render(chatState(imageMessage("m1", MpActorType.USER, "/no/such/dir/photo.jpg")))

        compose.onNodeWithTag(MiniPhoneChatTags.imageBroken("m1"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("La imagen no se puede mostrar").assertExists()
    }

    @Test
    fun tappingAnImageOpensTheFullScreenViewerAndTappingAnywhereClosesIt() {
        render(chatState(imageMessage("m1", MpActorType.USER, imageFile().absolutePath)))
        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_VIEWER).assertDoesNotExist()

        compose.onNodeWithTag(MiniPhoneChatTags.image("m1")).performClick()
        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_VIEWER).assertExists()

        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_VIEWER).performClick()
        compose.onNodeWithTag(MiniPhoneChatTags.IMAGE_VIEWER).assertDoesNotExist()
    }

    @Test
    fun ownBubbleShowsTheProfileAvatarOnTheRightWhenThereIsOne() {
        render(
            chatState(message("m1", MpActorType.USER, "¿Está ahí?"))
                .copy(selfName = "Ziqi", selfAvatarUri = "https://example.com/avatar.png"),
        )

        compose.onNodeWithTag(MiniPhoneChatTags.SELF_AVATAR).assertExists()
        val rootWidth = widthOf(compose.onRoot().getBoundsInRoot())
        val avatar = compose.onNodeWithTag(MiniPhoneChatTags.SELF_AVATAR).getBoundsInRoot()
        assertTrue("Tu avatar está en el lado derecho de la burbuja.", avatar.left > rootWidth / 2f)
    }

    @Test
    fun ownBubbleFallsBackToTheNicknameInitialWhenThereIsNoAvatar() {
        render(chatState(message("m1", MpActorType.USER, "¿Está ahí?")).copy(selfName = "Ziqi", selfAvatarUri = null))

        compose.onNodeWithTag(MiniPhoneChatTags.SELF_AVATAR).assertExists()
        compose.onNodeWithText("hijo").assertExists()
    }

    @Test
    fun imageButtonHandsThePickRequestToTheCaller() {
        var picks = 0
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(
                    state = chatState(),
                    onBack = {},
                    onLoadOlder = {},
                    input = ChatInputActions(onSend = {}, onPickImages = { picks += 1 }),
                )
            }
        }

        compose.onNodeWithTag(MiniPhoneChatTags.PICK_IMAGE).performClick()

        assertEquals("Simplemente haga clic y envíe la solicitud.", 1, picks)
        // La entrada de imágenes no debería afectar la tecla de envío: aún no se puede presionar la entrada vacía
        compose.onNodeWithTag(MiniPhoneChatTags.SEND_BUTTON).assertIsNotEnabled()
    }

    private fun render(state: ChatUiState) {
        compose.setContent {
            MiniPhoneTheme(darkTheme = false) {
                MiniPhoneChatScreen(
                    state = state,
                    onBack = {},
                    onLoadOlder = {},
                    input = ChatInputActions(),
                )
            }
        }
    }

    /** PNG verdadero colocado en el directorio temporal:La prensa de burbujas determina si el archivo existe o no para dibujar o romper la imagen. */
    private fun imageFile(): File {
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return File(directory, "photo.png").apply { writeBytes(bytes) }
    }

    private fun widthOf(bounds: DpRect): Dp = bounds.right - bounds.left

    private fun chatState(vararg messages: ChatMessageUi): ChatUiState = ChatUiState(
        threadId = "t1",
        characterId = "c1",
        peerName = "ali",
        messages = messages.toList(),
    )

    private fun message(id: String, sender: MpActorType, content: String, createdAt: Long = MINUTE_MS): ChatMessageUi = ChatMessageUi(
        id = id,
        sender = sender,
        type = MpMessageType.TEXT,
        content = content,
        createdAt = createdAt,
    )

    private fun imageMessage(id: String, sender: MpActorType, path: String, createdAt: Long = MINUTE_MS): ChatMessageUi = ChatMessageUi(
        id = id,
        sender = sender,
        type = MpMessageType.IMAGE,
        content = "[fotos]",
        mediaUri = path,
        createdAt = createdAt,
    )

    private companion object {
        const val MINUTE_MS: Long = 60_000L
    }
}
