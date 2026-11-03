package io.zer0.muse.data.miniphone

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.moment.MiniContextBuilder
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.ui.speech.VoiceMessageArchiver
import io.zer0.muse.ui.speech.TtsSynthResult
import io.zer0.muse.ui.speech.VoiceSynthesizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File

/**
 * Barreras de comportamiento implementadas por pequeños hosts de teléfonos celulares.
 *
 * Generar puerto,TTS Los puertos y los puertos de archivo de voz están inyectados con implementaciones falsas. —— Prueba única sin modelo conectado, no puede permitirse el lujo del motor TTS,
 * Simplemente verifique qué mapeo y recorte ha realizado el host y si el disco se perderá en caso de falla.
 */
class AppMiniPhoneHostTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `observeCharacters Asistentes de mapas para direcciones de asientos en el libro de direcciones`() = runBlocking {
        val repo = mockk<AssistantRepository>(relaxed = true)
        every { repo.observeAll } returns flowOf(
            listOf(
                AssistantEntity(
                    id = "a1",
                    name = "un ling",
                    avatarImageUrl = "/data/avatar/a1.png",
                    personaDefinition = "# gentil ilustrador\nMe gusta dibujar por la noche",
                ),
                AssistantEntity(id = "a2", name = "Sin asistente de avatar"),
            ),
        )

        val characters = host(repo = repo).observeCharacters().first()

