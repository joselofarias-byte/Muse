package io.zer0.muse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.zer0.common.Logger
import java.io.File

/**
 * Muse Especificaciones de fuente(Solución completa para alinear el diseño frontal §3).
 *
 * Estrategia de fuentes(Decisiones clave contra la IA):
 *  - Todos usan fuentes del sistema.(FontFamily.Default),No introduzcas ninguna fuente personalizada
 *  - La representación de fuentes del sistema es la más clara, la de carga más rápida y la que más se ajusta a los hábitos de lectura de los usuarios de la plataforma.
 *  - Para bloques de código FontFamily.Monospace(Fuente monoespaciada del sistema)
 *  - Eliminar original Inter / Noto Serif SC / JetBrains Mono Google Fonts depender de
 *
 * Sistema de tamaño de fuente(Jerarquía estándar de alineación):
 *   - titular  displayLarge  34 Bold   "dialogo" / "memoria" / "ajustes" parte superior de la página
 *   - Titular del título Título de sesión pequeño 20 SemiBold / título de grupo establecido
 *   - Cuerpo del textoGrande 16 Regular Contenido del mensaje/texto de configuración
 *   - Cuerpo auxiliarMedio 14 Vista previa del mensaje regular / Proceso de pensamiento
 *   - Cuerpo del comentarioPequeño 12 Marca de tiempo normal/Etiqueta de modelo
 *   - TabEtiqueta de textoPequeña 11 Etiqueta de pestaña inferior mediana(M-TP2:≥11sp Tamaño de fuente mínimo que cumpla con los requisitos de accesibilidad.)
 *
 * altura de fila:Envía mensajes de texto 1,6 veces(16sp → 25.6sp),Más holgado que el 1,4-1,5 normal,
 *      Reduce significativamente la fatiga al leer las respuestas de la IA durante largos períodos de tiempo.
 */
val MuseTypography: Typography = Typography(
    // ── capa de título ───────────────────────────────────────────────────────────
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),

    // ── capa de título ───────────────────────────────────────────────────────────
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),

    // ── Capa de texto(Fuerza principal del mensaje de chat.) ──────────────────────────────────────────
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.6.sp, // 1.6 Duplicar la altura de la fila,Los artículos largos son cómodos de leer.
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.4.sp, // 1.6 Duplicar la altura de la fila
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.8.sp, // L-TP3: 1.6 Duplicar la altura de la fila,Consistente con el cuerpo Sistema grande/cuerpo mediano
    ),

    // ── capa de etiqueta(Botón/Pestaña/Línea de acción) ────────────────────────────────────
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp, // Las etiquetas compactas también siguen siendo legibles, nada menos que 12sp
        lineHeight = 17.sp,
    ),
)

/** Fuente monoespaciada de bloque de código(sistema Monospace,No se introdujeron fuentes personalizadas). */
val MuseMonoFontFamily: FontFamily = FontFamily.Monospace

/**
 * E2: Cargar fuente de cuerpo personalizada desde la ruta del archivo(Usuarios importados vía SAF TTF/OTF).
 *
 * Archivo de fuente no válido(Eliminado/dañado/sin fuente)Volver a los valores predeterminados del sistema y registrar registros——Este es el archivo importado por el usuario.
 * Tolerancia a fallos empresariales,No permita que un archivo incorrecto destruya toda la situación UI;La persona que llama debe estar en remember(path) El caché da como resultado,
 * Evite reorganizarse cada vez createFromFile(Disco de sincronización IO).
 *
 * @param path Ruta absoluta al archivo de fuente;null/Déjelo en blanco y regrese directamente al valor predeterminado del sistema.
 */
