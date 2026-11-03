package io.zer0.muse.transformer

import android.content.Context
import io.zer0.common.Logger

/**
 * Phase 12: Cargador rápido de plantillas de Word.
 *
 * Extraiga las palabras de aviso grandes codificadas en SystemPromptAssembler al directorio activos/prompt_templates/,
 * Renderizado a través de soportes de PebbleTemplateEngine {{variable}} interpolación.
 *
 * estrategia de carga:
 * 1. Primero cargue el archivo .prompt desde activos/prompt_templates/
 * 2. Devuelve el valor predeterminado alternativo cuando falta el archivo o no se carga.(Manténgase compatible con versiones anteriores)
 *
 * v1.0.51: Admite respaldo local. orden de carga:
 * 1. {name}_{locale}.prompt (Como por ejemplo decision_tree_es.prompt)
 * 2. {name}.prompt (Sin sufijo local,como recurso universal)
 * 3. fallback parámetros
 *
 * locale valor: zh / en / ja / ko / ru / system(Sigue el sistema,Analizado por la persona que llama y pasado en el formato real locale)
 */
class PromptTemplateLoader(private val context: Context) {

    private val engine = PebbleTemplateEngine()

    // M-TPL6: Acceso simultáneo a múltiples corrutinas(Como varias veces dentro de buildStaticSnapshot render),Utilice ConcurrentHashMap para garantizar la seguridad de los subprocesos
    // v1.0.51: cache key Cambiar a "{name}_{locale}" Para admitir el almacenamiento en caché de dimensiones locales
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        private const val TAG = "PromptTemplateLoader"
        private const val TEMPLATE_DIR = "prompt_templates"

        // Lista de archivos de plantilla conocidos(Sin sufijo .prompt)
        val TEMPLATE_NAMES = listOf(
            "decision_tree",
            "mood_format",
            "artifact_format",
            "self_reflection",
            "tool_discipline",
            "operation_safety",
            // v2.5.3: Disciplina de Acción + Contrato de Entrega
            "action_discipline",
            "delivery_contract",
            // v2.6.6: Interfaz generativa (guía al modelo para generar tarjetas interactivas en la conversación)
            "generative_ui",
            // v1.0.51: Se agregó plantilla de personalidad predeterminada + reglas de memoria + declaración de plataforma
            "default_persona",
            "memory_rules",
            "platform_decl",
        )
    }

    /**
     * Cargar y renderizar la plantilla especificada(Ninguno locale,recurrir a la plantilla genérica).
     *
     * @param name Nombre de la plantilla(corresponder assets/prompt_templates/{name}.prompt)
     * @param context contexto de variable de plantilla(Opcional)
     * @param fallback Valor predeterminado cuando falla la carga del archivo
     */
    fun render(name: String, context: Map<String, Any?> = emptyMap(), fallback: String = ""): String =
        render(name, locale = null, context, fallback)

    /**
     * v1.0.51: Cargar y renderizar la plantilla especificada(respaldo con la configuración regional).
     *
     * orden de carga:
     * 1. assets/prompt_templates/{name}_{locale}.prompt
     * 2. assets/prompt_templates/{name}.prompt
     * 3. fallback parámetros
     *
     * @param name Nombre de la plantilla
     * @param locale código de idioma(zh/en/ja/ko/ru),null O una cadena vacía significa que no se hace distinción locale
     * @param context contexto de variable de plantilla(Opcional)
     * @param fallback Valor predeterminado cuando falla la carga del archivo
     */
    fun render(name: String, locale: String?, context: Map<String, Any?> = emptyMap(), fallback: String = ""): String {
        val templateText = loadTemplate(name, locale) ?: return fallback
        return try {
            engine.render(templateText, context)
        } catch (e: Exception) {
            Logger.w(TAG, "Error al renderizar la plantilla: $name (locale=$locale)", e)
            templateText // Devolver el texto original cuando falla la renderización
        }
    }

    /**
     * Cargar texto de plantilla(Con caché,Caché por dimensión local).
     *
     * cadena de retroceso:
     *  - chino locale: {name}_{locale}.prompt → {name}.prompt(original chino) → null
     *  - No chino locale: {name}_{locale}.prompt → {name}_en.prompt(Retroceso internacional)
     *    → {name}.prompt(el ultimo soldado) → null
     * Los usuarios internacionales dan prioridad al inglés frente al chino,evitar el día/Corea/Oeste/portugués/Los usuarios rusos recibieron rápidamente palabras en chino.
     */
    private fun loadTemplate(name: String, locale: String?): String? {
        val effectiveLocale = locale?.takeIf { it.isNotBlank() && it != "system" }
        val cacheKey = if (effectiveLocale != null) "${name}_$effectiveLocale" else name

        cache[cacheKey]?.let { return it }

        // 1. Pruebe primero las plantillas específicas de la configuración regional
        if (effectiveLocale != null) {
            readAsset("$TEMPLATE_DIR/${name}_$effectiveLocale.prompt")?.let { text ->
                cache[cacheKey] = text
                return text
            }
        }

        // 2. Retroceso internacional: Las configuraciones regionales no chinas prefieren la versión en inglés.(Universales {name}.prompt En realidad chino)
        val isChinese = effectiveLocale == null || effectiveLocale.let { it.startsWith("es", ignoreCase = true) || it.startsWith("zh", ignoreCase = true) }
        if (!isChinese) {
            readAsset("$TEMPLATE_DIR/${name}_en.prompt")?.let { text ->
                cache[cacheKey] = text
                return text
            }
        }

        // 3. plantilla universal(original chino,Como último recurso)
        readAsset("$TEMPLATE_DIR/$name.prompt")?.let { text ->
            cache[cacheKey] = text
            return text
        }
        Logger.w(TAG, "Error al cargar la plantilla: $name (locale=$locale)")
        return null
    }

    /** Lea el archivo de plantilla en activos;no existe/Devuelve nulo si falla la lectura. */
    private fun readAsset(path: String): String? = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    /**
     * Borrar caché de plantilla(Se llama cuando el archivo de plantilla cambia o cambia la configuración regional.).
     */
    fun clearCache() {
        cache.clear()
    }
}
