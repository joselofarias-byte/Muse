package io.zer0.muse.data

/**
 * v0.34: Configuración de parámetros predeterminados de generación de imágenes.
 *
 * El usuario está en"ajustes→Modelos y Servicios"Establecer el valor predeterminado en,InputBar Se puede sobrescribir temporalmente en modo de dibujo.,
 * Finalmente pasó a ImageService.generate().
 */
@kotlinx.serialization.Serializable
data class ImageGenConfig(
    /** Proveedores utilizados en los dibujos. ID(Déjelo en blanco para usar el actual Provider). */
    val providerId: String = "",
    /** modelo de dibujo ID(Déjelo en blanco para utilizar el modelo predeterminado del proveedor actual.). */
    val modelId: String = "",
    /** Tamaño de imagen,OpenAI DALL-E 3 apoyo 1024x1024 / 1792x1024 / 1024x1792. */
    val size: String = "1024x1024",
    /** Calidad de imagen:standard / hd(DALL-E 3). */
    val quality: String = "standard",
    /** Estilo de imagen:vivid / natural(DALL-E 3). */
    val style: String = "vivid",
    /** Formato de devolución:url / b64_json. */
    val responseFormat: String = "url",
    /** Generar cantidad,Generalmente 1. */
    val n: Int = 1,
)

/**
 * Configuración de parámetros predeterminados para la generación de video.
 *
 * El usuario está en"ajustes→generación de vídeo"Establecer proveedor predeterminado en/modelo,
 * ChatViewModel.execGenerateVideo Prefiero esta configuración;
 * Volver a la selección automática cuando se deja vacío(El primer proveedor que admite salida de vídeo).
 */
@kotlinx.serialization.Serializable
data class VideoGenConfig(
    /** Proveedor utilizado para la generación de videos. ID(Déjelo en blanco para seleccionar automáticamente un proveedor que admita salida de video.). */
    val providerId: String = "",
    /** modelo de vídeo ID(Déjelo en blanco para usar el modelo de video predeterminado del proveedor.). */
    val modelId: String = "",
    /** Duración del vídeo predeterminada(segundos),Generalmente 5 o 10. */
    val duration: Int = 5,
    /** Resolución predeterminada,Como 720p/1080p. */
    val resolution: String = "720p",
)

/**
 * v0.30-a: Clase de datos de retrato de usuario.
 *
 * Todos los campos pueden estar vacíos(El usuario no necesita completar).SystemPromptAssembler Ensamble la sección 3 en consecuencia.
 * Más adelante, se puede agregar una interfaz de usuario a la página de configuración para que los usuarios la completen.
 */
@kotlinx.serialization.Serializable
data class UserProfile(
    /** v1.76: ¿Cómo se dirige el asistente al usuario?(Como por ejemplo"Xiaoming"/"jefe"),Inyecte el mensaje del sistema para permitir que la IA personalice el saludo. */
    val userNickName: String? = null,
    /** B0-09: avatar de usuario URI(null significa no establecido,Utilice iniciales de marcador de posición). */
    val avatarUri: String? = null,
    /** v1.76: El nombre dado al asistente por el usuario.(Como por ejemplo"Xiao Miao"/"JARVIS"),Inyecte el mensaje del sistema para permitir que la IA se llame a sí misma. */
    val assistantName: String? = null,
    val age: String? = null,
    val city: String? = null,
    /** v1.98: Eliminar campo mbti(El usuario no necesita). Si hay alguno en el JSON antiguo mbti,Ignorado al deserializar(Serializable Comportamiento predeterminado). */
    val occupation: String? = null,
    /** v1.98: Campos profesionales(Como por ejemplo"desarrollo de software"/"Finanzas"/"medico"),AI Ajuste la profundidad de la terminología en consecuencia. No obligatorio. */
    val professionField: String? = null,
    val interests: String? = null,
    // ── v1.133: Reescribir retratos de usuarios,Inyectar todos los campos nuevos system prompt,Haz que el modelo sea realmente"saber"Usuario ──
    /** v1.133: Perfil(Un párrafo de introducción gratuita.,Deje que el modelo tenga una comprensión general del usuario.) */
    val bio: String? = null,
    /** v1.133: Antecedentes educativos(Como por ejemplo"Licenciatura en Ciencias de la Computación"/"MaestríaIngeniería financiera") */
    val educationBackground: String? = null,
    /** v1.133: Experiencia en habilidades(Como por ejemplo"Python, aprendizaje automático, diseño de producto") */
    val skills: String? = null,
    /** v1.133: estilo de comunicación(conciso/Detallado/animado/serio/Neutro,texto libre) */
    val communicationStyle: String? = null,
    /** v1.133: Preferencia de longitud de respuesta(corto/en/largo) */
    val responseLength: String? = null,
    /** v1.133: tono preferido(amigable/Profesional/humor/riguroso,texto libre) */
    val preferredTone: String? = null,
    /** v1.133: Idioma de respuesta preferido(chino/ingles/Mezclado de chino e inglés.) */
    val preferredLanguage: String? = null,
    /** v1.133: Temas tabú(separados por comas,AI Evitará activamente) */
    val avoidTopics: String? = null,
    /** v1.134: zona horaria(Como por ejemplo"Asia/Shanghai"/"UTC+8") */
    val timezone: String? = null,
)

