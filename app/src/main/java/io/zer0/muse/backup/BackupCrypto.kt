package io.zer0.muse.backup

import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * v1.120: Copia de seguridad en la nube Cifrado y descifrado AES-256-GCM.
 *
 * derivación de claves:PBKDF2-HMAC-SHA256(password, salt, iterations=100000, keyLen=256bit)
 * algoritmo de cifrado:AES-256-GCM(12B IV + 128bit authTag)
 *
 * Formato de archivo:[magic(4B "MENC")] [version(1B)] [salt(16B)] [iv(12B)] [ciphertext+authTag]
 *
 * En todos los dispositivos:El usuario ingresa la misma contraseña de respaldo en el nuevo dispositivo para descifrarlo.(salt Almacenado con texto cifrado).
 * Compatibilidad:importFromCloud Detección magic header,No hay magia considerada como copia de seguridad antigua de texto plano,Procese como está.
 */
object BackupCrypto {
    private const val MAGIC = "MENC"
    private const val VERSION: Byte = 1
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    /** H-SEC-5: NIST SP 800-132 Mínimo recomendado 210,000 veces(SHA-256). Mejore la resistencia contra el agrietamiento por fuerza bruta. */
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256

    /** Cifrado,Devuelve el flujo de bytes completo con un encabezado mágico.password Lanza IllegalArgumentException cuando está vacío. */
    fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "backupPassword must not be empty" }
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        // montar: magic(4) + version(1) + salt(16) + iv(12) + ciphertext
        return ByteArray(MAGIC.length + 1 + SALT_LENGTH + IV_LENGTH + ciphertext.size).also { out ->
            var offset = 0
            MAGIC.toByteArray(Charsets.US_ASCII).copyInto(out, offset)
            offset += MAGIC.length
            out[offset] = VERSION
            offset += 1
            salt.copyInto(out, offset)
            offset += SALT_LENGTH
            iv.copyInto(out, offset)
            offset += IV_LENGTH
            ciphertext.copyInto(out, offset)
        }
    }

    /** Descifrar.data debe ser [encrypt] Formato de salida.password Se lanza una excepción en caso de error o corrupción de datos. */
    fun decrypt(data: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "backupPassword must not be empty" }
        require(data.size > MAGIC.length + 1 + SALT_LENGTH + IV_LENGTH) { "encrypted data too short" }
        val magic = String(data, 0, MAGIC.length, Charsets.US_ASCII)
        require(magic == MAGIC) { "invalid magic header: $magic" }
        val version = data[MAGIC.length]
        require(version == VERSION) { "unsupported version: $version" }
        var offset = MAGIC.length + 1
        val salt = data.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val iv = data.copyOfRange(offset, offset + IV_LENGTH)
        offset += IV_LENGTH
        val ciphertext = data.copyOfRange(offset, data.size)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Determine si el flujo de bytes está en el formato cifrado por esta herramienta(Utilizado por importFromCloud para ser compatible con copias de seguridad antiguas de texto sin formato). */
    fun isEncrypted(data: ByteArray): Boolean {
        if (data.size <= MAGIC.length) return false
        val magic = runCatching { String(data, 0, MAGIC.length, Charsets.US_ASCII) }.getOrNull()
        return magic == MAGIC
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        // H-SEC-6: Copie y borre bytes confidenciales inmediatamente después de generar la clave,Evitar que el secreto original permanezca en el montón
        val rawSecret = factory.generateSecret(spec).encoded
        val keyBytes = rawSecret.copyOf(rawSecret.size)
        Arrays.fill(rawSecret, 0.toByte())
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }
}
