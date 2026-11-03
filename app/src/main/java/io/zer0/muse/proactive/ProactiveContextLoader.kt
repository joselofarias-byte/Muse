package io.zer0.muse.proactive

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.ProactiveOutcomeEntity
import io.zer0.muse.data.proactive.ThoughtDeriver
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.flow.first

/**
 * Recopile los materiales externos necesarios para una ronda de inspecciones: asistentes, sesiones, conversaciones recientes y nuevo combustible para el libro mayor.
 *
 * Se clasifica en una categoría separada porque todo "toma cosas de otra parte" y falla de la misma manera. ——
 * Cada elemento puede no estar disponible porque sus dependencias aún no están listas y, cuando no se pueden obtener, se debe degradar en lugar de bloquear toda la ronda.
 * juzgarlos/La separación de la generación permite concentrar la estrategia de tolerancia a fallas de "obtener datos" en un solo lugar.
 */
class ProactiveContextLoader(
    private val stores: ProactiveStores,
    private val runtime: ProactiveRuntime,
    private val sources: ProactiveSources,
) {

    companion object {
        private const val TAG = "ProactiveContext"

        /**
         * El número de mensajes enviados para generar el contexto.
         *
         * Calcule según "ronda": una pregunta y una respuesta se cuentan como una ronda.20 rueda = 40 artículo. Los 12 originales sólo fueron suficientes para seis rondas;
         * El "Fui a comer" de ayer no apareció en absoluto, por lo que el mensaje proactivo solo pudo encontrar temas de recuerdos anteriores.
         * Lo que encontré fue, naturalmente, la historia de hace unos días.
         */
        const val RECENT_MESSAGE_COUNT = 40

        /** Tome los últimos mensajes activos como un recordatorio de \"no volver a decirlo\". */
        const val RECENT_PROACTIVE_COUNT = 4

        /**
         * El presupuesto total de personajes introducido en el contexto generado.
         *
         * Ni el límite superior del número de partidas ni el truncamiento de una sola partida pueden controlar el importe total:40 Artículo × Un solo artículo de 600 palabras puede producir más de 20.000 palabras.
         * Abarrotarlos todos con palabras clave es costoso y diluye las últimas oraciones que realmente importan.
         * más allá del tiempo de**mayor**Uno de ellos empieza a perderse. Debemos mantenerlo en las últimas rondas de diálogo. ——
         * La respuesta al mensaje no solicitado es "esa frase de ahora". Si no puedes responder, no lo digas en absoluto.
         */
        const val CONTEXT_CHAR_BUDGET = 6000

        /** En el registro, \"desde la última actividad\" se convierte en un divisor de minutos. */
        private const val MINUTE_MS = 60_000L
    }

    /** Materiales necesarios durante la sesión para una ronda de inspecciones. */
    data class SessionContext(
        val sessionId: String,
        val recentMessages: List<UIMessage>,
        val lastUserMessageAt: Long,
        /**
         * El texto de varios mensajes recientes no solicitados.
         *
         * La lucha contra la duplicación no puede depender únicamente de "modelos para ver la historia"—— Si el usuario no responde después de enviar el mensaje proactivo,
         * La próxima vez se convertirá en el último elemento de la historia, y el modelo se dejará llevar más fácilmente por él.
         * Es más fiable enumerarlo por separado y marcar "Esto ya se ha dicho" que esperar que se descubra por sí solo.
         */
        val recentProactiveTexts: List<String> = emptyList(),
    )

    /**
     * Consigue el asistente que envía el mensaje.
     *
     * Primero se usa el especificado en la configuración, en caso contrario el predeterminado y en caso contrario el primero.
     * La diferencia con la implementación anterior es que ya no depende del estado mutable del "ayudante de recepción actual".
     */
    suspend fun loadAssistant(configuredId: String): AssistantEntity? {
        val all = runCatching { runtime.assistants.observeAll.first() }.getOrDefault(emptyList())
        if (all.isEmpty()) return null
        return all.firstOrNull { it.id == configuredId.takeIf { id -> id.isNotBlank() } }
            ?: all.firstOrNull { it.id == "default" }
            ?: all.first()
    }

    /**
     * Obtenga la conversación más reciente y la última vez que habló el usuario.
     *
     * En caso de error, se devuelve un contexto vacío en lugar de nulo: aún puedes hablar de forma proactiva cuando no hay un diálogo histórico.
     * (Por ejemplo, el usuario acaba de configurar un recordatorio), por lo que no se debe omitir todo el ciclo.
     */
    suspend fun loadSessionContext(
        sessionId: String,
        assistantId: String = "",
    ): SessionContext {
        val all = runCatching { runtime.sessions.observeMessages(sessionId).first() }
            .getOrDefault(emptyList())
        // Los mensajes de las herramientas no entran en el contexto: los mensajes activos deben ser respondidos con "palabras de las personas", y el viaje de ida y vuelta entre las herramientas es sólo ruido de proceso.
        val recent = all
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(RECENT_MESSAGE_COUNT)
        val budgeted = applyCharBudget(recent)
        val lastUserAt = budgeted.lastOrNull { it.role == MessageRole.USER }?.createdAt ?: 0L
        return SessionContext(sessionId, budgeted, lastUserAt, recentProactiveTexts(all, assistantId))
    }

    /**
     * Recorta el metraje más antiguo según el presupuesto total de personajes.
     *
     * Instálelo desde el último y deténgalo cuando no quepa: siempre se perderá el más antiguo, no el último.
     * El último se conserva incondicionalmente. —— Cuando se supera el presupuesto en un aspecto, es mejor superarlo ligeramente.
     * No dejes que el modelo reciba un material vacío que diga “aún no has hablado de nada”.
     */
    private fun applyCharBudget(messages: List<UIMessage>): List<UIMessage> {
        if (messages.isEmpty()) return messages
        var total = 0
        var keepFrom = messages.size
        for (index in messages.indices.reversed()) {
            val next = total + messages[index].content.length
            // keepFrom < messages.size Significa "se ha instalado el último":
            // Es la única excepción y todos los demás mensajes están presupuestados.
            if (next > CONTEXT_CHAR_BUDGET && keepFrom < messages.size) break
            total = next
            keepFrom = index
        }
        val kept = messages.subList(keepFrom, messages.size).toList()
        val dropped = messages.size - kept.size
        if (dropped > 0) {
            Logger.i(
                TAG,
                "La cantidad total de materiales supera el presupuesto. | budget=${CONTEXT_CHAR_BUDGET}palabra | Reservar=${kept.size}Artículo/${total}palabra | " +
                    "descartar=${dropped}Artículo(mayor)",
            )
        } else {
            Logger.d(TAG, "Cantidad total de material | budget=${CONTEXT_CHAR_BUDGET}palabra | Reservar=${kept.size}Artículo/${total}palabra")
        }
        return kept
    }

    /**
     * Utilice el ID de mensaje registrado en el resultado para verificar el texto de los mensajes activos recientes.
     *
     * Si todo falla, se devolverá una lista vacía. —— Prevenir la duplicación es la guinda del pastel. Si no lo consigue, no debe detener toda la inspección.
     */
    private suspend fun recentProactiveTexts(
        allMessages: List<UIMessage>,
        assistantId: String,
    ): List<String> {
        if (assistantId.isBlank()) return emptyList()
        return runCatching {
            val ids = stores.outcomes.getRecent(assistantId, RECENT_PROACTIVE_COUNT)
                .mapNotNull { it.messageId }
                .toSet()
            if (ids.isEmpty()) {
                emptyList()
            } else {
                allMessages.filter { it.id.toString() in ids }.map { it.content }
            }
        }.getOrDefault(emptyList())
    }

    /** Fuente de conversaciones de mensajería activa. Al comprobar \"a qué sala se envió el mensaje\", primero mire este campo. */
    enum class SessionSource(val wireName: String) {
        /** El asistente ha hecho hablar recientemente a los usuarios.**ordinario**Sesión (sin pestaña Agente). La primera opción para mensajes proactivos. */
        RECENT_ACTIVE("recent_active"),

        /** Da un paso atrás: conversaciones recientes en las que los usuarios han hablado,**incluir** Agent Tab(Los usuarios solo chatean en Agente). */
        RECENT_ANY("recent_any"),

        /** La sesión que el usuario está viendo actualmente.(pertenece al asistente). */
        VIEWED("viewed"),

        /** Reutilice la sesión de Agente existente del asistente (no cree una nueva para evitar tener una página de Agente vacía cada vez). */
        EXISTING_AGENT("existing_agent"),

        /** Consulte la sesión del Agente recién creada (cuando el asistente no tiene sesión). */
        NEW_AGENT("new_agent"),
    }

    /** El resultado de la selección del punto de aterrizaje.:Escriba sobre qué sesión, por qué la eligió y cuándo estuvo activa por última vez. */
    data class SessionPick(
        val sessionId: String,
        val source: SessionSource,
        val lastActivityAt: Long,
    )

    /**
     * Localice en qué sesión se debe escribir el mensaje activo(Solo te importan las conversaciones id).
     *
     * Para el proceso de selección específico, consulte [resolveSessionPick].
     */
    suspend fun resolveSession(assistant: AssistantEntity): String? =
        resolveSessionPick(assistant)?.sessionId

    /**
     * Localizar el punto de colocación de mensajes proactivos,y devuelve el motivo de la selección.
     *
     * comportamiento objetivo:Dónde terminar de charlar,Simplemente envíalo desde donde venga. Toma el primero disponible según prioridad:
     *  1. El asistente ha hecho hablar recientemente a los usuarios.**ordinario**sesión(Las condiciones de exclusión están escritas en SQL.,ver SessionDao)
     *  2. La sesión que el usuario está viendo actualmente.(Necesidad de pertenecer al asistente.,Y lo mismo no se borra/no archivado/No es un teléfono celular pequeño)
     *  1.5 Si solo chateaste en la pestaña Agente → Escriba en esa sesión de Agente
     *  3. Reutilizar sesiones de agentes existentes
     *  4. Crear una nueva sesión de agente
     *
     * Cada paso se empaqueta por separado runCatching: cuando la dependencia no está lista, se degrada al siguiente nivel.,En lugar de dejar que toda la ronda de inspecciones colapse.
     * Retorno completamente vacío de nivel 4 null,La capa superior trata esta ronda como "sesión no disponible" y la omite.
     */
    suspend fun resolveSessionPick(assistant: AssistantEntity): SessionPick? {
        val now = System.currentTimeMillis()
        // " en la configuraciónAgent Las sesiones también envían mensajes activos": Después de desactivarlo, la pestaña Agente no se tocará en absoluto (ni se creará una nueva sesión para ella).
        val agentAllowed = agentSessionAllowed()
        // Unidos por prioridad: fracaso en cada nivel/Si está vacío, se degradará al siguiente nivel y, finalmente, se escribirá un registro de selección de manera uniforme.
        val pick = pickRecentChat(assistant)
            ?: pickRecentAny(assistant, agentAllowed)
            ?: pickViewed(assistant, agentAllowed)
            // No se pueden omitir los paréntesis:`a ?: if (c) x else y ?: z` será analizado en `a ?: (if (c) x else (y ?: z))`,
            // El resultado es que "cuando permites al Agente, no puedes llegar al fondo del asunto".—— Esto es exactamente lo que encontré en esta autoprueba.
            ?: (if (agentAllowed) pickExistingAgent(assistant) else null)
            ?: (if (agentAllowed) createAgentSession(assistant) else null)
        return pick?.let { logPick(assistant, it, now) }
    }

    /** Leer \"Agent Active \"Enviar también mensajes activos a las sesiones\"; presione \"Permitir\" cuando el mensaje no se pueda leer (mantenga el comportamiento existente). */
    private suspend fun agentSessionAllowed(): Boolean = runCatching {
        runtime.settings.proactiveMessageConfigFlow.first().agentSessionEnabled
    }.getOrDefault(true)

    /** 1) El usuario habló por última vez**ordinario**sesión (Agent Tab No cuenta: es una interfaz independiente, y hablar allí no significa que el mensaje deba aparecer en la página del Agente). */
    private suspend fun pickRecentChat(assistant: AssistantEntity): SessionPick? {
        val session = runCatching {
            runtime.sessions.getRecentChatByAssistant(assistant.id)
        }.getOrNull() ?: return null
        return SessionPick(session.id, SessionSource.RECENT_ACTIVE, session.updatedAt)
    }

    /** 1.5) El usuario solo chateó en la pestaña Agente —— Vuelva a escribir en esa sesión del Agente en lugar de crear una nueva (al crear una nueva se agregarán más páginas del Agente). */
    private suspend fun pickRecentAny(assistant: AssistantEntity, agentAllowed: Boolean): SessionPick? {
        val session = runCatching {
            runtime.sessions.getRecentActiveByAssistant(assistant.id)
        }.getOrNull() ?: return null
        if (!agentAllowed && session.isAgentSession) return null
        return SessionPick(session.id, SessionSource.RECENT_ANY, session.updatedAt)
    }

    /** 2) La conversación que el usuario está viendo (se filtrará a este nivel cuando se acaba de abrir y aún no ha hablado, por lo que escribirla es exactamente donde el usuario quiere verla). */
    private suspend fun pickViewed(assistant: AssistantEntity, agentAllowed: Boolean): SessionPick? {
        val viewed = runCatching { runtime.settings.getViewedSessionId() }.getOrNull()
        val session = viewed
            ?.let { id -> runCatching { runtime.sessions.getSessionById(id) }.getOrNull() }
            ?.takeIf { it.isWritableForProactive(assistant.id) }
            ?.takeIf { agentAllowed || !it.isAgentSession }
            ?: return null
        return SessionPick(session.id, SessionSource.VIEWED, session.updatedAt)
    }

    /** 3) Reutilice la sesión de Agente existente del asistente (debe crear una nueva solo después de que el usuario la haya borrado activamente). */
    private suspend fun pickExistingAgent(assistant: AssistantEntity): SessionPick? {
        val session = runCatching {
            runtime.sessions.getRecentAgentByAssistant(assistant.id, 1).firstOrNull()
        }.getOrNull()
            ?.takeIf { it.deletedAt == null && !it.archived }
            ?: return null
        return SessionPick(session.id, SessionSource.EXISTING_AGENT, session.updatedAt)
    }

    /** 4) La última advertencia: no hay ninguna sesión disponible, cree una nueva sesión de Agente. */
    private suspend fun createAgentSession(assistant: AssistantEntity): SessionPick? {
        val created = runCatching {
            runtime.sessions.createAgentSession(assistant.id)
        }.getOrNull() ?: return null
        return SessionPick(created, SessionSource.NEW_AGENT, System.currentTimeMillis())
    }
    /** ¿Se pueden escribir mensajes activos en la conversación?:Pertenece al asistente, no se elimina ni se archiva. */
    private fun SessionEntity.isWritableForProactive(assistantId: String): Boolean =
        this.assistantId == assistantId && deletedAt == null && !archived

    private fun logPick(assistant: AssistantEntity, pick: SessionPick, now: Long): SessionPick {
        val idleMinutes = ((now - pick.lastActivityAt) / MINUTE_MS).coerceAtLeast(0L)
        Logger.i(
            TAG,
            "Selección de sesión de mensajería activa | assistantId=${assistant.id} | sessionId=${pick.sessionId} | " +
                "source=${pick.source.wireName} | último evento=${idleMinutes}minutos",
        )
        return pick
    }

    /** La hora a la que se envió el último mensaje activo, utilizada para suprimir ráfagas de mensajes. */
    suspend fun lastSentAt(assistantId: String): Long = runCatching {
        stores.outcomes.getRecent(assistantId, 1).firstOrNull()?.sentAt ?: 0L
    }.getOrDefault(0L)

    /** Encuentra mensajes activos en una conversación que aún están esperando una respuesta. */
    suspend fun findPendingOutcome(sessionId: String): ProactiveOutcomeEntity? =
        stores.outcomes.findPendingInSession(sessionId)

    /**
     * Derivar nuevas preocupaciones a partir de recuerdos e hitos.
     *
     * No lo tires si falla: La derivación es solo para "reponer combustible". Si se estropea, no debería detener toda la ronda de inspecciones.
     *
     * v17: Apareció(La puntuación está por debajo del umbral de deterioro)la memoria no participa en la derivación —— La preocupación aquí derivada se convertirá
     * El contexto de los mensajes proactivos,Dejar entrar el recuerdo de la aparición por esta abertura significa que la aparición no se hizo.
     */
    suspend fun deriveThoughts(assistantId: String, now: Long) {
        runCatching {
            val tracked = stores.thoughts.getOpenSourceRefs(assistantId)
            val scope = if (assistantId == "default") "main" else assistantId
            val facts = runCatching { sources.factStore.getByScope(scope, includeSuppressed = false) }.getOrDefault(emptyList()) +
                runCatching { sources.factStore.getByScope("main", includeSuppressed = false) }.getOrDefault(emptyList())

            val fromFacts = ThoughtDeriver.fromFacts(
                facts = facts.distinctBy { it.id },
                assistantId = assistantId,
                now = now,
                alreadyTracked = tracked,
            )
            fromFacts.forEach { stores.thoughts.note(it) }

            val milestones = runCatching { sources.milestoneDao.getAll() }.getOrDefault(emptyList())
                .map {
                    ThoughtDeriver.MilestoneRecord(
                        id = it.id,
                        title = it.title,
                        message = it.message,
                        createdAt = it.createdAt,
                        dismissedAt = it.dismissedAt,
                    )
                }
            ThoughtDeriver.fromMilestones(milestones, assistantId, now, tracked)
                .forEach { stores.thoughts.note(it) }

            if (fromFacts.isNotEmpty()) {
                Logger.i(TAG, "derivado de la memoria ${fromFacts.size} nuevas preocupaciones")
            }
        }.onFailure { e ->
            Logger.w(TAG, "La derivación de la preocupación falló (no afecta la inspección): ${e.message}")
        }
    }
}
