package io.zer0.muse.web

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** settings.snapshot Prueba de comportamiento de la carga útil (incluidos los valores de subpágina) y verificación del tipo de configuración.actualización. */
class HostSettingsCommandsTest {

    /** Repositorio falso: registra la escritura y deja que las lecturas posteriores vean el nuevo valor, equivalente a“Escríbelo y léelo de nuevo.”. */
    private class FakeAccess(initial: HostSettingsValues = HostSettingsValues()) : HostSettingsAccess {
        private val overrides = mutableMapOf<String, String>()
        private val initialValues = initial
        val writes = mutableListOf<Pair<String, String>>()

        override suspend fun readValues(): HostSettingsValues =
            initialValues.copy(raw = initialValues.raw + overrides)

        override suspend fun write(key: String, value: String) {
            writes += key to value
            overrides[key] = value
        }
    }

    /** Análisis de redacción falsa: cada nombre de recurso se analiza en texto con prefijo de idioma para facilitar su afirmación.“el texto proviene del análisis, no del nombre del recurso”. */
    private val strings = HostSettingsStrings { name, language -> "text($language):$name" }

    private fun commands(access: HostSettingsAccess) = HostSettingsCommands(access, strings)

    private fun HostSettingsSnapshot.value(key: String): HostSettingValue =
        values.single { it.key == key }

    // ── values Portada ──────────────────────────────────────────────

    @Test
    fun `values cover every category with the right type`() = runTest {
        val snapshot = commands(FakeAccess()).snapshot()
        val byKey = snapshot.values.associateBy { it.key }

        // Apariencia
        assertEquals("enum", byKey.getValue("themeMode").type)
        assertEquals("enum", byKey.getValue("fontSizeScale").type)
        assertEquals("enum", byKey.getValue("liquidGlassMode").type)
        assertEquals("number", byKey.getValue("liquidGlassStrength").type)
        assertEquals("boolean", byKey.getValue("dynamicColor").type)
        assertEquals("boolean", byKey.getValue("highContrast").type)
        // charlar
        assertEquals("boolean", byKey.getValue(HostSettingKeys.CHAT_ENTER_TO_SEND).type)
        assertEquals("boolean", byKey.getValue("chatPreferences.showToolCallDetails").type)
        assertEquals("boolean", byKey.getValue("chatPreferences.showTimestamp").type)
        assertEquals("boolean", byKey.getValue(HostSettingKeys.CHAT_STREAM_RESPONSE).type)
        assertEquals("boolean", byKey.getValue("chatPreferences.forceReasoning").type)
        assertEquals("enum", byKey.getValue("chatPreferences.reasoningLevel").type)
        // Voz
        assertEquals("enum", byKey.getValue("mediaConfig.ttsEngine").type)
        assertEquals("number", byKey.getValue("mediaConfig.ttsSpeechRate").type)
        assertEquals("text", byKey.getValue("mediaConfig.ttsVoiceName").type)
        // memoria
        assertEquals("boolean", byKey.getValue(HostSettingKeys.MEMORY).type)
        assertEquals("boolean", byKey.getValue("notificationMemoryEnabled").type)
        // Universales
        assertEquals("enum", byKey.getValue("language").type)
        assertEquals("boolean", byKey.getValue("model.configured").type)
    }

    @Test
    fun `number items carry their range so the client can render a slider`() = runTest {
        val snapshot = commands(FakeAccess()).snapshot()

        val strength = snapshot.value("liquidGlassStrength")
        assertEquals(0.0, strength.min)
        assertEquals(100.0, strength.max)
        assertNotNull("Los elementos numéricos deben ser escalonados.,De lo contrario, el control deslizante no tendrá dónde aterrizar.", strength.step)

        val rate = snapshot.value("mediaConfig.ttsSpeechRate")
        assertEquals(0.5, rate.min)
        assertEquals(2.0, rate.max)
    }

