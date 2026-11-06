package io.zer0.muse.license

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.compose.koinInject

/**
 * Phase 13: Página de licencias de código abierto.
 *
 * diseño(página de configuración"Open Source Licenses"):
 *  - TopAppBar Título"Licencia de código abierto"+ flecha de retorno
 *  - tarjeta de estadísticas superior: "Muse Utilice N dependencias de código abierto,Ambos son licencia Apache 2.0/MIT"
 *  - Tarjetas agrupadas por protocolo:
 *    Una pieza por grupo Surface,Dentro del grupo hay una lista de dependencias.(diseño de fila,Haga clic para ingresar el texto completo del acuerdo.)
 *  - Cada línea de dependencia: Nombre + Versión + Insignia de acuerdo + "Ver enlace del código fuente"flecha derecha
 *  - Haga clic en el título del grupo de protocolos. → Una ventana emergente muestra el texto completo del acuerdo.(Monospace fuente,copia desplazable)
 *  - haga clic en el enlace → Abrir proyecto en el navegador URL
 *
 * datos:
 *  - La lista de dependencias proviene de [LicenseRepository.loadManifest](assets/licenses/manifest.json)
 *  - El texto completo del acuerdo se carga de forma diferida bajo demanda.([LicenseRepository.loadLicenseText])
 */
@Composable
fun LicensesScreen(onBack: () -> Unit, repository: LicenseRepository = koinInject()) {
    val context = LocalContext.current
    var manifest by remember { mutableStateOf<LicenseManifest?>(null) }
    var showLicenseDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var dialogText by remember { mutableStateOf<String?>(null) }
    var dialogLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        manifest = repository.loadManifest()
    }

    // M10: Utilice LaunchedEffect para monitorear los cambios de showLicenseDialog y cargar el texto completo del acuerdo,
    // Alternativa al manual launch,Evite la desalineación del texto causada por condiciones de carrera de rutina al hacer clic en múltiples protocolos rápidamente
    LaunchedEffect(showLicenseDialog) {
        val key = showLicenseDialog ?: return@LaunchedEffect
        dialogText = null
        dialogLoading = true
        dialogText = repository.loadLicenseText(key.first)
        dialogLoading = false
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.licenses_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val m = manifest
        if (m == null) {
            // Cargando
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                MuseSpinner(
                    size = 32.dp,
                )
            }
            return@MusePageScaffold
        }
        if (m.dependencies.isEmpty()) {
            // El análisis falló/el manifiesto está vacío
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                MuseEmptyState(
                    icon = MuseIcons.info,
                    title = "Aún no hay datos de licencia",
                )
            }
            return@MusePageScaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Tarjeta de estadísticas superior ──
            item {
                HeaderSummary(manifest = m)
            }

            // ── Lista de dependencias agrupadas por protocolo ──
            m.groupedByLicense().forEach { (licenseId, entries) ->
                item(key = "license_$licenseId") {
                    LicenseGroupSection(
                        licenseId = licenseId,
                        entries = entries,
                        onClickProtocolText = {
                            showLicenseDialog = licenseId to licenseId.toLicenseLabel()
                        },
                        onClickUrl = { url ->
                            // M11: Utilice resultOf en su lugar runCatching,Preguntar al usuario después de detectar ActivityNotFoundException
                            resultOf {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }.onError { msg, t ->
                                MuseToast.show("No se encontró ninguna aplicación para abrir el enlace.")
                            }
                        },
                    )
                }
            }

            // ── Descripción inferior ──
            item {
                FooterNote()
            }
        }
    }

    // Ventana emergente de texto completo del acuerdo
    showLicenseDialog?.let { (licenseId, licenseLabel) ->
        LicenseTextDialog(
            title = licenseLabel,
            text = dialogText,
            isLoading = dialogLoading,
            onDismiss = {
                showLicenseDialog = null
                dialogText = null
            },
        )
    }
}

/**
 * tarjeta de estadísticas superior: App Nombre + número total de dependencias + breve descripción.
 */
