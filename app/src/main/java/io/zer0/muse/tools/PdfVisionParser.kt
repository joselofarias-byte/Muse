package io.zer0.muse.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.zer0.ai.ChatService
import io.zer0.ai.ProviderConfigStore
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64 as JavaBase64

/**
 * v1.0.52 P2-4: PDF Analizador visual.
 *
 * Usando el sistema Android [PdfRenderer] Renderice cada página PDF en Bitmap,Luego use modelos de IA que respalden la visión para hacer OCR,
 * Extraer texto y estructura. Admite versión escaneada PDF(tipo de imagen,Baje a esta ruta cuando la extracción de texto de PDFBox local devuelva nulo).
 *
 * Puntos de diseño:
 *  - 4 Concurrencia de carreteras(Evite que el OCR en serie de un solo subproceso de archivos PDF de gran tamaño tarde demasiado)
 *  - Devolución de llamada de progreso(Devolución de llamada una vez por finalización de OCR de página,UI Se puede mostrar "page X/Y")
 *  - Reducción de resolución de página grande(targetPageWidth Limitar el ancho de renderizado,Evite OOM y sobredimensionados base64)
 *  - Los resultados están empalmados por página.,traer "--- Page N ---" delimitador
 *
 * seguro:
 *  - PdfRenderer abrir solo lectura ParcelFileDescriptor,No escriba el archivo original
 *  - Renderizar mapa de bits en el tiempo dentro de la rutina recycle,Evitar la acumulación de memoria.
 *  - Límite de concurrencia 4,Evite que se generen más de 4 mapas de bits en un corto período de tiempo OOM
 */
