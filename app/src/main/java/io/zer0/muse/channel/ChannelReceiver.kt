package io.zer0.muse.channel

/**
 * Un contrato unificado en el extremo receptor del canal.
 *
 * Implementación de recepción de cinco plataformas (WeChat Broadcast, Feishu/QQ/Conexión larga de clavo Ding,Telegram sondeo)
 * Anteriormente eran independientes, pero los métodos del ciclo de vida son en realidad los mismos: ambos `restart()` / `stop()`.
 * Aquí, este acuerdo existente se declara explícitamente, de modo que el registro y la gestión del ciclo de vida solo dependen de interfaces.
 * ya no uno por uno when (platform) rama.
 *
 * Nota: Este paso solo unifica el ciclo de vida y no cambia ningún comportamiento de envío y recepción.
 * Los mensajes entrantes todavía están normalizados en [ChannelInbox.Inbound] Luego entrégalo al nivel superior.
 */
interface ChannelReceiver {
    /** La plataforma correspondiente a este extremo receptor. */
    val platform: ChannelPlatform

    /**
     * Iniciar o reiniciar la recepción.
     *
     * Permitir llamadas repetidas: se debe detener la conexión anterior internamente y se debe establecer una nueva.
     */
    fun restart()

    /** Deja de recibir y liberar recursos. Se permiten llamadas repetidas. */
    fun stop()
}
