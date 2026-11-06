package io.zer0.muse.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Muse ficha de diseño — Dimensiones específicas del componente(CMP-08).
 *
 * con [MusePaddings] / [MuseCornerRadius] / [MuseIconSizes] complementario:Espaciado universal/esquinas redondeadas/iconos
 * Toma esos tres juegos de fichas.,y algunos**Sólo pertenece a un determinado componente.**, El tamaño con el mismo valor no se puede encontrar globalmente.(Ancho de ventana emergente, cambiar geometría de pista)
 * Definido centralmente por propiedad del componente aquí,Los números mágicos ya no aparecen dentro de los componentes.,Sólo es necesario realizar el ajuste aquí.
 *
 * Al agregar un nuevo equipo, debe indicar "dónde se utilizará".;Puede caer en la ficha universal.(Espaciado, esquinas redondeadas, tamaño de icono)Utilice siempre tokens universales.
 */
object MuseDialogSizes {
    /** Acolchado ajustado de la ventana emergente; El área segura del sistema está a cargo del caparazón y no se cuenta en la tarjeta. */
    val contentPadding = 20.dp

    /** Las pantallas pequeñas también conservan espacios en blanco a los lados y las pantallas grandes no se estiran. */
    val maxWidth = 320.dp
    val edgePadding = 24.dp

    /** Una vez que el contenido se desplaza internamente, el título y las acciones permanecen visibles. */
    val contentMaxHeight = 520.dp
    val buttonVisualHeight = 40.dp
}

object MuseSwitchSizes {
    /** Cambiar ancho de vía(Cambiar las especificaciones estándar). */
    val trackWidth = 51.dp

    /** Cambiar la altura de la pista(Cambiar las especificaciones estándar). */
    val trackHeight = 31.dp

    /** Cambiar el diámetro del pulgar(altura orbital − 2×inset). */
    val thumbSize = 27.dp

    /** Inducción del pulgar y la órbita.(Reutilizar tokens de espaciado compactos comunes,No se introducen nuevos valores.). */
    val trackPadding = MusePaddings.tinyGap
}

/**
 * enumerar geometría de fila([io.zer0.muse.ui.common.surface.MuseListItem]).
 *
 * Originalmente, los dos valores se escribieron como literales en los parámetros predeterminados de MuseListItem.,No se puede comparar con el token web;
 * Después de mencionar esto `tokens.json` de `component.listRow.*` Utilice la misma fuente que el componente.
 */
object MuseListItemSizes {
    /** Altura mínima de fila de lista normal(Alinear Material3 ListItem predeterminado,Asegúrate de tocar el objetivo.). */
    val minHeight = 60.dp

    /** lista compacta(Menú/panel de selección)La altura mínima de la fila. */
    val compactMinHeight = 48.dp
}

/**
 * geometría del botón de la cápsula([io.zer0.muse.ui.common.form.MuseCapsuleButton]).
 *
 * Los comentarios de prensa se escribieron originalmente en el valor objetivo de animateFloatAsState,Cosecha actual constante,con
 * `tokens.json` de `component.button.capsulePressScale` Mismo origen.
 */
object MuseCapsuleButtonSizes {
    /** Zoom cuando se presiona(1f al tamaño original). */
    const val pressScale = 0.97f
}
