package io.zer0.muse.transformer

import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TransformerPipeline Pruebas unitarias ejecutoras de tuberías.
 *
 * Concatenación de canalizaciones de prueba, canalizaciones vacías, comportamiento tolerante a fallos,y tres ganchos(execute/visualTransform/onGenerationFinish)
 * Semántica de reinicio de cobertura y excepción(H-PIPE1 CancellationException / M-PIPE2 Error).
 */
class TransformerPipelineTest {

    // A transformer that prepends a fixed string
    private class PrependTransformer(private val prefix: String) : Transformer {
        override val name: String = "Prepend($prefix)"
        override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> =
            messages.map { it.copy(content = "$prefix${it.content}") }
    }

    // con un sufijo empalmado al final Transformer
    private class AppendTransformer(private val suffix: String) : Transformer {
        override val name: String = "Append($suffix)"
        override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> =
            messages.map { it.copy(content = "${it.content}$suffix") }
    }

    // A transformer that always throws (test fault tolerance)
    private class FailingTransformer : Transformer {
        override val name: String = "Failing"
        override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> =
            throw RuntimeException("La simulación falló")
    }

    /**
     * Subclase CancellationException personalizada,Se utiliza para verificar que se vuelve a lanzar la excepción de cancelación de rutina.(H-PIPE1).
     *
     * Utilice subclases en lugar de JobCancellationException,Evite enredarse con el estado interno del trabajo de runTest.
     * Mensaje de etiqueta única para facilitar la identificación de la afirmación.
     */
    private class TestCancellationException(message: String) :
        CancellationException(message)

    /** Lanzar excepción de cancelación Transformer,Se utiliza para verificar la semántica de reinicio de H-PIPE1. */
    private class CancellingTransformer : Transformer {
        override val name: String = "Cancelling"
        override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> =
            throw TestCancellationException("cancel-from-test")
    }

    /** Lanzar OutOfMemoryError Transformer,Se utiliza para verificar la semántica de reinicio de M-PIPE2. */
    private class ErrorTransformer : Transformer {
        override val name: String = "Error"
        override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> =
            throw OutOfMemoryError("simulated-oom")
    }

    // Agregar al contenido en la etapa visualTransform [V] marcado Transformer
    private class VisualMarkTransformer : Transformer {
        override val name: String = "VisualMark"
        override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> = messages // transform El escenario no cambia

        override suspend fun visualTransform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> =
            messages.map { it.copy(content = "[V]${it.content}") }
    }

    // Agregar contenido en la etapa onGenerationFinish [F] marcado Transformer
    private class FinishMarkTransformer : Transformer {
        override val name: String = "FinishMark"
        override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> = messages // transform El escenario no cambia

        override suspend fun onGenerationFinish(messages: List<UIMessage>, context: TransformContext): List<UIMessage> =
            messages.map { it.copy(content = "${it.content}[F]") }
    }

    // Lanzar RuntimeException en la etapa visualTransform Transformer
    private class VisualFailingTransformer : Transformer {
        override val name: String = "VisualFailing"
        override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> = messages

        override suspend fun visualTransform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> =
            throw RuntimeException("visual-fail")
    }

    // Lanzar RuntimeException en la etapa onGenerationFinish Transformer
    private class FinishFailingTransformer : Transformer {
        override val name: String = "FinishFailing"
        override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> = messages

        override suspend fun onGenerationFinish(messages: List<UIMessage>, context: TransformContext): List<UIMessage> =
            throw RuntimeException("finish-fail")
    }

