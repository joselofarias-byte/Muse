@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package io.zer0.muse.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquid

/**
 * v2.6: Cubo con efecto de vidrio líquido (motor de vidrio verdadero).
 *
 * ## Cambios sustanciales de v2.5.2 a v2.6
 * v2.5.2 Utilice "Haze 1.5.3 "Fondo difuso + tres elementos autopintados", porque el proyecto estaba en Compose 1.7.x en ese momento,
 * Las bibliotecas de Glass dedicadas requieren Compose 1.10+ (Haze 2.x / backdrop / liquid Misma razón).
 * v2.6 El proyecto se actualizó a Compose 1.10.4, se borró el umbral y se cambió a**motor dedicado**:
 *
 * | estilo | motor | pila de efectos |
 * |---|---|---|
 * | FROST(helado) | kyant `backdrop` | vibrancy + blur + lens(refracción/dispersión) + Highlight + Shadow |
 * | WATER(vaso de agua) | fletchmckee `liquid` | RuntimeShader(liquid):frost/curve/refraction/dispersion/saturation/contrast |
 *
 * ambos de**capa de fondo global**encuadre ([LocalLayerBackdrop] / [LocalWaterGlassState]),
 * en lugar de capturar cada uno —— Por lo tanto, la fuente de muestreo solo se registra una vez para toda la pantalla, compartida por decenas de superficies de vidrio, y el desplazamiento no se duplica.
 *
 * ## Bajar de versión inferior
 * - FROST/WATER El vidrio verdadero requiere API 33+ (RuntimeShader). Por debajo de 33 o sin capa de fondo,
 *   Regresar automáticamente a [Modifier.glassEdgeHighlight] + [Modifier.glassBorder] vidrio falso
 *   (Los mismos tres elementos resaltados que en v2.5.2), apariencia similar, costo cero.
 * - Todas las burbujas de mensajes están hechas de vidrio falso ([glassFakeSurfaceColor])——Docenas de desenfoques reales en una pantalla ralentizarán el desplazamiento.
 *   Este es un límite de rendimiento preservado intencionalmente, consistente con v2.5.2.
 *
 * ## compatibilidad retenida
 * [GlassStyle] / [LiquidGlassConfig] / [GlassIsland] / [glassEdgeHighlight] /
 * [glassFakeSurfaceColor] / [glassBorder] / [LocalLiquidGlass] La firma permanece sin cambios y el punto de llamada no tiene cambios.
 */

/** Estilo de cristal. */
enum class GlassStyle {
    /** vaso de agua — Fino y translúcido, con reflejos nítidos. */
    WATER,

    /** vidrio esmerilado — Blanco lechoso y espeso con reflejos suaves. */
    FROST,
}

/** Configuración global de vidrio líquido;mode=OFF o strength<=0 significa cerrado. */
data class LiquidGlassConfig(
    val style: GlassStyle = GlassStyle.FROST,
    /** intensidad 0f..1f;0 = Cerca. */
    val strength: Float = 0.5f,
    /**
     * v2.6.1: modo original(off/water/frost).
     *
     * fondo:Anteriormente habilitado solo miraba la fuerza, lo que resultaba en“El modo está desactivado pero el control deslizante de intensidad no está 0”tiempo
     * El cristal sigue vigente (prueba real del usuario: el cristal sigue ahí cuando se apaga la animación). ahora habilitado también requiere mode != off.
     */
    val mode: String = MODE_OFF,
) {
    val enabled: Boolean get() = mode != MODE_OFF && strength > 0.01f

    companion object {
        const val MODE_OFF = "off"
        const val MODE_WATER = "water"
        const val MODE_FROST = "frost"

        fun modeFrom(value: String?): String = when (value) {
            MODE_OFF, MODE_WATER, MODE_FROST -> value
            else -> MODE_OFF
        }

        fun styleFrom(mode: String): GlassStyle = if (mode == MODE_WATER) GlassStyle.WATER else GlassStyle.FROST
    }
}

/** API mínima requerida para True Glass Engine (RuntimeShader / RenderEffect completo). */
private const val GLASS_MIN_API = Build.VERSION_CODES.TIRAMISU

/** Si el dispositivo actual admite vidrio verdadero (API 33+). */
fun isRealGlassSupported(): Boolean = Build.VERSION.SDK_INT >= GLASS_MIN_API

