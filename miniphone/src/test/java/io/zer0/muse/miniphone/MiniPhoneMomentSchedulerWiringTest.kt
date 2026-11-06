package io.zer0.muse.miniphone

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * contrato de cableado:Cuando se inicia el anfitrión, debe registrar el fondo del pequeño teléfono celular para publicar automáticamente en Momentos.
 *
 * ¿Por qué utilizar la afirmación del código fuente en lugar de ejecutar la aplicación: las tareas periódicas de WorkManager solo están programadas en máquinas reales?,
 * En realidad, ninguno de los dos aparecerá en la prueba unitaria.,No puedo detectar "olvidé ajustar"—— Y este es exactamente el error del que hay que protegerse.:
 * Hay un conjunto completo de programación en el módulo.,El anfitrión no respondió.,La función no existe.
 *
 * Para leer el código fuente `Files.newBufferedReader`(El almacén ya sabe cómo escribir pruebas de cableado.),No hay necesidad `Files.readString`.
 * Las tres rutas candidatas se deben a que el directorio de trabajo de prueba puede ser el directorio del módulo.,También podría ser la raíz del repositorio.
 */
class MiniPhoneMomentSchedulerWiringTest {

    @Test
    fun `muse app registers miniphone moment scheduler in onCreate`() {
        val source = MUSE_APP_CANDIDATES
            .firstOrNull(Files::exists)
            ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
            ?: error("MuseApp.kt not found")

        assertTrue(
            "MuseApp El planificador del módulo debe importarse.",
            source.contains("import io.zer0.muse.miniphone.work.MiniPhoneMomentScheduler"),
        )

        val onCreateAt = source.indexOf("override fun onCreate()")
        val callAt = source.indexOf("MiniPhoneMomentScheduler.start(this)")
        assertTrue("MuseApp.kt no puedo encontrarlo override fun onCreate()", onCreateAt >= 0)
        assertTrue("MuseApp.onCreate No se registraron tareas en segundo plano para teléfonos celulares pequeños", callAt > onCreateAt)

        // El registro solo ocurre una vez al inicio;stop No lo ajuste aquí(Apagar el interruptor solo permite al trabajador saltarse rondas inactivas.)
        assertTrue(
            "La ruta de inicio no debería cancelar la tarea sin problemas",
            !source.substringAfter("override fun onCreate()").contains("MiniPhoneMomentScheduler.stop"),
        )
    }

    private companion object {
        val MUSE_APP_CANDIDATES: List<Path> = listOf(
            Path.of("../app/src/main/java/io/zer0/muse/MuseApp.kt"),
            Path.of("app/src/main/java/io/zer0/muse/MuseApp.kt"),
            Path.of("src/main/java/io/zer0/muse/MuseApp.kt"),
        )
    }
}