/**
 * v0.31: Preferencias de comportamiento en el chat(Dar a los usuarios más control).
 *
 * Todos los campos tienen valores predeterminados(Listo para usar),Los usuarios pueden"ajustes→charlar"Ajuste en la página de segundo nivel.
 * MessageBubble / ChatScreen / InputBar La lectura de estos interruptores determina el comportamiento de renderizado e interacción.
 *
 * grupo:
 *  - visualización de mensajes:showMoodBlock / showReasoning / showTokenEstimate / showModelName / showTimestamp
 *  - Estado expandido predeterminado:moodExpandedByDefault / reasoningExpandedByDefault
 *  - comportamiento interactivo:streamResponse / autoScrollToBottom / volumeKeyScroll / enterToSend / hapticFeedback / predictiveBackEnabled
 *  - Avanzado:longMessageThreshold / showToolCallDetails / use24Hour
 */
@kotlinx.serialization.Serializable
data class ChatPreferences(
    // ── visualización de mensajes ──
    /** Si mostrar bloques MOOD para IA(6 Paso Flujo de trabajo Paso 2). */
    val showMoodBlock: Boolean = true,
    /**
     * Conexión entre sesiones: al abrir una nueva conversación, los últimos mensajes de la conversación anterior también se leen en el contexto.
     *
     * El escenario es que el usuario cierra una ventana e inmediatamente abre una nueva para seguir hablando de lo mismo. Está desactivado por defecto porque ocupará
     * Ingrese el token; después de encenderlo, [carryOverMessageCount] Controla el número de barras.
     *
     * con nivel asistente `enableRecentChatsReference` División del trabajo:
     *  - Este último sólo ofrece "título + vista previa de 80 palabras", lo cual es una pista superficial;
     *  - Este elemento da giros de diálogo reales y se utiliza para abordar un contexto específico.
     */
    val carryOverEnabled: Boolean = false,
    /** La cantidad de mensajes traídos por conexiones entre sesiones (usuario + asistente cuentan como uno). Rango 4..40. */
    val carryOverMessageCount: Int = 20,
    /**
     * issue #3.5: Modo de respuesta rápida.
     *
     * Después de encenderlo, se omitirá el conjunto de palabras del mensaje del sistema "Recuperación de memoria relacionada". —— Ejecuta una consulta FTS en cada ronda.,
     * La mala suerte también puede provocar la incorporación de llamadas de red. La medición real muestra que el segmento de memoria a largo plazo se puede ocupar en una máquina real.
     * 500ms+(Iniciar sesión: sys-prompt-static-detail: 564ms [long_term_memory=552ms]),
     * Levante directamente el retraso de la primera palabra.
     *
     * memoria a largo plazo(<long_term_memory>)no afectado,todavía inyectar;Omita solo los que se basan en la pregunta actual.
     * Recuerdo de relevancia. Desactivado predeterminado(mantener el comportamiento existente).
     */
    val fastReplyMode: Boolean = false,
    /** Ya sea para mostrar el proceso de pensamiento de la IA(reasoning). */
    val showReasoning: Boolean = true,
    /**
     * v2.6.1(comentarios de los usuarios): Obliga al modelo a volver a pensar.
     *
     * Después de abrir,Los asistentes ya no aceptan solicitudes de chat reasoningLevel=OFF/AUTO restricción ——
     * Siempre que el modelo actual admita capacidades de inferencia,Sólo presione [forceReasoningLevel] Enviar parámetros de pensamiento,
     * dejar“Sin módulo de pensamiento”El asistente predeterminado también puede comprender el proceso de pensamiento.
     * Desactivado predeterminado(No cambia el comportamiento existente.);Cuando el modelo en sí no admite la inferencia, aún así se degrada a AUTO,No cotizaré 400.
     */
    val forceReasoning: Boolean = false,
    /** v2.6.1: Fuerza del razonamiento utilizado por el pensamiento forzado.(solo [forceReasoning]=true eficaz cuando). Valor predeterminado ALTO. */
    val forceReasoningLevel: io.zer0.ai.core.ReasoningLevel = io.zer0.ai.core.ReasoningLevel.HIGH,
    /** v1.64: Ya sea para mostrar el bloque de reflexión de IA(reflection,precisión de la autoevaluación/integridad/tono). */
    val showReflectionBlock: Boolean = true,
    /** Si se muestran estimaciones de tokens en la parte inferior de los mensajes de IA. */
    val showTokenEstimate: Boolean = true,
    /** Si se muestra el nombre del modelo en la parte inferior de los mensajes de AI. */
    val showModelName: Boolean = true,
    /** Si se muestra la marca de tiempo de cada mensaje. */
    val showTimestamp: Boolean = false,
    /**
     * Visualización de la barra de burbujas: el mensaje cubre todo el ancho de la página, eliminando el ancho máximo y la sangría horizontal de la burbuja.
     * Está desactivado de forma predeterminada (para mantener la apariencia de la burbuja); cuando está activado, los mensajes de usuario y asistente se organizan según el ancho completo de la página.
     */
    val bubbleFullWidth: Boolean = false,
    val showUserAvatar: Boolean = true,

    /**
     * v2.6.10: Si se muestra el nombre del asistente en la parte superior de los mensajes del asistente.
     *
     * Habilitado por defecto —— El nombre es un ancla de identidad y puedes saber quién está hablando en un escenario con múltiples asistentes.
     * con [showTimestamp] Independiente: desactivar las marcas de tiempo no debería ocultar los nombres junto con ellas.
     */
    val showAssistantName: Boolean = true,
    // ── v2.x(comentarios de los usuarios): El botón de acceso directo debajo del mensaje se muestra y se oculta ──
    /** asistente/Botones de acción rápida debajo de los mensajes de usuario —— Los usuarios pueden decidir cuáles mostrar, evitando filas de botones abarrotadas. */
    val quickActionCopy: Boolean = true,
    /** Copie el proceso de pensamiento (predeterminado desactivado: igual que el ícono de copia de texto, y aparecerán dos teclas de copia durante la reunión). */
    val quickActionReasoning: Boolean = false,
    val quickActionTranslate: Boolean = true,
    val quickActionShare: Boolean = true,
    val quickActionRegenerate: Boolean = true,
    /** Eliminar mensajes (desactivado por defecto: operación destructiva, no ocupa la cola de accesos directos de forma predeterminada, los usuarios pueden activarla ellos mismos). */
    val quickActionDelete: Boolean = false,
    /**
     * v2.6.0: Lista de activación ordenada de botones de acceso directo (Via Menú personalizado estilo navegador).
     * El elemento es la identificación de la acción (consulte el grupo completo de ui/chat/QuickActions.kt) y el orden es el orden de visualización debajo del mensaje.
     * lista vacía = No configurado, recurra a los seis interruptores anteriores anteriores (compatible con la configuración de usuario existente, escriba este campo después del primer uso).
     */
    val quickActionOrder: List<String> = emptyList(),

    /** v2.0: Diseño de texto — Escala del tamaño del texto del mensaje(1.0 = Predeterminado)y espaciado de palabras(em). */
    val messageFontScale: Float = 1.0f,
    val messageLetterSpacingEm: Float = 0f,
    // ── Estado expandido predeterminado ──
    /** MOOD Si los bloques se expanden o contraen de forma predeterminada. */
    val moodExpandedByDefault: Boolean = false,
    /** El proceso de pensamiento por defecto se expande o colapsa. */
    val reasoningExpandedByDefault: Boolean = false,
    /** v1.0.30: Si el campo de entrada muestra un botón de edición en pantalla completa. */
    val showExpandButton: Boolean = false,
    /** v1.64: Los bloques de reflexión se expanden o contraen de forma predeterminada. */
    val reflectionExpandedByDefault: Boolean = false,
    // ── comportamiento interactivo ──
    /** Ya sea para transmitir respuestas de salida(Toda la salida después del cierre.). */
    val streamResponse: Boolean = true,
    /** Si se desplaza automáticamente hacia abajo cuando llegan nuevos mensajes. */
    val autoScrollToBottom: Boolean = true,
    /** Si se deben habilitar las teclas de volumen para desplazarse por la lista de chat. */
    val volumeKeyScroll: Boolean = true,
    /** Si la tecla Enter envía un mensaje directamente(Cerrar, luego ingresar y avanzar línea). */
    val enterToSend: Boolean = false,
    /** Ya sea para habilitar la retroalimentación táctil. */
    val hapticFeedback: Boolean = true,
    // ── Avanzado ──
    /** Umbral de plegado de mensajes largos(recuento de palabras),Cuando se excede esta longitud, se colapsa automáticamente y se expande gradualmente. */
    val longMessageThreshold: Int = 200,
    /** Si se muestran mensajes de proceso intermedios de llamadas a herramientas. */
    val showToolCallDetails: Boolean = true,
    /**
     * v2.6.6: Si se deben permitir interfaces generativas —— Los modelos pueden usar show_card para generar tarjetas interactivas en conversaciones.
     * Después del cierre, las palabras clave ya no guiarán la generación; Las tarjetas existentes seguirán mostrándose normalmente.
     */
    val generativeUiEnabled: Boolean = true,
    /**
     * v2.6.7: Salida de tarjeta de preferencia —— ¿Debería el modelo ser más proactivo a la hora de llevar información estructurada con tarjetas?
     *
     * con [generativeUiEnabled] Capas:[generativeUiEnabled] decidir“Permitido o no permitido”,
     * esta decisión“¿Debería usarse primero?”. Válido sólo si está permitido.
     *
     * v2.6.13: **Desactivado de forma predeterminada**. El enlace de representación de la tarjeta aún se está rehaciendo (la altura y la interacción son inestables).
     * Activarlo de forma predeterminada significa colocar cosas no confiables donde los usuarios puedan verlas a primera vista.
     * Quienes quieran utilizarlo pueden activarlo en la configuración; Los valores antiguos de los usuarios existentes se desactivan mediante una migración única de ChatSettingsStore.
     */
    val preferCardOutput: Boolean = true,
    /** Si se debe pausar la ejecución de la herramienta real para esta ronda y las siguientes(Los pensamientos y las respuestas finales aún pueden continuar.). */
    val pauseToolExecution: Boolean = false,
    /** Si la marca de tiempo utiliza el reloj de 24 horas. */
    val use24Hour: Boolean = true,
    /**
     * F-41: Esquinas redondeadas de la burbuja del mensaje(0-28dp,engranaje entero).
     *
     * engranaje:0=cuadrado,8=esquinas redondeadas,20=Grandes esquinas redondeadas,28=Cápsulas.
     * Sólo funciona en burbujas de mensajes de usuario.(Como mensaje izquierdo arriba),El valor puede ser determinado por la página de configuración."mensaje esquinas redondeadas"Las opciones están escritas.
     */
    val bubbleRadius: Int = 20,
    // ── Generar comportamiento(situación general) ──
    /** temperatura global(0-2),El asistente vuelve a este valor cuando la temperatura no se ajusta por separado. */
    val globalTemperature: Float = 0.8f,
    /** estilo de tono:concise(conciso)/balanced(equilibrio)/detailed(Detallado). */
    val responseStyle: String = "balanced",
    /** tono:neutral(Neutro)/friendly(cordial)/formal(formales)/humorous(humor). */
    val responseTone: String = "neutral",
    /** v1.110: Si habilitar el pensamiento profundo de forma predeterminada(Nuevo/Valor inicial al cambiar de sesión,Evite tener que presionar botones manualmente cada vez). */
    val defaultDeepThinking: Boolean = false,
    /**
     * v1.0.4 (P3-4): Modo de rendimiento — Las sesiones muy largas solo muestran los últimos N mensajes,Desliza hacia arriba para cargar más,Reducir el retraso de la lista.
     *
     * Después de abrir pases ChatScreen [io.zer0.muse.perf.MessagePaginator] Realizar paginación a nivel de memoria en state.messages,
     * LazyColumn Solo renderiza lo más reciente [io.zer0.muse.perf.MessagePaginator.DEFAULT_PAGE_SIZE] * pageCount Artículo,
     * Expandir automáticamente la página siguiente al desplazarse hacia arriba(Paginación de memoria local pura,no comprobar DB);Expanda todo y luego deslícese hacia arriba para activar DB loadMoreHistory.
     * Mantener el comportamiento existente cuando está cerrado(Renderizar todo directamente state.messages).
     */
    val performanceMode: Boolean = false,
    /** v1.0.83: Si se debe utilizar el retorno predictivo de Android; realice un popBackStack normal inmediatamente después de cerrar la navegación. */
    val predictiveBackEnabled: Boolean = true,
    /**
     * U-6: Si se ha mostrado el mensaje de operación de gestos(v2.x abandonado — Se han eliminado la barra de mensajes y la referencia de deslizamiento hacia la izquierda.).
     *
     * Los campos están reservados únicamente por compatibilidad con versiones anteriores de datos JSON persistentes.,El nuevo código ya no se lee ni se escribe.
     */
    val chatGesturesHintShown: Boolean = false,
)

