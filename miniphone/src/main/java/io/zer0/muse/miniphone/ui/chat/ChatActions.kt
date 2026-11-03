package io.zer0.muse.miniphone.ui.chat

import androidx.compose.runtime.Immutable

/**
 * Tres devoluciones de llamada requeridas por el campo de entrada.
 *
 * Sintetiza un valor para llevar contigo:La mayoría de los parámetros en la página de chat son "devoluciones de llamada requeridas para un área determinada".,
 * Después de agrupar por región,Los lectores pueden darse cuenta de un vistazo que se trata de salidas para lo mismo.
 *
 * @param onSend enviar un texto
 * @param onPickImages Levante el selector de imágenes del sistema.
 * @param onTranscribeVoice Convertir grabación a texto;No puedo transferir de regreso null
 */
@Immutable
data class ChatInputActions(
    val onSend: (String) -> Unit = {},
    val onPickImages: () -> Unit = {},
    val onTranscribeVoice: suspend (ByteArray) -> String? = { null },
)

/**
 * Dos operaciones en un solo mensaje:Reenviar y eliminar.
 *
 * Los parámetros son mensajes. id —— La interfaz no sabe qué enlace debe tomar este mensaje.,Solo sabe en qué elemento hizo clic el usuario.
 */
@Immutable
data class ChatMessageActions(
    val onResend: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
)