/**
 * Puntos de anclaje del parámetro de vidrio (fuerzas 0 y 1 en ambos lados, interpolación lineal en el medio).
 *
 * - tint restringir —— Hacer visible el desenfoque y la refracción (tint Demasiado peso convertirá el vidrio en un bloque gris translúcido)
 * - El vaso de agua es borroso y poco profundo, dependiendo de la refracción./La dispersión apoya la textura; el desenfoque esmerilado es profundo y el tinte/resaltado apoya el grosor
 */
private data class GlassAnchor(
    val blurAt0: Dp,
    val blurAt1: Dp,
    val baseAlphaAt0: Float,
    val baseAlphaAt1: Float,
    val tintAlphaAt0: Float,
    val tintAlphaAt1: Float,
    /** Pico alfa del borde de resaltado superior (compartido con vidrio falso y resaltado). */
    val highlightAt0: Float,
    val highlightAt1: Float,
)

private val ANCHORS = mapOf(
    // Vaso de agua: baja resistencia=Desenfoque muy superficial(claro);alta resistencia=Obviamente esmerilado (los usuarios pueden levantarlo para obtener una sensación de vidrio esmerilado)
    GlassStyle.WATER to GlassAnchor(
        blurAt0 = 2.dp, blurAt1 = 32.dp,
        baseAlphaAt0 = 0.05f, baseAlphaAt1 = 0.20f,
        tintAlphaAt0 = 0.06f, tintAlphaAt1 = 0.26f,
        highlightAt0 = 0.30f, highlightAt1 = 0.55f,
    ),
    // Mate: baja intensidad=Ligeramente esmerilado; alta resistencia=Vidrio esmerilado grueso (borroso y teñido levantado simultáneamente, evitar“Difuso pero transparente”)
    GlassStyle.FROST to GlassAnchor(
        blurAt0 = 6.dp, blurAt1 = 72.dp,
        baseAlphaAt0 = 0.12f, baseAlphaAt1 = 0.55f,
        tintAlphaAt0 = 0.16f, tintAlphaAt1 = 0.58f,
        highlightAt0 = 0.18f, highlightAt1 = 0.32f,
    ),
)

/** Parámetros de vidrio analizados (compartidos por estilos y acumulaciones). */
internal data class ResolvedGlass(
    val blur: Dp,
    val baseAlpha: Float,
    val tintAlpha: Float,
    val highlightAlpha: Float,
    val style: GlassStyle,
)

internal fun resolveGlass(config: LiquidGlassConfig): ResolvedGlass {
    val a = ANCHORS.getValue(config.style)
    val s = config.strength.coerceIn(0f, 1f)
    fun lerp(x: Float, y: Float) = x + (y - x) * s
    return ResolvedGlass(
        blur = Dp(lerp(a.blurAt0.value, a.blurAt1.value)),
        baseAlpha = lerp(a.baseAlphaAt0, a.baseAlphaAt1),
        tintAlpha = lerp(a.tintAlphaAt0, a.tintAlphaAt1),
        highlightAlpha = lerp(a.highlightAt0, a.highlightAt1),
        style = config.style,
    )
}

/** color base de tinte de vidrio (trazo/vidrio falso/Highlight Fuente de consistencia con el color base). */
internal fun ResolvedGlass.surfaceTint(surfaceColor: Color): Color =
    surfaceColor.copy(alpha = (tintAlpha + baseAlpha * 0.35f).coerceIn(0f, 0.85f))

/**
 * v2.6: FROST vidrio real —— kyant backdrop motor.
 *
 * Pila de efectos:vibrancy(Aumentar la saturación)+ blur(borroso)+ lens(refracción/dispersión),
 * Además del borde resaltado superior (Highlight) y proyección (Shadow), y finalmente aplique una capa de tinte translúcido.
 *
 * debe ser colgado [LocalLayerBackdrop]Utilizado en el árbol (capa de contenido);backdrop cuando es nulo
 * Las personas que llaman deberían recurrir a fakeGlass([glassIslandModifier] La sucursal ha sido procesada).
 */
