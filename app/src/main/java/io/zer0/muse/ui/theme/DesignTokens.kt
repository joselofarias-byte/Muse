package io.zer0.muse.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Muse ficha de diseño — Sistema de animación.
 *
 * Proporciona duración de animación globalmente unificada, curva de suavizado, especificaciones de sombras y métodos semánticos de retroalimentación táctil.,
 * con existente [MusePaddings] / [MuseCornerRadius] / [MuseElevation] / [MuseIconSizes] complementario,
 * Juntos forman un sistema de tokens de diseño completo.
 *
 * descripción del diseño:
 *  - interacción táctil:200 ms facilidad de gradiente de color OutCubic cuando se presiona
 *  - Material 3 Expressive: Animación flexible expressive motion scheme
 *  - Apple HIG: Calificación de retroalimentación táctil (light / medium / heavy)
 */
object MuseAnimation {

    // ── indicador de patrón ──────────────────────────────────────────────────
    /**
     * Duración de un ciclo morfológico completo de Yunnan: hoja → estrella → anillo → Hojas traseras.
     *
     * 3.6s Cada una de las tres formas ocupa una pausa suficiente sin que la espera parezca larga.
     */
    const val GLYPH_CYCLE_MS = 3600

    /** El período de tiempo que la luz descendente viaja alrededor del contorno de un patrón de explosión estelar. Independientemente del ciclo morfológico, los períodos de los dos están en una proporción no entera. */
    const val GLYPH_TRAVEL_MS = 1600

    // ── ficha de duración ──────────────────────────────────────────────────────
    /** Transición extremadamente rápida (120ms): microinteracción — Indicador de estado/alternancia de icono. */
    const val FAST_MS = 120

    /** Transición rápida (180ms): Cambio de estado simple — Color del botón/interruptor. */
    const val FAST_NORMAL_MS = 180

    /** Transición estándar (240ms): Ampliación de tarjeta/cambio de panel. */
    const val NORMAL_MS = 240

    /** Estándar de retroalimentación de prensa (200ms): Presione el degradado de color. */
    const val TACTILE_MS = 200

    /** transición lenta (320ms): Ventana emergente de transición de página/hoja inferior. */
    const val SLOW_MS = 320

    /** Transición muy lenta (400ms): Reorganización del diseño de áreas grandes/gradiente de cambio de tema. */
    const val EXTRA_SLOW_MS = 400

    // v2.0: Desaceleración general de la animación del bucle global 1.5x(Valor de milisegundos más grande = rotación más lenta/intermitente),
    // Cubre círculos de carga, puntos de pensamiento,shimmer, grabando/chat grupal/Pulso delegado;Las transiciones de interacción limitadas no cambian.
    /** animación en bucle-Estándar (900ms): Punto de escritura/pulso del micrófono. */
    const val LOOP_NORMAL_MS = 900

    /** animación en bucle-lento (1200ms): Pulso de punto de estado/forma de onda de grabación/pulso de cadena de comisión. */
    const val LOOP_SLOW_MS = 1200

    /** animación en bucle-Extremadamente lento (1800ms): shimmer Marcador de posición/Pulso de conversación/Carga de rotación. */
    const val LOOP_EXTRA_SLOW_MS = 1800

    // v1.0.16: Equipo de transición de navegación(280-300ms,entre NORMAL y LENTO)
    /** Transición horizontal de navegación (280ms): NavGraph push/pop Deslizar horizontalmente. */
    const val NAV_HORIZONTAL_MS = 280

    /** Entrada vertical de navegación (300ms): NavGraph Ingrese verticalmente. */
    const val NAV_VERTICAL_ENTER_MS = 300

    /** Salida vertical de navegación (280ms): NavGraph Salga verticalmente. */
    const val NAV_VERTICAL_EXIT_MS = 280

    // ── v2.6.7 Restablecer animación: Entra al mercado/Separación de duración de salida ─────────────────────────────
    // Kelivo de referencia: entrada 360ms / salida 280ms. La salida debe ser más corta que la entrada. ——
    // Los usuarios no quieren esperar a que algo que ha decidido irse se desvanezca lentamente.
    /** Admisión de página (360ms): Más largo que la salida para dar tiempo a contenido nuevo. "quedarse quieto". */
    const val ENTER_MS = 360

    /** Salida de página (280ms): A falta de entrar, no debes retrasar la salida. */
    const val EXIT_MS = 280

    /** Admisión de bloque de contenido (260ms): Enumere elementos/tarjetas/información sobre herramientas. */
    const val CONTENT_ENTER_MS = 260

