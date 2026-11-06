package io.zer0.muse.web

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpHeaders
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.zer0.common.Logger

/** JWT El asunto: el perfil emisor lo compara con el perfil verificador. */
internal const val HOST_AUTH_SUBJECT = "muse-web"

/** subject nombre del reclamo. */
internal const val HOST_AUTH_SUBJECT_CLAIM = "sub"

/** El nombre de la cookie emitido después de iniciar sesión será transferido automáticamente por el navegador. */
internal const val HOST_AUTH_COOKIE = "token"

/**
 * Host Mode Puerta de credenciales JWT.
 *
 * Utilice ktor-server-auth que viene con él `bearer` provider + `com.auth0.jwt` Verificación manual sin ktor
 * `jwt()` provider: ¿Qué módulo obtiene credenciales?/Referencia sobre ruta fallida `com.auth0.jwk.JwkException`, mientras
 * `jwks-rsa` ha sido excluido (extrae guayaba, lo que entra en conflicto con la capacidad de escucha futura de AndroidX).
 * En consecuencia, el paso de obtención del bono es `NoClassDefFoundError` —— Todas las rutas protegidas pasan a ser 500 en lugar de 401,
 * `/ws` El apretón de manos se expresa como "no se puede conectar". Solo utilizamos firmas simétricas HMAC-SHA256, no se requiere rotación JWKS/RSA,
 * Sólo hay un camino claro hacia la autoverificación.
 *
 * La semántica es consistente con el original: sin certificado, firma inconsistente, caducada,subject No, siempre 401.
 */
internal fun AuthenticationConfig.installHostAuthProvider(
    providerName: String,
    realm: String,
    algorithm: Algorithm,
    cookieName: String = HOST_AUTH_COOKIE,
) {
    val verifier = JWT.require(algorithm).build()
    bearer(providerName) {
        this.realm = realm
        // Se aceptan ambos métodos de transporte:Authorization Primero el encabezado, seguido de la cookie httpOnly (que el navegador genera automáticamente).
        authHeader { call -> hostAuthHeader(call, cookieName) }
        authenticate { credential -> verifyHostToken(credential.token, verifier) }
    }
}

/**
 * desde el encabezado Autorización o `token` Cookie Saque las credenciales del portador.
 *
 * Si el encabezado se puede analizar, úselo; si no es una forma de portador, simplemente ignórela y entréguela al proveedor para el 401.
 */
internal fun hostAuthHeader(call: ApplicationCall, cookieName: String = HOST_AUTH_COOKIE): HttpAuthHeader? {
    val raw = call.request.headers[HttpHeaders.Authorization]
    if (raw != null) {
        (parseAuthorizationHeader(raw) as? HttpAuthHeader.Single)?.let { return it }
    }
    return call.request.cookies[cookieName]
        ?.takeIf { it.isNotBlank() }
        ?.let { HttpAuthHeader.Single(AuthScheme.Bearer, it) }
}

/**
 * Verificar un token.
 *
 * @return Devolver el capital cuando se apruebe; caducado, la firma no coincide, el formato es ilegal,subject Si no, devuelve nulo.
 *   El proveedor devuelve 401 de manera uniforme y no filtra excepciones en 500. Utilice el valor de retorno [Any] La recepción es seguida por
 *   `bearer` provider de `authenticate` Alineación de firma ([UserIdPrincipal] es el principal del lado de Java).
 */
internal fun verifyHostToken(token: String?, verifier: JWTVerifier, subject: String = HOST_AUTH_SUBJECT): Any? {
    if (token.isNullOrBlank()) return null
    val decoded = runCatching { verifier.verify(token) }.getOrElse { error ->
        Logger.d(TAG, "JWT La verificación falló: ${error.javaClass.simpleName}")
        return null
    }
    return if (decoded.getClaim(HOST_AUTH_SUBJECT_CLAIM).asString() == subject) UserIdPrincipal(subject) else null
}

private const val TAG = "HostAuth"