@Composable
fun Modifier.frostGlass(backdrop: LayerBackdrop?, shape: Shape, surfaceColor: Color, config: LiquidGlassConfig): Modifier {
    if (backdrop == null || !config.enabled) return this
    val p = resolveGlass(config)
    val tint = p.surfaceTint(surfaceColor)
    // v2.6.0 fix: La fuerza realmente impulsa los parámetros centrales —— refracción de la lente original/Radio de sombra codificado,
    // La fuerza solo se puede cambiar para desenfocar(14→4dp La brecha es pequeña)con reflejos alpha,Es casi invisible a simple vista.
    // Ahora refracciones, desenfoques, reflejos,tint Todos varían linealmente con la intensidad.,Y ampliar la brecha entre los dos extremos.
    val s = config.strength.coerceIn(0f, 1f)
    val edgeWidth = 1.dp
    val refractionHeight = (4f + 20f * s).dp
    val refractionAmount = (4f + 26f * s).dp
    val shadowRadius = (4f + 18f * s).dp
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(p.blur.toPx())
            lens(
                refractionHeight = refractionHeight.toPx(),
                refractionAmount = refractionAmount.toPx(),
                chromaticAberration = s > 0.4f,
            )
        },
        highlight = {
            Highlight(
                width = edgeWidth,
                blurRadius = edgeWidth * 2.4f,
                alpha = p.highlightAlpha,
            )
        },
        shadow = {
            Shadow(
                radius = shadowRadius,
                color = Color.Black.copy(alpha = if (surfaceColor.luminance() >= 0.5f) 0.10f else 0.18f),
            )
        },
        onDrawSurface = {
            drawRect(tint)
        },
    )
}

/**
 * v2.6: WATER vidrio real —— fletchmckee liquid motor (RuntimeShader).
 *
 * con [frostGlass] La diferencia: tómalo en serio·Sombreador de refracción (curve/frost/refraction/dispersion),
 * La apariencia y el tacto se acercan más al vidrio líquido. La persona que llama primero debe colgarlo en la capa de contenido. `Modifier.liquefiable(state)`.
 */
@Composable
fun Modifier.waterGlass(state: LiquidState?, shape: Shape, surfaceColor: Color, config: LiquidGlassConfig): Modifier {
    if (state == null || !config.enabled) return this
    val p = resolveGlass(config)
    val light = surfaceColor.luminance() >= 0.5f
    val tint = p.surfaceTint(surfaceColor)
    // v2.6.0 fix: La fuerza realmente impulsa los parámetros centrales —— Originalmente todo escrito hasta la muerte.,La fuerza tiene poco efecto sobre el vaso soluble.
    // ahora frost/curve/refraction/dispersion/saturation/contrast todos varían con la intensidad:
    // baja intensidad → película ligera(baja refracción/Baja dispersión/casi lineal);Alta resistencia → vidrio grueso(fuerte refracción/Fuerte dispersión/alto contraste).
    val params = waterGlassParams(light, config.strength.coerceIn(0f, 1f))
    return this.liquid(state) {
        this.shape = shape
        this.frost = params.frost.dp
        this.curve = params.curve
        this.refraction = params.refraction
        this.dispersion = params.dispersion
        this.saturation = params.saturation
        this.contrast = params.contrast
        this.tint = tint
    }
}

/**
 * Parámetros del vaso de agua(Sacar y bajar [waterGlass] La complejidad ciclomática de).
 *
 * @param light ¿El color base es más brillante?(Determinar la combinación de la capa base y el rango de intensidad.)
 * @param s intensidad 0..1
 */
private data class WaterGlassParams(
    val frost: Float,
    val curve: Float,
    val refraction: Float,
    val dispersion: Float,
    val saturation: Float,
    val contrast: Float,
)

private fun waterGlassParams(light: Boolean, s: Float): WaterGlassParams = WaterGlassParams(
    frost = (if (light) 4f else 6f) + (if (light) 20f else 24f) * s,
    curve = (if (light) 0.22f else 0.16f) + (if (light) 0.24f else 0.22f) * s,
    refraction = (if (light) 0.04f else 0.03f) + (if (light) 0.14f else 0.11f) * s,
    dispersion = (if (light) 0.05f else 0.04f) + (if (light) 0.22f else 0.16f) * s,
    saturation = (if (light) 0.20f else 0.16f) + (if (light) 0.34f else 0.28f) * s,
    contrast = (if (light) 1.05f else 1.12f) + (if (light) 0.24f else 0.34f) * s,
)

