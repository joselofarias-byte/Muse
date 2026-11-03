package io.zer0.ai.core

import io.zer0.common.Logger

/**
 * Provider matriz de competencias(compatibility).
 *
 * Diferentes proveedores/tránsito/modelos tienen diferentes niveles de soporte para el protocolo OpenAI(Si Anthropic no es compatible
 * developer role,Gemini OpenAI La capa de compatibilidad no admite tienda,Zhipu No compatible reasoning_effort).
 * Esta clase centraliza estas diferencias.,Provider Consulte los campos correspondientes antes de la construcción de la solicitud para determinar si se deben inyectar ciertos parámetros.,
 * Evite distribuir especializaciones codificadas en cada proveedor.
 *
 * principios de diseño:
 *  - default conservador:Los campos ambiguos siempre son true(Deje que el proveedor se encargue o ignore los parámetros desconocidos),
 *    Solo se establece explícitamente en falso si no se admite la validación.
 *  - derivación centralizada:Todos los juicios de diferencia se centran en [ProviderCompatRules.resolve],
 *    Provider Solo consume resultados,No repita el razonamiento.
 *
 * Semántica de campo:
 * @param supportsDeveloperRole ¿Se admite cambiar la función del sistema a developer role
 *   (OpenAI o1/o3 Cuando el modelo de inferencia requiere el uso de la función de desarrollador para pasar comandos del sistema).
 * @param supportsStore Si se admite el parámetro de tienda de las finalizaciones de chat de OpenAI
 *   (Se utiliza para persistir conversaciones fuera de la API de Asistentes.).
 * @param supportsReasoningEffort Si se admite el parámetro Reasoning_effort
 *   (OpenAI o Serie/Nivel de inferencia GPT-5;DeepSeek/Anthropic/Gemini Utilice campos diferentes para cada uno).
 * @param supportsToolCalling Ya sea para admitir llamadas a funciones/parámetros de herramientas.
 * @param supportsVision Ya sea para admitir la entrada de imágenes(multimodal).
 * @param supportsParallelToolCalls Si se admite el parámetro paralelo_tool_calls.
 * @param supportsJsonMode Ya sea para apoyar response_format={"type":"json_object"}.
 * @param supportsStreamOptions Si se admite stream_options.include_usage.
 * @param supportsLogprobs Si se admite el parámetro logprobs(Predeterminado false,La mayoría de los proveedores no se utilizan comúnmente.).
 * @param supportsResumeFromText Si se admite la reanudación del texto de salida después de que se interrumpe la transmisión(Ver notas de campo).
 * @param maxContextWindow Provider Límite superior de la ventana de contexto de nivel(token),null Indica que no existe un límite superior uniforme.
 * @param defaultMaxTokens Predeterminado max_tokens,null Indica que se utiliza Proveedor de forma predeterminada.
 * @param thinkingFormat v1.0.7: formato de pensamiento(9 tipos en total).
 *   null=Sigue los estándares reasoning_effort;no null=Vaya al campo de extensión del fabricante correspondiente(thinking / enable_thinking espera).
 *   Solo impulsa la construcción del cuerpo de solicitud de OpenAIProvider;AnthropicProvider / GeminiProvider Cada uno realiza un pensamiento original.
 * @param reasoningReplayContract v1.0.7: reasoning contrato de repetición(carrier + policy).
 *   null=No reproducir el historial reasoning;no null=Escriba el campo de cable correspondiente en el cuerpo de la solicitud según el transportista/póliza.
 *   Determine cómo se serializa UIMessage.reasoning/thinkingSignature en el cuerpo de la solicitud(Esencial el diálogo multiturno).
 */
