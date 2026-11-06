package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cálculo de engranajes para instantáneas de renderizado progresivo.
 *
 * Este conjunto de casos de uso bloquea el "número de recargas de página completa" en lugar de la apariencia: lo que es realmente caro en el lado de la tarjeta son las recargas de página completa de WebView.
 * El número de veces debe ser de O(L/step) hasta O(log), de lo contrario, puedes retrasar docenas de recargas simplemente ajustando el tamaño del paso de una sola vez.
 * Y ninguna otra puerta será roja.
 */
class ProgressiveCardTest {

    @Test
    fun `content_shorter_than_step_is_returned_as_is`() {
        assertEquals(0, ProgressiveCard.snapshotLength(0))
        assertEquals(24, ProgressiveCard.snapshotLength(24))
        assertEquals("abc", ProgressiveCard.snapshot("abc"))
    }

    @Test
    fun `snapshot_length_only_moves_on_doubling_steps`() {
        // Tamaño del paso 24: El engranaje está 24 / 48 / 96 / 192 / 384 / 768 / 1536 / 3072 / 6144 ...
        assertEquals(24, ProgressiveCard.snapshotLength(25))
        assertEquals(24, ProgressiveCard.snapshotLength(47))
        assertEquals(48, ProgressiveCard.snapshotLength(48))
        assertEquals(48, ProgressiveCard.snapshotLength(95))
        assertEquals(96, ProgressiveCard.snapshotLength(96))
        assertEquals(96, ProgressiveCard.snapshotLength(191))
        assertEquals(192, ProgressiveCard.snapshotLength(192))
        assertEquals(6144, ProgressiveCard.snapshotLength(12_287))
    }

    @Test
    fun `snapshot_is_a_prefix_and_never_longer_than_content`() {
        val content = "x".repeat(5000)
        val snap = ProgressiveCard.snapshot(content)
        assertEquals(3072, snap.length)
        assertTrue(content.startsWith(snap))
        assertEquals(content, ProgressiveCard.snapshot(content, step = 100_000))
    }

    @Test
    fun `snapshot_count_drops_from_linear_to_logarithmic`() {
        // 8KB Tarjeta: el tamaño de paso fijo de 120 es 68 recargas de página completa y el engranaje geométrico es 9 veces.
        assertEquals(9, ProgressiveCard.snapshotCount(8192))
        assertEquals(68, 8192 / 120)
        assertTrue(ProgressiveCard.snapshotCount(8192) * 7 <= 68)
    }

    @Test
    fun `streaming_growth_only_triggers_on_boundaries`() {
        // Simule una transmisión real: agregue 40 caracteres cada vez y cuente cuántas instantáneas han cambiado realmente.
        val total = StringBuilder()
        val seen = LinkedHashSet<Int>()
        repeat(300) {
            total.append("0123456789".repeat(4))
            seen.add(ProgressiveCard.snapshotLength(total.length))
        }
        assertEquals(12_000, total.length)
        // Calibre antiguo con paso fijo:24…12000 Cambiar cada múltiplo de 120 ≈ 99 veces;
        // El engranaje geométrico es sólo 24/48/…/6144 9 en total.
        assertEquals(9, seen.size)
        assertTrue(seen.size * 10 < 12_000 / 120)
    }

    @Test
    fun `custom_step_is_honoured`() {
        assertEquals(10, ProgressiveCard.snapshotLength(15, step = 10))
        assertEquals(20, ProgressiveCard.snapshotLength(35, step = 10))
        assertEquals(40, ProgressiveCard.snapshotLength(51, step = 10))
        assertEquals(2, ProgressiveCard.snapshotCount(35, step = 10))
    }

    @Test
    fun `snapshot_never_splits_a_surrogate_pair`() {
        // 23 ascii seguido de emoji (UTF-16 dos caracteres): la longitud de la instantánea es exactamente 24,
        // Está cortado en el proxy alto del emoji, que debe retroceder un espacio hasta 23.
        val content = "a".repeat(23) + "\uD83D\uDE00" + "cola"
        assertEquals(24, ProgressiveCard.snapshotLength(content.length))
        val snap = ProgressiveCard.snapshot(content)
        assertEquals(23, snap.length)
        assertTrue(snap.none { Character.isSurrogate(it) })
    }
}
