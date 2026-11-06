package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Búsqueda de texto completo de varias palabras: dos etapas (prioridad precisa + cualquier resultado)+ LIKE Divide las palabras y di la verdad.
 *
 * Cobertura: las palabras clave dispersas en varias líneas de datos se pueden recuperar juntas y las rondas se relajarán cuando se realicen suficientes aciertos precisos.
 * Si no se acierta toda la cadena de LIKE, se acertará por palabra.OR De hecho, la semántica puede recuperar las filas perdidas por AND y la cadena de consulta no contiene tokens de una sola palabra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactMultiTermSearchTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
        store = FactStore(dao, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun addFact(text: String, importance: Int = 0): Long =
        store.add(FactStore.Fact(fact = text, importance = importance), "main", "default")

    @Test
    fun `scattered keywords are all found in one two stage search`() = runTest {
        val zhiqi = addFact("A Ziqi le gusta hacer senderismo en las montañas los fines de semana.")
        val developer = addFact("Los desarrolladores tratan este proyecto como un pasatiempo a largo plazo.")
        val assistant = addFact("El asistente tiene la costumbre de responder mensajes a altas horas de la noche")

        val hits = store.searchFullTextScopedTwoStage("Asistente de desarrollo de Ziqi", "main", "default", limit = 10)

        assertEquals("Tres palabras repartidas en tres líneas.,debe ser recuperado: ${hits.map { it.fact }}", 3, hits.size)
        assertEquals(setOf(zhiqi, developer, assistant), hits.map { it.id }.toSet())
    }

    @Test
    fun `whole string like misses while per word like hits`() = runTest {
        addFact("A Ziqi le gusta hacer senderismo en las montañas los fines de semana.")
        addFact("Los desarrolladores tratan este proyecto como un pasatiempo a largo plazo.")

        // EXACT Duan va primero FTS(AND) Repasa toda la serie nuevamente. LIKE,Cuando ambos fallan, ve palabra por palabra LIKE
        val hits = store.searchFullTextScoped("Desarrollador Ziqi", "main", "default", limit = 10)

        assertEquals("Un montón de errores,Después de dividir las palabras, debes presionar una de ellas.: ${hits.map { it.fact }}", 2, hits.size)
    }

    @Test
    fun `single word query returns the same hits as before`() = runTest {
        val target = addFact("A Ziqi le gusta hacer senderismo en las montañas los fines de semana.")
        addFact("Los desarrolladores tratan este proyecto como un pasatiempo a largo plazo.")

        val exact = store.searchFullTextScoped("Ziqi", "main", "default", limit = 10)
        val twoStage = store.searchFullTextScopedTwoStage("Ziqi", "main", "default", limit = 10)

        assertEquals(listOf(target), exact.map { it.id })
        assertEquals(exact.map { it.id }, twoStage.map { it.id })
    }

    @Test
    fun `or semantics recalls rows that and semantics drops`() = runTest {
        addFact("A Ziqi le gusta hacer senderismo en las montañas los fines de semana.")
        addFact("Los desarrolladores tratan este proyecto como un pasatiempo a largo plazo.")

        val andHits = dao.searchFtsBySpace(FactFtsManager.toMatchQuery("desarrolladores Ziqi"), 10, "main", "default")
        val orHits = dao.searchFtsBySpace(
            FactFtsManager.toMatchQuery("desarrolladores Ziqi", orSemantics = true),
            10,
            "main",
            "default",
        )

        assertEquals("Y no debe tener ningún resultado cuando no hay dos palabras en la misma línea.", 0, andHits.size)
        assertEquals("OR Ambas líneas deben ser pescadas.", 2, orHits.size)
    }

    @Test
    fun `exact hits stop the search before the broad stage`() = runTest {
        val allInOne = addFact("Ziqi, asistente de desarrollo, los tres comieron juntos.")
        val onlyOne = addFact("La asistente seguía respondiendo mensajes a las tres de la madrugada.", importance = 2)

        val limited = store.searchFullTextScopedTwoStage("Asistente de desarrollo de Ziqi", "main", "default", limit = 1)

        // Rondas de relajación si se ejecutan.,importance=2 El que ocupará el primer lugar,El resultado no es allInOne
        assertEquals("Se han recopilado aciertos precisos. limit,No deberías volver a tomar la ronda de relajación.", listOf(allInOne), limited.map { it.id })

        val widened = store.searchFullTextScopedTwoStage("Asistente de desarrollo de Ziqi", "main", "default", limit = 3)
        assertEquals("Golpe preciso primero,Los resultados de la relajación se agregarán más adelante.", allInOne, widened.first().id)
        assertTrue("ambos deben ser devueltos: ${widened.map { it.id }}", widened.map { it.id }.contains(onlyOne))
    }

    @Test
    fun `match query drops the trailing single character token`() {
        val query = FactFtsManager.toMatchQuery("Asistente de desarrollo de Ziqi")
        val tokens = query.split(' ').map { it.trim('"') }.filter { it.isNotEmpty() }

        assertTrue("No debe haber palabras individuales en la cadena de consulta. token: $query", tokens.all { it.length >= 2 })
        assertTrue("El valor predeterminado de varias palabras sigue siendo Y conexión: $query", !query.contains(" OR "))

        val broad = FactFtsManager.toMatchQuery("Asistente de desarrollo de Ziqi", orSemantics = true)
        assertTrue("La semántica relajada debería generar combinaciones OR: $broad", broad.contains(" OR "))
    }

    @Test
    fun `index ngram still carries the trailing single character`() {
        // El comportamiento del lado del índice no ha cambiado:La última palabra todavía está ahí.,De lo contrario, no se encontrarán los caracteres al final de los fragmentos con longitudes impares.
        assertEquals("你好 好世 世界 界", FactFtsManager.toNgram("你好世界"))
        assertEquals("你好 好世 世界", FactFtsManager.toNgram("你好世界", includeTrailingTailChar = false))
    }

    @Test
    fun `query terms split on whitespace and punctuation`() {
        assertEquals(listOf("Ziqi", "desarrollador", "asistente"), FactFtsManager.toQueryTerms("Ziqi, desarrollador;asistente"))
        assertEquals(listOf("GPT", "4"), FactFtsManager.toQueryTerms("GPT-4"))
    }
}