data class ProviderCompat(
    val supportsDeveloperRole: Boolean = true,
    val supportsStore: Boolean = true,
    val supportsReasoningEffort: Boolean = true,
    val supportsToolCalling: Boolean = true,
    val supportsVision: Boolean = true,
    val supportsParallelToolCalls: Boolean = true,
    val supportsJsonMode: Boolean = true,
    val supportsStreamOptions: Boolean = true,
    val supportsLogprobs: Boolean = false,
    /**
     * C-11: Si se admite la reanudación del texto de salida después de que se interrumpe la transmisión(resumeFromText).
     *
     * resumeFromText tratará el contenido mostrado sin terminar como"mensaje final del asistente"Solicitud de inyección,
     * Dejemos que el modelo continúe donde lo dejó. Parte del proveedor/backend/El modelo no trata correctamente un truncado.
     * assistant rondas(Puede repetir respuestas, rechazar o tratar contenido incompleto como expresiones completas.),En este momento, se debe configurar false,
     * Desactivar el currículum automático de ChatService(En su lugar, deje que la deduplicación LCP de la capa UI se haga cargo,en lugar de inyectar mensajes parciales).
     *
     * default conservador true(Si no queda claro que no es compatible, no se cerrará.).
     */
    val supportsResumeFromText: Boolean = true,
    val maxContextWindow: Int? = null,
    val defaultMaxTokens: Int? = null,
    /**
     * v1.0.7: formato de pensamiento(9 tipos en total).
     *
     * - null:Sigue los estándares OpenAI `reasoning_effort` campo(por [supportsReasoningEffort] decidir si enviar)
     * - [ThinkingFormat.DEEPSEEK]:No envíes ningún parámetro de pensamiento.,Solo consume streaming reasoning_content
     * - [ThinkingFormat.KIMI]:enviar `thinking: {type: "enabled"|"disabled", keep: bool}`
     * - [ThinkingFormat.QWEN]:enviar `enable_thinking: bool`(+ Opcional `thinking_budget: int`)
     * - [ThinkingFormat.QWEN_CHAT_TEMPLATE]:enviar `chat_template_kwargs: {enable_thinking: bool}`
     * - [ThinkingFormat.ZHIPU]:enviar `thinking: {type: "enabled"|"disabled", clear_thinking: bool}`
     * - [ThinkingFormat.OPENROUTER]:enviar `reasoning: {effort: "low"|"medium"|"high"}`
     * - [ThinkingFormat.VOLCENGINE]:enviar `thinking: {type: "enabled"|"disabled"}`
     * - [ThinkingFormat.LONGCAT]:enviar `thinking: {type: "enabled"|"disabled"}`
     * - [ThinkingFormat.ANTHROPIC]:OpenAIProvider No consumir(AnthropicProvider Procesar el propio bloque de pensamiento nativo)
     */
    val thinkingFormat: ThinkingFormat? = null,
    /**
     * v1.0.7: reasoning contrato de repetición(5 especie portadora + 3 especies policy).
     *
     * - null:No reproducir el historial reasoning(Predeterminado,compatible con versiones anteriores)
     * - [ReasoningReplayContract]([ReasoningCarrier.REASONING_CONTENT], [ReasoningReplayPolicy.REQUIRE_TOOL_CALL]):
     *   Kimi / DeepSeek / MiMo / Zhipu Chat Completions(Reproducir sólo cuando se llama a la herramienta)
     * - [ReasoningReplayContract]([ReasoningCarrier.REASONING_ITEMS], [ReasoningReplayPolicy.PRESERVE]):
     *   OpenAI Responses API(siempre reservado reasoning item)
     * - [ReasoningReplayContract]([ReasoningCarrier.REASONING_DETAILS], [ReasoningReplayPolicy.PRESERVE]):
     *   OpenRouter(siempre reservado reasoning_details)
     */
    val reasoningReplayContract: ReasoningReplayContract? = null,
    /**
     * v2.4.5: Límite superior de temperatura de muestreo(exclusivo),null significa sólo sujeto a condiciones generales [MIN_TEMPERATURE, MAX_TEMPERATURE] restricciones.
     *
     * Algunos proveedores tienen un rango claro de temperatura.(Como Shangtang sensecore `[0.0, 2.0)`,2.0 ilegal),
     * El límite superior del control deslizante de temperatura en la aplicación es exactamente 2,0 y la tarjeta de personaje de terceros importada puede tener cualquier temperatura.
     * Este valor viene dado por [ProviderCompatRules] Declarar por anfitrión,Apretado uniformemente por ChatService antes de enviar una solicitud.
     */
    val maxTemperatureExclusive: Float? = null,
)

/**
 * Provider Reglas de derivación de habilidades.
 *
 * Estrategia de emparejamiento de tres niveles(De grueso a fino,Este último cubre el primero.):
 *  1. prensa [ProviderType] Distribuir(reglas basicas,Reflejar diferencias a nivel de protocolo)
 *  2. Refinar aún más por host baseUrl(Refleja diferencias en las capas de compatibilidad de OpenAI del proveedor)
 *  3. Refinar más por modelId(Reflejar llamadas de modelos específicos a herramientas/Apoyo al razonamiento)
 *
 * Cuando falta alguna capa, se conserva el valor de la capa anterior.,Finalmente regresa el fusionado. [ProviderCompat].
 *
 * v2.7: Agregue una capa de cobertura de directorio encima de las tres capas —— El modelo en el catálogo de capacidades de modelos.**declaración explícita**El formato de pensamiento tiene prioridad sobre
 * Tabla codificada. prioridad:[Directorio] > [tabla codificada] > [Valor predeterminado],ver [resolve] fin.
 *
 * Escrito de forma independiente.
 */
object ProviderCompatRules {

    private const val TAG = "ProviderCompatRules"

    /**
     * v2.7: Directorio de capacidades del modelo actualmente efectivo(model-catalog).
     *
     * por [io.zer0.ai.registry.ModelRegistry.installCatalog] Sincronizar la escritura al inyectar el directorio de tiempo de ejecución ——
     * Sólo hay una entrada de carga para el directorio.,La derivación de capacidad y la derivación de compatibilidad leen los mismos datos.
     */
    @Volatile
    private var catalog: ModelCatalog = ModelCatalog()

    /**
     * inyectar/Reemplace el directorio de capacidades.
     *
     * con [io.zer0.ai.registry.ModelRegistry.installCatalog] llamar en parejas,No lo llames por separado en otro lugar.,
     * De lo contrario, las dos cadenas bifurcadas verían directorios diferentes.
     */
    fun installCatalog(newCatalog: ModelCatalog) {
        catalog = newCatalog
        warnOnAmbiguousDeclarations(newCatalog)
    }