/**
 * v2.5.2: Laminación de tres elementos con borde de vidrio —— Fuente de textura de vidrio falso (sin desenfoque).
 *
 * Dibuja en orden:
 * 1. Borde resaltado superior: el blanco se desvanece de arriba a abajo (la principal fuente del grosor del vidrio)
 * 2. Borde oscuro en la parte inferior: el negro se desvanece ligeramente de abajo hacia arriba (sensación de volumen)
 * 3. Escaneo de luz oblicua: aprox. 35° Degradado lineal blanco (reflejo de luz ambiental)
 *
 * v2.6 No solo sirve para degradar las versiones inferiores, sino que también sirve para la superposición opcional de "agregar una capa de luz de borde al vidrio real".
 */
fun Modifier.glassEdgeHighlight(shape: Shape, config: LiquidGlassConfig): Modifier = this.clip(shape).drawWithContent {
    drawContent()
    val p = resolveGlass(config)
    val w = size.width
    val h = size.height
    val hi = p.highlightAlpha

    // 1. borde resaltado superior —— 1.5dp Del interior brillante al transparente
    val topBand = 1.5.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = hi),
            1f to Color.Transparent,
            startY = 0f,
            endY = topBand,
        ),
        size = Size(w, topBand),
    )
    // 2. Borde oscuro en la parte inferior —— 1dp De oscuro a transparente (sensación de volumen)
    val bottomBand = 1.dp.toPx()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = hi * 0.35f),
            startY = h - bottomBand,
            endY = h,
        ),
        topLeft = Offset(0f, h - bottomBand),
        size = Size(w, bottomBand),
    )
    // 3. escaneo de luz oblicua —— De arriba a la izquierda a abajo a la derecha, el blanco se desvanece (luz ambiental)
    drawRect(
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = hi * 0.55f),
            0.45f to Color.Transparent,
            start = Offset(0f, 0f),
            end = Offset(w * 0.85f, h),
        ),
    )
}

/**
 * v2.5.2: fondo de vidrio falso —— Úselo para superficies rodantes de alta frecuencia, como burbujas (sin difuminar).
 *
 * Reemplace el desenfoque con un degradado vertical: ligeramente más claro en la parte superior (luz ambiental), ligeramente más oscuro en la parte inferior (volumen), superpuesto al color base.
 * cooperar [glassEdgeHighlight] Puede obtener una apariencia similar al vidrio a un costo casi nulo.
 */
fun glassFakeSurfaceColor(base: Color, config: LiquidGlassConfig): Brush {
    val (top, mid, bottom) = fakeGlassStops(base, config)
    return Brush.verticalGradient(top, mid, bottom)
}

// Mantiene el vidrio oscuro sin convertirlo en una superficie gris clara.
internal fun fakeGlassStops(base: Color, config: LiquidGlassConfig): List<Pair<Float, Color>> {
    val p = resolveGlass(config)
    val lift = 0.10f * (p.highlightAlpha / 0.55f).coerceIn(0.3f, 1f)
    val depth = ((p.baseAlpha + p.tintAlpha) * 0.5f).coerceIn(0f, 0.55f)
    fun lerpTo(target: Color, alpha: Float) = androidx.compose.ui.graphics.lerp(base, target, alpha.coerceIn(0f, 1f))
    return if (base.luminance() < 0.5f) {
        val lifted = androidx.compose.ui.graphics.lerp(base, Color.White, 0.12f)
        fun onLifted(target: Color, alpha: Float) = androidx.compose.ui.graphics.lerp(lifted, target, alpha.coerceIn(0f, 1f))
        listOf(
            0f to onLifted(Color.White, 0.05f + lift * 0.06f),
            0.45f to onLifted(Color.Black, depth * 0.35f),
            1f to onLifted(Color.Black, depth * 0.70f),
        )
    } else {
        listOf(
            0f to lerpTo(Color.White, p.baseAlpha + lift),
            0.55f to lerpTo(Color.White, p.baseAlpha + p.tintAlpha * 0.6f),
            1f to lerpTo(Color.White, p.baseAlpha + p.tintAlpha),
        )
    }
}

