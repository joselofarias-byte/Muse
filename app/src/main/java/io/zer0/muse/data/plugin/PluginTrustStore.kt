package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Raíz de confianza para los editores de complementos externos.
 *
 * La raíz de confianza sólo se guarda en Android [Context.noBackupFilesDir], No escriba en el directorio del proyecto, paquete de complemento,
 * Configuración general o copia de seguridad. La clave pública en el paquete de importación es sólo una afirmación de identidad que debe verificarse; sólo si el usuario elige explícitamente“Confía en el editor”
 * Se escribirá aquí más tarde. El contenido del almacenamiento solo contiene la clave pública y la huella digital, y no contiene ninguna clave privada.
 */
class PluginTrustStore internal constructor(
    private val storageFile: File,
) {

    /** Android Un almacén privado de raíz de confianza que se utiliza en tiempo de ejecución y que no se incluye en las copias de seguridad. */
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, TRUST_ROOT_FILE_NAME),
    )

    @Serializable
    data class TrustedPublisher(
        val publisherId: String,
        val publicKey: String,
        val fingerprint: String,
        val trustedAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    private data class TrustRegistry(
        val publishers: List<TrustedPublisher> = emptyList(),
    )

    private val lock = Any()

    @Volatile
    private var cached: List<TrustedPublisher> = load()

    /** Devuelve el resumen público de la raíz de confianza actual; no se devuelve ningún material de clave privada. */
    fun list(): List<TrustedPublisher> = cached

    /** Encuentra la clave pública actualmente vinculada del emisor especificado. */
    fun find(publisherId: String): TrustedPublisher? = cached.firstOrNull { it.publisherId == publisherId }

    /**
     * Registre explícitamente la clave pública del emisor.
     *
     * Una vez registrado el mismo ID de emisor, no se permite sobrescribirlo con otra clave pública; La rotación debe utilizar un nuevo ID de emisor.
     * Evite paquetes manipulados“Actualizar clave pública”Evite la raíz de confianza existente.
     */
    fun trust(publisherId: String, publicKey: String): Result<TrustedPublisher> = synchronized(lock) {
        val normalizedId = publisherId.trim()
        if (!PluginSecurityGate.isValidPublisherId(normalizedId)) {
            return@synchronized Result.failure(IllegalArgumentException("ID de emisor ilegal"))
        }
        val key = parsePublicKey(publicKey.trim()).getOrElse { error -> return@synchronized Result.failure(error) }
        val normalizedKey = java.util.Base64.getEncoder().encodeToString(key.encoded)
        val fingerprint = PluginSecurityGate.publicKeyFingerprint(key.encoded)
        val existing = cached.firstOrNull { it.publisherId == normalizedId }
        if (existing != null) {
            return@synchronized if (existing.publicKey == normalizedKey) {
                Result.success(existing)
            } else {
                Result.failure(IllegalStateException("El ID del emisor está vinculado a otra clave pública."))
            }
        }
        val trusted = TrustedPublisher(
            publisherId = normalizedId,
            publicKey = normalizedKey,
            fingerprint = fingerprint,
        )
        val previous = cached
        val next = cached + trusted
        return@synchronized try {
            persist(next)
            cached = next
            Result.success(trusted)
        } catch (error: Exception) {
            cached = previous
            Result.failure(error)
        }
    }

    /** Elimina la raíz de confianza de un editor; sus complementos deben someterse nuevamente a decisiones de confianza explícitas después de su eliminación. */
    fun revoke(publisherId: String): Boolean = synchronized(lock) {
        val next = cached.filterNot { it.publisherId == publisherId }
        if (next.size == cached.size) return@synchronized false
        persist(next)
        cached = next
        true
    }

    /** La identidad del emisor se verifica utilizando la raíz de confianza nativa; los emisores desconocidos no se unen implícitamente. */
    fun isTrusted(signature: PluginSignature): Boolean {
        val trusted = find(signature.publisherId) ?: return false
        return trusted.publicKey == signature.publicKey
    }

    private fun parsePublicKey(encoded: String): Result<PublicKey> = runCatching {
        require(encoded.isNotBlank()) { "La clave pública del emisor está vacía" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_PUBLIC_KEY_BYTES) { "La clave pública del emisor es demasiado grande" }
        KeyFactory.getInstance(PluginSecurityGate.KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(bytes))
            .also { key ->
                require(key.algorithm == PluginSecurityGate.KEY_ALGORITHM) { "El algoritmo de clave pública del emisor no es compatible" }
            }
    }

    private fun load(): List<TrustedPublisher> {
        if (!storageFile.exists()) return emptyList()
        return runCatching {
            AppJson.decodeFromString<TrustRegistry>(storageFile.readText()).publishers
                .onEach { parsePublicKey(it.publicKey).getOrThrow() }
                .distinctBy { it.publisherId }
        }.getOrElse { error ->
            AtomicFileStore.quarantine(storageFile, "plugin_trust_root_parse")
            Logger.w("PluginTrustStore", "Falló la resolución de la raíz de confianza del complemento, el archivo corrupto se puso en cuarentena", error)
            emptyList()
        }
    }

    private fun persist(publishers: List<TrustedPublisher>) {
        AtomicFileStore.writeText(
            storageFile,
            AppJson.encodeToString(TrustRegistry.serializer(), TrustRegistry(publishers)),
        )
    }

    companion object {
        private const val TRUST_ROOT_FILE_NAME = "muse_plugin_trust_roots.json"
        private const val MAX_PUBLIC_KEY_BYTES = 512
    }
}
