package io.zer0.muse.backup

import io.zer0.muse.data.card.CardEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.6.8: `card_entities` Protector de cableado de respaldo.
 *
 * Antecedentes: una vez acoplada la tarjeta, se separa del flujo de mensajes y solo queda un marcador de posición en el mensaje; si esta tabla no tiene una copia de seguridad, después de cambiar la máquina
 * Todas las tarjetas de muelle se pierden permanentemente y no se pueden reconstruir a partir del mensaje. Esta prueba señala tres cosas:
 *  1. El formato de línea de ida y vuelta no pierde campos, incluidos los espacios nulos y de acoplamiento;
 *  2. El orden de exportación es estable (las tarjetas acopladas se basan en las ranuras, las tarjetas desacopladas se basan en el tiempo de creación);
 *  3. Todas las conexiones están disponibles en el nivel del código fuente (exportar instantánea/restaurar borrado/restaurar escritura/mapeo NDJSON).
 *
 * La protección "No te pierdas una lista al hacer una lista escrita a mano" se eliminó en v2.6.9: se cambió la lectura y escritura de respaldo.
 * `CardEntityDao`, los nombres de las columnas están determinados por Room por `@ColumnInfo` Las entidades y campos generados no necesitan modificarse.
 * Al hacer una copia de seguridad de cualquier archivo de su lado, el riesgo de pérdida silenciosa de archivos desaparece desde la raíz. El resto "la duplicación del formato de línea tiene que mantenerse al día"
 * El riesgo de "campos de entidad" todavía existe, causado por `line format mirrors every entity field` Guardia.
 *
 * El artículo 3 utiliza el modelo de "contrato de código fuente", que es coherente con el almacén existente. BackupRestoreTodoRelationshipTest /
 * BackupNdJsonRestoreContractTest Mismo tipo:buildBackup / applyBackupInternal Todos
 * private Y se basa en tres conjuntos de bibliotecas Room reales, por lo que no se puede llamar directamente a la capa de unidades.
 */
class BackupCardEntitiesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun card(id: String, dockSlot: Int? = null, createdAt: Long = 1_000L) = CardEntity(
        id = id,
        legacyCardId = "c_$id",
        originSessionId = "s1",
        originMessageId = "m1",
        title = "Título $id",
        code = "<div>$id</div>",
        isSvg = false,
        dataJson = """{"v":1}""",
        dockSlot = dockSlot,
        dockedAt = dockSlot?.let { 2_000L },
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun source(): String = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()

    // ── 1. El formato de línea se alinea con los campos de entidad ──

    @Test
    fun `line format mirrors every entity field`() {
        val entityProps = instanceFields(CardEntity::class.java)
        val recordProps = instanceFields(CardEntityRecord::class.java)

        assertEquals(
            "El formato de línea no corresponde a los campos de la entidad,Los nuevos campos no están respaldados",
            entityProps,
            recordProps,
        )
    }

    /**
     * data class El nombre del campo de instancia. Excluir cosas generadas en tiempo de compilación:kotlinx.serialization de
     * estático `Companion` campos, y `$` El campo sintético de clases internas al principio.
     */
    private fun instanceFields(type: Class<*>): Set<String> = type.declaredFields
        .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .map { it.name }
        .filterNot { it.contains('$') }
        .toSet()

    // ── 2. round-trip ──

    @Test
    fun `docked and inline cards round trip without data loss`() {
        val docked = card("docked-a", dockSlot = 0, createdAt = 1_000L)
        val inline = card("inline-b", dockSlot = null, createdAt = 2_000L)

        val backup = BackupService.Backup(
            exportedAt = 1L,
            sessions = emptyList(),
            messages = emptyList(),
            cardEntities = listOf(docked.toRecord(), inline.toRecord()),
        )
        val restored = json.decodeFromString(
            BackupService.Backup.serializer(),
            json.encodeToString(BackupService.Backup.serializer(), backup),
        )

        assertEquals(2, restored.cardEntities.size)
        assertEquals("El estado acoplado debe devolverse tal como está.", docked, restored.cardEntities[0].toEntity())
        assertEquals("También se deben conservar las tarjetas de desacoplamiento.", inline, restored.cardEntities[1].toEntity())
        assertNull("dockSlot para tarjetas desacopladas debe ser nulo en lugar de 0", restored.cardEntities[1].dockSlot)
    }

    @Test
    fun `null optional fields survive round trip`() {
        val bare = CardEntity(id = "bare", createdAt = 0L, updatedAt = 0L)
        val restored = json.decodeFromString(
            BackupService.Backup.serializer(),
            json.encodeToString(
                BackupService.Backup.serializer(),
                BackupService.Backup(
                    exportedAt = 1L,
                    sessions = emptyList(),
                    messages = emptyList(),
                    cardEntities = listOf(bare.toRecord()),
                ),
            ),
        ).cardEntities.single()

        assertEquals(bare, restored.toEntity())
        assertNull(restored.legacyCardId)
        assertNull(restored.originSessionId)
        assertNull(restored.originMessageId)
        assertNull(restored.dataJson)
        assertNull(restored.dockSlot)
        assertNull(restored.dockedAt)
    }

    @Test
    fun `old backup without cardEntities decodes to empty list`() {
        val oldStyle = """{"version":4,"exportedAt":1,"sessions":[],"messages":[]}"""
        val restored = json.decodeFromString(BackupService.Backup.serializer(), oldStyle)

        assertEquals(emptyList<CardEntityRecord>(), restored.cardEntities)
    }

    // ── 3. orden ──

    @Test
    fun `dock order puts docked cards first by slot then inline by createdAt`() {
        val records = listOf(
            card("inline-late", dockSlot = null, createdAt = 9_000L).toRecord(),
            card("slot-2", dockSlot = 2, createdAt = 1_000L).toRecord(),
            card("slot-0", dockSlot = 0, createdAt = 5_000L).toRecord(),
            card("inline-early", dockSlot = null, createdAt = 100L).toRecord(),
            card("slot-1", dockSlot = 1, createdAt = 3_000L).toRecord(),
        )

        assertEquals(
            "Las tarjetas acopladas se enumeran primero en orden ascendente de espacios, y las tarjetas desacopladas se enumeran al final en orden ascendente de tiempo de creación.",
            listOf("slot-0", "slot-1", "slot-2", "inline-early", "inline-late"),
            records.inDockOrder().map { it.id },
        )
    }

    @Test
    fun `dock order is deterministic for identical timestamps`() {
        val records = listOf(
            card("b", dockSlot = null, createdAt = 1_000L).toRecord(),
            card("a", dockSlot = null, createdAt = 1_000L).toRecord(),
        )

        assertEquals(
            "Las tarjetas con la misma marca de tiempo se ordenan por identificación y los dos resultados de exportación deben ser consistentes.",
            listOf("a", "b"),
            records.inDockOrder().map { it.id },
        )
        assertEquals(records.inDockOrder(), records.reversed().inDockOrder())
    }

    // ── 4. Contrato de código fuente para cableado en todas partes ──

    @Test
    fun `buildBackup snapshots card entities`() {
        val src = source()
        assertTrue(
            "buildBackup La instantánea db.withTransaction debe leer card_entities" +
                "(Es la fuente del punto de recuperación. Si se pierde la lectura, la reversión borrará todas las tarjetas.)",
            src.contains("cardEntities = db.cardEntityDao().getAll()"),
        )
        assertTrue(
            "buildBackup CardEntities debe pasarse Backup(...) estructura",
            Regex("cardEntities\\s*=\\s*cardEntities").containsMatchIn(src),
        )
    }

    @Test
    fun `restore clears and writes card entities`() {
        val src = source()
        assertTrue(
            "Debe borrarse antes de restaurar card_entities",
            src.contains("db.cardEntityDao().deleteAll()"),
        )
        assertTrue("La recuperación debe volver a escribir card_entities", src.contains("replaceCardEntities(") || src.contains("appendCardEntities("))
        assertTrue(
            "Después de la recuperación, card_entities debe conciliarse con el número de filas",
            src.contains("\"cardEntities\" -> db.cardEntityDao().count()"),
        )

        val applyBackup = src.substring(
            src.indexOf("private suspend fun applyBackupInternal"),
            src.indexOf("private suspend fun pruneRestoredMessageImages"),
        )
        assertTrue("También es necesario escribir en la tarjeta una única ruta de recuperación JSON.", applyBackup.contains("replaceCardEntities"))
        assertTrue("La ruta de recuperación JSON única también necesita verificar la ranura/reconciliación", applyBackup.contains("verifyCardEntitiesRestored"))
    }

    @Test
    fun `ndjson exports and restores card entity records`() {
        val src = source()
        assertTrue(
            "NDJSON La exportación debe escribir la línea cardEntity",
            src.contains("writeTypedLines(writer, \"cardEntity\""),
        )
        assertTrue(
            "NDJSON meta El recuento de cardEntities debe declararse",
            src.contains("put(\"cardEntities\", cardEntities.size)"),
        )
        assertTrue(
            "type→meta El mapeo debe contener cardEntity(La información faltante hará que la verificación de relectura y la conciliación omitan toda la tabla.)",
            src.contains("\"cardEntity\" to \"cardEntities\""),
        )
        assertTrue(
            "NDJSON La distribución de recuperación debe tener una rama cardEntity",
            src.contains("\"cardEntity\" ->"),
        )
    }

    @Test
    fun `duplicate dock slots are rejected with a readable reason`() {
        val duplicated = listOf(card("a", dockSlot = 0), card("b", dockSlot = 0))
        val error = runCatching { requireNoDuplicateDockSlots(duplicated) }.exceptionOrNull()

        assertTrue("Debe arrojar error", error is IllegalStateException)
        assertTrue(
            "El mensaje de error debería indicar que la ranura está duplicada.,En lugar de SQLite UNIQUE constraint failed: ${error?.message}",
            error?.message?.contains("Ranuras de acoplamiento duplicadas") == true,
        )
        // Puede haber varias tarjetas desacopladas y no se cuentan como duplicadas.
        requireNoDuplicateDockSlots(listOf(card("a"), card("b")))
    }
}
