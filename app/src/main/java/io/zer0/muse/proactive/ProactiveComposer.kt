package io.zer0.muse.proactive

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.proactive.ThoughtUrgency

/**
 * Generación de contenidos para mensajes proactivos.
 *
 * Llamó una vez y se le permitió darse por vencido.
 *
 * El material es**Las últimas 20 rondas de conversaciones originales.**, no una "lista guardada de preocupaciones". Esta distinción es clave:
 * Elija de la lista, escogiendo siempre el elemento con mayor peso, así se mencionará lo mismo una y otra vez;
 * Continúe con la conversación y lo que retome siempre será el tema del que acaba de hablar y que aún está candente.
 *
 * Las inquietudes se seguirán dando como pistas complementarias (cumpleaños, cosas prometidas, etc. con momentos específicos),
 * Pero ya no es un requisito previo para abrir la boca. —— Aún puedes contestar la llamada sin preocuparte por ello.
 *
 * `skip: true` si**legal y alentado**producción. Esto debe explicarse en términos contundentes en el mensaje.
 * De lo contrario, el modelo sentirá que "el sistema me ha pedido que tome la iniciativa y sería negligente no hablar", por lo que inventaré una frase.
 * "¿Cómo estuvo su día?"—— Eso es lo que este diseño pretende eliminar.
 */
