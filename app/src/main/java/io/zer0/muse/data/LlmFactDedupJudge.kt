package io.zer0.muse.data

import io.zer0.common.Logger
import io.zer0.memory.fact.DedupVerdict
import io.zer0.memory.fact.FactDedupJudge
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * v12: LLM determinante de deduplicación de hechos — capa de algoritmo(Similitud de personajes/Clave de entidad)Pares de candidatos difusos indeterminados,
 * Deje que el modelo grande determine si se trata del mismo hecho.
 *
 * diseño:
 *  - Sólo en FactStore"Hay candidatos para la misma clave de entidad pero falla la determinación del texto"Las escenas de baja frecuencia se llaman,1 convocatoria de LLM por;
 *  - LLM salida estricta JSON,El análisis falló/tiempo de espera/Las excepciones siempre se degradan a"No es lo mismo"(Preferiría no fusionarse,Sin bloqueo de escrituras);
 *  - La caché de decisiones la mantiene el lado de FactStore(Preguntar sólo una vez sobre los mismos hechos.).
 */
class LlmFactDedupJudge(
    private val llmClient: MemoryLlmClient,
) : FactDedupJudge {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun judge(a: String, b: String, entityKeyA: String?, entityKeyB: String?): DedupVerdict {
        return try {
            val systemPrompt = buildSystemPrompt()
            val userContent = buildUserContent(a, b, entityKeyA, entityKeyB)
            val raw = llmClient.callText(
                systemPrompt = systemPrompt,
                userContent = userContent,
                temperature = 0.0f,
                maxTokens = 200,
                timeoutMs = 15_000L,
            )
            parse(raw)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // v2.6.2(auditoría de memoria): Relanzamiento explícito —— De lo contrario, la corrutina se cancela (cierra la sesión)./Detener el fondo) será
            // Tratado como una excepción normal y tragado, el guardado automático cancelado puede continuar esperando LLM y escribir en la biblioteca.
            throw e
        } catch (e: Exception) {
            // tiempo de espera/red/El análisis falló: degradación conservadora,No bloquea el proceso principal de escritura en memoria.
            Logger.w(TAG, "LLM La sentencia de deduplicación falló,Bajar de categoría a otro: ${e.message}")
            DedupVerdict.NOT_SAME
        }
    }

    private fun buildSystemPrompt(): String = """
        Eres el juez de deduplicación del sistema de memoria. Dados dos hechos de memoria,Determinar si describen el mismo hecho.(lo mismo).
        - Las expresiones están redactadas de forma diferente pero tienen el mismo significado.(Como por ejemplo"El usuario tiene un corgi."con"el tiene un corgi")→ same=true
        - Diferentes hechos bajo una misma entidad(Como por ejemplo"como el cafe"con"Odio el cilantro")→ same=false
        - entre ellos [REDACTED] Representa información confidencial que ha sido insensibilizada.(nombre/telefono/Dirección, etc),Tratados como diferentes formas de escribir la misma entidad.
        - incierto same=false(Preferiría no fusionarse,No fusionar por error)
        Solo salida estricta JSON,No vallas la rebaja,sin texto extra:
        {"same": true/false, "confidence": 0.0~1.0, "reason": "breve razón"}
    """.trimIndent()

    private fun buildUserContent(a: String, b: String, entityKeyA: String?, entityKeyB: String?): String = buildString {
        append("hechos A: ").append(a).append('\n')
        append("hechos B: ").append(b).append('\n')
        if (!entityKeyA.isNullOrBlank() || !entityKeyB.isNullOrBlank()) {
            append("Clave de entidad A: ").append(entityKeyA ?: "null").append('\n')
            append("Clave de entidad B: ").append(entityKeyB ?: "null").append('\n')
            append("(La misma clave de entidad normalmente indica la misma entidad,Pero el contenido fáctico aún puede ser diferente.,Consulte la semántica.)")
        }
    }

    internal fun parse(raw: String): DedupVerdict {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = runCatching { json.decodeFromString<JudgeResponse>(trimmed) }.getOrNull()
            ?: runCatching { json.decodeFromString<JudgeResponse>(extractJsonObject(trimmed)) }.getOrNull()
            ?: return DedupVerdict.NOT_SAME
        return DedupVerdict(
            same = obj.same,
            confidence = obj.confidence.coerceIn(0f, 1f),
            reason = obj.reason.orEmpty().take(80),
        )
    }

    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else ""
    }

    @Serializable
    private data class JudgeResponse(
        val same: Boolean = false,
        val confidence: Float = 0f,
        val reason: String? = null,
    )

    private companion object {
        const val TAG = "LlmFactDedupJudge"
    }
}
