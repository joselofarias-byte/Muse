package io.zer0.muse.tools

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.skill.SkillEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * v0.23: Skill importador — Analice el archivo .skill.json y verifíquelo.
 *
 * .skill.json Formato(Los usuarios pueden compartir/Archivo de definición de habilidades importado):
 * ```json
 * {
 *   "id": "fetch_weather",
 *   "name": "comprobar el clima",
 *   "description": "Consulta el clima de una ciudad específica",
 *   "parametersJson": "{\"type\":\"object\",...}",
 *   "requiredJson": "[\"city\"]",
 *   "implementationKotlin": "http_get",
 *   "category": "custom"
 * }
 * ```
 *
 * restricciones de seguridad:
 *  - [implementationKotlin] debe ser [SkillExecutor] Una de las implementaciones integradas admitidas.
 *    (read_file / write_file / http_get / http_post),En caso contrario se rechaza la importación.
 *    Esto garantiza que la habilidad importada por el usuario no ejecutará ningún código.,Reutilice únicamente funciones de Kotlin predefinidas.
 *  - La habilidad de palabra rápida es la única excepción.:Declaración `implementationKotlin = "prompt"` Usar juntos `prompt` campo
 *    dar texto de comando,El código al entrar al almacén es `implementationKotlin = "prompt:<texto>"`(ver [PROMPT_PREFIX]).
 *    El texto de las instrucciones solo está sujeto al límite de 8 KB.,**No sujeto a la lista negra de palabras clave inyectadas**(Compensaciones de productos,ver [safetyReview]);
 *    No se ejecuta ningún código Kotlin durante la ejecución.
 *  - Utilice valores predeterminados razonables para los campos faltantes.
 *
 * Phase 2(Generación de LLM durante la sesión)Se utilizará el mismo formato y verificación para el almacenamiento.
 */
object SkillImporter {

    /**
     * Implementación incorporada de lista blanca de claves(Consistente con la rama when de SkillExecutor.execute).
     * v0.24: Se agregaron nuevas implementaciones de clases de búsqueda y adquisición de información.
     *
     * M-SI2: Las habilidades de importación de usuarios se limitan a reutilizar las siguientes implementaciones,No incluye autoexpansión/gestión/interacción/Tipo de destrucción
     * (install_skill / author_plugin / delegate_agent / channel_* / agent_phone /
     * task_plan / update_plan_step / list_skills / uninstall_skill / disable_skill /
     * delete_file No puedo esperar para abrirlo a los usuarios. skill).
     * v1.0.92 (A-2): Exploración relajante de solo lectura e implementación de clases de utilidad de bajo riesgo,Alineado con la capacidad de ejecución de enrutamiento.
     */
    val ALLOWED_IMPLEMENTATIONS: Set<String> = setOf(
        // v0.22 Archivo/HTTP Conceptos básicos
        "read_file", "write_file", "http_get", "http_post",
        // v0.24 Búsqueda y adquisición de información.(Las habilidades importadas por el usuario pueden reutilizar estas implementaciones)
        "web_search", "web_fetch", "knowledge_search",
        "arxiv_search",
        // v1.0.92 (A-2): clase de exploración de archivos(solo lectura)
        "list_dir", "file_exists", "read_public_file", "list_public_files",
        // v1.0.92 (A-2): Descargar y guardar(Las escrituras caen solo en el área de descarga pública.)
        "file_download", "save_to_downloads",
        // v1.0.92 (A-2): Utilidad general
        "translate", "generate_qr", "generate_image", "list_stickers",
        "send_sticker",
    )

    /**
     * Habilidades de palabras rápidas(prompt skill):El contenido es un texto de comando.,No se implementa ninguna implementación de Kotlin.
     *
     * ## codificación de almacenamiento
     *
     * Room schema No se debe cambiar,Reutilizar existente [SkillEntity.implementationKotlin] columna de texto,
     * distinguir el prefijo [PROMPT_PREFIX] codificación:
     *  - en la base de datos:`implementationKotlin = "prompt:<texto de comando>"`(Deja el texto como está,Sin escape);
     *  - `.skill.json` Al importar:`implementationKotlin = "prompt"`,poner texto de comando `prompt`
     *    campo(compatibles `promptText` Alias);También se acepta transmisión directa. `"prompt:<texto de comando>"`.
     *
     * ## reglas de análisis
     *
     *  - [isPromptSkill]:`implementationKotlin == "prompt"` o `startsWith("prompt:")`;
     *  - [decodePromptText]:despegar `prompt:` el prefijo obtiene el texto del comando(Regreso de habilidades con palabras sin indicaciones null);
     *  - ejecutado por `SkillManagementToolsImpl.execPromptSkill` Cambie el texto a `[comando de habilidad]` prefijo
     *    volver al modelo,no acepta parametros(Los parámetros pasados ​​por la persona que llama se ignoran).
     *
     * Razones para elegir prefijos en lugar de nuevas columnas:Evitar la migración de habitaciones(El esquema del proyecto es mantenido uniformemente por el agente principal.),
     * Y la columna es"como ejecutar"clave de enrutamiento,Discriminación de prefijos y `plugin:` Consistente con el acuerdo existente.
     */
    const val PROMPT_PREFIX = "prompt:"

