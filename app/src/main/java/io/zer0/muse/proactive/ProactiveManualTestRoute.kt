package io.zer0.muse.proactive

import io.zer0.muse.data.ProactiveMessageConfig

internal fun proactiveConfigOnReadFailure(): ProactiveMessageConfig = ProactiveMessageConfig(
    enabled = false,
    agentSessionEnabled = true,
    allowPlainChatSession = false,
)

object ProactiveManualTestRoute {
    suspend fun resolve(config: ProactiveMessageConfig, existingAgentSession: suspend () -> ProactiveContextLoader.SessionPick?, createAgentSession: suspend () -> ProactiveContextLoader.SessionPick, plainSession: suspend () -> ProactiveContextLoader.SessionPick? = { null }): ProactiveContextLoader.SessionPick? {
        if (config.agentSessionEnabled) {
            return existingAgentSession() ?: createAgentSession()
        }
        return if (config.allowPlainChatSession) plainSession() else null
    }
}