/**
 * v0.32: Cambios de funciones experimentales.
 *
 * De forma predeterminada, algunos elementos experimentales están habilitados según la estrategia de escala de grises.(Compresión de memoria larga, paralelismo de herramientas de solo lectura),El resto están desactivados por defecto.,
 * Los usuarios pueden realizar ajustes activamente.
 */
@kotlinx.serialization.Serializable
data class ExperimentsConfig(
    /** Experimental:Habilitar salida forzada del bloque MOOD(Intentaré solicitarlo incluso si el modelo no lo admite.). */
    val forceMoodBlock: Boolean = false,
    /** Experimental:modo de depuración,Mostrar más estados internos(MOOD/llamada de herramienta/Token Estadísticas). */
    val debugMode: Boolean = false,
    /** Experimental:Permitir la autorreflexión(Verifique automáticamente la calidad después de la respuesta). */
    val selfReflection: Boolean = false,
    /** v1.55: La compresión de memoria larga está habilitada de forma predeterminada(Resumen automático de conversaciones muy largas,Reduzca el umbral de compilación a 3.0 para que la compilación de hechos sea más agresiva). */
    val longMemoryCompression: Boolean = true,
    /**
     * Escala de grises:Las herramientas de solo lectura se ejecutan en paralelo(Habilitado por defecto,Se puede cerrar con un clic).
     *
     * Solo cuando todas las herramientas en la misma ronda son herramientas de la lista blanca de solo lectura, la concurrencia está sujeta al límite superior.,Los resultados se reponen en el orden de llamada original;
     * escribir/Herramienta de efectos secundarios para la serialización en cualquier caso. Vuelve a la semántica serial completa del B-38 después del apagado.
     */
    val parallelReadOnlyTools: Boolean = true,

    /**
     * Experimental:Recuperación de archivos adjuntos de sesión.
     *
     * Después de abrir,Los documentos cargados en la sesión se analizarán e indexarán en la base de datos de búsqueda.(kb_search Su contenido puede ser afectado.),
     * Sesión de enlace del ciclo de vida del índice,Se eliminará cuando se limpie la sesión. Desactivado de forma predeterminada(Funciones experimentales).
     */
    val sessionAttachmentRag: Boolean = false,

)

