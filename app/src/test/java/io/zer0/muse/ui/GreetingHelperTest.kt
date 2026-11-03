package io.zer0.muse.ui

import io.zer0.memory.fact.FactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
class GreetingHelperTest {

    private fun fact(text: String, time: String? = null) = FactEntity(
        id = 1L,
        fact = text,
        time = time,
        createdAt = "2026-08-07T12:00:00",
    )

    @Test
    fun `Límite de saludo de período de tiempo estable`() {
        assertEquals("buenos dias", GreetingHelper.getTimeGreeting(5))
        assertEquals("buenos dias", GreetingHelper.getTimeGreeting(10))
        assertEquals("buen mediodía", GreetingHelper.getTimeGreeting(11))
        assertEquals("buen mediodía", GreetingHelper.getTimeGreeting(13))
        assertEquals("buenas tardes", GreetingHelper.getTimeGreeting(14))
        assertEquals("buenas tardes", GreetingHelper.getTimeGreeting(17))
        assertEquals("buenas noches", GreetingHelper.getTimeGreeting(18))
        assertEquals("buenas noches", GreetingHelper.getTimeGreeting(22))
        assertEquals("es tarde en la noche", GreetingHelper.getTimeGreeting(23))
        assertEquals("es tarde en la noche", GreetingHelper.getTimeGreeting(0))
    }

    @Test
    fun `Si recuerdas los saludos para el examen de mañana, puedes usarlos primero como recordatorios del examen.`() {
        val today = LocalDate.of(2026, 8, 7)
        val tomorrow = today.plusDays(1).toString()
        val facts = listOf(fact("El usuario realizará mañana el test CET-4", time = tomorrow))

        val greeting = GreetingHelper.buildGreeting(facts, hour = 20, date = today)

        // Personalización primero:Contiene examen,No aparecen términos solares./fiesta
        assertTrue("Se deben incluir recordatorios de exámenes.: $greeting", greeting.contains("examen"))
        assertTrue("Los términos solares ya no deberían mostrarse: $greeting", !greeting.contains("Principios de otoño"))
        assertTrue("Se debe controlar la longitud: $greeting", greeting.length <= 40)
        // segunda persona:El contenido mostrado a los usuarios no debería aparecer."Usuario"
        assertTrue("Usa segunda persona: $greeting", !greeting.contains("Usuario"))
    }

    @Test
    fun `Generar recordatorios de viaje cuando haya vuelos en la memoria`() {
        val today = LocalDate.of(2026, 8, 7)
        val tomorrow = today.plusDays(1).toString()
        val facts = listOf(fact("Tengo un vuelo a Shanghai mañana por la mañana para un viaje de negocios.", time = tomorrow))

        val hint = GreetingHelper.getMemoryHint(facts, today)

        assertNotNull(hint)
        assertTrue("Los vuelos deberían incluirse.: $hint", hint!!.contains("vuelos"))
    }

    @Test
    fun `Cuando no hay eventos recientes, los términos solares y festivales son aleatorios y solo se selecciona uno.`() {
        val today = LocalDate.of(2026, 8, 7) // Mañana 8.8 Comienzo de Otoño
        val greeting = GreetingHelper.buildGreeting(emptyList(), hour = 20, date = today)

        assertTrue("Tómate el tiempo para saludar: $greeting", greeting.startsWith("buenas noches"))
        // Sólo se permite un sufijo(Seleccione aleatoriamente un período solar o un día festivo,No se puede luchar contra dos al mismo tiempo.)
        val extras = greeting.removePrefix("buenas noches,")
        assertTrue("Sólo debe haber un sufijo: $greeting", !extras.contains(","))
    }

    @Test
    fun `Sin recuerdos, sin términos solares, sólo saludos durante las fiestas.`() {
        val today = LocalDate.of(2026, 8, 10) // Sin estaciones, sin festivales
        val greeting = GreetingHelper.buildGreeting(emptyList(), hour = 9, date = today)
        assertEquals("buenos dias", greeting)
    }