    /** Salida del bloque de contenido (200ms): Más corto que la admisión. */
    const val CONTENT_EXIT_MS = 200

    // ── v2.6.7 Restablecer animación: Distancia de desplazamiento ─────────────────────────────────────
    // La implementación anterior hacía que la página ocupara toda la pantalla.(proporción 1.0)o media pantalla(0.5), con facilidadOutCubic es
    // "Da un paso adelante y luego arrástralo hacia atrás.", el sentido de lectura es "Sácalo".kelivo Toda la posición se traslada a 2%~8%.
    /** Relación de desplazamiento de transición de página (1/12 ≈ 8%): Se utiliza para empujar/pop horizontal. */
    const val SLIDE_FRACTION_PAGE = 1f / 12f

    /** Relación de desplazamiento de capa menor (1/16 ≈ 6%): La página raíz cubierta sale,pop Para volver a jugar. */
    const val SLIDE_FRACTION_SECONDARY = 1f / 16f

    /** Relación de desplazamiento de bloques de contenido (1/20 = 5%): Barra de punta/tarjeta/elemento flotante. */
    const val SLIDE_FRACTION_CONTENT = 1f / 20f

    // v1.0.16: Aumento del tamaño del paso para entrada escalonada
    //
    // v2.6.7: 180ms → 28ms, y debe estar tapado (ver [STAGGER_MAX_INDEX]).
    // Problema con los viejos valores.: 180ms es kelivo(16~20ms) más de un orden de magnitud,
    // Y el pulsador generalmente está escrito `index * STAGGER_STEP_MS` Sin límite ——
    // El décimo elemento tiene que esperar 1,8 segundos y el vigésimo elemento tiene que esperar 3,6 segundos. El rendimiento es el siguiente. "Cuanto más bajas, más atascado te quedas.".
    /** Tamaño del paso de entrada escalonado (28ms): El valor de incremento de retraso para que los elementos de la lista ingresen uno por uno. */
    const val STAGGER_STEP_MS = 28

    /** Número de secuencia máximo para la participación máxima escalonada: Los elementos que superan este valor comparten el mismo retraso para evitar largas esperas en la lista. */
    const val STAGGER_MAX_INDEX = 6

    // ── Token de curva de flexibilización ──────────────────────────────────────────────────
    /**
     * Flexibilización estándar (easeOutCubic):
     * Entrada rápida y salida lenta,Simula la sensación natural de desaceleración de las interacciones táctiles.
     */
    val EaseOutCubic: Easing = CubicBezierEasing(0.33f, 1.0f, 0.68f, 1.0f)

    /**
     * rebote elástico (easeOutBack): Rebote tras superar ligeramente el punto final,
     * Se utiliza para entrada de tarjeta/elemento emergente, etc."sensación de vitalidad"escena.
     */
    val EaseOutBack: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)

    /**
     * Material 3 Flexibilización estándar: Curva de transición universal,Se utiliza para cambiar de página/movimiento de elementos.
     * equivalente a [FastOutSlowInEasing],La interfaz del token unificado se nombra aquí.
     */
    val Standard: Easing = FastOutSlowInEasing

    /**
     * curva de aceleración (easeInCubic): Entrando lento y saliendo rápido,Se utiliza para la animación de salida/salida de elementos.
     */
    val EaseInCubic: Easing = CubicBezierEasing(0.32f, 0.0f, 0.67f, 0.0f)

    /**
     * v2.x: Material 3 Expressive enfatizar las curvas — gran desplazamiento/Énfasis en escenas de transición.
     * (motion-scheme Las especificaciones mejoradas,panel de suministro/Referencia de transición de contenedores grandes).
     */
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /**
     * v2.6.7: "Déjalo crecer primero y luego estabilízalo." Curva — Evaluación comparativa de Kelivo `Cubic(0.2, 0.8, 0.2, 1)`.
     *
     * El primer 20% del tiempo cubre el 80% de la distancia y el tiempo restante converge lentamente. para cambios de talla
     * ([androidx.compose.animation.animateContentSize]) y animación de expansión:
     * El sentido de la lectura es el contenido. "crecer" Sal en lugar de ser derribado instantáneamente.
     *
     * con [EaseOutCubic] la diferencia: EaseOutCubic Todavía hay avances significativos en las etapas intermedia y posterior,
     * Adecuado para desplazamiento; La sección final de esta curva es casi estacionaria, adecuada para "Tamaño/Cortar" este tipo de
     * Una escena donde el punto final debe ser estable.
     */
    val GrowEase: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.0f)

    /**
     * v2.x: Precipitación de especificación de resorte elástico — Presione para comprimir/Parámetros físicos de la escena del rebote de la tarjeta.
     * (Escritura original esparcida por todas partes. spring(MediumBouncy, StiffnessMedium);El nuevo código se toma de aquí.).
     */
    fun bouncySpring(): SpringSpec<Float> = SpringSpec(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}

