package io.zer0.muse.data.miniphone

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.asr.OneShotAsrTranscriber
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.moment.MiniContextBuilder
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.transformer.stripThinkTags
import io.zer0.muse.ui.speech.VoiceMessageArchiver
import io.zer0.muse.ui.speech.TtsSynthResult
import io.zer0.muse.ui.speech.VoiceSynthesizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * Omitir el puerto de generación de texto para caracteres.
 *
 * [AppMiniPhoneHost] Sólo haga dos cosas: "Reunir materiales según los personajes" y "Limpiar la salida del modelo".
 * Una llamada LLM completa se transfiere a este puerto; ver la implementación de producción [MomentChainTextGenerator].
 * El propósito de extraer el puerto es inyectar una implementación falsa en una sola prueba sin enviar realmente una solicitud de red (similar a [VoiceSynthesizer] por el mismo motivo).
 */
fun interface MiniPhoneTextGenerator {

    /**
     * @param assistant Identidad del personaje (diseño de personajes/libro mundial/fuente de enlace de inyección rápida de palabras)
     * @param material El texto del material entregado al modelo.
     * @param maxTokens El límite superior del token generado esta vez.
     * @return Modelo de salida bruta (think La etiqueta aún no ha sido quitada); retornos en caso de fracaso null
     */
    suspend fun generate(assistant: AssistantEntity?, material: String, maxTokens: Int): String?
}

/**
 * [MiniPhoneTextGenerator] Implementación de host: utilice un enlace generado existente del pequeño teléfono celular.
 *
 * Internamente idéntico al enlace de generación de bypass. —— [MiniContextBuilder] Personalidad ensamblada
 * (Personalidad básica / libro mundial / memoria / retrato de usuario / inyección rápida de palabras / eliminación de etiquetas de pensamiento) y luego entréguelo
 * [ChatService.completeText] Obtenga la respuesta completa de inmediato. La generación de omisión no tiene historial de sesión, por lo que no se toca
 * `ChatViewModel` Transformador relacionado con el diálogo.
 *
 * Los parámetros de muestreo siguen al personaje: si el personaje está vinculado a un modelo, utilizará su propio proveedor y modelo. Temperature/topP toma la configuración de los caracteres.
 * Vuelva al modelo predeterminado cuando no esté vinculado o se haya eliminado el modelo vinculado.
 *
 * [chatService] Cuando está vacío (el modelo no está configurado), siempre devolverá nulo y no se generará ninguna excepción.
 */
