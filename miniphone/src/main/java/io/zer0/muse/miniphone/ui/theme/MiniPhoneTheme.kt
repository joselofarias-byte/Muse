package io.zer0.muse.miniphone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * El pequeño teléfono celular tiene su propia paleta de colores.
 *
 * Este módulo no hereda el tema anfitrión.:La combinación de colores del anfitrión se ajusta al entorno de trabajo del chat.(Vidrio, degradado, piel de expresión.),
 * Lo que quieren los celulares pequeños es la textura del papel —— base blanca cálida,Verde oscuro para dar énfasis,El rojo ladrillo sólo queda sin leer.
 *
 * El color oscuro no es una simple inversión del color claro.:El fondo es casi negro.,Mueve la tarjeta hacia arriba,Los colores destacados garantizan la legibilidad,
 * Cambia tus propias burbujas a fondo claro y personajes oscuros.(Presionar palabras blancas sobre un fondo profundo no proporciona suficiente contraste.).
 */
@Immutable
@Suppress("LongParameterList")
data class MiniPhoneColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    val unreadDot: Color,
    val unreadDotText: Color,
    val bubbleSelf: Color,
    val bubbleSelfText: Color,
    val bubbleOther: Color,
    val bubbleOtherText: Color,
    val bubbleOtherBorder: Color,
    val wallpaperTop: Color,
    val wallpaperBottom: Color,
)

/** Color claro cuarta marcha:Abajo, tarjeta, palabra primaria, palabra secundaria. */
private val LightBackground = Color(0xFFF5F3EF)
private val LightSurface = Color(0xFFFFFFFF)
private val LightTextPrimary = Color(0xFF1C1B19)
private val LightTextSecondary = Color(0xFF8A857D)
private val LightDivider = Color(0xFFE6E2DA)
private val LightAccent = Color(0xFF4F6E5A)
private val LightUnread = Color(0xFFC4644F)

/** Color oscuro cuatro niveles. */
private val DarkBackground = Color(0xFF12110F)
private val DarkSurface = Color(0xFF24211D)
private val DarkTextPrimary = Color(0xFFEDE9E3)
private val DarkTextSecondary = Color(0xFF8F887D)
private val DarkDivider = Color(0xFF332F29)
private val DarkAccent = Color(0xFF7C9B85)

/** muestras de colores claros:Papel de arroz blanco cálido + verde oscuro. */
fun lightMiniPhoneColors(): MiniPhoneColors = MiniPhoneColors(
    isDark = false,
    background = LightBackground,
    surface = LightSurface,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    divider = LightDivider,
    accent = LightAccent,
    onAccent = LightSurface,
    unreadDot = LightUnread,
    unreadDotText = LightSurface,
    bubbleSelf = LightAccent,
    bubbleSelfText = LightSurface,
    bubbleOther = LightSurface,
    bubbleOtherText = LightTextPrimary,
    bubbleOtherBorder = LightDivider,
    wallpaperTop = Color(0xFFF8F6F2),
    wallpaperBottom = Color(0xFFE4DED3),
)

/** paleta de colores oscuros:mismo conjunto de semántica,Se recalcula todo el brillo. */
fun darkMiniPhoneColors(): MiniPhoneColors = MiniPhoneColors(
    isDark = true,
    background = DarkBackground,
    surface = DarkSurface,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    divider = DarkDivider,
    accent = DarkAccent,
    onAccent = DarkBackground,
    unreadDot = LightUnread,
    unreadDotText = LightSurface,
    bubbleSelf = DarkAccent,
    bubbleSelfText = DarkBackground,
    bubbleOther = DarkSurface,
    bubbleOtherText = DarkTextPrimary,
    bubbleOtherBorder = DarkDivider,
    wallpaperTop = Color(0xFF191713),
    wallpaperBottom = Color(0xFF100F0D),
)

/**
 * Escala de tamaño de fuente:Título/Texto/Secundario/Marca de tiempo.
 *
 * Aquí solo se definen el tamaño y el peso de la fuente.,Los colores están determinados por el lugar de uso. [MiniPhoneColors] tomar,
 * Evite utilizar el mismo TextStyle con colores incorrectos en diferentes fondos.
 */
@Immutable
data class MiniPhoneTypography(
    val title: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    val body: TextStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    val caption: TextStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    val timestamp: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp),
)

/**
 * Escalera de tamaño.
 *
 * Los primeros cinco elementos son las especificaciones fijas de este módulo.(Esquinas redondeadas de burbujas, esquinas redondeadas de tarjetas, dos avatares, márgenes de página);
 * Los siguientes elementos son la altura y el tamaño del icono que no se pueden evitar en el diseño del shell.,Esparcidos por todas partes individualmente, será más difícil alinearlos.
 */
@Immutable
@Suppress("LongParameterList")
data class MiniPhoneDimens(
    val bubbleRadius: Dp = 10.dp,
    val cardRadius: Dp = 14.dp,
    val avatarList: Dp = 44.dp,
    val avatarBubble: Dp = 36.dp,
    val pageMargin: Dp = 16.dp,
    val avatarLarge: Dp = 56.dp,
    val unreadBadge: Dp = 18.dp,
    val hairline: Dp = 1.dp,
    val statusBarHeight: Dp = 30.dp,
    val statusIcon: Dp = 15.dp,
    val pageHeaderHeight: Dp = 48.dp,
    val tabBarHeight: Dp = 58.dp,
    val tabIcon: Dp = 22.dp,
    val launcherTile: Dp = 56.dp,
    val launcherIcon: Dp = 30.dp,
    val dockHeight: Dp = 76.dp,
    /** La altura del círculo de amigos cubre.:Pulsa la parte superior de la pantalla.,No aprietes la primera carta de abajo. */
    val coverHeight: Dp = 160.dp,
)

val LocalMiniPhoneColors = staticCompositionLocalOf { lightMiniPhoneColors() }
val LocalMiniPhoneTypography = staticCompositionLocalOf { MiniPhoneTypography() }
val LocalMiniPhoneDimens = staticCompositionLocalOf { MiniPhoneDimens() }

/**
 * Pequeña entrada temática para teléfonos celulares.:Presione el interruptor oscuro del sistema para bajar la paleta de colores.,Envíe el tamaño de fuente y el tamaño al mismo tiempo.
 *
 * Sólo se proporcionan tres juegos de este módulo. token,Sin hosts puente MaterialTheme —— La interfaz de los teléfonos celulares pequeños no puede leer el host
 * colorScheme / typography,Si no puedes leerlo, no te llevarán cuando el anfitrión cambie de piel.
 */
@Composable
fun MiniPhoneTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = remember(darkTheme) { if (darkTheme) darkMiniPhoneColors() else lightMiniPhoneColors() }
    CompositionLocalProvider(
        LocalMiniPhoneColors provides colors,
        LocalMiniPhoneTypography provides MiniPhoneTypography(),
        LocalMiniPhoneDimens provides MiniPhoneDimens(),
        content = content,
    )
}

/** token puerto de acceso:Al escribir la interfaz, solo tome el color, el tamaño de fuente y el tamaño de aquí. */
object MiniPhoneTheme {
    val colors: MiniPhoneColors
        @Composable @ReadOnlyComposable
        get() = LocalMiniPhoneColors.current

    val typography: MiniPhoneTypography
        @Composable @ReadOnlyComposable
        get() = LocalMiniPhoneTypography.current

    val dimens: MiniPhoneDimens
        @Composable @ReadOnlyComposable
        get() = LocalMiniPhoneDimens.current
}
