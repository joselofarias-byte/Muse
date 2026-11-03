package io.zer0.muse.schedule

import kotlinx.coroutines.CancellationException

/**
 * La estrategia de falla del control maestro de programación en segundo plano.
 *
 * SettingsRepository El propio flujo proporciona valores predeterminados; Sólo se permiten enlaces de lectura que se vuelvan verdaderos explícitamente.
 * Continúe con las tareas de efectos secundarios. Omita esta ejecución cuando falle la lectura o falten dependencias para evitar que los usuarios desactiven la programación en segundo plano.
 * Más tarde, debido a una excepción de DataStore/Koin, la operación de notificación, red o copia de seguridad falló al abrirse.
 */
internal fun scheduleWorkEnabledOrFalse(value: Boolean?): Boolean = value == true

internal fun scheduleWorkEnabledFromRepository(available: Boolean, value: Boolean?): Boolean =
    available && scheduleWorkEnabledOrFalse(value)

internal suspend fun readScheduleWorkEnabledOrFalse(reader: suspend () -> Boolean): Boolean = try {
    scheduleWorkEnabledOrFalse(reader())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}
