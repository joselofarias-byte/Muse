package io.zer0.muse.data.proactive

/**
 * Se ocupa de la contabilidad y mantenimiento de libros mayores.
 *
 * Sólo me importa "qué recordar, dónde recordarlo y cuándo borrarlo" y no "debería decirlo o no".——
 * Eso es cuestión de juzgar el motor. Este límite permite medir ambos lados de forma independiente:
 * La lógica contable no necesita construir un escenario que se pueda enviar y la lógica de juicio no necesita escribirse en una biblioteca.
 */
class ProactiveThoughtRepository(
    private val readDao: PendingThoughtDao,
    private val writeDao: PendingThoughtWriteDao,
) {

    companion object {
        /** Si has dicho lo mismo tantas veces y aún no has respondido, simplemente desiste y deja de hablar de ello. */
        const val MAX_SURFACE_COUNT = 4

        /** El número de días que finalizó la inquietud. */
        private const val RETENTION_DAYS = 30
    }

    /**
     * Recuerde una preocupación.
     *
     * [PendingThoughtEntity.sourceRef] La deduplicación se realiza cuando no está vacío: solo se permite una preocupación OPEN de la misma fuente.
     * Sin esta deduplicación, se volvería a derivar la misma memoria en cada ronda y el libro mayor podría expandirse a docenas de entradas en una hora.
     *
     * @return true = Escríbalo realmente;false = El elemento ABRIR de la misma fuente ya existe, omitir
     */
    suspend fun note(thought: PendingThoughtEntity): Boolean {
        val ref = thought.sourceRef
        if (!ref.isNullOrBlank() && readDao.countOpenBySourceRef(ref) > 0) return false
        readDao.upsert(thought)
        return true
    }

    /** Marque que se ha expresado una preocupación. */
    suspend fun markSurfaced(id: String, at: Long) {
        writeDao.markSurfaced(id, at)
    }

    /** El usuario ya ha resuelto este asunto (marcado por la conversación o manualmente por el usuario). */
    suspend fun complete(id: String) {
        writeDao.updateStatus(id, ThoughtStatus.DONE.name)
    }

    /** A los usuarios ya no les importa esto. */
    suspend fun drop(id: String) {
        writeDao.updateStatus(id, ThoughtStatus.DROPPED.name)
    }

    suspend fun getOpen(assistantId: String): List<PendingThoughtEntity> = readDao.getOpen(assistantId)

    suspend fun getAllOpen(): List<PendingThoughtEntity> = readDao.getAllOpen()

    suspend fun getRecent(limit: Int = 30): List<PendingThoughtEntity> = readDao.getRecent(limit)

    suspend fun countOpen(assistantId: String): Int = readDao.countOpen(assistantId)

    /** Una colección de ID de origen contabilizados para la deduplicación por lotes durante la derivación. */
    suspend fun getOpenSourceRefs(assistantId: String): Set<String> =
        readDao.getOpenSourceRefs(assistantId).toSet()

    /**
     * Limpieza de rutina: registros finalizados que están vencidos, mencionados demasiadas veces o son demasiado antiguos.
     *
     * Debe ejecutarse antes de cada envío, en lugar de programarse por separado. —— Es barato (dos ACTUALIZACIONES),
     * Y "caducado pero aún en estado ABIERTO" hará que el motor de decisiones procese el mismo lote de datos inactivos repetidamente.
     */
    suspend fun maintenance(now: Long, outcomeDao: ProactiveOutcomeDao? = null): MaintenanceResult {
        val expired = writeDao.expireOverdue(now)
        val dropped = writeDao.dropOverSurfaced(MAX_SURFACE_COUNT)
        val deletedThoughts = writeDao.deleteClosedBefore(now - RETENTION_DAYS * 86_400_000L)
        val staleOutcomes = outcomeDao?.expireStale(now - RETENTION_DAYS * 86_400_000L) ?: 0
        val deletedOutcomes = outcomeDao?.deleteBefore(now - RETENTION_DAYS * 86_400_000L) ?: 0
        return MaintenanceResult(expired, dropped, staleOutcomes, deletedThoughts, deletedOutcomes)
    }

    data class MaintenanceResult(
        val expired: Int,
        val dropped: Int,
        val staleOutcomes: Int,
        val deletedThoughts: Int,
        val deletedOutcomes: Int,
    ) {
        val hasAny: Boolean
            get() = (expired + dropped + staleOutcomes + deletedThoughts + deletedOutcomes) > 0
    }
}

