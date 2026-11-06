package io.zer0.muse.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Copie la coherencia de los recursos para el diagnóstico de activación en segundo plano.
 *
 * La redacción de este panel se distribuye en 7 paquetes de idiomas.(Predeterminado + 6 locale). La falta de una clave no provocará un error de compilación,
 * En ese idioma sólo se expondrán otros idiomas o cadenas vacías.,Así que logra tres cosas con las pruebas.:
 *  1. El conjunto de claves es consistente en cada paquete de idioma.;
 *  2. tipo de marcador de posición(`%1$s` / `%1$d`)Igual que el paquete predeterminado,evitar el tiempo de ejecución `IllegalFormatConversionException`;
 *  3. Los nombres de estado no están traducidos —— Los usuarios y desarrolladores lo utilizan cuando buscan y brindan comentarios. `WorkInfo.State` Nombre inglés.
 *
 * La relación correspondiente entre nombres clave y enumeraciones también está clara.:Al agregar un nuevo estado o tipo de tarea,Si no agrega redacción,Falla aquí primero.
 * Esta prueba ha sido sometida a una autocomprobación de mutaciones.:Después de cambiar deliberadamente el nombre de una clave`every locale carries the same wake keys`El paquete de idioma y el nombre de la clave se informarán con precisión.
 */
class WakeDiagnosticsStringsTest {

    private val locales = listOf(
        "values",
        "values-en",
        "values-ja",
        "values-ko",
        "values-ru",
        "values-es",
        "values-pt-rBR",
    )

    private fun wakeStrings(locale: String): Map<String, String> {
        // El directorio de trabajo de prueba único es el directorio del módulo.,Pero en la historia, existe la costumbre de ejecutar según la raíz del almacén.,Pruebe en ambos sentidos.
        val candidates = listOf(
            Path.of("app/src/main/res/$locale/strings_features.xml"),
            Path.of("src/main/res/$locale/strings_features.xml"),
        )
        val file = candidates.firstOrNull(Files::exists)
            ?: error("no encontrado $locale de strings_features.xml,buscado $candidates")
        val text = Files.newBufferedReader(file).use { it.readText() }
        val matches = WAKE_STRING.findAll(text).toList()
        val keys = matches.map { it.groupValues[1] }
        assertEquals("$locale hay duplicados wake_* clave", keys.distinct().size, keys.size)
        return matches.associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholdersOf(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.sorted().toList()

    @Test
    fun `every locale carries the same wake keys`() {
        val defaultKeys = wakeStrings("values").keys
        assertTrue("El paquete predeterminado no tiene wake_* redacción", defaultKeys.isNotEmpty())
        locales.forEach { locale ->
            assertEquals("$locale de wake_* El conjunto de claves no coincide con el paquete predeterminado", defaultKeys, wakeStrings(locale).keys)
        }
    }

    @Test
    fun `placeholder types match the default locale`() {
        val defaults = wakeStrings("values")
        locales.filter { locale -> locale != "values" }.forEach { locale ->
            val localized = wakeStrings(locale)
            defaults.forEach { (key, value) ->
                assertEquals(
                    "$locale de $key El marcador de posición no es coherente con el paquete predeterminado",
                    placeholdersOf(value),
                    placeholdersOf(localized.getValue(key)),
                )
            }
        }
    }

    @Test
    fun `state names stay untranslated in every locale`() {
        // El nombre del estado es coherente con la escritura en la salida de adb y los registros del sistema.,Después de la traducción, no se puede encontrar.
        val expected = WakeState.values().associate { state -> "wake_state_${state.name.lowercase()}" to state.name }
        locales.forEach { locale ->
            val strings = wakeStrings(locale)
            expected.forEach { (key, value) ->
                assertEquals("$locale de $key Se debe mantener el texto original en inglés.(WorkInfo.State nombre)", value, strings.getValue(key))
            }
        }
    }

    @Test
    fun `state kind and stop reason keys follow the enums`() {
        val defaults = wakeStrings("values")
        WakeState.values().forEach { state ->
            assertTrue("Falta texto de estado wake_state_${state.name.lowercase()}", defaults.containsKey("wake_state_${state.name.lowercase()}"))
        }
        WakeWorkKind.values().forEach { kind ->
            assertTrue("Falta copia del tipo de tarea wake_kind_${kind.name.lowercase()}", defaults.containsKey("wake_kind_${kind.name.lowercase()}"))
        }
        // Cada motivo para detener la ejecución debe tener una frase para que el usuario la lea.,Ni más ni menos.
        assertEquals(
            "El número de motivos de detención no coincide con la enumeración WakeStopReason",
            WakeStopReason.values().size,
            defaults.keys.count { key -> key.startsWith("wake_stop_") },
        )
    }

    @Test
    fun `panel chrome keys exist so an empty block cannot pass`() {
        val defaults = wakeStrings("values")
        listOf(
            "wake_diagnostics_title",
            "wake_diagnostics_copy",
            "wake_diagnostics_next_run",
            "wake_diagnostics_stop_reason",
        ).forEach { key ->
            assertTrue("Falta copia del panel $key", defaults.containsKey(key))
        }
    }

    private companion object {
        val WAKE_STRING = Regex("""<string name="(wake_[a-z_]+)"[^>]*>([^<]*)</string>""")
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
    }
}
