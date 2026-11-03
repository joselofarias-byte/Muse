package io.zer0.muse.backup

import io.zer0.muse.data.SecureKeyStore
import kotlinx.serialization.Serializable

/**
 * Phase 8.9: Configuración de copia de seguridad en la nube(S3 + WebDAV Elige uno).
 *
 * Almacenado en KEY_CLOUD_BACKUP_CONFIG en el repositorio de configuración,JSON formato.
 * type=none Indica que la copia de seguridad en la nube no está configurada;type=s3 usar S3Client;type=webdav Utilice WebDavClient.
 *
 * H-001: s3SecretKey / webdavPassword Credenciales sensibles involucradas,Serializado por SettingsRepository para JSON
 * Deberías ir antes de realizar un pedido. [SecureKeyStore] cifrado. Esta clase de datos solo lleva definiciones de campos.,Pase de cifrado y descifrado [encrypted] /
 * [decrypted] Los métodos de miembros se completan en los límites de serialización.:Antes de escribir en DataStore `encrypted()`,despues de leer `decrypted()`.
 */
@Serializable
data class CloudBackupConfig(
    val type: String = "none", // none / s3 / webdav
    // S3 Configuración
    val s3Endpoint: String = "",
    val s3Region: String = "",
    val s3Bucket: String = "",
    val s3AccessKey: String = "",
    val s3SecretKey: String = "",
    val s3KeyPrefix: String = "muse/", // Prefijo de clave de objeto(Directorio)
    // WebDAV Configuración
    val webdavUrl: String = "",
    val webdavUsername: String = "",
    val webdavPassword: String = "",
    val webdavPath: String = "/muse/", // directorio remoto
    // Universales
    val autoSync: Boolean = false, // Ya sea para sincronizar automáticamente(Carga programada)
    /** v1.98: Intervalo de sincronización automática(horas). El valor predeterminado es 24 horas. solo si autoSync=true eficaz cuando. */
    val autoSyncIntervalHours: Int = 24,
    val lastSyncAt: Long = 0, // Marca de tiempo de la última sincronización
    /** v1.120: Contraseña de cifrado de copia de seguridad en la nube(El usuario puede configurar). Las copias de seguridad en la nube que no son del espacio-tiempo están cifradas con AES-256-GCM(PBKDF2 clave derivada),Esta contraseña se puede descifrar en todos los dispositivos. */
    val backupPassword: String = "",
    /**
     * B-9: ¿El usuario alguna vez estableció una contraseña de respaldo?(Desacoplamiento del texto sin formato de contraseña).
     * La capa de persistencia se establece al escribir backupPassword.isNotEmpty();Si se pierde la clave del almacén de claves, el
     * decrypt Volver vacío,Todavía se puede identificar con esta marca."Contraseña no válida",Rechazando así las cargas de degradación de texto claro.
     */
    val backupPasswordSet: Boolean = false,
) {
    val isConfigured: Boolean get() = when (type) {
        "s3" -> s3Endpoint.isNotBlank() && s3Bucket.isNotBlank() && s3AccessKey.isNotBlank() && s3SecretKey.isNotBlank()
        "webdav" -> webdavUrl.isNotBlank() && webdavUsername.isNotBlank()
        else -> false
    }

    /**
     * H-001: Devuelve s3SecretKey/webdavPassword cifrada(ir [SecureKeyStore.encrypt])copia de,
     * Por llamar antes que la perseverancia. Los valores nulos se dejan como están.(No cifrar valores nulos);cifrado(Hay enc_v1: prefijo)Déjalo como está.
     */
    suspend fun encrypted(): CloudBackupConfig = copy(
        s3SecretKey = SecureKeyStore.encrypt(s3SecretKey),
        webdavPassword = SecureKeyStore.encrypt(webdavPassword),
        backupPassword = SecureKeyStore.encrypt(backupPassword),
    )

    /**
     * H-001: Devuelve s3SecretKey / webdavPassword descifrado(ir [SecureKeyStore.decrypt])copia de,
     * Se llama después de leer desde la capa de persistencia. Los antiguos campos de texto sin formato se transmiten de forma transparente mediante descifrado.(Compatible con la migración).
     */
    suspend fun decrypted(): CloudBackupConfig = copy(
        s3SecretKey = SecureKeyStore.decrypt(s3SecretKey),
        webdavPassword = SecureKeyStore.decrypt(webdavPassword),
        backupPassword = SecureKeyStore.decrypt(backupPassword),
    )
}
