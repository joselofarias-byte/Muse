package io.zer0.muse.ui

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * v2.6.8 Regresión del enlace de interacción de la tarjeta (aserción de cableado a nivel de fuente).
 *
 * Las dos cosas reportadas por la máquina real son en realidad dos extremos de la misma cadena rota:
 *  1. Las tarjetas se incluyen en los pasos de las tarjetas de tareas y solo se pueden ver después de desplegar el plan de ejecución;
 *  2. Todos los controles de la tarjeta abierta no responden y el menú de selección de palabras del sistema aparece cuando se mantiene presionado.
 *
 * La causa principal es que "la ruta de la tarjeta de tareas codifica el puente como nulo", y la otra es "javaScriptEnabled = bridge != null"——
 * Cuando no hay devolución de llamada, todo el JS se cierra y el enlace del evento nunca se ejecuta. Estas pruebas bloquean tres cosas:
 *  - El puente debe poder llegar hasta la ficha del producto (no se puede volver a dejar vacío en ninguno de los dos caminos);
 *  - JS No más dependencia de los puentes;
 *  - show_card Es un entregable y no es absorbido por la tarjeta de tareas.
 *
 * Código fuente puro/Aserción de función pura, no es necesario ejecutar el renderizado Compose.
 */
class CardInteractionWiringTest {

    // ── 1. La ruta de la tarjeta de tareas debe ser puenteada. ────────────────────────────────────────────

    @Test
    fun `inline direct output forwards the card bridge instead of nulling it`() {
        val source = readUi("MessageBubbleExtras.kt")
        val block = source.substringAfter("internal fun DirectToolOutputInline(")
            .substringBefore("@Composable\nprivate fun DirectToolOutput(")

        assertFalse(
            "DirectToolOutputInline Solía ​​estar codificado onCardAction = null, las cartas en la tarjeta de tarea nunca tendrán un puente.",
            block.contains("onCardAction = null"),
        )
        assertTrue("El puente pasado por la persona que llama debe transmitirse.", block.contains("onCardAction = onCardAction"))
        assertTrue("Debe haber un puente en el parámetro.", block.contains("onCardAction: ((io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null"))
    }

    @Test
    fun `chat screen passes one shared bridge into the task card path`() {
        val screen = readUi("ChatScreen.kt")
        val taskCardCall = screen.substringAfter("io.zer0.muse.ui.taskcard.TaskCard(")
            .substringBefore(") else if (")
        assertTrue(
            "La tarjeta de tarea debe obtener el controlador de devolución y reutilizar handleCardAction (no es necesario crear un segundo conjunto).",
            taskCardCall.contains("onCardAction = handleCardAction"),
        )
    }

    @Test
    fun `message bubble forwards its task card bridge down to the task card`() {
        val bubble = readUi("MessageBubble.kt")
        val taskCardCall = bubble.substringAfter("io.zer0.muse.ui.taskcard.TaskCard(")
            .substringBefore(") else if (")
        assertTrue("La tarjeta de tarea debe recibir un controlador de devolución de llamada de la dimensión messageId", taskCardCall.contains("onCardAction = taskCardCardAction"))
        assertTrue(
            "Si no se pasa el procesador de la dimensión messageId, debe recurrir al procesador de este mensaje. No puede haber silencio sin puente.",
            bubble.contains("onTaskCardCardAction ?: onCardAction?.let"),
        )
    }

    // ── 2. El ID de tarjeta de la tarjeta de producto de la tarjeta de tarea debe ser el ID del mensaje, no toolCallId ──────────

    @Test
    fun `task card step output uses the message id as card identity`() {
        val source = readUi("taskcard/TaskCard.kt") + readUi("taskcard/TaskStepRow.kt")

        assertFalse(
            "toolCallId Hay dos espacios de identificación con la identificación del mensaje: usarlo como cardId evitará que la tarjeta lea sus propios datos y guarde mensajes de error de la pieza de trabajo.",
            source.contains("cardId = step.toolCallId"),
        )
        val taskCardFn = source.substringAfter("fun TaskCard(")
            .substringBefore("/** insignia del escenario")
        assertTrue("La línea de paso debe recibir el mensaje. id", taskCardFn.contains("cardId = data.id"))

        val stepRow = source.substringAfter("fun TaskStepRow(")
        assertTrue("La tarjeta de producto de la línea de paso debe utilizar el cardId pasado (ID de mensaje)", stepRow.contains("cardId = cardId"))
    }

    // ── 3. javascriptEnabled No dependas más de los puentes ────────────────────────────────

    @Test
    fun `rich content card keeps javascript enabled regardless of the bridge`() {
        // v2.6.12: El renderizado ha convergido en Markdown/card/CardRuntime,JS La inyección de interruptor y puente está ahí.
        // Las afirmaciones negativas se ejecutan en ambos archivos: volver a conectar el puente en cualquier lugar dará como resultado la misma devolución.
        val legacy = readUi("markdown/RichContentCard.kt").codeOnly()
        val runtime = readUi("markdown/card/CardRuntime.kt").codeOnly()
        for (source in listOf(legacy, runtime)) {
            assertFalse(
                "JS El puente de dependencia es la causa directa de que "todos los controles mueren": el enlace de eventos nunca se ejecuta cuando no hay devolución de llamada." +
                    "Una pulsación larga también degrada a la selección de palabras predeterminada de WebView",
                source.contains("javaScriptEnabled = bridge != null"),
            )
            assertFalse(
                "La primera prueba de altura no debe basarse en el puente, de lo contrario se cortará la tarjeta sin puente.",
                source.contains("onPageFinished = if (bridge != null)"),
            )
        }
        // El único punto de representación es CardRuntime:JS Habilitado incondicionalmente, el puente sólo se inyecta cuando puede regresar.
        assertTrue("La representación de tarjetas siempre debe estar habilitada JS", runtime.contains("javaScriptEnabled = true"))
        assertTrue(
            "El puente todavía solo inyecta cuando hay un retorno (el límite de seguridad no cambia)",
            runtime.contains("bridge?.let { webView.addJavascriptInterface"),
        )
    }

