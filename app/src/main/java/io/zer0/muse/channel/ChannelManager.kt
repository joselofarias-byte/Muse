package io.zer0.muse.channel

import android.content.Context
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v1.0.92: Administrador de canales — Configurar CRUD + distribución de envío.
 *
 * v1 alcance(lado emisor):Envíe el texto de Muse a Feishu/QQ y otras plataformas;
 * lado receptor(Conexión larga del evento / webhook)y interfaz de usuario de configuración,LLM Las herramientas se avanzan paso a paso.
 */
class ChannelManager(context: Context) {

    private val store = ChannelStore(File(context.filesDir, "channel_configs.json"))
    private val mutex = Mutex()

    private val _channels = MutableStateFlow<List<ChannelConfig>>(emptyList())
    val channels: StateFlow<List<ChannelConfig>> = _channels.asStateFlow()

    init {
        // Iniciar autoprueba: los campos que faltan en la declaración de capacidad de medios se desglosan aquí y no se dejan hasta que el usuario envía una imagen.
        validateChannelCapabilities()
    }

    private val senders: Map<ChannelPlatform, ChannelSender> = mapOf(
        ChannelPlatform.FEISHU to FeishuChannelSender(),
        ChannelPlatform.QQ to QqChannelSender(),
        ChannelPlatform.WECLAW to WeClawChannelSender(),
        ChannelPlatform.TELEGRAM to TelegramChannelSender(),
        ChannelPlatform.DINGTALK to DingtalkChannelSender(),
    )

    /** Tabla de capacidades multimedia de la plataforma. */
    fun mediaCapabilitiesOf(platform: ChannelPlatform): io.zer0.muse.channel.MediaCapabilities = platform.capabilities.mediaCapabilities

    /** Cargar configuración de persistencia(Se llama cuando se inicia la aplicación o se ingresa a la página.). */
    suspend fun refresh() {
        mutex.withLock {
            _channels.value = store.load()
        }
    }

    /** Nuevo/Actualice la configuración y consérvela. */
    suspend fun upsert(config: ChannelConfig) {
        mutex.withLock {
            val updated = _channels.value.filterNot { it.id == config.id } + config
            store.save(updated)
            _channels.value = updated
        }
    }

    /** Eliminar configuración. */
    suspend fun remove(id: String) {
        mutex.withLock {
            val updated = _channels.value.filterNot { it.id == id }
            store.save(updated)
            _channels.value = updated
        }
    }

    /**
     * Enviar texto al canal especificado;Todos los fallos convergen en objetos resultantes.,No se lanza ninguna excepción.
     *
     * [targetOverride] para respuestas automáticas"Publicar de nuevo a la fuente"uso(ver [ChannelSender.sendText]).
     */
    suspend fun sendText(channelId: String, text: String, targetOverride: String? = null): ChannelSendResult =
        sendTextWithOptions(channelId, text, ChannelSendOptions(targetOverride = targetOverride))

    internal suspend fun sendTextWithOptions(channelId: String, text: String, options: ChannelSendOptions): ChannelSendResult {
        val config = _channels.value.firstOrNull { it.id == channelId }
        val sender = config?.let { senders[it.platform] }
        val result = when {
            config == null -> ChannelSendResult(false, "El canal no existe: $channelId")
            !config.enabled -> ChannelSendResult(false, "El canal esta desactivado: ${config.name.ifBlank { channelId }}")
            text.isBlank() -> ChannelSendResult(false, "Enviar contenido está vacío")
            sender == null -> ChannelSendResult(false, "Plataforma no compatible: ${config.platform}")
            else -> sender.sendText(
                config,
                text,
                options.targetOverride,
                options.contextTokenOverride,
                options.sourceEventIdOverride,
                options.sourceEventSequenceOverride,
            ).fold(
                onSuccess = { ChannelSendResult(true, "Enviado a ${config.name.ifBlank { config.platform.name }}") },
                onFailure = { error ->
                    Logger.w(TAG, "Error al enviar el canal: ${error.message}")
                    ChannelSendResult(false, error.message ?: "Envío fallido")
                },
            )
        }
        return result
    }

    /**
     * v2.0.1: Detección de credenciales — Llame a la interfaz de solo lectura una vez por plataforma para verificar que las credenciales estén disponibles;Devuelve información de éxito visualizable.
     * WeChat(iLink)Sin credenciales detectables de forma independiente(Escanear código para vincular y verificar).
     */
    suspend fun validate(config: ChannelConfig): Result<String> = withContext(Dispatchers.IO) {
        when (config.platform) {
            ChannelPlatform.FEISHU -> runCatching { FeishuChannelSender().fetchToken(config) }
                .mapCatching { "tenant_access_token conseguir el éxito" }
            ChannelPlatform.QQ -> QqClient.fetchAccessToken(config.appId.trim(), config.appSecret.trim())
                .map { "access_token conseguir el éxito" }
            ChannelPlatform.DINGTALK ->
                DingtalkClient
                    .fetchAccessToken(config.appId.trim(), config.appSecret.trim(), config.dingtalkApiBase)
                    .map { "access_token conseguir el éxito" }
            ChannelPlatform.TELEGRAM -> TelegramClient.getMe(config.appSecret.trim())
            ChannelPlatform.WECLAW -> Result.success("El canal WeChat se completa escaneando el código QR y vinculándolo.,No se requieren pruebas separadas")
        }
    }

    companion object {
        private const val TAG = "ChannelManager"
    }
}

internal data class ChannelSendOptions(
    val targetOverride: String? = null,
    val contextTokenOverride: String? = null,
    val sourceEventIdOverride: String? = null,
    val sourceEventSequenceOverride: Int? = null,
)
