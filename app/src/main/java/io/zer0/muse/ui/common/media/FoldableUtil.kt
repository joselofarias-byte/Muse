package io.zer0.muse.ui.common.media

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * v2.x (C9): Detección de pantalla plegable — Basado en publico Android API([Sensor.TYPE_HINGE_ANGLE],API 30+).
 *
 * fondo:El SDK público de la plataforma no expone la posición de la línea de plegado(Display.getFeatures / DisplayFeature Todas son interfaces del sistema.,
 * No disponible para aplicaciones normales);La forma oficial disponible es**sensor de ángulo de bisagra**——juzgable"¿Es un dispositivo plegable?"con
 * "Ángulo de expansión actual",suficiente para apoyar tabletop(Medio doblado y colocado verticalmente.)Determinación de la escena.
 *
 * limitaciones:No puedo obtener la posición de la línea de plegado en la pantalla./frontera;Si se requiere un diseño de partición preciso más adelante,
 * Necesidad de evaluar la introducción. androidx.window(WindowInfoTracker + FoldingFeature)Dependencia.
 */
object MuseFoldable {

    /** Ya sea equipado con sensor de ángulo de bisagra(Dispositivo plegable);API < 30 Siempre falso. */
    fun hasHingeSensor(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null
    }
}

/**
 * Suscribir ángulo de bisagra(0 = cerrar,180 = completamente expandido);dispositivo no plegable / API < 30 / Permanece nulo si no hay eventos del sensor.
 *
 * Registrar el monitoreo del sensor cuando la combinación ingresa.,Cierra sesión automáticamente cuando te vayas.
 * Uso(Ramas de diseño futuro):
 * ```
 * val angle by rememberHingeAngle()
 * val isTabletop = angle?.let { it in 60f..120f } == true
 * ```
 */
@Composable
fun rememberHingeAngle(): State<Float?> {
    val context = LocalContext.current
    val angle = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context) {
        var listener: SensorEventListener? = null
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sm?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        } else {
            null
        }
        if (sm != null && sensor != null) {
            val l = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    angle.value = event.values.firstOrNull()
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            listener = l
            sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        onDispose {
            listener?.let { sm?.unregisterListener(it) }
        }
    }
    return angle
}