        assertEquals("Dos asistentes deben asignarse a dos roles", 2, characters.size)
        assertEquals("a1", characters[0].id)
        assertEquals("un ling", characters[0].name)
        assertEquals("/data/avatar/a1.png", characters[0].avatarUri)
        assertEquals("La primera línea de la definición de personalidad en una frase.", "gentil ilustrador", characters[0].tagline)
        assertEquals("a2", characters[1].id)
        assertNull("Ningún conjunto de avatar debe ser null", characters[1].avatarUri)
        assertEquals("Nadie establece la cadena vacía.", "", characters[1].tagline)
    }

    @Test
    fun `observeProfile Obtenga el apodo, avatar y firma del retrato del usuario.`() = runBlocking {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.userProfileFlow } returns flowOf(
            UserProfile(
                userNickName = " Ziqi ",
                avatarUri = "/data/avatar/me.png",
                bio = "Personas que codifican por la noche",
            ),
        )

        val profile = host(settings = settings).observeProfile().first()

        assertEquals("Ziqi", profile.nickname)
        assertEquals("/data/avatar/me.png", profile.avatarUri)
        assertEquals("Personas que codifican por la noche", profile.signature)
        assertEquals("El fondo de pantalla aún no acepta host", "", profile.wallpaperUri)
        assertEquals("La portada no acepta anfitriones temporalmente", "", profile.coverImageUri)
    }

    @Test
    fun `respond Transmita eventos activos tal como están y elimine el texto limpiamente`() = runBlocking {
        val runner = mockk<MiniPhoneToolTurnRunner>()
        every { runner.reply(any(), any(), any()) } returns flowOf(
            MpReplyEvent.Activity(MpActivityKind.SEARCHING),
            MpReplyEvent.Text("<think>piensa primero</think>\"Acabo de comprobarlo.\""),
        )

        val events = host(runner = runner, contextBuilder = conversationBuilder())
            .respond("a1", listOf(MpChatTurn(MpActorType.USER, "Comprueba el tiempo por mí")))
            .toList()

        assertEquals(
            listOf(MpReplyEvent.Activity(MpActivityKind.SEARCHING), MpReplyEvent.Text("Acabo de comprobarlo.")),
            events,
        )
    }

    @Test
    fun `respond Utilice las indicaciones del sistema y el historial para detallar la entrada del modelo y filtrar las líneas del sistema.`() = runBlocking {
        val runner = mockk<MiniPhoneToolTurnRunner>()
        val captured = slot<List<UIMessage>>()
        every { runner.reply(capture(captured), any(), any()) } returns flowOf(MpReplyEvent.Text("en"))

        val events = host(runner = runner, contextBuilder = conversationBuilder())
            .respond(
                "a1",
                listOf(
                    MpChatTurn(MpActorType.USER, "¿Está ahí?"),
                    MpChatTurn(MpActorType.SYSTEM, "La otra parte no respondió."),
                    MpChatTurn(MpActorType.CHARACTER, "en"),
                    MpChatTurn(MpActorType.USER, "Comprueba el tiempo por mí"),
                ),
            )
            .toList()

        assertEquals(listOf(MpReplyEvent.Text("en")), events)
        val sent = captured.captured
        assertEquals(
            listOf(MessageRole.SYSTEM, MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER),
            sent.map { it.role },
        )
        assertEquals("El aviso del sistema proviene de la cadena de ensamblaje de personalidad.", "eres un ling", sent.first().content)
        assertEquals("La historia sigue como es.", "Comprueba el tiempo por mí", sent.last().content)
    }

    @Test
    fun `La respuesta se envía cuando el modelo no proporciona texto. Failed`() = runBlocking {
        val runner = mockk<MiniPhoneToolTurnRunner>()
        every { runner.reply(any(), any(), any()) } returns flowOf(MpReplyEvent.Text("<think>solo pensando</think>"))

        val events = host(runner = runner, contextBuilder = conversationBuilder())
            .respond("a1", listOf(MpChatTurn(MpActorType.USER, "¿Está ahí?")))
            .toList()

        assertEquals(listOf(MpReplyEvent.Failed), events)
    }

    @Test
    fun `respond Aplicar a la cadena de ensamblaje de personalidad para la convención de marcado de voz.`() = runBlocking {
        val runner = mockk<MiniPhoneToolTurnRunner>()
        every { runner.reply(any(), any(), any()) } returns flowOf(MpReplyEvent.Text("en"))
        val voiceEnabled = slot<Boolean>()
        val builder = mockk<MiniContextBuilder>(relaxed = true)
        coEvery {
            builder.buildMessages(assistant = any(), userContent = any(), sessionId = any(), capture(voiceEnabled))
        } answers {
            listOf(
                UIMessage(role = MessageRole.SYSTEM, content = "eres un ling"),
                UIMessage(role = MessageRole.USER, content = secondArg()),
            )
        }

        host(runner = runner, contextBuilder = builder)
            .respond("a1", listOf(MpChatTurn(MpActorType.USER, "¿Está ahí?")))
            .toList()

        // Las etiquetas de voz sólo tienen significado en la ruta del mensaje privado:El anfitrión debe tomar la iniciativa.,De lo contrario, el modelo nunca generará el token.
        assertTrue("Las respuestas a mensajes privados deben activar la convención de marcas de voz.", voiceEnabled.captured)
    }

    @Test
    fun `composeMoment Sigue el tono de Moments y no inyectes textos de comentarios dinámicos.`() = runBlocking {
        val generator = mockk<MiniPhoneTextGenerator>(relaxed = true)
        val captured = slot<String>()
        coEvery { generator.generate(any(), capture(captured), any()) } returns "<think>x</think>El viento era muy fuerte hoy y la ropa tendida a secar se fue volando."

        val body = host(generator = generator).composeMoment("a1", "el clima de hoy")

        assertEquals("El viento era muy fuerte hoy y la ropa tendida a secar se fue volando.", body)
        assertTrue("Los materiales deben indicar que están publicados en Momentos.: ${captured.captured}", captured.captured.contains("Momentos"))
        assertTrue("Se deben traer materiales hint: ${captured.captured}", captured.captured.contains("el clima de hoy"))
    }

    @Test
    fun `Los materiales en Moments y mensajes privados suprimen la marca del sistema`() = runBlocking {
        val captured = slot<String>()
        val generator = mockk<MiniPhoneTextGenerator>(relaxed = true)
        coEvery { generator.generate(any(), capture(captured), any()) } returns "Texto"
        val host = host(generator = generator)

        host.composeMoment("a1", null)
        assertBannedMarks(captured.captured)
        assertTrue("Al realizar actualizaciones, indique que las publicó usted mismo.: ${captured.captured}", captured.captured.contains("No responder a nadie"))
        assertTrue("Requisitos dinámicos 30-80 palabras", captured.captured.contains("30-80"))

        host.composeProactiveMessage("a1", null)
        assertBannedMarks(captured.captured)
        assertTrue("Al enviar un mensaje privado, indique que está hablando usted mismo.: ${captured.captured}", captured.captured.contains("Lo que dijiste por iniciativa propia."))
        assertTrue("Sólo una o dos frases en un mensaje privado.", captured.captured.contains("una o dos frases"))
    }

    @Test
    fun `El rol maxTokens no pondrá una oración en cientos token`() = runBlocking {
        val captured = mutableListOf<Int>()
        val generator = mockk<MiniPhoneTextGenerator>(relaxed = true)
        coEvery { generator.generate(any(), any(), capture(captured)) } returns "Texto"
        val repo = mockk<AssistantRepository>(relaxed = true)
        every { repo.observeAll } returns flowOf(emptyList())
        coEvery { repo.getById("a1") } returns AssistantEntity(id = "a1", name = "un ling", maxTokens = 4_000)
        coEvery { repo.getById("a2") } returns AssistantEntity(id = "a2", name = "Ayuan", maxTokens = 40)
        val host = host(repo = repo, generator = generator)

        host.composeMoment("a1", null)
        host.composeMoment("a2", null)

        assertEquals(listOf(MOMENT_TOKEN_CAP, 40), captured)
    }

    @Test
    fun `cleanOutput Quita los corchetes de humor al final de la línea.`() {
        assertEquals("El viento era muy fuerte hoy y la ropa tendida a secar se fue volando.", composer().cleanOutput("El viento era muy fuerte hoy y la ropa tendida a secar se fue volando.\n(estado de ánimo: tranquilo)"))
        assertEquals("Hace mucho viento hoy.", composer().cleanOutput("Hace mucho viento hoy.(estado de ánimo: un poco bajo)"))
    }

    @Test
    fun `cleanOutput Elimina toda la línea comenzando con MOOD`() {
        assertEquals("Hace mucho viento hoy.", composer().cleanOutput("MOOD: happy\nHace mucho viento hoy."))
        assertEquals("Hace mucho viento hoy.", composer().cleanOutput("Hace mucho viento hoy.\nMOOD:calma"))
    }

    @Test
    fun `cleanOutput Elimina el segmento de etiquetas de estado de ánimo`() {
        assertEquals("Hace mucho viento hoy.", composer().cleanOutput("Hace mucho viento hoy.[mood]calma[/mood]"))
        assertEquals("Hace mucho viento hoy.", composer().cleanOutput("<mood>emo=0.3</mood>Hace mucho viento hoy."))
    }

    @Test
    fun `cleanOutput Carácter por carácter sin cambios para texto sin formato`() {
        val raw = "El viento era muy fuerte hoy y la ropa tendida a secar se fue volando.\nPor cierto, limpié el alféizar de la ventana.\n\nTambién tomó una foto."

        assertEquals(raw, composer().cleanOutput(raw))
    }

    @Test
    fun `Cuando el rol no existe, responder envía Error y composeMoment devuelve null`() = runBlocking {
        val repo = mockk<AssistantRepository>(relaxed = true)
        coEvery { repo.getById(any()) } returns null
        val generator = mockk<MiniPhoneTextGenerator>(relaxed = true)
        val runner = mockk<MiniPhoneToolTurnRunner>()
        val host = host(repo = repo, generator = generator, runner = runner)

        val events = host.respond("missing", listOf(MpChatTurn(MpActorType.USER, "¿Qué estás haciendo?"))).toList()

        assertEquals(listOf(MpReplyEvent.Failed), events)
        assertNull(host.composeMoment("missing", null))
        coVerify(exactly = 0) { generator.generate(any(), any(), any()) }
        verify(exactly = 0) { runner.reply(any(), any(), any()) }
    }

    @Test
    fun `synthesizeVoice No realizar el pedido y devolverlo cuando TTS devuelva falso null`() = runBlocking {
        val synthesizer = mockk<VoiceSynthesizer>()
        val store = mockk<VoiceMessageArchiver>(relaxed = true)
        coEvery { synthesizer.synthesizeTo(any(), any(), any()) } answers {
            secondArg<File>().writeBytes(ByteArray(TEMP_BYTES))
            TtsSynthResult.Failure("La síntesis falló")
        }
        val cacheDir = tempDir.newFolder("cache")

        val result = voiceHost(synthesizer = synthesizer, store = store, cacheDir = cacheDir)
            .synthesizeVoice("buenas noches", "soft")

        assertNull(result)
        coVerify(exactly = 0) { store.persist(any(), any()) }
        assertTrue("Los archivos temporales deben eliminarse", cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `synthesizeVoice Cuando tiene éxito, se devuelve la referencia del archivo y la duración y la emoción se transmite de forma transparente.`() = runBlocking {
        val synthesizer = mockk<VoiceSynthesizer>()
        val store = mockk<VoiceMessageArchiver>(relaxed = true)
        coEvery { synthesizer.synthesizeTo(any(), any(), "soft") } answers {
            secondArg<File>().writeBytes(ByteArray(TEMP_BYTES))
            TtsSynthResult.Success
        }
        coEvery { store.persist(any(), any()) } returns ARCHIVED_URI
        every { store.probeDurationMs(any()) } returns DURATION_MS
        val cacheDir = tempDir.newFolder("cache")

        val result = voiceHost(synthesizer = synthesizer, store = store, cacheDir = cacheDir)
            .synthesizeVoice("buenas noches", "soft")

        assertEquals(ARCHIVED_URI, result?.first)
        assertEquals(DURATION_MS, result?.second)
        coVerify(exactly = 1) { store.persist(any(), "buenas noches" + KEY_SEPARATOR + "soft") }
        assertTrue("Los archivos temporales deben eliminarse", cacheDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `MomentChainTextGenerator Dale el material al enlace Persona y luego ajústalo. completeText`() = runBlocking {
        val chatService = mockk<ChatService>()
        val contextBuilder = mockk<MiniContextBuilder>(relaxed = true)
        val assistant = AssistantEntity(id = "a1", name = "un ling")
        val builtMessages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "eres un ling"),
            UIMessage(role = MessageRole.USER, content = "Materiales"),
        )
        coEvery { contextBuilder.buildMessages(assistant = assistant, userContent = "Materiales") } returns builtMessages
        val captured = slot<List<UIMessage>>()
        coEvery {
            chatService.completeText(messages = capture(captured), temperature = 0.8f, maxTokens = 160)
        } returns ChatCompletion(text = "OK")

        val text = MomentChainTextGenerator(chatService, contextBuilder, mockk(relaxed = true))
            .generate(assistant, "Materiales", 160)

        assertEquals("OK", text)
        assertSame("Una lista de mensajes que deben entregarse al creador de contexto.", builtMessages, captured.captured)
    }

    @Test
    fun `MomentChainTextGenerator Parámetros de modelo y muestreo manipulados con roles.`() = runBlocking {
        val chatService = mockk<ChatService>()
        val contextBuilder = mockk<MiniContextBuilder>(relaxed = true)
        coEvery { contextBuilder.buildMessages(assistant = any(), userContent = any()) } returns
            listOf(UIMessage(role = MessageRole.USER, content = "Materiales"))
        val boundModel = Model(id = "m-bound", providerId = "p1")
        val boundProvider = ProviderConfig(id = "p1", displayName = "Proveedor uno", models = listOf(boundModel))
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.providersFlow } returns flowOf(listOf(boundProvider))
        every { settings.activeProviderIdFlow } returns flowOf("p1")
        val assistant = AssistantEntity(
            id = "a1",
            name = "un ling",
            modelId = "m-bound",
            providerId = "p1",
            temperature = 0.35f,
            topP = 0.9f,
            maxTokens = 400,
        )
        val capturedModel = slot<Model>()
        val capturedProvider = slot<ProviderConfig>()
        coEvery {
            chatService.completeText(
                messages = any(),
                model = capture(capturedModel),
                providerConfig = capture(capturedProvider),
                temperature = 0.35f,
                topP = 0.9f,
                maxTokens = 160,
            )
        } returns ChatCompletion(text = "OK")

        val text = MomentChainTextGenerator(chatService, contextBuilder, settings)
            .generate(assistant, "Materiales", 160)

        assertEquals("OK", text)
        assertEquals("Modelo de plataforma de personajes", "m-bound", capturedModel.captured.id)
        assertEquals("El proveedor proviene de la vinculación de roles", "p1", capturedProvider.captured.id)
    }

    @Test
    fun `MomentChainTextGenerator Recurrir al modelo predeterminado cuando el modelo vinculado no existe`() = runBlocking {
        val chatService = mockk<ChatService>()
        val contextBuilder = mockk<MiniContextBuilder>(relaxed = true)
        coEvery { contextBuilder.buildMessages(assistant = any(), userContent = any()) } returns
            listOf(UIMessage(role = MessageRole.USER, content = "Materiales"))
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.providersFlow } returns flowOf(emptyList())
        every { settings.activeProviderIdFlow } returns flowOf(null)
        val assistant = AssistantEntity(id = "a1", name = "un ling", modelId = "deleted-model", providerId = "gone")
        coEvery {
            chatService.completeText(
                messages = any(),
                model = null,
                providerConfig = null,
                temperature = 0.8f,
                topP = null,
                maxTokens = 160,
            )
        } returns ChatCompletion(text = "OK")

        val text = MomentChainTextGenerator(chatService, contextBuilder, settings)
            .generate(assistant, "Materiales", 160)

        assertEquals("OK", text)
        // Si el enlace no se puede resolver, no pase model/providerConfig: devuélvalo al modelo predeterminado.
        coVerify(exactly = 1) {
            chatService.completeText(
                messages = any(),
                model = null,
                providerConfig = null,
                temperature = 0.8f,
                topP = null,
                maxTokens = 160,
            )
        }
    }

    @Test
    fun `MomentChainTextGenerator Devuelto cuando el modelo no está configurado null`() = runBlocking {
        val contextBuilder = mockk<MiniContextBuilder>(relaxed = true)

        assertNull(MomentChainTextGenerator(null, contextBuilder, mockk(relaxed = true)).generate(null, "Materiales", 160))
    }

    @Test
    fun `El módulo de host está clasificado después del módulo de miniteléfono y cubre la implementación del marcador de posición.`() {
        val host = host()
        val koinApp = koinApplication {
            modules(
                io.zer0.muse.miniphone.di.miniPhoneModule,
                module { single<MiniPhoneHost> { host } },
            )
        }

        try {
            assertSame("La definición de host cargada más tarde debe ganar", host, koinApp.koin.get<MiniPhoneHost>())
        } finally {
            koinApp.close()
        }
    }

    // ── accesorio ────────────────────────────────────────────────────────────

    /** Pasos unitarios para generar enlaces:Tanto los almacenes como los puertos utilizan sustitutos.,Pruebe únicamente el material combinado y obtenga un resultado claro. */
    private fun composer(): MiniPhoneTextComposer = MiniPhoneTextComposer(
        assistantRepository = mockk(relaxed = true),
        textGenerator = mockk(relaxed = true),
        contextBuilder = mockk(relaxed = true),
    )

    /** Artículos prohibidos que deben aparecer en el material.:Cuando el modelo es desobediente, también es reprimido con palabras de estímulo. */
    private fun assertBannedMarks(material: String) {
        BANNED_MARKS.forEach { mark ->
            assertTrue("Al material le faltan elementos prohibidos \"$mark\": $material", material.contains(mark))
        }
        assertTrue("El material requiere que solo se imprima el texto principal.: $material", material.contains("Sólo texto de salida"))
    }

    /** El ensamblaje de personalidad resulta cuando solo hay un mensaje de usuario:Un mensaje del sistema + este mensaje. */
    private suspend fun conversationBuilder(): MiniContextBuilder {
        val builder = mockk<MiniContextBuilder>(relaxed = true)
        coEvery {
            builder.buildMessages(assistant = any(), userContent = any(), sessionId = any(), voiceMessageEnabled = any())
        } answers {
            listOf(
                UIMessage(role = MessageRole.SYSTEM, content = "eres un ling"),
                UIMessage(role = MessageRole.USER, content = secondArg()),
            )
        }
        return builder
    }

    private fun host(
        repo: AssistantRepository = defaultRepo(),
        settings: SettingsRepository = mockk(relaxed = true),
        generator: MiniPhoneTextGenerator = mockk(relaxed = true),
        contextBuilder: MiniContextBuilder = mockk(relaxed = true),
        runner: MiniPhoneToolTurnRunner = mockk(relaxed = true),
        cacheDir: File = tempDir.root,
    ): AppMiniPhoneHost = AppMiniPhoneHost(
        assistantRepository = repo,
        settingsRepository = settings,
        textGenerator = generator,
        contextBuilder = contextBuilder,
        toolTurnRunner = runner,
        voiceSynthesizer = mockk(relaxed = true),
        voiceStore = mockk(relaxed = true),
        cacheDir = cacheDir,
    )

    /** Anfitrión dedicado a casos de uso de voz:Sólo nos preocupamos por la cooperación entre el puerto TTS y el puerto de archivo. */
    private fun voiceHost(
        synthesizer: VoiceSynthesizer,
        store: VoiceMessageArchiver,
        cacheDir: File,
    ): AppMiniPhoneHost = AppMiniPhoneHost(
        assistantRepository = defaultRepo(),
        settingsRepository = mockk(relaxed = true),
        textGenerator = mockk(relaxed = true),
        contextBuilder = mockk(relaxed = true),
        toolTurnRunner = mockk(relaxed = true),
        voiceSynthesizer = synthesizer,
        voiceStore = store,
        cacheDir = cacheDir,
    )

    /** Almacén de funciones predeterminado: solo hay un asistente, que se puede obtener mediante cualquier ID. */
    private fun defaultRepo(): AssistantRepository {
        val repo = mockk<AssistantRepository>(relaxed = true)
        val assistant = AssistantEntity(id = "a1", name = "un ling")
        every { repo.observeAll } returns flowOf(listOf(assistant))
        coEvery { repo.getById(any()) } returns null
        coEvery { repo.getById("a1") } returns assistant
        return repo
    }

    private companion object {
        const val TEMP_BYTES = 64
        const val ARCHIVED_URI = "file:///data/voice_messages/abc.wav"
        const val DURATION_MS = 1_500L
        const val KEY_SEPARATOR = "\u0000"

        /** Consistente con el límite superior de la escena del círculo de amigos dentro del anfitrión.:30-80 Las palabras no requieren más cuota. */
        const val MOMENT_TOKEN_CAP = 200

        val BANNED_MARKS = listOf(
            "MOOD",
            "valor emocional",
            "Favorabilidad",
            "barra de estado",
            "etiqueta del sistema",
            "acción de soporte",
            "narración",
            "Marca de tiempo",
            "comillas",
            "#tema",
        )
    }
}