/**
 * Intimidad de la relación y presupuesto del día.
 *
 * El reinicio entre días se coloca en esta capa en lugar de DAO: "Qué día es hoy" es semántica empresarial.
 * Se supone que la capa de almacenamiento no debe saberlo. Ponerlo aquí también hace que "restablecer" y "leer" sean un par natural.
 * No habrá ninguna situación en la que leas el recuento de ayer y te olvides de borrarlo.
 */
class ProactiveRelationRepository(
    private val dao: ProactiveRelationDao,
) {

    companion object {
        private const val DAY_MS = 86_400_000L

        private fun dateKey(now: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(now))
    }

    /**
     * Obtenga el estado de la relación. Si no existe, créelo con el valor predeterminado.
     * Por cierto, reinicie el presupuesto entre días: en un nuevo día, la cuota y el reabastecimiento comienzan desde cero.
     */
    suspend fun getOrCreate(assistantId: String, now: Long): ProactiveRelationEntity {
        val today = dateKey(now)
        val existing = dao.get(assistantId)
        val needsReset = existing != null && ProactiveTimingEngine.needsBudgetReset(existing, today)
        if (needsReset) dao.resetBudget(assistantId, today)
        val result = when {
            existing == null -> ProactiveRelationEntity(assistantId = assistantId, budgetDate = today)
            needsReset -> existing.copy(budgetDate = today, spentToday = 0, earnedToday = 0f)
            else -> existing
        }
        if (existing == null) dao.upsert(result)
        return result
    }

    /** El crédito se consume después de enviar un mensaje activo. */
    suspend fun consume(assistantId: String) {
        dao.incrementSpent(assistantId)
    }

    /**
     * El usuario dijo "No me molestes últimamente".
     *
     * Al mismo tiempo, el nivel de intimidad se ve muy afectado: no se trata de una tachadura cualquiera, sino de un rechazo explícito.
     * Una vez finalizado el tiempo de reutilización, la cantidad no volverá a su valor original inmediatamente y deberá ganarse lentamente a través de interacciones posteriores.
     */
    suspend fun startCooldown(assistantId: String, now: Long, days: Int = 7) {
        dao.updateCooldown(assistantId, now + days * DAY_MS)
        val relation = getOrCreate(assistantId, now)
        dao.updateIntimacy(assistantId, (relation.intimacy - 0.3f).coerceIn(0f, 1f))
    }

    /**
     * Cancelar el tiempo de reutilización.
     *
     * El enfriamiento es originalmente una acción fuerte que "lo dice una vez y se ocupa de ello durante siete días", pero está escrito como un botón unidireccional en la interfaz.
     * Hice clic y quise arrepentirme, pero no tenía salida. —— Los usuarios solo pueden esperar o desactivar todo el interruptor de mensajería activa.
     * Ambos costos son mucho mayores que el propio "clic equivocado".
     */
    suspend fun clearCooldown(assistantId: String) {
        dao.updateCooldown(assistantId, 0L)
    }

    suspend fun setIntensity(assistantId: String, level: ProactiveIntensity) {
        dao.updateIntensity(assistantId, level.name)
    }

    /**
     * Restablezca el nivel activo: devuelva el equipo a su valor predeterminado y borre el límite de crédito actual a cero.
     *
     * Si el usuario solo regresa al grupo pero no paga el saldo, encontrará que todavía no hay límite hoy si cambia de "fijo" a "estándar". ——
     * La cuota que se gastó en el nivel anterior seguirá ocupando la cuota en el nuevo nivel, que parece que "el reinicio no ha surtido efecto".
     */
    suspend fun resetIntensity(assistantId: String, today: String) {
        dao.updateIntensity(assistantId, ProactiveIntensity.DEFAULT.name)
        dao.resetBudget(assistantId, today)
    }

    /** Escribe intimidad directamente. Solo se utiliza para reescribir resultados y no está expuesto en la interfaz. */
    suspend fun setIntimacy(assistantId: String, intimacy: Float) {
        dao.updateIntimacy(assistantId, intimacy.coerceIn(0f, 1f))
    }

    suspend fun get(assistantId: String): ProactiveRelationEntity? = dao.get(assistantId)
}

