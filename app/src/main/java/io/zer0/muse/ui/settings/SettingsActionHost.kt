package io.zer0.muse.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.zer0.muse.R
import io.zer0.muse.diagnostic.DiagnosticExporter
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.update.UpdateCheckResult
import io.zer0.muse.update.UpdateNotifier
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Establezca el ejecutor de la "acción en la página" en la entrada.
 *
 * Estos dos tipos de acciones, verificar actualizaciones y exportar diagnósticos, requieren contexto, rutina y estado de progreso, que se mantienen en un solo lugar;
 * Configure los resultados de la búsqueda en la página de inicio para compartirlos con la página de categorías, de modo que el comportamiento del clic no se escriba en ambos lugares.
 */
data class SettingsActionHost(
    /** Acción en curso (botón que muestra el círculo de progreso); inactivo es nulo. */
    val busyAction: SettingsActionId?,
    val run: (SettingsActionId) -> Unit,
)

@Composable
internal fun rememberSettingsActionHost(): SettingsActionHost {
    val context = LocalContext.current
    val updateNotifier: UpdateNotifier = koinInject()
    val scope = rememberCoroutineScope()
    var busyAction by remember { mutableStateOf<SettingsActionId?>(null) }

    val run: (SettingsActionId) -> Unit = { id ->
        when (id) {
            SettingsActionId.CHECK_UPDATE -> if (busyAction == null) {
                busyAction = id
                scope.launch {
                    val result = try {
                        updateNotifier.checkManually(context)
                    } finally {
                        busyAction = null
                    }
                    when (result) {
                        is UpdateCheckResult.NewVersion -> MuseToast.show(context.getString(R.string.update_found_new))
                        UpdateCheckResult.UpToDate -> MuseToast.show(context.getString(R.string.update_already_latest))
                        is UpdateCheckResult.Failed -> MuseToast.show(
                            context.getString(R.string.settings_about_update_check_failed, result.message),
                        )
                        UpdateCheckResult.Skipped -> Unit
                    }
                }
            }

            // La exportación incluye consultas de cola de WorkManager y escritura zip, y las coloca en la rutina para esperar los resultados sin bloquear la devolución de llamada de clic.
            SettingsActionId.EXPORT_DIAGNOSTICS -> scope.launch { DiagnosticExporter.share(context) }
        }
    }

    return SettingsActionHost(busyAction = busyAction, run = run)
}
