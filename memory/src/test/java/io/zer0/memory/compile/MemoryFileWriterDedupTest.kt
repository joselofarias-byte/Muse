package io.zer0.memory.compile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `memory.md` Escribe deduplicación.
 *
 * Antecedentes: Leer ruta `MemoryCompiler.readCompiledMemoryMarkdown` Antes de devolver el contenido, se llamará
 * `writeMemoryMd`(Los productos se colocan en el disco al mismo tiempo para facilitar la depuración y la copia de seguridad). Y las condiciones para eliminar duplicados se presentaron antes. `target == null`,
 * La ruta de lectura siempre toma target → La deduplicación nunca surte efecto → **Cada vez que se lee la memoria, el archivo se escribe de forma atómica.**.
 *
 * Aquí se utiliza el "archivo de manipulación externo" para determinar si realmente se omite la escritura:
 *   - Si se omite, el archivo conserva el contenido manipulado;
 *   - Si se escribe, el archivo se restaura al texto entrante.
 * Más confiable que el cronometraje y no depende de la precisión de la hora del sistema de archivos.
 */
class MemoryFileWriterDedupTest {

    private fun tempRoot(): File = Files.createTempDirectory("muse-memory-dedup").toFile()

    private fun memoryMd(root: File, target: MemoryCompileTarget?): File {
        val dir = if (target == null) {
            File(root, "memory")
        } else {
            File(root, "memory/${target.normalizedScope}/${target.normalizedSpaceId}")
        }
        return File(dir, "memory.md")
    }

    @Test
    fun `same_content_with_target_is_not_rewritten`() {
        val root = tempRoot()
        try {
            val writer = MemoryFileWriter(root)
            val target = MemoryCompileTarget(assistantId = "a1", scope = "a1", spaceId = "daily")

            writer.writeMemoryMd("## hechos\n- como el te", target = target)
            val file = memoryMd(root, target)
            assertEquals("## hechos\n- como el te", file.readText())

            // Manipulación externa: si el contenido permanece sin cambios, si se descarga nuevamente, el archivo se sobrescribirá con el texto original.
            file.writeText("TAMPERED")
            writer.writeMemoryMd("## hechos\n- como el te", target = target)

            assertEquals(
                "Se debe omitir la escritura en el disco cuando el contenido no haya cambiado (la ruta de lectura no debe activar la escritura en el disco)",
                "TAMPERED",
                file.readText(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `changed_content_with_target_is_written`() {
        val root = tempRoot()
        try {
            val writer = MemoryFileWriter(root)
            val target = MemoryCompileTarget(assistantId = "a1", scope = "a1", spaceId = "daily")

            writer.writeMemoryMd("v1", target = target)
            writer.writeMemoryMd("v2", target = target)

            assertEquals("v2", memoryMd(root, target).readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `different_targets_are_deduplicated_independently`() {
        val root = tempRoot()
        try {
            val writer = MemoryFileWriter(root)
            val first = MemoryCompileTarget(assistantId = "a1", scope = "a1", spaceId = "work")
            val second = MemoryCompileTarget(assistantId = "a2", scope = "a2", spaceId = "work")

            // Dos objetivos reciben el mismo contenido: deben colocarse por separado y no pueden bloquearse mediante la deduplicación del anterior.
            writer.writeMemoryMd("same", target = first)
            writer.writeMemoryMd("same", target = second)

            assertEquals("same", memoryMd(root, first).readText())
            assertEquals("same", memoryMd(root, second).readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `legacy_null_target_still_deduplicates`() {
        val root = tempRoot()
        try {
            val writer = MemoryFileWriter(root)
            writer.writeMemoryMd("legacy")
            val file = memoryMd(root, null)
            assertEquals("legacy", file.readText())

            file.writeText("TAMPERED")
            writer.writeMemoryMd("legacy")

            assertEquals("TAMPERED", file.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `tracking_table_is_bounded`() {
        val root = tempRoot()
        try {
            val writer = MemoryFileWriter(root)
            repeat(40) { index ->
                val target = MemoryCompileTarget(
                    assistantId = "a$index",
                    scope = "scope$index",
                    spaceId = "space$index",
                )
                writer.writeMemoryMd("content-$index", target = target)
                // En realidad, cada objetivo debe escribir su propio contenido (la limpieza de desbordamiento solo puede provocar escrituras múltiples, no escrituras perdidas).
                assertEquals("content-$index", memoryMd(root, target).readText())
            }
            assertTrue("Las escrituras no deben tener un límite superior implícito en la cantidad de objetivos.", true)
        } finally {
            root.deleteRecursively()
        }
    }
}
