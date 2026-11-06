package io.zer0.muse.data.session

import android.content.Context
import androidx.room.withTransaction
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.RagCitation
import io.zer0.ai.core.UIMessage
import io.zer0.common.ErrorMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.chat.orderConversationMessages
import io.zer0.muse.data.chat.rewrite.ConversationEventDraft
import io.zer0.muse.data.chat.rewrite.ConversationEventType
import io.zer0.muse.data.chat.rewrite.ConversationProjector
import io.zer0.muse.data.chat.rewrite.ConversationRebuildFlagStore
import io.zer0.muse.data.chat.rewrite.MessageCommitRequest
import io.zer0.muse.data.chat.rewrite.MessageCommitResult
import io.zer0.muse.session.TurnPhase
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.transformer.InternalMarkupSanitizer
import io.zer0.muse.transformer.MoodSkinParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * repositorio de sesiones:Encapsulación SessionDao + MessageDao,Proporciona API de modelo de dominio.
 *
 * - Lista de sesiones/crear/eliminar/cambiar
 * - Persistencia de mensajes(Escribir biblioteca al enviar,Transmisión de actualizaciones incrementales assistant,Editar/regenerar truncamiento)
 * - [MessageEntity] ↔ [UIMessage] conversión bidireccional
 *
 * H-SESS1: Operaciones entre mesas(messages + messages_fts + sessions)Para uso unificado [database.withTransaction]
 * paquete,Garantice la coherencia de los datos después de un accidente;[database] Inyectado por Koin(ver AppKoinModule).
 *
 * ── i18n Demostración de transformación(v1.90 cerrar,5 completados)──────────────────────────────
 * Esta clase sirve como punto de transformación de demostración para el esqueleto de la arquitectura i18n.,Muestra cómo reemplazar mensajes de registro/captura codificados con
 * [ErrorMessage] subclase. Se han implementado todos los puntos de transformación de demostración 1 a 5.(Ver a todos `// i18n Punto de transformación de demostración. N` marca),
 * Los puntos restantes a migrar se pueden promocionar gradualmente según este modelo.:
 *   1. catch / resultOf.onError en:usar `Logger.w(TAG, ErrorMessage.X.toLogString(), t)`
 *      Reemplazar mensajes desnudos en chino e inglés([toLogString] salida [code] + ingles para decir la verdad,Se puede talar y enterrar)
 *   2. Errores arrojados a la interfaz de usuario:Regresar `io.zer0.common.Result.Error` paquete [ErrorMessage]
 *   3. UI Para capa [io.zer0.muse.ui.toLocalizedString] Traducido a la cadena de configuración regional actual
 * ──────────────────────────────────────────────────────────────────────
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val database: MuseDb,
    private val context: Context,
    private val messageImageStore: MessageImageStore,
    /** v1.135-A: Limpieza de caché asistida visualmente(Limpiar cuando se elimina la sesión sidecar). */
    private val visionCache: io.zer0.muse.vision.VisionCache? = null,
    /** P3: Limpieza del modo de permiso de sesión(Limpiar la configuración de persistencia cuando se elimina la sesión). */
    private val sessionPermissionStore: SessionPermissionStore? = null,
    /** P2-4: Registrador de auditoría(Operaciones de usuario como la eliminación de sesiones.). */
    private val auditLogger: AuditLogger? = null,
    /**
     * Limpieza del lado de la memoria después de la eliminación de la sesión(Resumen/punto de control/hechos).
     *
     * Colóquelo en la capa del almacén en lugar del controlador: HostWebSocketGateway,SessionManageTools,
     * RecentlyDeletedScreen,A2aHandler Llame directamente al método de eliminación de esta clase,Todas las entradas que bajan aquí quedan automáticamente cubiertas.
     * null salto de tiempo(prueba única/Sin entorno DI).
     */
    private val sessionMemoryCleanup: io.zer0.muse.data.memory.SessionMemoryCleanup? = null,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val messageFtsDao: MessageFtsDao get() = database.messageFtsDao()
    private val urlListSerializer = ListSerializer(String.serializer())

    /** v1.133: RAG Serializador de lista de referencia(persistir en messages.ragCitationsJson). */
    private val ragCitationListSerializer = ListSerializer(RagCitation.serializer())

    /**
     * v2.0: Room Los singleton pueden retener brevemente conexiones SQLite cerradas después de una restauración de copia de seguridad.
     * Realice un reintento de reapertura solo para errores cerrados explícitos,Evite que se repitan errores comunes de la base de datos;
     * Active la misma instancia de Room para que se vuelva a abrir a través de openHelper.writableDatabase antes de volver a intentarlo.
     */
    private suspend fun <T> withDatabaseRecovery(operation: String, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: Exception) {
            if (!isClosedDatabaseError(e)) throw e
            Logger.w(TAG, "$operation Encontré una conexión de base de datos cerrada,Intenta reabrir y vuelve a intentarlo.", e)
            withContext(Dispatchers.IO) { database.openHelper.writableDatabase }
            delay(50L)
            return block()
        }
    }

    private fun isClosedDatabaseError(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return "connection is closed" in message ||
            "database is closed" in message ||
            "database not open" in message ||
            "cannot perform this operation because there is no current transaction" in message
    }

    companion object {
        private const val TAG = "SessionRepo"

        /** H-SESS3: observeBySession Número predeterminado de elementos cargados,Funciona con carga paginada para evitar OOM. */
        private const val OBSERVE_LIMIT = 200

        /** Vista previa de la sesión(lastMessagePreview)longitud máxima. */
        private const val MESSAGE_PREVIEW_LENGTH = 50

        /** El primer mensaje de usuario recibe un nombre automáticamente según la longitud del prefijo del contenido. */
        private const val AUTO_TITLE_LENGTH = 20

        /** El número de palabras reservadas antes y después de la posición coincidente del fragmento de búsqueda. */
        private const val SNIPPET_RADIUS = 30

        /** Cuando no hay ninguna coincidencia en el segmento de búsqueda, recurra a la longitud del prefijo. */
        private const val SNIPPET_FALLBACK_LENGTH = 60

        /** C-23: FTS Número de comprobaciones aleatorias de la coherencia del contenido. */
        private const val FTS_SAMPLE_COUNT = 10

        /**
         * A9: Ventana conservadora para convergencia de giro colgante.(milisegundos).
         *
         * solo `startedAt` Se considera que el proceso anterior dejó atrás el turno abierto de esta ventana antes del tiempo de inicio. Toma 15 minutos:
         * Una sola herramienta de vídeo puede tardar varios minutos en generarse a la vez.,No es raro esperar más de 10 minutos para varias rondas de herramientas y aprobaciones.,No importa lo corta que sea la ventana
         * Existe el riesgo de interrumpir accidentalmente el turno en curso. No se permite establecerlo en 0 —— Eso cambiará el giro recién creado por este proceso.
         */
        const val STALE_TURN_GRACE_MS = 15 * 60 * 1000L

        /** C-23: El límite superior de la cantidad de tokens utilizados para construir la detección MATCH para cada muestra durante las verificaciones puntuales de contenido. */
        private const val FTS_PROBE_TOKEN_LIMIT = 3

        /** Keep each Room `IN` query below SQLite's bind-argument limit. */
        private const val SESSION_ID_QUERY_BATCH_SIZE = 900

        /**
         * v2.6.13: El valor centinela de FolderId para la sesión privada de colección de tarjetas.
         *
         * Los ID de las carpetas reales son todos UUID y esta constante no entrará en conflicto con ellos; al mismo tiempo, no cambia con el idioma.
         * La misma sesión aún se reutiliza después de cambiar el idioma de la interfaz.
         */
        private const val CARD_FAVORITE_SESSION_MARKER = "__card_favorites__"

        /**
         * v2.6.13: Noticias estables sobre entradas de colecciones de tarjetas. id —— Derivado de la identificación de la tarjeta.
         *
         * ¿Por qué no utilizar UUID ya preparados? Las colecciones son idempotentes (hacer clic dos veces en la misma tarjeta no debería convertirse en dos).
         * Cuando la clave principal del mensaje entra en conflicto, es REEMPLAZAR, por lo que mientras la identificación sea estable, la colección duplicada naturalmente será una.
         * Utilice nombreUUIDFromBytes(UUID v3) para garantizar que el mismo cardId siempre obtenga la misma identificación.
         */
        private fun cardFavoriteMessageId(cardId: String): Uuid {
            val bytes = java.security.MessageDigest.getInstance("MD5")
                .digest(("muse-card-favorite:" + cardId).toByteArray(Charsets.UTF_8))
            // Marque v3 según RFC 4122 para evitar obtener una identificación heterogénea que se parezca a v4.
            bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x30).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
            val hex = bytes.joinToString("") { "%02x".format(it) }
            return Uuid.parse(
                "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                    "${hex.substring(16, 20)}-${hex.substring(20, 32)}",
            )
        }
    }

    /**
     * Observar conversaciones no archivadas(Máxima prioridad,Luego presione actualizado en en orden descendente). Para la lista principal.
     * v1.28: Excluir sesiones de la pestaña Agente(isAgentSession=0),Agent El chat diario no contamina la lista de tareas pendientes.
     */
    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeTaskSessions()

    /** Mira todas las conversaciones,para respaldo/Se utiliza para portales de gestión de datos como exportación selectiva. */
    fun observeAllSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()

    /** Obtener sesión por id(Se utiliza en escenarios de navegación únicos, como enlaces profundos de notificación.). */
    suspend fun getSessionById(sessionId: String): SessionEntity? = sessionDao.getById(sessionId)

    /**
     * v2.5.3 (P3-2): Escriba una instantánea de configuración a nivel de sesión. Solo se toma una vez cuando se genera por primera vez (las instantáneas existentes no se sobrescribirán).
     *
     * @return true Indica que esta escritura es efectiva;false Indica que la sesión ya tiene una instantánea (mantener“Configuración en ese momento”) o error de escritura.
     */
    suspend fun saveConfigSnapshotIfAbsent(sessionId: String, snapshot: SessionConfigSnapshot): Boolean {
        val existing = resultOf { sessionDao.getById(sessionId) }.getOrNull()
        val encoded = SessionConfigSnapshot.encode(snapshot)
        val alreadyCaptured = existing == null || !existing.configSnapshotJson.isNullOrBlank()
        return if (alreadyCaptured || encoded == null) {
            false
        } else {
            resultOf { sessionDao.setConfigSnapshot(sessionId, encoded) }.isSuccess
        }
    }

    /** v2.5.3 (P3-2): Lectura de instantáneas de configuración a nivel de sesión; no tomado/Los datos incorrectos devuelven nulo. */
    suspend fun getConfigSnapshot(sessionId: String): SessionConfigSnapshot? =
        SessionConfigSnapshot.decode(resultOf { sessionDao.getById(sessionId) }.getOrNull()?.configSnapshotJson)

    /** Obtener sesiones en lotes por id,Conservar el orden de las solicitudes e ignorar los duplicados/Identificación vacía. */
    /**
     * Guarde borradores de entrada para las sesiones (anulaciones, por sesión en la columna sesiones.input_draft).
     *
     * La implementación anterior está escrita en el mapa del almacén de datos global y solo guarda pero no lee (loadChatDraft nunca es llamado),
     * Se muestra como“El borrador se ha ido para siempre.”. Aquí se cambia para persistir con la sesión misma:
     *  - Cuando se elimina la sesión, los borradores desaparecen y no quedan datos sucios;
     *  - La lectura y la carga de la sesión están en el mismo enlace y se determina el tiempo de recuperación.
     */
    suspend fun saveInputDraft(sessionId: String, draft: String) {
        withContext(Dispatchers.IO) {
            runCatching { sessionDao.updateInputDraft(sessionId, draft) }
                .onFailure { Logger.w(TAG, "No se pudo guardar el borrador de entrada: ${it.message}") }
        }
    }

    /**
     * Leer el borrador de entrada de la sesión (devuelve una cadena vacía si no hay ningún borrador).
     *
     * Devuelve una cadena vacía cuando la base de datos no está disponible y nunca permite que las lecturas de borrador interrumpan la carga de la sesión.
     */
    suspend fun loadInputDraft(sessionId: String): String = withContext(Dispatchers.IO) {
        runCatching { sessionDao.getById(sessionId)?.inputDraft.orEmpty() }
            .onFailure { Logger.w(TAG, "No se pudo leer el borrador de entrada: ${it.message}") }
            .getOrDefault("")
    }

    suspend fun getSessionsByIds(sessionIds: Collection<String>): List<SessionEntity> = withContext(Dispatchers.IO) {
        val uniqueIds = sessionIds.filter { it.isNotBlank() }.distinct()
        if (uniqueIds.isEmpty()) return@withContext emptyList()

        val sessions = mutableListOf<SessionEntity>()
        for (batch in uniqueIds.chunked(SESSION_ID_QUERY_BATCH_SIZE)) {
            sessions += sessionDao.getByIds(batch)
        }
        val byId = sessions.associateBy { it.id }
        uniqueIds.mapNotNull(byId::get)
    }

    /** v0.45: Observar conversaciones archivadas(Orden descendente por actualizadoEn). Para listas de archivos. */
    fun observeArchived(): Flow<List<SessionEntity>> = sessionDao.observeArchived()

    /** v0.45: Alternar el estado de archivo de la sesión. */
    suspend fun setArchived(sessionId: String, archived: Boolean) {
        sessionDao.setArchived(sessionId, archived)
    }

    /** B7-03: Actualice la posición de lectura de la sesión. */
    suspend fun updateLastReadMessage(sessionId: String, messageId: String, readCount: Int) {
        sessionDao.updateLastReadMessage(sessionId, messageId, readCount)
    }

    /** B7-05: Persistencia de clasificación de arrastrar y soltar de la sesión superior. */
    suspend fun reorderPinnedSessions(ids: List<String>) {
        ids.forEachIndexed { index, id -> sessionDao.updateSortOrder(id, index) }
    }

    /** B8-01: Actualizar el cronograma de mensajería proactiva a nivel de sesión(null=Horario claro). */
    suspend fun updateProactiveNextTriggerAt(id: String, nextTriggerAt: Long?) {
        sessionDao.updateProactiveNextTriggerAt(id, nextTriggerAt)
    }

    // ── v2.0: eliminación suave ──

    /**
     * v2.0: Sesión de eliminación temporal.
     *
     * La eliminación temporal también debe limpiar la bandeja de salida,generation checkpoint y el nuevo enlace permanece,
     * De lo contrario, la tarea de recuperación puede volver a escribir mensajes en la sesión eliminada cuando se reinicie la aplicación.
     */
    suspend fun softDeleteSession(id: String) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            database.withTransaction {
                sessionDao.softDelete(id, now)
                database.messageOutboxDao().deleteBySession(id)
                database.generationCheckpointDao().deleteBySession(id)
                // H-SESS-2: Limpiar sincrónicamente el índice FTS durante la eliminación temporal,Evitar que los mensajes eliminados aparezcan en los resultados de búsqueda
                syncFtsDeleteBySession(id)
                // eventos, rondas,parts y los jefes de sucursales son auditables/Datos recuperables, retenidos cuando se eliminan temporalmente;
                // La entrada de recuperación marcará eliminadoDe nuevo, y la cascada FK solo limpiará las transacciones eliminadas por completo.
            }
        }
        // El lado de la memoria también es claro. —— Las expectativas del usuario son“Si lo eliminas, desaparecerá.”:Incluso dentro de los 7 días posteriores a“Eliminado recientemente”reanudar la sesión en,
        // El resumen y los hechos tampoco volverán(Restaurar restaura solo mensajes,Sin reconstrucción de memoria).
        cleanSessionMemory(listOf(id))
        auditLogger?.log(
            category = "user_action",
            action = "soft_delete_session",
            target = id,
        )
    }

    /** v2.0: Restaurar sesiones eliminadas temporalmente. */
    suspend fun restoreSession(id: String) {
        sessionDao.restore(id)
    }

    /** v2.0: Observe las sesiones eliminadas temporalmente dentro de los 7 días. */
    fun observeDeletedSessions(): Flow<List<SessionEntity>> {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        return sessionDao.observeDeleted(cutoff)
    }

    /** v2.0: Limpieza caducada permanentemente(7 Sesión eliminada temporalmente hace días). */
    suspend fun purgeOldDeletedSessions() {
        withContext(Dispatchers.IO) {
            val (expiredSessionIds, messageIds) = database.withTransaction {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                val sessionIds = sessionDao.listIdsDeletedBefore(cutoff)
                val deletedMessageIds = messageDao.getMessageIdsForExpiredSessions(cutoff)
                syncFtsClear()
                sessionDao.permanentlyDeleteOldSessions(cutoff)
                sessionIds to deletedMessageIds
            }
            deleteMessageImages(messageIds)
            // Después de la eliminación completa, no habrá entrada de recuperación.,Si mi memoria no se borra aquí, me quedaré huérfano para siempre.
            cleanSessionMemory(expiredSessionIds)
        }
    }

    /** v2.0: Borrar todas las conversaciones(eliminación completa,Reemplazar el deleteAll original para la página de administración de datos). */
    suspend fun hardDeleteAllSessions() {
        withContext(Dispatchers.IO) {
            val (allSessionIds, messageIds) = database.withTransaction {
                val sessionIds = sessionDao.listAllIds()
                val deletedMessageIds = messageDao.getAllMessageIds()
                syncFtsClear()
                sessionDao.deleteAll()
                sessionIds to deletedMessageIds
            }
            deleteMessageImages(messageIds)
            cleanSessionMemory(allSessionIds)
            auditLogger?.log(
                category = "user_action",
                action = "delete_all_sessions",
            )
        }
    }

    /** Número total de sesiones de observación.(Para panel de estadísticas). */
    fun observeSessionCount(): Flow<Int> = sessionDao.observeCount()

    /** Número total de mensajes observados(Para panel de estadísticas). */
    fun observeMessageCount(): Flow<Int> = messageDao.observeCount()

    /** v2.0: Obtenga el número total de sesiones a la vez. */
    suspend fun getSessionCount(): Int = sessionDao.count()

    /** v2.0: Obtenga el número total de mensajes a la vez. */
    suspend fun getTotalMessageCount(): Int = messageDao.countMessages()

    /**
     * Crear nueva sesión. Devuelve la identificación de la sesión. título predeterminado"nueva sesión".
     * @param warmupPending v2.x Importar calentamiento:importación externa/Copia de seguridad y restauración de la configuración de la sesión true,La primera ronda utiliza toda la historia para crear contexto.
     */
    suspend fun createSession(assistantId: String = "default", warmupPending: Boolean = false): String {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        sessionDao.insert(
            SessionEntity(
                id = id,
                title = context.getString(R.string.session_repo_default_title),
                createdAt = now,
                updatedAt = now,
                assistantId = assistantId,
                warmupPending = warmupPending,
            ),
        )
        return id
    }

    /** v1.28: Crear una sesión privada en la pestaña Agente(independiente de la sesión de tareas,No mostrar en la lista de tareas). */
    suspend fun createAgentSession(assistantId: String = "default"): String {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        sessionDao.insert(
            SessionEntity(
                id = id,
                title = "Agent",
                createdAt = now,
                updatedAt = now,
                assistantId = assistantId,
                isAgentSession = true,
            ),
        )
        return id
    }

    /** v1.28: Obtenga la sesión del Agente más reciente(para recuperación automática). */
    suspend fun getLatestAgentSession(): SessionEntity? = sessionDao.getLatestAgentSession()

    /** v1.0.54: Presione Asistente para ver las sesiones recientes del Agente. */
    suspend fun getRecentAgentByAssistant(assistantId: String, limit: Int): List<SessionEntity> =
        sessionDao.getRecentAgentByAssistant(assistantId, limit)

    /** Punto de colocación de mensaje activo:Este asistente ha sido hablado recientemente por un usuario y no ha sido eliminado./Conversaciones sin archivar. */
    suspend fun getRecentActiveByAssistant(assistantId: String): SessionEntity? =
        sessionDao.getRecentActiveByAssistant(assistantId)

    /** El asistente tiene conversaciones normales recientes en las que el usuario ha hablado (excluyendo la pestaña Agente) y es la primera opción para mensajes proactivos. */
    suspend fun getRecentChatByAssistant(assistantId: String): SessionEntity? =
        sessionDao.getRecentChatByAssistant(assistantId)

    /** v1.0.72: Configurar sesión"Sin referencia a la memoria"logo. */
    suspend fun setSessionIgnoreMemory(sessionId: String, ignore: Boolean) {
        withContext(Dispatchers.IO) {
            resultOf { sessionDao.setIgnoreMemory(sessionId, ignore) }
                .onError { msg, t -> Logger.w(TAG, "setSessionIgnoreMemory falló: $msg", t) }
        }
    }

    /**
     * v1.58: Bifurcación de un mensaje en la conversación fuente,Crea una nueva sesión y copia todo el historial hasta este mensaje.
     *
     * - reutilizar sesión fuente assistantId / folderId
     * - regeneración de mensajes id(Evite conflictos de clave primaria),Mantener original creadoEn el pedido
     * - Título cambiado a"bifurcado de {título de la fuente}"
     *
     * @return nueva sesión id;Devuelve nulo si la sesión de origen o el mensaje ancla no existe.
     */
    suspend fun forkSession(sourceSessionId: String, untilMessageId: String): String? {
        // H-SESS1: mesa cruzada(sessions + messages + messages_fts)Copiar,Envolver con transacción,
        // Reversión completa en caso de accidente,Evite mensajes de conversación bifurcados/FTS El índice está incompleto.
        return withContext(Dispatchers.IO) {
            database.withTransaction {
                val sourceSession = sessionDao.getById(sourceSessionId) ?: return@withTransaction null
                val anchor = messageDao.getById(sourceSessionId, untilMessageId) ?: return@withTransaction null
                val newId = Uuid.random().toString()
                val now = System.currentTimeMillis()
                sessionDao.insert(
                    SessionEntity(
                        id = newId,
                        title = context.getString(
                            R.string.session_repo_fork_title,
                            sourceSession.title.ifBlank {
                                context.getString(R.string.session_repo_default_title)
                            },
                        ),
                        createdAt = now,
                        updatedAt = now,
                        assistantId = sourceSession.assistantId,
                        folderId = sourceSession.folderId,
                        parentSessionId = sourceSessionId,
                    ),
                )
                sessionDao.incrementChildCount(sourceSessionId)
                // Copie todos los mensajes hasta el punto de anclaje.(Con punto de anclaje),Mensaje de reasignación completo, mensaje principal y referencias de grupos de variantes.
                // Evite la transformación de UIMessage para evitar perder archivos adjuntos, rueda de herramientas, colecciones, reacciones y campos de variantes antiguos.
                val anchorOrder = if (anchor.commitSeq > 0) anchor.commitSeq else anchor.seq
                val messages =
                    messageDao.getUpToBySession(
                        sessionId = sourceSessionId,
                        untilOrder = anchorOrder,
                        untilCreatedAt = anchor.createdAt,
                        untilMessageId = anchor.id,
                    )
                val messageIdMap = messages.associate { it.id to Uuid.random().toString() }
                val groupIdMap = messages.mapNotNull { it.variantGroupId }.distinct().associateWith { Uuid.random().toString() }
                val parentGroupIdMap = messages.mapNotNull { it.parentGroupId }.distinct().associateWith { Uuid.random().toString() }
                val remapped = messages.mapIndexed { index, msg ->
                    msg.copy(
                        id = messageIdMap.getValue(msg.id),
                        sessionId = newId,
                        seq = index.toLong() + 1,
                        commitSeq = index.toLong() + 1,
                        parentMessageId = msg.parentMessageId?.let(messageIdMap::get),
                        variantGroupId = msg.variantGroupId?.let(groupIdMap::get),
                        parentGroupId = msg.parentGroupId?.let(parentGroupIdMap::get),
                        // §11-11: La copia no hereda el bit de colección (la colección es"quiero quedarme con este"la intención, no el atributo de contenido).
                        deletedAt = null, favorite = false, favoriteTag = null,
                    )
                }
                remapped.forEach { msg ->
                    messageDao.upsert(msg)
                    resultOf {
                        syncFtsDelete(msg.id)
                        syncFtsInsert(msg.id, msg.content)
                    }.onError { _, t ->
                        Logger.w(TAG, "forkSession FTS Error de escritura: ${t?.message ?: ""}", t)
                    }
                }
                sessionDao.setMessageCount(newId, remapped.size)
                val last = remapped.lastOrNull()
                if (last != null) {
                    sessionDao.updatePreviewOnly(
                        id = newId,
                        preview = previewText(last.content),
                        now = now,
                    )
                    database.sessionBranchHeadDao().upsert(
                        SessionBranchHeadEntity(
                            sessionId = newId,
                            headMessageId = last.id,
                            nextCommitSeq = remapped.size.toLong() + 1,
                            projectionVersion = 1,
                            updatedAt = now,
                        ),
                    )
                }
                newId
            }
        }
    }

    /** v1.28: Observar la sesión de tareas.(Excluir sesiones de la pestaña Agente). */
    fun observeTaskSessions(): Flow<List<SessionEntity>> = sessionDao.observeTaskSessions()

    /** Eliminar sesión(Eliminación de mensajes en cascada + índice FTS). */
    suspend fun deleteSession(id: String) {
        // Phase 10.3: Primero elimine el índice FTS(Depende de la subconsulta de la tabla de mensajes,Debe ser antes de que los mensajes se eliminen en cascada)
        // H-SESS1: mesa cruzada(FTS + sessions)Envolver con transacción,Garantizar la coherencia
        withContext(Dispatchers.IO) {
            val messageIds = database.withTransaction {
                val deletedMessageIds = messageDao.getMessageIdsBySessionId(id)
                syncFtsDeleteBySession(id)
                sessionDao.deleteById(id)
                deletedMessageIds
            }
            deleteMessageImages(messageIds)
            // Limpieza de sincronización del lado de la memoria:Líneas de sesión eliminadas pero resumen/El hecho sigue siendo,El desplazamiento de memoria de la página de memoria todavía muestra su tarjeta.
            cleanSessionMemory(listOf(id))
            // v1.135-A: Borrar el caché de ayuda visual para esta sesión sidecar,Evite archivos huérfanos
            // i18n Punto 1 de transformación de demostración: chino original codificado "No se pudo borrar el caché visual: $id" Cambiar a
            // ErrorMessage.StorageError.IO_ERROR + sessionId como metadatos de registro.
            // Locale irrelevante,toLogString() salida "[ERR:storage_io_error] Local storage read/write failed"
            resultOf { visionCache?.clearSession(id) }
                .onError { _, t ->
                    Logger.w(
                        TAG,
                        "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [sessionId=$id]",
                        t,
                    )
                }
            // P3: Borrar la configuración del modo de permiso para esta sesión
            // i18n Punto 2 de transformación de demostración: chino original codificado "Error en el modo de permiso de sesión limpia: $id" Cambiar a
            // ErrorMessage.StorageError.IO_ERROR,Igual que el artículo anterior, el código pero diferentes metadatos diferencian los escenarios.
            resultOf { sessionPermissionStore?.clearMode(id) }
                .onError { _, t ->
                    Logger.w(
                        TAG,
                        "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [sessionId=$id, scope=permission]",
                        t,
                    )
                }
            // P2-4: Registro de auditoría — Eliminar sesión
            auditLogger?.log(
                category = "user_action",
                action = "delete_session",
                target = id,
            )
        }
    }

    /**
     * Llame al limpiador de memoria y tráguese la excepción.
     *
     * La limpieza es el toque final:Falla no debería dejar que se haya puesto la sesión/El proceso de eliminación de eliminación de mensajes arroja un error(Eso haría que la interfaz de usuario pensara que la eliminación falló.).
     * El limpiador ha seguido internamente los pasos para aislar la falla.,Aquí hay otra capa solo para proteger contra el montaje./Eventos inesperados distintos de la cancelación.
     */
    private suspend fun cleanSessionMemory(sessionIds: Collection<String>) {
        val cleanup = sessionMemoryCleanup ?: return
        if (sessionIds.isEmpty()) return
        resultOf { cleanup.cleanSessions(sessionIds) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo borrar la memoria de la sesión: $msg", t) }
    }

    /** Cambie el nombre de la sesión. */
    suspend fun renameSession(id: String, title: String) {
        // H-SESS2: átomo UPDATE,evitar leer-cambiar-Escribir actualizaciones perdidas de simultaneidad
        sessionDao.updateTitle(id, title, System.currentTimeMillis())
    }

    /** Phase 8.2: Cambiar asistente vinculado a sesión. */
    suspend fun setSessionAssistant(sessionId: String, assistantId: String) {
        sessionDao.setAssistantId(sessionId, assistantId)
    }

    /** Phase 8.2: Alternar estado superior de la conversación. */
    suspend fun setSessionPinned(sessionId: String, pinned: Boolean) {
        sessionDao.setPinned(sessionId, pinned)
    }

    /** Phase 9.1 (M13): Cambiar la carpeta de la sesión(null = Mover a desagrupado). */
    suspend fun setSessionFolder(sessionId: String, folderId: String?) {
        sessionDao.setFolderId(sessionId, folderId)
    }

    /** Phase 8.2: Obtener el enlace de la sesión Assistant id(Predeterminado si ninguno 'default'). */
    suspend fun getAssistantId(sessionId: String): String {
        return sessionDao.getById(sessionId)?.assistantId ?: "default"
    }

    /**
     * v2.6.7 fix (AUDIT F16): Obtenga todos los ID de sesión bajo el nombre de un asistente.
     *
     * suministro `search_conversation` Filtrado único para recuperación entre sesiones,
     * Reemplaza la implementación anterior“GetSessionById se usa una vez para cada resultado”Consulta N+1.
     */
    suspend fun sessionsOfAssistant(assistantId: String): List<String> = withContext(Dispatchers.IO) {
        resultOf { sessionDao.listIdsByAssistant(assistantId) }.getOrNull().orEmpty()
    }

    /** Phase 8.3: Cambiar el estado de la recopilación de mensajes(Agregación del panel de favoritos entre sesiones a través de observeAllFavorites). */
    suspend fun setMessageFavorite(messageId: String, favorite: Boolean) {
        messageDao.setFavorite(messageId, favorite)
    }

    /** Función1: Establecer respuesta de emoticón de mensaje(null = Cancelar respuesta). */
    suspend fun setMessageReaction(messageId: String, reaction: String?) {
        messageDao.setReaction(messageId, reaction)
    }

    /**
     * v2.6.13: Obtenga (o cree) una sesión dedicada a su colección de tarjetas.
     *
     * Las tarjetas en sí no son mensajes, sino favoritos ([MessageDao.observeAllFavorites]) sólo reconoce la tabla de mensajes,
     * Entonces "recoge una tarjeta"= Deja un comentario en una sesión dedicada `favorite = true` noticias.
     *
     * Por qué sesiones dedicadas + archivos:
     *  - Sesión dedicada: no inserta un registro que el usuario verá en la conversación actual, ni trae el código fuente de la tarjeta al contexto;
     *  - Archivo: no entrar en la lista de tareas ([SessionDao.observeTaskSessions] filtrar archived=1),
     *    pero [MessageDao.observeAllFavorites] Sin filtrar por archivado, aún se puede ver la página de la colección;
     *  - Idempotente: reutilización por título, las colecciones repetidas no crearán una nueva sesión cada vez.
     */
    suspend fun ensureCardFavoriteSession(): String = withContext(Dispatchers.IO) {
        sessionDao.getByFolderId(CARD_FAVORITE_SESSION_MARKER)?.let { return@withContext it.id }
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        sessionDao.insert(
            SessionEntity(
                id = id,
                title = context.getString(R.string.card_favorite_session_title),
                createdAt = now,
                updatedAt = now,
                assistantId = "default",
                archived = true,
                // folderId cuando“Esta es una sesión exclusiva de colección de cartas.”Marcado persistente: independiente del idioma/cambios de título,
                // Tampoco funciona con carpetas reales (UUID) golpe. Después del archivado, no se ingresa en la lista de tareas y los usuarios no pueden verlo.
                folderId = CARD_FAVORITE_SESSION_MARKER,
            ),
        )
        id
    }

    /**
     * v2.6.13: Añade una tarjeta a tus favoritos.
     *
     * Cómo hacerlo: En una sesión dedicada a la recogida de tarjetas ([ensureCardFavoriteSession]), falta una cosa `favorite = true` de
     * Mensaje del asistente, el texto es el código fuente de la tarjeta envuelto en la valla. Al hacer clic en la página de la colección, verá el código fuente y el resumen de la vista previa.
     * y“Abrir la tarjeta original”La capacidad la proporciona la ruta de renderizado de la propia tarjeta y no es necesario almacenar un resultado de renderizado adicional.
     *
     * Idempotente: la recopilación repetida de la misma tarjeta (mismo ID de mensaje) no generará una segunda.
     *
     * @param cardId Tarjeta de identidad (identificación del mensaje); utilizado para juicio de duplicación y eco
     * @param language idioma de la tarjeta (html/svg/…), determinar la marca de la valla
     * @param content Código fuente de la tarjeta
     * @return true Indica que esta vez realmente se agrega una nueva colección;false significa que ya existe
     */
    suspend fun favoriteCard(cardId: String, language: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val fence = language.lowercase().trim().ifBlank { "html" }
        val body = "```$fence\n$content\n```"
        val sessionId = ensureCardFavoriteSession()
        // El juicio depende en gran medida de“La identificación del mensaje permanece sin cambios”: La recopilación repetida de la misma tarjeta (mismo ID de mensaje) cubrirá la misma fila.
        // No escribas etiquetas en el texto. —— La página de la colección muestra el texto principal y el marcado será revelado al usuario.
        appendMessage(
            sessionId = sessionId,
            message = UIMessage(
                id = cardFavoriteMessageId(cardId),
                role = MessageRole.ASSISTANT,
                content = body,
                favorite = true,
            ),
        )
        true
    }

    /** v1.104 U7: Establecer etiquetas de grupos favoritos(null = Mover a desagrupado). */
    suspend fun setMessageFavoriteTag(messageId: String, tag: String?) {
        messageDao.setMessageFavoriteTag(messageId, tag)
    }

    /** v1.104 U7: Observe todas las etiquetas de grupos de colecciones con nombre(Eliminar NULL/eliminar duplicados/orden ascendente). */
    fun observeAllFavoriteTags(): Flow<List<String>> {
        return messageDao.observeAllFavoriteTags()
    }

    /** v1.48: Eliminar un solo mensaje por id(Menú de pulsación larga"Eliminar mensaje"). También limpie el índice FTS. */
    suspend fun deleteMessage(messageId: String) {
        // H-SESS1: mesa cruzada(FTS + messages)Envolver con transacción,Asegúrese de que FTS se elimine sincrónicamente con los mensajes
        withContext(Dispatchers.IO) {
            // v1.0.80 (T-4): Colección en cascada de mensajes que se eliminarán id(Anti-anillo BFS).
            // Eliminar mensaje de usuario cuando su hijo asistente responda(parentGroupId/parentMessageId señalar el user)
            // Si no se elimina en cascada,Al volver a la sesión, la compilación mantendrá estas respuestas huérfanas hasta el final.,
            // Se muestra como"Las viejas noticias de repente se convierten en las últimas"(Comentarios de los usuarios).
            val toDelete = mutableListOf<String>()
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<String>()
            queue.add(messageId)
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!visited.add(id)) continue
                toDelete += id
                messageDao.getChildrenByParentId(id).forEach { child ->
                    if (child.id !in visited) queue.add(child.id)
                }
            }
            database.withTransaction {
                toDelete.forEach { deleteMessageRow(it) }
            }
            // v1.134 P1-2: Limpiar archivos de imagen de mensajes eliminados,Evite que los archivos huérfanos ocupen almacenamiento
            // i18n Punto 3 de transformación de demostración: inglés original codificado "deleteMessage images cleanup failed: ${...}"
            // Cambiar a ErrorMessage.StorageError.IO_ERROR.toLogString(),Elimina las cuerdas desnudas.
            toDelete.forEach { id ->
                resultOf { messageImageStore.deleteByMessageId(id) }
                    .onError { _, t ->
                        Logger.w(
                            TAG,
                            "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [messageId=$id, scope=images]",
                            t,
                        )
                    }
            }
        }
    }

    /** Eliminar una sola línea de mensaje(FTS + messages + Punto de control + Recuento de redundancia + Vista previa),La persona que llama es responsable de la transacción. */
    private suspend fun deleteMessageRow(messageId: String) {
        // v1.107 Redundancia: Descúbrelo primero sessionId,Se utiliza para mantener recuentos de redundancia.
        val entity = messageDao.getByMessageId(messageId) ?: return
        syncFtsDelete(messageId)
        messageDao.deleteById(messageId)
        // v1.0.80 (M-1): Eliminar sincrónicamente los puntos de control generados — Después de que el usuario elimine el mensaje que generó el remanente de interrupción,
        // Si el punto de control todavía está DB,Al reiniciar, recoveryInterruptedGenerations enviará este mensaje
        // "reconstruir"resurrección(Cuando el mensaje no existe y no hay ningún mensaje de actualización, tome la rama de reconstrucción.). Al eliminar el mensaje se elimina el punto de control.,
        // Evite que los mensajes eliminados se reaviven después de reiniciar en la fuente.
        database.generationCheckpointDao().deleteByAssistantMessageId(messageId)
        database.generationCheckpointDao().deleteByUserMessageId(messageId)
        // Comentarios del usuario 2 (v2.6.4): "El chat truncado fue eliminado y reapareció, y empezó a generarse automáticamente".
        //
        // Causa: La construcción se interrumpió cuandocheckpoint El AssistantMessageId puede ser el mismo que eliminó el usuario.
        // El mensaje no coincide (la identificación pregenerada cuando se interrumpe la transmisión puede ser diferente de la fila de ubicación real), los dos botones anteriores id
        // La eliminación exacta lo perderá. El punto de control residual se eliminará la próxima vez que ingrese a la sesión. recoverInterruptedGenerations
        // Reconstrúyalo en un nuevo mensaje del asistente y ordénelo usando cp.createdAt → Ir al final de la lista de mensajes.
        //
        // En pocas palabras: borre los puntos de control cuyo createAt no sea anterior a este mensaje en la sesión.
        // Semánticamente seguro —— Los puntos de control anteriores a los mensajes eliminados no pertenecen al alcance de esta eliminación y se conservan.
        resultOf {
            database.generationCheckpointDao()
                .deleteBySessionAndCreatedAtFrom(entity.sessionId, entity.createdAt)
        }.onError { _, t -> Logger.w(TAG, "No se pudieron limpiar los puntos de control residuales: ${t?.message ?: ""}") }
        // v1.107 Redundancia: Disminuyendo sessions.messageCount
        resultOf { sessionDao.incrementMessageCount(entity.sessionId, -1) }
            .onError { _, t -> Logger.w(TAG, "decrementMessageCount failed: ${t?.message ?: ""}") }
        // v1.0.80 (T-4): Sincronizar sesión después de la eliminación preview — Si se elimina el último mensaje,
        // La vista previa de la lista de sesiones debería volver al nuevo último elemento,De lo contrario, el texto del mensaje eliminado seguirá apareciendo.
        resultOf {
            val newLast = messageDao.getLastBySession(entity.sessionId)
            sessionDao.updatePreview(
                id = entity.sessionId,
                preview = newLast?.let { previewText(it.content) } ?: "",
                now = System.currentTimeMillis(),
            )
        }.onError { _, t -> Logger.w(TAG, "updatePreview after delete failed: ${t?.message ?: ""}") }
    }

    /** Phase 8.3: Observe todos los mensajes favoritos en todas las sesiones(Para panel de colección). */
    fun observeAllFavorites(): Flow<List<UIMessage>> {
        return messageDao.observeAllFavorites()
            .map { entities -> entities.map { it.toUIMessage() } }
            .distinctUntilChanged()
    }

    /**
     * §11-4: Marcar lista de favoritos"Esta es una versión antigua".
     *
     * La regeneración abrirá una nueva fila de variantes.,El favorito de la vieja fila no se mueve → Es posible que tengas la versión 1 en tus favoritos.,
     * La sesión muestra la versión 3. Aquí obtenemos en solo lectura lo último de cada grupo. variantIndex,en línea con la colección
     * comparado con el propio índice de variantes,Regresar"Más antiguo que la última versión"La colección de ID de mensaje.
     *
     * ¿Por qué no simplemente pasar a mostrar la última versión?:La colección está en la fila antigua.,Después de cambiar para mostrar una nueva fila, el usuario hace clic para cancelar la colección.
     * Se aplicará a una línea que no se recopiló originalmente.(Parece que no hay respuesta cuando hago clic en él.). Marcar versiones antiguas puede decirles a los usuarios la verdad
     * “Esta es la edición que has recopilado.”,y no afecta la semántica operativa.
     */
    suspend fun staleFavoriteIds(messages: List<UIMessage>): Set<Uuid> {
        val grouped = messages
            .filter { it.variantGroupId != null }
            .groupBy { it.variantGroupId!! }
        if (grouped.isEmpty()) return emptySet()
        val latest = withContext(Dispatchers.IO) {
            messageDao.getLatestVariantIndexes(grouped.keys.toList()).associate { it.variantGroupId to it.variantIndex }
        }
        return messages
            .filter { msg ->
                val groupId = msg.variantGroupId ?: return@filter false
                val head = latest[groupId] ?: return@filter false
                msg.variantIndex < head
            }
            .map { it.id }
            .toSet()
    }

    /** Observar la lista de mensajes de una conversación específica.(girar UIMessage). */
    fun observeMessages(sessionId: String): Flow<List<UIMessage>> {
        // H-SESS3: Solo los elementos OBSERVE_LIMIT más recientes,Evite la carga completa para sesiones largas OOM;El historial anterior se carga en páginas.
        return messageDao.observeRecentBySession(sessionId, OBSERVE_LIMIT)
            .mapLatest { entities -> projectMessagesForRead(entities) }
            .distinctUntilChanged()
    }

    /**
     * v1.53-A1: Cargando página — Obtenga los últimos mensajes de límite de la sesión especificada(Ordenar por creadoEn ascendente).
     *
     * DAO Volver en orden descendente(último primero),aquí reversed() Convertir a orden ascendente,Conveniente para renderizado directo.
     * para carga inicial,Evite el retraso causado por cargar todos los mensajes a la vez/OOM.
     */
    suspend fun getRecentMessages(sessionId: String, limit: Int): List<UIMessage> {
        return projectMessagesForRead(messageDao.getRecentBySession(sessionId, limit).reversed())
    }

    /** Planificar recuperación de tarjeta:Leer mensajes de visualización de herramientas persistentes en la sesión,No sujeto a restricciones de paginación en la pantalla de inicio del chat. */
    suspend fun getToolCallMessages(sessionId: String): List<UIMessage> = withContext(Dispatchers.IO) {
        messageDao.getToolCallMessagesBySession(sessionId).map { it.toUIMessage() }
    }

    /** Para resumen diario:Lea los mensajes de los usuarios dentro del intervalo del calendario local especificado. */
    suspend fun getUserMessagesBetween(fromCreatedAt: Long, toCreatedAt: Long, limit: Int): List<UIMessage> = withContext(Dispatchers.IO) {
        messageDao.getUserMessagesBetween(fromCreatedAt, toCreatedAt, limit).map { it.toUIMessage() }
    }

    /**
     * v1.53-A1: Cargando página — Obtenga los mensajes límite cuya secuencia válida es anterior a beforeSeq(orden ascendente).
     *
     * Solía deslizarse hacia arriba para cargar más.:Tome la secuencia válida del mensaje más antiguo de la lista actual como ancla,Tomemos como ejemplo la historia anterior.
     * Devolver orden ascendente,Se puede unir previamente directamente a la lista de mensajes existente.
     */
    suspend fun getOlderMessages(sessionId: String, beforeSeq: Long, limit: Int): List<UIMessage> {
        return projectMessagesForRead(messageDao.getOlderBySession(sessionId, beforeSeq, limit).reversed())
    }

    /**
     * v1.0.72: Después del tiempo especificado(Contiene)mensajes de usuario(orden ascendente,edición limitada).
     * Resumen de hoy para Trabajador:Resumir lo que dijeron los usuarios ese día.,Genere material de archivo como LLM.
     */
    suspend fun getUserMessagesSince(fromCreatedAt: Long, limit: Int): List<UIMessage> {
        return messageDao.getUserMessagesSince(fromCreatedAt, limit).map { it.toUIMessage() }
    }

    /**
     * v1.0.51: Obtener mensajes [completos] de una sesión específica al mismo tiempo(Ordenar por creadoEn ascendente),Se utiliza como relleno para la migración de memoria de stock.
     *
     * con [observeMessages] / [getRecentMessages] diferencia:Sin paginación limit,Cargar historial completo.
     * backfill Todos los mensajes deben enviarse a SessionSummaryManager.rollingSummary para reconstruir el resumen.
     * usar Flow.first() Tome una instantánea,Sin observación continua.
     */
    suspend fun getAllMessagesForBackfill(sessionId: String): List<UIMessage> = withContext(Dispatchers.IO) {
        messageDao.observeBySession(sessionId).first().map { it.toUIMessage() }
    }

    /**
     * v2.x Importar calentamiento:Leer todos los mensajes válidos en la sesión a la vez(orden ascendente,Excluir eliminación temporal).
     *
     * con [getRecentMessages] Las ventanas se cargan de manera diferente.,no vayas aqui limit —— Es necesario importar la rueda de precalentamiento.
     * Historial completo del truncamiento del presupuesto simbólico(ver WarmupHistory),En lugar de cortar primero por número.
     */
    suspend fun getAllMessagesForWarmup(sessionId: String): List<UIMessage> = withContext(Dispatchers.IO) {
        messageDao.observeBySession(sessionId).first()
            .filter { it.deletedAt == null }
            .map { it.toUIMessage() }
    }

    /** v2.x Importar calentamiento:Borre la marca después de la primera ronda de respuestas exitosas.(idempotente;No borrar no bloquea el proceso principal). */
    suspend fun clearWarmupPending(sessionId: String) {
        resultOf { sessionDao.setWarmupPending(sessionId, false) }
            .onError { msg, _ -> Logger.w(TAG, "clearWarmupPending failed: $msg") }
    }

    /**
     * v1.0.51: Obtenga todas las conversaciones desarchivadas y eliminadas a la vez(Contiene sesión del agente),Para migración y recorrido de memoria de almacenamiento.
     *
     * usar Flow.first() Tome una instantánea,Sin observación continua.backfill Solo preocúpese por la lista de sesiones en el momento del inicio.
     */
    suspend fun getAllActiveSessionsForBackfill(): List<SessionEntity> = withContext(Dispatchers.IO) {
        sessionDao.observeActive().first()
    }

    /**
     * v1.0.52: Obtenga las últimas N conversaciones del asistente especificado(Para inyección de referencia de chats recientes system prompt).
     *
     * excluir archivado/eliminación suave/Agent Tab sesión,Presione actualizado en DESC para limitar el número de entradas.
     * llamador(SystemPromptAssembler.buildRecentChatsSection)Usar título + lastMessagePreview
     * Construya una lista concisa de conversaciones recientes.,Deje que LLM detecte el contexto reciente del usuario,Pero no se ejecuta como una instrucción.
     */
    suspend fun getRecentByAssistant(assistantId: String, limit: Int): List<SessionEntity> = withContext(Dispatchers.IO) {
        sessionDao.getRecentByAssistant(assistantId, limit)
    }

    /** v1.53-A1: Número total de mensajes de conversación(El juicio de paginación tieneMásSe utiliza historial). */
    suspend fun getMessageCount(sessionId: String): Int = messageDao.countBySession(sessionId)

    /** Uso total medido para una sola sesión(token Autocontrol de consumo;Solo se incluyen mensajes del asistente con tokens medidos reales.). */
    suspend fun getUsageTotalsBySession(sessionId: String): SessionUsageAggregate = withContext(Dispatchers.IO) {
        messageDao.usageTotalsBySession(sessionId)
    }

    /** persistir un mensaje,Devuelve su identificación. Actualiza también la sesión. updatedAt + lastMessagePreview + FTS índice. */
    suspend fun appendMessage(sessionId: String, message: UIMessage): String {
        // H-SESS1: mesa cruzada(messages + FTS + sessions)Envolver con transacción,Garantizar la coherencia
        return withDatabaseRecovery("appendMessage") {
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    appendMessageInternal(sessionId, message)
                }
            }
        }
    }

    /** Escriba incrementalmente los resultados de una sola rueda de herramientas que se colocará en el disco inmediatamente después de que se complete la ejecución de la herramienta. */
    suspend fun upsertToolRound(round: ToolRoundEntity) {
        withContext(Dispatchers.IO) {
            database.toolRoundDao().upsert(round)
        }
    }

    /**
     * El nuevo enlace envía el mensaje una vez,parts, rueda de herramientas, cabezal de rama y estado de finalización de giro.
     *
     * La segunda confirmación del mismo turno devuelve YaCommitted al ingresar la transacción; cualquier excepción de escritura será revertida
     * turn, mensajes y encabezados de ramas para evitar“Completado pero el texto aún no ha sido publicado.”Estado semicomprometido.
     */
    suspend fun commitConversationMessage(request: MessageCommitRequest): MessageCommitResult = withContext(Dispatchers.IO) {
        database.withTransaction {
            val session = sessionDao.getById(request.sessionId)
                ?: return@withTransaction MessageCommitResult.Rejected
            if (session.deletedAt != null) return@withTransaction MessageCommitResult.Rejected
            val turn = database.conversationTurnDao().getById(request.turnId)
                ?: return@withTransaction MessageCommitResult.Rejected
            if (turn.finishedAt != null) return@withTransaction MessageCommitResult.AlreadyCommitted
            val finished = database.conversationTurnDao().finishIfOpen(
                turnId = request.turnId,
                phase = "COMPLETED",
                finishedAt = System.currentTimeMillis(),
            )
            if (finished != 1) return@withTransaction MessageCommitResult.AlreadyCommitted

            val existing = messageDao.getById(request.sessionId, request.message.id.toString())
            val branch = database.sessionBranchHeadDao().get(request.sessionId)
            val nextCommitSeq = branch?.nextCommitSeq
                ?.coerceAtLeast(messageDao.getMaxSeq(request.sessionId) + 1)
                ?: (messageDao.getMaxSeq(request.sessionId) + 1)
            var entity = request.message.toEntity(request.sessionId)
            entity = if (existing != null) {
                entity.copy(
                    seq = existing.seq,
                    commitSeq = existing.commitSeq,
                    createdAt = existing.createdAt,
                    deletedAt = existing.deletedAt,
                )
            } else {
                entity.copy(
                    seq = if (entity.seq > 0) entity.seq else messageDao.getMaxSeq(request.sessionId) + 1,
                    commitSeq = if (entity.commitSeq > 0) entity.commitSeq else nextCommitSeq,
                    parentMessageId = entity.parentMessageId ?: turn.inputUserMessageId,
                )
            }
            messageDao.upsert(entity)
            resultOf {
                syncFtsDelete(entity.id)
                syncFtsInsert(entity.id, entity.content)
            }.onError { _, t -> Logger.w(TAG, "MessageCommit FTS Error de escritura: ${t?.message ?: ""}", t) }

            if (request.parts.isNotEmpty()) {
                database.messagePartDao().deleteByMessage(entity.id)
                database.messagePartDao().upsertAll(
                    request.parts.map { it.copy(messageId = entity.id) },
                )
            }
            if (request.toolRounds.isNotEmpty()) {
                database.toolRoundDao().upsertAll(
                    request.toolRounds.map { it.copy(turnId = request.turnId) },
                )
            }
            if (existing == null) sessionDao.incrementMessageCount(request.sessionId, 1)
            updateSessionPreview(request.sessionId, request.message)

            val now = System.currentTimeMillis()
            val branchHead = SessionBranchHeadEntity(
                sessionId = request.sessionId,
                headMessageId = entity.id,
                nextCommitSeq = entity.commitSeq + 1,
                projectionVersion = (branch?.projectionVersion ?: 0) + 1,
                updatedAt = now,
            )
            database.sessionBranchHeadDao().upsert(branchHead)
            val event = ConversationEventDraft(
                sessionId = request.sessionId,
                turnId = request.turnId,
                type = ConversationEventType.TURN_FINISHED,
                streamId = turn.streamId,
                generationSerial = turn.generationSerial,
                payloadJson =
                "{\"messageId\":\"${entity.id}\",\"contentLength\":${entity.content.length}," +
                    "\"contentHash\":\"${io.zer0.muse.data.chat.rewrite.sha256(entity.content)}\"}",
            )
            database.conversationEventDao().insert(
                event.toEntity(
                    eventSeq = database.conversationEventDao().nextEventSeq(request.sessionId),
                    eventId = Uuid.random().toString(),
                ),
            )
            database.generationCheckpointDao().deleteByAssistantMessageId(request.assistantMessageId)
            database.generationCheckpointDao().deleteByUserMessageId(request.userMessageId)
            MessageCommitResult.Committed
        }
    }

    /**
     * appendMessage versión no transaccional de,Para personas que llaman y ya están dentro de una transacción(Como por ejemplo [forkSession])Reutilizar,
     * Evite la sobrecarga del punto de guardado de las transacciones anidadas.
     */
    private suspend fun appendMessageInternal(sessionId: String, message: UIMessage): String {
        var entity = message.toEntity(sessionId)
        // v1.0.90 (S-5): Los mensajes de los usuarios también se distribuyen. seq — Anteriormente agregarMessageInternal directamente upsert,
        // entity.seq hengwei 0,resultado en mensaje de usuario seq=0,assistant seq=1, conflictos de secuencias de sesiones múltiples.
        // Unifique la lógica de distribución con upsertMessage.
        val existing = messageDao.getById(sessionId, entity.id)
        val branchHead = if (ConversationRebuildFlagStore.current.useCommitSeq) {
            database.sessionBranchHeadDao().get(sessionId)
        } else {
            null
        }
        if (existing != null) {
            if (entity.seq == 0L) entity = entity.copy(seq = existing.seq)
            if (entity.commitSeq == 0L) entity = entity.copy(commitSeq = existing.commitSeq)
            if (existing.createdAt != entity.createdAt) entity = entity.copy(createdAt = existing.createdAt)
        } else {
            if (entity.seq == 0L) {
                entity = entity.copy(seq = messageDao.getMaxSeq(sessionId) + 1)
            }
            if (ConversationRebuildFlagStore.current.useCommitSeq && entity.commitSeq == 0L) {
                val next = branchHead?.nextCommitSeq
                    ?.coerceAtLeast(messageDao.getMaxSeq(sessionId) + 1)
                    ?: (messageDao.getMaxSeq(sessionId) + 1)
                entity = entity.copy(commitSeq = next)
            }
        }
        messageDao.upsert(entity)
        if (ConversationRebuildFlagStore.current.useCommitSeq && existing == null) {
            val now = System.currentTimeMillis()
            database.sessionBranchHeadDao().upsert(
                SessionBranchHeadEntity(
                    sessionId = sessionId,
                    headMessageId = entity.id,
                    nextCommitSeq = entity.commitSeq + 1,
                    projectionVersion = (branchHead?.projectionVersion ?: 0) + 1,
                    updatedAt = now,
                ),
            )
        }
        // Phase 10.3: Sincronizar el índice FTS(toNgram Preprocesamiento chino 2-gram)
        // v1.63: Agregar defensa Fts previa a la eliminación,Evite el uso indebido de appendMessage en el futuro al actualizar una identificación existente y la duplicación de FTS
        // i18n Punto 4 de transformación de demostración: inglés original codificado "FTS insert failed: ..." Cambiado a Mensaje de error.
        // Nota:Aquí no hay lanzamiento hacia arriba ErrorMessage(FTS son datos derivados,El fallo se puede solucionar automáticamente mediante sureFtsIndexConsistent),
        // Normalizar registros solo con ErrorMessage code,Es conveniente contar la tasa de fallas del FTS entre módulos.
        resultOf {
            syncFtsDelete(entity.id)
            syncFtsInsert(entity.id, entity.content)
        }.onError { _, t ->
            Logger.w(
                TAG,
                "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [messageId=${entity.id}, scope=fts_insert]",
                t,
            )
        }
        if (existing == null) {
            updateSessionPreview(sessionId, message)
        }
        // v1.107 Redundancia: Incrementar messageCount solo para mensajes nuevos; restaurar/Reintentar con el mismo ID de mensaje upsert
        // El recuento no se puede repetir; de lo contrario, la recuperación de la bandeja de salida aumentará el recuento de sesiones de forma incremental.
        if (existing == null) {
            resultOf { sessionDao.incrementMessageCount(sessionId, 1) }
                .onError { _, t -> Logger.w(TAG, "incrementMessageCount failed: ${t?.message ?: ""}") }
        }
        return entity.id
    }

    // ── v1.0.15: envío de mensajes outbox(Cola de envío persistente)──────────────────────────
    // resolver"El usuario sale justo después de hacer clic en enviar App,El proceso es cancelado por el sistema provocando la pérdida de mensajes."problema.
    // outbox Los registros se escriben sincrónicamente cuando enqueueSend,Eliminar después de que el consumidor inicie launchStream;
    // App Escanee los registros residuales para volver a enviarlos al inicio.

    /**
     * Se agregó un evento de sombra de diálogo.
     *
     * eventSeq Asignado dentro de la transacción de sesión; Las sesiones eliminadas temporalmente rechazan los anexos para evitar la reescritura de la ventana de recuperación.
     */
    suspend fun recordConversationEvent(event: ConversationEventDraft): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val session = sessionDao.getById(event.sessionId)
            if (session == null || session.deletedAt != null) return@withTransaction false
            val eventDao = database.conversationEventDao()
            val eventId = event.deterministicEventId()
            if (eventDao.getByEventId(eventId) != null) return@withTransaction true
            val eventSeq = eventDao.nextEventSeq(event.sessionId)
            val entity = event.toEntity(eventSeq, eventId)
            eventDao.insert(entity) != -1L
        }
    }

    /** Escriba o actualice el estado de ronda persistente del nuevo enlace. */
    suspend fun upsertConversationTurn(turn: ConversationTurnEntity) {
        withContext(Dispatchers.IO) {
            database.conversationTurnDao().upsert(turn)
        }
    }

    /** Completa nuevas rondas de enlaces de forma idempotente; Las rondas completadas no se sobrescribirán con finalizaciones repetidas. */
    suspend fun finishConversationTurn(turnId: String, phase: String = "COMPLETED") {
        withContext(Dispatchers.IO) {
            database.conversationTurnDao().finishIfOpen(
                turnId = turnId,
                phase = phase,
                finishedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * A9: Convergencia de resacas que deja el proceso anterior al inicio turn(No hay tiempo para sobrescribir el proceso cuando se elimina. `finishedAt`).
     *
     * turn Está bien sólo en [finishConversationTurn] y commitConversationMessage están escritos en las dos rutas finales
     * `finishedAt`. Cuando el proceso se interrumpe por la fuerza, ninguno de los dos puede ejecutarse.,Así que quédate `phase = "STREAMING"` y
     * `finishedAt IS NULL` de filas:Utilice otros nuevos para la generación posterior. turnId,No se hará cargo de la antigua línea.;B5-01 Recuperación de punto de control de solo lectura
     * generation_checkpoints,La línea de giro tampoco se lee. Estas filas no están marcadas como fallidas ni como no reclamadas.,Colgar permanentemente.
     *
     * Se toma el estado final de convergencia. [TurnPhase.FAILED]:El conjunto de valores de fase en esta tabla no tiene el archivo "proceso eliminado/asumido por un nuevo proceso",
     * `CANCELLED` Se refiere específicamente al usuario en la semántica del tiempo de ejecución. stop(Esta escena no es),`RESUMABLE` No es un estado final y no contiene ningún código.
     * Presione girar línea para reanudar la generación,solo `FAILED` Expresando "final anormal, no habrá más resultados",Semántica más cercana a,Por lo tanto, no se agrega ningún valor de enumeración nuevo.
     *
     * solo manejar `startedAt` antes que `now -` [STALE_TURN_GRACE_MS] de filas:Las tareas programadas/receptores de canales pueden estar relacionados con esto
     * sweep e iniciar una ejecución para generar,nueva linea `startedAt` es el momento actual,No caerá por la ventana.
     *
     * idempotente:Las condiciones de escritura incluyen `finishedAt IS NULL`,Repetir la ejecución por segunda vez devuelve 0 y no cambia ninguna línea.
     *
     * @param now momento de convergencia(Las pruebas pueden inyectar valores fijos.,Reloj del sistema para producción.)
     * @return El número de filas convergió esta vez.
     */
    suspend fun closeStaleOpenTurns(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.IO) {
        val closed = database.conversationTurnDao().closeStaleOpenTurns(
            phase = TurnPhase.FAILED.name,
            startedBefore = now - STALE_TURN_GRACE_MS,
            finishedAt = now,
        )
        if (closed > 0) {
            Logger.i(TAG, "convergencia de giro de suspensión: $closed Artículo → phase=${TurnPhase.FAILED.name}, ventana ${STALE_TURN_GRACE_MS}ms")
        }
        closed
    }

    /** Escribir en la bandeja de salida (suéltelo antes de enviar, asegúrese de“Simplemente haga clic en enviar y salir”No se pierde ningún mensaje). */
    suspend fun insertOutbox(entity: MessageOutboxEntity) {
        database.messageOutboxDao().upsert(entity)
    }

    /** Eliminar después de que el consumidor inicie exitosamente launchStream outbox(Mensaje entregado,Ya no es necesario restaurar). */
    suspend fun deleteOutbox(id: String) {
        database.messageOutboxDao().deleteById(id)
    }

    /** App Escanee todos los registros de la bandeja de salida pendientes al inicio(Orden ascendente por tiempo de creación). */
    suspend fun getPendingOutbox(): List<MessageOutboxEntity> {
        return database.messageOutboxDao().getAll()
    }

    /** Lea la sesión de destino para generar una solicitud, que se utiliza para la reenvío al volver a la sesión. */
    suspend fun getPendingOutbox(sessionId: String): List<MessageOutboxEntity> {
        return database.messageOutboxDao().getBySessionId(sessionId)
    }

    /** Comprueba si la identificación del mensaje especificado se ha conservado en la tabla de mensajes.(Evite la duplicación al recuperar appendMessage). */
    suspend fun messageExists(sessionId: String, messageId: String): Boolean {
        return messageDao.getById(sessionId, messageId) != null
    }

    // ── B5-01: Generación de puntos de control de streaming(generar outbox)───────────────────────────────

    /** escribir/Actualizar el punto de control de compilación de transmisión. */
    suspend fun upsertGenerationCheckpoint(
        sessionId: String,
        userMessageId: String,
        assistantMessageId: String,
        content: String,
        createdAt: Long,
    ) {
        withDatabaseRecovery("upsertGenerationCheckpoint") {
            withContext(Dispatchers.IO) {
                database.generationCheckpointDao().upsert(
                    GenerationCheckpointEntity(
                        assistantMessageId = assistantMessageId,
                        sessionId = sessionId,
                        userMessageId = userMessageId,
                        content = content,
                        createdAt = createdAt,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /**
     * El consumo atómico envía una bandeja de salida cuando se confirma el primer punto de control.
     * El accidente sólo se puede ver“Ambos existen”o“El punto de control existe y la bandeja de salida ha sido eliminada.”, no deja una bandeja de salida reproducible después de escribir un punto de control.
     */
    suspend fun upsertGenerationCheckpointAndDeleteOutbox(
        sessionId: String,
        userMessageId: String,
        assistantMessageId: String,
        content: String,
        createdAt: Long,
        outboxId: String,
    ) {
        withDatabaseRecovery("upsertGenerationCheckpointAndDeleteOutbox") {
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    database.messageOutboxDao().deleteById(outboxId)
                    database.generationCheckpointDao().upsert(
                        GenerationCheckpointEntity(
                            assistantMessageId = assistantMessageId,
                            sessionId = sessionId,
                            userMessageId = userMessageId,
                            content = content,
                            createdAt = createdAt,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    /** Si se ha generado un punto de control; se utiliza para evitar la doble entrega del punto de control + bandeja de salida al iniciar la recuperación. */
    suspend fun hasGenerationCheckpointForUserMessage(userMessageId: String): Boolean =
        withContext(Dispatchers.IO) { database.generationCheckpointDao().countByUserMessageId(userMessageId) > 0 }

    /** Elimine el punto de control después de que la compilación finalice normalmente. */
    suspend fun deleteGenerationCheckpoint(assistantMessageId: String) {
        withContext(Dispatchers.IO) {
            database.generationCheckpointDao().deleteByAssistantMessageId(assistantMessageId)
        }
    }

    /**
     * B-23: Elimina todos los puntos de control en esta ronda según los mensajes de los usuarios.
     * Múltiples rondas de ciclos de herramientas escriben un punto de control en cada ronda(diferente assistantMessageId),Al final, sólo se eliminará la última ronda.
     * Las rondas intermedias permanecerán — Después de reiniciar, recoveryInterruptedGenerations agregará las rondas completadas.
     * [interrumpido]. La limpieza por lotes realizada por userMessageId cubre todas las rondas.
     */
    suspend fun deleteGenerationCheckpointsByUserMessageId(userMessageId: String) {
        withContext(Dispatchers.IO) {
            database.generationCheckpointDao().deleteByUserMessageId(userMessageId)
        }
    }

    /** Truncar/Eliminar puntos de control después de este momento al regenerar,Evite volver a respuestas descartadas. */
    suspend fun deleteGenerationCheckpointsBefore(sessionId: String, fromCreatedAt: Long) {
        withContext(Dispatchers.IO) {
            database.generationCheckpointDao().deleteBySessionAndCreatedAtFrom(sessionId, fromCreatedAt)
        }
    }

    /**
     * Revisión de corrección (2.0 C-14/C-20/B-01): prensa"Conversacional + Preciso createdAt"Eliminar todos los puntos de control generados en esta generación.
     * Todas las rondas generadas en la misma generación comparten lo mismo createdAt(streamStartedAt),La coincidencia exacta solo limpia esta generación:
     * El punto de control creado en la regeneración concurrente es diferente y no se eliminará accidentalmente.;Borrar incluso cuando no hay ningún mensaje de USUARIO(Ninguna línea única degenerada).
     */
    suspend fun deleteGenerationCheckpoints(sessionId: String, createdAt: Long) {
        withContext(Dispatchers.IO) {
            database.generationCheckpointDao().deleteBySessionAndCreatedAt(sessionId, createdAt)
        }
    }

    /** Lea todo lo pendiente para generar un punto de control. */
    suspend fun getPendingGenerationCheckpoints(): List<GenerationCheckpointEntity> =
        withContext(Dispatchers.IO) { database.generationCheckpointDao().getAllPending() }

    /**
     * B5-01: Restaure la generación de interrupciones eliminadas a la fuerza cuando se inicia la aplicación.
     *
     * Cada punto de control residual:
     * - El mensaje ya existe:Complete los últimos fragmentos de contenido y agréguelos [interrumpido] marca
     * - El mensaje no existe y no hay ningún mensaje del asistente de actualización.:Mensaje del asistente de reconstrucción
     * - El mensaje no existe pero hay un mensaje del asistente de actualización.(ha sido regenerado):Trátelo como punto de control caducado y elimínelo.
     */
    suspend fun recoverInterruptedGenerations() {
        withContext(Dispatchers.IO) {
            val pending = runCatching { database.generationCheckpointDao().getAllPending() }
                .getOrElse { e ->
                    Logger.w(TAG, "No se pudo leer el punto de control generado", e)
                    return@withContext
                }
            for (cp in pending) {
                try {
                    val session = sessionDao.getById(cp.sessionId)
                    if (session == null || session.deletedAt != null) {
                        // Las sesiones eliminadas temporalmente desactivan la escritura de recuperación; Limpiar los puntos de control residuales pero dejar intactos los mensajes históricos.
                        database.generationCheckpointDao().deleteByAssistantMessageId(cp.assistantMessageId)
                        continue
                    }
                    val existing = messageDao.getByMessageId(cp.assistantMessageId)
                    // v1.0.80 (M-2): El mensaje existe y está completo.(>= longitud del punto de control)saltar directamente,
                    // No más agregar [interrumpido] marca. Anteriormente, mientras el punto de control estuviera pendiente, la oferta se reponía.,
                    // Los mensajes completados estarán mal etiquetados después de reiniciar [interrumpido],causa"El contenido de reingreso es confuso"
                    // (Respuesta completa + marca de interrupción redundante).
                    if (existing != null && existing.content.length >= cp.content.length) {
                        // Punto de control sobrescrito de contenido: se completa normalmente pero no puede eliminar el punto de control.
                        // El residuo también debe eliminarse para evitar escanear repetidamente el mismo registro cada vez que se inicia.
                        database.generationCheckpointDao().deleteByAssistantMessageId(cp.assistantMessageId)
                        continue
                    }
                    val marker = "[interrumpido]"
                    val base = when {
                        existing != null && existing.content.length >= cp.content.length -> existing.content
                        else -> cp.content
                    }
                    val content = when {
                        base.contains(marker) -> base
                        base.isBlank() -> marker
                        else -> base + "\n\n" + marker
                    }
                    val ui: UIMessage? = when {
                        existing != null -> existing.toUIMessage().copy(content = content)
                        database.generationCheckpointDao().countNewerAssistantMessages(cp.sessionId, cp.updatedAt) > 0 -> {
                            database.generationCheckpointDao().deleteByAssistantMessageId(cp.assistantMessageId)
                            null
                        }
                        else -> {
                            val id = runCatching { Uuid.parse(cp.assistantMessageId) }.getOrElse {
                                Logger.w(TAG, "generation checkpoint assistantMessageId no UUID: " + cp.assistantMessageId)
                                Uuid.random()
                            }
                            UIMessage(
                                id = id,
                                role = MessageRole.ASSISTANT,
                                content = content,
                                createdAt = cp.createdAt,
                            )
                        }
                    }
                    if (ui != null) {
                        upsertMessage(cp.sessionId, ui)
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Falló la generación de interrupción de recuperación: " + cp.assistantMessageId, e)
                }
            }
            if (pending.isNotEmpty()) {
                Logger.i(TAG, "Recuperación de generación de interrupción completada: " + pending.size + " punto de control")
            }
        }
    }

    /**
     * Transmisión de actualizaciones a los mensajes del asistente(Insertar lo mismo varias veces id). Cada eliminación primero y luego inserción garantiza que el índice FTS sea idempotente.
     *
     * v1.97 (P1-2): Nuevo [skipFts] parámetros. Streaming de colocación periódica(builder Sigue creciendo)biografía del tiempo true,
     * saltar toNgram + deleteFts + insertFts Sobrecarga de CPU/IO(El texto largo en Ngram puede tardar decenas de milisegundos).
     * FTS son datos derivados,Una vez completada la transmisión, asegúrese de que FtsIndexConsistent se complete después de la colocación final del disco o del siguiente inicio.
     * ruta sin transmisión(Colocación final / Colocación interrumpida / Mensaje de herramienta / Mensaje de referencia)Usar predeterminado false,Asegúrese de la sincronización FTS.
     */
    suspend fun upsertMessage(sessionId: String, message: UIMessage, skipFts: Boolean = false) {
        withDatabaseRecovery("upsertMessage") {
            // H-SESS1: mesa cruzada(messages + FTS + sessions)Envolver con transacción,Garantizar la coherencia de las actualizaciones de streaming
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    var entity = message.toEntity(sessionId)
                    // v1.0.85 (T-1): Mantenga la primera actualización con la misma identificación. createdAt — REPLACE La semántica cubrirá toda la línea.,
                    // Si se transmite/Interrumpir la recuperación/UIMessage editado usa el valor predeterminado createdAt(hora actual),
                    // La sincronización del mensaje se actualizará para"ahora",causando confusión en la clasificación(Los primeros mensajes llegan hasta el final de la conversación.).
                    // Consultar filas antiguas,existe y createAt no está presente mientras se conserva el valor anterior,Mantener el tiempo original.
                    val existing = messageDao.getById(sessionId, entity.id)
                    if (existing != null) {
                        if (existing.createdAt != entity.createdAt) {
                            entity = entity.copy(createdAt = existing.createdAt)
                        }
                        // Comentarios del usuario 2 (v2.6.4): "La respuesta se generó normalmente y desaparecerá cuando vuelvas a ingresar al cuadro de chat".
                        //
                        // Causa raíz:upsertMessage es REEMPLAZAR la semántica. Si ya hay contenido que no está vacío,
                        // Luego apareció un mensaje con contenido vacío (restablecimiento del estado de transmisión,
                        // Si la ruta de recuperación pasa por contenido vacío, la rueda de herramientas reconstruye los campos faltantes, etc.), la respuesta completa quedará vacía.
                        // Agregue protección de regresión de contenido aquí: la fila anterior tiene contenido pero el nuevo valor está vacío → Mantenga el contenido antiguo.
                        if (existing.content.isNotBlank() && entity.content.isBlank()) {
                            Logger.w(
                                TAG,
                                "upsertMessage Evite que el contenido vacío sobrescriba las respuestas existentes(id=${entity.id}, longitud antigua=${existing.content.length})",
                            )
                            entity = entity.copy(content = existing.content)
                        }
                        // v92: Mantenga la primera actualización con la misma identificación. seq(REPLACE No cambie el número de secuencia estable del mensaje)
                        if (entity.seq == 0L) {
                            entity = entity.copy(seq = existing.seq)
                        }
                        // commitSeq Como parte de la identidad del mensaje como seq;REPLACE No se puede borrar al actualizar.
                        // De lo contrario, los nuevos mensajes de enlace se devolverán a la antigua rama de clasificación, lo que provocará que las posiciones históricas de los mensajes se desvíen.
                        if (entity.commitSeq == 0L) {
                            entity = entity.copy(commitSeq = existing.commitSeq)
                        }
                        // v1.0.88 (R-2): Campos de identidad variantes — rueda de herramientas/Interrumpir la recuperación/Construido por colocación periódica.
                        // UIMessage Campos como varianteGroupId a menudo se pierden,REPLACE Identidad variante perdida después de sobrescribir,
                        // Al volver a ingresar al árbol de sesiones y reconstruirlo, el mensaje se cuelga en la ubicación incorrecta.(superponerse/Dislocación). Utilice filas antiguas para cubrir cuando falten nuevos valores;
                        // nueva variante(variantGroupId No vacío)No afectado.
                        if (entity.variantGroupId == null && existing.variantGroupId != null) {
                            entity = entity.copy(
                                variantGroupId = existing.variantGroupId,
                                variantIndex = existing.variantIndex,
                                variantCount = existing.variantCount,
                                parentGroupId = existing.parentGroupId,
                            )
                        }
                    } else if (entity.seq == 0L) {
                        // v92: Nueva distribución de mensajes intrasesión monótona. seq = max+1(Fijado en la primera inserción.,Reservado para actualizaciones posteriores)
                        entity = entity.copy(seq = messageDao.getMaxSeq(sessionId) + 1)
                    }
                    messageDao.upsert(entity)
                    // Phase 10.3: sincronizar FTS(Eliminar e insertar,Evite entradas de índice duplicadas)
                    // v1.97 (P1-2): skipFts=true salto de tiempo,Evite la colocación periódica de discos de transmisión y la reconstrucción repetida FTS
                    if (!skipFts) {
                        resultOf {
                            syncFtsDelete(entity.id)
                            syncFtsInsert(entity.id, entity.content)
                        }.onError { _, t -> Logger.w(TAG, "FTS upsert sync failed: ${t?.message ?: ""}") }
                    }
                    if (message.role == MessageRole.ASSISTANT && message.content.isNotEmpty()) {
                        updateSessionPreview(sessionId, message)
                    }
                }
            }
        }
    }

    /**
     * Editar mensaje del asistente:Actualizar directamente el contenido del mensaje especificado.,y sincronizar índices FTS.
     */
    suspend fun updateMessageContent(sessionId: String, messageId: Uuid, content: String) {
        // H-SESS1: mesa cruzada(messages + FTS + sessions)Envolver con transacción
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val message = messageDao.getById(sessionId, messageId.toString()) ?: return@withTransaction
                val updated = message.copy(content = content, reasoning = null)
                messageDao.upsert(updated)
                // Phase 10.3: Sincronizar el índice FTS(Eliminar e insertar,Evite entradas de índice duplicadas)
                resultOf {
                    syncFtsDelete(message.id)
                    syncFtsInsert(message.id, content)
                }.onError { _, t -> Logger.w(TAG, "FTS update failed: ${t?.message ?: ""}") }
                // Actualizar la vista previa de la conversación con el contenido del mensaje más reciente
                updateSessionPreview(sessionId, updated.toUIMessage())
            }
        }
    }

    /** Truncar sesión:Eliminar createdAt >= fromCreatedAt noticias(Editar/efecto renacimiento). Eliminar el índice FTS simultáneamente. */
    suspend fun truncateFrom(sessionId: String, fromCreatedAt: Long) {
        // H-SESS1: mesa cruzada(FTS + messages)Envolver con transacción
        withContext(Dispatchers.IO) {
            database.withTransaction {
                truncateFromInternal(sessionId, fromCreatedAt)
            }
        }
    }

    /** truncateFrom versión no transaccional de,Para personas que llaman y ya están dentro de una transacción(Como por ejemplo [deleteLastMessage])Reutilizar. */
    private suspend fun truncateFromInternal(sessionId: String, fromCreatedAt: Long) {
        // Phase 10.3: Eliminar primero FTS(Depende de la subconsulta de mensajes,Debe ser antes de que se eliminen los mensajes.)
        syncFtsDeleteBySessionAndCreatedAt(sessionId, fromCreatedAt)
        messageDao.deleteFromCreatedAt(sessionId, fromCreatedAt)
        database.generationCheckpointDao().deleteBySessionAndCreatedAtFrom(sessionId, fromCreatedAt)
        // B-13: Después del truncamiento, vuelva a calcular la vista previa del último mensaje y el recuento de redundancia de la conversación.(Si está vacío, déjelo en blanco vista previa + recuento 0),
        // Alineado con el comportamiento de actualización previa/incremento de recuento de deleteMessageRow,Evitar que las vistas previas antiguas permanezcan en la lista de sesiones/Contar.
        val newLast = messageDao.getLastBySession(sessionId)
        sessionDao.updatePreview(
            id = sessionId,
            preview = newLast?.let { previewText(it.content) } ?: "",
            now = System.currentTimeMillis(),
        )
        sessionDao.setMessageCount(sessionId, messageDao.countBySession(sessionId))
    }

    /** Eliminar el último mensaje del asistente en la sesión.(efecto renacimiento). Eliminar el índice FTS simultáneamente. */
    suspend fun deleteLastMessage(sessionId: String) {
        // H-SESS1/H-SESS2: mesa cruzada(messages + FTS + sessions)leer-cambiar-escribir,Utilice envoltorios de transacciones para garantizar la coherencia;
        // La actualización de vista previa cambia al uso de átomos UPDATE(sin lectura-cambiar-escribir).
        withContext(Dispatchers.IO) {
            database.withTransaction {
                messageDao.getLastBySession(sessionId)?.let { last ->
                    // Reutilizar truncateFromInternal(Con sincronización FTS),Evite eliminar eliminaciones de índices faltantes
                    truncateFromInternal(sessionId, last.createdAt)
                }
                // Actualizar la vista previa al nuevo último elemento(átomo UPDATE,sin lectura-cambiar-escribir)
                val newLast = messageDao.getLastBySession(sessionId)
                sessionDao.updatePreview(
                    id = sessionId,
                    preview = newLast?.let { previewText(it.content) } ?: "",
                    now = System.currentTimeMillis(),
                )
            }
        }
    }

    /** Recibe el último mensaje de la conversación.(Desechable,no Flow). */
    suspend fun getLastMessage(sessionId: String): UIMessage? {
        return messageDao.getLastBySession(sessionId)?.toUIMessage()
    }

    private suspend fun syncFtsDelete(messageId: String) {
        if (!MessageFtsRuntime.useFts5) messageFtsDao.deleteFts(messageId)
    }

    private suspend fun syncFtsDeleteBySession(sessionId: String) {
        if (!MessageFtsRuntime.useFts5) messageFtsDao.deleteFtsBySession(sessionId)
    }

    private fun deleteMessageImages(messageIds: List<String>) {
        messageIds.forEach { messageImageStore.deleteByMessageId(it) }
    }

    private suspend fun syncFtsDeleteBySessionAndCreatedAt(sessionId: String, fromCreatedAt: Long) {
        if (!MessageFtsRuntime.useFts5) messageFtsDao.deleteFtsBySessionAndCreatedAt(sessionId, fromCreatedAt)
    }

    private suspend fun syncFtsClear() {
        if (!MessageFtsRuntime.useFts5) messageFtsDao.clearFts()
    }

    /**
     * v2.6.8: Escriba el índice de ngramas FTS4.
     *
     * texto índice ir [InternalMarkupSanitizer.searchableContent]: La cola del estado de la tarjeta es un campo de máquina
     * (`cardId=`,JSON valor clave), estar indexado equivale a permitir a los usuarios buscar `card_state`,`hours` este tipo
     * Fragmentos de contenido invisible, los fragmentos encontrados también son JSON. El resumen de una frase adjunto a la devolución de la tarjeta todavía está en el texto.
     * La semántica que buscan los usuarios no se pierde.
     */
    private suspend fun syncFtsInsert(messageId: String, content: String) {
        if (!MessageFtsRuntime.useFts5) {
            messageFtsDao.insertFts(messageId, MessageFtsManager.toNgram(InternalMarkupSanitizer.searchableContent(content)))
        }
    }

    private suspend fun searchFtsByMode(matchQuery: String): List<MessageSearchJoin> =
        if (MessageFtsRuntime.useFts5) messageFtsDao.searchFts5(matchQuery) else messageFtsDao.searchFts(matchQuery)

    private suspend fun searchFtsByModeInSession(matchQuery: String, sessionId: String, limit: Int): List<MessageSearchJoin> =
        if (MessageFtsRuntime.useFts5) {
            messageFtsDao.searchFts5InSession(matchQuery, sessionId, limit)
        } else {
            messageFtsDao.searchFtsInSession(matchQuery, sessionId, limit)
        }

    /**
     * Buscar mensajes en texto completo(Entre sesiones). Los resultados devueltos incluyen el título de la sesión + un fragmento de contenido basado en el texto original.
     *
     * enrutamiento(FTS Prioridad,LIKE Sólo el resultado final,Ver reglas de selección [shouldUseLikeFallback]):
     * - FTS Ir cuando esté disponible MATCH([MessageFtsManager] Convertir a texto original FTS5 según la palabra de consulta de ngram del motor actual/FTS4)
     * - LIKE Cubrir límites no cubiertos por FTS(Sin token/palabra indexable)Excepción con consulta FTS
     * - Ambas rutas JOIN mensajes + sesiones se descubren a la vez,No más controles de seguimiento uno por uno session(B4-01 eliminar N+1)
     * - Los fragmentos están unificados por [buildSnippet] Construido en base a contenido original,Alinear con la semántica de coincidencias
     */
    suspend fun searchMessages(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        searchMessagesInternal(query)
    }

    /**
     * Search original USER/ASSISTANT text within one session.
     *
     * This is intentionally separate from the cross-session search API: recall
     * injected into a prompt must never borrow a similarly matching message
     * from another conversation.
     */
    suspend fun searchMessagesInSession(sessionId: String, query: String, limit: Int = 8): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (sessionId.isBlank() || trimmed.isBlank() || limit <= 0) return@withContext emptyList()
            val boundedLimit = limit.coerceIn(1, 50)
            val joins =
                if (shouldUseLikeFallback(trimmed)) {
                    searchLikeAndJoinInSession(sessionId, trimmed, boundedLimit)
                } else {
                    when (
                        val result = resultOf {
                            searchFtsByModeInSession(
                                matchQuery = ftsMatchQuery(trimmed),
                                sessionId = sessionId,
                                limit = boundedLimit,
                            )
                        }
                    ) {
                        is io.zer0.common.Result.Success -> result.data
                        is io.zer0.common.Result.Error -> {
                            Logger.w(
                                TAG,
                                "${ErrorMessage.StorageError.IO_ERROR.toLogString()} " +
                                    "[scope=session_fts_search, fallback=LIKE, raw=${result.message}]",
                            )
                            searchLikeAndJoinInSession(sessionId, trimmed, boundedLimit)
                        }
                    }
                }
            return@withContext joins.map { join ->
                // v2.6.8: El texto original en los resultados./Los fragmentos también van a la salida de búsqueda. —— Incluso si el lado índice (FTS5 external content)
                // El índice aún se crea de acuerdo con el texto original y los fragmentos vistos por el usuario no pueden ser el estado de la tarjeta JSON.
                val searchable = InternalMarkupSanitizer.searchableContent(join.content)
                SearchResult(
                    messageId = join.messageId,
                    sessionId = join.sessionId,
                    sessionTitle = join.sessionTitle,
                    contentSnippet = buildSnippet(searchable, trimmed),
                    role = join.role,
                    createdAt = join.createdAt,
                    content = searchable,
                )
            }
        }

    /**
     * Implementación del núcleo de búsqueda:[searchMessages] con [searchMessageContentFlow] entrada solo compartida,garantia
     * El conjunto de resultados, el orden y la semántica de los fragmentos de los dos enlaces de búsqueda son consistentes(Evite la deriva en la implementación de FTS/LIKE).
     */
    private suspend fun searchMessagesInternal(query: String): List<SearchResult> {
        // Nota:transmisión upsertMessage(skipFts=true)FTS puede retrasarse después de la colocación periódica,
        // colocación final(skipFts=false)O asegúrese de que FtsIndexConsistent se complete la próxima vez que lo inicie.
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val joins: List<MessageSearchJoin> = if (shouldUseLikeFallback(trimmed)) {
            searchLikeAndJoin(trimmed)
        } else {
            // H-SESS1: FTS Respaldo cuando la consulta es anormal LIKE;Volver a lanzar correctamente con resultOf CancellationException
            // i18n Punto de transformación de demostración 5: inglés original codificado "FTS search failed, fallback to LIKE: ..."
            // Se cambió a ErrorMessage.StorageError.IO_ERROR + mensaje sin formato como información de depuración complementaria.
            // FTS El fracaso ha sido COMO,Sin lanzamiento hacia arriba ErrorMessage,Sólo los códigos de registro estandarizados facilitan el seguimiento de la agregación de alertas.
            when (val r = resultOf { searchFtsByMode(ftsMatchQuery(trimmed)) }) {
                is io.zer0.common.Result.Success -> r.data
                is io.zer0.common.Result.Error -> {
                    Logger.w(
                        TAG,
                        "${ErrorMessage.StorageError.IO_ERROR.toLogString()} [scope=fts_search, fallback=LIKE, raw=${r.message}]",
                    )
                    searchLikeAndJoin(trimmed)
                }
            }
        }

        return joins.map { join ->
            // v2.6.8: Lo mismo [searchMessagesInSession], tanto el fragmento como el texto original van a la salida de búsqueda.
            val searchable = InternalMarkupSanitizer.searchableContent(join.content)
            SearchResult(
                messageId = join.messageId,
                sessionId = join.sessionId,
                sessionTitle = join.sessionTitle,
                contentSnippet = buildSnippet(searchable, trimmed),
                role = join.role,
                createdAt = join.createdAt,
                // Tarea 2: traer el texto original a la interfaz de usuario para extraer 2 oraciones de contexto + resaltado de palabras clave
                content = searchable,
            )
        }
    }

    /** Genere términos de consulta MATCH de acuerdo con el motor FTS actual(FTS5 Texto original unicode61 / FTS4 ngram). */
    private fun ftsMatchQuery(trimmed: String): String =
        if (MessageFtsRuntime.useFts5) MessageFtsManager.toFts5MatchQuery(trimmed) else MessageFtsManager.toMatchQuery(trimmed)

    /**
     * Determine si la consulta debe omitir FTS e ir directamente a la coincidencia de subcadenas LIKE.
     *
     * Ninguno de los motores FTS puede cubrir de manera confiable el límite LIKE y debe retroceder:
     *  - Sin token de índice: la consulta no contiene chino ni inglés/Caracteres numéricos(Como por ejemplo "!!!","~~"),ngram con unicode61
     *    No puedo cortarlo token,MATCH debe ser un conjunto vacío;
     *  - Consulta de una sola palabra: las palabras chinas no son independientes del ngram FTS4 token(Sólo se conservará la última palabra del viaje.),FTS5 unicode61
     *    Y fusionar el CJK consecutivo en uno token,Una palabra MATCH no se recordará;Las letras individuales en inglés en FTS coinciden con palabras completas en lugar de subcadenas.
     * En estos dos casos utilice LIKE"Contener es un éxito"La semántica es consistente con las expectativas del usuario.
     */
    private fun shouldUseLikeFallback(trimmed: String): Boolean {
        if (trimmed.length == 1) return true
        return MessageFtsManager.toNgram(trimmed).isBlank()
    }

    /**
     * LIKE camino sucio:JOIN messages + sessions Revisión(SQL único para recuperar el título de la sesión).
     *
     * B4-01:El original ha sido eliminado."Consulta la sesión una a una y añade el título."de N+1;Este camino está sólo en [shouldUseLikeFallback]
     * Se activa cuando se produce un acierto o una excepción de consulta FTS,Y hay un límite superior de LIMIT,Las búsquedas normales casi nunca caen en esta rama.
     */
    private suspend fun searchLikeAndJoin(query: String): List<MessageSearchJoin> =
        messageDao.searchMessageContentLike(buildLikePattern(query), 50)

    private suspend fun searchLikeAndJoinInSession(sessionId: String, query: String, limit: Int): List<MessageSearchJoin> =
        messageDao.searchMessageContentLikeInSession(sessionId, buildLikePattern(query), limit)

    /**
     * v2.x: Búsqueda de contenido de mensajes Versión de flujo(Para que SearchViewModel escuche).
     *
     * Reutilización directa [searchMessagesInternal](con [searchMessages] La misma implementación),Entonces las dos búsquedas
     * El conjunto de visitas y la clasificación de la entrada son completamente consistentes con la semántica del fragmento.;Los fragmentos están unificados por [buildSnippet] Generado en base al texto original.
     * (FTS5 cinturón sin retorno `[ ]` marcado SQL snippet,UI / El panel de comando se puede mostrar directamente).
     *
     * llamador:SearchViewModel de searchMessageContent() Recójalo una vez y luego guárdelo en StateFlow.
     *
     * @param query Buscar palabras clave ingresadas por el usuario(sin escapar)
     * @return [SearchResult] de Flow(soltero emit,La consulta vacía devuelve una lista vacía)
     */
    fun searchMessageContentFlow(query: String): Flow<List<SearchResult>> = flow {
        emit(searchMessagesInternal(query))
    }.flowOn(Dispatchers.IO)

    /** M-SESS2: Construir cadena de patrón LIKE,Escapar de caracteres comodín \ % _ Luego envuélvalo en %...%. */
    private fun buildLikePattern(query: String): String {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    /**
     * Phase 10.3 (C-23 Fortalecer): Verifique la coherencia del índice FTS,Si es inconsistente, reconstruya en su totalidad.
     *
     * escena desencadenante:
     * - v8→v9 Después de la migración(messages_fts mesa vacia,Requiere indexación completa por primera vez)
     * - Modificar externamente la tabla de mensajes.(Después de la importación de la copia de seguridad)
     * - Corrupción del índice(FTS Inicie la recuperación automática la próxima vez después de consultar una excepción)
     *
     * El juicio se divide en dos niveles.:
     * 1. Comparación de recuento de filas:msgCount == ftsCount(messages ¿Es la misma base que message_fts?).
     * 2. Verificación puntual del contenido(C-23):Un número igual de líneas no significa que el contenido sea consistente. —— Editar/La regeneración hace que el contenido del mensaje se actualice en lugar de
     *    FTS Cuando falla la sincronización,Es posible que el número de filas no cambie, pero el índice aún contiene el contenido anterior. El muestreo más reciente en este momento.
     *    [FTS_SAMPLE_COUNT] mensajes,Construye una sonda MATCH con su texto actual,FTS Puede golpear el
     *    messageId considerado como consistente,De lo contrario, se considera inconsistente y se activa la reconstrucción.
     *
     * messages_fts Dos implementaciones(FTS4 Solo message_id+content_ngram,FTS5 external content
     * solo rowid+content)Ninguna de las columnas de tiempo update_at existe,messages no hay mesa updatedAt,Por lo tanto es imposible
     * comparación directa MAX(updatedAt);Verificación del contenido del muestreo(Pequeños cambios, acotados N veces al inicio. MATCH,No introducido
     * Problemas de rendimiento del escaneo completo).
     *
     * por [ChatViewModel] init Llamado de forma asincrónica cuando se inicia la aplicación.,IO ejecución del hilo.
     * La reconstrucción de mensajes de nivel 10.000 tarda unos cientos de milisegundos,No bloquea la interfaz de usuario.
     */
    suspend fun ensureFtsIndexConsistent() = withContext(Dispatchers.IO) {
        val msgCount = resultOf { messageDao.countMessages() }.getOrNull() ?: -1
        val ftsCount = resultOf { messageFtsDao.countFts() }.getOrNull() ?: -1
        if (msgCount < 0 || ftsCount < 0) {
            Logger.w(TAG, "FTS count check failed: msg=$msgCount fts=$ftsCount")
            return@withContext
        }
        if (msgCount != ftsCount) {
            Logger.i(TAG, "FTS index inconsistent (row count): msg=$msgCount fts=$ftsCount, rebuilding...")
            rebuildFtsIndex()
            return@withContext
        }
        // C-23: Una vez que el número de filas es igual, se comprueba aleatoriamente el contenido adicional.,Capturar"El contenido se actualiza mientras FTS no está sincronizado y el recuento de filas sigue siendo el mismo"de deriva.
        val staleSampleIds = ftsSampleIdsStale()
        if (staleSampleIds.isEmpty()) {
            Logger.d(TAG, "FTS index consistent: $ftsCount rows, content spot-check OK")
        } else {
            Logger.i(
                TAG,
                "FTS content spot-check failed for ${staleSampleIds.size} sample(s) " +
                    "(e.g. ${staleSampleIds.first()}), rebuilding...",
            )
            rebuildFtsIndex()
        }
    }

    /**
     * C-23: Devuelve una lista de mensajes de muestra cuyo contenido no coincide con el índice FTS.(Las muestras de contenido en blanco no participan en el juicio.).
     */
    private suspend fun ftsSampleIdsStale(): List<String> {
        val samples = resultOf { messageDao.getFtsSample(FTS_SAMPLE_COUNT) }
            .onError { msg, t ->
                // semánticamente similar"La lectura de la base de datos falló momentáneamente",Esto no debe utilizarse para juzgar erróneamente las inconsistencias y reconstruir el índice completo.;
                // llevar un diario,Volver vacío(Saltar esta verificación puntual).
                Logger.w(TAG, "FTS sample fetch failed, skipping content check: $msg", t)
            }
            .getOrNull() ?: return emptyList()
        val stale = mutableListOf<String>()
        for (row in samples) {
            if (row.content.isNotBlank() && !ftsMatchesMessage(row)) {
                stale.add(row.id)
            }
        }
        return stale
    }

    /**
     * C-23: Determina si una sola muestra puede golpearse a sí misma con su texto en el modo FTS actual.
     *
     * Construya una sonda MATCH utilizando un subconjunto de tokens del contenido más reciente de la muestra,FTS La descripción del ID del mensaje se incluye en el resultado devuelto.
     * El contenido del índice es consistente con el mensaje.;De lo contrario, se considera que el índice todavía es contenido antiguo.(deriva).
     */
    private suspend fun ftsMatchesMessage(sample: FtsRebuildRow): Boolean {
        val probe = buildFtsContentProbe(sample.content)
        if (probe.isBlank()) return true
        return when (val r = resultOf { searchFtsByMode(probe) }) {
            is io.zer0.common.Result.Success -> r.data.any { it.messageId == sample.id }
            is io.zer0.common.Result.Error -> {
                // FTS La anomalía de la consulta no prueba que la muestra se haya desviado(Y la ruta del error de consulta en sí ME GUSTA cubrirla.);
                // saltar registro,No reconstruir erróneamente basándose en esto.,Evite que un error momentáneo destruya todo el índice.
                Logger.w(TAG, "FTS probe search failed for ${sample.id}: ${r.message}")
                true
            }
        }
    }

    /**
     * C-23: Construya una cadena de sonda FTS MATCH a partir de contenido de muestra(tomar [FTS_PROBE_TOKEN_LIMIT] Sin duplicados token).
     *
     * Hacer Y con todos los tokens en todo el contenido dará como resultado"Solo un cambio de token"Los mensajes generan alta sensibilidad pero alta
     * falso negativo;Tomar una pequeña cantidad de tokens representativos puede alcanzar de manera estable el índice sincronizado.,Al mismo tiempo, se pierden suficientes contenidos antiguos.
     */
    private fun buildFtsContentProbe(content: String): String {
        // v2.6.8: La cadena de detección debe ser del mismo calibre que el texto del índice; de lo contrario, los mensajes con el estado de la tarjeta se considerarán erróneamente como
        // “Deriva del índice”Y active la reconstrucción completa repetidamente.
        val searchable = InternalMarkupSanitizer.searchableContent(content)
        if (MessageFtsRuntime.useFts5) {
            // FTS5 unicode61 Segmentar el índice del texto original por espacio en blanco:Envuelve directamente las palabras en frases.
            val terms = searchable.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
            return terms.take(FTS_PROBE_TOKEN_LIMIT)
                .joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"" }
        }
        // FTS4 ngram índice:hacer con el contenido actual 2-gram,Toma los primeros sin repetirlos. token(token Comillas incluidas).
        val tokens = MessageFtsManager.toNgram(searchable)
            .split(' ')
            .filter { it.isNotBlank() }
            .distinct()
        return tokens.take(FTS_PROBE_TOKEN_LIMIT)
            .joinToString(" ") { "\"${it.replace("\"", "")}\"" }
    }

    /**
     * Phase 10.3: Reconstruya completamente el índice FTS. Después de borrarlos, repita los mensajes y vuelva a insertarlos.(toNgram Convertir).
     *
     * v1.114: Envuelva clearFts + inserto artículo por artículo en `database.withTransaction` en,evitar el período de reconstrucción
     * FTS La consulta devuelve resultados inconsistentes(Después de borrar y antes de que se complete la inserción, la consulta devolverá datos vacíos o incompletos.). Cada inserto todavía usa
     * resultOf tolerancia a fallas,El fracaso de una sola entrada no afecta a otras entradas,La transacción se envía normalmente.
     * La persona que llama debe estar en el hilo IO.
     */
    suspend fun rebuildFtsIndex() = withContext(Dispatchers.IO) {
        // v1.114: paquete de transacciones,ClearFts garantizados + visibilidad atómica insertada,Evite inconsistencias en las consultas durante las reconstrucciones
        database.withTransaction {
            if (MessageFtsRuntime.useFts5) {
                messageFtsDao.rebuildFts5()
                Logger.i(TAG, "FTS5 rebuild done")
            } else {
                messageFtsDao.clearFts()
                val rows = messageDao.getAllForFtsRebuild()
                var ok = 0
                rows.forEach { row ->
                    resultOf {
                        messageFtsDao.insertFts(
                            row.id,
                            MessageFtsManager.toNgram(InternalMarkupSanitizer.searchableContent(row.content)),
                        )
                        ok++
                    }.onError { _, t ->
                        Logger.w(TAG, "FTS rebuild insert failed for ${row.id}: ${t?.message ?: ""}")
                    }
                }
                Logger.i(TAG, "FTS rebuild done: $ok/${rows.size} messages indexed")
            }
        }
    }

    /**
     * Crear fragmentos de búsqueda:Antes y después de la posición de golpe. [SNIPPET_RADIUS] palabra,Compense ambos extremos según sea necesario "…".
     *
     * Haga coincidir la alineación semántica con FTS:FTS4 ngram El resultado no garantiza que el texto original contenga una subcadena de consulta continua.(solo pregunta query
     * Cada ficha de 2 gramos golpea),En este momento, el token de consulta del primer resultado se utiliza para localizar,Asegúrate de que el fragmento caiga con un golpe real.
     * en,En lugar de degenerar directamente en un prefijo cuando no hay coincidencia.LIKE El camino debe recorrerse con precisión.,El comportamiento se mantiene sin cambios.
     */
    private fun buildSnippet(content: String, query: String): String {
        val (matchStart, matchLength) = locateSnippetMatch(content, query)
        if (matchStart < 0) return content.take(SNIPPET_FALLBACK_LENGTH)
        val start = (matchStart - SNIPPET_RADIUS).coerceAtLeast(0)
        val end = (matchStart + matchLength + SNIPPET_RADIUS).coerceAtMost(content.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < content.length) "…" else ""
        return prefix + content.substring(start, end) + suffix
    }

    /**
     * Punto de anclaje del clip de posición,Regresar (índice inicial, longitud del partido);Devolución no encontrada (-1, 0).
     *
     * 1. Prefiere subcadenas exactas(No distingue entre mayúsculas y minúsculas):LIKE El camino debe llegar,El resultado es consistente con la implementación anterior.;
     * 2. De lo contrario, utilice el primer token de consulta de ngram que aparece en el texto original como punto de anclaje.,
     *    Alinear la semántica de coincidencia de tokens FTS(Tome la primera ficha para asegurarse de que el fragmento esté lo más adelantado posible).
     */
    private fun locateSnippetMatch(content: String, query: String): Pair<Int, Int> {
        val exact = content.indexOf(query, ignoreCase = true)
        if (exact >= 0) return exact to query.length
        if (query.isBlank()) return -1 to 0
        var bestStart = -1
        var bestLength = 0
        val tokens = MessageFtsManager.toNgram(query).split(' ').filter { it.isNotBlank() }
        for (token in tokens) {
            val idx = content.indexOf(token, ignoreCase = true)
            if (idx >= 0 && (bestStart < 0 || idx < bestStart)) {
                bestStart = idx
                bestLength = token.length
            }
        }
        return bestStart to bestLength
    }

    /**
     * Actualizar vista previa de la sesión + actualizadoEn.
     *
     * Ya no nombrar títulos automáticamente con el prefijo del primer mensaje,El título permanece como predeterminado."nueva sesión",
     * Se llama cuando la interfaz de usuario sale de la conversación. [io.zer0.muse.ui.ChatViewModel.autoTitleOnExit] Activar nombres de resumen de IA.
     */
    private suspend fun updateSessionPreview(sessionId: String, message: UIMessage) {
        val preview = previewText(message.content)
        sessionDao.updatePreviewOnly(
            id = sessionId,
            preview = preview,
            now = System.currentTimeMillis(),
        )
    }

    // ── herramientas de conversión ──────────────────────────────────────────────────────────

    /** Nueva ruta de lectura de proyección: derivada del campo heredado cuando el mensaje antiguo no tiene partes, manteniendo todos los campos antiguos. */
    private suspend fun projectMessagesForRead(entities: List<MessageEntity>): List<UIMessage> {
        if (!ConversationRebuildFlagStore.current.useMessageProjection || entities.isEmpty()) {
            return orderConversationMessages(entities.map { it.toUIMessage() })
        }
        val ids = entities.map { it.id }
        val parts = database.messagePartDao().getByMessages(ids)
        val projection = ConversationProjector.project(
            messages = entities,
            parts = parts,
            useCommitSeq = ConversationRebuildFlagStore.current.useCommitSeq,
        )
        val byId = entities.associateBy { it.id }
        return orderConversationMessages(
            projection.messages.mapNotNull { projected ->
                byId[projected.id]?.toUIMessage()?.copy(
                    content = projected.content,
                    reasoning = projected.reasoning,
                )
            },
        )
    }

    /** Lista de conversación/El sistema solicita mostrar solo el texto principal y ocultar el estado de ánimo,mod,think etc. bloque interno y cola de estado de la tarjeta. */
    private fun previewText(content: String): String =
        InternalMarkupSanitizer.displayContent(content).take(MESSAGE_PREVIEW_LENGTH).ifBlank { "…" }

    /** MessageEntity → UIMessage. */
    private fun MessageEntity.toUIMessage(): UIMessage {
        // B6-02: Limpieza de mensajes históricos — Compatible con datos antiguos que aún no han sido eliminados <moodfx> etiqueta
        val parsedMoodSkin = MoodSkinParser.extract(content, moodSkin)
        return UIMessage(
            // Phase 8.5 Reparación: Los ID en formatos que no sean UUID no fallan(Importación de datos/Compatible con versiones anteriores),recurrir al azar UUID
            id = runCatching { Uuid.parse(id) }.getOrElse {
                Logger.w(TAG, "invalid message id (not UUID): $id")
                Uuid.random()
            },
            role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER),
            content = parsedMoodSkin.second,
            reasoning = reasoning,
            thinkingSignature = thinkingSignature,
            thinkingEncryptedContent = thinkingEncryptedContent,
            modelId = modelId,
            createdAt = createdAt,
            seq = seq,
            commitSeq = commitSeq,
            imageUrls = parseImageUrls(imageUrlsJson),
            // v2.6.16: Mensaje de voz ida y vuelta (línea antigua) voiceUri=NULL / voiceDurationMs=0, es decir, mensajes que no son de voz)
            voiceUri = voiceUri,
            voiceDurationMs = voiceDurationMs,
            favorite = favorite,
            favoriteTag = favoriteTag,
            citationUrls = parseImageUrls(citationUrlsJson),
            ragCitations = parseRagCitations(ragCitationsJson),
            imageBase64List = messageImageStore.toBase64List(parseImageUrls(imageBase64Json)),
            // Reparación de auditoría (S-02): URL de resultado de generación de vídeo ida y vuelta(Anteriormente solo se almacenaba en la memoria.,Reiniciar perdido)
            videoFileUri = videoFileUri,
            artifactIds = parseImageUrls(artifactIdsJson),
            moodSkin = parsedMoodSkin.first ?: moodSkin,
            mood = mood,
            reflection = reflection,
            reaction = reaction,
            variantGroupId = variantGroupId,
            variantIndex = variantIndex,
            variantCount = variantCount,
            parentGroupId = parentGroupId,
            // v1.0.72: Herramienta de recuperación de información de tarjetas de llamadas.(ToolCallCard se muestra normalmente después de reiniciar)
            toolCallInfo = toolCallInfoJson?.let { json ->
                runCatching {
                    io.zer0.common.AppJson.decodeFromString(io.zer0.ai.core.ToolCallInfo.serializer(), json)
                }.getOrNull()
            },
            // A5: Generar metadatos ida y vuelta(Los datos antiguos son null,UI Estimación local alternativa)
            durationMs = durationMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            reasoningTokens = reasoningTokens,
            // Las filas antiguas solo tienen valores combinados:Presione "Leer" para explicar,Sin reabastecimiento ni recálculo;cacheWriteTokens viejo comportamiento NULL=desconocido
            cachedTokens = cachedTokens,
            cacheWriteTokens = cacheWriteTokens,
            // H11: El mensaje fuente del mensaje de traducción. id(Comparación y plegado de texto original.)
            translationSourceId = translationSourceId,
            // v2.4.6: Mapeo de traducción ida y vuelta(Nombre del idioma → traducción)
            translations = parseTranslations(translationsJson),
            // v2.6.13: Metadatos adjuntos ida y vuelta. Esta columna de datos antiguos es "[]", se resuelve en una lista vacía y el comportamiento es el mismo que antes del cambio.
            attachments = parseAttachments(attachmentsJson),
        )
    }

    /** UIMessage → MessageEntity. */
    private fun UIMessage.toEntity(sessionId: String): MessageEntity = MessageEntity(
        id = id.toString(),
        sessionId = sessionId,
        role = role.name,
        content = content,
        reasoning = reasoning,
        thinkingSignature = thinkingSignature,
        thinkingEncryptedContent = thinkingEncryptedContent,
        modelId = modelId,
        createdAt = createdAt,
        seq = 0L,
        commitSeq = 0L,
        imageUrlsJson = encodeImageUrls(imageUrls),
        // v2.6.16: Mensaje de voz Audio Cotización + Duración
        voiceUri = voiceUri,
        voiceDurationMs = voiceDurationMs,
        favorite = favorite,
        // v1.104 U7: Etiqueta de grupo de recogida ida y vuelta.(Predeterminado NULL,No agrupado)
        favoriteTag = favoriteTag,
        citationUrlsJson = encodeImageUrls(citationUrls),
        // v1.133: RAG Persistencia de la lista de referencia ida y vuelta
        ragCitationsJson = encodeRagCitations(ragCitations),
        // Phase 8.6: Lista de imágenes multimodales base64
        // v1.134 P1-2: MessageImageStore Coloque la imagen grande base64 en el disco. filesDir/muse_images/,
        // DB sólo mantener "file://xxx" camino,Evite la expansión del tamaño de fila de la tabla de mensajes;
        // datos cortos(< 1024 personaje)todavía existe base64,Ninguno al leer datos antiguos "file://" El prefijo se devuelve directamente.
        imageBase64Json = encodeImageUrls(messageImageStore.toPersistable(id.toString(), imageBase64List)),
        // Reparación de auditoría (S-02): URL de resultado de generación de vídeo ida y vuelta
        videoFileUri = videoFileUri,
        // v1.43: Artifact id lista
        artifactIdsJson = encodeImageUrls(artifactIds),
        // v1.103: MOOD / reflection persistencia(Anteriormente, toEntity se descartaba, lo que provocaba que desapareciera después del corte de página.)
        moodSkin = moodSkin,
        mood = mood,
        reflection = reflection,
        // Función1: Mensaje emoticon respuesta ida y vuelta
        reaction = reaction,
        // v1.0.30: Persistencia de información variante
        variantGroupId = variantGroupId,
        variantIndex = variantIndex,
        variantCount = variantCount,
        parentGroupId = parentGroupId,
        parentMessageId = parentMessageId,
        // v1.0.72: Persistencia de información de tarjeta de llamadas de herramienta(ToolCallCard se muestra normalmente después de reiniciar)
        toolCallInfoJson = toolCallInfo?.let {
            runCatching { io.zer0.common.AppJson.encodeToString(io.zer0.ai.core.ToolCallInfo.serializer(), it) }.getOrNull()
        },
        // A5: Generar persistencia de metadatos(provider Se prefiere la medición real,Falta es null)
        durationMs = durationMs,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
        cacheWriteTokens = cacheWriteTokens,
        // H11: El mensaje fuente del mensaje de traducción. id(Comparación y plegado de texto original.)
        translationSourceId = translationSourceId,
        // v2.4.6: Persistencia del mapeo de traducción
        translationsJson = encodeTranslations(translations),
        // v2.6.13: Metadatos adjuntos ida y vuelta. Anteriormente, esta columna se había escrito con el valor predeterminado "[]",
        // Entonces, el único archivo adjunto en el mensaje era la línea de texto "Guardado en el espacio de trabajo". Después de reiniciar, ni siquiera pude deletrear el nombre del archivo.
        attachmentsJson = encodeAttachments(attachments),
    )

    /** v1.0.30: Obtenga mensajes por identificación (para consulta de variantes). */
    suspend fun getMessageById(messageId: String): MessageEntity? = withContext(Dispatchers.IO) { messageDao.getByMessageId(messageId) }

    /**
     * Revisión de corrección (2.0 B-02): Lea el mensaje por identificación y conviértalo en mensaje de UI — Si el generador de memoria no tiene contenido durante la recuperación de la interrupción
     * (Detener después de cambiar de sesión),Retroceder para leer la ronda actual de mensajes en DB(Se ha escrito parte del contenido de la colocación periódica en streaming).
     */
    suspend fun getMessageAsUiMessage(messageId: String): UIMessage? =
        withContext(Dispatchers.IO) { messageDao.getByMessageId(messageId)?.toUIMessage() }

    /**
     * Revisión de corrección (2.0 B-04): Convertir la lista de URL de medios a formato persistente — URI de datos base64 larga
     * [MessageImageStore] Descargar al archivo:// ruta,DB Guarde solo caminos cortos,Evite el relleno de base64 de nivel MB
     * messages OK(Anteriormente, el URI de datos de execGenerateImage/QrCode se escribía directamente imageUrlsJson).
     * http(s) La URL corta se devuelve sin cambios. Nombre de archivo con sufijo hash de contenido,La escritura paralela de un mismo mensaje no se cubre entre sí.
     */
    suspend fun toPersistableImageUrls(messageId: String, urls: List<String>): List<String> =
        withContext(Dispatchers.IO) { messageImageStore.toPersistable(messageId, urls) }

    /**
     * Revisión de corrección (2.0 A-04): Adjunte campos multimedia a los mensajes eliminados — La generación en segundo plano sigue ejecutándose después de cambiar de sesión.,
     * exec* Los medios no se pueden escribir en la interfaz de usuario actual. _messages(El mensaje pertenece a la conversación original.),Este método directamente DB
     * Golpea las noticias(Ocupación de la rueda de herramientas,Ya caído por persistAssistantToolMsg)Fusionar contenido/fotos/vídeo/Voz,
     * Puede verlo restaurando desde la base de datos cuando el usuario vuelve a la sesión original. Fusión idempotente de contenido(Si ya está incluido no se volverá a empalmar.).
     */
    suspend fun attachMediaToMessage(
        sessionId: String?,
        messageId: Uuid,
        content: String,
        imageUrls: List<String>? = null,
        videoFileUri: String? = null,
        voiceUri: String? = null,
        voiceDurationMs: Long = 0L,
    ) {
        if (sessionId.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val existing = messageDao.getByMessageId(messageId.toString()) ?: return@withTransaction
                val mergedContent = when {
                    existing.content.isBlank() -> content
                    existing.content.contains(content) -> existing.content
                    else -> existing.content + "\n\n" + content
                }
                val updated = existing.copy(
                    content = mergedContent,
                    imageUrlsJson = if (imageUrls != null) {
                        encodeImageUrls((parseImageUrls(existing.imageUrlsJson) + imageUrls).distinct())
                    } else {
                        existing.imageUrlsJson
                    },
                    videoFileUri = videoFileUri ?: existing.videoFileUri,
                    // empatía fonética:Si no se aprueba, se conservarán las referencias existentes en la biblioteca.,Cuando se apruebe, se sobrescribirán la referencia y la duración.
                    // (La duración depende de la cita.:Cambiar la duración después de cambiar la voz.,De lo contrario, simplemente escribir 0 borrará la duración anterior.)
                    voiceUri = voiceUri ?: existing.voiceUri,
                    voiceDurationMs = if (voiceUri != null) voiceDurationMs else existing.voiceDurationMs,
                )
                messageDao.upsert(updated)
                resultOf {
                    syncFtsDelete(updated.id)
                    syncFtsInsert(updated.id, updated.content)
                }.onError { _, t -> Logger.w(TAG, "attachMediaToMessage FTS sync failed: ${t?.message ?: ""}") }
            }
        }
    }

    /** v1.0.30: Recibe todos los mensajes del mismo grupo de variantes. */
    suspend fun getVariants(groupId: String): List<MessageEntity> = withContext(Dispatchers.IO) { messageDao.getVariants(groupId) }

    /** v1.0.30: Actualizar el recuento de grupos de variantes. */
    suspend fun updateVariantCount(groupId: String, count: Int) {
        withContext(Dispatchers.IO) { messageDao.updateVariantCount(groupId, count) }
    }

    /** v1.0.30: Inserte directamente MessageEntity (para actualizaciones de campos variantes). */
    suspend fun upsertMessageEntity(entity: MessageEntity) {
        withContext(Dispatchers.IO) { messageDao.upsert(entity) }
    }

    private fun encodeImageUrls(urls: List<String>): String = // v1.100: Cortocircuito en lista vacía,Evite la colocación cíclica(cada 300 caracteres)4 campos vacíos
        // Realice la serialización JSON una vez cada una.(Aunque el costo es pequeño.,Pero la acumulación de alta frecuencia)
        if (urls.isEmpty()) "[]" else runCatching { json.encodeToString(urlListSerializer, urls) }.getOrDefault("[]")

    private fun parseImageUrls(s: String): List<String> =
        runCatching { json.decodeFromString(urlListSerializer, s) }.getOrDefault(emptyList())

    /** v1.133: Lista de referencias de RAG serializadas(Cortocircuito en lista vacía,Evite la colocación de alta frecuencia por encima de la cabeza). */
    private fun encodeRagCitations(citations: List<RagCitation>): String =
        if (citations.isEmpty()) "[]" else runCatching { json.encodeToString(ragCitationListSerializer, citations) }.getOrDefault("[]")

    private fun parseRagCitations(s: String): List<RagCitation> =
        runCatching { json.decodeFromString(ragCitationListSerializer, s) }.getOrDefault(emptyList())

    /** v2.6.13: Serialización de metadatos de archivos adjuntos de mensajes. Almacenamiento de lista vacía "[]". */
    private val attachmentListSerializer: kotlinx.serialization.KSerializer<List<io.zer0.ai.core.AttachmentRef>> =
        kotlinx.serialization.builtins.ListSerializer(io.zer0.ai.core.AttachmentRef.serializer())

    private fun encodeAttachments(items: List<io.zer0.ai.core.AttachmentRef>): String =
        if (items.isEmpty()) "[]" else runCatching { json.encodeToString(attachmentListSerializer, items) }.getOrDefault("[]")

    private fun parseAttachments(s: String): List<io.zer0.ai.core.AttachmentRef> =
        runCatching { json.decodeFromString(attachmentListSerializer, s) }.getOrDefault(emptyList())

    /** v2.4.6: Serialización de mapeo de traducción(Nombre del idioma → traducción). Almacenamiento de mapas vacío "{}". */
    private val translationMapSerializer: kotlinx.serialization.KSerializer<Map<String, String>> =
        kotlinx.serialization.builtins.MapSerializer(
            kotlin.String.serializer(),
            kotlin.String.serializer(),
        )
    private fun encodeTranslations(translations: Map<String, String>): String {
        if (translations.isEmpty()) return "{}"
        return runCatching { json.encodeToString(translationMapSerializer, translations) }.getOrDefault("{}")
    }

    private fun parseTranslations(s: String): Map<String, String> =
        runCatching { json.decodeFromString(translationMapSerializer, s) }.getOrDefault(emptyMap())
}
