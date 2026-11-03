package io.zer0.muse.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Muse ficha de forma(Solución completa para alinear el diseño frontal §5.1).
 *
 * sistema de esquinas redondeadas(De grande a pequeño):
 *  - [extraLarge]: 20.dp — Tarjetas grandes/contenedores grandes
 *  - [large]:     18.dp — Fuerza principal de la burbuja de mensajes(Grandes esquinas redondeadas=suave=Temperamento de la aplicación de emoción)
 *  - [medium]:     12.dp — Botón/Bloque de código/Elemento de lista(medio,Ni demasiado redondo ni demasiado cuadrado.)
 *  - [small]:       8.dp — Código en línea/etiquetas pequeñas
 *  - [extraSmall]:  6.dp — esquina de la cola de burbuja(más pequeño que otros ángulos,Indicar la dirección del mensaje)
 *
 * Uso:
 * ```
 * Card(shape = MuseShapes.medium) { ... }
 * Surface(shape = MuseShapes.large) { ... }
 * ```
 *
 * La cola de burbuja requiere filetes asimétricos,usar [BubbleShape] en lugar de RoundedCornerShape.copy.
 *
 * Nota:[MuseShapes] limitado por [Shapes] 5 ranuras fijas(extraSmall/small/medium/large/extraLarge),
 * Otras esquinas redondeadas(tiny / semiLarge / huge / mega / pill)Ver abajo [Shapes] Atributos extendidos.
 */
val MuseShapes: Shapes = Shapes(
    // L-SH2: Impulsado por la constante MuseCornerRadius,Eliminar la duplicación numérica(Sólo se necesita un cambio).
    extraSmall = RoundedCornerShape(MuseCornerRadius.BUBBLE_TAIL.dp), // Esquina de cola de burbuja/etiqueta pequeña
    small = RoundedCornerShape(MuseCornerRadius.SMALL.dp), // Código en línea/etiquetas pequeñas
    medium = RoundedCornerShape(MuseCornerRadius.BUTTON.dp), // Botón/Bloque de código/Elemento de lista
    large = RoundedCornerShape(MuseCornerRadius.BUBBLE.dp), // Fuerza principal de la burbuja de mensajes
    extraLarge = RoundedCornerShape(MuseCornerRadius.CARD.dp), // Tarjetas grandes/contenedores grandes
)

/**
 * Muse Extensión de token de forma(completo [MuseShapes] esquinas redondeadas).
 *
 * [MuseShapes] si [Shapes] Ejemplo,Sólo 5 ranuras fijas;Valores para el resto de filetes de todo el proyecto
 * (50/28/24/16/4 Espere el valor desnudo histórico)Unificado aquí como token,Expuesto a través de propiedades extendidas de Formas,
 * hacer `MuseShapes.xxx` forma y existencia `MuseShapes.medium` etc. Manténgase constante.
 *
 * Engranaje de finalización(desde la infancia hasta la edad adulta):
 *  - [tiny]:      4.dp  — etiqueta/logotipo
 *  - [semiLarge]: 16.dp — Cuadro de entrada/tarjeta intermedia
 *  - [huge]:      24.dp — filete superior BottomSheet
 *  - [mega]:      28.dp — Esquinas redondeadas grandes especiales(FAB)
 *  - [pill]:      50%  — forma de cápsula(botón/barra de búsqueda)
 *
 * Uso:
 * ```
 * FilledTonalButton(shape = MuseShapes.pill) { ... }
 * Surface(shape = MuseShapes.semiLarge) { ... }
 * ```
 */
// L-SH1: La siguiente forma expandida se cambia al caché val de nivel superior,Evite crear una nueva RoundedCornerShape en cada visita.
// L-SH2: La unificación numérica está impulsada por la constante MuseCornerRadius,Corresponde estrictamente a MuseShapes.
// L-8: Estas propiedades extendidas se definen en Formas,Pero semánticamente se supone que el receptor es MuseShapes
// (Los tokens numéricos provienen de MuseCornerRadius,Viene con 5 ranuras de MuseShapes). Otros Shapes
// Las llamadas de instancia a estas extensiones se pueden compilar.,Pero el valor no tiene nada que ver con el sistema MuseShapes.,No se debe mezclar.
/** Etiqueta/logotipo. */
private val TinyShape = RoundedCornerShape(MuseCornerRadius.TINY.dp)
val Shapes.tiny: RoundedCornerShape get() = TinyShape

/** Cuadro de entrada/Tarjeta. */
private val SemiLargeShape = RoundedCornerShape(MuseCornerRadius.SEMI_LARGE.dp)
val Shapes.semiLarge: RoundedCornerShape get() = SemiLargeShape

