package io.zer0.muse.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path

/**
 * Estabilidad de instancia de devoluciones de llamadas de tarjetas.
 *
 * puente de cartas con `onCardAction` de**Ejemplo**Como clave para recordar: cambie una nueva lambda en cada cuadro,
 * El puente se reconstruye una vez y se vuelve a ensamblar todo el HTML de la tarjeta (incluidas 7 veces de limpieza regular del texto completo). En la máquina real, está transmitiendo.
 * Haciendo lo mismo repetidamente en cada cuadro, por lo que esta cadena debe estar fijada:
 *
 *  - El mismo msg.id obtiene la misma instancia lambda entre múltiples reorganizaciones;
 *  - ChatScreen De hecho, así es como está escrito en CardRuntime (aserciones a nivel de código fuente, consulte los comentarios de cada caso de uso).
 *
 * ¿Por qué no utilizar "contar el número de llamadas a ensamblar HTML" para realizar la prueba? Está oculto en `CardRuntime` privado
 * `remember` bloque, la única forma de observarlo es insertar el contador en el código de producción. —— Esto se implementó con fines de prueba.
 * Aquí se cambia a prueba "remember La garantía dada por sí misma” (Compose caso de uso en tiempo de ejecución)+ Código de producción de pines
 * Se utiliza esta garantía (afirmación del código fuente), las dos son equivalentes juntas y no contaminan el código de producción.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CardCallbackStabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Compose Tiempo de ejecución:remember(key) estabilidad de instancia de ──────────────────────

    @Test
    fun `callback remembered by message id is the same instance across recompositions`() {
        val messageId = "msg-1"
        val tick = mutableStateOf(0)
        val rememberedInstances = mutableListOf<() -> Int>()
        val inlineInstances = mutableListOf<() -> Int>()

        composeTestRule.setContent {
            val current = tick.value
            // Mismo método de escritura que ChatScreen:key Sólo msg.id inmutable.
            val stable = remember(messageId) { { messageId.length } }
            // Contraste: lambda en línea sin recordar.
            val unstable = { messageId.length + current }
            SideEffect {
                rememberedInstances += stable
                inlineInstances += unstable
            }
            MaterialTheme {
                Text(text = "tick=$current")
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { tick.value = 1 }
        composeTestRule.waitForIdle()

        assertTrue("Pasar por al menos dos reorganizaciones.: ${rememberedInstances.size}", rememberedInstances.size >= 2)
        assertSame(
            "La reorganización del mismo msg.id debe ser la misma instancia; de lo contrario, el puente de la tarjeta se reconstruirá en cada cuadro.",
            rememberedInstances.first(),
            rememberedInstances.last(),
        )
        assertNotSame(
            "Sin recordar, cada fotograma es un objeto nuevo. —— Este es el caché que fue penetrado antes del cambio.",
            inlineInstances.first(),
            inlineInstances.last(),
        )
    }

    // ── Nivel de código fuente: el código de producción utiliza la garantía anterior ──────────────────────────────

    @Test
    fun `chat screen remembers the per message card bridge`() {
        val screen = readSource("ChatScreen.kt")

        val anchor = screen.indexOf("val onCardAction =")
        assertTrue("ChatScreen Debe haber una declaración onCardAction en", anchor >= 0)
        val window = screen.substring(anchor, minOf(screen.length, anchor + 400))
        assertTrue(
            "La devolución de la tarjeta debe basarse en el recuerdo de msg.id; de lo contrario, el recuerdo descendente (puente → HTML) Falló en cada fotograma",
            window.contains("remember(msg.id)"),
        )
        assertTrue(
            "remember Aún necesitará reutilizar el handleCardAction compartido más adelante y no podrá crear un segundo conjunto de procesadores.",
            window.contains("handleCardAction(msg.id.toString(), action)"),
        )
        assertTrue(
            "MessageBubble Lo que recibas debe ser esta instancia estable.",
            screen.contains("onCardAction = onCardAction,"),
        )
        assertFalse(
            "La antigua lambda en línea era un objeto nuevo cada vez que se reorganizaba",
            screen.contains("onCardAction = { action -> handleCardAction("),
        )
    }

    @Test
    fun `card runtime builds its web view client once`() {
        val runtime = readSource("markdown/card/CardRuntime.kt")

        assertTrue(
            "WebViewClient Debe recordarse una vez; de lo contrario, el usuario posterior que recuerde usarlo como clave siempre no será válido.",
            runtime.contains("val webViewClient = remember { RichContentWebViewClient() }"),
        )
        assertTrue(runtime.contains("webViewClient = webViewClient,"))
        assertFalse(
            "La antigua forma de escribir es una nueva por cuadro. client",
            runtime.contains("webViewClient = RichContentWebViewClient(),"),
        )
    }

    @Test
    fun `rich content card keeps its data reader across recompositions`() {
        val rich = readSource("markdown/RichContentCard.kt")

        assertTrue(
            "La devolución de llamada de lectura de datos también ingresa la clave de recuerdo del puente de la tarjeta y debe almacenarse en caché según las dependencias.",
            rich.contains("remember(store, inlineCardData) { { id: String -> store?.get(id) ?: inlineCardData } }"),
        )
        assertFalse(
            "El antiguo método de escritura crea una nueva lambda en cada fotograma y el puente se reconstruye en consecuencia.",
            rich.contains("readData = { id -> store?.get(id) ?: inlineCardData },"),
        )
    }

    /** Lea el código fuente de producción en el directorio ui (el directorio de trabajo de prueba puede ser la raíz del módulo o la raíz del almacén). */
    private fun readSource(relative: String): String {
        val candidates = listOf(
            Path.of("src/main/java/io/zer0/muse/ui/$relative"),
            Path.of("app/src/main/java/io/zer0/muse/ui/$relative"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate ui/$relative from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
