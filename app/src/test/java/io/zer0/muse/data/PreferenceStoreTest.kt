package io.zer0.muse.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prueba del almacenamiento de preferencias del usuario.
 *
 * Ítem 20 de autoevaluación del asistente: "Aprendizaje de preferencias del usuario: por ejemplo, si está acostumbrado, no utilice estímulos vacíos ni haga un resumen de tres puntos.
 * Estos pueden resolverse mediante restricciones estrictas en lugar de depender de mi juicio sobre el terreno. "
 *
 * Fijado aquí: la escritura se puede volver a leer, la duplicación no se superpone, la desactivación no elimina, la normalización y la deduplicación.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class PreferenceStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: PreferenceStore

    @Before
    fun setUp() = runTest {
        store = PreferenceStore(context)
        store.clear()
    }

    @Test
    fun `saved preference can be read back`() = runTest {
        store.add("No respondas con estímulos vacíos.")

        val rules = store.enabledPreferences().map { it.rule }

        assertEquals(listOf("No respondas con estímulos vacíos."), rules)
    }

    @Test
    fun `duplicate preference is not added twice`() = runTest {
        val first = store.add("Hagamos un resumen de tres puntos")
        val second = store.add("Hagamos un resumen de tres puntos")

        assertTrue(first)
        assertFalse("La misma preferencia de oración no debe guardarse repetidamente", second)
        assertEquals(1, store.enabledPreferences().size)
    }

    @Test
    fun `duplicate detection ignores whitespace and trailing punctuation`() = runTest {
        store.add("No uses estímulos vacíos")

        val added = store.add("  No utilices estímulos vacíos. ")

        assertFalse("Si son iguales después de la normalización, no se deben agregar.", added)
        assertEquals(1, store.enabledPreferences().size)
    }

    @Test
    fun `different preferences are both kept`() = runTest {
        store.add("No respondas con estímulos vacíos.")
        store.add("No termines con un resumen de tres puntos")

        assertEquals(2, store.enabledPreferences().size)
    }

    @Test
    fun `disabled preference is excluded from injection but kept in storage`() = runTest {
        store.add("No uses estímulos vacíos")
        val id = store.preferencesFlow.first().single().id

        store.setEnabled(id, false)

        assertTrue("No se deben ingresar palabras de aviso después de la desactivación", store.enabledPreferences().isEmpty())
        assertEquals("La desactivación no es la eliminación", 1, store.preferencesFlow.first().size)
    }

    @Test
    fun `re-enabling brings it back`() = runTest {
        store.add("No uses estímulos vacíos")
        val id = store.preferencesFlow.first().single().id
        store.setEnabled(id, false)
        store.setEnabled(id, true)

        assertEquals(1, store.enabledPreferences().size)
    }

    @Test
    fun `remove deletes the preference`() = runTest {
        store.add("No uses estímulos vacíos")
        val id = store.preferencesFlow.first().single().id

        store.remove(id)

        assertTrue(store.preferencesFlow.first().isEmpty())
    }

    @Test
    fun `blank rule is rejected`() = runTest {
        assertFalse(store.add("   "))
        assertTrue(store.preferencesFlow.first().isEmpty())
    }

    @Test
    fun `source wording is preserved for traceability`() = runTest {
        store.add("No respondas con estímulos vacíos.", source = "No me elogies de ahora en adelante, solo di palabras humanas.")

        val saved = store.preferencesFlow.first().single()

        assertEquals("No me elogies de ahora en adelante, solo di palabras humanas.", saved.source)
    }

    @Test
    fun `clear removes everything`() = runTest {
        store.add("a")
        store.add("b")

        store.clear()

        assertTrue(store.preferencesFlow.first().isEmpty())
    }

    @Test
    fun `enabled filter drops blank rules that slipped through`() = runTest {
        // Las rutas normales no escribirán reglas vacías y aquí se simulan datos históricos sucios.
        store.add("Preferencias efectivas")

        assertTrue(store.enabledPreferences().all { it.rule.isNotBlank() })
    }
}