    @Test
    fun `every enum option carries a localized label`() = runTest {
        val snapshot = commands(FakeAccess(HostSettingsValues(language = "ja"))).snapshot()

        val enums = snapshot.values.filter { it.type == "enum" }
        assertTrue("Al menos debe haber un tema./Idioma/motor/Piense en la solidez de varios elementos de enumeración.", enums.size >= 4)
        enums.forEach { item ->
            assertTrue("${item.key} Los candidatos deben ser traídos", item.options.isNotEmpty())
            item.options.forEach { option ->
                assertTrue("${item.key}/${option.value} el nombre para mostrar está vacío", option.label.isNotBlank())
                // El nombre para mostrar proviene de la resolución localizada del host,No el nombre del recurso en sí.
                assertTrue(
                    "${item.key}/${option.value} El nombre para mostrar no parece texto localizado: ${option.label}",
                    option.label.startsWith("text(ja):"),
                )
            }
        }
    }

    @Test
    fun `values mirror the current settings`() = runTest {
        val access = FakeAccess(
            HostSettingsValues(
                language = "en",
                raw = mapOf(
                    "themeMode" to "dark",
                    "liquidGlassStrength" to "55",
                    "chatPreferences.reasoningLevel" to "MEDIUM",
                    "mediaConfig.ttsEngine" to "openai",
                    "mediaConfig.ttsSpeechRate" to "1.25",
                    "model.configured" to "true",
                ),
            ),
        )

        val snapshot = commands(access).snapshot()

        assertEquals("dark", snapshot.value("themeMode").value)
        assertEquals("55", snapshot.value("liquidGlassStrength").value)
        assertEquals("MEDIUM", snapshot.value("chatPreferences.reasoningLevel").value)
        assertEquals("openai", snapshot.value("mediaConfig.ttsEngine").value)
        assertEquals("1.25", snapshot.value("mediaConfig.ttsSpeechRate").value)
        assertEquals("true", snapshot.value("model.configured").value)
    }

    @Test
    fun `read only items are advertised but never writable`() = runTest {
        val snapshot = commands(FakeAccess()).snapshot()

        val readOnly = snapshot.values.filterNot { it.writable }.map { it.key }
        assertEquals(listOf("mediaConfig.ttsVoiceName", "model.configured"), readOnly)
        assertTrue("No se permite incluir elementos de solo lectura en la lista blanca", readOnly.none { it in snapshot.writableKeys })
    }

    /** Antideriva: la lista blanca debe ser un subconjunto de los elementos grabables en los valores, y ambos se derivan de la misma definición. */
    @Test
    fun `writable keys and the values list cannot drift apart`() = runTest {
        val snapshot = commands(FakeAccess()).snapshot()

        assertEquals(HostSettingDefinitions.writableKeys, snapshot.writableKeys)
        assertEquals(
            "values Los elementos grabables deben ser exactamente los mismos que los de la lista blanca.",
            snapshot.values.filter { it.writable }.map { it.key },
            snapshot.writableKeys,
        )
        assertEquals("La lista blanca no es coherente con el número de valores.", snapshot.values.size, snapshot.values.map { it.key }.distinct().size)
    }

    // ── Escribe con éxito ────────────────────────────────────────────────

    @Test
    fun `boolean number and enum writes land and read back`() = runTest {
        val access = FakeAccess()

        assertEquals(
            HostSettingsUpdateResult.Applied(HostSettingKeys.PII_GUARD),
            commands(access).update(HostSettingKeys.PII_GUARD, "true"),
        )
        assertEquals(
            HostSettingsUpdateResult.Applied("liquidGlassStrength"),
            commands(access).update("liquidGlassStrength", "70"),
        )
        assertEquals(HostSettingsUpdateResult.Applied("themeMode"), commands(access).update("themeMode", "dark"))

        assertEquals(
            listOf(
                HostSettingKeys.PII_GUARD to "true",
                "liquidGlassStrength" to "70",
                "themeMode" to "dark",
            ),
            access.writes,
        )
        val snapshot = commands(access).snapshot()
        assertEquals("true", snapshot.value(HostSettingKeys.PII_GUARD).value)
        assertEquals("70", snapshot.value("liquidGlassStrength").value)
        assertEquals("dark", snapshot.value("themeMode").value)
    }

