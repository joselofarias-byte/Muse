package io.zer0.muse.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Muse elevación/Escala semántica sombreada (CMP-01: fuente de datos única,tonalElevation Compartido con sombraElevación).
 *
 * Finalidad de cada archivo:
 *  - [none]:   0 — Sin jerarquía(superficie predeterminada)
 *  - [micro]:  0.5 — adherirse a la superficie,solo pistas sobre niveles
 *  - [low]:    1 — Valor predeterminado de elemento de lista/tarjeta
 *  - [card]:   1 — tarjeta(Mismo valor que bajo,distinción semántica)
 *  - [medium]: 2 — Isla de entrada/barra flotante shadowElevation
 *  - [high]:   4 — Artículo seleccionado/Tarjeta destacada
 *  - [modal]:  8 — cuadro de diálogo / BottomSheet
 *  - [floating]: 12 — v2.x: capa flotante/panel grande(Compensa los grandes puestos)
 *  - [overlay]:  24 — v2.x: Elemento grande flotante/modal de pantalla completa(Compensa los grandes puestos)
 *
 * Dos conjuntos de registros históricos con el mismo nombre y valores diferentes(MuseShadow.low=2/high=12/modal=24)Fusionado y eliminado,Sigue la misma escala.
 */
object MuseElevation {
    val none: Dp = 0.dp
    val micro: Dp = 0.5.dp
    val low: Dp = 1.dp
    val card: Dp = 1.dp
    val medium: Dp = 2.dp
    val high: Dp = 4.dp
    val modal: Dp = 8.dp

    /** v2.x: capa flotante/panel grande — 12dp(Compensa los grandes puestos,menú/Uso de contenedores de paneles.). */
    val floating: Dp = 12.dp

    /** v2.x: Elemento grande flotante/modal de pantalla completa — 24dp(Compensa los grandes puestos,Para usar con superposiciones de pantalla completa). */
    val overlay: Dp = 24.dp
}