    /**
     * v2.7: Marque "Declaraciones inconsistentes de modelos con el mismo nombre entre proveedores" al cargar el directorio,Simplemente lleve un registro y no cambie su comportamiento.
     *
     * fondo:[catalogThinkingFormat] Si no se puede obtener el ID del proveedor, busque globalmente por nombre de modelo,Gana el proveedor que aparece primero.
     * Cuando varios proveedores declaran el mismo nombre de modelo,Las dos formas saldrán mal silenciosamente.:
     *  - Conflicto de valores:Ambos declaran y asignan diferentes formatos. —— No importa a qué empresa lo envíes,Siempre hay una empresa que se aprovecha de las convenciones sobre cables de otras personas.
     *    Este es un problema puro de datos.,Es necesario cambiar el lado de generación del directorio.
     *  - declaración unilateral:Sólo una empresa hizo declaraciones y las demás no. —— ¿Qué parte "tomará prestada" el proveedor no declarado?,en lugar de volver a caer
     *    tabla codificada(El glm-5.2 actual tiene esta forma.). El resultado puede ser el correcto.,Pero el mecanismo está mal.
     *
     * Simplemente llama a la policía pero no lo arregles.:Lo que realmente hay que arreglar es cambiar la consulta a (providerId, modelId) comprobar,miss volver a la tabla codificada.
     */
    private fun warnOnAmbiguousDeclarations(installed: ModelCatalog) {
        if (installed.providers.isEmpty()) return
        val declarations = HashMap<String, MutableList<Pair<String, String?>>>()
        for ((providerId, models) in installed.providers) {
            for ((modelId, entry) in models) {
                declarations.getOrPut(modelId) { mutableListOf() } += providerId to entry.compat?.thinkingFormat
            }
        }
        for ((modelId, entries) in declarations) {
            if (entries.size < 2) continue
            val formats = entries.map { thinkingFormatOfCatalog(it.second) }
            val distinct = formats.filterNotNull().toSet()
            val detail = entries.joinToString(" | ") { "${it.first}=${it.second ?: "<No declarado>"}" }
            when {
                distinct.size >= 2 ->
                    Logger.w(TAG, "Conflicto de declaración de directorio:$modelId $detail —— Los modelos con el mismo nombre tienen valores diferentes.,La consulta recuperará silenciosamente la primera aparición.")
                distinct.size == 1 && formats.any { it == null } ->
                    Logger.w(TAG, "declaración unilateral directorio:$modelId $detail —— Los proveedores no declarados toman prestadas declaraciones de otros,en lugar de recurrir a una tabla codificada")
                else -> Unit
            }
        }
    }

    /**
     * v1.0.53: Colección de hosts conocidos(con [overrideByHost] La rama cuando tiene una correspondencia uno a uno.).
     *
     * Se utiliza para determinar si el host de baseUrl es un punto final oficial conocido o un sitio de agregación.
     * desconocido host(Estación de transferencia construida por el usuario,Como por ejemplo tokenrhythm.studio)No en este conjunto,
     * [overrideByModelId] Saltará el pensamiento de extensión del proveedor Inyección de formato,Evite estaciones de transferencia que no soporten
     * thinking / enable_thinking campos y devolver HTTP 400.
     */
    private val KNOWN_HOSTS: Set<String> = setOf(
        // Conexión directa nacional
        "api.deepseek.com", "api.moonshot.cn", "api.kimi.com",
        "open.bigmodel.cn", "api.z.ai",
        "dashscope.aliyuncs.com", "coding.dashscope.aliyuncs.com",
        "api.minimax.chat",
        "api.baichuan-ai.com", "api.lingyiwanwu.com", "api.stepfun.com",
        "ark.cn-beijing.volces.com",
        "api.hunyuan.cloud.tencent.com", "qianfan.baidubce.com",
        "api-inference.modelscope.cn", "cloud.infini-ai.com",
        "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com",
        "apihub.agnes-ai.com", "api.longcat.chat",
        // v2.4.5: Shangtang SenseNova / SenseCore,temperature es un intervalo abierto [0.0, 2.0)
        "token.sensenova.cn", "api.sensenova.cn",
        // Conexión directa al extranjero
        "api.x.ai", "cli-chat-proxy.grok.com", "api.perplexity.ai",
        "api.groq.com", "api.together.xyz", "api.mistral.ai",
        "api.deepinfra.com", "api.fireworks.ai",
        "api.githubcopilot.com", "models.inference.ai.azure.com",
        "localhost",
        // tránsito/Sitio de agregación
        "api.siliconflow.cn", "openrouter.ai", "opencode.ai",
    )

    /**
     * v2.7: Directorio `compat.thinkingFormat` vocabulario → [ThinkingFormat].
     *
     * Sólo mapa "OpenAI Este grupo se basa en los campos de extensión del proveedor para expresar opiniones sobre los protocolos de compatibilidad. Las dos categorías siguientes se tratan como no declaradas.,
     * Asumido por una tabla codificada:
     *  - `anthropic` / `google-thinking-level`:El protocolo nativo viene con capacidades,respectivamente por AnthropicProvider /
     *    GeminiProvider Implementarlo usted mismo.[ProviderCompat.thinkingFormat] Anule solo las rutas compatibles con OpenAI,
     *    poner `anthropic` El mapeo permitirá que el modelo de Claude utilice enlaces compatibles con OpenAI para aplicar la convención de cables de Anthropic.
     *  - vocabulario desconocido:La versión de vocabulario del directorio no coincide con el código.,Sin adivinanzas.
     *
     * Los otros tres conjuntos de campos de directorio en esta ronda**No cableado**,Razones para quedarse aquí,Para evitar tener que volver a adivinar la próxima vez:
     *  - `toolUse.dialect`:Los 270 valores no nulos del directorio son `openai`(con[ToolUseSpec]El valor predeterminado de),
     *    El formato de cable llamado por la herramienta es en realidad [ProviderType] decisión. No cambiará ningún comportamiento después de unirse.,Pon también el directorio
     *    El modelo Antrópico/Géminis está marcado en el dialecto openai. —— Este es un problema de datos de directorio.,No respondas todavía.
     *  - `thinkingLevels` / `defaultThinkingLevel`:Colección de equipo a nivel de modelo,pero [ProviderCompat] sin rodamiento
     *    campo de engranajes,Para implementarlo verdaderamente, necesitamos unificar los niveles de razonamiento.(Es decir, el proveedor solicita el montaje.),Más allá del alcance de esta ronda.
     *  - `compat.reasoningProfile`:`anthropic-adaptive*` / `kimi-openai` Este tipo de retrato del "pensamiento adaptativo",
     *    Aún no existe una rama de pensamiento adaptativo en el código.,No hay ningún campo de destino mapeable.
     */
    fun thinkingFormatOfCatalog(raw: String?): ThinkingFormat? = when (raw?.trim()?.lowercase()) {
        "deepseek" -> ThinkingFormat.DEEPSEEK
        "kimi" -> ThinkingFormat.KIMI
        "qwen" -> ThinkingFormat.QWEN
        "qwen-chat-template", "qwen_chat_template" -> ThinkingFormat.QWEN_CHAT_TEMPLATE
        "zhipu" -> ThinkingFormat.ZHIPU
        "openrouter" -> ThinkingFormat.OPENROUTER
        "volcengine" -> ThinkingFormat.VOLCENGINE
        "longcat" -> ThinkingFormat.LONGCAT
        else -> null
    }

