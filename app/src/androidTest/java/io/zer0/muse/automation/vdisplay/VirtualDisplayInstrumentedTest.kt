package io.zer0.muse.automation.vdisplay

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.zer0.muse.vdproto.IVirtualDisplayService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v2.2.1 pantalla virtual:Lado del equipo e2e(shell Servidor + transmisión mano a mano + creación de pantalla + inicio en pantalla + captura de pantalla).
 *
 * prefijo(Preparado por guión externo.,asumir omitir si falta,No juzgues el fracaso):
 *  1. El jar del servidor se implementó en /data/local/tmp/muse-vd-server.jar y se inició con el uid del shell.;
 *  2. binder A esta aplicación se ha llegado vía retransmisión de la mano.(El registro no está vacío.).
 *
 * Este caso de uso no depende de Shizuku(La parte de inicio se reemplaza por adb con el comando equivalente,Servidor de autenticación/contrato/Enlace completo de captura de pantalla).
 */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayInstrumentedTest {
    @Test
    fun server_handoff_display_and_screenshot_work() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // El servidor vuelve a publicar cada 5 segundos. binder;instrument El registro está vacío después de reiniciar la aplicación.,
        // Las encuestas aquí esperan que llegue la próxima ronda de transmisiones(la mayoría 10s).
        var binder = VirtualDisplayBinderRegistry.current()
        for (attempt in 0 until 40) {
            if (binder != null) break
            Thread.sleep(250)
            binder = VirtualDisplayBinderRegistry.current()
        }
        assumeTrue("La carpeta del servidor de pantalla virtual no llegó en 10 segundos.(Frontend no iniciado),saltar", binder != null)

        val proxy = IVirtualDisplayService.Stub.asInterface(binder)
        assertTrue("Error en la detección de supervivencia del servidor(isAlive=false)", proxy.isAlive)

        val displayId = proxy.ensureDisplay(720, 1280, 320)
        assertTrue("ensureDisplay debería volver válido displayId,real $displayId", displayId >= 0)

        // Inicie la aplicación Configuración desde la pantalla,Produzca contenido de renderizado realista
        val launched = proxy.launchApp("com.android.settings", displayId)
        Log.i(TAG, "VD-E2E displayId=$displayId launched=$launched")

        // El primer fotograma puede retrasarse:Reintentar esperando
        var bytes: ByteArray? = null
        for (attempt in 0 until 12) {
            Thread.sleep(500)
            bytes = proxy.requestScreenshot(displayId)
            if (bytes != null && bytes.isNotEmpty()) break
        }
        assertNotNull("requestScreenshot Se deben devolver los bytes de imagen.(Creación de pantalla del lado del servidor/Anormalidad en el enlace de captura de pantalla)", bytes)
        assertTrue("Los bytes de imagen no están vacíos.", bytes!!.isNotEmpty())
        assertTrue(
            "Salida JPEG esperada(FFD8),real ${bytes.take(2).map { it.toInt() and 0xFF }}",
            (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8,
        )

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("Las capturas de pantalla deben ser decodificables", decoded)
        assertTrue(
            "El tamaño decodificado debe ser 720x1280,real ${decoded.width}x${decoded.height}",
            decoded.width == 720 && decoded.height == 1280,
        )

        // dejar evidencia:Directorio privado de la aplicación + registro(Los scripts externos pueden ser extraídos por adb)
        val dir = File(context.filesDir, "vd").apply { mkdirs() }
        File(dir, "e2e_shot.jpg").writeBytes(bytes)
        Log.i(TAG, "VD-E2E shot saved bytes=${bytes.size} displayId=$displayId")

        // Deje de lado la inyección de script externo `input -d <displayId>` ventana,Luego capture otro fotograma para comparar los cambios de contenido.
        Log.i(TAG, "VD-E2E input-window open displayId=$displayId")
        Thread.sleep(10_000)
        val after = proxy.requestScreenshot(displayId)
        if (after != null && after.isNotEmpty()) {
            File(dir, "e2e_shot_after.jpg").writeBytes(after)
            Log.i(TAG, "VD-E2E after-shot saved bytes=${after.size} displayId=$displayId")
        } else {
            Log.w(TAG, "VD-E2E after-shot unavailable")
        }

        val destroyed =
            runCatching {
                proxy.destroyDisplay(displayId)
                true
            }.getOrDefault(false)
        assertTrue("destroyDisplay excepción de llamada", destroyed)
    }

    companion object {
        private const val TAG = "VdE2E"
    }
}
