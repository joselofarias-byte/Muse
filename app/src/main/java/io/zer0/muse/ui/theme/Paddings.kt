package io.zer0.muse.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Phase 12: muse ficha de espaciado(MusePaddings).
 *
 * curar"padding 8/12/14/16/24 Mezclar"problemas de inconsistencia.
 * Referencia unificada para todo el proyecto. [MusePaddings] Simbólico.
 *
 * Jerarquía de tokens(ritmo del espacio en blanco):
 *  - [screen]:         16.dp — márgenes horizontales de la pantalla(Scaffold padding)
 *  - [cardInner]:      horizontal=16, vertical=12 — relleno de tarjeta
 *  - [cardInnerLoose]: horizontal=16, vertical=14 — Acolchado de tarjeta ligeramente suelto.
 *  - [sectionGap]:     16.dp — section espaciado entre
 *  - [itemGap]:        16.dp — espaciado entre elementos de la lista(LazyColumn spacedBy;v2.0.1 Levantado de 12)
 *  - [cardGap]:        20.dp — espacio entre barajas de cartas(Página de clase de configuración;v2.0.1 Nuevo)
 *  - [contentGap]:     8.dp — Espaciado entre componentes dentro de la tarjeta.
 *  - [tightGap]:       4.dp — Espacio reducido(icono + texto)
 *  - [tinyGap]:        2.dp — Logotipo / espacio muy reducido entre líneas
 *  - [labelVerticalGap]: 6.dp — Logotipo pequeño/espacio entre líneas ajustado
 *  - [auxGap]:         10.dp — Logotipo medio/espaciado secundario en la tarjeta
 *  - [largeGap]:       24.dp — BottomSheet Espacio grande interno/estado vacío
 *  - [emptyStateGap]:  32.dp — Estado vacío/gran espacio en blanco
 *  v1.0.17 Expandir(Token compuesto de PaddingValues,Modo de combinación convergente de alta frecuencia):
 *  - [cardInnerSpaced]: horizontal=16, vertical=8 — ancho de pantalla + contenidoGap vertical
 *  - [bubbleInner]:     horizontal=12, vertical=8 — Burbujas/Acolchado de tarjeta ajustado
 *  CMP-02: Otras combinaciones de compuestos(cardInnerTight / cardInnerMedium / cardInnerAux / chipInner)
 *  Duplicar con posición del documento,Ya**Eliminar**;Los pulsadores siempre se combinan explícitamente con el engranaje de valor único anterior.,El segundo conjunto de fichas combinadas no se conserva.
 */
object MusePaddings {
    /** márgenes horizontales de la pantalla(Scaffold padding). */
    val screen = 16.dp

    /** relleno de tarjeta(Unifica todas las Tarjetas padding). */
    val cardInner = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

    /** Acolchado de tarjeta ligeramente suelto.(Escena con un gran espacio en blanco para los elementos de la lista.). */
    val cardInnerLoose = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

    /** section espaciamiento entre ellos. */
    val sectionGap = 16.dp

    /** v2.0.1: El espacio entre grupos de tarjetas (página de configuración, medido 20 dp). */
    val cardGap = 20.dp

    /** Altura de la cápsula de la barra de búsqueda. La página de techo necesita compensar el espacio superior de la lista con el mismo valor, por lo que comparte este token con el componente. */
    val searchBarHeight = 48.dp

    /** espaciado entre elementos de la lista(LazyColumn spacedBy).v2.0.1: 12 → 16("más flojo"dirección estructural). */
    val itemGap = 16.dp

    /** El espacio entre los componentes dentro de la tarjeta. */
    val contentGap = 8.dp

    /** Espacio reducido(icono + texto). */
    val tightGap = 4.dp

    /** v1.0.15: Logotipo / espacio entre líneas muy ajustado. */
    val tinyGap = 2.dp

    /** v1.0.15: Logotipo pequeño/espacio entre líneas ajustado. */
    val labelVerticalGap = 6.dp

    /** v1.0.15: Logotipo medio/espaciado secundario en la tarjeta. */
    val auxGap = 10.dp

    /** v1.0.15: BottomSheet Espaciado grande interno/estado nulo. */
    val largeGap = 24.dp

    /** v1.0.15: Estado vacío/gran espacio en blanco. */
    val emptyStateGap = 32.dp

