package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.image.ImageService
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.tools.script.PythonSkillEngine
import io.zer0.muse.tools.script.SkillBridge
import io.zer0.muse.tools.script.SkillEngineResult
import io.zer0.muse.tools.script.WebViewSkillEngine
import io.zer0.muse.ui.qrcode.QrCodeGenerator

/**
 * P1-3b Deslocalización:Skill medios de comunicación/JS/Implementación de herramienta de complemento (migrada desde SkillExecutor.kt).
 * Llamado por el delegado de SkillExecutor.
 */
class SkillMediaToolsImpl(
    private val context: Context,
    private val stickerLibraryRepository: StickerLibraryRepository?,
    private val imageService: ImageService?,
    private val imageDrawConfigProvider: suspend () -> Pair<ProviderConfig?, String?> = { null to null },
    private val pluginManager: PluginManager?,
    /**
     * Interruptor maestro enchufable (habilitado de forma predeterminada).
     *
     * Utilice lambda en lugar de depender directamente de SettingsRepository: esta clase se construye en muchos lugares (incluidas las pruebas).
     * Agregar una dependencia opcional es más seguro que cambiar todos los puntos de construcción; y [imageDrawConfigProvider] Mismo tipo.
     */
    private val pluginsGloballyEnabled: suspend () -> Boolean = { true },
) {

    suspend fun execListStickers(args: Map<String, String>): String {
        val repo = stickerLibraryRepository
            ?: return context.getString(R.string.skill_sticker_not_configured)
        val category = args["category"]?.takeIf { it.isNotBlank() }
        // v1.0.54: Si no hay ningún parámetro de categoría, solo se devolverá la descripción general de la categoría.(Nombre de la categoría + cantidad),
        //   Ya no enumera todos los emoticonos en su totalidad — Cuando hay muchos emoticonos, la lista completa puede llegar a decenas de miles de caracteres.,
        //   Después de ser truncado por Agent Loop, el modelo no puede ver las clasificaciones posteriores.,No te atrevas a llamar a send_sticker.
        if (category == null) {
            val cats = repo.listCategories()
            if (cats.isEmpty()) return context.getString(R.string.skill_sticker_list_empty)
            val sb = StringBuilder()
            sb.appendLine("total ${cats.size} categorias:")
            cats.forEach { c ->
                val cnt = repo.listStickers(c).size
                sb.appendLine("[$c]( $cnt un)")
            }
            // Asistente de Autoevaluación Artículo 16: El nombre de la categoría debe coincidir estrictamente con la lista. Si adivina mal, no será válido y no habrá
            // Recomendaciones sobre "¿qué es apropiado para este contexto?" Aquí hay una comparación según el contexto, para que el modelo pueda comenzar desde
            // "¿Cuál es la emoción de la conversación actual?" Se asigna directamente a la clasificación, en lugar de adivinar primero y luego volver a verificar.
            appendCategoryGuide(cats, sb)
            sb.append("Llame a list_stickers y pase el parámetro de categoría para ver los emoticones específicos de una determinada categoría.,Luego llame a send_sticker para enviar.")
            return sb.toString()
        }
        val items = repo.listStickers(category)
        if (items.isEmpty()) {
            // Si el nombre de la categoría se adivina incorrectamente, proporcione el candidato en lugar de "vacío"—— De lo contrario, el modelo sólo podrá volver a adivinar.
            val cats = repo.listCategories()
            return if (cats.isEmpty()) {
                context.getString(R.string.skill_sticker_list_empty)
            } else {
                buildString {
                    appendLine("Clasificación \"$category\" No hay emoticones a continuación (el nombre de la categoría debe ser exactamente el mismo que el de la lista).")
                    appendLine("Categorías existentes: ${cats.joinToString(",")}")
                    append("Seleccione un nombre de categoría preciso y vuelva a llamar a list_stickers.")
                }
            }
        }
        val sb = StringBuilder()
        // v1.0.54: La lista de categorías se limita a mostrar los primeros 20 elementos — Puede haber cientos de pegatinas en la categoría.,La salida completa se truncará,
        //   Si el modelo no puede ver la lista completa, verifíquela repetidamente. list_stickers,Bucle infinito sin ajustar send_sticker.
        val shown = items.take(20)
        sb.appendLine("total ${items.size} emoticones(Clasificación: $category),Lo siguiente es lo anterior ${shown.size} un:")
        shown.forEach { item ->
            sb.appendLine("[${item.category}] ${item.fileName} (id=${item.id})")
        }
        sb.append("Seleccione uno adecuado de la lista anterior id,Llame a send_sticker inmediatamente para enviar,No repita las listas de consultas.")
        return sb.toString()
    }

    /**
     * Un cuadro comparativo de clasificaciones según contexto.
     *
     * Los nombres de las categorías están definidos por el usuario ("Feliz", "Escultura de arena", "Gato Gato"…), no hay forma de codificar el mapeo,
     * Por lo tanto, las categorías se clasifican en varias emociones de conversación comunes según las palabras clave. No impongas categorías irreconocibles;
     * Sólo indica "El resto de las categorías se juzgan por sus nombres".—— Adivinar el mapeo incorrecto es peor que no darlo en absoluto.
     */
    private fun appendCategoryGuide(categories: List<String>, sb: StringBuilder) {
        sb.append(buildCategoryGuide(categories))
    }

    suspend fun execSendSticker(args: Map<String, String>): String {
        val repo = stickerLibraryRepository
            ?: return context.getString(R.string.skill_sticker_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_id)
        val file = repo.getStickerFile(id)
            ?: return context.getString(R.string.skill_sticker_not_found, id)
        return context.getString(R.string.skill_sticker_sent, file.absolutePath)
    }

    suspend fun execGenerateImage(args: Map<String, String>): String {
        val service = imageService
            ?: return context.getString(R.string.skill_image_not_configured)
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_prompt)
        val size = args["size"]?.takeIf { it.isNotBlank() } ?: "1024x1024"
        // v1.136: Skill Se puede especificar explícitamente model,Si no se especifica, ImageService se encargará de ello según ProviderSpecificConfig/Catálogo
        val (drawProviderConfig, configModelId) = imageDrawConfigProvider()
        val model = args["model"]?.takeIf { it.isNotBlank() } ?: configModelId ?: ""
        val referenceImage = args["reference_image"]?.takeIf { it.isNotBlank() }
        return resultOf {
            val urls = service.generate(
                prompt = prompt,
                params = io.zer0.ai.image.ImageGenParams(
                    model = model,
                    size = size,
                    responseFormat = "url",
                    n = 1,
                    referenceImageUri = referenceImage,
                ),
                providerConfig = drawProviderConfig,
            )
            if (urls.isEmpty()) return@resultOf context.getString(R.string.skill_image_no_result, prompt)
            context.getString(R.string.skill_image_generated, prompt, urls.joinToString("\n"))
        }.onError { msg, _ -> Logger.w("SkillExecutor", "generate_image falló: $msg") }
            .getOrNull() ?: context.getString(R.string.skill_image_failed, prompt)
    }

    suspend fun execGenerateQr(args: Map<String, String>): String {
        val content = args["content"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_content_qr)
        val size = args["size"]?.toIntOrNull()?.coerceIn(100, 2000) ?: 600
        return resultOf {
            val bitmap = io.zer0.muse.ui.qrcode.QrCodeGenerator.generateQrBitmap(content, size)
                ?: return@resultOf context.getString(R.string.skill_qr_gen_failed)
            val filename = "qr_${System.currentTimeMillis()}.png"
            val file = java.io.File(context.cacheDir, filename)
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            context.getString(R.string.skill_qr_generated, file.absolutePath)
        }.onError { msg, _ -> Logger.w("SkillExecutor", "generate_qr falló: $msg") }
            .getOrNull() ?: context.getString(R.string.skill_qr_gen_failed)
    }

    suspend fun execExecuteJavascript(args: Map<String, String>): String {
        val code = args["code"]?.takeIf { it.isNotBlank() }
            ?: return """{"result":"","logs":[],"error":"Falta el código de parámetro o está vacío"}"""
        val timeoutMs = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1L, 60_000L) ?: 10000L
        // inicialización JsSandbox(idempotente)— inyectar Application Context
        JsSandbox.init(context)

        val input = kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(code))
            put("timeout_ms", kotlinx.serialization.json.JsonPrimitive(timeoutMs.toString()))
        }
        // confiado a CodeExecutionTool.execute(suspend)— Reutilizar el análisis de parámetros unificado y la lógica de serialización de resultados
        val output = CodeExecutionTool.execute(input)
        return output.toString()
    }

    suspend fun execPluginTool(skill: SkillEntity, argumentsJson: String): String {
        if (!pluginsGloballyEnabled()) {
            return "El complemento ha sido desactivado globalmente (configuración → Herramientas y extensiones → Interruptor maestro enchufable)"
        }
        val parts = skill.implementationKotlin.split(":")
        if (parts.size < 3) return "Error de formato de enrutamiento de herramienta complementaria: ${skill.implementationKotlin}"
        val pluginId = parts[1]
        val functionName = parts[2]
        val manager = pluginManager ?: return "Complemento no instalado: $pluginId"
        val plugin = manager.findPlugin(pluginId) ?: return "Complemento no instalado: $pluginId"
        if (!plugin.enabled) return "Complemento deshabilitado: ${plugin.name}"
        // P0: capabilities/tools/entryCode Siempre proviene del manifiesto después de la nueva verificación del disco.
        // Las cachés de registro como InstalledPlugin.capabilities ya no son de confianza; y antes de ingresar al sandbox JS
        // Las denegaciones han sido eliminadas por el nuevo manifiesto./Nombre de función renombrado.
        val verified = manager.loadVerifiedFunction(pluginId, functionName)
            ?: return "La verificación de entrada del complemento falló o la herramienta se eliminó: $pluginId/$functionName"

        val configJson = manager.getPluginConfigJson(verified.manifest)
        val argsJson = "[" + argumentsJson.ifBlank { "{}" } + "]"

        // v2.7: Los motores de ejecución se envían mediante manifest.runtime.
        //  - python: Tiempo de ejecución de Python integrado (sin zona de pruebas WebView, aislamiento de procesos secundarios)
        //  - Descanso (js predeterminado):WebView V8 caja de arena + puente __bridge__
        if (verified.manifest.runtime.equals("python", ignoreCase = true)) {
            return execPythonPluginTool(
                pluginId = pluginId,
                entryCode = verified.entryCode,
                functionName = functionName,
                argsJson = argsJson,
                configJson = configJson,
            )
        }

        JsSandbox.init(context)
        // C-30: Pase el ID del complemento como scopeKey,Haga que los disyuntores estén aislados del almacenamiento local mediante un complemento,
        //   El bucle infinito de un complemento no se interrumpirá cuando expire el tiempo de espera/Influya en otros complementos y herramientas JS integradas.
        return when (
            val result = WebViewSkillEngine().callFunction(
                verified.entryCode,
                functionName,
                argsJson,
                scopeKey = pluginId,
                pluginConfigJson = configJson,
            )
        ) {
            is SkillEngineResult.Success -> {
                val value = result.valueJson
                // F-17 + v2.2.1 Gran arenero;v2.x(Sandbox Unleashed): acción puente**Lanzamiento completo por defecto**.
                // En el pasado, las capacidades se publicaban una por una estrictamente de acuerdo con la declaración del manifiesto, lo cual era demasiado conservador: si el autor del complemento olvidaba declararlo, no se podía ajustar.
                // Ahora se cambia a: No hay umbral para acciones, pero**Mantenga dos tipos de resultados finales**(Estos dos elementos son corrección más que conservadurismo):
                //  1. fs_* Todavía bloqueado en el propio directorio sandbox del complemento(workspace/plugins/<pluginId>), evitar el escape del camino;
                //  2. http_* Todavía uso protección SSRF (rechazo de red interna)/dirección local).
                val allowedBridgeActions = SkillBridge.ALL_ACTIONS
                val bridgeHost = SkillBridge.Host(
                    context = context,
                    fsRoot = java.io.File(context.filesDir, "workspace/plugins/$pluginId"),
                )
                when (val bridge = SkillBridge.tryHandle(value, allowedBridgeActions, bridgeHost)) {
                    is SkillBridge.HandleResult.NotBridge -> {
                        runCatching {
                            AppJson.decodeFromString<String>(value)
                        }.getOrElse {
                            value.ifBlank { "null" }
                        }
                    }
                    // El resultado de la ejecución de la solicitud de puente se devuelve directamente a LLM
                    is SkillBridge.HandleResult.Output -> bridge.json
                    // Resolución de puente/La ejecución falló (incluida la acción no admitida)→ Devuelve un objeto de error para evitar que el script piense que fue exitoso
                    is SkillBridge.HandleResult.Failure ->
                        kotlinx.serialization.json.buildJsonObject {
                            put("error", kotlinx.serialization.json.JsonPrimitive(bridge.message))
                        }.toString()
                }
            }
            is SkillEngineResult.Error -> {
                if (JsSandbox.isCircuitBrokenFor(pluginId)) {
                    resultOf { manager.setEnabled(pluginId, false) }
                        .onError { msg, _ -> Logger.w("SkillMediaToolsImpl", "Falló la desactivación automática de complementos: $msg") }
                    "El complemento se ha desactivado automáticamente: JS El tiempo de espera del entorno de pruebas se ha agotado continuamente. Vuelva a intentarlo más tarde."
                } else {
                    "Error al ejecutar la herramienta de complemento: ${result.message}"
                }
            }
        }
    }

    /**
     * v2.7: Ejecute la herramienta de complemento de Python.
     *
     * Diferencias con la rama JS:
     *  - Sin zona de pruebas WebView, sin puente __bridge__ (Python El proceso hijo en sí tiene capacidades completas,
     *    Lectura y escritura de archivos/red/La biblioteca estándar se utiliza directamente; los riesgos elevados todavía están cubiertos por la cadena de aprobación general de la herramienta plug-in);
     *  - Asegúrese de que los datos del tiempo de ejecución de Python antes de la primera llamada (stdlib/CA bundle) está listo.
     *  - El valor de retorno se devuelve directamente como resultado de la herramienta (una cadena); La serialización JSON se utiliza cuando se devuelven elementos que no son cadenas.
     */
    private suspend fun execPythonPluginTool(
        pluginId: String,
        entryCode: String,
        functionName: String,
        argsJson: String,
        configJson: String?,
    ): String {
        val dataReady = io.zer0.muse.runtime.PythonRuntime.ensureData(context)
        if (dataReady.isFailure) {
            return "Python No listo en tiempo de ejecución: ${dataReady.exceptionOrNull()?.message}"
        }
        return when (
            val result = PythonSkillEngine(context).callPluginFunction(
                entryCode = entryCode,
                functionName = functionName,
                argsJson = argsJson,
                scopeKey = pluginId,
                pluginConfigJson = configJson,
            )
        ) {
            is SkillEngineResult.Success -> runCatching {
                AppJson.decodeFromString<String>(result.valueJson)
            }.getOrElse {
                result.valueJson.ifBlank { "null" }
            }
            is SkillEngineResult.Error -> "Error al ejecutar la herramienta de complemento: ${result.message}"
        }
    }
    companion object {
        /**
         * Generar segmentos de comparación contextual. Función pura, conveniente para pruebas individuales.
         *
         * @return Una cadena vacía indica que ninguna categoría coincide con la palabra clave (este párrafo no se publica en este momento)
         */
        internal fun buildCategoryGuide(categories: List<String>): String {
            val guide = STICKER_MOOD_KEYWORDS.mapNotNull { (mood, keys) ->
                val matched = categories.filter { cat -> keys.any { cat.contains(it, ignoreCase = true) } }
                if (matched.isEmpty()) null else mood to matched
            }
            if (guide.isEmpty()) return ""
            val covered = guide.flatMap { it.second }.toSet()
            val rest = categories.filterNot { it in covered }
            return buildString {
                appendLine("Elija categorías según el estado de ánimo de la conversación actual:")
                guide.forEach { (mood, cats) -> appendLine("- $mood → ${cats.joinToString(",")}") }
                if (rest.isNotEmpty()) appendLine("- El resto de categorías se pueden juzgar por su nombre.: ${rest.joinToString(",")}")
                appendLine()
            }
        }
    }
}

