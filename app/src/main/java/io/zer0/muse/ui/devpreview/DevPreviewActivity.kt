package io.zer0.muse.ui.devpreview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import io.zer0.muse.ui.theme.MuseTheme

/**
 * Una actividad separada para la vista previa del desarrollo.
 *
 * El único propósito de la existencia: cuando no hay un proveedor de configuración ni una sesión real,
 * También puede ver el diseño real de la burbuja del mensaje (MOOD Secciones, líneas de resumen de tarjetas de pensamiento, etc.).
 *
 * Convertido independientemente en una Actividad en lugar de colgarlo en la entrada principal, es para permitir [io.zer0.muse.MainActivity]
 * El proceso de inicio no se ve afectado en absoluto. —— El código de vista previa no ingresa al enlace principal ni agrega ramas a la entrada principal.
 *
 * Uso:
 * ```
 * adb shell am start -n io.zer0.muse/.ui.devpreview.DevPreviewActivity
 * ```
 *
 * No está registrado en el escritorio y no verá la entrada durante el uso normal.
 */
class DevPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MuseTheme(darkTheme = isSystemInDarkTheme()) {
                DevPreviewScreen()
            }
        }
    }
}
