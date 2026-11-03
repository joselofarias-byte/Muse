package io.zer0.muse.ui.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-TEST-20: Prueba de almacenamiento del glosario de traducción.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class GlossaryStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = GlossaryStore(context)

    @Test
    fun `add and list round trip`() {
        store.clear()
        store.add("API", "API")
        assertEquals(mapOf("API" to "API"), store.list())
    }

    @Test
    fun `add trims keys and values`() {
        store.clear()
        store.add("  GPU  ", "  procesador de gráficos  ")
        assertEquals(mapOf("GPU" to "procesador de gráficos"), store.list())
    }

    @Test
    fun `add ignores blank original`() {
        store.clear()
        store.add("  ", "value")
        assertTrue(store.isEmpty())
    }

    @Test
    fun `remove deletes existing entry only`() {
        store.clear()
        store.add("API", "API")
        assertTrue(store.remove("API"))
        assertFalse(store.remove("API"))
        assertTrue(store.isEmpty())
    }

    @Test
    fun `clear empties glossary`() {
        store.clear()
        store.add("A", "B")
        store.clear()
        assertTrue(store.isEmpty())
    }

    @Test
    fun `toPromptSnippet formats entries and returns empty for empty glossary`() {
        store.clear()
        assertEquals("", store.toPromptSnippet())
        store.add("API", "API")
        store.add("GPU", "procesador de gráficos")
        val snippet = store.toPromptSnippet()
        assertTrue(snippet.contains("API→API"))
        assertTrue(snippet.contains("GPU→procesador de gráficos"))
    }
}
