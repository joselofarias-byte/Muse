package io.zer0.muse.data.groupchat

import androidx.room.withTransaction
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

/**
 * Almacén de chat grupal — Encapsulación DAO,Exponer métodos de flujo + suspensión.
 *
 * Responsabilidades:
 *  - chat grupal CRUD(createChat / deleteChat / updateChat / observeChats)
 *  - envío de mensajes(sendMessage)y observación(observeMessages @Deprecated / getPaggedMessages versión paginada)
 *  - Carga de mensajes en páginas(getRecentMessagesPaged Primera pantalla + getOlderMessages Deslice hacia arriba para cargar más)
 *  - Leer mensajes recientes(getRecentMessages)Para que la herramienta/programador del Agente lea el contexto
 *  - memberIds JSON serialización/Asistencia de deserialización
 *
 * Todas las operaciones de base de datos están envueltas en [Dispatchers.IO] en.
 * Operaciones entre mesas(Enviar mensaje + actualizar marca de tiempo, eliminar chat grupal + mensaje en cascada)usar [MuseDb.withTransaction] Atomicidad garantizada.
 *
 * @param db Room base de datos(para transacciones entre mesas)
 * @param groupChatDao chat grupal DAO
 * @param groupChatMessageDao Mensajes de chat grupal DAO
 * @param assistantRepository Assistant almacén(Se utiliza para comprobar los nombres de los miembros.,Opcional)
 * @param messageImageStore v2.x cierre del legado:Almacenamiento unificado de imágenes de mensajes.(Misma instancia que el chat único),
 *        Generar chat grupal en gráfico/La gran base64 del gráfico de usuario se coloca en filesDir/muse_images/,DB Sólo se guardan las referencias file://.
 */
