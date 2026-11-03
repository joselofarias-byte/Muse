package io.zer0.muse.channel

/**
 * Portal unificado para la entrega de medios salientes.
 *
 * Solo se encarga de la distribución según la plataforma, y ​​la implementación específica está en los archivos independientes de cada plataforma.
 * De esta forma, cada archivo tiene una única responsabilidad y al agregar la plataforma no es necesario tocar otros lugares.
 */
internal object MediaSender {
    /**
     * Enviar medios. Se debe pasar antes de llamar. [ChannelPlatform.checkMediaSendable], lo comprobaré nuevamente aquí.
     */
    suspend fun send(config: ChannelConfig, media: OutboundMedia, targetOverride: String? = null): Result<Unit> {
        config.platform.checkMediaSendable(media)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        return when (config.platform) {
            ChannelPlatform.TELEGRAM -> TelegramMediaSender.send(config, media, targetOverride)
            ChannelPlatform.FEISHU -> FeishuMediaSender.send(config, media, targetOverride)
            ChannelPlatform.QQ -> QqMediaSender.send(config, media, targetOverride)
            ChannelPlatform.DINGTALK -> DingtalkMediaSender.send(config, media, targetOverride)
            ChannelPlatform.WECLAW -> Result.failure(
                UnsupportedOperationException(
                    "El canal WeChat actualmente no admite el envío${media.kind.name.lowercase()}, actualmente solo puedo enviar y recibir mensajes de texto",
                ),
            )
        }
    }
}
