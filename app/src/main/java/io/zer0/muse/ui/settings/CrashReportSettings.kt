package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.crash.CrashReporterFactory
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.crash.SubmitOutcome
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

/**
 * Página de informes de fallos — Un botón sin nada que el usuario pueda completar.
 *
 * ## ¿Qué se ha cambiado en esta versión?
 *
 * La versión anterior requiere que los usuarios seleccionen "Correo/Webhook" y luego completen la dirección de correo electrónico de recepción o la URL del Webhook. Esta es la infraestructura para desarrolladores.
 * La configuración se envía al usuario final: no sabe quién completar, falla silenciosamente si comete un error y parece que la página de configuración le pide que tome una decisión técnica.
 *
 * Ahora sólo quedan tres cosas:
 *  1. Vea cuántos artículos están esperando ser reportados
 *  2. Haga clic en "Reportar ahora"→ Enviar directamente al servidor del desarrollador (museai.ltd), un clic para completar
 *  3. Cuando no se puede acceder al servidor, se utiliza automáticamente el correo electrónico. Se ha completado el destinatario y el usuario sólo necesita hacer clic en enviar en la aplicación de correo electrónico.
 *
 * ## Límites de privacidad (sin relajación)
 *
 *  - Los informes solo se producen cuando el usuario hace clic en el botón y no hay informes silenciosos en segundo plano.
 *  - Primer paso de la pila de choque [MuseCrashHandler.redactSensitive] Desensibilización (API key / Bearer token Reemplazar con ***)
 *  - Solo recopile modelo/marca/versión de Android/ABI/versión de la aplicación/marca de tiempo
 *  - no recoger IMEI / Android ID / MAC / ubicación/cuenta
 */
@Composable
fun CrashReportSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    // Reutilizar named("chat") OkHttpClient(El tiempo de espera se sobrescribirá internamente)
    val chatClient: OkHttpClient = koinInject<OkHttpClient>(qualifier = named("chat"))
    val scope = rememberCoroutineScope()

    val anrDetection by settings.anrDetectionFlow.collectAsStateWithLifecycle(initialValue = true)

    var pendingCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        pendingCount = MuseCrashHandler.getPendingCrashReports(context).size
    }

    var showConfirm by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_crash_page_title), onBack = onBack) {
        // ── 1. Fallo que se informará + informar inmediatamente ──
        item { SectionLabel(stringResource(R.string.settings_crash_pending_section)) }
        item {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.cardInner),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.bug,
                        contentDescription = null,
                        tint = if (pendingCount > 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_crash_pending_count, pendingCount),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (pendingCount > 0) {
                                stringResource(R.string.settings_crash_pending_hint_active)
                            } else {
                                stringResource(R.string.settings_crash_pending_hint_idle)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (reporting) {
                        MuseSpinner(size = 20.dp)
                    }
                }
                SettingsGroupDivider()
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_crash_report_now_button),
                    onClick = { showConfirm = true },
                    enabled = pendingCount > 0 && !reporting,
                    leadingIcon = MuseIcons.cloudUpload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                // El destino es fijo y se puede escribir en una línea debajo del botón. No es un control que requiera operación del usuario.
                Text(
                    text = stringResource(R.string.settings_crash_target_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }

        // ── 2. ajustes(ANR Detección) ──
        item { SectionLabel(stringResource(R.string.settings_crash_settings_section)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.bell,
                    title = stringResource(R.string.settings_crash_anr_title),
                    subtitle = stringResource(R.string.settings_crash_anr_subtitle),
                    checked = anrDetection,
                    onCheckedChange = { v ->
                        scope.launch { settings.saveAnrDetection(v) }
                    },
                )
            }
        }

        // ── 3. Declaración de privacidad ──
        item { SectionLabel(stringResource(R.string.settings_crash_privacy_section)) }
        item {
            SettingsGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                ) {
                    Text(
                        text = stringResource(R.string.settings_crash_privacy_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.settings_crash_privacy_content),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // Confirmación del informe (conserve este paso: antes de enviar el contenido, los usuarios tienen derecho a saber qué están enviando)
    if (showConfirm) {
        MuseDialog(
            onDismissRequest = { showConfirm = false },
            title = stringResource(R.string.settings_crash_confirm_dialog_title),
            content = {
                Text(
                    text = stringResource(R.string.settings_crash_confirm_dialog_content, pendingCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.settings_crash_confirm_button),
            onConfirm = {
                showConfirm = false
                reporting = true
                scope.launch {
                    val outcome = CrashReporterFactory.submit(
                        context = context,
                        client = chatClient,
                        crashLogs = MuseCrashHandler.getPendingCrashReports(context),
                    )
                    // El servidor borra la cola sólo después de aceptarla; cuando se devuelve el correo electrónico, es imposible saber si el usuario realmente lo envió en la aplicación de correo.
                    // Por lo tanto, mantenga registros para evitar "hacer clic pero no enviarlo, pero nunca volver a verlo la próxima vez".
                    if (outcome is SubmitOutcome.Uploaded) {
                        MuseCrashHandler.clearCrashQueue(context)
                    }
                    reporting = false
                    pendingCount = MuseCrashHandler.getPendingCrashReports(context).size
                    MuseToast.show(
                        when (outcome) {
                            is SubmitOutcome.Uploaded ->
                                context.getString(R.string.settings_crash_done_uploaded, outcome.count)
                            is SubmitOutcome.MailFallback ->
                                context.getString(R.string.settings_crash_done_mail)
                            SubmitOutcome.NothingToSend, SubmitOutcome.Failed ->
                                context.getString(R.string.settings_crash_done_failed)
                        },
                    )
                    Logger.i("CrashReportSettings", "Informar resultados: $outcome, restante $pendingCount Artículo")
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showConfirm = false },
        )
    }
}