    @Test
    fun `pipeline executes transformers in order`() = runTest {
        val pipeline = TransformerPipeline(
            listOf(
                PrependTransformer("A"),
                AppendTransformer("B"),
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "X",
        )
        val result = pipeline.execute(listOf(msg), TransformContext())

        assertEquals(1, result.size)
        assertEquals("AXB", result[0].content)
    }

    @Test
    fun `first transformer output is second transformer input`() = runTest {
        val pipeline = TransformerPipeline(
            listOf(
                PrependTransformer("P1:"),
                PrependTransformer("P2:"),
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "hello",
        )
        val result = pipeline.execute(listOf(msg), TransformContext())

        assertEquals("P2:P1:hello", result[0].content)
    }

    @Test
    fun `empty pipeline returns original messages`() = runTest {
        val pipeline = TransformerPipeline(emptyList())
        val msgs = listOf(
            io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "Hola"),
        )
        val result = pipeline.execute(msgs, TransformContext())

        assertEquals(msgs, result)
    }

    @Test
    fun `failing transformer keeps previous result`() = runTest {
        val pipeline = TransformerPipeline(
            listOf(
                PrependTransformer("OK-"),
                FailingTransformer(),
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "test",
        )
        val result = pipeline.execute(listOf(msg), TransformContext())

        assertEquals(1, result.size)
        // FailingTransformer saltado,Preservar la salida de PrependTransformer
        assertEquals("OK-test", result[0].content)
    }

    @Test
    fun `builder pattern works`() = runTest {
        val pipeline = TransformerPipeline.Builder()
            .add(PrependTransformer("B1:"))
            .add(AppendTransformer(":B2"))
            .build()

        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "x",
        )
        val result = pipeline.execute(listOf(msg), TransformContext())

        assertEquals("B1:x:B2", result[0].content)
    }

    // ── Phase 2.3.6 suplemento:applyVisualTransform / applyOnGenerationFinish gancho ────

    @Test
    fun `applyVisualTransform chains visual hooks in order`() = runTest {
        val pipeline = TransformerPipeline(
            listOf(
                VisualMarkTransformer(),
                object : Transformer {
                    override val name: String = "VisualSuffix"
                    override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                    override suspend fun visualTransform(messages: List<UIMessage>, context: TransformContext) =
                        messages.map { it.copy(content = "${it.content}[V2]") }
                },
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "streaming",
        )
        val result = pipeline.applyVisualTransform(listOf(msg), TransformContext())

        // cadena:VisualMark agregar [V] prefijo → VisualSuffix agregar [V2] sufijo
        assertEquals("[V]streaming[V2]", result[0].content)
    }

    @Test
    fun `applyVisualTransform with default hooks returns original list`() = runTest {
        // La implementación predeterminada de visualTransform lo devuelve tal cual,Las tuberías deben ser equivalentes a no-op
        val pipeline = TransformerPipeline(
            listOf(
                object : Transformer {
                    override val name: String = "DefaultVisual"
                    override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                    // visualTransform Usar implementación predeterminada
                },
            ),
        )
        val msgs = listOf(
            io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "hi"),
        )
        val result = pipeline.applyVisualTransform(msgs, TransformContext())

        // El visualTransform predeterminado regresa sin cambios,debería obtener una lista equivalente(Puede que no sea la misma referencia.,Pero el contenido es el mismo.)
        assertEquals(1, result.size)
        assertEquals("hi", result[0].content)
    }

    @Test
    fun `applyVisualTransform empty pipeline returns original messages`() = runTest {
        val pipeline = TransformerPipeline(emptyList())
        val msgs = listOf(
            io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "x"),
        )
        val result = pipeline.applyVisualTransform(msgs, TransformContext())
        // L-PIPE3: Una tubería vacía devuelve la lista original por adelantado
        assertSame("Una tubería vacía debería devolver la misma referencia.(L-PIPE3 Optimizar)", msgs, result)
    }