class MomentChainTextGenerator(
    private val chatService: ChatService?,
    private val contextBuilder: MiniContextBuilder,
    private val settings: SettingsRepository,
) : MiniPhoneTextGenerator {

    override suspend fun generate(assistant: AssistantEntity?, material: String, maxTokens: Int): String? {
        val service = chatService ?: return null
        val messages = resultOf { contextBuilder.buildMessages(assistant = assistant, userContent = material) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo ensamblar el contexto del rol: ${t?.message ?: msg}") }
            .getOrNull()
            ?: return null
        val binding = resolveBinding(assistant)
        return resultOf {
            service.completeText(
                messages = messages,
                model = binding?.model,
                providerConfig = binding?.providerConfig,
                temperature = assistant?.temperature?.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
                    ?: DEFAULT_TEMPERATURE,
                topP = assistant?.topP?.coerceIn(MIN_TOP_P, MAX_TOP_P),
                maxTokens = maxTokens,
            ).text
        }.onError { msg, t ->
            Logger.w(TAG, "Error al omitir la generación de texto: ${t?.message ?: msg}")
        }.getOrNull()
    }

    /**
     * Los personajes se vinculan a modelos y proveedores.
     *
     * Atado `providerId` Simplemente golpea esa casa con precisión; solo atar `modelId` Primero mire al proveedor de activación y luego a todos los proveedores.
     * Si no se puede analizar (no se elimina ningún enlace/proveedor o modelo), se devuelve nulo y la persona que llama regresa al modelo predeterminado.
     */
    private suspend fun resolveBinding(assistant: AssistantEntity?): ModelBinding? {
        val modelId = assistant?.modelId?.takeIf { it.isNotBlank() } ?: return null
        val providerId = assistant.providerId?.takeIf { it.isNotBlank() }
        val providers = resultOf { settings.providersFlow.first() }
            .onError { msg, t -> Logger.w(TAG, "No se pudo leer la lista de proveedores: ${t?.message ?: msg}") }
            .getOrNull()
            .orEmpty()
        val model = findModel(providers, providerId, modelId)
        if (model == null) {
            Logger.w(TAG, "El modelo vinculado a caracteres no está disponible(provider=${providerId ?: "-"}, model=$modelId), recurrir al modelo predeterminado")
            return null
        }
        val provider = providers.firstOrNull { it.id == model.providerId }
            ?: providerId?.let { id -> providers.firstOrNull { it.id == id } }
        if (provider == null) {
            Logger.w(TAG, "El modelo vinculado a roles no tiene proveedor disponible(provider=${providerId ?: "-"}), recurrir al modelo predeterminado")
            return null
        }
        return ModelBinding(model = model, providerConfig = provider)
    }

    /** prensa providerId → Activar proveedor → Encuentra modelos en orden de todos los proveedores. */
    private suspend fun findModel(providers: List<ProviderConfig>, providerId: String?, modelId: String): Model? {
        if (providerId != null) {
            providers.firstOrNull { it.id == providerId }
                ?.models
                ?.firstOrNull { it.id == modelId }
                ?.let { return it }
        }
        val activeProviderId = resultOf { settings.activeProviderIdFlow.first() }.getOrNull()
        return providers.firstOrNull { it.id == activeProviderId }
            ?.models
            ?.firstOrNull { it.id == modelId }
            ?: providers.firstNotNullOfOrNull { provider -> provider.models.firstOrNull { it.id == modelId } }
    }

    private data class ModelBinding(val model: Model, val providerConfig: ProviderConfig)

    private companion object {
        private const val TAG = "MomentChainTextGenerator"
        private const val DEFAULT_TEMPERATURE = 0.8f
        private const val MIN_TEMPERATURE = 0f
        private const val MAX_TEMPERATURE = 2f
        private const val MIN_TOP_P = 0f
        private const val MAX_TOP_P = 1f
    }
}

/**
 * Los pasos internos de una generación de texto: encontrar caracteres, ensamblar materiales, ajustar el puerto de generación y limpiar la salida.
 *
 * Estos miembros fueron originalmente dispuestos en la [AppMiniPhoneHost] Aquí, el anfitrión está tan lleno de métodos de interfaz, voz y mapeo que la línea principal no se puede ver con claridad;
 * Después de eliminarlo, el host solo retiene los métodos y delegados de la interfaz, y aquí solo nos preocupamos por ellos."generar un texto"Esta cadena no tiene un estado de escritura.
 *
 * Los métodos de clase generados nunca arrojan excepciones: el error se devuelve a nulo y el host decide lanzar una excepción. [MpReplyEvent.Failed] Todavía regresa vacío.
 */
internal class MiniPhoneTextComposer(
    private val assistantRepository: AssistantRepository,
    private val textGenerator: MiniPhoneTextGenerator,
    private val contextBuilder: MiniContextBuilder,
) {

    /** Lea el papel; si no se puede leer (no existe/excepción), se devolverá nulo. */
    suspend fun findAssistant(characterId: String): AssistantEntity? =
        resultOf { assistantRepository.getById(characterId) }
            .onError { msg, t -> Logger.w(TAG, "No se pudo leer el rol: id=$characterId, ${t?.message ?: msg}") }
            .getOrNull()

    /** Materiales dinámicos: tono dinámico, texto único, sin imágenes. */
    suspend fun composeMoment(assistant: AssistantEntity, hint: String?): String? =
        generate(assistant, momentMaterial(hint), MAX_TOKENS_MOMENT)?.let(::cleanOutput)

    /** Material de mensaje privado activo: la misma personalidad de personaje, el mismo enlace generacional, pero cambia la tarea a \"solo di una palabra\". */
    suspend fun composeProactiveMessage(assistant: AssistantEntity, hint: String?): String? =
        generate(assistant, proactiveChatMaterial(hint), MAX_TOKENS_PROACTIVE_CHAT)?.let(::cleanOutput)

    /** Material de mensaje privado: concatene el diálogo entregado por el módulo en \"Mensajes del sistema de personalidad + Rondas recientes\". */
    suspend fun buildConversation(assistant: AssistantEntity, history: List<MpChatTurn>): List<UIMessage> {
        // El sistema indica que esto no es lo que dijo el personaje.,fotos/La línea de voz no tiene texto —— Ninguno entra en el contexto.
        val turns = history
            .filter { it.sender != MpActorType.SYSTEM && it.content.isNotBlank() }
            .takeLast(MAX_HISTORY_TURNS)
        val lastUser = turns.lastOrNull { it.sender == MpActorType.USER } ?: return emptyList()
        // Reutilizar la misma cadena de ensamblaje de personalidad.:Libro mundial/Inyección rápida de palabras: obtenga palabras clave basadas en el último mensaje del usuario;
        // La mensajería privada es la única forma de tener una salida de voz,Por cierto, agregue la convención de marcas de voz al comando del sistema.
        val built = resultOf {
            contextBuilder.buildMessages(
                assistant = assistant,
                userContent = lastUser.content,
                voiceMessageEnabled = true,
            )
        }
            .onError { msg, t -> Logger.w(TAG, "No se pudo ensamblar el contexto del rol: ${t?.message ?: msg}") }
            .getOrNull()
            ?: return emptyList()
        return built.takeWhile { it.role == MessageRole.SYSTEM } + turns.map { it.toUIMessage() }
    }

    /** Quite las etiquetas de pensamiento, las etiquetas del sistema y las citas envolventes;Si está vacío después de la extracción, se considerará que no hay resultado. */
    fun cleanOutput(raw: String): String? =
        stripSystemMarks(stripThinkTags(raw)).trim()
            .removeSurrounding("\"")
            .trim()
            .takeIf { it.isNotBlank() }

    /**
     * Quite las etiquetas del sistema agregadas por el propio modelo:MOOD línea completa,`[mood]…[/mood]` / `<mood>…</mood>` sección,
     * final de línea `(Estado de ánimo:…)` Este tipo de cola de soporte. Eliminar solo etiquetas claras,No muevas el texto.
     */
    private fun stripSystemMarks(text: String): String {
        if (text.isBlank()) return text
        var result = text
        if (MOOD_BLOCK.containsMatchIn(result)) {
            result = MOOD_BLOCK.replace(result, "")
        }
        if (MOOD_LINE.containsMatchIn(result)) {
            result = result.lineSequence()
                .filterNot { line -> MOOD_LINE.matches(line.trim()) }
                .joinToString("\n")
        }
        if (MOOD_TAIL.containsMatchIn(result)) {
            result = MOOD_TAIL.replace(result, "")
        }
        return result
    }

    /** tiempo de espera ([GENERATION_TIMEOUT_MS]) y el error se devuelven a nulo y no se envían a la persona que llama. */
    private suspend fun generate(assistant: AssistantEntity, material: String, maxTokens: Int): String? {
        // La cantidad real es la menor entre "maxTokens de rol" y "límite superior del escenario":No importa cuánto desempeñes el papel, no debes poner cientos en una oración. token
        val budget = assistant.maxTokens?.takeIf { it > 0 }?.let { minOf(it, maxTokens) } ?: maxTokens
        return resultOf {
            withTimeoutOrNull(GENERATION_TIMEOUT_MS) { textGenerator.generate(assistant, material, budget) }
        }.onError { msg, t ->
            Logger.w(TAG, "Falló la generación de texto de caracteres: ${t?.message ?: msg}")
        }.getOrNull()
    }

    private fun MpChatTurn.toUIMessage(): UIMessage = UIMessage(
        role = if (sender == MpActorType.USER) MessageRole.USER else MessageRole.ASSISTANT,
        content = content,
    )

    /** Materiales dinámicos: tono dinámico, texto único, sin imágenes. */
    private fun momentMaterial(hint: String?): String = buildString {
        appendLine("<moment_task>")
        appendLine("Por favor, publica una publicación en Moments como tú mismo.")
        appendHint(hint)
        appendLine("</moment_task>")
        appendLine()
        appendLine("<style>")
        appendLine("- Hablado con naturalidad,30-80 Palabras, como personas reales compartiendo sus vidas.")
        appendLine("- Esta no es una respuesta para nadie, es una actualización publicada por usted mismo.")
        appendLine("- No muestre MOOD, valor de opinión, preferencia, barra de estado, etiqueta del sistema, acción entre corchetes, narración, marca de tiempo, comillas,#tema.")
        appendLine("- Sin charlas de servicio al cliente ni presentación personal de la IA.")
        appendLine("- Imprima únicamente el texto en sí, sin prefijos, explicaciones ni ilustraciones.")
        appendLine("</style>")
    }

    /** Materiales activos de mensajes privados.:La misma personalidad de carácter, el mismo vínculo generacional.,Simplemente cambie la tarea a \"solo diga una palabra\". */
    private fun proactiveChatMaterial(hint: String?): String = buildString {
        appendLine("<chat_task>")
        appendLine("Tome la iniciativa de enviar un mensaje privado al usuario.")
        appendHint(hint)
        appendLine("</chat_task>")
        appendLine()
        appendLine("<style>")
        appendLine("- Habla directamente con el usuario en la primera persona del personaje, una o dos frases.")
        appendLine("- Esto no es una respuesta a nadie, es algo que usted tomó la iniciativa de decir.")
        appendLine("- No muestre MOOD, valor de opinión, preferencia, barra de estado, etiqueta del sistema, acción entre corchetes, narración, marca de tiempo, comillas,#tema.")
        appendLine("- Sin rebajas, sin acento en el servicio al cliente.")
        appendLine("- Sólo se emite el texto en sí.")
        appendLine("</style>")
    }

    /** Los materiales recopilados a partir de los módulos están conectados tal como están: el contexto es lo primero y la última oración es lo que se escribirá en este artículo. */
    private fun StringBuilder.appendHint(hint: String?) {
        hint?.takeIf { it.isNotBlank() }?.lineSequence()?.forEach { line -> appendLine(line) }
    }

    private companion object {
        private const val TAG = "MiniPhoneTextComposer"

        /** El límite superior de la generación de una oración: la falla se manejará cuando se agote el tiempo de espera (la generación de omisión no debe retrasar la interfaz). */
        private const val GENERATION_TIMEOUT_MS = 15_000L

        private const val MAX_TOKENS_MOMENT = 200

        /** Un mensaje privado proactivo solo requiere una o dos frases:El límite es pequeño.,Evite escribir una carta completa. */
        private const val MAX_TOKENS_PROACTIVE_CHAT = 120

        /** Los mensajes privados solo traen las rondas más recientes.:El contenido anterior no es útil para una respuesta de una sola frase.,Sólo se comerá el presupuesto contextual. */
        private const val MAX_HISTORY_TURNS = 20

        /** MOOD toda la línea comenzando con:El modelo escribe la barra de estado como parte del texto. */
        private val MOOD_LINE = Regex("""^\s*MOOD\s*[:：].*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

        /** `[mood]…[/mood]` con `<mood>…</mood>` Segmentos envueltos. */
        private val MOOD_BLOCK = Regex("""[\[<]\s*mood\s*[\]>][\s\S]*?[\[<]\s*/\s*mood\s*[\]>]""", RegexOption.IGNORE_CASE)

        /** final de línea/\"(Estado de ánimo:…)\" cola del soporte. */
        private val MOOD_TAIL = Regex("""[((]\s*estado de ánimo\s*[::][^))\n]*[))]""")
    }
}

/**
 * Implementación en el lado del host de teléfonos celulares pequeños: comunicación y adquisición de asistentes existentes, adquisición de perfiles de usuario de retratos de usuarios en la configuración,
 * Generar y caminar [MiniPhoneTextGenerator], la voz utiliza la síntesis TTS y el archivo de voz existentes del anfitrión.
 *
 * frontera (con [MiniPhoneHost] acuerdo):
 * - Lea solo los datos del host y no escriba en ninguna tabla;
 * - Generar método de clase sin lanzar excepción: enviar mensaje privado [MiniPhoneToolTurnRunner], no se pudo enviar [MpReplyEvent.Failed];
 * - La voz es el único producto que se comercializará y sólo podrá comercializarse [VoiceMessageArchiver] Escriba en el directorio de archivos de voz.
 *
 * Los pasos específicos de la generación de texto están en [MiniPhoneTextComposer] Aquí, esta clase solo conserva la implementación de la interfaz, la voz y el mapeo.
 *
 * @param cacheDir TTS El directorio de inicio de archivos temporales (debe eliminarse una vez completada la síntesis)
 */
class AppMiniPhoneHost(
    private val assistantRepository: AssistantRepository,
    private val settingsRepository: SettingsRepository,
    textGenerator: MiniPhoneTextGenerator,
    contextBuilder: MiniContextBuilder,
    private val toolTurnRunner: MiniPhoneToolTurnRunner,
    private val voiceSynthesizer: VoiceSynthesizer,
    private val voiceStore: VoiceMessageArchiver,
    private val cacheDir: File,
) : MiniPhoneHost {

    private val textComposer = MiniPhoneTextComposer(
        assistantRepository = assistantRepository,
        textGenerator = textGenerator,
        contextBuilder = contextBuilder,
    )

    /** Transcripción única de grabaciones de usuarios.:Comparte la misma configuración de voz que el reconocimiento de streaming,No compartas el enlace de la colección. */
    private val voiceTranscriber = OneShotAsrTranscriber(settingsRepository)

    override fun observeCharacters(): Flow<List<MpCharacter>> =
        assistantRepository.observeAll
            .map { assistants -> assistants.map { it.toMpCharacter() } }
            .catch { t ->
                Logger.w(TAG, "No se pudo leer la lista de funciones, tratada como libreta de direcciones vacía: ${t.message}")
                emit(emptyList())
            }

    override fun observeProfile(): Flow<MpProfile> =
        settingsRepository.userProfileFlow
            .map { it.toMpProfile() }
            .catch { t ->
                Logger.w(TAG, "No se pudieron leer los datos del usuario, tratados como datos vacíos: ${t.message}")
                emit(MpProfile(nickname = ""))
            }

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flow {
        val assistant = textComposer.findAssistant(characterId)
        val messages = assistant?.let { textComposer.buildConversation(it, history) }
        if (assistant == null || messages.isNullOrEmpty()) {
            emit(MpReplyEvent.Failed)
            return@flow
        }
        toolTurnRunner.reply(
            messages = messages,
            temperature = assistant.temperature,
            maxTokens = assistant.maxTokens,
        ).collect { event ->
            if (event !is MpReplyEvent.Text) {
                emit(event)
                return@collect
            }
            // Es necesario dividir el texto.:Sintetizar discurso para párrafos dentro de etiquetas,El resto se enviará como mensaje de texto como de costumbre.;Después de despegarlo, está vacío y significa "esta frase no se ha pronunciado".
            val script = textComposer.cleanOutput(event.content)
            if (script == null) emit(MpReplyEvent.Failed) else emitVoiceScript(script) { synthesizeVoice(it, null) }
        }
    }

    override suspend fun composeMoment(characterId: String, hint: String?): String? {
        val assistant = textComposer.findAssistant(characterId) ?: return null
        return textComposer.composeMoment(assistant, hint)
    }

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? {
        val assistant = textComposer.findAssistant(characterId) ?: return null
        return textComposer.composeProactiveMessage(assistant, hint)
    }

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? {
        if (text.isBlank()) return null
        val tempFile = File(cacheDir, "$VOICE_TEMP_PREFIX${UUID.randomUUID()}$VOICE_TEMP_EXTENSION")
        return try {
            // Cancelar lanzamientos tal cual (resultOf CancellationException se vuelve a generar internamente) y otras excepciones se normalizan null
            resultOf { synthesizeToArchive(text, emotion, tempFile) }
                .onError { msg, t -> Logger.w(TAG, "Proceso anormal de síntesis del habla.: ${t?.message ?: msg}") }
                .getOrNull()
        } finally {
            // éxito/falló/Cancele la eliminación de archivos temporales en las tres rutas (el archivo es una copia independiente)
            tempFile.delete()
        }
    }

    /**
     * Un discurso grabado por el usuario se convierte en texto.
     *
     * Los resultados solo están disponibles si el usuario ha sido configurado para reconocimiento de voz (completar manualmente o seguir al proveedor);Leer configuraciones, analizar proveedores,
     * Envíe solicitudes y analice respuestas, todo en un solo lugar [OneShotAsrTranscriber] adentro,Aquí solo se realiza el reenvío,No permita que el módulo vea ningún detalle del protocolo.
     */
    override suspend fun transcribeVoice(wavBytes: ByteArray): String? =
        voiceTranscriber.transcribe(wavBytes)

    /**
     * ¿Se puede utilizar la entrada de grabación?:El criterio es exactamente el mismo que si el transcriptor realmente enviará una solicitud.
     *
     * Tanto la anotación como el análisis se reutilizan. [OneShotAsrTranscriber]:No reescribas un conjunto de condiciones aquí,
     * De lo contrario, tarde o temprano el "apoyo" y la "realidad capaz" de ambas partes se descarriarán.
     */
    override suspend fun supportsVoiceTranscription(): Boolean = voiceTranscriber.supportsTranscription()

    /** Nombre de la versión del host:Tomado de la información de compilación de la propia aplicación.,No lo escribas hasta la muerte aquí. */
    override fun appVersionName(): String = BuildConfig.VERSION_NAME

    /** síntesis → Realizar el pedido → Duración de la detección. Devuelve nulo si falla algún paso. */
    private suspend fun synthesizeToArchive(text: String, emotion: String?, tempFile: File): Pair<String, Long>? {
        val outcome = resultOf { voiceSynthesizer.synthesizeTo(text, tempFile, emotion) }
            .onError { msg, t -> Logger.w(TAG, "La síntesis de voz falló: ${t?.message ?: msg}") }
            .getOrNull()
        // El sonido del sistema de respaldo también se sintetiza (el audio está disponible). Solo ríndete si falla por completo.
        if (outcome == null || outcome is TtsSynthResult.Failure) return null
        // key Estable: el mismo texto y la misma emoción se sintetizan repetidamente y se guardan en el mismo archivo.
        val uri = voiceStore.persist(tempFile, text + VOICE_KEY_SEPARATOR + (emotion ?: "")) ?: return null
        // La duración solo se utiliza para la visualización de la interfaz y no se puede detectar (0) también regresa como de costumbre
        return uri to voiceStore.probeDurationMs(tempFile)
    }

    private fun AssistantEntity.toMpCharacter(): MpCharacter = MpCharacter(
        id = id,
        name = name,
        avatarUri = avatarImageUrl.takeIf { it.isNotBlank() },
        tagline = taglineOf(),
    )

    /** Diseño de personajes de una oración: se da prioridad a la primera línea de la definición de personalidad, seguida de la primera línea de todo el párrafo del diseño de personajes; si no se incluye ninguno, la cadena está vacía. */
    private fun AssistantEntity.taglineOf(): String {
        val source = personaDefinition.ifBlank { systemPrompt }
        return source.lineSequence()
            .map { it.trim().trimStart('#', '-', '*', '>').trim() }
            .firstOrNull { it.isNotBlank() }
            ?.take(TAGLINE_MAX_CHARS)
            ?: ""
    }

    private fun UserProfile.toMpProfile(): MpProfile = MpProfile(
        nickname = userNickName?.trim().orEmpty(),
        avatarUri = avatarUri?.takeIf { it.isNotBlank() },
        signature = bio?.trim()?.takeIf { it.isNotBlank() },
        // Los fondos de pantalla y las portadas dinámicas los mantiene el propio teléfono celular y no los proporciona el host.
        wallpaperUri = "",
        coverImageUri = "",
    )

    private companion object {
        private const val TAG = "AppMiniPhoneHost"

        private const val TAGLINE_MAX_CHARS = 60

        /** Nombres de archivos temporales y extensiones coherentes con los mensajes de voz existentes. */
        private const val VOICE_TEMP_PREFIX = "miniphone-voice-"
        private const val VOICE_TEMP_EXTENSION = ".wav"

        /** Delimitador de clave de archivo de voz: y `send_voice` El isomorfismo asegura que el mismo texto y la misma emoción lleguen al mismo audio. */
        private const val VOICE_KEY_SEPARATOR = "\u0000"
    }
}