    /** `.skill.json` Habilidad de palabra rápida de declaración media [SkillEntity.implementationKotlin] Toma valor. */
    const val PROMPT_IMPLEMENTATION_KEY = "prompt"

    /** Límite máximo de longitud de texto para instrucciones rápidas sobre habilidades con palabras(Número de caracteres,Alineación 8KB). Si el archivo es demasiado largo, la importación será directamente rechazada. */
    const val MAX_PROMPT_CHARS = 8 * 1024

    /**
     * parámetro vacío JSON Schema — Las habilidades con palabras rápidas y sin parámetros se utilizan de manera uniforme.
     *
     * No es necesario utilizar palabras literales. `{}`:OpenAI Los puntos finales compatibles requieren el tipo de objeto `properties` existir,
     * y ChatStreamCoordinator.normalizeSkillSchema solo falta `type` Es hora de compensar type,
     * `{}` producirá `{"type":"object"}` Rechazado por un proveedor estricto.
     */
    const val EMPTY_PARAMETERS_JSON = """{"type":"object","properties":{}}"""

    /** [EMPTY_PARAMETERS_JSON] El resultado del análisis de,El valor de almacenamiento normalizado de habilidad de palabra rápida y habilidad sin parámetro. */
    private val EMPTY_PARAMS_OBJECT: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { })
    }

    /** Si se trata de una habilidad de palabra rápida(ver [PROMPT_PREFIX] Instrucciones de codificación para). */
    fun isPromptSkill(implementationKotlin: String): Boolean =
        implementationKotlin == PROMPT_IMPLEMENTATION_KEY || implementationKotlin.startsWith(PROMPT_PREFIX)

    /**
     * Analizar el texto del comando para obtener habilidades con las palabras(despegar `prompt:` prefijo).
     * @return Regreso de habilidades con palabras sin indicaciones null;`prompt:` Devuelve una cadena vacía si hay texto vacío después
     */
    fun decodePromptText(implementationKotlin: String): String? = if (implementationKotlin.startsWith(PROMPT_PREFIX)) {
        implementationKotlin.removePrefix(PROMPT_PREFIX)
    } else {
        null
    }

    /** El texto de instrucción de habilidades de palabras de indicación de codificación es [SkillEntity.implementationKotlin] Forma de almacenamiento. */
    fun encodePromptText(text: String): String = PROMPT_PREFIX + text

    /**
     * H-SI1: Colección de identificación retenida incorporada — Las habilidades importadas por el usuario no pueden utilizar estas id,
     * Evite que LLM sobrescriba el sistema integrado a través de install_skill skill(Como por ejemplo id="read_file" Anular herramientas integradas).
     * Fuente:[SkillExecutor.BUILT_IN_SKILLS] Los identificadores de todas las habilidades integradas en .
     *
     * v1.0.4 Reparación HTTP 400 "Tool names must be unique":Bloquear simultáneamente [ToolRegistry.BUILT_IN_TOOL_IDS]
     * Todos los nombres de herramientas integradas en,Evitar que los usuarios importen archivos con el mismo nombre que las herramientas locales skill(Como por ejemplo generate_image / calculator / notify espera),
     * De lo contrario, cuando el asistente habilita la habilidad y la herramienta local con el mismo nombre al mismo tiempo,,Se emiten herramientas duplicadas que activan DeepSeek/Relay 400.
     */
    val RESERVED_IDS: Set<String> =
        SkillExecutor.BUILT_IN_SKILLS.map { it.id }.toSet() + ToolRegistry.BUILT_IN_TOOL_IDS.toSet()

    /** Analiza los resultados. */
    sealed class Result {
        data class Ok(val skill: SkillEntity) : Result()
        data class Err(val reason: String) : Result()
    }

    /** L-SI6: id Babosa legal regular(letras minúsculas/numeros/subrayado/guión). */
    private val ID_REGEX = Regex("^[a-z0-9_-]+$")

    /**
     * B-19: El usuario importa la lista blanca de categorías permitidas por la habilidad.
     *
     * Documentación de valor e install_skill(SkillExecutor)consistente:file/http/search/knowledge/system/agent/sticker/custom,
     * También compatible con categorías de usuarios existentes(SkillManagementToolsImpl prensa "user"/"custom" Filtrar usuario propio skill).
     *
     * razones de seguridad:
     *  - category="plugin" Establecido exclusivamente al crear una habilidad de complemento real por parte de PluginManager(PluginManager.kt category="plugin"),
     *    ChatStreamCoordinator usar listEnabled().filter { it.category == "plugin" } pon estos skill
     *    Fusionar incondicionalmente definiciones de herramientas en cada sesión(Omitir la lista blanca de skillsIdsJson de ayuda).
     *    Si los usuarios pueden importar habilidades y completarlas ellos mismos "plugin",Puedes pretender ser un complemento y saltarte la lista blanca. → B-19 Ultra vires.
     *  - Por lo tanto, el complemento no está en la lista blanca de importación.,Rechazar explícitamente al importar.
     */
    private val ALLOWED_CATEGORIES: Set<String> = setOf(
        "file", "http", "search", "knowledge", "system", "agent", "sticker", "custom", "user",
    )

    /**
     * Lista blanca de tipos de parámetros — El tipo de cada propiedad en los parámetros Json de la habilidad personalizada debe estar en esta colección.
     * prohibido function / null / any tipo difuso,Reducir el riesgo de que LLM genere parámetros ilegales.
     */
    private val ALLOWED_PARAM_TYPES: Set<String> = setOf("string", "integer", "number", "boolean", "array", "object")

    /**
     * Prompt injection / Lista negra de palabras clave de ingeniería social(No distingue entre mayúsculas y minúsculas).
     * Si estas palabras aparecen en el nombre/descripción,Indica que esta habilidad puede intentar anular las indicaciones del sistema, cambiar el comportamiento de la IA o inducir al usuario.
     */
    private val BLOCKED_KEYWORDS: List<String> = listOf(
        "system prompt", "system instruction", "ignore previous", "ignore all", "ignore the",
        "override", "jailbreak", "dan mode", "do anything now", "developer mode",
        "you are now", "you are a", "pretend to be", "roleplay as",
        "ignorar antes", "ignorar anterior", "ignorar arriba", "instrucciones anteriores", "ignora todas",
        "sistema de superposición", "Aviso del sistema", "tu eres", "tu eres ahora",
        "jugar", "fingir", "no decirle a los usuarios", "no revelar", "Confidencial",
    )

    /**
     * Prefijo de identificación del sistema sensible/lista negra de subcadenas — Evite que LLM se disfrace de herramientas del sistema utilizando ID como admin/system/root.
     */
    private val BLOCKED_ID_SUBSTRINGS: List<String> = listOf(
        "system", "admin", "root", "builtin", "internal", "dev", "debug", "master",
        "superuser", "owner", "config",
    )

    /**
     * autorización de seguridad:Compruebe si la habilidad generada por LLM contiene inyección rápida, ingeniería social o permisos excesivos.
     * Devolver motivo específico en caso de falla,Devuelve nulo en caso de éxito.
     *
     * @param promptText Texto de comando para habilidad con palabras rápidas(Las habilidades con palabras que no se solicitan son null);
     *   No nulo significa que esta es una habilidad de palabra rápida,saltar ahora [BLOCKED_KEYWORDS] Lista negra de palabras clave
     */
    private fun safetyReview(id: String, name: String, description: String, params: JsonObject, promptText: String? = null): String? {
        // 1. Comprobación de longitud básica
        if (name.length < 2) return "name Longitud demasiado corta(al menos 2 caracteres)"
        if (description.length < 5) return "description Longitud demasiado corta(Al menos 5 caracteres),Agregue el propósito y los escenarios desencadenantes de esta habilidad."

        // 2. id lista negra
        val lowerId = id.lowercase()
        for (sub in BLOCKED_ID_SUBSTRINGS) {
            if (lowerId.contains(sub)) {
                return "id '$id' Contiene palabras reservadas '$sub',No está permitido pretender ser un sistema. skill"
            }
        }

        // 3. name / description Lista negra de palabras clave(No distingue entre mayúsculas y minúsculas)
        //
        // Habilidades para escribir palabras rápidas**No sujeto a esta lista negra**:Su contenido es originalmente una instrucción de trabajo del modelo.,Y la lista de palabras clave
        // (Contiene "tú eres", "jugar", etc.)Una gran cantidad de comandos chinos normales se considerarán erróneamente como inyección,Entra directamente en conflicto con el propósito de este tipo.
        // El umbral restante para las habilidades con palabras rápidas es:crear/Las actualizaciones deben ser aprobadas por el usuario y el texto de instrucciones tiene una longitud máxima.(8KB),
        // Al ejecutar, utilice "[comando de habilidad] El siguiente contenido no es una inyección de fotogramas del "comando del sistema". Esta es una clara compensación de producto.,
        // No es una omisión——Para restaurar las restricciones,Simplemente cambie las condiciones aplicables en el siguiente párrafo para incluir el texto inmediato.
        if (promptText == null) {
            val combinedText = "$name $description".lowercase()
            for (keyword in BLOCKED_KEYWORDS) {
                if (combinedText.contains(keyword.lowercase())) {
                    return "name / description Contiene contenido no permitido('$keyword'),Puede implicar la inyección rápida de palabras o anulaciones de comportamiento."
                }
            }
        }

        // 4. parametersJson inspección estructural
        val type = params["type"]?.jsonPrimitive?.contentOrNull
        if (type != "object") {
            return "parametersJson El tipo debe ser 'object',En realidad '$type'"
        }
        val properties = params["properties"]?.jsonObject
        if (properties == null) {
            return "parametersJson objeto de propiedades faltantes"
        }
        for ((paramName, paramDef) in properties) {
            if (paramDef !is JsonObject) return "parámetros '$paramName' La definición de debe ser un objeto JSON."
            val paramType = paramDef["type"]?.jsonPrimitive?.contentOrNull
            if (paramType.isNullOrBlank()) return "parámetros '$paramName' Campo de tipo faltante"
            if (paramType !in ALLOWED_PARAM_TYPES) {
                return "parámetros '$paramName' tipo '$paramType' No dentro del rango permitido($ALLOWED_PARAM_TYPES)"
            }
        }

        return null
    }

    /**
     * v1.0.92: Exportar paquete de habilidades(Recipe) — Múltiples habilidades empaquetadas en una sola JSON,Fácil de compartir e importar por lotes.
     *
     * Formato: {"recipe":1,"name":"...","exportedAt":123,"skills":[{...soltero skill json...}]}
     * Cada elemento está relacionado con [parse] Leer alineación de campo,puede ser [parseRecipe] Importación por lotes.
     */
    fun exportRecipe(skills: List<SkillEntity>, name: String = "Muse Skills"): String {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("recipe", 1)
            put("name", name)
            put("exportedAt", System.currentTimeMillis())
            put(
                "skills",
                kotlinx.serialization.json.buildJsonArray {
                    skills.forEach { s ->
                        add(
                            kotlinx.serialization.json.buildJsonObject {
                                put("id", s.id)
                                put("name", s.name)
                                put("description", s.description)
                                put("category", s.category)
                                put("implementationKotlin", s.implementationKotlin)
                                put("parametersJson", s.parametersJson)
                                put("requiredJson", s.requiredJson)
                            },
                        )
                    }
                },
            )
        }
        return AppJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }

    /** v1.0.92: Resultados del análisis del paquete de habilidades. */
    sealed interface RecipeParseResult {
        data class Ok(val skills: List<SkillEntity>) : RecipeParseResult
        data class Err(val reason: String) : RecipeParseResult
    }

    /**
     * v1.0.92: Paquete de habilidades analíticas(Recipe)Y revisa uno por uno.
     *
     * Cada habilidad en el paquete está relacionada con [parse] Misma lista blanca/Clasificación/Verificación rápida de palabras;
     * Los fracasos individuales no bloquearán otras habilidades,Regresa cuando todo falla [RecipeParseResult.Err].
     */
    fun parseRecipe(jsonText: String): RecipeParseResult {
        val root = resultOf {
            AppJson.decodeFromString(kotlinx.serialization.json.JsonObject.serializer(), jsonText)
        }.getOrNull() ?: return RecipeParseResult.Err("JSON El análisis falló")
        val arr = root["skills"] as? kotlinx.serialization.json.JsonArray
            ?: return RecipeParseResult.Err("No en formato de paquete de habilidades(Conjunto de habilidades faltantes)")
        if (arr.isEmpty()) return RecipeParseResult.Err("El paquete de habilidades está vacío")
        val valid = mutableListOf<SkillEntity>()
        val errors = mutableListOf<String>()
        arr.forEachIndexed { index, element ->
            val item = element as? kotlinx.serialization.json.JsonObject ?: return@forEachIndexed
            when (val r = parse(item.toString())) {
                is Result.Ok -> valid += r.skill
                is Result.Err -> errors += "#${index + 1}: ${r.reason}"
            }
        }
        return if (valid.isEmpty()) {
            RecipeParseResult.Err("No hay habilidades importables en el paquete.: ${errors.take(3).joinToString("; ")}")
        } else {
            RecipeParseResult.Ok(valid)
        }
    }

    /**
     * v1.0.92: Exporte una sola habilidad como texto .skill.json.
     *
     * campos con [parse] Las claves de lectura corresponden uno a uno.(id/name/description/category/
     * implementationKotlin/parametersJson/requiredJson),
     * El texto exportado se puede volver a importar directamente(Implementación incorporada o habilidades de palabras rápidas).
     */
    fun exportToJson(skill: SkillEntity): String {
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("id", skill.id)
            put("name", skill.name)
            put("description", skill.description)
            put("category", skill.category)
            put("implementationKotlin", skill.implementationKotlin)
            put("parametersJson", skill.parametersJson)
            put("requiredJson", skill.requiredJson)
        }
        return AppJson.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Analice el texto .skill.json y verifíquelo.
     * @param jsonText Contenido del archivo
     * @return [Result.Ok] Incluye almacenaje [SkillEntity];[Result.Err] Incluyendo razones de falla
     */
    fun parse(jsonText: String): Result {
        // L-SI5: Utilice resultOf en su lugar para mantener el estilo consistente(Lanzamiento correcto CancellationException)
        val raw: JsonObject = resultOf {
            AppJson.decodeFromString(JsonObject.serializer(), jsonText)
        }.onError { msg, _ ->
            Logger.w("SkillImporter", "JSON El análisis falló: $msg")
        }.getOrNull() ?: return Result.Err("JSON El análisis falló")

        val id = raw["id"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return Result.Err("Falta el campo de identificación")
        // L-SI6: Comprobar longitud de identificación(1..64)y legal slug(solo letras minusculas/numeros/subrayado/guión)
        if (id.isBlank() || id.any { it.isWhitespace() }) {
            return Result.Err("id No puede estar vacío ni contener espacios en blanco.")
        }
        if (id.length > 64) {
            return Result.Err("id No puede exceder los 64 caracteres de longitud(actual ${id.length})")
        }
        if (!ID_REGEX.matches(id)) {
            return Result.Err("id Solo puede contener letras minúsculas, números, guiones bajos y guiones.(Como por ejemplo fetch_weather / my-skill-1)")
        }
        // H-SI1: La identificación de verificación no está en la colección de identificación reservada incorporada,Evitar la sobrescritura incorporada skill
        if (id in RESERVED_IDS) {
            return Result.Err("id '$id' Conflictos con habilidades incorporadas,No se permite anular el incorporado skill,Por favor usa otro id")
        }

        val name = raw["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return Result.Err("Falta el campo de nombre")
        val description = raw["description"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        val parametersJson = raw["parametersJson"]?.jsonPrimitive?.contentOrNull ?: "{}"
        val requiredJson = raw["requiredJson"]?.jsonPrimitive?.contentOrNull ?: "[]"
        val implementationKotlin = raw["implementationKotlin"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return Result.Err("Falta implementación del campo Kotlin")
        val category = raw["category"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { "custom" } ?: "custom"
        // B-19: Realice la verificación de la lista blanca de categorías al importar. Predeterminado/respaldo vacío "custom"(compatible con versiones anteriores);
        // Los valores que no están en la lista blanca siempre se rechazan,especialmente defensivo category="plugin" Finge eludir la lista blanca del asistente.
        if (category !in ALLOWED_CATEGORIES) {
            return Result.Err(
                "category '$category' No dentro del rango permitido($ALLOWED_CATEGORIES);" +
                    "Las habilidades importadas no pueden declararse como 'plugin'(Las habilidades de complementos son administradas exclusivamente por el administrador de complementos.)",
            )
        }

        val isPrompt = isPromptSkill(implementationKotlin)
        if (!isPrompt && implementationKotlin !in ALLOWED_IMPLEMENTATIONS) {
            return Result.Err(
                "implementationKotlin Debe ser una de las implementaciones integradas.: $ALLOWED_IMPLEMENTATIONS o habilidades de palabras rápidas key '$PROMPT_IMPLEMENTATION_KEY'," +
                    "En realidad '$implementationKotlin'. Las habilidades personalizadas solo pueden reutilizar funciones de Kotlin predefinidas o usar habilidades de palabras rápidas(No admite la ejecución de código arbitrario).",
            )
        }

        // Habilidades de palabras rápidas:Extraer y verificar el texto del comando;sin parámetros(parametersJson/requiredJson Fijado al vacio)
        var promptText: String? = null
        val storedImplementation: String
        val validatedParams: JsonObject
        val validatedRequiredJson: String
        if (isPrompt) {
            promptText = when {
                implementationKotlin.startsWith(PROMPT_PREFIX) ->
                    implementationKotlin.removePrefix(PROMPT_PREFIX).trim()
                else -> stringField(raw, "prompt") ?: stringField(raw, "promptText") ?: ""
            }.trim()
            if (promptText.isEmpty()) {
                return Result.Err(
                    "prompt El texto del comando de la habilidad no puede estar vacío.: Utilice el campo de solicitud para proporcionar el texto del comando," +
                        "O escriba implementaciónKotlin como '$PROMPT_PREFIX<texto de comando>'",
                )
            }
            if (promptText.length > MAX_PROMPT_CHARS) {
                return Result.Err(
                    "prompt Texto de comando demasiado largo(${promptText.length} personaje,límite superior $MAX_PROMPT_CHARS)," +
                        "Simplifique y vuelva a intentarlo.",
                )
            }
            storedImplementation = encodePromptText(promptText)
            validatedParams = EMPTY_PARAMS_OBJECT
            validatedRequiredJson = "[]"
        } else {
            // M-SI3: Verifique si parámetrosJson es un objeto JSON legal
            validatedParams = resultOf {
                AppJson.decodeFromString(JsonObject.serializer(), parametersJson)
            }.getOrNull() ?: return Result.Err("parametersJson No es un objeto JSON válido: $parametersJson")
            // Verifique si requireJson es una matriz JSON legal
            resultOf {
                AppJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()),
                    requiredJson,
                )
            }.getOrNull() ?: return Result.Err("requiredJson No es una matriz JSON válida: $requiredJson")
            validatedRequiredJson = requiredJson
            storedImplementation = implementationKotlin
        }

        // M-SI4: autorización de seguridad(prompt injection / Ingeniería social/tipo de parámetro ilegal)
        safetyReview(id, name, description, validatedParams, promptText)?.let {
            Logger.w("SkillImporter", "skill '$id' Error en la revisión de seguridad: $it")
            return Result.Err("Error en la revisión de seguridad: $it")
        }

        val now = System.currentTimeMillis()
        return Result.Ok(
            SkillEntity(
                id = id,
                name = name,
                description = description,
                parametersJson = validatedParams.toString(),
                requiredJson = validatedRequiredJson,
                implementationKotlin = storedImplementation,
                enabled = true,
                category = category,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * suministro `update_skill` Reutilizar el mismo conjunto de controles de importación:Reúna los campos actualizados en equivalentes .skill.json
     * ir de nuevo [parse],Garantizar restricciones en las rutas de actualización y de instalación.(Mantener lista blanca de ID/categoría /
     * Implementar lista blanca/lista negra de inyección/límite de texto rápido)Totalmente consistente.
     *
     * @return [Result.Ok] Contiene nuevas entidades después de la verificación.(createdAt/enabled Anulado por la persona que llama)
     */
    fun validateForUpdate(
        id: String,
        name: String,
        description: String,
        parametersJson: String,
        requiredJson: String,
        implementationKotlin: String,
        category: String,
    ): Result {
        val json = buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", description)
            put("parametersJson", parametersJson)
            put("requiredJson", requiredJson)
            put("implementationKotlin", implementationKotlin)
            put("category", category)
        }.toString()
        return parse(json)
    }

    /** Leer campo de cadena;Devuelto si falta el campo o no hay una cadena null(No lanzar excepción). */
    private fun stringField(raw: JsonObject, name: String): String? = (raw[name] as? JsonPrimitive)?.contentOrNull
}
