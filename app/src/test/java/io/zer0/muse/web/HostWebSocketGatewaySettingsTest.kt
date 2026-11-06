package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.ChatViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * settings.update Estrategia de enfoque: entornos globales**No**Sujeto al enfoque de la sesión.
 *
 * Antecedentes: escritura conversacional (chat.send / session.select / …) debe serializarse para evitar que dos clientes entren en la misma sesión al mismo tiempo.
 * Mensajes de relleno; la configuración global no tiene este problema——Al chatear por teléfono, no se debe rechazar el cambio de un interruptor en la página web.
 * Si hay un conflicto, simplemente presione y escriba para sobrescribirlo. Esta prueba es una versión ejecutable de esa política:
 * Otro enlace mantiene el foco, este enlace aún se puede cambiar y los cambios en realidad se escriben en el repositorio de configuración.
 */
class HostWebSocketGatewaySettingsTest {

    /** Se abre una conexión falsa a la puerta de enlace: comando de [incoming] enviado, evento de [events] leer. */
    private class FakeConnection {
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        val events = mutableListOf<HostEvent>()
        private val mock = mockk<DefaultWebSocketServerSession>(relaxed = true)

        val session: DefaultWebSocketServerSession = mock

        init {
            every { mock.incoming } returns incoming
            coEvery { mock.send(any<Frame>()) } coAnswers {
                val frame = firstArg<Frame>()
                if (frame is Frame.Text) {
                    events += AppJson.decodeFromString<HostEvent>(frame.data.decodeToString())
                }
            }
        }
    }

    private class Harness(val gateway: HostWebSocketGateway, val settingsRepo: SettingsRepository)

    private val tempDir = Files.createTempDirectory("host-gateway-settings").toFile()

    private fun harness(): Harness {
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.state } returns MutableStateFlow(ChatUiState())
        every { chat.messages } returns MutableStateFlow(emptyList())
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns
            MutableStateFlow<Map<String, ChatGenerationManager.ActiveGeneration>>(emptyMap())
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        return Harness(
            gateway = HostWebSocketGateway(
                chatViewModel = chat,
                generationManager = generations,
                sessionRepo = mockk<SessionRepository>(relaxed = true),
                assistantRepo = mockk<AssistantRepository>(relaxed = true),
                settingsRepo = settingsRepo,
                settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
                mediaLinks = HostMediaLinks(
                    HostMediaStore(
                        imageDir = File(tempDir, "muse_images").apply { mkdirs() },
                        voiceDir = File(tempDir, "voice_messages").apply { mkdirs() },
                    ),
                ),
            ),
            settingsRepo = settingsRepo,
        )
    }

    @Test
    fun `settings update applies for a connection that does not hold the focus`() = runTest {
        val harness = harness()
        val phone = FakeConnection()
        val browser = FakeConnection()

        // La primera conexión (teléfono celular) está en serve() El enlace capta la atención; Por lo tanto, el segundo enlace (página web) no puede captar la atención.
        val phoneJob = launch { harness.gateway.serve(phone.session) }
        advanceUntilIdle()
        val browserJob = launch { harness.gateway.serve(browser.session) }
        advanceUntilIdle()

        browser.incoming.send(
            Frame.Text(
                """{"protocolVersion":1,"type":"settings.update","requestId":"req-settings","key":"piiGuardEnabled","value":"true"}""",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "Se deben aceptar escrituras establecidas para eventos reales.: ${browser.events}",
            browser.events.any { it.type == "command.accepted" && it.requestId == "req-settings" },
        )
        assertTrue(
            "La escritura de configuraciones no debe ser bloqueada por el foco: ${browser.events}",
            browser.events.none { it.error == "focus_not_held" },
        )
        coVerify(exactly = 1) { harness.settingsRepo.savePiiGuardEnabled(true) }

        // Verificación de requisitos previos: la escritura de sesión en la misma conexión que no mantiene el foco aún se rechaza. —— foco par aislado session.* Sigue siendo válido.
        browser.incoming.send(Frame.Text("""{"protocolVersion":1,"type":"session.new","requestId":"req-new"}"""))
        advanceUntilIdle()

        assertTrue(
            "La escritura conversacional debe seguir manteniendo el foco: ${browser.events}",
            browser.events.any { it.type == "error" && it.requestId == "req-new" && it.error == "focus_not_held" },
        )

        browser.incoming.close()
        phone.incoming.close()
        advanceUntilIdle()
        browserJob.join()
        phoneJob.join()
    }
}
