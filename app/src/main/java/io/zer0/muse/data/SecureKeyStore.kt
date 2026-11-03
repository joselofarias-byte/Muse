package io.zer0.muse.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v1.53-A2: cuerda sensible(apiKey / OAuth token)Herramientas de almacenamiento cifrado.
 *
 * Cifrado simétrico AES-256-GCM basado en Android Keystore,Se utiliza para cifrar campos confidenciales en el almacén de datos.
 *
 * - Las claves se gestionan a nivel de hardware mediante Android Keystore(No exportable,aislamiento de procesos,Destruir al desinstalar)
 * - Formato del resultado del cifrado:`"enc_v1:" + Base64(iv(12B) + ciphertext + authTag(16B))`
 * - Al descifrar:Ninguno `enc_v1:` El prefijo se trata como datos antiguos de texto sin formato.,Transmisión transparente tal como está.(Compatible con la migración)
 * - Generar clave automáticamente en la primera llamada(alias = [ALIAS])
 * - El cifrado y el descifrado son operaciones de carpeta de IO/Keystore,La función se declara suspendida y se utiliza. withContext(Dispatchers.IO) Obligado a abandonar el hilo principal.
 *
 * Estrategia de migración:
 * - Al leer la apiKey almacenada en texto sin formato en la versión anterior, decrypt transmite de forma transparente el texto sin formato al leer.,cifrar cifra automáticamente la próxima vez que escriba
 * - El usuario no lo sabe,No se requieren pasos de migración explícitos
 * - Keystore Descarga de claves/Perdido después de borrar datos,Los datos cifrados no se pueden descifrar(Fallo explícito,ver [SecureKeyCipher.decryptOrNull]),
 *   El usuario debe volver a ingresar apiKey
 */
/** R-TEST-03: Abstracciones de cifrado y descifrado inyectables,Es conveniente que las pruebas únicas de JVM utilicen memoria para reemplazar el almacén de claves de Android. */
interface SecureKeyCipher {
    suspend fun encrypt(plain: String): String

    /**
     * Descifrar(Entrada compatible). El error de descifrado devuelve una cadena vacía — con"valor nulo legal/Transmisión transparente de texto plano antiguo."Indistinguible.
     * Se utiliza cuando se requiere una diferenciación explícita de fallas. [decryptOrNull].
     */
    suspend fun decrypt(stored: String): String

    /**
     * Phase 3 (Fiabilidad P1): Descifrar entrada de error explícito — falló(Clave no válida/Corrupción de datos/Formato ilegal)Regresar null,
     * Texto claro devuelto exitosamente;Nulo y ninguno `enc_v1:` El antiguo texto plano del prefijo se devuelve sin cambios.(Compatible con la migración).
     *
     * Implementación predeterminada de la delegación [decrypt](fracaso indistinguible,Sólo funciona con implementaciones en memoria que no fallan);
     * Android Keystore La implementación se ha anulado al registro explícito de error + motivo.
     */
    suspend fun decryptOrNull(stored: String): String? = decrypt(stored)
}

/**
 * v1.53-A2: cuerda sensible(apiKey / OAuth token)Herramientas de almacenamiento cifrado.
 *
 * Usado por defecto [AndroidKeyStoreCipher];JVM Las pruebas se pueden inyectar en la memoria para implementar cadenas de llamadas de verificación.
 *
 * Phase 3 (P1): El error de descifrado ya no se expresa como una cadena vacía — [decryptOrNull] Devuelve nulo y registra el motivo,
 * [decrypt] Preservar la semántica de cadenas vacías para los llamantes existentes(B-9 Protectores de contraseña de respaldo, varios Config.decrypted() espera)Compatible.
 * Llamada a guardia lateral marcada:Estas personas que llaman tratan los valores nulos como"No configurado/Necesito volver a entrar",Comportamiento seguro bajo semántica de cadenas vacías;
 * Necesidad de distinguir"Falló el descifrado"con"valor nulo legal"El nuevo código para debería usar en su lugar [decryptOrNull].
 */
object SecureKeyStore : SecureKeyCipher {
    /** Implementación de cifrado y descifrado actualmente efectiva,Almacén de claves de Android predeterminado. */
    @Volatile
    var delegate: SecureKeyCipher = AndroidKeyStoreCipher

    override suspend fun encrypt(plain: String): String = delegate.encrypt(plain)

    /** Entrada compatible:El error de descifrado devuelve una cadena vacía(ver [SecureKeyCipher.decrypt] Descripción). */
    override suspend fun decrypt(stored: String): String {
        val value = delegate.decryptOrNull(stored)
        if (value == null) {
            // Fallo explícito:No más silencio —— Deje la cadena vacía para que sea compatible con las protecciones existentes.,Pero el registro falló.
            Logger.w(TAG, "decrypt failed for stored value, legacy decrypt returns empty")
            return ""
        }
        return value
    }

    /**
     * Phase 3 (P1): Descifrar entrada de error explícito — Retorno del fracaso null(El motivo está registrado por la implementación.).
     * Nulo y ninguno `enc_v1:` El antiguo texto sin formato del prefijo se devuelve sin cambios.
     */
    override suspend fun decryptOrNull(stored: String): String? = delegate.decryptOrNull(stored)

