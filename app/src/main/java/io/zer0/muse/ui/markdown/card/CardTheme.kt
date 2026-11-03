package io.zer0.muse.ui.markdown.card

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * El valor exacto del tema de la tarjeta.
 *
 * La carcasa de la tarjeta tiene un conjunto de variables estáticas predeterminadas (siguiendo `prefers-color-scheme`), suficiente para ser utilizado en escenarios "sin host".
 * El suplemento aquí es**valor exacto**: El tema real de la aplicación actual, incluidos los colores cambiados por el usuario.
 *
 * El método consiste en inyectar la variable con el mismo nombre después del valor predeterminado estático y confiar en la cobertura en cascada. —— Entonces:
 *
 *  - Cuando hay un host, los colores de la tarjeta y la interfaz nativa son los mismos;
 *  - Cuando no hay un host, el valor predeterminado estático aún se establece y no se vuelve transparente.
 *
 * Los nombres de las variables deben ser consistentes con los existentes, de lo contrario los dos sistemas coexistirán y el modelo no sabrá cuál usar.
 */
internal object CardTheme {

    /** Generar bloques variables para cobertura (entrada cómoda en fase de combinación, equivalente a `cssOf(MaterialTheme.colorScheme)`). */
    @Composable
    @ReadOnlyComposable
    fun css(): String = cssOf(MaterialTheme.colorScheme)

    /**
     * Encuentra el bloque variable dada la combinación de colores —— Función pura.
     *
     * Está separado para que lo use la persona que llama. `remember(scheme)` Caché: la altura de la tarjeta se reorganizará cada vez que se informe.
     * Y este CSS se trata sólo de combinación de colores. En el pasado, se reorganizaban treinta o cuarenta líneas de variables cada vez que se reorganizaban, y debido a que era
     * `remember(spec, bridge, themeCss, ...)` La comparación de igualdad de contenido de claves y cadenas también debe recorrer toda la sección.
     * El mismo después del almacenamiento en caché. [scheme] Las instancias devuelven la misma referencia y la comparación degenera en igualdad de referencia.
     */
    fun cssOf(scheme: ColorScheme): String {
        val dark = scheme.background.luminance() < 0.5f
        return buildString {
            append(":root{")
            appendSurfaces(scheme)
            appendInk(scheme)
            appendAccents(scheme)
            appendSemantic(scheme)
            appendDataColors(dark)
            appendMetrics()
            append("}")
        }
    }

    /** Superficie: La superficie de la tarjeta y la capa de hundimiento.`--muse-bg` Manténgalo transparente, es la parte inferior de la página, no el lado de la tarjeta. */
    private fun StringBuilder.appendSurfaces(scheme: ColorScheme) {
        declare("--muse-surface", scheme.surface)
        declare("--muse-surface-2", scheme.surfaceVariant)
        declare("--muse-surface-3", scheme.surfaceContainerHigh)
        declare("--muse-card-line-soft", scheme.outlineVariant.copy(alpha = 0.55f))
    }

    /** Tinta: texto de nivel 3. */
    private fun StringBuilder.appendInk(scheme: ColorScheme) {
        declare("--muse-fg", scheme.onSurface)
        declare("--muse-muted", scheme.onSurfaceVariant)
        declare("--muse-faint", scheme.outline)
        declare("--muse-border", scheme.outlineVariant)
    }

    /** Destacado: verde laurel. El único color que interactúa con el foco. */
    private fun StringBuilder.appendAccents(scheme: ColorScheme) {
        declare("--muse-accent", scheme.primary)
        declare("--muse-accent-fg", scheme.onPrimary)
        declare("--muse-accent-soft", scheme.primaryContainer)
        declare("--muse-accent-soft-fg", scheme.onPrimaryContainer)
    }

    /** Semántica: un acierto, un aviso, un error. Restricción de cantidad. */
    private fun StringBuilder.appendSemantic(scheme: ColorScheme) {
        declare("--muse-good", scheme.tertiary)
        declare("--muse-warn", Color(0xFFB07D20))
        declare("--muse-danger", scheme.error)
    }

    /**
     * Seis colores utilizados para la codificación de datos.
     *
     * Separado de los colores de acento: los colores de acento solo interactúan y los colores de datos solo codifican datos. La selección de color se encuentra en el rango de saturación baja.
     * Y trate de mantenerse cerca de la gama de colores adyacente de verde laurel para evitar la aparición repentina de rojo y violeta muy saturados en la pantalla.
     */
    private fun StringBuilder.appendDataColors(dark: Boolean) {
        val palette = if (dark) {
            listOf(
                Color(0xFF4A9F70),
                Color(0xFF5C8FA8),
                Color(0xFFC08A4E),
                Color(0xFF9A7FB0),
                Color(0xFFB06A62),
                Color(0xFF8C8A5A),
            )
        } else {
            listOf(
                Color(0xFF2A7A55),
                Color(0xFF416F98),
                Color(0xFFA86F35),
                Color(0xFF7D6394),
                Color(0xFF9D5750),
                Color(0xFF6D7843),
            )
        }
        palette.forEachIndexed { index, color -> declare("--muse-d${index + 1}", color) }
    }

    /** Espaciado, esquinas redondeadas, fuentes y medidas. El valor numérico es el borrador final y todo el uso se basa en variables. */
    private fun StringBuilder.appendMetrics() {
        append("--muse-gap-xs:4px;--muse-gap-sm:8px;")
        // El espaciado es de cinco pasos. Todo el relleno/espacios en la tarjeta se toman desde aquí, no se escriben números mágicos.
        append("--muse-s1:4px;--muse-s2:8px;--muse-s3:12px;")
        append("--muse-s4:16px;--muse-s5:24px;--muse-s6:32px;")
        append("--muse-radius-lg:16px;")
        // Utilice serifas sólo para títulos y citas; Utilice sans-serifs para el texto para garantizar la legibilidad en tamaños de fuente pequeños.
        append("--muse-serif:'Noto Serif SC','Source Han Serif SC','Songti SC',serif;")
        append("--muse-sans:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;")
        append("--muse-mono:'JetBrains Mono',ui-monospace,monospace;")
        // Ancho de lectura.runtime Se anulará con el ancho preferido declarado.
        append("--muse-measure:100%;")
    }

    /**
     * Escribe una variable.
     *
     * Primero sobrescriba la entrada del host. `--muse-theme-<nombre>`y luego proporcione el valor calculado al tema actual.
     * Cuando el anfitrión quiere cambiar el diseño de toda la tarjeta, puede sobrescribir la anterior sin cambiar el estilo propio de la tarjeta.
     */
    private fun StringBuilder.declare(name: String, value: Color) {
        val themeKey = name.removePrefix("--muse-")
        append(name).append(":var(--muse-theme-").append(themeKey).append(',')
        append(value.toCssHex()).append(");")
    }
}

/** Color a CSS hexadecimal. Cuando se utiliza alfa, se emite rgba para garantizar que no se trague la translucidez. */
internal fun Color.toCssHex(): String {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return if (a >= 255) {
        "#%02X%02X%02X".format(r, g, b)
    } else {
        "rgba($r,$g,$b,${"%.3f".format(a / 255f)})"
    }
}
