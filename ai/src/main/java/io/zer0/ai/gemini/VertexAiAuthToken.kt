package io.zer0.ai.gemini

import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Phase 9.4 (M10): Google Vertex AI Autenticación de cuenta de servicio.
 *
 * Implementar estándares Google Service Account JWT → Access Token proceso de intercambio:
 *  1. estructura JWT(header + claims),claims Contiene iss/scope/aud/iat/exp
 *  2. Usar la clave privada de la cuenta de servicio(RSA)Firmar JWT con SHA256withRSA
 *  3. POST Consíguelo en https://oauth2.googleapis.com/token access_token
 *  4. caché token(expires_in Predeterminado 3600s),Actualizar por adelantado 60 segundos antes del vencimiento
 *
 * Formato de clave privada: PEM PKCS#8(El campo de clave privada en el archivo JSON de la cuenta de servicio)
 * ```
 * -----BEGIN PRIVATE KEY-----
 * MIIEvQ...
 * -----END PRIVATE KEY-----
 * ```
 *
 * Seguridad del hilo: [cachedToken] / [cachedExpiresAt] protegido con sincronizado,
 * Varias corrutinas pueden recuperar tokens simultáneamente sin solicitudes repetidas.(típico: ChatService.completeText + memory Tareas en segundo plano).
 *
 * v1.80 Reparación:
 *  - M-GEM5: exchangeToken Utilice try/catch en su lugar runCatching,rethrow CancellationException
 *  - M-GEM6: refreshAndGet Cambiar a singleflight(Sólo revisa el interior de la cerradura.,HTTP fuera de la cerradura)
 *  - M-GEM7: Bajar de categoría anterior cuando falla la actualización token;getValidToken El error arroja una excepción con motivo en lugar de regresar null
 *  - L-GEM1: Reutilice io.zer0.common.AppJson en lugar de una instancia Json de creación propia
 *  - M-VTX1: El constructor recibe información externa. OkHttpClient,Evite crear un cliente de creación propia que conduzca a la agrupación de conexiones./fuga de hilo
 *  - M-VTX2: verificar expiresInSec > 0,Prevenir respuestas anormales(0/numero negativo)Provoca actualización infinita
 *  - L-VTX1: parsePemPrivateKey compatibles PKCS#1(RSA PRIVATE KEY)Formato,Empaquetado automáticamente como PKCS#8
 *  - L-VTX2: Utilice java.util.Base64 en su lugar android.util.Base64(Facilita las pruebas unitarias de JVM)
 *
 * Referencia: https://developers.google.com/identity/protocols/oauth2/service-account#jwtset
 * Escrito de forma independiente(Según el acuerdo de documentación oficial de Google.),Apache 2.0.
 */
