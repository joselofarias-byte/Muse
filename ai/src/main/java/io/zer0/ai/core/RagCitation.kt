package io.zer0.ai.core

import kotlinx.serialization.Serializable

/**
 * v1.133: RAG Cotización — Para renderizar en ChatUI"¿Qué documentos se citan en esta respuesta?".
 *
 * por [io.zer0.muse.rag.RagService.buildInjectionContextWithCitations] Regresar,
 * ChatViewModel inyectar en [UIMessage.ragCitations] campo,
 * MessageBubble Representado como una cotización en la que se puede hacer clic chip(Haga clic para ampliar snippet,Mantenga presionado para saltar a los detalles del documento.).
 *
 * Colóquelo debajo del paquete ai.core(en vez de muse.rag)es dejar [UIMessage] Se puede cotizar directamente,
 * Evite las dependencias inversas(ai Los módulos no deben depender del módulo de la aplicación.).
 */
@Serializable
data class RagCitation(
    /** Número de referencia(empezar desde 1,Corresponde al mensaje del sistema en [1] [2] ...). */
    val index: Int,
    val docId: String,
    val docTitle: String,
    val chunkId: String,
    val chunkIndex: Int,
    /** fragmento de golpe(primeras 200 palabras,para ampliar la vista previa del chip). */
    val snippet: String,
    /** Puntuación de búsqueda(Similitud de vectores/puntuación RRF/puntuación de reclasificación,depende del camino). */
    val score: Float,
    /** v1.133: tipo de golpe(vector / hybrid / rerank / keyword_fallback). */
    val matchType: String,
    /**
     * v1.133: ¿Es un documento interno?(Documentos de desarrollo preestablecidos devdoc, etc.).
     *
     * por [io.zer0.muse.rag.RagService] Al construir RagCitation a partir de [io.zer0.muse.data.knowledge.KnowledgeDocEntity]
     * entrante,Reemplazo unificado original `docId.startsWith("devdoc-")` Juicios codificados.
     * Falso predeterminado para mantener la compatibilidad con versiones anteriores(Los datos serializados antiguos toman el valor predeterminado al deserializarlos.).
     */
    val isInternal: Boolean = false,
)