/**
 * v0.32: Compartir configuración de plantilla.
 *
 * Controlar la exportación/Formato y contenido al compartir conversaciones.
 */
@kotlinx.serialization.Serializable
data class ShareTemplateConfig(
    /** Si se debe incluir una marca de tiempo al compartir. */
    val includeTimestamp: Boolean = true,
    /** Si se debe incluir el nombre del modelo al compartir. */
    val includeModelName: Boolean = false,
    /** Si se debe incluir el número de token al compartir. */
    val includeTokenCount: Boolean = false,
    /** Si incluir bloques MOOD al compartir. */
    val includeMoodBlock: Boolean = false,
    /** ¿Incluye un proceso de pensamiento al compartir? */
    val includeReasoning: Boolean = false,
    /** Formato de compartir:markdown / plain_text / html. */
    val format: String = "markdown",
    /** Título personalizado(Si está vacío, utilice el título de la sesión.). */
    val customTitle: String = "",
)

/**
 * v0.32: Configuración de medios.
 *
 * Parámetros que controlan los anuncios TTS y la salida de audio.
 */
@kotlinx.serialization.Serializable
data class MediaConfig(
    /** TTS Velocidad de transmisión de voz(0.5-2.0,1.0 para normales). */
    val ttsSpeechRate: Float = 1.0f,
    /** TTS tono de transmisión(0.5-2.0,1.0 para normales). */
    val ttsPitch: Float = 1.0f,
    /** TTS Idioma de transmisión(null Indica seguir el sistema.). */
    val ttsLanguage: String? = null,
    /** Modo de salida de audio:speaker / earpiece / bluetooth. */
    val audioOutput: String = "speaker",
    /** v1.97: TTS tipo de motor:"system"(sistema TTS) / "openai"(OpenAI TTS) / "minimax"(MiniMax TTS) / "edge"(Edge TTS). */
    val ttsEngine: String = "system",
    /** v1.97: nube TTS API Key(Almacenamiento cifrado). */
    val ttsApiKey: String = "",
    /** v1.97: Nombre del modelo de TTS en la nube(Como por ejemplo openai: gpt-4o-mini-tts, minimax: speech-2.6-turbo). */
    val ttsModel: String = "",
    /** v1.97: Sonidos TTS en la nube(Como por ejemplo openai: alloy/echo/fable/onyx/nova/shimmer). */
    val ttsVoice: String = "",
    /** v1.97: Personalización de TTS en la nube endpoint(Déjelo en blanco para usar el valor predeterminado). */
    val ttsEndpoint: String = "",
    /** Nombre del sonido TTS del sistema(Una cadena vacía significa usar el sonido predeterminado.). */
    val ttsVoiceName: String = "",
    /** v1.99(4.8): ElevenLabs stability(0-1,Predeterminado 0.5). */
    val ttsStability: Float = 0.5f,
    /** v1.99(4.8): ElevenLabs similarity_boost(0-1,Predeterminado 0.75). */
    val ttsSimilarityBoost: Float = 0.75f,
    /** v1.99(4.8): MiniMax emoción(happy/sad/angry/neutral espera,Vacío significa predeterminado). */
    val ttsEmotion: String = "",
    /** v1.99(4.8): Multiplicador de velocidad de voz sintética Cloud TTS(0.25-4.0,1.0 para normales). */
    val ttsCloudSpeed: Float = 1.0f,
    /** v1.99(4.8): Formato de audio TTS en la nube(mp3/opus/aac/flac/wav,Predeterminado mp3). */
    val ttsResponseFormat: String = "mp3",
    /**
     * ¿El TTS en la nube sigue la configuración del proveedor?(Predeterminado false).
     *
     * Después de abrir, se toma baseUrl/apiKey de [ttsProviderId] Proveedor a señalar,Modelo tomado de [ttsModelId];
     * Utilice siempre la configuración manual al apagar([ttsEndpoint] / [ttsApiKey] / [ttsModel]),El comportamiento es consistente con versiones anteriores.
     */
    val ttsFollowProvider: Boolean = false,
    /** Proveedor seleccionado en modo seguimiento id(Cadena vacía significa no seleccionado). */
    val ttsProviderId: String = "",
    /** Modelo seleccionado en modo seguimiento id(Cadena vacía significa no seleccionado). */
    val ttsModelId: String = "",
) {
    /**
     * P0-2: Devolver ttsApiKey cifrado(ir [SecureKeyStore.encrypt])copia de,Por llamar antes que la perseverancia.
     * Los valores nulos se dejan como están.(No cifrar valores nulos);cifrado(Hay enc_v1: prefijo)Déjalo como está. Presione WebSearchConfig.modo cifrado.
     */
    suspend fun encrypted(): MediaConfig = copy(
        ttsApiKey = SecureKeyStore.encrypt(ttsApiKey),
    )

    /**
     * P0-2: Devolver ttsApiKey descifrado(ir [SecureKeyStore.decrypt])copia de,Se llama después de leer desde la capa de persistencia.
     * El antiguo texto sin formato se transmite de forma transparente mediante descifrado.(Compatible con la migración). Presione WebSearchConfig.modo descifrado.
     */
    suspend fun decrypted(): MediaConfig = copy(
        ttsApiKey = SecureKeyStore.decrypt(ttsApiKey),
    )
}

