package io.zer0.muse.miniphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Enviar activamente un mensaje privado al contrato de cableado del Trabajador:Período, retraso inicial, ruta de falla.
 *
 * ¿Por qué leer el código fuente en lugar de la programación real?:WorkManager Las tareas periódicas solo se registran en el dispositivo,No se puede clasificar en la prueba unitaria.,
 * Tampoco puede detectar incidentes como "el ciclo vuelve a ser de 15 minutos" o "la ruta de error se escribe como reintento". —— Y esto es contra lo que debemos protegernos.:
 * Los primeros se convertirán en ocho acosos diarios,Este último volverá a llamar dentro de los 30 segundos posteriores a un fallo de red.
 *
 * Para leer el código fuente `Files.newBufferedReader`(El almacén ya sabe cómo escribir pruebas de cableado.).
 * Múltiples rutas candidatas porque el directorio de trabajo de prueba puede ser el directorio del módulo,También podría ser la raíz del repositorio.
 */
class MpProactiveChatWorkerWiringTest {

    @Test
    fun `worker never retries and always reports success`() {
        val source = read(WORKER)

        assertTrue("Clase MpProactiveChatWorker no encontrada", source.contains("class MpProactiveChatWorker("))
        assertTrue("Debe regresar cuando no se pueda obtener el corredor. success", source.contains("if (runner == null) return Result.success()"))
        assertTrue("Debes regresar después de correr normalmente. success", source.contains("return Result.success()"))
        assertFalse("Los mensajes privados no solicitados no deben devolverse retry", source.contains("Result.retry"))
        // dos caminos(koin faltante / agotado)todos deben regresar success
        assertEquals(2, source.split("Result.success()").size - 1)
    }

    @Test
    fun `periodic work repeats every three hours after a ninety minute delay`() {
        val source = read(SCHEDULER)

        assertTrue("La constante del período debe ser de 3 horas.", source.contains("REPEAT_INTERVAL_HOURS: Long = 3L"))
        assertTrue("La constante de retraso inicial debe ser de 90 minutos.", source.contains("INITIAL_DELAY_MINUTES: Long = 90L"))
        assertTrue(
            "Las tareas periódicas deben programarse durante 3 horas.",
            source.contains("PeriodicWorkRequestBuilder<MpProactiveChatWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)"),
        )
        assertTrue(
            "El primer horario debe retrasarse 90 minutos.,Escalona tareas con el círculo de amigos",
            source.contains("setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)"),
        )
        assertTrue("Debe existir un nombre de tarea único,De lo contrario, se descartarán un montón de instancias duplicadas.", source.contains("UNIQUE_WORK_NAME"))
    }

    @Test
    fun `module scheduler entry registers proactive chat work`() {
        val source = read(MOMENT_SCHEDULER)

        assertTrue("El puerto de registro en segundo plano del módulo debe programar mensajes privados activos juntos.", source.contains("MiniPhoneProactiveChatScheduler.start(context)"))
        assertTrue("La cancelación de la entrada debe cancelarse al mismo tiempo.", source.contains("MiniPhoneProactiveChatScheduler.stop(context)"))
    }

    private fun read(candidates: List<Path>): String {
        return candidates
            .firstOrNull(Files::exists)
            ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
            ?: error("source not found: $candidates")
    }

    private companion object {
        val WORKER: List<Path> = listOf(
            Path.of("src/main/java/io/zer0/muse/miniphone/work/MpProactiveChatWorker.kt"),
            Path.of("miniphone/src/main/java/io/zer0/muse/miniphone/work/MpProactiveChatWorker.kt"),
            Path.of("../miniphone/src/main/java/io/zer0/muse/miniphone/work/MpProactiveChatWorker.kt"),
        )

        val SCHEDULER: List<Path> = listOf(
            Path.of("src/main/java/io/zer0/muse/miniphone/work/MiniPhoneProactiveChatScheduler.kt"),
            Path.of("miniphone/src/main/java/io/zer0/muse/miniphone/work/MiniPhoneProactiveChatScheduler.kt"),
            Path.of("../miniphone/src/main/java/io/zer0/muse/miniphone/work/MiniPhoneProactiveChatScheduler.kt"),
        )

        val MOMENT_SCHEDULER: List<Path> = listOf(
            Path.of("src/main/java/io/zer0/muse/miniphone/work/MiniPhoneMomentScheduler.kt"),
            Path.of("miniphone/src/main/java/io/zer0/muse/miniphone/work/MiniPhoneMomentScheduler.kt"),
            Path.of("../miniphone/src/main/java/io/zer0/muse/miniphone/work/MiniPhoneMomentScheduler.kt"),
        )
    }
}
