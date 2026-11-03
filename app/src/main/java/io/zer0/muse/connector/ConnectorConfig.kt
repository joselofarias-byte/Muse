package io.zer0.muse.connector

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * v1.0.92: OAuth Configuración del conector — Acceso universal al servicio OAuth2.
 *
 * Reutilización del proceso de conexión [io.zer0.muse.auth.OAuthManager](código de autorización + PKCE),
 * Las devoluciones de llamada están unificadas `io.zer0.muse://oauth/callback`;token Por OAuthManager
 * Botón de almacenamiento de credenciales cifradas `connector_{id}` Ahorra de forma aislada.
 */
@Serializable
data class ConnectorConfig(
    val id: String,
    val name: String = "",
    /** Punto final de autorización(Como por ejemplo https://github.com/login/oauth/authorize). */
    val authorizeUrl: String = "",
    /** token punto final de intercambio(Como por ejemplo https://github.com/login/oauth/access_token). */
    val tokenUrl: String = "",
    /** Ámbitos separados por espacios. */
    val scope: String = "",
    val clientId: String = "",
    /** clave de aplicación(Los clientes públicos se pueden dejar en blanco);Cifrado cuando se almacena. */
    val clientSecret: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Almacenamiento de configuración del conector — filesDir/connector_configs.json(Escritura atómica),
 * clientSecret por [SecureKeyStore] Cifrado y colocado en el disco.
 */
class ConnectorStore(context: Context) {

    private val file = File(context.filesDir, "connector_configs.json")

    @Serializable
    private data class Payload(val connectors: List<ConnectorConfig> = emptyList())

    suspend fun load(): List<ConnectorConfig> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(Payload.serializer(), file.readText()).connectors.map { decrypt(it) }
        }.onFailure { e ->
            Logger.w(TAG, "La lectura de la configuración del conector falló: ${e.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * v2.0: distinguir“El archivo de configuración está dañado”con“Aún no configurado”de lectura —
     * Devuelve nulo si el archivo existe pero fue analizado/Falló el descifrado(dañado),UI Debería solicitar en lugar de mostrar una lista vacía.
     */
    suspend fun loadOrNull(): List<ConnectorConfig>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(Payload.serializer(), file.readText()).connectors.map { decrypt(it) }
        }.onFailure { e ->
            Logger.w(TAG, "La lectura de la configuración del conector falló: ${e.message}")
        }.getOrNull()
    }

    suspend fun save(connectors: List<ConnectorConfig>) = withContext(Dispatchers.IO) {
        val payload = Payload(connectors.map { encrypt(it) })
        AtomicFileStore.writeText(file, AppJson.encodeToString(Payload.serializer(), payload))
    }

    private suspend fun encrypt(config: ConnectorConfig): ConnectorConfig = config.copy(
        clientSecret = if (config.clientSecret.isBlank()) "" else SecureKeyStore.encrypt(config.clientSecret),
    )

    private suspend fun decrypt(config: ConnectorConfig): ConnectorConfig = config.copy(
        clientSecret = if (config.clientSecret.isBlank()) "" else SecureKeyStore.decrypt(config.clientSecret),
    )

    companion object {
        private const val TAG = "ConnectorStore"
    }
}