    @Test
    fun `Informar cumpleaños cuando el cumpleaños sea mañana.`() {
        val today = LocalDate.of(2026, 8, 7)
        val facts = listOf(fact("El cumpleaños del usuario es8mes8dia"))
        val hint = GreetingHelper.getMemoryHint(facts, today)
        assertNotNull(hint)
        assertTrue("Se debe indicar el cumpleaños: $hint", hint!!.contains("cumpleaños"))
    }

    @Test
    fun `3No hay recordatorios para eventos fuera del cielo.`() {
        val today = LocalDate.of(2026, 8, 7)
        val far = today.plusDays(5).toString()
        val facts = listOf(fact("El usuario quiere realizar el examen.", time = far))
        assertNull(GreetingHelper.getMemoryHint(facts, today))
    }

    @Test
    fun `El resumen diario más reciente seguirá al saludo de la hora.`() {
        val today = LocalDate.of(2026, 8, 10)
        val greeting = GreetingHelper.buildGreeting(
            facts = emptyList(),
            hour = 9,
            date = today,
            dailySummary = "Hoy completó la solución de problemas de la interfaz y también anotó la reunión de mañana.",
            dailySummaryDate = today.toString(),
        )

        assertTrue("Se debe reservar tiempo para los saludos.: $greeting", greeting.startsWith("Buenos días,"))
        assertTrue("Debe traer elementos de resumen diario: $greeting", greeting.contains("Solución de problemas de interfaz completada"))
    }

    @Test
    fun `Los resúmenes diarios ya no están truncados por una sola línea de presupuesto,Sólo sujeto a un límite superior de seguridad flojo`() {
        val today = LocalDate.of(2026, 8, 10)
        val raw = "Hoy completamos la solución de problemas de la interfaz, registramos varias tareas pendientes y discutimos los arreglos de seguimiento."
        val summary = GreetingHelper.getDailySummaryHint(
            raw,
            today.toString(),
            today,
        )
        assertNotNull(summary)
        // v2.7: Las capas de presentación ya no se limitan a 24 caracteres; El texto breve debe dejarse como está (solo normalizado).
        assertTrue("Los resúmenes de extensión normal no deben truncarse: $summary", summary!!.length > GreetingHelper.DAILY_SUMMARY_HINT_MAX_LENGTH)
        assertTrue("El texto original debe mantenerse intacto.", summary.contains("Arreglos de seguimiento discutidos"))
        // Sujeto únicamente a un límite superior de seguridad flexible
        assertTrue(summary.length <= GreetingHelper.DAILY_SUMMARY_DISPLAY_SAFETY_LIMIT)

        val hint = GreetingHelper.getMemoryHint(
            listOf(
                fact(
                    "El usuario realizará el examen CET-4 mañana y preparará los materiales pertinentes.",
                    time = today.plusDays(1).toString(),
                ),
            ),
            today,
        )
        assertNotNull(hint)
        // Los recordatorios de eventos recientes siguen siendo breves recordatorios de una sola línea, con un presupuesto de 18 caracteres.
        assertTrue(hint!!.length <= GreetingHelper.PERSONALIZED_HINT_MAX_LENGTH)
    }

    @Test
    fun `Los resúmenes diarios anormalmente largos todavía están protegidos por límites de seguridad`() {
        val today = LocalDate.of(2026, 8, 10)
        val huge = "resumen muy largo".repeat(100)
        val summary = GreetingHelper.getDailySummaryHint(huge, today.toString(), today)
        assertNotNull(summary)
        assertTrue(
            "Los artículos extremadamente largos deben bloquearse mediante límites de seguridad.: ${summary!!.length}",
            summary.length <= GreetingHelper.DAILY_SUMMARY_DISPLAY_SAFETY_LIMIT + 1,
        )
    }

    @Test
    fun `Los resúmenes diarios de más de un día no deberían seguir mostrándose`() {
        val today = LocalDate.of(2026, 8, 10)
        val greeting = GreetingHelper.buildGreeting(
            facts = emptyList(),
            hour = 9,
            date = today,
            dailySummary = "Este es un viejo resumen de ayer.",
            dailySummaryDate = today.minusDays(2).toString(),
        )

        assertEquals("buenos dias", greeting)
    }
}
