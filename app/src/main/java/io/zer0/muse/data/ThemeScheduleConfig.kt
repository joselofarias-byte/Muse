package io.zer0.muse.data

import kotlinx.serialization.Serializable

/**
 * Configuración de conmutación programada de tema.
 *
 * Cambia automáticamente los colores brillantes en momentos específicos/Modo oscuro.
 */
@Serializable
data class ThemeScheduleConfig(
    /** Interruptor maestro. */
    val enabled: Boolean = false,
    /** hora de despertar(horas,0-23). Después de eso, cambie al modo de luz.(o seguir el sistema). */
    val wakeUpHour: Int = 7,
    /** hora de despertar(minutos,0-59). */
    val wakeUpMinute: Int = 0,
    /** hora de dormir(horas,0-23). Después de eso, cambie al modo oscuro. */
    val sleepHour: Int = 22,
    /** hora de dormir(minutos,0-59). */
    val sleepMinute: Int = 0,
)
