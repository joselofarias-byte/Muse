package io.zer0.muse.ui.artifact

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2: El producto se guarda como un nombre de archivo para generar pruebas.
 */
class ArtifactExportFileNameTest {

    @Test
    fun `El título normal genera el nombre del archivo txt`() {
        assertEquals("report.txt", artifactExportFileName("report"))
    }

    @Test
    fun `Los caracteres ilegales se reemplazan con guiones bajos`() {
        assertEquals("My_Doc_2024.txt", artifactExportFileName("My Doc/2024"))
    }

    @Test
    fun `título chino reservado`() {
        assertEquals("análisis_de_datos.txt", artifactExportFileName("análisis de datos"))
    }

    @Test
    fun `El título vacío vuelve al nombre de archivo predeterminado`() {
        assertEquals("$DEFAULT_ARTIFACT_FILE_NAME.txt", artifactExportFileName("   "))
    }

    @Test
    fun `Títulos muy largos truncados a 40 caracteres`() {
        assertEquals("a".repeat(40) + ".txt", artifactExportFileName("a".repeat(100)))
    }
}
