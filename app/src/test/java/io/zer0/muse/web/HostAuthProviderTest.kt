package io.zer0.muse.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Date

/**
 * Puerta JWT en el lado de producción:[installHostAuthProvider] El supuesto proveedor utiliza HTTP real.
 *
 * Aquí no hay más sustitutos. —— Las pruebas anteriores de JVM omitieron ktor `jwt()` provider, porque cuando reciba el bono
 * Para cargar los excluidos `com.auth0.jwk.JwkException`; Ahora el lado de producción ya no usa ese módulo.
 * La instalación de prueba es el mismo código en WebServer.
 *
 * Afirmación clave "401 En lugar de 500": credenciales faltantes, firmas inconsistentes, caducadas, todo debe estar limpio 401,
 * De lo contrario `/ws` El protocolo de enlace le aparece al navegador como "no se puede conectar".
 */
class HostAuthProviderTest {

    private val jwtSecret = "muse-auth-test-secret"
    private val algorithm: Algorithm = Algorithm.HMAC256(jwtSecret)

    private fun token(
        secret: String = jwtSecret,
        expiresAt: Long = System.currentTimeMillis() + 60_000L,
        subject: String = HOST_AUTH_SUBJECT,
    ): String = JWT.create()
        .withSubject(subject)
        .withIssuedAt(Date())
        .withExpiresAt(Date(expiresAt))
        .sign(Algorithm.HMAC256(secret))

    /** Un servicio mínimo isomorfo a WebServer: el mismo código de proveedor, una ruta REST protegida + un /ws protegido. */
    private fun withServer(block: (port: Int) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(WebSockets)
            install(Authentication) {
                installHostAuthProvider(providerName = AUTH_NAME, realm = "muse-auth-test", algorithm = algorithm)
            }
            routing {
                authenticate(AUTH_NAME) {
                    get("/api/sessions") { call.respondText("[]") }
                    webSocket("/ws") { /* El protocolo de enlace es exitoso y no se ejecuta ningún comando. */ }
                }
            }
        }
        server.start(wait = false)
        try {
            waitUntilReady(port)
            block(port)
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `protected rest route without credentials is a clean 401`() {
        withServer { port ->
            val response = get(port, "/api/sessions")

            assertEquals("Las credenciales faltantes deben ser 401, no pueden ser 500: ${response.text()}", HttpURLConnection.HTTP_UNAUTHORIZED, response.status)
        }
    }

    @Test
    fun `websocket handshake without credentials is a clean 401`() {
        withServer { port ->
            assertEquals("Si el protocolo de enlace carece de credenciales, debe ser 401 y no puede ser 500", HttpURLConnection.HTTP_UNAUTHORIZED, rawHandshake(port))
        }
    }

    @Test
    fun `valid token in the authorization header is let through`() {
        withServer { port ->
            val response = get(port, "/api/sessions", authorization = "Bearer ${token()}")

            assertEquals("Se deben liberar tokens legítimos: ${response.text()}", HttpURLConnection.HTTP_OK, response.status)
            assertEquals("[]", response.text())
        }
    }

    @Test
    fun `valid token in the cookie is let through`() {
        withServer { port ->
            val response = get(port, "/api/sessions", cookie = "${HOST_AUTH_COOKIE}=${token()}")

            assertEquals("Cookie Los tokens legales también deberían liberarse.: ${response.text()}", HttpURLConnection.HTTP_OK, response.status)
        }
    }

    @Test
    fun `expired token is a 401`() {
        withServer { port ->
            val expired = token(expiresAt = System.currentTimeMillis() - 60_000L)

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, get(port, "/api/sessions", "Bearer $expired").status)
            assertEquals(
                "Los tokens caducados deben colocarse en las cookies. 401",
                HttpURLConnection.HTTP_UNAUTHORIZED,
                get(port, "/api/sessions", cookie = "${HOST_AUTH_COOKIE}=$expired").status,
            )
        }
    }

    @Test
    fun `token signed with another secret is a 401`() {
        withServer { port ->
            val forged = token(secret = "not-the-server-secret")

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, get(port, "/api/sessions", "Bearer $forged").status)
        }
    }

    @Test
    fun `token with a foreign subject is a 401`() {
        withServer { port ->
            val foreign = token(subject = "somebody-else")

            assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, get(port, "/api/sessions", "Bearer $foreign").status)
        }
    }

    @Test
    fun `websocket handshake with a valid token upgrades`() {
        withServer { port ->
            assertEquals(
                "Un token válido debería completar el protocolo de enlace.",
                SWITCHING_PROTOCOLS,
                rawHandshake(port, authorization = "Bearer ${token()}"),
            )
        }
    }

    @Test
    fun `websocket handshake with the token in a cookie upgrades too`() {
        withServer { port ->
            assertEquals(
                "Cookie También es una fuente legítima de credenciales.",
                SWITCHING_PROTOCOLS,
                rawHandshake(port, cookie = "${HOST_AUTH_COOKIE}=${token()}"),
            )
        }
    }

    /**
     * Envíe una solicitud de actualización de WebSocket real utilizando un socket simple y devuelva el código de estado de respuesta.
     *
     * No utilice HttpURLConnection: se descartará silenciosamente `Connection: Upgrade` Este tipo de cabeza restringida,
     * Cuando la solicitud llega al servidor, ya no es una solicitud de actualización.WebSockets El complemento devuelve 400 primero y la autenticación no se ejecuta en absoluto. —— de esa manera
     * No hay forma de detectar "el certificado faltante es 401 en lugar de 500".
     */
    private fun rawHandshake(port: Int, authorization: String? = null, cookie: String? = null): Int {
        val extra = buildString {
            if (authorization != null) append("Authorization: ").append(authorization).append("\r\n")
            if (cookie != null) append("Cookie: ").append(cookie).append("\r\n")
        }
        val request = buildString {
            append("GET /ws HTTP/1.1\r\n")
            append("Host: 127.0.0.1:").append(port).append("\r\n")
            append("Connection: Upgrade\r\n")
            append("Upgrade: websocket\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
            append(extra)
            append("\r\n")
        }
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val statusLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine().orEmpty()
            // en forma de `HTTP/1.1 401 Unauthorized`
            return statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("No se puede analizar la línea de respuesta: $statusLine")
        }
    }

    private class Response(val status: Int, val body: ByteArray) {
        fun text(): String = String(body, Charsets.UTF_8)
    }

    private fun get(
        port: Int,
        path: String,
        authorization: String? = null,
        cookie: String? = null,
    ): Response {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
            ?: ByteArray(0)
        connection.disconnect()
        return Response(status, body)
    }

    private fun waitUntilReady(port: Int) {
        repeat(100) {
            val ready = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) } }.isSuccess
            if (ready) return
            Thread.sleep(10)
        }
        error("Servicio de prueba de autenticación no iniciado")
    }

    private companion object {
        const val AUTH_NAME = "auth-jwt"

        /** Código de estado para una actualización exitosa. */
        const val SWITCHING_PROTOCOLS = 101
    }
}
