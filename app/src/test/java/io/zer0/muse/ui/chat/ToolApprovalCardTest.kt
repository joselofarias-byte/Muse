package io.zer0.muse.ui.chat

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.AppJson
import io.zer0.muse.R
import io.zer0.muse.tools.toolArgsToMap
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * I-AUDIT(P1-2/I-P2-2): Pruebas semánticas de tarjetas de aprobación de herramientas.
 *
 * Anteriormente, solo había una prueba de Compose UI en todo el proyecto: TokenStatsBarTest.,tarjeta de aprobación onApprove/onDeny
 * Los errores de cableado no se pueden detectar en CI. Esta prueba bloquea dos rutas críticas de seguridad.:La aprobación se activa al aprobar,
 * Denegar requiere una segunda confirmación para activar onDeny.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolApprovalCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun approveButton_triggersOnApproveOnce() {
        var approved = 0
        var denied: String? = null
        composeTestRule.setContent {
            MaterialTheme {
                ToolApprovalCard(
                    toolName = "web_search",
                    argumentsPreview = "{\"q\":\"weather\"}",
                    onApprove = { approved++ },
                    onDeny = { denied = it },
                )
            }
        }
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_approve)).performClick()
        assertTrue("Al hacer clic en aprobar debería activarse onApprove", approved == 1)
        assertNull("La aprobación no debería desencadenar onDeny", denied)
    }

    @Test
    fun denyButton_requiresConfirmationThenTriggersOnDeny() {
        var approved = false
        var denied = 0
        composeTestRule.setContent {
            MaterialTheme {
                ToolApprovalCard(
                    toolName = "web_search",
                    argumentsPreview = "{\"q\":\"weather\"}",
                    onApprove = { approved = true },
                    onDeny = { denied++ },
                )
            }
        }
        // Primera vez haciendo clic"rechazar"Simplemente expanda el cuadro de entrada del motivo,Sin devolución de llamada inmediata onDeny
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_deny)).performClick()
        assertFalse("Hacer clic en rechazar por primera vez no debería activar onDeny", denied > 0)
        // Segundo clic"Confirmar rechazo"solo devolución de llamada onDeny
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_confirm_deny)).performClick()
        assertTrue("Confirmar que el rechazo debería activarse onDeny", denied == 1)
        assertFalse("El rechazo no debería desencadenar onApprove", approved)
    }

    /**
     * Parámetros de gran volumen:El estado colapsado debe indicarle al usuario "cuántos caracteres quedan sin ver",Después de desplegarlo, podrá obtener el texto al final.
     *
     * Esta es la escena de un accidente real.:4KB Solicitud de escritura de texto,Las cartas solo muestran el comienzo.,El usuario pensó que lo había visto claramente.
     */
    @Test
    fun longArguments_showHiddenCountThenExpandToFullContent() {
        val body = "A".repeat(3_700) + "TAIL_MARKER"
        val fullArgs = mapOf("path" to "notes/long.md", "content" to body)
        composeTestRule.setContent {
            MaterialTheme {
                ToolApprovalCard(
                    toolName = "write_file",
                    argumentsPreview = "{\"path\":\"notes/long.md\",\"content\":\"AAA…",
                    argumentsFull = fullArgs,
                    onApprove = {},
                    onDeny = {},
                )
            }
        }
        // estado plegado:Se debe escribir claramente fuera de la vista previa cuántos caracteres se omiten.
        val expectedNote = context.getString(R.string.tool_approval_args_hidden, body.length - 300)
        composeTestRule.onNodeWithText(expectedNote, substring = true).assertExists()
        // El resumen sólo es válido si todos los parámetros están presentes(No se pudo analizar la vista previa de 200 caracteres anteriores,Sólo queda la cadena de escape original para los parámetros de caracteres grandes.)
        composeTestRule.onNodeWithText(
            context.getString(R.string.tool_approval_write_summary, "long.md", body.length),
        ).assertExists()
        // La etiqueta final no es visible en el estado contraído(Lo que ve el usuario es la vista previa.)
        composeTestRule.onNodeWithText("TAIL_MARKER", substring = true).assertDoesNotExist()

        // Haga clic para ampliar y ver el texto completo.
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_show_full_args), substring = true)
            .performClick()
        composeTestRule.onNodeWithText(body).assertExists()
        composeTestRule.onNodeWithText("TAIL_MARKER", substring = true).assertExists()
        // Después de la expansión, el mensaje cambia para contraerse.,Ya no dice "hay caracteres que no se muestran"
        composeTestRule.onNodeWithText(expectedNote, substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_hide_full_args)).assertExists()
    }

    /**
     * El texto que vea después de la expansión debe ser coherente palabra por palabra con el valor realmente obtenido por la herramienta.
     *
     * Los parámetros son texto JSON en la capa de transporte.(content Escritura de salto de línea en `\n` escapar),Antes de la ejecución por [toolArgsToMap]
     * Decodificado a verdadera nueva línea. Si la tarjeta muestra directamente el original JSON,Lo que ve el usuario es `\n` literal —— Eso es igual a
     * "Miró,Pero no miran lo mismo". Este caso de uso está bloqueado:La tarjeta se expande para mostrar el valor decodificado.
     */
    @Test
    fun expandedContent_matchesTheValueTheToolExecutes() {
        val rawJson = "{\"path\":\"notes/long.md\",\"content\":\"primera linea\\nsegunda linea\\n" +
            "B".repeat(400) + "\\nEND_MARKER\"}"
        // El mismo conjunto de análisis que el eslabón de producción.: Lo que obtiene la capa de ejecución es la cadena decodificada.
        val executed = toolArgsToMap(AppJson.decodeFromString(JsonObject.serializer(), rawJson))
        val executedContent = executed["content"] as String
        assertTrue("El valor de ejecución debe ser verdadera nueva línea.", executedContent.contains("\n"))
        assertFalse("No debe haber barras invertidas literales en los valores de ejecución.", executedContent.contains("\\n"))

        composeTestRule.setContent {
            MaterialTheme {
                ToolApprovalCard(
                    toolName = "write_file",
                    argumentsPreview = rawJson.take(200),
                    argumentsFull = executed,
                    onApprove = {},
                    onDeny = {},
                )
            }
        }
        composeTestRule.onNodeWithText(context.getString(R.string.tool_approval_show_full_args), substring = true)
            .performClick()

        // Expandir para mostrar el valor de ejecución palabra por palabra,En lugar de formato JSON escapado
        composeTestRule.onNodeWithText(executedContent).assertExists()
        composeTestRule.onNodeWithText("primera linea\\nsegunda linea", substring = true).assertDoesNotExist()
    }

    @Test
    fun hiddenCharacters_countsOnlyWhatIsNotShown() {
        assertEquals("El número restante de caracteres debe informarse al truncar", 3_411, hiddenCharacters(3_711, 300))
        assertEquals("El número no debe informarse cuando no hay truncamiento.", 0, hiddenCharacters(120, 120))
        assertEquals("La longitud de la impresión no debe ser negativa si excede la cantidad total", 0, hiddenCharacters(80, 200))
    }

    @Test
    fun approvalArgumentEntries_prefersFullArguments() {
        val entries = approvalArgumentEntries(
            preview = "{\"path\":\"a.md\",\"content\":\"tru",
            full = mapOf("path" to "a.md", "content" to "truncated-not-this"),
        )
        assertEquals(listOf("path" to "a.md", "content" to "truncated-not-this"), entries)
    }

    @Test
    fun approvalArgumentEntries_fallsBackToParsablePreview() {
        assertEquals(
            listOf("q" to "weather"),
            approvalArgumentEntries(preview = "{\"q\":\"weather\"}", full = emptyMap()),
        )
        // Cuando la vista previa se trunca en JSON ilegal sin parámetros completos,Los artículos no deben crearse de la nada
        assertTrue(approvalArgumentEntries(preview = "{\"q\":\"wea", full = emptyMap()).isEmpty())
    }

    @Test
    fun approvalValueOf_and_extraArguments_skipSummaryKeys() {
        val entries = listOf("path" to "a.md", "content" to "body", "encoding" to "utf-8")
        assertEquals("a.md", approvalValueOf(entries, listOf("path", "file_path")))
        assertEquals("body", approvalValueOf(entries, listOf("content", "text")))
        assertEquals(listOf("encoding" to "utf-8"), approvalExtraArguments(entries))
    }

    @Test
    fun approvalRawJson_prettyPrintsEveryKey() {
        val raw = approvalRawJson(listOf("path" to "a.md", "content" to "x"))
        assertTrue("Todo debe ser retenido key", raw != null && raw.contains("\"path\"") && raw.contains("\"content\""))
        assertNull("No lo recuperes cuando no haya entradas. JSON", approvalRawJson(emptyList()))
    }
}
