package io.zer0.muse.tools

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v1.0.52 P2-4: PdfVisionParser + PdfVisionSkill + DefaultVisionOcrClient Pruebas unitarias.
 *
 * Cobertura:
 *  - [PdfVisionSkill] Verificación de parámetros(ruta faltante / ruta incluida ..)
 *  - [PdfVisionSkill] max_pages Abrazadera(C-32: resolveMaxPages pura logica,No depende de comprobaciones de existencia de archivos)
 *  - [PdfVisionSkill] resolución de ruta(espacio de trabajo/filesDir/Download/camino absoluto)
 *  - [PdfVisionParser] camino del fracaso temprano(El archivo no existe/el modelo visual no está disponible)
 *  - [DefaultVisionOcrClient] Lógica de tolerancia a fallos(text/reasoning/vacío/anormal)
 *
 * No cubierto(Requiere un dispositivo Android real/PdfRenderer):
 *  - El PDF real se representa como Bitmap
 *  - 4 Implementación real de concurrencia vial.
 *  - Llamada real del modelo visual.
 *
 * Modo de operación: `./gradlew :app:testDebugUnitTest --tests "*PdfVisionParserTest*"`
 *
 * C-31 Evaluación:[FakeVisionOcrClient] Sólo se utiliza en este documento.,No hay nombres en todos los almacenes./La misma responsabilidad se copia en todos los archivos.
 * Baja repetibilidad,No vale la pena construir infraestructura de accesorios de prueba.,Mantenga los archivos privados. Consulte el informe de auditoría en profundidad C-31 para obtener instrucciones de revisión.
 */
class PdfVisionParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── PdfVisionSkill Verificación de parámetros ─────────────────────────────────────────

    private val mockParser: PdfVisionParser = mockk(relaxed = true)
    private val mockContext: Context = mockk(relaxed = true)
    private val workspaceRoot: File by lazy { tempFolder.newFolder("workspace") }

    @Test
    fun `execute returns error when path missing`() = runTest {
        val result = PdfVisionSkill.execute(
            args = emptyMap(),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("La ruta faltante debería devolver un error", result.startsWith("[error]"))
        assertTrue("Debería indicar que falta path", result.contains("path"))
    }

    @Test
    fun `execute returns error when path is blank`() = runTest {
        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "   "),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("Una ruta en blanco debería devolver un error", result.startsWith("[error]"))
    }

    @Test
    fun `execute rejects path containing dot-dot`() = runTest {
        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "../etc/passwd"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("Las rutas que contienen .. deben rechazarse", result.startsWith("[error]"))
        assertTrue("se le debe pedir que prohíba ..", result.contains(".."))
    }

    @Test
    fun `execute returns error when file not found in any candidate`() = runTest {
        // mockContext Volver al directorio temporal,Asegúrese de que no existan todas las rutas candidatas
        every { mockContext.filesDir } returns tempFolder.newFolder("files")
        every { mockContext.cacheDir } returns tempFolder.newFolder("cache")

        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "nonexistent.pdf"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("El archivo no existe debería devolver un error", result.startsWith("[error]"))
        assertTrue("Debería indicar que el archivo no existe.", result.contains("no existe"))
    }

    @Test
    fun `resolveMaxPages clamps above hard cap to hard cap`() {
        // C-32: max_pages La sujeción cambió a prueba de lógica pura,No depende de las comprobaciones de existencia de archivos con simulacro de coCada ​​pedido.
        assertEquals("201 debe sujetarse a 200", 200, PdfVisionSkill.resolveMaxPages("201"))
        assertEquals("Superar con creces el límite superior debe sujetarse a 200", 200, PdfVisionSkill.resolveMaxPages("9999"))
    }

    @Test
    fun `resolveMaxPages clamps below 1 to 1`() {
        assertEquals("0 debe sujetarse a 1", 1, PdfVisionSkill.resolveMaxPages("0"))
        assertEquals("Los números negativos deben sujetarse a 1", 1, PdfVisionSkill.resolveMaxPages("-5"))
    }

    @Test
    fun `resolveMaxPages uses default when missing or invalid`() {
        assertEquals("Usar el valor predeterminado por defecto", PdfVisionSkill.DEFAULT_MAX_PAGES, PdfVisionSkill.resolveMaxPages(null))
        assertEquals("Usar valor predeterminado para valores no numéricos", PdfVisionSkill.DEFAULT_MAX_PAGES, PdfVisionSkill.resolveMaxPages("abc"))
        assertEquals("La cadena vacía usa el valor predeterminado", PdfVisionSkill.DEFAULT_MAX_PAGES, PdfVisionSkill.resolveMaxPages(""))
    }

    @Test
    fun `resolveMaxPages keeps in-range value`() {
        assertEquals("1 frontera", 1, PdfVisionSkill.resolveMaxPages("1"))
        assertEquals("Los valores legales se dejan como están.", 80, PdfVisionSkill.resolveMaxPages("80"))
        assertEquals("El límite superior se deja como está.", PdfVisionSkill.HARD_MAX_PAGES, PdfVisionSkill.resolveMaxPages("200"))
    }

    @Test
    fun `execute uses default max_pages when parameter missing`() = runTest {
        val fakePdf = File(workspaceRoot, "test.pdf").apply { writeText("fake") }
        every { mockContext.filesDir } returns tempFolder.newFolder("files")
        every { mockContext.cacheDir } returns tempFolder.newFolder("cache")

        coEvery {
            mockParser.parse(any(), any())
        } returns PdfVisionParser.ParseResult(
            success = true,
            text = "OCR contenido",
            pagesProcessed = 5,
            pageCount = 5,
        )

        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "test.pdf"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("El título debe incluirse en caso de éxito.", result.contains("[PDF Análisis visual exitoso]"))
        assertTrue("Se debe incluir contenido OCR", result.contains("OCR contenido"))
    }

    @Test
    fun `execute handles non-numeric max_pages gracefully`() = runTest {
        val fakePdf = File(workspaceRoot, "test.pdf").apply { writeText("fake") }
        every { mockContext.filesDir } returns tempFolder.newFolder("files")
        every { mockContext.cacheDir } returns tempFolder.newFolder("cache")

        coEvery {
            mockParser.parse(any(), any())
        } returns PdfVisionParser.ParseResult(
            success = true,
            text = "contenido",
            pagesProcessed = 1,
            pageCount = 1,
        )

        // Las páginas max_pages no numéricas deben degradarse al valor predeterminado,No lanzar excepción
        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "test.pdf", "max_pages" to "abc"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("Las páginas max_pages no numéricas deben degradarse al valor predeterminado,No colapsado", result.contains("[PDF Análisis visual exitoso]"))
    }

    @Test
    fun `execute returns error when parser throws exception`() = runTest {
        val fakePdf = File(workspaceRoot, "test.pdf").apply { writeText("fake") }
        every { mockContext.filesDir } returns tempFolder.newFolder("files")
        every { mockContext.cacheDir } returns tempFolder.newFolder("cache")

        coEvery {
            mockParser.parse(any(), any())
        } throws RuntimeException("Simular excepciones de PdfRenderer")

        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "test.pdf"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("parser Lanzar una excepción debería devolver un error", result.startsWith("[error]"))
        assertTrue("Debe contener un mensaje de excepción", result.contains("Simular excepciones de PdfRenderer"))
    }

    @Test
    fun `execute returns error when parser reports failure`() = runTest {
        val fakePdf = File(workspaceRoot, "test.pdf").apply { writeText("fake") }
        every { mockContext.filesDir } returns tempFolder.newFolder("files")
        every { mockContext.cacheDir } returns tempFolder.newFolder("cache")

        coEvery {
            mockParser.parse(any(), any())
        } returns PdfVisionParser.ParseResult(
            success = false,
            text = "",
            pagesProcessed = 0,
            pageCount = 0,
            error = "No hay ningún modelo visual configurado",
        )

        val result = PdfVisionSkill.execute(
            args = mapOf("path" to "test.pdf"),
            parser = mockParser,
            context = mockContext,
            workspaceRoot = workspaceRoot,
        )
        assertTrue("parser El error debería devolver un error", result.startsWith("[error]"))
        assertTrue("Debe contener un mensaje de error para el analizador.", result.contains("No hay ningún modelo visual configurado"))
    }

    // ── PdfVisionParser camino del fracaso temprano(usar fake ocrClient,pasando por alto la realidad LLM) ──────

    @Test
    fun `parse returns failure when file does not exist`() = runTest {
        val fakeOcr = FakeVisionOcrClient(available = true)
        val parser = PdfVisionParser(
            context = mockContext,
            ocrClient = fakeOcr,
        )

        val result = parser.parse("/nonexistent/path/file.pdf")

        assertFalse("El archivo no existe debería fallar", result.success)
        assertTrue("Debería indicar que el archivo no existe.", result.error?.contains("no existe") == true)
        assertEquals(0, result.pageCount)
        assertEquals(0, result.pagesProcessed)
    }

    @Test
    fun `parse returns failure when vision model unavailable`() = runTest {
        // Crear simulación de archivo temporal PDF(El contenido no es importante,Verificar solo isAvailable=false fracaso temprano)
        val fakePdf = tempFolder.newFile("test.pdf").apply { writeText("fake") }
        val fakeOcr = FakeVisionOcrClient(available = false)
        val parser = PdfVisionParser(
            context = mockContext,
            ocrClient = fakeOcr,
        )

        val result = parser.parse(fakePdf.absolutePath)

        assertFalse("El modelo visual no está disponible y debería fallar", result.success)
        assertTrue("Debería indicar que el modelo visual no está configurado.", result.error?.contains("Visión") == true)
        assertEquals("isAvailable=false no debe ser llamado cuando ocr", 0, fakeOcr.ocrCallCount)
    }

    // ── DefaultVisionOcrClient Lógica de tolerancia a fallos ────────────────────────────────

    @Test
    fun `DefaultVisionOcrClient returns text when non-blank`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "OCR Texto extraído", reasoningContent = null)

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        val result = client.ocr("base64data")

        assertEquals("Debería regresar no vacío text", "OCR Texto extraído", result)
    }

    @Test
    fun `DefaultVisionOcrClient falls back to reasoningContent when text empty`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = true)
        // Modelo de razonamiento de simulación.:text vacío,Pero el razonamientoContenido tiene contenido
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "", reasoningContent = "El modelo de inferencia pone OCR aquí.")

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        val result = client.ocr("base64data")

        assertEquals("text Debería degradarse cuando esté vacío. reasoningContent", "El modelo de inferencia pone OCR aquí.", result)
    }

    @Test
    fun `DefaultVisionOcrClient returns empty marker when both text and reasoning blank`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "", reasoningContent = "")

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        val result = client.ocr("base64data")

        assertEquals("text+reasoning están vacíos deberían devolver una etiqueta de contenido vacía", "[OCR Devolver contenido vacío]", result)
    }

    @Test
    fun `DefaultVisionOcrClient returns empty input marker when base64 blank`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)

        val result = client.ocr("")

        assertEquals("Base64 vacía debería devolver la entrada como token vacío", "[OCR La entrada está vacía]", result)
    }

    @Test
    fun `DefaultVisionOcrClient returns failure marker when ChatService throws`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("error de red")

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        val result = client.ocr("base64data")

        assertTrue("ChatService Lanzar una excepción debería devolver una marca de error", result.startsWith("[OCR llamada fallida"))
        assertTrue("Debe contener un mensaje de excepción", result.contains("error de red"))
    }

    @Test
    fun `DefaultVisionOcrClient isAvailable returns false when no provider config`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns null

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        assertFalse("Cuando no hay ningún proveedor configurado, isAvailable debe estar false", client.isAvailable())
    }

    @Test
    fun `DefaultVisionOcrClient isAvailable returns false when no vision model`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = false)

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        assertFalse("Está disponible cuando no hay ningún modelo visual. false", client.isAvailable())
    }

    @Test
    fun `DefaultVisionOcrClient isAvailable returns true when vision model configured`() = runTest {
        val mockChatService = mockk<ChatService>()
        val mockConfigStore = mockk<ProviderConfigStore>()
        coEvery { mockConfigStore.get() } returns testProviderConfig(visionSupported = true)

        val client = DefaultVisionOcrClient(mockChatService, mockConfigStore)
        assertTrue("Cuando el modelo visual está configurado, isAvailable debe estar true", client.isAvailable())
    }

    // ── herramientas auxiliares ────────────────────────────────────────────────────────

    /**
     * Fake VisionOcrClient,Evitando llamadas reales de LLM.
     *
     * @param available isAvailable() valor de retorno
     * @param ocrResult ocr() texto fijo devuelto(Predeterminado "fake-ocr")
     */
    private class FakeVisionOcrClient(
        private val available: Boolean,
        private val ocrResult: String = "fake-ocr",
    ) : VisionOcrClient {
        var ocrCallCount: Int = 0
            private set

        override suspend fun isAvailable(): Boolean = available

        override suspend fun ocr(imageBase64: String): String {
            ocrCallCount++
            return ocrResult
        }
    }

    /**
     * Construya un ProviderConfig para realizar pruebas.
     *
     * @param visionSupported Si el modelo admite la entrada de visión
     */
    private fun testProviderConfig(visionSupported: Boolean): ProviderConfig {
        val model = Model(
            id = "test-model",
            name = "Test Model",
            providerId = "test-provider",
            supportsVision = visionSupported,
        )
        return ProviderConfig(
            id = "test-provider",
            displayName = "Test Provider",
            type = ProviderType.OPENAI,
            baseUrl = "https://test.example.com",
            apiKey = "test-key",
            models = listOf(model),
        )
    }
}
