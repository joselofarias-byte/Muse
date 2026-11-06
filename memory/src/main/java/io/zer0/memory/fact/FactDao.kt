package io.zer0.memory.fact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Fact objeto de acceso a datos(Consultas precompiladas).
 *
 * v6: Se agregó el índice de texto completo FTS4 de Facts_fts.
 *  - La búsqueda de texto completo tiene prioridad FTS4 MATCH(ngram preprocesamiento)
 *  - sola palabra/Respaldo en caso de excepción LIKE '%query%'
 *  - La búsqueda de etiquetas utiliza json_each para una coincidencia exacta(Evite la falta de coincidencia de subcadenas LIKE)
 */
@Dao
interface FactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FactEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<FactEntity>): List<Long>

    /**
     * v4: Obtenga todos los datos en orden descendente de importancia + tiempo descendente.
     * clave(importance=2)e importante(importance=1)los hechos son lo primero,Conveniente para la visualización de prioridad de la interfaz de usuario.
     *
     * v8: Se agregó filtrado de alcance opcional.,null Representa todo el alcance.,No nulo Devuelve sólo hechos para el ámbito especificado.
     */
    @Query("SELECT * FROM facts WHERE (:scope IS NULL OR scope = :scope) ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    suspend fun getAll(scope: String? = null): List<FactEntity>

    @Query("SELECT * FROM facts WHERE id = :id")
    suspend fun getById(id: Long): FactEntity?

    @Query("SELECT * FROM facts WHERE session_id = :sessionId ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    suspend fun getBySession(sessionId: String): List<FactEntity>

    /**
     * Cuando se elimina una sesión, se borran todos los datos generados por ella.(ha aparecido/filas fusionadas).
     *
     * La eliminación línea por línea solo es económica si hay pocos datos sobre una sesión;Limpieza y eliminación a nivel de sesión de una sola vez,Luego borre los datos derivados por número de fila.
     *
     * @return número real de filas eliminadas
     */
    @Query("DELETE FROM facts WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String): Int

    /**
     * Cuando se elimina el asistente, se borran todos los datos sobre el alcance en la biblioteca.
     *
     * La línea del subasistente suele estar en `facts_<id>.db` adentro,Sin embargo, la biblioteca principal aún puede tener líneas históricas con el mismo alcance.,
     * Es necesario borrar ambas bibliotecas.,De lo contrario, la página de memoria aún puede leer el hecho de que el asistente ha sido eliminado.
     *
     * @return número real de filas eliminadas
     */
    @Query("DELETE FROM facts WHERE scope = :scope")
    suspend fun deleteAllByScope(scope: String): Int

    @Query("SELECT COUNT(*) FROM facts")
    suspend fun count(): Int

    @Query("DELETE FROM facts WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /**
     * P2: Actualizar el contenido del hecho especificado.(Se utiliza para recordar la edición de la interfaz de usuario de la página Capa de hechos).
     *
     * v8: Parámetro de alcance opcional agregado:
     *  - scope cuando es nulo,Actualizar solo content,Mantener original scope(COALESCE Semántica)
     *  - scope Cuando no es nulo,Actualizar contenido y scope(Se utiliza para que la interfaz de usuario cambie los alcances de los hechos.)
     */
    @Query("UPDATE facts SET fact = :content, scope = COALESCE(:scope, scope) WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, scope: String? = null): Int

    /**
     * v4: Actualiza la importancia de un hecho específico.(Se utiliza para ajustes manuales de la interfaz de usuario de la página de memoria.).
     * importance: 0=ordinario,1=importante,2=clave
     */
    @Query("UPDATE facts SET importance = :importance WHERE id = :id")
    suspend fun updateImportance(id: Long, importance: Int): Int

    /** B4-05: ajustes/Cancelar pin manual(pinnedAt nulo significa cancelar). */
    @Query("UPDATE facts SET pinned_at = :pinnedAt WHERE id = :id")
    suspend fun updatePinnedAt(id: Long, pinnedAt: String?): Int

    /** v12: Rellenar claves de entidad(Tarea de reflexión sobre datos históricos nulos). */
    @Query("UPDATE facts SET entity_key = :entityKey WHERE id = :id")
    suspend fun updateEntityKey(id: Long, entityKey: String): Int

    /**
     * v10 P2-3: Actualizar la clasificación y etiqueta del hecho especificado.(para la gestión de memoria de IA updatedEntities/autoCategorize).
     * category O mantener el valor original cuando las etiquetas sean nulas(COALESCE Semántica).
     */
    @Query("UPDATE facts SET category = COALESCE(:category, category), tags = COALESCE(:tags, tags) WHERE id = :id")
    suspend fun updateCategoryAndTags(id: Long, category: String? = null, tags: String? = null): Int

    /**
     * Reescritura de hits de recuperación: actualice la última hora de hit.
     *
     * llenar un vacío a largo plazo —— hitBonus La bonificación por deterioro depende de last_hit_at, pero la ruta de recuperación nunca se vuelve a escribir.
     * entonces“éxitos recientes”En realidad, es igual al tiempo de almacenamiento y la bonificación nunca ha entrado en vigor.
     */
    @Query("UPDATE facts SET last_hit_at = :hitAt WHERE id IN (:ids)")
    suspend fun updateLastHitAt(ids: List<Long>, hitAt: String): Int

    /**
     * El recuento de visitas aumenta.
     *
     * Declaración independiente, no fusionada en la actualización del campo completo —— Esa ACTUALIZACIÓN solo cambia las columnas enumeradas.
     * Pero abarrotar la cuenta haría que cada uno“editar hechos”Todos reinician el conteo.
     */
    @Query("UPDATE facts SET hit_count = hit_count + 1 WHERE id IN (:ids)")
    suspend fun incrementHitCount(ids: List<Long>): Int

    /**
     * v17: golpear/bono de acceso —— Agregue una magnitud a la puntuación de persistencia y reinicie el reloj de decadencia,También borre la marca de salida.
     *
     * límite superior [cap] Evite que los golpes repetidos acumulen puntuaciones hasta niveles altos y sin sentido;Sólo procesar entrantes id,
     * Cuando aparece la línea no jugada, solo agrega una bonificación de acierto.(Misma semántica que restablecer el reloj de decaimiento).
     */
    @Query("UPDATE facts SET score = MIN(score + :bonus, :cap), score_updated_at = :updatedAt, suppressed_at = NULL WHERE id IN (:ids)")
    suspend fun boostScores(ids: List<Long>, bonus: Float, cap: Float, updatedAt: String): Int

    /**
     * v18: viejos recuerdos que han sido reemplazados id —— La fila a la que apunta reemplaza_id de otras filas.
     *
     * recordar/Es necesario juzgar la ruta de inyección.“¿Ha cedido este recuerdo?”,Generalmente solo hay unas pocas líneas de este tipo.,
     * Entonces solo toma las columnas requeridas,No es necesario asignar toda la tabla a una colección.
     * [scope] / [spaceId] Pasar nulo significa que no hay límite(con [getAll] La semántica de alcance completo de).
     */
    @Query(
        """
        SELECT supersedes_id FROM facts
        WHERE supersedes_id IS NOT NULL
          AND (:scope IS NULL OR scope = :scope)
          AND (:spaceId IS NULL OR space_id = :spaceId)
    """,
    )
    suspend fun getSupersededIds(scope: String?, spaceId: String?): List<Long>

    /**
     * v19: Filas que se han fusionado con otros hechos id —— Estas líneas no participan en la recuperación automática y no ingresan a la proyección de compilación.
     *
     * con [getSupersededIds] la diferencia: lo que se toma ahí es“viejo señaló id”(Requiere operaciones de conjunto externo),
     * Aquí tomamos la fila con merged_into_id —— Recuperar el filtrado directamente“¿Está este banco en el set?”.
     * la nueva línea apuntaba a(Producto combinado)No en esta colección,Participe en el retiro con normalidad.
     * Obtener solo la columna de identificación,No mapear filas enteras;[scope] / [spaceId] Pasar nulo significa ilimitado.
     */
    @Query(
        """
        SELECT id FROM facts
        WHERE merged_into_id IS NOT NULL
          AND (:scope IS NULL OR scope = :scope)
          AND (:spaceId IS NULL OR space_id = :spaceId)
    """,
    )
    suspend fun getMergedIds(scope: String?, spaceId: String?): List<Long>

    /** v19: Qué filas originales se agregan mediante un hecho de fusión(Ordenar por id ascendente,Para visualización de interfaz de usuario“Fusionado por N elementos”). */
    @Query("SELECT * FROM facts WHERE merged_into_id = :factId ORDER BY id ASC")
    suspend fun getMergedChildren(factId: Long): List<FactEntity>

    /** v19: Etiquetar un conjunto de hechos fuente como“Fusionado en [mergedIntoId]”. */
    @Query("UPDATE facts SET merged_into_id = :mergedIntoId WHERE id IN (:ids)")
    suspend fun updateMergedInto(ids: List<Long>, mergedIntoId: Long): Int

    /** v19: Deshacer fusión —— señal clara [mergedIntoId] marca. */
    @Query("UPDATE facts SET merged_into_id = NULL WHERE merged_into_id = :mergedIntoId")
    suspend fun clearMergedInto(mergedIntoId: Long): Int

    /** v5: Actualización de campo completo(Se utiliza para reemplazar contenido después de fusionar y deduplicar). */
    @Query(
        "UPDATE facts SET fact = :fact, tags = :tags, time = :time, session_id = :sessionId, created_at = :createdAt, importance = :importance, category = :category, confidence = :confidence, source = :source, expires_at = :expiresAt, last_confirmed_at = :lastConfirmedAt, last_hit_at = :lastHitAt, entity_key = :entityKey WHERE id = :id",
    )
    suspend fun updateEntity(
        id: Long,
        fact: String,
        tags: String,
        time: String?,
        sessionId: String?,
        createdAt: String,
        importance: Int,
        category: String,
        confidence: Float,
        source: String,
        expiresAt: String?,
        lastConfirmedAt: String?,
        lastHitAt: String?,
        entityKey: String? = null,
    )

    /**
     * v5: Buscar antes del texto dado40hechos de coincidencia de prefijos de palabras(Se utiliza para eliminar duplicados.).
     *
     * v8: Se agregó filtrado de alcance opcional.,Busque únicamente hechos similares dentro del mismo alcance al realizar la deduplicación.,
     * evitar"main"Los hechos del alcance se fusionaron incorrectamente con hechos del alcance del subayudante.
     */
    @Query(
        "SELECT * FROM facts WHERE fact LIKE :prefix || '%' AND (:scope IS NULL OR scope = :scope) ORDER BY importance DESC, created_at DESC LIMIT 5",
    )
    suspend fun findSimilar(prefix: String, scope: String? = null): List<FactEntity>

    /**
     * v9: Encuentre hechos similares por alcance + filtrado doble space_id(Se utiliza para eliminar duplicados.).
     * con [findSimilar] la diferencia:Filtrar estrictamente por alcance + space_id,No acepta nulo.
     */
    @Query(
        """
        SELECT * FROM facts
        WHERE fact LIKE :prefix || '%'
          AND scope = :scope
          AND space_id = :spaceId
        ORDER BY importance DESC, created_at DESC
        LIMIT 5
        """,
    )
    suspend fun findSimilarBySpace(prefix: String, scope: String, spaceId: String): List<FactEntity>

    /**
     * v12: prensa entity_key + scope + space_id busqueda exacta(Comprobación de duplicación a nivel de entidad al escribir).
     * Diferentes formas de escribir la misma entidad comparten lo mismo entity_key,Las visitas se fusionan en lugar de agregarse,
     * resolver directamente"Zhang San/Sr. Zhang/Maestro Zhang San"Múltiples problemas de memoria repetitiva.
     */
    @Query(
        """
        SELECT * FROM facts
        WHERE entity_key = :entityKey
          AND scope = :scope
          AND space_id = :spaceId
        ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, created_at DESC
        LIMIT 10
        """,
    )
    suspend fun findByEntityKey(entityKey: String, scope: String, spaceId: String): List<FactEntity>

    @Query("DELETE FROM facts")
    suspend fun deleteAll(): Int

    /**
     * Eliminar creado antes de [cutoffIso] todos los hechos.
     * por [FactStore.applyDecay] Llamado en canalización diaria,Implementar el olvido impulsado por la configuración.
     *
     * Usar create_at en lugar de time:time es la hora del evento descrita por el hecho mismo.(Disponible y disponible mucho antes del almacenamiento.),
     * created_at Es hora de dejar el inventario.,Más estable como referencia de atenuación.
     *
     * v8: Se agregó filtrado de alcance opcional.,null Representa todo el alcance.,No nulo Solo se eliminan los hechos del ámbito especificado.
     *
     * @return número real de filas eliminadas
     */
    /**
     * F-6: Hechos fijados manualmente(pinned_at No vacío)Nunca participes en la eliminación automática de decaimiento. —
     * La semántica de permanecer en la cima es"Recuerde siempre los requisitos del usuario",No se ve afectado por el corte de tiempo o la limpieza de vencimiento.
     * Después de que el usuario cancela el pin, este continúa decayendo de acuerdo con las reglas normales.
     */
    @Query(
        "DELETE FROM facts WHERE created_at < :cutoffIso AND pinned_at IS NULL AND (:scope IS NULL OR scope = :scope) AND (:spaceId IS NULL OR space_id = :spaceId)",
    )
    suspend fun deleteOlderThan(cutoffIso: String, scope: String? = null, spaceId: String? = null): Int

    /**
     * v4: Eliminar creado antes de [cutoffIso] y importance < [minImportance] hecho.
     * hechos clave(importance=2)Pase minImportance=3 nunca borrar,darse cuenta"nunca decaer".
     *
     * v8: Se agregó filtrado de alcance opcional.,null Representa todo el alcance.
     *
     * @return número real de filas eliminadas
     */
    // F-6: pinned_at IS NULL — Los hechos fijados no se ven afectados por la decadencia automática
    @Query(
        "DELETE FROM facts WHERE created_at < :cutoffIso AND importance < :minImportance AND pinned_at IS NULL AND (:scope IS NULL OR scope = :scope) AND (:spaceId IS NULL OR space_id = :spaceId)",
    )
    suspend fun deleteOlderThanExceptImportant(cutoffIso: String, minImportance: Int, scope: String? = null, spaceId: String? = null): Int

    /**
     * v7: Eliminar por caída del tiempo de acierto.
     *  - nunca golpear(last_hit_at IS NULL)El hecho es juzgado por creado_at
     *  - El hecho de que haya sido golpeado se juzga por last_hit_at
     *  - importance < minImportance los hechos serán eliminados
     *
     * cooperar [MemoryConfig.hitBonus] darse cuenta"Los recuerdos referenciados se olvidan más lentamente.".
     *
     * v8: Se agregó filtrado de alcance opcional.,null Representa todo el alcance.
     */
    @Query(
        """
        DELETE FROM facts
        WHERE importance < :minImportance
          AND pinned_at IS NULL
          AND (:scope IS NULL OR scope = :scope)
          AND (:spaceId IS NULL OR space_id = :spaceId)
          AND (
            (last_hit_at IS NULL AND created_at < :neverHitCutoffIso)
            OR
            (last_hit_at IS NOT NULL AND last_hit_at < :hitCutoffIso)
          )
    """,
    )
    suspend fun deleteOlderThanWithHit(
        neverHitCutoffIso: String,
        hitCutoffIso: String,
        minImportance: Int,
        scope: String? = null,
        spaceId: String? = null,
    ): Int

    /**
     * B-18: Eliminar hechos caducados(expires_at No vacío y anterior a la hora actual).
     * hecho oportuno(Como por ejemplo"Habrá una reunión mañana por la mañana.")Después de su vencimiento, ya no permanecerá ni se inyectará.
     *
     * @return número real de filas eliminadas
     */
    // F-6: pinned_at IS NULL — Los datos urgentes fijados en la parte superior no se eliminarán automáticamente debido a su vencimiento.(Volver a las reglas normales después de cancelar fijado)
    @Query(
        "DELETE FROM facts WHERE expires_at IS NOT NULL AND expires_at != '' AND expires_at < :nowISO AND pinned_at IS NULL AND (:scope IS NULL OR scope = :scope) AND (:spaceId IS NULL OR space_id = :spaceId)",
    )
    suspend fun deleteExpired(nowISO: String, scope: String? = null, spaceId: String? = null): Int

    /**
     * v17: Candidato a salida de decadencia —— Obtenga sólo las columnas necesarias para la liquidación.
     *
     * - hechos clave(importance=2)No participa en la decadencia con hechos fijados.,Por eso no aparece en los candidatos.;
     * - `refAt`: La hora de inicio de esta ronda de decadencia. Golpear de nuevo desde el último acuerdo(last_hit_at Más reciente que score_updated_at)
     *   hora de tomar last_hit_at(presione restablecer el reloj),De lo contrario, tome la última hora de liquidación.,Regresar al primer asentamiento. created_at;
     * - `hitSinceDecay`: 1 expresar"Citado desde el último acuerdo",Necesito recuperar esta ronda [boostScores] Mismo bono de golpe.
     */
    @Query(
        """
        SELECT id, score,
               CASE
                 WHEN last_hit_at IS NOT NULL AND (score_updated_at IS NULL OR last_hit_at > score_updated_at)
                   THEN last_hit_at
                 ELSE COALESCE(score_updated_at, created_at)
               END AS refAt,
               CASE
                 WHEN last_hit_at IS NOT NULL AND (score_updated_at IS NULL OR last_hit_at > score_updated_at)
                   THEN 1 ELSE 0
               END AS hitSinceDecay
        FROM facts
        WHERE importance < 2 AND pinned_at IS NULL
          AND (:scope IS NULL OR scope = :scope)
          AND (:spaceId IS NULL OR space_id = :spaceId)
    """,
    )
    suspend fun getDecayCandidates(scope: String?, spaceId: String?): List<DecayCandidate>

    /** v17: Puntuación de deterioro de esta ronda de abandono(Marca de salida fija). */
    @Query("UPDATE facts SET score = :score, score_updated_at = :updatedAt WHERE id = :id")
    suspend fun updateDecayedScore(id: Long, score: Float, updatedAt: String): Int

    /**
     * v17: salida de umbral —— puntuación inferior a [threshold] de ordinario/El hecho no fijado se escribe en el tiempo de aparición.
     *
     * Sólo afecta“Si participar en el retiro”,No elimine filas. Devuelve el número de nuevas entradas en esta ronda.
     */
    @Query(
        """
        UPDATE facts SET suppressed_at = :at
        WHERE suppressed_at IS NULL AND importance < 2 AND pinned_at IS NULL AND score < :threshold
          AND (:scope IS NULL OR scope = :scope)
          AND (:spaceId IS NULL OR space_id = :spaceId)
    """,
    )
    suspend fun suppressBelowThreshold(at: String, threshold: Float, scope: String?, spaceId: String?): Int

    /**
     * v17: umbral de retorno —— Puntuación devuelta a [threshold] arriba(o cambiar a clave/fijar hacia arriba)Los hechos vuelven a entrar en el recuerdo.
     *
     * Configurar el umbral de descenso/Después de apagar la velocidad del olvido.,Los recuerdos que se han jugado se pueden recuperar en lotes en la siguiente ronda de liquidación.
     */
    @Query(
        """
        UPDATE facts SET suppressed_at = NULL
        WHERE suppressed_at IS NOT NULL
          AND (score >= :threshold OR importance >= 2 OR pinned_at IS NOT NULL)
          AND (:scope IS NULL OR scope = :scope)
          AND (:spaceId IS NULL OR space_id = :spaceId)
    """,
    )
    suspend fun releaseAboveThreshold(threshold: Float, scope: String?, spaceId: String?): Int

    /**
     * Búsqueda de texto completo(LIKE,compatibles con todos ROM).
     * Hacer coincidencia de subcadenas en el campo de hechos,v4: Ordenar por importancia descendente + tiempo descendente.
     */
    @Query(
        "SELECT * FROM facts WHERE fact LIKE '%' || :query || '%' AND (scope = :scope OR :scope IS NULL) ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC LIMIT :limit",
    )
    suspend fun likeSearch(query: String, limit: Int, scope: String? = null): List<FactEntity>

    /**
     * v9: LIKE búsqueda de texto completo con doble filtrado por alcance + space_id(Se utiliza para quitar fondos de bolsas pesadas.).
     */
    @Query(
        """
        SELECT * FROM facts
        WHERE fact LIKE '%' || :query || '%'
          AND scope = :scope
          AND space_id = :spaceId
        ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC
        LIMIT :limit
        """,
    )
    suspend fun likeSearchBySpace(query: String, limit: Int, scope: String, spaceId: String): List<FactEntity>

    /**
     * Revisión de corrección (B-19): ME GUSTA búsqueda de texto completo filtrada solo por space_id(scope Sin límite,Todos los alcances).
     * centro de memoria scope=null(Todos)Semánticamente, el aislamiento aún debe basarse en el space_id actual,Mismo calibre que getBySpace.
     */
    @Query(
        """
        SELECT * FROM facts
        WHERE fact LIKE '%' || :query || '%'
          AND space_id = :spaceId
        ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC
        LIMIT :limit
        """,
    )
    suspend fun likeSearchBySpaceId(query: String, limit: Int, spaceId: String): List<FactEntity>

    /**
     * v6: FTS4 Búsqueda de texto completo.
     * Usando expresiones MATCH ngramificadas(por [FactFtsManager.toMatchQuery] generar).
     */
    @Query(
        """
        SELECT f.* FROM facts_fts
        JOIN facts f ON facts_fts.fact_id = f.id
        WHERE content_ngram MATCH :matchQuery
        ORDER BY (f.pinned_at IS NOT NULL) DESC, f.importance DESC, f.time DESC
        LIMIT :limit
    """,
    )
    suspend fun searchFts(matchQuery: String, limit: Int): List<FactEntity>

    /** Busque FTS por alcance + espacio para evitar perderse el retiro tomando primero el top-K global y luego filtrando. */
    @Query(
        """
        SELECT f.* FROM facts_fts
        JOIN facts f ON facts_fts.fact_id = f.id
        WHERE content_ngram MATCH :matchQuery
          AND f.scope = :scope
          AND f.space_id = :spaceId
        ORDER BY (f.pinned_at IS NOT NULL) DESC, f.importance DESC, f.time DESC
        LIMIT :limit
    """,
    )
    suspend fun searchFtsBySpace(matchQuery: String, limit: Int, scope: String, spaceId: String): List<FactEntity>

    /** Revisión de corrección (B-19): Búsqueda FTS filtrada solo por space_id(scope Sin límite,Todos los alcances). */
    @Query(
        """
        SELECT f.* FROM facts_fts
        JOIN facts f ON facts_fts.fact_id = f.id
        WHERE content_ngram MATCH :matchQuery
          AND f.space_id = :spaceId
        ORDER BY (f.pinned_at IS NOT NULL) DESC, f.importance DESC, f.time DESC
        LIMIT :limit
    """,
    )
    suspend fun searchFtsBySpaceId(matchQuery: String, limit: Int, spaceId: String): List<FactEntity>

    /**
     * Búsqueda de etiqueta + rango de fechas.SQL por [FactStore.searchByTags] empalme dinámico
     * (Porque la combinación de número de etiqueta y rango de fechas es dinámica,Room La @Query estática es difícil de expresar).
     */
    @RawQuery
    suspend fun tagSearch(query: SupportSQLiteQuery): List<FactTagSearchRow>

    // ── v8: por alcance(scope)Consulta/Atenuación ──

    /**
     * v8: Observar lista de hechos por alcance.(Flow forma),Se utiliza para la actualización de la interfaz de usuario en tiempo real.
     * ordenar con [getAll] consistente:importance DESC + time DESC.
     *
     * v2.6.2(auditoría de memoria): Agregar filtro caducado —— La implementación anterior no se filtró, lo que resultó en“La consulta de lista no se muestra,
     * Actualizaciones en tiempo real pero visualización”La semántica de es inconsistente (suspend Todas las consultas han sido filtradas y caducadas).
     */
    @Query(
        "SELECT * FROM facts WHERE scope = :scope " +
            "AND (expires_at IS NULL OR expires_at = '' OR expires_at >= :nowIso) " +
            "ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC",
    )
    fun observeByScope(scope: String, nowIso: String): Flow<List<FactEntity>>

    /**
     * v8: Consulta la lista de hechos de forma sincrónica por alcance.
     * Se utiliza para inyección de avisos del sistema, recuperación de memoria del subasistente y otros escenarios.
     */
    @Query("SELECT * FROM facts WHERE scope = :scope ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    suspend fun getByScope(scope: String): List<FactEntity>

    /**
     * v8: Eliminar por caída de alcance — Solo elimine el alcance especificado que sea anterior a [cutoffIso] y importance < [minImportance] hecho.
     * Se utiliza para la decadencia independiente de cada alcance auxiliar en la canalización diaria.,Evite los hechos de poca importancia de un asistente
     * Afecta el ritmo de decaimiento de otros asistentes.
     *
     * @return número real de filas eliminadas
     */
    // F-6: pinned_at IS NULL — Los hechos fijados no se ven afectados por la limpieza del deterioro del alcance
    @Query("DELETE FROM facts WHERE scope = :scope AND created_at < :cutoffIso AND importance < :minImportance AND pinned_at IS NULL")
    suspend fun deleteByScopeExceptImportant(scope: String, cutoffIso: String, minImportance: Int): Int

    // ── v6: FTS4 Sincronización de índice ──

    /** Insertar/Actualizar el índice Facts_fts(fact_id No indexado,content_ngram Índice de texto completo). */
    @Query("INSERT OR REPLACE INTO facts_fts(fact_id, content_ngram) VALUES(:factId, :contentNgram)")
    suspend fun insertFts(factId: Long, contentNgram: String)

    /** Elimine el índice FTS para el hecho especificado. */
    @Query("DELETE FROM facts_fts WHERE fact_id = :factId")
    suspend fun deleteFts(factId: Long)

    /** Borrar índice FTS(rebuild usar). */
    @Query("DELETE FROM facts_fts")
    suspend fun clearFts()

    /** obtener todos los hechos(rebuild Para indexar,tomar solo id + fact). */
    @Query("SELECT id, fact FROM facts")
    suspend fun getAllForFtsRebuild(): List<FtsRebuildRow>

    /** facts Número de filas de la tabla(ensureFtsIndexConsistent Más útil). */
    @Query("SELECT COUNT(*) FROM facts")
    suspend fun countFacts(): Int

    /** facts_fts Número de filas de la tabla(ensureFtsIndexConsistent Más útil). */
    @Query("SELECT COUNT(*) FROM facts_fts")
    suspend fun countFts(): Int

    // ── v9: prensa Space(space_id)Consulta/observar/Atenuación ───────────────────────────

    /**
     * v9: Observar la lista de hechos por space_id(Flow forma),Se utiliza para la actualización de la interfaz de usuario en tiempo real.
     * ordenar con [getAll] consistente:importance DESC + time DESC.
     */
    @Query(
        "SELECT * FROM facts WHERE space_id = :spaceId " +
            "AND (expires_at IS NULL OR expires_at = '' OR expires_at >= :nowIso) " +
            "ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC",
    )
    fun observeBySpace(spaceId: String, nowIso: String): Flow<List<FactEntity>>

    /**
     * v9: Consulta la lista de hechos de forma sincrónica mediante space_id.
     * Se utiliza para la inyección de avisos del sistema, visualización de la interfaz de usuario de la página de memoria y otros escenarios.
     */
    @Query("SELECT * FROM facts WHERE space_id = :spaceId ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC")
    suspend fun getBySpace(spaceId: String): List<FactEntity>

    /**
     * v9: Lista de hechos de consulta de filtro doble por alcance + space_id.
     * scope Cuarentena por agente,space_id Aislar por escena,Los dos son ortogonales.
     */
    @Query(
        "SELECT * FROM facts WHERE scope = :scope AND space_id = :spaceId ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC",
    )
    suspend fun getByScopeAndSpace(scope: String, spaceId: String): List<FactEntity>

    /**
     * v9: Lista de hechos de observación de doble filtro por alcance + space_id(Flow forma).
     */
    @Query(
        "SELECT * FROM facts WHERE scope = :scope AND space_id = :spaceId " +
            "AND (expires_at IS NULL OR expires_at = '' OR expires_at >= :nowIso) " +
            "ORDER BY (pinned_at IS NOT NULL) DESC, importance DESC, time DESC",
    )
    fun observeByScopeAndSpace(scope: String, spaceId: String, nowIso: String): Flow<List<FactEntity>>

    /**
     * v9: Eliminación de descomposición por space_id — Elimine solo el espacio especificado que sea anterior a [cutoffIso] y importance < [minImportance] hecho.
     *
     * @return número real de filas eliminadas
     */
    // F-6: pinned_at IS NULL — Los hechos fijados no se ven afectados por la limpieza de la descomposición espacial
    @Query("DELETE FROM facts WHERE space_id = :spaceId AND created_at < :cutoffIso AND importance < :minImportance AND pinned_at IS NULL")
    suspend fun deleteBySpaceExceptImportant(spaceId: String, cutoffIso: String, minImportance: Int): Int

    /**
     * v9: Cuente el número de hechos por space_id.
     */
    @Query("SELECT COUNT(*) FROM facts WHERE space_id = :spaceId")
    suspend fun countBySpace(spaceId: String): Int
}

/** Etiquetar resultados de búsqueda(traer matchCount).v4: Contiene un campo de importancia. */
data class FactTagSearchRow(
    val id: Long,
    val fact: String,
    val tags: String,
    val time: String?,
    @androidx.room.ColumnInfo(name = "session_id")
    val sessionId: String?,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: String,
    val importance: Int = 0,
    val category: String = "general",
    val confidence: Float = 1.0f,
    val source: String = "inferred",
    val expiresAt: String? = null,
    val lastConfirmedAt: String? = null,
    val lastHitAt: String? = null,
    val entityKey: String? = null,
    @androidx.room.ColumnInfo(name = "scope")
    val scope: String = "main",
    @androidx.room.ColumnInfo(name = "space_id")
    val spaceId: String = "default",
    @androidx.room.ColumnInfo(name = "score")
    val score: Float = 10f,
    @androidx.room.ColumnInfo(name = "suppressed_at")
    val suppressedAt: String? = null,
    val matchCount: Int,
)

/** v17: Línea de candidatos para el acuerdo de decadencia(Incluya solo las columnas necesarias para el cálculo). */
data class DecayCandidate(
    val id: Long,
    val score: Float,
    /** El tiempo de inicio de esta ronda de atenuación es ISO 8601. */
    val refAt: String?,
    /** 1 = Golpear de nuevo desde el último acuerdo/Fusionado. */
    val hitSinceDecay: Int = 0,
)

/** FTS rebuild Utilice peso ligero. */
data class FtsRebuildRow(
    val id: Long,
    val fact: String,
)
