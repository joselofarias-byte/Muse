package io.zer0.muse.channel

import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * QQ Los mensajes entrantes se colocan en la base de datos.
 *
 * Los mensajes de imagen primero se descargan y convierten a base64 antes de almacenarlos en la base de datos; si la descarga falla, se degradan a texto de marcador de posición.
 * Se utilizan archivos separados para evitar que el receptor asuma demasiadas responsabilidades.
 */
internal fun recordQqInbound(scope: CoroutineScope, source: ChannelInbox.Source, data: JsonObject, text: String) {
    val attachment = (data["attachments"] as? JsonArray)?.firstOrNull() as? JsonObject
    val imageUrl = ChannelImageFetcher.qqImageUrl(attachment)
    if (imageUrl == null) {
        ChannelInbox.record(source, text, "")
        return
    }
    scope.launch {
        val base64 = ChannelImageFetcher.fetchQq(imageUrl)
        if (base64 != null) {
            ChannelInbox.record(
                source = source,
                text = text,
                rawPayload = "",
                media = ChannelInbox.Media(kind = "image", base64 = base64),
            )
        } else {
            Logger.w("QqReceiver", "QQ Error en la adquisición de imágenes(from=${source.from})")
            ChannelInbox.record(source, "[fotos(No se pudo obtener)]", "")
        }
    }
}
