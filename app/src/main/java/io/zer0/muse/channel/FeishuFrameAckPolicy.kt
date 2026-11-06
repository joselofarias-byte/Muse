package io.zer0.muse.channel

/** Solo los marcos de datos con identidades de eventos toman la ruta ACK del evento Feishu. */
internal fun shouldAcknowledgeFeishuEventFrame(messageId: String?, frameType: String?): Boolean =
    !messageId.isNullOrBlank() && frameType == "event"