    @Test
    fun `boolean accepts case and surrounding spaces`() = runTest {
        val access = FakeAccess()

        assertEquals(HostSettingsUpdateResult.Applied(HostSettingKeys.MEMORY), commands(access).update(HostSettingKeys.MEMORY, " TRUE "))

        assertEquals(listOf(HostSettingKeys.MEMORY to "true"), access.writes)
    }

    // ── Denegar camino ────────────────────────────────────────────────

    @Test
    fun `unknown key is rejected`() = runTest {
        val access = FakeAccess()
        val result = commands(access).update("someMagicKey", "true")

        assertTrue(result is HostSettingsUpdateResult.Rejected)
        assertTrue((result as HostSettingsUpdateResult.Rejected).reason.contains("someMagicKey"))
        assertTrue("Las claves desconocidas no se deben escribir en el repositorio.", access.writes.isEmpty())
    }

    @Test
    fun `type mismatch is rejected`() = runTest {
        val access = FakeAccess()

        listOf(null, "", "yes", "1", "on").forEach { raw ->
            val rejected = commands(access).update(HostSettingKeys.PII_GUARD, raw)
            assertTrue("valor $raw debe ser rechazado mediante verificación de tipo", rejected is HostSettingsUpdateResult.Rejected)
        }
        assertTrue("Si el tipo es ilegal, no se debe escribir en el almacén.", access.writes.isEmpty())
    }

    @Test
    fun `number out of range is rejected`() = runTest {
        val access = FakeAccess()

        listOf("101", "-1", "1e9").forEach { raw ->
            val rejected = commands(access).update("liquidGlassStrength", raw)
            assertTrue("$raw más allá 0..100", rejected is HostSettingsUpdateResult.Rejected)
            assertTrue(
                "Se deben especificar las razones del rechazo.: ${(rejected as HostSettingsUpdateResult.Rejected).reason}",
                rejected.reason.contains("out of range"),
            )
        }
        assertTrue(commands(access).update("mediaConfig.ttsSpeechRate", "0.1") is HostSettingsUpdateResult.Rejected)
        assertTrue("Cuando esté fuera de los límites, no se permite escribir al almacén.", access.writes.isEmpty())
    }

    @Test
    fun `invalid enum value is rejected`() = runTest {
        val access = FakeAccess()

        val rejected = commands(access).update("themeMode", "purple")
        assertTrue(rejected is HostSettingsUpdateResult.Rejected)
        assertTrue(
            "Los motivos de rechazo deben incluir candidatos: ${(rejected as HostSettingsUpdateResult.Rejected).reason}",
            rejected.reason.contains("dark"),
        )
        assertTrue(commands(access).update("mediaConfig.ttsEngine", "nope") is HostSettingsUpdateResult.Rejected)
        assertTrue("Los valores de enumeración ilegales no se deben escribir en el almacén.", access.writes.isEmpty())
    }

    @Test
    fun `read only key is rejected`() = runTest {
        val access = FakeAccess()

        val rejected = commands(access).update("model.configured", "true")

        assertTrue(rejected is HostSettingsUpdateResult.Rejected)
        assertTrue((rejected as HostSettingsUpdateResult.Rejected).reason.contains("read-only"))
        assertTrue(access.writes.isEmpty())
    }

    @Test
    fun `missing key is rejected`() = runTest {
        val access = FakeAccess()

        assertTrue(commands(access).update(null, "true") is HostSettingsUpdateResult.Rejected)
        assertTrue(commands(access).update("   ", "true") is HostSettingsUpdateResult.Rejected)
        assertTrue(access.writes.isEmpty())
    }

    // ── Comportamiento de instantáneas existente ──────────────────────────────────────────────

