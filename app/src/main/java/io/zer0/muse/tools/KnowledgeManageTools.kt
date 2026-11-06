package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.rag.RagService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Conjunto de herramientas de gestión de la base de conocimientos —— Permita que el asistente escriba en la base de conocimientos.
 *
 * Anteriormente, el asistente solo tenía Knowledge_search (búsqueda de solo lectura) para la base de conocimientos y eliminaba el documento./Eliminar base de datos/Construir una base de datos/hacer una lista
 * no puedo hacerlo todo——Prueba real del usuario"Incluso si le pido a AI que lo elimine, no se eliminará, e incluso si intento reemplazarlo con Agnes/DeepSeek, no funcionará.".
 *
 * denominación unificada `knowledge_`, con `app_*`(control de aplicaciones),`screen_*`(UI Automatización) paralelo:
 *  - `knowledge_list_docs`   Enumere todos los documentos en la base de conocimientos especificada (SAFE, sólo lectura)
 *  - `knowledge_delete_doc`  Eliminar un documento (HIGH, pasar por aprobación)
 *  - `knowledge_create_kb`   Crear una base de conocimientos (HIGH)
 *  - `knowledge_delete_kb`   Eliminar base de conocimientos (HIGH, eliminar todos sus documentos juntos)
 *
 * Restricciones de diseño:
 *  - No disponible"Cambiar el contenido del documento"——eso es una importacion/Responsabilidad de volver a indexar, es fácil introducir datos sucios mediante la corrección de herramientas;
 *  - Base de conocimientos predeterminada (id=default) no se puede eliminar, de acuerdo con la protección de KnowledgeScreen en la capa de UI;
 *  - Elimine documentos y borre fragmentos e índices vectoriales al mismo tiempo (reutilizando RagService.deleteDocument), sin dejar huérfanos.
 */
