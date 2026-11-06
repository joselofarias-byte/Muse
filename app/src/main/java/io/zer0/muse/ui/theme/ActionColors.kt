package io.zer0.muse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Ficha de color de acción sólida.
 *
 * Antecedentes: anteriormente era posible mezclar tres estilos con controles en los que se podía hacer clic —— Botón principal del color de la marca, contenedor gris translúcido.
 * (surfaceVariant.copy(alpha = 0.5f)) e íconos desnudos, además de íconos apilados cuando se presionan
 * Lavado oscuro translúcido, el conjunto parece una sola capa."máscara negra"Cubre el contenido.
 *
 * En la primera ronda, los calibres se unificaron (todos sólidos, se quitó la máscara alfa), pero el contenedor se usó en Surface.
 * El resultado es que todos los botones son sólo negros, blancos y grises, sin importar qué tema se cambie.
 *
 * Calibre actual:**Los bloques sólidos siempre usan el color del tema.**.
 *  - Operación principal (enviar, confirmar, botón de acción, estado seleccionado)= [container] color principal sólido + [content];
 *  - Bloque sólido secundario (botón de icono de la barra superior, botón de cápsula secundaria)= [tonalContainer] Contenedor de color primario de baja saturación,
 *    Siga el tema pero no robe el foco y evite que la barra superior se llene con bloques de colores muy saturados;
 *  - La clase seleccionada no está seleccionada/deshabilitada = opaco [neutralContainer];
 *  - Todos los contenedores son opacos y no utilizan apilamiento de colores alfa."máscara".
 *
 * [container]/[content] con [tonalContainer]/[tonalContent] Todos están emparejados en ColorScheme.
 * perspectivas/combinación de fondo,12 Ambos conjuntos de temas preestablecidos + temas personalizados definen estos dos pares. Cambiar el tema significa cambiar el color y el contraste lo garantiza el tema.
 */
object MuseActionColors {

    /** Contenedor de operación principal: color principal del tema. */
    val container: Color
        @Composable get() = MaterialTheme.colorScheme.primary

    /** Contenido de la operación principal: y [container] Pares de colores opuestos. */
    val content: Color
        @Composable get() = MaterialTheme.colorScheme.onPrimary

    /** Contenedor sólido secundario: un color de contenedor poco saturado del color primario (botón de icono de la barra superior, etc.). */
    val tonalContainer: Color
        @Composable get() = MaterialTheme.colorScheme.primaryContainer

    /** Contenido sólido secundario: con [tonalContainer] en parejas. */
    val tonalContent: Color
        @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer

    /** Contenedor neutro (control de selección no seleccionado/estado deshabilitado): opaco, reemplaza el color de superposición translúcido original. */
    val neutralContainer: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant

    /** Contenido neutro. */
    val neutralContent: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    /** Texto de descripción secundaria. */
    val mutedContent: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

    /** Contenedor de operaciones peligrosas (retirar/Claro): tema de color incorrecto. */
    val dangerContainer: Color
        @Composable get() = MaterialTheme.colorScheme.error

    /** Contenido de operación peligrosa: con [dangerContainer] Pares de colores invertidos (normalmente texto blanco en temas claros). */
    val dangerContent: Color
        @Composable get() = MaterialTheme.colorScheme.onError

    /** Deshabilite la transparencia del estado (de acuerdo con las convenciones de componentes existentes). */
    const val disabledAlpha = 0.38f
}
