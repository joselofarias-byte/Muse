package io.zer0.muse.tools

import io.zer0.muse.data.assistant.AssistantEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * v1.200: Agent Constantes de etiquetas de habilidad y funciones auxiliares.
 *
 * Cada Asistente se puede marcar con un conjunto de etiquetas de capacidad en AssistantDetailPages,
 * Permite que AgentRouter busque automáticamente el subasistente más apropiado cuando el usuario no especifica explícitamente un ID de asistente.
 */
object AgentCapability {

    // ── habilidad general ──
    const val CHAT = "chat"
    const val REASONING = "reasoning"
    const val WRITING = "writing"
    const val CREATIVE = "creative"
    const val TRANSLATION = "translation"
    const val REVIEW = "review"

    // ── Campos profesionales ──
    const val CODE = "code"
    const val MATH = "math"
    const val RESEARCH = "research"
    const val DATA = "data"
    const val LEGAL = "legal"
    const val MEDICAL = "medical"
    const val FINANCE = "finance"
    const val EDUCATION = "education"

    // ── Herramientas/modal ──
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val WEB_SEARCH = "web_search"
    const val MEMORY = "memory"
    const val SCHEDULE = "schedule"
    const val KNOWLEDGE = "knowledge"

    /** Todas las etiquetas de capacidad opcionales,Ordenar por categoría,Se utiliza para la visualización de la interfaz de usuario. */
    val ALL_CAPABILITIES = listOf(
        CHAT, REASONING, WRITING, CREATIVE, TRANSLATION, REVIEW,
        CODE, MATH, RESEARCH, DATA, LEGAL, MEDICAL, FINANCE, EDUCATION,
        IMAGE, VIDEO, WEB_SEARCH, MEMORY, SCHEDULE, KNOWLEDGE,
    )

    /** El amigable nombre chino de la etiqueta de habilidad. */
    fun displayName(capability: String): String = when (capability) {
        CHAT -> "charlar"
        REASONING -> "razonamiento"
        WRITING -> "escribiendo"
        CREATIVE -> "creatividad"
        TRANSLATION -> "Traducción"
        REVIEW -> "revisión"
        CODE -> "Programación"
        MATH -> "Matemáticas"
        RESEARCH -> "Investigación"
        DATA -> "Procesamiento de datos"
        LEGAL -> "ley"
        MEDICAL -> "medico"
        FINANCE -> "Finanzas"
        EDUCATION -> "educación"
        IMAGE -> "Generación de imágenes"
        VIDEO -> "generación de vídeo"
        WEB_SEARCH -> "búsqueda en internet"
        MEMORY -> "organización de la memoria"
        SCHEDULE -> "Gestión de horarios"
        KNOWLEDGE -> "base de conocimientos"
        else -> capability
    }

    /** Analiza una lista de etiquetas de capacidad de una AssistantEntity. */
    fun fromEntity(entity: AssistantEntity): List<String> {
        return parseCapabilitiesJson(entity.capabilitiesJson)
    }

    /** Serializar una lista de etiquetas de capacidad en una cadena JSON. */
    fun toJson(capabilities: List<String>): String {
        return io.zer0.common.AppJson.encodeToString(
            ListSerializer(String.serializer()),
            capabilities.distinct().filter { it.isNotBlank() },
        )
    }

    /** Analizar la cadena JSON de la etiqueta de capacidad. */
    fun parseCapabilitiesJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return runCatching {
            io.zer0.common.AppJson.decodeFromString(
                ListSerializer(String.serializer()),
                json,
            )
        }.getOrElse { emptyList() }
    }
}
