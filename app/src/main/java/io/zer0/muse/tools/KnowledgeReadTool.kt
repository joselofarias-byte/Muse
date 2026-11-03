package io.zer0.muse.tools

import io.zer0.muse.data.knowledge.KnowledgeDocDao
import kotlinx.coroutines.runBlocking

/**
 * v2.6.2: Lea el texto completo del documento de la base de conocimientos por docId.
 *
 * Anteriormente, el asistente solo tenía Knowledge_search (búsqueda vectorial, que devolvía fragmentos topK) para la base de conocimientos y para un artículo.
 * 195 Sólo puede ver fragmentos dispersos de páginas PDF, lo que hace imposible comprender completamente la estructura del documento o leer el texto completo a pedido.
 *
 * Uso: primero use Knowledge_list_docs para confirmar la ID del documento y luego use esta herramienta para leer el texto completo.
 * Los documentos muy largos se leen en segmentos mediante desplazamiento (hasta [MAX_CHARS_PER_READ] personajes), evitar
 * Abarrotar 195 páginas en la ventana contextual a la vez.
 *
 * Documentación interna (is_internal=1) no se permite leer a través de esta herramienta——Eso es devdoc, aislado de la base de conocimientos del usuario.
 */
object KnowledgeReadTool {

    const val TOOL_NAME = "knowledge_read_doc"

    /** Límite de lectura única.195 página PDF ≈ 50 Esvástica, leída varias veces. */
    private const val MAX_CHARS_PER_READ = 30_000

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "Lea el texto completo del documento de la base de conocimientos. Los documentos muy largos se segmentan por desplazamiento (hasta 30.000 caracteres cada vez)," +
            "Lea el primer párrafo sin pasar el desplazamiento. El número restante de caracteres y el siguiente desplazamiento se proporcionarán al final de la devolución." +
            "La identificación del documento se puede obtener con Knowledge_list_docs.",
        parameters = mapOf(
            "doc_id" to "Requerido, identificación del documento (se puede obtener con Knowledge_list_docs)",
            "offset" to "Opcional, desplazamiento de carácter inicial; comienza en 0 cuando se omite",
        ),
        required = setOf("doc_id"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    fun executeFromArgs(args: Map<String, String>, docDao: KnowledgeDocDao): String {
        val docId = args["doc_id"]?.trim().orEmpty()
        if (docId.isBlank()) return "Error: doc_id is required."
        val offset = args["offset"]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        return runBlocking {
            val doc = docDao.getById(docId)
            when {
                doc == null -> "Error: Documento no encontrado $docId. Utilice Knowledge_list_docs para ver la lista de documentación."
                doc.isInternal -> "Error: Documentación $docId Es un documento de desarrollo interno y no se permite su lectura a través de esta herramienta."
                doc.content.isBlank() -> "Documentación ${doc.title}($docId) el texto está vacío."
                else -> {
                    val content = doc.content
                    val total = content.length
                    if (offset >= total) {
                        "ha excedido el final del documento (longitud total $total personajes,solicitud offset=$offset)."
                    } else {
                        val end = minOf(offset + MAX_CHARS_PER_READ, total)
                        val chunk = content.substring(offset, end)
                        val remaining = total - end
                        buildString {
                            appendLine("「${doc.title}\"($docId)— No. ${offset + 1}-$end / $total personaje")
                            if (remaining > 0) {
                                appendLine("[también $remaining personajes, uso offset=$end Continuar leyendo]")
                            } else {
                                appendLine("[Texto completo]")
                            }
                            appendLine("---")
                            append(chunk)
                        }
                    }
                }
            }
        }
    }
}

/** Registrador: Inyección de Koin, guiada uniformemente por AutomationInitializer. */
class KnowledgeReadToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val docDao: KnowledgeDocDao,
) {
    init {
        toolRegistry.register(KnowledgeReadTool.toolDef()) { args ->
            KnowledgeReadTool.executeFromArgs(args, docDao)
        }
    }
}
