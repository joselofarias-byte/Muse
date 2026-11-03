package io.zer0.muse.data.sticker

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * R-TEST-08: Etiqueta ZIP Nombre de archivo chino / extensión en mayúsculas / directorio anidado / archivo de ruido.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class StickerLibraryRepositoryImportZipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = StickerLibraryRepository(context)

    @Before
    fun clean() = runTest {
        repository.clearAll()
    }

    @After
    fun tearDown() = runTest {
        repository.clearAll()
    }

    @Test
    fun `import zip with chinese folders and uppercase extension`() = runTest {
        val zip = createZip(
            entries = mapOf(
                "Emoticones/feliz/001.GIF" to byteArrayOf(1, 2, 3),
                "Emoticones/feliz/002.png" to byteArrayOf(4, 5, 6),
                "Emoticones/diariamente/003.jpg" to byteArrayOf(7, 8, 9),
            ),
            noiseEntries = listOf(".DS_Store", "__MACOSX/extra", "readme.txt"),
        )
        val result = repository.importZip(Uri.fromFile(zip), null)
        assertTrue("La importación debería tener éxito: $result", result.isSuccess)
        assertEquals(3, result.getOrNull())

        val stickers = repository.listStickers()
        assertEquals(3, stickers.size)
        assertTrue(stickers.any { it.fileName == "001.GIF" && it.category == "feliz" })
        assertTrue(stickers.any { it.fileName == "002.png" && it.category == "feliz" })
        assertTrue(stickers.any { it.fileName == "003.jpg" && it.category == "diariamente" })
    }

    @Test
    fun `empty zip returns readable error`() = runTest {
        val zip = createZip(emptyMap(), emptyList())
        val result = repository.importZip(Uri.fromFile(zip), null)
        assertTrue("Se debe devolver un zip vacío. Error", result.isError)
        assertTrue(result.toString().contains("No se encontró ninguna imagen"))
    }

    private fun createZip(entries: Map<String, ByteArray>, noiseEntries: List<String>): File {
        val zip = File.createTempFile("sticker_test_", ".zip", context.cacheDir)
        ZipOutputStream(zip.outputStream(), Charset.forName("GBK")).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
            noiseEntries.forEach { name ->
                zos.putNextEntry(ZipEntry(name))
                zos.closeEntry()
            }
        }
        return zip
    }
}
