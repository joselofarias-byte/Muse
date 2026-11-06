package io.zer0.memory.observe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** D3-P1: Prueba de registro de canalización(Agregar/Desplazar/Tolerancia a fallos). */
class PipelineLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `append writes one jsonl line per record`() {
        val log = PipelineLog(File(tmp.root, "pl.jsonl"))
        log.appendStep(
            MemoryStage.INDEX,
            "compileFacts",
            "daily",
            ok = true,
            durationMs = 5,
            failureKind = null,
            error = null,
        )
        log.appendStep(
            MemoryStage.EXTRACT,
            "deepMemory",
            "daily",
            ok = false,
            durationMs = 7,
            failureKind = FailureKind.PERMANENT,
            error = RuntimeException("parse"),
        )

        val lines = File(tmp.root, "pl.jsonl").readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"stage\":\"INDEX\""))
        assertTrue(lines[0].contains("\"ok\":true"))
        assertTrue(lines[1].contains("\"failureKind\":\"PERMANENT\""))
    }

    @Test
    fun `rotates to dot one when exceeding max bytes`() {
        val log = PipelineLog(File(tmp.root, "pl.jsonl"), maxBytes = 200)
        repeat(20) {
            log.appendStep(
                MemoryStage.INDEX,
                "compileFacts",
                "daily",
                ok = true,
                durationMs = 1,
                failureKind = null,
                error = null,
            )
        }
        assertTrue("El registro maestro debería existir", File(tmp.root, "pl.jsonl").exists())
        assertTrue("Se debe generar un archivo continuo después de exceder el límite.", File(tmp.root, "pl.jsonl.1").exists())
    }

    @Test
    fun `creates parent directories automatically`() {
        val log = PipelineLog(File(tmp.root, "nested/deep/pl.jsonl"))
        log.appendStep(
            MemoryStage.INDEX,
            "x",
            "t",
            ok = true,
            durationMs = 0,
            failureKind = null,
            error = null,
        )
        assertTrue(File(tmp.root, "nested/deep/pl.jsonl").exists())
    }
}