class KnowledgeManageTools(
    private val docDao: KnowledgeDocDao,
    private val kbDao: KnowledgeBaseDao,
    private val ragService: RagService,
) {
    companion object {
        private const val TAG = "KnowledgeManageTools"
    }

    fun register(registry: ToolRegistry) {
        registerListDocs(registry)
        registerDeleteDoc(registry)
        registerCreateKb(registry)
        registerDeleteKb(registry)
    }

    /** Enumere los documentos en la base de conocimientos (SAFE, sólo lectura). */
    private fun registerListDocs(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_list_docs",
                description = "Enumere todos los documentos de usuario para la base de conocimientos especificada (id, título, número de fragmentos, tiempo de actualización)." +
                    "Si no se pasa kb_id, aparece la base de conocimientos predeterminada. Se utiliza para confirmar la identificación del documento antes de eliminarlo.",
                parameters = mapOf(
                    "kb_id" to "Opcional, identificación de la base de conocimientos; cuando se omite, se busca en la base de conocimientos predeterminada",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val kbId = args["kb_id"]?.trim().takeUnless { it.isNullOrBlank() } ?: "default"
            val docs = kotlinx.coroutines.runBlocking { docDao.getByKbIds(listOf(kbId)) }
            if (docs.isEmpty()) {
                "base de conocimientos $kbId No hay documentación de usuario en ."
            } else {
                docs.joinToString("\n") { doc ->
                    "${doc.id} | ${doc.title} | ${doc.chunkCount} bloquear | ${doc.fileType}"
                }
            }
        }
    }

    /** Eliminar un documento de la base de conocimientos (HIGH, pasar por el proceso de aprobación). */
    private fun registerDeleteDoc(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_delete_doc",
                description = "Eliminar un documento (junto con sus fragmentos y su índice vectorial) de la base de conocimientos." +
                    "La identificación del documento debe confirmarse con Knowledge_list_docs antes de la ejecución; no se puede revocar.",
                parameters = mapOf(
                    "doc_id" to "Requerido, identificación del documento (se puede obtener con Knowledge_list_docs)",
                ),
                required = setOf("doc_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val docId = args["doc_id"]?.trim().orEmpty()
            if (docId.isBlank()) {
                errorJson("El parámetro doc_id falta o está vacío")
            } else {
                kotlinx.coroutines.runBlocking {
                    val doc = docDao.getById(docId)
                    when {
                        doc == null -> errorJson("Documento no encontrado $docId")
                        doc.isInternal -> errorJson("Documentación $docId Es un documento de desarrollo interno y no se permite eliminarlo mediante herramientas.")
                        else -> {
                            ragService.deleteDocument(docId)
                            Logger.i(TAG, "Herramienta para eliminar documentos de la base de conocimientos.: $docId (${doc.title})")
                            okJson("Documento eliminado ${doc.title}($docId), la fragmentación y la indexación de vectores se limpian juntas.")
                        }
                    }
                }
            }
        }
    }

    /** Crear una base de conocimientos (HIGH). */
    private fun registerCreateKb(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_create_kb",
                description = "Crear una nueva base de conocimientos.id Puede omitirse (generarse automáticamente); Se requiere nombre." +
                    "Base de conocimientos predeterminada (id=default) no se puede crear repetidamente.",
                parameters = mapOf(
                    "name" to "Obligatorio, nombre de la base de conocimientos",
                    "kb_id" to "Opcional, identificación de la base de conocimientos (generada automáticamente cuando se omite)",
                    "description" to "Opcional, descripción de la base de conocimientos.",
                ),
                required = setOf("name"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val name = args["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                errorJson("El nombre del parámetro falta o está vacío")
            } else {
                val id = args["kb_id"]?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "kb-${System.currentTimeMillis()}"
                kotlinx.coroutines.runBlocking {
                    val existing = kbDao.getById(id)
                    if (existing != null) {
                        errorJson("base de conocimientos id=$id Ya existe (nombre:${existing.name}), cambie una identificación o úsela directamente")
                    } else {
                        kbDao.upsert(
                            KnowledgeBaseEntity(
                                id = id,
                                name = name,
                                description = args["description"]?.trim().orEmpty(),
                            ),
                        )
                        Logger.i(TAG, "Herramientas para crear una base de conocimientos: $id ($name)")
                        okJson("Base de conocimientos creada$name\"(id=$id). Ahora puedes importar documentos en él.")
                    }
                }
            }
        }
    }

    /** Eliminar base de conocimientos (HIGH, elimine también todos sus documentos y carpetas). */
    private fun registerDeleteKb(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_delete_kb",
                description = "Elimine una base de conocimientos y todos sus documentos, carpetas e índices vectoriales. Irrevocable." +
                    "Base de conocimientos predeterminada (id=default) no se puede eliminar.",
                parameters = mapOf(
                    "kb_id" to "Requerido, ID de la base de conocimientos (confirmar antes de usar Knowledge_list_docs)",
                ),
                required = setOf("kb_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val kbId = args["kb_id"]?.trim().orEmpty()
            when {
                kbId.isBlank() -> errorJson("El parámetro kb_id falta o está vacío")
                kbId == "default" -> errorJson("La base de conocimientos predeterminada no se puede eliminar (de acuerdo con la protección de la capa de la interfaz de usuario)")
                else -> {
                    kotlinx.coroutines.runBlocking {
                        val kb = kbDao.getById(kbId)
                        if (kb == null) {
                            errorJson("Base de conocimientos no encontrada $kbId")
                        } else {
                            val docs = docDao.getByKbIds(listOf(kbId))
                            docs.forEach { doc -> ragService.deleteDocument(doc.id) }
                            kbDao.delete(kbId)
                            Logger.i(TAG, "Herramienta para eliminar la base de conocimientos: $kbId (${kb.name}), Documentación ${docs.size} Capítulo")
                            okJson("Base de conocimientos eliminada \"${kb.name}\"($kbId) y su ${docs.size} documentos.")
                        }
                    }
                }
            }
        }
    }

    private fun okJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    private fun errorJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(message))
    }.toString()
}

/** Registrador: Inyección de Koin, guiada uniformemente por AutomationInitializer. */
class KnowledgeManageToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tools: KnowledgeManageTools,
) {
    init {
        tools.register(toolRegistry)
    }
}
