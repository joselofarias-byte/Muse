package io.zer0.muse.channel

/**
 * Utilice el valor de entrada actual en la página de configuración para deletrear un objeto de cupón para verificar "si se puede guardar".
 *
 * Cuando el usuario no ha hecho clic en guardar en la página de configuración, los campos están dispersos y parciales, y no hay un ChannelConfig completo;
 * Esta función permite que la lógica de verificación siga siendo manejada por el propio tipo de credencial, en lugar de tener que escribirla nuevamente en la interfaz de usuario.
 */
fun ChannelPlatform.buildCredentials(
    appId: String,
    secret: String,
    robotCode: String = "",
    apiBaseUrl: String = "",
    region: String = "cn",
): ChannelCredentials = when (this) {
    ChannelPlatform.FEISHU -> ChannelCredentials.Feishu(
        appId = appId,
        appSecret = secret,
        region = region.ifBlank { "cn" },
    )
    ChannelPlatform.QQ -> ChannelCredentials.Qq(appId = appId, appSecret = secret)
    ChannelPlatform.DINGTALK -> ChannelCredentials.DingTalk(
        appId = appId,
        appSecret = secret,
        robotCode = robotCode,
        apiBase = apiBaseUrl,
    )
    ChannelPlatform.WECLAW -> ChannelCredentials.WeClaw(botToken = secret)
    ChannelPlatform.TELEGRAM -> ChannelCredentials.Telegram(botToken = secret)
}
