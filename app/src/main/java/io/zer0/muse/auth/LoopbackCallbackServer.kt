@file:Suppress("TooGenericExceptionCaught", "MayBeConst")

package io.zer0.muse.auth

import io.zer0.common.Logger
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.6.0: OAuth loopback servidor de devolución de llamada (Authorization Code Flow dedicado).
 *
 * Antecedentes:OpenAI Codex CLI Solo se permite el mismo client_id público `http://localhost:<port>/…`
 * URI de devolución de llamada en forma de esquema personalizado (io.zer0.muse://oauth/callback) será autorizado por el servidor
 * Rechazar. Entonces, cuando redirigirUri es un bucle invertido, se inicia un servidor HTTP temporal para recibir redireccionamientos del navegador.
 *
 * Implementación: nativa [ServerSocket] Análisis HTTP GET minimalista escrito a mano —— Simplemente lea la línea de solicitud +
 * Analizar consulta sin ninguna dependencia del marco HTTP (Android Ninguno com.sun.net.httpserver,
 * Ktor/Netty El escenario de solicitud única es demasiado pesado).
 *
 * Ciclo de vida:launchAuthorizationCodeFlow Antes de abrir el navegador start(), obtener código o
 * tiempo de espera/después de la cancelación stop(). Autorice un servidor una vez y no lo reutilice.
 *
 * Seguridad: solo enlaza loopback (no accesible desde el exterior);state comprobar multiplexación
 * [OAuthManager.completeAuthorizationCodeFlow] Camino; devuelve una página legible al navegador.
 */
internal object LoopbackCallbackServer {

    private const val TAG = "LoopbackCallbackServer"

    private val SUCCESS_HTML =
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>Muse OAuth</title></head>" +
            "<body style=\"font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">" +
            "<div style=\"text-align:center\"><h2>&#10003; Autorización exitosa</h2><p>Puedes cerrar esta página y regresar a Muse para continuar.</p></div></body></html>"

    private val FAILURE_HTML =
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>Muse OAuth</title></head>" +
            "<body style=\"font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">" +
            "<div style=\"text-align:center\"><h2>&#10007; Autorización fallida</h2><p>Vuelve a Muse para comprobar el mensaje de error y vuelve a intentarlo.</p></div></body></html>"

    /**
     * Inicie el servidor de bucle invertido en el puerto especificado por redirigirUri.
     * @param redirectUri en forma de http://localhost:1455/auth/callback
     * @param pending Proceso de autorización actualmente esperando devolución de llamada (code resolver después de la verificación estatal);
     *   Conserve los parámetros para mantener la semántica del documento asociado con completeAuthorizationCodeFlow
     */
    fun start(redirectUri: String, @Suppress("UnusedParameter") pending: OAuthManager.PendingAuthCode): LoopbackServerHandle {
        val slash = redirectUri.removePrefix("http://")
        val hostEnd = slash.indexOf('/')
        val hostPort = if (hostEnd > 0) slash.take(hostEnd) else slash
        val port = hostPort.substringAfter(':', "80").toIntOrNull() ?: 80
        val serverSocket = ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))
        Logger.i(TAG, "Loopback Servidor de devolución de llamada iniciado: 127.0.0.1:$port")
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "oauth-loopback") }
        val closed = AtomicBoolean(false)
        executor.execute {
            try {
                // Solicitud única: la devolución de llamada de autorización solo ocurre una vez;accept bloqueado por stop() close socket interrumpir
                val socket: Socket = serverSocket.accept()
                socket.use { handle(it) }
            } catch (_: java.net.SocketException) {
                // stop() Cerrar interrupciones normales del socket
            } catch (t: Throwable) {
                Logger.e(TAG, "loopback Excepción del servidor de devolución de llamada", t)
            }
        }
        return LoopbackServerHandle(serverSocket, executor, closed)
    }

    // CyclomaticComplexMethod 15/15:Máquina de estados tres estados(error/Parámetros faltantes/state verificar)×Analizando la tolerancia a fallos,
    // La división extenderá el procesamiento del protocolo de devolución de llamada a múltiples funciones y estará exenta por punto de acuerdo con la convención del proyecto.
    @Suppress("CyclomaticComplexMethod")
    private fun handle(socket: Socket) {
        var ok = false
        try {
            val requestLine = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: ""
            val rawUri = requestLine.split(" ").getOrNull(1) ?: ""
            val query = rawUri.substringAfter('?', "")
            val params = query.split("&")
                .filter { it.isNotBlank() }
                .associate {
                    val idx = it.indexOf('=')
                    if (idx <= 0) {
                        it to ""
                    } else {
                        URLDecoder.decode(it.take(idx), "UTF-8") to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
                    }
                }
            val error = params["error"]
            val code = params["code"]
            val state = params["state"]
            ok = when {
                error != null -> {
                    Logger.w(TAG, "El servidor de autorización regresa error=$error")
                    false
                }
                code.isNullOrBlank() || state.isNullOrBlank() -> {
                    Logger.w(TAG, "Falta la devolución de llamada code/state")
                    false
                }
                // Reutilizar la verificación del estado existente + ruta de resolución diferida
                !OAuthManager.completeAuthorizationCodeFlow(code, state) -> {
                    Logger.w(TAG, "state Verificación fallida o ningún proceso pendiente")
                    false
                }
                else -> true
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "No se pudo analizar la devolución de llamada de bucle invertido", t)
        }
        try {
            val body = (if (ok) SUCCESS_HTML else FAILURE_HTML).toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 ${if (ok) 200 else 400} ${if (ok) "OK" else "Bad Request"}\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().use { out ->
                out.write(headers.toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "Error al escribir la respuesta: ${t.message}")
        }
    }
}

/** loopback identificador del servidor;stop() Idempotente (cerrar socket + cerrar grupo de subprocesos). */
internal class LoopbackServerHandle(
    private val serverSocket: ServerSocket,
    private val executor: java.util.concurrent.ExecutorService,
    private val closed: AtomicBoolean,
) {
    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        runCatching { executor.shutdownNow() }
        Logger.i("LoopbackCallbackServer", "Loopback El servidor de devolución de llamada se ha detenido")
    }
}