    /**
     * v2.7: La tabla de contenidos es el formato de pensamiento declarado por un determinado modelo.;Devuelto si no está declarado o si el vocabulario no es mapeable null(Dáselo a una tabla codificada).
     *
     * Consulta calibre y [io.zer0.ai.registry.ModelRegistry.catalogEntryOf] consistente:Presione primero providerId +
     * modelId golpe preciso,Luego regrese a la búsqueda global por nombre de modelo. La derivación del período de solicitud solo se puede obtener modelId(ver [resolve]),
     * Echa un vistazo general;Proveedores creados por el usuario/El ID de proveedor de la estación de tránsito no coincide con el nombre del proveedor lógico en el directorio.,También realizar una encuesta global.
     *
     * límites conocidos:Consulta global sin ID de proveedor**Sin distinción entre proveedores** —— [ModelCatalog.entryByModelId]
     * Da el primer golpe en el orden de proveedores en el directorio.,Cuando varios proveedores declaran un modelo con el mismo nombre, gana el que aparece primero.,y sin ningún
     * Registro. Dos consecuencias:Si los valores son diferentes, se enviará silenciosamente el campo incorrecto.;Cuando sólo una de las partes declara,Los proveedores no declarados pedirán prestado a otra parte
     * Declarar en lugar de recurrir a una tabla codificada(Por ejemplo, glm-5.2 no está declarado en zhipu/zhipu-coding,lo tomaré prestado
     * opencode-go parte de). No hay ningún conflicto de valores en el directorio actual.,Sin embargo, si hay un conflicto de valores entre proveedores con el mismo nombre en el futuro,,
     * Esto debe cambiarse a una consulta con ID de proveedor.,No puede seguir utilizando índices globales.
     *
     * Desacuerdos conocidos(Directorio vs tabla codificada,1 lugar en total,Mantenga la prioridad del directorio, sin excepciones):
     * `opencode-go/glm-5.2` declaración de directorio `zhipu`,y `opencode.ai` La regla del anfitrión da `DEEPSEEK`.
     * La base para ese lado de la mesa es una reparación real de 2d15c2c3. 400,Pero solo cubre los modelos de la serie DeepSeek. reasoning_content
     * Reproducción,Los modelos GLM nunca han sido declarados.;En el lado del directorio están los metadatos por modelo declarados por el upstream,No probado localmente. Ambos lados del modelo.
     * No existe una base de prueba real,Así que manténgalo como "Directorio primero". Si el objeto pensante aparece en opencode.ai en el futuro, que es 400
     * retroalimentación,Debería recurrir a tablas codificadas(O cambiar los datos del directorio),En lugar de agregar excepciones de código aquí.
     */
    fun catalogThinkingFormat(modelId: String?, providerId: String? = null): ThinkingFormat? {
        if (modelId.isNullOrBlank()) return null
        val current = catalog
        if (current.providers.isEmpty()) return null
        val entry = providerId?.takeIf { it.isNotBlank() }?.let { current.entryOf(it, modelId) }
            ?: current.entryByModelId(modelId)
        return thinkingFormatOfCatalog(entry?.compat?.thinkingFormat)
    }

    /**
     * derivado [ProviderCompat].
     *
     * @param providerType Provider Tipo(Determinar las reglas básicas a nivel de protocolo)
     * @param baseUrl Provider de baseUrl(Ya sea analizado o crudo,Omitir la coincidencia de host cuando la cadena esté vacía)
     * @param modelId modelo concreto id(Opcional,para el refinamiento a nivel de modelo)
     */
    fun resolve(providerType: ProviderType, baseUrl: String, modelId: String? = null): ProviderCompat {
        // Nivel 1:Obtenga reglas básicas por tipo de proveedor
        var compat = baseForType(providerType)

        // Nivel 2:Refinar por host baseUrl
        val host = extractHost(baseUrl)
        // v1.0.53: Determinar si el host es un punto final conocido(oficial/Sitio de agregación).
        // Estación de transferencia desconocida(Como por ejemplo tokenrhythm.studio)El host no está en KNOWN_HOSTS,
        // El refinamiento del ID del modelo de capa 3 omitirá el pensamiento de extensión del proveedor Inyección de formato,Mantenga el campo del pensamiento en secreto.
        val hostKnown = host.isNotEmpty() && host in KNOWN_HOSTS
        if (host.isNotEmpty()) {
            compat = compat.overrideByHost(host)
        }

        // Nivel 3:Refinar por ID de modelo
        if (!modelId.isNullOrBlank()) {
            compat = compat.overrideByModelId(modelId, hostKnown)

            // v2.5.3 (P2-1): Declaración de reproducción a nivel de modelo —— Como funda.
            // Cuando las dos primeras capas no declaran un contrato de reproducción, declare la estrategia de reproducción de acuerdo con la identificación del modelo (que cubre escenarios de sitios de agregación/host desconocidos,
            // Por ejemplo, Kimi/DeepSeek también debería reproducir Reasoning_content si el host no coincide cuando se degrada la estación de agregación).
            // No se sobrescribirá cuando haya una declaración explícita (conexión directa oficial a la capa de host o capa de modelo).
            if (compat.reasoningReplayContract == null) {
                ModelReasoningReplayResolver.declare(modelId)?.let { decl ->
                    compat = compat.copy(
                        reasoningReplayContract =
                            ReasoningReplayContract(
                                decl.carrier,
                                decl.policy,
                                decl.requiresNonEmptyReasoning,
                            ),
                    )
                }
            }
        }

        // Nivel 4(máxima prioridad):Los directorios se declaran explícitamente.
        // prioridad:Directorio > tabla codificada > Valor predeterminado(null).
        // El índice se declara por modelo.,Más específico que adivinar la tabla por prefijo de nombre/host,Entonces se sobrescriben los resultados de las primeras tres capas.
        // Pero lo que describe es "la convención de campo de este modelo en el punto final oficial".;Correcto o no [KNOWN_HOSTS] Estación de transferencia de construcción propia en,
        // Todavía siga de manera conservadora el procesamiento de v1.0.53 —— Es posible que el relé desconocido no acepte pensar/enable_thinking, etc.
        // Campos de extensión del proveedor,La inyección incondicional 400,Este caso conserva los resultados de la tabla codificada.
        val catalogFormat = catalogThinkingFormat(modelId)
        if (catalogFormat != null && (host.isEmpty() || hostKnown)) {
            compat = compat.copy(thinkingFormat = catalogFormat)
        }

        return compat
    }