class GroupChatRepository(
    private val db: MuseDb,
    private val groupChatDao: GroupChatDao,
    private val groupChatMessageDao: GroupChatMessageDao,
    private val assistantRepository: AssistantRepository,
    private val messageImageStore: MessageImageStore,
) {

    private val TAG = "GroupChatRepo"

    companion object {
        /** v1.53-GC: Paginación de mensajes de chat grupal — Tamaño de la página de inicio(Número inicial de artículos cargados). */
        const val INITIAL_PAGE_SIZE = 30

        /** v1.53-GC: Paginación de mensajes de chat grupal — Desliza hacia arriba para cargar más tamaños de página. */
        const val LOAD_MORE_PAGE_SIZE = 20
    }

    /** Mira todos los chats grupales(Orden descendente por actualizadoEn). */
    fun observeChats(): Flow<List<GroupChatEntity>> = groupChatDao.observeAll()

    /**
     * Observar los mensajes del chat grupal especificado.(Ordenar por marca de tiempo ascendente,Límite total 200).
     *
     * v1.53-GC: Existe riesgo de OOM al observar completamente chats grupales largos(Imagen base64 de almacenamiento directo DB lista de mensajes largos superpuestos).
     * Utilice un nuevo código en su lugar [getPagedMessages](Por encima del pliegue Flow) + [getRecentMessagesPaged](carga inicial)
     * + [getOlderMessages](Desliza hacia arriba para cargar más)Esquema de paginación. Este método conserva la compatibilidad con versiones anteriores.,No se utiliza en código nuevo.
     */
    @Deprecated(
        "Existe riesgo de OOM al observar completamente chats grupales largos,Usar en su lugar getPagedMessages / getRecentMessagesPaged + getOlderMessages Cargando en páginas.",
        ReplaceWith("getPagedMessages(chatId)"),
    )
    fun observeMessages(chatId: String): Flow<List<GroupChatMessageEntity>> =
        groupChatMessageDao.observeMessages(chatId).map { list -> list.map(::hydrateMessage) }.flowOn(Dispatchers.IO)

    /**
     * v1.53-GC: Observe los últimos mensajes de initialPageSize en el chat grupal especificado(Ordenar por marca de tiempo ascendente,Flow actualizaciones en tiempo real).
     *
     * Sustituir importe total [observeMessages]:Sólo la página más reciente es visible en la primera pantalla de la interfaz de chat.,Flow se reenvía automáticamente cuando llegan nuevos mensajes,
     * ViewModel Agregar incrementalmente a la lista cargada en consecuencia(El historial anterior cargado permanece sin cambios).
     *
     * @param chatId chat grupal id
     * @param initialPageSize Tamaño de la página de inicio,Predeterminado [INITIAL_PAGE_SIZE]
     */
    fun getPagedMessages(chatId: String, initialPageSize: Int = INITIAL_PAGE_SIZE): Flow<List<GroupChatMessageEntity>> =
        groupChatMessageDao.observeRecentMessages(chatId, initialPageSize)
            .map { list -> list.map(::hydrateMessage) }
            .flowOn(Dispatchers.IO)

    /**
     * v1.53-GC: Carga inicial de paginación — Obtenga los últimos mensajes límite del chat grupal especificado(Ordenar por marca de tiempo ascendente).
     *
     * DAO Volver en orden descendente(último primero),aquí reversed() Convertir a orden ascendente,Conveniente para renderizado directo.
     * Se utiliza para cargar la primera pantalla de ViewModel.,Evite el retraso causado por cargar todos los mensajes a la vez/OOM.
     *
     * @param chatId chat grupal id
     * @param limit tamaño de página,Predeterminado [INITIAL_PAGE_SIZE]
     * @return Lista de mensajes(orden ascendente por tiempo)
     */
    suspend fun getRecentMessagesPaged(chatId: String, limit: Int = INITIAL_PAGE_SIZE): List<GroupChatMessageEntity> =
        withContext(Dispatchers.IO) {
            groupChatMessageDao.getRecentMessages(chatId, limit).map(::hydrateMessage).reversed()
        }

    /**
     * v1.53-GC: Paginación para cargar más. — Tomar antes del punto de anclaje(beforeTimestamp, beforeId)limitar mensajes(Ordenar por marca de tiempo ascendente).
     *
     * Solía deslizarse hacia arriba para cargar más.:El primer mensaje de la lista actual (timestamp, id) para puntos de anclaje dobles,Tomemos como ejemplo la historia anterior.
     * DAO Volver en orden descendente(último primero),aquí reversed() Convertir a orden ascendente,Se puede unir previamente directamente a una lista existente.
     * doble punto de anclaje(timestamp + id)Evite que se pierdan o dupliquen mensajes del mismo milisegundo.
     *
     * @param chatId chat grupal id
     * @param beforeTimestamp Marca de tiempo de anclaje(El primer elemento de la lista actual timestamp)
     * @param beforeId mensaje de anclaje id(El primer elemento de la lista actual id,Mismos milisegundos para reutilizar)
     * @param limit tamaño de página,Predeterminado [LOAD_MORE_PAGE_SIZE]
     * @return Lista de mensajes(orden ascendente por tiempo),Una lista vacía significa que no hay antecedentes anteriores.
     */
    suspend fun getOlderMessages(
        chatId: String,
        beforeTimestamp: Long,
        beforeId: String,
        limit: Int = LOAD_MORE_PAGE_SIZE,
    ): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        groupChatMessageDao.getOlderMessages(chatId, beforeTimestamp, beforeId, limit)
            .map(::hydrateMessage)
            .reversed()
    }

    /** Observar los metadatos de un chat grupal específico.(Se utiliza para actualizar el título de la página de detalles.). */
    fun observeChat(id: String): Flow<GroupChatEntity?> = groupChatDao.observeById(id)

    /** Obtenga un chat grupal único por identificación(Desechable). */
    suspend fun getChat(id: String): GroupChatEntity? = withContext(Dispatchers.IO) {
        groupChatDao.getById(id)
    }

    /**
     * Crea un chat grupal.
     *
     * @param name Nombre del chat grupal
     * @param memberIds Lista de ID de asistente de miembro
     * @param teamId Equipo asociado opcional id
     * @return Chat grupal recién creado id
     */
    suspend fun createChat(name: String, memberIds: List<String>, teamId: String? = null): String = withContext(Dispatchers.IO) {
        val chatId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        groupChatDao.upsert(
            GroupChatEntity(
                id = chatId,
                name = name,
                memberIdsJson = serializeMemberIds(memberIds),
                teamId = teamId,
                // De forma predeterminada, el primer miembro se establece como host: si se dejó en blanco antes, el programador encontró que el hostId no estaba entre los miembros.
                // Si se vuelve a turnarse para hablar, la plantilla de "moderador" seleccionada por el usuario no surtirá efecto.
                hostId = memberIds.firstOrNull(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        chatId
    }

    /**
     * Actualizar la información del chat grupal(Nombre/Descripción/miembro/modo de discusión).
     *
     * @param chatId chat grupal id
     * @param name nuevo nombre(null Indica que no hay actualización)
     * @param description nueva descripción(null Indica que no hay actualización)
     * @param memberIds Nueva lista de miembros(null Indica que no hay actualización)
     * @param discussionMode modo de discusión(null Indica que no hay actualización)
     * @param autoMaxRounds Auto El número máximo de rondas en el modo.(null Indica que no hay actualización)
     * @param hostId Moderador AI id(null Indica que no hay actualización;pasar "" Borrar)
     * @param replyLengthMode Rango de longitud del discurso(brief/standard/detailed,null Indica que no hay actualización)
     * @param observerIds Lista de identificaciones de miembros observadores(No participe en la rotación de oradores predeterminada.,null Indica que no hay actualización)
     */
    suspend fun updateChat(
        chatId: String,
        name: String? = null,
        description: String? = null,
        memberIds: List<String>? = null,
        discussionMode: String? = null,
        autoMaxRounds: Int? = null,
        hostId: String? = null,
        replyLengthMode: String? = null,
        observerIds: List<String>? = null,
    ) = withContext(Dispatchers.IO) {
        // M2: Leer con contenedor de transacción-cambiar-escribir,Evitar la pérdida de actualizaciones simultáneas
        db.withTransaction {
            val existing = groupChatDao.getById(chatId) ?: return@withTransaction
            val updated = existing.copy(
                name = name ?: existing.name,
                description = description ?: existing.description,
                memberIdsJson = memberIds?.let { serializeMemberIds(it) } ?: existing.memberIdsJson,
                discussionMode = discussionMode ?: existing.discussionMode,
                autoMaxRounds = autoMaxRounds ?: existing.autoMaxRounds,
                hostId = if (hostId != null) hostId.ifBlank { null } else existing.hostId,
                replyLengthMode = replyLengthMode ?: existing.replyLengthMode,
                observerIdsJson = observerIds?.let { serializeObserverIds(it) } ?: existing.observerIdsJson,
                updatedAt = System.currentTimeMillis(),
            )
            groupChatDao.upsert(updated)
        }
    }

    /**
     * Enviar un mensaje a un chat grupal.
     *
     * @param chatId chat grupal id
     * @param senderType Tipo de remitente: "user" / "assistant"
     * @param senderId remitente id(userId o assistantId)
     * @param senderName Nombre para mostrar del remitente(caché)
     * @param body Texto del mensaje
     * @param imageBase64Json Lista base64 de archivos adjuntos de imágenes(JSON cuerda,Predeterminado "[]")
     * @param mood Agent emociones(Opcional)
     * @param reasoning Agent proceso de pensamiento(Opcional)
     * @param videoFileUri v2.x cierre del legado:generate_video Dirección de vídeo generada(assistant noticias,Predeterminado null)
     * @return nuevas noticias id;Regresa cuando el chat grupal no existe null(No en stock,La persona que llama debe manejar)
     */
    suspend fun sendMessage(
        chatId: String,
        senderType: String,
        senderId: String,
        senderName: String,
        body: String,
        imageBase64Json: String = "[]",
        fileAttachmentsJson: String = "[]",
        mood: String? = null,
        reasoning: String? = null,
        whisperTargetId: String? = null,
        replyToId: String? = null,
        messageType: String = "normal",
        videoFileUri: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val msgId = UUID.randomUUID().toString()
        // v2.x cierre del legado: Colocación de archivos adjuntos de imágenes — Base64 larga escrita a través de MessageImageStore filesDir/muse_images/,
        // DB Guarde solo las referencias del archivo://(Misma estrategia que SessionRepository de chat único),Evite que las líneas de mensajes del chat grupal se guarden en MB base64.
        // Complete la escritura del archivo fuera de la transacción.,Evite envolver el archivo IO en transacciones de base de datos.
        val persistableImageJson = runCatching {
            val images = AppJson.decodeFromString(ListSerializer(String.serializer()), imageBase64Json)
            if (images.isEmpty()) {
                imageBase64Json
            } else {
                AppJson.encodeToString(
                    ListSerializer(String.serializer()),
                    messageImageStore.toPersistable(msgId, images),
                )
            }
        }.getOrElse { imageBase64Json }
        // H-GC2: Insertar mensaje + actualizar la marca de tiempo de la sesión debe ser atómico,Evite fallas después de que se haya escrito el mensaje pero la marca de tiempo no se haya actualizado
        // Reparación de auditoría (5.2): Regresa cuando el chat grupal no existe null,Ya no vuelve a colgar msgId(La persona que llama cree que la transmisión fue exitosa
        // Biblioteca de rechazo real,Todas las referencias posteriores a esta identificación no serán válidas.).
        val saved = db.withTransaction {
            // M6: Verificar que exista el chat grupal,Prevenir mensajes huérfanos(Verifique dentro de las transacciones para evitar condiciones de carrera)
            if (groupChatDao.getById(chatId) == null) return@withTransaction false
            groupChatMessageDao.upsert(
                GroupChatMessageEntity(
                    id = msgId,
                    chatId = chatId,
                    senderType = senderType,
                    senderId = senderId,
                    senderName = senderName,
                    body = body,
                    imageBase64Json = persistableImageJson,
                    videoFileUri = videoFileUri,
                    fileAttachmentsJson = fileAttachmentsJson,
                    timestamp = System.currentTimeMillis(),
                    mood = mood,
                    reasoning = reasoning,
                    whisperTargetId = whisperTargetId,
                    replyToId = replyToId,
                    messageType = messageType,
                ),
            )
            // Actualizar chat grupal updatedAt(Para ordenar páginas de lista)
            groupChatDao.touchUpdatedAt(chatId)
            // v1.107 Redundancia: Actualizar campos redundantes del chat grupal(Vista previa del último mensaje + recuento + tiempo de la última actividad)
            val preview = body.take(50).ifBlank { "…" }
            resultOf {
                groupChatDao.updateLastMessageAndCount(chatId, preview, 1, System.currentTimeMillis())
            }.onError { msg, _ -> Logger.w(TAG, "updateLastMessageAndCount failed: $msg") }
            true
        }
        if (saved) msgId else null
    }

    /**
     * Eliminar chat grupal(Elimina en cascada todos sus mensajes.).
     *
     * H-GC1: Eliminar entre tablas(Mensaje + Chat grupal)Envolver con @Transaction,Atomicidad garantizada.
     *
     * @param chatId chat grupal id
     */
    suspend fun deleteChat(chatId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            groupChatMessageDao.deleteByChat(chatId)
            groupChatDao.deleteById(chatId)
        }
    }

    /**
     * v1.77: Eliminar un solo mensaje en un chat grupal.
     *
     * @param messageId noticias id
     */
    suspend fun deleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        // Reparación de auditoría (5.3): Recalcular campos redundantes después de la eliminación(Vista previa del último mensaje/contar/Hora de la última actividad).
        // La implementación original solo elimina mensajes.,Después de eliminar las últimas noticias, la vista previa de la página de la lista todavía muestra el contenido eliminado y el recuento es artificialmente alto.
        db.withTransaction {
            val msg = groupChatMessageDao.getById(messageId)
            if (msg == null) return@withTransaction
            val chatId = msg.chatId
            groupChatMessageDao.deleteById(messageId)
            val last = groupChatMessageDao.getLatest(chatId)
            val count = groupChatMessageDao.countByChat(chatId)
            if (last != null) {
                groupChatDao.updateLastMessageAndCount(
                    chatId,
                    last.body.take(50).ifBlank { "…" },
                    count,
                    last.timestamp,
                )
            } else {
                groupChatDao.updateLastMessageAndCount(chatId, "", 0, 0)
            }
        }
    }

    /**
     * Cambia el chat grupal al estado superior.
     *
     * @param chatId chat grupal id
     */
    suspend fun togglePin(chatId: String) = withContext(Dispatchers.IO) {
        // M2: Leer con contenedor de transacción-cambiar-escribir,Evitar la pérdida de actualizaciones simultáneas
        db.withTransaction {
            val existing = groupChatDao.getById(chatId) ?: return@withTransaction
            groupChatDao.upsert(existing.copy(pinned = !existing.pinned))
        }
    }

    /**
     * v2.x: Alternar el estado del archivo del chat grupal.
     *
     * @param chatId chat grupal id
     * @param archived Archivo o no
     */
    suspend fun toggleArchive(chatId: String, archived: Boolean) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val existing = groupChatDao.getById(chatId) ?: return@withTransaction
            groupChatDao.upsert(existing.copy(isArchived = archived))
        }
    }

    /**
     * Obtenga los últimos N mensajes del chat grupal especificado(Regresar en orden ascendente por marca de tiempo,Para que el agente lea el contexto).
     *
     * @param chatId chat grupal id
     * @param limit Límite superior del número de artículos.,Predeterminado 20
     * @return Lista de mensajes(orden ascendente por tiempo)
     */
    suspend fun getRecentMessages(chatId: String, limit: Int = 20): List<GroupChatMessageEntity> = withContext(Dispatchers.IO) {
        // DAO Presione DESC para obtener los últimos N elementos,Regresar en orden ascendente después de invertir(Fácil de leer secuencialmente)
        groupChatMessageDao.getRecentMessages(chatId, limit).map(::hydrateMessage).reversed()
    }

    /** v1.0.72: Buscar mensajes en el chat grupal(Texto de coincidencia de palabras clave,en orden cronológico inverso). */
    suspend fun searchMessages(chatId: String, query: String, limit: Int = 100): List<GroupChatMessageEntity> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            groupChatMessageDao.searchMessages(chatId, query.trim(), limit).map(::hydrateMessage)
        }

    /** Obtenga el último mensaje del chat grupal especificado(Para obtener una vista previa de la página de lista). */
    suspend fun getLatestMessage(chatId: String): GroupChatMessageEntity? = withContext(Dispatchers.IO) {
        groupChatDao.getLatestMessage(chatId)
    }

    /** Cuente la cantidad de mensajes en el chat grupal especificado. */
    suspend fun countMessages(chatId: String): Int = withContext(Dispatchers.IO) {
        groupChatDao.countMessages(chatId)
    }

    // ── memberIds JSON Asistencia de serialización ──

    /**
     * v2.x cierre del legado:Coloque el campo de archivo adjunto de imagen en DB(file:// cita o viejo base64)normalizar para renderizar/La forma requerida por el contexto.
     *
     * Escribir escritura paralela [MessageImageStore.toPersistable] Coloque la base64 larga en el disco como ruta file://(DB Guardar solo referencias),
     * En el lado de lectura, restaure el archivo:// a base64(suplemento data:image/png;base64, prefijo,Representador de chat con grupo
     * MessageImageGrid/SmartImage consistente con las expectativas);http(s)/El valor que ya es un URI de datos se deja intacto.
     * Chatea con Dan [io.zer0.muse.data.session.SessionRepository] imageBase64List está normalizado con la misma estrategia.
     */
    private fun hydrateMessage(message: GroupChatMessageEntity): GroupChatMessageEntity {
        val json = message.imageBase64Json
        if (json.isBlank() || json == "[]") return message
        val raw = resultOf {
            AppJson.decodeFromString(ListSerializer(String.serializer()), json)
        }.getOrNull() ?: return message
        if (raw.isEmpty()) return message
        val hydrated = messageImageStore.toBase64List(raw).mapNotNull { value ->
            when {
                value.isBlank() -> null
                value.startsWith("data:") || value.startsWith("http://") ||
                    value.startsWith("https://") || value.startsWith("file:") ||
                    value.startsWith("/") -> value
                else -> "data:image/png;base64,$value"
            }
        }
        return message.copy(
            imageBase64Json = AppJson.encodeToString(ListSerializer(String.serializer()), hydrated),
        )
    }

    /** Serializar una lista de memberIds en una cadena JSON. */
    fun serializeMemberIds(memberIds: List<String>): String {
        return AppJson.encodeToString(ListSerializer(String.serializer()), memberIds)
    }

    /** Deserialice memberIdsJson en una lista. */
    fun parseMemberIds(chat: GroupChatEntity): List<String> {
        if (chat.memberIdsJson.isBlank() || chat.memberIdsJson == "[]") return emptyList()
        // L3: Utilice resultOf en su lugar runCatching,Evite tragar excepciones.
        return resultOf {
            AppJson.decodeFromString(ListSerializer(String.serializer()), chat.memberIdsJson)
        }.getOrNull() ?: emptyList()
    }
    // ── B5-02: El chat grupal genera un libro mayor(Una vez finalizado el proceso, presione el punto de interrupción para reproducir) ──

    suspend fun upsertGenerationLedger(entity: GroupChatGenerationLedgerEntity) {
        withContext(Dispatchers.IO) { db.groupChatGenerationLedgerDao().upsert(entity) }
    }

    suspend fun deleteGenerationLedger(id: String) {
        withContext(Dispatchers.IO) { db.groupChatGenerationLedgerDao().deleteById(id) }
    }

    suspend fun deleteGenerationLedgersByChatId(chatId: String) {
        withContext(Dispatchers.IO) { db.groupChatGenerationLedgerDao().deleteByChatId(chatId) }
    }

    suspend fun getGenerationLedger(id: String): GroupChatGenerationLedgerEntity? =
        withContext(Dispatchers.IO) { db.groupChatGenerationLedgerDao().getById(id) }

    suspend fun getPendingGenerationLedgers(): List<GroupChatGenerationLedgerEntity> =
        withContext(Dispatchers.IO) { db.groupChatGenerationLedgerDao().getPending() }

    // ── v2.x Gestión del contexto del chat grupal:Documentos compartidos en grupo ──

    /** Serializar una lista de documentos compartidos de grupo en una cadena JSON. */
    fun serializeSharedDocs(docs: List<GroupSharedDoc>): String {
        return AppJson.encodeToString(GroupSharedDoc.serializer().let { kotlinx.serialization.builtins.ListSerializer(it) }, docs)
    }

    /** Deserialice SharedDocsJson en una lista. */
    fun parseSharedDocs(chat: GroupChatEntity): List<GroupSharedDoc> {
        if (chat.sharedDocsJson.isBlank() || chat.sharedDocsJson == "[]") return emptyList()
        return resultOf {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(GroupSharedDoc.serializer()),
                chat.sharedDocsJson,
            )
        }.getOrNull() ?: emptyList()
    }

    /** v2.x: Analizar la lista de identificación de miembros observadores(No participe en la rotación de oradores predeterminada.). */
    fun parseObserverIds(chat: GroupChatEntity): List<String> {
        if (chat.observerIdsJson.isBlank() || chat.observerIdsJson == "[]") return emptyList()
        return resultOf {
            AppJson.decodeFromString(ListSerializer(String.serializer()), chat.observerIdsJson)
        }.getOrNull() ?: emptyList()
    }

    /** v2.x: Lista serializada de identificaciones de miembros observadores. */
    fun serializeObserverIds(ids: List<String>): String {
        return AppJson.encodeToString(ListSerializer(String.serializer()), ids)
    }

    /**
     * v2.x: Agregue un documento compartido de grupo.
     *
     * @param chatId chat grupal id
     * @param title Título del documento
     * @param content Texto del documento
     * @return nuevo documento id(Retorno del fracaso null)
     */
    suspend fun addSharedDoc(chatId: String, title: String, content: String): String? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val chat = groupChatDao.getById(chatId) ?: return@withTransaction null
            val docs = parseSharedDocs(chat).toMutableList()
            val docId = UUID.randomUUID().toString()
            docs.add(
                GroupSharedDoc(
                    id = docId,
                    title = title.take(200),
                    content = content,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            val updated = chat.copy(
                sharedDocsJson = serializeSharedDocs(docs),
                updatedAt = System.currentTimeMillis(),
            )
            groupChatDao.upsert(updated)
            docId
        }
    }

    /**
     * v2.x: Eliminar un documento compartido en grupo.
     *
     * @param chatId chat grupal id
     * @param docId Documentación id
     */
    suspend fun removeSharedDoc(chatId: String, docId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val chat = groupChatDao.getById(chatId) ?: return@withTransaction
            val docs = parseSharedDocs(chat).filterNot { it.id == docId }
            val updated = chat.copy(
                sharedDocsJson = serializeSharedDocs(docs),
                updatedAt = System.currentTimeMillis(),
            )
            groupChatDao.upsert(updated)
        }
    }

    // ── v2.x Gestión del contexto del chat grupal:AI Contexto exclusivo ──

    /** Serializar el mapa de contexto específico del miembro en una cadena JSON. */
    fun serializeMemberPrivateContext(map: Map<String, String>): String {
        return AppJson.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(
                String.serializer(),
                String.serializer(),
            ),
            map,
        )
    }

    /** Deserializar memberPrivateContextJson al mapa. */
    fun parseMemberPrivateContext(chat: GroupChatEntity): Map<String, String> {
        if (chat.memberPrivateContextJson.isBlank() || chat.memberPrivateContextJson == "{}") return emptyMap()
        return resultOf {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.MapSerializer(
                    String.serializer(),
                    String.serializer(),
                ),
                chat.memberPrivateContextJson,
            )
        }.getOrNull() ?: emptyMap()
    }

    /**
     * v2.x: Establezca un contexto exclusivo para un miembro del grupo.
     *
     * @param chatId chat grupal id
     * @param assistantId miembro AI id
     * @param contextText Texto contextual exclusivo(Pasar una cadena vacía borra el contexto exclusivo del miembro.)
     */
    suspend fun setMemberPrivateContext(chatId: String, assistantId: String, contextText: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val chat = groupChatDao.getById(chatId) ?: return@withTransaction
            val map = parseMemberPrivateContext(chat).toMutableMap()
            if (contextText.isBlank()) {
                map.remove(assistantId)
            } else {
                map[assistantId] = contextText
            }
            val updated = chat.copy(
                memberPrivateContextJson = serializeMemberPrivateContext(map),
                updatedAt = System.currentTimeMillis(),
            )
            groupChatDao.upsert(updated)
        }
    }
}
