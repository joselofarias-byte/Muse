package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Clave de autor nativa: un par de claves P-256 que se encuentran en el dispositivo del usuario y que se utilizan para firmar complementos redactados por el Asistente.
 *
 * Límite de seguridad:
 *  - Las claves privadas sólo se guardan en [Context.noBackupFilesDir](No ingrese la copia de seguridad de Android, no escriba el paquete de complemento,
 *    (No escriba en el directorio del proyecto), utilice el estándar PKCS#8 Base64 para guardar la clave pública en X.509 SubjectPublicKeyInfo
 *    El Base64 estándar se guarda junto; el contenido de la clave privada no está expuesto al mundo exterior.
 *  - Sólo firma cubre [PluginSecurityGate.signaturePayload] Definición de contenido, algoritmo de firma y sesión.
 *    El algoritmo de verificación es consistente (`SHA256withECDSA`,DER codificación).
 *  - La clave solo se genera una vez, en el primer uso, en el mismo dispositivo [publisherId]/clave pública/Las huellas dactilares permanecen estables;
 *    Si falla la lectura o la generación, se devolverá un error legible, no se lanzará ninguna excepción a la capa superior y no se cambiará ninguna clave nueva de forma silenciosa.
 *    (Cambiar la clave no coincidirá por completo con la raíz de confianza existente y los complementos firmados).
 *  - Esta clase sólo proporciona claves; "Si se requiere confirmación del usuario" es responsable de la ruta de habilitación de firma de PluginManager.
 *    Esta clase no activa ninguna raíz de escritura de confianza por sí sola.
 */
class LocalAuthorKeyStore internal constructor(
    private val storageFile: File,
) {

    /** Android Un almacén de claves de autor privado y sin respaldo que se utiliza en tiempo de ejecución. */
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, KEY_FILE_NAME),
    )

    /** ID de emisor fijo del autor nativo; en la misma raíz del espacio de nombres de confianza que el emisor del mercado externo. */
    val publisherId: String get() = PUBLISHER_ID

    /**
     * Clave pública del autor nativo (X.509 SPKI Línea única estándar Base64).
     *
     * La primera llamada genera y persiste un par de claves; a partir de entonces siempre se reutiliza el mismo.
     */
    fun publicKeyBase64(): Result<String> = material().map { it.publicKeyBase64 }

    /** La huella digital SHA-256 (hexadecimal en minúscula) de la clave pública del autor nativo, utilizada para comparar con el registro de registro del complemento. */
    fun fingerprint(): Result<String> = material().map { it.fingerprint }

    /**
     * Firmar con clave privada de autor nativo [payload].
     *
     * Regresar `SHA256withECDSA` firma DER; la persona que llama es responsable de firmar [PluginSecurityGate.signaturePayload]
     * Ensamble la carga útil y ajuste la firma en el manifiesto.
     */
    fun sign(payload: ByteArray): Result<ByteArray> {
        val material = material().getOrElse { error -> return Result.failure(error) }
        return try {
            val signer = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM)
            signer.initSign(material.privateKey)
            signer.update(payload)
            Result.success(signer.sign())
        } catch (error: Exception) {
            Logger.e(TAG, "Error al firmar la clave del autor nativo", error)
            Result.failure(IllegalStateException("Error al firmar la clave del autor nativo: ${error.message}", error))
        }
    }

    @Serializable
    private data class StoredAuthorKey(
        val publisherId: String,
        /** PKCS#8 Codificación estándar Base64 (una sola línea). */
        val privateKeyPkcs8: String,
        /** X.509 SubjectPublicKeyInfo Codificación estándar Base64 (una sola línea). */
        val publicKeyX509: String,
    )

    private class AuthorKeyMaterial(
        val privateKey: PrivateKey,
        val publicKeyBase64: String,
        val fingerprint: String,
    )

    private val lock = Any()

    @Volatile
    private var cached: AuthorKeyMaterial? = null

    private fun material(): Result<AuthorKeyMaterial> = synchronized(lock) {
        cached?.let { return Result.success(it) }
        val loaded = if (storageFile.exists()) {
            readMaterial().getOrElse { error -> return Result.failure(error) }
        } else {
            val generated = generateMaterial().getOrElse { error -> return Result.failure(error) }
            try {
                persist(generated)
            } catch (error: Exception) {
                Logger.e(TAG, "Error al escribir la clave del autor nativo", error)
                return Result.failure(
                    IllegalStateException("No se puede guardar la clave de autor nativa: ${error.message ?: "Error de escritura"}", error),
                )
            }
            generated
        }
        cached = loaded
        Result.success(loaded)
    }

    private fun readMaterial(): Result<AuthorKeyMaterial> = try {
        val stored = AppJson.decodeFromString<StoredAuthorKey>(storageFile.readText())
        require(stored.publisherId == PUBLISHER_ID) { "La clave del autor y el emisor no coinciden" }
        val privateKeyBytes = decodeBase64(stored.privateKeyPkcs8, "clave privada")
        val publicKeyBytes = decodeBase64(stored.publicKeyX509, "clave pública")
        val privateKey = KeyFactory.getInstance(PluginSecurityGate.KEY_ALGORITHM)
            .generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
        require(privateKey is ECPrivateKey) { "La clave privada del autor nativo no es una clave privada EC" }
        val publicKey = KeyFactory.getInstance(PluginSecurityGate.KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        require(publicKey is ECPublicKey) { "La clave pública del autor nativo no es la clave pública EC" }
        require(privateKey.params.curve.field.fieldSize == P256_FIELD_SIZE) { "La clave privada del autor nativo no es P-256" }
        require(publicKey.params.curve.field.fieldSize == P256_FIELD_SIZE) { "La clave pública del autor nativo no es P-256" }
        // Verifique que el par de claves colocadas en el disco coincidan entre sí: corrupción de archivos/falla cerrada cuando se reemplaza,
        // En lugar de firmar con una clave que no puede pasar la raíz de la verificación de confianza.
        val probe = ByteArray(32) { index -> index.toByte() }
        val signature = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(probe)
            sign()
        }
        val matches = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(probe)
            verify(signature)
        }
        require(matches) { "La clave pública del autor nativo no coincide con la clave privada" }
        Result.success(
            AuthorKeyMaterial(
                privateKey = privateKey,
                publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded),
                fingerprint = PluginSecurityGate.publicKeyFingerprint(publicKey.encoded),
            ),
        )
    } catch (error: Exception) {
        Logger.w(TAG, "Error al leer la clave del autor nativo", error)
        Result.failure(
            IllegalStateException("La clave de autor nativa no está disponible: ${error.message ?: "Archivo corrupto"}", error),
        )
    }

    private fun generateMaterial(): Result<AuthorKeyMaterial> {
        val pair = try {
            KeyPairGenerator.getInstance(PluginSecurityGate.KEY_ALGORITHM).run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair()
            }
        } catch (error: Exception) {
            Logger.e(TAG, "Falló la generación de clave de autor nativo", error)
            return Result.failure(
                IllegalStateException("No se puede generar la clave de autor nativa: ${error.message ?: "El sistema no soporta P-256"}", error),
            )
        }
        return materialOf(pair)
    }

    private fun materialOf(pair: KeyPair): Result<AuthorKeyMaterial> = try {
        val privateKey = pair.private as ECPrivateKey
        val publicKey = pair.public as ECPublicKey
        require(privateKey.params.curve.field.fieldSize == P256_FIELD_SIZE) { "La clave privada no es P-256" }
        require(publicKey.params.curve.field.fieldSize == P256_FIELD_SIZE) { "La clave pública no es P-256" }
        Result.success(
            AuthorKeyMaterial(
                privateKey = privateKey,
                publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded),
                fingerprint = PluginSecurityGate.publicKeyFingerprint(publicKey.encoded),
            ),
        )
    } catch (error: Exception) {
        Result.failure(IllegalStateException("El formato de clave de autor nativo no es compatible: ${error.message}", error))
    }

    private fun persist(material: AuthorKeyMaterial) {
        val stored = StoredAuthorKey(
            publisherId = PUBLISHER_ID,
            privateKeyPkcs8 = Base64.getEncoder().encodeToString(material.privateKey.encoded),
            publicKeyX509 = material.publicKeyBase64,
        )
        AtomicFileStore.writeText(
            storageFile,
            AppJson.encodeToString(StoredAuthorKey.serializer(), stored),
        )
        // Los permisos del archivo de clave privada son lo más estrictos posible: sólo el propietario puede leer y escribir, pero no puede ejecutar; se ignora si algunos sistemas de archivos no lo admiten.
        runCatching {
            storageFile.setReadable(true, true)
            storageFile.setWritable(true, true)
            storageFile.setExecutable(false, false)
        }.onFailure { error ->
            Logger.w(TAG, "Error al ajustar los permisos del archivo clave del autor nativo(No afecta la funcionalidad)", error)
        }
    }

    private fun decodeBase64(encoded: String, label: String): ByteArray {
        require(encoded.isNotBlank()) { "$label esta vacio" }
        require(encoded.length <= MAX_KEY_BASE64_CHARS) { "$label demasiado grande" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(Base64.getEncoder().encodeToString(bytes) == encoded) { "$label no estándar Base64" }
        return bytes
    }

    companion object {
        private const val TAG = "LocalAuthorKeyStore"

        /** ID del editor del autor local: la misma regla de nomenclatura que el editor externo, y es fija y no configurable. */
        const val PUBLISHER_ID = "local-author"

        private const val KEY_FILE_NAME = "muse_local_author_key.json"
        private const val P256_FIELD_SIZE = 256
        private const val MAX_KEY_BASE64_CHARS = 4096
    }
}
