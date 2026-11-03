package io.zer0.memory.deep

import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.summary.SessionSummaryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2.3: [DeepMemoryProcessor.parseFactExtractionResult] Pruebas unitarias.
 *
 * Verificar la lógica de análisis tolerante a fallas de la salida LLM:
 *  - Matriz JSON pura → Analizar correctamente
 *  - traer ```json valla → Pelado automático
 *  - con bloque de pensamiento → Pelado automático
 *  - JSON Insertar texto → Extracción de máquina de estado profundo con soportes
 *  - cadena vacía/Ninguno JSON → Devolver lista vacía(No lanzar excepción)
 *  - Falta campo → Usar valor predeterminado
 *  - importance cruzar la linea → coerceIn(0, 2)
 *
 * Nota: Esta prueba sólo verifica [parseFactExtractionResult] Limpieza de cadenas + lógica de análisis JSON,LLM no se llama.
 * Proporcionar un contexto con Robolectric para construir FactDbProvider(Esta prueba no llama a ningún método de FactDb).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeepMemoryProcessorParseTest {

    private lateinit var processor: DeepMemoryProcessor

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val factDbProvider = FactDbProvider(context)
        // Phase 2.3: Corrección fake —— MemoryLlmClient Solo la interfaz callText, no existe complete/streamChat
        val fakeLlmClient = object : MemoryLlmClient {
            override suspend fun callText(
                systemPrompt: String,
                userContent: String,
                model: Model?,
                temperature: Float,
                maxTokens: Int,
                timeoutMs: Long,
            ): String = error("Esta prueba no debe llamar LLM")
        }
        processor = DeepMemoryProcessor(factDbProvider, fakeLlmClient)
    }

    @Test
    fun `empty input returns empty list`() {
        assertTrue(processor.parseFactExtractionResult("", sampleSummary(), "zh-CN")!!.isEmpty())
    }

    @Test
    fun `blank input returns empty list`() {
        assertTrue(processor.parseFactExtractionResult("   \n\t  ", sampleSummary(), "zh-CN")!!.isEmpty())
    }

    @Test
    fun `pure json array is parsed`() {
        val raw = """[{"fact":"A los usuarios les encanta el modo oscuro","tags":["preference"],"importance":1,"category":"preference"}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("A los usuarios les encanta el modo oscuro", result[0].fact)
        assertEquals(listOf("preference"), result[0].tags)
        assertEquals(1, result[0].importance)
        assertEquals("preference", result[0].category)
    }

    @Test
    fun `json fence is stripped`() {
        val raw = """
            Here is the extracted facts:
            ```json
            [{"fact":"cumpleaños: 1990-05-20","tags":["identity"],"importance":2}]
            ```
        """.trimIndent()
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("cumpleaños: 1990-05-20", result[0].fact)
        assertEquals(2, result[0].importance)
    }

    @Test
    fun `thinking block is stripped`() {
        val raw = """
            <thinking>
            Let me analyze the conversation...
            </thinking>
            [{"fact":"Preferencias mencionadas por el usuario","tags":[],"importance":0}]
        """.trimIndent()
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("Preferencias mencionadas por el usuario", result[0].fact)
    }

    @Test
    fun `json embedded in text is extracted via bracket depth state machine`() {
        val raw = """
            Análisis completado,Extraiga los siguientes hechos:
            Result: [{"fact":"a los usuarios les gusta Kotlin","tags":["preference"],"importance":1}]
            Done.
        """.trimIndent()
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("a los usuarios les gusta Kotlin", result[0].fact)
    }

    @Test
    fun `invalid json returns null without throwing`() {
        val raw = """```json
            [{ this is not valid json ]
            ```"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertNull("JSON El error de análisis debería regresar null(Reintento del disparador)en lugar de una lista vacía", result)
    }

    @Test
    fun `missing fields use defaults`() {
        val raw = """[{"fact":"Minimizar campo"}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        val fact = result[0]
        assertEquals("Minimizar campo", fact.fact)
        assertEquals(emptyList<String>(), fact.tags)
        assertEquals(0, fact.importance)
        assertEquals("general", fact.category)
        assertEquals("inferred", fact.source)
        assertEquals(0.7f, fact.confidence, 0.001f)
    }

    @Test
    fun `user_explicit source gets higher default confidence`() {
        val raw = """[{"fact":"Los usuarios están claramente informados.","source":"user_explicit"}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals(1.0f, result[0].confidence, 0.001f)
    }

    @Test
    fun `importance is clamped to 0-2 range`() {
        val raw = """[
            {"fact":"demasiado bajo importance","importance":-5},
            {"fact":"demasiado alto importance","importance":99}
        ]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(2, result.size)
        assertEquals("Los valores negativos deberían obligarnos a 0", 0, result[0].importance)
        assertEquals("Más de 2 deben obligar a 2", 2, result[1].importance)
    }

    @Test
    fun `sessionId is propagated from summary`() {
        val raw = """[{"fact":"ID de sesión de prueba aprobado"}]"""
        val summary = sampleSummary(sessionId = "session-xyz-123")
        val result = processor.parseFactExtractionResult(raw, summary, "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("sessionId Debe pasarse transparentemente del resumen al Fact", "session-xyz-123", result[0].sessionId)
    }

    @Test
    fun `multiple facts are parsed in order`() {
        val raw = """[
            {"fact":"hechos A","importance":0},
            {"fact":"hechos B","importance":1},
            {"fact":"hechos C","importance":2,"category":"identity"}
        ]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(3, result.size)
        assertEquals("hechos A", result[0].fact)
        assertEquals("hechos B", result[1].fact)
        assertEquals("hechos C", result[2].fact)
        assertEquals("general", result[0].category)
        assertEquals("identity", result[2].category)
    }

    // ── Phase 2.3.7 suplemento:límites más finos case ─────────────────────────────

    @Test
    fun `confidence is clamped to 0-1 range when explicitly provided`() {
        // confidence Cuando se proporciona explícitamente pero fuera de los límites,debería ser coerceIn(0f, 1f) Truncar
        val raw = """[
            {"fact":"demasiado alto confidence","confidence":1.5},
            {"fact":"demasiado bajo confidence","confidence":-0.3},
            {"fact":"normales confidence","confidence":0.85}
        ]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(3, result.size)
        assertEquals("1.5 debe ser cortado 1.0", 1.0f, result[0].confidence, 0.001f)
        assertEquals("-0.3 debe ser cortado 0.0", 0.0f, result[1].confidence, 0.001f)
        assertEquals("0.85 Se debe conservar el valor original.", 0.85f, result[2].confidence, 0.001f)
    }

    @Test
    fun `blank category falls back to general`() {
        // category Al proporcionar explícitamente una cadena vacía,Debería volver al valor predeterminado "general"
        val raw = """[{"fact":"vacío category","category":""}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("La categoría de cadena vacía debería volver a ser general", "general", result[0].category)
    }

    @Test
    fun `blank source falls back to inferred`() {
        // source Al proporcionar explícitamente una cadena vacía,Debería volver al valor predeterminado "inferred"
        val raw = """[{"fact":"vacío source","source":""}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("Una fuente de cadena vacía debería recurrir a inferred", "inferred", result[0].source)
        // La fuente vacía no debería activar user_explicit 1.0 confidence,más bien 0.7
        assertEquals("Una fuente vacía debería recurrir al valor predeterminado inferido confidence", 0.7f, result[0].confidence, 0.001f)
    }

    @Test
    fun `tags default to empty list when not provided`() {
        // tags Lista vacía esperada si falta el campo(DTO Valor predeterminado)
        val raw = """[{"fact":"Sin campo de etiquetas"}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("El campo de etiquetas que faltan debe ser una lista vacía", emptyList<String>(), result[0].tags)
    }

    @Test
    fun `expiresAt and lastConfirmedAt are propagated when provided`() {
        // Estos dos campos que aceptan valores NULL deben pasarse tal como están cuando se proporcionan.
        val raw = """[{
            "fact":"hechos urgentes",
            "expiresAt":"2027-12-31T23:59:59Z",
            "lastConfirmedAt":"2026-07-28T10:00:00Z"
        }]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("2027-12-31T23:59:59Z", result[0].expiresAt)
        assertEquals("2026-07-28T10:00:00Z", result[0].lastConfirmedAt)
    }

    @Test
    fun `text with no opening bracket returns null`() {
        // Ninguno [ carácter findJsonArrayCandidate devuelve null → parseFactExtractionResult Regresar null
        val raw = """Este es un texto sin ninguna matriz JSON.,Sólo texto plano."""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertNull("Ninguno [ Se debe devolver el texto del personaje. null(El análisis falló)", result)
    }

    @Test
    fun `unterminated json array returns null`() {
        // La máquina de estado de profundidad del soporte aún no se cierra después de escanear hasta el final. → Regresar null
        val raw = """Analizar resultados:[{"fact":"abierto JSON","tags":["test"]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")

        assertNull("Se debe devolver una matriz JSON abierta null(El análisis falló)", result)
    }

    @Test
    fun `brackets inside json strings do not affect depth tracking`() {
        // JSON dentro de la cuerda [ ] No debería afectar la máquina de estado de profundidad del soporte.
        val raw = """resultado:[{"fact":"contiene [ y ] cadena de caracteres","tags":["a[b]c"]}] Completo"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("contiene [ y ] cadena de caracteres", result[0].fact)
        assertEquals(listOf("a[b]c"), result[0].tags)
    }

    @Test
    fun `escaped quotes inside json strings are handled`() {
        // JSON La cadena contiene comillas dobles escapadas \" tiempo,findJsonArrayCandidate máquina de estados
        // Las fugas deben manejarse correctamente,no por \" Alternar incorrectamente el estado inString da como resultado un cálculo incorrecto de la profundidad del corchete
        // Nota:raw no con [ comienzo,Forzar la ruta para encontrar JsonArrayCandidate
        val raw = """Extraer resultados:[{"fact":"Los usuarios dicen\"Hola\"","tags":[]}] fin"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        assertEquals(1, result.size)
        assertEquals("Los usuarios dicen\"Hola\"", result[0].fact)
    }

    @Test
    fun `thinking tag without closing tag is stripped gracefully`() {
        // <thinking> Cuando no está cerrado,La regularidad no coincide,Pero el proceso no debería fallar.
        val raw = """<thinking>No hay etiqueta de cierre para esta reflexión.
[{"fact":"Hechos que aún se pueden extraer","importance":0}]"""
        val result = processor.parseFactExtractionResult(raw, sampleSummary(), "zh-CN")!!

        // abierto <thinking> No coincide con expresiones regulares,Pero el findJsonArrayCandidate posterior aún se puede extraer JSON
        assertEquals(
            "Cuando la etiqueta del pensamiento no está cerrada,JSON La matriz aún debe recuperarse a través de la máquina de estados.",
            1,
            result.size,
        )
        assertEquals("Hechos que aún se pueden extraer", result[0].fact)
    }

    private fun sampleSummary(sessionId: String = "test-session"): SessionSummaryManager.SummaryData {
        return SessionSummaryManager.SummaryData(
            sessionId = sessionId,
            createdAt = "2026-07-28T10:00:00Z",
            updatedAt = "2026-07-28T11:00:00Z",
            summary = "Los usuarios discutieron sus preferencias.",
            messageCount = 10,
            sourceTimeRange = null,
        )
    }
}
