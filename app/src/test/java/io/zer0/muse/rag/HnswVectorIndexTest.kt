package io.zer0.muse.rag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v1.55: HNSW Pruebas unitarias de indexación de vectores.
 *
 * Portada:
 *  - add + search corrección(Tasa de recuperación superior en comparación con los resultados del recorrido de fuerza bruta)
 *  - save + load consistencia(serialización/Los resultados de la búsqueda son consistentes después de la deserialización.)
 *  - remove(Después de la eliminación, la identificación ya no aparecerá en los resultados de búsqueda.,size reducción sincrónica)
 *  - condiciones de contorno(índice vacío,k=0,k Excede el tamaño, índice de un solo elemento)
 */
class HnswVectorIndexTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "hnsw_test_${System.nanoTime()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.listFiles()?.forEach { it.delete() }
        tempDir.delete()
    }

    /** Construya un índice con alta efSearch(Garantizar un alto nivel de recuperación en escenarios de prueba,Evite errores de aproximación que interfieran con las afirmaciones.). */
    private fun newTestIndex(m: Int = 16, efConstruction: Int = 200, efSearch: Int = 100): HnswVectorIndex = HnswVectorIndex(
        M = m,
        efConstruction = efConstruction,
        efSearch = efSearch,
        seed = 42L,
    )

    /** Generar n vectores aleatorios de dimensiones tenues(semilla fija,reproducible). */
    private fun genVectors(n: Int, dim: Int, seed: Long = 42L): List<Pair<String, FloatArray>> {
        val rng = Random(seed)
        return (0 until n).map { i ->
            val vec = FloatArray(dim) { rng.nextFloat() * 2f - 1f } // [-1, 1]
            "vec-$i" to vec
        }
    }

    /** recorrido violento top-k(Instrucciones de implementación,Se utiliza para verificar los resultados de HNSW.). */
    private fun bruteForceSearch(query: FloatArray, vectors: List<Pair<String, FloatArray>>, k: Int): List<Pair<String, Float>> {
        return vectors
            .map { (id, vec) -> id to cosineSim(query, vec) }
            .sortedByDescending { it.second }
            .take(k)
    }

    /** similitud coseno(Inversa de la distancia interna HNSW:score = 1 - distance). */
    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until n) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }

    private data class PersistedNode(
        val id: String,
        val vector: FloatArray,
        val level: Int = 0,
        val connections: List<List<Int>> = listOf(emptyList()),
    )

    /** Escriba un archivo HNSW legal mínimo por corrupción/Prueba de regresión de cargas multidimensionales. */
    private fun writePersistedIndex(file: File, nodes: List<PersistedNode>, fingerprint: String = "") {
        val maxLayer = nodes.maxOfOrNull { it.level } ?: -1
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.write("HNSW01".toByteArray(Charsets.US_ASCII))
            out.writeInt(2)
            out.writeInt(HnswVectorIndex.DEFAULT_M)
            out.writeInt(HnswVectorIndex.DEFAULT_EF_CONSTRUCTION)
            out.writeInt(HnswVectorIndex.DEFAULT_EF_SEARCH)
            val fingerprintBytes = fingerprint.toByteArray(Charsets.UTF_8)
            out.writeInt(fingerprintBytes.size)
            out.write(fingerprintBytes)
            out.writeInt(maxLayer)
            out.writeInt(if (nodes.isEmpty()) -1 else 0)
            out.writeInt(nodes.size)
            nodes.forEach { node ->
                val idBytes = node.id.toByteArray(Charsets.UTF_8)
                out.writeInt(idBytes.size)
                out.write(idBytes)
                out.writeInt(node.level)
                out.writeInt(node.vector.size)
                node.vector.forEach(out::writeFloat)
                out.writeInt(node.level + 1)
                for (layer in 0..node.level) {
                    val connections = node.connections.getOrElse(layer) { emptyList() }
                    out.writeInt(connections.size)
                    connections.forEach(out::writeInt)
                }
            }
        }
    }

    // ── add + search corrección ──

    @Test
    fun `La búsqueda de índice vacío devuelve una lista vacía`() {
        val index = newTestIndex()
        assertTrue(index.size == 0)
        val results = index.search(FloatArray(64), k = 5)
        assertTrue("Una búsqueda de índice vacía debería devolver una lista vacía", results.isEmpty())
    }

    @Test
    fun `k Menor o igual a 0 devuelve una lista vacía`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 10, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertTrue(index.search(vectors[0].second, k = 0).isEmpty())
        assertTrue(index.search(vectors[0].second, k = -1).isEmpty())
    }

    @Test
    fun `k Devuelve todos los resultados disponibles cuando se excede el tamaño`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 5, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }

        val results = index.search(vectors[0].second, k = 100)
        assertTrue("k > size Los resultados de tamaño deben devolverse cuando", results.size <= 5)
        // No obligado a igualar 5(HNSW Puede ser un poco menor porque el gráfico no atraviesa todos los nodos.),Pero al menos se debe devolver el vector de consulta en sí.
        assertTrue("Se debe devolver al menos 1 resultado.", results.isNotEmpty())
    }

    @Test
    fun `La búsqueda de índice de un solo elemento devuelve el elemento`() {
        val index = newTestIndex()
        val vec = FloatArray(16) { it.toFloat() }
        index.add("only", vec)

        val results = index.search(vec, k = 1)
        assertEquals(1, results.size)
        assertEquals("only", results[0].id)
        // La autosimilitud debe ser 1.0(distancia coseno 0)
        assertEquals(1.0f, results[0].score, 1e-4f)
        assertEquals(0.0f, results[0].distance, 1e-4f)
    }

    @Test
    fun `add Después de que el tamaño aumenta sincrónicamente`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 50, dim = 32)
        for ((i, pair) in vectors.withIndex()) {
            index.add(pair.first, pair.second)
            assertEquals(i + 1, index.size)
        }
    }

    @Test
    fun `add Sobrescribe el vector antiguo con la misma identificación y mantén el tamaño sin cambios.`() {
        val index = newTestIndex()
        val vec1 = FloatArray(16) { 1f }
        val vec2 = FloatArray(16) { 2f }
        index.add("dup", vec1)
        assertEquals(1, index.size)
        index.add("dup", vec2)
        assertEquals("el tamaño no debe aumentar después de sobrescribir con la misma identificación", 1, index.size)

        // Después de verificar la cobertura, use vec2 para consultar los resultados. score=1(vec1 ha sido reemplazado)
        val results = index.search(vec2, k = 1)
        assertEquals(1, results.size)
        assertEquals("dup", results[0].id)
        assertEquals(1.0f, results[0].score, 1e-4f)
    }

    @Test
    fun `La adición de vectores extradimensionales se rechaza sin destruir los índices existentes`() {
        val index = newTestIndex()
        val original = FloatArray(8) { it.toFloat() + 1f }
        index.add("original", original)

        assertThrows(IllegalArgumentException::class.java) {
            index.add("other", FloatArray(9) { 1f })
        }

        assertEquals(1, index.size)
        assertEquals("original", index.search(original, k = 1).single().id)
        assertTrue("No se puede acceder a una consulta de dimensión diferente HNSW", index.search(FloatArray(9) { 1f }, k = 1).isEmpty())
    }

    @Test
    fun `El reemplazo de diferentes dimensiones con el mismo ID se rechaza cuando existen otros nodos`() {
        val index = newTestIndex()
        val first = FloatArray(8) { 1f }
        val second = FloatArray(8) { 2f }
        index.add("first", first)
        index.add("second", second)

        assertThrows(IllegalArgumentException::class.java) {
            index.add("first", FloatArray(9) { 3f })
        }

        assertEquals(2, index.size)
        assertTrue(index.search(first, k = 2).any { it.id == "first" })
    }

    @Test
    fun `search top-1 Consistente con el recorrido de fuerza bruta: 100 vectores a pequeña escala`() {
        val n = 100
        val dim = 64
        val vectors = genVectors(n = n, dim = dim)
        val index = newTestIndex(efSearch = 200) // Un alto nivel de efSearch garantiza un alto nivel de recuperación
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertEquals(n, index.size)

        // Utilice los primeros 20 vectores como consulta(debe ser suyo top-1,similitud coseno = 1.0)
        var matched = 0
        val total = 20
        for (i in 0 until total) {
            val query = vectors[i].second
            val expected = bruteForceSearch(query, vectors, k = 1).first()
            val actual = index.search(query, k = 1).firstOrNull() ?: continue
            if (actual.id == expected.first) matched++
        }
        // Al menos el 90% del retiro(HNSW es un algoritmo aproximado,Permitir una pequeña probabilidad miss)
        assertTrue("top-1 recordar ${matched.toDouble() / total} debería >= 0.9", matched.toDouble() / total >= 0.9)
    }

    @Test
    fun `search top-1 Consistente con el recorrido de fuerza bruta: 500 vectores de escala media`() {
        val n = 500
        val dim = 64
        val vectors = genVectors(n = n, dim = dim)
        val index = newTestIndex(efSearch = 200)
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertEquals(n, index.size)

        // Recuperación de prueba con 50 vectores de consulta aleatorios
        val rng = Random(123L)
        var matched = 0
        val total = 50
        repeat(total) {
            val query = FloatArray(dim) { rng.nextFloat() * 2f - 1f }
            val expected = bruteForceSearch(query, vectors, k = 1).firstOrNull() ?: return@repeat
            val actual = index.search(query, k = 1).firstOrNull() ?: return@repeat
            if (actual.id == expected.first) matched++
        }
        // Retirada a mediana escala de al menos el 85%
        assertTrue("500 Recuperación del top 1 del vector ${matched.toDouble() / total} debería >= 0.85", matched.toDouble() / total >= 0.85)
    }

    @Test
    fun `search Devolver resultados en orden descendente de similitud`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 30, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }

        val query = vectors[0].second
        val results = index.search(query, k = 10)
        assertTrue(results.size >= 2)

        for (i in 1 until results.size) {
            assertTrue(
                "Los resultados deben estar en orden descendente por puntuación.:results[${i - 1}].score=${results[i - 1].score} >= results[$i].score=${results[i].score}",
                results[i - 1].score >= results[i].score,
            )
        }
    }

    @Test
    fun `score Complementario a la distancia 1`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 10, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }

        val results = index.search(vectors[0].second, k = 5)
        for (r in results) {
            assertEquals("score + distance debería = 1", 1.0f, r.score + r.distance, 1e-4f)
        }
    }

    // ── save + load consistencia ──

    @Test
    fun `save Los resultados de la búsqueda después de la carga son consistentes.`() {
        val vectors = genVectors(n = 80, dim = 32)
        val index1 = newTestIndex(efSearch = 200)
        vectors.forEach { (id, vec) -> index1.add(id, vec) }
        assertEquals(80, index1.size)

        // guardar
        val file = File(tempDir, "save_load_test.bin")
        index1.save(file)
        assertTrue("El archivo de índice debería haber sido creado.", file.exists())
        assertTrue("El archivo de índice no debe estar vacío", file.length() > 0)

        // Cargar en un nuevo índice
        val index2 = newTestIndex(efSearch = 200)
        assertEquals(0, index2.size)
        index2.load(file)
        assertEquals("load El último tamaño debe ser consistente con el índice original.", index1.size, index2.size)

        // Comparar resultados de búsqueda(Utilice 10 vectores de consulta)
        val rng = Random(456L)
        repeat(10) {
            val query = FloatArray(32) { rng.nextFloat() * 2f - 1f }
            val r1 = index1.search(query, k = 5)
            val r2 = index2.search(query, k = 5)
            assertEquals("load Los números de resultados deben ser consistentes después", r1.size, r2.size)
            for ((a, b) in r1.zip(r2)) {
                assertEquals("load La última identificación debe ser consistente.", a.id, b.id)
                assertEquals("load Después de la puntuación debe ser consistente.", a.score, b.score, 1e-4f)
                assertEquals("load La distancia debe ser constante después", a.distance, b.distance, 1e-4f)
            }
        }
    }

    @Test
    fun `save Sólo se conservará el índice más reciente después de sobrescribir los archivos existentes.`() {
        val file = File(tempDir, "replace.bin")
        val first = newTestIndex().apply { add("old", FloatArray(8) { 1f }) }
        first.save(file)

        val second = newTestIndex().apply { add("new", FloatArray(8) { 2f }) }
        second.save(file)

        val loaded = newTestIndex()
        loaded.load(file)
        assertEquals(1, loaded.size)
        assertEquals("new", loaded.search(FloatArray(8) { 2f }, 1).single().id)
        assertTrue(loaded.search(FloatArray(8) { 1f }, 1).none { it.id == "old" })
    }

    @Test
    fun `embedding Negarse a cargar cuando las huellas dactilares no coincidan y conservar el índice actual`() {
        val file = File(tempDir, "fingerprint.bin")
        newTestIndex().apply {
            add("saved", FloatArray(4) { 1f })
            save(file, "provider\u0000model-a\u00004")
        }
        val target = newTestIndex().apply { add("kept", FloatArray(4) { 2f }) }

        assertThrows(IllegalArgumentException::class.java) {
            target.load(file, "provider\u0000model-b\u00004")
        }

        assertEquals(1, target.size)
        assertEquals("kept", target.search(FloatArray(4) { 2f }, 1).single().id)
    }

    @Test
    fun `La carga de índices de dimensiones mixtas falla y no sobrescribe los índices existentes`() {
        val file = File(tempDir, "mixed-dimension.bin")
        writePersistedIndex(
            file,
            listOf(
                PersistedNode("a", FloatArray(4) { 1f }),
                PersistedNode("b", FloatArray(5) { 2f }),
            ),
        )
        val target = newTestIndex().apply { add("kept", FloatArray(4) { 3f }) }

        assertThrows(IllegalArgumentException::class.java) { target.load(file) }

        assertEquals(1, target.size)
        assertEquals("kept", target.search(FloatArray(4) { 3f }, 1).single().id)
    }

    @Test
    fun `La carga del índice truncado falla y no sobrescribe los índices existentes`() {
        val validFile = File(tempDir, "valid.bin")
        newTestIndex().apply {
            add("a", FloatArray(4) { 1f })
            save(validFile)
        }
        val truncatedFile = File(tempDir, "truncated.bin")
        truncatedFile.writeBytes(validFile.readBytes().dropLast(1).toByteArray())
        val target = newTestIndex().apply { add("kept", FloatArray(4) { 3f }) }

        assertThrows(Exception::class.java) { target.load(truncatedFile) }

        assertEquals(1, target.size)
        assertEquals("kept", target.search(FloatArray(4) { 3f }, 1).single().id)
    }

    @Test
    fun `Después de eliminar todos los nodos, guardar y cargar estarán vacíos y se podrán crear nuevas dimensiones.`() {
        val file = File(tempDir, "empty-rebuild.bin")
        val index = newTestIndex().apply {
            add("old", FloatArray(4) { 1f })
            remove("old")
            save(file)
        }
        val loaded = newTestIndex()
        loaded.load(file)
        assertEquals(0, loaded.size)

        loaded.add("new", FloatArray(7) { 2f })
        assertEquals("new", loaded.search(FloatArray(7) { 2f }, 1).single().id)
    }

    @Test
    fun `load Los archivos que no existen no lanzan una excepción y el índice permanece vacío`() {
        val index = newTestIndex()
        val nonexistent = File(tempDir, "does_not_exist.bin")
        // No se debe lanzar ninguna excepción
        index.load(nonexistent)
        assertEquals(0, index.size)
    }

    @Test
    fun `load magic Los archivos no coincidentes se rechazan y los índices existentes no se sobrescriben`() {
        val index = newTestIndex()
        index.add("kept", FloatArray(4) { 1f })
        val badFile = File(tempDir, "bad_magic.bin")
        // Escritura incorrecta magic header
        badFile.writeBytes("BADMAG".toByteArray(Charsets.US_ASCII) + ByteArray(20))

        assertThrows(IllegalArgumentException::class.java) { index.load(badFile) }

        assertEquals("Los encabezados no válidos no deben sobrescribir los índices existentes", 1, index.size)
        assertEquals("kept", index.search(FloatArray(4) { 1f }, 1).single().id)
    }

    @Test
    fun `save Contiene nodos eliminados: tamaño consistente después de la carga`() {
        val vectors = genVectors(n = 50, dim = 32)
        val index = newTestIndex()
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertEquals(50, index.size)

        // eliminar 10
        for (i in 0 until 10) {
            index.remove("vec-$i")
        }
        assertEquals(40, index.size)

        // guardar → Cargar
        val file = File(tempDir, "with_deletes.bin")
        index.save(file)
        val loaded = newTestIndex()
        loaded.load(file)
        // load Los nodos eliminados deben eliminarse después,size Debe ser igual al número de nodos supervivientes.
        assertEquals("save/load Los nodos eliminados deben comprimirse y eliminarse", 40, loaded.size)

        // Los identificadores eliminados no deberían aparecer en los resultados de búsqueda.
        for (i in 0 until 10) {
            val results = loaded.search(vectors[i].second, k = 5)
            assertFalse("eliminado vec-$i no debería aparecer", results.any { it.id == "vec-$i" })
        }
    }

    // ── remove ──

    @Test
    fun `remove El tamaño se reduce después de la identificación existente.`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 20, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertEquals(20, index.size)

        index.remove("vec-5")
        assertEquals(19, index.size)
        index.remove("vec-10")
        assertEquals(18, index.size)
    }

    @Test
    fun `remove Si la identificación no existe, no se generará ninguna excepción y el tamaño permanecerá sin cambios.`() {
        val index = newTestIndex()
        val vectors = genVectors(n = 5, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }
        assertEquals(5, index.size)

        // No lanzar excepción,size sin cambios
        index.remove("nonexistent")
        assertEquals(5, index.size)
    }

    @Test
    fun `remove Después de eso, la identificación ya no aparece en los resultados de búsqueda.`() {
        val index = newTestIndex(efSearch = 200)
        val vectors = genVectors(n = 30, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }

        // Primero verifique que vec-0 sea suyo top-1
        val beforeRemove = index.search(vectors[0].second, k = 1)
        assertTrue("Antes de la eliminación, vec-0 debe estar en top-1", beforeRemove.any { it.id == "vec-0" })

        // Eliminar vec-0
        index.remove("vec-0")
        assertEquals(29, index.size)

        // Después de eliminar vec-0 ya no debería aparecer en los resultados.
        val afterRemove = index.search(vectors[0].second, k = 10)
        assertFalse("vec-0 no debería aparecer después de la eliminación", afterRemove.any { it.id == "vec-0" })
        assertTrue("Los resultados aún deben devolverse después de la eliminación.(otros vectores)", afterRemove.isNotEmpty())
    }

    @Test
    fun `remove Todavía funciona bien después del punto de entrada. search`() {
        val index = newTestIndex(efSearch = 200)
        val vectors = genVectors(n = 50, dim = 32)
        vectors.forEach { (id, vec) -> index.add(id, vec) }

        // Eliminar repetidamente varios nodos(Puede llegar al punto de entrada),Verificar que la búsqueda siga funcionando
        for (i in 0 until 20) {
            index.remove("vec-$i")
        }
        assertEquals(30, index.size)

        // search No se debe lanzar ninguna excepción,debería devolver resultados
        val rng = Random(789L)
        repeat(5) {
            val query = FloatArray(32) { rng.nextFloat() * 2f - 1f }
            val results = index.search(query, k = 5)
            assertNotNull(results)
            assertTrue(results.size <= 5)
        }
    }

    // ── VectorIndexFactory ──

    @Test
    fun `Factory createIndex BRUTE_FORCE Regresar null`() {
        val index = VectorIndexFactory.createIndex(VectorIndexFactory.IndexStrategy.BRUTE_FORCE)
        assertEquals(null, index)
    }

    @Test
    fun `Factory createIndex HNSW Devolver instancia de HnswVectorIndex`() {
        val index = VectorIndexFactory.createIndex(VectorIndexFactory.IndexStrategy.HNSW)
        assertNotNull(index)
        assertTrue("Se esperaba una instancia de HnswVectorIndex", index is HnswVectorIndex)
    }

    @Test
    fun `Factory autoStrategy Umbral correcto`() {
        assertEquals(
            VectorIndexFactory.IndexStrategy.BRUTE_FORCE,
            VectorIndexFactory.autoStrategy(0),
        )
        assertEquals(
            VectorIndexFactory.IndexStrategy.BRUTE_FORCE,
            VectorIndexFactory.autoStrategy(4999),
        )
        assertEquals(
            VectorIndexFactory.IndexStrategy.HNSW,
            VectorIndexFactory.autoStrategy(5000),
        )
        assertEquals(
            VectorIndexFactory.IndexStrategy.HNSW,
            VectorIndexFactory.autoStrategy(100_000),
        )
    }

    @Test
    fun `Factory createAutoIndex Devolución de instancias HNSW a escala`() {
        val index = VectorIndexFactory.createAutoIndex(50_000)
        assertNotNull(index)
        assertTrue(index is HnswVectorIndex)
    }

    @Test
    fun `Factory createAutoIndex retorno a pequeña escala null`() {
        val index = VectorIndexFactory.createAutoIndex(100)
        assertEquals(null, index)
    }
}
