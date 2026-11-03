package io.zer0.muse.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * P2-11: Almacenamiento seguro de credenciales,Basado en el almacén de claves de Android + Normal SharedPreferences(Cifrado y colocado en el disco.).
 *
 * con ordinario API Key(Existe en el flujo de SettingsRepository.providers)aislamiento físico,
 * OAuth access_token / refresh_token guardar aquí,Reducir el riesgo de fugas.
 *
 * darse cuenta:El proyecto no tiene dependencia androidx.security:security-crypto,Uso de cifrado directo de Keystore + SP ordinario para protección,
 *      con existente [io.zer0.muse.data.SecureKeyStore] El estilo sigue siendo consistente.
 *
 * Cifrado:AES-256-GCM,Las claves son administradas por Android Keystore(No exportable,Destruir al desinstalar)
 * almacenamiento:`"enc_v1:" + Base64(iv + ciphertext + authTag)` escribir SharedPreferences
 *
 * garantía de seguridad:
 *  - No generar texto sin formato del token en el registro(Solo genera la longitud/Primeros 4 caracteres para depurar)
 *  - La falla de cifrado no vuelve silenciosamente al texto sin formato(escribe abortado,Devuelto cuando la persona que llama lee null,El usuario necesita iniciar sesión nuevamente)
 *  - El error de descifrado regresa null(El usuario necesita iniciar sesión nuevamente),No lances excepciones para evitar fallos
 */
class SecureCredentialStore(context: Context) {

    private val masterKeyAlias: String = "muse_oauth_master_key"

    /** Instancia de SP cifrada(independiente de otros módulos SP,Implementar aislamiento físico). */
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Inicializar clave maestra(Android Keystore generado en,AES256-GCM). Devuelve el alias para que la persona que llama pueda observarlo fácilmente. */
    private fun ensureMasterKey(): String {
        getOrCreateKey()
        return masterKeyAlias
    }