/** Sábana bajera superior redondeada. */
private val HugeShape = RoundedCornerShape(MuseCornerRadius.SHEET.dp)
val Shapes.huge: RoundedCornerShape get() = HugeShape

/** Esquinas redondeadas grandes especiales(FAB). */
private val MegaShape = RoundedCornerShape(MuseCornerRadius.MEGA.dp)
val Shapes.mega: RoundedCornerShape get() = MegaShape

/** v2.0.1: tarjeta grande——Configure tarjetas de agrupación/panel (esquinas grandes y redondeadas). */
private val LargeCardShape = RoundedCornerShape(MuseCornerRadius.LARGE_CARD.dp)
val Shapes.largeCard: RoundedCornerShape get() = LargeCardShape

/** forma de cápsula:50% esquinas redondeadas,Cápsula completa a cualquier altura.(botón/barra de búsqueda). */
// L-9: Explícitamente sobrecargado con porcentaje,Evite los desencadenantes .dp por falta de comunicación Int→dp La sobrecarga produce esquinas redondeadas inesperadas.
private val PillShape = RoundedCornerShape(percent = MuseCornerRadius.PILL)
val Shapes.pill: RoundedCornerShape get() = PillShape

/**
 * v1.48 (h18): ficha en forma de burbuja(Filete asimétrico,Esquina trasera 6dp + cuerpo 20dp).
 *
 * Cumplir la promesa del comentario de la línea 23,Unifique las esquinas redondeadas de la burbuja de mensajes de chat individual y de chat grupal,erradicar los valores desnudos
 * `RoundedCornerShape(20.dp,20.dp,20.dp,6.dp)`(charla privada)con
 * `RoundedCornerShape(18.dp,18.dp,4.dp,18.dp)`(chat grupal)Mézclalo.
 *
 *  - [userBubble]:      (20,20,6,20) — Burbuja de usuario,cola inferior derecha
 *  - [assistantBubble]: (20,20,20,6) — AI Burbujas,cola inferior izquierda
 *
 * Correspondencia numérica [MuseCornerRadius.BUBBLE_TAIL] (6) con [MuseCornerRadius.CARD] (20).
 */
/** Burbuja de usuario:La pequeña esquina redondeada en la esquina inferior derecha simula una cola.(Cuerpo 20dp + cola 6dp). */
private val UserBubbleShape = RoundedCornerShape(
    MuseCornerRadius.CARD.dp,
    MuseCornerRadius.CARD.dp,
    MuseCornerRadius.BUBBLE_TAIL.dp,
    MuseCornerRadius.CARD.dp,
)
val Shapes.userBubble: RoundedCornerShape get() = UserBubbleShape

/** AI Burbujas:La pequeña esquina redondeada en la esquina inferior izquierda simula la cola.(Cuerpo 20dp + cola 6dp). */
private val AssistantBubbleShape = RoundedCornerShape(
    MuseCornerRadius.CARD.dp,
    MuseCornerRadius.CARD.dp,
    MuseCornerRadius.CARD.dp,
    MuseCornerRadius.BUBBLE_TAIL.dp,
)
val Shapes.assistantBubble: RoundedCornerShape get() = AssistantBubbleShape

/**
 * Constantes de conveniencia(Úselo directamente en escenas RoundedCornerShape o comentarios de código).
 * numérico y [MuseShapes] Correspondencia estricta,Los cambios deben sincronizarse.
 *
 * CMP-02: Alias del mismo valor(MEDIUM / LARGE / EXTRA_LARGE / HUGE)Ya**Eliminar** —
 * Sólo se reserva un nombre canónico para cada arte.,Evite que dos conjuntos de nombres para el mismo valor se bifurquen nuevamente.
 */
object MuseCornerRadius {
    /** esquina de la cola de burbuja(ángulo asimétrico). */
    const val BUBBLE_TAIL = 6

    /** Código en línea/etiquetas pequeñas. */
    const val SMALL = 8

    /** Botón/bloque de código/elemento de lista. */
    const val BUTTON = 12

    /** La burbuja del mensaje principal. */
    const val BUBBLE = 18

    /** Tarjetas/Contenedores Grandes. */
    const val CARD = 20

    /** Abajo aparece la parte superior del panel. */
    const val SHEET = 24

    // ── ficha de finalización(denominación estandarizada,El valor numérico corresponde al anterior.,Los cambios deben sincronizarse)──

    /** Etiqueta/logotipo. */
    const val TINY = 4

    /** Cuadro de entrada/Tarjeta. */
    const val SEMI_LARGE = 16

    /** Esquinas redondeadas grandes especiales(FAB). */
    const val MEGA = 28

    /** v2.0.1: tarjeta grande(Configurar tarjetas de grupo/panel). */
    const val LARGE_CARD = 24

    /** forma de cápsula(50% esquinas redondeadas). */
    const val PILL = 50
}