/**
 * Configuración de mensajes activos(El asistente virtual acompañante envía mensajes de forma proactiva a los usuarios como una persona real).
 *
 * - [enabled] interruptor principal,El programador se salta al apagar
 * - [intervalMinutes] v1.30: Intervalo de activación(minutos),Los usuarios pueden personalizarlo con el control deslizante en la página de configuración.(15 minutos ~ 24 horas,Ajuste continuo)
 * - [lastTriggeredAt] Última marca de tiempo activada,El planificador determina si expirará en función de esto.
 * - [randomOffsetMinutes] v1.30: desplazamiento aleatorio(minutos),intervalo real = intervalMinutes ± randomOffsetMinutes,
 *   Haga que los tiempos de envío sean más naturales,Evite la sensación mecánica de intervalos fijos. Ajuste continuo
 * - [agentId] v1.27: Especificar el asistente del agente que envía mensajes no solicitados id,Una cadena vacía significa usar el asistente predeterminado
 * - [allowedHourStart] v1.95: Horas de inicio de la ventana de envío permitidas(0-23,24reloj de hora),No omitas el envío durante este período para no molestarte por la noche.
 * - [allowedHourEnd] v1.95: Horas de finalización de la ventana de envío permitidas(0-23,24reloj de hora),Soporte nocturno(Como indica 22-822Haga clic para pasar al día siguiente8punto)
 * - [agentOnly] v1.95: soloAgentLas conversaciones pueden enviar mensajes activos.(true=solo enviarAgent Tabsesión,false=sin limite)
 * - [maxDailyMessages] **En desuso**: Una vez se escribió desde el puesto "Ritmo", pero no existe un método de lectura en todo el almacén.
 *   En realidad, nunca entró en vigor. El número diario de artículos es ahora [io.zer0.muse.data.proactive.ProactiveIntensity.cap]
 *   Decisión (Tranquilo 3/Restringido 8/Estándar 15/Activo 30/Apego ilimitado). Los campos están reservados únicamente para la compatibilidad de deserialización con configuraciones antiguas.
 * - [temperature] v2.0 5.9: LLM llamar a la temperatura(Predeterminado 0.8),Para la etapa de toma de decisiones temperature×0.5,Utilice este campo durante la fase de generación.
 */
