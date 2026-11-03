package io.zer0.muse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Muse sistema de combinación de colores(Solución completa para alinear el diseño frontal §2).
 *
 * filosofía del diseño:90% Negro, blanco y gris + <5% Color de la marca. La marca verde es un recurso escaso,solo usado en
 * elementos interactivos(Botón Enviar / Selección de pestaña / Activación de interruptor / Burbuja de usuario en modo oscuro),
 * Nunca pavimente un área grande.
 *
 * modo de luz:fondo blanco cálido(#FAFAF8,No blanco puro,Sin deslumbramiento durante mucho tiempo)
 *           Las burbujas de usuario están alojadas en colorScheme.primary(color claro=marca verde,ver PresetTheme)
 *           AI gris cálido burbuja(#F0F0EC,más oscuro que el fondo,Deja que las burbujas floten)
 * modo oscuro:OLED Fondo negro puro(#000000,Ahorro de energía + relación de contraste más alta)
 *           Las burbujas de usuario están alojadas en colorScheme.primary(marca verde,Utilice el color para distinguir entre usuarios y AI)
 *           AI burbuja gris oscuro(#1C1C1E,Un poco más brillante que el fondo.,Sólo mira el límite)
 *
 * Nota(M-PT1):Históricamente este archivo se define LightUserBubble / DarkUserBubble /
 * LightOnUserBubble / DarkOnUserBubble Color especial para burbujas.,Pero la burbuja del proyecto en su totalidad pasó
 * MaterialTheme.colorScheme.primary / onPrimary renderizado(ver PresetTheme),estos
 * Nunca se hace referencia a los valores de color.,Eliminado para eliminar el código muerto. La coincidencia de colores de las burbujas se mantiene en PresetTheme.
 */

// ── Color de la marca ──────────────────────────────────────────────────────────────
/**
 * verde laurel:Color de marca principal(modo de luz). El símbolo de la hoja de laurel derivado de la Muse.,Un verde oscuro más oscuro.
 * v1.0.21: #2D8C5F -> #2A7A55,Contraste 4.0:1 -> 4.8:1,Cumple con los estándares WCAG AA,
 * Manteniendo el reconocimiento de la marca Laurel Green(Legibilidad primero).
 */
val LaurelGreen = Color(0xFF2A7A55)

/**
 * Versión iluminadora verde laurel:Modo oscuro dedicado primario.
 * v1.0.21 Nuevo:En modo oscuro, el fondo negro puro con LaurelGreen original tiene poca visibilidad(Contraste 3.5:1),
 * Se iluminó a #4A9F70 y el contraste aumentó a 5.5:1,botón/Las burbujas son más claras en las pantallas OLED.
 */
val LaurelGreenBright = Color(0xFF4A9F70)

/** Contenedor de luz verde de marca.(Fondo seleccionado del modo de luz.). */
val LaurelGreenLightContainer = Color(0xFFD4EBDD)

/** Texto verde oscuro de la marca.(modo de luz onContainer). */
val LaurelGreenDark = Color(0xFF0F3D2A)

/** Texto de luz verde de marca(modo oscuro onContainer). */
val LaurelGreenLight = Color(0xFFA8DBC4)

/** Contenedor hondo verde de marca(Fondo seleccionado en modo oscuro). */
val LaurelGreenDarkContainer = Color(0xFF1F4D38)

/** estrella de oro:Color de acento secundario. Solo se utiliza para logotipo/identidad de miembro/insignia de hito,La interfaz diaria no aparece. */
val StarGold = Color(0xFFF5C842)

// ── Muestras de modo de luz ────────────────────────────────────────────────────────
/** Fondo de la página:blanco cálido,No blanco puro,Tono amarillo muy ligeramente cálido. */
val LightBg = Color(0xFFFAFAF8)

/** AI Burbujas:gris cálido,Hazlo más oscuro que el fondo para que las burbujas floten. Las burbujas de usuario usan colorScheme.primary en su lugar. */
val LightAiBubble = Color(0xFFF0F0EC)

/** texto del cuerpo:Casi negro. */
val Ink = Color(0xFF1A1A1A)

/** texto secundario:marca de tiempo/nombre del modelo/pista. */
val Secondary = Color(0xFF8E8E93)

/** línea divisoria:extremadamente superficial,Está presente pero no es llamativo. */
val Divider = Color(0xFFE8E8E4)

/**
 * color peligro:Eliminar/detener generación.
 * v1.0.21: #FF3B30(rojo frio) -> #D94034(Rojo ladrillo cálido),Armoniza con texturas cálidas.,
 * Reducir la agresión visual,manteniendo al mismo tiempo un contraste de advertencia adecuado.
 */
val Danger = Color(0xFFD94034)

/** Contenedores luminosos de colores peligrosos.v1.0.21: Viene con ajuste de calefacción. */
val DangerLightContainer = Color(0xFFF8DCD8)

// ── Muestras de modo oscuro ────────────────────────────────────────────────────────
/** Fondo de página oscuro:OLED negro puro,Ahorro de energía + relación de contraste más alta. */
val DarkBg = Color(0xFF000000)

/** Burbujas oscuras de IA:gris oscuro,Un poco más claro que el fondo. Las burbujas de usuario usan colorScheme.primary en su lugar. */
val DarkAiBubble = Color(0xFF1C1C1E)

/** texto oscuro:Blanco ligeramente cálido,No utilizar blanco puro reduce la fatiga del contraste. */
val DarkInk = Color(0xFFE8E8E8)

/** línea divisoria oscura:Extremadamente oscuro. */
val DarkDivider = Color(0xFF2C2C2E)

/** Contenedores de colores oscuros peligrosos.v1.0.21: Viene con ajuste de calefacción. */
val DangerDarkContainer = Color(0xFF3D1818)

// ── paleta de colores emocionales(Visualización de relaciones/revisión de memoria/trayectoria emocional)───────────────────────────
/** rosa coral:Cariñoso, reconfortante. para insignias de hitos,AI Tarjetas de atención proactiva. */
val CoralWhisper = Color(0xFFFF6B6B)

/** lavanda:Sueños, recuerdos. Se utiliza para revisar la memoria, nodos de la línea de tiempo y portadas de memorias. */
val LavenderDream = Color(0xFF9B8EC4)

/** naranja ámbar:Cálido y nostálgico. Se utiliza para la barra de progreso de la duración de la relación y para resaltar la revisión anual. */
val AmberWarmth = Color(0xFFE8A838)

/** verde salvia:Paz, crecimiento. Para uso en el área positiva de la pista de emociones. */
val SageCalm = Color(0xFF7DB88F)

/** gris brumoso:Neutral, transitorio. Se utiliza para ilustraciones de estados vacíos, líneas divisorias y estados deshabilitados. */
val MistGray = Color(0xFFC7C7CC)

// ── Lista de conversación iniciales avatar 8 ciclo de color ────────────────────────────────────────
/** Paleta de colores preestablecida de avatar inicial,de [MaterialTheme.colorScheme] Generado dinámicamente,No más paletas fijas de 8 colores. */
@Composable
fun avatarColors(): List<Color> = buildList {
    val scheme = MaterialTheme.colorScheme
    add(scheme.primary.copy(alpha = 0.8f))
    add(scheme.secondary.copy(alpha = 0.8f))
    add(scheme.tertiary.copy(alpha = 0.8f))
    add(scheme.error.copy(alpha = 0.8f))
    add(scheme.primaryContainer)
    add(scheme.secondaryContainer)
    add(scheme.tertiaryContainer)
    add(scheme.inversePrimary.copy(alpha = 0.7f))
}
