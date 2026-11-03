package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.ui.theme.MuseDateFormats
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class ExportFormat { MARKDOWN, PLAIN_TEXT, JSON }

@Serializable
data class ExportMessage(
    val role: String,
    val content: String,
    val mood: String? = null,
    val reasoning: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class ExportSession(
    val title: String,
    val model: String = "",
    val exportedAt: String = "",
    val messages: List<ExportMessage> = emptyList(),
)

/**
 * v1.105 Fase 1: Exportar el Coordinador de la sesión separada ChatViewModel.
 *
 * Responsabilidades:
 *  - exportSessionAsMarkdown: Exportar la sesión actual como Markdown / PlainText / HTML / JSON
 *  - exportSessionAsJson: Exportar a formato JSON(Estructura de datos completa)
 *  - exportSessionAsPlainText: Exportar a formato de texto plano
 *  - markdownToHtml: minimalista Markdown → HTML Convertir(métodos de herramientas internas)
 *
 * Solo lectura pura + escritura StringBuilder,No retenido state,Pase [accessor] Leer instantánea.
 */
class ChatExportCoordinator(
    private val accessor: ChatStateAccessor,
    private val settings: SettingsRepository,
    private val sessionRepository: SessionRepository,
    /** P2-4: Registrador de auditoría(Registrar cuando se complete la exportación). */
    private val auditLogger: AuditLogger? = null,
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * v0.29 P0-3: Exportar la sesión actual como texto Markdown(para compartir/Exportar).
     */
    suspend fun exportSessionAsMarkdown(): String {
        val state = accessor.snapshot
        val tpl = settings.shareTemplateFlow.first()
        val title = if (tpl.customTitle.isNotBlank()) {
            tpl.customTitle
        } else {
            resolveTitle(state)
        }
        val sb = StringBuilder()
        sb.append("# ").append(title).append("\n\n")

        val meta = mutableListOf<String>()
        if (tpl.includeTimestamp) {
            val now = java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, java.util.Locale.getDefault())
                .format(java.util.Date())
            meta.add("tiempo de exportación: $now")
        }
        if (tpl.includeModelName) {
            val model = state.providers.firstOrNull { it.id == state.activeProviderId }
                ?.models?.firstOrNull { it.id == state.selectedModelId }
            val modelName = model?.name ?: model?.id ?: "modelo desconocido"
            meta.add("modelo: $modelName")
        }
        meta.forEach { sb.append("> ").append(it).append("  \n") }
        if (meta.isNotEmpty()) sb.append("\n")

        val allMessages = loadAllMessages(state)

        var totalChars = 0
        allMessages.forEach { msg ->
            val role = if (msg.role == MessageRole.USER) "Usuario" else "asistente"
            sb.append("**").append(role).append("**: ").append(msg.content).append("\n\n")
            totalChars += msg.content.length
            if (tpl.includeMoodBlock && !msg.mood.isNullOrBlank()) {
                sb.append("<mood>\n").append(msg.mood).append("\n</mood>\n\n")
            }
            if (tpl.includeReasoning && !msg.reasoning.isNullOrBlank()) {
                sb.append("### proceso de pensamiento\n\n").append(msg.reasoning).append("\n\n")
            }
        }

        if (tpl.includeTokenCount) {
            val estTokens = totalChars / 3
            sb.append("> estimación Token: ~").append(estTokens).append("  \n\n")
        }

        val output = when (tpl.format) {
            "plain_text" -> sb.toString()
                .replace(Regex("""\*\*"""), "")
                .replace(Regex("""^> """, RegexOption.MULTILINE), "")
                .replace(Regex("""^# """, RegexOption.MULTILINE), "")
                .replace(Regex("""^### """, RegexOption.MULTILINE), "")
                .replace(Regex("""</?mood>"""), "")
            "html" -> markdownToHtml(sb.toString())
            else -> sb.toString()
        }
        auditLogger?.log(
            category = "user_action",
            action = "export_session",
            target = resolveSessionId(state).orEmpty(),
            detail = mapOf("format" to "markdown"),
        )
        return output
    }

    /**
     * Función4: Exporte la sesión actual a formato JSON.
     */
    suspend fun exportSessionAsJson(): String {
        val state = accessor.snapshot
        val title = resolveTitle(state)
        val model = state.providers.firstOrNull { it.id == state.activeProviderId }
            ?.models?.firstOrNull { it.id == state.selectedModelId }
        val modelName = model?.name ?: model?.id ?: ""
        val now = java.text.SimpleDateFormat(MuseDateFormats.DATE_TIME_FULL, java.util.Locale.getDefault())
            .format(java.util.Date())

        val allMessages = loadAllMessages(state)
        val exportMessages = allMessages.map { msg ->
            ExportMessage(
                role = msg.role.name,
                content = msg.content,
                mood = msg.mood,
                reasoning = msg.reasoning,
                createdAt = msg.createdAt,
            )
        }

        val export = ExportSession(
            title = title,
            model = modelName,
            exportedAt = now,
            messages = exportMessages,
        )
        val output = json.encodeToString(export)
        auditLogger?.log(
            category = "user_action",
            action = "export_session",
            target = resolveSessionId(state).orEmpty(),
            detail = mapOf("format" to "json"),
        )
        return output
    }

    /**
     * Función4: Exporte la sesión actual a formato de texto sin formato.
     */
    suspend fun exportSessionAsPlainText(): String {
        val state = accessor.snapshot
        val title = resolveTitle(state)
        val sb = StringBuilder()
        sb.append(title).append("\n")
        sb.append("=".repeat(title.length)).append("\n\n")

        val allMessages = loadAllMessages(state)
        allMessages.forEach { msg ->
            val role = if (msg.role == MessageRole.USER) "Usuario" else "asistente"
            sb.append("[$role]\n").append(msg.content).append("\n\n")
        }
        val output = sb.toString()
        auditLogger?.log(
            category = "user_action",
            action = "export_session",
            target = resolveSessionId(state).orEmpty(),
            detail = mapOf("format" to "plain_text"),
        )
        return output
    }

    /**
     * Función4: Portal de exportación unificado — Devuelve el tipo MIME y texto del contenido correspondiente según formato.
     */
    suspend fun exportSession(format: ExportFormat): Pair<String, String> {
        return when (format) {
            ExportFormat.MARKDOWN -> "text/markdown" to exportSessionAsMarkdown()
            ExportFormat.PLAIN_TEXT -> "text/plain" to exportSessionAsPlainText()
            ExportFormat.JSON -> "application/json" to exportSessionAsJson()
        }
    }

    /**
     * Exportar la sesión actual a un solo archivo HTML(Imágenes CSS + base64 en línea,Sin enlaces externos;CDN Eliminado).
     *
     * Delegar a [io.zer0.muse.data.export.ConversationExporter.exportToHtml],
     * Lógica de carga Y de lista de mensajes y título [exportSessionAsMarkdown] coherente.
     */
    suspend fun exportSessionAsHtml(): String {
        val state = accessor.snapshot
        val title = resolveTitle(state)
        val messages = loadAllMessages(state)
        // C-24: HTML La ruta no tiene contexto y se puede aplicar. locale,Utilice la configuración regional predeterminada del sistema para determinar el idioma de la copia de exportación.
        val locale = java.util.Locale.getDefault()
        val output = io.zer0.muse.data.export.ConversationExporter.exportToHtml(messages, title, locale)
        auditLogger?.log(
            category = "user_action",
            action = "export_session",
            target = resolveSessionId(state).orEmpty(),
            detail = mapOf("format" to "html"),
        )
        return output
    }

    /**
     * Exportar la sesión actual a un archivo PDF(Android Representación nativa de documentos PDF,A4 Paginación).
     *
     * Delegar a [io.zer0.muse.data.export.ConversationExporter.exportToPdf],
     * El archivo devuelto se encuentra en cacheDir/export/,Expuesto a intenciones de compartir a través de FileProvider.
     */
    suspend fun exportSessionAsPdf(context: android.content.Context): java.io.File {
        val state = accessor.snapshot
        val title = resolveTitle(state)
        val messages = loadAllMessages(state)
        // C-24: Utilice la configuración regional predeterminada del sistema para determinar las etiquetas de función en el PDF/Fecha y otro lenguaje de redacción.
        val locale = java.util.Locale.getDefault()
        val output = io.zer0.muse.data.export.ConversationExporter.exportToPdf(context, messages, title, locale)
        auditLogger?.log(
            category = "user_action",
            action = "export_session",
            target = resolveSessionId(state).orEmpty(),
            detail = mapOf("format" to "pdf"),
        )
        return output
    }

    /** Analizar el título de la sesión actual(El título vacío vuelve a "muse dialogo"). */
    private fun resolveTitle(state: io.zer0.muse.ui.ChatUiState): String {
        val sessionId = resolveSessionId(state)
        return state.sessions.find { it.id == sessionId }?.title?.takeIf { it.isNotBlank() } ?: "muse dialogo"
    }

    private suspend fun loadAllMessages(state: io.zer0.muse.ui.ChatUiState): List<io.zer0.ai.core.UIMessage> {
        val sessionId = resolveSessionId(state)
        return if (sessionId != null) {
            sessionRepository.observeMessages(sessionId).first()
        } else {
            accessor.messagesSnapshot
        }
    }

    private fun resolveSessionId(state: io.zer0.muse.ui.ChatUiState): String? =
        if (state.isAgentMode) state.agentSessionId else state.currentSessionId

    /**
     * v0.32: minimalista Markdown → HTML Convertir(Compartir plantilla en formato html,No se introducen dependencias adicionales.).
     *
     * C-09: El texto del mensaje proviene del usuario./LLM(No es digno de confianza),La implementación original inserta directamente el texto original de md. body,
     * puede ser `<script>`/`</...>` Inyectar. Aquí primero `& < >` Hacer escape HTML y eliminar la sintaxis de marcado,
     * Evite la inyección mientras conserva el efecto de renderizado de rebajas;`<mood>` Los bloques son etiquetas de convención interna.,Después de escapar, parece
     * `&lt;mood&gt;`,Formulario de escape coincidente regular correspondiente(El contenido permanece tal cual. <pre> mostrar).
     */
    private fun markdownToHtml(md: String): String {
        val body = md
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace(Regex("""^### (.+)$""", RegexOption.MULTILINE), "<h3>$1</h3>")
            .replace(Regex("""^# (.+)$""", RegexOption.MULTILINE), "<h1>$1</h1>")
            .replace(Regex("""\*\*(.+?)\*\*"""), "<strong>$1</strong>")
            .replace(Regex("""^&gt; (.+)$""", RegexOption.MULTILINE), "<blockquote>$1</blockquote>")
            .replace(Regex("""&lt;mood&gt;\n([\s\S]*?)\n&lt;/mood&gt;"""), "<pre><code>mood\n$1\n</code></pre>")
            .replace("\n\n", "</p><p>")
        return "<!DOCTYPE html><html><body><p>$body</p></body></html>"
    }
}
