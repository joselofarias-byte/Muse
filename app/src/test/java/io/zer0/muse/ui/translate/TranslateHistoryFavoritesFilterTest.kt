package io.zer0.muse.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §11-6: Filtrar colección de traducciones.
 *
 * Se puede hacer clic en la colección, se puede colocar en la biblioteca y se puede ingresar en los favoritos del estado, pero nunca ha habido una salida en la historia. —— UI Sólo renderiza la historia.
 * Fijar aquí"Cuando se cambia a la vista de colección, se muestran los favoritos y, cuando se vuelve a cambiar, se muestra el historial completo.".
 */
class TranslateHistoryFavoritesFilterTest {

    private fun item(id: String, favorite: Boolean) = TranslateViewModel.TranslateHistoryItem(
        id = id,
        sourceText = "source-$id",
        translatedText = "translated-$id",
        sourceLanguage = "chino",
        targetLanguage = "English",
        timestamp = 1_000L,
        favorite = favorite,
    )

    @Test
    fun `favorites only shows the favorite list`() {
        val history = listOf(item("a", true), item("b", false))

        val shown = TranslateViewModel.historyForDisplay(
            history = history,
            favorites = history.filter { it.favorite },
            favoritesOnly = true,
        )

        assertEquals(listOf("a"), shown.map { it.id })
    }

    @Test
    fun `turning the filter off restores the full history`() {
        val history = listOf(item("a", true), item("b", false))

        val shown = TranslateViewModel.historyForDisplay(
            history = history,
            favorites = history.filter { it.favorite },
            favoritesOnly = false,
        )

        assertEquals(listOf("a", "b"), shown.map { it.id })
    }

    @Test
    fun `no favorites yields an empty list rather than falling back to history`() {
        val history = listOf(item("a", false))

        val shown = TranslateViewModel.historyForDisplay(
            history = history,
            favorites = emptyList(),
            favoritesOnly = true,
        )

        assertEquals(emptyList<String>(), shown.map { it.id })
    }
}
