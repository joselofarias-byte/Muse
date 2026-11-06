package io.zer0.muse.data.artifact

import android.content.Context
import io.zer0.muse.R
import kotlin.uuid.Uuid

/**
 * Extraído del contenido de respuesta de IA `<artifact>` etiqueta,generar [ArtifactEntity] lista,
 * y reemplace la etiqueta original con el marcador de posición `[artifact:{id}]` Para que la interfaz de usuario represente tarjetas.
 *
 * - Los nombres de las etiquetas no distinguen entre mayúsculas y minúsculas
 * - type El valor predeterminado es el predeterminado "document"
 * - language Opcional
 * - Admite contenido que abarca varias líneas y cualquier espacio en blanco entre atributos
 */
object ArtifactExtractor {

    /**
     * partido `<artifact ...>...</artifact>`,Se ignoran las mayúsculas y minúsculas y el contenido puede abarcar líneas.
     *
     * L13 Limitaciones conocidas: Para la sección de atributos `[^>]*` partido,Cuando el valor del atributo contiene > tiempo(Como por ejemplo title="a>b")La expresión regular regular se truncará por adelantado,
     * La producción real de LLM es rara;Para un análisis preciso, utilice un analizador XML/HTML en su lugar,Pero el proyecto no introduce dependencias adicionales.,Mantenga el plan habitual por ahora.
     */
    private val artifactTagRegex = Regex(
        """<artifact\b([^>]*)>(.*?)</artifact>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Coincidir con un solo atributo `name="value"` o `name='value'`,Se ignora el caso. */
    private val attrRegex = Regex(
        """\b(title|type|language)\s*=\s*["']([^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Extraiga el producto y reemplace el marcador de posición.
     *
     * @return Pair(Contenido reemplazado, Lista de entidades de producto)
     */
    fun extractArtifacts(
        sessionId: String,
        messageId: String,
        content: String,
        context: Context? = null,
    ): Pair<String, List<ArtifactEntity>> {
        val artifacts = mutableListOf<ArtifactEntity>()
        var replaced = content

        // Reemplazar de atrás hacia adelante,Evitar sustituciones anteriores cambiando el índice de partidos posteriores
        val matches = artifactTagRegex.findAll(content).toList()
        if (matches.isEmpty()) return content to emptyList()

        matches.reversed().forEach { match ->
            val attrs = parseAttributes(match.groupValues[1])
            val artifactContent = match.groupValues[2]
            val id = Uuid.random().toString()
            val now = System.currentTimeMillis()
            val artifact = ArtifactEntity(
                id = id,
                sessionId = sessionId,
                messageId = messageId,
                title = attrs["title"] ?: context?.getString(R.string.artifact_default_title) ?: "Untitled",
                type = attrs["type"] ?: "document",
                content = artifactContent,
                language = attrs["language"],
                createdAt = now,
                updatedAt = now,
            )
            artifacts.add(artifact)
            replaced = replaced.replaceRange(match.range, "[artifact:$id]")
        }

        return replaced to artifacts.reversed()
    }

    private fun parseAttributes(tagOpening: String): Map<String, String> {
        return attrRegex.findAll(tagOpening).associate { result ->
            val name = result.groupValues[1].lowercase()
            val value = result.groupValues[2]
            name to value
        }
    }
}
