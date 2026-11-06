package io.zer0.muse.miniphone.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Ubicación de la imagen:Denominación, deduplicación, omisión de fuentes ilegibles y grabación por encima de límites.
 *
 * La prueba inyecta el flujo de entrada.:En la máquina real es ContentResolver,Cambie esto a un segmento de bytes.,Para fijar "la misma imagen
 * "Elige dos veces y quédate sólo con una" y "el fracaso de una no afecta a las demás" son dos cosas que realmente pueden causar problemas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MpImageStoreTest {

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("mp-image-store").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun sameImagePickedTwiceKeepsOneFileAndTheSamePath() = runBlocking {
        val bytes = pngBytes(width = 4, height = 4)
        val store = storeOf(bytes)

        val first = store.import(pickerUri("1"))
        val second = store.import(pickerUri("2"))

        assertNotNull(first)
        assertEquals("El mismo contenido debe seguir el mismo camino.", first, second)
        val stored = directory.listFiles().orEmpty()
        assertEquals("Sólo se debe guardar un documento", 1, stored.size)
        assertTrue("El nombre del archivo son los primeros 16 dígitos del resumen del contenido + el sufijo real.", stored.single().name.matches(NAME_PATTERN))
    }

    @Test
    fun unreadableSourceIsSkippedInsteadOfFailing() = runBlocking {
        val store = MpImageStore(
            directory = directory,
            openStream = { throw IOException("El original ha sido eliminado.") },
        )

        assertNull(store.import(pickerUri("missing")))
        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    @Test
    fun emptySourceIsSkipped() = runBlocking {
        val store = MpImageStore(directory = directory, openStream = { ByteArrayInputStream(ByteArray(0)) })

        assertNull(store.import(pickerUri("empty")))
        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    @Test
    fun oversizedImageIsReencodedDownToTheMaxEdge() = runBlocking {
        val store = storeOf(
            bytes = pngBytes(width = 2_000, height = 800),
            compressMinBytes = 0,
        )

        val path = requireNotNull(store.import(pickerUri("big")))

        assertTrue("Después de grabar es JPEG", path.endsWith(".jpg"))
        val stored = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, stored)
        assertEquals(1_600, maxOf(stored.outWidth, stored.outHeight))
        assertEquals("La relación de aspecto permanece sin cambios", 800 * 1_600 / 2_000, minOf(stored.outWidth, stored.outHeight))
    }

    @Test
    fun sampleSizeStaysAtPowersOfTwoAndAboveHalfTheTarget() {
        assertEquals(1, sampleSizeFor(longEdge = 1_600, maxEdge = 1_600))
        assertEquals(2, sampleSizeFor(longEdge = 6_000, maxEdge = 1_600))
        assertEquals(8, sampleSizeFor(longEdge = 12_800, maxEdge = 1_600))
        assertTrue(sampleSizeFor(longEdge = 12_800, maxEdge = 1_600) * 1_600 <= 12_800)
    }

    private fun storeOf(bytes: ByteArray, compressMinBytes: Long = DEFAULT_COMPRESS_MIN_BYTES): MpImageStore = MpImageStore(
        directory = directory,
        openStream = { ByteArrayInputStream(bytes) },
        compressMinBytes = compressMinBytes,
    )

    private fun pickerUri(id: String): Uri = Uri.parse("content://picker/$id")

    /** PNG verdadero: deje que la lectura y la recodificación del tamaño sigan la ruta de decodificación verdadera. */
    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private companion object {
        /** 16 caracteres hexadecimales + sufijo. */
        val NAME_PATTERN = Regex("[0-9a-f]{16}\\.(png|jpg|gif|img)")

        /** Consistente con el umbral predeterminado de MpImageStore:Escríbalo explícitamente en la prueba.,Esto le recordará cuando cambie el valor predeterminado. */
        const val DEFAULT_COMPRESS_MIN_BYTES: Long = 8L * 1024 * 1024
    }
}