fun loadCustomFontFamily(path: String?): FontFamily {
    if (path.isNullOrBlank()) return FontFamily.Default
    return try {
        // createFromFile Aquí solo se realiza la verificación de la validez del archivo.(Excepción de lanzamiento no válida),No se utiliza para la construcción.——
        // Compose 1.7 La fuente no tiene constructor tipográfico.,Usar en su lugar Font(file) Carga diferida del mismo archivo
        android.graphics.Typeface.createFromFile(path)
        FontFamily(Font(File(path)))
    } catch (e: java.io.IOException) {
        // El archivo de fuente no existe o no es legible
        Logger.w("MuseTypography", "Error al cargar la fuente personalizada,Volver a los valores predeterminados del sistema: ${e.message}")
        FontFamily.Default
    } catch (e: RuntimeException) {
        // Typeface genera una excepción de tiempo de ejecución cuando el archivo de fuente está dañado o el formato no es compatible
        Logger.w("MuseTypography", "Error al cargar la fuente personalizada,Volver a los valores predeterminados del sistema: ${e.message}")
        FontFamily.Default
    }
}

/**
 * E2: Reemplaza la familia de fuentes de todos los estilos de tipografía con la fuente especificada.
 *
 * Reemplazar solo fontFamily,Tamaño de fuente reservado/altura de fila/Peso de fuente(El tamaño de fuente se escala por [scaled] independiente y responsable).
 * Los bloques de código no se ven afectados——Ellos usan explícitamente [MuseMonoFontFamily],Sin pasar por la Tipografía global.
 */
fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

/**
 * Escala del tamaño de fuente — Aumente o reduzca el tamaño de fuente/altura de línea de toda la tipografía.
 *
 * 4 archivo: small=0.85x / medium=1.0x(Predeterminado) / large=1.15x / xlarge=1.3x.
 * Sólo escalar el tamaño del texto,No afecta el peso de la fuente ni la familia de fuentes.
 *
 * @param scale Rango de tamaño de fuente "small" / "medium" / "large" / "xlarge"
 */
fun Typography.scaled(scale: String): Typography {
    val factor = when (scale) {
        "small" -> 0.85f
        "medium" -> 1.0f // v1.0.47: Enumerar explícitamente los engranajes predeterminados,Evite que el medio se utilice de otra manera"desconocido"Pantalla de actualización de alarma
        "large" -> 1.15f
        "xlarge" -> 1.3f
        else -> {
            // L-6: El engranaje desconocido regresa silenciosamente al medio, lo que no favorece la resolución de problemas,Advertir y retroceder aquí medium(1.0x).
            Logger.w("MuseTypography", "desconocido fontSizeScale=$scale,revertir medium")
            1.0f
        }
    }
    if (factor == 1.0f) return this
    return copy(
        displayLarge = displayLarge.scale(factor),
        displayMedium = displayMedium.scale(factor),
        displaySmall = displaySmall.scale(factor),
        headlineLarge = headlineLarge.scale(factor),
        headlineMedium = headlineMedium.scale(factor),
        headlineSmall = headlineSmall.scale(factor),
        titleLarge = titleLarge.scale(factor),
        titleMedium = titleMedium.scale(factor),
        titleSmall = titleSmall.scale(factor),
        bodyLarge = bodyLarge.scale(factor),
        bodyMedium = bodyMedium.scale(factor),
        bodySmall = bodySmall.scale(factor),
        labelLarge = labelLarge.scale(factor),
        labelMedium = labelMedium.scale(factor),
        labelSmall = labelSmall.scale(factor),
    )
}

/** Un único TextStyle escala proporcionalmente fontSize / lineHeight / letterSpacing. */
private fun TextStyle.scale(factor: Float): TextStyle {
    // L-TP4: letterSpacing Escalar por factor sincrónicamente,Evite espacios ajustados en tamaños de fuente grandes.
    // no establecido explícitamente(Unspecified)permanecer como está,No fuerza el interletraje.
    val scaledLetterSpacing = if (letterSpacing != TextUnit.Unspecified) {
        letterSpacing * factor
    } else {
        letterSpacing
    }
    // L-7: fontSize / lineHeight También hay que estar vigilado. Unspecified,De lo contrario Unspecified * factor
    // Inyectará valores inesperados(Aunque la copia conservará el valor original.,Pero la semántica de guardia explícita es más clara y está alineada con el espaciado entre letras.).
    val scaledFontSize = if (fontSize != TextUnit.Unspecified) fontSize * factor else fontSize
    val scaledLineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight * factor else lineHeight
    return copy(
        fontSize = scaledFontSize,
        lineHeight = scaledLineHeight,
        letterSpacing = scaledLetterSpacing,
    )
}
