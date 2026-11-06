package io.zer0.muse.asr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI Realtime ASR Prueba de contrato de protocolo GA del lado del cliente.
 *
 * Portada:
 *  - Las solicitudes de protocolo de enlace ya no se envían `OpenAI-Beta: realtime=v1`(Beta El canal esta desconectado,Usar este encabezado resultará en rechazo.);
 *  - `session.update` de `session.type` fijo a `transcription`(GA Requerido);
 *  - La configuración del lado de entrada toma la ruta anidada de GA `session.audio.input.*`,Beta Ya no aparecen las teclas planas;
 *  - `format.rate` igual a la tasa de muestreo de adquisición(16000),No es un valor de ejemplo de documentación;
 *  - modelo/palabras calientes/El idioma se conserva en la ruta anidada tal como está;turn_detection Escribe explícitamente nulo (apaga el VAD del servidor,
 *    Cambiar al compromiso manual del cliente),commit El mensaje sólo lleva tipo;
 *  - OPENAI_REALTIME El modelo predeterminado se actualizó a un modelo de transcripción en vivo.
 *
 * correr: ./gradlew :app:testDebugUnitTest --tests "*OpenAiRealtimeAsrProtocolTest*"
 */
class OpenAiRealtimeAsrProtocolTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val endpoint = "wss://api.openai.com/v1/realtime?intent=transcription"

    /** Beta Teclas planas de la época,GA No reconocido,payload No debe quedar nadie en él. */
    private val betaFlatSessionKeys = listOf(
        "input_audio_format",
        "input_audio_transcription",
        "input_audio_noise_reduction",
        "input_audio_noise_reduction_types",
    )

    /** `turn_detection` Solo en el nivel superior de la sesión está el método de escritura Beta.;Vaya a audio.input para encontrar la ubicación de GA. */
    private val betaSessionLevelOnlyKeys = betaFlatSessionKeys + "turn_detection"

    private fun session(
        model: String = "gpt-realtime-whisper",
        hotwords: List<String> = emptyList(),
        language: String? = null,
        sampleRate: Int = 16_000,
    ): JsonObject = buildRealtimeSessionUpdatePayload(
        model = model,
        hotwords = hotwords,
        language = language,
        sampleRate = sampleRate,
    )["session"]!!.jsonObject

    /** Obtenga objetos anidados capa por capa según la ruta,Volver si falta alguna capa null(Afirmar el error en lugar de lanzar NPE para que sea más fácil de leer). */
    private fun JsonObject.at(vararg path: String): JsonObject? {
        var cur: JsonObject = this
        for (key in path) {
            cur = cur[key] as? JsonObject ?: return null
        }
        return cur
    }

    private fun JsonObject.atValue(vararg path: String): JsonElement? {
        if (path.isEmpty()) return this
        val parent = at(*path.dropLast(1).toTypedArray()) ?: return null
        return parent[path.last()]
    }

    // ── estrechar la mano ──────────────────────────────────────────────────────────

    @Test
    fun `handshake request sends no OpenAI-Beta header`() {
        val request = buildRealtimeRequest(endpoint, "sk-test-key")

        assertNull("GA No se requiere encabezado de etiqueta Beta", request.header("OpenAI-Beta"))
        assertFalse(
            "Los encabezados de las etiquetas beta no deberían aparecer en ningún caso",
            request.headers.names().any { it.contains("beta", ignoreCase = true) },
        )
        assertEquals("Bearer sk-test-key", request.header("Authorization"))
    }

    @Test
    fun `handshake request keeps the GA realtime endpoint untouched`() {
        val request = buildRealtimeRequest(endpoint, "sk-test-key")
        val url = request.url

        // OkHttp HttpUrl regularizará el wss en https,Por lo tanto, se afirma párrafo por párrafo
        assertEquals("api.openai.com", url.host)
        assertEquals("/v1/realtime", url.encodedPath)
        assertEquals("transcription", url.queryParameter("intent"))
        // GA Punto final sin número de versión de fecha y parámetro de consulta de versión de API
        assertFalse(url.toString().contains("api-version"))
        assertFalse(url.encodedPath.contains("20"))
    }

    // ── session.update:forma general ─────────────────────────────────────────

    @Test
    fun `session update matches the GA nested shape exactly`() {
        val payload = buildRealtimeSessionUpdatePayload(
            model = "gpt-realtime-whisper",
            hotwords = listOf("Muse", "Ziqi"),
            language = "zh",
            sampleRate = 16_000,
        )
        val expected = json.parseToJsonElement(
            """
            {"type":"session.update","session":{"type":"transcription","audio":{"input":{
              "format":{"type":"audio/pcm","rate":16000},
              "transcription":{"model":"gpt-realtime-whisper","prompt":"Muse, Ziqi","language":"zh"},
              "turn_detection":null,
              "noise_reduction":null}}}}
            """.trimIndent(),
        )
        assertEquals("payload Debe caer completamente en el camino anidado de GA.(y turn_detection explícito null)", expected, payload)
    }

    @Test
    fun `session update declares transcription session type`() {
        val payload = buildRealtimeSessionUpdatePayload(
            model = "gpt-realtime-whisper",
            hotwords = emptyList(),
            language = null,
        )

        assertEquals("session.update", payload["type"]!!.jsonPrimitive.content)
        assertEquals(
            "GA Utilice session.type para distinguir los formularios de sesión,Si falta, será rechazado.",
            "transcription",
            payload["session"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    // ── session.update:Caminos anidados ─────────────────────────────────────────

    @Test
    fun `transcription model lives under session audio input transcription`() {
        val transcription = session(model = "gpt-realtime-whisper").at("audio", "input", "transcription")

        assertNotNull("La configuración de transcripción debe estar en session.audio.input.transcription", transcription)
        assertEquals(
            "gpt-realtime-whisper",
            transcription!!["model"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `input format declares pcm with the capture sample rate`() {
        val format = session().at("audio", "input", "format")

        assertNotNull("El formato de entrada debe estar en session.audio.input.format", format)
        assertEquals("audio/pcm", format!!["type"]!!.jsonPrimitive.content)
        assertEquals(
            "16000 Captura de parámetros desde AsrAudioCapture,No es un ejemplo de documento 24000",
            16_000,
            format["rate"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun `input format rate follows the configured capture sample rate`() {
        val format = session(sampleRate = 48_000).at("audio", "input", "format")

        assertEquals(
            "La tasa de muestreo declarada debe seguir la recolección real.,De lo contrario, el servidor lo interpreta según la longitud del marco de error. PCM",
            48_000,
            format!!["rate"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun `session update keeps model hotwords and language`() {
        val transcription = session(
            model = "gpt-realtime-whisper",
            hotwords = listOf("Muse", "Ziqi"),
            language = "zh",
        ).at("audio", "input", "transcription")

        assertEquals("gpt-realtime-whisper", transcription!!["model"]!!.jsonPrimitive.content)
        assertEquals("Muse, Ziqi", transcription["prompt"]!!.jsonPrimitive.content)
        assertEquals("zh", transcription["language"]!!.jsonPrimitive.content)
    }

    @Test
    fun `session update omits optional prompt and language when unset`() {
        val transcription = session(hotwords = emptyList(), language = null)
            .at("audio", "input", "transcription")

        assertNull(transcription!!["prompt"])
        assertNull(transcription["language"])
    }

    @Test
    fun `turn detection is explicitly disabled for manual commit`() {
        val input = session().at("audio", "input")

        assertNotNull("turn_detection la clave debe existir(Nulo explícito significa manual commit)", input!!["turn_detection"])
        assertEquals(
            "El VAD del lado del servidor debe estar apagado:El punto final de un período de entrada lo determina el usuario que hace clic en \"Finalizar\",No se puede reemplazar por la duración del silencio",
            JsonNull,
            input["turn_detection"],
        )
        assertNull("Los parámetros VAD no deben quedarse atrás", input["turn_detection"] as? JsonObject)
    }

    @Test
    fun `manual commit message is emitted on user finish`() {
        val commit = json.parseToJsonElement(buildRealtimeCommitMessage()).jsonObject

        assertEquals(
            "turn_detection=null tiempo,input_audio_buffer.commit Es la única señal que puede abrir una sección de entrada.",
            "input_audio_buffer.commit",
            commit["type"]!!.jsonPrimitive.content,
        )
        assertEquals("commit Los eventos solo toman type", 1, commit.size)
    }

    @Test
    fun `noise reduction is declared as explicit json null`() {
        val input = session().at("audio", "input")

        assertNotNull("noise_reduction la clave debe existir(Nulo explícito significa desactivado)", input!!["noise_reduction"])
        assertEquals(
            "Cuando la supresión de ruido no está habilitada, se debe escribir nulo explícitamente en lugar de omitirlo.",
            JsonNull,
            input["noise_reduction"],
        )
    }

    // ── session.update:No quedan claves planas Beta ─────────────────────────────

    @Test
    fun `session update has no beta flat keys`() {
        val session = session(model = "gpt-realtime-whisper", hotwords = listOf("Muse"), language = "zh")

        for (key in betaSessionLevelOnlyKeys) {
            assertNull("Beta llave plana $key No reconocido en GA,Debe ser eliminado del nivel superior de la sesión.", session[key])
        }
        for (key in betaFlatSessionKeys) {
            assertNull(
                "llave plana $key No debe estar oculto en session.audio.input",
                session.at("audio", "input")?.get(key),
            )
        }
        // El cliente de transcripción pura no envía la configuración del diálogo
        assertNull("No se debe configurar la transliteración pura session.audio.output", session.at("audio")?.get("output"))
        assertNull(session["instructions"])
        assertNull(session["voice"])
    }

    @Test
    fun `payload round trips through json encoder`() {
        val payload = buildRealtimeSessionUpdatePayload("gpt-realtime-whisper", emptyList(), null)
        val encoded = json.encodeToString(JsonObject.serializer(), payload)
        val decoded = json.parseToJsonElement(encoded).jsonObject

        assertEquals("session.update", decoded["type"]!!.jsonPrimitive.content)
        val session = decoded["session"]!!.jsonObject
        assertEquals("transcription", session["type"]!!.jsonPrimitive.content)
        assertEquals(
            "gpt-realtime-whisper",
            session.at("audio", "input", "transcription")!!["model"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "16000",
            (session.atValue("audio", "input", "format", "rate") as JsonPrimitive).content,
        )
        assertTrue(
            "Las claves planas beta no deben aparecer después de la codificación",
            betaSessionLevelOnlyKeys.none { session[it] != null },
        )
    }

    // ── Modelo predeterminado ────────────────────────────────────────────────────────

    @Test
    fun `openai realtime default model is gpt-realtime-whisper`() {
        assertEquals(
            "gpt-realtime-whisper",
            AsrConfig(provider = AsrProviderType.OPENAI_REALTIME).defaultModel(),
        )
    }
}