    /**
     * Nivel 1:prensa [ProviderType] Toma las reglas básicas.
     *
     * - OPENAI: Todos true(Predeterminado,Cubre OpenAI oficial + Personalizado + todas las transferencias compatibles con OpenAI)
     * - ANTHROPIC: No compatible developer role / store / json_mode / stream_options
     *   (Anthropic ir Messages API,system es un campo de nivel superior,Sin concepto de store/json_mode/stream_options)
     * - GEMINI: No compatible store(OpenAI Restricciones de la capa de compatibilidad) / reasoning_effort
     *   (Gemini Utilice thinkConfig.thinkingBudget en lugar de reasoning_effort)
     *
     * Nota:[ProviderType] Sin valor de enumeración PERSONALIZADO,Custom Tome la sucursal OPENAI(Predeterminado todo true),
     *   La diferencia se maneja mediante la coincidencia de hosts de capa 2.
     */
    private fun baseForType(providerType: ProviderType): ProviderCompat = when (providerType) {
        ProviderType.OPENAI -> ProviderCompat()
        ProviderType.ANTHROPIC -> ProviderCompat(
            supportsDeveloperRole = false,
            supportsStore = false,
            supportsJsonMode = false,
            supportsStreamOptions = false,
        )
        ProviderType.GEMINI -> ProviderCompat(
            supportsStore = false,
            supportsReasoningEffort = false,
        )
        // v1.0.7: OPENAI_RESPONSES ir Responses API,Las reglas básicas son las mismas que OPENAI,
        //   Pero el razonamiento vuelve a funcionar. REASONING_ITEMS carrier + PRESERVE policy.
        //   thinkingFormat quedarse null(Responses API Utilice usted mismo el campo razonamiento.esfuerzo,Manejado por la rama useResponseApi de OpenAIProvider)
        ProviderType.OPENAI_RESPONSES -> ProviderCompat(
            reasoningReplayContract = ReasoningReplayContract(
                ReasoningCarrier.REASONING_ITEMS,
                ReasoningReplayPolicy.PRESERVE,
            ),
        )
    }

