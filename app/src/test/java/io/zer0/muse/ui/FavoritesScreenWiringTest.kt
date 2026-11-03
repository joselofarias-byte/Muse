package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regresión de favoritos a nivel de fuente (auditoría §11-1 / §11-8 / §11-9).
 *
 * Los tres de estos"El código se ve bien, pero la interfaz es reveladora."El tipo no puede detectarse mediante una prueba única lógica pura.
 * heredar [MessageBubbleActionMenuWiringTest] El enfoque clava directamente la estructura del código fuente.
 */
class FavoritesScreenWiringTest {

    @Test
    fun `preset group token never reaches the screen as a literal`() {
        val source = locate("app/src/main/java/io/zer0/muse/ui/FavoritesScreen.kt")

        // §11-1: El diálogo de grupo predeterminado fue la fuente de fugas de tokens internos y se eliminó.
        assertFalse(
            "Se debe eliminar el cuadro de diálogo de selección de grupo predeterminado (dice __group_*__ cadena interna)",
            source.contains("FavoriteGroupSelectDialog"),
        )
        // Los valores de stock deben asignarse a recursos de cadena cuando se leen, en lugar de mostrarse tal cual.
        assertTrue(
            "La copia de agrupación debe utilizar la asignación de favoritoTagLabel",
            source.contains("favoriteTagLabel(tag)"),
        )
        // La escritura grupal solo conserva una ruta de la etiqueta personalizada (definición única + punto de llamada único).
        assertEquals(
            "Sólo puede haber un cuadro de diálogo de grupo",
            2,
            Regex("FavoriteTagEditDialog\\(").findAll(source).count(),
        )
    }

    @Test
    fun `favorite star has a real selected state`() {
        val targets = listOf(
            "app/src/main/java/io/zer0/muse/ui/MessageBubble.kt",
            "app/src/main/java/io/zer0/muse/ui/translate/TranslateHistorySection.kt",
            "app/src/main/java/io/zer0/muse/ui/FavoritesScreen.kt",
        )

        targets.forEach { relative ->
            val source = locate(relative).lines()
                // El método de escritura anterior se citará como contraejemplo en los comentarios y la línea completa de comentarios se eliminará durante la comparación.
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .joinToString("\n")
            // §11-8: `if (x) MuseIcons.star else MuseIcons.star` Es una ecuación ternaria de identidad y los bits de estado no participan en la representación.
            assertFalse(
                "$relative Todavía existen fórmulas ternarias idénticas marcadas con estrellas",
                Regex("if \\([^)]*\\)\\s*MuseIcons\\.star\\s*else\\s*MuseIcons\\.star").containsMatchIn(source),
            )
        }

        // El estado seleccionado debe participar efectivamente en la prestación:tint / alpha / El tercer nivel del contenedor utiliza el mismo calibre.
        listOf(
            "app/src/main/java/io/zer0/muse/ui/translate/TranslateHistorySection.kt",
        ).forEach { relative ->
            val source = locate(relative)
            assertTrue("$relative Se debe utilizar FavoriteStarTint para distinguir el estado seleccionado.", source.contains("favoriteStarTint("))
        }
    }

    @Test
    fun `preview truncation is structural not a raw take`() {
        val source = locate("app/src/main/java/io/zer0/muse/ui/FavoritesScreen.kt")

        // §11-9: La toma a nivel de personaje eliminará las barreras de código/caracteres de énfasis / [N] Cita.
        assertFalse("La vista previa de la tarjeta de colección no se puede volver a utilizar take(", Regex("\\.take\\(\\s*280\\s*\\)").containsMatchIn(source))
        assertTrue(
            "El truncamiento de la vista previa debe implementarse mediante el conocimiento de la estructura.",
            source.contains("MarkdownPreviewTruncation.preview("),
        )
    }

    @Test
    fun `favorites screen no longer reads the dead group filter`() {
        val source = locate("app/src/main/java/io/zer0/muse/ui/FavoritesScreen.kt")

        // §11-13: favoriteGroup / setFavoriteGroup No hay ningún punto de llamada en toda la posición y la lógica de filtrado es una rama muerta.
        assertFalse("favoriteGroup Eliminado", source.contains("state.favoriteGroup"))
        assertFalse("setFavoriteGroup Eliminado", source.contains("viewModel.setFavoriteGroup"))
    }
}

private fun locate(relative: String): String {
    val candidates = listOf(Path.of(relative), Path.of("..").resolve(relative))
    val path = candidates.firstOrNull(Files::exists)
        ?: error("no encontrado $relative, directorio actual ${System.getProperty("user.dir")}")
    return Files.newBufferedReader(path).use { it.readText() }
}
