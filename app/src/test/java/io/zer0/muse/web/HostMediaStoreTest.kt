package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

/**
 * Libro mayor de tokens de medios y conversión de citas.
 *
 * Cubre tres cosas: permitir que los archivos en el directorio obtengan tokens, volver a emitir el mismo archivo para reutilizar el mismo token,
 * LRU Una vez superado el límite, el token antiguo deja de ser válido; además, la colocación de base64 desnuda y la eliminación cuando no se puede reconocer el contenido.
 */
class HostMediaStoreTest {

    private val workDir = Files.createTempDirectory("host-media-store").toFile()
    private val imageDir = File(workDir, "muse_images").apply { mkdirs() }
    private val voiceDir = File(workDir, "voice_messages").apply { mkdirs() }
    private val outsideDir = File(workDir, "shared_prefs").apply { mkdirs() }

    private fun store(maxEntries: Int = HostMediaStore.DEFAULT_MAX_ENTRIES) =
        HostMediaStore(imageDir, voiceDir, maxEntries = maxEntries)

    /** Escribir una imagen con un encabezado de archivo multimedia/archivo de audio,Devuelve el archivo en sí. */
    private fun mediaFile(dir: File, name: String, bytes: ByteArray = PNG_BYTES): File =
        File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun `file reference inside allowed dir yields a token that resolves back to the file`() {
        val subject = store()
        val file = mediaFile(imageDir, "msg_0_aaa.bin")

        val token = subject.tokenForFile("file://${file.absolutePath}")
        assertNotNull(token)
        assertEquals(TOKEN_LENGTH, token!!.length)
        assertEquals(file.canonicalFile, subject.resolve(token))
    }

    @Test
    fun `voice directory is served too`() {
        val subject = store()
        val file = mediaFile(voiceDir, "2f7c.wav")

        val token = subject.tokenForFile(file.absolutePath)
        assertNotNull(token)
        assertEquals(file.canonicalFile, subject.resolve(token!!))
    }

    @Test
    fun `repeated issuance for the same file reuses one token`() {
        val subject = store()
        val file = mediaFile(imageDir, "same.bin")

        val first = subject.tokenForFile("file://${file.absolutePath}")
        val second = subject.tokenForFile("file://${file.absolutePath}")
        // Otra forma de escribir para apuntar al mismo archivo después de la normalización.(traer ../)También debe afectar a la misma cuenta.
        val third = subject.tokenForFile("file://${imageDir.absolutePath}/../muse_images/same.bin")

        assertNotNull(first)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun `references outside the allowed media directories are rejected`() {
        val secret = mediaFile(outsideDir, "prefs.xml", "<prefs/>".toByteArray())
        val subject = store()

        assertNull("La ruta absoluta apunta directamente fuera del directorio de medios.", subject.tokenForFile("file://${secret.absolutePath}"))
        assertNull("Escapar del directorio de medios", subject.tokenForFile("file://${imageDir.absolutePath}/../shared_prefs/prefs.xml"))
        assertNull("referencia en blanco", subject.tokenForFile("   "))
        assertNull("Archivos que aún no se han colocado en el disco.", subject.tokenForFile("file://${File(imageDir, "not-written.png").absolutePath}"))
    }

    @Test
    fun `token stops resolving once the file is cleaned up`() {
        val subject = store()
        val file = mediaFile(imageDir, "gone.bin")
        val token = subject.tokenForFile(file.absolutePath)
        assertNotNull(token)
        assertTrue(file.delete())

        assertNull("Una vez limpiado el archivo, los resultados ya no deberían analizarse.", subject.resolve(token!!))
    }

    @Test
    fun `lru cap turns the least used token into a miss`() {
        val subject = store(maxEntries = 2)
        val first = subject.tokenForFile(mediaFile(imageDir, "one.bin").absolutePath)
        val third = subject.tokenForFile(mediaFile(imageDir, "two.bin").absolutePath)
        assertNotNull(first)
        assertNotNull(third)
        subject.tokenForFile(mediaFile(imageDir, "three.bin").absolutePath)

        assertNull("El token que no se haya utilizado durante más tiempo después de exceder el límite debería dejar de ser válido.", subject.resolve(first!!))
        assertNotNull(subject.resolve(third!!))
    }

    @Test
    fun `resolving a token keeps it ahead of the eviction line`() {
        val subject = store(maxEntries = 2)
        val first = subject.tokenForFile(mediaFile(imageDir, "one.bin").absolutePath)
        val second = subject.tokenForFile(mediaFile(imageDir, "two.bin").absolutePath)
        assertNotNull(first)
        assertNotNull(second)
        // Lea una vez y mencione primero el usado recientemente.,Cuando se inserta el tercero, se elimina el segundo.
        subject.resolve(first!!)
        val third = subject.tokenForFile(mediaFile(imageDir, "three.bin").absolutePath)

        assertNotNull(subject.resolve(first))
        assertNull(subject.resolve(second!!))
        assertNotNull(subject.resolve(third!!))
    }

    @Test
    fun `seeded mapping that points outside the media dirs resolves to nothing`() {
        val secret = mediaFile(outsideDir, "prefs.xml", "<prefs/>".toByteArray())
        val subject = store()
        subject.seedMapping(SEED_TOKEN, secret.absolutePath)

        assertNull(subject.resolve(SEED_TOKEN))
    }

    @Test
    fun `bare base64 image is materialized into the media dir and reuses one token`() {
        val subject = store()
        val payload = Base64.getEncoder().encodeToString(PNG_BYTES)

        val token = subject.tokenForInlineData(payload, null, MESSAGE_ID)
        assertNotNull(token)
        assertEquals(token, subject.tokenForInlineData(payload, null, MESSAGE_ID))

        val file = subject.resolve(token!!)
        assertNotNull(file)
        assertTrue("El archivo descargado debe estar en el directorio de imágenes.", file!!.parentFile == imageDir.canonicalFile)
        assertTrue("El contenido del disco debe ser coherente con los bytes decodificados.", PNG_BYTES.contentEquals(file.readBytes()))
        // El nombre del archivo comienza con la identificación del mensaje.:Al eliminar un mensaje, MessageImageStore {messageId}_ El escaneo de prefijos también lo borrará.
        assertTrue("Los archivos desplegables en línea deben tener el prefijo del ID del mensaje atribuido.: ${file.name}", file.name.startsWith("${MESSAGE_ID}_"))
    }

    @Test
    fun `data uri declares the type when the payload itself is not recognizable`() {
        val subject = store()
        val payload = Base64.getEncoder().encodeToString("raw rgb blob, no magic".toByteArray() + ByteArray(16))

        val token = subject.tokenForInlineData(payload, "image/png", MESSAGE_ID)
        assertNotNull(token)
        val file = subject.resolve(token!!)
        assertNotNull(file)
        assertEquals("image/png", HostMediaContentType.of(file!!))
    }

    @Test
    fun `payload that is not a recognizable medium is dropped`() {
        val subject = store()
        val payload = Base64.getEncoder().encodeToString("just some plain text, definitely not an image".toByteArray())

        assertNull(subject.tokenForInlineData(payload, null, MESSAGE_ID))
        assertNull(subject.tokenForInlineData("too short", null, MESSAGE_ID))
    }

    @Test
    fun `links swap every usable form for a media route and keep plain http urls`() {
        val subject = store()
        val links = HostMediaLinks(subject)
        val file = mediaFile(imageDir, "card.bin")
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES)

        val mapped = links.links(
            listOf(
                "file://${file.absolutePath}",
                dataUri,
                "https://cdn.example.com/a.png",
                "not a media reference at all",
                "",
            ),
            MESSAGE_ID,
        )

        assertEquals(3, mapped.size)
        assertTrue(mapped[0].startsWith(HostMediaLinks.MEDIA_PATH_PREFIX))
        assertTrue(mapped[1].startsWith(HostMediaLinks.MEDIA_PATH_PREFIX))
        assertEquals("https://cdn.example.com/a.png", mapped[2])
        assertEquals(file.canonicalFile, subject.resolve(mapped[0].removePrefix(HostMediaLinks.MEDIA_PATH_PREFIX)))
    }

