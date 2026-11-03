@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.ui.common.surface

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.zer0.muse.ui.common.findActivity

/** Configures Muse dialog windows without the platform dim layer. */
internal fun clearMuseWindowDim(window: Window?) {
    window ?: return
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    window.setDimAmount(0f)
}

@Composable
internal fun MuseDialogWindowEffect(forceFullScreen: Boolean = false, bottomAligned: Boolean = false) {
    val localView = LocalView.current
    val activityWindow = LocalContext.current.findActivity()?.window
    val dialogWindow = (localView.parent as? DialogWindowProvider)?.window

    DisposableEffect(localView, dialogWindow, forceFullScreen, bottomAligned) {
        if (dialogWindow == null) {
            onDispose { }
        } else {
            val originalFlags = dialogWindow.attributes.flags
            val originalNavigationColor = dialogWindow.navigationBarColor
            val originalNavigationContrast =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dialogWindow.isNavigationBarContrastEnforced
                } else {
                    null
                }
            val dialogInsetsController = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            val originalLightNavigation = dialogInsetsController.isAppearanceLightNavigationBars
            val originalLightStatus = dialogInsetsController.isAppearanceLightStatusBars
            val originalDimAmount = dialogWindow.attributes.dimAmount
            val originalWidth = dialogWindow.attributes.width
            val originalHeight = dialogWindow.attributes.height
            val originalCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes.layoutInDisplayCutoutMode
            } else {
                null
            }
            val originalGravity = dialogWindow.attributes.gravity
            val originalSoftInputMode = dialogWindow.attributes.softInputMode
            val originalBackground: Drawable? = dialogWindow.decorView.background

            clearMuseWindowDim(dialogWindow)
            // La ventana independiente sigue el contraste del icono de la barra del sistema host y prohíbe que el sistema superponga el área de gestos con un color protector.
            dialogWindow.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) dialogWindow.isNavigationBarContrastEnforced = false
            activityWindow?.let { host ->
                val hostController = WindowCompat.getInsetsController(host, host.decorView)
                dialogInsetsController.isAppearanceLightNavigationBars = hostController.isAppearanceLightNavigationBars
                dialogInsetsController.isAppearanceLightStatusBars = hostController.isAppearanceLightStatusBars
            }
            if (bottomAligned) {
                // Bottom menus use the platform decor boundary as the single source of
                // truth. In particular, do not read Compose WindowInsets and move the
                // panel again: on Android 15/16 Dialog insets can already be consumed.
                WindowCompat.setDecorFitsSystemWindows(dialogWindow, true)
                dialogWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                dialogWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    gravity = Gravity.BOTTOM
                }
            }
            if (forceFullScreen) {
                // v2.0 Reparación(Reproducción de máquina real del usuario.: scrim No puedo cubrir la barra de estado):
                // Sólo las ventanas setLayout todavía se dibujan desde el borde inferior de la barra de estado en algunas ROM — Desactivar explícitamente el ajuste de decoración,
                // Haga que la ventana comience en la esquina superior izquierda de la pantalla.,Y configúrelo nuevamente después del primer cuadro para evitar que el diseño interno de Dialog lo sobrescriba.
                WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
                dialogWindow.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                )
                dialogWindow.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
                dialogWindow.decorView.post {
                    dialogWindow.setLayout(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            onDispose {
                if (originalFlags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                } else {
                    dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                }
                dialogWindow.setDimAmount(originalDimAmount)
                dialogWindow.navigationBarColor = originalNavigationColor
                if (originalNavigationContrast != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    dialogWindow.isNavigationBarContrastEnforced = originalNavigationContrast
                }
                dialogInsetsController.isAppearanceLightNavigationBars = originalLightNavigation
                dialogInsetsController.isAppearanceLightStatusBars = originalLightStatus
                if (forceFullScreen) {
                    dialogWindow.setLayout(originalWidth, originalHeight)
                    dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                }
                if (bottomAligned) {
                    dialogWindow.setBackgroundDrawable(originalBackground)
                    dialogWindow.setSoftInputMode(originalSoftInputMode)
                    dialogWindow.attributes = dialogWindow.attributes.apply {
                        gravity = originalGravity
                    }
                }
                if (originalCutoutMode != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dialogWindow.attributes = dialogWindow.attributes.apply {
                        layoutInDisplayCutoutMode = originalCutoutMode
                    }
                }
            }
        }
    }
}
