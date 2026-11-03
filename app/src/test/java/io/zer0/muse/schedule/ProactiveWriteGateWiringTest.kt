package io.zer0.muse.schedule

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guardia de arquitectura: la inspección de mensajes activos debe mirar la puerta de recuperación antes de tocar los datos.
 *
 * Durante la copia de seguridad y la recuperación, la base de datos completa es reemplazada por la base de datos completa. En este momento, los mensajes activos escritos se escribirán en una base de datos que se sobrescribirá pronto.
 * La actuación es "Una vez completada la recuperación, aparece de la nada un mensaje adicional de hace tres días". Esta restricción sólo se puede observar leyendo el código fuente. ——
 * Su modo de falla es que los datos están contaminados, no que se lanza una excepción.
 */
class ProactiveWriteGateWiringTest {

    @Test
    fun `proactive cycle checks restore gate before doing anything else`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/proactive/ProactiveCycleRunner.kt"),
                Path.of("app/src/main/java/io/zer0/muse/proactive/ProactiveCycleRunner.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ProactiveCycleRunner.kt not found")

        // La puerta debe aparecer en la fase de preparación y estar clasificada en el asistente de lectura./Antes de la sesión de lectura
        val prepareRegion = source.substringAfter("private suspend fun gateBlock")
            .substringBefore("private suspend fun assemble")
        assertTrue(
            "Las puertas de restauración deben inspeccionarse durante la fase de preparación.",
            prepareRegion.contains("ProcessWriteGate.restoring"),
        )

        val assembleIndex = source.indexOf("private suspend fun assemble")
        val gateIndex = source.indexOf("ProcessWriteGate.restoring")
        assertTrue(
            "La puerta de restauración debe cargarse antes de cargar el material; de lo contrario, se leerá la biblioteca que se está reemplazando.",
            gateIndex in 0 until assembleIndex,
        )
    }
}