    @Test
    fun `voice link is null for blank and for an unusable reference`() {
        val links = HostMediaLinks(store())

        assertNull(links.voiceLink(null, MESSAGE_ID))
        assertNull(links.voiceLink("", MESSAGE_ID))
        assertNull(links.voiceLink("file://${File(outsideDir, "nope.mp3").absolutePath}", MESSAGE_ID))
        assertNotNull(links.voiceLink(mediaFile(voiceDir, "say.m4a", M4A_BYTES).absolutePath, MESSAGE_ID))
    }

    @Test
    fun `content type comes from the file header first and the extension second`() {
        assertEquals("image/png", HostMediaContentType.of(mediaFile(imageDir, "a.png", PNG_BYTES)))
        assertEquals("image/jpeg", HostMediaContentType.of(mediaFile(imageDir, "a.jpg", JPEG_BYTES)))
        assertEquals("image/gif", HostMediaContentType.of(mediaFile(imageDir, "a.gif", GIF_BYTES)))
        assertEquals("image/webp", HostMediaContentType.of(mediaFile(imageDir, "a.webp", WEBP_BYTES)))
        // .bin La imagen descargada no tiene extensión disponible.,Solo puede confiar en el encabezado del archivo.
        assertEquals("image/jpeg", HostMediaContentType.of(mediaFile(imageDir, "legacy.bin", JPEG_BYTES)))
        assertEquals("audio/mp4", HostMediaContentType.of(mediaFile(voiceDir, "a.m4a", M4A_BYTES)))
        assertEquals("audio/aac", HostMediaContentType.of(mediaFile(voiceDir, "a.aac", AAC_BYTES)))
        assertEquals("audio/mpeg", HostMediaContentType.of(mediaFile(voiceDir, "a.mp3", MP3_BYTES)))
        // Volver a la extensión cuando no se reconoce el encabezado del archivo,No enviará mp3 como archivo adjunto binario.
        assertEquals("audio/mpeg", HostMediaContentType.of(mediaFile(voiceDir, "fallback.mp3", "junk".toByteArray())))
        assertEquals(
            HostMediaContentType.OCTET_STREAM,
            HostMediaContentType.of(mediaFile(voiceDir, "unknown.xyz", "junk".toByteArray())),
        )
    }

    private companion object {
        const val TOKEN_LENGTH = 32
        const val SEED_TOKEN = "seededtoken00000000000000000000"
        const val MESSAGE_ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"

        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24)
        val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(20)
        val GIF_BYTES = "GIF89a".toByteArray() + ByteArray(20)
        val WEBP_BYTES = "RIFF".toByteArray() + byteArrayOf(0x10, 0, 0, 0) + "WEBP".toByteArray() + ByteArray(8)
        val M4A_BYTES = byteArrayOf(0, 0, 0, 0x20.toByte()) + "ftyp".toByteArray() + "M4A ".toByteArray() + ByteArray(16)
        val AAC_BYTES = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x80.toByte()) + ByteArray(20)
        val MP3_BYTES = "ID3".toByteArray() + byteArrayOf(0x03, 0x00) + ByteArray(24)
    }
}
