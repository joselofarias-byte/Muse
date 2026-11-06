package io.zer0.memory.ai

import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * v2.6.2(comentarios de los usuarios): Notificación → Organizador de memoria.
 *
 * fondo:El usuario espera que el asistente "conozca" las notificaciones recibidas (como la llegada del mensajero, recordatorios de horarios, estado del pedido),
 * Y se puede mencionar de forma natural en los mensajes activos. Notifique al oyente que se han recopilado datos, pero que nunca antes habían entrado en la canalización de la memoria.
 *
 * Responsabilidades:
 *  1. Reciba una instantánea de las notificaciones dentro de un período de tiempo
 *  2. Entregar al modelo de gran herramienta ([MemoryLlmClient], enrutado por la capa de aplicación a UtilityTier.LARGE)
 *     Clasificar/integrar/eliminar duplicaciones/juicio de valor
 *  3. Escriba sólo hechos estructurados que sean "dignos de recordar" [FactStore](Haga otra deduplicación de similitud en el lado de la escritura)
 *
 * Puntos de diseño:
 *  - **Filtrar en lugar de cantidad completa**:Ruidos como mensajes de marketing, noticias o me gusta deben mantenerse fuera de la biblioteca; de lo contrario, la calidad de la recuperación de la memoria colapsará.
 *    Los usuarios pueden solicitar "recordar más", por lo que la tendencia de filtrado es flexible (se recuerdan todos los eventos, la vida y las citas).
 *    Pero aún así se elimina el ruido puro.
 *  - **límite de tiempo**:solo manejar [windowMs] Las notificaciones en la ventana evitan mencionar como noticia noticias antiguas de hace tres días.
 *  - **idempotente**:El mismo lote de huellas digitales del contenido de la notificación (conjunto de claves de notificación) solo se procesa una vez para evitar que se caiga repetidamente en la base de datos.
 *  - marca de hecho de notificación `source = "notification"`, facilitando a los usuarios identificarlos y limpiarlos individualmente.
 */
