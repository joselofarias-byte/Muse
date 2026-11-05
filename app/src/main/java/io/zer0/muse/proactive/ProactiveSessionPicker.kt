package io.zer0.muse.proactive

import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.proactive.ProactiveContextLoader.SessionPick
import io.zer0.muse.proactive.ProactiveContextLoader.SessionSource
import kotlinx.coroutines.flow.first

class ProactiveSessionPicker(private val runtime: ProactiveRuntime) {

    private companion object {

        private const val MINUTE_MS = 60_000L
    }

    suspend fun resolveSessionPick(assistant: AssistantEntity): SessionPick? {
        val now = System.currentTimeMillis()
        val config = runCatching { runtime.settings.proactiveMessageConfigFlow.first() }.getOrDefault(
            proactiveConfigOnReadFailure(),
        )
        val pick = if (config.agentSessionEnabled) {
            pickExistingAgent(assistant) ?: createAgentSession(assistant)
        } else if (config.allowPlainChatSession) {
            pickPlainSession(assistant)
        } else {
            null
        }
        return pick?.let { logPick(assistant, it, now) }
    }

    suspend fun resolveManualTestRoute(assistant: AssistantEntity): SessionPick? {
        val config =
            runCatching { runtime.settings.proactiveMessageConfigFlow.first() }
                .getOrDefault(proactiveConfigOnReadFailure())
        return ProactiveManualTestRoute.resolve(
            config = config,
            existingAgentSession = { pickExistingAgent(assistant) },
            createAgentSession = {
                createAgentSession(assistant)
                    ?: error("No se pudo crear una sesión de agente")
            },
            plainSession = { pickPlainSession(assistant) },
        )
    }

    private suspend fun pickPlainSession(assistant: AssistantEntity): SessionPick? = pickRecentChat(assistant)
        ?: pickRecentAny(assistant, agentAllowed = false)
        ?: pickViewed(assistant, agentAllowed = false)

    private suspend fun pickRecentChat(assistant: AssistantEntity): SessionPick? {
        val session = runCatching {
            runtime.sessions.getRecentChatByAssistant(assistant.id)
        }.getOrNull() ?: return null
        if (session.isAgentSession) return null
        return SessionPick(session.id, SessionSource.RECENT_ACTIVE, session.updatedAt)
    }

    private suspend fun pickRecentAny(assistant: AssistantEntity, agentAllowed: Boolean): SessionPick? {
        val session = runCatching {
            runtime.sessions.getRecentActiveByAssistant(assistant.id)
        }.getOrNull() ?: return null
        if ((!agentAllowed && session.isAgentSession)) return null
        return SessionPick(session.id, SessionSource.RECENT_ANY, session.updatedAt)
    }

    private suspend fun pickViewed(assistant: AssistantEntity, agentAllowed: Boolean): SessionPick? {
        val viewed = runCatching { runtime.settings.getViewedSessionId() }.getOrNull()
        val session = viewed
            ?.let { id -> runCatching { runtime.sessions.getSessionById(id) }.getOrNull() }
            ?.takeIf { it.isWritableForProactive(assistant.id) }
            ?.takeIf { agentAllowed || !it.isAgentSession }
            ?: return null
        return SessionPick(session.id, SessionSource.VIEWED, session.updatedAt)
    }

    private suspend fun pickExistingAgent(assistant: AssistantEntity): SessionPick? {
        val session = runCatching {
            runtime.sessions.getRecentAgentByAssistant(assistant.id, 1).firstOrNull()
        }.getOrNull()
            ?.takeIf { it.deletedAt == null && !it.archived }
            ?: return null
        return SessionPick(session.id, SessionSource.EXISTING_AGENT, session.updatedAt)
    }

    private suspend fun createAgentSession(assistant: AssistantEntity): SessionPick? {
        val created = runCatching {
            runtime.sessions.createAgentSession(assistant.id)
        }.getOrNull() ?: return null
        return SessionPick(created, SessionSource.NEW_AGENT, System.currentTimeMillis())
    }

    private fun SessionEntity.isWritableForProactive(assistantId: String): Boolean =
        this.assistantId == assistantId && deletedAt == null && !archived

    private fun logPick(assistant: AssistantEntity, pick: SessionPick, now: Long): SessionPick {
        val idleMinutes = ((now - pick.lastActivityAt) / MINUTE_MS).coerceAtLeast(0L)
        Logger.i(
            TAG,
            "Selección de sesión proactiva | assistantId=${assistant.id} | sessionId=${pick.sessionId} | " +
                "source=${pick.source.wireName} | inactividad=${idleMinutes} minutos",
        )
        return pick
    }
}

private const val TAG = "ProactiveContext"
