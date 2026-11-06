package io.zer0.muse.ui.speech

import kotlinx.coroutines.runBlocking
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
 * [VoiceMessageStore] Pruebas de comportamiento (JVM pura, sin dependencia de la pila de audio de Android).
 *
 * Detección de duración superada [VoiceDurationProbe] Inyecta un valor fijo para que no se toque MediaMetadataRetriever.
 * Cobertura: Colocación/Análisis de ida y vuelta, reutilización de la misma clave, falta de degradación del archivo fuente, rechazo de referencia fuera de límites, eliminación de un solo elemento,
 * Limpieza huérfana (incluido el .tmp dejado por la interrupción de la descarga del disco).
 */
class VoiceMessageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(probe: VoiceDurationProbe = VoiceDurationProbe { 1234L }): VoiceMessageStore =
        VoiceMessageStore(storageDir = File(tmp.root, "voice_messages"), durationProbe = probe)

    private fun tempAudio(name: String = "chunk.wav", content: String = "RIFF-fake-wav"): File =
        File(tmp.newFolder(), name).apply { writeText(content) }

    @Test
    fun persistCopiesIntoStoreAndResolveReturnsSameContent() = runBlocking {
        val store = store()
        val source = tempAudio(content = "audio-bytes")

        val uri = store.persist(source, "msg-1")

        assertNotNull("La colocación debe ser exitosa.", uri)
        assertTrue("La referencia debe tener el formato file://, que en realidad $uri", uri!!.startsWith("file://"))
        val stored = store.resolve(uri)
        assertNotNull("La referencia debe resolverse nuevamente en el archivo.", stored)
        assertEquals("El contenido debe ser coherente con el archivo temporal.", "audio-bytes", stored!!.readText())
        assertEquals("El archivo debe estar en el directorio de la tienda.", storeDir(store), stored.parentFile)
        assertTrue("Archivos fuente retenidos (la limpieza es responsabilidad de la persona que llama)", source.exists())
    }

    @Test
    fun persistSameKeyReusesSingleFile() = runBlocking {
        val store = store()

        val first = store.persist(tempAudio(content = "v1"), "same-text")
        val second = store.persist(tempAudio(content = "v2"), "same-text")

        assertEquals("La misma clave debería llegar a la misma referencia de archivo.", first, second)
        assertEquals("Sólo debe haber un archivo en el directorio.", 1, storeDir(store).listFiles()!!.size)
        assertEquals("Las composiciones duplicadas deben sobrescribirse con el contenido más reciente.", "v2", store.resolve(second!!)!!.readText())
    }

    @Test
    fun persistReturnsNullWhenSourceMissingOrEmpty() = runBlocking {
        val store = store()

        assertNull("El archivo fuente no existe y debe degradarse a null", store.persist(File(tmp.root, "nope.wav"), "k1"))
        assertNull("Los archivos vacíos deben degradarse a null", store.persist(tempAudio(content = ""), "k2"))
        assertFalse("No se deben crear directorios de archivo cuando los archivos fuente no están disponibles", storeDir(store).exists())
    }

    @Test
    fun resolveRejectsMissingForeignAndBlankUris() = runBlocking {
        val store = store()
        val uri = store.persist(tempAudio(), "msg-2")!!

        assertEquals("La cadena vacía no se puede analizar", null, store.resolve(""))
        assertEquals("Los espacios en blanco puros no son analizables", null, store.resolve("   "))

        val outside = File(tmp.newFolder(), "outside.wav").apply { writeText("x") }
        assertNull("store Las rutas fuera del directorio no deben tratarse como voz.", store.resolve("file://" + outside.absolutePath))

        store.delete(uri)
        assertNull("Debe devolverse cuando el archivo haya sido eliminado. null", store.resolve(uri))
    }

    @Test
    fun deleteRemovesOnlyTargetFile() = runBlocking {
        val store = store()
        val keep = store.persist(tempAudio(content = "keep"), "keep-key")!!
        val drop = store.persist(tempAudio(content = "drop"), "drop-key")!!

        store.delete(drop)

        assertNull("El archivo de destino debe eliminarse", store.resolve(drop))
        assertNotNull("Otros archivos no se ven afectados", store.resolve(keep))
        store.delete(drop) // Eliminarlo nuevamente no debería generar una excepción.
    }

    @Test
    fun cleanupOrphansKeepsReferencedAndRemovesTempFiles() = runBlocking {
        val store = store()
        val keep = store.persist(tempAudio(content = "keep"), "keep-key")!!
        val orphan = store.persist(tempAudio(content = "orphan"), "orphan-key")!!
        File(storeDir(store), "broken.wav.tmp").writeText("half-written")

        val removed = store.cleanupOrphans(listOf(keep))

        assertEquals("1 guardado huérfano + 1 debe eliminarse .tmp", 2, removed)
        assertNotNull("Los archivos referenciados deben conservarse.", store.resolve(keep))
        assertNull("Los archivos sin referencia deben limpiarse", store.resolve(orphan))
        assertEquals("Sólo se debe dejar en el directorio el referenciado.", 1, storeDir(store).listFiles()!!.size)
    }

    @Test
    fun durationMsUsesProbeAndDegradesToZero() = runBlocking {
        val store = store(probe = VoiceDurationProbe { file -> file.length() * 10 })
        val uri = store.persist(tempAudio(content = "0123456789"), "duration-key")!!

        assertEquals("La duración debe provenir del detector.", 100L, store.durationMs(uri))
        assertEquals("El plazo durante el cual la referencia no puede resolverse es 0", 0L, store.durationMs("file:///nowhere/missing.wav"))
    }

    @Test
    fun probeFailureDegradesToZero() {
        val store = store(probe = VoiceDurationProbe { error("decoder exploded") })

        assertEquals("La duración durante la cual el detector lanza una excepción debe ser 0", 0L, store.probeDurationMs(tempAudio()))
    }

    /** store Directorio (el parámetro de construcción es interno y la prueba lo lee directamente). */
    private fun storeDir(store: VoiceMessageStore): File = store.storageDir
}