@kotlinx.serialization.Serializable
data class ProactiveMessageConfig(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 240,
    val lastTriggeredAt: Long = 0,
    /** B8-01: Marca de tiempo del siguiente mensaje activo(Restaurar desde la configuración persistente después de reiniciar el proceso,0=Aún no programado). */
    val nextTriggerAt: Long = 0,
    /** v1.30: desplazamiento aleatorio(minutos),intervalo real = intervalMinutes ± randomOffsetMinutes. */
    val randomOffsetMinutes: Int = 60,
    /** v1.27: Especificar el asistente del agente que envía mensajes no solicitados id,Una cadena vacía significa utilizar el asistente predeterminado. */
    val agentId: String = "",
    /** v1.95: Horas de inicio de la ventana de envío permitidas(0-23,24reloj de hora),Predeterminado8punto,No omitas el envío durante este período para no molestarte por la noche. */
    val allowedHourStart: Int = 8,
    /** v1.95: Horas de finalización de la ventana de envío permitidas(0-23,24reloj de hora),Predeterminado22punto,Soporte nocturno(Como indica 22-822Haga clic para pasar al día siguiente8punto). */
    val allowedHourEnd: Int = 22,
    /**
     * v2.6.x: Permitir entrega todo el día(true = 24 Las horas están dentro del rango de envío permitido.).
     *
     * fondo: [allowedHourStart] / [allowedHourEnd] Sólo puede expresar un intervalo.,
     * Cerrar a la izquierda y abrir a la derecha(suntil)En ningún caso se podrán recuperar 24 horas completas bajo la semántica de ——
     * Suponiendo que 23-23 resultará en un intervalo vacío(Ni un solo mensaje),Supongamos que 23-0 tomaría al filial nocturno para cubrir sólo 23 puntos.
     * No existe ninguna configuración que se pueda expresar cuando el usuario quiere "publicar todo el día",Por lo tanto, este interruptor principal se proporciona por separado.
     *
     * true tiempo: Saltar la verificación de la ventana de hora actual,también omitir [computeNextTriggerTime] adentro
     * "Aplazado hasta el próximo activo/Permitir paso de ventana(Aún se conservan los intervalos, los límites diarios y las reservas de fallos.).
     */
    val allDayAllowed: Boolean = false,
    /**
     * **En desuso**:v1.95 Interruptor "Sólo sesión de agente". Nunca se ha leído la posición completa (solo se dibuja un interruptor en la página de configuración),
     * Prueba real del usuario "Abrir/No importa si está cerrado”. Los campos reservados solo son compatibles con la deserialización de la configuración anterior; utilice la lógica [agentSessionEnabled].
     */
    @Deprecated("Sin lector, utilice en su lugar agentSessionEnabled")
    val agentOnly: Boolean = false,

    /** Destino de agente; al desactivarlo, las conversaciones normales requieren permiso explícito. */
    val agentSessionEnabled: Boolean = true,
    /**
     * Garantía diaria: solo omite la espera entre mensajes, nunca los límites de seguridad.
     */
    val dailyGuaranteeHours: Int = 22,
    /** Solo permite mensajes en conversaciones normales si el usuario lo habilita. */
    val allowPlainChatSession: Boolean = false,
    /**
     * v2.0 5.9: Límite diario de mensajes activos(Predeterminado 3).
     *
     * El límite superior estricto diario se determina mediante información activa y se respeta al deducir el presupuesto.
     */
    val maxDailyMessages: Int = 3,
    /**
     * v2.0 5.9: LLM llamar a la temperatura(Predeterminado 0.8),Se utiliza tanto en la etapa de toma de decisiones como en la de generación.
     *
     * Uso práctico en la etapa de toma de decisiones. temperature × 0.5(Las decisiones requieren certeza);Este campo se utiliza durante la fase de generación.
     */
    val temperature: Float = 0.8f,
    /**
     * v1.0.72: Probabilidad de enviar mensajes activos(0-100,Predeterminado 100).
     *
     * etapa de decisión shouldSend=true después,Luego use esta probabilidad para decidir si realmente enviarlo.:
     *  - 100 = enviar cada vez(Toma de decisiones inmediata)
     *  - 50 = Media probabilidad
     *  - 0 = nunca enviar(equivalente a cerrar,Pero mantén un registro de decisiones)
     * El envío de pruebas no está sujeto a esta restricción.(forceSend pelo liso).
     */
    val sendProbability: Int = 100,
    /**
     * A-08: Marca de tiempo de falla de la última llamada de LLM(0=Sin fallos).
     *
     * toma de decisiones/Escribir cuando falla la fase de construcción LLM;Habrá al menos un intervalo baseInterval antes de volver a intentarlo.,
     * Evitar la desconexión de la red/garantizadoEnvíe reintentos de bucle activo cada minuto para quemar la cuota cuando falla el servicio.
     * Borrado después de una transmisión exitosa.
     */
    val lastFailedAt: Long = 0,
    /**
     * Revisión de corrección (2.0 B-12): Número de fallos consecutivos(0=Sin fallos).
     *
     * toma de decisiones/Incrementado cuando falla la compilación,Borrado después de una transmisión exitosa;El intervalo de retroceso crece exponencialmente con el conteo.
     * (1x/2x/4x/8x/16x baseInterval),Ponle fin a"Reintentos infinitos de intervalo de base fijo"El ciclo de quema de cuotas.
     */
    val consecutiveFailures: Int = 0,
    /**
     * v2.6.2(comentarios de los usuarios): **En desuso, sin implementación**.
     *
     * Estaba previsto que el asistente devolviera un `nextIntervalMinutes`, úselo para cubrir intervalos fijos,
     * Depende del asistente decidir con qué frecuencia vendrá la próxima vez. Sin embargo, no hay ningún lector en todo el almacén y la interfaz ya no escribe este campo:
     * La programación y el juicio solo ven [intervalMinutes](Incluyendo la inquietud del día).
     *
     * Reservado sólo para compatibilidad de deserialización con configuraciones antiguas; El nuevo código no debe leerlo ni escribirlo.
     */
    val aiPaced: Boolean = false,
)