    // ── 4. show_card Es un entregable y no es absorbido por la tarjeta de tareas. ────────────────────────────

    @Test
    fun `show card is classified as an inline deliverable`() {
        assertTrue(isInlineDeliverableTool(SHOW_CARD_TOOL_NAME))
        assertFalse(isInlineDeliverableTool("render_data"))
        assertFalse(isInlineDeliverableTool("generate_image"))
        assertFalse(isInlineDeliverableTool(null))
    }

    @Test
    fun `inline deliverable ids collect only show card tool messages`() {
        val owner = UIMessage(role = MessageRole.ASSISTANT, content = "")
        val card = toolMessage(owner, "show_card")
        val image = toolMessage(owner, "generate_image")
        val plain = UIMessage(role = MessageRole.ASSISTANT, content = "Responder")

        val ids = inlineDeliverableMessageIds(listOf(owner, card, image, plain))

        assertEquals(setOf(card.id.toString()), ids)
    }

    @Test
    fun `chat screen keeps show card out of the task card dedupe set`() {
        val screen = readUi("ChatScreen.kt")
        val dedupe = screen.substringAfter("val cardRenderedByTaskCard by remember(")
            .substringBefore("val groupedRuns by remember(")
        assertTrue(
            "El entregable debe eliminarse de la colección "renderizado por tarjeta de tarea"; de lo contrario, la tarjeta se ocultará." +
                "Los usuarios sólo pueden ver el plan de ejecución cuando lo expanden.",
            dedupe.contains("inlineDeliverableMessageIds(visibleMessages)"),
        )
        assertTrue(dedupe.contains("covered - "))
    }

    @Test
    fun `show card is never folded into a grouped tool run card`() {
        val screen = readUi("ChatScreen.kt")
        val grouping = screen.substringAfter("ChatDisplayGrouper.group(visibleMessages)")
            .substringBefore(".filterIsInstance")
        assertTrue(
            "El juego de cartas está plegado de forma predeterminada. Poner los entregables equivale a esconder las cartas nuevamente.",
            grouping.contains("!io.zer0.muse.ui.isInlineDeliverableTool(msg.toolCallInfo?.toolName)"),
        )
    }

    @Test
    fun `task card renders only a reference line for a generated card`() {
        // v2.6.8 Dividir: la familia de filas de pasos se ha movido a TaskStepRow.kt (TaskCard.kt Se ha superado el límite superior del número de funciones).
        // El contenido de la afirmación permanece sin cambios, pero el puntero sigue al código.
        val source = readUi("taskcard/TaskStepRow.kt")
        val stepRow = source.substringAfter("fun TaskStepRow(")
        assertTrue(
            "Sólo queda una línea de referencia "Tarjeta generada" en la tarjeta de tarea.",
            source.contains("isInlineDeliverableTool(step.toolName)"),
        )
        assertTrue(
            "Cita la redacción y sigue los recursos, no el chino codificado.",
            source.contains("stringResource(R.string.task_card_card_generated)"),
        )
        assertTrue("Este juicio debe estar en el área de resultados del paso.", stepRow.isNotEmpty())
    }

    @Test
    fun `generated card reference string exists in all shipped locales`() {
        val locales = listOf(
            "values",
            "values-en",
            "values-ja",
            "values-ko",
            "values-ru",
            "values-es",
            "values-pt-rBR",
        )
        locales.forEach { locale ->
            val path = Path.of("app/src/main/res/$locale/strings_features.xml")
            val candidates = listOf(path, Path.of("src/main/res/$locale/strings_features.xml"))
            val file = candidates.firstOrNull(Files::exists)
                ?: error("missing resource file for $locale")
            val text = Files.newBufferedReader(file).use { it.readText() }
            assertTrue(
                "$locale Falta task_card_card_generated (se deben sincronizar siete idiomas)",
                text.contains("name=\"task_card_card_generated\""),
            )
        }
    }

    // ── auxiliar ────────────────────────────────────────────────────────────

    private fun toolMessage(owner: UIMessage, toolName: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        parentMessageId = owner.id.toString(),
        toolCallInfo = ToolCallInfo(
            toolName = toolName,
            arguments = """{"title":"t","code":"<div/>"}""",
            result = "ok",
            isSuccess = true,
        ),
    )

    private fun readUi(relative: String): String {
        val candidates = listOf(
            Path.of("src/main/java/io/zer0/muse/ui/$relative"),
            Path.of("app/src/main/java/io/zer0/muse/ui/$relative"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate ui/$relative from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }

    /** tirar a la basura `//` Línea de comentarios para evitar ser juzgado erróneamente como un éxito cuando se menciona la implementación anterior en los comentarios. */
    private fun String.codeOnly(): String = lineSequence()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")
}