    private const val TAG = "SecureKeyStore"
}

private object AndroidKeyStoreCipher : SecureKeyCipher {
    private const val TAG = "SecureKeyStore"
    private const val ALIAS = "muse_api_key_master"
    private const val PREFIX = "enc_v1:"
    private const val IV_LENGTH = 12 // GCM 12 bytes recomendados IV
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val MAX_LOG_MSG_LENGTH = 500

    // M-SK1: Proteger la sección crítica getOrCreateKey,Evite verificar y luego crear condiciones de carrera TOCTOU que conduzcan a la generación repetida de claves
    private val lock = Any()

    /**
     * Obtener o crear una clave AES-256(Alojado por el almacén de claves de Android).
     * Primera llamada para generar,Las llamadas posteriores se leen desde Keystore.
     *
     * M-SK1: Proteja las secciones críticas de verificación y luego creación con sincronizado,Evite llamadas iniciales simultáneas
     * Ambos hilos no observan ninguna clave y simultáneamente generateKey(Este último anulará al primero.,Los datos cifrados no se pueden descifrar).
     */
    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /**
     * Cifrar texto sin formato,Regresar `"enc_v1:" + Base64(iv + ciphertext)`.
     *
     * - La cadena vacía se devuelve sin cambios.(No cifrar valores nulos)
     * - cifrado(con prefijo)La cadena se devuelve tal cual(Evite el cifrado repetido)
     * - Error de cifrado [KeyStoreException],Atendido por la persona que llama(H-SK1:Nunca volver a texto claro,Evite violaciones silenciosas de las garantías de seguridad)
     *
     * M-SK2: Keystore para operaciones de carpeta/disco,Declararlo como suspender y usar. withContext(Dispatchers.IO) Forzado a salir del hilo principal.
     */
    @Suppress("TooGenericExceptionCaught") // H-SK1: Embalaje unificado para todos los fallos.,Deshabilitar la reversión de texto claro
    override suspend fun encrypt(plain: String): String = withContext(Dispatchers.IO) {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return@withContext plain
        try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv // GCM Generado automáticamente 12B IV
            val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // iv + cipherBytes(authTag añadido al final del texto cifrado,GCM normativo)
            PREFIX + Base64.encodeToString(iv + cipherBytes, Base64.NO_WRAP)
        } catch (e: CancellationException) {
            // L4: suspend Debe ser relanzado en contexto. CancellationException,De lo contrario, se violará la semántica de cancelación de rutinas.
            throw e
        } catch (t: Exception) {
            // H-SK1: La falla del cifrado nunca volverá silenciosamente al texto sin formato(De lo contrario, las credenciales confidenciales se almacenarán en texto sin cifrar.),El lanzamiento de excepciones lo maneja la persona que llama.
            Logger.w(TAG, "encrypt failed: ${truncateForLog(t.message)}")
            throw KeyStoreException("SecureKeyStore encrypt failed: ${truncateForLog(t.message)}", t)
        }
    }

    /**
     * Descifrar(Entrada de error explícita).
     *
     * - La cadena vacía se devuelve sin cambios.
     * - Ninguno `enc_v1:` El prefijo se trata como datos antiguos de texto sin formato.,Transmisión transparente tal como está.(Compatible con la migración)
     * - Falló el descifrado(Clave no válida/Corrupción de datos/Formato ilegal)Devuelve nulo y registra el motivo,Nunca devuelva una cadena vacía para pretender tener éxito;
     *   Los llamadores antiguos que requieren semántica de cadena vacía van [decrypt]
     *
     * M-SK2: Keystore para operaciones de carpeta/disco,Declararlo como suspender y usar. withContext(Dispatchers.IO) Forzado a salir del hilo principal.
     */
    override suspend fun decryptOrNull(stored: String): String? = withContext(Dispatchers.IO) {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return@withContext stored
        // L5: Utilice resultOf en su lugar runCatching(Personalizar Result API,Lanzamiento correcto CancellationException)
        resultOf {
            val encoded = stored.removePrefix(PREFIX)
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return@resultOf null
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val cipherBytes = combined.copyOfRange(IV_LENGTH, combined.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.onError { msg, _ ->
            Logger.w(TAG, "decrypt failed, returning null: ${truncateForLog(msg)}")
        }.getOrNull()
    }

    /**
     * Entrada compatible:El error de descifrado devuelve una cadena vacía(con"valor nulo legal"indistinguible).
     * por [decryptOrNull] derivado,El comportamiento es consistente con versiones anteriores.,Asegúrese de que no sea necesario modificar los llamantes existentes.
     */
    override suspend fun decrypt(stored: String): String = decryptOrNull(stored) ?: ""

    /** Truncar mensajes de registro demasiado largos,Evite mensajes de excepción(puede contener stacktrace)Explotar archivos de registro. */
    private fun truncateForLog(msg: String?): String {
        if (msg == null) return ""
        return if (msg.length > MAX_LOG_MSG_LENGTH) msg.substring(0, MAX_LOG_MSG_LENGTH) + "..." else msg
    }
}
