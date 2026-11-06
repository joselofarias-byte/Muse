package io.zer0.muse.ui

import io.zer0.ai.core.UIMessage

/**
 * Reparación de auditoría (A-16): Los medios del mensaje final fusionan pura función.
 *
 * fondo(S-01/A-01): Agent Loop El finalAssistantMessage de cierre lo construye GenerationHandler,
 * Contiene sólo content + toolCalls,Sin campos multimedia;Y execGenerateImage/Video/QrCode ha puesto los medios
 * Escribe una copia de memoria del mensaje con la misma identificación. Si está cubierto directamente,fotos/vídeo/El código QR se perderá.
 *
 * fusionar reglas:
 *  - finalAssistant propios medios → Sujeto a ello(El mensaje de cierre puede contener nuevos medios.)
 *  - finalAssistant sin medios → Retención alternativa existingMsg(Medios escritos por rueda de herramientas.)
 *  - existingMsg para null → Los medios son una lista vacía(No hay fuentes para fusionar)
 *
 * Extraiga funciones puras para facilitar pruebas únicas(A-16 pedido),punto de llamada: ChatViewModel Agent Loop Refinamiento.
 */
internal fun mergeFinalAssistantMedia(finalAssistant: UIMessage, existingMsg: UIMessage?): UIMessage = finalAssistant.copy(
    imageUrls = finalAssistant.imageUrls.ifEmpty { existingMsg?.imageUrls ?: emptyList() },
    imageBase64List = finalAssistant.imageBase64List.ifEmpty { existingMsg?.imageBase64List ?: emptyList() },
    videoFileUri = finalAssistant.videoFileUri ?: existingMsg?.videoFileUri,
)
