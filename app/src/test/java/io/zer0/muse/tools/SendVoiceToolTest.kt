package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.zer0.muse.ui.speech.TtsSynthResult
import io.zer0.muse.ui.speech.VoiceDurationProbe
import io.zer0.muse.ui.speech.VoiceMessageArchiver
import io.zer0.muse.ui.speech.VoiceMessageStore
import io.zer0.muse.ui.speech.VoiceSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v2.x: El asistente envía un mensaje de voz (`send_voice`) barreras de comportamiento de la capa de herramientas.
 *
 * Tanto el puerto sintético como el puerto de archivo están inyectados con implementaciones falsas. —— El motor TTS nunca se iniciará en una sola prueba (iniciará los servicios del sistema,
 * Aún debe esperar la devolución de llamada asincrónica), simplemente verifique la propia orquestación de la herramienta: qué resultados se entregan, si los archivos temporales se eliminan,
 * ¿Persistirá persistir en vano cuando falle?
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SendVoiceToolTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun tool(synth: VoiceSynthesizer, archive: VoiceMessageArchiver): MediaGenToolsImpl = MediaGenToolsImpl(
        context = context,
        settings = mockk(relaxed = true),
        imageService = mockk(relaxed = true),
        videoGenerationService = mockk(relaxed = true),
        voiceSynthesizer = synth,
        voiceMessageStore = archive,
    )

    @Test
    fun `Cuando la síntesis sea exitosa, entregue Success con uri y duración y elimine el archivo temporal`() = runBlocking {
        val synth = FakeSynthesizer()
        val archive = FakeArchive()
        val host = RecordingHost()
        val impl = tool(synth, archive).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "Buenas noches, acuéstate temprano.", "emotion" to "soft"))

        val success = host.results.single() as MediaGenResult.Success
        assertEquals(ARCHIVED_URI, success.voiceUri)
        assertEquals(ARCHIVE_DURATION_MS, success.voiceDurationMs)
        assertEquals("emotion Debe pasarse de forma transparente al puerto de composición.", "soft", synth.lastEmotion)
        assertEquals("El repartidor debe abrir rondas según el tipo de voz.", listOf(MediaGenKind.VOICE), host.kinds)
        assertFalse("Los archivos temporales no fueron eliminados", tempFileOf(synth).exists())
        assertFalse("La copia devuelta al modelo no debe contener uri: $reply", reply.contains("file://"))
        assertTrue("La copia de devolución debe indicar que la voz ha sido enviada.: $reply", reply.contains("Voz"))
    }

    @Test
    fun `El fallo se produce cuando la síntesis falla y no se ajusta persist`() = runBlocking {
        val synth = FakeSynthesizer(TtsSynthResult.Failure("Tanto la nube como el sistema fallaron"))
        val archive = FakeArchive()
        val host = RecordingHost()
        val impl = tool(synth, archive).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "Hola"))

        val failure = host.results.single() as MediaGenResult.Failure
        assertEquals("La síntesis de voz falló", failure.message)
        assertEquals("Si la síntesis falla no se debe colocar.", 0, archive.persistCalls)
        assertFalse("Los archivos temporales también deben eliminarse en las rutas fallidas.", tempFileOf(synth).exists())
        assertTrue("Los resultados de la herramienta deben indicar la causa de la falla.: $reply", reply.contains("La síntesis de voz falló"))
    }

    @Test
    fun `Al volver al sonido del sistema, los resultados de la herramienta indican que la nube no ha surtido efecto y por qué`() = runBlocking {
        val synth = FakeSynthesizer(TtsSynthResult.SystemFallback("API Key esta vacio"))
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive()).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "Buenas noches, acuéstate temprano."))

        assertTrue("Incluso después de volver al tono del sistema, la voz siguió saliendo.: $reply", reply.contains("El teléfono celular viene con sonidos."))
        assertTrue("Hay que explicar claramente el motivo por el que la nube no es eficaz.: $reply", reply.contains("API Key esta vacio"))
        assertTrue("Retroceder se considera una entrega exitosa", host.results.single() is MediaGenResult.Success)
    }

    @Test
    fun `Cuando la síntesis en la nube es exitosa, el resultado de la herramienta no retrocede`() = runBlocking {
        val impl = tool(FakeSynthesizer(TtsSynthResult.Success), FakeArchive())
            .also { it.installHost(RecordingHost()) }

        val reply = impl.execSendVoice(mapOf("text" to "Buenas noches, acuéstate temprano."))

        assertEquals("Mensaje de voz enviado", reply)
        assertFalse("El tono del sistema no se puede mejorar cuando la nube es normal.: $reply", reply.contains("El teléfono celular viene con sonidos."))
    }

    @Test
    fun `persist Entregado cuando se devuelve nulo Failure`() = runBlocking {
        val synth = FakeSynthesizer()
        val archive = FakeArchive(uri = null)
        val host = RecordingHost()
        val impl = tool(synth, archive).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "Unas palabras sobre el fallo del archivo"))

        assertEquals("No se pudo guardar el archivo de voz", (host.results.single() as MediaGenResult.Failure).message)
        assertFalse("Los archivos temporales también deben eliminarse en las rutas fallidas.", tempFileOf(synth).exists())
        assertTrue("Los resultados de la herramienta deben indicar la causa de la falla.: $reply", reply.contains("No se pudo guardar el archivo de voz"))
    }

    @Test
    fun `La entrega sigue siendo exitosa cuando la detección de duración devuelve 0`() = runBlocking {
        val host = RecordingHost()
        val impl = tool(FakeSynthesizer(), FakeArchive(durationMs = 0L)).also { it.installHost(host) }

        impl.execSendVoice(mapOf("text" to "No se puede detectar la duración"))

        val success = host.results.single() as MediaGenResult.Success
        assertEquals("Incluso si se desconoce la duración, se enviará como de costumbre.", ARCHIVED_URI, success.voiceUri)
        assertEquals(0L, success.voiceDurationMs)
    }

    @Test
    fun `Entregar cancelado cuando se cancela la rutina y eliminar los archivos temporales`() = runBlocking {
        val synth = FakeSynthesizer(error = CancellationException("El usuario deja de generar"))
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive()).also { it.installHost(host) }

        val thrown = runCatching { impl.execSendVoice(mapOf("text" to "Cancelado a mitad de frase")) }.exceptionOrNull()

        assertTrue("Cancelar se lanza tal cual: $thrown", thrown is CancellationException)
        assertTrue("Cancelar entrega Cancelled", host.results.single() is MediaGenResult.Cancelled)
        assertFalse("Cancelar la ruta y eliminar los archivos temporales.", tempFileOf(synth).exists())
    }

    @Test
    fun `Se envía repetidamente el mismo texto y emoción y solo se envía una copia del audio`() = runBlocking {
        val synth = FakeSynthesizer()
        val store = realStore()
        val host = RecordingHost()
        val impl = tool(synth, store).also { it.installHost(host) }

        impl.execSendVoice(mapOf("text" to "misma frase", "emotion" to "calm"))
        impl.execSendVoice(mapOf("text" to "misma frase", "emotion" to "calm"))

        assertEquals("El mismo texto y el mismo sentimiento deberían llegar al mismo archivo comprimido", 1, storedFiles(store).size)
        val uris = host.results.filterIsInstance<MediaGenResult.Success>().map { it.voiceUri }.distinct()
        assertEquals("Ambas presentaciones deben citar el mismo audio.", 1, uris.size)
    }

    @Test
    fun `Diferentes emociones se completan en diferentes archivos`() = runBlocking {
        val store = realStore()
        val impl = tool(FakeSynthesizer(), store).also { it.installHost(RecordingHost()) }

        impl.execSendVoice(mapOf("text" to "misma frase", "emotion" to "calm"))
        impl.execSendVoice(mapOf("text" to "misma frase", "emotion" to "sad"))

        assertEquals("Clave de la emoción: diferentes emociones no pueden cubrirse entre sí", 2, storedFiles(store).size)
    }

    @Test
    fun `Cuando falla la generación, no se escribirá ninguna voz en ningún mensaje y se eliminará el archivo`() = runBlocking {
        val archive = FakeArchive()
        val host = RecordingHost(active = false)
        val impl = tool(FakeSynthesizer(), archive).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "La última ronda ha sido cancelada."))

        assertTrue("Los resultados no se entregarán en rondas no válidas.", host.results.isEmpty())
        assertEquals("Los archivos no entregados no deben conservarse", listOf(ARCHIVED_URI), archive.deleted)
        assertTrue(reply, reply.contains("No emitido"))
    }

    @Test
    fun `Si falta texto, se informará directamente un error de parámetro`() = runBlocking {
        val synth = FakeSynthesizer()
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive()).also { it.installHost(host) }

        val reply = impl.execSendVoice(emptyMap())

        assertTrue(reply, reply.contains("text"))
        assertEquals("Los parámetros faltantes no deberían activar la síntesis", 0, synth.calls)
        assertTrue(host.results.isEmpty())
    }

    // ── Lectura en voz alta párrafo por párrafo: puerta china / ranura independiente / número máximo de párrafos por ronda ─────────────────

    @Test
    fun `Si no hay caracteres chinos en el texto, no lo sintetice y explique el motivo`() = runBlocking {
        val synth = FakeSynthesizer()
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive()).also { it.installHost(host) }

        val reply = impl.execSendVoice(mapOf("text" to "Good night, sleep well. See you tomorrow."), TURN_KEY)

        assertEquals("Si no hablas chino, no deberías gastar el crédito TTS", 0, synth.calls)
        assertTrue("Tampoco debería haber espacios para la entrega.", host.kinds.isEmpty())
        assertTrue("Los resultados de la herramienta deben aclararse y no generarse.: $reply", reply.contains("nada de chino"))
    }

    @Test
    fun `Disposición mixta de chino e inglés, incluido el chino`() = runBlocking {
        val synth = FakeSynthesizer()
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive()).also { it.installHost(host) }

        impl.execSendVoice(mapOf("text" to "Buenas noches,sleep well."), TURN_KEY)

        assertEquals("Siempre que mezcles un carácter chino, se sintetizará como de costumbre.", 1, synth.calls)
        assertTrue("El texto mixto debe entregarse correctamente", host.results.single() is MediaGenResult.Success)
    }

    @Test
    fun `Se envían tres mensajes de voz en una misma ronda y cada entrega no se cubre entre sí`() = runBlocking {
        val synth = FakeSynthesizer()
        val host = RecordingHost()
        // El puerto de archivo recibe una referencia diferente cada vez: si varias voces comparten la ranura/Con el mismo resultado portador, tres resultados serán uno mismo.
        val archive = FakeArchive(uriFactory = { n -> "file:///data/voice_messages/v$n.wav" })
        val impl = tool(synth, archive).also { it.installHost(host) }

        repeat(VOICE_SEGMENTS) { index ->
            impl.execSendVoice(mapOf("text" to "No.${index + 1}lineas"), TURN_KEY)
        }

        assertEquals("Las tres secciones deben combinarse.", VOICE_SEGMENTS, synth.calls)
        assertEquals("Cada voz abre su propio espacio", List(VOICE_SEGMENTS) { MediaGenKind.VOICE }, host.kinds)
        val uris = host.results.filterIsInstance<MediaGenResult.Success>().map { it.voiceUri }
        assertEquals("Se entrega un mensaje de voz a la vez, nada menos", VOICE_SEGMENTS, uris.size)
        assertEquals("Cada audio referenciado debe ser diferente y no pueden cubrirse entre sí.", VOICE_SEGMENTS, uris.distinct().size)
    }

    @Test
    fun `La sexta voz en una sola ronda está bloqueada por el límite superior y no se sintetiza afinada`() = runBlocking {
        val synth = FakeSynthesizer()
        val host = RecordingHost()
        val impl = tool(synth, FakeArchive(uriFactory = { n -> "file:///data/voice_messages/v$n.wav" }))
            .also { it.installHost(host) }

        val replies = (1..TURN_LIMIT).map { index ->
            impl.execSendVoice(mapOf("text" to "No.${index}lineas"), TURN_KEY)
        }
        val over = impl.execSendVoice(mapOf("text" to "No.${TURN_LIMIT + 1}lineas"), TURN_KEY)

        assertTrue("antes $TURN_LIMIT Todas las tiras serán enviadas.: $replies", replies.all { it.contains("Se ha dado voz") })
        assertTrue("Se debe explicar el motivo de la superación del límite.: $over", over.contains("límite superior"))
        assertEquals("La llamada de síntesis se detiene en $TURN_LIMIT veces", TURN_LIMIT, synth.calls)
        assertEquals("El artículo que supere el límite no será entregado.", TURN_LIMIT, host.results.size)

        // La cuota está vinculada a la ronda: si se cambia el identificador de la ronda, se reiniciará el conteo.
        impl.execSendVoice(mapOf("text" to "La primera línea de la siguiente ronda."), "turn-2")
        assertEquals("El límite superior es sólo para la ronda actual.", TURN_LIMIT + 1, synth.calls)
    }

    @Test
    fun `find_tools Esta herramienta se puede encontrar utilizando palabras clave como "discurso"`() {
        // Descubribilidad: una vez enviada la herramienta, el modelo debe encontrarla. Utilice la cuenta registrada real para ejecutar la búsqueda,
        // No hecho a mano ToolDef —— De lo contrario, no se informará ningún error incluso si se cambia la descripción.
        val registry = ToolRegistry(context)
        MediaGenToolsRegistrar(registry, tool(FakeSynthesizer(), FakeArchive()))
        val defs = registry.listTools()

        listOf("hacer una voz", "Voz", "voice", "leer en voz alta").forEach { query ->
            val names = FindToolsTool.search(query, defs).map { it.name }
            assertTrue("Consulta \"$query\"Deberías poder buscar send_voice, pero en realidad: $names", "send_voice" in names)
        }
    }

    // ── accesorio ────────────────────────────────────────────────────────────

    private fun tempFileOf(synth: FakeSynthesizer): File =
        requireNotNull(synth.lastFile) { "No se llamó al puerto de síntesis y no se pudo obtener la ruta del archivo temporal." }

    private fun realStore(): VoiceMessageStore =
        VoiceMessageStore(File(context.cacheDir, "voice-store-${System.nanoTime()}"), VoiceDurationProbe { 800L })

    private fun storedFiles(store: VoiceMessageStore): List<File> =
        store.storageDir.listFiles()?.filter { it.isFile }.orEmpty()

    /** Implementación falsa de puerto sintético: resultado opcional/Lanza una excepción y escribe algunos bytes cuando sea exitoso para que persista tenga algo que mover. */
    private class FakeSynthesizer(
        private val outcome: TtsSynthResult = TtsSynthResult.Success,
        private val error: Throwable? = null,
    ) : VoiceSynthesizer {
        var lastFile: File? = null
        var lastEmotion: String? = null
        var calls = 0

        override suspend fun synthesizeTo(text: String, outputFile: File, emotionOverride: String?): TtsSynthResult {
            calls++
            lastFile = outputFile
            lastEmotion = emotionOverride
            error?.let { throw it }
            // No habrá audio sólo si hay un fallo total; Habrá sonido cuando el sistema retroceda.
            if (outcome !is TtsSynthResult.Failure) outputFile.writeBytes(ByteArray(BYTES))
            return outcome
        }
    }

    /** Implementación falsa del puerto de archivo: registra el número de llamadas persistentes y referencias eliminadas. */
    private class FakeArchive(
        private val uri: String? = ARCHIVED_URI,
        private val durationMs: Long = ARCHIVE_DURATION_MS,
        /** Cada llamada se entrega a un generador con una referencia diferente (para verificar que varias voces no se cubran entre sí). */
        private val uriFactory: ((Int) -> String?)? = null,
    ) : VoiceMessageArchiver {
        var persistCalls = 0
        val deleted = mutableListOf<String>()

        override suspend fun persist(tempFile: File, key: String): String? {
            persistCalls++
            return uriFactory?.invoke(persistCalls) ?: uri
        }

        override fun probeDurationMs(file: File): Long = durationMs

        override suspend fun delete(uri: String) {
            deleted += uri
        }
    }

    /** Implementación falsa del servidor de entrega: solo recibir resultados y no hacer UI. */
    private class RecordingHost(private val active: Boolean = true) : MediaGenHost {
        val results = mutableListOf<MediaGenResult>()
        val kinds = mutableListOf<MediaGenKind>()

        override suspend fun begin(kind: MediaGenKind): MediaGenSlot {
            kinds += kind
            return object : MediaGenSlot {
                override fun isActive(): Boolean = active

                override suspend fun onProgress(elapsedSeconds: Int) = Unit

                override suspend fun report(result: MediaGenResult) {
                    results += result
                }
            }
        }

        override suspend fun finish(kind: MediaGenKind) = Unit
    }

    private companion object {
        const val ARCHIVED_URI = "file:///data/voice_messages/abc.wav"
        const val ARCHIVE_DURATION_MS = 1_500L
        const val BYTES = 128

        /** La ID redonda inyectada por el host falso (en el enlace real, es la ID del mensaje del asistente). */
        const val TURN_KEY = "turn-message-1"

        /** Número de párrafos para leer la escena párrafo por párrafo (menor o igual a [TURN_LIMIT], el límite superior no debe activarse). */
        const val VOICE_SEGMENTS = 3

        /** con [MediaGenToolsImpl] El límite de voz de un solo turno mantiene una base de afirmación consistente. */
        const val TURN_LIMIT = 5
    }
}
