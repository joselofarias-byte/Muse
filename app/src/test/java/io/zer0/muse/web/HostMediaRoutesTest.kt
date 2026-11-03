package io.zer0.muse.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.Date

/**
 * `/api/media/{token}` El comportamiento de lectura: autenticación, tipo de contenido correcto, fuera de límites y faltante son todos 404.
 *
 * Aquí, se utiliza una solicitud HTTP real para crear un servicio Ktor real. Lo que se prueba es que "la ruta está instalada en `authenticate` bloquear" esta restricción:
 * El canal de autenticación debe bloquear el enrutamiento de las solicitudes sin credenciales y ni siquiera pueden ingresar a la función de procesamiento.
 *
 * La puerta se llena con la porción de producción. [installHostAuthProvider](bearer provider + java-jwt HMAC verificación),
 * No es un suplente.
 */
class HostMediaRoutesTest {

    private val workDir = Files.createTempDirectory("host-media-routes").toFile()
    private val imageDir = File(workDir, "muse_images").apply { mkdirs() }
    private val voiceDir = File(workDir, "voice_messages").apply { mkdirs() }
    private val outsideDir = File(workDir, "shared_prefs").apply { mkdirs() }
    private val jwtSecret = "muse-media-test-secret"
    private val algorithm: Algorithm = Algorithm.HMAC256(jwtSecret)

    /** Mismo calibre que WebServer.issueJwt:subject=muse-web,24h Dentro del período de validez. */
    private fun signedToken(): String = JWT.create()
        .withSubject(HOST_AUTH_SUBJECT)
        .withIssuedAt(Date())
        .withExpiresAt(Date(System.currentTimeMillis() + 60_000L))
        .sign(algorithm)

    /** Iniciar un verdadero servicio Ktor:El enrutamiento de medios está instalado en `authenticate` bloquear,Situado al mismo nivel que WebServer. */
    private fun withServer(store: HostMediaStore, block: (port: Int, authorization: String) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(Authentication) {
                installHostAuthProvider(providerName = AUTH_NAME, realm = "muse-media-test", algorithm = algorithm)
            }
            routing {
                authenticate(AUTH_NAME) {
                    installHostMediaRoutes(store)
                }
            }
        }
        server.start(wait = false)
        try {
            waitUntilReady(port)
            block(port, "Bearer ${signedToken()}")
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `request without jwt never reaches the media handler`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val token = store.tokenForFile(File(imageDir, "a.png").also { it.writeBytes(PNG_BYTES) }.absolutePath)
        assertNotNull(token)

        withServer(store) { port, _ ->
            val response = get(port, mediaPath(token))

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.status)
            assertTrue("Los bytes de imagen no deben eliminarse sin autenticación: ${response.text()}", response.body.isEmpty())
        }
    }

    @Test
    fun `forged jwt is refused too`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val token = store.tokenForFile(File(imageDir, "b.png").also { it.writeBytes(PNG_BYTES) }.absolutePath)

        withServer(store) { port, _ ->
            val response = get(port, mediaPath(token), "Bearer not.a.real.token")

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.status)
            assertTrue(response.body.isEmpty())
        }
    }

    @Test
    fun `registered token returns the bytes with the detected content type`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val file = File(imageDir, "gen_0_dead.bin").apply { writeBytes(PNG_BYTES) }
        val token = store.tokenForFile("file://${file.absolutePath}")
        assertNotNull(token)

        withServer(store) { port, authorization ->
            val response = get(port, mediaPath(token), authorization)

            assertEquals("body=${response.text()}", HttpURLConnection.HTTP_OK, response.status)
            assertTrue(
                "Content-Type Debe estar determinado por el encabezado del archivo.,real: ${response.contentType}",
                response.contentType.orEmpty().startsWith("image/png"),
            )
            assertNotNull("deberia traer Cache-Control", response.cacheControl)
            assertTrue("Los encabezados de respuesta deben permitir el almacenamiento en caché privado del navegador: ${response.cacheControl}", response.cacheControl!!.contains("private"))
            assertTrue(response.cacheControl!!.contains("max-age"))
            assertEquals(PNG_BYTES.size, response.body.size)
        }
    }

    @Test
    fun `voice file keeps its container type`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val file = File(voiceDir, "say.m4a").apply { writeBytes(M4A_BYTES) }
        val token = store.tokenForFile(file.absolutePath)

        withServer(store) { port, authorization ->
            val response = get(port, mediaPath(token), authorization)

            assertEquals("body=${response.text()}", HttpURLConnection.HTTP_OK, response.status)
            assertEquals("audio/mp4", response.contentType?.substringBefore(';'))
        }
    }

    @Test
    fun `mapping that points outside the media directories is a not found`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val secret = File(outsideDir, "prefs.xml").apply { writeBytes("<prefs><token>secret</token></prefs>".toByteArray()) }
        // Preestablecer una asignación para omitir la verificación de registro:El lado de lectura debe bloquearse.
        store.seedMapping("escaped", secret.absolutePath)

        withServer(store) { port, authorization ->
            val response = get(port, MEDIA_PATH + "escaped", authorization)

            assertEquals("body=${response.text()}", HttpURLConnection.HTTP_NOT_FOUND, response.status)
            assertTrue("Una solicitud fuera de límites no puede eliminar el contenido del archivo.", response.body.isEmpty())
        }
    }

    @Test
    fun `cleaned up file is a not found instead of a server error`() {
        val store = HostMediaStore(imageDir, voiceDir)
        val file = File(imageDir, "gone.png").apply { writeBytes(PNG_BYTES) }
        val token = store.tokenForFile(file.absolutePath)
        assertTrue(file.delete())

        withServer(store) { port, authorization ->
            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, get(port, mediaPath(token), authorization).status)
            assertEquals(HttpURLConnection.HTTP_NOT_FOUND, get(port, "$MEDIA_PATH${"0".repeat(32)}", authorization).status)
        }
    }

    private fun mediaPath(token: String?): String = "$MEDIA_PATH${token.orEmpty()}"

    private class Response(val status: Int, val contentType: String?, val cacheControl: String?, val body: ByteArray) {
        fun text(): String = String(body, Charsets.UTF_8)
    }

    private fun get(port: Int, path: String, authorization: String? = null): Response {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
            ?: ByteArray(0)
        val result = Response(
            status = status,
            contentType = connection.contentType,
            cacheControl = connection.getHeaderField("Cache-Control"),
            body = body,
        )
        connection.disconnect()
        return result
    }

    private fun waitUntilReady(port: Int) {
        repeat(100) {
            val ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) } }.isSuccess
            if (ready) return
            Thread.sleep(10)
        }
        error("El servicio de prueba de enrutamiento de medios no se inicia")
    }

    private companion object {
        const val AUTH_NAME = "auth-jwt"
        const val MEDIA_PATH = "/api/media/"

        val PNG_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24)
        val M4A_BYTES = byteArrayOf(0, 0, 0, 0x20.toByte()) + "ftyp".toByteArray() + "M4A ".toByteArray() + ByteArray(16)
    }
}