    /**
     * Nivel 2:Refinar por anfitrión.
     *
     * Sólo manejamos domésticos conocidos./Diferencias en los puntos finales compatibles con OpenAI de fabricantes extranjeros:
     *
     * Conexión directa nacional:
     *  - api.deepseek.com: apoyo json_mode,No compatible reasoning_effort
     *    v1.0.7: thinkingFormat=DEEPSEEK(Sin parámetros de pensamiento,Solo consume streaming reasoning_content)
     *    v1.0.7: reasoningReplay=REASONING_CONTENT/REQUIRE_TOOL_CALL(Se requieren varias rondas de llamadas a herramientas reasoning_content)
     *  - api.moonshot.cn / api.kimi.com: apoyo json_mode(Kimi compatibles OpenAI json_object)
     *    v1.0.7: thinkingFormat=KIMI(thinking.type+keep),reasoningReplay=REASONING_CONTENT/REQUIRE_TOOL_CALL
     *  - open.bigmodel.cn / api.z.ai: No compatible store,No compatible reasoning_effort
     *    v1.0.7: thinkingFormat=ZHIPU(thinking.type+clear_thinking),reasoningReplay=REASONING_CONTENT/REQUIRE_TOOL_CALL
     *  - dashscope.aliyuncs.com / coding.dashscope.aliyuncs.com: No compatible store
     *    v1.0.7: thinkingFormat quedarse null(Refinamiento por capa modelId,Qwen3 usar QWEN/QWEN_CHAT_TEMPLATE)
     *  - api.xiaomimimo.com / token-plan-cn.xiaomimimo.com: v1.0.7 thinkingFormat=KIMI(Xiaomi MiMo usa algo similar a Kimi thinking.type)
     *  - api.longcat.chat: v1.0.7 thinkingFormat=LONGCAT(thinking.type)
     *  - ark.cn-beijing.volces.com: Predeterminado todo true;thinkingFormat quedarse null(Refinamiento por capa modelId doubao-thinking)
     *  - api.minimaxi.com: Ir al protocolo antrópico(por type=ANTHROPIC Procesamiento de reglas básicas)
     *  - api.baichuan-ai.com / api.lingyiwanwu.com / api.stepfun.com / api.hunyuan.cloud.tencent.com /
     *    qianfan.baidubce.com / api-inference.modelscope.cn / cloud.infini-ai.com / apihub.agnes-ai.com:
     *    Predeterminado todo true(thinkingFormat quedarse null,Protocolo estándar de razonamiento_esfuerzo)
     *
     * Conexión directa al extranjero:
     *  - api.x.ai / cli-chat-proxy.grok.com: Predeterminado todo true(xAI Grok,OpenAI compatibles)
     *  - api.perplexity.ai: Predeterminado todo true(Perplexity Sonar Serie)
     *  - api.groq.com: No compatible store(Groq Inferencia de latencia ultrabaja,Ninguno Assistants API)
     *  - api.together.xyz / api.deepinfra.com / api.fireworks.ai: Predeterminado todo true(agregación,Las diferencias son manejadas por modelId)
     *  - api.mistral.ai: No compatible store(Mistral La Plateforme)
     *  - api.githubcopilot.com / models.inference.ai.azure.com: Predeterminado todo true
     *  - localhost: Predeterminado todo true(Ollama locales,OpenAI compatibles)
     *
     * tránsito/Sitio de agregación(RELAY):
     *  - api.siliconflow.cn: Predeterminado todo true(thinkingFormat quedarse null,SiliconFlow Reúne a todos,Las diferencias son manejadas por modelId)
     *  - openrouter.ai: v1.0.7 thinkingFormat=OPENROUTER(reasoning.effort),reasoningReplay=REASONING_DETAILS/PRESERVE
     *
     * v1.0.1 (P3): RELAY Política predeterminada conservadora de la estación de tránsito — La capacidad de la estación de agregación para transmitir de forma transparente el modelo subyacente.,
     *  No se puede determinar el soporte de un modelo específico en la capa de host,Así que guárdalo todo true,
     *  Las restricciones conocidas del modelo se manejan mediante el refinamiento del modelId de capa 3.
     *
     * Los hosts no listados mantienen las reglas existentes sin cambios(default conservador true).
     */
    private fun ProviderCompat.overrideByHost(host: String): ProviderCompat {
        return when (host) {
            // ── Conexión directa nacional ──
            "api.deepseek.com" -> copy(
                supportsJsonMode = true,
                supportsReasoningEffort = false,
                // v1.0.7: DeepSeek Ir con el protocolo Reasoning_Content,No envíes ningún parámetro de pensamiento.
                thinkingFormat = ThinkingFormat.DEEPSEEK,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.6: api.kimi.com(Kimi Coding Plan)Pertenece al mismo lado oscuro de la luna que api.moonshot.cn,json_mode apoyo
            // v1.0.7: thinkingFormat=KIMI(thinking.type+keep)
            "api.moonshot.cn", "api.kimi.com" -> copy(
                supportsJsonMode = true,
                thinkingFormat = ThinkingFormat.KIMI,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.6: api.z.ai Es el punto final en el extranjero de Zhipu.,Mismas restricciones que open.bigmodel.cn
            // v1.0.7: thinkingFormat=ZHIPU(thinking.type+clear_thinking)
            "open.bigmodel.cn", "api.z.ai" -> copy(
                supportsStore = false,
                supportsReasoningEffort = false,
                thinkingFormat = ThinkingFormat.ZHIPU,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.6: coding.dashscope.aliyuncs.com Mismo límite que dashscope.aliyuncs.com
            // v1.0.7: thinkingFormat quedarse null(Refinamiento por capa modelId:qwen3-coder → QWEN_CHAT_TEMPLATE,Otros → QWEN)
            "dashscope.aliyuncs.com", "coding.dashscope.aliyuncs.com" -> copy(
                supportsStore = false,
            )
            // v1.0.6: api.minimaxi.com Ir al protocolo antrópico,por type=ANTHROPIC Procesamiento de reglas básicas
            // Mantenga compatible el punto final heredado api.minimax.chat(Los usuarios abandonados pero existentes aún pueden tenerlo)
            "api.minimax.chat" -> copy(
                supportsJsonMode = false,
            )
            "api.baichuan-ai.com" -> this // v1.0.1 (P3): Baichuan,Predeterminado todo true
            "api.lingyiwanwu.com" -> this // v1.0.1 (P3): cero una cosa,Predeterminado todo true
            "api.stepfun.com" -> this // v1.0.1 (P3): estrellas de paso,Predeterminado todo true
            "ark.cn-beijing.volces.com" -> this // v1.0.1 (P3): motor volcánico Doubao,Predeterminado todo true(thinking Refinamiento por modelId)
            // v1.0.6: Agregar proveedores nacionales(Todos son compatibles con OpenAI,Predeterminado todo true)
            "api.hunyuan.cloud.tencent.com" -> this // Tencent Hunyuan
            "qianfan.baidubce.com" -> this // Baidu Qian Fan
            "api-inference.modelscope.cn" -> this // Magia
            "cloud.infini-ai.com" -> this // Wuwen Xinqiong
            // v1.0.7: Xiaomi MiMo utiliza un protocolo de tipo think similar a Kimi
            "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com" -> copy(
                thinkingFormat = ThinkingFormat.KIMI,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            "apihub.agnes-ai.com" -> this // picante Agnes
            // v2.4.5: La temperatura de SenseNova/SenseCore es un rango abierto [0.0, 2.0),
            //   2.0 será upstream como 400 "field Temperature invalid" Rechazar.
            //   El límite superior del control deslizante de temperatura en la aplicación es exactamente 2.0,El valor de importación de tarjetas de personajes de terceros puede ser arbitrario.,Es necesario endurecer las exportaciones.
            "token.sensenova.cn", "api.sensenova.cn" -> copy(
                maxTemperatureExclusive = 2.0f,
            )
            // v1.0.7: Meituan LongCat utiliza el protocolo think.type
            "api.longcat.chat" -> copy(
                thinkingFormat = ThinkingFormat.LONGCAT,
            )

            // ── Conexión directa al extranjero ──
            // v1.0.6: cli-chat-proxy.grok.com es el punto final de respuestas xAI OAuth,Igual que api.x.ai xAI
            "api.x.ai", "cli-chat-proxy.grok.com" -> this // xAI Grok,Predeterminado todo true
            "api.perplexity.ai" -> this // v1.0.6: Perplexity,Predeterminado todo true
            "api.groq.com" -> copy(
                supportsStore = false, // Groq Ninguno Assistants API
            )
            "api.together.xyz" -> this // v1.0.1 (P3): Together AI,Predeterminado todo true
            "api.mistral.ai" -> copy(
                supportsStore = false, // Mistral Ninguno Assistants API
            )
            "api.deepinfra.com" -> this // v1.0.1 (P3): DeepInfra,Predeterminado todo true
            "api.fireworks.ai" -> this // v1.0.1 (P3): Fireworks AI,Predeterminado todo true
            "api.githubcopilot.com" -> this // v1.0.1 (P3): GitHub Copilot Models
            "models.inference.ai.azure.com" -> this // v1.0.1 (P3): GitHub Models
            "localhost" -> this // v1.0.6: Ollama locales,Predeterminado todo true

            // ── tránsito/Sitio de agregación(RELAY)── El default conservador lo es todo true,Refinamiento por capa modelId ──
            "api.siliconflow.cn" -> this
            // v1.0.7: OpenRouter ir reasoning.effort(Chat Completions Extensión de protocolo),
            //   reasoning Reproducir la matriz Reasoning_details,siempre reservado
            "openrouter.ai" -> copy(
                thinkingFormat = ThinkingFormat.OPENROUTER,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_DETAILS,
                    ReasoningReplayPolicy.PRESERVE,
                ),
            )
            // v1.x: Console Go(opencode.ai)Transferir el modelo de inferencia del sistema DeepSeek,
            //   Transmisión transparente del protocolo DeepSeek:thinking En modo, se deben devolver los mensajes históricos del asistente
            //   reasoning_content,De lo contrario regresar 400 "The reasoning_content in the
            //   thinking mode must be passed back to the API".
            "opencode.ai" -> copy(
                thinkingFormat = ThinkingFormat.DEEPSEEK,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            else -> this
        }
    }

    /**
     * Nivel 3:Refinar por modelId.
     *
     * Manejar solo restricciones explícitas a nivel de modelo(conservador,Sin especulaciones):
     *  - o1-preview / o1-mini: No es compatible con los primeros modelos o1 tool calling(Compatible con versiones posteriores)
     *  - deepseek-reasoner / deepseek-r1: DeepSeek-R1 modelo de inferencia,API La documentación no admite explícitamente tool calling
     *    (v1.0.1 P3: Algunas estaciones de transferencia utilizan deepseek-r1 en lugar de deepseek-reasoner,Ambos ID aquí están cubiertos.)
     *
     * v1.0.7 thinkingFormat Refinamiento a nivel de modelo(Cubriendo la capa huésped null):
     *  - qwen3-coder-*:QWEN_CHAT_TEMPLATE(chat_template_kwargs.enable_thinking)
     *  - qwen3-* (Otros):QWEN(enable_thinking + thinking_budget)
     *  - qwq-* / qwen-vl-*:QWEN(QwQ / Qwen-VL La serie es la misma que el protocolo de pensamiento Qwen3.)
     *  - doubao-*-thinking-*:VOLCENGINE(thinking.type)
     *  - glm-z1-*:ZHIPU(Si el anfitrión no coincide,Como la agregación SiliconFlow)
     *  - kimi-k1-* / kimi-k2-*:KIMI(Si el anfitrión no coincide,Como la agregación SiliconFlow)
     *  - deepseek-r1 / deepseek-reasoner:DEEPSEEK(Si el anfitrión no coincide,Como la agregación SiliconFlow)
     *  - longcat-*:LONGCAT(Si el anfitrión no coincide,Como la agregación SiliconFlow)
     *
     * v1.0.53: Extensión de proveedor thinkingFormat(Qwen/Doubao/GLM/Kimi/LongCat)solo en [hostKnown]=true
     *  inyectado cuando. Estación de transferencia desconocida(Como por ejemplo tokenrhythm.studio)Los campos de extensión como think/enable_thinking no son compatibles,
     *  La inyección incondicional resultará en HTTP 400 "Campo de solicitud desconocido".
     *  DeepSeek-R1 excepción:ThinkingFormat.DEEPSEEK No envíes ningún parámetro de pensamiento.,Solo consume streaming reasoning_content,
     *  Transparente para estaciones de transferencia,Por lo tanto, no se necesita ningún guardia conocido como host.
     *
     * Otras series o1/o3/o4 reservadas supportsToolCalling=true(Compatible con versiones posteriores,
     * No hay que adivinar la versión para evitar una activación errónea). Si más tarde se descubre que la versión específica no es compatible,Se puede agregar aquí.
     */
    private fun ProviderCompat.overrideByModelId(modelId: String, hostKnown: Boolean): ProviderCompat {
        val id = stripAggregatorPrefix(modelId)
        return when {
            // No compatible con los primeros modelos de inferencia de o1 tool calling(OpenAI La documentación oficial ha declarado)
            id == "o1-preview" || id == "o1-mini" -> copy(
                supportsToolCalling = false,
            )
            // DeepSeek-R1 El modelo de inferencia no soporta tool calling
            // v1.0.1 (P3): deepseek-reasoner(oficial)y deepseek-r1(Alias de estación de transferencia)Todo cubierto
            id == "deepseek-reasoner" || id == "deepseek-r1" -> copy(
                supportsToolCalling = false,
                // v1.0.7: Incluso en sitios de agregación(SiliconFlow / OpenRouter espera)en,DeepSeek-R1 Sigo usando el protocolo DEEPSEEK
                // v1.0.53: DEEPSEEK No envíes ningún parámetro de pensamiento.,Transparente para estaciones de transferencia,No hay necesidad de hostguardias conocidos
                thinkingFormat = ThinkingFormat.DEEPSEEK,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7+: el resto deepseek-* modelo(deepseek-chat/deepseek-v4-flash esperando oficial+estación de transferencia)
            // Siga también el protocolo DEEPSEEK.DeepSeek El modo de pensamiento de la API oficial requiere mensajes históricos del asistente
            // volver reasoning_content,De lo contrario, varias rondas/Vuelve el bucle de herramientas 400
            // ("The reasoning_content in the thinking mode must be passed back to the API").
            // DEEPSEEK thinkingFormat No envíe ningún parámetro de pensamiento.,Transparente a puntos de tránsito desconocidos,No hay necesidad de hostguardias conocidos;
            // La repetición sólo tiene efecto cuando el mensaje histórico realmente contiene razonamientos.,Las conversaciones normales no se ven afectadas.
            id.startsWith("deepseek-") -> copy(
                thinkingFormat = ThinkingFormat.DEEPSEEK,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7: Qwen3-Coder serie ir chat_template_kwargs.enable_thinking
            //   (Qwen3-Coder Los modelos utilizan el protocolo chat_template,enable_thinking Debe estar integrado en chat_template_kwargs)
            // v1.0.53: Única inyección de huésped conocida,El relé desconocido no admite el campo chat_template_kwargs
            id.startsWith("qwen3-coder") && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.QWEN_CHAT_TEMPLATE,
            )
            // v1.0.7: Qwen3 / QwQ / Qwen-VL serie ir enable_thinking + thinking_budget
            // v1.0.53: Única inyección de huésped conocida,El relé desconocido no admite el campo enable_thinking
            (id.startsWith("qwen3") || id.startsWith("qwq-") || id.startsWith("qwen-vl")) && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.QWEN,
            )
            // v1.0.7: Volcano Engine Doubao Thinking Series Go thinking.type
            // v1.0.53: Única inyección de huésped conocida,La estación de transferencia desconocida no admite el campo de pensamiento
            id.startsWith("doubao") && id.contains("thinking") && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.VOLCENGINE,
            )
            // v1.0.7: Espectro inteligente serie GLM-Z1 thinking.type + clear_thinking(Anular escenarios incomparables del host,Como sitio de agregación)
            // v1.0.53: Única inyección de huésped conocida,La estación de transferencia desconocida no admite el campo de pensamiento
            (id.startsWith("glm-z1") || id.startsWith("glm-4-thinking")) && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.ZHIPU,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7: Kimi K1/K2 serie ir thinking.type + keep(Anular escenarios incomparables del host)
            // v1.0.53: Única inyección de huésped conocida,Estación de transferencia desconocida(Como por ejemplo tokenrhythm.studio)El campo del pensamiento no es compatible.,
            //   La inyección incondicional resultará en HTTP 400 "Campos de solicitud desconocidos:thinking"
            (id.startsWith("kimi-k1") || id.startsWith("kimi-k2")) && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.KIMI,
                reasoningReplayContract = ReasoningReplayContract(
                    ReasoningCarrier.REASONING_CONTENT,
                    ReasoningReplayPolicy.REQUIRE_TOOL_CALL,
                ),
            )
            // v1.0.7: Serie Meituan LongCat(Anular escenarios incomparables del host)
            // v1.0.53: Única inyección de huésped conocida,La estación de transferencia desconocida no admite el campo de pensamiento
            id.startsWith("longcat") && hostKnown -> copy(
                thinkingFormat = ThinkingFormat.LONGCAT,
            )
            else -> this
        }
    }

    /** Eliminar sitio de agregación/Después de utilizar el prefijo del fabricante para identificar la familia de modelos, la identificación original todavía se utiliza para las solicitudes reales. */
    private fun stripAggregatorPrefix(modelId: String): String {
        val raw = modelId.trim().lowercase()
        // v2.6.1: Primero retire la etiqueta del paquete principal de la estación de tránsito.(Como por ejemplo `[ais]gemini-3.8-flash`).
        val deLabeled = io.zer0.ai.core.stripChannelGroupLabels(raw)
        val prefixes = listOf(
            "openrouter/", "opencode-go/", "siliconflow/", "dashscope/",
            "deepseek-ai/", "zhipu/", "qwen/", "openai/", "anthropic/",
        )
        return prefixes.firstOrNull { deLabeled.startsWith(it) }?.let(deLabeled::removePrefix)
            ?: deLabeled.substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: deLabeled
    }

    /**
     * Extracto de URLbase host(minúscula,ir al puerto).
     *
     * Compatible con los siguientes formularios:
     *  - https://api.openai.com/v1
     *  - http://localhost:8080/v1
     *  - api.deepseek.com
     *  - Cadena vacía/Excepción → Devolver cadena vacía(Depende de la persona que llama omitir la coincidencia de host)
     */
    private fun extractHost(baseUrl: String): String {
        val trimmed = baseUrl.trim().ifBlank { return "" }
        return try {
            // Implementación sencilla:Eliminar prefijo de protocolo,Consigue la parte anterior a la primera /,ir al puerto
            val noScheme = trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .lowercase()
            noScheme.substringBefore(':')
        } catch (t: Throwable) {
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            Logger.w(TAG, "extract host falló(baseUrl=$baseUrl): ${t.message}")
            ""
        }
    }
}
