package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import java.io.File

/**
 * v1.0.52 P2-4: parse_pdf Portal de herramientas + registrador.
 *
 * AI Se puede invocar esta herramienta para analizar visualmente archivos PDF.(Renderizar cada página como una imagen → modelo visual OCR).
 *
 * Escenarios de uso:
 *  - Versión escaneada PDF(tipo de imagen,DocumentParser La extracción de texto de PDFBox vuelve vacía)
 *  - Diseño complejo PDF(mesa/fórmula/Gráficos y texto mixtos.,El orden de extracción del texto está desordenado)
 *  - Solicitud explícita del usuario"Ver contenido PDF"Y cuando la extracción de texto local es insuficiente
 *
 * seguro:
 *  - operación de solo lectura,No modificar el archivo original.
 *  - SAFE nivel de riesgo(Sin efectos secundarios,Solo requiere mucho tiempo y llamadas API)
 *  - Límite estricto en el recuento de páginas 200(Evitar que los archivos PDF de gran tamaño destruyan el contexto LLM)
 *
 * Formato de devolución:
 *  éxito: Texto completo empalmado(por página "--- Page N ---" separar)
 *  falló: "[error] ..." mensaje legible en el formulario
 */
object PdfVisionSkill {

    const val NAME = "parse_pdf"

    /** Número máximo de páginas predeterminado(Evite que los archivos PDF extremadamente grandes tarden demasiado + explosión de contexto). */
    const val DEFAULT_MAX_PAGES = 50

    /** tapa dura(El usuario puede especificar explícitamente el 200,pero no más alto). */
    const val HARD_MAX_PAGES = 200

    /**
     * Analice el parámetro max_pages y sujételo al rango legal.
     *
     * pura logica,Para verificación directa mediante pruebas unitarias.,No depende del sistema de archivos ni de las llamadas al analizador.
     * (C-32: Retire la abrazadera max_pages de la ejecución,Evite el acoplamiento de prueba"Comprobación de existencia de archivos→parser llamar"orden.)
     *
     * @param raw Valor del parámetro original;null/vacío/Para fines no numéricos [DEFAULT_MAX_PAGES];Abrazadera transfronteriza para [1, HARD_MAX_PAGES].
     */
    internal fun resolveMaxPages(raw: String?): Int = raw?.toIntOrNull()?.coerceIn(1, HARD_MAX_PAGES) ?: DEFAULT_MAX_PAGES

    fun toolDef() = ToolRegistry.ToolDef(
        name = NAME,
        description = "Análisis visual de archivos PDF(Renderizar cada página como una imagen → El modelo visual OCR extrae texto)." +
            "Aplicable a la versión escaneada PDF(tipo de imagen)o diseño complejo PDF(mesa/fórmula/Gráficos y texto mixtos.)." +
            "Versión de texto normal PDF por favor dé prioridad read_file(si es legible)o documento adjunto de análisis,Esta herramienta lleva mucho tiempo." +
            "Necesidad de configurar un modelo que admita la visión.(GPT-4o / Gemini / Claude espera)." +
            "Devolver texto OCR por página,usar '--- Page N ---' Separado.",
        parameters = mapOf(
            "path" to "Requerido,PDF ruta absoluta del archivo(Como por ejemplo '/sdcard/Download/report.pdf' o ruta relativa al espacio de trabajo)",
            "max_pages" to "Opcional,Número máximo de páginas analizadas(Predeterminado 50,tapa dura 200),Evite que los PDF muy grandes tarden demasiado",
        ),
        required = setOf("path"),
        category = "built-in",
        parameterTypes = mapOf("max_pages" to "integer"),
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * Ejecute la herramienta parse_pdf.
     *
     * @param args Parámetros de la herramienta
     * @param parser PDF Ejemplo de analizador visual
     * @param context Se utiliza para resolver rutas relativas del espacio de trabajo.(Opcional)
     * @param workspaceRoot directorio raíz del espacio de trabajo(Se utiliza para resolver rutas relativas.)
     */
    suspend fun execute(args: Map<String, String>, parser: PdfVisionParser, context: Context, workspaceRoot: File): String {
        val rawPath = args["path"]?.trim()?.takeIf { it.isNotBlank() }
            ?: return "[error] Falta el parámetro requerido path"

        if (rawPath.contains("..")) return "[error] El camino no debe contener '..'"

        val maxPages = resolveMaxPages(args["max_pages"])

        // ruta de análisis:Ruta relativa del espacio de trabajo → filesDir → cacheDir → externo Download → camino absoluto
        val candidates = listOf(
            File(workspaceRoot, rawPath),
            File(context.filesDir, rawPath),
            File(context.cacheDir, rawPath),
            File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), rawPath),
            File(rawPath),
        )
        val target = candidates.firstOrNull { it.exists() && it.isFile }
            ?: return "[error] PDF El archivo no existe: $rawPath"

        // registro de progreso(Fácil de depurar,UI Si se necesita una visualización del progreso, se puede ampliar más adelante.)
        var lastReported = 0L
        val result = runCatching {
            parser.parse(target.absolutePath) { current, total ->
                // Limitar la frecuencia de registro(Sólo uno por página)
                val now = System.currentTimeMillis()
                if (now - lastReported > 500 || current == total) {
                    Logger.i("PdfVisionSkill", "parse_pdf Progreso: $current/$total (${target.name})")
                    lastReported = now
                }
            }
        }.getOrElse {
            Logger.w("PdfVisionSkill", "parse_pdf anormal", it)
            return "[error] excepción de análisis: ${it.message}"
        }

        if (!result.success) {
            return "[error] ${result.error ?: "El análisis falló"}"
        }

        // Truncado a maxPages(Si el PDF excede maxPages,parse Procesado internamente;Aquí solo se realiza el truncamiento de la salida final)
        val finalText = if (result.pagesProcessed > maxPages) {
            result.text.take(estimateCharsForPages(maxPages)) +
                "\n\n[Nota: Análisis real ${result.pagesProcessed} página,Truncado a $maxPages contenido de la página]"
        } else {
            result.text
        }

        return buildString {
            appendLine("[PDF Análisis visual exitoso] Archivo: ${target.name}")
            appendLine("Páginas totales: ${result.pageCount},Analizado con éxito: ${result.pagesProcessed}")
            appendLine()
            append(finalText)
        }
    }

    /** Estimación aproximada del límite de caracteres para N páginas(Aproximadamente 2000 caracteres por página,Evitar que la salida sea demasiado larga). */
    private fun estimateCharsForPages(pages: Int): Int = pages * 2000
}

/**
 * PdfVision Registrador de herramientas — Registre parse_pdf en ToolRegistry.
 *
 * depender de ToolRegistry + PdfVisionParser + Context + WorkspaceManager(tomar rootDir).
 */
class PdfVisionToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val parser: PdfVisionParser,
    private val context: Context,
    private val workspaceRoot: File,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(PdfVisionSkill.toolDef()) { args ->
            PdfVisionSkill.execute(args, parser, context, workspaceRoot)
        }
    }
}
