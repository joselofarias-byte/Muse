package io.zer0.muse.ui.theme

import androidx.compose.material3.ColorScheme
import dynamiccolor.ColorSpecs
import dynamiccolor.DynamicScheme
import dynamiccolor.Variant
import hct.Hct
import io.zer0.material3.toColorScheme
import kotlinx.serialization.Serializable
import palettes.TonalPalette
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * v1.97 gap7: Tema definido por el usuario — Genere un ColorScheme completo basado en el color de la semilla.
 *
 * El usuario selecciona en la página de configuración. 1~3 color de semilla(primary Requerido,secondary/tertiary Opcional),
 * llamar [generateColorScheme] Espacio de color HCT a través de Material Color Utilities
 * con algoritmo Variant.TONAL_SPOT(Android 12+ Fondo de pantalla color dinámico mismo estilo.)La coincidencia de colores completa se deriva automáticamente.
 *
 * persistencia:Serializar a JSON y guardar DataStore Preferences(ver SettingsRepository.customThemesFlow).
 * con [PresetTheme] la diferencia:PresetTheme Se fija mediante ajuste manual. ColorScheme;CustomTheme Generado algorítmicamente,
 * Los usuarios solo necesitan seleccionar el color de la semilla.,No es necesario comprender los más de 50 campos de ColorScheme.
 *
 * Instrucciones de implementación:Implementación existente CustomTheme.kt(Apache 2.0).
 *
 * @param id identificador único(Generado automáticamente UUID)
 * @param name Nombre visible del usuario(Permitir cadena vacía,UI Retroceso cuando se muestra"Sin nombre")
 * @param primaryColorArgb color principal de la semilla ARGB(Verde laurel predeterminado,Consistente con los colores de la marca Muse.)
 * @param secondaryColorArgb color secundario de la semilla,null Indica que se deriva automáticamente del primario.
 * @param tertiaryColorArgb Color de semilla de tercer nivel,null Indica que se deriva automáticamente del primario.
 */
@Serializable
@OptIn(ExperimentalUuidApi::class)
data class CustomTheme(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val primaryColorArgb: Long = DEFAULT_PRIMARY_ARGB,
    val secondaryColorArgb: Long? = null,
    val tertiaryColorArgb: Long? = null,
) {
    /**
     * Generar según el color de la semilla. [dark] o completo en modo ligero [ColorScheme].
     *
     * cadena de algoritmos:
     *  1. color principal de la semilla → [Hct.fromInt] Obtenga representación del espacio de color HCT
     *  2. usar [ColorSpecs] Derivar 6 por versión de especificación TonalPalette
     *     (primary/secondary/tertiary/neutral/neutralVariant/error)
     *  3. Si se especifica explícitamente secundario/terciario,usar [TonalPalette.fromInt] Correspondencia de cobertura palette
     *  4. montar [DynamicScheme] → llamar [toColorScheme] La expansión se convierte en Compose ColorScheme
     *
     * uso Variant.TONAL_SPOT — El mismo que se usa para los colores dinámicos del fondo de pantalla de Android 12+ variant,
     * Asegúrese de que los resultados generados sean coherentes con el estilo de color dinámico a nivel del sistema.contrastLevel=0.0 Indica contraste estándar
     * (línea base de especificación de diseño,-1 es el contraste más bajo,1 para mayor contraste).
     *
     * @param dark true generar colores oscuros ColorScheme,false generar color de luz
     * @return completo Compose Material3 ColorScheme(Contiene gradiente de contenedor de superficie y color fijo.)
     */
    fun generateColorScheme(dark: Boolean): ColorScheme {
        val sourceHct = Hct.fromInt(primaryColorArgb.toInt())
        val specVersion = DynamicScheme.DEFAULT_SPEC_VERSION
        val platform = DynamicScheme.DEFAULT_PLATFORM
        val contrastLevel = 0.0
        val colorSpec = ColorSpecs.get(specVersion)

        val primaryPalette = colorSpec.getPrimaryPalette(
            Variant.TONAL_SPOT,
            sourceHct,
            dark,
            platform,
            contrastLevel,
        )
        // secondary/tertiary Cuando no se especifica,Derivado automáticamente de primario(Comportamiento predeterminado,Consistente con el color dinámico del sistema.)
        val secondaryPalette = if (secondaryColorArgb != null) {
            TonalPalette.fromInt(secondaryColorArgb.toInt())
        } else {
            colorSpec.getSecondaryPalette(
                Variant.TONAL_SPOT,
                sourceHct,
                dark,
                platform,
                contrastLevel,
            )
        }
        val tertiaryPalette = if (tertiaryColorArgb != null) {
            TonalPalette.fromInt(tertiaryColorArgb.toInt())
        } else {
            colorSpec.getTertiaryPalette(
                Variant.TONAL_SPOT,
                sourceHct,
                dark,
                platform,
                contrastLevel,
            )
        }

        val scheme = DynamicScheme(
            sourceHct,
            Variant.TONAL_SPOT,
            dark,
            contrastLevel,
            platform,
            specVersion,
            primaryPalette,
            secondaryPalette,
            tertiaryPalette,
            colorSpec.getNeutralPalette(
                Variant.TONAL_SPOT,
                sourceHct,
                dark,
                platform,
                contrastLevel,
            ),
            colorSpec.getNeutralVariantPalette(
                Variant.TONAL_SPOT,
                sourceHct,
                dark,
                platform,
                contrastLevel,
            ),
            colorSpec.getErrorPalette(
                Variant.TONAL_SPOT,
                sourceHct,
                dark,
                platform,
                contrastLevel,
            ),
        )
        return scheme.toColorScheme()
    }

    companion object {
        /**
         * Color de semilla principal predeterminado — verde laurel(Consistente con los colores de la marca Muse.).
         * v1.0.74 fix (Auditoría inicial 3.10): El valor original 0xFF2D8C5F es la versión anterior. LaurelGreen,
         * Color.kt v1.0.21 ha sido cambiado a #2A7A55,La alineación sincrónica aquí evita la desviación del color predeterminado del tema personalizado.
         *
         * Representado por ARGB Long(Alto 8 bits alpha=0xFF),Evite la confusión causada por la representación negativa de números Int.
         */
        const val DEFAULT_PRIMARY_ARGB: Long = 0xFF2A7A55L
    }
}