/**
 * v2.5.2: trazos de vidrio —— Un borde de 1 px con oscuridad superior e inferior brillante para mejorar aún más el"pieza de vidrio"borde.
 * con [glassEdgeHighlight] Úselos juntos (resalte primero y luego trace).
 */
fun Modifier.glassBorder(shape: Shape, config: LiquidGlassConfig): Modifier {
    val p = resolveGlass(config)
    return this.border(
        width = 0.8.dp,
        brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = p.highlightAlpha * 0.9f),
            0.5f to Color.White.copy(alpha = p.highlightAlpha * 0.25f),
            1f to Color.Black.copy(alpha = p.highlightAlpha * 0.22f),
        ),
        shape = shape,
    )
}

/**
 * v2.6: Aspecto uniforme del acristalamiento (para [GlassIsland] etcétera).
 *
 * Orden de decisión:
 * 1. FROST + Hay una capa de fondo + API 33+ → [frostGlass]
 * 2. WATER + si liquidState + API 33+ → [waterGlass]
 * 3. El resto (versión inferior/sin capas/apagado)→ Vidrio falso:[glassFakeSurfaceColor] + Resaltado + Trazo
 */
@Composable
internal fun glassSurfaceModifier(
    shape: Shape,
    surfaceColor: Color,
    config: LiquidGlassConfig,
    backdrop: LayerBackdrop?,
    waterState: LiquidState?,
): Modifier {
    val realGlass = config.enabled && isRealGlassSupported()
    return when {
        realGlass && config.style == GlassStyle.FROST && backdrop != null ->
            Modifier.frostGlass(backdrop, shape, surfaceColor, config)
        realGlass && config.style == GlassStyle.WATER && waterState != null ->
            Modifier.waterGlass(waterState, shape, surfaceColor, config)
        else ->
            // Degradación: vidrio falso (base degradada + resaltar tres elementos + trazo)
            Modifier
                .clip(shape)
                .background(glassFakeSurfaceColor(surfaceColor, config))
                .glassEdgeHighlight(shape, config)
                .glassBorder(shape, config)
    }
}

/**
 * v2.6.1: Modificador de apariencia de vidrio falso —— ventana independiente(Popup / Dialog / BottomSheet)Para uso interno.
 *
 * La fuente de fondo de la ventana principal no se puede seleccionar en todas las ventanas,El vidrio real hará que la pantalla se vuelva negra;Aquí use directamente la base de degradado + resaltado de borde + trazo,
 * Exprese la textura del vidrio sin depender del fondo. La configuración y el color base se toman del tema actual.
 */
@Composable
fun Modifier.fakeGlassSurface(shape: Shape, surfaceColor: Color): Modifier {
    val config = LocalLiquidGlass.current
    return this
        .clip(shape)
        .background(glassFakeSurfaceColor(surfaceColor, config))
        .glassEdgeHighlight(shape, config)
        .glassBorder(shape, config)
}

/** Instantánea global de la configuración del vidrio líquido;MainActivity Proporcionado después de recopilar el flujo de configuración. Desactivado de forma predeterminada. */
val LocalLiquidGlass = staticCompositionLocalOf { LiquidGlassConfig(strength = 0f) }

/**
 * v2.6: Capa de fondo global FROST (kyant backdrop). Para capa de contenido `Modifier.layerBackdrop(it)` registro;
 * null = Glass no está habilitado ni es compatible.
 */
val LocalLayerBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * v2.6: Estado mundial del AGUA (fletchmckee liquid). Para capa de contenido `Modifier.liquefiable(it)` registro;
 * null = Glass no está habilitado ni es compatible.
 */
val LocalWaterGlassState = staticCompositionLocalOf<LiquidState?> { null }

/**
 * v2.6: ¿El fondo detrás del cristal está oscuro?
 *
 * El vidrio es translúcido, el color del contenido de la isla debe coincidir“fondo que brilla”Sólo en comparación se puede ver con claridad.
 * Determinado por la página en función de su propio fondo (sin fondo/gradiente de luz=false, degradado oscuro/Imagen de fondo oscuro=true),
 * [GlassIsland] Con el título de la columna superior seleccionado en consecuencia, la profundidad/Color de contenido claro.
 */
val LocalGlassIsBackgroundDark = staticCompositionLocalOf { false }