/**
 * v2.6.2(comentarios de los usuarios): Configuración de la constelación de memoria (gráfico de conocimiento).
 *
 * - [aiEnhanceEnabled]: AI Profundizar el cambio de correlación. Después de abrir“Vecinos semánticos pero relación poco clara”lado
 *   Utilice modelos de herramientas grandes para determinar tipos de relaciones específicas (causes / contradicts / part_of). Una vez por borde
 *   LLM Llamar es caro y está desactivado de forma predeterminada.
 * - [edgeBuildFrequency]: Frecuencia suplementaria. Controla cuándo completar los bordes con existencias para nuevos hechos.
 */
@kotlinx.serialization.Serializable
data class MemoryGraphConfig(
    val aiEnhanceEnabled: Boolean = false,
    val edgeBuildFrequency: EdgeBuildFrequency = EdgeBuildFrequency.SESSION_END,
)

/** Frecuencia suplementaria. */
@kotlinx.serialization.Serializable
enum class EdgeBuildFrequency {
    /** Reponga una vez al final de cada sesión (predeterminado, recomendado). */
    SESSION_END,

    /** Rellénelo cada vez que abra la página del signo del zodíaco. */
    ON_OPEN,

    /** Sólo manual (punto de usuario“Restablecer asociación”). */
    MANUAL,
}