    @Test
    fun `applyVisualTransform tolerates single transformer failure`() = runTest {
        val pipeline = TransformerPipeline(
            listOf(
                VisualMarkTransformer(),
                VisualFailingTransformer(),
                object : Transformer {
                    override val name: String = "AfterFail"
                    override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                    override suspend fun visualTransform(messages: List<UIMessage>, context: TransformContext) =
                        messages.map { it.copy(content = "${it.content}-after") }
                },
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "text",
        )
        val result = pipeline.applyVisualTransform(listOf(msg), TransformContext())

        // VisualFailingTransformer Se salta el fracaso,Mantener el resultado del paso anterior;AfterFail posterior continúa procesándose
        assertEquals("[V]text-after", result[0].content)
    }

    @Test
    fun `applyOnGenerationFinish chains finish hooks in order`() = runTest {
        val pipeline = TransformerPipeline(
            listOf(
                FinishMarkTransformer(),
                object : Transformer {
                    override val name: String = "FinishPrefix"
                    override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                    override suspend fun onGenerationFinish(messages: List<UIMessage>, context: TransformContext) =
                        messages.map { it.copy(content = "[F2]${it.content}") }
                },
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "final",
        )
        val result = pipeline.applyOnGenerationFinish(listOf(msg), TransformContext())

        // cadena:FinishMark agregar [F] sufijo → FinishPrefix agregar [F2] prefijo
        assertEquals("[F2]final[F]", result[0].content)
    }

    @Test
    fun `applyOnGenerationFinish with default hooks returns original list`() = runTest {
        val pipeline = TransformerPipeline(
            listOf(
                object : Transformer {
                    override val name: String = "DefaultFinish"
                    override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                    // onGenerationFinish Usar implementación predeterminada
                },
            ),
        )
        val msgs = listOf(
            io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "hi"),
        )
        val result = pipeline.applyOnGenerationFinish(msgs, TransformContext())

        assertEquals(1, result.size)
        assertEquals("hi", result[0].content)
    }

    @Test
    fun `applyOnGenerationFinish empty pipeline returns original messages`() = runTest {
        val pipeline = TransformerPipeline(emptyList())
        val msgs = listOf(
            io.zer0.ai.core.UIMessage(role = io.zer0.ai.core.MessageRole.USER, content = "x"),
        )
        val result = pipeline.applyOnGenerationFinish(msgs, TransformContext())
        assertSame("Una tubería vacía debería devolver la misma referencia.(L-PIPE3 Optimizar)", msgs, result)
    }

    @Test
    fun `applyOnGenerationFinish tolerates single transformer failure`() = runTest {
        val pipeline = TransformerPipeline(
            listOf(
                FinishMarkTransformer(),
                FinishFailingTransformer(),
                object : Transformer {
                    override val name: String = "AfterFail"
                    override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                    override suspend fun onGenerationFinish(messages: List<UIMessage>, context: TransformContext) =
                        messages.map { it.copy(content = "${it.content}-after") }
                },
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "text",
        )
        val result = pipeline.applyOnGenerationFinish(listOf(msg), TransformContext())

        // FinishFailingTransformer Se salta el fracaso,AfterFail posterior continúa procesándose
        assertEquals("text[F]-after", result[0].content)
    }

    // ── Phase 2.3.6 suplemento:Semántica de reinicio de excepciones(H-PIPE1 / M-PIPE2) ────────────────

    @Test
    fun `execute rethrows CancellationException instead of swallowing`() = kotlinx.coroutines.runBlocking {
        // H-PIPE1: Se debe volver a lanzar la excepción de cancelación de rutina,no tragar
        val pipeline = TransformerPipeline(
            listOf(
                PrependTransformer("before-"),
                CancellingTransformer(),
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "x",
        )
        var caught: TestCancellationException? = null
        try {
            pipeline.execute(listOf(msg), TransformContext())
        } catch (e: TestCancellationException) {
            caught = e
        }
        assertTrue("debe ser capturado TestCancellationException(re-lanzado,No tragado)", caught != null)
        assertEquals("cancel-from-test", caught!!.message)
    }

    @Test
    fun `execute rethrows Error instead of swallowing`() = kotlinx.coroutines.runBlocking {
        // M-PIPE2: OOM/StackOverflow etc. El error no es una excepción recuperable.,Vuelve a lanzar y deja que el nivel superior se encargue de ello.
        val pipeline = TransformerPipeline(
            listOf(
                PrependTransformer("before-"),
                ErrorTransformer(),
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "x",
        )
        var caught: OutOfMemoryError? = null
        try {
            pipeline.execute(listOf(msg), TransformContext())
        } catch (e: OutOfMemoryError) {
            caught = e
        }
        assertTrue("debe ser capturado OutOfMemoryError(Error re-lanzado,No tragado)", caught != null)
        assertEquals("simulated-oom", caught!!.message)
    }

    @Test
    fun `applyVisualTransform rethrows CancellationException`() = kotlinx.coroutines.runBlocking {
        // H-PIPE1: visualTransform También hay que volver a lanzar etapas CancellationException
        val pipeline = TransformerPipeline(
            listOf(
                object : Transformer {
                    override val name: String = "VisualCancelling"
                    override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                    override suspend fun visualTransform(messages: List<UIMessage>, context: TransformContext) =
                        throw TestCancellationException("visual-cancel")
                },
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "x",
        )
        var caught: TestCancellationException? = null
        try {
            pipeline.applyVisualTransform(listOf(msg), TransformContext())
        } catch (e: TestCancellationException) {
            caught = e
        }
        assertTrue("visualTransform Habría que volver a lanzar el escenario. CancellationException", caught != null)
        assertEquals("visual-cancel", caught!!.message)
    }

    @Test
    fun `applyOnGenerationFinish rethrows CancellationException`() = kotlinx.coroutines.runBlocking {
        // H-PIPE1: onGenerationFinish También hay que volver a lanzar etapas CancellationException
        val pipeline = TransformerPipeline(
            listOf(
                object : Transformer {
                    override val name: String = "FinishCancelling"
                    override suspend fun transform(messages: List<UIMessage>, context: TransformContext) = messages
                    override suspend fun onGenerationFinish(messages: List<UIMessage>, context: TransformContext) =
                        throw TestCancellationException("finish-cancel")
                },
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.ASSISTANT,
            content = "x",
        )
        var caught: TestCancellationException? = null
        try {
            pipeline.applyOnGenerationFinish(listOf(msg), TransformContext())
        } catch (e: TestCancellationException) {
            caught = e
        }
        assertTrue("onGenerationFinish Habría que volver a lanzar el escenario. CancellationException", caught != null)
        assertEquals("finish-cancel", caught!!.message)
    }

    @Test
    fun `execute continues after non-fatal exception in middle transformer`() = runTest {
        // verificar:Tiro del transformador medio RuntimeException,Los transformadores posteriores continuarán ejecutándose.
        // (estrategia de tolerancia a fallos:Saltar pasos fallidos,Mantener el resultado del paso anterior,Continuar con el seguimiento)
        val pipeline = TransformerPipeline(
            listOf(
                PrependTransformer("A-"),
                FailingTransformer(), // saltar
                AppendTransformer("-Z"),
            ),
        )
        val msg = io.zer0.ai.core.UIMessage(
            role = io.zer0.ai.core.MessageRole.USER,
            content = "x",
        )
        val result = pipeline.execute(listOf(msg), TransformContext())

        assertEquals(1, result.size)
        // Failing saltado,current Reservar "A-x";AppendTransformer Continuar agregando -Z
        assertEquals("A-x-Z", result[0].content)
    }
}
