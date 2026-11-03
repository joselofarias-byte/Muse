package io.zer0.muse.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import io.zer0.muse.ui.speech.VoiceAudioPlayer
import io.zer0.muse.ui.speech.VoiceDurationProbe
import io.zer0.muse.ui.speech.VoiceMessageStore
import io.zer0.muse.ui.speech.VoicePlaybackController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Prueba de comportamiento de reproducción y representación de la barra de mensajes de voz.
 *
 * pases de jugador [VoiceAudioPlayer] Inyecta una implementación falsa y por lo tanto no toca la pila de audio del sistema: la afirmación es
 * "El reproductor ha sido ajustado varias veces / el icono no ha sido cambiado / el anterior no se ha detenido al cambiar de tira"Estas burbujas tienen sus propios contratos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceMessageBubbleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val storageDir: File by lazy { File(tmp.root, "voice_messages").apply { mkdirs() } }

    private val store: VoiceMessageStore by lazy {
        VoiceMessageStore(storageDir = storageDir, durationProbe = VoiceDurationProbe { 7000L })
    }

    /** El archivo real en el directorio de archivo (`resolve` Sólo reconoce archivos en el directorio). */
    private fun storedVoice(name: String): String {
        val file = File(storageDir, name).apply { writeText("RIFF-fake-wav") }
        return "file://" + file.absolutePath
    }

    private fun render(
        voiceUri: String,
        durationMs: Long,
        controller: VoicePlaybackController = VoicePlaybackController { FakeVoiceAudioPlayer() },
        isFromUser: Boolean = false,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                VoiceMessageBubble(
                    voiceUri = voiceUri,
                    durationMs = durationMs,
                    isFromUser = isFromUser,
                    controller = controller,
                    store = store,
                )
            }
        }
    }

    @Test
    fun durationOfSevenSecondsShowsMinuteSecondText() {
        render(voiceUri = storedVoice("seven.wav"), durationMs = 7_000L)

        composeTestRule.onNodeWithText("0:07").assertExists()
        composeTestRule.onNodeWithText("--:--").assertDoesNotExist()
    }

    @Test
    fun unknownDurationShowsPlaceholderInsteadOfZero() {
        render(voiceUri = storedVoice("unknown.wav"), durationMs = 0L)

        composeTestRule.onNodeWithText("--:--").assertExists()
        composeTestRule.onNodeWithText("0:00", substring = true).assertDoesNotExist()
    }

    @Test
    fun tapStartsPlaybackOnceAndSwitchesIconToPause() {
        val player = FakeVoiceAudioPlayer()
        val controller = VoicePlaybackController { player }
        render(voiceUri = storedVoice("toggle.wav"), durationMs = 7_000L, controller = controller)

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_PLAY_ICON_TAG, useUnmergedTree = true).assertExists()

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_BUBBLE_TAG).performClick()

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_PAUSE_ICON_TAG, useUnmergedTree = true).assertExists()
        assertEquals("Al hacer clic una vez, el juego solo debería comenzar una vez.", 1, player.loadCount)
        assertEquals("No se deben realizar ajustes adicionales después de la transmisión. start", 0, player.startCount)
        assertTrue("El controlador debe estar en estado de juego.", controller.state.isPlaying)

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_BUBBLE_TAG).performClick()

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_PLAY_ICON_TAG, useUnmergedTree = true).assertExists()
        assertEquals("Al hacer clic nuevamente, se debe hacer una pausa en lugar de recargar.", 1, player.pauseCount)
        assertEquals("La interacción completa solo se carga una vez", 1, player.loadCount)
        assertFalse("El controlador debe estar en estado de pausa.", controller.state.isPlaying)
    }

    @Test
    fun completionCallbackResetsToIdle() {
        val player = FakeVoiceAudioPlayer()
        val controller = VoicePlaybackController { player }
        render(voiceUri = storedVoice("finish.wav"), durationMs = 7_000L, controller = controller)
        composeTestRule.onNodeWithTag(VOICE_MESSAGE_BUBBLE_TAG).performClick()

        player.finishPlayback()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_PLAY_ICON_TAG, useUnmergedTree = true).assertExists()
        assertEquals("Después de jugar, debería restablecerse al modo inactivo.", null, controller.state.uri)
        assertFalse("No debería reproducirse una vez terminado.", controller.state.isPlaying)
        assertTrue("El jugador debe ser liberado después de jugar.", player.releaseCount >= 1)
    }

    @Test
    fun unresolvableUriShowsExpiredStateWithoutThrowing() {
        val player = FakeVoiceAudioPlayer()
        val controller = VoicePlaybackController { player }
        val missing = "file://" + File(storageDir, "gone.wav").absolutePath
        render(voiceUri = missing, durationMs = 7_000L, controller = controller)

        composeTestRule.onNodeWithTag(VOICE_MESSAGE_EXPIRED_TAG).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.chat_voice_expired)).assertExists()
        composeTestRule.onNodeWithTag(VOICE_MESSAGE_BUBBLE_TAG).assertDoesNotExist()
        // En estado de falla, el reproductor no se construye y no se produce ningún sonido.
        composeTestRule.onNodeWithTag(VOICE_MESSAGE_EXPIRED_TAG).performClick()
        assertEquals("Las voces no válidas no deberían cargar el reproductor.", 0, player.loadCount)
        assertEquals(null, controller.state.uri)
    }

    @Test
    fun playingSecondVoiceStopsFirstOne() {
        val firstPlayer = FakeVoiceAudioPlayer()
        val secondPlayer = FakeVoiceAudioPlayer()
        val players = ArrayDeque(listOf(firstPlayer, secondPlayer))
        val controller = VoicePlaybackController { players.removeFirst() }
        val firstUri = storedVoice("first.wav")
        val secondUri = storedVoice("second.wav")

        composeTestRule.setContent {
            MaterialTheme {
                Column {
                    VoiceMessageBubble(
                        voiceUri = firstUri,
                        durationMs = 7_000L,
                        isFromUser = false,
                        controller = controller,
                        store = store,
                    )
                    VoiceMessageBubble(
                        voiceUri = secondUri,
                        durationMs = 7_000L,
                        isFromUser = true,
                        controller = controller,
                        store = store,
                    )
                }
            }
        }

        composeTestRule.onAllNodesWithTag(VOICE_MESSAGE_BUBBLE_TAG)[0].performClick()
        assertTrue(firstPlayer.playing)

        composeTestRule.onAllNodesWithTag(VOICE_MESSAGE_BUBBLE_TAG)[1].performClick()

        assertFalse("El artículo 1 debería suspenderse", firstPlayer.playing)
        assertTrue("El primer jugador debería ser liberado.", firstPlayer.releaseCount >= 1)
        assertTrue("El artículo 2 debería difundirse ahora", secondPlayer.playing)
        assertEquals("Los derechos de radiodifusión se transferirán al artículo 2.", secondUri, controller.state.uri)
    }
}

/** Reproductor falso: solo graba llamadas y no emite ningún sonido. */
private class FakeVoiceAudioPlayer : VoiceAudioPlayer {
    var loadCount = 0
    var startCount = 0
    var pauseCount = 0
    var releaseCount = 0
    var playing = false
    private var onCompletion: (() -> Unit)? = null

    override fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean {
        loadCount++
        playing = true
        this.onCompletion = onCompletion
        return true
    }

    override fun start() {
        startCount++
        playing = true
    }

    override fun pause() {
        pauseCount++
        playing = false
    }

    override fun positionMs(): Long = 0L

    override fun release() {
        releaseCount++
        playing = false
    }

    fun finishPlayback() {
        playing = false
        onCompletion?.invoke()
    }
}
