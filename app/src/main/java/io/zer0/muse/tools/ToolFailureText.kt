package io.zer0.muse.tools

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * v2.2.1: Redacción uniforme para información sobre fallos de herramientas.
 *
 * motivación:Hasta ahora, el mensaje de fallo de las herramientas de red sólo contenía una frase. `e.message`,A menudo se ven modelos"La conexión falló: timeout"
 * Descripciones tan vagas no pueden corregirse por sí solas. asistente unificado**Solicitar dirección**con**etapa de falla**(tiempo de espera/Resolución de nombres de dominio/TLS)
 * Escribir los resultados juntos,deja que el modelo(y registros)Puede localizar directamente el problema.
 *
 * Usuario:SkillBridge(http_get/http_post),SkillSearchToolsImpl(HTTP GET/POST Habilidades).
 * MCP Los comentarios de fallas para las llamadas están en línea en McpRegistry(Necesita estar adjunto serverId,redactado de manera diferente).
 */
internal object ToolFailureText {

    /**
     * Copia visible de combinación fallida de clases HTTP.
     *
     * @param prefix Nombre de la acción(Como por ejemplo "HTTP GET","http_post")
     * @param url dirección de destino(revelado tal como es,Fácil de modelar/Verificación de usuario)
     * @param cause excepción original;null Tratado como anomalía de red desconocida
     */
    fun httpFailure(prefix: String, url: String, cause: Throwable?): String = when (cause) {
        is SocketTimeoutException, is InterruptedIOException ->
            "$prefix tiempo de espera(conectar o leer): $url"
        is UnknownHostException ->
            "$prefix Falló la resolución del nombre de dominio: $url"
        is SSLException ->
            "$prefix TLS El apretón de manos falló: $url"
        null ->
            "$prefix Solicitud fallida: $url → Anomalía de red desconocida"
        else ->
            "$prefix Solicitud fallida: $url → ${cause.message ?: cause.javaClass.simpleName}"
    }
}