    /** Almacenamiento cifrado OAuth token(providerId → TokenBundle). */
    suspend fun storeOAuthToken(providerId: String, bundle: TokenBundle) {
        if (providerId.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                ensureMasterKey()
                val json = AppJson.encodeToString(TokenBundle.serializer(), bundle)
                val encrypted = encrypt(json)
                prefs.edit().putString(providerId, encrypted).apply()
                // Mantener el índice de ID de proveedor(Conveniente para que listProviderIds no se repita sobre todos SP key)
                val ids = readIndex().toMutableSet()
                ids.add(providerId)
                writeIndex(ids)
                Logger.i(TAG, "OAuth token salvado: provider=${maskId(providerId)}, len=${json.length}")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // La falla del cifrado nunca volverá silenciosamente al texto sin formato;Pero no permita que el proceso OAuth falle,Grabar registro de errores
                Logger.w(TAG, "storeOAuthToken falló: ${truncateForLog(t.message)}")
            }
        }
    }

    /** Lea el token de OAuth. Devuelve nulo si no hay ningún registro o falla el descifrado. */
    suspend fun getOAuthToken(providerId: String): TokenBundle? {
        if (providerId.isBlank()) return null
        return withContext(Dispatchers.IO) {
            val stored = prefs.getString(providerId, null) ?: return@withContext null
            val json = if (stored.startsWith(PREFIX)) {
                decrypt(stored)
            } else {
                // Legacy values were written as plaintext. Migrate synchronously before
                // returning anything; never expose a legacy value if re-encryption fails.
                runCatching {
                    val migrated = encrypt(stored)
                    check(prefs.edit().putString(providerId, migrated).commit())
                    stored
                }.onFailure {
                    prefs.edit().remove(providerId).commit()
                    val ids = readIndex().toMutableSet()
                    ids.remove(providerId)
                    writeIndex(ids)
                    Logger.w(TAG, "legacy OAuth token Error de migración, eliminado: ${truncateForLog(it.message)}")
                }.getOrNull()
            } ?: return@withContext null
            runCatching {
                AppJson.decodeFromString(TokenBundle.serializer(), json)
            }.onFailure {
                Logger.w(TAG, "getOAuthToken Falló la deserialización: ${truncateForLog(it.message)}")
            }.getOrNull()
        }
    }

    /** Eliminar el token de OAuth. */
    suspend fun deleteOAuthToken(providerId: String) {
        if (providerId.isBlank()) return
        withContext(Dispatchers.IO) {
            prefs.edit().remove(providerId).apply()
            val ids = readIndex().toMutableSet()
            ids.remove(providerId)
            writeIndex(ids)
            Logger.i(TAG, "OAuth token Eliminado: provider=${maskId(providerId)}")
        }
    }

    /** Enumera todos los ID de proveedores de OAuth almacenados. */
    suspend fun listProviderIds(): List<String> = withContext(Dispatchers.IO) {
        readIndex()
    }

    // ── herramientas internas(Keystore Cifrado y descifrado + mantenimiento del índice SP) ─────────────────────────

    /** Obtener o crear una clave AES-256(Alojado por el almacén de claves de Android). */
    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(masterKeyAlias, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        val spec = KeyGenParameterSpec.Builder(
            masterKeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    /** Cifrar texto sin formato,Regresar `"enc_v1:" + Base64(iv + ciphertext + authTag)`. */
    private fun encrypt(plain: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // GCM Generado automáticamente 12B IV
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
    }

    /** Descifrar.legacy El lector migra primero el texto sin formato; esta función no devuelve el texto sin formato como credencial. */
    private fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return runCatching {
            val encoded = stored.removePrefix(PREFIX)
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return null
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val cipherBytes = combined.copyOfRange(IV_LENGTH, combined.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.onFailure {
            Logger.w(TAG, "decrypt falló: ${truncateForLog(it.message)}")
        }.getOrNull()
    }

    /** providerId índice(Evite recorrer todas las claves SP para determinar cuáles son OAuth token). */
    private fun readIndex(): List<String> {
        val raw = prefs.getString(KEY_INDEX, "") ?: ""
        return raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun writeIndex(ids: Set<String>) {
        prefs.edit().putString(KEY_INDEX, ids.joinToString(",")).apply()
    }

    /** Truncar mensajes de registro,Evite filtrar contenidos de tokens o explotar archivos de registro. */
    private fun truncateForLog(msg: String?): String {
        if (msg == null) return ""
        return if (msg.length > MAX_LOG_MSG_LENGTH) msg.substring(0, MAX_LOG_MSG_LENGTH) + "..." else msg
    }

    /** Seguridad de registro:providerId Solo genera los primeros 4 caracteres + longitud,Evite las inferencias correlacionales. */
    private fun maskId(id: String): String {
        return if (id.length <= 4) id else "${id.take(4)}***(${id.length})"
    }

    companion object {
        private const val TAG = "SecureCredentialStore"
        private const val PREFS_NAME = "muse_oauth_store"
        private const val KEY_INDEX = "__provider_ids__"
        private const val PREFIX = "enc_v1:"
        private const val IV_LENGTH = 12 // GCM 12 bytes recomendados IV
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val MAX_LOG_MSG_LENGTH = 200
        private val lock = Any()
    }
}

/**
 * OAuth token bundle(Proyección de respuesta de token estándar de OAuth 2.0).
 *
 * por [SecureCredentialStore] Almacenamiento cifrado, [OAuthManager] Después de iniciar sesión correctamente, escriba
 * en [OAuthManager.refreshTokenIfNeeded] Leer y actualizar.
 *
 * @param accessToken token de acceso(Bearer)
 * @param refreshToken token de actualización(Algunos proveedores no regresan,Disponible)
 * @param expiresAt tiempo de vencimiento(epoch millis,0 Indica que no tiene vencimiento/desconocido)
 * @param scope Alcance real de la autorización(Puede diferir de lo solicitado)
 */
@Serializable
data class TokenBundle(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long = 0, // epoch millis
    val scope: String = "",
    /** v2.x(Codex): OpenID Connect id_token(Contiene el reclamo chatgpt_account_id, requerido en el encabezado de la solicitud). */
    val idToken: String? = null,
)
