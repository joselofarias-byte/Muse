package io.zer0.muse.proactive

import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.proactive.ProactiveOutcomeRepository
import io.zer0.muse.data.proactive.ProactiveRelationRepository
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.schedule.ChatGenerationManager

/**
 * Agregación de dependencias de capas de mensajes activos.
 *
 * La razón para dividirlo en tres paquetes pequeños en lugar de uno grande es porque las razones del cambio de estos tres grupos de cosas son completamente diferentes:
 *  - [ProactiveStores]  La capa de almacenamiento se activa cuando se cambia la estructura de la tabla.
 *  - [ProactiveSources] Fuente de combustible, solo se activa al agregar una nueva derivación de fuente de preocupación
 *  - [ProactiveRuntime] Entorno de host, activado al cambiar el modelo de sesión o el método de notificación
 *
 * Por cierto, el número de parámetros de cada constructor vuelve a estar dentro de un rango legible. —— Constructor con una docena de parámetros.
 * La posición debe contarse cada vez que se llama y es posible que el compilador no informe si es incorrecta.
 */
class ProactiveStores(
    val thoughts: ProactiveThoughtRepository,
    val relations: ProactiveRelationRepository,
    val outcomes: ProactiveOutcomeRepository,
)

class ProactiveSources(
    val factStore: FactStore,
    val milestoneDao: io.zer0.muse.data.milestone.MilestoneDao,
)

class ProactiveRuntime(
    val settings: SettingsRepository,
    val sessions: SessionRepository,
    val assistants: AssistantRepository,
    val notifier: MuseNotificationManager,
    val generations: ChatGenerationManager,
)