/**
 * Tabla de palabras clave contextuales para la clasificación de emoji.
 *
 * El nombre de la categoría lo personaliza el usuario. Aquí sólo se realiza una coincidencia débil de "aciertos de palabras clave": los aciertos se clasificarán según las emociones correspondientes.
 * Los aciertos faltantes se dejan al modelo para que los juzgue por su nombre. Es mejor dar menos consejos que dar un mapeo incorrecto.
 */
private val STICKER_MOOD_KEYWORDS: List<Pair<String, List<String>>> = listOf(
    "feliz/celebrar" to listOf("feliz", "celebrar", "feliz", "feliz", "happy", "celebration", "party"),
    "comodidad/curar" to listOf("comodidad", "curar", "abrazo", "cálido", "comfort", "hug", "healing"),
    "triste/abajo" to listOf("triste", "llorar", "abajo", "triste", "sad", "cry"),
    "enojado/quejarse" to listOf("enojado", "enojado", "quejarse", "poner los ojos en blanco", "angry", "annoyed"),
    "divertido/escultura de arena" to listOf("divertido", "escultura de arena", "naturaleza demoniaca", "funny", "meme", "silly"),
    "lindo/actuar lindo" to listOf("lindo", "actuar lindo", "lindo", "cute", "kawaii"),
    "animar/vamos" to listOf("animar", "vamos", "anímate", "cheer", "encourage"),
    "indefenso/Mostrado" to listOf("indefenso", "Mostrado", "acostarse", "sigh", "shrug"),
    "buenas noches/dormir" to listOf("buenas noches", "dormir", "somnoliento", "goodnight", "sleep"),
)