/**
 * Cuenta de resultados de mensajes proactivos.
 *
 * Los resultados y la intimidad deben cambiar juntos: si sólo recuerdas los resultados sin cambiar la intimidad, el aprendizaje no sucederá;
 * Si sólo cambias la intimidad sin recordar los resultados, no podrás rastrear "por qué se volvió tan restringida".
 */
class ProactiveOutcomeRepository(
    private val dao: ProactiveOutcomeDao,
    private val relations: ProactiveRelationRepository,
) {

    companion object {
        /**
         * Se considerará caducado si no hay respuesta después de enviarlo durante un tiempo prolongado.
         *
         * La escala de un día es "Si no lo viste hoy, no lo viste": un mensaje activo se publicará durante dos o tres días antes de que finalice.
         * Los resultados del aprendizaje se obtendrán con un desfase de dos o tres días; si lo terminas demasiado pronto, "solo lo veré mañana por la mañana" se considerará una falta de interés.
         */
        const val EXPIRE_AFTER_HOURS = 24

        /** Igual que arriba, milisegundos. */
        const val EXPIRE_AFTER_MS = EXPIRE_AFTER_HOURS * 3_600_000L
    }

    /**
     * Recuerda un mensaje que acaba de ser enviado, el estado es PENDIENTE.
     *
     * @return La identificación del nuevo registro. El botón "Esta vez no" en la notificación depende de él para volver al mismo registro.
     *         Así que no puedes simplemente escribir y dejarlo en paz. —— La clave principal generada internamente debe devolverse a la persona que llama.
     */
    suspend fun recordSent(
        thought: PendingThoughtEntity?,
        sessionId: String,
        now: Long,
        assistantId: String = "",
        messageId: String? = null,
    ): String {
        val id = java.util.UUID.randomUUID().toString()
        dao.upsert(
            ProactiveOutcomeEntity(
                id = id,
                // La preocupación es opcional: la mayoría de los mensajes no solicitados ahora provienen de "continuar la conversación",
                // No existe ningún asiento correspondiente en el libro mayor.
                thoughtId = thought?.id,
                assistantId = thought?.assistantId ?: assistantId,
                sessionId = sessionId,
                kind = thought?.kind ?: ThoughtKind.FOLLOW_UP.name,
                sentAt = now,
                state = OutcomeState.PENDING.name,
                // Sólo guardándolo podrás saber "lo que se dijo la última vez" en la siguiente ronda para evitar repeticiones.
                messageId = messageId,
            ),
        )
        return id
    }

    /**
     * Actualiza el destino de un resultado y ajusta el nivel de intimidad simultáneamente.
     *
     * El estado de terminación no se sobrescribirá con la degradación (la notificación aún será RESPONDIDA si la tachas después de responder)——
     * De lo contrario, si el usuario hace clic para leer y luego borra la notificación, se registrará como un comentario negativo.
     */
    suspend fun resolve(outcomeId: String, state: OutcomeState, now: Long) {
        val current = dao.getById(outcomeId) ?: return
        val isTerminal = current.stateEnum() == state ||
            current.stateEnum() == OutcomeState.REPLIED ||
            state == OutcomeState.PENDING
        if (!isTerminal) applyTransition(current, state, now)
    }

    private suspend fun applyTransition(current: ProactiveOutcomeEntity, state: OutcomeState, now: Long) {
        dao.upsert(current.stamped(state, now))
        val relation = relations.getOrCreate(current.assistantId, now)
        val next = ProactiveLearning.applyOutcome(relation.intimacy, state)
        if (next != relation.intimacy) relations.setIntimacy(current.assistantId, next)
    }

    /** Escriba la marca de tiempo en la columna correspondiente. La columna de estado siempre se actualiza y la columna de tiempo se divide por estado. */
    private fun ProactiveOutcomeEntity.stamped(state: OutcomeState, now: Long): ProactiveOutcomeEntity =
        when (state) {
            OutcomeState.OPENED -> copy(state = state.name, openedAt = now)
            OutcomeState.REPLIED -> copy(state = state.name, repliedAt = now)
            OutcomeState.DISMISSED -> copy(state = state.name, dismissedAt = now)
            OutcomeState.EXPIRED, OutcomeState.PENDING -> copy(state = state.name)
        }

    /** Encuentre el mensaje activo más reciente en una conversación que aún está esperando una respuesta, para detección de respuesta y \"abrir como leído\". */
    suspend fun findPendingInSession(sessionId: String): ProactiveOutcomeEntity? =
        dao.findPendingInSession(sessionId)

    /**
     * enviar por encima [EXPIRE_AFTER_MS] El registro aún desatendido termina con EXPIRADO.
     *
     * entregar [resolve] Vaya uno por uno en lugar de actualizar directamente el estado: el final también es una retroalimentación,
     * La intimidad tendrá que bajar un poco; si simplemente cambia el estado, este "envíelo y nadie le prestará atención" significa que no ha sucedido en la relación.
     *
     * @return El número de elementos al final de este tiempo.
     */
    suspend fun expireOverdue(now: Long): Int {
        val stale = dao.listPendingBefore(now - EXPIRE_AFTER_MS)
        stale.forEach { resolve(it.id, OutcomeState.EXPIRED, now) }
        return stale.size
    }

    /**
     * ¿Cuáles de estos tipos han sido rechazados por los usuarios con los pies?
     *
     * Pregunte sobre los tipos de candidatos involucrados a la vez, en lugar de consultar la base de datos uno por uno: puede haber más de una docena de candidatos,
     * Solo hay unos pocos tipos y, naturalmente, los resultados determinados por tipo se pueden reutilizar.
     */
    suspend fun unpopularKinds(assistantId: String, kinds: Collection<ThoughtKind>): Set<ThoughtKind> =
        kinds.filterTo(mutableSetOf()) { isKindUnpopular(assistantId, it) }

    suspend fun getRecent(assistantId: String, limit: Int = 20): List<ProactiveOutcomeEntity> =
        dao.getRecent(assistantId, limit)

    /**
     * Si un tipo ha sido rechazado por los usuarios con los pies.
     *
     * Propósito: Cuando los usuarios no estén interesados ​​en los hitos, dejar de publicar informes de logros.
     * El criterio en sí (límite inferior del tamaño de la muestra, más de la mitad de la tasa tachada) es [ProactiveLearning] Aquí solo se toman números.
     */
    suspend fun isKindUnpopular(assistantId: String, kind: ThoughtKind): Boolean {
        val dismissed = dao.countKindState(assistantId, kind.name, OutcomeState.DISMISSED.name)
        val total = dismissed +
            dao.countKindState(assistantId, kind.name, OutcomeState.REPLIED.name) +
            dao.countKindState(assistantId, kind.name, OutcomeState.OPENED.name) +
            dao.countKindState(assistantId, kind.name, OutcomeState.EXPIRED.name)
        if (total == 0) return false
        return ProactiveLearning.isKindUnpopular(dismissed, total)
    }
}
