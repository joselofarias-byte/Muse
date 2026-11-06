package io.zer0.muse.perf

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.transformer.ExperienceTextCache
import io.zer0.muse.ui.markdown.SanitizedHtmlCache
import io.zer0.muse.util.MessageTokenCache
import io.zer0.muse.util.TokenEstimator
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * mejoras de rendimiento**micropunto de referencia**(JVM lado, solo impresión sin afirmación).
 *
 * ## ¿Por qué tenerlo?
 *
 * La mayoría de los beneficios de esta serie de cambios son"conteo determinista"(número de recargas, número de escrituras en disco, número de asignaciones),
 * Pero nadie sabe la magnitud relativa de cuánto tiempo se traducen estos recuentos. Cuando la máquina real no está disponible, al menos se puede hacer en la JVM
 * poner**misma entrada**en"Cambiar el método de escritura anterior."con"Método de escritura cambiado"El consumo de tiempo a continuación se escribe uno al lado del otro. —— no representa ART
 * rendimiento absoluto activado, pero puede responder"¿Vale la pena mantener esta optimización?".
 *
 * ## disciplina
 *
 * - **No hagas afirmaciones**(La máquina requiere mucho tiempo y fluctuaciones aleatorias, la afirmación debe ser inconsistente); solo imprima la tabla en stdout.
 * - Calienta todo primero para evitar contar la carga JIT/clase.
 * - Los números son sólo para comparación horizontal (antes de la modificación versus después de la modificación, almacenamiento en caché activado versus almacenamiento en caché desactivado) y no se pueden usar como"La máquina real lleva tiempo"Cita.
 *
 * Ejecutar:`:app:testDebugUnitTest --tests "io.zer0.muse.perf.PerfMicroBenchmarkTest"`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PerfMicroBenchmarkTest {

    private inline fun measureMillis(rounds: Int, block: () -> Unit): Double {
        // precalentar
        repeat(3) { block() }
        val start = System.nanoTime()
        repeat(rounds) { block() }
        return (System.nanoTime() - start) / 1_000_000.0 / rounds
    }

    private fun row(name: String, before: Double, after: Double) {
        println(
            "[perf] %-46s antes del cambio %8.3f ms | Después de la modificación %8.3f ms | aumento %5.1fx".format(
                name,
                before,
                after,
                if (after > 0) before / after else 0.0,
            ),
        )
    }

    /** Desglose de costos de los enlaces de registro (P22/P23 Comparación antes y después). */
    private fun probeLoggingCosts() {        // 6) El costo total de un tronco (PII Desensibilización + archivo adjunto), consulte la implementación de Logger.d
        val logDir = java.nio.file.Files.createTempDirectory("muse-log-bench").toFile()
        io.zer0.common.Logger.initFileLog(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        )
        io.zer0.common.Logger.d("bench", "precalentar")
        val perLog = measureMillis(50) {
            repeat(100) { index -> io.zer0.common.Logger.d("bench", "No. $index Registros: una vez que se completa la llamada a la herramienta, lleva tiempo 12ms") }
        }
        // Luego ponga el fregadero (DebugLogStore) conectado——En producción, MuseApp configura Logger.sink.
        // DebugLogStore ajustará el búfer circular cada vez que escriba un registro (en la ronda 21, se cambia a un recuento de incremento automático, ya no O(n)).
        io.zer0.muse.debug.DebugLogStore.clear()
        io.zer0.common.Logger.sink = { level, tag, msg, thr ->
            io.zer0.muse.debug.DebugLogStore.log(level, tag, msg, thr)
        }
        repeat(2_000) { index -> io.zer0.common.Logger.d("bench", "Llene el primero $index log, usado para llenar el buffer") }
        val perLogWithSink = measureMillis(50) {
            repeat(100) { index -> io.zer0.common.Logger.d("bench", "No. $index Registros: una vez que se completa la llamada a la herramienta, lleva tiempo 12ms") }
        }
        row("Registro único (incluido el receptor DebugLogStore, el búfer está lleno)", 0.0, perLogWithSink / 100.0)
        io.zer0.common.Logger.sink = null
        io.zer0.muse.debug.DebugLogStore.clear()

        // Mantenga solo la PII insensibilizada: aumente el nivel del archivo a WARN (D (no escriba el archivo),sink Déjelo en blanco para aislar el costo de la desensibilización en sí.
        io.zer0.common.Logger.fileLogLevel = io.zer0.common.Logger.Level.WARN
        val sanitizeOnly = measureMillis(50) {
            repeat(100) { index -> io.zer0.common.Logger.d("bench", "No. $index Registros: una vez que se completa la llamada a la herramienta, lleva tiempo 12ms") }
        }
        row("Registro único (solo desensibilización de PII, sin escritura de archivos)", 0.0, sanitizeOnly / 100.0)
        io.zer0.common.Logger.fileLogLevel = io.zer0.common.Logger.Level.DEBUG
        // La línea base antes del cambio es 0,246 ms en la misma máquina."por línea open + append + close"valor medido real (20.ª ronda).
        row("Registro único (desensibilización + anexar,100 Media de barra; antes del cambio=cambiar archivo por línea)", 0.246, perLog / 100.0)
        logDir.deleteRecursively()
    }

    /** Estimaciones históricas de ocupación una vez por ronda en el ciclo de la herramienta:5 ¿Cuánto puedes guardar compartiendo caché por ronda (Ronda 38)? */
    private fun probeToolLoopTokens() {
        val base = (0 until 100).map { index ->
            UIMessage(
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "No. $index Mensaje histórico, la longitud del texto es media.".repeat(4),
            )
        }
        // Simular 5 rondas: adicionales cada ronda assistant(tool_calls) + tool(result) dos
        val rounds = (1..5).map { round ->
            listOf(
                UIMessage(role = MessageRole.ASSISTANT, content = "No. $round El texto antes de la herramienta rueda se llama".repeat(10)),
                UIMessage(role = MessageRole.TOOL, content = "No. $round salida de herramienta de rueda".repeat(40)),
            )
        }
        val plain = measureMillis(30) {
            var history = base
            rounds.forEach { added ->
                history = history + added
                TokenEstimator.estimate(history)
            }
        }
        val cached = measureMillis(30) {
            val cache = MessageTokenCache()
            var history = base
            rounds.forEach { added ->
                history = history + added
                TokenEstimator.estimate(history, cache = cache)
            }
        }
        row("Estimaciones históricas de ocupación para 5 rondas del ciclo de la herramienta (recalculadas individualmente)", 0.0, plain)
        row("Igual que arriba,5 caché de recuento compartido redondo (P27)", plain, cached)
    }

    /** Revisión de desbordamiento antes de enviar: calcule la misma carga útil dos veces y cuánto se puede guardar almacenando en búfer la segunda vez (ronda 37). */
    private fun probePreSendOverflow() {
        val prefix = (0 until 2).map { index ->
            UIMessage(role = MessageRole.SYSTEM, content = "Prefijo del sistema $index ".repeat(400))
        }
        val history = (0 until 200).map { index ->
            UIMessage(
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "No. $index Un mensaje histórico con longitud de texto media, utilizado para simular una sesión cercana al límite superior de la ventana.".repeat(4),
            )
        }
        val compressed = listOf(UIMessage(role = MessageRole.SYSTEM, content = "Un resumen condensado de esta ronda.".repeat(60))) +
            history.takeLast(20)

        val cold = measureMillis(30) {
            TokenEstimator.estimate(prefix + history)
            TokenEstimator.estimate(prefix + compressed)
        }
        val warm = measureMillis(30) {
            val cache = MessageTokenCache()
            TokenEstimator.estimate(prefix + history, cache = cache)
            TokenEstimator.estimate(prefix + compressed, cache = cache)
        }
        row("Revisión de desbordamiento antes del envío: estimación de dos pasadas original + comprimida", 0.0, cold)
        row("Igual que arriba, dos pases comparten el caché de recuento (P26)", cold, warm)
    }

    /** tres elementos"Siempre ha sido reconocido por los comentarios como O(n), pero nunca lo midió"de costos residuales (Ronda 36). */
    private fun probeResidualCosts() {        val longHistory = (0 until 200).map { index ->
            UIMessage(
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "No. $index El texto del mensaje, que tiene una duración cercana a la de una conversación real, se utiliza para estimar el coste de replicar toda la tabla.".repeat(3),
            )
        }
        // ① Transmisión por fragmento"Copiar toda la tabla + cambiar una"
        val listCopy = measureMillis(200) {
            val copy = longHistory.toMutableList()
            copy[longHistory.size - 1] = longHistory.last().copy(content = longHistory.last().content + "x")
        }
        row("Transmisión por fragmento:200 Copie toda la tabla de mensajes y reemplácela por una", 0.0, listCopy)

        // ② La cantidad total ocupada por lo anterior se revaloriza una vez por ronda.
        val sysPrompt = "Palabra de aviso del sistema".repeat(400)
        io.zer0.muse.ui.chat.ContextTokenUsage.estimate(longHistory, sysPrompt)
        val usageCost = measureMillis(50) { io.zer0.muse.ui.chat.ContextTokenUsage.estimate(longHistory, sysPrompt) }
        row("Una vez por ronda:200 historial + sistema solicita una reevaluación de ocupación", 0.0, usageCost)

        // ③ La transmisión se recalcula cada vez que cambia el estado."agrupación de procesos"
        val displayMessages = (0 until 100).map { index ->
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = if (index % 3 == 0) "Capítulo con texto principal. $index Artículo" else "",
                reasoning = if (index % 3 == 0) null else "pieza de pensamiento $index",
            )
        }
        io.zer0.muse.ui.chat.ChatDisplayGrouper.group(displayMessages, { it.content.isBlank() && it.reasoning != null })
        val groupCost = measureMillis(100) {
            io.zer0.muse.ui.chat.ChatDisplayGrouper.group(
                displayMessages,
                { it.content.isBlank() && it.reasoning != null },
            )
        }
        row("Cada cambio de estado:100 procesar agrupación de mensajes", 0.0, groupCost)
    }

    @Test
    fun report() {
        println("[perf] ===== JVM Micro-benchmark (no representa el consumo de tiempo real de la máquina, solo una comparación relativa)=====")

        // 1) Limpieza de HTML de tarjeta: por ensamblaje versus caché de visitas (P5)
        val svg = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 400\">")
            repeat(200) { index ->
                append("<rect x=\"${index * 4}\" y=\"10\" width=\"3\" height=\"120\" fill=\"#2f8f6b\"/>")
                append("<text x=\"${index * 4}\" y=\"140\" font-size=\"10\">$index</text>")
            }
            append("</svg>")
        }
        val cardCode = svg + "<script>" + "var x=1;".repeat(400) + "</script>"
        val coldSanitize = measureMillis(30) {
            SanitizedHtmlCache.clear()
            SanitizedHtmlCache.get(cardCode)
        }
        val warmSanitize = measureMillis(2_000) { SanitizedHtmlCache.get(cardCode) }
        row("Tarjeta HTML limpia (${cardCode.length / 1024}KB)", coldSanitize, warmSanitize)

        // 2) token Estimación:50 historial, volumen completo secuencial versus caché compartido (P11)
        val history = (0 until 50).map { index ->
            UIMessage(
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "No. $index Un mensaje histórico con longitud de texto media, utilizado para simular una conversación real.".repeat(6),
            )
        }
        TokenEstimator.estimate(history) // Activar la inicialización de jtokkit
        val noCache = measureMillis(20) { TokenEstimator.estimate(history.take(25)) }
        val cache = MessageTokenCache()
        TokenEstimator.estimate(history.take(25), cache = cache)
        val withCache = measureMillis(200) { TokenEstimator.estimate(history, cache = cache) }
        row("token estimación (25 mensaje, ejecución repetida de BPE pura)", noCache, withCache)

        // 3) Puntuación de la base de datos de experiencias:200 Copia en minúscula de la experiencia, recalculada o almacenada en caché (P13)
        val experiences = (0 until 200).map { index ->
            Triple("exp-$index", 1L, ("título de experiencia $index " + "El texto más extenso se utiliza para simular escenarios reales.".repeat(8)))
        }
        val expCache = ExperienceTextCache()
        val expCold = measureMillis(20) {
            expCache.clear()
            experiences.forEach { (id, updatedAt, text) ->
                val key = expCache.key(id, updatedAt)
                expCache.get(key) ?: expCache.put(key, text.lowercase())
            }
        }
        experiences.forEach { (id, updatedAt, text) -> expCache.put(expCache.key(id, updatedAt), text.lowercase()) }
        val expWarm = measureMillis(500) {
            experiences.forEach { (id, updatedAt, _) -> expCache.get(expCache.key(id, updatedAt)) }
        }
        row("Texto de puntuación de la base de datos de experiencias (200 minúscula)", expCold, expWarm)

        // 4) Codificación de datos de tarjetas: tabla completa frente a fragmentación de una sola tarjeta (P18)
        val cards = (0 until 50).associate { index ->
            "card-$index" to ("[" + "{\"k\":\"v\"},".repeat(400) + "{}]")
        }
        val cardJson = kotlinx.serialization.json.JsonObject(
            cards.map { (key, value) ->
                key to kotlinx.serialization.json.JsonPrimitive(value)
            }.toMap(),
        )
        val fullTable = measureMillis(20) {
            io.zer0.common.AppJson.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                cardJson,
            )
        }
        val oneShard = measureMillis(500) {
            io.zer0.muse.data.card.CardDataFiles.encode("card-7", cards.getValue("card-7"))
        }
        row("Codificación de ubicación de datos de la tarjeta (50 tarjeta)", fullTable, oneShard)

        // 5) Estimación de la barra de token por cuadro durante la transmisión (P21): Estimación de salida para un solo mensaje de 5 KB
        val streamed = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "El texto generado por el asistente contiene fragmentos del código fuente de la tarjeta.".repeat(180),
        )
        TokenEstimator.estimate(streamed.content)
        val perFrameEstimate = measureMillis(100) { TokenEstimator.estimate(listOf(streamed)) }
        row("Estimación del token de salida para un único mensaje de 5 KB (transmisión por cuadro)", perFrameEstimate, 0.0)

        probeLoggingCosts()



        // 7) en arranque en frio"Registro de herramientas"¿Qué tan caro es (esto es lo que se hace simultáneamente durante el período de inicio)?
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        io.zer0.muse.tools.ToolRegistry(ctx) // precalentar
        val registryOnly = measureMillis(20) { io.zer0.muse.tools.ToolRegistry(ctx) }
        val withOneRegistrar = measureMillis(20) {
            val r = io.zer0.muse.tools.ToolRegistry(ctx)
            io.zer0.muse.tools.CoreToolsRegistrar(ctx, r)
        }
        row("ToolRegistry Construir (registro incorporado)", registryOnly, 0.0)
        row("+1 registrador (CoreToolsRegistrar,3 herramientas)", withOneRegistrar - registryOnly, 0.0)

        // 8) Recuperación del historial de notificaciones realizada por el hilo principal durante el arranque en frío:SharedPreferences El costo de análisis del JSON aquí.
        val records = (0 until 200).map { index ->
            io.zer0.muse.notification.NotificationRecord(
                packageName = "com.example.app$index",
                title = "Título $index",
                text = "Se trata de un texto de notificación, de longitud cercana a una notificación real, que se utiliza para estimar los costos de análisis.".repeat(2),
                timestamp = 1_800_000_000_000L + index,
            )
        }
        val recordsJson = io.zer0.common.AppJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(io.zer0.muse.notification.NotificationRecord.serializer()),
            records,
        )
        val recordSerializer = kotlinx.serialization.builtins.ListSerializer(
            io.zer0.muse.notification.NotificationRecord.serializer(),
        )
        val restoreCost = measureMillis(30) {
            io.zer0.common.AppJson.decodeFromString(recordSerializer, recordsJson)
        }
        println(
            "[perf] Recuperación del historial de notificaciones del período de inicio (200 artículo,${recordsJson.length} Carácter JSON) análisis %8.3f ms".format(restoreCost),
        )

        probeResidualCosts()
        probePreSendOverflow()
        probeToolLoopTokens()

        println("[perf] ===== Nota: Lo anterior es la magnitud relativa de JVM; la máquina real requiere equipo adb que requiere mucho tiempo =====")
        assertTrue("Los microbenchmarks no hacen afirmaciones, solo se utilizan para imprimir", true)
    }
}
