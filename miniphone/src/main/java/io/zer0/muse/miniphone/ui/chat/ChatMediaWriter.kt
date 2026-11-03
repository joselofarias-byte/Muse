package io.zer0.muse.miniphone.ui.chat

import android.net.Uri
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import io.zer0.muse.miniphone.data.media.MpImageStore

/**
 * Guardar y registrar mensajes con imágenes.
 *
 * [MpImageStore] Sólo responsable de escribir bytes en el directorio privado.;"Solo publique un mensaje si lo ha escrito correctamente. Si no puede escribirlo, omita este".
 * Esta capa de arreglo está aquí.,La persona que llama no necesita juzgar si la ruta obtenida es confiable.
 *
 * @param threadId Esta identificación de sesión: la imagen seleccionada por el usuario se devolverá aquí de forma predeterminada.
 */
internal class ChatMediaWriter(
    private val imageStore: MpImageStore,
    private val writer: ChatMessageWriter,
    private val threadId: String,
) {

    /**
     * Imágenes seleccionadas por el usuario del álbum.:Guarda uno y suelta otro,El orden es consistente con la selección.
     *
     * no puedo guardar(No se puede leer ni escribir en el disco)Salta el que está directamente,No afecta a las imágenes restantes.
     */
    suspend fun storePickedImages(uris: List<Uri>, characterId: String) {
        uris.mapNotNull { uri -> imageStore.import(uri) }
            .forEach { path -> store(threadId, characterId, MpActorType.USER, path) }
    }

    /**
     * Fotos del cabello del personaje de Luo Yiyi.:El archivo está en el disco.,Simplemente escriba la línea del mensaje.
     *
     * En realidad, no se genera ninguna imagen en ninguna parte en esta etapa.,Este método está reservado para pruebas y dibujos de personajes posteriores.;
     * La persona que llama es responsable de garantizar que [localFile] ya existe.
     */
    suspend fun storeCharacterImage(threadId: String, characterId: String, localFile: String) {
        store(threadId, characterId, MpActorType.CHARACTER, localFile)
    }

    private suspend fun store(threadId: String, characterId: String, sender: MpActorType, mediaUri: String) {
        writer.append(
            ChatOutgoingMessage(
                threadId = threadId,
                characterId = characterId,
                sender = sender,
                type = MpMessageType.IMAGE,
                content = IMAGE_PREVIEW_TEXT,
                mediaUri = mediaUri,
            ),
        )
    }

    companion object {
        /** El texto del mensaje con imagen.:No hay ninguna foto en la biblioteca.,Guarde solo esta copia preliminar,Úselo tanto para listas como para vistas previas no leídas. */
        const val IMAGE_PREVIEW_TEXT: String = "[fotos]"
    }
}
