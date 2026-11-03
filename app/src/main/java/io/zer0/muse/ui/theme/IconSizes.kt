package io.zer0.muse.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Phase 12: muse ficha de tamaño de icono(MuseIconSizes).
 *
 * curar"IconButton size 24/28/32/36/44 Mezclar"inconsistencias,
 * Al cumplir con las líneas rojas de accesibilidad de Material Design(tocar el objetivo ≥ 48dp).
 *
 * Jerarquía de tokens(De grande a pequeño):
 *  - [touchTarget]: 48.dp — Tamaño mínimo del objetivo táctil(MD3 linea roja,Todos los IconButtons deben usar este tamaño)
 *  - [iconEmpty]:   64.dp — Estado vacío/Icono grande de estado de error(MuseEmptyState / MuseErrorStateBox arriba)
 *  - [iconLarge]:   32.dp — gran icono(Icono de avatar/acento)
 *  - [icon]:        24.dp — Tamaño visual del icono(MD3 Estándar)
 *  - [iconMedium]:  20.dp — icono mediano(Línea de navegación/acción)
 *  - [iconSmall]:   18.dp — pequeño icono(elemento de la lista)
 *  - [iconTiny]:    14.dp — microicono(Icono en la etiqueta)
 *
 * Uso:
 * ```
 * IconButton(
 *     modifier = Modifier.size(MuseIconSizes.touchTarget),
 *     onClick = { ... },
 * ) {
 *     Icon(Icons.Default.Xxx, modifier = Modifier.size(MuseIconSizes.icon), ...)
 * }
 * ```
 */
object MuseIconSizes {
    /** Tamaño mínimo del objetivo táctil(MD3 linea roja,Todos los IconButtons deben usar este tamaño). */
    val touchTarget = 48.dp

    /** Estado vacío/Icono grande de estado de error(MuseEmptyState / MuseErrorStateBox arriba,L-ES2). */
    val iconEmpty = 64.dp

    /** gran icono(Icono de avatar/acento). */
    val iconLarge = 32.dp

    /** Tamaño visual del icono(MD3 Estándar). */
    val icon = 24.dp

    /** icono mediano(Línea de navegación/acción). */
    val iconMedium = 20.dp

    /** pequeño icono(elemento de la lista). */
    val iconSmall = 18.dp

    /** microicono(Icono en la etiqueta). */
    val iconTiny = 14.dp

    /**
     * v2.x: Ancho de línea de icono personalizado — muse-icons El valor autorizado del generador.(1.7f).
     * MuseIcons.kt Generar archivos para;Ajustar el ancho de línea requiere modificar el generador simultáneamente(muse-icons Guión de almacén),
     * Esta constante es para documentación y referencia fuera de este documento.,No ensucies otros códigos escritos a mano con codificación rígida.
     */
    const val strokeWidth = 1.7f

    /** B7-07: 16dp icono/Círculo de progreso. */
    val iconSmallTiny = 16.dp

    /** B7-07: 28dp Icono de miniatura de vídeo. */
    val iconVideo = 28.dp

    /** B7-07: 32dp Área táctil compacta(Quitar puntos/Botón de herramienta). */
    val controlTouch = 32.dp

    /** B7-07: 36dp detenerse/Enviar dimensiones internas de la cápsula. */
    val stopButton = 36.dp

    /** UI-FIX A: El diámetro visual del botón de círculo sólido de la barra superior.(El área táctil permanece [touchTarget]). */
    val topBarSolid = 36.dp

    /** B7-07: Trazo de círculo de progreso. */
    val progressStroke = 2.dp

    /** B7-07: La altura mínima del cuadro de entrada. */
    val inputMinHeight = 36.dp

    /** CMP-08: Tamaño predeterminado del botón flotante(Material FAB normativo 56dp). */
    val fab = 56.dp

    /** B7-07: Tamaño de la barra de onda de grabación. */
    val waveformHeight = 24.dp
    val waveformBarWidth = 3.dp
    val waveformBarRadius = 1.5.dp
}
