package io.zer0.muse.schedule

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.fact.FactStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.ui.GreetingHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId

internal fun dailySummaryNotificationsEnabledOrFalse(value: Boolean?): Boolean = scheduleWorkEnabledOrFalse(value)

/** Returns the latest configured summary hour that has already passed today. */
internal fun latestDueDailySummaryHour(currentMinutes: Int, configuredSlots: Collection<Int>): Int? = configuredSlots
    .asSequence()
    .filter { it in 0..23 }
    .filter { it * 60 <= currentMinutes }
    .maxOrNull()

/**
 * La única forma de generar resúmenes diarios.
 *
 * Worker La compensación por la página de inicio y la recepción se ejecuta aquí para evitar“Solo se entregó WorkManager pero no hay contenido en la página de inicio.”
 * Y active dos solicitudes de LLM al mismo tiempo. La preferencia en un momento dado todavía la maneja SettingsRepository DataStore
 * garantía comercial,Mutex Responsable de la serialización rápida dentro del mismo proceso.
 */
class DailySummaryService(
    private val settings: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val factStore: FactStore,
    private val chatService: ChatService?,
    private val notificationManager: MuseNotificationManager?,
) {

    private val mutex = Mutex()
    private val activeSlots = mutableSetOf<String>()

    /**
     * Cuando se abre la página de inicio en la recepción, se genera inmediatamente un resumen del período actual que ha expirado.
     * Los puntos de tiempo que se hayan adelantado con éxito no se regenerarán; Si falla, se liberará la marca de preferencia, lo que permitirá que el siguiente primer plano ingrese y vuelva a intentarlo.
     */
    suspend fun generateCurrentSlotIfDue(nowMillis: Long = System.currentTimeMillis()): Boolean = mutex.withLock {
        val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
            now.get(java.util.Calendar.MINUTE)
        val configuredSlots = try {
            settings.dailySummarySlotsFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo leer el período de resumen diario,Saltar compensación de primer plano: ${e.message}", e)
            return@withLock false
        }
        val dueHour = latestDueDailySummaryHour(currentMinutes, configuredSlots)
            ?: return@withLock false
        val zone = ZoneId.systemDefault()
        val targetDate = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val targetDateText = targetDate.toString()
        val slotKey = DailySummaryWorker.slotKey(targetDateText, dueHour, 0)
        val summaryDate = DailySummaryWorker.summaryDateForTarget(targetDateText, dueHour)
        val completed = settings.isDailySummarySlotCompleted(slotKey)
        if (!completed && settings.isDailySummarySlotClaimed(slotKey) && slotKey !in activeSlots) {
            // El proceso puede finalizar después de ser reclamado pero antes de guardarlo; cuando el proceso actual no tiene tareas activas en ese momento, es seguro liberarlo e intentarlo nuevamente.
            settings.releaseDailySummarySlot(slotKey)
        }
        val notificationsEnabled = try {
            settings.dailySummaryEnabledFlow.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo leer el interruptor de notificación de resumen diario,Cerrar procesamiento de notificaciones: ${e.message}", e)
            dailySummaryNotificationsEnabledOrFalse(null)
        }
        generateForSlotLocked(slotKey, summaryDate, notificationsEnabled)
    }

    /** Worker Entrada de generación de puntos de tiempo compartida con la compensación de la recepción. */
    suspend fun generateForSlot(slotKey: String, summaryDate: LocalDate, notificationsEnabled: Boolean): Boolean = mutex.withLock {
        generateForSlotLocked(slotKey, summaryDate, notificationsEnabled)
    }

    private suspend fun generateForSlotLocked(slotKey: String, summaryDate: LocalDate, notificationsEnabled: Boolean): Boolean {
        if (settings.isDailySummarySlotCompleted(slotKey)) {
            Logger.d(TAG, "Se ha completado el punto de tiempo del resumen diario,saltar: $slotKey")
            return true
        }
        var claimed = resultOf { settings.claimDailySummarySlot(slotKey) }
            .onError { message, throwable ->
                Logger.w(TAG, "Error en la preferencia del punto de tiempo del resumen diario($slotKey): ${throwable?.message ?: message}")
            }
            .getOrNull() ?: false
        if (!claimed && slotKey !in activeSlots && !settings.isDailySummarySlotCompleted(slotKey)) {
            // claimed solo representa“alguna vez comenzó”;Si el proceso sale antes de guardar, el proceso actual no tiene tareas activas,
            // Libere el espacio antiguo y vuelva a aprovecharlo para evitar que un accidente se trague permanentemente el período.
            settings.releaseDailySummarySlot(slotKey)
            claimed = resultOf { settings.claimDailySummarySlot(slotKey) }.getOrNull() ?: false
        }
        if (!claimed) {
            Logger.d(TAG, "El punto de tiempo del resumen diario se está ejecutando o se ha adelantado,saltar: $slotKey")
            return false
        }
        activeSlots += slotKey
        return try {
            val generated = resultOf {
                generateAndDeliver(slotKey, summaryDate, notificationsEnabled)
            }.onError { message, throwable ->
                Logger.w(TAG, "Error al generar el resumen diario($slotKey): ${throwable?.message ?: message}")
            }.getOrNull() == true
            if (!generated) {
                // Solo suelte el marcador de posición cuando no se pueda guardar para evitar un error transitorio que devore permanentemente ese momento.
                resultOf { settings.releaseDailySummarySlot(slotKey) }
                    .onError { message, throwable ->
                        Logger.w(TAG, "No se pudo publicar el punto de tiempo del resumen diario($slotKey): ${throwable?.message ?: message}")
                    }
            }
            generated
        } finally {
            activeSlots -= slotKey
        }
    }

    private suspend fun generateAndDeliver(slotKey: String, summaryDate: LocalDate, notificationsEnabled: Boolean): Boolean {
        val zone = ZoneId.systemDefault()
        val dayStart = summaryDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = summaryDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        // Recupere únicamente los mensajes de los usuarios para evitar repetir las respuestas del subdirector en los materiales de resumen diarios.
        val todayMessages = withContext(Dispatchers.IO) {
            resultOf { sessionRepository.getUserMessagesBetween(dayStart, dayEnd, 40) }
                .getOrNull()
                ?: emptyList()
        }
        // v17: Los resúmenes diarios ponen estos hechos en contexto,Apareció(La puntuación está por debajo del umbral de deterioro)no participa en la inyección.
        val facts = resultOf { factStore.getAll("main", includeSuppressed = false) }.getOrNull() ?: emptyList()
        val todayFacts = facts
            .sortedByDescending { it.createdAt }
            .take(30)
            .mapNotNull { fact ->
                val timePart = fact.time?.takeIf { it.isNotBlank() }?.let { " (tiempo:$it)" } ?: ""
                "${fact.fact}$timePart"
            }

        // v2.x: Umbral de material — Cuando no hay suficiente información válida del usuario y no hay datos disponibles,No escribas un resumen duro.
        // Cuando el material sea deficiente, LLM compilará"Olvídalo,No te preocupes más"Frases tan poco informativas(Comentarios de los usuarios),Utilice plantillas de saludo ligeras en su lugar.
        val effectiveUserCount = todayMessages.count { it.content.isNotBlank() }
        val summary = if (chatService != null &&
            (effectiveUserCount >= MIN_MESSAGES_FOR_SUMMARY || todayFacts.isNotEmpty())
        ) {
            generateSummary(chatService, todayMessages, todayFacts)
        } else {
            Logger.i(TAG, "Material de resumen diario insuficiente(mensaje válido=$effectiveUserCount, facts=${todayFacts.size}),Utilice saludos ligeros")
            lightGreeting()
        }

        if (summary.isNullOrBlank()) {
            Logger.w(TAG, "El resultado de la generación del resumen diario está vacío,saltar")
            return false
        }

        // Guárdelo primero y luego decida si desea enviar notificaciones según los cambios de usuario y de front-end; la página de inicio siempre puede reutilizar este resultado.
        settings.saveDailySummary(summaryDate.toString(), summary)
        settings.completeDailySummarySlot(slotKey)
        val manager = notificationManager
        if (manager == null) {
            Logger.w(TAG, "MuseNotificationManager No listo,Resumen diario guardado pero notificación no enviada")
            return true
        }
        if (manager.isAppForeground()) {
            Logger.d(TAG, "resumen diario:Aplicación en primer plano,Saltar notificación(Contenido guardado,Saludo de la página de inicio visible)")
            return true
        }
        if (!notificationsEnabled) {
            Logger.d(TAG, "Resumen diario guardado,El interruptor está apagado,Saludo de la página de inicio visible")
            return true
        }
        manager.notifyReminder(
            title = SUMMARY_TITLES.random(),
            message = summary,
            notificationId = DailySummaryWorker.NOTIF_ID_DAILY_SUMMARY,
            target = io.zer0.muse.notification.MuseNotificationTarget.Home,
        )
        Logger.i(TAG, "Resumen diario guardado y enviado: ${summary.take(40)}...")
        return true
    }

    /** LLM Resumen local determinista sobre el fallo, utilizando únicamente imágenes reales. */
    private suspend fun generateSummary(service: ChatService, todayMessages: List<UIMessage>, facts: List<String>): String? {
        val hasContent = todayMessages.isNotEmpty() || facts.isNotEmpty()
        val systemPrompt = if (hasContent) {
            """
Eres el asistente acompañante del usuario. Por favor escriba un resumen de la tarde de hoy basado en material real.
reglas:Inglés hablado, revisa como un amigo.;Elija solo 1 punto más importante;No inventes cosas que no sucedieron;Salida de 18 a 40 palabras;
Solo genera una línea de texto,Sin títulos, prefijos, comillas,MOOD, reflexión o salto de línea;
No menciones información estadística como número de chats, número de mensajes, etc.,no usar“escribir”“grabar”palabras como,Hablemos del asunto directamente.
Cuando el material no es suficiente para refinar los puntos clave,Simplemente escribe un saludo vespertino natural.,No resuma ni repita con fuerza las oraciones originales del material.
            """.trimIndent()
        } else {
            """
Eres el asistente acompañante del usuario. El usuario no ha dejado material disponible hoy,Por favor escriba un saludo vespertino natural.
No inventes lo que pasó hoy;Puedes saludar o simplemente preguntar cómo estás.;16 Dentro de palabras;Sólo se genera una línea de texto.
            """.trimIndent()
        }
        val userContent = buildString {
            appendLine("<summary_material>")
            if (todayMessages.isNotEmpty()) {
                appendLine("Lo que dijeron los usuarios hoy(en orden cronológico):")
                todayMessages.forEach { message ->
                    appendLine("- ${message.content.replace('\n', ' ').take(120)}")
                }
            }
            if (facts.isNotEmpty()) {
                appendLine("Eventos recientes del usuario(Mencione únicamente asuntos que sean relevantes para el momento actual o que sean claramente inminentes.):")
                facts.take(10).forEach { appendLine("- $it") }
            }
            if (!hasContent) appendLine("No hay imágenes disponibles.")
            appendLine("</summary_material>")
        }
        return resultOf {
            withTimeoutOrNull(GEN_TIMEOUT_MS) {
                GenerationGate.withPermit {
                    service.completeText(
                        messages = listOf(
                            UIMessage(
                                role = MessageRole.SYSTEM,
                                content = systemPrompt,
                                createdAt = System.currentTimeMillis(),
                            ),
                            UIMessage(
                                role = MessageRole.USER,
                                content = userContent,
                                createdAt = System.currentTimeMillis(),
                            ),
                        ),
                        temperature = 0.6f,
                        // v2.7: relajarse para 96 —— Palabras rápidas objetivo de 18 a 40 palabras,96 token Deja suficiente margen,
                        // La oración no se cortará a la mitad porque el límite superior es demasiado ajustado (el resumen de comentarios de los usuarios se corta).
                        maxTokens = 96,
                    ).text.let { io.zer0.muse.transformer.stripThinkTags(it) }
                }
            }
        }.onError { message, throwable ->
            Logger.w(TAG, "Fallo de llamada de resumen diario LLM: ${throwable?.message ?: message}")
        }.getOrNull()?.let { raw ->
            // v2.7: Solo normalización + límite superior de seguridad flexible, sin truncamiento estricto de 24 caracteres (la longitud está determinada por la palabra emergente/maxTokens controlar).
            GreetingHelper.normalizeGreetingText(raw, SUMMARY_MAX_CHARS)
        } ?: buildLocalSummary(todayMessages, facts)
    }

    /**
     * Redacción ligera cuando no hay material suficiente (sin resumen escrito).
     *
     * P1-8 Reparación (informe de auditoría): La implementación anterior generaba "Buenos días" por período de tiempo./buen mediodía/Buenas noches" y**Escribir texto de resumen muerto**,
     * Los saludos de franja horaria en la parte superior de la página de inicio se calculan en tiempo real. —— Así que cuando lo abras por la noche, verás
     * "Buenas noches" aparece al mismo tiempo que "Buenas tardes" en el resumen, lo que parece un error.
     *
     * El resumen se genera una vez y se reutiliza durante mucho tiempo. No debe contener información sobre períodos de tiempo vencidos, solo el contenido en sí.
     */
    private fun lightGreeting(): String = "No hay nada de qué hablar hoy, no dudes en ponerte en contacto conmigo cuando se te ocurra algo."

    private fun buildLocalSummary(todayMessages: List<UIMessage>, facts: List<String>): String {
        // v1.0.92: El área de saludo está situada como"Espacio de recordatorio rápido del asistente" —
        // copia antigua("Charlé hoyNveces,escribir:XX")Tanto haciendo estadística como actuando.(Comentarios de los usuarios),Eliminado;
        // La versión normal sólo conserva"Cosas que vale la pena recordar"mismo,Di las cosas directamente.
        val firstFact = facts.firstOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(20)
        val raw = when {
            !firstFact.isNullOrBlank() -> firstFact
            todayMessages.any { it.content.isNotBlank() } -> "Has trabajado duro hoy, vete a la cama temprano."
            else -> "No hay nuevas conversaciones hoy."
        }
        return GreetingHelper.normalizeGreetingText(raw, SUMMARY_MAX_CHARS) ?: "No hay nuevas conversaciones hoy."
    }

    companion object {
        private const val TAG = "DailySummaryService"
        private const val GEN_TIMEOUT_MS = 60_000L

        /**
         * v2.7: El número máximo de palabras en el lado de generación del resumen (el número objetivo de palabras en el mensaje + el límite estricto flexible aquí).
         *
         * Comentarios de los usuarios: el resumen siempre se trunca, espero“No hay límite en el recuento de palabras, pero limita el número total de palabras generadas por el modelo.”.
         * La capa de presentación ya no está truncada; El control de longitud se otorga al lado de la generación:
         *  - La palabra clave claramente requiere entre 18 y 40 palabras (revisión oral, extensión natural)
         *  - maxTokens=96 Da suficiente margen, pero no dejes que el modelo se convierta en un artículo largo.
         *  - Aquí solo se usa un límite superior de seguridad flexible (el mismo 200 para la capa de visualización) para evitar que el modelo se escape de manera anormal.
         */
        private const val SUMMARY_MAX_CHARS = GreetingHelper.DAILY_SUMMARY_DISPLAY_SAFETY_LIMIT

        /** v2.x: Número mínimo de mensajes de usuario válidos necesarios para un resumen escrito(Utilice saludos ligeros cuando esté por debajo de este valor y no haya ningún hecho.). */
        private const val MIN_MESSAGES_FOR_SUMMARY = 3
        private val SUMMARY_TITLES = listOf(
            "resumen de hoy",
            "¿Cómo estuvo tu día hoy?",
            "Repaso antes de acostarse",
            "Hablaste mucho hoy",
            "La reseña de hoy",
        )
    }
}