/**
 * Muse ficha de diseño — Descripción unificada de la sombra (CMP-01).
 *
 * sombra/La elevación conserva solo un conjunto de escalas semánticas [MuseElevation](tonalElevation Compartido con sombraElevación),
 * Finalidad de cada archivo:
 *  - none(0) / micro(0.5): Sin capas / cerca de la superficie
 *  - low(1) / card(1):     Valor predeterminado de elemento de lista/tarjeta
 *  - medium(2):            Isla de entrada/barra flotante shadowElevation
 *  - high(4):              Artículo seleccionado/Tarjeta destacada
 *  - modal(8):             cuadro de diálogo / BottomSheet
 * Hay dos conjuntos históricos con el mismo nombre y valores diferentes. [MuseShadow] Eliminado,La persona que llama usa uniformemente [MuseElevation].
 */

/**
 * Muse ficha de diseño — Encapsulación semántica de retroalimentación háptica.
 *
 * voluntad Compose [HapticFeedback] La API original está envuelta en métodos semánticos.,
 * La persona que llama no necesita preocuparse por el subyacente [HapticFeedbackType] Selección,Tampoco hay necesidad de preocuparse por la lectura del interruptor.
 *
 * Uso:
 * ```
 * val haptic = LocalHapticFeedback.current
 * // Haga clic en el elemento de la lista
 * MuseHaptics.soft(haptic)
 * // botón/interruptor
 * MuseHaptics.light(haptic)
 * // enviar mensaje
 * MuseHaptics.medium(haptic)
 * // Eliminar/operación peligrosa
 * MuseHaptics.heavy(haptic)
 * ```
 *
 * Acceso al interruptor de preferencia(v1.0.14):
 *  - [enabled] El campo consta de [io.zer0.muse.MuseApp] Suscríbete al inicio
 *    [io.zer0.muse.data.SettingsRepository.chatPreferencesFlow] sincronizar,
 *    Usuario en "Configuración" → comportamiento de chat → Después de cambiar "retroalimentación háptica",todos [MuseHaptics] La convocatoria surte efecto inmediatamente.
 *  - La persona que llama no necesita leer el interruptor en sí.,Simplemente llame al método semántico directamente.
 *  - Seguridad del hilo:[enabled] para @Volatile,Todas las llamadas hápticas están en el hilo de la interfaz de usuario.,Sin riesgo de complicaciones.
 */
object MuseHaptics {
    /** Interruptor maestro táctil,Predeterminado true(Consistente con el valor predeterminado de ChatPreferences.hapticFeedback). */
    @Volatile
    private var enabled: Boolean = true

    /** Llamado por MuseApp cuando se inició,Sincronice las preferencias del usuario con el interruptor maestro. */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** toque: Interacciones diarias como clic en elementos de lista/expansión de tarjeta/control deslizante. */
    fun soft(haptic: HapticFeedback) {
        if (enabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** toque: Clic de botón/cambio de interruptor/selección de elemento de menú. */
    fun light(haptic: HapticFeedback) {
        if (enabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** medio: Mantenga presionado/enviar mensaje/deslizar para activar la eliminación/Archivar/confirmar operaciones. */
    fun medium(haptic: HapticFeedback) {
        if (enabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** fuerte ataque: Borrar/confirmación de operación peligrosa.
     * v1.0.74 fix (Auditoría inicial 3.3): Originalmente igual que medio. LongPress,Operaciones peligrosas y clics comunes
     * No hay diferencia en el tacto. Usar en su lugar Confirm(Android 13+ Tener un claro sentido de confirmación.,El sistema de versión inferior se degrada automáticamente).
     */
    fun heavy(haptic: HapticFeedback) {
        // v1.0.74 fix (Auditoría inicial 3.3): La versión actual de Compose tiene engranajes táctiles limitados disponibles.
        // (solo TextHandleMove/LongPress),heavy Utilice doble LongPress para simular una sensación de confirmación más fuerte,
        // Diferencia con LongPress simple medio.
        if (enabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
}
