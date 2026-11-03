package io.zer0.muse.miniphone.ui.chat

import java.util.UUID

/**
 * Dos fuentes alternativas utilizadas al abandonar la biblioteca:¿Qué hora es ahora y la identificación del próximo mensaje?
 *
 * Sintetizar un valor es evitar que la lista de parámetros de ViewModel se hinche. —— Estas dos cosas siempre se reemplazan juntas.,
 * Prueba crucificada con reloj falso `createdAt`, utilice identificadores de incremento automático para hacer que las afirmaciones sean predecibles.
 */
data class ChatWriteClock(
    val now: () -> Long = System::currentTimeMillis,
    val newId: () -> String = { UUID.randomUUID().toString() },
)
