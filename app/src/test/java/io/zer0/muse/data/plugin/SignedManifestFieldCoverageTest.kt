package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.x(Proyecto de deuda técnica C): prueba de demonios incluidos en la lista blanca de carga útil firmada.
 *
 * Tres garantías clave:
 *  1. La lista blanca cubre todos los campos de PluginManifest (esta prueba recuerda el mantenimiento sincrónico cuando cambia el conjunto de campos)
 *  2. par de filtros de lista blanca"Todos los campos están en la lista blanca."La salida del manifiesto es consistente byte por byte con la serialización directa.
 *     (Compatibilidad de verificación del paquete de stock)
 *  3. Se eliminarán los campos nuevos fuera de la lista blanca (los campos nuevos no participarán en la firma de forma predeterminada)
 */
class SignedManifestFieldCoverageTest {

    /** PluginManifest Todos los nombres de campos de nivel superior (en el mismo orden que la declaración del serializador). */
    private val allManifestFields = setOf(
        "id", "name", "version", "description", "author", "minAppVersion",
        "entry", "kind", "trust", "hidden", "capabilities", "permissions",
        "activationEvents", "hooks", "enabled", "tools", "signature",
        "contributes", "uiPanel", "toolCards",
    )

    @Test
    fun `whitelist covers all current manifest fields`() {
        // usar un"Se pueden codificar todos los valores predeterminados"Serialización de manifiesto, consulte los campos de salida reales
        val manifest = PluginManifest(id = "t", name = "T")
        val encoded = AppJson.encodeToString(PluginManifest.serializer(), manifest)
        val keys = AppJson.parseToJsonElement(encoded).jsonObject.keys
        // AppJson.encodeDefaults=true: Aparecerán todos los campos que no sean NUNCA.
        keys.forEach { key ->
            assertTrue("manifest campo '$key' No en la lista blanca——Si es un campo nuevo, confirme si se requiere protección de firma.", key in PluginSecurityGate.SIGNED_TOP_LEVEL_FIELDS)
        }
        // Inversa: no debe haber campos en el manifiesto que ya no existan en la lista blanca (renombrados/Recordatorio para limpiar al eliminar)
        PluginSecurityGate.SIGNED_TOP_LEVEL_FIELDS.forEach { field ->
            assertTrue("campos de la lista blanca '$field' Ya no existe en PluginManifest, límpielo simultáneamente.", field in allManifestFields)
        }
    }

    @Test
    fun `whitelist filter is byte-identical for fully-whitelisted manifest`() {
        val manifest = PluginManifest(
            id = "t",
            name = "T",
            capabilities = listOf("network"),
            activationEvents = listOf("onStartup"),
        )
        val direct = AppJson.encodeToString(PluginManifest.serializer(), manifest)
        val viaWhitelist = PluginSecurityGate.signedManifestJson(manifest)
        // JsonObject.toString El formato de salida del objeto de Json.encodeToString es consistente (tanto compacto como sin espacios)
        // Aquí hacemos una doble afirmación de equivalencia semántica + comparación de bytes: si los bytes son inconsistentes, significa que el formato de codificación se ha desviado y es necesario verificarlo.
        assertEquals(
            AppJson.parseToJsonElement(direct).jsonObject,
            AppJson.parseToJsonElement(viaWhitelist).jsonObject,
        )
        assertEquals(direct, viaWhitelist)
    }

    @Test
    fun `fields outside whitelist are stripped from payload`() {
        val manifest = PluginManifest(id = "t", name = "T")
        val direct = AppJson.encodeToString(PluginManifest.serializer(), manifest)
        // Simule nuevos campos en el futuro: inserte un campo fuera de la lista blanca en JSON, que debe eliminarse después del filtrado
        val withNewField = Json.parseToJsonElement(direct).jsonObject
        val mutated = buildJsonObject {
            withNewField.forEach { (k, v) -> put(k, v) }
            put("futureField", "should-be-stripped")
        }
        val reserialized = mutated.toString()
        assertTrue(reserialized.contains("futureField"))
        val filtered = buildJsonObject {
            AppJson.parseToJsonElement(reserialized).jsonObject.forEach { (k, v) ->
                if (k in PluginSecurityGate.SIGNED_TOP_LEVEL_FIELDS) put(k, v)
            }
        }
        assertFalse(filtered.toString().contains("futureField"))
        // Semánticamente igual que el manifiesto original (después de eliminar los nuevos campos)
        assertEquals(AppJson.parseToJsonElement(direct).jsonObject, filtered)
    }
}
