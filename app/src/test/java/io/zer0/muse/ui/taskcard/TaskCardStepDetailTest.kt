package io.zer0.muse.ui.taskcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba del resumen de pasos de la tarjeta de tareas.
 *
 * Comentarios de los usuarios "Las tarjetas que se muestran en una tarjeta son diferentes de las de otros lugares y están exprimidas": el título del paso show_card en la captura de pantalla
 * Lo que sigue es un original truncado. JSON(`{"title":"vinyl_music_player","code":"<style>...`).
 * La causa raíz es el resumen de pasos. `args.take(200)`, y el parámetro de show_card es el HTML completo.
 * Fijado aquí: el resumen de herramientas tipo tarjeta debe ser el título de la tarjeta, no el texto original del parámetro.
 *
 * Prueba pura de JVM (sin Robolectric habilitado): lo que se prueba es la decisión resumida en sí, no se requiere ningún tiempo de ejecución de Android.
 */
class TaskCardStepDetailTest {

    private fun detail(name: String, args: String): String = TaskCardData.stepDetailFor(name, args, TaskCardData.DelegateAgentArgs("", ""))

    @Test
    fun `show_card step detail is the card title not the raw json`() {
        val args = """{"title":"vinyl_music_player","code":"<style>:root{--bg:var(--muse-bg)}</style><div>player</div>"}"""

        val out = detail("show_card", args)

        assertEquals("vinyl_music_player", out)
        assertFalse("El resumen de pasos no puede ser original JSON,real: $out", out.contains("\"code\""))
        assertFalse("El resumen de pasos no puede ser HTML,real: $out", out.contains("<style>"))
    }

    @Test
    fun `render_data step detail is also the card title`() {
        assertEquals("q4_revenue", detail("render_data", """{"title":"q4_revenue","data":{"a":1}}"""))
    }

    @Test
    fun `card step without title shows no detail instead of raw json`() {
        assertEquals("", detail("show_card", """{"code":"<div>no title here</div>"}"""))
    }

    @Test
    fun `card title is truncated to the step detail budget`() {
        val longTitle = "t".repeat(200)

        val out = detail("show_card", """{"title":"$longTitle","code":"<div>x</div>"}""")

        assertTrue("El resumen debe truncarse,longitud real ${out.length}", out.length <= 60)
    }

    @Test
    fun `malformed card args do not crash and do not leak raw text`() {
        assertEquals("", detail("show_card", "not-json-at-all"))
    }

    @Test
    fun `ordinary tools still show their arguments as detail`() {
        val args = """{"query":"El clima de hoy"}"""

        assertEquals(args, detail("web_search", args))
    }

    @Test
    fun `ordinary tool detail is capped at 200 chars`() {
        val out = detail("web_search", "x".repeat(500))

        assertEquals(200, out.length)
    }

    @Test
    fun `delegate agent detail stays the delegated task`() {
        val out = TaskCardData.stepDetailFor(
            "delegate_agent",
            """{"assistantId":"a1","task":"Ayúdame a organizar las actas de la reunión de esta semana."}""",
            TaskCardData.DelegateAgentArgs("a1", "Ayúdame a organizar las actas de la reunión de esta semana."),
        )

        assertEquals("Ayúdame a organizar las actas de la reunión de esta semana.", out)
    }
}
