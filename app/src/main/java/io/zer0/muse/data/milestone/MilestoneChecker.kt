package io.zer0.muse.data.milestone

import io.zer0.common.Logger
import io.zer0.muse.data.session.MessageDao
import io.zer0.muse.data.session.SessionDao

/**
 * Phase 2 2B: Comprobador de hitos — Enviar mensaje/Compruebe si el hito se activa cuando se crea la sesión.
 *
 * Condición de activación:
 *  - first_message: primer mensaje
 *  - day_7: Día 7 juntos
 *  - msg_100: Mensaje 100
 *  - day_30: Día 30 juntos
 *  - day_100: 100 días juntos
 *  - msg_1000: Mensaje 1000
 *  - day_365: Día 365 juntos
 */
class MilestoneChecker(
    private val milestoneDao: MilestoneDao,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
) {
    companion object {
        private const val TAG = "MilestoneChecker"

        data class MilestoneDefinition(
            val condition: String,
            val title: String,
            val messageTemplate: String,
        )

        val DEFINITIONS = listOf(
            MilestoneDefinition("first_message", "primera conversación", "Tu historia comienza con este mensaje. ✨"),
            MilestoneDefinition("day_7", "Día 7 juntos", "Una semana de compañerismo es el comienzo más hermoso 🌿"),
            MilestoneDefinition("msg_100", "Mensaje 100", "Cien conversaciones, cien colisiones de inspiración. 💡"),
            MilestoneDefinition("day_30", "Día 30 juntos", "Después de un mes de estar contigo ya soy el amigo que mejor te entiende. 🌙"),
            MilestoneDefinition("day_100", "100 días juntos", "Cien días de compañerismo, gracias por recorrer el camino ⭐"),
            MilestoneDefinition("msg_1000", "Mensaje 1000", "Mil conversaciones, cada una única ✨"),
            MilestoneDefinition("day_365", "Un año de aniversario juntos", "Todo un año de compañerismo, esta es la relación más preciosa. 🎉"),
        )
    }

    /**
     * Verifique y active hitos. Se debe llamar después de enviar el mensaje.
     */
    suspend fun checkAndTrigger(sessionId: String, assistantId: String? = null) {
        try {
            checkMessageMilestones(sessionId, assistantId)
            checkDayMilestones(sessionId, assistantId)
        } catch (e: Exception) {
            Logger.w(TAG, "Milestone check failed", e)
        }
    }

    private suspend fun checkMessageMilestones(sessionId: String, assistantId: String?) {
        val messageCount = messageDao.countBySession(sessionId)
        val thresholds = mapOf(
            "first_message" to 1L,
            "msg_100" to 100L,
            "msg_1000" to 1000L,
        )
        for ((condition, threshold) in thresholds) {
            if (messageCount >= threshold) {
                insertIfNew(condition, threshold, assistantId, sessionId)
            }
        }
    }

    private suspend fun checkDayMilestones(sessionId: String, assistantId: String?) {
        val session = sessionDao.getById(sessionId) ?: return
        val days = (System.currentTimeMillis() - session.createdAt) / (24 * 60 * 60 * 1000)
        val dayThresholds = mapOf(
            "day_7" to 7L,
            "day_30" to 30L,
            "day_100" to 100L,
            "day_365" to 365L,
        )
        for ((condition, threshold) in dayThresholds) {
            if (days >= threshold) {
                insertIfNew(condition, days, assistantId, sessionId)
            }
        }
    }

    private suspend fun insertIfNew(condition: String, triggerValue: Long, assistantId: String?, sessionId: String) {
        val existing = milestoneDao.findByCondition(condition)
        if (existing != null) return

        val def = DEFINITIONS.find { it.condition == condition } ?: return
        milestoneDao.upsert(
            MilestoneEntity(
                conditionType = condition,
                triggerValue = triggerValue,
                title = def.title,
                message = def.messageTemplate,
                assistantId = assistantId,
                sessionId = sessionId,
            ),
        )
        Logger.i(TAG, "Milestone triggered: $condition (value=$triggerValue)")
    }
}
