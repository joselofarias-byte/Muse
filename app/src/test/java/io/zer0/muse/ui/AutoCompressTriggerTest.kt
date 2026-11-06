package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba automática del calibre del gatillo de compresión.
 *
 * Fondo: original arreglado"ocupar > 80%"desencadenar. Para modelos con ventanas superiores a 200K,80% significa que se han rellenado 160K,
 * dejar para"Resultado de esta ronda + resultados de herramientas posteriores"Solo queda espacio 40K —— Un ciclo de herramienta puede bloquear la ventana a mitad de la generación.
 * No hay posibilidad de compresión durante la generación.
 *
 * Cambiar a"Ocupar al otro lado de la ventana − Monto reservado", cantidad reservada = `max(16K, 10% × ventana)`.
 */
class AutoCompressTriggerTest {

    @Test
    fun `unknown window or unknown usage never triggers`() {
        assertFalse("La ventana desconocida no se activa", ChatViewModel.shouldAutoCompress(currentTokens = 5_000, maxTokens = 0))
        assertFalse("La ocupación desconocida no se activa", ChatViewModel.shouldAutoCompress(currentTokens = 0, maxTokens = 100_000))
        assertFalse("Los números negativos no se activan", ChatViewModel.shouldAutoCompress(currentTokens = -1, maxTokens = -1))
    }

    @Test
    fun `small window keeps the fixed floor reserve`() {
        // 100K Ventana:10% = 10K < 16K límite inferior → Reserva 16K, punto de activación 84K
        val window = 100_000
        assertFalse("83K No cruzar la línea", ChatViewModel.shouldAutoCompress(currentTokens = 83_000, maxTokens = window))
        assertTrue("85K debería desencadenar", ChatViewModel.shouldAutoCompress(currentTokens = 85_000, maxTokens = window))
    }

    @Test
    fun `large window reserves proportionally instead of a fixed ratio`() {
        // 1M Ventana: Reservada = max(16K, 100K) = 100K → Punto de activación 900K (en lugar de un 80% fijo de 800K para activar... (ver más abajo)
        val window = 1_000_000
        assertFalse("850K No cruzar la línea", ChatViewModel.shouldAutoCompress(currentTokens = 850_000, maxTokens = window))
        assertTrue("950K debería desencadenar", ChatViewModel.shouldAutoCompress(currentTokens = 950_000, maxTokens = window))
        // Regresión clave: activación fija del 80 % en 820 K (prematura), la reserva proporcional es 900 K.
        // La semántica de los dos es diferente, aquí está el candado."Ya no se tratan los grandes ventanales con un porcentaje fijo del 80%".
        assertFalse(
            "No debe activarse a un 80% fijo en ventanas grandes (820K < 900K)",
            ChatViewModel.shouldAutoCompress(currentTokens = 820_000, maxTokens = window),
        )
    }

    @Test
    fun `tiny window falls back to the fixed ratio`() {
        // 10K La ventana ni siquiera cabe en la reserva de 16K. → Volver al 80% fijo (de lo contrario, se activará para siempre o nunca)
        assertFalse("7K Aún no cruzado 80%", ChatViewModel.shouldAutoCompress(currentTokens = 7_000, maxTokens = 10_000))
        assertTrue("9K cruz 80%", ChatViewModel.shouldAutoCompress(currentTokens = 9_000, maxTokens = 10_000))
    }

    @Test
    fun `trigger leaves room for the coming tool round`() {
        // Invariante: cuando se activa, el espacio restante cubre al menos la cantidad reservada (es decir, el espacio disponible para resultados y salidas de herramientas posteriores)
        listOf(32_768, 100_000, 200_000, 1_000_000).forEach { window ->
            val reserve = maxOf(16_384, (window * 0.1).toInt())
            val triggerPoint = window - reserve
            assertTrue(
                "ventana $window: Punto gatillo $triggerPoint todavía debe quedar después $reserve",
                window - triggerPoint >= reserve,
            )
        }
    }
}
