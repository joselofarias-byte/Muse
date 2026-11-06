package io.zer0.muse.web

import io.zer0.muse.data.SecureKeyStore
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/**
 * Phase 8.11: Configuración del servidor web integrado.
 *
 * - [enabled]: Ya sea para iniciar el servidor web; la configuración se aplicará inmediatamente cuando la página de configuración la guarde y se restaurará automáticamente cuando el proceso se inicie en frío.
 * - [port]: puerto de escucha(Predeterminado 8765,Evitar conflictos con servicios comunes)
 * - [password]: Contraseña de inicio de sesión(Una cadena vacía significa que se genera una contraseña aleatoria en el primer arranque)
 * - [pin]: 6 dígitos PIN,Web Al acceder al terminal por primera vez es necesario introducir(Una cadena vacía indica que se genera en el primer inicio)
 *
 * persistencia: SettingsRepository DataStore,JSON Depósito y retiro completos.
 * seguro: R-SEC-03 Iniciar JWT usando una clave de firma aleatoria independiente [jwtSecret],Las contraseñas de los usuarios ya no se reutilizan.
 * PIN Emitido después de pasar la verificación. JWT,Reutilice el mismo sistema de autenticación que el inicio de sesión con contraseña.
 */
@Serializable
data class WebServerConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    val password: String = "",
    val pin: String = "",
    /** R-SEC-03: Clave de firma JWT independiente(32 Valor aleatorio de bytes,Separado de la contraseña del usuario). */
    val jwtSecret: String = "",
    /** R-SEC-03: Si se debe permitir el acceso a LAN;false Cuando se vincula solo 127.0.0.1 y solo origen nativo CORS. */
    val allowLan: Boolean = false,
    /**
     * v2.x: Ya sea para habilitar HTTPS(certificado autofirmado).
     * Predeterminado false,Mantener el comportamiento HTTP existente sin cambios;Cuando está habilitado, lo configura el motor Netty. sslConnector,
     * WebSocket Caminar sincrónicamente wss,Cookie secure=true.
     */
    val httpsEnabled: Boolean = false,
) {
    /**
     * H8: La contraseña/PIN de retorno está encriptado(ir [SecureKeyStore.encrypt])copia de,
     * Por llamar antes que la perseverancia. Los valores nulos se dejan como están.(No cifrar valores nulos).
     * prensa [io.zer0.muse.data.ProxyConfig.encrypted] modo.
     */
    suspend fun encrypted(): WebServerConfig = copy(
        password = SecureKeyStore.encrypt(password),
        pin = SecureKeyStore.encrypt(pin),
        jwtSecret = SecureKeyStore.encrypt(jwtSecret),
    )

    /**
     * H8: Devolver contraseña/PIN descifrado(ir [SecureKeyStore.decrypt])copia de,
     * Se llama después de leer desde la capa de persistencia. El antiguo texto sin formato se transmite de forma transparente mediante descifrado.(compatibles).
     * prensa [io.zer0.muse.data.ProxyConfig.decrypted] modo.
     */
    suspend fun decrypted(): WebServerConfig = copy(
        password = SecureKeyStore.decrypt(password),
        pin = SecureKeyStore.decrypt(pin),
        jwtSecret = SecureKeyStore.decrypt(jwtSecret),
    )

    companion object {
        /** Puerto predeterminado(Evite puertos de desarrollo comunes como 8080/3000). */
        const val DEFAULT_PORT = 8765

        /** Conjunto de caracteres de contraseña aleatorios(Eliminar caracteres confusos 0/O/1/l/I). */
        private val PASSWORD_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789".toCharArray()

        /** Generar contraseña aleatoria de 8 dígitos(Se utiliza al activar por primera vez.).H6: Utilice SecureRandom en su lugar Math.random(). */

        /** R-SEC-03: Genere una clave de firma JWT aleatoria de 32 bytes(Base64 URL Seguro sin relleno). */
        fun generateRandomJwtSecret(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
        fun generateRandomPassword(length: Int = 8): String {
            val sb = StringBuilder(length)
            val random = SecureRandom()
            repeat(length) {
                sb.append(PASSWORD_CHARS[random.nextInt(PASSWORD_CHARS.size)])
            }
            return sb.toString()
        }

        /**
         * Generar 6 dígitos PIN(primer lugar 1-9,Evite los ceros a la izquierda que pueden causar confusión entre la visualización y la entrada.).
         * H7: Utilice SecureRandom en su lugar Math.random(),Eliminar el sesgo de truncamiento(L3).
         *
         * [random] Usado por defecto [SecureRandom];Las semillas fijas se pueden inyectar en una sola prueba. [java.util.Random] Comportamiento de bloqueo.
         */
        fun generateRandomPin(random: java.util.Random = SecureRandom()): String {
            val sb = StringBuilder(PIN_LENGTH)
            // primer lugar 1-9,evitar los ceros a la izquierda
            sb.append(1 + random.nextInt(9))
            repeat(PIN_LENGTH - 1) {
                sb.append(random.nextInt(10))
            }
            return sb.toString()
        }

        /** PIN Longitud fija. */
        const val PIN_LENGTH = 6
    }
}