class VertexAiAuthToken(
    private val serviceAccountEmail: String,
    privateKeyPem: String,
    /**
     * v1.80 (M-VTX1): entrante externo OkHttpClient(Recomendado ProviderHttpSupport.sharedHttpClient),
     * Evite que cada instancia de VertexAiAuthToken cree su propio cliente y provoque un agrupamiento de conexiones/Fuga de hilo.
     * La persona que llama puede cerrar el cliente de manera uniforme cuando se destruye el Proveedor.
     */
    private val client: OkHttpClient,
    private val scope: String = "https://www.googleapis.com/auth/cloud-platform",
) {
    /** Clave privada RSA analizada(java.security.PrivateKey). */
    private val privateKey: java.security.PrivateKey = parsePemPrivateKey(privateKeyPem)

    /** Token de acceso en caché.null Indica no tomado/Venció. */
    @Volatile
    private var cachedToken: String? = null

    /** Marca de tiempo de vencimiento del token de caché(milisegundos). */
    @Volatile
    private var cachedExpiresAt: Long = 0L

    /**
     * Obtenga un token de acceso válido. Si el caché es válido, regrese directamente,De lo contrario, actualice sincrónicamente.
     * Llamado en corrutina([withContext] Cortar a IO).
     *
     * M-GEM7: Se produce una excepción con un motivo cuando falla la actualización y el token anterior ha caducado por completo.,Ya no devuelve nulo.
     */
    suspend fun getValidToken(): String {
        val cached = cachedToken
        val now = System.currentTimeMillis()
        // Actualizar 60 por adelantado,Evite que las solicitudes de límites caduquen token
        if (cached != null && now < cachedExpiresAt - 60_000L) {
            return cached
        }
        return refreshAndGet()
    }

    /**
     * Actualizar token sincrónicamente.
     *
     * M-GEM6: singleflight modo — synchronized El bloqueo solo verifica si otras corrutinas lo han actualizado.,
     * HTTP La llamada se ejecuta fuera de la cerradura.,Evite bloquear otras corrutinas debido al bloqueo y otras redes.
     * Se pueden emitir solicitudes duplicadas cuando se ejecutan varias rutinas al mismo tiempo.,Pero no hay punto muerto,Y después del éxito, la memoria caché de reescritura en el candado será visible de manera uniforme.
     */
    private suspend fun refreshAndGet(): String = withContext(Dispatchers.IO) {
        // dentro de la cerradura:doble verificación,Es posible que la rutina anterior haya actualizado la rutina que espera el bloqueo después de adquirir el bloqueo.
        synchronized(this@VertexAiAuthToken) {
            val now = System.currentTimeMillis()
            cachedToken?.takeIf { now < cachedExpiresAt - 60_000L }?.let { return@withContext it }
        }
        // fuera de la cerradura:Construya JWT y realice intercambio HTTP(Permitir concurrencia,Evite el bloqueo y otras redes)
        val jwt = buildSignedJwt()
        val token = exchangeToken(jwt)
        if (token != null) {
            // M-VTX2: verificar expiresInSec > 0,Respuesta de excepción(0/numero negativo)Usar valor predeterminado para bajar de categoría 3600s,Evite la actualización infinita
            val effectiveExpiresIn = if (token.expiresInSec > 0L) token.expiresInSec else 3600L
            val now = System.currentTimeMillis()
            synchronized(this@VertexAiAuthToken) {
                cachedToken = token.accessToken
                cachedExpiresAt = now + effectiveExpiresIn * 1000L
            }
            Logger.d(TAG, "Vertex AI token Actualizar exitosamente,Periodo de validez ${effectiveExpiresIn}s")
            return@withContext token.accessToken
        }
        // M-GEM7: Error al actualizar,Si el token antiguo no ha caducado por completo,Rebaja usada token
        val stale = cachedToken
        val now = System.currentTimeMillis()
        if (stale != null && now < cachedExpiresAt) {
            Logger.w(TAG, "Token Error al actualizar,Rebaja usada token(restante ${cachedExpiresAt - now}ms)")
            return@withContext stale
        }
        // M-GEM7: Ninguno disponible token,Lanzar excepción con razón (v1.0.27 Phase 5-A: Usar en su lugar ProviderException)
        throw ProviderException(
            ProviderError.AuthError(displayMessage = "Vertex AI token Error al actualizar y no hay archivos antiguos disponibles token"),
        )
    }

    /**
     * Construya y firme el JWT.
     * - Header: {"alg":"RS256","typ":"JWT"}
     * - Claims: iss/scope/aud/iat/exp(1 Horas de validez)
     */
    private fun buildSignedJwt(): String {
        val now = System.currentTimeMillis() / 1000L // Segundo nivel
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val claims = buildString {
            append('{')
            append("\"iss\":\"").append(serviceAccountEmail).append("\",")
            append("\"scope\":\"").append(scope).append("\",")
            append("\"aud\":\"").append(TOKEN_ENDPOINT).append("\",")
            append("\"iat\":").append(now).append(',')
            append("\"exp\":").append(now + 3600L)
            append('}')
        }
        val headerB64 = base64UrlEncode(header.toByteArray(Charsets.UTF_8))
        val claimsB64 = base64UrlEncode(claims.toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$claimsB64"

        // SHA256withRSA firma
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(signingInput.toByteArray(Charsets.UTF_8))
        val signatureBytes = signature.sign()
        val signatureB64 = base64UrlEncode(signatureBytes)

        return "$signingInput.$signatureB64"
    }

    /**
     * Cambie JWT por access_token.
     * POST application/x-www-form-urlencoded: grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion={jwt}
     *
     * M-GEM5: Utilice try/catch en su lugar runCatching,especializado rethrow CancellationException,
     * Evite tragar señales de cancelación de rutina.
     */
    private fun exchangeToken(jwt: String): TokenResponse? {
        val formBody = buildString {
            append("grant_type=").append(java.net.URLEncoder.encode(JWT_GRANT_TYPE, "UTF-8"))
            append("&assertion=").append(java.net.URLEncoder.encode(jwt, "UTF-8"))
        }
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .post(formBody.toRequestBody(X_WWW_FORM_URLENCODED))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    Logger.w(
                        TAG,
                        "Token Error en el intercambio HTTP ${resp.code}: " +
                            (io.zer0.ai.core.ProviderError.safeErrorDetail(body) ?: "<empty>"),
                    )
                    return@use null
                }
                AppJson.decodeFromString<TokenResponse>(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Token Excepción de cambio: ${e.message}")
            null
        }
    }

    /**
     * PEM clave privada → java.security.PrivateKey.
     *
     * v1.80 (L-VTX1): compatibles PKCS#1(`BEGIN RSA PRIVATE KEY`)formato.
     *  PKCS#1 Secuencia ASN.1 que contiene solo clave privada RSA,KeyFactory necesitar PKCS8EncodedKeySpec,
     *  Cuando se detecta aquí el encabezado PKCS#1, se envuelve con la capa de envoltura estándar RSA PrivateKeyInfo y luego se analiza.
     *  Estructura de capas de embalaje(ASN.1 DER):
     *    SEQUENCE {
     *      INTEGER 0                          -- versión
     *      SEQUENCE { OID rsaEncryption, NULL } -- identificador de algoritmo
     *      OCTET STRING <PKCS#1 byte original>        -- privateKey
     *    }
     *  El prefijo 22 bytes es fijo.(RSA + PKCS#8 cabeza),El último byte es la etiqueta de longitud OCTET STRING.
     *
     * v1.80 (L-VTX2): Utilice java.util.Base64 en lugar de android.util.Base64.
     */
    private fun parsePemPrivateKey(pem: String): java.security.PrivateKey {
        val isPkcs1 = pem.contains("-----BEGIN RSA PRIVATE KEY-----")
        // Eliminar encabezados, colas y saltos de línea PEM,Obtener cadena base64
        val base64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.getMimeDecoder().decode(base64)
        val pkcs8Bytes = if (isPkcs1) wrapPkcs1ToPkcs8(keyBytes) else keyBytes
        val spec = PKCS8EncodedKeySpec(pkcs8Bytes)
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    /**
     * v1.80 (L-VTX1): Envuelva los bytes de clave privada RSA de PKCS#1 en PKCS#8 PrivateKeyInfo.
     * Encabezado fijo de 26 bytes totales:SEQUENCE(4) + version(3) + AlgorithmIdentifier{rsaEncryption,NULL}(13)
     * + OCTET STRING tag/length(4),Finalmente agregue el byte PKCS#1 original.
     */
    private fun wrapPkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
        // RSA Encryption OID + NULL params de AlgorithmIdentifier,Frente con SECUENCIA y version
        // Prefijo completo de 26 bytes:
        //   30 82 xx xx                 SEQUENCE (longitud total,Completar más tarde)
        //   02 01 00                    INTEGER 0 (versión)
        //   30 0d 06 09 2a 86 48 86 f7 0d 01 01 01 00  SEQUENCE { rsaEncryption, NULL }
        //   04 82 xx xx                 OCTET STRING (paquete PKCS#1)
        val prefix = byteArrayOf(
            0x30.toByte(), 0x82.toByte(), 0x00, 0x00, // SEQUENCE,Completa la longitud
            0x02, 0x01, 0x00, // version = 0
            0x30, 0x0d, // SEQUENCE(AlgorithmIdentifier)
            0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, // OID rsaEncryption
            0x05, 0x00, // NULL
            0x04, 0x82.toByte(), 0x00, 0x00, // OCTET STRING,Completa la longitud
        )
        val totalLen = prefix.size + pkcs1.size
        // SEQUENCE longitud(totalLen - 4,Ocupa 2 bytes big endian)
        val seqLen = totalLen - 4
        prefix[2] = ((seqLen shr 8) and 0xFF).toByte()
        prefix[3] = (seqLen and 0xFF).toByte()
        // OCTET STRING longitud(pkcs1.size,Ocupa 2 bytes big endian,La posición es el byte 2/1 desde el final del prefijo.)
        prefix[prefix.size - 2] = ((pkcs1.size shr 8) and 0xFF).toByte()
        prefix[prefix.size - 1] = (pkcs1.size and 0xFF).toByte()
        return prefix + pkcs1
    }

    /** JWT Codificación privada Base64URL(Ninguno padding).v1.80 (L-VTX2): Utilice java.util.Base64 en su lugar. */
    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    @Serializable
    private data class TokenResponse(
        @kotlinx.serialization.SerialName("access_token") val accessToken: String = "",
        @kotlinx.serialization.SerialName("expires_in") val expiresInSec: Long = 3600L,
        @kotlinx.serialization.SerialName("token_type") val tokenType: String = "Bearer",
    )

    private companion object {
        const val TAG = "VertexAiAuthToken"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val JWT_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        val X_WWW_FORM_URLENCODED = "application/x-www-form-urlencoded".toMediaType()
    }
}
