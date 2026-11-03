package io.zer0.muse.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.core.view.WindowCompat
import io.zer0.common.Logger

/**
 * v0.52: Buscar en la cadena de contexto Activity(para operación Window).
 * Compose LocalContext puede estar empaquetado por ContextWrapper,Debe realizar un seguimiento para encontrar la actividad.
 */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * Muse Entrada temática (v0.22 reescribir).
 *
 * decisiones de diseño:
 *  - usar [MaterialExpressiveTheme] sustituto MaterialTheme,habilitar [MotionScheme.expressive]
 *    (CardGroup La animación de prensa y la animación elástica de cambio de página se basan en esto.)
 *  - 6 conjunto de temas predeterminados (warm_paper / sakura / ocean / spring / autumn / amoled) por [themeId] cambiar
 *  - v1.97 gap7: Admite temas personalizados ([customThemes]) — Generado por el usuario según el color de la semilla ColorScheme,
 *    La prioridad está entre colores dinámicos y temas predeterminados.(color dinámico > Personalizar > Predeterminado)
 *  - modo tema (system / light / dark) por [darkTheme] controlar
 *  - Deshabilitar la selección dinámica de colores de Material You (dynamicColor):Los colores de la marca no se contaminan con los fondos de pantalla del sistema.
 *    (si es necesario en el futuro,Puedes agregar interruptores en la configuración.,Mantenga los parámetros aquí)
 *  - Escala del tamaño de fuente [fontSizeScale] Pase [MuseTypography.scaled] Aplicar a todo el texto
 *
 * Safe Mode Descripción (L-13):Cada parámetro tiene un valor predeterminado.(darkTheme Sigue el sistema,themeId=mono,
 * fontSizeScale=medium),Por lo tanto `MuseTheme { content }` puede funcionar de forma independiente.Safe Mode objetivos de diseño
 * si"Mínimo disponible":En un escenario de degradación donde SettingsRepository no está listo o falla la lectura,No depende de ningún tiempo de ejecución
 * La interfaz básica se puede representar en el estado,Por lo tanto, los colores dinámicos, los temas personalizados y la escala del tamaño de fuente no están conectados deliberadamente.
 *
 * @param darkTheme Si el modo oscuro (Determinado por la persona que llama según SettingsRepository.themeModeFlow)
 * @param themeId Tema predeterminado id (Pasado por la persona que llama SettingsRepository.themeIdFlow)
 * @param darkThemeId Tema independiente del modo oscuro id (Una cadena vacía indica una versión oscura que sigue el tema claro.)
 * @param fontSizeScale Rango de tamaño de fuente "small" / "medium" / "large" / "xlarge"
 * @param dynamicColor Si se debe habilitar la selección dinámica de color de Material You (Predeterminado false)
 * @param customThemes v1.97 gap7: Lista de temas definidos por el usuario(Generar según el color de la semilla. ColorScheme)
 * @param bodyFontFamily E2: Familia de fuentes de texto personalizadas(Fuente predeterminada del sistema;Cargado por la persona que llama por customFontPath)
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress(
    "LongParameterList",
    "CyclomaticComplexMethod",
    // Componente raíz del tema:modo tema/color dinámico/Tema personalizado/El conjunto de parámetros, como las fuentes y la rama de reserva de color, son estructuras inherentes a nivel de pantalla.
)
fun MuseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeId: String = "mono",
    darkThemeId: String = "",
    fontSizeScale: String = "medium",
    dynamicColor: Boolean = false,
    customThemes: List<CustomTheme> = emptyList(),
    bodyFontFamily: FontFamily = FontFamily.Default,
    /** H5: tema de alto contraste — perspectivas/Contraste de fondo completo(oscuro=Fondo negro puro y caracteres blancos puros.,color claro=Texto negro puro sobre fondo blanco puro),Para usuarios con baja visión. */
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    // L-1: LocalContext.current Debe leerse afuera, recuerda.(remember La clave no contiene context,
    // De lo contrario, los cambios de contexto no activarán el recálculo);colorScheme Almacenamiento en caché con recordar,Evite reconstruir cada vez que reorganice
    // ColorScheme(dynamicColorScheme Leer recursos del sistema,Tabla de verificación de temas predeterminados,Todo debe estar en caché).
    // v1.97 gap7: remember key Unirse customThemes,hacer nuevo/Editar/Vuelva a calcular inmediatamente después de eliminar un tema personalizado.
    val context = LocalContext.current
    val composeDensity = LocalDensity.current
    val composeConfiguration = LocalConfiguration.current
    val resolvedThemeId = if (darkTheme && darkThemeId.isNotBlank()) darkThemeId else themeId
    val colorScheme = remember(dynamicColor, darkTheme, resolvedThemeId, customThemes) {
        when {
            // color dinámico (Android 12+): Selección de color de fondo de pantalla del sistema (Desactivado de forma predeterminada,Interfaz reservada)
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            // v1.97 gap7: Tema personalizado — Generado en base al algoritmo HCT de color de semilla ColorScheme
            // La prioridad es después de los colores dinámicos y antes de los temas predeterminados.,Permitir a los usuarios usar el color del fondo de pantalla del sistema,Colores de semillas personalizados también disponibles
            else -> {
                val custom = customThemes.firstOrNull { it.id == resolvedThemeId }
                if (custom != null) {
                    custom.generateColorScheme(darkTheme)
                } else {
                    // Tema predeterminado (Ruta de acceso predeterminada)
                    val preset = findPresetTheme(resolvedThemeId)
                    if (darkTheme) preset.darkScheme else preset.lightScheme
                }
            }
        }
    }
    // H5: El modo de alto contraste superpone el fondo sobre el color del tema/Contraste de perspectiva,Mantenga la semántica del color del tema, como primario
    // v1.0.90: En otros casos, hazlo una vez."gris blanquecino"normalización —— Predeterminado/Personalizar/Colores dinámicos en distintos tonos de neutros.,
    // Sin embargo, la apariencia constante del producto es que el color de fondo es similar al blanco del papel y la diferencia de gradación entre los bloques grises es muy pequeña.(No es una tarjeta gris).
    val effectiveColorScheme = when {
        highContrast -> colorScheme.toHighContrast(darkTheme)
        else -> colorScheme.toWhiterNeutrals(darkTheme)
    }

    // v0.52: Barra de estado pretintada + barra de navegación,Evite el parpadeo al cambiar de tema(Blanco primero, luego negro / Negro primero, luego blanco).
    // usar DisposableEffect(darkTheme) Restablece sincrónicamente la apariencia de la barra del sistema cuando cambia el tema oscuro,
    // Antes que SideEffect y se activa inmediatamente cuando cambia la clave,Reduce la aberración cromática en un cuadro.
    // L-3: key tomar solo darkTheme —— Apariencia de la barra del sistema(Icono claro y oscuro)Confíe únicamente en los modos claro y oscuro,Nada que ver con themeId,
    // Incluir themeId hace que las barras del sistema se restablezcan innecesariamente al cambiar de tema.themeId causado por cambios colorScheme
    // El cambio se realiza automáticamente recordando lo anterior,No afecta la apariencia de la barra del sistema.
    val view = LocalView.current
    LaunchedEffect(
        composeDensity.density,
        composeDensity.fontScale,
        composeConfiguration.screenWidthDp,
        composeConfiguration.screenHeightDp,
        composeConfiguration.smallestScreenWidthDp,
    ) {
        // maquina real“Toda la interfaz de usuario se reduce.”Diagnóstico: Grabar Redacción/Recursos/Ver zoom de tres capas al mismo tiempo,
        // Se utiliza para distinguir entre el tamaño de visualización del sistema, la escala compatible con el proveedor y la escala del código de la aplicación.
        val resourceMetrics = context.resources.displayMetrics
        Logger.i(
            "MuseDensity",
            "composeDensity=${composeDensity.density}, composeFontScale=${composeDensity.fontScale}, " +
                "resourceDensity=${resourceMetrics.density}, resourceDensityDpi=${resourceMetrics.densityDpi}, " +
                "windowDp=${composeConfiguration.screenWidthDp}x${composeConfiguration.screenHeightDp}, " +
                "smallestWidthDp=${composeConfiguration.smallestScreenWidthDp}, " +
                "view=${view.width}x${view.height}, viewScale=${view.scaleX}x${view.scaleY}",
        )
    }
    if (!view.isInEditMode) {
        DisposableEffect(darkTheme, effectiveColorScheme) {
            val window = context.findActivity()?.window
            if (window != null) {
                // edge-to-edge MainActivity.enableEdgeToEdge es responsable de unificar,L-11 Eliminar aquí
                // setDecorFitsSystemWindows Llamado para evitar configuraciones repetidas.
                // v2.0 Reparación(Sistema oscuro + al aplicar color claro.,Los iconos de la barra de estado permanecen claros e ilegibles):
                // enableEdgeToEdge SystemBarStyle.auto predeterminado sigue“sistema”modo oscuro,Su valor automático estará en
                // Las oportunidades posteriores sobrescribirán la configuración aquí.;Utilice decorView en su lugar para obtener controller,Y configúrelo una vez después del primer cuadro.,
                // Garantizar un tema ligero para la barra de estado dentro de la aplicación/Los iconos de la barra de navegación siempre están oscuros.
                val applySystemBarAppearance = {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !darkTheme
                    controller.isAppearanceLightNavigationBars = !darkTheme
                }
                applySystemBarAppearance()
                window.decorView.post { applySystemBarAppearance() }
                // v2.6.4: Inmersión en la barra del sistema. —— Cambie el fondo a transparente para que el contenido de la página/El fondo se extiende debajo de la barra de estado y la barra de navegación.
                // En lugar de revelar el color del tema (comentarios de los usuarios“Xiaobaitiao no es adecuado”).
                // statusBarsPadding / NavigationBarsPadding aún evita el contenido legible en cada página para evitar las barras del sistema.
                // La claridad y la oscuridad de los iconos de la barra de estado y de la barra de navegación han sido configuradas por el controlador de arriba.
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                // API 29+ Desactive la máscara de contraste de la barra de navegación (cuando no se utiliza la barra de gestos, se colocará una capa oscura debajo de la barra de navegación).
                // Se convertirá en una franja abrupta y oscura sobre un fondo transparente).
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                // v2.6.4: decorView El fondo sigue el tema —— Una vez que la barra del sistema se vuelve transparente, si hay páginas que no ocupan toda la ventana,
                // El color de fondo del tema se expone debajo de la barra del sistema en lugar del color predeterminado de la ventana (para evitar que las barras blancas queden expuestas debajo de los temas oscuros).
                window.decorView.setBackgroundColor(effectiveColorScheme.background.toArgb())
            }
            onDispose {
                // L-2: Mantener el aspecto actual,No se realiza ninguna restauración.MuseTheme envolver todo App,onDispose solo en
                // Activity Se activa cuando se destruye,no existe"La próxima interfaz sin temática de Muse"Escenas que necesitan ser restauradas.;
                // Configuración del código original !darkTheme En realidad no-op(Establecer el mismo valor que el cuerpo del efecto.),La anotación afirma
                // "Volver a los valores predeterminados del sistema",La anotación de propiedad no coincide con el comportamiento,Comentarios corregidos aquí para que coincidan con la semántica real.
                val window = context.findActivity()?.window
                if (window != null) {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !darkTheme
                    controller.isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    // M-TP1: Caché escalado por fontSizeScale Typography,Evite copiar toda la tipografía cada vez que reorganice.
    // E2: bodyFontFamily cambiar(importar/fuente clara)reconstruir juntos,Aplique fuentes personalizadas a todos los estilos.
    val scaledTypography = remember(fontSizeScale, bodyFontFamily) {
        val base = MuseTypography.scaled(fontSizeScale)
        if (bodyFontFamily == FontFamily.Default) base else base.withFontFamily(bodyFontFamily)
    }
    // L-TH2: MotionScheme.expressive() Caché como singleton,Evite reorganizar y crear nuevos cada vez.
    val motionScheme = remember { MotionScheme.expressive() }

    MuseMotion.Provide {
        MaterialExpressiveTheme(
            colorScheme = effectiveColorScheme,
            typography = scaledTypography,
            shapes = MuseShapes,
            motionScheme = motionScheme,
        ) {
            // v1.0.92: Eliminar globalmente la máscara de prensa circular(ripple).
            // CMP-03 La ondulación predeterminada de M3 restaurada se extenderá por todo el ancho de la fila de la tarjeta formando un gran círculo gris que cubrirá la mayor parte de la tarjeta.
            // (M3 bounded ripple El radio se calcula desde el punto de contacto hasta la esquina más alejada.),Eliminar todo por decisión del producto:
            //  - LocalIndication Cambio a implementación silenciosa(Foundation clickable / Camino de control desnudo);
            //  - LocalRippleConfiguration conjunto null(M3 Dentro del componente ripple() camino,
            //    Material3 1.3+ interruptor oficial:configuration cuando es nulo ripple() Devuelve una implementación vacía).
            // Los componentes que requieren comentarios de la prensa son autodibujados.(MuseDialogButton Presione para comprimir y liberar, etc.).
            // El código comercial ya no codifica los colores nude(Inyecta sincrónicamente color de estado semántico y color de resaltado de código).
            val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors
            val codeColors = if (darkTheme) DarkCodeColors else LightCodeColors
            CompositionLocalProvider(
                LocalIndication provides MuseNoRippleIndication,
                LocalRippleConfiguration provides null,
                LocalStatusColors provides statusColors,
                LocalCodeColors provides codeColors,
                // Deshabilite globalmente el rebote elástico de desplazamiento excesivo para contenedores de desplazamiento.
                //
                // Stretch Rebound encabeza la lista en Android/tiempo de fondo“banda de goma”efecto,
                // En la interfaz de la tarjeta, el borde del gesto se confundirá con el área de retorno del sistema y también se confundirá con el área de retorno del sistema.
                // La determinación del área de exclusión de gestos interfiere entre sí (cuando el dedo comienza desde el borde, el desplazamiento será devorado primero por el rebote).
                // Aquí se desactiva uniformemente y se detiene al desplazarse hasta el borde, lo que hace que la interacción sea más segura.
                androidx.compose.foundation.LocalOverscrollFactory provides null,
                // v2.6.2(comentarios de los usuarios): Si la descarga está actualmente oscura —— Para antecedentes de chat y otras necesidades
                // “poco profundo/Dos series de profundidad”El consumidor lee isSystemInDarkTheme en lugar de adivinar cada uno.
                io.zer0.muse.ui.theme.LocalIsDarkTheme provides darkTheme,
            ) {
                content()
            }
        }
    }
}

/**
 * H5: modo de alto contraste — poner fondo/perspectivas/Los colores relacionados con la superficie maximizan el contraste(negro puro/blanco puro),Conserva el color semántico primario del tema.
 * Para usuarios con baja visión:Máximo contraste entre texto y fondo.(21:1),secondaryContainer/errorContainer espera
 * Los colores de los contenedores semánticos permanecen sin cambios.,Evite dañar la legibilidad del color del estado.
 */
private fun ColorScheme.toHighContrast(darkTheme: Boolean): ColorScheme = if (darkTheme) {
    copy(
        background = Color(0xFF000000),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFF1E1E1E),
        onSurfaceVariant = Color(0xFFE6E6E6),
    )
} else {
    copy(
        background = Color(0xFFFFFFFF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF000000),
        surfaceVariant = Color(0xFFE6E6E6),
        onSurfaceVariant = Color(0xFF1E1E1E),
    )
}

/**
 * v1.0.90: Tema irrelevante"gris blanquecino"Normalizar.
 *
 * Tema preestablecido, escala de grises ajustada a mano y temas personalizados(HCT generar)Las escalas de grises son de diferentes tonalidades y son inconsistentes cuando se ven en la misma pantalla;
 * La apariencia del producto siempre ha sido que el color de fondo es cercano al blanco del papel y la diferencia de gradación entre los bloques grises es muy pequeña (no es una tarjeta gris).
 * Aquí, la familia de superficies y la variante de contorno se unifican en el extremo blanco:
 *
 *  - Sólo se toca el color base, no hay error primario/secundario, ni ningún on* con color de texto;
 * Aclarar el color de fondo sólo aumentará el contraste del texto de primer plano, pero no creará un riesgo de legibilidad.
 *  - Se levanta más en modo ligero(El color de fondo es parecido al blanco del papel.), el modo oscuro solo mejora un poco(Mantenga el OLED oscuro y solo ilumine las áreas grises un nivel).
 *  - El modo de alto contraste no va aquí(ver [toHighContrast]), los dos tienen objetivos opuestos.
 *
 * v2.0.1 "Doble plano": el modo de luz agrega además color de fondo de la página (background/surfaceDim) Presione un nivel hacia el extremo negro,
 * dejar"Color de fondo claro + superficie de tarjeta más brillante(surface)"La diferencia de brillo se convierte en el principal portador de jerarquía;
 * La superficie de la tarjeta ya no depende de los trazos./Capas de sombra. El modo oscuro permanece sin cambios por el momento (pendiente de calibración de referencia oscura).
 */
private fun ColorScheme.toWhiterNeutrals(darkTheme: Boolean): ColorScheme = if (darkTheme) {
    copy(
        // v2.x Plano dual oscuro(Calibración del modo oscuro):El color de fondo está un paso más cerca del negro puro OLED(con colores claros"Color de fondo más oscuro"isomorfismo);
        // Referencia de medición real:oscuro = #000 Tarjeta inferior / #1A1A1A,Fondo de la jerarquía-Pobre cominidad.
        background = darkenToward(background, DOUBLE_PLANE_DARK_BG_PUSH),
        surface = darkenToward(surface, DOUBLE_PLANE_DARK_BG_PUSH),
        surfaceDim = darkenToward(surfaceDim, DOUBLE_PLANE_DARK_BG_PUSH),
        // Los bloques grises en la superficie de la tarjeta están moderadamente convergentes.(El ascensor original es ligeramente más alto.,La tarjeta es más brillante cuando se combina con un fondo profundo.;Reciclaje aprox. 30%,Reservar"Mate pero no tapado"Percepción).
        surfaceBright = liftTowardWhite(surfaceBright, 0.04f),
        surfaceContainerLowest = liftTowardWhite(surfaceContainerLowest, 0.02f),
        surfaceContainerLow = liftTowardWhite(surfaceContainerLow, 0.04f),
        surfaceContainer = liftTowardWhite(surfaceContainer, 0.07f),
        surfaceContainerHigh = liftTowardWhite(surfaceContainerHigh, 0.09f),
        surfaceContainerHighest = liftTowardWhite(surfaceContainerHighest, 0.11f),
        surfaceVariant = liftTowardWhite(surfaceVariant, 0.10f),
        outlineVariant = liftTowardWhite(outlineVariant, 0.10f),
    )
} else {
    copy(
        // v2.0.1 Plano doble: el color de fondo se presiona un nivel más profundo (×0.955), superficie de la tarjeta(surface)Mantenga el fondo blanco y use brillo contrastante para crear jerarquía.
        background = darkenToward(background, DOUBLE_PLANE_BG_PUSH),
        surfaceDim = darkenToward(surfaceDim, DOUBLE_PLANE_BG_PUSH),
        surfaceContainerLowest = liftTowardWhite(surfaceContainerLowest, 0.25f),
        surfaceContainerLow = liftTowardWhite(surfaceContainerLow, 0.40f),
        surfaceContainer = liftTowardWhite(surfaceContainer, 0.55f),
        surfaceContainerHigh = liftTowardWhite(surfaceContainerHigh, 0.62f),
        surfaceContainerHighest = liftTowardWhite(surfaceContainerHighest, 0.68f),
        surfaceVariant = liftTowardWhite(surfaceVariant, 0.68f),
        outlineVariant = liftTowardWhite(outlineVariant, 0.55f),
    )
}

/** Rango de profundidad del color de fondo del modo de color de luz de \"plano dual\":1 - 0.955 = 4.5%. (Parámetros de calibración, ajustados durante la iteración de la muestra). */
private const val DOUBLE_PLANE_BG_PUSH = 0.045f

/**
 * Amplitud de ennegrecimiento del color de fondo del modo oscuro de "plano dual" (calibración del modo oscuro,v2.x):
 * 75% de presión hacia el negro puro OLED (predeterminado cerca del fondo negro, como #101014 → #040405 nivel),
 * dejar"fondo vs tarjeta"Las diferencias de luminosidad se convierten en el principal portador de los niveles de oscuridad.
 * (Parámetros de calibración: con capturas de pantalla oscuras/Ajuste de un solo punto durante la iteración de la apariencia y sensación de la máquina real. )
 */
private const val DOUBLE_PLANE_DARK_BG_PUSH = 0.75f

/** Lleva el color hacia el blanco puro. [amount](0..1), solo cambia el brillo sin cambiar el tono. */
private fun liftTowardWhite(color: Color, amount: Float): Color = Color(
    red = color.red + (1f - color.red) * amount,
    green = color.green + (1f - color.green) * amount,
    blue = color.blue + (1f - color.blue) * amount,
    alpha = color.alpha,
)

/** v2.0.1 Plano dual: presione el color hacia el extremo negro [amount](0..1), mantiene el tono (oscurecimiento multiplicativo, solo se usa para la superficie del fondo). */
private fun darkenToward(color: Color, amount: Float): Color = Color(
    red = color.red * (1f - amount),
    green = color.green * (1f - amount),
    blue = color.blue * (1f - amount),
    alpha = color.alpha,
)

/**
 * v2.6.2(comentarios de los usuarios): Si el tema oscuro se utiliza actualmente (configuración de usuario resuelta, no valor del sistema).
 *
 * necesidades de suministro "superficiales"/Los consumidores utilizan "Deep Two Sets" (como la cobertura de fondo oscuro en el chat).
 * El valor predeterminado es falso, lo que garantiza que no falle al renderizar fuera de MuseTheme.
 */
val LocalIsDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }
