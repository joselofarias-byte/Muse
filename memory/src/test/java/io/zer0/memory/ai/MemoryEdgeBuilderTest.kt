package io.zer0.memory.ai

import io.zer0.memory.fact.FactStore
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.2(comentarios de los usuarios): Pruebas de regresión del constructor de complementos de constelaciones de memoria.
 *
 * Utilice MemoryLinkDao de Memory Fake para verificar el borde suplementario de tres capas (clave de entidad/Misma sesión/Misma categoría) produce bordes,
 * Y no tiene dirección para eliminar duplicados y es idempotente (las ejecuciones repetidas no repiten las inserciones).
 */
class MemoryEdgeBuilderTest {

    /** Registre el DAO falso mínimo insertado (implemente solo los dos métodos utilizados para complementar los bordes). */
    private class FakeLinkDao : MemoryLinkDao {
        val inserted = mutableListOf<MemoryLinkEntity>()
        override suspend fun insert(link: MemoryLinkEntity): Long {
            inserted.add(link)
            return inserted.size.toLong()
        }

        override suspend fun insertBatch(links: List<MemoryLinkEntity>): List<Long> {
            inserted.addAll(links)
            return links.indices.map { it.toLong() }
        }

        override suspend fun listBySpaceAndScope(spaceId: String, scope: String): List<MemoryLinkEntity> =
            inserted.filter { it.spaceId == spaceId && it.scope == scope }

        override suspend fun listBySpace(spaceId: String): List<MemoryLinkEntity> = inserted.filter { it.spaceId == spaceId }
        override fun observeBySpaceAndScope(spaceId: String, scope: String): kotlinx.coroutines.flow.Flow<List<MemoryLinkEntity>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun listByFactId(factId: Long): List<MemoryLinkEntity> = emptyList()
        override suspend fun deleteByFactId(factId: Long): Int = 0
        override suspend fun deleteBySpaceId(spaceId: String): Int = 0
        override suspend fun migrateBySpaceId(sourceSpaceId: String, targetSpaceId: String): Int = 0
        override suspend fun countBySpaceAndScope(spaceId: String, scope: String): Int = 0
        override suspend fun deleteById(id: Long): Int = 0
        override suspend fun deleteAll(): Int = 0
        override suspend fun deleteOrphans(): Int = 0
    }

    @Suppress("LongParameterList")
    private fun fact(
        id: Long,
        text: String,
        entityKey: String? = null,
        sessionId: String? = null,
        category: String = "general",
        createdAt: String = "2026-10-16T10:00:00Z",
    ) = FactStore.Fact(
        id = id,
        fact = text,
        entityKey = entityKey,
        sessionId = sessionId,
        category = category,
        createdAt = createdAt,
    )

    @Test
    fun `same entity key produces edges`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(
            fact(1, "A Zhang San le gusta el café", entityKey = "Zhang San"),
            fact(2, "Zhang San vive en Beijing", entityKey = "Zhang San"),
        )
        val result = builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue("Las mismas claves de entidad deberían producir bordes", result.entityEdges >= 1)
        assertTrue(dao.inserted.isNotEmpty())
    }

    @Test
    fun `same session produces edges`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(
            fact(1, "Tuvimos una reunión hoy para discutir el presupuesto.", sessionId = "s1", category = "event"),
            fact(2, "El presupuesto se fija en cien mil", sessionId = "s1", category = "event"),
        )
        val result = builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue("La misma sesión debería generar aristas.", result.sessionEdges >= 1)
    }

    @Test
    fun `same category within window produces edges`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(
            fact(1, "compré un teclado", category = "shopping"),
            fact(2, "compré un ratón", category = "shopping"),
        )
        val result = builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue("Misma categoría + hora de cierre debería generar bordes", result.categoryEdges >= 1)
    }

    @Test
    fun `is idempotent`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(
            fact(1, "A Zhang San le gusta el café", entityKey = "Zhang San"),
            fact(2, "Zhang San vive en Beijing", entityKey = "Zhang San"),
        )
        builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        val afterFirst = dao.inserted.size
        // Segunda ejecución: los bordes existentes deben omitirse mediante la deduplicación
        builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue("No se deben agregar bordes duplicados", dao.inserted.size == afterFirst)
    }

    @Test
    fun `no edges when single fact`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(fact(1, "el único hecho"))
        val result = builder.buildEdges(newFacts = facts, existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue(result.total == 0)
    }

    @Test
    fun `different category far apart no edge`() = kotlinx.coroutines.runBlocking {
        val dao = FakeLinkDao()
        val builder = MemoryEdgeBuilder(dao)
        val facts = listOf(
            fact(1, "compré un teclado", category = "shopping", createdAt = "2026-10-10T10:00:00Z"),
            fact(2, "Reunión para discutir", category = "work", createdAt = "2026-10-16T10:00:00Z"),
        )
        val result = builder.buildEdges(newFacts = listOf(facts[0]), existingFacts = facts, scope = "main", spaceId = "default")
        assertTrue("Una clasificación diferente + tiempo no debería producir aristas", result.total == 0)
    }
}