/**
 * v1.25: Equipo colaborativo.
 *
 * Los usuarios organizan varios asistentes en un equipo,El asistente principal puede delegar tareas a los miembros del equipo a través de delegado_agente.
 */
@kotlinx.serialization.Serializable
data class AgentTeam(
    /** Identificación única del equipo. */
    val id: String = java.util.UUID.randomUUID().toString(),
    /** Nombre del equipo,Como \"grupo de redacción\" y \"grupo de investigación\". */
    val name: String = "",
    /** Descripción del equipo/Consejos de uso. */
    val description: String = "",
    /** Lista de ID de asistente de miembro del equipo,El orden es el orden de comisión recomendado. */
    val memberIds: List<String> = emptyList(),
    /** v1.200: Definición del flujo de trabajo del equipo. Cuando está vacío, se ejecuta en serie en el orden de los miembros. */
    val workflow: io.zer0.muse.tools.DelegationContract.TeamWorkflow? = null,
    /** Crear marca de tiempo. */
    val createdAt: Long = System.currentTimeMillis(),
    /** Última marca de tiempo actualizada. */
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * v1.25: Configuración global de colaboración multiagente.
 */
@kotlinx.serialization.Serializable
data class MultiAgentConfig(
    /** interruptor principal,SystemPromptAssembler inyectará mensajes de colaboración entre múltiples agentes solo después de que esté activado. */
    val enabled: Boolean = true,
    /** v1.200: Conmutador de enrutamiento automático. Después de activarlo, cuando el mensaje del usuario tiene un grado bajo de coincidencia con el asistente actual, se delegará automáticamente a un Agente/Equipo más adecuado. */
    val autoRoutingEnabled: Boolean = false,
    /**
     * v2.x: LLM Conmutador de enrutamiento semántico(Desactivado predeterminado).
     *
     * Después de abrir,[io.zer0.muse.tools.AgentRouter.routeWithLlm] Utilizará LLM para determinar el mejor objetivo de ruta,
     * Enrutamiento de reglas de palabras clave alternativas.LLM Baja automáticamente al enrutamiento de reglas cuando la llamada falla o devuelve una identificación no válida.
     * cuando está cerrado(Predeterminado)Siga la ruta habitual,El comportamiento es coherente con v1.x.
     *
     * con [llmReviewEnabled] diferente,Este campo está serializado con MultiAgentConfig JSON,
     * Porque no existe un método de guardado independiente.,No existe ninguna condición de carrera de doble escritura.
     */
    val llmRoutingEnabled: Boolean = false,
    /** Listado de equipos colaborativos creados por el usuario. */
    val teams: List<AgentTeam> = emptyList(),
    /** Equipo predeterminado id,Si no se especifica, el asistente principal lo elegirá por sí mismo. */
    val defaultTeamId: String? = null,
    /**
     * v1.201: Estrategia de pausa en la colaboración entre humanos y máquinas(Se mantuvo con MultiAgentConfig para DataStore).
     *
     * Nota:[DelegationPauseManager.PausePolicy] debe ser @Serializable data class
     * Para serializar con MultiAgentConfig. actual [DelegationPauseManager.PausePolicy] no traído
     * @Serializable anotación,Debe agregarse en DelegationPauseManager.kt(Consulte la lista de cambios sugeridos para obtener más detalles.).
     */
    val pausePolicy: io.zer0.muse.tools.DelegationPauseManager.PausePolicy = io.zer0.muse.tools.DelegationPauseManager.PausePolicy(),
    /**
     * v1.201: LLM Modelo utilizado en revisión integral id(situación general,Compartir entre equipos).
     * Vacío significa usar el modelo predeterminado del proveedor activo.
     * persistir en independencia DataStore key: multi_agent_review_model(@Transient:No serializado con JSON,
     * Leer y escribir mediante clave independiente.,Evite condiciones de carrera de doble escritura entre updateMultiAgentConfig y el método de guardado independiente).
     */
    @kotlinx.serialization.Transient
    val reviewModelId: String? = null,
    /**
     * v1.201: Cambio de revisión integral de Global LLM(Desactivado predeterminado).
     * Después de abrir,En realidad, se llama a la revisión de LLM cuando el flujo de trabajo del equipo selecciona la estrategia de agregación LLM_REVIEW.;
     * LLM_REVIEW baja automáticamente a EXPERT_REVIEW al apagar.
     * Persistir en la clave de DataStore independiente: multi_agent_llm_review_enabled.
     */
    @kotlinx.serialization.Transient
    val llmReviewEnabled: Boolean = false,
)
