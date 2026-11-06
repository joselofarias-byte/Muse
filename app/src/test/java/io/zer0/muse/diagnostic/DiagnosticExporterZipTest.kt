package io.zer0.muse.diagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * Verificación del contenido del embalaje para paquetes de exportación de diagnóstico.
 *
 * El punto no es el formato zip.,Más bien, "el usuario hace clic para exportar,El estado de alerta de fondo también se unirá". Este enlace:
 * [DiagnosticExporter.buildDiagnosticZip] Background-work.txt debe colocarse en el paquete.,
 * y el contenido es [io.zer0.muse.schedule.BackgroundWakeDiagnostics.formatSnapshot] Texto de instantánea.
 *
 * La colección en sí inevitablemente fallará en el entorno de prueba.(Sin moneda,WorkManager no inicializado),Esto solo cubre
 * "El error de recopilación no afecta la exportación completa del paquete", esta ruta de degradación.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticExporterZipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun entriesOf(zip: File): Map<String, String> = ZipFile(zip).use { zipFile ->
        zipFile.entries().asSequence()
            .filter { entry -> !entry.isDirectory }
            .associate { entry -> entry.name to zipFile.getInputStream(entry).bufferedReader().readText() }
    }

    @Test
    fun `diagnostic zip carries the background work snapshot`() = runBlocking {
        val zip = DiagnosticExporter.buildDiagnosticZip(context)
        assertNotNull("Diagnosticar fallas en el empaque zip", zip)
        val entries = entriesOf(zip!!)
        try {
            assertTrue("Falta información del dispositivo", entries.containsKey("device-info.txt"))
            assertTrue("Falta una instantánea de activación en segundo plano", entries.containsKey("background-work.txt"))
            val snapshot = entries.getValue("background-work.txt")
            assertTrue("A la instantánea le falta el encabezado", snapshot.startsWith("# Muse Instantánea de activación en segundo plano"))
            assertTrue("A la instantánea le falta el recuento de tareas", snapshot.contains("work_count="))
            // Aún debería haber una línea de explicación cuando falla la recopilación.,en lugar de un archivo vacío
            assertTrue(
                "Las instantáneas deben tener work_manager_error o líneas de tarea cuando falla la recopilación",
                snapshot.contains("work_manager_error=") || snapshot.contains(" | kind="),
            )
        } finally {
            zip.delete()
        }
    }
}
