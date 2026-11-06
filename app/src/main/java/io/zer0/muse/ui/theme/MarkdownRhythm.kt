package io.zer0.muse.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * Muse ficha de diseño — Ritmo de lectura de artículos largos.(MuseMarkdownRhythm,v2.x).
 *
 * E3 escenario 5 · Calibración del diseño del contenido del chat largo:Markdown "Bloque de código/tabla/bloque de citas/título para respuesta larga /
 * Ritmo vertical de "espaciado de párrafos" bajo el sistema de doble plano. Anteriormente estos intervalos estaban dispersos entre `MarkdownText` en todas partes
 * (8 / 6 / 4 / 2 dp valor desnudo),ni alineados entre sí,El nivel del título y del texto también depende de"diferencia numérica aleatoria"expresión.
 *
 * Aquí, el ritmo de lectura converge en una sola ficha.,por `markdownBlockGap` Conductor unificado(Los bloques de líneas en blanco solo contribuyen al espaciado de párrafos,
 * ya no se separan `Spacer`). Páginas de lectura de alineación y orientación del ritmo:
 *  - **párrafo suelto**:Deje una posición de respiración estable entre párrafos.;
 *  - **uniformidad del bloque**:Los bloques estructurales independientes, como código/tabla/referencia, deben dejarse en blanco en la parte superior e inferior.,Contraste con el texto;
 *  - **El titulo es personal.**:El espacio en blanco encima del título es obviamente más grande que el espacio en blanco debajo.,Manténgalo cerca del texto al que conduce.(principio de proximidad).
 *
 * Las esquinas redondeadas no están definidas aquí —— Los bloques de código/contenedores de tablas están unificados [MuseShapes.medium](12dp,Borrador de diseño"bloque de código"archivo),
 * Camina por el divisor [MusePaddings.dividerThickness] con `onSurface@13%` Color del hilo(Alineación MuseDivider).
 */
object MuseMarkdownRhythm {
    /** espaciado vertical entre párrafos(Cuando el texto original utiliza líneas en blanco para separar párrafos). */
    val paragraphGap = 10.dp

    /**
     * bloque estructural(Código/Tabla/Referencia/Fórmula/Separador)Espaciado de las palabras de contexto.
     * más que [paragraphGap]:Bloques independientes en el flujo de texto."Una sección propia",Se necesitan límites más claros.
     */
    val blockGap = 16.dp

    /** Deje espacio encima del título. */
    val headingSpaceAbove = 18.dp

    /**
     * Deja espacio debajo del título(menos que [headingSpaceAbove]).
     * principio de proximidad:El título se acerca más al texto al que conduce.,más lejos del párrafo anterior,Los lectores pueden ver la atribución de un vistazo.
     */
    val headingSpaceBelow = 8.dp

    /** Espacio reducido entre los elementos de la lista(Incluso si hay líneas en blanco entre las entradas de texto originales,También mantiene compacta la apariencia de la lista.). */
    val listItemGap = 2.dp

    /** Relleno de contenedor de tabla/bloque de código. */
    val blockInner = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

    /** Relleno de celda del encabezado de la tabla. */
    val tableCellHeader = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

    /** Relleno de celda de datos de tabla. */
    val tableCellBody = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
}
