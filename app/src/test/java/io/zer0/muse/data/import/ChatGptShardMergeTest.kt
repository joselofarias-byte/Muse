@file:Suppress("PackageName")

package io.zer0.muse.data.`import`

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v2.4.6: ChatGPT Nueva versión de la prueba de combinación de fragmentos de exportación jerárquica.
 *
 * La nueva versión de exportación divide la sesión en conversations-000.json / conversations-001.json ...
 * (El nivel superior es cada matriz JSON). El reconocimiento anterior solo reconocía conversaciones.json, lo que resultaba en 0 elementos importados en todo el paquete.
 * Esta prueba protege la lógica de fusión de fragmentos: el resultado de la fusión debe ser una única matriz legal de nivel superior y los elementos deben estar en completo orden.
 */
class ChatGptShardMergeTest {

    private fun shard(name: String, content: String): File {
        val f = File.createTempFile("shard_", "_$name")
        f.writeText(content)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `merges multiple shards into one top level array`() {
        val a = shard("a", """[{"id":"c1","title":"one"},{"id":"c2","title":"two"}]""")
        val b = shard("b", """[{"id":"c3","title":"three"}]""")

        val merged = ThirdPartyImporter.mergeConversationShards(listOf(a, b))

        assertEquals("""[{"id":"c1","title":"one"},{"id":"c2","title":"two"},{"id":"c3","title":"three"}]""", merged)
    }

    @Test
    fun `handles empty and whitespace shards`() {
        val a = shard("a", "[]")
        val b = shard("b", "   ")
        val c = shard("c", """[{"id":"x"}]""")

        val merged = ThirdPartyImporter.mergeConversationShards(listOf(a, b, c))

        assertEquals("""[{"id":"x"}]""", merged)
    }

    @Test
    fun `strips BOM and surrounding whitespace`() {
        val a = shard("a", "\uFEFF[{\"id\":\"a\"}]\n")
        val b = shard("b", "\n [{\"id\":\"b\"}] ")

        val merged = ThirdPartyImporter.mergeConversationShards(listOf(a, b))

        assertEquals("""[{"id":"a"},{"id":"b"}]""", merged)
        // Debe ser una única matriz legal de alto nivel.
        assertTrue(merged.startsWith("["))
        assertTrue(merged.endsWith("]"))
    }
}
