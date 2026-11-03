package io.zer0.muse.data.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** recuento de mensajes de rol(Para panel de estadísticas). */
data class RoleCount(
    val role: String,
    val cnt: Int,
)

/** Uso total medido para una sola sesión (provider Número de tokens devueltos, estimación no local). */
data class SessionUsageAggregate(
    val requests: Int,
    val promptTokens: Long,
    val completionTokens: Long,
)

/** Phase 10.3: Resultados de la búsqueda ÚNETE a la proyección(Mensaje + título de la conversación,Evite consultas N+1). */
data class MessageSearchJoin(
    val messageId: String,
    val sessionId: String,
    val content: String,
    val role: String,
    val createdAt: Long,
    val sessionTitle: String,
)

/**
 * Objeto de acceso a datos de mensajes.
 */
@Dao
interface MessageDao {

    /** Observar todos los mensajes en una conversación específica.(Ordenar ascendente por secuencia monótona). Para copia de seguridad y exportación,Se requiere carga completa. */
    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END ASC, createdAt ASC, id ASC",
    )
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    /**
     * H-SESS3: Observe los últimos mensajes de límite de la sesión especificada(Ordenar por creadoEn ascendente),UI Para renderizado en streaming.
     *
     * Para evitar OOM causado por la carga completa de sesiones largas: la subconsulta primero presiona DESC para obtener la última barra de límite,
     * La capa exterior está ordenada por ASC.,Se garantiza que devolverá los "elementos límite más recientes" y los mostrará en orden cronológico.
     * La historia anterior consiste en [getOlderBySession] Cargando en páginas.limit Pasado por la persona que llama
     * (Como por ejemplo [SessionRepository.OBSERVE_LIMIT]).
     */
    @Query(
        "SELECT * FROM (SELECT * FROM messages WHERE sessionId = :sessionId AND deletedAt IS NULL ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END DESC, createdAt DESC, id DESC LIMIT :limit) ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END ASC, createdAt ASC, id ASC",
    )
    fun observeRecentBySession(sessionId: String, limit: Int): Flow<List<MessageEntity>>

    /**
     * v1.53-A1: carga de ventana — Obtenga los últimos mensajes de límite de la sesión especificada(Tome en orden descendente por creado en,Luego inviértelo en la memoria.).
     *
     * Se utiliza para la paginación en la carga inicial.:Obtenga solo los artículos de PAGE_SIZE más recientes,Evite el retraso causado por cargar todo a la vez/OOM.
     * El orden de devolución es descendente.(último primero),La persona que llama necesita reversed().
     */
    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND deletedAt IS NULL ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END DESC, createdAt DESC, id DESC LIMIT :limit",
    )
    suspend fun getRecentBySession(sessionId: String, limit: Int): List<MessageEntity>

    /** Recuperación del historial planificada:Leer solo mensajes con información de visualización de herramientas,Evite cargar sesiones largas enteras para planes de recuperación. */
    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId AND toolCallInfoJson IS NOT NULL " +
            "ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END ASC, createdAt ASC, id ASC",
    )
    suspend fun getToolCallMessagesBySession(sessionId: String): List<MessageEntity>

    /**
     * v1.53-A1: carga de ventana — Obtener la sesión especificada "secuencia válida" < beforeSeq mensajes de primer límite(orden descendente).
     *
     * Solía deslizarse hacia arriba para cargar más.:Una secuencia válida del mensaje más antiguo de la lista actual.(commitSeq>0 tomar commitSeq,
     * De lo contrario toma seq)como punto de anclaje,Tomemos como ejemplo la historia anterior. La clave de filtro y la clave de clasificación tienen el mismo origen(Igual que
     * CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END),Evite creado en
     * REPLACE(Editar/renacer/Interrumpir la recuperación)Duplicación de paginación causada por la actualización/Noticias filtradas.
     * El orden de devolución es descendente.(último primero),La persona que llama necesita reversed().
     */
    @Query(
        "SELECT * FROM messages WHERE sessionId = :sessionId " +
            "AND (CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END) < :beforeSeq " +
            "ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END DESC, createdAt DESC, id DESC " +
            "LIMIT :limit",
    )
    suspend fun getOlderBySession(sessionId: String, beforeSeq: Long, limit: Int): List<MessageEntity>

    /** v1.53-A1: Número total de mensajes de conversación(El juicio de paginación tieneMásSe utiliza historial). */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    /** v92: Max dentro de la sesión seq(A los nuevos mensajes se les asigna seq con). */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM messages WHERE sessionId = :sessionId")
    suspend fun getMaxSeq(sessionId: String): Long

    /**
     * v1.58/v2.4: Consultar la sesión hasta el punto de anclaje especificado.(Contiene)todas las noticias de(orden ascendente),Se utiliza para conversar bifurcando tenedores.
     *
     * El límite de la bifurcación debe utilizar la misma clave compuesta que la clasificación de lectura de la sesión. Simplemente truncar mediante createAt tomará el mismo milisegundo
     * También se copian los mensajes posteriores al punto de anclaje.,Tanto la escritura en streaming como la importación por lotes pueden producir la misma marca de tiempo.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE sessionId = :sessionId
          AND (
            (CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END) < :untilOrder
            OR (
              (CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END) = :untilOrder
              AND createdAt < :untilCreatedAt
            )
            OR (
              (CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END) = :untilOrder
              AND createdAt = :untilCreatedAt
              AND id <= :untilMessageId
            )
          )
        ORDER BY CASE WHEN commitSeq > 0 THEN commitSeq ELSE seq END ASC, createdAt ASC, id ASC
        """,
    )
    suspend fun getUpToBySession(sessionId: String, untilOrder: Long, untilCreatedAt: Long, untilMessageId: String): List<MessageEntity>

    /** insertar mensaje(Reemplazar en caso de conflicto,Admite mensajes del asistente de actualización de transmisión). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    /** Para resumen diario:Recibir mensajes de usuario dentro de un intervalo de tiempo específico(orden ascendente,edición limitada). */
    @Query(
        "SELECT * FROM messages WHERE role = 'USER' AND createdAt >= :fromCreatedAt AND createdAt < :toCreatedAt ORDER BY createdAt ASC, rowid ASC LIMIT :limit",
    )
    suspend fun getUserMessagesBetween(fromCreatedAt: Long, toCreatedAt: Long, limit: Int): List<MessageEntity>

    /** Compatible con consultas de límite inferior de otras personas que llaman. */
    @Query("SELECT * FROM messages WHERE role = 'USER' AND createdAt >= :fromCreatedAt ORDER BY createdAt ASC, rowid ASC LIMIT :limit")
    suspend fun getUserMessagesSince(fromCreatedAt: Long, limit: Int): List<MessageEntity>

    /** Inserción por lotes. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    /** Eliminar todos los mensajes en la conversación especificada(Cascada activada cuando se elimina la sesión,También se puede ajustar manualmente). */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** Leer mensajes antes de eliminar la sesión en cascada id,Sidecar de imagen para limpiar discos. */
    @Query("SELECT id FROM messages WHERE sessionId = :sessionId")
    suspend fun getMessageIdsBySessionId(sessionId: String): List<String>

    /** 7 Días de eliminación en cascada de sesiones caducadas antes de leer mensajes id,Para limpieza de sidecar de imagen. */
    @Query(
        "SELECT messages.id FROM messages " +
            "INNER JOIN sessions ON sessions.id = messages.sessionId " +
            "WHERE sessions.deletedAt IS NOT NULL AND sessions.deletedAt < :cutoff",
    )
    suspend fun getMessageIdsForExpiredSessions(cutoff: Long): List<String>

    /** Leer mensajes antes de eliminarlos por completo id,Para limpieza de sidecar de imagen. */
    @Query("SELECT id FROM messages")
    suspend fun getAllMessageIds(): List<String>

    /** Una vez que la copia de seguridad y la restauración sean exitosas, confirme la ruta a la imagen del disco a la que todavía hace referencia el mensaje. */
    @Query(
        """
        SELECT imageBase64Json FROM messages
        WHERE imageBase64Json LIKE '%file://%'
        """,
    )
    suspend fun getMessagesWithPersistedImagePaths(): List<String>

    /** v2.6.16: Referencias de audio de voz a las que todavía hacen referencia los mensajes (se utilizan como lista blanca al limpiar archivos de voz huérfanos). */
    @Query("SELECT voiceUri FROM messages WHERE voiceUri IS NOT NULL")
    suspend fun getReferencedVoiceUris(): List<String>

    /** v1.48: Eliminar un solo mensaje por id(Mantenga presionado el menú para eliminar mensajes enviados accidentalmente). También limpie el índice FTS. */
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)

    /** v1.0.80 (T-4): Verifique todos los mensajes secundarios del mensaje principal(Al eliminar el mensaje de un usuario, elimine las respuestas del asistente en cascada). */
    @Query("SELECT * FROM messages WHERE parentGroupId = :parentId OR parentMessageId = :parentId")
    suspend fun getChildrenByParentId(parentId: String): List<MessageEntity>

    /** Eliminar un solo mensaje por id(Editar/Se utiliza para truncamiento durante la regeneración.). */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND createdAt >= :fromCreatedAt")
    suspend fun deleteFromCreatedAt(sessionId: String, fromCreatedAt: Long)

    /** Obtenga el último mensaje de la sesión especificada. */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastBySession(sessionId: String): MessageEntity?

    /** Recibe un solo mensaje por id(Para editar mensajes del asistente). */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND id = :messageId LIMIT 1")
    suspend fun getById(sessionId: String, messageId: String): MessageEntity?

    /** v1.107: Recibe solo un mensaje por ID de mensaje(Verifique el ID de sesión al eliminar para mantener el recuento de redundancia). */
    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getByMessageId(messageId: String): MessageEntity?

    /** P3-16: Recibe el último mensaje del asistente global(Para widgets de conversación de escritorio,Entre sesiones). */
    @Query("SELECT * FROM messages WHERE role = 'ASSISTANT' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLastAssistantMessage(): MessageEntity?

    /**
     * v1.0.4: Recibe los últimos N mensajes del asistente a nivel mundial(Entre sesiones,Para páginas de informes/seguimiento del estado de ánimo).
     *
     * usado para [io.zer0.muse.data.emotion.MoodParser] Analizar desde el contenido del mensaje `<mood>` etiqueta,
     * Las tendencias de sentimiento agregadas se muestran en la página del informe.
     */
    @Query("SELECT * FROM messages WHERE role = 'ASSISTANT' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentAssistantMessages(limit: Int): List<MessageEntity>

    /**
     * LIKE Una búsqueda exhaustiva:Sólo si FTS no puede anular el query(Sin token/palabra indexable)O se utiliza cuando se produce una excepción de consulta FTS.
     *
     * Una única sesión SQL JOIN recupera el título de la sesión a la vez(Sin inspección adicional artículo por artículo N+1);Regresar [MessageSearchJoin]
     * (Contiene contenido texto original.),Construido en base al texto original por la capa Repositorio usando buildSnippet
     * [SearchResult.contentSnippet],Consistente con la semántica del fragmento de ruta FTS.
     *
     * Notas de rendimiento:LIKE Es un escaneo de tabla completo.,naturalmente más lento que FTS;pasa aqui (a) Sólo se activa en los límites antes mencionados,
     * (b) [limit] Un límite superior limita el costo a un rango acotado,Por lo tanto, se reserva como red de seguridad en lugar de forzar un cambio al FTS.
     *
     * @param pattern Cadena de patrón LIKE escapada(Como por ejemplo %keyword%),cooperar ESCAPE '\'
     * @param limit Número máximo de artículos devueltos(Predeterminado 50)
     */
    @Query(
        """
        SELECT
            m.id as messageId,
            m.sessionId as sessionId,
            m.content as content,
            m.role as role,
            m.createdAt as createdAt,
            s.title as sessionTitle
        FROM messages m
        JOIN sessions s ON m.sessionId = s.id
        WHERE m.content LIKE :pattern ESCAPE '\'
        ORDER BY m.createdAt DESC
        LIMIT :limit
    """,
    )
    suspend fun searchMessageContentLike(pattern: String, limit: Int = 50): List<MessageSearchJoin>

    /** LIKE fallback restricted to one session and user-visible conversation roles. */
    @Query(
        """
        SELECT
            m.id as messageId,
            m.sessionId as sessionId,
            m.content as content,
            m.role as role,
            m.createdAt as createdAt,
            s.title as sessionTitle
        FROM messages m
        JOIN sessions s ON m.sessionId = s.id
        WHERE m.sessionId = :sessionId
          AND m.role IN ('USER', 'ASSISTANT')
          AND m.content LIKE :pattern ESCAPE '\'
        ORDER BY m.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchMessageContentLikeInSession(sessionId: String, pattern: String, limit: Int = 50): List<MessageSearchJoin>

    /** flujo total de mensajes(Para panel de estadísticas). */
    @Query("SELECT COUNT(*) FROM messages")
    fun observeCount(): Flow<Int>

    // ── v1.0.30: Gestión de variantes ──

    /** Obtenga todos los mensajes para el mismo grupo de variantes (ordenados por índice de variantes). */
    @Query("SELECT * FROM messages WHERE variantGroupId = :groupId ORDER BY variantIndex ASC")
    suspend fun getVariants(groupId: String): List<MessageEntity>

    /** Actualiza el recuento de variantes de todos los mensajes dentro del grupo de variantes. */
    @Query("UPDATE messages SET variantCount = :count WHERE variantGroupId = :groupId")
    suspend fun updateVariantCount(groupId: String, count: Int)

    /** Borrar todos los mensajes(Se utiliza para realizar copias de seguridad y restaurar,Android 16 prohibido execSQL DML). */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** Obtener todos los mensajes(rebuild Para indexar,tomar solo id + content). */
    @Query("SELECT id, content FROM messages")
    suspend fun getAllForFtsRebuild(): List<FtsRebuildRow>

    /**
     * Toma el más cercano [limit] mensajes(id + content,Orden descendente por creado en).FTS Para comprobar la coherencia del contenido.
     *
     * C-23: ensureFtsIndexConsistent Cuando el número de filas es igual pero el contenido puede variar,Muestra de noticias recientes
     * Validación MATCH a nivel de contenido. La última novedad es el editor./Regenerar en las áreas más frecuentemente tocadas.,La tasa de aciertos de muestreo más alta;
     * El límite acotado evita la carga completa(full rebuild solo necesito getAllForFtsRebuild).
     */
    @Query("SELECT id, content FROM messages ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getFtsSample(limit: Int): List<FtsRebuildRow>

    /** Phase 10.3: messages Número de filas de la tabla(ensureFtsIndexConsistent Más útil). */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countMessages(): Int

    /** Número de mensajes para cada rol.(Para panel de estadísticas). */
    @Query("SELECT role, COUNT(*) as cnt FROM messages GROUP BY role")
    suspend fun countByRole(): List<RoleCount>

    /**
     * v0.46: Para mapas de calor estadísticos — Consulta la marca de tiempo creada en todos los mensajes de usuario.
     * Se utiliza para agrupar por fecha en la capa ViewModel,Cuente el número de mensajes diarios.
     * Solo revisa los mensajes de los usuarios(Comportamiento activo del usuario),No marque el asistente/sistema/herramienta.
     *
     * role las tiendas de campo MessageRole.USER.name(mayúsculas enum name,Como por ejemplo "USER"),
     * con [getLastAssistantMessage] de `role = 'ASSISTANT'` Sea coherente en su estilo de escritura.
     */
    @Query("SELECT createdAt FROM messages WHERE role = 'USER'")
    suspend fun getAllUserMessageTimestamps(): List<Long>

    /**
     * v1.104 U8: Para filtrar el rango de tiempo de la página de estadísticas. — Consulta la marca de tiempo creada en todos los mensajes del ASISTENTE.
     *
     * con [getAllUserMessageTimestamps] simetria,Para ViewModel después de filtrar por rango de tiempo en la memoria
     * calcular totalAiMessages(Evite depender de countByRole para la agregación completa).
     */
    @Query("SELECT createdAt FROM messages WHERE role = 'ASSISTANT'")
    suspend fun getAllAssistantMessageTimestamps(): List<Long>

    /**
     * v0.46: Para la página de estadísticas — Consulta todas las novedades(Sin límite de roles)La marca de tiempo creada en.
     * Se utiliza para contar el número total de mensajes/semana/mes/promedio diario/tendencias semanales y otros indicadores derivados,
     * Calculado después de la fecha groupBy en la capa ViewModel.
     */
    @Query("SELECT createdAt FROM messages")
    suspend fun getAllMessageTimestamps(): List<Long>

    /**
     * Phase 8.3: Establecer el estado de la recopilación de mensajes.
     */
    @Query("UPDATE messages SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    /**
     * Phase 8.3: Observe todos los mensajes favoritos en todas las sesiones(Para panel de colección,Orden descendente por creado en).
     *
     * El calibre es consistente con otras consultas en este documento.:JOIN sessions Excluya conversaciones eliminadas temporalmente y excluya mensajes eliminados temporalmente.
     * Las consultas desnudas anteriores dejarán los favoritos de las sesiones eliminadas en la lista(El usuario hace clic y no puede encontrar la sesión.),
     * y traer LIMIT 200 —— A partir del artículo 201, es silencioso y no puede ser descobrado. La recopilación la inicia el usuario
     * Una pequeña colección salvada,La lista está alojada en LazyColumn.,No se realiza ningún truncamiento silencioso.
     */
    @Query(
        "SELECT m.* FROM messages m JOIN sessions s ON m.sessionId = s.id " +
            "WHERE m.favorite = 1 AND m.deletedAt IS NULL AND s.deletedAt IS NULL " +
            "ORDER BY m.createdAt DESC",
    )
    fun observeAllFavorites(): Flow<List<MessageEntity>>

    /**
     * v1.104 U7: Establecer etiquetas de grupos favoritos(null = Mover a desagrupado).
     */
    @Query("UPDATE messages SET favoriteTag = :tag WHERE id = :id")
    suspend fun setMessageFavoriteTag(id: String, tag: String?)

    /** Función1: Establecer respuesta de emoticón de mensaje(null = Cancelar respuesta). */
    @Query("UPDATE messages SET reaction = :reaction WHERE id = :id")
    suspend fun setReaction(id: String, reaction: String?)

    /**
     * v1.104 U7: Observe todas las etiquetas de grupos de colecciones con nombre(Eliminar NULL/eliminar duplicados/orden ascendente).
     *
     * Para la representación de FilterChip en la parte superior de la pantalla Favoritos"Todos / tag1 / tag2 / …".
     * NULL(No agrupado)No en este resultado — Por UI solo con"No agrupado"chip Llega al fondo de esto.
     */
    @Query(
        "SELECT DISTINCT m.favoriteTag FROM messages m JOIN sessions s ON m.sessionId = s.id " +
            "WHERE m.favorite = 1 AND m.deletedAt IS NULL AND s.deletedAt IS NULL " +
            "AND m.favoriteTag IS NOT NULL ORDER BY m.favoriteTag ASC",
    )
    fun observeAllFavoriteTags(): Flow<List<String>>

    /**
     * §11-4: Obtenga los números de serie de la última versión de cada grupo de variantes.
     *
     * La colección es**OK**,La regeneración agregará una nueva fila de variantes, pero el favorito de la fila anterior no cambiará. Entonces en los favoritos
     * probablemente acostado“1ra edición”,La sesión muestra la versión 3. Aquí, el modo de solo lectura toma el valor máximo de cada grupo. variantIndex,
     * Marcar colecciones más pequeñas que ellas como versiones anteriores mediante la interfaz de usuario —— No cambie la ubicación de la colección ni muestre el contenido.,
     * por mostrar“Última versión”Hará que los no favoritos caigan en una línea de no favoritos(Se hizo clic pero no hubo respuesta.).
     */
    @Query(
        "SELECT variantGroupId, MAX(variantIndex) AS variantIndex FROM messages " +
            "WHERE variantGroupId IN (:groupIds) GROUP BY variantGroupId",
    )
    suspend fun getLatestVariantIndexes(groupIds: List<String>): List<VariantHead>

    // ── v0.47: Extensión de la página de estadísticas(migración cero,consulta pura) ──

    /** v1.0.30: ASSISTANT Longitud del contenido acumulativo del mensaje (≈ token base de estimación). */
    @Query("SELECT COALESCE(SUM(LENGTH(content)), 0) FROM messages WHERE role = 'ASSISTANT'")
    suspend fun sumAssistantContentLength(): Long

    /**
     * Uso total medido para una sola sesión (get_token_usage columna "Esta sesión").
     *
     * La tabla de flujo de uso use_records no tiene una dimensión de sesión y los números a nivel de sesión solo se pueden medir a partir de mensajes en el proveedor.
     * Campos de uso de suma: cuente solo las filas ASISTENTES (usage Cuelgue solo los mensajes del asistente), omita líneas eliminadas y líneas de ceros,
     * De acuerdo con el enfoque de "no recordar todos los ceros" de use_records.
     */
    @Query(
        """
        SELECT COUNT(*) AS requests,
               COALESCE(SUM(promptTokens), 0) AS promptTokens,
               COALESCE(SUM(completionTokens), 0) AS completionTokens
        FROM messages
        WHERE sessionId = :sessionId
          AND role = 'ASSISTANT'
          AND deletedAt IS NULL
          AND (COALESCE(promptTokens, 0) + COALESCE(completionTokens, 0)) > 0
        """,
    )
    suspend fun usageTotalsBySession(sessionId: String): SessionUsageAggregate

    /**
     * v0.47: Cuente el número de mensajes del ASISTENTE agrupados por modelId(Para gráficos de anillos).
     *
     * Cuente solo los mensajes con el rol ASISTENTE y el ID de modelo no vacío(user/system Ningún mensaje modelId).
     * Se utiliza para verificar el repositorio de configuración en la capa ViewModel para obtener el nombre para mostrar del modelo.
     */
    @Query("SELECT modelId, COUNT(*) as cnt FROM messages WHERE role = 'ASSISTANT' AND modelId IS NOT NULL GROUP BY modelId")
    suspend fun countByModel(): List<ModelCount>

    /**
     * v0.47: Estadísticas de mensajes agrupados por ID de asistente(Para gráficos de barras horizontales).
     *
     * JOIN sessions tomar assistantId,Agregue el número de todos los mensajes por asistente.
     * Se utiliza para verificar el AssistantRepository en la capa ViewModel para obtener el nombre para mostrar del asistente.
     */
    @Query(
        "SELECT s.assistantId as assistantId, COUNT(*) as cnt FROM messages m JOIN sessions s ON m.sessionId = s.id GROUP BY s.assistantId",
    )
    suspend fun countByAssistant(): List<AssistantCount>

    /**
     * v0.47: Número agregado de mensajes por hora(24 Para gráfico de barras).
     *
     * createdAt marca de tiempo en milisegundos,/1000 Utilice strftime para obtener la hora en la zona horaria local después de convertir segundos(00-23).
     * Las horas faltantes las completa ViewModel 0,Haz una lista de 24 elementos.
     */
    @Query(
        "SELECT strftime('%H', createdAt/1000, 'unixepoch', 'localtime') as hour, " +
            "COUNT(*) as cnt FROM messages GROUP BY hour ORDER BY hour",
    )
    suspend fun countByHour(): List<HourCount>

    /**
     * v0.47: Obtenga las N conversaciones principales con la mayor cantidad de mensajes(Top 10 Para listas).
     *
     * Agregar el número de mensajes por ID de sesión,orden descendente [n] artículo.
     * Se utiliza para verificar SessionDao en la capa ViewModel para obtener el título de la sesión.
     */
    @Query("SELECT sessionId, COUNT(*) as cnt FROM messages GROUP BY sessionId ORDER BY cnt DESC LIMIT :n")
    suspend fun topSessionsByMessageCount(n: Int): List<SessionMessageCount>

    /**
     * widget de escritorio:Obtenga el último mensaje del asistente del "asistente seleccionado por el usuario en el widget".
     *
     * Anteriormente, el widget se ajustaba directamente [getLastAssistantMessage] Obtenga la última versión global,La selección de un asistente no tiene ningún impacto en el contenido mostrado.
     *
     * Se coloca al final de la interfaz para no mover los números de línea de los métodos existentes. —— La línea base de ktlint de este módulo coincide por número de línea,
     * Insertar en el medio hará que todas las entradas posteriores se desvíen hacia"Nueva violación".
     */
    @Query(
        "SELECT m.* FROM messages m JOIN sessions s ON m.sessionId = s.id " +
            "WHERE m.role = 'ASSISTANT' AND m.deletedAt IS NULL " +
            "AND s.assistantId = :assistantId AND s.deletedAt IS NULL " +
            "ORDER BY m.createdAt DESC LIMIT 1",
    )
    suspend fun getLastAssistantMessageForAssistant(assistantId: String): MessageEntity?
}

/** Phase 10.3: FTS rebuild Utilice proyección ligera. */
data class FtsRebuildRow(
    val id: String,
    val content: String,
)

// ── v0.47: Proyección ampliada de la página de estadísticas(migración cero,Para consulta pura) ──

/** v0.47: Recuento de mensajes de ASISTENTE agrupados por modelId(Para gráficos de anillos). */
data class ModelCount(
    val modelId: String,
    val cnt: Int,
)

/** v0.47: Recuento de mensajes agrupados por ID de asistente(Para gráficos de barras horizontales). */
data class AssistantCount(
    val assistantId: String,
    val cnt: Int,
)

/** v0.47: Recuento de mensajes agrupados por hora(24 Para gráfico de barras,hour para "00"-"23"). */
data class HourCount(
    val hour: String,
    val cnt: Int,
)

/** v0.47: Proyección del recuento de mensajes para una sola sesión(Top 10 Para listas). */
data class SessionMessageCount(
    val sessionId: String,
    val cnt: Int,
)

/** §11-4: La proyección del número de serie de la última versión del grupo de variantes.(Pestaña de favoritos“versión antigua”usar). */
data class VariantHead(
    val variantGroupId: String,
    val variantIndex: Int,
)
