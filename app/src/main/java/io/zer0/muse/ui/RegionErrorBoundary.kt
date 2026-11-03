package io.zer0.muse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.delay

/**
 * I3 límite de error de área(Defensivo de datos):área de chat/área de entrada/Las áreas de la lista se degradan de forma independiente,Error en la construcción de datos de renderizado de un solo bloque
 * No arrastra hacia abajo toda la página,y vuelva a intentarlo automáticamente.
 *
 * Compose 1.7.6 No se detecta error en el período de combinación API(try-catch Llamada de composición de paquete prohibida por el compilador,
 * AndroidComposeView.setErrorHandler Introducido desde 1.8),Por lo tanto, la etapa de preparación de datos antes de la representación del área es
 * aislado en común capturable lambda [data] en:Construcción de datos/analizar/Esta área se degrada de forma independiente cuando el formateo genera una excepción.
 * combinación/Diseño/Excepción durante el período de sorteo, no hay canal de captura a nivel de región,Reportado globalmente por MuseCrashHandler.
 *
 * Retraso después de la degradación [autoRetryDelayMs] Reconstruir automáticamente una vez,Si vuelve a fallar, solo quedan los reintentos manuales.
 */
@Composable
internal fun <T> RegionErrorBoundary(
    regionName: String,
    modifier: Modifier = Modifier,
    autoRetryDelayMs: Long = 3_000L,
    data: () -> T,
    render: @Composable (T) -> Unit,
) {
    var failed by remember(regionName) { mutableStateOf(false) }
    var generation by remember(regionName) { mutableIntStateOf(0) }
    var autoRetried by remember(regionName) { mutableStateOf(false) }
    val retry: () -> Unit = {
        autoRetried = true
        failed = false
        generation++
    }
    if (failed) {
        RegionFallback(modifier = modifier, onRetry = retry)
        if (!autoRetried) {
            LaunchedEffect(regionName, generation) {
                delay(autoRetryDelayMs)
                retry()
            }
        }
    } else {
        val dataResult = runCatching { data() }
        if (dataResult.isFailure) {
            // I3: Error al generar datos de renderizado(Como el análisis/Excepción de formato)→ La región se degrada en lugar de colapsar,Ver categoría por motivos comerciales KDoc
            failed = true
        } else {
            val value = dataResult.getOrThrow()
            key(generation) {
                render(value)
            }
        }
    }
}

/** I3 Marcador de posición de degradación:Icono de error + copiar + botón de reintento manual. */
@Composable
private fun RegionFallback(modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(MusePaddings.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = MuseIcons.alertTriangle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(MusePaddings.screen),
        )
        Spacer(Modifier.height(MusePaddings.itemGap))
        Text(
            text = stringResource(R.string.chat_region_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.itemGap))
        MuseTactileButton(
            icon = MuseIcons.refresh,
            onClick = onRetry,
            contentDescription = null,
        )
    }
}
