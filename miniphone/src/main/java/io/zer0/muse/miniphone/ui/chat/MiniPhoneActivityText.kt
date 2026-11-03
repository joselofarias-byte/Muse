package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MpActivityKind

/**
 * Esa línea de copia durante la generación.
 *
 * Sólo presione [MpActivityKind] tomar sentencia:El anfitrión no entrega el nombre de la herramienta, los parámetros ni el valor de retorno.,Tampoco hay nada aquí para mostrarlos.
 */
internal object MiniPhoneActivityText {

    fun of(kind: MpActivityKind): String = when (kind) {
        MpActivityKind.SEARCHING -> SEARCHING
        MpActivityKind.REMEMBERING -> REMEMBERING
        MpActivityKind.CALENDAR -> CALENDAR
        MpActivityKind.WEATHER -> WEATHER
        MpActivityKind.NOTES -> NOTES
        MpActivityKind.OTHER -> OTHER
    }

    private const val SEARCHING: String = "La otra parte está hurgando…"
    private const val REMEMBERING: String = "La otra parte recordó algo.…"
    private const val CALENDAR: String = "La otra parte está mirando el horario.…"
    private const val WEATHER: String = "La otra parte miró por la ventana.…"
    private const val NOTES: String = "La otra parte hojeó las notas.…"
    private const val OTHER: String = "La otra parte está ocupada con otras cosas.…"
}
