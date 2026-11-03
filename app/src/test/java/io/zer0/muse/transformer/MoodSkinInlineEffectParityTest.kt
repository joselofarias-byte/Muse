package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La regularidad de los efectos especiales en línea se cambia al control de comportamiento después de la precompilación.
 *
 * Aquí, la implementación anterior de "Se compilan ocho reglas regulares para cada llamada" se deja tal como está en la prueba como referencia:
 * La nueva implementación debe ser su equivalente byte por byte, sin permitir ninguna deriva de calibre para ninguna de las entradas.
 * Además, determine algunos resultados esperados para evitar que ambas partes corrijan errores al mismo tiempo y se confirmen mutuamente.
 */
class MoodSkinInlineEffectParityTest {

    private val effects = listOf("glow", "big", "huge", "whisper", "red", "shake", "blur", "glitch")

    /** Implementación antes de la modificación: cada llamada ahora compila una regla regular para cada efecto especial. */
    private fun legacyContains(text: String): Boolean = effects.any { effect ->
        Regex("""\[$effect\]([\s\S]*?)\[/$effect\]""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    /** Implementación antes del cambio: cada vez que se realiza una llamada, se compila una regla regular para cada efecto especial y luego se reemplaza en secuencia. */
    private fun legacyStrip(text: String): String {
        var result = text
        for (effect in effects) {
            val regex = Regex("""\[$effect\]([\s\S]*?)\[/$effect\]""", RegexOption.IGNORE_CASE)
            result = regex.replace(result) { it.groupValues[1] }
        }
        return result
    }

    private val samples = mapOf(
        "Con efectos especiales" to "Texto[glow]palabras brillantes[/glow]finalizando",
        "Sin efectos especiales" to "Texto ordinario, ni un solo corchete",
        "Caso mixto" to "Texto[Glow]mayúsculas[/GLOW]con[Big]también en mayúscula[/bIg]",
        "Varios párrafos" to "[glow]uno[/glow]atrapado en el medio[red]dos[/red][shake]tres[/shake]",
        "Anidando con el mismo nombre" to "[glow]afuera[glow]dentro[/glow]afuera[/glow]",
        "Efectos especiales desconocidos" to "[glowx]Sin contar efectos especiales[/glowx] con [blurred]no cuenta[/blurred]",
        "No cerrado" to "Texto[glow]sin etiqueta de cierre",
        "Uso común de corchetes" to "[1] notas a pie de página con [enlace](https://a.b)",
    )

    @Test
    fun `containsInlineEffect matches the per call compilation`() {
        for ((name, text) in samples) {
            assertEquals(
                "containsInlineEffect Inconsistente con antes del cambio.: $name",
                legacyContains(text),
                MoodSkinParser.containsInlineEffect(text),
            )
        }
    }

    @Test
    fun `stripInlineEffects matches the per call compilation`() {
        for ((name, text) in samples) {
            assertEquals(
                "stripInlineEffects Inconsistente con antes del cambio.: $name",
                legacyStrip(text),
                MoodSkinParser.stripInlineEffects(text),
            )
        }
    }

    @Test
    fun `expected outputs are pinned`() {
        assertTrue(MoodSkinParser.containsInlineEffect("Texto[glow]brillante[/glow]"))
        assertFalse(MoodSkinParser.containsInlineEffect("El texto no tiene efectos especiales."))
        assertTrue("No distingue entre mayúsculas y minúsculas", MoodSkinParser.containsInlineEffect("Texto[GlOw]brillante[/GLOW]"))

        assertEquals("Terminaciones de palabras brillantes en el texto.", MoodSkinParser.stripInlineEffects("Texto[glow]palabras brillantes[/glow]finalizando"))
        assertEquals("Texto original", MoodSkinParser.stripInlineEffects("Texto original"))
        assertEquals(
            "También se deben eliminar las letras mayúsculas y minúsculas mixtas.",
            "Mayúsculas y minúsculas",
            MoodSkinParser.stripInlineEffects("[Glow]mayúsculas[/GLOW]con[Big]minúscula[/bIg]"),
        )
        assertEquals(
            "Se despegan varias secciones en secuencia y la última sección no se ve afectada por la sección anterior.",
            "uno dos tres",
            MoodSkinParser.stripInlineEffects("[glow]uno[/glow][red]dos[/red][shake]tres[/shake]"),
        )
        assertEquals(
            "El nombre del efecto desconocido no es un efecto especial.",
            "[glowx]Sin contar efectos especiales[/glowx]",
            MoodSkinParser.stripInlineEffects("[glowx]Sin contar efectos especiales[/glowx]"),
        )
    }

    @Test
    fun `cleanForExport still strips moodfx then inline effects`() {
        val raw = "<moodfx>rage</moodfx>Texto[glow]palabras brillantes[/glow]"
        assertEquals("Palabras brillantes en el texto principal.", MoodSkinParser.cleanForExport(raw))
        assertEquals("El efecto peeling es el mismo que antes del cambio.", legacyStrip("Texto[glow]palabras brillantes[/glow]"), MoodSkinParser.cleanForExport(raw))
    }
}
