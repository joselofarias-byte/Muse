package io.zer0.muse.miniphone.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Determinación del recuento de mensajes nuevos.
 *
 * Solo prueba esto:Dado "el número de elementos la última vez / el número de elementos esta vez / si la persona está en la parte inferior / el número de elementos que ya se muestran",
 * Se deben mostrar varios elementos. Todas las funciones puras,No se requieren gestos, diseños de listas ni interfaces.
 */
class ChatNewMessagesPillTest {

    @Test
    fun awayFromBottomCountsTheMessagesThatArrived() {
        assertEquals(
            "Cuando esté lejos del final, mostrará 3 elementos nuevos. 3",
            3,
            advanceNewMessageCount(previousTotal = 2, currentTotal = 5, atBottom = false, displayed = 0),
        )
    }

    @Test
    fun anotherArrivalAccumulatesOnTopOfTheDisplayedCount() {
        assertEquals(
            "Los 3 elementos existentes no se borran.,otra vez +1",
            4,
            advanceNewMessageCount(previousTotal = 5, currentTotal = 6, atBottom = false, displayed = 3),
        )
    }

    @Test
    fun atBottomNeverShowsAnything() {
        assertEquals(
            "Cuando las personas están en la parte inferior, los mensajes nuevos son directamente visibles.",
            0,
            advanceNewMessageCount(previousTotal = 2, currentTotal = 5, atBottom = true, displayed = 0),
        )
        assertEquals(
            "Después de regresar al último elemento, borre la cantidad de elementos que se mostrarán.",
            0,
            advanceNewMessageCount(previousTotal = 6, currentTotal = 6, atBottom = true, displayed = 3),
        )
    }

    @Test
    fun unchangedTotalKeepsWhatIsAlreadyShown() {
        assertEquals(
            "Si el número de artículos no cambia, seguirá siendo el mismo.",
            2,
            advanceNewMessageCount(previousTotal = 4, currentTotal = 4, atBottom = false, displayed = 2),
        )
    }

    @Test
    fun deletedMessageDoesNotMakeTheCountGoNegative() {
        assertEquals(
            "Cuando se eliminan sus propios mensajes, no se borrarán ni se volverán negativos.",
            2,
            advanceNewMessageCount(previousTotal = 5, currentTotal = 4, atBottom = false, displayed = 2),
        )
    }

    @Test
    fun firstObservationOnAnEmptyThreadShowsNothing() {
        assertEquals(
            "No aparece ningún mensaje emergente cuando se acaba de ingresar a la página y no hay nada",
            0,
            advanceNewMessageCount(previousTotal = 0, currentTotal = 0, atBottom = false, displayed = 0),
        )
    }

    @Test
    fun pillLabelReadsAsASentence() {
        assertEquals("3 nuevos mensajes", newMessagePillLabel(3))
        assertEquals("1 nuevos mensajes", newMessagePillLabel(1))
    }
}