class ProactiveComposer(
    private val chatService: ChatService,
    /**
     * Ensamblador de avisos del sistema compartido con el chat normal.
     *
     * Los mensajes no solicitados son los que más requieren este contexto: se pronuncian sin que el usuario hable,
     * No hay ningún mensaje del usuario que ayude a "colocar" la personalidad en su lugar, todo está respaldado por indicaciones del sistema.
     */
    private val promptAssembler: io.zer0.muse.transformer.SystemPromptAssembler,
) {

    companion object {
        private const val TAG = "ProactiveComposer"

        /** Límite superior del token de texto. Lo suficiente para escribir un párrafo, pero no lo suficiente para comenzar una historia larga. */
        private const val MAX_TOKENS = 400

        /** Suma el número de pistas. No tiene sentido dar algunas entradas más, el libro de contabilidad es sólo un acompañamiento. */
        private const val MAX_CANDIDATES = 3

        /** Varios mensajes activos históricos se enumeran en el mensaje antiduplicación. */
        private const val MAX_RECENT_PROACTIVE = 4

        /**
         * La longitud del truncamiento de un único mensaje histórico.
         *
         * Originalmente, se redujo a 300 palabras, y una confesión más larga se cortaría en media oración, y las palabras siguientes fácilmente responderían a la pregunta equivocada.
         * 600 Las palabras pueden dar cabida a una expresión emocional completa sin dejar que 40 mensajes revienten la ventana.
         */
        private const val MESSAGE_CLIP = 600
    }

    /** Genere todas las entradas necesarias a la vez. */
    data class Input(
        val assistant: AssistantEntity,
        /** La identificación de la sesión actual, utilizada para excluirse de las conexiones entre sesiones en los mensajes del sistema. */
        val sessionId: String? = null,
        /** Pistas adicionales. puede estar vacio —— Puedes contestar la llamada sin preocuparte por ello. */
        val candidates: List<PendingThoughtEntity> = emptyList(),
        val recentMessages: List<UIMessage>,
        /** El texto de varios mensajes recientes no solicitados se utiliza como recordatorio de que \"esto ya se ha dicho antes\". */
        val recentProactiveMessages: List<String> = emptyList(),
        val now: Long,
        /**
         * El nivel activo seleccionado por el usuario.
         *
         * Determina el calibre de la apertura: "Tranquilo" sólo habla de cosas específicas, "Ocasionalmente pensado" puede responder a la conversación pero no toma la iniciativa de compartir.
         * No existen restricciones adicionales para estándares y superiores. Las pistas de los candidatos se filtran en [ProactiveCycleRunner] Hazlo por dentro,
         * Aquí solo soy responsable de escribir el calibre en la palabra clave. —— Ambas partes deben usar la misma enumeración y no pueden escribir un conjunto de juicios cada una.
         */
        val intensity: ProactiveIntensity = ProactiveIntensity.DEFAULT,
    )

    suspend fun compose(
        input: Input,
        model: io.zer0.ai.core.Model?,
        providerConfig: io.zer0.ai.core.ProviderConfig?,
        temperature: Float,
    ): ProactiveReplyParser.Result {
        // Se puede decir que no hay candidatos. —— El material es la conversación misma, no el libro de contabilidad.
        return callModel(input, model, providerConfig, temperature).fold(
            onSuccess = { ProactiveReplyParser.parse(it) },
            onFailure = { e ->
                Logger.w(TAG, "Error en la llamada de generación de mensajes activos: ${e.message}")
                ProactiveReplyParser.Result.Failed(e.message ?: "llamada fallida")
            },
        )
    }

    /** Haga la solicitud real. El valor de retorno se incluye como Resultado, lo que permite a la persona que llama decidir cómo calcular el error. */
    private suspend fun callModel(
        input: Input,
        model: io.zer0.ai.core.Model?,
        providerConfig: io.zer0.ai.core.ProviderConfig?,
        temperature: Float,
    ): Result<String> = runCatching {
        chatService.completeText(
            messages = buildPrompt(input),
            model = model,
            providerConfig = providerConfig,
            temperature = temperature.coerceIn(0f, 2f),
            maxTokens = MAX_TOKENS,
        ).text
    }

    // ══════════════════════════════════════════════════════════════
    // Construcción inmediata
    // ══════════════════════════════════════════════════════════════

    internal suspend fun buildPrompt(input: Input): List<UIMessage> {
        val system = UIMessage(role = MessageRole.SYSTEM, content = buildSystemPrompt(input))
        val user = UIMessage(role = MessageRole.USER, content = buildContext(input))
        return listOf(system, user)
    }

    private suspend fun buildSystemPrompt(input: Input): String {
        val full = runCatching {
            promptAssembler.build(
                assistant = input.assistant,
                memoryEnabled = input.assistant.memoryEnabled,
                timeReminderEnabled = input.assistant.enableTimeReminder,
                sessionId = input.sessionId,
            ).firstOrNull()?.content.orEmpty()
        }.getOrDefault("")
        // Cuando el ensamblaje falla, se devuelve la personalidad mínima. El mensaje activo no puede quedarse sin palabras sólo porque el sistema indique que hay un problema.
        val persona = full.ifBlank { fallbackPersona(input.assistant) }
        return buildString {
            appendLine(persona)
            appendLine()
            appendLine("---")
            appendLine()
            append(proactiveInstruction(input.intensity))
        }
    }

    /** La personalidad secreta del ensamblador cuando no puede conseguir el contenido. */
    private fun fallbackPersona(assistant: AssistantEntity): String = buildString {
        appendLine("tu eres \"${assistant.name.ifBlank { "muse" }}”.")
        if (assistant.personaDefinition.isNotBlank()) {
            appendLine("【Definición de personalidad】")
            appendLine(assistant.personaDefinition)
        }
        if (assistant.systemPrompt.isNotBlank()) {
            appendLine("[Personalidad y Código de Conducta]")
            appendLine(assistant.systemPrompt)
        }
    }

    /**
     * Se dan diferentes "¿Qué puedo decir esta vez?" según el nivel de iniciativa.
     *
     * Los tres niveles no son la frecuencia, sino tres motivos diferentes para abrir la puerta.
     * Si el modo "silencioso" todavía le da la instrucción "recibir de conversaciones recientes", el usuario seguirá recibiendo saludos si selecciona silencio. ——
     * Las reglas en la palabra clave son más decisivas que la enumeración en el campo, por lo que este párrafo debe reemplazarse con engranajes, no complementarse.
     */
    private fun intensityGuidance(intensity: ProactiveIntensity): String = when (intensity) {
        ProactiveIntensity.QUIET -> buildString {
            appendLine("[Solo di algo esta vez]")
            appendLine("Ahora estás programado para estar \"callado\": hablar sólo cuando surjan asuntos específicos.")
            appendLine("Si hay algo en \"Otras cosas en las que todavía estás pensando\", simplemente menciónalo; si no hay algo, simplemente sáltelo.")
            appendLine("No hables de trivialidades, no compartas tus propias experiencias, no menciones los nodos entre ustedes.")
            appendLine()
        }

        ProactiveIntensity.RESERVED -> buildString {
            append(findTopicGuidance())
            appendLine("[Solo di algo esta vez]")
            appendLine("Ahora está configurado para \"pensar ocasionalmente\": puede continuar con lo que ha hablado y también puede preocuparse por su estado.")
            appendLine("No tomes la iniciativa de compartir tus propios asuntos y no tomes represalias contra los nodos entre ustedes.")
            appendLine()
        }

        ProactiveIntensity.STANDARD,
        ProactiveIntensity.ACTIVE,
        ProactiveIntensity.CLINGY,
        -> findTopicGuidance()
    }

    /** Busque orientación en conversaciones recientes. Úselo sólo si el modo permite \"recibir llamadas\". */
    private fun findTopicGuidance(): String = buildString {
        appendLine("[Cómo encontrar algo de qué hablar]")
        appendLine("Los \"chats recientes\" a continuación son sus conversaciones reales, ordenadas en orden cronológico. Necesitas encontrar una oración aquí que pueda conectarse:")
        appendLine()
        appendLine("1. Veamos primero las últimas frases. ¿Qué fue lo último que dijo?")
        appendLine("2. Si menciona algo pero no dice el resultado (\"Fui a comer\" o \"Tengo una reunión más tarde\"), pregúntale cómo te fue.")
        appendLine("   Por ejemplo, cuando dice que va a comer, quiere decir \"¿has terminado de comer?\". y \"¿qué comiste?\"——Este es el enfoque más natural.")
        appendLine("3. Si fuiste tú quien dijo lo último y él no respondió, depende de si hay más en tu frase; si no, no continúes.")
        appendLine("4. Si la conversación reciente se completó y aclaró, entonces no hables de ello. Esto es normal.")
        appendLine()
    }

    /**
     * Restricciones adicionales para esta ronda de mensajes activos.
     *
     * El gran párrafo anterior es una personalidad general que también se utiliza en el chat. Aquí sólo agrego las cosas únicas de la escena de "tomar la iniciativa de hablar".
     */
    private fun proactiveInstruction(intensity: ProactiveIntensity): String = buildString {
        appendLine("Situación actual: hace tiempo que no hablas. Quieres continuar con lo que dijiste la última vez.")
        appendLine("No te hace preguntas ni espera tu respuesta.——Pero tienes algo que decir o simplemente quieres saber qué le pasó.")
        appendLine()
        append(intensityGuidance(intensity))
        appendLine("[Lo más importante]")
        appendLine("**No arregles viejas cuentas.** La gente real no recuerda una cosa y de repente envía un mensaje al respecto dos o tres días después.")
        appendLine("Lo que digas debe estar directamente relacionado con \"lo que hablaste recientemente\" y no debe abarcar varios días entre medio.")
        appendLine("Si no has hablado de nada en absoluto en los últimos días, preferirías no publicarlo.")
        appendLine()
        appendLine("【No】")
        appendLine("- Saludos vacantes (\"¿Cómo estás hoy?\" \"¿Estás ocupado?\")")
        appendLine("- Repita lo que ha dicho recientemente o reformúlelo.")
        appendLine("- Demuestra que tienes buena memoria repitiendo la información que recuerdas")
        appendLine("- Finge que estás aquí para brindar un servicio (\"¿Cómo puedo ayudarte?\")")
        appendLine("- Inventa cosas que no sucedieron entre ustedes.")
        appendLine()
        appendLine("[Es completamente normal darse por vencido]")
        appendLine("No todos los días un verdadero amigo tiene algo que decir. Es mucho peor decir algo que no decir nada.")
        appendLine("Si no se te ocurre nada que puedas aprender, omítelo.")
        appendLine()
        appendLine("[Formato de salida] Solo devuelve JSON, sin bloques de código de rebajas, sin explicación:")
        appendLine("""{"skip": true, "reason": "¿Por qué no hay nada que recoger?"}""")
        appendLine("O:")
        appendLine(
            """{"skip": false, "content": "lo que quieres decir", "tone": "cuidar|Compartir|mencionado casualmente", "thought_id": ""}""",
        )
        appendLine()
        appendLine("thought_id Cómo completar:")
        appendLine("Solo cuando su oración hable de una de las \"otras cosas en las que todavía está pensando\", copie la identificación de ese elemento tal como está.")
        appendLine("En otros casos, deje la cadena vacía y no cree una identificación usted mismo.")
        appendLine()
        appendLine("content Requisitos: tan natural como enviar un mensaje de WeChat, principalmente de una a tres oraciones, sin párrafos ni listas.")
        appendLine("No utilice clichés de servicio al cliente como \"recordatorio\", \"calidez\" y \"querido\". No se llame a sí mismo \"asistente\".")
    }

    private fun buildContext(input: Input): String = buildString {
        appendLine("## De lo que hablamos recientemente")
        if (input.recentMessages.isEmpty()) {
            appendLine("(Aún no has hablado de nada. Por lo general, este no es el momento adecuado para iniciar la conversación).")
        } else {
            input.recentMessages.forEach { msg ->
                val who = if (msg.role == MessageRole.USER) "el" else "tu"
                appendLine("[$who] ${msg.content.take(MESSAGE_CLIP)}")
            }
        }
        appendLine()
        if (input.recentProactiveMessages.isNotEmpty()) {
            appendLine("## Lo que dijiste recientemente (no lo vuelvas a decir ni lo reformules)")
            input.recentProactiveMessages.take(MAX_RECENT_PROACTIVE).forEach { text ->
                appendLine("- ${text.take(MESSAGE_CLIP)}")
            }
            appendLine()
        }
        if (input.candidates.isNotEmpty()) {
            appendLine("## Cualquier otra cosa en la que estés pensando (úsala si puedes captarla de forma natural, no la menciones si no puedes captarla)")
            input.candidates.take(MAX_CANDIDATES).forEach { thought ->
                // id Debe estar expuesto al modelo: se utilizará para rellenar el think_id una vez finalizado el mensaje.
                // Sólo en el libro mayor se puede registrar que "este asunto ha sido dicho". Si no se revela la identificación, el relleno siempre estará vacío.
                // surfaceCount Siempre 0, "darse por vencido automáticamente si repites lo mismo una y otra vez" nunca se activará.
                appendLine("- ${describeKind(thought)}|${describeAge(thought, input.now)}|id=${thought.id}")
                appendLine("  ${thought.seed}")
            }
            appendLine()
        }
        appendLine(
            if (input.intensity.onlyWhenNeeded) {
                "Si hay algo específico, dígalo. Si no, omítelo."
            } else {
                "Luego continúa con \"lo que hablamos recientemente\" o salta."
            },
        )
    }

    /** La descripción china de la naturaleza de la preocupación se utiliza para que el modelo entienda \"qué tipo de palabras son estas\". */
    private fun describeKind(thought: PendingThoughtEntity): String = when (thought.kindEnum()) {
        ThoughtKind.REMINDER -> "Algo que decir (con un momento específico)"
        ThoughtKind.FOLLOW_UP -> "Temas inacabados"
        ThoughtKind.CARE -> "sobre su estado"
        ThoughtKind.SHARE -> "algo que quieras compartir"
        ThoughtKind.MILESTONE -> "El nodo entre ustedes"
    }

    /** sentido del tiempo. El modelo necesita saber si \"simplemente sucedió\" o \"han pasado semanas\". */
    private fun describeAge(thought: PendingThoughtEntity, now: Long): String {
        val hours = (now - thought.createdAt).coerceAtLeast(0L) / 3_600_000L
        val age = when {
            hours < 1 -> "Acabo de recordar"
            hours < 24 -> "${hours}Recordado hace horas"
            else -> "${hours / 24}Piénsalo hace días"
        }
        val urgency = when (thought.urgencyEnum()) {
            ThoughtUrgency.IMMEDIATE -> "El límite de tiempo es muy ajustado."
            ThoughtUrgency.WINDOW -> "Puedes decirlo estos días."
            ThoughtUrgency.EVERGREEN -> "Puedes decirlo en cualquier momento."
        }
        return "$age,$urgency"
    }
}