@Composable
private fun HeaderSummary(manifest: LicenseManifest) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = MuseShapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
        ) {
            Text(
                text = "${manifest.appName} v${manifest.appVersion}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.licenses_total_count, manifest.totalCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Muse Lanzamiento comercial de código cerrado,Todas las dependencias de terceros son protocolos flexibles." +
                    "(Apache 2.0 / MIT / BSD / EPL-1.0)," +
                    "No es necesario abrir nuestro código,Sólo mantén esta declaración.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * Agrupación de un único protocolo(Surface Contenedor + fila de encabezado + lista de dependencias internas).
 *
 * Haga clic en la fila del título → Una ventana emergente muestra el texto completo del acuerdo.
 * Haga clic dentro de la fila"Ver código fuente"botón → Se abre el navegador.
 */
@Composable
private fun LicenseGroupSection(
    licenseId: String,
    entries: List<LicenseEntry>,
    onClickProtocolText: () -> Unit,
    onClickUrl: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Título del grupo de protocolo(Haga clic para ampliar el texto completo del acuerdo.)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClickProtocolText)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = licenseId.toLicenseLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Surface(
                shape = MuseShapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = "${entries.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "Ver acuerdo",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Icon(
                imageVector = MuseIcons.chevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }

        // lista de dependencia(baraja de cartas,Heredar el objeto visual SettingsGroup)
        SettingsGroup {
            entries.forEachIndexed { index, entry ->
                DependencyRow(
                    entry = entry,
                    onClickUrl = onClickUrl,
                )
                if (index < entries.lastIndex) {
                    SettingsGroupDivider()
                }
            }
        }
    }
}

/**
 * línea de dependencia única(Utilizado dentro del grupo de configuración).
 *
 * Diseño: [nombre + versión] [Letra pequeña de derechos de autor] [Ver fuente flecha derecha]
 * Haga clic en toda la fila → Si hay una URL, ábrela.;Sin respuesta sin URL(Evite comentarios retrasados).
 */
@Composable
private fun DependencyRow(entry: LicenseEntry, onClickUrl: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (entry.hasUrl) it.clickable { onClickUrl(entry.url) } else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // nombre + número de versión
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = entry.versionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // Derechos de autor(línea única omitida,Si es demasiado largo, se truncará automáticamente.)
            Text(
                text = entry.copyright,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
            )
            if (entry.notes.isNotBlank()) {
                Text(
                    text = entry.notes,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    maxLines = 2,
                )
            }
        }
        if (entry.hasUrl) {
            Icon(
                imageVector = MuseIcons.externalLink,
                contentDescription = "Ver código fuente",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Descripción inferior: asuntos legales/Portal de privacidad(v2.x Comience como un enlace real,Anteriormente era una descripción de marcador de posición.).
 */
@Composable
private fun FooterNote() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Si tienes preguntas sobre el acuerdo.,La declaración completa está disponible en el siguiente enlace." +
                "Los datos de esta página se actualizan cada vez que se construye.(generatedAt marcar el tiempo).",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(
                "Página de inicio del proyecto" to "https://github.com/Zer0Qing/Muse",
                "Sitio web oficial museai.ltd" to "https://museai.ltd",
            ).forEach { (label, url) ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        resultOf {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }.onError { _, _ -> MuseToast.show("No se encontró ninguna aplicación para abrir el enlace.") }
                    },
                )
            }
        }
    }
}

/**
 * Ventana emergente de texto completo del acuerdo — cierre con un solo botón,El texto largo se puede desplazar.
 */
@Composable
private fun LicenseTextDialog(title: String, text: String?, isLoading: Boolean, onDismiss: () -> Unit) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp),
            ) {
                when {
                    isLoading -> MuseSpinner(
                        modifier = Modifier
                            .align(Alignment.Center),
                        size = 24.dp,
                    )
                    text.isNullOrBlank() -> Text(
                        text = "No se encontró el texto del acuerdo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    else -> Column(
                        // v1.0.7 Reparación:Quitar anidamiento verticalScroll(MuseDialog content Tiene desplazamiento incorporado)
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MuseMonoFontFamily,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        dismissText = null,
    )
}