class PdfVisionParser(
    private val context: Context,
    private val ocrClient: VisionOcrClient,
    private val pageConcurrency: Int = DEFAULT_PAGE_CONCURRENCY,
    private val targetPageWidth: Int = DEFAULT_TARGET_PAGE_WIDTH,
) {

    /**
     * Analiza los resultados.
     *
     * @param success Si todas las páginas tienen éxito
     * @param text El texto completo empalmado.(Usado por página "--- Page N ---" separar)
     * @param pagesProcessed Número real de páginas realizadas con OCR correctamente
     * @param pageCount PDF Páginas totales
     * @param error Razón del fracaso(success=false el tiempo no está vacío)
     */
    data class ParseResult(
        val success: Boolean,
        val text: String,
        val pagesProcessed: Int,
        val pageCount: Int,
        val error: String? = null,
    )

    /**
     * Analizar PDF.
     *
     * @param pdfPath PDF ruta absoluta del archivo
     * @param onPageProgress Devolución de llamada de progreso,(currentPage, totalPages),currentPage empezar desde 1
     * @return Analizar resultados
     */
    suspend fun parse(pdfPath: String, onPageProgress: (currentPage: Int, totalPages: Int) -> Unit = { _, _ -> }): ParseResult =
        withContext(Dispatchers.IO) {
            val file = File(pdfPath)
            if (!file.exists() || !file.isFile) {
                return@withContext ParseResult(false, "", 0, 0, "PDF El archivo no existe: $pdfPath")
            }
            if (!file.canRead()) {
                return@withContext ParseResult(false, "", 0, 0, "PDF El archivo no es legible: $pdfPath")
            }

            // Consultar disponibilidad del modelo de visión.(fracaso temprano,Evite el renderizado a medias solo para descubrir un modelo no visual)
            if (!ocrClient.isAvailable()) {
                return@withContext ParseResult(
                    success = false,
                    text = "",
                    pagesProcessed = 0,
                    pageCount = 0,
                    error = "Modelo de IA habilitado para visión no configurado,Configure el modelo compatible con visión en la configuración(Como por ejemplo GPT-4o / Gemini / Claude)",
                )
            }

            val pfd: ParcelFileDescriptor = runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrElse {
                return@withContext ParseResult(false, "", 0, 0, "No se pudo abrir el PDF: ${it.message}")
            }

            val renderer = PdfRenderer(pfd)
            try {
                val pageCount = renderer.pageCount
                if (pageCount <= 0) {
                    return@withContext ParseResult(false, "", 0, 0, "PDF El número de páginas es 0")
                }

                // Los resultados de OCR de cada página se almacenan de acuerdo con el índice pageIndex.,Asegúrese de que el orden de empalme sea coherente con el PDF original
                val results = Array<String?>(pageCount) { null }
                val errors = Array<String?>(pageCount) { null }
                val semaphore = Semaphore(pageConcurrency.coerceAtLeast(1))

                coroutineScope {
                    (0 until pageCount).map { pageIndex ->
                        async {
                            semaphore.withPermit {
                                try {
                                    val base64 = renderPageToBase64(renderer, pageIndex)
                                    val ocrText = ocrClient.ocr(base64)
                                    results[pageIndex] = ocrText
                                } catch (t: Throwable) {
                                    Logger.w(TAG, "OCR No. ${pageIndex + 1} Página fallida", t)
                                    errors[pageIndex] = t.message ?: t.javaClass.simpleName
                                } finally {
                                    // Completado por página(Sin importar el éxito o el fracaso)Todo el progreso de devolución de llamada
                                    onPageProgress(pageIndex + 1, pageCount)
                                }
                            }
                        }
                    }.awaitAll()
                }

                val pagesProcessed = results.count { it != null }
                val text = buildString {
                    for (i in 0 until pageCount) {
                        val pageText = results[i]
                        if (pageText != null) {
                            appendLine("--- Page ${i + 1} ---")
                            appendLine(pageText)
                            appendLine()
                        }
                    }
                }.trimEnd()

                // Todo falló → success=false
                if (pagesProcessed == 0) {
                    val firstError = errors.firstOrNull { it != null } ?: "error desconocido"
                    return@withContext ParseResult(
                        success = false,
                        text = "",
                        pagesProcessed = 0,
                        pageCount = pageCount,
                        error = "El OCR falló en todas las páginas,primer error: $firstError",
                    )
                }

                // El éxito parcial cuenta como éxito,Pero el mensaje de error está empalmado al final del texto.(Permite que LLM sepa qué páginas fallaron)
                val finalText = if (pagesProcessed < pageCount) {
                    val failedPages = errors.indices.filter { errors[it] != null }.joinToString(",") { (it + 1).toString() }
                    "$text\n\n[Nota: No. $failedPages Error en el OCR de página,Puede que falte contenido]"
                } else {
                    text
                }

                ParseResult(
                    success = true,
                    text = finalText,
                    pagesProcessed = pagesProcessed,
                    pageCount = pageCount,
                )
            } finally {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }

    /**
     * Representa la página especificada como mapa de bits y la convierte a base64.
     *
     * Estrategia de reducción de resolución:
     *  - calcular scale = targetPageWidth / page.width(si page.width > targetPageWidth)
     *  - Renderizar el tamaño del mapa de bits objetivo = page.width*scale × page.height*scale
     *  - Utilice ARGB_8888 para garantizar la claridad(PDF escaneado OCR es sensible a la nitidez)
     *
     * Nota:La visibilidad interna facilita las pruebas unitarias para verificar la lógica a través de la reflexión.(El PdfRenderer real no puede mock).
     */
    internal fun renderPageToBase64(renderer: PdfRenderer, pageIndex: Int): String {
        val page = renderer.openPage(pageIndex)
        try {
            val scale = if (page.width > targetPageWidth) {
                targetPageWidth.toFloat() / page.width.toFloat()
            } else {
                1f
            }
            val bmpWidth = (page.width * scale).toInt().coerceAtLeast(1)
            val bmpHeight = (page.height * scale).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
            try {
                // PdfRenderer.RENDER_MODE_FOR_DISPLAY adecuado para OCR(Más ligero que FOR_PRINT pero más rápido)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                val bytes = baos.toByteArray()
                return JavaBase64.getEncoder().encodeToString(bytes)
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }

    companion object {
        private const val TAG = "PdfVisionParser"

        /** Simultaneidad de 4 vías predeterminada(Equilibrando velocidad y memoria). */
        const val DEFAULT_PAGE_CONCURRENCY = 4

        /** Límite de ancho de renderizado predeterminado(Píxel).A4 @ 96dpi ≈ 794px,1024 Acérquese ligeramente para conservar los detalles. */
        const val DEFAULT_TARGET_PAGE_WIDTH = 1024

        /** Palabra de aviso de OCR única(Se requiere que el modelo extraiga el texto y la estructura lo más completamente posible.,Sin resumen). */
        const val OCR_PROMPT = "Intente extraer el texto visible y la estructura de esta página lo más completamente posible.,Preservar título, lista, orden de tabla,Sin resumen." +
            "Si es una versión escaneada de la página.,Realice OCR para restaurar el texto. Si la página es una imagen pura(sin texto),Describe el contenido principal de la imagen."
    }
}

/**
 * Abstracción del cliente OCR del modelo visual.
 *
 * El propósito de extraer la interfaz.:
 *  1. Las pruebas unitarias pueden inyectar implementaciones falsas,Sin pasar por Android PdfRenderer y llamadas LLM reales
 *  2. Soporte futuro para modelos visuales que no son ChatService(Como motor de OCR local/nube OCR API)tiempo,Simplemente implemente esta interfaz
 */
interface VisionOcrClient {
    /** Si el modelo visual disponible está configurado(Comprobación temprana de fallos). */
    suspend fun isAvailable(): Boolean

    /** Hazlo en una única imagen JPEG base64. OCR,Devuelve el texto extraído. */
    suspend fun ocr(imageBase64: String): String
}

/**
 * Implementación predeterminada:Basado en [ChatService] + El modelo visual en la configuración actual del proveedor.
 *
 * comportamiento:
 *  - isAvailable(): Verifique el repositorio de configuración si el proveedor actual tiene supportsVisionInput() modelo
 *  - ocr(): Construir mensaje de USUARIO con imageBase64List,llamar completeText(UTILITY El pensamiento relacionado con el modo ahorra tiempo)
 *  - tolerancia a fallas:text Se utiliza para bajar de categoría cuando está vacío. reasoning(El modelo de inferencia puede poner el contenido del OCR en reasoning_content)
 */
class DefaultVisionOcrClient(
    private val chatService: ChatService,
    private val configStore: ProviderConfigStore,
    /** v2.x: Enrutamiento de modelo auxiliar(archivo visual;null mantener el comportamiento original,Presione para activar la llamada al modelo visual del proveedor.). */
    private val settings: io.zer0.muse.data.SettingsRepository? = null,
) : VisionOcrClient {

    /** v2.x: Analizar el enlace de archivos visuales(Golpe exacto con el proveedor;no obligado/Devuelve nulo en caso de error y vuelve a la lógica original.). */
    private suspend fun resolveVisionRoute(): Pair<io.zer0.ai.core.ProviderConfig, io.zer0.ai.core.Model>? {
        val repo = settings ?: return null
        return runCatching {
            io.zer0.muse.data.routing.UtilityModelRouter(repo)
                .resolve(io.zer0.muse.data.routing.UtilityTier.VISION)
        }.getOrNull()
    }

    override suspend fun isAvailable(): Boolean {
        // v2.x: El enlace de archivos visual se puede liberar directamente(Habilite OCR para usar un modelo visual seleccionado por el usuario)
        if (resolveVisionRoute() != null) return true
        val config = configStore.get() ?: return false
        // Cualquier modelo puede soportar la visión.
        return config.models.any { it.supportsVisionInput() }
    }

    override suspend fun ocr(imageBase64: String): String {
        if (imageBase64.isBlank()) return "[OCR La entrada está vacía]"

        val message = UIMessage(
            role = MessageRole.USER,
            content = PdfVisionParser.OCR_PROMPT,
            imageBase64List = listOf(imageBase64),
        )

        // v2.x: Priorizar el enlace con archivos visuales(Golpe exacto con el proveedor);Mantener el comportamiento original cuando no está vinculado(Activar modelo predeterminado del proveedor)
        val routed = resolveVisionRoute()
        val completion = runCatching {
            chatService.completeText(
                messages = listOf(message),
                model = routed?.second,
                providerConfig = routed?.first,
                mode = ChatRequestMode.UTILITY,
            )
        }.getOrElse {
            return "[OCR llamada fallida: ${it.message}]"
        }

        // prioridad text;degradación vacía reasoningContent(El modelo de inferencia puede poner el contenido del OCR en reasoning_content)
        return completion.text.takeIf { it.isNotBlank() }
            ?: completion.reasoningContent?.takeIf { it.isNotBlank() }
            ?: "[OCR Devolver contenido vacío]"
    }
}