class NotificationMemoryDigest(
    private val factDbProvider: FactDbProvider,
    private val llmClient: MemoryLlmClient,
) {

    /** Una única notificación (proyectada desde el servicio de escucha de notificaciones por la capa de aplicación para evitar que el módulo de memoria dependa de la aplicación). */
    @Serializable
    data class NotificationInput(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val timestamp: Long,
    )

    /** Ordena los resultados. */
    @Serializable
    data class DigestResult(
        val extracted: Int = 0,
        val skipped: Boolean = false,
        val reason: String = "",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /** control de concurrencia:Ejecute solo la combinación de notificaciones una vez al mismo tiempo (LLM Llamar es más caro). */
    private val mutex = Mutex()

    /** Huellas digitales del lote de notificaciones procesadas (LRU en proceso para evitar la combinación repetida del mismo lote de notificaciones). */
    private val processedFingerprints = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > MAX_FINGERPRINTS
    }

    /**
     * Clasifique un lote de notificaciones y guárdelas en la memoria.
     *
     * @param notifications Instantánea de notificación (la persona que llama se filtra por ventana de tiempo)
     * @param scope ámbito de memoria(helperid;"main" Indica el asistente principal)
     * @param spaceId espacio de memoria
     * @param model Modelo de herramienta grande (null Cuando MemoryLlmClient vuelve al modelo de enlace principal)
     * @param locale Idioma
     * @return Combinando estadísticas
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun digest(
        notifications: List<NotificationInput>,
        scope: String = "main",
        spaceId: String = "default",
        model: Model? = null,
        locale: String = "es",
    ): DigestResult = mutex.withLock {
        if (notifications.isEmpty()) return@withLock DigestResult(skipped = true, reason = "Sin notificación")
        val fingerprint = fingerprintOf(notifications)
        synchronized(processedFingerprints) {
            if (processedFingerprints.containsKey(fingerprint)) {
                return@withLock DigestResult(skipped = true, reason = "Este lote de notificaciones ha sido solucionado.")
            }
        }

        val factStore = factDbProvider.getFactStore(scope)
        val existingPreview = runCatching {
            factStore.getByScopeAndSpace(scope, spaceId)
                .take(MAX_EXISTING_PREVIEW)
                .joinToString("\n") { "- ${it.fact.take(60)}" }
                .takeIf { it.isNotBlank() }
        }.getOrNull()

        val raw = try {
            withContext(Dispatchers.IO) {
                llmClient.callText(
                    systemPrompt = buildSystemPrompt(locale, existingPreview),
                    userContent = buildUserContent(notifications),
                    model = model,
                    temperature = 0.3f,
                    maxTokens = DIGEST_MAX_TOKENS,
                    timeoutMs = 60_000L,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Fallo de llamada de LLM de tarjeta de notificación: ${e.message}", e)
            return@withLock DigestResult(skipped = true, reason = "LLM llamada fallida")
        }
        val parsed = parseResult(raw)
        if (parsed == null || parsed.facts.isEmpty()) {
            markProcessed(fingerprint)
            return@withLock DigestResult(skipped = true, reason = "nada que recordar")
        }

        // Cayó en la biblioteca:source=notification,Es conveniente para los usuarios identificar y limpiar; La deduplicación por similitud se realiza en el lado de la escritura.
        val entries = parsed.facts
            .filter { it.isNotBlank() }
            .take(MAX_FACTS_PER_BATCH)
            .map { text ->
                FactStore.Fact(
                    fact = text,
                    sessionId = null,
                    importance = 0,
                    category = "event",
                    confidence = 0.8f,
                    source = "notification",
                )
            }
        val inserted = runCatching { factStore.addBatch(entries, scope, spaceId) }
            .onFailure { Logger.w(TAG, "Notificación de entrada fallida en la base de datos: ${it.message}", it) }
            .getOrDefault(0)

        markProcessed(fingerprint)
        Logger.i(TAG, "Clasificación de notificaciones completada: entrada ${notifications.size} Artículo → Cayó en la biblioteca $inserted Artículo")
        DigestResult(extracted = inserted)
    }

    private fun markProcessed(fingerprint: String) {
        synchronized(processedFingerprints) { processedFingerprints[fingerprint] = true }
    }

    private fun fingerprintOf(notifications: List<NotificationInput>): String {
        val seed = notifications.joinToString("|") { "${it.packageName}:${it.timestamp}:${it.title.take(30)}" }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }

    private fun buildUserContent(notifications: List<NotificationInput>): String = buildString {
        appendLine("## Notificaciones recientes")
        appendLine()
        notifications.sortedBy { it.timestamp }.forEach { n ->
            val app = n.appLabel.ifBlank { n.packageName }
            appendLine("- [$app] ${n.title}${if (n.text.isNotBlank()) " — ${n.text.take(200)}" else ""}")
        }
    }

    /** Analizar la salida de LLM:Expectativa `{"facts": ["...", "..."]}`. Tolerancia a fallos/think. */
    internal fun parseResult(raw: String): DigestJson? {
        val s0 = raw.replace(Regex("<think(?:ing)?>[\\s\\S]*?</think(?:ing)?>", RegexOption.IGNORE_CASE), "")
        val fence = Regex("""```(?:json)?\s*\n([\s\S]*?)\n```""").find(s0)
        var s = fence?.groupValues?.get(1) ?: s0.trim()
        if (!s.startsWith("{")) {
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            s = s.substring(start, end + 1)
        }
        return runCatching { json.decodeFromString(DigestJson.serializer(), s) }
            .onFailure { Logger.w(TAG, "Error de análisis JSON de tarjeta de notificación: ${it.message}") }
            .getOrNull()
    }

    @Serializable
    internal data class DigestJson(val facts: List<String> = emptyList())

    companion object {
        private const val TAG = "NotifMemoryDigest"
        private const val DIGEST_MAX_TOKENS = 1500
        private const val MAX_FACTS_PER_BATCH = 15
        private const val MAX_EXISTING_PREVIEW = 20
        private const val MAX_FINGERPRINTS = 50

        /**
         * Indicador del sistema de compilación.
         *
         * Los usuarios requieren "memorizar más", por lo que la tendencia de filtrado es flexible: recordar eventos/vida/acuerdos/tareas pendientes;
         * Simplemente elimine el puro ruido de marketing. Es necesario generar oraciones cortas estructuradas (un hecho independiente) para facilitar la deduplicación y recuperación posteriores.
         */
        internal fun buildSystemPrompt(locale: String = "es", existingPreview: String? = null): String {
            val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
            val existingSection = if (!existingPreview.isNullOrBlank()) {
                if (isZh) {
                    "\n## Ya recordado (para evitar duplicaciones)\n\n$existingPreview\n"
                } else {
                    "\n## Existing memories (avoid duplicates)\n\n$existingPreview\n"
                }
            } else {
                ""
            }
            return if (isZh) {
                """
Eres un organizador de recuerdos. El usuario quiere que el asistente "recuerde" las notificaciones recibidas en su teléfono y pueda mencionarlas más tarde de forma natural.
A continuación se muestra un lote de notificaciones que los usuarios han recibido recientemente. Extraiga los hechos que valga la pena recordar durante mucho tiempo.

$existingSection
## Vale la pena recordar (recuerde tantos como sea posible)
- expreso/Logística, estado del pedido, progreso de la comida para llevar.
- Programar recordatorios, reuniones, citas, vuelos.
- factura/pagar/Recordatorio de pago, importe y plazo
- Recordatorios de salud (medicación, examen físico, ejercicio)
- Códigos de verificación, confirmaciones de citas, códigos de recogida y otra información urgente
- Acuerdos y tareas pendientes entre usuarios y otros

## No vale la pena recordarlo (eliminado)
- Promoción de marketing pura, publicidad, promoción de cupones.
- Información de noticias, informes meteorológicos (a menos que al usuario obviamente le importe)
- Me gusta en redes sociales, seguimientos y recordatorios de comentarios
- actualizaciones del sistema,App Consejos de versión

## Requisitos de salida
- Cada hecho se expresa en una frase china independiente y autosuficiente (se puede entender sin contexto)
- Fusionar múltiples notificaciones del mismo asunto (como múltiples actualizaciones de la misma entrega urgente, solo se registra el estado final)
- No genere duplicados de la "memoria existente"
- Simplemente genere JSON sin descripciones adicionales ni barreras de rebajas:
{"facts": ["hechos1", "hechos2"]}
Si no vale la pena recordar todo el lote de notificaciones, regrese {"facts": []}
                """.trimIndent()
            } else {
                """
You are a memory organizer. The user wants the assistant to REMEMBER notifications received on their phone.
Below is a batch of recent notifications. Extract facts worth remembering long-term.

$existingSection
## Worth remembering (prefer more)
- Deliveries, orders, food progress
- Calendar reminders, meetings, flights/trains
- Bills, payments, deadlines with amounts
- Health reminders (medication, checkups)
- Verification codes, appointment/parcel pickup codes
- Commitments and to-dos

## Not worth remembering (drop)
- Pure marketing, ads, coupons
- News, generic weather
- Social likes/follows/comments
- System or app updates

## Output
- One self-contained sentence per fact
- Merge multiple notifications about the same item
- Skip anything already in "Existing memories"
- JSON only, no markdown fences:
{"facts": ["fact 1", "fact 2"]}
If nothing is worth remembering, return {"facts": []}
                """.trimIndent()
            }
        }
    }
}
