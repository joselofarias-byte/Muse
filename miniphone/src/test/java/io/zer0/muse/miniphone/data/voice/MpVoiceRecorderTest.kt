package io.zer0.muse.miniphone.data.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * bordes de la grabadora:Cuando la colección no se pueda iniciar, no se eliminará. Una vez completada la recopilación, se entregará un WAV completo y el archivo temporal se eliminará al final.
 *
 * Todos los recopiladores son reemplazados por implementaciones falsas. —— No encienda el micrófono ni solicite permiso durante una prueba única,Sólo revisa "cómo entran los bytes al archivo y cómo salen".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MpVoiceRecorderTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `Cuando no se puede obtener el recopilador, start devuelve falso y no se descarta ningún archivo`() = runTest {
        val cache = tempDir.newFolder(CACHE_DIR)

        val recorder = MpVoiceRecorder(
            directory = cache,
            factory = { _, _, _ -> null },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse("Debe regresar si falla la inicialización false", recorder.start())
        assertTrue("Las rutas fallidas no deberían dejar archivos temporales", cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun `Cuando la minería falla, start devuelve falso y borra los archivos temporales`() = runTest {
        val cache = tempDir.newFolder(CACHE_DIR)
        val record = FakeAudioRecord(startError = IllegalStateException("El micrófono está ocupado."))

        val recorder = MpVoiceRecorder(
            directory = cache,
            factory = { _, _, _ -> record },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertFalse(recorder.start())
        assertTrue("Si la minería falla, el recolector debe ser liberado.", record.released)
        assertTrue("No extraer no debería dejar archivos temporales", cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun `Después de detenerse, entregue los bytes con el encabezado WAV y elimine el archivo temporal`() = runTest {
        val cache = tempDir.newFolder(CACHE_DIR)
        val pcm = ByteArray(PCM_BYTES) { SAMPLE_BYTE }
        val record = FakeAudioRecord(chunks = listOf(pcm))

        val recorder = MpVoiceRecorder(
            directory = cache,
            factory = { _, _, _ -> record },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(recorder.start())
        val wav = recorder.stop()

        assertNotNull("Cuando se recopilan datos, debe haber audio.", wav)
        assertEquals("WAV = 44 encabezado de byte + PCM", 44 + PCM_BYTES, wav!!.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(PCM_BYTES, leInt(wav, 40))
        assertEquals(MpVoiceRecorder.SAMPLE_RATE, leInt(wav, 24))
        assertEquals("Después de que la primera muestra caiga a la cabeza tal como está", SAMPLE_BYTE, wav[44])
        assertTrue("Los archivos temporales deben eliminarse después de la entrega.", cache.listFiles().isNullOrEmpty())
        assertTrue("Libere el recolector después de detenerse.", record.released)
    }

    @Test
    fun `Cuando no se recopila ni un solo byte, stop devuelve nulo y no quedan archivos`() = runTest {
        val cache = tempDir.newFolder(CACHE_DIR)
        val record = FakeAudioRecord(chunks = emptyList())

        val recorder = MpVoiceRecorder(
            directory = cache,
            factory = { _, _, _ -> record },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(recorder.start())

        assertNull("Sin audio, sin resultados", recorder.stop())
        assertTrue(cache.listFiles().isNullOrEmpty())
    }

    @Test
    fun `Deje de minar y elimine archivos temporales después de la cancelación`() = runTest {
        val cache = tempDir.newFolder(CACHE_DIR)
        val record = FakeAudioRecord(chunks = listOf(ByteArray(PCM_BYTES)))

        val recorder = MpVoiceRecorder(
            directory = cache,
            factory = { _, _, _ -> record },
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(recorder.start())
        recorder.cancel()

        assertTrue("Cancelar para dejar de comprar", record.stopped)
        assertTrue("Cancelar para liberar al cobrador", record.released)
        assertTrue("Cancelar para eliminar archivos temporales", cache.listFiles().isNullOrEmpty())
        assertNull("Si cancelas, no habrá audio para entregar.", recorder.stop())
    }

    @Test
    fun `Limpie y elimine solo los archivos de prefijo de este módulo que tengan más de una hora`() {
        val cache = tempDir.newFolder(CACHE_DIR)
        val now = 1_700_000_000_000L
        val fresh = cache.recordingFile("mp-voice-fresh.pcm", touchedAt = now - 1_000L)
        val stale = cache.recordingFile("mp-voice-stale.pcm", touchedAt = now - STALE_MS - 1_000L)
        val foreign = cache.recordingFile("notes.txt", touchedAt = now - STALE_MS * 3)

        val removed = cleanupStaleRecordings(cache, now = now)

        assertEquals("Sólo se debe eliminar el archivo de prefijo antiguo.", 1, removed)
        assertTrue("La grabación que acaba de cambiar no se puede eliminar.", fresh.exists())
        assertTrue("Los archivos que no tengan el prefijo de este módulo no se pueden eliminar.", foreign.exists())
        assertFalse("Los residuos que superen la hora deberán eliminarse.", stale.exists())
    }

    private fun File.recordingFile(name: String, touchedAt: Long): File = File(this, name).apply {
        writeBytes(ByteArray(4))
        setLastModified(touchedAt)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val CACHE_DIR: String = "cache"
        const val PCM_BYTES: Int = 3_200
        const val SAMPLE_BYTE: Byte = 0x2A
        const val STALE_MS: Long = 2L * 60L * 60L * 1_000L
    }
}

/**
 * coleccionista falso:escupir datos en trozos dados,Informe después de vomitar -1(De acuerdo con la convención de AudioRecord.read).
 *
 * @param chunks Bloque PCM que se entrega a la persona que llama por turno
 * @param startError Cuando no esté vacío, recogerlo y tirarlo directamente.,Se utiliza para verificar rutas fallidas.
 */
private class FakeAudioRecord(
    private val chunks: List<ByteArray> = emptyList(),
    private val startError: Exception? = null,
) : MpAudioRecord {

    private var cursor: Int = 0

    var stopped: Boolean = false
        private set

    var released: Boolean = false
        private set

    override fun start() {
        startError?.let { throw it }
    }

    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        val chunk = chunks.getOrNull(cursor) ?: return -1
        cursor += 1
        val length = minOf(chunk.size, size)
        chunk.copyInto(buffer, offset, 0, length)
        return length
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {
        released = true
    }
}
