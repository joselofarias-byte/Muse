package io.zer0.muse.ui.common.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge

/**
 * MuseToast Renderizar host — colocado en la raíz Composable,observar [MuseToast] estado.
 *
 * cuando MuseToast.show() cuando lo llaman,Una punta de cápsula translúcida se desliza hacia la parte superior.,
 * Se deslizará automáticamente y desaparecerá después del tiempo de espera.
 *
 * Visión:
 *  - Fondo oscuro translúcido(Más obvio bajo el tema oscuro)
 *  - texto blanco
 *  - esquinas redondeadas de la cápsula(24dp)
 *  - Centro superior flotante,No bloquea el área de entrada.
 */
@Composable
fun MuseToastHost() {
    val toastData by MuseToast.message.collectAsState()
    // Residente del host Toast a nivel de aplicación,No es necesario comenzar y detenerse en la página Ciclo de vida,Mantenga CollectAsState normal.

    // M-MTH1: Almacene en caché el último texto del sistema que no esté vacío.
    // ToastData ha sido borrado para null,Si se usa directamente toastData?.text mostrará texto vacío,
    // Hace que la animación deslizable muestre una cápsula en blanco. Utilice recordar para conservar el último texto.,Al salir de la animación aún se muestra el texto original.
    var lastText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toastData) {
        // v1.74: Evitación de captura de variables locales !!(Las propiedades delegadas no pueden smart-cast)
        val data = toastData
        if (data != null) {
            lastText = data.text
            kotlinx.coroutines.delay(data.durationMillis)
            MuseToast.clear()
        }
    }

    AnimatedVisibility(
        visible = toastData != null,
        // v2.6.7: Toast Cambiar a "crecer en el acto"(zoom + aparición gradual), ya no mueve toda la pantalla hacia arriba.
        // La barra de aviso debería aparecer directamente en la posición emergente. Si se desliza por toda la pantalla, robará el foco de atención.
        enter = MuseMotion.scaleFadeEnter(initialScale = 0.94f),
        exit = MuseMotion.scaleFadeExit(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // M-MTH2: 60.dp codificado → statusBars inset,Adecuado para flequillo/Diferencia de altura de la barra de estado.
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Surface(
                shape = MuseShapes.huge,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                shadowElevation = MuseElevation.modal,
                // M-MTH3: liveRegion = Polite, toast TalkBack habla automáticamente cuando cambia el texto,
                // Comparación del comportamiento de lectura sin barreras de Toast nativo(No interrumpir la operación actual del usuario.,Lee en voz alta en tu tiempo libre).
                modifier = Modifier
                    .padding(horizontal = MusePaddings.largeGap)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = lastText ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = MusePaddings.messageGap, vertical = MusePaddings.itemGap),
                )
            }
        }
    }
}