    /**
     * Toque el tamaño del objetivo(MD3 linea roja,IconButton / Altura mínima de fila).
     * L-PD1: unificado con [MuseIconSizes.touchTarget] como única fuente de datos,Referencia del delegado aquí,
     * Evite definir 48.dp en dos lugares para provocar una doble deriva en la fuente de datos.
     */
    val touchTarget: androidx.compose.ui.unit.Dp get() = MuseIconSizes.touchTarget

    /** Relleno del cuadro de entrada. */
    val inputPadding = 12.dp

    /** B7-07: Paisaje del campo de entrada/Márgenes verticales. */
    val inputHorizontal = 24.dp
    val inputVertical = 2.dp

    /** B7-07: Espacio reducido dentro de los campos de entrada(Entrada rápida/Documentación/Vista previa del vídeo). */
    val inputStackGap = 6.dp

    /** B7-07: El tamaño de la miniatura de vista previa que se enviará. */
    val previewThumb = 72.dp

    /** B7-07: Pequeños puntos eliminan el acolchado de los botones. */
    val removeDotPadding = 3.dp

    /** B7-07: Ancho máximo de vista previa en línea. */
    val maxInlineWidth = 120.dp

    /** B7-07: Acolchado vertical de chip compacto. */
    val compactChipVertical = 1.dp

    /** v2.4.5: Márgenes de la isla de entrada de chat,Hace que la cápsula sea más ligera y ligeramente elevada. */
    val inputIslandHorizontal = 12.dp
    val inputIslandVertical = 2.dp

    /** v2.4.5 (Alineación de la imagen de referencia): Ingrese el espacio flotante en la parte inferior de la isla.(Levantar isla de entrada,No unido al borde inferior). */
    val inputIslandBottomGap = 10.dp

    /** v2.4.5 (Alineación de la imagen de referencia): Ingrese el relleno izquierdo y derecho dentro de la fila de la isla(Más estrecho que contentGap). */
    val inputIslandInnerHorizontal = 6.dp

    /** v2.4.5: Espaciado vertical de la fila interior de la isla de entrada;Los objetivos táctiles todavía están garantizados por IconSizes.touchTarget. */
    val inputIslandRowVertical = 0.dp

    /** B7-07: La altura máxima del cuadro de entrada del mensaje. */
    val maxMessageFieldHeight = 160.dp

    /** B7-07: Ancho del divisor del panel de herramientas/Espesor. */
    val dividerWidth = 1.dp
    val dividerThickness = 0.5.dp

    /** B7-07: Además, el panel de herramientas enumera la altura máxima. */
    val maxToolSheetListHeight = 360.dp

    /** B7-07: Relleno vertical de fila de lista de herramientas. */
    val listRowVertical = 14.dp

    /** relleno de iconos(Espaciado entre icono y texto adyacente). */
    val iconPadding = 8.dp

    /** M-CS5: espaciado de mensajes(lista de chat LazyColumn spacedBy,sensación de respiración). */
    val messageGap = 20.dp

    /** v1.0.17: ancho de pantalla + contenidoGap vertical(Burbujas/tarjeta). */
    val cardInnerSpaced = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

    /** v1.0.17: Burbujas/Acolchado de tarjetas ajustado. */
    val bubbleInner = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    /**
     * CMP-02: alias de combinación compuesta(cardInnerTight / cardInnerMedium / cardInnerAux / chipInner)
     * Todo eliminado — El pulsador se cambia a una combinación explícita de engranajes de valor único arriba
     * (Ejemplo:`padding(horizontal = itemGap, vertical = auxGap)`),El segundo conjunto de fichas combinadas ya no se conserva.
     */
    /** acolchado de virutas más suelto(Con iconos+etiqueta compuesta para texto,como ayudas visuales/Etiqueta de velocidad de voz). */
    val chipInnerLoose = PaddingValues(horizontal = 8.dp, vertical = 4.dp)

    /** Acolchado de virutas más ajustado(Como el margen en línea de TopBar). */
    val chipInnerTight = PaddingValues(horizontal = 4.dp, vertical = 1.dp)

    /** CHAT-18: Deje el final de la lista en blanco(FAB / El campo de entrada cede). */
    val listBottomClearance = 88.dp

    /** CHAT-18: section Sangría inicial del título — Alinear las columnas de texto del título de la fila de la sesión(Margen en línea 16dp + columna de icono 26dp = 42dp). */
    val sectionTitleIndent = 42.dp
}