    @Test
    fun `snapshot exposes every catalog group and the toggle state`() = runTest {
        val access = FakeAccess(
            HostSettingsValues(
                language = "zh",
                themeMode = "dark",
                fontSizeScale = "large",
                themeId = "sakura",
                toggles = mapOf(HostSettingKeys.PII_GUARD to true),
            ),
        )

        val snapshot = commands(access).snapshot()

        assertEquals(HostSettingsCatalog.groups.size, snapshot.groups.size)
        assertEquals(HostSettingsCatalog.groups.map { it.key }, snapshot.groups.map { it.key })
        assertEquals(
            HostSettingsCatalog.entries.map { it.id },
            snapshot.groups.flatMap { group -> group.entries.map { it.id } },
        )
        assertEquals("zh", snapshot.display.language)
        assertEquals("dark", snapshot.display.themeMode)
        assertEquals("large", snapshot.display.fontSizeScale)

        val switch = snapshot.groups.flatMap { it.entries }.single { it.kind == HostSettingsCatalog.KIND_SWITCH }
        assertEquals(HostSettingKeys.PII_GUARD, switch.toggleKey)
        assertEquals(true, switch.checked)
        assertTrue(
            snapshot.groups.flatMap { it.entries }
                .filter { it.kind != HostSettingsCatalog.KIND_SWITCH }
                .all { it.checked == null },
        )
    }

    @Test
    fun `snapshot keeps the readable toggle list within the agreed size`() = runTest {
        val snapshot = commands(FakeAccess()).snapshot()

        assertEquals(HostSettingKeys.readable.size, snapshot.toggles.size)
        assertTrue(
            "El número de interruptores de uso común debe estar entre 8 y 12.,real ${snapshot.toggles.size}",
            snapshot.toggles.size in 8..12,
        )
        // Los valores faltantes se completan con elementos falsos en lugar de faltantes.,Evite que el cliente trate indefinido como un estado de cambio.
        assertTrue(snapshot.toggles.none { it.key.isBlank() })
        assertTrue(
            "Las claves grabables deben ser legibles en valores.,De lo contrario, el nuevo valor no se podrá obtener después de escribirlo en la página web.",
            snapshot.writableKeys.all { key -> snapshot.values.any { it.key == key } },
        )
    }

    /**
     * El texto localizado se entrega con la instantánea.:banda de agrupación groupTitle,banda de entrada title,DescKey declarado con desc.
     * Esta es la única fuente de datos después de que la página web elimina su propia tabla de copia.,Las palabras faltantes equivalen a una línea vacía en la interfaz.
     */
    @Test
    fun `snapshot carries localized text for every group and entry`() = runTest {
        val snapshot = commands(FakeAccess(HostSettingsValues(language = "ja"))).snapshot()

        snapshot.groups.forEach { group ->
            assertTrue("grupo ${group.key} Falta título localizado", group.groupTitle.isNotBlank())
            assertEquals("text(ja):${group.key}", group.groupTitle)
            assertTrue("grupo ${group.key} sin entradas", group.entries.isNotEmpty())
            group.entries.forEach { entry ->
                assertTrue("entrada ${entry.id} Falta título localizado", entry.title.isNotBlank())
                assertEquals("text(ja):${entry.titleKey}", entry.title)
                assertEquals(entry.descKey?.let { "text(ja):$it" }, entry.desc)
            }
        }
        assertTrue(
            "Debe haber al menos una entrada con una descripción.,De lo contrario, esta afirmación es igual a no probada",
            snapshot.groups.flatMap { it.entries }.any { it.desc != null },
        )
    }

    @Test
    fun `unresolvable resources leave empty text instead of raw resource names`() = runTest {
        val missing = HostSettingsStrings { _, _ -> null }
        val snapshot = HostSettingsCommands(FakeAccess(), missing).snapshot()

        assertTrue("Si no se puede analizar, no puede utilizar el nombre del recurso como redacción.", snapshot.groups.all { it.groupTitle.isEmpty() })
        assertTrue(snapshot.groups.flatMap { it.entries }.all { it.title.isEmpty() && it.desc == null })
        assertTrue("El nombre para mostrar del candidato de enumeración no puede devolver también el nombre del recurso.", snapshot.values.all { item -> item.options.all { it.label.isEmpty() } })
    }
}
