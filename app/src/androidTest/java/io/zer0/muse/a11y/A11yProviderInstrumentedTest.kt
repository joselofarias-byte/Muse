package io.zer0.muse.a11y

import android.content.Context
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.zer0.muse.tools.system.AccessibilityClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2.2.1: Acceso dual sin barreras(Proveedor independiente/servicio en la aplicación)Verificación de equipos.
 *
 * prefijo(Preparado por guión externo.,asumir omitir si falta,No juzgues el fracaso):
 *  1. Provider caso de uso: Instalado accessibility-provider-debug.apk(Igual que la firma de depuración)y sus servicios de accesibilidad están habilitados;
 *  2. Casos de uso en la aplicación: Los servicios de accesibilidad de la aplicación principal están habilitados.,y el servicio del Proveedor no está habilitado(En caso contrario, la ruta tendrá prioridad. Provider).
 *
 * Verificar enlace: bindService/instancia estática → UI Interfaz de operación → Proceso cruzado/Lea el nivel de UI y la actividad actual en el mismo proceso.
 */
@RunWith(AndroidJUnit4::class)
class A11yProviderInstrumentedTest {
    @Test
    fun provider_bridge_serves_ui_operations() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AccessibilityClient(context)

        assumeTrue("Proveedor independiente APK no instalado,saltar", client.isProviderInstalled())
        assumeTrue(
            "El servicio de accesibilidad del proveedor independiente no está habilitado en la configuración del sistema,saltar",
            isServiceEnabled(context, AccessibilityClient.PROVIDER_PACKAGE),
        )

        // ColorOS Esperando restricciones en segundo plano de ROM personalizadas(WIU lógica)Se denegará la vinculación entre aplicaciones para aplicaciones en segundo plano.
        // Traiga sus propias actividades al primer plano antes de realizar la prueba.,Simular escenarios de uso reales(Vincular cuando el usuario abre la aplicación).
        bringSelfToFront(context)

        // El enlace es asincrónico: Votación para puente listo(binder Después de alcanzar la transferencia isConnected true)
        var connected = false
        for (attempt in 0 until 24) {
            if (client.isConnected()) {
                connected = true
                break
            }
            Thread.sleep(250)
        }
        assertTrue("Provider Puente no listo en 6 segundos", connected)

        assertReadable(context, client)
    }

    @Test
    fun in_app_service_serves_ui_operations_when_provider_off() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = AccessibilityClient(context)

        assumeTrue(
            "Los servicios de accesibilidad en la aplicación no están habilitados en la configuración del sistema,saltar",
            isServiceEnabled(context, context.packageName),
        )
        assumeTrue(
            "El proveedor independiente todavía está habilitado(Las rutas tienen prioridad sobre los servicios dentro de la aplicación),saltar",
            !isServiceEnabled(context, AccessibilityClient.PROVIDER_PACKAGE),
        )

        bringSelfToFront(context)

        var connected = false
        for (attempt in 0 until 40) {
            if (client.isConnected()) {
                connected = true
                break
            }
            Thread.sleep(250)
        }
        // instrument El inicio forzará la detención de la aplicación,Algunas ROM no vuelven a vincular los servicios de accesibilidad dentro de la aplicación al proceso de prueba;
        // La escena se omite en lugar de fallar.(La ruta del proveedor entre procesos también tiene una anulación estricta del caso de uso de Provider_bridge.).
        assumeTrue("Los servicios de accesibilidad en la aplicación no están listos durante el proceso de prueba(force-stop Restricciones de vinculación),saltar", connected)

        assertReadable(context, client)
    }

    /** Aserción de legibilidad de pantalla: Nombre del componente de actividad actual + nivel de UI no vacío(con reintento,El estado de la ventana puede retrasarse). */
    private suspend fun assertReadable(context: Context, client: AccessibilityClient) {
        var activity = ""
        for (attempt in 0 until 6) {
            activity = client.currentActivityName()
            if (activity.contains('/')) break
            Thread.sleep(500)
        }
        assertTrue("currentActivityName Se debe devolver el nombre del componente.,real: $activity", activity.contains('/'))

        var page = ""
        for (attempt in 0 until 8) {
            page = client.getPageInfo()
            if (page.contains("[activity]")) break
            Thread.sleep(500)
        }
        assertTrue("getPageInfo Debería devolver texto jerárquico no vacío,real: $page", page.isNotBlank())
        assertTrue("getPageInfo debe contener [activity] cabeza,real: $page", page.contains("[activity]"))
    }

    private fun bringSelfToFront(context: Context) {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        }
        Thread.sleep(2000)
    }

    /** Si el servicio de accesibilidad que analiza el paquete especificado está habilitado a través de la configuración Seguro(No depende de la implementación interna). */
    private fun isServiceEnabled(context: Context, packageName: String): Boolean {
        val enabled =
            runCatching {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            }.getOrNull().orEmpty()
        return enabled.split(':').any { entry ->
            entry.trim().startsWith("$packageName/")
        }
    }
}
